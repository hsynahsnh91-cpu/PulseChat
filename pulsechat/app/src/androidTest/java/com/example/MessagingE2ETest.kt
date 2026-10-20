package com.example

import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.Ids
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end messaging on a real Android runtime against the Firebase emulators (see
 * [EmulatorE2ETest]): registration, user search, one DM, text + photo, sync to a fresh device,
 * media download, unread counter, read receipts, reactions, edit, delete for everyone, and
 * security-rule enforcement.
 */
@RunWith(AndroidJUnit4::class)
class MessagingE2ETest : EmulatorE2ETest() {

    @Test
    fun registerSearchSendReceiveReadReactEditDelete() = runBlocking {
        startFresh()
        val pw = "Passw0rd1"
        val suffix = uniqueSuffix()
        val aliceName = "alice$suffix"
        val bobName = "bob$suffix"

        // 1. Alice registers (username + password, profile with unique handles created by transaction).
        c.authRepository.register("Alice", aliceName, pw, pw)
        val alice = awaitSignedIn()
        switchDevice()

        // 2. Bob registers, finds Alice by username, opens one DM, sends a text and a photo.
        c.authRepository.register("Bob", bobName, pw, pw)
        val bob = awaitSignedIn()
        val bobId10 = c.db.userDao().get(bob)!!.uniqueId10
        assertTrue("search finds Alice", c.userRepository.searchUsers(aliceName).any { it.userId == alice })
        val cid = c.chatRepository.openDirectChat(alice)
        assertEquals(Ids.directConversationId(alice, bob), cid)
        assertEquals("no duplicate direct chats", cid, c.chatRepository.openDirectChat(alice))
        val text = c.chatRepository.sendText(cid, "Hello Alice", null, emptyList())
        await("text acknowledged by the server") { c.db.messageDao().get(text.messageId)?.status == MessageStatus.SENT }
        c.chatRepository.sendMediaUri(cid, testImage(), MessageType.IMAGE, "pulse", null)
        var photoId = ""
        await("photo uploaded and acknowledged", 90_000) {
            val photo = c.db.messageDao().observeRecent(cid, 0L, 20).first().firstOrNull { it.type == MessageType.IMAGE }
            photoId = photo?.messageId.orEmpty()
            photo != null && photo.status == MessageStatus.SENT && photo.mediaUrl != null
        }
        switchDevice()

        // 3. Alice signs in on a "fresh device": both messages arrive through sync, media downloads.
        c.authRepository.signInWithPassword(aliceName, pw)
        awaitSignedIn()
        await("text synced to the recipient") { c.db.messageDao().get(text.messageId)?.content == "Hello Alice" }
        await("photo synced to the recipient") { c.db.messageDao().get(photoId)?.mediaUrl != null }
        await("unread counter") { c.db.conversationDao().get(cid)?.unreadCount == 2 }
        c.chatRepository.downloadMedia(photoId)
        val local = c.db.messageDao().get(photoId)?.localMediaPath
        assertTrue("photo downloaded from Storage", local != null && File(local).length() > 0)
        c.chatRepository.react(text.messageId, "👍")
        c.chatRepository.markRead(cid)
        assertEquals(0, c.db.conversationDao().get(cid)!!.unreadCount)
        c.firestore.waitForPendingWrites().await()
        val serverConv = c.firestore.collection("conversations").document(cid).get(Source.SERVER).await()
        assertTrue("read receipt stored on the server", (serverConv.get("receipts") as? Map<*, *>)?.containsKey(alice) == true)
        val serverText = c.firestore.collection("conversations").document(cid).collection("messages").document(text.messageId).get(Source.SERVER).await()
        assertEquals("reaction stored on the server", "👍", (serverText.get("reactions") as? Map<*, *>)?.get(alice))
        switchDevice()

        // 4. Bob signs in with his 10-digit ID: the read receipt and the reaction reach the sender.
        c.authRepository.signInWithPassword(bobId10, pw)
        awaitSignedIn()
        await("read receipt reached the sender") { c.db.messageDao().get(text.messageId)?.status == MessageStatus.READ }
        await("reaction reached the sender") { c.db.messageDao().get(text.messageId)?.reactionMap()?.get(alice) == "👍" }

        // 5. The production rules are enforced in this run: Bob cannot write Alice's profile, read her
        //    private settings or change her reaction.
        val users = c.firestore.collection("users")
        assertDenied("writing another user's profile") { users.document(alice).update("displayName", "Mallory").await() }
        assertDenied("reading another user's private settings") { users.document(alice).collection("private").document("settings").get(Source.SERVER).await() }
        assertDenied("changing another user's reaction") {
            // Same shape as ChatRepository.react (incl. updatedAt) - only the reaction owner differs.
            c.firestore.collection("conversations").document(cid).collection("messages").document(text.messageId)
                .update(FieldPath.of("reactions", alice), "😡", FieldPath.of("updatedAt"), FieldValue.serverTimestamp()).await()
        }

        // 6. Author edit and delete-for-everyone are accepted by the server rules.
        c.chatRepository.editMessage(text.messageId, "Hello Alice!")
        c.chatRepository.deleteForEveryone(listOf(photoId))
        c.firestore.waitForPendingWrites().await()
        val messages = c.firestore.collection("conversations").document(cid).collection("messages")
        val remoteText = messages.document(text.messageId).get(Source.SERVER).await()
        assertEquals("Hello Alice!", remoteText.getString("text"))
        assertTrue(remoteText.contains("editedAt") && remoteText.get("editedAt") != null)
        val remotePhoto = messages.document(photoId).get(Source.SERVER).await()
        assertEquals(true, remotePhoto.getBoolean("deletedForEveryone"))
        assertFalse("media removed for everyone", remotePhoto.contains("media"))

        // 7. The real UI shows the conversation in the chat list.
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Alice").fetchSemanticsNodes().isNotEmpty() }

        c.authRepository.signOut()
    }
}
