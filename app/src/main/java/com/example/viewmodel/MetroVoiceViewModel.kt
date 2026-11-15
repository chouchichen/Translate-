package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ForeignLanguageOption
import com.example.data.model.GeneratedMinutesResult
import com.example.data.model.SUPPORTED_FOREIGN_LANGUAGES
import com.example.data.model.TestSession
import com.example.data.model.TranslationResult
import com.example.data.model.Utterance
import com.example.data.repository.MetroVoiceRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "MetroVoiceVM"

const val PENDING_TRANSLATION_PLACEHOLDER = "⏳ [口譯處理中...]"
const val FAILED_TRANSLATION_PREFIX = "⚠️ [翻譯失敗，請點擊重試]"
const val TRANSLATION_STALE_TIMEOUT_MS = 65_000L

fun Utterance.isTranslationPending(): Boolean =
  translatedText.startsWith("⏳")

fun Utterance.isTranslationFailed(): Boolean =
  translatedText.startsWith("⚠️")

/**
 * Multi-device safe retry check:
 * - Failed utterances (`⚠️`) are always retryable (when not currently translating on this device).
 * - Pending utterances (`⏳`) are retryable ONLY if:
 *   1. They were started on THIS device prior to a process termination (`id in localInterruptedIds`), OR
 *   2. Their timestamp has exceeded [TRANSLATION_STALE_TIMEOUT_MS] (65s > 60s model timeout).
 * This prevents a second device (Phone B) from manually clicking retry within 65s while
 * Phone A is still actively translating the sentence.
 */
fun Utterance.canRetryTranslation(
  activeTranslatingIds: Set<String> = emptySet(),
  localInterruptedIds: Set<String> = emptySet(),
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
  if (id in activeTranslatingIds) return false
  if (isTranslationFailed()) return true
  if (isTranslationPending()) {
    return id in localInterruptedIds || isStalePendingTranslation(nowEpochMs)
  }
  return false
}

fun Utterance.isStalePendingTranslation(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
  if (!isTranslationPending()) return false
  val timestampMs = (updatedAt ?: createdAt)?.toDate()?.time ?: return false
  return (nowEpochMs - timestampMs) >= TRANSLATION_STALE_TIMEOUT_MS
}

sealed interface DataUiState<out T> {
  data object Loading : DataUiState<Nothing>
  data class Success<T>(val data: T) : DataUiState<T>
  data class Error(val message: String) : DataUiState<Nothing>
}

sealed interface AiTaskState {
  data object Idle : AiTaskState
  data class Translating(val speakerRole: String, val previewText: String) : AiTaskState
  data object GeneratingMinutes : AiTaskState
  data class Error(val message: String) : AiTaskState
}

class MetroVoiceViewModel(
  private val repository: MetroVoiceRepository,
  private val currentUserId: String,
  private val autoSeedOnEmpty: Boolean = true,
  private val customTranslationGenerator:
    (suspend (session: TestSession, speakerRole: String, spokenText: String) -> String)? =
    null,
) : ViewModel() {

  /**
   * Preserves spoken-order TTS playback even when a shorter second sentence finishes AI
   * translation before a longer first sentence.
   */
  private class OrderedSpeechSlot(
    val slotId: Long,
    val onSpeakTranslationReady: (String, String) -> Unit,
    var isReady: Boolean = false,
    var translatedText: String? = null,
    var targetLang: String? = null,
    var isFailedOrSkipped: Boolean = false,
  )

  private var nextSpeechSlotId = 1L
  private val speechOrderQueue = ArrayDeque<OrderedSpeechSlot>()

  private val translationSchema =
    Schema.obj(
      properties =
        mapOf(
          "translatedText" to
            Schema.string(
              description =
                "Accurate, natural translation tailored for MRT/Metro railway engineering and testing."
            ),
          "technicalTerms" to
            Schema.array(
              items = Schema.string(),
              description =
                "Up to 4 bilingual railway/MRT technical terms found in the sentence, formatted as 'Term: 中文解釋'",
            ),
        )
    )

  private val minutesSchema =
    Schema.obj(
      properties =
        mapOf(
          "summary" to
            Schema.string(
              description =
                "Bilingual (Traditional Chinese & Foreign Language) executive summary of the MRT test session"
            ),
          "keyPoints" to
            Schema.array(
              items = Schema.string(),
              description =
                "List of key technical observations, test measurements, and engineering decisions",
            ),
          "actionItems" to
            Schema.array(
              items = Schema.string(),
              description =
                "List of concrete follow-up action items including responsible party and target system",
            ),
          "testVerdict" to
            Schema.string(
              description =
                "Recommended test verdict: '通過 (PASS)', '有條件通過 (CONDITIONAL)', '待複測 (PENDING RE-TEST)', '異常需追蹤 (ISSUE OPEN)', or '進行中 (In Progress)'"
            ),
        )
    )

  private val translationModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig =
        generationConfig {
          responseMimeType = "application/json"
          responseSchema = translationSchema
        },
    )
  }

  private val minutesModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig =
        generationConfig {
          responseMimeType = "application/json"
          responseSchema = minutesSchema
        },
    )
  }

  private val _selectedSessionId = MutableStateFlow<String?>(null)
  val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

  private val _aiTaskState = MutableStateFlow<AiTaskState>(AiTaskState.Idle)
  val aiTaskState: StateFlow<AiTaskState> = _aiTaskState.asStateFlow()

  private val _autoSpeakTranslation = MutableStateFlow(true)
  val autoSpeakTranslation: StateFlow<Boolean> = _autoSpeakTranslation.asStateFlow()

  private val _spotlightUtterance = MutableStateFlow<Utterance?>(null)
  val spotlightUtterance: StateFlow<Utterance?> = _spotlightUtterance.asStateFlow()

  private val _activeTranslatingUtteranceIds = MutableStateFlow<Set<String>>(emptySet())
  val activeTranslatingUtteranceIds: StateFlow<Set<String>> =
    _activeTranslatingUtteranceIds.asStateFlow()

  private val _localInterruptedUtteranceIds =
    MutableStateFlow(repository.getInterruptedTranslationIds(currentUserId))
  val localInterruptedUtteranceIds: StateFlow<Set<String>> =
    _localInterruptedUtteranceIds.asStateFlow()

  @Volatile private var inFlightCreationCount: Int = 0
  private val recoveringOrphanIds = mutableSetOf<String>()

  private var hasSeededInitialSession = false

  init {
    viewModelScope.launch {
      try {
        repository.resumePendingSessionDeletions(currentUserId)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {}
    }
  }

  val sessionsState: StateFlow<DataUiState<List<TestSession>>> =
    repository
      .observeSessions(currentUserId)
      .map<List<TestSession>, DataUiState<List<TestSession>>> { list ->
        if (list.isEmpty() && autoSeedOnEmpty && !hasSeededInitialSession) {
          hasSeededInitialSession = true
          seedDefaultMrtSession()
        } else if (list.isNotEmpty() && _selectedSessionId.value == null) {
          _selectedSessionId.value = list.first().id
        } else if (
          list.isNotEmpty() && list.none { it.id == _selectedSessionId.value }
        ) {
          _selectedSessionId.value = list.first().id
        }
        DataUiState.Success(list)
      }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing sessions", rootError)
        emit(DataUiState.Error(rootError.message ?: "無法載入測試會議場次"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = DataUiState.Loading,
      )

  @OptIn(ExperimentalCoroutinesApi::class)
  val utterancesState: StateFlow<DataUiState<List<Utterance>>> =
    _selectedSessionId
      .flatMapLatest { sessionId ->
        if (sessionId.isNullOrBlank()) {
          flowOf(DataUiState.Success(emptyList()))
        } else {
          repository
            .observeUtterances(currentUserId, sessionId)
            .map<List<Utterance>, DataUiState<List<Utterance>>> { list ->
              // Only auto-mark a "⏳ [口譯處理中...]" utterance as failed in Firestore if:
              // 1. It was recorded in THIS device's own interrupted translation store before a crash, OR
              // 2. Its timestamp has exceeded TRANSLATION_STALE_TIMEOUT_MS (65s > 60s model timeout).
              // And use a conditional Firestore transaction (`markUtteranceFailedIfStillPending`)
              // so it NEVER overwrites a completed translation that another device just saved!
              if (inFlightCreationCount == 0) {
                val activeIds = _activeTranslatingUtteranceIds.value
                val interruptedOnThisDevice = _localInterruptedUtteranceIds.value
                val nowMs = System.currentTimeMillis()
                list.forEach { utt ->
                  val shouldRecover =
                    utt.id.isNotBlank() &&
                      utt.isTranslationPending() &&
                      utt.id !in activeIds &&
                      (utt.id in interruptedOnThisDevice || utt.isStalePendingTranslation(nowMs))
                  if (shouldRecover && recoveringOrphanIds.add(utt.id)) {
                    viewModelScope.launch {
                      markUtteranceTranslationFailed(
                        utt.sessionId,
                        utt.id,
                        "上次口譯未完成，請點擊重試",
                      )
                      repository.clearInFlightTranslation(currentUserId, utt.id)
                      _localInterruptedUtteranceIds.value =
                        _localInterruptedUtteranceIds.value - utt.id
                    }
                  }
                }
              }
              DataUiState.Success(list)
            }
            .catch { error ->
              val firestoreError =
                generateSequence(error) { it.cause }
                  .filterIsInstance<FirebaseFirestoreException>()
                  .firstOrNull()
              if (firestoreError == null && error is CancellationException) throw error
              val rootError = firestoreError ?: error
              Log.w(TAG, "Error observing utterances", rootError)
              emit(DataUiState.Error(rootError.message ?: "無法載入對話口譯紀錄"))
            }
        }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = DataUiState.Loading,
      )

  fun selectSession(sessionId: String) {
    _selectedSessionId.value = sessionId
  }

  fun toggleAutoSpeak() {
    _autoSpeakTranslation.value = !_autoSpeakTranslation.value
  }

  fun showSpotlightCard(utterance: Utterance?) {
    _spotlightUtterance.value = utterance
  }

  fun clearAiError() {
    if (_aiTaskState.value is AiTaskState.Error) {
      _aiTaskState.value = AiTaskState.Idle
    }
  }

  private fun markSpeechSlotReadyAndDrain(
    slot: OrderedSpeechSlot,
    translatedText: String,
    targetLang: String,
  ) {
    synchronized(speechOrderQueue) {
      slot.translatedText = translatedText
      slot.targetLang = targetLang
      slot.isReady = true
    }
    drainSpeechOrderQueue()
  }

  private fun markSpeechSlotFailedAndDrain(slot: OrderedSpeechSlot) {
    synchronized(speechOrderQueue) {
      slot.isFailedOrSkipped = true
    }
    drainSpeechOrderQueue()
  }

  private fun drainSpeechOrderQueue() {
    val readyCallbacks = mutableListOf<Triple<(String, String) -> Unit, String, String>>()
    synchronized(speechOrderQueue) {
      while (speechOrderQueue.isNotEmpty()) {
        val head = speechOrderQueue.first()
        if (head.isFailedOrSkipped) {
          speechOrderQueue.removeFirst()
          continue
        }
        if (!head.isReady) {
          // Earlier spoken sentence is still translating; hold later sentences so TTS
          // always speaks in the exact order the sentences were spoken!
          break
        }
        speechOrderQueue.removeFirst()
        val text = head.translatedText
        val lang = head.targetLang
        if (!text.isNullOrBlank() && !lang.isNullOrBlank() && _autoSpeakTranslation.value) {
          readyCallbacks.add(Triple(head.onSpeakTranslationReady, text, lang))
        }
      }
    }
    readyCallbacks.forEach { (callback, text, lang) ->
      callback.invoke(text, lang)
    }
  }

  private fun seedDefaultMrtSession() {
    viewModelScope.launch {
      try {
        val sessionId =
          repository.createSession(
            title = "板南線 CBTC 號誌與月台門連動測試",
            metroLine = "板南線 (BL)",
            testCategory = "CBTC 號誌與聯鎖測試",
            foreignLangCode = "en-US",
            foreignLangLabel = "English (英文)",
            initialSummary =
              "【測試場次說明】本場次為板南線 CBTC 車地通訊與第二月台門 (PSD) 同步開啟測試。您可直接點擊下方「外籍技師講話」或「我方講話」進行雙向即時翻譯，並隨時點擊「AI 即時產出會議紀錄」。",
          )
        _selectedSessionId.value = sessionId

        repository.addUtterance(
          sessionId = sessionId,
          speakerRole = "FOREIGN_ENGINEER",
          speakerLabel = "外籍技師 (English (英文))",
          sourceLang = "en-US",
          targetLang = "zh-TW",
          originalText =
            "Good morning team. Let's verify the CBTC wayside-to-train handover and PSD opening delay at Platform 2.",
          translatedText =
            "各位早安，我們開始驗證第二月台的 CBTC 車地訊號交遞與月台門開啟延遲時間。",
          technicalTerms =
            listOf(
              "CBTC: 通訊式列車控制系統",
              "Wayside-to-train handover: 軌旁對列車訊號交遞",
              "PSD: 月台門系統 (Platform Screen Door)",
            ),
          isPinnedToMinutes = true,
        )

        repository.addUtterance(
          sessionId = sessionId,
          speakerRole = "LOCAL_ENGINEER",
          speakerLabel = "捷運測試工程師 (我方)",
          sourceLang = "zh-TW",
          targetLang = "en-US",
          originalText =
            "收到，列車車載控制器 VOBC 已切換至 ATO 自動駕駛模式，精準停車誤差目前為正負 5 公分以內。",
          translatedText =
            "Copy that. The onboard VOBC controller has been switched to ATO automatic driving mode, and stopping accuracy is currently within plus or minus 5 centimeters.",
          technicalTerms =
            listOf(
              "VOBC: 車載控制器 (Vehicle On-Board Controller)",
              "ATO: 列車自動運轉系統 (Automatic Train Operation)",
            ),
          isPinnedToMinutes = true,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to seed initial MRT session", e)
      }
    }
  }

  fun createNewSession(
    title: String,
    metroLine: String,
    testCategory: String,
    languageOption: ForeignLanguageOption,
  ) {
    viewModelScope.launch {
      try {
        val newId =
          repository.createSession(
            title = title,
            metroLine = metroLine,
            testCategory = testCategory,
            foreignLangCode = languageOption.code,
            foreignLangLabel = languageOption.label,
          )
        _selectedSessionId.value = newId
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _aiTaskState.value =
          AiTaskState.Error(e.localizedMessage ?: "建立新測試會議失敗")
      }
    }
  }

  fun changeSessionLanguage(session: TestSession, languageOption: ForeignLanguageOption) {
    viewModelScope.launch {
      try {
        repository.updateSessionLanguage(
          userId = currentUserId,
          sessionId = session.id,
          foreignLangCode = languageOption.code,
          foreignLangLabel = languageOption.label,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _aiTaskState.value =
          AiTaskState.Error(e.localizedMessage ?: "切換語言設定失敗")
      }
    }
  }

  fun deleteSession(sessionId: String) {
    viewModelScope.launch {
      try {
        repository.deleteSession(currentUserId, sessionId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _aiTaskState.value =
          AiTaskState.Error(e.localizedMessage ?: "刪除測試會議失敗")
      }
    }
  }

  fun togglePinUtterance(utterance: Utterance) {
    viewModelScope.launch {
      try {
        repository.togglePinUtterance(
          userId = currentUserId,
          sessionId = utterance.sessionId,
          utteranceId = utterance.id,
          newPinnedState = !utterance.isPinnedToMinutes,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _aiTaskState.value =
          AiTaskState.Error(e.localizedMessage ?: "更新重點標記失敗")
      }
    }
  }

  /**
   * Parses and strictly validates the structured JSON returned by the translation model.
   * Rejects blank or missing `translatedText` with [IllegalStateException] instead of
   * silently falling back to the original source text.
   */
  internal fun parseAndValidateTranslationJson(jsonString: String): TranslationResult {
    val json = JSONObject(jsonString)
    val translatedText = json.optString("translatedText", "").trim()
    if (translatedText.isEmpty()) {
      throw IllegalStateException("AI 模型回傳空白譯文，請點擊重試翻譯。")
    }
    val termsJsonArray = json.optJSONArray("technicalTerms")
    val terms =
      if (termsJsonArray != null) {
        (0 until termsJsonArray.length()).mapNotNull { idx ->
          termsJsonArray.optString(idx)?.takeIf { it.isNotBlank() }
        }
      } else {
        emptyList()
      }
    return TranslationResult(translatedText = translatedText, technicalTerms = terms)
  }

  private suspend fun executeAiTranslation(
    session: TestSession,
    speakerRole: String,
    cleanInput: String,
  ): TranslationResult {
    val isForeignSpeaker = speakerRole == "FOREIGN_ENGINEER"
    val directionPrompt =
      if (isForeignSpeaker) {
        """
        You are a real-time professional MRT / Metro Railway Testing & Commissioning Interpreter.
        Context:
        - Metro Line: ${session.metroLine}
        - Test Category: ${session.testCategory}
        - Foreign Engineer Language: ${session.foreignLangLabel} (${session.foreignLangCode})
        Task:
        1. Translate the foreign engineer's statement below into natural, accurate Traditional Chinese (繁體中文, Taiwan MRT engineering terminology such as 號誌、聯鎖、轉轍器、月台門 PSD、列車自動保護 ATP、車載控制器 VOBC、轉向架).
        2. Extract up to 4 technical railway terms mentioned and provide brief bilingual explanations in the format "Original Term: 繁體中文專業術語".

        Statement to translate:
        "$cleanInput"
        """.trimIndent()
      } else {
        """
        You are a real-time professional MRT / Metro Railway Testing & Commissioning Interpreter.
        Context:
        - Metro Line: ${session.metroLine}
        - Test Category: ${session.testCategory}
        - Target Foreign Language: ${session.foreignLangLabel} (${session.foreignLangCode})
        Task:
        1. Reverse-translate the Taiwan MRT engineer's statement below from Traditional Chinese into clear, professional, idiomatic ${session.foreignLangLabel} (${session.foreignLangCode}) so the foreign railway engineer immediately understands the instruction or test observation.
        2. Extract up to 4 technical railway terms mentioned and provide brief bilingual glosses in the format "Target Term: 繁體中文專業術語".

        Statement to translate:
        "$cleanInput"
        """.trimIndent()
      }

    val jsonString =
      if (customTranslationGenerator != null) {
        withTimeout(60_000L) {
          customTranslationGenerator.invoke(session, speakerRole, cleanInput)
        }
      } else {
        val response = withTimeout(60_000L) { translationModel.generateContent(directionPrompt) }
        response.text ?: throw IllegalStateException("AI 模型回傳空白回應，請點擊重試翻譯。")
      }

    return parseAndValidateTranslationJson(jsonString)
  }

  /**
   * Bidirectional Real-Time Translation via Firebase AI Logic (Gemini 3.8 Flash):
   * 1. Assigns an [OrderedSpeechSlot] in the exact call order so that even if a shorter second
   *    sentence finishes AI translation before a longer first sentence, TTS speech output always
   *    plays in strict spoken order (Sentence 1 first, Sentence 2 second).
   * 2. Immediately persists the recognized [spokenText] (`originalText`) to Firestore and records
   *    the utterance ID in THIS device's `PendingTranslationStore` (`SharedPreferences`).
   * 3. Translates the utterance and updates the saved Firestore document.
   */
  fun translateAndRecordUtterance(
    session: TestSession,
    speakerRole: String,
    spokenText: String,
    onSpeakTranslationReady: (textToSpeak: String, languageTag: String) -> Unit,
  ) {
    val cleanInput = spokenText.trim()
    if (cleanInput.isEmpty()) return

    val speechSlot =
      synchronized(speechOrderQueue) {
        OrderedSpeechSlot(
          slotId = nextSpeechSlotId++,
          onSpeakTranslationReady = onSpeakTranslationReady,
        ).also { speechOrderQueue.addLast(it) }
      }

    val isForeignSpeaker = speakerRole == "FOREIGN_ENGINEER"
    val sourceLang = if (isForeignSpeaker) session.foreignLangCode else "zh-TW"
    val targetLang = if (isForeignSpeaker) "zh-TW" else session.foreignLangCode
    val speakerLabel =
      if (isForeignSpeaker) {
        "外籍技師 (${session.foreignLangLabel})"
      } else {
        "捷運測試工程師 (我方)"
      }

    viewModelScope.launch {
      _aiTaskState.value = AiTaskState.Translating(speakerRole, cleanInput)

      // Step 1: Persist recognized originalText to Firestore first.
      val savedUtteranceId: String =
        try {
          inFlightCreationCount++
          val newId =
            repository.addUtterance(
              sessionId = session.id,
              speakerRole = speakerRole,
              speakerLabel = speakerLabel,
              sourceLang = sourceLang,
              targetLang = targetLang,
              originalText = cleanInput,
              translatedText = PENDING_TRANSLATION_PLACEHOLDER,
              technicalTerms = emptyList(),
              isPinnedToMinutes = false,
            )
          repository.markInFlightTranslation(currentUserId, newId)
          _activeTranslatingUtteranceIds.value = _activeTranslatingUtteranceIds.value + newId
          newId
        } catch (e: CancellationException) {
          markSpeechSlotFailedAndDrain(speechSlot)
          throw e
        } catch (e: Exception) {
          markSpeechSlotFailedAndDrain(speechSlot)
          Log.e(TAG, "Initial Firestore write for utterance failed", e)
          _aiTaskState.value =
            AiTaskState.Error(
              "無法儲存逐字稿（原文尚未寫入雲端）：${e.localizedMessage ?: "請確認網路或會議狀態後重新發言"}"
            )
          return@launch
        } finally {
          inFlightCreationCount--
        }

      // Step 2: Run AI translation and update the saved utterance
      try {
        val result = executeAiTranslation(session, speakerRole, cleanInput)

        // Dispatch speech strictly in spoken order via speechOrderQueue!
        markSpeechSlotReadyAndDrain(speechSlot, result.translatedText, targetLang)

        repository.updateUtteranceTranslation(
          userId = currentUserId,
          sessionId = session.id,
          utteranceId = savedUtteranceId,
          translatedText = result.translatedText,
          technicalTerms = result.technicalTerms,
        )

        _aiTaskState.value = AiTaskState.Idle
      } catch (e: TimeoutCancellationException) {
        markSpeechSlotFailedAndDrain(speechSlot)
        Log.e(TAG, "Translation timed out", e)
        markUtteranceTranslationFailed(session.id, savedUtteranceId, "連線逾時")
        _aiTaskState.value =
          AiTaskState.Error("即時口譯逾時（原文已保存至逐字稿，可直接點擊該句「重試翻譯」）。")
      } catch (e: CancellationException) {
        markSpeechSlotFailedAndDrain(speechSlot)
        throw e
      } catch (e: Exception) {
        markSpeechSlotFailedAndDrain(speechSlot)
        Log.e(TAG, "Firebase AI translation failed", e)
        markUtteranceTranslationFailed(
          session.id,
          savedUtteranceId,
          e.localizedMessage ?: "翻譯失敗",
        )
        _aiTaskState.value =
          AiTaskState.Error(
            "即時口譯失敗：${e.localizedMessage ?: "請稍後再試"}（原文已保存至逐字稿，可點擊「重試翻譯」）"
          )
      } finally {
        repository.clearInFlightTranslation(currentUserId, savedUtteranceId)
        _localInterruptedUtteranceIds.value =
          _localInterruptedUtteranceIds.value - savedUtteranceId
        _activeTranslatingUtteranceIds.value =
          _activeTranslatingUtteranceIds.value - savedUtteranceId
      }
    }
  }

  /**
   * Retries AI translation for an already-persisted [Utterance] whose initial translation
   * failed, timed out, or was interrupted on this device (`canRetryTranslation`).
   * - Guards with [Utterance.canRetryTranslation] so a second device (Phone B) cannot manually
   *   trigger a concurrent retry within 65s while Phone A is still actively translating.
   * - Uses a conditional Firestore transaction (`claimUtteranceForRetryIfEligible`) before
   *   calling the model so it never overwrites a completed translation from another device.
   */
  fun retryTranslateUtterance(
    session: TestSession,
    utterance: Utterance,
    onSpeakTranslationReady: (textToSpeak: String, languageTag: String) -> Unit,
  ) {
    if (utterance.id.isBlank() || utterance.originalText.isBlank()) return
    if (
      !utterance.canRetryTranslation(
        activeTranslatingIds = _activeTranslatingUtteranceIds.value,
        localInterruptedIds = _localInterruptedUtteranceIds.value,
      )
    ) {
      return
    }

    val isForeignSpeaker = utterance.speakerRole == "FOREIGN_ENGINEER"
    val pinnedForeignLangCode =
      (if (isForeignSpeaker) utterance.sourceLang else utterance.targetLang)
        .takeIf { it.isNotBlank() }
        ?: session.foreignLangCode
    val pinnedForeignLangLabel =
      SUPPORTED_FOREIGN_LANGUAGES.firstOrNull { it.code == pinnedForeignLangCode }?.label
        ?: if (pinnedForeignLangCode == session.foreignLangCode) {
          session.foreignLangLabel
        } else {
          pinnedForeignLangCode
        }
    val pinnedTargetLang =
      if (isForeignSpeaker) {
        utterance.targetLang.takeIf { it.isNotBlank() } ?: "zh-TW"
      } else {
        pinnedForeignLangCode
      }
    val sessionForUtterance =
      session.copy(
        foreignLangCode = pinnedForeignLangCode,
        foreignLangLabel = pinnedForeignLangLabel,
      )

    viewModelScope.launch {
      _activeTranslatingUtteranceIds.value = _activeTranslatingUtteranceIds.value + utterance.id
      repository.markInFlightTranslation(currentUserId, utterance.id)
      _aiTaskState.value = AiTaskState.Translating(utterance.speakerRole, utterance.originalText)
      try {
        val claimed =
          repository.claimUtteranceForRetryIfEligible(
            userId = currentUserId,
            sessionId = utterance.sessionId,
            utteranceId = utterance.id,
            pendingPlaceholder = PENDING_TRANSLATION_PLACEHOLDER,
          )
        if (!claimed) {
          // Another device already completed translating this utterance; do not overwrite!
          _aiTaskState.value = AiTaskState.Idle
          return@launch
        }

        val result =
          executeAiTranslation(
            session = sessionForUtterance,
            speakerRole = utterance.speakerRole,
            cleanInput = utterance.originalText,
          )

        if (_autoSpeakTranslation.value) {
          onSpeakTranslationReady(result.translatedText, pinnedTargetLang)
        }

        repository.updateUtteranceTranslation(
          userId = currentUserId,
          sessionId = utterance.sessionId,
          utteranceId = utterance.id,
          translatedText = result.translatedText,
          technicalTerms = result.technicalTerms,
        )

        _aiTaskState.value = AiTaskState.Idle
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Retry translation timed out", e)
        markUtteranceTranslationFailed(utterance.sessionId, utterance.id, "連線逾時")
        _aiTaskState.value = AiTaskState.Error("重試口譯逾時，請檢查網路連線後再試一次。")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Retry translation failed", e)
        markUtteranceTranslationFailed(
          utterance.sessionId,
          utterance.id,
          e.localizedMessage ?: "翻譯失敗",
        )
        _aiTaskState.value =
          AiTaskState.Error("重試口譯失敗：${e.localizedMessage ?: "請稍後再試"}")
      } finally {
        repository.clearInFlightTranslation(currentUserId, utterance.id)
        _localInterruptedUtteranceIds.value =
          _localInterruptedUtteranceIds.value - utterance.id
        _activeTranslatingUtteranceIds.value =
          _activeTranslatingUtteranceIds.value - utterance.id
      }
    }
  }

  private suspend fun markUtteranceTranslationFailed(
    sessionId: String,
    utteranceId: String?,
    reason: String,
  ) {
    if (utteranceId.isNullOrBlank()) return
    try {
      repository.markUtteranceFailedIfStillPending(
        userId = currentUserId,
        sessionId = sessionId,
        utteranceId = utteranceId,
        failedText = "$FAILED_TRANSLATION_PREFIX（$reason）",
      )
    } catch (_: Exception) {}
  }

  /**
   * Generates real-time bilingual MRT Testing Meeting Minutes from the current session's utterances
   * and persists them directly to the active TestSession document in Firestore.
   */
  fun generateMeetingMinutes(
    session: TestSession,
    utterances: List<Utterance>,
    onMinutesGenerated: () -> Unit = {},
  ) {
    if (utterances.isEmpty()) {
      _aiTaskState.value = AiTaskState.Error("目前尚無對話紀錄，請先進行雙向翻譯對話後再產出會議紀錄。")
      return
    }

    viewModelScope.launch {
      _aiTaskState.value = AiTaskState.GeneratingMinutes
      try {
        val transcriptBuilder = StringBuilder()
        utterances.forEachIndexed { index, utt ->
          val pinTag = if (utt.isPinnedToMinutes) "[★重點標記] " else ""
          val translationLine =
            if (utt.isTranslationFailed() || utt.isTranslationPending()) {
              "（譯文待補，請依據原文彙整）"
            } else {
              utt.translatedText
            }
          transcriptBuilder.append(
            "${index + 1}. $pinTag[${utt.speakerLabel}]\n" +
              "   原文 (${utt.sourceLang}): ${utt.originalText}\n" +
              "   譯文 (${utt.targetLang}): $translationLine\n"
          )
        }

        val prompt =
          """
          You are a Senior MRT Railway Testing & Commissioning Secretary and Technical Translator.
          Analyze the following bilingual MRT field testing dialogue and generate structured, formal Meeting Minutes (會議紀錄).
          Prioritize items marked with [★重點標記].

          Session Metadata:
          - Title: ${session.title}
          - Metro Line / Location: ${session.metroLine}
          - Test Category: ${session.testCategory}
          - Foreign Engineer Language: ${session.foreignLangLabel} (${session.foreignLangCode})

          Bilingual Transcript:
          $transcriptBuilder

          Requirements for JSON fields:
          1. "summary": Provide a concise bilingual executive summary (Traditional Chinese first, followed by ${session.foreignLangLabel} translation) covering the test objective and overall outcome.
          2. "keyPoints": Provide 3 to 6 concrete technical observations, measurements, or parameters discussed (each item in Traditional Chinese with key ${session.foreignLangLabel} technical terms in parentheses).
          3. "actionItems": Provide 2 to 5 concrete follow-up action items specifying who needs to do what (in both Traditional Chinese and brief ${session.foreignLangLabel}).
          4. "testVerdict": Choose the single most appropriate status from: "通過 (PASS)", "有條件通過 (CONDITIONAL)", "待複測 (PENDING RE-TEST)", "異常需追蹤 (ISSUE OPEN)", or "進行中 (In Progress)".
          """.trimIndent()

        val response = withTimeout(60_000L) { minutesModel.generateContent(prompt) }
        val jsonString =
          response.text ?: throw IllegalStateException("AI 模型回傳空白會議紀錄，請重試。")

        val json = JSONObject(jsonString)
        val summary = json.optString("summary", "").trim()
        val keyPointsArr = json.optJSONArray("keyPoints")
        val keyPoints =
          if (keyPointsArr != null) {
            (0 until keyPointsArr.length()).mapNotNull { idx ->
              keyPointsArr.optString(idx)?.takeIf { it.isNotBlank() }
            }
          } else {
            emptyList()
          }

        val actionItemsArr = json.optJSONArray("actionItems")
        val actionItems =
          if (actionItemsArr != null) {
            (0 until actionItemsArr.length()).mapNotNull { idx ->
              actionItemsArr.optString(idx)?.takeIf { it.isNotBlank() }
            }
          } else {
            emptyList()
          }

        val verdict =
          json.optString("testVerdict", "進行中 (In Progress)").trim().ifEmpty {
            "進行中 (In Progress)"
          }

        val generated =
          GeneratedMinutesResult(
            summary = summary.ifEmpty { "已完成雙向測試對話彙整。" },
            keyPoints = keyPoints,
            actionItems = actionItems,
            testVerdict = verdict,
          )

        repository.updateSessionMinutes(
          userId = currentUserId,
          sessionId = session.id,
          summary = generated.summary,
          keyPoints = generated.keyPoints,
          actionItems = generated.actionItems,
          testVerdict = generated.testVerdict,
        )

        _aiTaskState.value = AiTaskState.Idle
        onMinutesGenerated()
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Meeting minutes generation timed out", e)
        _aiTaskState.value =
          AiTaskState.Error("會議紀錄生成逾時（超過 60 秒），請稍後再試。")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Meeting minutes generation failed", e)
        _aiTaskState.value =
          AiTaskState.Error("產出會議紀錄失敗：${e.localizedMessage ?: "請稍後再試"}")
      }
    }
  }

  fun updateVerdictManually(session: TestSession, newVerdict: String) {
    viewModelScope.launch {
      try {
        repository.updateSessionMinutes(
          userId = currentUserId,
          sessionId = session.id,
          summary = session.minutesSummary,
          keyPoints = session.minutesKeyPoints,
          actionItems = session.minutesActionItems,
          testVerdict = newVerdict,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _aiTaskState.value = AiTaskState.Error("更新測試判定失敗：${e.localizedMessage}")
      }
    }
  }

  fun buildFormattedMinutesReport(
    session: TestSession,
    utterances: List<Utterance>,
  ): String {
    return buildString {
      appendLine("==========================================")
      appendLine("【MetroVoice 捷運測試雙向口譯暨會議紀錄】")
      appendLine("==========================================")
      appendLine("會議名稱：${session.title}")
      appendLine("路線／地點：${session.metroLine}")
      appendLine("測試項目：${session.testCategory}")
      appendLine("外語語系：${session.foreignLangLabel} (${session.foreignLangCode})")
      appendLine("測試判定：${session.minutesTestVerdict}")
      appendLine("對話筆數：${utterances.size} 筆")
      appendLine("------------------------------------------")
      appendLine("一、【執行摘要 (Executive Summary)】")
      appendLine(session.minutesSummary.ifBlank { "（尚未產出摘要）" })
      appendLine()
      appendLine("二、【關鍵測試發現與技術數據 (Key Technical Findings)】")
      if (session.minutesKeyPoints.isEmpty()) {
        appendLine("- 尚無紀錄")
      } else {
        session.minutesKeyPoints.forEachIndexed { idx, pt ->
          appendLine("${idx + 1}. $pt")
        }
      }
      appendLine()
      appendLine("三、【待辦與追蹤改善事項 (Action Items)】")
      if (session.minutesActionItems.isEmpty()) {
        appendLine("- 尚無待辦事項")
      } else {
        session.minutesActionItems.forEachIndexed { idx, act ->
          appendLine("[ ] ${idx + 1}. $act")
        }
      }
      appendLine()
      appendLine("四、【雙向口譯逐字稿紀錄 (Bilingual Transcript)】")
      utterances.forEachIndexed { idx, utt ->
        val star = if (utt.isPinnedToMinutes) "★ " else ""
        appendLine("#${idx + 1} $star${utt.speakerLabel}")
        appendLine("  原文 (${utt.sourceLang}): ${utt.originalText}")
        appendLine("  譯文 (${utt.targetLang}): ${utt.translatedText}")
        if (utt.technicalTerms.isNotEmpty()) {
          appendLine("  術語: ${utt.technicalTerms.joinToString(" | ")}")
        }
        appendLine()
      }
    }
  }
}
