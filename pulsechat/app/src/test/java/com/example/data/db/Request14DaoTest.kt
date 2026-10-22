package com.example.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BlockedUser
import com.example.data.model.Contact
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.StatusItem
import com.example.data.model.StatusView
import com.example.data.model.User
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Queries behind the status views count, status author names, message order and contact sharing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Request14DaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun statusesThatArrivedBeforeTheProfileGetTheNameLater() = runBlocking {
        val dao = db.statusDao()
        dao.upsert(StatusItem(statusId = "s1", userId = "alice", authorName = ""))
        dao.upsert(StatusItem(statusId = "s2", userId = "alice", authorName = ""))
        dao.upsert(StatusItem(statusId = "s3", userId = "bob", authorName = "Bob"))
        dao.updateAuthor("alice", "Alice Johnson", "https://example.com/a.jpg")
        assertEquals("Alice Johnson", dao.get("s1")?.authorName)
        assertEquals("https://example.com/a.jpg", dao.get("s2")?.authorPhotoUrl)
        assertEquals("Bob", dao.get("s3")?.authorName)
    }

    @Test
    fun viewerCountCountsEachPersonOnce() = runBlocking {
        val dao = db.statusDao()
        assertEquals(0, dao.observeViewerCount(listOf("s1", "s2")).first())
        dao.upsertView(StatusView("s1", "alice", 1L))
        dao.upsertView(StatusView("s2", "alice", 2L))
        dao.upsertView(StatusView("s2", "carol", 3L))
        dao.upsertView(StatusView("other", "dave", 4L))
        assertEquals(2, dao.observeViewerCount(listOf("s1", "s2")).first())
    }

    @Test
    fun newestCreatedAtIsTheLatestMessageOfThatChat() = runBlocking {
        val dao = db.messageDao()
        assertNull(dao.newestCreatedAt("c1"))
        dao.upsert(Message(messageId = "m1", conversationId = "c1", senderId = "me", type = MessageType.TEXT, content = "a", status = MessageStatus.SENT, createdAt = 100L))
        dao.upsert(Message(messageId = "m2", conversationId = "c1", senderId = "bob", type = MessageType.TEXT, content = "b", status = MessageStatus.SENT, createdAt = 300L))
        dao.upsert(Message(messageId = "m3", conversationId = "c2", senderId = "bob", type = MessageType.TEXT, content = "c", status = MessageStatus.SENT, createdAt = 900L))
        assertEquals(300L, dao.newestCreatedAt("c1"))
    }

    @Test
    fun shareableContactsAreSavedContactsAndChatPartnersButNotBlockedOrHandlelessPeople() = runBlocking {
        db.userDao().upsertAll(
            listOf(
                User(userId = "me", username = "me", displayName = "Me"),
                User(userId = "saved", username = "saved", displayName = "Zed Saved"),
                User(userId = "peer", username = "peer", displayName = "Amy Peer"),
                User(userId = "blocked", username = "blocked", displayName = "Blocked"),
                User(userId = "nohandle", username = "", displayName = "No Handle"),
                User(userId = "stranger", username = "stranger", displayName = "Stranger"),
                User(userId = "grouponly", username = "grouponly", displayName = "Group Only"),
            )
        )
        db.contactDao().upsert(Contact(contactId = "k1", ownerUserId = "me", targetUserId = "saved", customNickname = "Buddy"))
        db.contactDao().upsert(Contact(contactId = "k2", ownerUserId = "me", targetUserId = "blocked"))
        db.contactDao().upsert(Contact(contactId = "k3", ownerUserId = "me", targetUserId = "nohandle"))
        db.contactDao().block(BlockedUser("me", "blocked", 1L))
        db.conversationDao().upsert(Conversation(conversationId = "d1", participantIds = "me,peer", peerId = "peer"))
        db.conversationDao().upsert(Conversation(conversationId = "g1", isGroup = true, participantIds = "me,grouponly"))
        db.conversationDao().upsert(Conversation(conversationId = "d2", participantIds = "x,stranger", peerId = "stranger"))

        val rows = db.contactDao().observeShareable("me").first()
        // Sorted by the nickname when there is one: "Amy Peer" before "Buddy".
        assertEquals(listOf("peer", "saved"), rows.map { it.userId })
        assertEquals("Buddy", rows.last().customNickname)
    }
}
