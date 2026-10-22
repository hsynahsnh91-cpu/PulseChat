package com.example.data.repository

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.model.ChatListRow
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.MessageSearchRow
import com.example.data.model.Receipt
import com.example.data.model.StarredRow
import com.example.data.model.User
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.data.remote.RemoteMappers
import com.example.data.remote.toTimestamp
import com.example.domain.ChatListPolicy
import com.example.domain.DeliveryPolicy
import com.example.domain.DisappearingPolicy
import com.example.domain.EditHistory
import com.example.domain.GroupError
import com.example.domain.GroupPolicy
import com.example.domain.Ids
import com.example.domain.MediaRefs
import com.example.domain.MessageOrder
import com.example.domain.MessagePolicy
import com.example.domain.PresencePolicy
import com.example.domain.TextTools
import com.example.domain.VideoEditMath
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.domain.model.TransferState
import com.example.util.ConnectivityMonitor
import com.example.util.ServerClock
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class ChatError {
    NOT_SIGNED_IN, NOT_ALLOWED, BLOCKED_CONTACT, LOCAL_ONLY, LEFT_GROUP, EMPTY, TOO_LONG, TOO_LARGE,
    EDIT_WINDOW_PASSED, DELETE_WINDOW_PASSED, PIN_LIMIT, EXIT_GROUP_FIRST, FILE_UNREADABLE, SEND_FAILED,
    UPDATE_FAILED, GROUP_INVALID, NOT_FOUND
}

class ChatException(val error: ChatError) : Exception(error.name)

/** The cover of a video message: its position (ms) and a small JPEG (Base64), from the video editor. */
data class VideoCover(val coverMs: Long, val thumb: String?)

/** Write side of messaging. Every mutation is applied to Room first, then committed to Firestore. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(
    private val firestore: FirebaseFirestore,
    private val db: AppDatabase,
    private val sync: SyncManager,
    private val media: MediaRepository,
    private val users: UserRepository,
    private val prefs: AppPreferences,
    private val connectivity: ConnectivityMonitor,
    private val serverClock: ServerClock,
    private val scope: CoroutineScope,
    private val currentUid: () -> String?,
) {
    /** Send failures are reported to Settings › Diagnostics (set by AppContainer). */
    var diagnostics: DiagnosticsLog? = null
    /** Stops live sharing when its message is deleted for everyone (set by AppContainer). */
    var onLiveLocationDeleted: ((String) -> Unit)? = null
    private val _errors = MutableSharedFlow<ChatError>(extraBufferCapacity = 8)
    /** One-off failures of background commits, surfaced as snackbars. */
    val errors: SharedFlow<ChatError> = _errors.asSharedFlow()

    private val lastTypingWrite = ConcurrentHashMap<String, Long>()
    private val typingState = ConcurrentHashMap<String, Boolean>()

    private fun requireUid(): String = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
    private fun convRef(cid: String) = firestore.collection(Fs.CONVERSATIONS).document(cid)
    private fun msgRef(cid: String, mid: String) = convRef(cid).collection(Fs.MESSAGES).document(mid)

    // ================================================================== reads

    fun observeChatList(uid: String): Flow<List<ChatListRow>> = db.conversationDao().observeChatList(uid)
    fun observeConversation(cid: String): Flow<Conversation?> = db.conversationDao().observe(cid)
    fun observeGroups(uid: String): Flow<List<Conversation>> = db.conversationDao().observeGroups(uid)
    fun observeUnreadChats(uid: String): Flow<Int> = db.conversationDao().observeUnreadChats(uid, System.currentTimeMillis())
    fun observeReceipts(cid: String): Flow<List<Receipt>> = db.receiptDao().observeForConversation(cid)
    fun observeStarred(uid: String): Flow<List<StarredRow>> = db.messageDao().observeStarred(uid)
    fun observeMessage(id: String): Flow<Message?> = db.messageDao().observe(id)

    fun observeMessages(cid: String, limit: Int): Flow<List<Message>> =
        observeConversation(cid).map { it?.clearedAt ?: 0L }.distinctUntilChanged()
            .flatMapLatest { cleared -> db.messageDao().observeRecent(cid, cleared, limit) }

    fun observeMedia(cid: String): Flow<List<Message>> =
        observeConversation(cid).map { it?.clearedAt ?: 0L }.distinctUntilChanged()
            .flatMapLatest { cleared -> db.messageDao().observeMedia(cid, cleared) }

    fun observeTyping(cid: String): Flow<Set<String>> = sync.typing.map { it[cid]?.keys ?: emptySet() }.distinctUntilChanged()
    fun observeAllTyping(): Flow<Map<String, Set<String>>> = sync.typing.map { m -> m.mapValues { it.value.keys } }.distinctUntilChanged()

    suspend fun getConversation(cid: String): Conversation? = db.conversationDao().get(cid)
    suspend fun getMessage(id: String): Message? = db.messageDao().get(id)
    suspend fun getMessages(ids: List<String>): List<Message> = db.messageDao().getMany(ids)
    suspend fun forwardTargets(): List<Conversation> = db.conversationDao().forwardTargets(requireUid())

    suspend fun searchInConversation(cid: String, query: String): List<Message> {
        val c = db.conversationDao().get(cid) ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return db.messageDao().searchInConversation(cid, c.clearedAt, query.trim())
    }

    suspend fun searchAll(query: String, type: MessageType?, linksOnly: Boolean): List<MessageSearchRow> =
        db.messageDao().searchAll(requireUid(), query.trim(), type?.name ?: "", linksOnly)

    /** Position (from the newest) of a message in the visible timeline, used for jump-to-message. */
    suspend fun positionFromNewest(message: Message): Int {
        val c = db.conversationDao().get(message.conversationId) ?: return 0
        return db.messageDao().countNewerThan(message.conversationId, c.clearedAt, message.createdAt, message.messageId)
    }

    // ================================================================== opening chats

    /** Deterministic direct-chat ID: opening a chat twice never creates duplicates. */
    suspend fun openDirectChat(peerId: String): String {
        val me = requireUid()
        val cid = Ids.directConversationId(me, peerId)
        if (db.conversationDao().get(cid) == null) {
            db.conversationDao().upsert(
                Conversation(
                    conversationId = cid, isGroup = false, participantIds = Conversation.joinIds(listOf(me, peerId).sorted()),
                    peerId = peerId, lastMessageTimestamp = 0L, isHidden = true,
                    disappearingSeconds = 0L,
                )
            )
        }
        users.ensureProfiles(listOf(peerId))
        return cid
    }

    /** The private, device-local Gemini assistant conversation. */
    suspend fun ensureAiConversation(assistantName: String): String {
        val me = requireUid()
        val cid = Ids.AI_CONVERSATION_ID + "_" + me
        if (db.userDao().get(Ids.AI_ASSISTANT_ID) == null) {
            db.userDao().upsert(User(userId = Ids.AI_ASSISTANT_ID, username = "gemini", displayName = assistantName, isAiAssistant = true, avatarColorHex = "#7C3AED"))
        }
        if (db.conversationDao().get(cid) == null) {
            db.conversationDao().upsert(
                Conversation(
                    conversationId = cid, title = assistantName, participantIds = Conversation.joinIds(listOf(me, Ids.AI_ASSISTANT_ID)),
                    peerId = Ids.AI_ASSISTANT_ID, isLocalOnly = true, lastMessageTimestamp = System.currentTimeMillis(),
                )
            )
        }
        return cid
    }

    // ================================================================== sending

    private suspend fun requireWritable(cid: String, me: String): Conversation {
        val conv = db.conversationDao().get(cid) ?: throw ChatException(ChatError.NOT_FOUND)
        if (conv.isAiConversation) return conv
        if (conv.isLocalOnly) throw ChatException(ChatError.LOCAL_ONLY)
        if (conv.isLeft) throw ChatException(ChatError.LEFT_GROUP)
        if (conv.isGroup && !GroupPolicy.canSend(conv.roster(), me)) throw ChatException(ChatError.NOT_ALLOWED)
        val peer = conv.peerId
        if (!conv.isGroup && peer != null && peer in db.contactDao().blockedIds(me)) throw ChatException(ChatError.BLOCKED_CONTACT)
        return conv
    }

    private fun initialStatus(): MessageStatus = if (connectivity.isOnline) MessageStatus.SENDING else MessageStatus.PENDING

    suspend fun sendText(cid: String, text: String, replyTo: Message?, mentions: List<String>): Message {
        val me = requireUid()
        val conv = requireWritable(cid, me)
        when (MessagePolicy.validateText(text)) {
            MessagePolicy.TextError.EMPTY -> throw ChatException(ChatError.EMPTY)
            MessagePolicy.TextError.TOO_LONG -> throw ChatException(ChatError.TOO_LONG)
            null -> Unit
        }
        val now = nextCreatedAt(conv)
        val m = withReply(
            Message(
                messageId = Ids.newMessageId(), conversationId = cid, senderId = me, content = text.trim(),
                type = MessageType.TEXT, status = if (conv.isAiConversation) MessageStatus.SENT else initialStatus(),
                createdAt = now, mentions = mentions.distinct().joinToString(","),
                expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
            ), replyTo
        )
        insertLocal(m, clearDraft = true)
        if (!conv.isAiConversation) pushMessage(m, conv)
        return m
    }

    private fun withReply(m: Message, replyTo: Message?): Message = if (replyTo == null) m else m.copy(
        replyToMessageId = replyTo.messageId,
        replyToSenderId = replyTo.senderId,
        replyToType = replyTo.type,
        replyToPreview = TextTools.preview(SyncManager.previewText(replyTo).ifBlank { replyTo.fileName.orEmpty() }, 120),
    )

    /**
     * Time stamp for a message created on this phone now: the estimated server time (device time in
     * chats that stay on the phone), but never earlier than the newest message already in the chat.
     * So a new message is listed last right away - also while it is still uploading and when the
     * phone's clock is behind - until the server's own time replaces it.
     */
    suspend fun nextCreatedAt(cid: String, localOnly: Boolean = false): Long =
        MessageOrder.nextCreatedAt(if (localOnly) System.currentTimeMillis() else serverClock.now(), db.messageDao().newestCreatedAt(cid))

    private suspend fun nextCreatedAt(conv: Conversation): Long = nextCreatedAt(conv.conversationId, conv.isLocalOnly)

    /** Inserts a device-local message (used by the AI assistant conversation). */
    suspend fun insertLocal(m: Message, clearDraft: Boolean = false) {
        db.withTransaction {
            db.messageDao().upsert(m)
            if (clearDraft) db.conversationDao().setDraft(m.conversationId, null, 0L)
            db.conversationDao().setHidden(m.conversationId, false)
            sync.refreshLastMessage(m.conversationId)
        }
    }

    /**
     * Private reply to a status update: a normal message in the one-to-one chat with its author,
     * quoting the status ([quote] is the short description shown in the quote).
     */
    suspend fun sendStatusReply(ownerId: String, statusId: String, quote: String, text: String): Message {
        val cid = openDirectChat(ownerId)
        val quoted = Message(messageId = "status_$statusId", conversationId = cid, senderId = ownerId, content = quote, type = MessageType.TEXT)
        return sendText(cid, text.trim(), quoted, emptyList())
    }

    suspend fun sendContact(cid: String, name: String, phone: String) {
        val me = requireUid()
        val conv = requireWritable(cid, me)
        val now = nextCreatedAt(conv)
        val m = Message(
            messageId = Ids.newMessageId(), conversationId = cid, senderId = me, content = name.trim(), type = MessageType.CONTACT,
            status = initialStatus(), createdAt = now, contactName = name.trim().take(100), contactPhone = phone.trim().take(40),
            expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
        )
        insertLocal(m)
        pushMessage(m, conv)
    }

    suspend fun sendLocation(cid: String, lat: Double, lng: Double) {
        val me = requireUid()
        val conv = requireWritable(cid, me)
        val now = nextCreatedAt(conv)
        val m = Message(
            messageId = Ids.newMessageId(), conversationId = cid, senderId = me, content = "", type = MessageType.LOCATION,
            status = initialStatus(), createdAt = now, locationLat = lat, locationLng = lng,
            expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
        )
        insertLocal(m)
        pushMessage(m, conv)
    }

    /** The chat message that shows a live-location session (points are written by LiveLocationRepository). */
    suspend fun sendLiveLocation(cid: String, lat: Double, lng: Double, liveId: String, liveUntil: Long) {
        val me = requireUid()
        val conv = requireWritable(cid, me)
        val now = nextCreatedAt(conv)
        val m = Message(
            messageId = Ids.newMessageId(), conversationId = cid, senderId = me, content = "", type = MessageType.LOCATION,
            status = initialStatus(), createdAt = now, locationLat = lat, locationLng = lng, liveId = liveId, liveUntil = liveUntil,
            expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
        )
        insertLocal(m)
        pushMessage(m, conv)
    }

    /** Throws when the user may not post in [cid] (checked before live sharing starts). */
    suspend fun checkCanPost(cid: String) {
        val me = requireUid()
        requireWritable(cid, me)
    }

    suspend fun sendPoll(cid: String, question: String, options: List<String>) {
        val me = requireUid()
        val conv = requireWritable(cid, me)
        val clean = options.map { it.trim().replace("|", "/") }.filter { it.isNotEmpty() }.distinct().take(12)
        if (question.isBlank() || clean.size < 2) throw ChatException(ChatError.EMPTY)
        val now = nextCreatedAt(conv)
        val m = Message(
            messageId = Ids.newMessageId(), conversationId = cid, senderId = me, content = question.trim(), type = MessageType.POLL,
            status = initialStatus(), createdAt = now, pollQuestion = question.trim().take(300), pollOptions = clean.joinToString("|"),
            expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
        )
        insertLocal(m)
        pushMessage(m, conv)
    }

    suspend fun sendMediaUri(cid: String, uri: Uri, type: MessageType, caption: String, replyTo: Message?, cover: VideoCover? = null) {
        val me = requireUid()
        val conv = requireWritable(cid, me)
        val local = try {
            media.importUri(uri, type, media.effectiveMaxBytes(type))
        } catch (e: MediaTooLargeException) {
            throw ChatException(ChatError.TOO_LARGE)
        } catch (e: Exception) {
            Log.w(TAG, "import failed", e)
            throw ChatException(ChatError.FILE_UNREADABLE)
        }
        sendLocalMedia(conv, me, local, MediaRepository.typeForMime(local.mime).let { if (type == MessageType.DOCUMENT) MessageType.DOCUMENT else it }, caption, null, replyTo, forwarded = false, cover = cover)
    }

    suspend fun sendVoice(cid: String, file: File, durationMs: Long, waveform: List<Int>, replyTo: Message?) {
        val me = requireUid()
        val conv = requireWritable(cid, me)
        val local = media.importFile(file, "audio/mp4", "voice_${System.currentTimeMillis()}.m4a")
        sendLocalMedia(conv, me, local.copy(durationMs = durationMs), MessageType.AUDIO, "", waveform.joinToString(","), replyTo, forwarded = false)
    }

    private suspend fun sendLocalMedia(
        conv: Conversation, me: String, local: LocalMedia, type: MessageType, caption: String, waveform: String?,
        replyTo: Message?, forwarded: Boolean, cover: VideoCover? = null,
    ) {
        // Every video travels with a small cover (the one chosen in the editor, or a frame near the
        // start), so the other side sees it in the chat before downloading the video.
        val coverMs = if (type == MessageType.VIDEO) (cover?.coverMs ?: VideoEditMath.defaultCoverMs(local.durationMs)) else 0L
        val thumb = if (type != MessageType.VIDEO) null else {
            cover?.thumb ?: runCatching { media.videoCover(local.path, coverMs) }.getOrNull()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        }
        val now = nextCreatedAt(conv)
        val id = Ids.newMessageId()
        val ext = MediaRepository.extensionFor(local.mime, local.fileName)
        val m = withReply(
            Message(
                messageId = id, conversationId = conv.conversationId, senderId = me, content = caption.trim(), type = type,
                status = initialStatus(), createdAt = now, localMediaPath = local.path, mediaMimeType = local.mime,
                mediaSizeBytes = local.sizeBytes, fileName = local.fileName, mediaWidth = local.width, mediaHeight = local.height,
                mediaDurationSeconds = (local.durationMs / 1000L).toInt(), waveform = waveform,
                transferState = TransferState.UPLOADING, transferProgress = 0,
                storagePath = "chat-media/${conv.conversationId}/$me/$id.$ext", isForwarded = forwarded,
                expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
                mediaThumb = thumb, mediaCoverMs = coverMs,
            ), replyTo
        )
        insertLocal(m, clearDraft = caption.isNotBlank())
        uploadAndPush(m, conv)
    }

    private fun uploadAndPush(m: Message, conv: Conversation) {
        scope.launch(Dispatchers.IO) {
            var lastReported = -10
            try {
                db.messageDao().setTransfer(m.messageId, TransferState.UPLOADING, 0)
                val uploaded = media.upload(
                    key = m.messageId,
                    file = File(m.localMediaPath!!),
                    storagePath = m.storagePath!!,
                    mime = m.mediaMimeType ?: "application/octet-stream",
                    customMetadata = mapOf("participants" to conv.participantIds),
                ) { p ->
                    // 100 % is recorded by setUploadedRef; a late progress write must not undo it.
                    if (p < 100 && (p < lastReported || p - lastReported >= 5)) {
                        lastReported = p
                        scope.launch(Dispatchers.IO) { db.messageDao().setTransfer(m.messageId, TransferState.UPLOADING, p) }
                    }
                }
                db.messageDao().setUploadedRef(m.messageId, uploaded.url, uploaded.path)
                pushMessage(m.copy(mediaUrl = uploaded.url, storagePath = uploaded.path, transferState = TransferState.UPLOADED), conv)
            } catch (e: Exception) {
                val cancelled = e is CancellationException || e.javaClass.simpleName.contains("Cancel")
                Log.w(TAG, "upload failed for ${m.messageId}: ${e.message}")
                db.messageDao().setTransfer(m.messageId, TransferState.UPLOAD_FAILED, 0)
                markFailed(m.messageId, when {
                    cancelled -> "cancelled"
                    e is MediaTooLargeException -> "too_large"
                    else -> "upload"
                })
            }
        }
    }

    fun cancelUpload(messageId: String) = media.cancelUpload(messageId)

    /** Writes the message (idempotent create keyed by message ID) and the chat preview in one batch. */
    private fun pushMessage(m: Message, conv: Conversation, extra: Map<String, Any?> = emptyMap()) {
        buildMessageBatch(m, conv, extra).commit()
            .addOnSuccessListener { sync.enqueue { advance(m.messageId, MessageStatus.SENT) } }
            .addOnFailureListener { e ->
                Log.w(TAG, "send ${m.messageId} failed: ${e.message}")
                sync.enqueue { markFailed(m.messageId, reasonFor(e)) }
            }
    }

    /** The message document (created under its own ID) plus the chat preview, as one atomic batch. */
    private fun buildMessageBatch(m: Message, conv: Conversation, extra: Map<String, Any?> = emptyMap()): WriteBatch {
        val ref = convRef(conv.conversationId)
        val batch = firestore.batch()
        if (!conv.isGroup) {
            batch.set(ref, mapOf("type" to Fs.TYPE_DIRECT, "participants" to conv.getParticipantsList().sorted()), SetOptions.merge())
        }
        batch.set(ref.collection(Fs.MESSAGES).document(m.messageId), RemoteMappers.messageCreateMap(m, m.expiresAt) + extra)
        batch.update(
            ref, mapOf(
                "lastMessage" to mapOf(
                    "id" to m.messageId, "senderId" to m.senderId, "type" to m.type.name,
                    "preview" to TextTools.preview(SyncManager.previewText(m), 100), "createdAt" to FieldValue.serverTimestamp(),
                ),
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "typing.${m.senderId}" to FieldValue.delete(),
            )
        )
        typingState[conv.conversationId] = false
        return batch
    }

    // ================================================================== scheduled messages

    enum class ScheduledResult { SENT, ALREADY_SENT, RETRY, FAILED }
    data class ScheduledOutcome(val result: ScheduledResult, val reason: String? = null)

    /** Throws [ChatException] when the user may not post in [cid] now (used when scheduling). */
    suspend fun checkCanSchedule(cid: String) {
        val me = requireUid()
        val conv = requireWritable(cid, me)
        if (conv.isAiConversation) throw ChatException(ChatError.NOT_ALLOWED)
    }

    /**
     * Sends a scheduled text under its pre-assigned message ID, exactly once:
     * 1. the server is asked whether that message already exists (an earlier attempt may have
     *    succeeded without the app learning about it) - if so, nothing is written;
     * 2. otherwise the same atomic batch as a normal send is committed and the server
     *    acknowledgement awaited;
     * 3. a duplicate create is impossible: the document ID is fixed and the security rules reject
     *    re-creating an existing message, so racing attempts end with one message.
     * Authorization is the normal message-create rule (sender = signed-in member of the chat).
     */
    suspend fun sendScheduledText(id: String, cid: String, text: String, owner: String): ScheduledOutcome {
        val me = currentUid() ?: return ScheduledOutcome(ScheduledResult.RETRY, "not_signed_in")
        if (me != owner) return ScheduledOutcome(ScheduledResult.FAILED, "other_account")
        val conv = try {
            requireWritable(cid, me)
        } catch (e: ChatException) {
            return ScheduledOutcome(ScheduledResult.FAILED, e.error.name.lowercase())
        }
        if (conv.isAiConversation) return ScheduledOutcome(ScheduledResult.FAILED, "not_allowed")
        when (MessagePolicy.validateText(text.trim())) {
            MessagePolicy.TextError.EMPTY -> return ScheduledOutcome(ScheduledResult.FAILED, "empty")
            MessagePolicy.TextError.TOO_LONG -> return ScheduledOutcome(ScheduledResult.FAILED, "too_long")
            null -> Unit
        }
        if (!connectivity.isOnline) return ScheduledOutcome(ScheduledResult.RETRY, "offline")
        when (serverHasMessage(conv, id)) {
            true -> { confirmSent(id); return ScheduledOutcome(ScheduledResult.ALREADY_SENT) }
            null -> return ScheduledOutcome(ScheduledResult.RETRY, "check_failed")
            false -> Unit
        }
        val now = nextCreatedAt(conv)
        val existing = db.messageDao().get(id)
        val m = existing?.copy(status = MessageStatus.SENDING, failureReason = null) ?: Message(
            messageId = id, conversationId = cid, senderId = me, content = text.trim(), type = MessageType.TEXT,
            status = MessageStatus.SENDING, createdAt = now, expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
        )
        if (existing == null) insertLocal(m) else db.messageDao().setStatus(id, MessageStatus.SENDING, null)
        return try {
            val acked = withTimeoutOrNull(SCHEDULED_SEND_TIMEOUT_MS) { buildMessageBatch(m, conv).commit().await(); true }
            if (acked == true) {
                advance(id, MessageStatus.SENT)
                ScheduledOutcome(ScheduledResult.SENT)
            } else {
                // The write may still be queued; the next attempt asks the server first.
                ScheduledOutcome(ScheduledResult.RETRY, "timeout")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                // Either a racing attempt created it first (re-creating is denied) or posting is not allowed.
                if (serverHasMessage(conv, id) == true) { confirmSent(id); ScheduledOutcome(ScheduledResult.ALREADY_SENT) }
                else {
                    // Never reached the server: drop the local copy; the scheduled entry (retry/edit/delete) represents it.
                    db.withTransaction { db.messageDao().deleteHard(listOf(id)); sync.refreshLastMessage(cid) }
                    diagnostics?.record(DiagnosticsLog.SEND, "send_failed", "denied")
                    ScheduledOutcome(ScheduledResult.FAILED, "denied")
                }
            } else {
                ScheduledOutcome(ScheduledResult.RETRY, e.code.name.lowercase())
            }
        } catch (e: Exception) {
            ScheduledOutcome(ScheduledResult.RETRY, e.javaClass.simpleName)
        }
    }

    /** true/false = the server has / doesn't have the message; null = unknown (offline, error). */
    private suspend fun serverHasMessage(conv: Conversation, id: String): Boolean? = try {
        withTimeoutOrNull(15_000) { msgRef(conv.conversationId, id).get(Source.SERVER).await().exists() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: FirebaseFirestoreException) {
        // A direct chat whose document doesn't exist yet can't be read - and can't contain the message.
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && !conv.isGroup) false else null
    } catch (e: Exception) {
        null
    }

    private suspend fun confirmSent(id: String) {
        if (db.messageDao().get(id) != null) advance(id, MessageStatus.SENT)
    }

    // ================================================================== media & link browser

    fun observeMessagesOfTypes(cid: String, types: List<MessageType>, limit: Int = 500): Flow<List<Message>> =
        observeConversation(cid).map { it?.clearedAt ?: 0L }.distinctUntilChanged()
            .flatMapLatest { cleared -> db.messageDao().observeByTypes(cid, cleared, types.map { it.name }, limit) }

    fun observeMessagesWithLinks(cid: String, limit: Int = 500): Flow<List<Message>> =
        observeConversation(cid).map { it?.clearedAt ?: 0L }.distinctUntilChanged()
            .flatMapLatest { cleared -> db.messageDao().observeWithLinks(cid, cleared, limit) }

    /** Visible messages after [since] (oldest first), used as AI context after an explicit user action. */
    suspend fun messagesSince(cid: String, since: Long, limit: Int): List<Message> {
        val cleared = db.conversationDao().get(cid)?.clearedAt ?: 0L
        return db.messageDao().since(cid, maxOf(since, cleared), limit)
    }

    /** The newest visible messages (oldest first). */
    suspend fun recentMessages(cid: String, limit: Int): List<Message> {
        val cleared = db.conversationDao().get(cid)?.clearedAt ?: 0L
        return db.messageDao().recent(cid, cleared, limit).asReversed()
    }

    private suspend fun advance(id: String, status: MessageStatus) {
        val cur = db.messageDao().get(id) ?: return
        val next = DeliveryPolicy.advance(cur.status, status)
        if (next != cur.status) db.messageDao().setStatus(id, next, null)
    }

    private suspend fun markFailed(id: String, reason: String) {
        val cur = db.messageDao().get(id) ?: return
        val next = DeliveryPolicy.advance(cur.status, MessageStatus.FAILED)
        if (next == MessageStatus.FAILED) {
            db.messageDao().setStatus(id, MessageStatus.FAILED, reason)
            diagnostics?.record(DiagnosticsLog.SEND, "send_failed", reason)
        }
    }

    private fun reasonFor(e: Exception): String =
        if ((e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "denied" else "error"

    /** Retries a failed message with the same ID, after checking the server doesn't already have it. */
    suspend fun retry(messageId: String) {
        val m = db.messageDao().get(messageId) ?: return
        if (m.status != MessageStatus.FAILED) return
        val me = requireUid()
        val conv = requireWritable(m.conversationId, me)
        db.messageDao().setStatus(m.messageId, initialStatus(), null)
        if (m.type.isMedia && m.mediaUrl == null) {
            if (m.localMediaPath != null && File(m.localMediaPath).exists()) uploadAndPush(m, conv)
            else markFailed(m.messageId, "missing_file")
            return
        }
        if (connectivity.isOnline) {
            try {
                if (msgRef(m.conversationId, m.messageId).get(Source.SERVER).await().exists()) {
                    advance(m.messageId, MessageStatus.SENT)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "retry existence check failed: ${e.message}")
            }
        }
        pushMessage(m, conv)
    }

    /** After a restart: re-drive messages that never got a server acknowledgement. */
    suspend fun resendPending() {
        val me = currentUid() ?: return
        for (m in db.messageDao().pendingOutgoing(me)) {
            val conv = db.conversationDao().get(m.conversationId) ?: continue
            if (conv.isLocalOnly) continue
            if (m.type.isMedia && m.mediaUrl == null) {
                if (!media.isUploading(m.messageId)) {
                    db.messageDao().setTransfer(m.messageId, TransferState.UPLOAD_FAILED, 0)
                    markFailed(m.messageId, "interrupted")
                }
                continue
            }
            if (!connectivity.isOnline) continue // Firestore keeps its own persisted write queue
            try {
                if (msgRef(m.conversationId, m.messageId).get(Source.SERVER).await().exists()) advance(m.messageId, MessageStatus.SENT)
                else pushMessage(m, conv)
            } catch (e: Exception) {
                Log.w(TAG, "resend check failed: ${e.message}")
            }
        }
        db.messageDao().interruptedTransfers().filter { it.transferState == TransferState.DOWNLOADING }.forEach {
            db.messageDao().setTransfer(it.messageId, TransferState.NONE, 0)
        }
    }

    // ================================================================== message actions

    private fun facts(m: Message, me: String, conv: Conversation?) = MessagePolicy.Facts(
        isMine = m.senderId == me, type = m.type, status = m.status, createdAt = m.createdAt,
        deletedForEveryone = m.deletedForEveryone, hasText = m.content.isNotBlank(), isAiConversation = conv?.isAiConversation == true,
    )

    private fun commit(batch: WriteBatch, onFailure: suspend () -> Unit) {
        batch.commit().addOnFailureListener { e ->
            Log.w(TAG, "commit failed: ${e.message}")
            sync.enqueue { onFailure() }
            _errors.tryEmit(ChatError.UPDATE_FAILED)
        }
    }

    private fun touchConversation(batch: WriteBatch, cid: String, lastPreview: String? = null) {
        val values = mutableMapOf<String, Any?>("updatedAt" to FieldValue.serverTimestamp())
        if (lastPreview != null) values["lastMessage.preview"] = lastPreview
        batch.update(convRef(cid), values)
    }

    private fun touchConversationBestEffort(cid: String, lastPreview: String? = null) {
        val values = mutableMapOf<String, Any?>("updatedAt" to FieldValue.serverTimestamp())
        if (lastPreview != null) values["lastMessage.preview"] = lastPreview
        convRef(cid).update(values).addOnFailureListener { e ->
            Log.d(TAG, "touchConversation skipped: ${e.message}")
        }
    }

    suspend fun editMessage(messageId: String, newText: String) {
        val me = requireUid()
        val m = db.messageDao().get(messageId) ?: throw ChatException(ChatError.NOT_FOUND)
        val conv = db.conversationDao().get(m.conversationId)
        if (!MessagePolicy.canEdit(facts(m, me, conv), serverClock.now())) throw ChatException(ChatError.EDIT_WINDOW_PASSED)
        when (MessagePolicy.validateText(newText)) {
            MessagePolicy.TextError.EMPTY -> throw ChatException(ChatError.EMPTY)
            MessagePolicy.TextError.TOO_LONG -> throw ChatException(ChatError.TOO_LONG)
            null -> Unit
        }
        val text = newText.trim()
        if (text == m.content) return
        if (!EditHistory.canEditAgain(m.editHistory)) throw ChatException(ChatError.NOT_ALLOWED)
        // The replaced text joins the message's edit history (readable by the chat's members only,
        // removed with the message; see domain/EditHistory and firestore.rules editByAuthor).
        val at = maxOf(serverClock.now(), m.createdAt + 1L)
        db.messageDao().update(m.copy(content = text, editedAt = at, editHistory = EditHistory.append(m.editHistory, m.content, at)))
        sync.refreshLastMessage(m.conversationId)
        val ref = msgRef(m.conversationId, m.messageId)
        val baseUpdate = mapOf(
            "text" to text,
            "editedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        val withHistory = baseUpdate + ("edits" to FieldValue.arrayUnion(mapOf("text" to m.content, "at" to at.toTimestamp())))
        val preview = if (conv?.lastMessageId == m.messageId && conv.lastMessageSenderId == me) TextTools.preview(text, 100) else null
        ref.update(withHistory)
            .addOnSuccessListener { touchConversationBestEffort(m.conversationId, preview) }
            .addOnFailureListener { e ->
                // Compatible with servers whose live rules have not yet added `edits` to editByAuthor().
                ref.update(baseUpdate)
                    .addOnSuccessListener { touchConversationBestEffort(m.conversationId, preview) }
                    .addOnFailureListener { e2 ->
                        Log.w(TAG, "editMessage failed: ${e2.message ?: e.message}")
                        sync.enqueue { db.messageDao().update(m); sync.refreshLastMessage(m.conversationId) }
                        _errors.tryEmit(ChatError.UPDATE_FAILED)
                    }
            }
    }

    suspend fun deleteForMe(ids: List<String>) {
        val messages = db.messageDao().getMany(ids)
        db.messageDao().deleteForMe(ids)
        media.deleteLocal(messages.map { it.localMediaPath })
        val me = currentUid()
        messages.map { it.conversationId }.distinct().forEach { cid ->
            sync.refreshLastMessage(cid)
            if (me != null) db.conversationDao().recomputeUnread(cid, me)
        }
    }

    suspend fun deleteForEveryone(ids: List<String>) {
        val me = requireUid()
        val messages = db.messageDao().getMany(ids)
        val now = serverClock.now()
        val allowed = messages.filter { m -> MessagePolicy.canDeleteForEveryone(facts(m, me, db.conversationDao().get(m.conversationId)), now) }
        if (allowed.size < messages.size) _errors.tryEmit(ChatError.DELETE_WINDOW_PASSED)
        for (m in allowed) {
            val conv = db.conversationDao().get(m.conversationId)
            db.messageDao().update(
                m.copy(
                    deletedForEveryone = true, content = "", mediaUrl = null, localMediaPath = null, reactions = "", isStarred = false,
                    replyToMessageId = null, replyToPreview = null, replyToSenderId = null, pollOptions = null, pollQuestion = null,
                    contactName = null, contactPhone = null, locationLat = null, locationLng = null, mentions = "", transferState = TransferState.NONE,
                    editHistory = "", liveId = null, liveUntil = 0L, mediaThumb = null, mediaCoverMs = 0L,
                )
            )
            media.deleteLocal(listOf(m.localMediaPath))
            sync.refreshLastMessage(m.conversationId)
            val baseDelete = mapOf(
                "deletedForEveryone" to true, "text" to "", "media" to FieldValue.delete(), "replyTo" to FieldValue.delete(),
                "poll" to FieldValue.delete(), "contact" to FieldValue.delete(), "location" to FieldValue.delete(),
                "mentions" to emptyList<String>(), "reactions" to emptyMap<String, String>(), "updatedAt" to FieldValue.serverTimestamp(),
            )
            val withEditsDelete = baseDelete + ("edits" to FieldValue.delete())
            val preview = if (conv?.lastMessageId == m.messageId && conv.lastMessageSenderId == me) "" else null
            val batch = firestore.batch()
            batch.update(msgRef(m.conversationId, m.messageId), withEditsDelete)
            // A live location goes with its message: sharing stops and the shared points are deleted.
            m.liveId?.let { liveId ->
                onLiveLocationDeleted?.invoke(liveId)
                batch.delete(convRef(m.conversationId).collection(Fs.LIVE).document(liveId))
            }
            batch.commit()
                .addOnSuccessListener { touchConversationBestEffort(m.conversationId, preview) }
                .addOnFailureListener { e ->
                    msgRef(m.conversationId, m.messageId).update(baseDelete)
                        .addOnSuccessListener { touchConversationBestEffort(m.conversationId, preview) }
                        .addOnFailureListener { e2 ->
                            Log.w(TAG, "deleteForEveryone failed: ${e2.message ?: e.message}")
                            sync.enqueue { db.messageDao().update(m); sync.refreshLastMessage(m.conversationId) }
                            _errors.tryEmit(ChatError.UPDATE_FAILED)
                        }
                }
            if (!m.storagePath.isNullOrBlank()) scope.launch(Dispatchers.IO) { media.deleteRemote(m.storagePath) }
        }
    }

    /** Sets (or toggles off) my single reaction on a message. */
    suspend fun react(messageId: String, emoji: String?) {
        val me = requireUid()
        val m = db.messageDao().get(messageId) ?: return
        val conv = db.conversationDao().get(m.conversationId)
        if (!MessagePolicy.canReact(facts(m, me, conv))) return
        val map = m.reactionMap().toMutableMap()
        val newValue = if (emoji == null || map[me] == emoji) null else emoji.take(16)
        if (newValue == null) map.remove(me) else map[me] = newValue
        db.messageDao().update(m.copy(reactions = Message.encodeMap(map)))
        val batch = firestore.batch()
        batch.update(msgRef(m.conversationId, m.messageId), mapOf("reactions.$me" to (newValue ?: FieldValue.delete()), "updatedAt" to FieldValue.serverTimestamp()))
        touchConversation(batch, m.conversationId)
        commit(batch) { db.messageDao().update(m) }
    }

    suspend fun votePoll(messageId: String, optionIndex: Int) {
        val me = requireUid()
        val m = db.messageDao().get(messageId) ?: return
        if (m.type != MessageType.POLL || m.deletedForEveryone || optionIndex !in m.pollOptionList().indices) return
        if (m.senderId == me && DeliveryPolicy.isAwaitingServer(m.status)) return
        val votes = m.pollVoteMap().toMutableMap()
        val newValue = if (votes[me] == optionIndex) null else optionIndex
        if (newValue == null) votes.remove(me) else votes[me] = newValue
        db.messageDao().update(m.copy(pollVotesByUser = Message.encodeMap(votes)))
        val batch = firestore.batch()
        batch.update(msgRef(m.conversationId, m.messageId), mapOf("poll.votes.$me" to (newValue ?: FieldValue.delete()), "updatedAt" to FieldValue.serverTimestamp()))
        touchConversation(batch, m.conversationId)
        commit(batch) { db.messageDao().update(m) }
    }

    suspend fun setStarred(ids: List<String>, starred: Boolean) = db.messageDao().setStarred(ids, starred)

    /** Forwards messages as new messages (new IDs, "Forwarded" label, no reply/mentions). */
    suspend fun forward(messageIds: List<String>, targetIds: List<String>): Int {
        val me = requireUid()
        val messages = db.messageDao().getMany(messageIds).sortedBy { it.createdAt }
        val forwardable = messages.filter { MessagePolicy.canForward(facts(it, me, null)) }
        var sent = 0
        for (cid in targetIds.distinct()) {
            val conv = try { requireWritable(cid, me) } catch (e: ChatException) { _errors.tryEmit(e.error); continue }
            for (src in forwardable) {
                val now = nextCreatedAt(conv)
                if (src.type.isMedia) {
                    val path = src.localMediaPath?.takeIf { File(it).exists() }
                        ?: src.mediaUrl?.takeIf { MediaRefs.isPulse(it) }?.let { ref ->
                            runCatching { media.cachedStoreFile(ref).absolutePath }.getOrNull()
                        }
                    if (path != null && File(path).exists()) {
                        val copy = File(media.mediaDir, "fwd_${Ids.newId("m")}.${MediaRepository.extensionFor(src.mediaMimeType ?: "", src.fileName ?: "")}")
                        File(path).copyTo(copy, overwrite = true)
                        val local = LocalMedia(copy.absolutePath, src.mediaMimeType ?: "application/octet-stream", copy.length(), src.fileName ?: copy.name, src.mediaWidth, src.mediaHeight, src.mediaDurationSeconds * 1000L)
                        sendLocalMedia(conv, me, local, src.type, src.content, src.waveform, null, forwarded = true,
                            cover = if (src.type == MessageType.VIDEO) VideoCover(src.mediaCoverMs, src.mediaThumb) else null)
                        sent++
                    } else if (src.mediaUrl != null) {
                        val m = src.copy(
                            messageId = Ids.newMessageId(), conversationId = cid, senderId = me, status = initialStatus(), createdAt = now,
                            isForwarded = true, replyToMessageId = null, replyToPreview = null, replyToSenderId = null, replyToType = null,
                            mentions = "", reactions = "", isStarred = false, deletedForMe = false, localMediaPath = null,
                            transferState = TransferState.NONE, updatedAt = 0L, editedAt = 0L, pollVotesByUser = "",
                            editHistory = "", failureReason = null,
                            expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
                        )
                        insertLocal(m)
                        pushMessage(m, conv)
                        sent++
                    }
                } else {
                    val m = Message(
                        messageId = Ids.newMessageId(), conversationId = cid, senderId = me, content = src.content, type = src.type,
                        status = initialStatus(), createdAt = now, isForwarded = true, locationLat = src.locationLat, locationLng = src.locationLng,
                        contactName = src.contactName, contactPhone = src.contactPhone,
                        expiresAt = DisappearingPolicy.expiresAt(now, conv.disappearingSeconds),
                    ).let { if (it.type == MessageType.AI_RESPONSE) it.copy(type = MessageType.TEXT) else it }
                    insertLocal(m)
                    pushMessage(m, conv)
                    sent++
                }
            }
        }
        return sent
    }

    // ================================================================== read state, drafts, typing

    suspend fun markRead(cid: String) {
        val c = db.conversationDao().get(cid) ?: return
        if (c.isLocalOnly) {
            db.conversationDao().markReadUpTo(cid, c.lastMessageTimestamp)
            currentUid()?.let { db.conversationDao().recomputeUnread(cid, it) }
        } else sync.markRead(cid)
    }

    suspend fun setDraft(cid: String, text: String) =
        db.conversationDao().setDraft(cid, text.takeIf { it.isNotBlank() }, System.currentTimeMillis())

    /** Publishes my typing state (throttled; never queued while offline). */
    fun setTyping(cid: String, typing: Boolean) {
        val me = currentUid() ?: return
        if (!connectivity.isOnline) return
        val now = System.currentTimeMillis()
        val was = typingState[cid] ?: false
        if (typing && was && now - (lastTypingWrite[cid] ?: 0L) < PresencePolicy.TYPING_THROTTLE_MS) return
        if (!typing && !was) return
        scope.launch(Dispatchers.IO) {
            val c = db.conversationDao().get(cid) ?: return@launch
            if (c.isLocalOnly || c.isLeft || c.lastMessageId == null && !c.isGroup) return@launch
            typingState[cid] = typing
            lastTypingWrite[cid] = now
            convRef(cid).update(mapOf("typing.$me" to (if (typing) FieldValue.serverTimestamp() else FieldValue.delete())))
        }
    }

    // ================================================================== chat preferences

    suspend fun setPinned(cid: String, pinned: Boolean): Boolean {
        val me = requireUid()
        val c = db.conversationDao().get(cid) ?: return false
        if (pinned && !ChatListPolicy.canPin(db.conversationDao().pinnedCount(me), c.isPinned)) {
            _errors.tryEmit(ChatError.PIN_LIMIT)
            return false
        }
        val at = if (pinned) System.currentTimeMillis() else 0L
        db.conversationDao().setPinned(cid, pinned, at)
        if (!c.isLocalOnly) sync.writeChatPref(me, cid, mapOf("pinned" to pinned, "pinnedAt" to at))
        return true
    }

    suspend fun setArchived(cid: String, archived: Boolean) = pref(cid, mapOf("archived" to archived)) {
        db.conversationDao().setArchived(cid, archived)
        if (archived) db.conversationDao().setPinned(cid, false, 0L)
    }

    suspend fun setFavorite(cid: String, favorite: Boolean) = pref(cid, mapOf("favorite" to favorite)) { db.conversationDao().setFavorite(cid, favorite) }

    suspend fun setMarkedUnread(cid: String, unread: Boolean) {
        if (!unread) { markRead(cid); return }
        pref(cid, mapOf("markedUnread" to true)) { db.conversationDao().setMarkedUnread(cid, true) }
    }

    private suspend fun pref(cid: String, values: Map<String, Any?>, local: suspend () -> Unit) {
        val me = requireUid()
        local()
        val c = db.conversationDao().get(cid) ?: return
        if (!c.isLocalOnly) sync.writeChatPref(me, cid, values)
    }

    suspend fun clearChat(cid: String, keepStarred: Boolean) {
        val me = requireUid()
        val now = maxOf(System.currentTimeMillis(), serverClock.now())
        val withMedia = db.messageDao().withLocalMedia(cid).filter { !keepStarred || !it.isStarred }
        db.withTransaction {
            db.messageDao().clearConversation(cid, keepStarred)
            db.conversationDao().clear(cid, now)
        }
        media.deleteLocal(withMedia.map { it.localMediaPath })
        val c = db.conversationDao().get(cid)
        if (c != null && !c.isLocalOnly) sync.writeChatPref(me, cid, mapOf("clearedAt" to now, "markedUnread" to false))
    }

    /** Deletes the chat for me. Groups must be exited first. */
    suspend fun deleteChat(cid: String) {
        val me = requireUid()
        val c = db.conversationDao().get(cid) ?: return
        if (c.isGroup && !c.isLeft && !c.isLocalOnly) throw ChatException(ChatError.EXIT_GROUP_FIRST)
        clearChat(cid, keepStarred = false)
        db.conversationDao().setHidden(cid, true)
        db.conversationDao().setPinned(cid, false, 0L)
        if (!c.isLocalOnly) sync.writeChatPref(me, cid, mapOf("hidden" to true, "pinned" to false, "pinnedAt" to 0L))
    }

    // ================================================================== groups

    private fun systemExtra(me: String, event: String, targets: List<String> = emptyList(), value: String? = null): Map<String, Any?> =
        mapOf("system" to mapOf("event" to event, "actor" to me, "targets" to targets, "value" to value))

    private suspend fun systemMessage(cid: String, me: String, event: String, targets: List<String> = emptyList(), value: String? = null): Message =
        Message(
            messageId = Ids.newMessageId(), conversationId = cid, senderId = me, content = "", type = MessageType.SYSTEM,
            status = initialStatus(), createdAt = nextCreatedAt(cid),
            systemEvent = JSONObject(mapOf("event" to event, "actor" to me, "targets" to org.json.JSONArray(targets), "value" to (value ?: ""))).toString(),
        )

    suspend fun createGroup(name: String, description: String, photo: Uri?, memberIds: List<String>): String {
        val me = requireUid()
        val errors = GroupPolicy.validateCreate(name, description, memberIds, me)
        if (errors.isNotEmpty()) throw GroupValidationException(errors)
        val cid = Ids.newId("grp")
        val participants = (listOf(me) + memberIds).distinct()
        val photoUrl = photo?.let { uri ->
            runCatching {
                val bytes = media.compressImage(uri, 640, 85) ?: return@runCatching null
                media.uploadBytes(
                    bytes = bytes,
                    storagePath = "group-photos/$cid/$me/photo_${System.currentTimeMillis()}.jpg",
                    mime = "image/jpeg",
                    customMetadata = mapOf("participants" to participants.joinToString(",")),
                ).url
            }.getOrNull()
        }
        val now = System.currentTimeMillis()
        val seconds = prefs.current.defaultDisappearingSeconds
        val conv = Conversation(
            conversationId = cid, isGroup = true, title = name.trim(), avatarUrl = photoUrl, participantIds = Conversation.joinIds(participants),
            description = description.trim().ifBlank { null }, adminIds = me, createdBy = me, onlyAdminsCanSend = false,
            onlyAdminsCanEditInfo = true, disappearingSeconds = seconds, lastMessageTimestamp = now, createdAt = now,
        )
        val sys = systemMessage(cid, me, "created", value = name.trim())
        db.withTransaction {
            db.conversationDao().upsert(conv)
            db.messageDao().upsert(sys)
            sync.refreshLastMessage(cid)
        }
        val batch = firestore.batch()
        batch.set(
            convRef(cid), mapOf(
                "type" to Fs.TYPE_GROUP, "participants" to participants, "admins" to listOf(me), "createdBy" to me,
                "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
                "title" to name.trim(), "description" to description.trim(), "photoUrl" to photoUrl,
                "onlyAdminsCanSend" to false, "onlyAdminsCanEditInfo" to true, "disappearingSeconds" to seconds,
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "lastMessage" to mapOf("id" to sys.messageId, "senderId" to me, "type" to MessageType.SYSTEM.name, "preview" to "", "createdAt" to FieldValue.serverTimestamp()),
            )
        )
        batch.set(msgRef(cid, sys.messageId), RemoteMappers.messageCreateMap(sys, 0L) + systemExtra(me, "created", value = name.trim()))
        batch.commit()
            .addOnSuccessListener { sync.enqueue { advance(sys.messageId, MessageStatus.SENT) } }
            .addOnFailureListener { e -> Log.w(TAG, "createGroup failed", e); _errors.tryEmit(ChatError.UPDATE_FAILED); sync.enqueue { markFailed(sys.messageId, reasonFor(e)) } }
        return cid
    }

    private suspend fun groupOp(cid: String, check: (com.example.domain.GroupRoster, String) -> Boolean, update: Map<String, Any?>, event: String, targets: List<String> = emptyList(), value: String? = null) {
        val me = requireUid()
        val c = db.conversationDao().get(cid) ?: throw ChatException(ChatError.NOT_FOUND)
        if (!c.isGroup || c.isLeft || !check(c.roster(), me)) throw ChatException(ChatError.NOT_ALLOWED)
        val sys = systemMessage(cid, me, event, targets, value)
        insertLocal(sys)
        val batch = firestore.batch()
        batch.update(convRef(cid), update + mapOf(
            "updatedAt" to FieldValue.serverTimestamp(), "lastMessageAt" to FieldValue.serverTimestamp(),
            "lastMessage" to mapOf("id" to sys.messageId, "senderId" to me, "type" to MessageType.SYSTEM.name, "preview" to "", "createdAt" to FieldValue.serverTimestamp()),
        ))
        batch.set(msgRef(cid, sys.messageId), RemoteMappers.messageCreateMap(sys, 0L) + systemExtra(me, event, targets, value))
        batch.commit()
            .addOnSuccessListener { sync.enqueue { advance(sys.messageId, MessageStatus.SENT) } }
            .addOnFailureListener { e -> Log.w(TAG, "group op $event failed", e); _errors.tryEmit(ChatError.UPDATE_FAILED); sync.enqueue { markFailed(sys.messageId, reasonFor(e)) } }
    }

    suspend fun addMembers(cid: String, ids: List<String>) {
        val c = db.conversationDao().get(cid) ?: return
        val fresh = ids.filter { it !in c.getParticipantsList() }.distinct()
        if (fresh.isEmpty()) return
        groupOp(cid, { r, me -> GroupPolicy.canAdd(r, me, fresh) }, mapOf("participants" to FieldValue.arrayUnion(*fresh.toTypedArray())), "added", fresh)
        db.conversationDao().upsert(c.copy(participantIds = Conversation.joinIds(c.getParticipantsList() + fresh)))
        users.ensureProfiles(fresh)
    }

    suspend fun removeMember(cid: String, target: String) {
        groupOp(cid, { r, me -> GroupPolicy.canRemove(r, me, target) },
            mapOf("participants" to FieldValue.arrayRemove(target), "admins" to FieldValue.arrayRemove(target)), "removed", listOf(target))
        db.conversationDao().get(cid)?.let { c ->
            val r = GroupPolicy.remove(c.roster(), target)
            db.conversationDao().upsert(c.copy(participantIds = Conversation.joinIds(r.participants), adminIds = Conversation.joinIds(r.admins)))
        }
    }

    suspend fun setAdmin(cid: String, target: String, admin: Boolean) {
        if (admin) groupOp(cid, { r, me -> GroupPolicy.canPromote(r, me, target) }, mapOf("admins" to FieldValue.arrayUnion(target)), "promoted", listOf(target))
        else groupOp(cid, { r, me -> GroupPolicy.canDemote(r, me, target) }, mapOf("admins" to FieldValue.arrayRemove(target)), "demoted", listOf(target))
        db.conversationDao().get(cid)?.let { c ->
            val admins = if (admin) c.getAdminList() + target else c.getAdminList() - target
            db.conversationDao().upsert(c.copy(adminIds = Conversation.joinIds(admins)))
        }
    }

    /** Leaves a group; the last admin hands admin rights to the longest-standing member. */
    suspend fun leaveGroup(cid: String) {
        val me = requireUid()
        val c = db.conversationDao().get(cid) ?: return
        val outcome = GroupPolicy.leave(c.roster(), me) as? GroupPolicy.LeaveOutcome.Left ?: return
        val update = mutableMapOf<String, Any?>("participants" to FieldValue.arrayRemove(me))
        update["admins"] = if (outcome.promoted != null) outcome.roster.admins else FieldValue.arrayRemove(me)
        update["typing.$me"] = FieldValue.delete()
        groupOp(cid, { r, uid -> GroupPolicy.isMember(r, uid) }, update, "left", listOfNotNull(outcome.promoted))
        db.conversationDao().upsert(
            c.copy(participantIds = Conversation.joinIds(outcome.roster.participants), adminIds = Conversation.joinIds(outcome.roster.admins), isLeft = true, isPinned = false)
        )
    }

    suspend fun updateGroupInfo(cid: String, name: String, description: String, photo: Uri?) {
        val me = requireUid()
        val c = db.conversationDao().get(cid) ?: return
        val errors = GroupPolicy.validateCreate(name, description, listOf(me, "placeholder"), me)
        if (errors.isNotEmpty()) throw GroupValidationException(errors)
        val update = mutableMapOf<String, Any?>("title" to name.trim(), "description" to description.trim())
        var photoUrl = c.avatarUrl
        if (photo != null) {
            val bytes = media.compressImage(photo, 640, 85) ?: throw ChatException(ChatError.FILE_UNREADABLE)
            photoUrl = media.uploadBytes(
                bytes = bytes,
                storagePath = "group-photos/$cid/$me/photo_${System.currentTimeMillis()}.jpg",
                mime = "image/jpeg",
                customMetadata = mapOf("participants" to c.participantIds),
            ).url
            update["photoUrl"] = photoUrl
        }
        val event = when {
            photo != null -> "photo"
            name.trim() != c.title -> "title"
            else -> "description"
        }
        groupOp(cid, { r, uid -> GroupPolicy.canEditInfo(r, uid) }, update, event, value = if (event == "title") name.trim() else null)
        db.conversationDao().upsert(c.copy(title = name.trim(), description = description.trim().ifBlank { null }, avatarUrl = photoUrl))
    }

    suspend fun setGroupPermissions(cid: String, onlyAdminsCanSend: Boolean, onlyAdminsCanEditInfo: Boolean) {
        val c = db.conversationDao().get(cid) ?: return
        groupOp(cid, { r, uid -> GroupPolicy.canChangeSettings(r, uid) },
            mapOf("onlyAdminsCanSend" to onlyAdminsCanSend, "onlyAdminsCanEditInfo" to onlyAdminsCanEditInfo), "settings",
            value = "send=${if (onlyAdminsCanSend) "admins" else "all"};edit=${if (onlyAdminsCanEditInfo) "admins" else "all"}")
        db.conversationDao().upsert(c.copy(onlyAdminsCanSend = onlyAdminsCanSend, onlyAdminsCanEditInfo = onlyAdminsCanEditInfo))
    }

    suspend fun setDisappearing(cid: String, seconds: Long) {
        val me = requireUid()
        if (!DisappearingPolicy.isValidOption(seconds)) return
        val c = db.conversationDao().get(cid) ?: return
        if (c.isGroup) {
            groupOp(cid, { r, uid -> GroupPolicy.canEditInfo(r, uid) }, mapOf("disappearingSeconds" to seconds), "disappearing", value = seconds.toString())
        } else {
            requireWritable(cid, me)
            val sys = systemMessage(cid, me, "disappearing", value = seconds.toString())
            insertLocal(sys)
            pushMessage(sys, c, systemExtra(me, "disappearing", value = seconds.toString()) + mapOf("text" to ""))
            convRef(cid).update(mapOf("disappearingSeconds" to seconds))
        }
        db.conversationDao().upsert((db.conversationDao().get(cid) ?: c).copy(disappearingSeconds = seconds))
    }

    // ================================================================== history, push, housekeeping

    /** Loads an older page of history from the server (pagination beyond the live window). */
    suspend fun loadOlder(cid: String, pageSize: Long = 50): Int {
        val me = requireUid()
        val c = db.conversationDao().get(cid) ?: return 0
        if (c.isLocalOnly) return 0
        val oldest = db.messageDao().oldestCreatedAt(cid) ?: System.currentTimeMillis()
        val snap = convRef(cid).collection(Fs.MESSAGES)
            .whereLessThan("createdAt", com.google.firebase.Timestamp(java.util.Date(oldest)))
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(pageSize).get().await()
        if (snap.isEmpty) return 0
        sync.applyMessageDocs(me, cid, snap.documents, fromCache = false, notify = false, advanceCursor = false)
        return snap.size()
    }

    /** Fetches a message announced by a push notification (app may not have live listeners). */
    suspend fun fetchFromPush(cid: String, messageId: String): Boolean {
        val me = currentUid() ?: return false
        return try {
            if (db.conversationDao().get(cid) == null) {
                val cdoc = convRef(cid).get().await()
                if (!cdoc.exists()) return false
                sync.applyConversationDoc(me, cdoc)
            }
            val doc = msgRef(cid, messageId).get().await()
            if (!doc.exists()) return false
            sync.applyMessageDocs(me, cid, listOf(doc), fromCache = false, notify = true, advanceCursor = false)
            true
        } catch (e: Exception) {
            Log.w(TAG, "fetchFromPush failed: ${e.message}")
            false
        }
    }

    suspend fun purgeExpired() {
        val expired = db.messageDao().expired(serverClock.now())
        if (expired.isEmpty()) return
        media.deleteLocal(expired.map { it.localMediaPath })
        db.messageDao().deleteHard(expired.map { it.messageId })
        val me = currentUid()
        expired.map { it.conversationId }.distinct().forEach { cid ->
            sync.refreshLastMessage(cid)
            if (me != null) db.conversationDao().recomputeUnread(cid, me)
        }
    }

    suspend fun downloadMedia(messageId: String) {
        val m = db.messageDao().get(messageId) ?: return
        if (m.deletedForEveryone || (m.mediaUrl == null && m.storagePath == null)) return
        if (m.localMediaPath != null && File(m.localMediaPath).exists()) return
        db.messageDao().setTransfer(messageId, TransferState.DOWNLOADING, 0)
        var lastReported = -10
        try {
            val file = media.download(messageId, m.storagePath, m.mediaUrl, m.fileName, m.mediaMimeType) { p ->
                if (p < 100 && p - lastReported >= 5) {
                    lastReported = p
                    scope.launch(Dispatchers.IO) { db.messageDao().setTransfer(messageId, TransferState.DOWNLOADING, p) }
                }
            }
            db.messageDao().setLocalMedia(messageId, file.absolutePath, TransferState.DOWNLOADED)
        } catch (e: Exception) {
            val cancelled = e is CancellationException || e.javaClass.simpleName.contains("Cancel")
            db.messageDao().setTransfer(messageId, if (cancelled) TransferState.NONE else TransferState.DOWNLOAD_FAILED, 0)
            if (!cancelled) _errors.tryEmit(ChatError.UPDATE_FAILED)
        }
    }

    fun cancelDownload(messageId: String) = media.cancelDownload(messageId)

    fun clearSessionState() {
        lastTypingWrite.clear()
        typingState.clear()
    }

    suspend fun clearMediaFor(cid: String) {
        val files = db.messageDao().withLocalMedia(cid).filter { it.mediaUrl != null }
        media.deleteLocal(files.map { it.localMediaPath })
        db.messageDao().forgetLocalMedia(cid)
    }

    companion object {
        private const val SCHEDULED_SEND_TIMEOUT_MS = 45_000L
        private const val TAG = "ChatRepository"
    }
}

class GroupValidationException(val errors: List<GroupError>) : Exception(errors.joinToString())
