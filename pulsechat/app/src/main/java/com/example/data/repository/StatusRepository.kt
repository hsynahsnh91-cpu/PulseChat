package com.example.data.repository

import android.net.Uri
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.model.StatusItem
import com.example.data.model.StatusView
import com.example.data.model.StatusViewRow
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.data.remote.RemoteMappers
import com.example.data.remote.millis
import com.example.data.remote.toTimestamp
import com.example.domain.Ids
import com.example.domain.MediaLimits
import com.example.domain.StatusPolicy
import com.example.domain.model.MessageType
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class StatusAudienceEmptyException : Exception("no audience")

/**
 * 24-hour statuses. Each status document carries an explicit audience list computed from the
 * author's privacy setting; Firestore rules only let listed users (and the author) read it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusRepository(
    private val firestore: FirebaseFirestore,
    private val db: AppDatabase,
    private val media: MediaRepository,
    private val users: UserRepository,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val currentUid: () -> String?,
) {
    private val regs = mutableListOf<ListenerRegistration>()
    private val viewRegs = ConcurrentHashMap<String, ListenerRegistration>()

    fun observeActive(): Flow<List<StatusItem>> =
        flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }
            .flatMapLatest { now -> db.statusDao().observeActive(now) }

    fun observeViewers(statusId: String): Flow<List<StatusViewRow>> = db.statusDao().observeViews(statusId)

    @Synchronized
    fun start(me: String) {
        stop()
        val col = firestore.collection(Fs.STATUSES)
        regs += col.whereArrayContains("audience", me).addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "statuses listener: ${err.code}"); return@addSnapshotListener }
            snap ?: return@addSnapshotListener
            scope.launch(Dispatchers.IO) { apply(me, snap.documentChanges) }
        }
        regs += col.whereEqualTo("ownerId", me).addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "own statuses listener: ${err.code}"); return@addSnapshotListener }
            snap ?: return@addSnapshotListener
            scope.launch(Dispatchers.IO) { apply(me, snap.documentChanges) }
        }
    }

    @Synchronized
    fun stop() {
        regs.forEach { it.remove() }; regs.clear()
        viewRegs.values.forEach { it.remove() }; viewRegs.clear()
    }

    private suspend fun apply(me: String, changes: List<DocumentChange>) {
        val now = System.currentTimeMillis()
        for (change in changes) {
            val doc = change.document
            if (change.type == DocumentChange.Type.REMOVED) {
                if (doc.getString("ownerId") != me) db.statusDao().delete(doc.id)
                viewRegs.remove(doc.id)?.remove()
                continue
            }
            if (doc.millis("expiresAt") in 1..now) { db.statusDao().delete(doc.id); continue }
            val owner = doc.getString("ownerId").orEmpty()
            users.refreshProfile(owner)
            val author = db.userDao().get(owner)
            val existing = db.statusDao().get(doc.id)
            db.statusDao().upsert(RemoteMappers.status(doc, existing, author?.displayName.orEmpty(), author?.avatarUrl))
            if (owner == me && !viewRegs.containsKey(doc.id)) listenViews(doc.id)
        }
    }

    private fun listenViews(statusId: String) {
        viewRegs[statusId] = firestore.collection(Fs.STATUSES).document(statusId).collection(Fs.VIEWS)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                scope.launch(Dispatchers.IO) {
                    for (d in snap.documents) {
                        db.statusDao().upsertView(StatusView(statusId, d.id, d.millis("viewedAt").takeIf { it > 0 } ?: System.currentTimeMillis()))
                    }
                    users.ensureProfiles(snap.documents.map { it.id })
                }
            }
    }

    private suspend fun audience(me: String): List<String> {
        val settings = prefs.current
        val contacts = db.contactDao().contactIds(me).toSet() - db.contactDao().blockedIds(me).toSet()
        return StatusPolicy.audience(settings.statusAudienceMode, contacts, settings.statusAudienceList).filter { it != me }.take(500)
    }

    suspend fun postText(text: String, backgroundHex: String, font: String) {
        val me = currentUid() ?: return
        val clean = text.trim().take(StatusPolicy.MAX_TEXT)
        if (clean.isEmpty()) return
        val aud = audience(me)
        if (aud.isEmpty()) throw StatusAudienceEmptyException()
        val id = Ids.newId("st")
        val now = System.currentTimeMillis()
        val self = db.userDao().get(me)
        db.statusDao().upsert(
            StatusItem(
                statusId = id, userId = me, authorName = self?.displayName.orEmpty(), authorPhotoUrl = self?.avatarUrl, text = clean,
                mediaType = "TEXT", fontFamily = font, backgroundColorHex = backgroundHex, createdAt = now,
                expiresAt = now + StatusPolicy.LIFETIME_MS, isViewed = true, uploadState = StatusItem.UPLOAD_PENDING, audience = aud.joinToString(","),
            )
        )
        write(id, me, "TEXT", clean, backgroundHex, font, null, aud, now)
    }

    suspend fun postMedia(uri: Uri, type: MessageType, caption: String) {
        val me = currentUid() ?: return
        val aud = audience(me)
        if (aud.isEmpty()) throw StatusAudienceEmptyException()
        val limit = minOf(if (type == MessageType.VIDEO) MediaLimits.MAX_VIDEO_BYTES else MediaLimits.MAX_IMAGE_BYTES, media.effectiveMaxBytes(type))
        val local = media.importUri(uri, type, limit)
        val id = Ids.newId("st")
        val now = System.currentTimeMillis()
        val self = db.userDao().get(me)
        val kind = if (type == MessageType.VIDEO) "VIDEO" else "IMAGE"
        val path = "status-media/$me/$id.${MediaRepository.extensionFor(local.mime, local.fileName)}"
        val item = StatusItem(
            statusId = id, userId = me, authorName = self?.displayName.orEmpty(), authorPhotoUrl = self?.avatarUrl, text = caption.trim().ifBlank { null },
            mediaType = kind, createdAt = now, expiresAt = now + StatusPolicy.LIFETIME_MS, isViewed = true,
            localMediaPath = local.path, uploadState = StatusItem.UPLOAD_UPLOADING, uploadProgress = 0, audience = aud.joinToString(","), storagePath = path,
        )
        db.statusDao().upsert(item)
        uploadAndWrite(item, local.mime)
    }

    private fun uploadAndWrite(item: StatusItem, mime: String) {
        scope.launch(Dispatchers.IO) {
            try {
                var last = -10
                val uploaded = media.upload(item.statusId, File(item.localMediaPath!!), item.storagePath!!, mime) { p ->
                    if (p < 100 && (p < last || p - last >= 5)) { last = p; scope.launch(Dispatchers.IO) { db.statusDao().setUpload(item.statusId, StatusItem.UPLOAD_UPLOADING, p) } }
                }
                db.statusDao().upsert(item.copy(mediaUrl = uploaded.url, storagePath = uploaded.path, uploadState = StatusItem.UPLOAD_PENDING, uploadProgress = 100))
                write(item.statusId, item.userId, item.mediaType, item.text, item.backgroundColorHex, item.fontFamily,
                    mapOf("url" to uploaded.url, "path" to uploaded.path, "mime" to mime), item.audience.split(",").filter { it.isNotBlank() }, item.createdAt)
            } catch (e: Exception) {
                Log.w(TAG, "status upload failed", e)
                db.statusDao().setUpload(item.statusId, StatusItem.UPLOAD_FAILED, 0)
            }
        }
    }

    private fun write(id: String, me: String, type: String, text: String?, bg: String, font: String, mediaMap: Map<String, Any?>?, aud: List<String>, createdAt: Long) {
        firestore.collection(Fs.STATUSES).document(id).set(
            mapOf(
                "ownerId" to me, "type" to type, "text" to text, "bgColor" to bg, "font" to font, "media" to mediaMap,
                "createdAt" to FieldValue.serverTimestamp(), "expiresAt" to (createdAt + StatusPolicy.LIFETIME_MS).toTimestamp(), "audience" to aud,
            )
        ).addOnSuccessListener {
            scope.launch(Dispatchers.IO) { db.statusDao().setUpload(id, StatusItem.UPLOAD_DONE, 100) }
        }.addOnFailureListener { e ->
            Log.w(TAG, "status write failed", e)
            scope.launch(Dispatchers.IO) { db.statusDao().setUpload(id, StatusItem.UPLOAD_FAILED, 0) }
        }
    }

    suspend fun retry(statusId: String) {
        val item = db.statusDao().get(statusId) ?: return
        if (item.uploadState != StatusItem.UPLOAD_FAILED) return
        if (item.mediaType == "TEXT" || item.mediaUrl != null) {
            db.statusDao().setUpload(statusId, StatusItem.UPLOAD_PENDING, 0)
            write(item.statusId, item.userId, item.mediaType, item.text, item.backgroundColorHex, item.fontFamily,
                item.mediaUrl?.let { mapOf("url" to it, "path" to item.storagePath) }, item.audience.split(",").filter { it.isNotBlank() }, item.createdAt)
        } else if (item.localMediaPath != null && File(item.localMediaPath).exists()) {
            uploadAndWrite(item, MediaRepository.guessMime(item.localMediaPath))
        }
    }

    fun cancelUpload(statusId: String) = media.cancelUpload(statusId)

    suspend fun markViewed(status: StatusItem) {
        val me = currentUid() ?: return
        if (status.isViewed || status.userId == me) return
        db.statusDao().markViewed(status.statusId)
        firestore.collection(Fs.STATUSES).document(status.statusId).collection(Fs.VIEWS).document(me)
            .set(mapOf("viewedAt" to FieldValue.serverTimestamp()))
    }

    suspend fun delete(statusId: String) {
        val item = db.statusDao().get(statusId)
        db.statusDao().delete(statusId)
        viewRegs.remove(statusId)?.remove()
        media.deleteLocal(listOf(item?.localMediaPath))
        runCatching { firestore.collection(Fs.STATUSES).document(statusId).delete().await() }
        media.deleteRemote(item?.storagePath)
    }

    suspend fun purgeExpired() { db.statusDao().deleteExpired(System.currentTimeMillis()) }

    companion object { private const val TAG = "StatusRepository" }
}
