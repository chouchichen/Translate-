package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.TestSession
import com.example.data.model.Utterance
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class MetroVoiceRepository(private val db: FirebaseFirestore) {

  constructor(
    context: Context
  ) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private val auth = Firebase.auth

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("請先使用 Google 帳號登入後再存取雲端資料。")
  }

  fun observeSessions(userId: String): Flow<List<TestSession>> = flow {
    val path = "users/$userId/sessions"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("sessions")
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(TestSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  suspend fun getSessionById(userId: String, sessionId: String): TestSession? {
    val docRef = db.collection("users").document(userId).collection("sessions").document(sessionId)
    return try {
      val snapshot = docRef.get().await()
      snapshot.toObject(TestSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      throw e
    }
  }

  suspend fun createSession(
    title: String,
    metroLine: String,
    testCategory: String,
    foreignLangCode: String,
    foreignLangLabel: String,
    initialSummary: String = "尚未產出會議摘要，請於雙向翻譯對話後點擊「AI 即時產出會議紀錄」。",
  ): String {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid).collection("sessions").document()
    val payload =
      mapOf(
        "userId" to uid,
        "title" to title.trim().take(200).ifEmpty { "捷運測試會議" },
        "metroLine" to metroLine.trim().take(100).ifEmpty { "板南線 (BL)" },
        "testCategory" to testCategory.trim().take(100).ifEmpty { "CBTC 號誌與聯鎖測試" },
        "foreignLangCode" to foreignLangCode.trim().take(20).ifEmpty { "en-US" },
        "foreignLangLabel" to foreignLangLabel.trim().take(60).ifEmpty { "English (英文)" },
        "utteranceCount" to 0L,
        "minutesSummary" to initialSummary.take(5000),
        "minutesKeyPoints" to emptyList<String>(),
        "minutesActionItems" to emptyList<String>(),
        "minutesTestVerdict" to "進行中 (In Progress)",
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    try {
      docRef.set(payload).await()
      return docRef.id
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      throw e
    }
  }

  suspend fun updateSessionMinutes(
    userId: String,
    sessionId: String,
    summary: String,
    keyPoints: List<String>,
    actionItems: List<String>,
    testVerdict: String,
  ) {
    val docRef = db.collection("users").document(userId).collection("sessions").document(sessionId)
    val updates =
      mapOf(
        "minutesSummary" to summary.take(5000),
        "minutesKeyPoints" to keyPoints.take(30).map { it.take(500) },
        "minutesActionItems" to actionItems.take(30).map { it.take(500) },
        "minutesTestVerdict" to testVerdict.take(100).ifEmpty { "進行中 (In Progress)" },
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    try {
      docRef.update(updates).await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      throw e
    }
  }

  suspend fun updateSessionLanguage(
    userId: String,
    sessionId: String,
    foreignLangCode: String,
    foreignLangLabel: String,
  ) {
    val docRef = db.collection("users").document(userId).collection("sessions").document(sessionId)
    val updates =
      mapOf(
        "foreignLangCode" to foreignLangCode.take(20),
        "foreignLangLabel" to foreignLangLabel.take(60),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    try {
      docRef.update(updates).await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      throw e
    }
  }

  /**
   * Deletes the parent `TestSession` document first (which immediately blocks any concurrent
   * device from adding new utterances under this session via Firestore security rules and
   * atomic batch checks), and then repeatedly queries and batch-deletes the `utterances`
   * subcollection in a loop until a fresh query confirms zero child documents remain.
   */
  suspend fun deleteSession(
    userId: String,
    sessionId: String,
    onAfterFirstSubcollectionQueryForTest: (suspend () -> Unit)? = null,
  ) {
    val sessionRef =
      db.collection("users").document(userId).collection("sessions").document(sessionId)
    val utterancesCollection = sessionRef.collection("utterances")
    try {
      // 1. Delete parent session first so any concurrent utterance creation is blocked immediately
      sessionRef.delete().await()

      // 2. Repeatedly drain the utterances subcollection until a query returns empty
      var pass = 0
      while (pass < 20) {
        val utterancesSnapshot = utterancesCollection.get().await()
        if (pass == 0 && onAfterFirstSubcollectionQueryForTest != null) {
          onAfterFirstSubcollectionQueryForTest.invoke()
        }
        val docs = utterancesSnapshot.documents
        if (docs.isEmpty()) {
          if (pass == 0 && onAfterFirstSubcollectionQueryForTest != null) {
            pass++
            continue
          }
          break
        }
        for (chunk in docs.chunked(450)) {
          val batch = db.batch()
          for (doc in chunk) {
            batch.delete(doc.reference)
          }
          batch.commit().await()
        }
        pass++
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, sessionRef.path)
      throw e
    }
  }

  fun observeUtterances(userId: String, sessionId: String): Flow<List<Utterance>> = flow {
    val path = "users/$userId/sessions/$sessionId/utterances"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("sessions")
        .document(sessionId)
        .collection("utterances")
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(Utterance::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedBy { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  suspend fun addUtterance(
    sessionId: String,
    speakerRole: String,
    speakerLabel: String,
    sourceLang: String,
    targetLang: String,
    originalText: String,
    translatedText: String,
    technicalTerms: List<String>,
    isPinnedToMinutes: Boolean = false,
    incrementSessionCount: Boolean = true,
  ): String {
    val uid = requireUserId()
    val sessionRef = db.collection("users").document(uid).collection("sessions").document(sessionId)
    val uttRef = sessionRef.collection("utterances").document()
    val payload =
      mapOf(
        "userId" to uid,
        "sessionId" to sessionId,
        "speakerRole" to
          if (speakerRole == "LOCAL_ENGINEER") "LOCAL_ENGINEER" else "FOREIGN_ENGINEER",
        "speakerLabel" to speakerLabel.trim().take(100).ifEmpty { "Engineer" },
        "sourceLang" to sourceLang.trim().take(20).ifEmpty { "en-US" },
        "targetLang" to targetLang.trim().take(20).ifEmpty { "zh-TW" },
        "originalText" to originalText.trim().take(2000).ifEmpty { "-" },
        "translatedText" to translatedText.trim().take(2000).ifEmpty { "-" },
        "technicalTerms" to technicalTerms.take(15).map { it.take(200) },
        "isPinnedToMinutes" to isPinnedToMinutes,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    try {
      if (incrementSessionCount) {
        val batch = db.batch()
        batch.set(uttRef, payload)
        batch.update(
          sessionRef,
          mapOf(
            "utteranceCount" to FieldValue.increment(1L),
            "updatedAt" to FieldValue.serverTimestamp(),
          ),
        )
        batch.commit().await()
      } else {
        uttRef.set(payload).await()
      }
      return uttRef.id
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, uttRef.path)
      throw e
    }
  }

  suspend fun updateUtteranceTranslation(
    userId: String,
    sessionId: String,
    utteranceId: String,
    translatedText: String,
    technicalTerms: List<String>,
  ) {
    val uttRef =
      db.collection("users")
        .document(userId)
        .collection("sessions")
        .document(sessionId)
        .collection("utterances")
        .document(utteranceId)
    val cleanTranslation =
      translatedText.trim().take(2000).ifEmpty {
        throw IllegalArgumentException("Translated text must not be blank")
      }
    try {
      uttRef
        .update(
          mapOf(
            "translatedText" to cleanTranslation,
            "technicalTerms" to technicalTerms.take(15).map { it.take(200) },
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
        .await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, uttRef.path)
      throw e
    }
  }

  suspend fun togglePinUtterance(
    userId: String,
    sessionId: String,
    utteranceId: String,
    newPinnedState: Boolean,
  ) {
    val uttRef =
      db.collection("users")
        .document(userId)
        .collection("sessions")
        .document(sessionId)
        .collection("utterances")
        .document(utteranceId)
    try {
      uttRef
        .update(
          mapOf(
            "isPinnedToMinutes" to newPinnedState,
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
        .await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, uttRef.path)
      throw e
    }
  }
}
