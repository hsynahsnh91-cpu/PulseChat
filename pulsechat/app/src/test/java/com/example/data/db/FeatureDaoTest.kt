package com.example.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatFolder
import com.example.data.model.ChatFolderMember
import com.example.data.model.Message
import com.example.data.model.MessageTranscript
import com.example.data.model.Reminder
import com.example.data.model.ReminderState
import com.example.data.model.ScheduledMessage
import com.example.data.model.ScheduledState
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FeatureDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun scheduled(id: String, at: Long = 10_000L) =
        ScheduledMessage(id = id, ownerId = "me", conversationId = "c1", text = "hello", scheduledAt = at, createdAt = 1L, updatedAt = 1L)

    @Test
    fun onlyOneConcurrentClaimWins() = runBlocking {
        val dao = db.scheduledMessageDao()
        dao.insert(scheduled("s1"))
        val results = (1..8).map { async(Dispatchers.IO) { dao.claim("s1", 20_000L, staleBefore = 0L) } }.awaitAll()
        assertEquals("exactly one attempt may send", 1, results.sum())
        val row = dao.get("s1")!!
        assertEquals(ScheduledState.SENDING, row.state)
        assertEquals(1, row.attempts)
    }

    @Test
    fun aClaimFromADeadAttemptCanBeTakenOverButALiveOneCannot() = runBlocking {
        val dao = db.scheduledMessageDao()
        dao.insert(scheduled("s1"))
        assertEquals(1, dao.claim("s1", now = 50_000L, staleBefore = 0L))
        assertEquals("live attempt keeps ownership", 0, dao.claim("s1", now = 60_000L, staleBefore = 40_000L))
        assertEquals("stale attempt is taken over", 1, dao.claim("s1", now = 300_000L, staleBefore = 100_000L))
        dao.markSent("s1", 301_000L)
        assertEquals("a sent message is never claimed again", 0, dao.claim("s1", now = 900_000L, staleBefore = 800_000L))
    }

    @Test
    fun editingAndCancellingAreBlockedWhileSending() = runBlocking {
        val dao = db.scheduledMessageDao()
        dao.insert(scheduled("s1"))
        assertEquals(1, dao.updateContent("s1", "edited", 30_000L, 2L))
        assertEquals("edited", dao.get("s1")!!.text)
        dao.claim("s1", 40_000L, 0L)
        assertEquals(0, dao.updateContent("s1", "too late", 50_000L, 3L))
        assertEquals(0, dao.deleteIfEditable("s1"))
        assertEquals(1, dao.release("s1", "offline", 41_000L))
        assertEquals(ScheduledState.PENDING, dao.get("s1")!!.state)
        assertEquals(1, dao.deleteIfEditable("s1"))
        assertNull(dao.get("s1"))
    }

    @Test
    fun failedMessagesCanBeRescheduled() = runBlocking {
        val dao = db.scheduledMessageDao()
        dao.insert(scheduled("s1"))
        dao.claim("s1", 1L, 0L)
        dao.markFailed("s1", "blocked_contact", 2L)
        assertEquals(0, dao.claim("s1", 3L, 0L))
        assertEquals(1, dao.reschedule("s1", 5L, 4L))
        val row = dao.get("s1")!!
        assertEquals(ScheduledState.PENDING, row.state)
        assertEquals(0, row.attempts)
        assertNull(row.lastError)
    }

    @Test
    fun reminderFiresOnlyOnce() = runBlocking {
        val dao = db.reminderDao()
        dao.upsert(Reminder(id = "r1", ownerId = "me", conversationId = "c1", messageId = "m1", title = "Call mom", remindAt = 5L, createdAt = 1L))
        assertEquals(1, dao.markFired("r1", 6L))
        assertEquals(0, dao.markFired("r1", 7L))
        assertEquals(ReminderState.FIRED, dao.get("r1")!!.state)
        dao.reschedule("r1", 100L)
        assertEquals(listOf("r1"), dao.observeUpcoming("me").first().map { it.id })
    }

    @Test
    fun folderMembershipIsPersistedAndDeletedWithTheFolder() = runBlocking {
        val dao = db.folderDao()
        dao.insertFolder(ChatFolder("f1", "me", "Work", 0, 1L))
        dao.insertFolder(ChatFolder("f2", "other", "Theirs", 0, 1L))
        dao.addMembers(listOf(ChatFolderMember("f1", "c1", 2L), ChatFolderMember("f1", "c2", 2L)))
        dao.addMembers(listOf(ChatFolderMember("f1", "c2", 3L))) // duplicates are ignored
        dao.removeMembers("f1", listOf("c1"))
        dao.addMembers(listOf(ChatFolderMember("f1", "c3", 3L)))
        assertEquals(setOf("c2", "c3"), dao.memberIds("f1").toSet())
        assertEquals(listOf("Work"), dao.observeFolders("me").first().map { it.name })
        assertEquals(2, dao.observeFoldersWithCounts("me").first().single().members)
        dao.deleteMembersOf("f1")
        dao.deleteFolderRow("f1")
        assertTrue(dao.memberIds("f1").isEmpty())
        assertTrue(dao.observeMembers("me").first().isEmpty())
    }

    @Test
    fun transcriptsAndIncomingCountsFollowTheirConversation() = runBlocking {
        db.messageDao().upsertAll(listOf(
            Message(messageId = "m1", conversationId = "c1", senderId = "bob", type = MessageType.AUDIO, status = MessageStatus.SENT, createdAt = 10L),
            Message(messageId = "m2", conversationId = "c2", senderId = "bob", type = MessageType.AUDIO, status = MessageStatus.SENT, createdAt = 20L),
            Message(messageId = "m3", conversationId = "c1", senderId = "me", content = "hi", status = MessageStatus.SENT, createdAt = 30L),
            Message(messageId = "m4", conversationId = "c1", senderId = "bob", content = "see https://a.io", status = MessageStatus.SENT, createdAt = 40L),
        ))
        db.transcriptDao().upsert(MessageTranscript("m1", "hello", "gemini", 1L))
        db.transcriptDao().upsert(MessageTranscript("m2", "other chat", "gemini", 1L))
        assertEquals(listOf("m1"), db.transcriptDao().observeForConversation("c1").first().map { it.messageId })
        assertEquals(2, db.messageDao().countIncomingSince("c1", "me", 5L))
        assertEquals(1, db.messageDao().countIncomingSince("c1", "me", 15L))
        assertEquals(listOf("m4"), db.messageDao().observeWithLinks("c1", 0L, 10).first().map { it.messageId })
        assertEquals(listOf("m1"), db.messageDao().observeByTypes("c1", 0L, listOf("AUDIO"), 10).first().map { it.messageId })
    }
}
