package com.example.domain

import com.example.data.model.ChatNotificationSettings

/** Per-chat notification decisions (mute and preview), shared by the notification code and the UI. */
object NotificationPolicy {
    const val HOUR_MS = 3_600_000L

    /** The mute choices offered for one chat, in the order shown. */
    enum class MuteOption(val durationMs: Long) {
        ONE_HOUR(HOUR_MS),
        EIGHT_HOURS(8 * HOUR_MS),
        ONE_DAY(24 * HOUR_MS),
        UNTIL_TURNED_ON(-1L),
    }

    fun mutedUntil(option: MuteOption, now: Long): Long =
        if (option == MuteOption.UNTIL_TURNED_ON) ChatNotificationSettings.MUTED_FOREVER else now + option.durationMs

    fun isMuted(mutedUntil: Long, now: Long): Boolean = mutedUntil > now

    /**
     * Muted chats stay silent; in a muted group, a message that mentions me still notifies (the mute
     * dialog says so). Expired mutes need no clean-up: the time simply passes.
     */
    fun shouldNotify(mutedUntil: Long, now: Long, isGroup: Boolean, mentionsMe: Boolean): Boolean =
        !isMuted(mutedUntil, now) || (isGroup && mentionsMe)

    /** How much a message notification may show. */
    enum class Content {
        /** Sender and message text. */
        FULL,
        /** The chat / sender name, but "New message" instead of the text. */
        NAME_ONLY,
        /** Nothing identifying: app name and "New message", hidden on the lock screen. */
        HIDDEN,
    }

    /**
     * A locked chat never shows who wrote or what. Otherwise a choice made for this chat wins over the
     * app-wide "Show previews" setting (which, when off, hides names too, as before).
     */
    fun content(appPreviews: Boolean, chatPreview: String, locked: Boolean): Content = when {
        locked -> Content.HIDDEN
        chatPreview == ChatNotificationSettings.PREVIEW_SHOW -> Content.FULL
        chatPreview == ChatNotificationSettings.PREVIEW_HIDE -> Content.NAME_ONLY
        appPreviews -> Content.FULL
        else -> Content.HIDDEN
    }
}
