package com.example.domain

import com.example.domain.model.ChatFilter

/** Minimal view of a chat-list row needed for ordering and filtering. */
interface ChatListEntry {
    val entryId: String
    val entryTitle: String
    val entryPreview: String?
    val entryPinned: Boolean
    val entryPinnedAt: Long
    val entryLastActivity: Long
    val entryUnread: Int
    val entryMarkedUnread: Boolean
    val entryFavorite: Boolean
    val entryGroup: Boolean
    val entryArchived: Boolean
}

object ChatListPolicy {
    const val MAX_PINNED = 3

    fun hasUnread(entry: ChatListEntry): Boolean = entry.entryUnread > 0 || entry.entryMarkedUnread

    fun matchesFilter(entry: ChatListEntry, filter: ChatFilter): Boolean = when (filter) {
        ChatFilter.ALL -> true
        ChatFilter.UNREAD -> hasUnread(entry)
        ChatFilter.FAVORITES -> entry.entryFavorite
        ChatFilter.GROUPS -> entry.entryGroup
    }

    /** Pinned chats first (most recently pinned on top), then by latest activity. */
    fun <T : ChatListEntry> sort(items: List<T>): List<T> = items.sortedWith(
        compareByDescending<T> { it.entryPinned }
            .thenByDescending { if (it.entryPinned) it.entryPinnedAt else 0L }
            .thenByDescending { it.entryLastActivity }
            .thenBy { it.entryId }
    )

    fun <T : ChatListEntry> arrange(items: List<T>, filter: ChatFilter, query: String, archived: Boolean): List<T> =
        sort(items.filter { it.entryArchived == archived && matchesFilter(it, filter) && TextTools.matches(query, it.entryTitle, it.entryPreview) })

    fun canPin(currentlyPinned: Int, alreadyPinned: Boolean): Boolean = alreadyPinned || currentlyPinned < MAX_PINNED

    fun unreadBadge(entry: ChatListEntry): String? = when {
        entry.entryUnread > 99 -> "99+"
        entry.entryUnread > 0 -> entry.entryUnread.toString()
        entry.entryMarkedUnread -> ""
        else -> null
    }
}
