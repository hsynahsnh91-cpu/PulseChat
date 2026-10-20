package com.example.data

import android.app.Application
import com.example.data.model.Message
import com.example.data.repository.MediaRepository
import com.example.data.repository.SyncManager
import com.example.domain.model.MessageType
import com.example.media.VoiceRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PureLogicTest {
    @Test
    fun waveformDownsamplingKeepsPeaksAndBounds() {
        val bars = VoiceRecorder.downsample(listOf(0, 100, 0, 0, 50, 0, 0, 0), 4)
        assertEquals(listOf(100, 4, 50, 4), bars)
        assertEquals(48, VoiceRecorder.downsample(emptyList(), 48).size)
        assertTrue(VoiceRecorder.downsample((0..500).map { it % 100 }, 48).all { it in 4..100 })
    }

    @Test
    fun previewTextUsesTheMostUsefulField() {
        val base = Message(messageId = "m", conversationId = "c", senderId = "a", content = "caption")
        assertEquals("report.pdf", SyncManager.previewText(base.copy(type = MessageType.DOCUMENT, fileName = "report.pdf")))
        assertEquals("Omar", SyncManager.previewText(base.copy(type = MessageType.CONTACT, contactName = "Omar")))
        assertEquals("Lunch?", SyncManager.previewText(base.copy(type = MessageType.POLL, pollQuestion = "Lunch?")))
        assertEquals("", SyncManager.previewText(base.copy(deletedForEveryone = true)))
        assertEquals("caption", SyncManager.previewText(base.copy(type = MessageType.IMAGE)))
    }

    @Test
    fun messageJsonFieldsRoundTrip() {
        val m = Message(reactions = Message.encodeMap(mapOf("a" to "👍", "b" to "❤️")), pollOptions = "x|y|z", pollVotes = "1,0", pollVotesByUser = """{"a":2}""", mentions = "u1, u2")
        assertEquals(mapOf("a" to "👍", "b" to "❤️"), m.reactionMap())
        assertEquals(listOf(1, 0, 1), m.pollCounts())
        assertEquals(listOf("u1", "u2"), m.mentionList())
        assertEquals("", Message.encodeMap(emptyMap()))
    }

    @Test
    fun mimeTypesMapToMessageTypes() {
        assertEquals(MessageType.IMAGE, MediaRepository.typeForMime("image/png"))
        assertEquals(MessageType.VIDEO, MediaRepository.typeForMime("video/mp4"))
        assertEquals(MessageType.AUDIO, MediaRepository.typeForMime("audio/mp4"))
        assertEquals(MessageType.DOCUMENT, MediaRepository.typeForMime("application/pdf"))
        assertEquals("pdf", MediaRepository.extensionFor("application/pdf", "file.pdf"))
    }

    @Test
    fun legacyHashMatchesPreviousLocalAccountsFormat() {
        // SHA-256 hex of "password" - the format earlier versions stored for device-local accounts.
        assertEquals("5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8",
            com.example.data.repository.AuthRepository.legacyHash("password"))
    }
}
