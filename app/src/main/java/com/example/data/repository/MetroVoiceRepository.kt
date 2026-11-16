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
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Durable journal of session IDs whose deletion is in progress.
 * Backed by Android [ android.content.SharedPreferences ] by default so that if the app
 * process is terminated after the parent `TestSession` document is deleted—but before all
 * child `utterances` have been purged—the next app launch automatically resumes and finishes
 * deleting the remaining child `utterances`.
 */
interface PendingDeleteSessionStore {
  fun markPendingDelete(userId: String, sessionId: String)
  fun clearPendingDelete(userId: String, sessionId: String)
  fun getPendingDeleteSessionIds(userId: String): Set<String>
}

class SharedPrefsPendingDeleteSessionStore(context: Context) : PendingDeleteSessionStore {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  @Synchronized
  override fun markPendingDelete(userId: String, sessionId: String) {
    val current = prefs.getStringSet(keyForUser(userId), emptySet())?.toMutableSet() ?: mutableSetOf()
    current.add(sessionId)
    prefs.edit().putStringSet(keyForUser(userId), current).commit()
  }

  @Synchronized
  override fun clearPendingDelete(userId: String, sessionId: String) {
    val current = prefs.getStringSet(keyForUser(userId), emptySet())?.toMutableSet() ?: mutableSetOf()
    if (current.remove(sessionId)) {
      prefs.edit().putStringSet(keyForUser(userId), current).commit()
    }
  }

  @Synchronized
  override fun getPendingDeleteSessionIds(userId: String): Set<String> {
    return prefs.getStringSet(keyForUser(userId), emptySet())?.toSet() ?: emptySet()
  }

  private fun keyForUser(userId: String): String = "pending_delete_sessions_$userId"

  companion object {
    const val PREFS_NAME = "metro_voice_pending_deletes"
  }
}

class InMemoryPendingDeleteSessionStore : PendingDeleteSessionStore {
  private val map = ConcurrentHashMap<String, MutableSet<String>>()

  @Synchronized
  override fun markPendingDelete(userId: String, sessionId: String) {
    map.getOrPut(userId) { mutableSetOf() }.add(sessionId)
  }

  @Synchronized
  override fun clearPendingDelete(userId: String, sessionId: String) {
    map[userId]?.remove(sessionId)
  }

  @Synchronized
  override fun getPendingDeleteSessionIds(userId: String): Set<String> {
    return map[userId]?.toSet() ?: emptySet()
  }
}

/**
 * Durable journal of utterance IDs that THIS device started translating.
 * Allows the same device to immediately detect and retry an utterance that was interrupted
 * by a local process termination, while preventing a second device (Phone B) from mistaking
 * Phone A's live in-flight translation (< 65s) for an interrupted one.
 */
interface PendingTranslationStore {
  fun markInFlightTranslation(userId: String, utteranceId: String)
  fun clearInFlightTranslation(userId: String, utteranceId: String)
  fun getInterruptedTranslationIds(userId: String): Set<String>
}

class SharedPrefsPendingTranslationStore(context: Context) : PendingTranslationStore {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  @Synchronized
  override fun markInFlightTranslation(userId: String, utteranceId: String) {
    val current = prefs.getStringSet(keyForUser(userId), emptySet())?.toMutableSet() ?: mutableSetOf()
    current.add(utteranceId)
    prefs.edit().putStringSet(keyForUser(userId), current).commit()
  }

  @Synchronized
  override fun clearInFlightTranslation(userId: String, utteranceId: String) {
    val current = prefs.getStringSet(keyForUser(userId), emptySet())?.toMutableSet() ?: mutableSetOf()
    if (current.remove(utteranceId)) {
      prefs.edit().putStringSet(keyForUser(userId), current).commit()
    }
  }

  @Synchronized
  override fun getInterruptedTranslationIds(userId: String): Set<String> {
    return prefs.getStringSet(keyForUser(userId), emptySet())?.toSet() ?: emptySet()
  }

  private fun keyForUser(userId: String): String = "pending_translations_$userId"

  companion object {
    const val PREFS_NAME = "metro_voice_pending_translations"
  }
}

class InMemoryPendingTranslationStore : PendingTranslationStore {
  private val map = ConcurrentHashMap<String, MutableSet<String>>()

  @Synchronized
  override fun markInFlightTranslation(userId: String, utteranceId: String) {
    map.getOrPut(userId) { mutableSetOf() }.add(utteranceId)
  }

  @Synchronized
  override fun clearInFlightTranslation(userId: String, utteranceId: String) {
    map[userId]?.remove(utteranceId)
  }

  @Synchronized
  override fun getInterruptedTranslationIds(userId: String): Set<String> {
    return map[userId]?.toSet() ?: emptySet()
  }
}

private fun resolveApplicationContext(db: FirebaseFirestore): Context {
  return try {
    val clazz = Class.forName("android.app.ActivityThread")
    val currentApp = clazz.getMethod("currentApplication").invoke(null) as? Context
    currentApp?.applicationContext ?: db.app.applicationContext
  } catch (_: Throwable) {
    db.app.applicationContext
  }
}

class MetroVoiceRepository(
  private val db: FirebaseFirestore,
  private val pendingDeleteStore: PendingDeleteSessionStore =
    SharedPrefsPendingDeleteSessionStore(resolveApplicationContext(db)),
  private val pendingTranslationStore: PendingTranslationStore =
    SharedPrefsPendingTranslationStore(resolveApplicationContext(db)),
) {

  constructor(
    context: Context,
    db: FirebaseFirestore =
      FirebaseFirestore.getInstance(
        context.applicationContext.getString(R.string.firestore_database_id)
      ),
  ) : this(
    db = db,
    pendingDeleteStore = SharedPrefsPendingDeleteSessionStore(context.applicationContext),
    pendingTranslationStore = SharedPrefsPendingTranslationStore(context.applicationContext),
  )

  private val auth = Firebase.auth

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("請先使用 Google 帳號登入後再存取雲端資料。")
  }

  fun getPendingDeleteSessionIds(userId: String): Set<String> =
    pendingDeleteStore.getPendingDeleteSessionIds(userId)

  fun markInFlightTranslation(userId: String, utteranceId: String) {
    pendingTranslationStore.markInFlightTranslation(userId, utteranceId)
  }

  fun clearInFlightTranslation(userId: String, utteranceId: String) {
    pendingTranslationStore.clearInFlightTranslation(userId, utteranceId)
  }

  fun getInterruptedTranslationIds(userId: String): Set<String> =
    pendingTranslationStore.getInterruptedTranslationIds(userId)

  /**
   * Resumes and completes any session deletions that were interrupted before their
   * `utterances` subcollection finished being purged.
   */
  suspend fun resumePendingSessionDeletions(userId: String) {
    val pendingSessionIds = pendingDeleteStore.getPendingDeleteSessionIds(userId)
    if (pendingSessionIds.isEmpty()) return

    for (sessionId in pendingSessionIds) {
      try {
        deleteSession(userId, sessionId)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Keep in pendingDeleteStore so a subsequent retry can finish cleaning up
      }
    }
  }

  fun observeSessions(userId: String): Flow<List<TestSession>> = flow {
    // Automatically resume any interrupted session deletions on startup
    try {
      if (auth.currentUser?.uid == userId) {
        resumePendingSessionDeletions(userId)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {}

    val path = "users/$userId/sessions"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("sessions")
        .snapshots()
        .map { snapshot ->
          val pendingDeletes = pendingDeleteStore.getPendingDeleteSessionIds(userId)
          snapshot
            .toObjects(TestSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .filter { it.id !in pendingDeletes }
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
   * Crash-resilient and offline-safe session deletion:
   * 1. Records [sessionId] in [pendingDeleteStore] (`SharedPreferences`) BEFORE deleting parent.
   * 2. Deletes the parent `TestSession` document so Firestore security rules (`exists(...)`)
   *    and atomic batch checks immediately block any other device from adding new utterances.
   * 3. Repeatedly queries and batch-deletes the `utterances` subcollection using [Source.SERVER]
   *    in a loop until a fresh [Source.SERVER] query confirms zero child documents remain on the
   *    cloud server (never trusting a potentially empty offline local cache).
   * 4. Clears [sessionId] from [pendingDeleteStore] ONLY after [Source.SERVER] confirms 0 child
   *    `utterances` remain; if offline, throws `FirebaseFirestoreException(UNAVAILABLE)` so the
   *    pending delete marker stays in `SharedPreferences` and resumes on next launch.
   */
  suspend fun deleteSession(
    userId: String,
    sessionId: String,
    onAfterFirstSubcollectionQueryForTest: (suspend () -> Unit)? = null,
    simulateOfflineOnFinalServerCheckForTest: Boolean = false,
  ) {
    val sessionRef =
      db.collection("users").document(userId).collection("sessions").document(sessionId)
    val utterancesCollection = sessionRef.collection("utterances")
    try {
      // 1. Persist pending delete intent to durable SharedPreferences store before deleting parent
      pendingDeleteStore.markPendingDelete(userId, sessionId)

      // 2. Delete parent session first so any concurrent utterance creation is blocked immediately
      sessionRef.delete().await()

      // 3. Repeatedly drain the utterances subcollection from Source.SERVER until 0 docs remain
      var pass = 0
      var verifiedEmptyOnServer = false
      while (pass < 20) {
        val utterancesSnapshot = utterancesCollection.get(Source.SERVER).await()
        if (pass == 0 && onAfterFirstSubcollectionQueryForTest != null) {
          onAfterFirstSubcollectionQueryForTest.invoke()
        }
        val docs = utterancesSnapshot.documents
        if (docs.isEmpty()) {
          if (pass == 0 && onAfterFirstSubcollectionQueryForTest != null) {
            pass++
            continue
          }
          if (simulateOfflineOnFinalServerCheckForTest) {
            throw FirebaseFirestoreException(
              "Simulated offline failure during Source.SERVER final verification",
              FirebaseFirestoreException.Code.UNAVAILABLE,
            )
          }
          verifiedEmptyOnServer = true
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

      if (!verifiedEmptyOnServer) {
        if (simulateOfflineOnFinalServerCheckForTest) {
          throw FirebaseFirestoreException(
            "Simulated offline failure during Source.SERVER final verification",
            FirebaseFirestoreException.Code.UNAVAILABLE,
          )
        }
        val finalServerSnapshot = utterancesCollection.get(Source.SERVER).await()
        if (!finalServerSnapshot.isEmpty) {
          throw IllegalStateException("Subcollection still has remaining documents on server")
        }
      }

      // 4. Clear pending delete entry only after Source.SERVER confirms zero child utterances remain
      pendingDeleteStore.clearPendingDelete(userId, sessionId)
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
    translationAttemptId: String = UUID.randomUUID().toString(),
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
        "translationAttemptId" to translationAttemptId.trim().take(128).ifEmpty { "attempt_init" },
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

  /**
   * Atomically writes the completed translation inside a Firestore transaction ONLY IF the
   * document is still in the pending (`⏳`) state AND its `translationAttemptId` matches
   * [expectedAttemptId].
   * Guarantees that even if two devices somehow raced, only the device holding the active
   * [expectedAttemptId] can commit its translation.
   */
  private fun buildHealedUtterancePayloadFromCache(
    cached: DocumentSnapshot,
    userId: String,
    sessionId: String,
    translatedText: String,
    technicalTerms: List<String>,
    translationAttemptId: String,
  ): Map<String, Any> {
    val role = cached.getString("speakerRole")
    val validRole = if (role == "LOCAL_ENGINEER") "LOCAL_ENGINEER" else "FOREIGN_ENGINEER"
    val label = cached.getString("speakerLabel")?.trim()?.take(100).orEmpty().ifEmpty { "Engineer" }
    val sourceLang = cached.getString("sourceLang")?.trim()?.take(20).orEmpty().ifEmpty { "en-US" }
    val targetLang = cached.getString("targetLang")?.trim()?.take(20).orEmpty().ifEmpty { "zh-TW" }
    val originalText =
      cached.getString("originalText")?.trim()?.take(2000).orEmpty().ifEmpty { "..." }
    val isPinned = cached.getBoolean("isPinnedToMinutes") ?: false
    val createdAt = cached.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()
    val payload =
      mutableMapOf<String, Any>(
        "userId" to userId,
        "sessionId" to sessionId,
        "speakerRole" to validRole,
        "speakerLabel" to label,
        "sourceLang" to sourceLang,
        "targetLang" to targetLang,
        "originalText" to originalText,
        "translatedText" to translatedText.trim().take(2000).ifEmpty { "..." },
        "technicalTerms" to technicalTerms.take(15).map { it.take(200) },
        "isPinnedToMinutes" to isPinned,
        "createdAt" to createdAt,
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    if (translationAttemptId.isNotBlank()) {
      payload["translationAttemptId"] = translationAttemptId.trim().take(128)
    }
    return payload
  }

  /**
   * Atomically writes the completed translation inside a Firestore transaction ONLY IF the
   * document is still in the pending (`⏳`) state AND its `translationAttemptId` matches
   * [expectedAttemptId].
   * Guarantees that even if two devices somehow raced, only the device holding the active
   * [expectedAttemptId] can commit its translation.
   */
  suspend fun completeUtteranceTranslationIfAttemptMatches(
    userId: String,
    sessionId: String,
    utteranceId: String,
    expectedAttemptId: String,
    translatedText: String,
    technicalTerms: List<String>,
  ): Boolean {
    val sessionRef =
      db.collection("users").document(userId).collection("sessions").document(sessionId)
    val uttRef = sessionRef.collection("utterances").document(utteranceId)
    val cleanTranslation =
      translatedText.trim().take(2000).ifEmpty {
        throw IllegalArgumentException("Translated text must not be blank")
      }
    val cachedSnapshot =
      try {
        uttRef.get(Source.CACHE).await()
      } catch (_: Exception) {
        null
      }
    return try {
      db.runTransaction { transaction ->
          val snapshot = transaction.get(uttRef)
          val effectiveSnap =
            if (snapshot.exists()) {
              snapshot
            } else if (cachedSnapshot != null && cachedSnapshot.exists()) {
              val parentSnap = transaction.get(sessionRef)
              if (!parentSnap.exists()) return@runTransaction false
              cachedSnapshot
            } else {
              return@runTransaction false
            }
          val currentTranslated = effectiveSnap.getString("translatedText").orEmpty()
          val currentAttemptId = effectiveSnap.getString("translationAttemptId").orEmpty()
          if (!currentTranslated.startsWith("⏳")) {
            return@runTransaction false
          }
          if (expectedAttemptId.isNotBlank() && currentAttemptId != expectedAttemptId) {
            return@runTransaction false
          }
          if (snapshot.exists()) {
            transaction.update(
              uttRef,
              mapOf(
                "translatedText" to cleanTranslation,
                "technicalTerms" to technicalTerms.take(15).map { it.take(200) },
                "updatedAt" to FieldValue.serverTimestamp(),
              ),
            )
          } else {
            transaction.set(
              uttRef,
              buildHealedUtterancePayloadFromCache(
                cached = effectiveSnap,
                userId = userId,
                sessionId = sessionId,
                translatedText = cleanTranslation,
                technicalTerms = technicalTerms,
                translationAttemptId = expectedAttemptId.ifBlank { currentAttemptId },
              ),
            )
          }
          true
        }
        .await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, uttRef.path)
      throw e
    }
  }

  /**
   * Conditionally marks an utterance as failed inside a Firestore transaction ONLY IF its
   * current `translatedText` in Firestore is still in the pending (`⏳`) state and (when
   * [expectedAttemptId] is provided) its `translationAttemptId` still matches [expectedAttemptId].
   * Prevents a stale timeout recovery or error callback on Device B from overwriting a
   * completed translation or a newer retry attempt that Device A started!
   */
  suspend fun markUtteranceFailedIfStillPending(
    userId: String,
    sessionId: String,
    utteranceId: String,
    failedText: String,
    expectedAttemptId: String? = null,
  ): Boolean {
    val sessionRef =
      db.collection("users").document(userId).collection("sessions").document(sessionId)
    val uttRef = sessionRef.collection("utterances").document(utteranceId)
    val cleanFailedText = failedText.trim().take(2000).ifEmpty { "⚠️ [翻譯失敗，請點擊重試]" }
    val cachedSnapshot =
      try {
        uttRef.get(Source.CACHE).await()
      } catch (_: Exception) {
        null
      }
    return try {
      db.runTransaction { transaction ->
          val snapshot = transaction.get(uttRef)
          val effectiveSnap =
            if (snapshot.exists()) {
              snapshot
            } else if (cachedSnapshot != null && cachedSnapshot.exists()) {
              val parentSnap = transaction.get(sessionRef)
              if (!parentSnap.exists()) return@runTransaction false
              cachedSnapshot
            } else {
              return@runTransaction false
            }
          val currentTranslated = effectiveSnap.getString("translatedText").orEmpty()
          val currentAttemptId = effectiveSnap.getString("translationAttemptId").orEmpty()
          if (!currentTranslated.startsWith("⏳")) {
            // Another device already completed (or updated) this translation; do not overwrite!
            return@runTransaction false
          }
          if (!expectedAttemptId.isNullOrBlank() && currentAttemptId != expectedAttemptId) {
            // Another device already claimed a newer retry attempt; do not overwrite!
            return@runTransaction false
          }
          if (snapshot.exists()) {
            transaction.update(
              uttRef,
              mapOf(
                "translatedText" to cleanFailedText,
                "technicalTerms" to emptyList<String>(),
                "updatedAt" to FieldValue.serverTimestamp(),
              ),
            )
          } else {
            transaction.set(
              uttRef,
              buildHealedUtterancePayloadFromCache(
                cached = effectiveSnap,
                userId = userId,
                sessionId = sessionId,
                translatedText = cleanFailedText,
                technicalTerms = emptyList(),
                translationAttemptId = expectedAttemptId ?: currentAttemptId,
              ),
            )
          }
          true
        }
        .await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, uttRef.path)
      false
    }
  }

  /**
   * Atomically claims an utterance for retry inside a Firestore transaction ONLY IF:
   * 1. Its current `translatedText` is still in failed (`⚠️`) or pending (`⏳`) state, AND
   * 2. Its current `translatedText`, `translationAttemptId`, and `updatedAt` in Firestore
   *    match the exact snapshot version ([expectedTranslatedText], [expectedAttemptId],
   *    [expectedUpdatedAt]) that the user saw when pressing Retry!
   *
   * When claimed, writes [newAttemptId] into `translationAttemptId` and sets `translatedText`
   * to [pendingPlaceholder]. If Phone A and Phone B both see the same failed utterance and
   * press Retry simultaneously, Phone A's transaction updates `translationAttemptId` to
   * Phone A's [newAttemptId] and `translatedText` to `⏳`, so Phone B's transaction sees a
   * version mismatch (`currentAttemptId != expectedAttemptId` and `currentTranslated != expectedTranslatedText`)
   * and returns `false`—guaranteeing only ONE device translates, speaks TTS, and writes back!
   */
  suspend fun claimUtteranceForRetryIfEligible(
    userId: String,
    sessionId: String,
    utteranceId: String,
    expectedTranslatedText: String,
    expectedAttemptId: String,
    expectedUpdatedAt: Timestamp?,
    newAttemptId: String,
    pendingPlaceholder: String,
  ): Boolean {
    val sessionRef =
      db.collection("users").document(userId).collection("sessions").document(sessionId)
    val uttRef = sessionRef.collection("utterances").document(utteranceId)
    val cachedSnapshot =
      try {
        uttRef.get(Source.CACHE).await()
      } catch (_: Exception) {
        null
      }
    return try {
      db.runTransaction { transaction ->
          val snapshot = transaction.get(uttRef)
          val effectiveSnap =
            if (snapshot.exists()) {
              snapshot
            } else if (cachedSnapshot != null && cachedSnapshot.exists()) {
              val parentSnap = transaction.get(sessionRef)
              if (!parentSnap.exists()) return@runTransaction false
              cachedSnapshot
            } else {
              return@runTransaction false
            }
          val currentTranslated = effectiveSnap.getString("translatedText").orEmpty()
          val currentAttemptId = effectiveSnap.getString("translationAttemptId").orEmpty()
          val currentUpdatedAt = effectiveSnap.getTimestamp("updatedAt")

          if (!currentTranslated.startsWith("⚠️") && !currentTranslated.startsWith("⏳")) {
            // Translation already succeeded on another device; do not overwrite or re-translate!
            return@runTransaction false
          }
          if (currentAttemptId != expectedAttemptId) {
            // Another device already claimed a new retry attempt token!
            return@runTransaction false
          }
          val isSameAttemptAutoRecoveredToFailed =
            expectedAttemptId.isNotBlank() &&
              expectedTranslatedText.startsWith("⏳") &&
              currentTranslated.startsWith("⚠️")
          if (!isSameAttemptAutoRecoveredToFailed) {
            if (currentTranslated != expectedTranslatedText) {
              // Another device already changed the state (e.g. from ⚠️ to ⏳) after this user's snapshot!
              return@runTransaction false
            }
            if (
              expectedUpdatedAt != null &&
                currentUpdatedAt != null &&
                currentUpdatedAt != expectedUpdatedAt
            ) {
              // Document version timestamp changed since the user pressed Retry!
              return@runTransaction false
            }
          }

          if (snapshot.exists()) {
            transaction.update(
              uttRef,
              mapOf(
                "translatedText" to pendingPlaceholder,
                "translationAttemptId" to newAttemptId.trim().take(128),
                "updatedAt" to FieldValue.serverTimestamp(),
              ),
            )
          } else {
            transaction.set(
              uttRef,
              buildHealedUtterancePayloadFromCache(
                cached = effectiveSnap,
                userId = userId,
                sessionId = sessionId,
                translatedText = pendingPlaceholder,
                technicalTerms = emptyList(),
                translationAttemptId = newAttemptId.trim().take(128),
              ),
            )
          }
          true
        }
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
