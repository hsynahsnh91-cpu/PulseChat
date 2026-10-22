package com.example.data.repository

import android.net.Uri
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.model.BlockedUser
import com.example.data.model.Contact
import com.example.data.model.ContactRow
import com.example.data.model.User
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.data.remote.RemoteMappers
import com.example.data.remote.millis
import com.example.domain.FieldError
import com.example.domain.Ids
import com.example.domain.MediaRefs
import com.example.domain.PresenceDisplay
import com.example.domain.PresencePolicy
import com.example.domain.Validators
import com.example.domain.model.Visibility
import com.example.util.AppForeground
import com.example.util.ServerClock
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

class ProfileUpdateException(val field: FieldError) : Exception(field.name)

class UserRepository(
    private val firestore: FirebaseFirestore,
    private val db: AppDatabase,
    private val prefs: AppPreferences,
    private val media: MediaRepository,
    private val serverClock: ServerClock,
    private val scope: CoroutineScope,
    private val currentUid: () -> String?,
) {
    private val inFlight = ConcurrentHashMap<String, Long>()
    private var presenceJob: Job? = null

    fun observeUser(uid: String): Flow<User?> = db.userDao().observe(uid)
    fun observeUsers(ids: List<String>): Flow<List<User>> = db.userDao().observeMany(ids)
    suspend fun getUser(uid: String): User? = db.userDao().get(uid)

    /** Fetches public + privacy-restricted profile fields. Restricted fields the viewer may not see stay hidden. */
    suspend fun refreshProfile(uid: String, force: Boolean = false) {
        if (uid.isBlank() || uid == Ids.AI_ASSISTANT_ID) return
        val local = db.userDao().get(uid)
        if (!force && local != null && System.currentTimeMillis() - local.profileSyncedAt < STALE_MS) return
        val last = inFlight[uid]
        if (!force && last != null && System.currentTimeMillis() - last < 10_000) return
        inFlight[uid] = System.currentTimeMillis()
        try {
            val userRef = firestore.collection(Fs.USERS).document(uid)
            val doc = userRef.get().await()
            if (!doc.exists()) return
            var user = RemoteMappers.user(doc, local)
            val photo = readRestricted(uid, Fs.PHOTO_DOC)
            val about = readRestricted(uid, Fs.ABOUT_DOC)
            user = user.copy(
                avatarUrl = if (photo.second) photo.first?.getString("url") else null,
                statusMessage = if (about.second) about.first?.getString("text").orEmpty() else "",
            )
            db.userDao().upsert(user)
            // Status updates that arrived before this profile showed "Unknown" until they changed.
            if (user.displayName.isNotBlank()) db.statusDao().updateAuthor(uid, user.displayName, user.avatarUrl)
        } catch (e: Exception) {
            Log.w(TAG, "refreshProfile($uid) failed: ${e.message}")
        } finally {
            inFlight.remove(uid)
        }
    }

    /** Returns (snapshot, allowed). Permission denied means the owner's privacy settings hide it. */
    private suspend fun readRestricted(uid: String, docId: String): Pair<com.google.firebase.firestore.DocumentSnapshot?, Boolean> = try {
        firestore.collection(Fs.USERS).document(uid).collection(Fs.RESTRICTED).document(docId).get().await() to true
    } catch (e: FirebaseFirestoreException) {
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) null to false else throw e
    }

    fun ensureProfiles(ids: Collection<String>) {
        ids.filter { it.isNotBlank() && it != Ids.AI_ASSISTANT_ID }.distinct().forEach { id ->
            scope.launch(Dispatchers.IO) { refreshProfile(id) }
        }
    }

    // ------------------------------------------------------------------ search

    /**
     * Finds a person by their exact username (with or without "@") or exact 10-digit ID. Display
     * names are deliberately not searched: they are not unique, so a match could be the wrong
     * person. Both handles are unique by construction (reserved in usernames/uniqueIds).
     */
    suspend fun searchUsers(query: String): List<User> {
        val me = currentUid() ?: return emptyList()
        val q = Validators.normalizeUsername(query)
        val uid = try {
            when {
                Validators.isTenDigitId(q) -> firestore.collection(Fs.UNIQUE_IDS).document(q).get().await().getString("uid")
                Validators.usernameError(q) == null -> firestore.collection(Fs.USERNAMES).document(q).get().await().getString("uid")
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
            throw e
        }
        if (uid == null || uid == me || uid in db.contactDao().blockedIds(me)) return emptyList()
        val doc = firestore.collection(Fs.USERS).document(uid).get().await()
        if (!doc.exists()) return emptyList()
        val found = RemoteMappers.user(doc, db.userDao().get(uid))
        val local = db.userDao().get(uid)
        db.userDao().upsert(found.copy(avatarUrl = local?.avatarUrl, statusMessage = local?.statusMessage.orEmpty(), profileSyncedAt = local?.profileSyncedAt ?: 0L))
        ensureProfiles(listOf(uid))
        return listOf(found)
    }

    // ------------------------------------------------------------------ contacts

    fun observeContacts(): Flow<List<ContactRow>> = db.contactDao().observeContacts(currentUid().orEmpty())

    /** Saved contacts and the people of my one-to-one chats, for sharing as a contact card. */
    fun observeShareableContacts(): Flow<List<ContactRow>> = db.contactDao().observeShareable(currentUid().orEmpty())

    /**
     * The PulseChat user behind a shared contact card ("@username"), looked up on this phone first
     * and otherwise on the server (needs a connection). Null when nobody (else) has that handle.
     */
    suspend fun findByHandle(handle: String): User? {
        val me = currentUid() ?: return null
        val username = Validators.normalizeUsername(handle)
        if (Validators.usernameError(username) != null) return null
        db.userDao().findByUsername(username)?.let { local ->
            return local.takeIf { it.userId != me && it.userId !in db.contactDao().blockedIds(me) }
        }
        return searchUsers(username).firstOrNull()
    }
    fun observeContact(target: String): Flow<Contact?> = db.contactDao().observe(currentUid().orEmpty(), target)

    suspend fun saveContact(target: String, nickname: String?) {
        val me = currentUid() ?: return
        val clean = nickname?.trim()?.takeIf { it.isNotEmpty() }?.take(Validators.DISPLAY_NAME_MAX)
        val existing = db.contactDao().get(me, target)
        db.contactDao().upsert(
            Contact(contactId = "${me}_$target", ownerUserId = me, targetUserId = target, customNickname = clean, addedAt = existing?.addedAt ?: System.currentTimeMillis())
        )
        firestore.collection(Fs.USERS).document(me).collection(Fs.CONTACTS).document(target)
            .set(mapOf("nickname" to clean, "addedAt" to FieldValue.serverTimestamp()))
        refreshProfile(target, force = true) // contacts may unlock contacts-only profile fields
    }

    suspend fun removeContact(target: String) {
        val me = currentUid() ?: return
        db.contactDao().delete(me, target)
        firestore.collection(Fs.USERS).document(me).collection(Fs.CONTACTS).document(target).delete()
    }

    suspend fun isContact(target: String): Boolean = db.contactDao().get(currentUid().orEmpty(), target) != null

    // ------------------------------------------------------------------ blocking & reporting

    fun observeBlockedIds(): Flow<List<String>> = db.contactDao().observeBlockedIds(currentUid().orEmpty())

    fun observeBlockedUsers(): Flow<List<User>> = observeBlockedIds().map { ids -> if (ids.isEmpty()) emptyList() else db.userDao().getMany(ids) }

    suspend fun block(target: String) {
        val me = currentUid() ?: return
        db.contactDao().block(BlockedUser(me, target, System.currentTimeMillis()))
        firestore.collection(Fs.USERS).document(me).collection(Fs.BLOCKED).document(target)
            .set(mapOf("blockedAt" to FieldValue.serverTimestamp()))
    }

    suspend fun unblock(target: String) {
        val me = currentUid() ?: return
        db.contactDao().unblock(me, target)
        firestore.collection(Fs.USERS).document(me).collection(Fs.BLOCKED).document(target).delete()
    }

    suspend fun report(target: String?, conversationId: String?, messageIds: List<String>, reason: String) {
        val me = currentUid() ?: return
        firestore.collection(Fs.REPORTS).add(
            mapOf(
                "reporterId" to me,
                "targetUserId" to target,
                "conversationId" to conversationId,
                "messageIds" to messageIds.take(20),
                "reason" to reason.take(500),
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    // ------------------------------------------------------------------ own profile

    suspend fun updateDisplayName(name: String) {
        val me = currentUid() ?: return
        Validators.displayNameError(name)?.let { throw ProfileUpdateException(it) }
        val clean = name.trim()
        firestore.collection(Fs.USERS).document(me).update(mapOf("displayName" to clean, "displayNameLower" to clean.lowercase()))
        db.userDao().get(me)?.let { db.userDao().upsert(it.copy(displayName = clean)) }
    }

    suspend fun updateAbout(about: String) {
        val me = currentUid() ?: return
        Validators.aboutError(about)?.let { throw ProfileUpdateException(it) }
        val clean = about.trim()
        firestore.collection(Fs.USERS).document(me).collection(Fs.RESTRICTED).document(Fs.ABOUT_DOC)
            .set(mapOf("text" to clean, "updatedAt" to FieldValue.serverTimestamp()))
        db.userDao().get(me)?.let { db.userDao().upsert(it.copy(statusMessage = clean)) }
    }

    suspend fun updatePhoto(uri: Uri) {
        val me = currentUid() ?: return
        val bytes = media.compressImage(uri, 640, 85) ?: throw IllegalArgumentException("unreadable image")
        val previous = db.userDao().get(me)?.avatarUrl
        val blocked = db.contactDao().blockedIds(me).toSet()
        val contacts = (db.contactDao().contactIds(me).toSet() - blocked).joinToString(",")
        val url = media.uploadBytes(
            bytes = bytes,
            storagePath = "profile-photos/$me/avatar_${System.currentTimeMillis()}.jpg",
            mime = "image/jpeg",
            customMetadata = mapOf(
                "visibility" to prefs.current.photoVisibility.name.lowercase(),
                "contacts" to contacts,
                "blocked" to blocked.joinToString(","),
            ),
        ).url
        firestore.collection(Fs.USERS).document(me).collection(Fs.RESTRICTED).document(Fs.PHOTO_DOC)
            .set(mapOf("url" to url, "updatedAt" to FieldValue.serverTimestamp())).await()
        db.userDao().get(me)?.let { db.userDao().upsert(it.copy(avatarUrl = url)) }
        deleteOldPhoto(previous, url)
    }

    suspend fun removePhoto() {
        val me = currentUid() ?: return
        val previous = db.userDao().get(me)?.avatarUrl
        firestore.collection(Fs.USERS).document(me).collection(Fs.RESTRICTED).document(Fs.PHOTO_DOC)
            .set(mapOf("url" to null, "updatedAt" to FieldValue.serverTimestamp()))
        db.userDao().get(me)?.let { db.userDao().upsert(it.copy(avatarUrl = null)) }
        deleteOldPhoto(previous, null)
    }

    /** Replaced photos kept in the built-in media store are removed (Cloud Storage copies stay). */
    private fun deleteOldPhoto(previous: String?, current: String?) {
        if (previous != null && previous != current && MediaRefs.isPulse(previous)) scope.launch(Dispatchers.IO) { media.deleteRemote(previous) }
    }

    // ------------------------------------------------------------------ privacy

    suspend fun updateVisibility(lastSeen: Visibility, photo: Visibility, about: Visibility) {
        val me = currentUid() ?: return
        prefs.setVisibilities(lastSeen, photo, about)
        firestore.collection(Fs.USERS).document(me).update(
            mapOf(
                "lastSeenVisibility" to lastSeen.name.lowercase(),
                "photoVisibility" to photo.name.lowercase(),
                "aboutVisibility" to about.name.lowercase(),
            )
        )
    }

    fun setReadReceipts(enabled: Boolean) = writePrivateSetting("readReceipts", enabled) { prefs.setReadReceipts(enabled) }
    fun setNotificationPreviews(enabled: Boolean) = writePrivateSetting("notificationPreviews", enabled) { prefs.setNotificationPreviews(enabled) }

    private fun writePrivateSetting(key: String, value: Any, local: () -> Unit) {
        local()
        val me = currentUid() ?: return
        firestore.collection(Fs.USERS).document(me).collection(Fs.PRIVATE).document(Fs.SETTINGS_DOC)
            .set(mapOf(key to value, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
    }

    /** Pulls the account's server-side privacy settings into local preferences after sign-in. */
    suspend fun pullOwnSettings() {
        val me = currentUid() ?: return
        try {
            val profile = firestore.collection(Fs.USERS).document(me).get().await()
            prefs.setVisibilities(
                Visibility.parse(profile.getString("lastSeenVisibility")),
                Visibility.parse(profile.getString("photoVisibility")),
                Visibility.parse(profile.getString("aboutVisibility")),
            )
            val settings = firestore.collection(Fs.USERS).document(me).collection(Fs.PRIVATE).document(Fs.SETTINGS_DOC).get().await()
            settings.getBoolean("readReceipts")?.let { prefs.setReadReceipts(it) }
            settings.getBoolean("notificationPreviews")?.let { prefs.setNotificationPreviews(it) }
            refreshProfile(me, force = true)
        } catch (e: Exception) {
            Log.w(TAG, "pullOwnSettings failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ presence

    /** Publishes a heartbeat while the app is in the foreground and "last seen" when it leaves. */
    fun startPresence() {
        if (presenceJob?.isActive == true) return
        presenceJob = scope.launch {
            var clockMeasured = false
            AppForeground.inForeground.collectLatest { foreground ->
                val me = currentUid() ?: return@collectLatest
                val ref = firestore.collection(Fs.PRESENCE).document(me)
                if (foreground) {
                    while (isActive && currentUid() == me) {
                        val sentAt = System.currentTimeMillis()
                        runCatching {
                            ref.set(mapOf("online" to true, "heartbeatAt" to FieldValue.serverTimestamp())).await()
                            val ackedAt = System.currentTimeMillis()
                            // Only a quick round trip gives a usable clock sample; a write that waited in
                            // the offline queue would make the device clock look far behind.
                            if (!clockMeasured && ackedAt - sentAt < CLOCK_SAMPLE_MAX_ROUND_TRIP_MS) {
                                val snap = ref.get(com.google.firebase.firestore.Source.SERVER).await()
                                serverClock.observe(snap.millis("heartbeatAt"), (sentAt + ackedAt) / 2)
                                clockMeasured = true
                            }
                        }
                        delay(PresencePolicy.HEARTBEAT_INTERVAL_MS)
                    }
                } else {
                    goOffline(me)
                }
            }
        }
    }

    suspend fun goOffline(uid: String) {
        runCatching {
            firestore.collection(Fs.PRESENCE).document(uid).set(
                mapOf("online" to false, "heartbeatAt" to FieldValue.serverTimestamp(), "lastSeen" to FieldValue.serverTimestamp())
            )
        }
    }

    fun stopPresence() { presenceJob?.cancel(); presenceJob = null }

    /** Presence of another user as allowed by their privacy settings (and ours: reciprocity). */
    fun observePresence(uid: String): Flow<PresenceDisplay> {
        if (uid == Ids.AI_ASSISTANT_ID) return flow { emit(PresenceDisplay.Hidden) }
        val raw: Flow<Triple<Boolean, Long, Long>?> = callbackFlow {
            val reg = firestore.collection(Fs.PRESENCE).document(uid).addSnapshotListener { snap, err ->
                if (err != null) { trySend(null); return@addSnapshotListener }
                if (snap == null || !snap.exists()) { trySend(Triple(false, 0L, 0L)); return@addSnapshotListener }
                trySend(Triple(snap.getBoolean("online") ?: false, snap.millis("heartbeatAt"), snap.millis("lastSeen")))
            }
            awaitClose { reg.remove() }
        }
        val ticker = flow { while (true) { emit(Unit); delay(20_000) } }
        return combine(raw, ticker, prefs.settings) { p, _, settings ->
            if (p == null) PresenceDisplay.Hidden
            else PresencePolicy.resolve(
                online = p.first, heartbeatAt = p.second, lastSeen = p.third, now = serverClock.now(),
                readable = true, viewerSharesLastSeen = settings.lastSeenVisibility != Visibility.NOBODY,
            )
        }.distinctUntilChanged()
    }

    companion object {
        private const val TAG = "UserRepository"
        private const val STALE_MS = 30 * 60 * 1000L
        private const val CLOCK_SAMPLE_MAX_ROUND_TRIP_MS = 3_000L
    }
}
