package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.TestSession
import com.example.speech.ListeningChannel
import com.example.speech.RecordingSessionContext
import com.example.speech.VoiceBridgeManager
import com.example.viewmodel.AiTaskState
import com.example.viewmodel.MetroVoiceViewModel
import com.example.viewmodel.isTranslationFailed
import com.google.firebase.firestore.FirebaseFirestoreException
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
   * Verifies Fixes #1, #2, and #3:
   * - Starting mic on Session A (`en-US`), then switching UI to Session B (`ja-JP`),
   * - Calling `finishListening()` (tapping stop/finish button without cancelling recognition),
   * - Receiving delayed `onResults` from `SpeechRecognizer`,
   * - Saves the utterance to Session A with Session A's language (`en-US` -> `zh-TW`),
   *   AND invokes TTS `speakText` with the translated output!
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
   * Verifies Fixes #4 and #5:
   * - If the AI model returns a blank `translatedText` (or fails), it is rejected as an error
   *   (never silently substituting `originalText` as `translatedText`),
   * - The recognized `originalText` is STILL immediately persisted in Firestore with a retryable status,
   * - Calling `retryTranslateUtterance` updates the existing utterance in place and triggers TTS.
   */
  @Test
  fun translationFailureAndBlankTranslation_preservesOriginalTextAndSupportsRetry() = runBlocking {
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

    val viewModel =
      MetroVoiceViewModel(
        repository = repository,
        currentUserId = aliceUid,
        autoSeedOnEmpty = false,
        customTranslationGenerator = { _, _, _ ->
          if (shouldReturnBlankJson) {
            // Model returns valid JSON but blank translatedText (Bug #5 scenario)
            """{"translatedText": "   ", "technicalTerms": []}"""
          } else {
            """
            {
              "translatedText": "The ATO stopping accuracy is within plus or minus 5 cm.",
              "technicalTerms": ["ATO: Automatic Train Operation"]
            }
            """.trimIndent()
          }
        },
      )

    // 1. Trigger local-to-foreign translation while model returns blank translatedText
    viewModel.translateAndRecordUtterance(
      session = session,
      speakerRole = "LOCAL_ENGINEER",
      spokenText = "ATO 停車精準度在正負 5 公分內。",
      onSpeakTranslationReady = { text, lang -> spokenOutputs.add(text to lang) },
    )

    // 2. Verify originalText was preserved in Firestore and marked as failed (NOT silently filled with originalText)
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
    assertTrue(failedUtterance.isTranslationFailed())
    assertFalse(
      "Blank translation must not copy originalText into translatedText",
      failedUtterance.translatedText == failedUtterance.originalText,
    )
    assertTrue("Failed translation must not trigger TTS", spokenOutputs.isEmpty())
    assertTrue(viewModel.aiTaskState.value is AiTaskState.Error)

    // 3. Now retry translation on the preserved utterance
    shouldReturnBlankJson = false
    viewModel.retryTranslateUtterance(
      session = session,
      utterance = failedUtterance,
      onSpeakTranslationReady = { text, lang -> spokenOutputs.add(text to lang) },
    )

    val retriedUtterances =
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeUtterances(aliceUid, sessionId).first { list ->
          list.any {
            it.id == failedUtterance.id &&
              it.translatedText == "The ATO stopping accuracy is within plus or minus 5 cm."
          }
        }
      }
    withTimeout(FLOW_TIMEOUT_MS) {
      viewModel.aiTaskState.first { it is AiTaskState.Idle }
    }
    assertEquals("Should update existing utterance in place without duplicating", 1, retriedUtterances.size)
    assertEquals(1, spokenOutputs.size)
    assertEquals(
      "The ATO stopping accuracy is within plus or minus 5 cm." to "en-US",
      spokenOutputs.first(),
    )
  }

  /**
   * Verifies Fix #6:
   * Deleting a session also deletes all child documents in its `utterances` subcollection.
   */
  @Test
  fun deleteSession_deletesChildUtterancesSubcollection() = runBlocking {
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

    withTimeout(DEFAULT_TIMEOUT_MS) { repository.deleteSession(aliceUid, sessionId) }

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
