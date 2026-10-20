package com.example

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.repository.SessionState
import com.example.domain.Ids
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.util.EmulatorConfig
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end messaging on a real Android runtime against the Firebase emulators (Auth, Firestore
 * with this repo's security rules, Storage). Two accounts take turns on the device; local data is
 * wiped between them so each sign-in behaves like a fresh device and everything must come from sync.
 * Skipped unless the runner receives `firebaseEmulatorHost`.
 */
@RunWith(AndroidJUnit4::class)
class MessagingE2ETest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val c: AppContainer get() = (context.applicationContext as PulseChatApplication).container

    @Before fun setUp() {
        assumeTrue("Firebase emulators not configured", EmulatorConfig.host != null)
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private suspend fun await(what: String, timeoutMs: Long = 45_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(250)
        }
        fail("Timed out waiting for: $what")
    }

    private suspend fun awaitSignedIn(): String {
        await("signed in") { c.authRepository.session.value is SessionState.SignedIn }
        return c.authRepository.currentUid!!
    }

    /** Signs out and wipes local data, so the next account starts like a new device. */
    private suspend fun switchDevice() {
        c.authRepository.signOut()
        await("signed out") { c.authRepository.session.value == SessionState.SignedOut }
        delay(1_500) // let queued sync work drain before wiping
        withContext(Dispatchers.IO) { c.db.clearAllTables() }
    }

    private fun testImage(): Uri {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "e2e_${System.currentTimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(79, 70, 229)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    @Test
    fun registerSearchSendReceiveReadReactEditDelete() = runBlocking {
        if (c.authRepository.session.value !is SessionState.SignedOut) switchDevice()
        val pw = "Passw0rd1"
        val suffix = (System.currentTimeMillis() % 1_000_000).toString()
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

        // 5. Author edit and delete-for-everyone are accepted by the server rules.
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

        // 6. The real UI shows the conversation in the chat list.
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Alice").fetchSemanticsNodes().isNotEmpty() }

        c.authRepository.signOut()
    }
}
