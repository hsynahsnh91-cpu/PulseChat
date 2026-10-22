package com.example.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.model.BlockedUser
import com.example.data.model.Contact
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.Receipt
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.data.remote.RemoteMappers
import com.example.data.remote.millis
import com.example.data.remote.toTimestamp
import com.example.domain.DisappearingPolicy
import com.example.domain.Ids
import com.example.domain.PresencePolicy
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.util.AppForeground
import com.example.util.ServerClock
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class SyncState { STOPPED, CONNECTING, LIVE, ERROR }

data class SyncStatus(val state: SyncState, val backendNotReady: Boolean = false, val message: String? = null)

/**
 * Mirrors Firestore into Room (the UI's single source of truth). All listener events are applied
 * sequentially through one work queue, every upsert is keyed by a stable ID (duplicates are
 * harmless) and stale/out-of-order message versions are ignored using `updatedAt`.
 */
class SyncManager(
    private val firestore: FirebaseFirestore,
    private val db: AppDatabase,
    private val users: UserRepository,
    private val prefs: AppPreferences,
    private val serverClock: ServerClock,
    private val scope: CoroutineScope,
    private val currentUid: () -> String?,
) {
    /** Problems are reported to Settings › Diagnostics (set by AppContainer). */
    var diagnostics: DiagnosticsLog? = null
    private val _status = MutableStateFlow(SyncStatus(SyncState.STOPPED))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** conversationId -> (userId -> local expiry time of their typing indicator). */
    private val _typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val typing: StateFlow<Map<String, Map<String, Long>>> = _typing.asStateFlow()

    /** Conversation currently on screen: its messages are marked read immediately. */
    @Volatile var activeConversationId: String? = null

    /** Called for genuinely new incoming messages (notifications, auto-download, auto-reply). */
    @Volatile var onIncoming: (suspend (Conversation, List<Message>) -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    @Volatile private var uid: String? = null
    private val globalRegs = mutableListOf<ListenerRegistration>()
    private val messageRegs = ConcurrentHashMap<String, ListenerRegistration>()
    private val pendingPrefs = ConcurrentHashMap<String, Map<String, Any?>>()
    private val deliveredWritten = ConcurrentHashMap<String, Long>()
    private val readWritten = ConcurrentHashMap<String, Long>()

    init {
        scope.launch(Dispatchers.IO) {
            for (job in work) {
                try { job() } catch (e: Exception) { Log.w(TAG, "sync job failed", e) }
            }
        }
        scope.launch {
            while (true) {
                delay(1500)
                pruneTyping()
            }
        }
    }

    fun enqueue(job: suspend () -> Unit) { work.trySend(job) }

    val isRunning: Boolean get() = uid != null

    @Synchronized
    fun start(me: String) {
        if (uid == me && globalRegs.isNotEmpty()) return
        stop()
        uid = me
        _status.value = SyncStatus(SyncState.CONNECTING)
        val userDoc = firestore.collection(Fs.USERS).document(me)

        // Metadata changes are included so the switch from cached data to the server's answer arrives
        // even when no conversation changed in the meantime. Without them the first server sync is
        // never reported and the sync state stays "connecting" until some conversation changes.
        globalRegs += firestore.collection(Fs.CONVERSATIONS).whereArrayContains("participants", me)
            .addSnapshotListener(executor, MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) { onListenerError("conversations", err); return@addSnapshotListener }
                if (snap != null) enqueue { applyConversations(me, snap.documentChanges, snap.metadata.isFromCache) }
            }
        globalRegs += userDoc.collection(Fs.CHAT_PREFS).addSnapshotListener(executor) { snap, err ->
            if (err != null) { onListenerError("chatPrefs", err); return@addSnapshotListener }
            if (snap != null) enqueue { applyChatPrefs(snap.documentChanges) }
        }
        globalRegs += userDoc.collection(Fs.CONTACTS).addSnapshotListener(executor) { snap, err ->
            if (err != null) { onListenerError("contacts", err); return@addSnapshotListener }
            if (snap != null) enqueue { applyContacts(me, snap.documentChanges) }
        }
        globalRegs += userDoc.collection(Fs.BLOCKED).addSnapshotListener(executor) { snap, err ->
            if (err != null) { onListenerError("blocked", err); return@addSnapshotListener }
            if (snap != null) enqueue { applyBlocked(me, snap.documentChanges) }
        }
        enqueue {
            // Resume message listeners for known conversations right away (works offline from cache).
            db.conversationDao().syncedForUser(me).filter { !it.isLeft }.forEach { ensureMessageListener(me, it) }
        }
    }

    @Synchronized
    fun stop() {
        globalRegs.forEach { it.remove() }
        globalRegs.clear()
        messageRegs.values.forEach { it.remove() }
        messageRegs.clear()
        uid = null
        _status.value = SyncStatus(SyncState.STOPPED)
        _typing.value = emptyMap()
    }

    private fun onListenerError(what: String, err: FirebaseFirestoreException) {
        Log.w(TAG, "listener $what failed: ${err.code} ${err.message}")
        val backend = err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
            err.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION ||
            err.code == FirebaseFirestoreException.Code.NOT_FOUND
        _status.value = SyncStatus(SyncState.ERROR, backendNotReady = backend, message = err.code.name)
        diagnostics?.record(DiagnosticsLog.SYNC, if (err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "sync_denied" else "sync_error", err.code.name)
    }

    // ------------------------------------------------------------------ conversations

    private suspend fun applyConversations(me: String, changes: List<DocumentChange>, fromCache: Boolean) {
        val profileIds = mutableSetOf<String>()
        for (change in changes) {
            val doc = change.document
            if (change.type == DocumentChange.Type.REMOVED) {
                if (!fromCache) {
                    db.conversationDao().setLeft(doc.id, true)
                    messageRegs.remove(doc.id)?.remove()
                }
                continue
            }
            val conv = applyConversationDoc(me, doc)
            profileIds += conv.getParticipantsList()
            updateTyping(doc.id, me, RemoteMappers.typing(doc))
            ensureMessageListener(me, conv)
        }
        users.ensureProfiles(profileIds)
        if (!fromCache) {
            _status.value = SyncStatus(SyncState.LIVE)
            prefs.lastServerSyncAt = System.currentTimeMillis()
        }
    }

    suspend fun applyConversationDoc(me: String, doc: DocumentSnapshot): Conversation = db.withTransaction {
        val existing = db.conversationDao().get(doc.id)
        var conv = RemoteMappers.conversation(doc, me, existing)
        val receipts = RemoteMappers.receipts(doc)
        // My own read watermark is part of the document: history restored on a new device (or read on
        // another device) is not shown as unread.
        val myRead = receipts[me]?.second ?: 0L
        if (myRead > conv.lastReadAt) conv = conv.copy(lastReadAt = myRead)
        pendingPrefs.remove(doc.id)?.let { conv = applyPrefs(conv, it) }
        db.conversationDao().upsert(conv)
        receipts.forEach { (u, p) -> db.receiptDao().upsert(Receipt(doc.id, u, p.first, p.second)) }
        applyReceipts(conv, me, receipts)
        refreshLastMessage(doc.id)
        db.conversationDao().recomputeUnread(doc.id, me)
        conv
    }

    /** Advances my sent messages to DELIVERED/READ using every recipient's watermark. */
    private suspend fun applyReceipts(conv: Conversation, me: String, receipts: Map<String, Pair<Long, Long>>) {
        val recipients = conv.getParticipantsList().filter { it != me && it != Ids.AI_ASSISTANT_ID }
        if (recipients.isEmpty()) return
        val delivered = recipients.minOf { r -> receipts[r]?.let { maxOf(it.first, it.second) } ?: 0L }
        val read = recipients.minOf { r -> receipts[r]?.second ?: 0L }
        if (delivered > 0) db.messageDao().advanceStatuses(conv.conversationId, me, delivered, MessageStatus.DELIVERED, listOf(MessageStatus.SENT.name))
        if (read > 0) db.messageDao().advanceStatuses(conv.conversationId, me, read, MessageStatus.READ, listOf(MessageStatus.SENT.name, MessageStatus.DELIVERED.name))
    }

    private fun ensureMessageListener(me: String, conv: Conversation) {
        val cid = conv.conversationId
        if (conv.isLocalOnly || conv.isLeft || messageRegs.containsKey(cid)) return
        val cursor = maxOf(0L, conv.syncCursor - 2_000L)
        val query = firestore.collection(Fs.CONVERSATIONS).document(cid).collection(Fs.MESSAGES)
            .whereGreaterThanOrEqualTo("updatedAt", cursor.toTimestamp())
            .orderBy("updatedAt", Query.Direction.ASCENDING)
            .limitToLast(MESSAGE_WINDOW)
        messageRegs[cid] = query.addSnapshotListener(executor) { snap, err ->
            if (err != null) {
                Log.w(TAG, "messages($cid) listener: ${err.code}")
                if (err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) messageRegs.remove(cid)?.remove()
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            val docs = snap.documentChanges.filter { it.type != DocumentChange.Type.REMOVED }.map { it.document }
            if (docs.isNotEmpty()) enqueue { applyMessageDocs(me, cid, docs, snap.metadata.isFromCache) }
        }
    }

    // ------------------------------------------------------------------ messages

    suspend fun applyMessageDocs(me: String, cid: String, docs: List<DocumentSnapshot>, fromCache: Boolean, notify: Boolean = true, advanceCursor: Boolean = true) {
        val incomingNew = mutableListOf<Message>()
        var maxCursor = 0L
        var maxIncoming = 0L
        val conv = db.conversationDao().get(cid) ?: return
        val now = serverClock.now()
        db.withTransaction {
            for (doc in docs) {
                val existing = db.messageDao().get(doc.id)
                val pending = doc.metadata.hasPendingWrites()
                val remoteUpdated = doc.millis("updatedAt")
                if (existing != null && !pending && existing.updatedAt > 0 && remoteUpdated < existing.updatedAt) continue // stale
                val m = RemoteMappers.message(doc, cid, me, existing)
                if (DisappearingPolicy.isExpired(m.expiresAt, now)) {
                    if (existing != null) db.messageDao().deleteHard(listOf(m.messageId))
                    continue
                }
                if (existing == null && conv.clearedAt > 0 && m.createdAt <= conv.clearedAt) continue
                db.messageDao().upsert(m)
                if (!pending) maxCursor = maxOf(maxCursor, remoteUpdated)
                if (m.senderId != me && !pending) {
                    maxIncoming = maxOf(maxIncoming, m.createdAt)
                    if (notify && !fromCache && existing == null && m.createdAt > conv.lastReadAt && now - m.createdAt < NOTIFY_WINDOW_MS &&
                        !m.deletedForEveryone && m.type != MessageType.SYSTEM) incomingNew += m
                }
            }
            refreshLastMessage(cid)
            db.conversationDao().recomputeUnread(cid, me)
            if (advanceCursor && maxCursor > 0 && !fromCache) db.conversationDao().advanceCursor(cid, maxCursor)
            val receipts = db.receiptDao().forConversation(cid).associate { it.userId to (it.deliveredUpTo to it.readUpTo) }
            db.conversationDao().get(cid)?.let { applyReceipts(it, me, receipts) }
        }
        if (maxIncoming > 0) markDelivered(cid, me, maxIncoming)
        if (cid == activeConversationId && AppForeground.inForeground.value) markRead(cid)
        if (incomingNew.isNotEmpty()) {
            val updated = db.conversationDao().get(cid) ?: conv
            if (!updated.isHidden) onIncoming?.invoke(updated, incomingNew)
        }
    }

    /** Keeps the chat-list preview consistent with what this device actually shows. */
    suspend fun refreshLastMessage(cid: String) {
        val c = db.conversationDao().get(cid) ?: return
        val latest = db.messageDao().latestVisible(cid, c.clearedAt) ?: return
        val preview = previewText(latest)
        if (latest.messageId == c.lastMessageId && preview == c.lastMessageText && latest.createdAt == c.lastMessageTimestamp) return
        db.conversationDao().update(
            c.copy(
                lastMessageId = latest.messageId,
                lastMessageText = preview,
                lastMessageTimestamp = latest.createdAt,
                lastMessageSenderId = latest.senderId,
                lastMessageType = latest.type,
                isHidden = c.isHidden && latest.createdAt <= c.clearedAt,
            )
        )
    }

    // ------------------------------------------------------------------ receipts

    fun markDelivered(cid: String, me: String, upTo: Long) {
        if (upTo <= (deliveredWritten[cid] ?: 0L)) return
        deliveredWritten[cid] = upTo
        firestore.collection(Fs.CONVERSATIONS).document(cid).update(mapOf("receipts.$me.d" to upTo.toTimestamp()))
            .addOnFailureListener { deliveredWritten.remove(cid) }
    }

    /** Marks everything in the conversation read locally and (if enabled) sends a read receipt. */
    suspend fun markRead(cid: String) {
        val me = uid ?: currentUid() ?: return
        val c = db.conversationDao().get(cid) ?: return
        val latestIncoming = db.messageDao().latestIncomingAt(cid, me) ?: 0L
        db.conversationDao().markReadUpTo(cid, maxOf(latestIncoming, c.lastMessageTimestamp))
        db.conversationDao().recomputeUnread(cid, me)
        if (c.markedUnread) writeChatPref(me, cid, mapOf("markedUnread" to false))
        if (c.isLocalOnly || latestIncoming <= 0) return
        if (prefs.current.readReceipts) {
            if (latestIncoming > (readWritten[cid] ?: 0L)) {
                readWritten[cid] = latestIncoming
                deliveredWritten[cid] = maxOf(deliveredWritten[cid] ?: 0L, latestIncoming)
                firestore.collection(Fs.CONVERSATIONS).document(cid).update(
                    mapOf("receipts.$me.r" to latestIncoming.toTimestamp(), "receipts.$me.d" to latestIncoming.toTimestamp())
                ).addOnFailureListener { readWritten.remove(cid) }
            }
        } else {
            markDelivered(cid, me, latestIncoming)
        }
    }

    // ------------------------------------------------------------------ typing

    private fun updateTyping(cid: String, me: String, remote: Map<String, Long>) {
        val now = System.currentTimeMillis()
        val serverNow = serverClock.now()
        val active = remote.filter { (u, at) -> u != me && serverNow - at < PresencePolicy.TYPING_TIMEOUT_MS }
            .mapValues { (_, at) -> now + (PresencePolicy.TYPING_TIMEOUT_MS - (serverNow - at)).coerceIn(0L, PresencePolicy.TYPING_TIMEOUT_MS) }
        val current = _typing.value.toMutableMap()
        if (active.isEmpty()) current.remove(cid) else current[cid] = active
        _typing.value = current
    }

    private fun pruneTyping() {
        val now = System.currentTimeMillis()
        val current = _typing.value
        if (current.isEmpty()) return
        val pruned = current.mapValues { (_, m) -> m.filterValues { it > now } }.filterValues { it.isNotEmpty() }
        if (pruned != current) _typing.value = pruned
    }

    // ------------------------------------------------------------------ chat preferences

    fun writeChatPref(me: String, cid: String, values: Map<String, Any?>) {
        firestore.collection(Fs.USERS).document(me).collection(Fs.CHAT_PREFS).document(cid)
            .set(values + ("updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
    }

    private suspend fun applyChatPrefs(changes: List<DocumentChange>) {
        for (change in changes) {
            if (change.type == DocumentChange.Type.REMOVED) continue
            val doc = change.document
            val data = doc.data
            db.withTransaction {
                val c = db.conversationDao().get(doc.id)
                if (c == null) pendingPrefs[doc.id] = data else db.conversationDao().upsert(applyPrefs(c, data))
            }
        }
    }

    private fun applyPrefs(c: Conversation, p: Map<String, Any?>): Conversation {
        val clearedAt = maxOf(c.clearedAt, (p["clearedAt"] as? Number)?.toLong() ?: 0L)
        val hidden = (p["hidden"] as? Boolean ?: c.isHidden) && c.lastMessageTimestamp <= clearedAt
        return c.copy(
            isPinned = p["pinned"] as? Boolean ?: c.isPinned,
            pinnedAt = (p["pinnedAt"] as? Number)?.toLong() ?: c.pinnedAt,
            isArchived = p["archived"] as? Boolean ?: c.isArchived,
            mutedUntil = (p["mutedUntil"] as? Number)?.toLong() ?: c.mutedUntil,
            isFavorite = p["favorite"] as? Boolean ?: c.isFavorite,
            markedUnread = p["markedUnread"] as? Boolean ?: c.markedUnread,
            clearedAt = clearedAt,
            isHidden = hidden,
        )
    }

    // ------------------------------------------------------------------ contacts & blocks

    private suspend fun applyContacts(me: String, changes: List<DocumentChange>) {
        val ids = mutableListOf<String>()
        for (change in changes) {
            val doc = change.document
            if (change.type == DocumentChange.Type.REMOVED) {
                db.contactDao().delete(me, doc.id)
            } else {
                db.contactDao().upsert(
                    Contact(
                        contactId = "${me}_${doc.id}", ownerUserId = me, targetUserId = doc.id,
                        customNickname = doc.getString("nickname"), addedAt = doc.millis("addedAt").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                )
                ids += doc.id
            }
        }
        users.ensureProfiles(ids)
    }

    private suspend fun applyBlocked(me: String, changes: List<DocumentChange>) {
        for (change in changes) {
            val doc = change.document
            if (change.type == DocumentChange.Type.REMOVED) db.contactDao().unblock(me, doc.id)
            else db.contactDao().block(BlockedUser(me, doc.id, doc.millis("blockedAt").takeIf { it > 0 } ?: System.currentTimeMillis()))
        }
        users.ensureProfiles(changes.map { it.document.id })
    }

    companion object {
        private const val TAG = "SyncManager"
        const val MESSAGE_WINDOW = 500L
        const val NOTIFY_WINDOW_MS = 6 * 60 * 60 * 1000L

        /** Raw preview content; the UI adds the localized type label/icon. */
        fun previewText(m: Message): String = when {
            m.deletedForEveryone -> ""
            m.type == MessageType.DOCUMENT -> m.fileName ?: m.content
            m.type == MessageType.CONTACT -> m.contactName ?: m.content
            m.type == MessageType.POLL -> m.pollQuestion ?: m.content
            m.type == MessageType.AUDIO -> m.content
            else -> m.content
        }
    }
}
