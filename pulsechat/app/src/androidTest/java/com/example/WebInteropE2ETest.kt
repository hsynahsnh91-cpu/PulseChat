package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.Message
import com.example.domain.Ids
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Android ↔ Web on the shared Firebase backend (emulators). The "web peer" (web/test/android-peer.ts:
 * the PulseChat web data layer on the Firebase JS SDK) runs on the CI host next to this emulator and
 * answers in real time, while this test drives the real Android app:
 * an account created on the web signs in on Android, Android finds the web user, text and photos go
 * both ways, the web client's read receipt turns the Android ticks blue, and the web client's
 * chat-list preview shows up on Android.
 *
 * Skipped unless the runner receives the `webPeer` argument (the web peer's username).
 */
@RunWith(AndroidJUnit4::class)
class WebInteropE2ETest : EmulatorE2ETest() {

    @Test
    fun androidAndWebClientsShareAccountsChatsMediaAndReceipts() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val peerName = args.getString("webPeer")
        assumeTrue("web peer not running", !peerName.isNullOrBlank())
        val webOnly = args.getString("webOnlyAccount") ?: "webonly"
        val pw = args.getString("webPassword") ?: "Passw0rd1"
        startFresh()

        // 1. An account created by the web client signs in on Android (same Auth scheme and profile documents).
        c.authRepository.signInWithPassword(webOnly, pw)
        val webOnlyUid = awaitSignedIn()
        assertEquals("Web Only", c.db.userDao().get(webOnlyUid)?.displayName)
        switchDevice()

        // 2. A new Android user finds the web user and opens the deterministic direct chat.
        val me = "droid${uniqueSuffix()}"
        c.authRepository.register("Droid", me, pw, pw)
        val uid = awaitSignedIn()
        val peer = c.userRepository.searchUsers(peerName!!).firstOrNull { it.username == peerName }
        assertNotNull("web user found by Android search", peer)
        val peerId = peer!!.userId
        val cid = c.chatRepository.openDirectChat(peerId)
        assertEquals(Ids.directConversationId(uid, peerId), cid)

        // 3. Text: the web client replies in real time (quoting my message) and marks it read.
        val token = "ping ${uniqueSuffix()}"
        val sent = c.chatRepository.sendText(cid, token, null, emptyList())
        var reply: Message? = null
        await("reply from the web client", 60_000) {
            reply = c.db.messageDao().observeRecent(cid, 0L, 50).first()
                .firstOrNull { it.senderId == peerId && it.content == "echo: $token" }
            reply != null
        }
        assertEquals("the web reply quotes my message", sent.messageId, reply!!.replyToMessageId)
        await("read receipt written by the web client", 60_000) { c.db.messageDao().get(sent.messageId)?.status == MessageStatus.READ }
        await("chat-list preview written by the web client") { c.db.conversationDao().get(cid)?.lastMessageText?.startsWith("echo:") == true }

        // 4. Photo: the web client downloads my upload from Storage and sends it back; Android downloads the web upload.
        c.chatRepository.sendMediaUri(cid, testImage(), MessageType.IMAGE, "photo $token", null)
        var mine: Message? = null
        await("my photo uploaded", 90_000) {
            mine = c.db.messageDao().observeRecent(cid, 0L, 50).first()
                .firstOrNull { it.senderId == uid && it.type == MessageType.IMAGE && it.status != MessageStatus.SENDING && it.mediaUrl != null }
            mine != null
        }
        var echoed: Message? = null
        await("photo from the web client", 90_000) {
            echoed = c.db.messageDao().observeRecent(cid, 0L, 50).first()
                .firstOrNull { it.senderId == peerId && it.type == MessageType.IMAGE && it.mediaUrl != null }
            echoed != null
        }
        val photo = echoed!!
        assertEquals("echo: photo $token", photo.content)
        assertTrue("web upload lives in the web user's Storage folder", photo.storagePath?.startsWith("chat-media/$cid/$peerId/") == true)
        assertEquals("the web client re-sent exactly the bytes it downloaded", mine!!.mediaSizeBytes, photo.mediaSizeBytes)
        c.chatRepository.downloadMedia(photo.messageId)
        val local = c.db.messageDao().get(photo.messageId)?.localMediaPath
        assertTrue("web upload downloaded on Android", local != null && File(local).length() == photo.mediaSizeBytes)

        c.authRepository.signOut()
    }
}
