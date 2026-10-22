package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.domain.model.TransferState
import org.json.JSONObject

/**
 * A chat message. [messageId] is a client-generated UUID used as the Firestore document ID, so
 * retries and duplicate deliveries are idempotent.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId"),
        Index("createdAt"),
        Index("senderId"),
        Index(value = ["conversationId", "createdAt"]),
    ]
)
data class Message(
    @PrimaryKey
    val messageId: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val recipientId: String? = null,
    val content: String = "",
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val mediaUrl: String? = null, // remote download URL (legacy rows may hold a content:// URI)
    val mediaDurationSeconds: Int = 0,
    val replyToMessageId: String? = null,
    val isAiGenerated: Boolean = false,
    val fileName: String? = null,
    val fileSize: String? = null,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val pollQuestion: String? = null,
    val pollOptions: String? = null, // Pipe-separated options
    val pollVotes: String? = null,   // Legacy comma-separated counts
    // v3
    val updatedAt: Long = 0L,
    val editedAt: Long = 0L,
    val deletedForEveryone: Boolean = false,
    val deletedForMe: Boolean = false,
    val isStarred: Boolean = false,
    val isForwarded: Boolean = false,
    val reactions: String = "",       // JSON object {userId: emoji}
    val mentions: String = "",        // Comma-separated user IDs
    val replyToSenderId: String? = null,
    val replyToPreview: String? = null,
    val replyToType: MessageType? = null,
    val localMediaPath: String? = null,
    val mediaMimeType: String? = null,
    val mediaSizeBytes: Long = 0L,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val waveform: String? = null,     // Comma-separated 0..100 amplitudes
    val transferState: TransferState = TransferState.NONE,
    val transferProgress: Int = 0,
    val storagePath: String? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val pollVotesByUser: String = "", // JSON object {userId: optionIndex}
    val expiresAt: Long = 0L,
    val failureReason: String? = null,
    val systemEvent: String? = null,  // JSON describing group events
    // v5
    /** Earlier versions of an edited text, oldest first: JSON [{"text": ..., "at": millis}] (see domain/EditHistory). */
    val editHistory: String = "",
    /** Live location: the session shown by this LOCATION message and when it ends. */
    val liveId: String? = null,
    val liveUntil: Long = 0L,
    // v6
    /** Video cover chosen by the sender: a small JPEG (Base64) shown before the video is downloaded. */
    val mediaThumb: String? = null,
    /** Where the cover frame is in the video (ms), so the downloaded video shows the same frame. */
    val mediaCoverMs: Long = 0L,
) {
    fun reactionMap(): Map<String, String> = parseStringMap(reactions)
    fun mentionList(): List<String> = mentions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun pollOptionList(): List<String> = pollOptions?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun pollVoteMap(): Map<String, Int> {
        if (pollVotesByUser.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(pollVotesByUser)
            o.keys().asSequence().associateWith { o.optInt(it, -1) }.filterValues { it >= 0 }
        } catch (e: Exception) { emptyMap() }
    }

    /** Vote totals per option: legacy counts (from before per-user voting) plus per-user votes. */
    fun pollCounts(): List<Int> {
        val options = pollOptionList()
        val legacy = pollVotes?.split(",")?.map { it.trim().toIntOrNull() ?: 0 } ?: emptyList()
        val votes = pollVoteMap().values
        return options.indices.map { i -> (legacy.getOrNull(i) ?: 0) + votes.count { it == i } }
    }

    val hasLocalMedia: Boolean get() = !localMediaPath.isNullOrBlank()

    companion object {
        fun parseStringMap(json: String): Map<String, String> {
            if (json.isBlank()) return emptyMap()
            return try {
                val o = JSONObject(json)
                o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotEmpty() }
            } catch (e: Exception) { emptyMap() }
        }

        fun encodeMap(map: Map<String, Any>): String = if (map.isEmpty()) "" else JSONObject(map).toString()
    }
}
