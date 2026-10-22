package com.example

import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.Message
import com.example.data.model.StatusItem
import com.example.data.remote.RemoteMappers
import com.example.data.repository.ChatError
import com.example.data.repository.ChatException
import com.example.domain.Ids
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Groups, per-user chat preferences, statuses, saved contacts and blocking, end to end against the
 * Firebase emulators with this repo's security rules (see [EmulatorE2ETest]).
 */
@RunWith(AndroidJUnit4::class)
class GroupsStatusPrivacyE2ETest : EmulatorE2ETest() {

    @Test
    fun groupsPreferencesStatusContactsAndBlocking() = runBlocking {
        startFresh()
        val pw = "Passw0rd1"
        val suffix = uniqueSuffix()
        // Usernames deliberately differ from the display names: people are found only by their exact
        // username or 10-digit ID, never by a (non-unique) display name.
        val carolName = "qa_c$suffix"
        val daveName = "qa_d$suffix"
        val statusText = "Pulse status $suffix"

        // 1. Carol registers.
        c.authRepository.register("Carol", carolName, pw, pw)
        val carol = awaitSignedIn()
        val carolId10 = c.db.userDao().get(carol)!!.uniqueId10
        switchDevice()

        // 2. Dave registers, finds Carol by 10-digit ID and username (not by name), saves her as a contact, posts a
        //    text and a photo status (audience: my contacts), messages her, and creates a group with
        //    24-hour disappearing messages where only admins may send.
        c.authRepository.register("Dave", daveName, pw, pw)
        val dave = awaitSignedIn()
        assertTrue("search by 10-digit ID", c.userRepository.searchUsers(carolId10).any { it.userId == carol })
        assertTrue("search by exact username", c.userRepository.searchUsers("@$carolName").any { it.userId == carol })
        assertTrue("display names are not searchable", c.userRepository.searchUsers("Carol").isEmpty())
        assertTrue("partial usernames are not searchable", c.userRepository.searchUsers(carolName.dropLast(2)).isEmpty())
        c.userRepository.saveContact(carol, "Carol QA")

        c.statusRepository.postText(statusText, "#4F46E5", "sans")
        c.statusRepository.postMedia(testImage(), MessageType.IMAGE, "photo status")
        val mine = c.db.statusDao().observeActive(System.currentTimeMillis()).first().filter { it.userId == dave }
        val textStatusId = mine.single { it.mediaType == "TEXT" }.statusId
        val photoStatusId = mine.single { it.mediaType == "IMAGE" }.statusId
        await("statuses published", 90_000) {
            listOf(textStatusId, photoStatusId).all { c.db.statusDao().get(it)?.uploadState == StatusItem.UPLOAD_DONE }
        }

        val dm = c.chatRepository.openDirectChat(carol)
        val hello = c.chatRepository.sendText(dm, "Hi Carol", null, emptyList())
        await("direct message acknowledged") { c.db.messageDao().get(hello.messageId)?.status == MessageStatus.SENT }

        val group = c.chatRepository.createGroup("Pulse team", "E2E group", null, listOf(carol))
        c.chatRepository.setDisappearing(group, 86_400L)
        // Like a person who sets the timer and then writes: the setting is confirmed and shown first
        // (a snapshot of the brand-new group taken just before the change can still be on its way).
        c.firestore.waitForPendingWrites().await()
        await("timer applied on this phone") { c.db.conversationDao().get(group)?.disappearingSeconds == 86_400L }
        val welcome = c.chatRepository.sendText(group, "Welcome to the team", null, emptyList())
        assertTrue("disappearing timer applies to new messages", welcome.expiresAt > welcome.createdAt)
        await("group message acknowledged") { c.db.messageDao().get(welcome.messageId)?.status == MessageStatus.SENT }
        c.chatRepository.setGroupPermissions(group, onlyAdminsCanSend = true, onlyAdminsCanEditInfo = true)
        c.firestore.waitForPendingWrites().await()
        val serverGroup = c.firestore.collection("conversations").document(group).get(Source.SERVER).await()
        assertEquals("admin-only sending stored on the server", true, serverGroup.getBoolean("onlyAdminsCanSend"))
        assertEquals("disappearing timer stored on the server", 86_400L, serverGroup.getLong("disappearingSeconds"))
        assertEquals(listOf(dave), serverGroup.get("admins"))
        switchDevice()

        // 3. Carol on a fresh device receives the DM, the group with its settings and the disappearing
        //    message. Posting in the admin-only group is refused by the app and by the server rules.
        c.authRepository.signInWithPassword(carolName, pw)
        awaitSignedIn()
        await("group synced with its settings") {
            val g = c.db.conversationDao().get(group)
            g != null && g.isGroup && g.title == "Pulse team" && g.onlyAdminsCanSend && g.disappearingSeconds == 86_400L && g.getAdminList() == listOf(dave)
        }
        await("disappearing message synced with its expiry") { (c.db.messageDao().get(welcome.messageId)?.expiresAt ?: 0L) > 0L }
        await("direct message synced") { c.db.messageDao().get(hello.messageId)?.content == "Hi Carol" }
        val refused = runCatching { c.chatRepository.sendText(group, "Can I post?", null, emptyList()) }.exceptionOrNull()
        assertEquals("the app refuses to send in an admin-only group", ChatError.NOT_ALLOWED, (refused as? ChatException)?.error)
        val forged = Message(messageId = Ids.newMessageId(), conversationId = group, senderId = carol, content = "Sneaky", type = MessageType.TEXT)
        assertDenied("non-admin posting in an admin-only group") {
            // Exactly the payload the app writes for a text message.
            c.firestore.collection("conversations").document(group).collection("messages").document(forged.messageId)
                .set(RemoteMappers.messageCreateMap(forged, 0L)).await()
        }

        // Per-user chat preferences, status views and the block list.
        val mutedUntil = System.currentTimeMillis() + 8 * 60 * 60 * 1000L
        assertTrue(c.chatRepository.setPinned(group, true))
        c.chatRepository.setFavorite(group, true)
        c.chatSettings.setMutedUntil(group, mutedUntil)
        c.chatRepository.setMarkedUnread(group, true)
        c.chatRepository.setArchived(dm, true)
        await("both statuses delivered to the audience") {
            c.db.statusDao().get(textStatusId)?.text == statusText && c.db.statusDao().get(photoStatusId)?.mediaUrl != null
        }
        c.statusRepository.markViewed(c.db.statusDao().get(textStatusId)!!)
        c.statusRepository.markViewed(c.db.statusDao().get(photoStatusId)!!)
        c.userRepository.block(dave)
        switchDevice()

        // 4. Carol on another fresh device (signing in with her 10-digit ID): preferences and the block
        //    list are restored from the server. She leaves the group.
        c.authRepository.signInWithPassword(carolId10, pw)
        awaitSignedIn()
        await("chat preferences restored") {
            val g = c.db.conversationDao().get(group)
            g != null && g.isPinned && g.isFavorite && g.markedUnread && c.chatSettings.notificationSettings(group).mutedUntil == mutedUntil
        }
        await("archived chat restored") { c.db.conversationDao().get(dm)?.isArchived == true }
        await("block list restored") { dave in c.db.contactDao().blockedIds(carol) }
        c.chatRepository.leaveGroup(group)
        assertTrue(c.db.conversationDao().get(group)!!.isLeft)
        switchDevice()

        // 5. Dave on a fresh device: saved contact and status viewers restored, Carol has left the
        //    group, and his message to Carol (who blocked him) is rejected by the server.
        c.authRepository.signInWithPassword(daveName, pw)
        awaitSignedIn()
        await("saved contact restored") { carol in c.db.contactDao().contactIds(dave) }
        await("status viewer recorded") { c.statusRepository.observeViewers(textStatusId).first().any { it.viewerId == carol } }
        await("member left the group") {
            val g = c.db.conversationDao().get(group)
            g != null && carol !in g.getParticipantsList() && g.getAdminList() == listOf(dave)
        }
        await("direct chat synced") { c.db.conversationDao().get(dm) != null }
        val ping = c.chatRepository.sendText(dm, "Are you there?", null, emptyList())
        await("message to a user who blocked me is rejected by the server") {
            c.db.messageDao().get(ping.messageId)?.let { it.status == MessageStatus.FAILED && it.failureReason == "denied" } == true
        }

        // The real UI lists the group.
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Pulse team").fetchSemanticsNodes().isNotEmpty() }
        c.authRepository.signOut()
    }
}
