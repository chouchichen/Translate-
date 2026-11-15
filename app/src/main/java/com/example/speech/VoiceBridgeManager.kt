package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.data.model.TestSession
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListeningChannel {
  NONE,
  FOREIGN_TO_LOCAL,
  LOCAL_TO_FOREIGN,
}

/**
 * Immutable snapshot of the active session and direction captured at the moment
 * speech recording starts. Guarantees that switching sessions, languages, or speakers
 * while speaking or finalizing recognition never misroutes or mistranslates the utterance.
 */
data class RecordingSessionContext(
  val session: TestSession,
  val channel: ListeningChannel,
  val languageTag: String,
) {
  val speakerRole: String
    get() =
      if (channel == ListeningChannel.FOREIGN_TO_LOCAL) {
        "FOREIGN_ENGINEER"
      } else {
        "LOCAL_ENGINEER"
      }
}

data class QueuedSpeechItem(
  val utteranceId: String,
  val text: String,
  val languageTag: String,
)

data class VoiceBridgeState(
  val activeChannel: ListeningChannel = ListeningChannel.NONE,
  val isFinalizingSpeech: Boolean = false,
  val pinnedSessionId: String? = null,
  val partialTranscript: String = "",
  val rmsDbLevel: Float = 0f,
  val isTtsSpeaking: Boolean = false,
  val isTtsReady: Boolean = false,
  val currentSpeakingItem: QueuedSpeechItem? = null,
  val pendingSpeechQueueSize: Int = 0,
  val completedSpeechHistory: List<Pair<String, String>> = emptyList(),
  val lastSpokenText: String? = null,
  val lastSpokenLanguageTag: String? = null,
  val statusHint: String? = null,
)

/**
 * Abstraction over Android's [SpeechRecognizer] so that both production and automated tests
 * run through the exact same recognizer lifecycle (`startListening`, `stopListening`,
 * queued turn handoff, and automatic `ERROR_RECOGNIZER_BUSY` retry).
 */
interface SpeechRecognizerEngine {
  fun setRecognitionListener(listener: RecognitionListener)
  fun startListening(recognizerIntent: Intent)
  fun stopListening()
  fun cancel()
  fun destroy()
}

fun interface SpeechRecognizerEngineFactory {
  fun create(context: Context): SpeechRecognizerEngine
}

private class AndroidSpeechRecognizerEngine(context: Context) : SpeechRecognizerEngine {
  private val delegate: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

  override fun setRecognitionListener(listener: RecognitionListener) {
    delegate.setRecognitionListener(listener)
  }

  override fun startListening(recognizerIntent: Intent) {
    delegate.startListening(recognizerIntent)
  }

  override fun stopListening() {
    delegate.stopListening()
  }

  override fun cancel() {
    delegate.cancel()
  }

  override fun destroy() {
    delegate.destroy()
  }
}

/**
 * Abstraction over Android's [TextToSpeech] engine to support utterance-driven FIFO
 * queueing without `QUEUE_FLUSH` truncation and to verify full playback completion in tests.
 */
interface TtsDriver {
  fun setProgressListener(
    onStart: (utteranceId: String) -> Unit,
    onDone: (utteranceId: String) -> Unit,
    onError: (utteranceId: String) -> Unit,
  )
  fun setLanguage(locale: Locale): Int
  fun setSpeechRate(rate: Float)
  fun speak(text: String, queueMode: Int, utteranceId: String)
  fun stop()
  fun shutdown()
}

class VoiceBridgeManager(
  private val context: Context,
  private val onFinalSpeechResult:
    (recordingContext: RecordingSessionContext, recognizedText: String) -> Unit,
  private val customSpeakHandler: ((text: String, languageTag: String) -> Unit)? = null,
  private val recognizerEngineFactory: SpeechRecognizerEngineFactory? = null,
  private val customTtsDriver: TtsDriver? = null,
) {
  /**
   * Self-contained state for a single speech recognition turn.
   * - If a previous turn is still finalizing (`pendingFinalizingAttempts` is non-empty),
   *   [isWaitingForPreviousTurn] defers starting the underlying hardware recognizer until
   *   the previous turn's `onResults`/`onError` releases the speech service.
   * - If the system speech service still reports `ERROR_RECOGNIZER_BUSY` on startup before
   *   any partial speech has been captured, [busyRetryCount] automatically retries up to
   *   [MAX_BUSY_RETRIES] times so the second speaker's turn is never dropped.
   */
  private class RecognitionAttempt(
    val id: Long,
    val context: RecordingSessionContext,
    var recognizer: SpeechRecognizerEngine? = null,
    var listener: RecognitionListener? = null,
    var partialTranscript: String = "",
    var isFinalizing: Boolean = false,
    var isWaitingForPreviousTurn: Boolean = false,
    var busyRetryCount: Int = 0,
    var isCompleted: Boolean = false,
  )

  private var nextAttemptId = 1L
  private var currentAttempt: RecognitionAttempt? = null
  private val pendingFinalizingAttempts = ArrayDeque<RecognitionAttempt>()

  private var ttsDriver: TtsDriver? = null
  private val speechQueue = ArrayDeque<QueuedSpeechItem>()
  private var activeSpeechItem: QueuedSpeechItem? = null
  private val completedSpeechList = mutableListOf<Pair<String, String>>()

  private val _state = MutableStateFlow(VoiceBridgeState())
  val state: StateFlow<VoiceBridgeState> = _state.asStateFlow()

  init {
    initTextToSpeech()
  }

  private fun initTextToSpeech() {
    if (customTtsDriver != null) {
      ttsDriver = customTtsDriver
      bindTtsProgressListener(customTtsDriver)
      _state.value = _state.value.copy(isTtsReady = true)
      return
    }
    try {
      var androidTts: TextToSpeech? = null
      val driver =
        object : TtsDriver {
          override fun setProgressListener(
            onStart: (utteranceId: String) -> Unit,
            onDone: (utteranceId: String) -> Unit,
            onError: (utteranceId: String) -> Unit,
          ) {
            androidTts?.setOnUtteranceProgressListener(
              object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                  if (utteranceId != null) onStart(utteranceId)
                }

                override fun onDone(utteranceId: String?) {
                  if (utteranceId != null) onDone(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                  if (utteranceId != null) onError(utteranceId)
                }
              }
            )
          }

          override fun setLanguage(locale: Locale): Int =
            androidTts?.setLanguage(locale) ?: TextToSpeech.ERROR

          override fun setSpeechRate(rate: Float) {
            androidTts?.setSpeechRate(rate)
          }

          override fun speak(text: String, queueMode: Int, utteranceId: String) {
            androidTts?.speak(text, queueMode, null, utteranceId)
          }

          override fun stop() {
            androidTts?.stop()
          }

          override fun shutdown() {
            androidTts?.shutdown()
          }
        }

      androidTts =
        TextToSpeech(context.applicationContext) { status ->
          if (status == TextToSpeech.SUCCESS) {
            ttsDriver = driver
            bindTtsProgressListener(driver)
            _state.value = _state.value.copy(isTtsReady = true)
            // If items were queued before TTS finished initializing, start the first one now
            if (activeSpeechItem == null && speechQueue.isNotEmpty()) {
              val next = speechQueue.removeFirst()
              dispatchSpeechItem(next)
            }
          } else {
            Log.w(TAG, "TextToSpeech initialization returned status: $status")
          }
        }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize TextToSpeech", e)
    }
  }

  private fun bindTtsProgressListener(driver: TtsDriver) {
    driver.setProgressListener(
      onStart = { utteranceId ->
        if (activeSpeechItem?.utteranceId == utteranceId) {
          _state.value = _state.value.copy(isTtsSpeaking = true)
        }
      },
      onDone = { utteranceId -> handleTtsUtteranceFinished(utteranceId, completedSuccessfully = true) },
      onError = { utteranceId ->
        handleTtsUtteranceFinished(utteranceId, completedSuccessfully = false)
      },
    )
  }

  private fun handleTtsUtteranceFinished(
    utteranceId: String,
    completedSuccessfully: Boolean,
  ) {
    val current = activeSpeechItem
    if (current != null && current.utteranceId == utteranceId) {
      if (completedSuccessfully) {
        completedSpeechList.add(current.text to current.languageTag)
      }
      val next = speechQueue.removeFirstOrNull()
      if (next != null) {
        dispatchSpeechItem(next)
      } else {
        activeSpeechItem = null
        _state.value =
          _state.value.copy(
            isTtsSpeaking = false,
            currentSpeakingItem = null,
            pendingSpeechQueueSize = 0,
            completedSpeechHistory = completedSpeechList.toList(),
          )
      }
    }
  }

  /**
   * Starts speech recognition and immediately pins the target [session] and [channel].
   * - If a previous turn is still active or waiting for final `onResults`, it is gracefully
   *   finalized (`stopListening()`) and moved to [pendingFinalizingAttempts]—and starting the
   *   new turn's hardware recognizer waits until the previous turn releases the speech service.
   * - Does NOT flush or cut off ongoing TTS translations so the previous speaker's translated
   *   audio finishes playing completely.
   */
  fun startListening(
    channel: ListeningChannel,
    session: TestSession,
    forceEnableInTest: Boolean = false,
  ) {
    val previousAttempt = currentAttempt
    if (previousAttempt != null && !previousAttempt.isCompleted) {
      finishAttemptInternal(previousAttempt)
      if (!pendingFinalizingAttempts.contains(previousAttempt)) {
        pendingFinalizingAttempts.addLast(previousAttempt)
      }
      currentAttempt = null
    }

    val languageTag =
      if (channel == ListeningChannel.FOREIGN_TO_LOCAL) {
        session.foreignLangCode
      } else {
        "zh-TW"
      }

    val hasCustomFactory = recognizerEngineFactory != null
    if (!forceEnableInTest && !hasCustomFactory && !SpeechRecognizer.isRecognitionAvailable(context)) {
      _state.value =
        _state.value.copy(
          activeChannel = ListeningChannel.NONE,
          isFinalizingSpeech = false,
          pinnedSessionId = null,
          statusHint = "此裝置／模擬器未安裝系統語音辨識服務，可直接點擊下方「現場常用語快選」或輸入文字進行即時雙向口譯。",
        )
      return
    }

    val pinnedContext =
      RecordingSessionContext(
        session = session,
        channel = channel,
        languageTag = languageTag,
      )
    val shouldWaitForPrevious = pendingFinalizingAttempts.any { !it.isCompleted }
    val attempt =
      RecognitionAttempt(
        id = nextAttemptId++,
        context = pinnedContext,
        isWaitingForPreviousTurn = shouldWaitForPrevious,
      )
    currentAttempt = attempt

    val listener = createRecognitionListener(attempt, forceEnableInTest)
    attempt.listener = listener

    _state.value =
      _state.value.copy(
        activeChannel = channel,
        isFinalizingSpeech = false,
        pinnedSessionId = session.id,
        partialTranscript = "",
        rmsDbLevel = 0f,
        statusHint =
          if (shouldWaitForPrevious) {
            "正在完成上一句收尾，隨即啟動收音..."
          } else {
            null
          },
      )

    if (!shouldWaitForPrevious) {
      startRecognizerForAttempt(attempt, forceEnableInTest)
    }
  }

  private fun startRecognizerForAttempt(
    attempt: RecognitionAttempt,
    forceEnableInTest: Boolean,
  ) {
    if (attempt.isCompleted) return
    attempt.isWaitingForPreviousTurn = false
    val listener = attempt.listener ?: createRecognitionListener(attempt, forceEnableInTest).also {
      attempt.listener = it
    }

    try {
      val engine: SpeechRecognizerEngine? =
        when {
          recognizerEngineFactory != null -> recognizerEngineFactory.create(context)
          !forceEnableInTest -> AndroidSpeechRecognizerEngine(context)
          else -> null
        }

      attempt.recognizer = engine
      if (engine != null) {
        engine.setRecognitionListener(listener)
        val intent =
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
              RecognizerIntent.EXTRA_LANGUAGE_MODEL,
              RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, attempt.context.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
          }
        engine.startListening(intent)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start SpeechRecognizerEngine", e)
      attempt.isCompleted = true
      destroyAttemptRecognizer(attempt)
      if (currentAttempt === attempt) {
        currentAttempt = null
        _state.value =
          _state.value.copy(
            activeChannel = ListeningChannel.NONE,
            isFinalizingSpeech = false,
            pinnedSessionId = null,
            statusHint = "無法啟動語音收音，請使用下方捷運現場語句快選或文字輸入。",
          )
      }
    }
  }

  private fun onPendingAttemptSettled(forceEnableInTest: Boolean) {
    val active = currentAttempt ?: return
    if (
      !active.isCompleted &&
        active.isWaitingForPreviousTurn &&
        pendingFinalizingAttempts.none { !it.isCompleted }
    ) {
      startRecognizerForAttempt(active, forceEnableInTest)
    }
  }

  private fun createRecognitionListener(
    attempt: RecognitionAttempt,
    forceEnableInTest: Boolean,
  ): RecognitionListener {
    return object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        if (currentAttempt === attempt && !attempt.isCompleted) {
          _state.value = _state.value.copy(statusHint = "請開始說話，系統正在即時收音...")
        }
      }

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {
        if (currentAttempt === attempt && !attempt.isCompleted) {
          _state.value = _state.value.copy(rmsDbLevel = rmsdB.coerceIn(0f, 12f))
        }
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        if (attempt.isCompleted) return
        attempt.isFinalizing = true
        if (currentAttempt === attempt) {
          _state.value =
            _state.value.copy(
              isFinalizingSpeech = true,
              rmsDbLevel = 0f,
              statusHint = "正在完成語音辨識...",
            )
        }
      }

      override fun onError(error: Int) {
        if (attempt.isCompleted) return

        val fallbackPartial = attempt.partialTranscript.trim()
        val wasFinalizing = attempt.isFinalizing

        // Automatic recovery when the system speech service reports BUSY (or transient client
        // handoff error) on rapid turn-taking before the second speaker has produced partial text!
        if (
          fallbackPartial.isBlank() &&
            !wasFinalizing &&
            (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
              error == SpeechRecognizer.ERROR_CLIENT) &&
            attempt.busyRetryCount < MAX_BUSY_RETRIES
        ) {
          attempt.busyRetryCount++
          destroyAttemptRecognizer(attempt)
          if (currentAttempt === attempt) {
            _state.value =
              _state.value.copy(
                statusHint = "語音服務切換中，正在自動重新接通收音 (${attempt.busyRetryCount}/$MAX_BUSY_RETRIES)..."
              )
          }
          startRecognizerForAttempt(attempt, forceEnableInTest)
          return
        }

        attempt.isCompleted = true
        val attemptContext = attempt.context

        destroyAttemptRecognizer(attempt)
        pendingFinalizingAttempts.remove(attempt)
        val isStillCurrent = currentAttempt === attempt
        if (isStillCurrent) {
          currentAttempt = null
        }

        // If a queued next turn was waiting for this finalizing turn to release the speech service,
        // start that queued turn now!
        onPendingAttemptSettled(forceEnableInTest)

        if (
          fallbackPartial.isNotBlank() &&
            (wasFinalizing ||
              error == SpeechRecognizer.ERROR_NO_MATCH ||
              error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
              error == SpeechRecognizer.ERROR_CLIENT ||
              error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        ) {
          if (isStillCurrent) {
            _state.value =
              _state.value.copy(
                activeChannel = ListeningChannel.NONE,
                isFinalizingSpeech = false,
                pinnedSessionId = null,
                partialTranscript = "",
                rmsDbLevel = 0f,
                statusHint = null,
              )
          }
          onFinalSpeechResult(attemptContext, fallbackPartial)
          return
        }

        if (isStillCurrent) {
          val hint =
            when (error) {
              SpeechRecognizer.ERROR_NO_MATCH,
              SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "未偵測到清晰語音。若在模擬器環境，可點擊下方「捷運現場情境語句」一鍵測試即時翻譯。"
              SpeechRecognizer.ERROR_AUDIO ->
                "麥克風音訊無法存取。您可使用下方「捷運現場情境語句」或文字輸入即時翻譯。"
              SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "請允許麥克風權限以使用即時語音收音。"
              else ->
                "語音收音結束（代碼 $error），可直接點選下方現場語句或輸入文字。"
            }
          _state.value =
            _state.value.copy(
              activeChannel = ListeningChannel.NONE,
              isFinalizingSpeech = false,
              pinnedSessionId = null,
              partialTranscript = "",
              rmsDbLevel = 0f,
              statusHint = hint,
            )
        }
        Log.w(TAG, "SpeechRecognizer error=$error on channel=${attemptContext.channel}")
      }

      override fun onResults(results: Bundle?) {
        if (attempt.isCompleted) return
        attempt.isCompleted = true

        val attemptContext = attempt.context
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestText =
          matches?.firstOrNull()?.trim().orEmpty().ifBlank {
            attempt.partialTranscript.trim()
          }

        destroyAttemptRecognizer(attempt)
        pendingFinalizingAttempts.remove(attempt)
        val isStillCurrent = currentAttempt === attempt
        if (isStillCurrent) {
          currentAttempt = null
          _state.value =
            _state.value.copy(
              activeChannel = ListeningChannel.NONE,
              isFinalizingSpeech = false,
              pinnedSessionId = null,
              partialTranscript = "",
              rmsDbLevel = 0f,
              statusHint = null,
            )
        }

        // Start any next turn that was waiting for this attempt to finish!
        onPendingAttemptSettled(forceEnableInTest)

        if (bestText.isNotBlank() && attemptContext.channel != ListeningChannel.NONE) {
          onFinalSpeechResult(attemptContext, bestText)
        }
      }

      override fun onPartialResults(partialResults: Bundle?) {
        if (attempt.isCompleted) return
        val partial =
          partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (partial.isNotBlank()) {
          attempt.partialTranscript = partial
          if (currentAttempt === attempt) {
            _state.value = _state.value.copy(partialTranscript = partial)
          }
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }
  }

  private fun finishAttemptInternal(attempt: RecognitionAttempt) {
    if (attempt.isCompleted) return
    attempt.isFinalizing = true
    try {
      attempt.recognizer?.stopListening()
    } catch (e: Exception) {
      Log.w(TAG, "stopListening failed for attempt ${attempt.id}", e)
    }
  }

  /**
   * Signals that the user has finished speaking (`SpeechRecognizer.stopListening()`)
   * WITHOUT cancelling or destroying the recognizer, allowing Android's `SpeechRecognizer`
   * to deliver `onResults` asynchronously.
   */
  fun finishListening() {
    val active = currentAttempt ?: return
    if (active.isCompleted) return
    _state.value =
      _state.value.copy(
        isFinalizingSpeech = true,
        rmsDbLevel = 0f,
        statusHint = "正在辨識剛才講完的語音...",
      )
    finishAttemptInternal(active)
  }

  /**
   * Explicitly cancels and discards the currently active speech recognition turn without
   * emitting a final result. Any earlier turn already in [pendingFinalizingAttempts] is
   * left alone to finish delivering its result.
   */
  fun cancelListening() {
    val active = currentAttempt
    if (active != null) {
      active.isCompleted = true
      cancelAndDestroyAttemptRecognizer(active)
      pendingFinalizingAttempts.remove(active)
      currentAttempt = null
    }
    _state.value =
      _state.value.copy(
        activeChannel = ListeningChannel.NONE,
        isFinalizingSpeech = false,
        pinnedSessionId = null,
        partialTranscript = "",
        rmsDbLevel = 0f,
      )
  }

  private fun destroyAttemptRecognizer(attempt: RecognitionAttempt) {
    try {
      attempt.recognizer?.destroy()
    } catch (_: Exception) {}
    attempt.recognizer = null
  }

  private fun cancelAndDestroyAttemptRecognizer(attempt: RecognitionAttempt) {
    try {
      attempt.recognizer?.cancel()
      attempt.recognizer?.destroy()
    } catch (_: Exception) {}
    attempt.recognizer = null
  }

  /**
   * Test helper to simulate `SpeechRecognizer` final results in FIFO order across turns.
   */
  internal fun deliverRecognitionResultsForTest(recognizedText: String) {
    val targetAttempt =
      pendingFinalizingAttempts.firstOrNull { !it.isCompleted } ?: currentAttempt
    val bundle =
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(recognizedText))
      }
    targetAttempt?.listener?.onResults(bundle)
  }

  internal fun deliverPartialResultsForTest(partialText: String) {
    val bundle =
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partialText))
      }
    currentAttempt?.listener?.onPartialResults(bundle)
  }

  /**
   * Enqueues [text] in [languageTag] for sequential TTS playback.
   * Never uses `QUEUE_FLUSH` by default so that back-to-back translated sentences play
   * completely in FIFO order without cutting off the previous sentence mid-speech, and
   * switches the TTS engine language right before each utterance begins playing.
   */
  fun speakText(
    text: String,
    languageTag: String,
    interruptCurrent: Boolean = false,
  ) {
    val cleanText = text.trim()
    if (cleanText.isEmpty()) return

    if (interruptCurrent) {
      speechQueue.clear()
      activeSpeechItem = null
      try {
        ttsDriver?.stop()
      } catch (_: Exception) {}
    }

    val item =
      QueuedSpeechItem(
        utteranceId = UUID.randomUUID().toString(),
        text = cleanText,
        languageTag = languageTag,
      )

    if (activeSpeechItem == null) {
      dispatchSpeechItem(item)
    } else {
      speechQueue.addLast(item)
      _state.value =
        _state.value.copy(
          pendingSpeechQueueSize = speechQueue.size,
        )
    }
  }

  private fun dispatchSpeechItem(item: QueuedSpeechItem) {
    activeSpeechItem = item
    _state.value =
      _state.value.copy(
        isTtsSpeaking = true,
        currentSpeakingItem = item,
        pendingSpeechQueueSize = speechQueue.size,
        lastSpokenText = item.text,
        lastSpokenLanguageTag = item.languageTag,
      )

    if (customSpeakHandler != null && customTtsDriver == null) {
      customSpeakHandler.invoke(item.text, item.languageTag)
      handleTtsUtteranceFinished(item.utteranceId, completedSuccessfully = true)
      return
    }

    val driver = ttsDriver
    if (driver == null) {
      // Keep item as active/queued until TTS initialization callback dispatches it
      return
    }

    try {
      val locale = Locale.forLanguageTag(item.languageTag)
      val langResult = driver.setLanguage(locale)
      if (
        langResult == TextToSpeech.LANG_MISSING_DATA ||
          langResult == TextToSpeech.LANG_NOT_SUPPORTED
      ) {
        Log.w(TAG, "TTS locale ${item.languageTag} not fully supported, using default fallback")
      }
      driver.setSpeechRate(0.98f)
      customSpeakHandler?.invoke(item.text, item.languageTag)
      driver.speak(item.text, TextToSpeech.QUEUE_ADD, item.utteranceId)
    } catch (e: Exception) {
      Log.e(TAG, "TTS speakText failed", e)
      handleTtsUtteranceFinished(item.utteranceId, completedSuccessfully = false)
    }
  }

  fun stopSpeaking() {
    speechQueue.clear()
    activeSpeechItem = null
    try {
      ttsDriver?.stop()
    } catch (_: Exception) {}
    _state.value =
      _state.value.copy(
        isTtsSpeaking = false,
        currentSpeakingItem = null,
        pendingSpeechQueueSize = 0,
      )
  }

  fun clearHint() {
    _state.value = _state.value.copy(statusHint = null)
  }

  fun release() {
    cancelListening()
    val pending = pendingFinalizingAttempts.toList()
    pendingFinalizingAttempts.clear()
    pending.forEach { attempt ->
      attempt.isCompleted = true
      cancelAndDestroyAttemptRecognizer(attempt)
    }
    stopSpeaking()
    try {
      ttsDriver?.shutdown()
    } catch (_: Exception) {}
    ttsDriver = null
  }

  private companion object {
    const val TAG = "VoiceBridgeManager"
    const val MAX_BUSY_RETRIES = 3
  }
}
