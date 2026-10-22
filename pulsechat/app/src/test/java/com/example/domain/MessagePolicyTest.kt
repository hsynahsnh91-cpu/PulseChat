package com.example.domain

import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MessagePolicyTest {
    private val now = 10_000_000_000L
    private fun facts(
        mine: Boolean = true, type: MessageType = MessageType.TEXT, status: MessageStatus = MessageStatus.SENT,
        age: Long = 60_000L, deleted: Boolean = false, ai: Boolean = false,
    ) = MessagePolicy.Facts(mine, type, status, now - age, deleted, true, ai)

    @Test fun editAllowedOnlyForOwnRecentAckedText() {
        assertTrue(MessagePolicy.canEdit(facts(), now))
        assertTrue(MessagePolicy.canEdit(facts(age = -5_000L), now))
        assertFalse(MessagePolicy.canEdit(facts(age = now), now))
        assertFalse(MessagePolicy.canEdit(facts(mine = false), now))
        assertFalse(MessagePolicy.canEdit(facts(age = MessagePolicy.EDIT_WINDOW_MS + 1), now))
        assertFalse(MessagePolicy.canEdit(facts(type = MessageType.IMAGE), now))
        assertFalse(MessagePolicy.canEdit(facts(status = MessageStatus.PENDING), now))
        assertFalse(MessagePolicy.canEdit(facts(deleted = true), now))
        assertFalse(MessagePolicy.canEdit(facts(ai = true), now))
    }

    @Test fun deleteForEveryoneWindow() {
        assertTrue(MessagePolicy.canDeleteForEveryone(facts(age = 47 * 3600_000L), now))
        assertFalse(MessagePolicy.canDeleteForEveryone(facts(age = 49 * 3600_000L), now))
        assertFalse(MessagePolicy.canDeleteForEveryone(facts(mine = false), now))
        assertFalse(MessagePolicy.canDeleteForEveryone(facts(status = MessageStatus.FAILED), now))
        assertFalse(MessagePolicy.canDeleteForEveryone(facts(type = MessageType.SYSTEM), now))
    }

    @Test fun deletedMessagesSupportNoContentActions() {
        val f = facts(deleted = true)
        assertFalse(MessagePolicy.canReply(f))
        assertFalse(MessagePolicy.canForward(f))
        assertFalse(MessagePolicy.canCopy(f))
        assertFalse(MessagePolicy.canReact(f))
        assertFalse(MessagePolicy.canStar(f))
    }

    @Test fun reactionsNeedServerCopy() {
        assertFalse(MessagePolicy.canReact(facts(status = MessageStatus.PENDING)))
        assertTrue(MessagePolicy.canReact(facts(mine = false, status = MessageStatus.SENT)))
        assertFalse(MessagePolicy.canReact(facts(type = MessageType.SYSTEM)))
        assertTrue(MessagePolicy.canRetry(facts(status = MessageStatus.FAILED)))
        assertFalse(MessagePolicy.canRetry(facts(mine = false, status = MessageStatus.FAILED)))
    }

    @Test fun textValidation() {
        assertEquals(MessagePolicy.TextError.EMPTY, MessagePolicy.validateText("   "))
        assertEquals(MessagePolicy.TextError.TOO_LONG, MessagePolicy.validateText("a".repeat(MessagePolicy.MAX_TEXT_LENGTH + 1)))
        assertNull(MessagePolicy.validateText("hello"))
    }

    @Test fun timelineOrderingAndDedupe() {
        data class M(val id: String, val at: Long, val upd: Long)
        val list = listOf(M("b", 2, 1), M("a", 2, 1), M("c", 1, 1), M("a", 2, 5))
        val unique = MessageTimeline.deduplicate(list, { it.id }, { it.upd })
        assertEquals(3, unique.size)
        assertEquals(5L, unique.first { it.id == "a" }.upd)
        val ordered = MessageTimeline.ordered(unique, { it.at }, { it.id })
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
        assertTrue(MessageTimeline.shouldApplyRemote(10, 10))
        assertFalse(MessageTimeline.shouldApplyRemote(10, 9))
    }

    @Test fun groupingAndUnreadSeparator() {
        val utc = TimeZone.getTimeZone("UTC")
        val a = MessageTimeline.Entry("1", "me", 1_000_000, MessageType.TEXT)
        val b = MessageTimeline.Entry("2", "me", 1_060_000, MessageType.TEXT)
        val c = MessageTimeline.Entry("3", "bob", 1_070_000, MessageType.TEXT)
        val d = MessageTimeline.Entry("4", "bob", 1_070_000 + MessageTimeline.GROUP_WINDOW_MS + 1, MessageType.TEXT)
        assertTrue(MessageTimeline.isGroupedWithPrevious(a, b, utc))
        assertFalse(MessageTimeline.isGroupedWithPrevious(b, c, utc))
        assertFalse(MessageTimeline.isGroupedWithPrevious(c, d, utc))
        assertFalse(MessageTimeline.isGroupedWithPrevious(null, a, utc))
        assertEquals(2, MessageTimeline.firstUnreadIndex(listOf(a, b, c, d), "me", 1_065_000))
        assertEquals(-1, MessageTimeline.firstUnreadIndex(listOf(a, b, c, d), "me", 9_999_999))
    }
}
