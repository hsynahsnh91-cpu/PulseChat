package com.example.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatNotificationSettings
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.User
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun msg(id: String, cid: String, sender: String, at: Long, status: MessageStatus = MessageStatus.SENT, type: MessageType = MessageType.TEXT) =
        Message(messageId = id, conversationId = cid, senderId = sender, content = "t$id", type = type, status = status, createdAt = at, updatedAt = at)

    @Test
    fun chatListMembershipIsExactNotSubstring() = runBlocking {
        db.conversationDao().upsert(Conversation(conversationId = "a", participantIds = "u1,u10", peerId = "u10", lastMessageTimestamp = 1))
        db.conversationDao().upsert(Conversation(conversationId = "b", participantIds = "u10,u2", peerId = "u2", lastMessageTimestamp = 2))
        db.conversationDao().upsert(Conversation(conversationId = "c", participantIds = "u1,u3", peerId = "u3", lastMessageTimestamp = 3, isHidden = true))
        db.userDao().upsert(User(userId = "u10", displayName = "Ten"))
        val rows = db.conversationDao().observeChatList("u1").first()
        assertEquals(listOf("a"), rows.map { it.conversation.conversationId })
        assertEquals("Ten", rows.single().displayTitle)
    }

    @Test
    fun unreadCountsOnlyIncomingAfterWatermark() = runBlocking {
        db.conversationDao().upsert(Conversation(conversationId = "c", participantIds = "me,bob", lastReadAt = 100))
        db.messageDao().upsertAll(listOf(
            msg("1", "c", "bob", 50), msg("2", "c", "bob", 150), msg("3", "c", "me", 160), msg("4", "c", "bob", 170),
            msg("5", "c", "bob", 180, type = MessageType.SYSTEM),
        ))
        db.conversationDao().recomputeUnread("c", "me")
        assertEquals(2, db.conversationDao().get("c")!!.unreadCount)
        db.conversationDao().markReadUpTo("c", 170)
        db.conversationDao().recomputeUnread("c", "me")
        assertEquals(0, db.conversationDao().get("c")!!.unreadCount)
    }

    @Test
    fun receiptsAdvanceOnlyAcknowledgedOwnMessages() = runBlocking {
        db.conversationDao().upsert(Conversation(conversationId = "c", participantIds = "me,bob"))
        db.messageDao().upsertAll(listOf(
            msg("1", "c", "me", 10), msg("2", "c", "me", 20), msg("3", "c", "me", 30, MessageStatus.PENDING), msg("4", "c", "bob", 15),
        ))
        db.messageDao().advanceStatuses("c", "me", 25, MessageStatus.DELIVERED, listOf(MessageStatus.SENT.name))
        db.messageDao().advanceStatuses("c", "me", 12, MessageStatus.READ, listOf(MessageStatus.SENT.name, MessageStatus.DELIVERED.name))
        assertEquals(MessageStatus.READ, db.messageDao().get("1")!!.status)
        assertEquals(MessageStatus.DELIVERED, db.messageDao().get("2")!!.status)
        assertEquals(MessageStatus.PENDING, db.messageDao().get("3")!!.status)
        assertEquals(MessageStatus.SENT, db.messageDao().get("4")!!.status)
    }

    @Test
    fun clearedChatsHideOlderMessagesAndSearchWorks() = runBlocking {
        db.conversationDao().upsert(Conversation(conversationId = "c", participantIds = "me,bob"))
        db.messageDao().upsertAll(listOf(msg("1", "c", "bob", 10), msg("2", "c", "bob", 20)))
        db.messageDao().upsert(msg("3", "c", "bob", 30).copy(content = "Lunch at noon?"))
        assertEquals(listOf("3", "2", "1"), db.messageDao().observeRecent("c", 0, 10).first().map { it.messageId })
        assertEquals(listOf("3"), db.messageDao().observeRecent("c", 20, 10).first().map { it.messageId })
        assertEquals(listOf("3"), db.messageDao().searchInConversation("c", 0, "lunch").map { it.messageId })
        assertEquals(1, db.messageDao().countNewerThan("c", 0, 20, "2"))
        db.messageDao().deleteForMe(listOf("3"))
        assertTrue(db.messageDao().observeRecent("c", 0, 10).first().none { it.messageId == "3" })
    }

    @Test
    fun pinnedCountAndPreferences() = runBlocking {
        db.conversationDao().upsert(Conversation(conversationId = "a", participantIds = "me,x", isPinned = true))
        db.conversationDao().upsert(Conversation(conversationId = "b", participantIds = "me,y", isPinned = true, isArchived = true))
        assertEquals(1, db.conversationDao().pinnedCount("me"))
        db.chatSettingsDao().upsertNotificationSettings(ChatNotificationSettings("me", "a", mutedUntil = Long.MAX_VALUE))
        db.conversationDao().setDraft("a", "draft", 5)
        val a = db.conversationDao().get("a")!!
        assertTrue(db.chatSettingsDao().notificationSettings("me", "a")!!.isMuted())
        assertEquals("draft", a.draftText)
    }

    @Test
    fun starredGlobalSearchAndExpiry() = runBlocking {
        db.userDao().upsert(User(userId = "bob", displayName = "Bob"))
        db.conversationDao().upsert(Conversation(conversationId = "c", participantIds = "me,bob", peerId = "bob"))
        db.conversationDao().upsert(Conversation(conversationId = "other", participantIds = "x,y"))
        db.messageDao().upsertAll(listOf(
            msg("1", "c", "bob", 10).copy(content = "see https://example.com"),
            msg("2", "c", "bob", 20, type = MessageType.IMAGE).copy(content = "beach photo"),
            msg("3", "c", "me", 30).copy(expiresAt = 35),
            msg("4", "other", "x", 40).copy(content = "photo of someone else"),
        ))
        db.messageDao().setStarred(listOf("2"), true)
        val starred = db.messageDao().observeStarred("me").first()
        assertEquals(listOf("2"), starred.map { it.message.messageId })
        assertEquals("Bob", starred.single().senderName)

        assertEquals(listOf("2"), db.messageDao().searchAll("me", "photo", "", false).map { it.message.messageId })
        assertEquals(listOf("2"), db.messageDao().searchAll("me", "", MessageType.IMAGE.name, false).map { it.message.messageId })
        assertEquals(listOf("1"), db.messageDao().searchAll("me", "", "", true).map { it.message.messageId })

        assertEquals(listOf("3"), db.messageDao().expired(36).map { it.messageId })
        assertTrue(db.messageDao().expired(34).isEmpty())
    }
}
