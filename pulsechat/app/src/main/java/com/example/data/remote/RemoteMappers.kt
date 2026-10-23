package com.example.data.remote

import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.StatusItem
import com.example.data.model.User
import com.example.domain.DeliveryPolicy
import com.example.domain.EditHistory
import com.example.domain.Ids
import com.example.domain.TextTools
import com.example.domain.VideoEditMath
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.domain.model.TransferState
import android.util.Base64
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import org.json.JSONObject
import java.io.File

/** Converts between Firestore documents and Room entities, preserving device-local state. */
object RemoteMappers {

    fun user(doc: DocumentSnapshot, existing: User?): User = User(
        userId = doc.id,
        uniqueId10 = doc.getString("uniqueId10") ?: existing?.uniqueId10.orEmpty(),
        googleId = existing?.googleId,
        username = doc.getString("username") ?: existing?.username.orEmpty(),
        displayName = doc.getString("displayName") ?: existing?.displayName.orEmpty(),
        passwordHash = existing?.passwordHash,
        avatarUrl = existing?.avatarUrl,
        statusMessage = existing?.statusMessage.orEmpty(),
        isOnline = existing?.isOnline ?: false,
        lastSeen = existing?.lastSeen ?: 0L,
        isAiAssistant = false,
        avatarColorHex = doc.getString("avatarColorHex") ?: existing?.avatarColorHex ?: "#4F46E5",
        createdAt = doc.millis("createdAt").takeIf { it > 0 } ?: existing?.createdAt ?: System.currentTimeMillis(),
        profileSyncedAt = System.currentTimeMillis(),
    )

    fun userProfileMap(uid: String, username: String, uniqueId10: String, displayName: String, colorHex: String): Map<String, Any?> = mapOf(
        "uid" to uid,
        "username" to username,
        "uniqueId10" to uniqueId10,
        "displayName" to displayName,
        "displayNameLower" to displayName.trim().lowercase(),
        "avatarColorHex" to colorHex,
        "lastSeenVisibility" to "everyone",
        "photoVisibility" to "everyone",
        "aboutVisibility" to "everyone",
        "createdAt" to FieldValue.serverTimestamp(),
    )

    /** Merges a remote conversation document with the device's per-user state. */
    fun conversation(doc: DocumentSnapshot, me: String, existing: Conversation?): Conversation {
        val participants = doc.stringList("participants")
        val isGroup = doc.getString("type") == Fs.TYPE_GROUP
        val peer = if (isGroup) null else participants.firstOrNull { it != me }
        val last = doc.map("lastMessage")
        val lastAt = doc.millis("lastMessageAt")
        val base = existing ?: Conversation(conversationId = doc.id, createdAt = doc.millis("createdAt").takeIf { it > 0 } ?: System.currentTimeMillis(), lastMessageTimestamp = lastAt)
        val lastType = (last["type"] as? String)?.let { name -> MessageType.entries.firstOrNull { it.name == name } }
        val remoteNewer = lastAt > base.lastMessageTimestamp || existing == null
        return base.copy(
            isGroup = isGroup,
            title = if (isGroup) doc.getString("title").orEmpty() else base.title,
            avatarUrl = if (isGroup) doc.getString("photoUrl") else base.avatarUrl,
            participantIds = Conversation.joinIds(participants),
            description = doc.getString("description"),
            adminIds = Conversation.joinIds(doc.stringList("admins")),
            createdBy = doc.getString("createdBy"),
            onlyAdminsCanSend = doc.getBoolean("onlyAdminsCanSend") ?: false,
            onlyAdminsCanEditInfo = doc.getBoolean("onlyAdminsCanEditInfo") ?: true,
            onlyAdminsCanMentionAll = doc.getBoolean("onlyAdminsCanMentionAll") ?: true,
            lastMentionAllAt = doc.millis("lastMentionAllAt"),
            disappearingSeconds = doc.getLong("disappearingSeconds") ?: 0L,
            peerId = peer ?: base.peerId,
            lastMessageText = if (remoteNewer && last.isNotEmpty()) last["preview"] as? String else base.lastMessageText,
            lastMessageTimestamp = if (remoteNewer && lastAt > 0) lastAt else base.lastMessageTimestamp,
            lastMessageSenderId = if (remoteNewer && last.isNotEmpty()) last["senderId"] as? String else base.lastMessageSenderId,
            lastMessageId = if (remoteNewer && last.isNotEmpty()) last["id"] as? String else base.lastMessageId,
            lastMessageType = if (remoteNewer && last.isNotEmpty()) lastType else base.lastMessageType,
            remoteUpdatedAt = doc.millis("updatedAt"),
            isLeft = false,
            isLocalOnly = false,
            isHidden = base.isHidden && lastAt <= base.clearedAt,
        )
    }

    /** Receipt watermarks stored in the conversation document: receipts.{uid}.{d|r}. */
    fun receipts(doc: DocumentSnapshot): Map<String, Pair<Long, Long>> =
        doc.map("receipts").mapNotNull { (uid, value) ->
            val m = value as? Map<*, *> ?: return@mapNotNull null
            uid to (m.millisOf("d") to m.millisOf("r"))
        }.toMap()

    /** Typing markers stored in the conversation document: typing.{uid} = timestamp. */
    fun typing(doc: DocumentSnapshot): Map<String, Long> =
        doc.map("typing").mapNotNull { (uid, value) ->
            val at = when (value) {
                is com.google.firebase.Timestamp -> value.millis()
                is Number -> value.toLong()
                else -> 0L
            }
            if (at > 0) uid to at else null
        }.toMap()

    fun message(doc: DocumentSnapshot, conversationId: String, me: String, existing: Message?): Message {
        val type = doc.getString("type")?.let { name -> MessageType.entries.firstOrNull { it.name == name } } ?: MessageType.TEXT
        val senderId = doc.getString("senderId").orEmpty()
        val media = doc.map("media")
        val reply = doc.map("replyTo")
        val contact = doc.map("contact")
        val location = doc.map("location")
        val poll = doc.map("poll")
        val reactions = doc.map("reactions").mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        val votes = (poll["votes"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Number)?.toInt()?.let { key to it } } }?.toMap() ?: emptyMap()
        val deleted = doc.getBoolean("deletedForEveryone") ?: false
        val pending = doc.metadata.hasPendingWrites()
        // A write of mine that the server has not confirmed yet keeps its local position: the SDK's
        // estimate for the server time stamp is the phone's clock, which can be minutes behind and
        // would list the message above newer ones until the confirmation arrives.
        val createdAt = existing?.createdAt?.takeIf { pending && it > 0 }
            ?: doc.millis("createdAt").takeIf { it > 0 } ?: (doc.getLong("clientCreatedAt") ?: System.currentTimeMillis())
        val acked = !pending
        val remoteStatus = when {
            senderId != me -> existing?.status ?: MessageStatus.DELIVERED
            acked -> MessageStatus.SENT
            else -> existing?.status ?: MessageStatus.SENDING
        }
        val status = if (existing != null) DeliveryPolicy.advance(existing.status, remoteStatus) else remoteStatus
        val localPath = existing?.localMediaPath?.takeIf { File(it).exists() }
        val remoteUrl = media["url"] as? String
        val transfer = when {
            deleted -> TransferState.NONE
            localPath != null && existing?.transferState == TransferState.UPLOADING -> TransferState.UPLOADING
            localPath != null -> if (senderId == me) TransferState.UPLOADED else TransferState.DOWNLOADED
            existing?.transferState == TransferState.DOWNLOADING -> TransferState.DOWNLOADING
            remoteUrl != null -> TransferState.NONE
            else -> existing?.transferState ?: TransferState.NONE
        }
        return Message(
            messageId = doc.id,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = null,
            content = if (deleted) "" else doc.getString("text").orEmpty(),
            type = type,
            status = status,
            createdAt = createdAt,
            mediaUrl = if (deleted) null else remoteUrl,
            mediaDurationSeconds = ((media["durationMs"] as? Number)?.toLong()?.div(1000L))?.toInt() ?: 0,
            replyToMessageId = reply["id"] as? String,
            isAiGenerated = false,
            fileName = media["name"] as? String,
            fileSize = null,
            locationLat = (location["lat"] as? Number)?.toDouble(),
            locationLng = (location["lng"] as? Number)?.toDouble(),
            pollQuestion = poll["question"] as? String,
            pollOptions = (poll["options"] as? List<*>)?.mapNotNull { it as? String }?.joinToString("|"),
            pollVotes = null,
            updatedAt = doc.millis("updatedAt"),
            editedAt = doc.millis("editedAt"),
            deletedForEveryone = deleted,
            deletedForMe = existing?.deletedForMe ?: false,
            isStarred = (existing?.isStarred ?: false) && !deleted,
            isForwarded = doc.getBoolean("forwarded") ?: false,
            reactions = if (deleted) "" else Message.encodeMap(reactions),
            mentions = doc.stringList("mentions").joinToString(","),
            replyToSenderId = reply["senderId"] as? String,
            replyToPreview = reply["preview"] as? String,
            replyToType = (reply["type"] as? String)?.let { name -> MessageType.entries.firstOrNull { it.name == name } },
            localMediaPath = if (deleted) null else localPath,
            mediaMimeType = media["mime"] as? String,
            mediaSizeBytes = (media["size"] as? Number)?.toLong() ?: 0L,
            mediaWidth = (media["width"] as? Number)?.toInt() ?: 0,
            mediaHeight = (media["height"] as? Number)?.toInt() ?: 0,
            waveform = media["waveform"] as? String,
            transferState = transfer,
            transferProgress = if (transfer == TransferState.UPLOADED || transfer == TransferState.DOWNLOADED) 100 else existing?.transferProgress ?: 0,
            storagePath = media["path"] as? String,
            contactName = contact["name"] as? String,
            contactPhone = contact["phone"] as? String,
            pollVotesByUser = Message.encodeMap(votes),
            expiresAt = doc.millis("expiresAt"),
            failureReason = null,
            systemEvent = (doc.get("system") as? Map<*, *>)?.let { JSONObject(it.mapKeys { k -> k.key.toString() }).toString() },
            editHistory = if (deleted) "" else editHistory(doc.get("edits")),
            liveId = if (deleted) null else location["liveId"] as? String,
            liveUntil = if (deleted) 0L else location.millisOf("liveUntil"),
            mediaThumb = if (deleted || type != MessageType.VIDEO) null else thumbOf(media["thumb"]) ?: existing?.mediaThumb,
            mediaCoverMs = if (deleted) 0L else (media["coverMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L,
            pollAnonymous = poll["anonymous"] as? Boolean ?: false,
            pollExpiresAt = poll.millisOf("expiresAt"),
            mediaQuality = (media["quality"] as? String)?.takeIf { it.isNotBlank() } ?: existing?.mediaQuality ?: "STANDARD",
            originalSizeBytes = (media["originalSize"] as? Number)?.toLong() ?: existing?.originalSizeBytes ?: 0L,
        )
    }

    /** `media.thumb` (bytes) as Base64 for the local row; oversized or malformed covers are ignored. */
    fun thumbOf(raw: Any?): String? {
        val bytes = (raw as? Blob)?.toBytes() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_THUMB_BYTES) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** The video cover fields written with a video message (only when it has a cover). */
    private fun videoCoverFields(m: Message): Map<String, Any> {
        if (m.type != MessageType.VIDEO) return emptyMap()
        val bytes = m.mediaThumb?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        val fields = HashMap<String, Any>()
        if (bytes != null && bytes.isNotEmpty() && bytes.size <= VideoEditMath.COVER_MAX_BYTES) fields["thumb"] = Blob.fromBytes(bytes)
        if (m.mediaCoverMs > 0) fields["coverMs"] = m.mediaCoverMs
        return fields
    }

    /** Covers above this are not stored (the rules refuse covers above 64 KB). */
    private const val MAX_THUMB_BYTES = 64 * 1024

    /** `edits` on a message document: [{text, at}] written with arrayUnion by each edit (oldest first). */
    fun editHistory(raw: Any?): String {
        val list = raw as? List<*> ?: return ""
        val entries = list.mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val text = entry["text"] as? String ?: return@mapNotNull null
            EditHistory.Entry(text, entry.millisOf("at"))
        }
        return EditHistory.encode(entries)
    }

    /** Full document written when a message is first sent (create-only; see firestore.rules). */
    fun messageCreateMap(m: Message, expiresAtMillis: Long): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "senderId" to m.senderId,
            "type" to m.type.name,
            "text" to m.content,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "clientCreatedAt" to m.createdAt,
            "editedAt" to null,
            "deletedForEveryone" to false,
            "forwarded" to m.isForwarded,
            "mentions" to m.mentionList(),
            "reactions" to emptyMap<String, String>(),
        )
        if (m.replyToMessageId != null) {
            map["replyTo"] = mapOf(
                "id" to m.replyToMessageId,
                "senderId" to m.replyToSenderId,
                "type" to (m.replyToType?.name ?: MessageType.TEXT.name),
                "preview" to TextTools.preview(m.replyToPreview.orEmpty(), 120),
            )
        }
        if (m.type.isMedia && m.mediaUrl != null) {
            val mediaMap = mutableMapOf<String, Any?>(
                "url" to m.mediaUrl,
                "path" to m.storagePath,
                "mime" to m.mediaMimeType,
                "size" to m.mediaSizeBytes,
                "name" to m.fileName,
                "durationMs" to m.mediaDurationSeconds * 1000L,
                "width" to m.mediaWidth,
                "height" to m.mediaHeight,
                "waveform" to m.waveform,
                "quality" to m.mediaQuality.ifBlank { "STANDARD" },
            )
            if (m.originalSizeBytes > 0L) mediaMap["originalSize"] = m.originalSizeBytes
            mediaMap.putAll(videoCoverFields(m))
            map["media"] = mediaMap
        }
        if (m.type == MessageType.CONTACT) map["contact"] = mapOf("name" to m.contactName, "phone" to m.contactPhone)
        if (m.type == MessageType.LOCATION && m.locationLat != null && m.locationLng != null) {
            // Live sessions add their ID and end time; older apps still show the starting point.
            map["location"] = if (m.liveId == null) mapOf("lat" to m.locationLat, "lng" to m.locationLng)
                else mapOf("lat" to m.locationLat, "lng" to m.locationLng, "liveId" to m.liveId, "liveUntil" to m.liveUntil)
        }
        if (m.type == MessageType.POLL) {
            val pollMap = mutableMapOf<String, Any?>(
                "question" to m.pollQuestion,
                "options" to m.pollOptionList(),
                "votes" to emptyMap<String, Int>(),
                "anonymous" to m.pollAnonymous,
            )
            if (m.pollExpiresAt > 0L) pollMap["expiresAt"] = m.pollExpiresAt.toTimestamp()
            map["poll"] = pollMap
        }
        if (expiresAtMillis > 0) map["expiresAt"] = expiresAtMillis.toTimestamp()
        return map
    }

    fun status(doc: DocumentSnapshot, existing: StatusItem?, authorName: String, authorPhoto: String?): StatusItem {
        val media = doc.map("media")
        val music = doc.map("music")
        val reshare = doc.map("resharedFrom")
        return StatusItem(
            statusId = doc.id,
            userId = doc.getString("ownerId").orEmpty(),
            authorName = authorName,
            authorPhotoUrl = authorPhoto,
            text = doc.getString("text"),
            mediaUrl = media["url"] as? String ?: existing?.mediaUrl,
            mediaType = doc.getString("type") ?: "TEXT",
            fontFamily = doc.getString("font") ?: "DEFAULT",
            backgroundColorHex = doc.getString("bgColor") ?: "#3730A3",
            createdAt = doc.millis("createdAt").takeIf { it > 0 } ?: existing?.createdAt ?: System.currentTimeMillis(),
            expiresAt = doc.millis("expiresAt"),
            isViewed = existing?.isViewed ?: false,
            localMediaPath = existing?.localMediaPath,
            uploadState = StatusItem.UPLOAD_DONE,
            uploadProgress = 100,
            audience = doc.stringList("audience").joinToString(","),
            storagePath = media["path"] as? String,
            musicTrackId = music["trackId"] as? String ?: existing?.musicTrackId,
            musicTitle = music["title"] as? String ?: existing?.musicTitle,
            musicArtist = music["artist"] as? String ?: existing?.musicArtist,
            musicUrl = music["url"] as? String ?: existing?.musicUrl,
            musicStartMs = (music["startMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: existing?.musicStartMs ?: 0L,
            musicDurationMs = (music["durationMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: existing?.musicDurationMs ?: 0L,
            mentions = doc.stringList("mentions").joinToString(","),
            allowReshare = doc.getBoolean("allowReshare") ?: true,
            resharedFromStatusId = reshare["statusId"] as? String ?: existing?.resharedFromStatusId,
            resharedFromUserId = reshare["userId"] as? String ?: existing?.resharedFromUserId,
            resharedFromAuthorName = reshare["authorName"] as? String ?: existing?.resharedFromAuthorName,
            likeCount = existing?.likeCount ?: 0,
            isLikedByMe = existing?.isLikedByMe ?: false,
        )
    }

    fun isAi(id: String) = id == Ids.AI_ASSISTANT_ID
}
