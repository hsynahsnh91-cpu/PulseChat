package com.example.data.model

import androidx.room.Entity

/**
 * Notification settings of one chat for one account on this phone (v5). Kept apart from the
 * conversation itself so the chat's own state (messages, members, read position) never decides
 * whether or how it notifies. Mute and preview are also synced to the account's other phones
 * through users/{uid}/chatPrefs/{cid}.
 */
@Entity(tableName = "chat_notification_settings", primaryKeys = ["ownerId", "conversationId"])
data class ChatNotificationSettings(
    val ownerId: String,
    val conversationId: String,
    /** 0 = notifications on; [MUTED_FOREVER] = until the user turns them back on. */
    val mutedUntil: Long = 0L,
    /** [PREVIEW_DEFAULT] follows the app-wide setting; [PREVIEW_SHOW] / [PREVIEW_HIDE] override it for this chat. */
    val preview: String = PREVIEW_DEFAULT,
    val updatedAt: Long = 0L,
) {
    fun isMuted(now: Long = System.currentTimeMillis()): Boolean = mutedUntil > now

    companion object {
        const val MUTED_FOREVER = Long.MAX_VALUE
        const val PREVIEW_DEFAULT = "DEFAULT"
        const val PREVIEW_SHOW = "SHOW"
        const val PREVIEW_HIDE = "HIDE"
        val PREVIEWS = setOf(PREVIEW_DEFAULT, PREVIEW_SHOW, PREVIEW_HIDE)
    }
}

/**
 * A chat that opens only after the phone's own screen-lock check (v5). Device-local and never
 * synced. This is an access lock inside the app: the chat's data on the phone is not encrypted by it.
 */
@Entity(tableName = "locked_chats", primaryKeys = ["ownerId", "conversationId"])
data class LockedChat(
    val ownerId: String,
    val conversationId: String,
    val lockedAt: Long,
)
