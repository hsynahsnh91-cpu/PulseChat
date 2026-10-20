package com.example.data.model

import androidx.room.Embedded
import androidx.room.Entity
import com.example.domain.ChatListEntry
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType

/** Receipt watermarks of one participant in one conversation (server timestamps, millis). */
@Entity(tableName = "receipts", primaryKeys = ["conversationId", "userId"])
data class Receipt(
    val conversationId: String,
    val userId: String,
    val deliveredUpTo: Long,
    val readUpTo: Long,
)

@Entity(tableName = "blocked_users", primaryKeys = ["ownerUserId", "blockedUserId"])
data class BlockedUser(
    val ownerUserId: String,
    val blockedUserId: String,
    val blockedAt: Long,
)

/** Who viewed one of my statuses. */
@Entity(tableName = "status_views", primaryKeys = ["statusId", "viewerId"])
data class StatusView(
    val statusId: String,
    val viewerId: String,
    val viewedAt: Long,
)

/** Chat-list row: conversation + resolved peer profile + last message state. */
data class ChatListRow(
    @Embedded val conversation: Conversation,
    val peerName: String?,
    val peerAvatar: String?,
    val peerColor: String?,
    val peerNickname: String?,
    val lastStatus: MessageStatus?,
    val lastDeleted: Boolean?,
    val lastSenderName: String?,
) : ChatListEntry {
    val displayTitle: String
        get() = when {
            conversation.isGroup -> conversation.title
            !peerNickname.isNullOrBlank() -> peerNickname
            !peerName.isNullOrBlank() -> peerName
            else -> conversation.title
        }
    val displayAvatar: String? get() = if (conversation.isGroup) conversation.avatarUrl else peerAvatar ?: conversation.avatarUrl
    override val entryId: String get() = conversation.conversationId
    override val entryTitle: String get() = displayTitle
    override val entryPreview: String? get() = conversation.lastMessageText
    override val entryPinned: Boolean get() = conversation.isPinned
    override val entryPinnedAt: Long get() = conversation.pinnedAt
    override val entryLastActivity: Long get() = conversation.lastMessageTimestamp
    override val entryUnread: Int get() = conversation.unreadCount
    override val entryMarkedUnread: Boolean get() = conversation.markedUnread
    override val entryFavorite: Boolean get() = conversation.isFavorite
    override val entryGroup: Boolean get() = conversation.isGroup
    override val entryArchived: Boolean get() = conversation.isArchived
}

data class ContactRow(
    val userId: String,
    val displayName: String,
    val username: String,
    val uniqueId10: String,
    val avatarUrl: String?,
    val avatarColorHex: String,
    val statusMessage: String,
    val customNickname: String?,
) {
    val shownName: String get() = customNickname?.takeIf { it.isNotBlank() } ?: displayName
}

data class StarredRow(
    @Embedded val message: Message,
    val conversationTitle: String?,
    val conversationIsGroup: Boolean?,
    val senderName: String?,
)

data class MessageSearchRow(
    @Embedded val message: Message,
    val conversationTitle: String?,
    val conversationIsGroup: Boolean?,
    val peerName: String?,
    val senderName: String?,
)

data class StatusViewRow(
    val viewerId: String,
    val viewedAt: Long,
    val displayName: String?,
    val avatarUrl: String?,
    val avatarColorHex: String?,
)

data class StorageRow(
    val conversationId: String,
    val title: String?,
    val peerName: String?,
    val isGroup: Boolean,
    val bytes: Long,
    val files: Int,
)

data class MediaTypeCount(val type: MessageType, val files: Int, val bytes: Long)
