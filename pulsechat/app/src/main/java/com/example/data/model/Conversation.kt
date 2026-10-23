package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.GroupRoster
import com.example.domain.Ids
import com.example.domain.model.MessageType

/**
 * A direct or group conversation plus this device user's per-chat preferences
 * (pin/archive/mute/favourite/draft/read watermark), which are also synced to
 * `users/{uid}/chatPrefs/{conversationId}`.
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey
    val conversationId: String,
    val isGroup: Boolean = false,
    val title: String = "",
    val avatarUrl: String? = null,
    val participantIds: String = "", // Comma-separated user IDs
    val lastMessageText: String? = null,
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val lastMessageSenderId: String? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // v3: group metadata
    val description: String? = null,
    val adminIds: String = "",
    val createdBy: String? = null,
    val onlyAdminsCanSend: Boolean = false,
    val onlyAdminsCanEditInfo: Boolean = true,
    val disappearingSeconds: Long = 0L,
    // v3: list rendering
    val lastMessageId: String? = null,
    val lastMessageType: MessageType? = null,
    val peerId: String? = null,
    // v3: per-user preferences
    val pinnedAt: Long = 0L,
    val isArchived: Boolean = false,
    /** Legacy (before v5). Mutes now live in chat_notification_settings; this column is only read once to move them. */
    val mutedUntil: Long = 0L,
    val isFavorite: Boolean = false,
    val markedUnread: Boolean = false,
    val draftText: String? = null,
    val draftUpdatedAt: Long = 0L,
    val lastReadAt: Long = 0L,
    val clearedAt: Long = 0L,
    val isHidden: Boolean = false,
    // v3: sync bookkeeping
    val syncCursor: Long = 0L,
    val remoteUpdatedAt: Long = 0L,
    val isLeft: Boolean = false,
    val isLocalOnly: Boolean = false,
    // v7 — Feature 3: Group @all permission & cooldown watermark
    val onlyAdminsCanMentionAll: Boolean = true,
    val lastMentionAllAt: Long = 0L,
) {
    fun getParticipantsList(): List<String> = participantIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun getAdminList(): List<String> = adminIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val isAiConversation: Boolean get() = Ids.AI_ASSISTANT_ID in getParticipantsList()


    fun roster(): GroupRoster = GroupRoster(
        participants = getParticipantsList(),
        admins = getAdminList(),
        createdBy = createdBy,
        onlyAdminsCanSend = onlyAdminsCanSend,
        onlyAdminsCanEditInfo = onlyAdminsCanEditInfo,
        onlyAdminsCanMentionAll = onlyAdminsCanMentionAll,
        lastMentionAllAt = lastMentionAllAt,
    )

    companion object {
        fun joinIds(ids: Collection<String>): String = ids.filter { it.isNotBlank() }.distinct().joinToString(",")
    }
}
