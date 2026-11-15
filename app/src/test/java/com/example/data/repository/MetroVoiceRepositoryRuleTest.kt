package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.SUPPORTED_FOREIGN_LANGUAGES
import com.example.data.model.TestSession
import com.example.speech.ListeningChannel
import com.example.speech.RecordingSessionContext
import com.example.speech.SpeechRecognizerEngine
import com.example.speech.TtsDriver
import com.example.speech.VoiceBridgeManager
import com.example.viewmodel.AiTaskState
import com.example.viewmodel.DataUiState
import com.example.viewmodel.MetroVoiceViewModel
import com.example.viewmodel.PENDING_TRANSLATION_PLACEHOLDER
import com.example.viewmodel.canRetryTranslation
import com.example.viewmodel.isTranslationFailed
import com.example.viewmodel.isTranslationPending
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MetroVoiceRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun createAndObserveSession_authenticatedOwner_succeeds() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val repository = MetroVoiceRepository(firestore)

    val sessionId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.createSession(
          title = "板南線 CBTC 號誌測試",
          metroLine = "板南線 (BL)",
          testCategory = "CBTC 號誌與聯鎖測試",
          foreignLangCode = "en-US",
          foreignLangLabel = "English (英文)",
        )
      }
    assertTrue(sessionId.isNotBlank())

    val emittedSessions =
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeSessions(aliceUid).first { list -> list.any { it.id == sessionId } }
      }
    assertTrue(emittedSessions.any { it.id == sessionId && it.title == "板南線 CBTC 號誌測試" })
  }

  @Test
  fun addAndPinUtterance_authenticatedOwner_succeeds() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val repository = MetroVoiceRepository(firestore)

    val sessionId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.createSession(
          title = "環狀線月台門連動測試",
          metroLine = "環狀線 (Y)",
          testCategory = "月台門 (PSD) 同步連動",
          foreignLangCode = "en-US",
          foreignLangLabel = "English (英文)",
        )
      }

    val utteranceId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.addUtterance(
          sessionId = sessionId,
          speakerRole = "FOREIGN_ENGINEER",
          speakerLabel = "外籍技師",
          sourceLang = "en-US",
          targetLang = "zh-TW",
          originalText = "Checking PSD interlock relay timing.",
          translatedText = "正在檢查月台門聯鎖繼電器時序。",
          technicalTerms = listOf("PSD: 月台門", "Interlock relay: 聯鎖繼電器"),
          isPinnedToMinutes = false,
        )
      }
    assertTrue(utteranceId.isNotBlank())

    withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.togglePinUtterance(aliceUid, sessionId, utteranceId, true)
    }

    val utterances =
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeUtterances(aliceUid, sessionId).first { list ->
          list.any { it.id == utteranceId && it.isPinnedToMinutes }
        }
      }
    assertTrue(utterances.any { it.id == utteranceId && it.isPinnedToMinutes })
  }

  /**
   * Verifies session pinning, `finishListening()` without cancelling, and automatic TTS output
   * for microphone recognition.
   */
  @Test
  fun microphoneFlow_finishListeningAndSwitchSession_recordsToPinnedSessionAndSpeaksTranslation() =
    runBlocking {
      val aliceUid = signInTestUser(ALICE_EMAIL)
      val repository = MetroVoiceRepository(firestore)
      val context = ApplicationProvider.getApplicationContext<Context>()

      val sessionAId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSession(
            title = "Session A English CBTC",
            metroLine = "板南線 (BL)",
            testCategory = "CBTC 號誌與聯鎖測試",
            foreignLangCode = "en-US",
            foreignLangLabel = "English (英文)",
          )
        }
      val sessionBId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSession(
            title = "Session B Japanese Bogie",
            metroLine = "環狀線 (Y)",
            testCategory = "電聯車動態與煞車測試",
            foreignLangCode = "ja-JP",
            foreignLangLabel = "日本語 (日文)",
          )
        }

      val sessionA =
        withTimeout(DEFAULT_TIMEOUT_MS) { repository.getSessionById(aliceUid, sessionAId)!! }

      val spokenOutputs = mutableListOf<Pair<String, String>>()

      val viewModel =
        MetroVoiceViewModel(
          repository = repository,
          currentUserId = aliceUid,
          autoSeedOnEmpty = false,
          customTranslationGenerator = { session, _, _ ->
            """
            {
              "translatedText": "請確認第二月台月台門聯鎖繼電器延遲（場次：${session.foreignLangCode}）。",
              "technicalTerms": ["PSD: 月台門系統"]
            }
            """.trimIndent()
          },
        )

      lateinit var voiceBridge: VoiceBridgeManager
      voiceBridge =
        VoiceBridgeManager(
          context = context,
          onFinalSpeechResult = { recordingContext: RecordingSessionContext, recognizedText: String ->
            viewModel.translateAndRecordUtterance(
              session = recordingContext.session,
              speakerRole = recordingContext.speakerRole,
              spokenText = recognizedText,
              onSpeakTranslationReady = { textToSpeak, langTag ->
                voiceBridge.speakText(textToSpeak, langTag)
              },
            )
          },
          customSpeakHandler = { text, lang -> spokenOutputs.add(text to lang) },
        )

      // 1. User starts speaking in Session A (English -> Chinese)
      viewModel.selectSession(sessionAId)
      voiceBridge.startListening(
        channel = ListeningChannel.FOREIGN_TO_LOCAL,
        session = sessionA,
        forceEnableInTest = true,
      )

      // 2. While speaking, user switches UI to Session B (Japanese) and taps "講完送出辨識" (finishListening)
      viewModel.selectSession(sessionBId)
      voiceBridge.finishListening()
      assertTrue(voiceBridge.state.value.isFinalizingSpeech)
      assertEquals(ListeningChannel.FOREIGN_TO_LOCAL, voiceBridge.state.value.activeChannel)

      // 3. SpeechRecognizer delivers final result after finishListening()
      voiceBridge.deliverRecognitionResultsForTest("Please verify PSD relay delay at Platform 2.")

      // 4. Verify utterance was recorded in Session A (NOT Session B) with final translation
      val sessionAUtterances =
        withTimeout(FLOW_TIMEOUT_MS) {
          repository.observeUtterances(aliceUid, sessionAId).first { list ->
            list.any {
              it.originalText == "Please verify PSD relay delay at Platform 2." &&
                it.translatedText.contains("請確認第二月台月台門聯鎖繼電器延遲（場次：en-US）")
            }
          }
        }
      withTimeout(FLOW_TIMEOUT_MS) {
        viewModel.aiTaskState.first { it is AiTaskState.Idle }
      }
      assertEquals(1, sessionAUtterances.size)
      assertEquals("en-US", sessionAUtterances.first().sourceLang)
      assertEquals("zh-TW", sessionAUtterances.first().targetLang)

      val sessionBUtterances =
        withTimeout(FLOW_TIMEOUT_MS) { repository.observeUtterances(aliceUid, sessionBId).first() }
      assertTrue("Session B must remain empty", sessionBUtterances.isEmpty())

      // 5. Verify TTS speakText was called for the microphone utterance!
      assertEquals(1, spokenOutputs.size)
      assertEquals(
        "請確認第二月台月台門聯鎖繼電器延遲（場次：en-US）。" to "zh-TW",
        spokenOutputs.first(),
      )
    }

  /**
   * Verifies realistic turn-taking through the `SpeechRecognizerEngine` and `TtsDriver` lifecycles:
   * 1. Foreign engineer speaks in Turn 1 (`en-US` -> `zh-TW`), and local engineer immediately presses
   *    the second mic (`zh-TW` -> `en-US`) BEFORE Turn 1's final `onResults` arrives.
   * 2. `VoiceBridgeManager` calls `stopListening()` on Turn 1 and waits for Turn 1 to finish before
   *    starting Turn 2's hardware recognizer so the two recognizers do not collide.
   * 3. When Turn 2's recognizer starts, the simulated phone speech service returns
   *    `SpeechRecognizer.ERROR_RECOGNIZER_BUSY` on the first try; `VoiceBridgeManager` automatically
   *    retries (`busyRetryCount`) and captures Turn 2's speech without dropping the turn!
   * 4. While Turn 1's `zh-TW` translation is still being spoken aloud by `TtsDriver`, Turn 2's
   *    `en-US` translation finishes. `VoiceBridgeManager` enqueues Turn 2 (`QUEUE_ADD`, NEVER
   *    `QUEUE_FLUSH`), does NOT cut off Turn 1 mid-sentence, and only switches `TtsDriver` to
   *    `en-US` and plays Turn 2 after Turn 1's `onDone` completes!
   */
  @Test
  fun turnTakingMicrophone_secondMicPressedBeforeFirstResultArrives_preservesBothInCorrectDirection() =
    runBlocking {
      val aliceUid = signInTestUser(ALICE_EMAIL)
      val repository = MetroVoiceRepository(firestore)
      val context = ApplicationProvider.getApplicationContext<Context>()

      val sessionId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSession(
            title = "Turn-Taking Bidirectional Test",
            metroLine = "板南線 (BL)",
            testCategory = "CBTC 號誌與聯鎖測試",
            foreignLangCode = "en-US",
            foreignLangLabel = "English (英文)",
          )
        }
      val session =
        withTimeout(DEFAULT_TIMEOUT_MS) { repository.getSessionById(aliceUid, sessionId)!! }

      // Simulated Android Speech Recognition Service that enforces single-active-recognizer
      // and injects ERROR_RECOGNIZER_BUSY on the first attempt of Turn 2
      open class FakeRecognizerInstance : SpeechRecognizerEngine {
        var listener: RecognitionListener? = null
        var startedLanguage: String? = null
        var stopCalled = false
        var destroyed = false

        override fun setRecognitionListener(listener: RecognitionListener) {
          this.listener = listener
        }

        override fun startListening(recognizerIntent: Intent) {
          startedLanguage = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        }

        override fun stopListening() {
          stopCalled = true
        }

        override fun cancel() {
          destroyed = true
        }

        override fun destroy() {
          destroyed = true
        }
      }

      val createdRecognizers = mutableListOf<FakeRecognizerInstance>()
      var simulateBusyOnNextStart = false

      // Simulated asynchronous Android TextToSpeech driver that holds each utterance active
      // until completeCurrentUtterance() is called, verifying no QUEUE_FLUSH cutoff occurs!
      var ttsOnStart: ((String) -> Unit)? = null
      var ttsOnDone: ((String) -> Unit)? = null
      var currentTtsLocale: Locale? = null
      val activeTtsQueue = ArrayDeque<Triple<String, String, String>>() // (utteranceId, text, langTag)
      var everUsedQueueFlush = false

      val fakeTtsDriver =
        object : TtsDriver {
          override fun setProgressListener(
            onStart: (utteranceId: String) -> Unit,
            onDone: (utteranceId: String) -> Unit,
            onError: (utteranceId: String) -> Unit,
          ) {
            ttsOnStart = onStart
            ttsOnDone = onDone
          }

          override fun setLanguage(locale: Locale): Int {
            currentTtsLocale = locale
            return TextToSpeech.LANG_AVAILABLE
          }

          override fun setSpeechRate(rate: Float) {}

          override fun speak(text: String, queueMode: Int, utteranceId: String) {
            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
              everUsedQueueFlush = true
            }
            activeTtsQueue.addLast(
              Triple(utteranceId, text, currentTtsLocale?.toLanguageTag().orEmpty())
            )
            ttsOnStart?.invoke(utteranceId)
          }

          override fun stop() {
            activeTtsQueue.clear()
          }

          override fun shutdown() {
            activeTtsQueue.clear()
          }
        }

      val viewModel =
        MetroVoiceViewModel(
          repository = repository,
          currentUserId = aliceUid,
          autoSeedOnEmpty = false,
          customTranslationGenerator = { _, speakerRole, spokenText ->
            if (speakerRole == "FOREIGN_ENGINEER") {
              """{"translatedText": "【中譯】$spokenText", "technicalTerms": []}"""
            } else {
              """{"translatedText": "[EN] $spokenText", "technicalTerms": []}"""
            }
          },
        )

      lateinit var voiceBridge: VoiceBridgeManager
      voiceBridge =
        VoiceBridgeManager(
          context = context,
          onFinalSpeechResult = { recordingContext: RecordingSessionContext, recognizedText: String ->
            viewModel.translateAndRecordUtterance(
              session = recordingContext.session,
              speakerRole = recordingContext.speakerRole,
              spokenText = recognizedText,
              onSpeakTranslationReady = { textToSpeak, langTag ->
                voiceBridge.speakText(textToSpeak, langTag)
              },
            )
          },
          recognizerEngineFactory = {
            object : FakeRecognizerInstance() {
              override fun startListening(recognizerIntent: Intent) {
                super.startListening(recognizerIntent)
                createdRecognizers.add(this)
                if (simulateBusyOnNextStart) {
                  simulateBusyOnNextStart = false
                  listener?.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                }
              }
            }
          },
          customTtsDriver = fakeTtsDriver,
        )

      // 1. Turn 1: Foreign engineer starts speaking (en-US -> zh-TW)
      voiceBridge.startListening(
        channel = ListeningChannel.FOREIGN_TO_LOCAL,
        session = session,
      )
      assertEquals(1, createdRecognizers.size)
      assertEquals("en-US", createdRecognizers[0].startedLanguage)

      // 2. Turn 2: Local engineer immediately presses the second mic (zh-TW -> en-US) BEFORE Turn 1's onResults arrives!
      // Verify Turn 1 receives stopListening(), and Turn 2 waits for Turn 1 to finish before starting its recognizer.
      simulateBusyOnNextStart = true
      voiceBridge.startListening(
        channel = ListeningChannel.LOCAL_TO_FOREIGN,
        session = session,
      )
      assertTrue("Turn 1 recognizer must receive stopListening()", createdRecognizers[0].stopCalled)
      assertEquals(
        "Turn 2 must wait for Turn 1 to finish before starting a second recognizer",
        1,
        createdRecognizers.size,
      )
      assertEquals(ListeningChannel.LOCAL_TO_FOREIGN, voiceBridge.state.value.activeChannel)

      // 3. Turn 1 delivers its final result -> triggers Turn 2 recognizer -> first attempt hits ERROR_RECOGNIZER_BUSY -> auto-retries!
      val turn1Bundle =
        Bundle().apply {
          putStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION,
            arrayListOf("Beacon handover latency is 12 ms."),
          )
        }
      createdRecognizers[0].listener?.onResults(turn1Bundle)

      // Verify Turn 2 automatically started, hit ERROR_RECOGNIZER_BUSY on recognizer[1], and auto-retried on recognizer[2]!
      assertEquals(
        "Turn 2 must automatically retry when speech service returns ERROR_RECOGNIZER_BUSY",
        3,
        createdRecognizers.size,
      )
      assertEquals("zh-TW", createdRecognizers[2].startedLanguage)
      assertEquals(ListeningChannel.LOCAL_TO_FOREIGN, voiceBridge.state.value.activeChannel)

      // 4. Now Turn 2 finishes speaking and delivers its result on the retried recognizer[2]
      voiceBridge.finishListening()
      val turn2Bundle =
        Bundle().apply {
          putStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION,
            arrayListOf("收到，應答器延遲符合規範。"),
          )
        }
      createdRecognizers[2].listener?.onResults(turn2Bundle)
      assertEquals(ListeningChannel.NONE, voiceBridge.state.value.activeChannel)

      // 5. Wait for both utterances to finish translating and saving in Firestore
      val utterances =
        withTimeout(FLOW_TIMEOUT_MS) {
          repository.observeUtterances(aliceUid, sessionId).first { list ->
            list.size == 2 &&
              list.any { it.translatedText == "【中譯】Beacon handover latency is 12 ms." } &&
              list.any { it.translatedText == "[EN] 收到，應答器延遲符合規範。" }
          }
        }
      withTimeout(FLOW_TIMEOUT_MS) {
        viewModel.aiTaskState.first { it is AiTaskState.Idle }
      }

      val foreignUtt = utterances.first { it.speakerRole == "FOREIGN_ENGINEER" }
      assertEquals("Beacon handover latency is 12 ms.", foreignUtt.originalText)
      assertEquals("en-US", foreignUtt.sourceLang)
      assertEquals("zh-TW", foreignUtt.targetLang)

      val localUtt = utterances.first { it.speakerRole == "LOCAL_ENGINEER" }
      assertEquals("收到，應答器延遲符合規範。", localUtt.originalText)
      assertEquals("zh-TW", localUtt.sourceLang)
      assertEquals("en-US", localUtt.targetLang)

      // 6. Verify Sequential TTS Queue:
      // While Turn 1's TTS has NOT yet called onDone, Turn 2's translation arrived.
      // Verify Turn 1 was NOT cut off by QUEUE_FLUSH, only Turn 1 has been dispatched to TtsDriver so far,
      // and Turn 2 is waiting in pendingSpeechQueueSize!
      assertFalse("TTS must never use QUEUE_FLUSH to cut off ongoing speech", everUsedQueueFlush)
      assertEquals(
        "Only Turn 1 should be dispatched to TTS engine while Turn 1 is still speaking",
        1,
        activeTtsQueue.size,
      )
      assertEquals(1, voiceBridge.state.value.pendingSpeechQueueSize)
      val (turn1TtsId, turn1TtsText, turn1TtsLang) = activeTtsQueue.removeFirst()
      assertEquals("【中譯】Beacon handover latency is 12 ms.", turn1TtsText)
      assertEquals("zh-TW", turn1TtsLang)

      // Now complete Turn 1's TTS playback -> must automatically dispatch Turn 2 in en-US!
      ttsOnDone?.invoke(turn1TtsId)
      assertEquals(1, activeTtsQueue.size)
      assertEquals(0, voiceBridge.state.value.pendingSpeechQueueSize)
      val (turn2TtsId, turn2TtsText, turn2TtsLang) = activeTtsQueue.removeFirst()
      assertEquals("[EN] 收到，應答器延遲符合規範。", turn2TtsText)
      assertEquals("en-US", turn2TtsLang)

      // Complete Turn 2's TTS playback -> verify both completed in exact FIFO order!
      ttsOnDone?.invoke(turn2TtsId)
      assertFalse(voiceBridge.state.value.isTtsSpeaking)
      assertEquals(
        listOf(
          "【中譯】Beacon handover latency is 12 ms." to "zh-TW",
          "[EN] 收到，應答器延遲符合規範。" to "en-US",
        ),
        voiceBridge.state.value.completedSpeechHistory,
      )
    }

  /**
   * Verifies:
   * 1. Blank `translatedText` is rejected and marks the utterance failed.
   * 2. If the user changes the session language from English (`en-US`) to Japanese (`ja-JP`)
   *    before clicking Retry on a failed `zh-TW -> en-US` utterance, retry uses the utterance's
   *    own pinned `en-US` language for both AI translation and TTS speech output.
   */
  @Test
  fun translationFailureAndBlankTranslation_preservesOriginalTextAndSupportsConsistentRetryAfterLangChange() =
    runBlocking {
      val aliceUid = signInTestUser(ALICE_EMAIL)
      val repository = MetroVoiceRepository(firestore)

      val sessionId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSession(
            title = "板南線反譯韌性測試",
            metroLine = "板南線 (BL)",
            testCategory = "CBTC 號誌與聯鎖測試",
            foreignLangCode = "en-US",
            foreignLangLabel = "English (英文)",
          )
        }
      val session: TestSession =
        withTimeout(DEFAULT_TIMEOUT_MS) { repository.getSessionById(aliceUid, sessionId)!! }

      var shouldReturnBlankJson = true
      val spokenOutputs = mutableListOf<Pair<String, String>>()
      val requestedTranslationLangCodes = mutableListOf<String>()

      val viewModel =
        MetroVoiceViewModel(
          repository = repository,
          currentUserId = aliceUid,
          autoSeedOnEmpty = false,
          customTranslationGenerator = { reqSession, _, _ ->
            requestedTranslationLangCodes.add(reqSession.foreignLangCode)
            if (shouldReturnBlankJson) {
              """{"translatedText": "   ", "technicalTerms": []}"""
            } else {
              """
              {
                "translatedText": "The ATO stopping accuracy is within plus or minus 5 cm (${reqSession.foreignLangCode}).",
                "technicalTerms": ["ATO: Automatic Train Operation"]
              }
              """.trimIndent()
            }
          },
        )

      // 1. Trigger local-to-foreign (zh-TW -> en-US) translation while model returns blank translatedText
      viewModel.translateAndRecordUtterance(
        session = session,
        speakerRole = "LOCAL_ENGINEER",
        spokenText = "ATO 停車精準度在正負 5 公分內。",
        onSpeakTranslationReady = { text, lang -> spokenOutputs.add(text to lang) },
      )

      // 2. Verify originalText was preserved in Firestore and marked as failed
      val failedUtterance =
        withTimeout(FLOW_TIMEOUT_MS) {
          repository
            .observeUtterances(aliceUid, sessionId)
            .first { list -> list.any { it.isTranslationFailed() } }
            .first()
        }
      withTimeout(FLOW_TIMEOUT_MS) {
        viewModel.aiTaskState.first { it is AiTaskState.Error }
      }
      assertEquals("ATO 停車精準度在正負 5 公分內。", failedUtterance.originalText)
      assertEquals("en-US", failedUtterance.targetLang)
      assertTrue(failedUtterance.isTranslationFailed())

      // 3. User switches the session's foreign language from English (en-US) to Japanese (ja-JP)
      val japaneseOption = SUPPORTED_FOREIGN_LANGUAGES.first { it.code == "ja-JP" }
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.updateSessionLanguage(
          userId = aliceUid,
          sessionId = sessionId,
          foreignLangCode = japaneseOption.code,
          foreignLangLabel = japaneseOption.label,
        )
      }
      val updatedSessionJa =
        withTimeout(DEFAULT_TIMEOUT_MS) { repository.getSessionById(aliceUid, sessionId)!! }
      assertEquals("ja-JP", updatedSessionJa.foreignLangCode)

      // 4. Now retry translation on the failed en-US utterance while passing the updated ja-JP session
      shouldReturnBlankJson = false
      viewModel.retryTranslateUtterance(
        session = updatedSessionJa,
        utterance = failedUtterance,
        onSpeakTranslationReady = { text, lang -> spokenOutputs.add(text to lang) },
      )

      val retriedUtterances =
        withTimeout(FLOW_TIMEOUT_MS) {
          repository.observeUtterances(aliceUid, sessionId).first { list ->
            list.any {
              it.id == failedUtterance.id &&
                it.translatedText ==
                  "The ATO stopping accuracy is within plus or minus 5 cm (en-US)."
            }
          }
        }
      withTimeout(FLOW_TIMEOUT_MS) {
        viewModel.aiTaskState.first { it is AiTaskState.Idle }
      }
      assertEquals(1, retriedUtterances.size)
      assertEquals("en-US", retriedUtterances.first().targetLang)
      assertEquals("en-US", requestedTranslationLangCodes.last())
      assertEquals(1, spokenOutputs.size)
      assertEquals(
        "The ATO stopping accuracy is within plus or minus 5 cm (en-US)." to "en-US",
        spokenOutputs.first(),
      )
    }

  /**
   * Verifies:
   * 1. When the very first Firestore write (`addUtterance`) fails, the error state accurately
   *    states `"無法儲存逐字稿（原文尚未寫入雲端）"` and NEVER falsely claims `"原文已保存至逐字稿"`.
   * 2. Multi-device safety: When Phone A (`viewModelA`) and Phone B (`viewModelB`) are both viewing
   *    the same session, Phone B observing Phone A's in-flight `"⏳ [口譯處理中...]"` utterance
   *    does NOT overwrite it as failed in Firestore while Phone A is still translating.
   * 3. Reopen retry: When the app is reopened (`reopenedViewModel`) and an utterance is stuck in
   *    `"⏳ [口譯處理中...]"`, `canRetryTranslation` is immediately `true` so the user can retry it.
   */
  @Test
  fun initialWriteFailureAndOrphanedPendingUtterance_reportsAccurateErrorAndAllowsRetry() =
    runBlocking {
      val aliceUid = signInTestUser(ALICE_EMAIL)
      val repository = MetroVoiceRepository(firestore)

      val viewModel =
        MetroVoiceViewModel(
          repository = repository,
          currentUserId = aliceUid,
          autoSeedOnEmpty = false,
          customTranslationGenerator = { _, _, _ ->
            """{"translatedText": "Recovered translation.", "technicalTerms": []}"""
          },
        )

      // Part A: Attempt to record utterance to a non-existent session so the initial write fails
      val nonExistentSession =
        TestSession(
          id = "non_existent_session_999",
          userId = aliceUid,
          title = "Non-existent Session",
          metroLine = "板南線 (BL)",
          testCategory = "CBTC",
          foreignLangCode = "en-US",
          foreignLangLabel = "English (英文)",
        )
      viewModel.translateAndRecordUtterance(
        session = nonExistentSession,
        speakerRole = "FOREIGN_ENGINEER",
        spokenText = "This initial write will be rejected.",
        onSpeakTranslationReady = { _, _ -> },
      )

      val errorState =
        withTimeout(FLOW_TIMEOUT_MS) {
          viewModel.aiTaskState.first { it is AiTaskState.Error } as AiTaskState.Error
        }
      assertTrue(
        "Error message must state that transcript was NOT saved, got: ${errorState.message}",
        errorState.message.contains("無法儲存逐字稿（原文尚未寫入雲端）"),
      )
      assertFalse(
        "Error message must NOT falsely claim originalText was saved",
        errorState.message.contains("原文已保存"),
      )

      // Part B: Multi-device safety — Phone A is actively translating while Phone B is observing
      val validSessionId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSession(
            title = "Multi-Device & Reopen Session",
            metroLine = "環狀線 (Y)",
            testCategory = "CBTC",
            foreignLangCode = "en-US",
            foreignLangLabel = "English (英文)",
          )
        }
      val validSession =
        withTimeout(DEFAULT_TIMEOUT_MS) { repository.getSessionById(aliceUid, validSessionId)!! }

      val phoneATranslationGate = CompletableDeferred<String>()
      val phoneAViewModel =
        MetroVoiceViewModel(
          repository = repository,
          currentUserId = aliceUid,
          autoSeedOnEmpty = false,
          customTranslationGenerator = { _, _, _ -> phoneATranslationGate.await() },
        )
      val phoneBViewModel =
        MetroVoiceViewModel(
          repository = repository,
          currentUserId = aliceUid,
          autoSeedOnEmpty = false,
        )
      phoneAViewModel.selectSession(validSessionId)
      phoneBViewModel.selectSession(validSessionId)

      // Phone A starts translating; its utterance is saved as PENDING_TRANSLATION_PLACEHOLDER
      phoneAViewModel.translateAndRecordUtterance(
        session = validSession,
        speakerRole = "FOREIGN_ENGINEER",
        spokenText = "Phone A active translation sentence.",
        onSpeakTranslationReady = { _, _ -> },
      )

      // Phone B observes the pending utterance while Phone A is still translating
      val pendingSeenByPhoneB =
        withTimeout(FLOW_TIMEOUT_MS) {
          phoneBViewModel.utterancesState
            .first { state ->
              state is DataUiState.Success &&
                state.data.any { it.originalText == "Phone A active translation sentence." }
            }
            .let { (it as DataUiState.Success).data.first() }
        }
      assertTrue(
        "Phone B must see the pending translation without overwriting it as failed",
        pendingSeenByPhoneB.isTranslationPending(),
      )
      assertFalse(
        "Phone B must NOT corrupt Phone A's active translation into failed state",
        pendingSeenByPhoneB.isTranslationFailed(),
      )

      // Now Phone A finishes translating -> both devices see the completed translation!
      phoneATranslationGate.complete(
        """{"translatedText": "手機 A 順利完成的中文譯文。", "technicalTerms": []}"""
      )
      withTimeout(FLOW_TIMEOUT_MS) {
        phoneBViewModel.utterancesState.first { state ->
          state is DataUiState.Success &&
            state.data.any { it.translatedText == "手機 A 順利完成的中文譯文。" }
        }
      }

      // Part C: Simulate an utterance saved with PENDING_TRANSLATION_PLACEHOLDER before app termination
      val stuckUtteranceId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.addUtterance(
            sessionId = validSessionId,
            speakerRole = "FOREIGN_ENGINEER",
            speakerLabel = "外籍技師",
            sourceLang = "en-US",
            targetLang = "zh-TW",
            originalText = "Sentence interrupted by process kill.",
            translatedText = PENDING_TRANSLATION_PLACEHOLDER,
            technicalTerms = emptyList(),
          )
        }

      val reopenedViewModel =
        MetroVoiceViewModel(
          repository = repository,
          currentUserId = aliceUid,
          autoSeedOnEmpty = false,
          customTranslationGenerator = { _, _, _ ->
            """{"translatedText": "程序重啟後成功補譯的中文譯文。", "technicalTerms": []}"""
          },
        )
      reopenedViewModel.selectSession(validSessionId)

      val stuckUtteranceOnReopen =
        withTimeout(FLOW_TIMEOUT_MS) {
          reopenedViewModel.utterancesState
            .first { state ->
              state is DataUiState.Success && state.data.any { it.id == stuckUtteranceId }
            }
            .let { (it as DataUiState.Success).data.first { u -> u.id == stuckUtteranceId } }
        }
      assertTrue(
        "Stuck pending utterance on reopen must allow retry via canRetryTranslation",
        stuckUtteranceOnReopen.canRetryTranslation(
          reopenedViewModel.activeTranslatingUtteranceIds.value
        ),
      )

      val spoken = mutableListOf<Pair<String, String>>()
      reopenedViewModel.retryTranslateUtterance(
        session = validSession,
        utterance = stuckUtteranceOnReopen,
        onSpeakTranslationReady = { text, lang -> spoken.add(text to lang) },
      )

      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeUtterances(aliceUid, validSessionId).first { list ->
          list.any {
            it.id == stuckUtteranceId && it.translatedText == "程序重啟後成功補譯的中文譯文。"
          }
        }
      }
      withTimeout(FLOW_TIMEOUT_MS) {
        reopenedViewModel.aiTaskState.first { it is AiTaskState.Idle }
      }
      assertEquals(listOf("程序重啟後成功補譯的中文譯文。" to "zh-TW"), spoken)
    }

  /**
   * Verifies:
   * 1. Deleting a session deletes the parent session first (blocking concurrent writes via
   *    `firestore.rules`) and drains all child `utterances` in a loop.
   * 2. If the app process is terminated AFTER the parent session document is deleted—while
   *    child `utterances` have not finished being deleted—the durable `PendingDeleteSessionStore`
   *    retains the `sessionId`, and on restart (`observeSessions` / `resumePendingSessionDeletions`)
   *    automatically resumes and purges all remaining child `utterances`!
   */
  @Test
  fun deleteSession_deletesChildUtterancesSubcollectionAndBlocksConcurrentWrites() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val sharedPendingDeleteStore = InMemoryPendingDeleteSessionStore()
    val repository = MetroVoiceRepository(firestore, sharedPendingDeleteStore)

    val sessionId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.createSession(
          title = "待刪除測試場次",
          metroLine = "淡水信義線 (R)",
          testCategory = "供電系統與第三軌負載",
          foreignLangCode = "en-US",
          foreignLangLabel = "English (英文)",
        )
      }

    withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.addUtterance(
        sessionId = sessionId,
        speakerRole = "FOREIGN_ENGINEER",
        speakerLabel = "外籍技師",
        sourceLang = "en-US",
        targetLang = "zh-TW",
        originalText = "Utterance 1 to delete",
        translatedText = "待刪除語句 1",
        technicalTerms = emptyList(),
      )
      repository.addUtterance(
        sessionId = sessionId,
        speakerRole = "LOCAL_ENGINEER",
        speakerLabel = "我方工程師",
        sourceLang = "zh-TW",
        targetLang = "en-US",
        originalText = "待刪除語句 2",
        translatedText = "Utterance 2 to delete",
        technicalTerms = emptyList(),
      )
    }

    // Simulate process crash right after parent session is deleted (before utterances are deleted)
    var postParentDeleteWriteRejected = false
    try {
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.deleteSession(
          userId = aliceUid,
          sessionId = sessionId,
          onAfterFirstSubcollectionQueryForTest = {
            // 1. Verify another device cannot write new utterances once parent session is deleted
            try {
              repository.addUtterance(
                sessionId = sessionId,
                speakerRole = "FOREIGN_ENGINEER",
                speakerLabel = "另一台裝置",
                sourceLang = "en-US",
                targetLang = "zh-TW",
                originalText = "Concurrent write from second device",
                translatedText = "第二台裝置同時寫入",
                technicalTerms = emptyList(),
              )
            } catch (_: Exception) {
              postParentDeleteWriteRejected = true
            }
            // 2. Simulate app termination mid-delete before child utterances are purged
            throw RuntimeException("Simulated process termination before utterances batch delete")
          },
        )
      }
      fail("Expected simulated interruption exception")
    } catch (_: RuntimeException) {}

    assertTrue(
      "Concurrent utterance creation after parent session delete must be rejected",
      postParentDeleteWriteRejected,
    )
    assertTrue(
      "Interrupted session deletion must remain recorded in PendingDeleteSessionStore",
      sharedPendingDeleteStore.getPendingDeleteSessionIds(aliceUid).contains(sessionId),
    )

    // Utterances are still in Firestore prior to restart resume
    val remainingBeforeResume =
      withTimeout(FLOW_TIMEOUT_MS) { repository.observeUtterances(aliceUid, sessionId).first() }
    assertEquals(2, remainingBeforeResume.size)

    // Simulate app restart with a new Repository instance sharing the durable PendingDeleteSessionStore
    val restartedRepository = MetroVoiceRepository(firestore, sharedPendingDeleteStore)
    withTimeout(FLOW_TIMEOUT_MS) {
      restartedRepository.observeSessions(aliceUid).first()
    }

    // Verify restart automatically resumed and wiped all leftover child utterances!
    val afterResumeUtterances =
      withTimeout(FLOW_TIMEOUT_MS) {
        restartedRepository.observeUtterances(aliceUid, sessionId).first()
      }
    assertTrue(
      "All leftover child utterances must be purged when pending delete resumes on restart",
      afterResumeUtterances.isEmpty(),
    )
    assertTrue(
      "PendingDeleteSessionStore must be cleared once cleanup completes",
      sharedPendingDeleteStore.getPendingDeleteSessionIds(aliceUid).isEmpty(),
    )
  }

  @Test
  fun getSessionById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val repository = MetroVoiceRepository(firestore)
    val sessionId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.createSession(
          title = "Alice Private MRT Test",
          metroLine = "板南線 (BL)",
          testCategory = "CBTC 號誌與聯鎖測試",
          foreignLangCode = "en-US",
          foreignLangLabel = "English (英文)",
        )
      }

    signInTestUser(BOB_EMAIL)
    try {
      withTimeout(DEFAULT_TIMEOUT_MS) { repository.getSessionById(aliceUid, sessionId) }
      fail("Expected FirebaseFirestoreException PERMISSION_DENIED for cross-user access")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  @Test
  fun observeSessions_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    auth.signOut()
    val repository = MetroVoiceRepository(firestore)

    try {
      withTimeout(FLOW_TIMEOUT_MS) { repository.observeSessions(aliceUid).first() }
      fail("Expected exception for unauthenticated caller")
    } catch (e: Exception) {
      val firestoreException = e.unwrapFirestoreException()
      assertNotNull("Expected nested FirebaseFirestoreException, got $e", firestoreException)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
    }
  }

  private companion object {
    const val ALICE_EMAIL = "alice@metrovoice.test"
    const val BOB_EMAIL = "bob@metrovoice.test"
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 3000L
  }
}
