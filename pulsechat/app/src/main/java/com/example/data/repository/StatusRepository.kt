package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.db.StatusDao
import com.example.data.model.StatusItem
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class StatusRepository(
    private val context: Context,
    private val statusDao: StatusDao,
    private val scope: CoroutineScope
) {
    private val firestore: FirebaseFirestore by lazy {
        val dbId = context.getString(R.string.firestore_database_id)
        try {
            FirebaseFirestore.getInstance(FirebaseApp.getInstance(), dbId)
        } catch (e: Exception) {
            FirebaseFirestore.getInstance()
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            syncRemoteStatuses()
        }
    }

    fun getActiveStatuses(): Flow<List<StatusItem>> {
        return statusDao.getActiveStatuses()
    }

    fun getMyStatuses(userId: String): Flow<List<StatusItem>> {
        return statusDao.getStatusesForUser(userId)
    }

    suspend fun postStatus(
        userId: String,
        authorName: String,
        authorPhotoUrl: String?,
        text: String?,
        mediaUrl: String? = null,
        mediaType: String = "TEXT",
        fontFamily: String = "DEFAULT",
        backgroundColorHex: String = "#075E54"
    ) = withContext(Dispatchers.IO) {
        val statusId = "status_" + UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val status = StatusItem(
            statusId = statusId,
            userId = userId,
            authorName = authorName,
            authorPhotoUrl = authorPhotoUrl,
            text = text,
            mediaUrl = mediaUrl,
            mediaType = mediaType,
            fontFamily = fontFamily,
            backgroundColorHex = backgroundColorHex,
            createdAt = now,
            expiresAt = now + 86400000L,
            isViewed = false
        )

        statusDao.insertStatus(status)

        try {
            firestore.collection("statuses").document(statusId).set(status).await()
        } catch (e: Exception) {
            Log.w("StatusRepository", "Firestore post status failed", e)
        }
    }

    suspend fun markStatusViewed(statusId: String) = withContext(Dispatchers.IO) {
        statusDao.markStatusViewed(statusId)
    }

    private suspend fun syncRemoteStatuses() {
        try {
            val now = System.currentTimeMillis()
            val snapshot = firestore.collection("statuses")
                .whereGreaterThan("expiresAt", now)
                .get()
                .await()
            val list = snapshot.toObjects(StatusItem::class.java)
            list.forEach { statusDao.insertStatus(it) }
        } catch (e: Exception) {
            Log.w("StatusRepository", "Sync remote statuses failed: ${e.message}")
        }
    }
}
