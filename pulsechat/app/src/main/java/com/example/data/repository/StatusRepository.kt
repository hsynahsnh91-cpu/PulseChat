package com.example.data.repository

import android.net.Uri
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.model.StatusItem
import com.example.data.model.StatusLike
import com.example.data.model.StatusLikeRow
import com.example.data.model.StatusView
import com.example.data.model.StatusViewRow
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.data.remote.RemoteMappers
import com.example.data.remote.map
import com.example.data.remote.millis
import com.example.data.remote.stringList
import com.example.data.remote.toTimestamp
import com.example.domain.Ids
import com.example.domain.MediaLimits
import com.example.domain.StatusMusicPolicy
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
    var onStatusMentionedMe: ((StatusItem) -> Unit)? = null,
) {
    private val regs = mutableListOf<ListenerRegistration>()
    private val viewRegs = ConcurrentHashMap<String, ListenerRegistration>()
    private val likeRegs = ConcurrentHashMap<String, ListenerRegistration>()

    fun observeActive(): Flow<List<StatusItem>> =
        flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }
            .flatMapLatest { now -> db.statusDao().observeActive(now) }

    fun observeStatus(statusId: String): Flow<StatusItem?> = db.statusDao().observeById(statusId)

    fun observeViewers(statusId: String): Flow<List<StatusViewRow>> = db.statusDao().observeViews(statusId)

    fun observeViewerCount(statusIds: List<String>): Flow<Int> = db.statusDao().observeViewerCount(statusIds)

    fun observeLikes(statusId: String): Flow<List<StatusLikeRow>> = db.statusDao().observeLikes(statusId)

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
        likeRegs.values.forEach { it.remove() }; likeRegs.clear()
    }

    private suspend fun apply(me: String, changes: List<DocumentChange>) {
        val now = System.currentTimeMillis()
        for (change in changes) {
            val doc = change.document
            if (change.type == DocumentChange.Type.REMOVED) {
                if (doc.getString("ownerId") != me) {
                    db.statusDao().delete(doc.id)
                    db.statusDao().deleteLikesForStatus(doc.id)
                    db.statusDao().deleteViewsForStatus(doc.id)
                }
                viewRegs.remove(doc.id)?.remove()
                likeRegs.remove(doc.id)?.remove()
                continue
            }
            if (doc.millis("expiresAt") in 1..now) {
                db.statusDao().delete(doc.id)
                db.statusDao().deleteLikesForStatus(doc.id)
                db.statusDao().deleteViewsForStatus(doc.id)
                continue
            }
            val owner = doc.getString("ownerId").orEmpty()
            users.refreshProfile(owner)
            val author = db.userDao().get(owner)
            val existing = db.statusDao().get(doc.id)
            val mapped = RemoteMappers.status(
                doc,
                existing,
                author?.displayName ?: existing?.authorName.orEmpty(),
                author?.avatarUrl ?: existing?.authorPhotoUrl,
            )
            db.statusDao().upsert(mapped)
            if (owner == me) {
                if (!viewRegs.containsKey(doc.id)) listenViews(doc.id)
                if (!likeRegs.containsKey(doc.id)) listenLikes(doc.id, me)
            } else {
                syncMyLikeState(doc.id, me)
                if (me in mapped.mentionedUserIds && prefs.markStatusMentionNotified(me, mapped.statusId)) {
                    onStatusMentionedMe?.invoke(mapped)
                }
            }
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

    private fun listenLikes(statusId: String, me: String) {
        likeRegs[statusId] = firestore.collection(Fs.STATUSES).document(statusId).collection(Fs.LIKES)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                scope.launch(Dispatchers.IO) {
                    db.statusDao().deleteLikesForStatus(statusId)
                    var likedByMe = false
                    for (d in snap.documents) {
                        db.statusDao().upsertLike(StatusLike(statusId, d.id, d.millis("likedAt").takeIf { it > 0 } ?: System.currentTimeMillis()))
                        if (d.id == me) likedByMe = true
                    }
                    db.statusDao().setLikedState(statusId, likedByMe, snap.documents.size)
                    users.ensureProfiles(snap.documents.map { it.id })
                }
            }
    }

    private suspend fun syncMyLikeState(statusId: String, me: String) {
        runCatching {
            val likeDoc = firestore.collection(Fs.STATUSES).document(statusId)
                .collection(Fs.LIKES).document(me).get().await()
            val liked = likeDoc.exists()
            if (liked) {
                db.statusDao().upsertLike(StatusLike(statusId, me, likeDoc.millis("likedAt").takeIf { it > 0 } ?: System.currentTimeMillis()))
            } else {
                db.statusDao().deleteLike(statusId, me)
            }
            val current = db.statusDao().get(statusId)
            val count = if (liked) maxOf(1, current?.likeCount ?: 0) else ((current?.likeCount ?: 0) - if (current?.isLikedByMe == true) 1 else 0).coerceAtLeast(0)
            db.statusDao().setLikedState(statusId, liked, count)
        }
    }

    private suspend fun audience(me: String): List<String> {
        val settings = prefs.current
        val contacts = db.contactDao().contactIds(me).toSet() - db.contactDao().blockedIds(me).toSet()
        return StatusPolicy.audience(settings.statusAudienceMode, contacts, settings.statusAudienceList).filter { it != me }.take(500)
    }

    private suspend fun validatedMentions(me: String, requested: List<String>, aud: List<String>): List<String> {
        if (requested.isEmpty()) return emptyList()
        val blocked = db.contactDao().blockedIds(me).toSet()
        return StatusPolicy.validateMentions(
            requestedIds = requested,
            authorId = me,
            blockedIds = blocked,
            allowedAudience = aud.toSet(),
        ).allowedMentionIds
    }

    suspend fun postText(
        text: String,
        backgroundHex: String,
        font: String,
        musicSelection: StatusMusicPolicy.Selection? = null,
        mentions: List<String> = emptyList(),
        allowReshare: Boolean = true,
    ) {
        val me = currentUid() ?: return
        val clean = text.trim().take(StatusPolicy.MAX_TEXT)
        if (clean.isEmpty()) return
        val aud = audience(me)
        if (aud.isEmpty()) throw StatusAudienceEmptyException()
        val validMentions = validatedMentions(me, mentions, aud)
        val id = Ids.newId("st")
        val now = System.currentTimeMillis()
        val self = db.userDao().get(me)
        val item = StatusItem(
            statusId = id,
            userId = me,
            authorName = self?.displayName.orEmpty(),
            authorPhotoUrl = self?.avatarUrl,
            text = clean,
            mediaType = "TEXT",
            fontFamily = font,
            backgroundColorHex = backgroundHex,
            createdAt = now,
            expiresAt = now + StatusPolicy.LIFETIME_MS,
            isViewed = true,
            uploadState = StatusItem.UPLOAD_PENDING,
            audience = aud.joinToString(","),
            musicTrackId = musicSelection?.trackId,
            musicTitle = musicSelection?.title,
            musicArtist = musicSelection?.artist,
            musicUrl = musicSelection?.localUri,
            musicStartMs = musicSelection?.startMs ?: 0L,
            musicDurationMs = musicSelection?.durationMs ?: 0L,
            mentions = validMentions.joinToString(","),
            allowReshare = allowReshare,
        )
        db.statusDao().upsert(item)
        write(
            item = item,
            mediaMap = null,
            aud = aud,
            mentions = validMentions,
            musicSelection = musicSelection,
        )
    }

    suspend fun postMedia(
        uri: Uri,
        type: MessageType,
        caption: String,
        musicSelection: StatusMusicPolicy.Selection? = null,
        mentions: List<String> = emptyList(),
        allowReshare: Boolean = true,
    ) {
        val me = currentUid() ?: return
        val aud = audience(me)
        if (aud.isEmpty()) throw StatusAudienceEmptyException()
        val validMentions = validatedMentions(me, mentions, aud)
        val limit = minOf(if (type == MessageType.VIDEO) MediaLimits.MAX_VIDEO_BYTES else MediaLimits.MAX_IMAGE_BYTES, media.effectiveMaxBytes(type))
        val local = media.importUri(uri, type, limit)
        val id = Ids.newId("st")
        val now = System.currentTimeMillis()
        val self = db.userDao().get(me)
        val kind = if (type == MessageType.VIDEO) "VIDEO" else "IMAGE"
        val path = "status-media/$me/$id.${MediaRepository.extensionFor(local.mime, local.fileName)}"
        val item = StatusItem(
            statusId = id,
            userId = me,
            authorName = self?.displayName.orEmpty(),
            authorPhotoUrl = self?.avatarUrl,
            text = caption.trim().ifBlank { null },
            mediaType = kind,
            createdAt = now,
            expiresAt = now + StatusPolicy.LIFETIME_MS,
            isViewed = true,
            localMediaPath = local.path,
            uploadState = StatusItem.UPLOAD_UPLOADING,
            uploadProgress = 0,
            audience = aud.joinToString(","),
            storagePath = path,
            musicTrackId = musicSelection?.trackId,
            musicTitle = musicSelection?.title,
            musicArtist = musicSelection?.artist,
            musicUrl = musicSelection?.localUri,
            musicStartMs = musicSelection?.startMs ?: 0L,
            musicDurationMs = musicSelection?.durationMs ?: 0L,
            mentions = validMentions.joinToString(","),
            allowReshare = allowReshare,
        )
        db.statusDao().upsert(item)
        uploadAndWrite(item, local.mime, musicSelection)
    }

    private fun uploadAndWrite(item: StatusItem, mime: String, musicSelection: StatusMusicPolicy.Selection? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                var last = -10
                val uploaded = media.upload(
                    key = item.statusId,
                    file = File(item.localMediaPath!!),
                    storagePath = item.storagePath!!,
                    mime = mime,
                    customMetadata = mapOf(
                        "audience" to item.audience,
                        "expiresAt" to item.expiresAt.toString(),
                        "statusId" to item.statusId,
                    ),
                ) { p ->
                    if (p < 100 && (p < last || p - last >= 5)) {
                        last = p
                        scope.launch(Dispatchers.IO) { db.statusDao().setUpload(item.statusId, StatusItem.UPLOAD_UPLOADING, p) }
                    }
                }
                val updated = item.copy(mediaUrl = uploaded.url, storagePath = uploaded.path, uploadState = StatusItem.UPLOAD_PENDING, uploadProgress = 100)
                db.statusDao().upsert(updated)
                val aud = updated.audience.split(",").filter { it.isNotBlank() }
                val mentions = updated.mentionedUserIds.filter { it in aud }
                write(
                    item = updated,
                    mediaMap = mapOf("url" to uploaded.url, "path" to uploaded.path, "mime" to mime),
                    aud = aud,
                    mentions = mentions,
                    musicSelection = musicSelection,
                )
            } catch (e: Exception) {
                Log.w(TAG, "status upload failed", e)
                db.statusDao().setUpload(item.statusId, StatusItem.UPLOAD_FAILED, 0)
            }
        }
    }

    private fun write(
        item: StatusItem,
        mediaMap: Map<String, Any?>?,
        aud: List<String>,
        mentions: List<String>,
        musicSelection: StatusMusicPolicy.Selection?,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                var remoteMusicUrl: String? = item.musicUrl
                if (musicSelection != null && musicSelection.isLocalCustom && !musicSelection.localUri.isNullOrBlank()) {
                    runCatching {
                        val imported = media.importUri(Uri.parse(musicSelection.localUri), MessageType.AUDIO, StatusMusicPolicy.MAX_AUDIO_BYTES)
                        val musicStoragePath = "status-media/${item.userId}/${item.statusId}_music.${MediaRepository.extensionFor(imported.mime, imported.fileName)}"
                        val uploadedMusic = media.upload(
                            key = "${item.statusId}_music",
                            file = File(imported.path),
                            storagePath = musicStoragePath,
                            mime = imported.mime,
                            customMetadata = mapOf(
                                "audience" to item.audience,
                                "expiresAt" to item.expiresAt.toString(),
                                "statusId" to item.statusId,
                            ),
                        ) {}
                        remoteMusicUrl = uploadedMusic.url
                    }
                }
                val musicMap = if (musicSelection != null || item.hasMusic) {
                    mapOf(
                        "trackId" to (musicSelection?.trackId ?: item.musicTrackId),
                        "title" to (musicSelection?.title ?: item.musicTitle).orEmpty().take(100),
                        "artist" to (musicSelection?.artist ?: item.musicArtist).orEmpty().take(100),
                        "url" to remoteMusicUrl,
                        "startMs" to (musicSelection?.startMs ?: item.musicStartMs).coerceAtLeast(0L),
                        "durationMs" to (musicSelection?.durationMs ?: item.musicDurationMs).coerceIn(
                            StatusMusicPolicy.MIN_SEGMENT_DURATION_MS,
                            StatusMusicPolicy.MAX_SEGMENT_DURATION_MS,
                        ),
                    )
                } else null

                val payload = mutableMapOf<String, Any?>(
                    "ownerId" to item.userId,
                    "type" to item.mediaType,
                    "text" to item.text,
                    "bgColor" to item.backgroundColorHex,
                    "font" to item.fontFamily,
                    "media" to mediaMap,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to (item.createdAt + StatusPolicy.LIFETIME_MS).toTimestamp(),
                    "audience" to aud,
                    "mentions" to mentions,
                    "allowReshare" to item.allowReshare,
                )
                if (musicMap != null) payload["music"] = musicMap

                firestore.collection(Fs.STATUSES).document(item.statusId).set(payload).await()
                db.statusDao().setUpload(item.statusId, StatusItem.UPLOAD_DONE, 100)
                if (!viewRegs.containsKey(item.statusId)) listenViews(item.statusId)
                if (!likeRegs.containsKey(item.statusId)) listenLikes(item.statusId, item.userId)
            } catch (e: Exception) {
                Log.w(TAG, "status write failed", e)
                db.statusDao().setUpload(item.statusId, StatusItem.UPLOAD_FAILED, 0)
            }
        }
    }

    suspend fun retry(statusId: String) {
        val item = db.statusDao().get(statusId) ?: return
        if (item.uploadState != StatusItem.UPLOAD_FAILED) return
        val aud = item.audience.split(",").filter { it.isNotBlank() }
        val mentions = item.mentionedUserIds.filter { it in aud }
        if (item.mediaType == "TEXT" || item.mediaUrl != null) {
            db.statusDao().setUpload(statusId, StatusItem.UPLOAD_PENDING, 0)
            write(
                item = item,
                mediaMap = item.mediaUrl?.let { mapOf("url" to it, "path" to item.storagePath) },
                aud = aud,
                mentions = mentions,
                musicSelection = null,
            )
        } else if (item.localMediaPath != null && File(item.localMediaPath).exists()) {
            uploadAndWrite(item, MediaRepository.guessMime(item.localMediaPath))
        }
    }

    /**
     * Toggles the current user's like on [statusId] with optimistic local update and automatic
     * rollback on network/permission error. Each user can have at most one like per status
     * (`statuses/{statusId}/likes/{me}`).
     */
    suspend fun toggleLike(statusId: String): Result<Boolean> = runCatching {
        val me = currentUid() ?: throw IllegalStateException("Not signed in")
        val current = db.statusDao().get(statusId) ?: throw IllegalStateException("Status not found")
        val now = System.currentTimeMillis()
        if (!StatusPolicy.isActive(current.expiresAt, now)) {
            throw IllegalStateException("Status has expired")
        }
        val wasLiked = current.isLikedByMe
        val nextLiked = !wasLiked
        val nextCount = (current.likeCount + if (nextLiked) 1 else -1).coerceAtLeast(0)

        db.statusDao().setLikedState(statusId, nextLiked, nextCount)
        if (nextLiked) {
            db.statusDao().upsertLike(StatusLike(statusId, me, now))
        } else {
            db.statusDao().deleteLike(statusId, me)
        }

        try {
            val likeRef = firestore.collection(Fs.STATUSES).document(statusId).collection(Fs.LIKES).document(me)
            if (nextLiked) {
                likeRef.set(mapOf("likedAt" to FieldValue.serverTimestamp())).await()
            } else {
                likeRef.delete().await()
            }
            nextLiked
        } catch (e: Exception) {
            db.statusDao().setLikedState(statusId, wasLiked, current.likeCount)
            if (wasLiked) {
                db.statusDao().upsertLike(StatusLike(statusId, me, now))
            } else {
                db.statusDao().deleteLike(statusId, me)
            }
            throw e
        }
    }

    /**
     * Reshares [originalStatusId] to the current user's own status updates, enforcing:
     * - `allowReshare == true` on the original status,
     * - original status not expired,
     * - current user in original audience and not blocked,
     * - no duplicate reshare by the same user,
     * - effective audience intersected with original status's permitted audience so private
     *   statuses are never widened,
     * - effective expiration capped to `min(original.expiresAt, now + 24h)`,
     * - root author attribution preserved without infinite reshare chains.
     */
    suspend fun reshareStatus(originalStatusId: String): Result<StatusItem> = runCatching {
        val me = currentUid() ?: throw IllegalStateException("Not signed in")
        val now = System.currentTimeMillis()
        val origDoc = firestore.collection(Fs.STATUSES).document(originalStatusId).get().await()
        if (!origDoc.exists()) throw IllegalStateException("RESHARE_DENIED:EXPIRED")

        val origOwnerId = origDoc.getString("ownerId").orEmpty()
        val origAllowReshare = origDoc.getBoolean("allowReshare") ?: true
        val origAudience = origDoc.stringList("audience").toSet()
        val origExpiresAt = origDoc.millis("expiresAt")
        val blockedByMe = db.contactDao().blockedIds(me).toSet()
        val alreadyReshared = db.statusDao().resharedStatusIdsByUser(me, now).toSet()

        val denial = StatusPolicy.checkReshareEligibility(
            ownerId = origOwnerId,
            allowReshare = origAllowReshare,
            audience = origAudience,
            blockedByOwner = blockedByMe,
            expiresAt = origExpiresAt,
            viewerId = me,
            now = now,
            alreadyResharedIdsByViewer = alreadyReshared,
            statusId = originalStatusId,
        )
        if (denial != null) {
            throw IllegalStateException("RESHARE_DENIED:${denial.name}")
        }

        val myRequestedAud = audience(me).toSet()
        val (effectiveAudSet, effectiveExpiresAt) = StatusPolicy.computeReshareScope(
            resharerAudience = myRequestedAud,
            originalAudience = origAudience,
            originalOwnerId = origOwnerId,
            originalExpiresAt = origExpiresAt,
            resharerBlockedIds = blockedByMe,
            now = now,
        )
        val effectiveAud = effectiveAudSet.filter { it != me }
        if (effectiveAud.isEmpty()) throw StatusAudienceEmptyException()

        val self = db.userDao().get(me)
        val origAuthor = db.userDao().get(origOwnerId)
        val origAuthorName = origAuthor?.displayName ?: origOwnerId.take(8)
        val priorReshare = origDoc.map("resharedFrom")
        val rootStatusId = (priorReshare["statusId"] as? String)?.takeIf { it.isNotBlank() } ?: originalStatusId
        val rootUserId = (priorReshare["userId"] as? String)?.takeIf { it.isNotBlank() } ?: origOwnerId
        val rootAuthorName = (priorReshare["authorName"] as? String)?.takeIf { it.isNotBlank() } ?: origAuthorName

        val origMedia = origDoc.map("media").takeIf { it.isNotEmpty() }
        val origMusic = origDoc.map("music").takeIf { it.isNotEmpty() }
        val origType = origDoc.getString("type") ?: "TEXT"
        val origText = origDoc.getString("text")
        val origFont = origDoc.getString("font") ?: "DEFAULT"
        val origBg = origDoc.getString("bgColor") ?: "#3730A3"

        val id = Ids.newId("st")
        val resharedFromMap = mapOf(
            "statusId" to rootStatusId,
            "directStatusId" to originalStatusId,
            "userId" to rootUserId,
            "authorName" to rootAuthorName,
        )
        val payload = mutableMapOf<String, Any?>(
            "ownerId" to me,
            "type" to origType,
            "text" to origText,
            "bgColor" to origBg,
            "font" to origFont,
            "media" to origMedia,
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to effectiveExpiresAt.toTimestamp(),
            "audience" to effectiveAud,
            "mentions" to emptyList<String>(),
            "allowReshare" to true,
            "resharedFrom" to resharedFromMap,
        )
        if (origMusic != null) payload["music"] = origMusic

        firestore.collection(Fs.STATUSES).document(id).set(payload).await()

        val item = StatusItem(
            statusId = id,
            userId = me,
            authorName = self?.displayName.orEmpty(),
            authorPhotoUrl = self?.avatarUrl,
            text = origText,
            mediaUrl = origMedia?.get("url") as? String,
            mediaType = origType,
            fontFamily = origFont,
            backgroundColorHex = origBg,
            createdAt = now,
            expiresAt = effectiveExpiresAt,
            isViewed = true,
            uploadState = StatusItem.UPLOAD_DONE,
            uploadProgress = 100,
            audience = effectiveAud.joinToString(","),
            storagePath = origMedia?.get("path") as? String,
            musicTrackId = origMusic?.get("trackId") as? String,
            musicTitle = origMusic?.get("title") as? String,
            musicArtist = origMusic?.get("artist") as? String,
            musicUrl = origMusic?.get("url") as? String,
            musicStartMs = (origMusic?.get("startMs") as? Number)?.toLong() ?: 0L,
            musicDurationMs = (origMusic?.get("durationMs") as? Number)?.toLong() ?: 0L,
            mentions = "",
            allowReshare = true,
            resharedFromStatusId = rootStatusId,
            resharedFromUserId = rootUserId,
            resharedFromAuthorName = rootAuthorName,
        )
        db.statusDao().upsert(item)
        listenViews(id)
        listenLikes(id, me)
        item
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
        db.statusDao().deleteLikesForStatus(statusId)
        db.statusDao().deleteViewsForStatus(statusId)
        viewRegs.remove(statusId)?.remove()
        likeRegs.remove(statusId)?.remove()
        media.deleteLocal(listOf(item?.localMediaPath))
        runCatching {
            val docRef = firestore.collection(Fs.STATUSES).document(statusId)
            val likes = runCatching { docRef.collection(Fs.LIKES).get().await() }.getOrNull()
            likes?.documents?.forEach { d -> runCatching { d.reference.delete().await() } }
            val views = runCatching { docRef.collection(Fs.VIEWS).get().await() }.getOrNull()
            views?.documents?.forEach { d -> runCatching { d.reference.delete().await() } }
            docRef.delete().await()
        }
        if (item?.isReshared != true) {
            media.deleteRemote(item?.storagePath)
        }
    }

    suspend fun purgeExpired() { db.statusDao().deleteExpired(System.currentTimeMillis()) }

    companion object { private const val TAG = "StatusRepository" }
}
