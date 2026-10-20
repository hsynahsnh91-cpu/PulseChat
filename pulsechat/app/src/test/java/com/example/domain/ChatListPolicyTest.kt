package com.example.domain

import com.example.domain.model.ChatFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListPolicyTest {
    data class Row(
        override val entryId: String,
        override val entryTitle: String = entryId,
        override val entryPreview: String? = null,
        override val entryPinned: Boolean = false,
        override val entryPinnedAt: Long = 0,
        override val entryLastActivity: Long = 0,
        override val entryUnread: Int = 0,
        override val entryMarkedUnread: Boolean = false,
        override val entryFavorite: Boolean = false,
        override val entryGroup: Boolean = false,
        override val entryArchived: Boolean = false,
    ) : ChatListEntry

    private val rows = listOf(
        Row("old", entryLastActivity = 1),
        Row("new", entryLastActivity = 9, entryUnread = 2),
        Row("pinA", entryPinned = true, entryPinnedAt = 5, entryLastActivity = 0),
        Row("pinB", entryPinned = true, entryPinnedAt = 7, entryLastActivity = 0, entryGroup = true),
        Row("arch", entryArchived = true, entryLastActivity = 100),
        Row("fav", entryFavorite = true, entryLastActivity = 3, entryMarkedUnread = true, entryTitle = "سارة"),
    )

    @Test fun pinnedFirstThenLatestActivity() {
        val result = ChatListPolicy.arrange(rows, ChatFilter.ALL, "", archived = false).map { it.entryId }
        assertEquals(listOf("pinB", "pinA", "new", "fav", "old"), result)
    }

    @Test fun archivedChatsAreSeparate() {
        assertEquals(listOf("arch"), ChatListPolicy.arrange(rows, ChatFilter.ALL, "", archived = true).map { it.entryId })
    }

    @Test fun filters() {
        assertEquals(listOf("new", "fav"), ChatListPolicy.arrange(rows, ChatFilter.UNREAD, "", false).map { it.entryId })
        assertEquals(listOf("fav"), ChatListPolicy.arrange(rows, ChatFilter.FAVORITES, "", false).map { it.entryId })
        assertEquals(listOf("pinB"), ChatListPolicy.arrange(rows, ChatFilter.GROUPS, "", false).map { it.entryId })
    }

    @Test fun searchIgnoresArabicVariants() {
        assertEquals(listOf("fav"), ChatListPolicy.arrange(rows, ChatFilter.ALL, "ساره", false).map { it.entryId })
    }

    @Test fun pinLimitAndBadges() {
        assertTrue(ChatListPolicy.canPin(2, false))
        assertFalse(ChatListPolicy.canPin(3, false))
        assertTrue(ChatListPolicy.canPin(3, true))
        assertEquals("2", ChatListPolicy.unreadBadge(rows[1]))
        assertEquals("", ChatListPolicy.unreadBadge(rows[5]))
        assertEquals("99+", ChatListPolicy.unreadBadge(Row("x", entryUnread = 150)))
        assertNull(ChatListPolicy.unreadBadge(rows[0]))
    }
}
