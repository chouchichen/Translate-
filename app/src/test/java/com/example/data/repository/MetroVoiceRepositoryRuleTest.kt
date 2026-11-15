package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.SUPPORTED_FOREIGN_LANGUAGES
import com.example.data.model.TestSession
import com.example.speech.ListeningChannel
import com.example.speech.RecordingSessionContext
import com.example.speech.VoiceBridgeManager
import com.example.viewmodel.AiTaskState
import com.example.viewmodel.DataUiState
import com.example.viewmodel.MetroVoiceViewModel
import com.example.viewmodel.PENDING_TRANSLATION_PLACEHOLDER
import com.example.viewmodel.canRetryTranslation
import com.example.viewmodel.isTranslationFailed
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
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
   * Verifies natural turn-taking:
   * Foreign engineer finishes speaking (`FOREIGN_TO_LOCAL`), and local engineer immediately
   * presses the other microphone (`LOCAL_TO_FOREIGN`) BEFORE the first turn's `onResults` arrives.
   * When the first turn's delayed `onResults` arrives:
   * 1. It is NOT cancelled or dropped.
   * 2. It uses Turn 1's pinned `FOREIGN_ENGINEER` (`en-US` -> `zh-TW`) context, NOT Turn 2's.
   * 3. It does NOT destroy or clear Turn 2's active recognizer (`LOCAL_TO_FOREIGN`).
   * 4. When Turn 2's `onResults` arrives afterward, Turn 2 is recorded as `LOCAL_ENGINEER` (`zh-TW` -> `en-US`).
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

      val spokenOutputs = mutableListOf<Pair<String, String>>()

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
          customSpeakHandler = { text, lang -> spokenOutputs.add(text to lang) },
        )

      // Turn 1: Foreign engineer starts speaking (en-US -> zh-TW)
      voiceBridge.startListening(
        channel = ListeningChannel.FOREIGN_TO_LOCAL,
        session = session,
        forceEnableInTest = true,
      )
      assertEquals(ListeningChannel.FOREIGN_TO_LOCAL, voiceBridge.state.value.activeChannel)

      // Turn 2: Local engineer immediately presses the second mic (zh-TW -> en-US) BEFORE Turn 1's onResults arrives
      voiceBridge.startListening(
        channel = ListeningChannel.LOCAL_TO_FOREIGN,
        session = session,
        forceEnableInTest = true,
      )
      assertEquals(ListeningChannel.LOCAL_TO_FOREIGN, voiceBridge.state.value.activeChannel)

      // Delayed onResults for Turn 1 arrives while Turn 2 is active
      voiceBridge.deliverRecognitionResultsForTest("Beacon handover latency is 12 ms.")

      // Turn 2 must STILL be the active channel on VoiceBridgeManager!
      assertEquals(
        "Turn 1 delayed onResults must not clear Turn 2 active channel",
        ListeningChannel.LOCAL_TO_FOREIGN,
        voiceBridge.state.value.activeChannel,
      )

      // Now Turn 2 finishes and its onResults arrives
      voiceBridge.finishListening()
      voiceBridge.deliverRecognitionResultsForTest("收到，應答器延遲符合規範。")

      assertEquals(ListeningChannel.NONE, voiceBridge.state.value.activeChannel)

      // Wait for both utterances to finish translating and saving in Firestore
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

      assertTrue(
        spokenOutputs.contains("【中譯】Beacon handover latency is 12 ms." to "zh-TW")
      )
      assertTrue(
        spokenOutputs.contains("[EN] 收到，應答器延遲符合規範。" to "en-US")
      )
    }

  /**
   * Verifies:
   * 1. Blank `translatedText` is rejected and marks the utterance failed.
   * 2. If the user changes the session language from English (`en-US`) to Japanese (`ja-JP`)
   *    before clicking Retry on a failed `zh-TW -> en-US` utterance, retry uses the utterance's
   *    own pinned `en-US` language for both AI translation and TTS speech output (never mixing
   *    Japanese translation text with an `en-US` language tag and `en-US` TTS voice).
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
   * 1. When the very first Firestore write (`addUtterance`) fails (e.g. session does not exist),
   *    the error state accurately states `"無法儲存逐字稿（原文尚未寫入雲端）"` and NEVER falsely
   *    claims `"原文已保存至逐字稿"`.
   * 2. When an utterance was saved with `PENDING_TRANSLATION_PLACEHOLDER` (`"⏳ [口譯處理中...]"`)
   *    and the app was terminated and reopened (new `MetroVoiceViewModel`), the stuck pending
   *    utterance is immediately retryable (`canRetryTranslation == true`), automatically marked
   *    as interrupted (`⚠️`), and can be retried in place.
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

      // Part B: Simulate an utterance saved with PENDING_TRANSLATION_PLACEHOLDER before app termination
      val validSessionId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSession(
            title = "Interrupted Pending Session",
            metroLine = "環狀線 (Y)",
            testCategory = "CBTC",
            foreignLangCode = "en-US",
            foreignLangLabel = "English (英文)",
          )
        }
      val validSession =
        withTimeout(DEFAULT_TIMEOUT_MS) { repository.getSessionById(aliceUid, validSessionId)!! }

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

      // Create a fresh ViewModel (simulating app restart) and select the session
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

      // Verify the orphaned pending utterance is automatically recovered to retryable failed state
      val recoveredUtterance =
        withTimeout(FLOW_TIMEOUT_MS) {
          reopenedViewModel.utterancesState
            .first { state ->
              state is DataUiState.Success &&
                state.data.any {
                  it.id == stuckUtteranceId &&
                    it.canRetryTranslation(reopenedViewModel.activeTranslatingUtteranceIds.value) &&
                    it.isTranslationFailed()
                }
            }
            .let { (it as DataUiState.Success).data.first { u -> u.id == stuckUtteranceId } }
        }
      assertTrue(recoveredUtterance.canRetryTranslation())

      // Retry the recovered utterance and verify it succeeds
      val spoken = mutableListOf<Pair<String, String>>()
      reopenedViewModel.retryTranslateUtterance(
        session = validSession,
        utterance = recoveredUtterance,
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
   * 1. Deleting a session deletes all child documents in `utterances` across multiple passes
   *    even if another document was present after the first query.
   * 2. Once `deleteSession` deletes the parent session, any concurrent attempt by another
   *    device/client to add a new utterance to that session is rejected by Firestore.
   */
  @Test
  fun deleteSession_deletesChildUtterancesSubcollectionAndBlocksConcurrentWrites() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val repository = MetroVoiceRepository(firestore)

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

    val beforeDelete =
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeUtterances(aliceUid, sessionId).first { it.size == 2 }
      }
    assertEquals(2, beforeDelete.size)

    var postParentDeleteWriteRejected = false
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.deleteSession(
        userId = aliceUid,
        sessionId = sessionId,
        onAfterFirstSubcollectionQueryForTest = {
          // Simulate another device attempting to write a new utterance during session deletion:
          // Because deleteSession deletes the parent session first and firestore.rules requires
          // exists(.../sessions/$(sessionId)), this concurrent write is rejected!
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
        },
      )
    }
    assertTrue(
      "Concurrent utterance creation during/after session deletion must be rejected",
      postParentDeleteWriteRejected,
    )

    val afterDeleteUtterances =
      withTimeout(FLOW_TIMEOUT_MS) { repository.observeUtterances(aliceUid, sessionId).first() }
    assertTrue(
      "All child utterances must be deleted when parent session is deleted",
      afterDeleteUtterances.isEmpty(),
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
