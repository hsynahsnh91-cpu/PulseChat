package com.example.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatNotificationSettings
import com.example.data.model.Conversation
import com.example.data.model.LockedChat
import com.example.data.model.Message
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Notification settings and chat locks are per account and keep locked chats out of lists, search and starred. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ChatSettingsDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private suspend fun seed() {
        db.conversationDao().upsert(Conversation(conversationId = "a", participantIds = "me,bob", peerId = "bob", unreadCount = 2, lastMessageTimestamp = 10L))
        db.conversationDao().upsert(Conversation(conversationId = "b", participantIds = "me,sara", peerId = "sara", unreadCount = 1, lastMessageTimestamp = 20L))
        db.conversationDao().upsert(Conversation(conversationId = "c", participantIds = "me,omar", peerId = "omar", unreadCount = 4, lastMessageTimestamp = 30L))
        db.messageDao().upsertAll(
            listOf(
                Message(messageId = "ma", conversationId = "a", senderId = "bob", type = MessageType.TEXT, content = "secret plan", status = MessageStatus.DELIVERED, createdAt = 5L, isStarred = true),
                Message(messageId = "mb", conversationId = "b", senderId = "sara", type = MessageType.TEXT, content = "public plan", status = MessageStatus.DELIVERED, createdAt = 6L, isStarred = true),
            )
        )
    }

    @Test
    fun mutedAndLockedStateComeWithTheChatListRows() = runBlocking {
        seed()
        db.chatSettingsDao().upsertNotificationSettings(ChatNotificationSettings("me", "b", mutedUntil = Long.MAX_VALUE))
        db.chatSettingsDao().lock(LockedChat("me", "a", 1L))
        val rows = db.conversationDao().observeChatList("me").first().associateBy { it.conversation.conversationId }
        assertTrue(rows.getValue("a").isLocked)
        assertFalse(rows.getValue("b").isLocked)
        assertTrue(rows.getValue("b").isMuted())
        assertFalse(rows.getValue("c").isMuted())
        // Another account on the same phone has its own settings.
        db.conversationDao().upsert(Conversation(conversationId = "x", participantIds = "bob,me2", peerId = "bob"))
        db.chatSettingsDao().upsertNotificationSettings(ChatNotificationSettings("me2", "a", mutedUntil = Long.MAX_VALUE))
        assertFalse(db.conversationDao().observeChatList("me").first().first { it.conversation.conversationId == "a" }.isMuted())
        assertFalse(db.chatSettingsDao().isLocked("me2", "a"))
    }

    @Test
    fun unreadBadgeSkipsMutedAndLockedChats() = runBlocking {
        seed()
        assertEquals(3, db.conversationDao().observeUnreadChats("me", 100L).first())
        db.chatSettingsDao().upsertNotificationSettings(ChatNotificationSettings("me", "b", mutedUntil = 200L))
        db.chatSettingsDao().lock(LockedChat("me", "c", 1L))
        assertEquals(1, db.conversationDao().observeUnreadChats("me", 100L).first())
        // The mute has ended at 300: the chat counts again.
        assertEquals(2, db.conversationDao().observeUnreadChats("me", 300L).first())
    }

    @Test
    fun lockedChatsStayOutOfSearchAndStarredMessages() = runBlocking {
        seed()
        assertEquals(setOf("ma", "mb"), db.messageDao().searchAll("me", "plan", "", false).map { it.message.messageId }.toSet())
        db.chatSettingsDao().lock(LockedChat("me", "a", 1L))
        assertEquals(listOf("mb"), db.messageDao().searchAll("me", "plan", "", false).map { it.message.messageId })
        assertEquals(listOf("mb"), db.messageDao().observeStarred("me").first().map { it.message.messageId })
        // Search inside the (unlocked) chat itself still works.
        assertEquals(listOf("ma"), db.messageDao().searchInConversation("a", 0L, "secret").map { it.messageId })
        db.chatSettingsDao().unlock("me", "a")
        assertEquals(2, db.messageDao().observeStarred("me").first().size)
    }
}
