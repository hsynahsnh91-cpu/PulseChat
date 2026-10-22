package com.example

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.MediaRefs
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Media without Cloud Storage: the production project has no usable bucket, so photos, voice
 * notes and profile photos go to PulseChat's built-in media store (Firestore chunks, see
 * MediaRefs). The emulator suite has Storage, so the test forces the built-in store and checks the
 * whole path against this repo's firestore.rules: upload in several chunks, sync to a "fresh
 * device", download with identical bytes, profile photo, and that nobody else can overwrite or
 * delete the files. It also checks the per-tab titles and menus of the home screen.
 */
@RunWith(AndroidJUnit4::class)
class BuiltInMediaStoreE2ETest : EmulatorE2ETest() {

    @After fun restoreBackend() { c.media.forceBuiltInStore = false }

    @Test
    fun photoVoiceAndProfilePhotoWithoutCloudStorage() = runBlocking {
        startFresh()
        c.media.forceBuiltInStore = true
        val pw = "Passw0rd1"
        val suffix = uniqueSuffix()
        val aliceName = "media_a$suffix"
        val bobName = "media_b$suffix"

        // 1. Alice registers.
        c.authRepository.register("Alice", aliceName, pw, pw)
        val alice = awaitSignedIn()
        switchDevice()

        // 2. Bob registers, sets a profile photo, and sends Alice a photo and a voice note larger
        //    than one chunk (so the multi-chunk path is exercised).
        c.authRepository.register("Bob", bobName, pw, pw)
        val bob = awaitSignedIn()
        c.userRepository.updatePhoto(testImage())
        val bobPhoto = c.db.userDao().get(bob)?.avatarUrl
        assertTrue("profile photo stored in the built-in media store: $bobPhoto", MediaRefs.isPulse(bobPhoto))

        val cid = c.chatRepository.openDirectChat(alice)
        c.chatRepository.sendMediaUri(cid, testImage(), MessageType.IMAGE, "photo", null)
        val voiceBytes = Random(7).nextBytes(MediaRefs.CHUNK_BYTES + 150_000)
        val voiceFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a").apply { writeBytes(voiceBytes) }
        c.chatRepository.sendVoice(cid, voiceFile, 4_000, listOf(10, 40, 80, 40, 10), null)

        var photoId = ""
        var voiceId = ""
        await("photo and voice note uploaded and acknowledged", 120_000) {
            val recent = c.db.messageDao().observeRecent(cid, 0L, 20).first()
            val photo = recent.firstOrNull { it.type == MessageType.IMAGE }
            val voice = recent.firstOrNull { it.type == MessageType.AUDIO }
            photoId = photo?.messageId.orEmpty()
            voiceId = voice?.messageId.orEmpty()
            photo?.status == MessageStatus.SENT && voice?.status == MessageStatus.SENT
        }
        val sentVoice = c.db.messageDao().get(voiceId)!!
        assertTrue("voice note uses the built-in store", MediaRefs.isPulse(sentVoice.mediaUrl) && sentVoice.storagePath == sentVoice.mediaUrl)
        val voiceMeta = c.firestore.collection("media").document(MediaRefs.idOf(sentVoice.mediaUrl)!!).get(Source.SERVER).await()
        assertEquals("voice note split into two chunks", 2L, voiceMeta.getLong("chunks"))
        assertEquals(bob, voiceMeta.getString("owner"))
        assertEquals("chat", voiceMeta.getString("scope"))
        assertEquals(cid, voiceMeta.getString("conversationId"))
        switchDevice()

        // 3. Alice on a fresh device: both messages arrive, the files download with identical bytes,
        //    and Bob's profile photo loads.
        c.authRepository.signInWithPassword(aliceName, pw)
        awaitSignedIn()
        await("media messages synced") {
            MediaRefs.isPulse(c.db.messageDao().get(photoId)?.mediaUrl) && MediaRefs.isPulse(c.db.messageDao().get(voiceId)?.mediaUrl)
        }
        c.chatRepository.downloadMedia(photoId)
        c.chatRepository.downloadMedia(voiceId)
        val photoLocal = c.db.messageDao().get(photoId)?.localMediaPath
        assertTrue("photo downloaded", photoLocal != null && File(photoLocal).length() > 0)
        val voiceLocal = c.db.messageDao().get(voiceId)?.localMediaPath
        assertTrue("voice note downloaded", voiceLocal != null)
        assertArrayEquals("voice note bytes are identical", voiceBytes, File(voiceLocal!!).readBytes())

        c.userRepository.refreshProfile(bob, force = true)
        val photoRef = c.db.userDao().get(bob)?.avatarUrl
        assertEquals("Bob's profile photo reference reached Alice", bobPhoto, photoRef)
        assertTrue("profile photo loads for display", c.media.cachedStoreFile(photoRef!!).length() > 0)

        // 4. Only the uploader can change or delete media.
        val voiceDoc = c.firestore.collection("media").document(MediaRefs.idOf(sentVoice.mediaUrl)!!)
        assertDenied("overwriting someone else's media chunk") {
            voiceDoc.collection("chunks").document("0").set(mapOf("owner" to alice, "data" to Blob.fromBytes(ByteArray(4)))).await()
        }
        assertDenied("deleting someone else's media") { voiceDoc.delete().await() }

        // 5. Home screen: each tab shows its own title and menu (chat actions stay in Chats).
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Bob").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_updates").performClick()
        compose.onNodeWithTag("home_title").assertTextEquals(context.getString(R.string.tab_updates))
        compose.onNodeWithTag("home_menu").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.privacy_status)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("no chat actions in the Updates menu", compose.onAllNodesWithText(context.getString(R.string.new_group)).fetchSemanticsNodes().isEmpty())
        Espresso.pressBack()
        compose.onNodeWithTag("tab_calls").performClick()
        compose.onNodeWithTag("home_title").assertTextEquals(context.getString(R.string.tab_calls))
        compose.onNodeWithTag("home_menu").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.calls_clear_log)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("no chat actions in the Calls menu", compose.onAllNodesWithText(context.getString(R.string.archived_chats)).fetchSemanticsNodes().isEmpty())
        Espresso.pressBack()
        compose.onNodeWithTag("tab_chats").performClick()
        compose.onNodeWithTag("home_title").assertTextEquals(context.getString(R.string.app_name))

        c.authRepository.signOut()
    }
}
