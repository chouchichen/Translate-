package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
