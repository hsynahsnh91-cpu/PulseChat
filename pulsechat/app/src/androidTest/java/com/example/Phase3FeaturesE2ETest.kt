package com.example

import android.util.Base64
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.repository.VideoCover
import com.example.domain.VideoEditMath
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.media.VideoTrimException
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Video editing end to end (real video, real cut on the device, real sync through the Firebase
 * emulators running this repo's rules): the cut keeps its voice, starts on a keyframe, and the cover
 * chosen by the sender reaches the other person inside the message, before any download.
 */
@RunWith(AndroidJUnit4::class)
class Phase3FeaturesE2ETest : EmulatorE2ETest() {

    private fun com.example.data.model.Message.onServer() =
        status == MessageStatus.SENT || status == MessageStatus.DELIVERED || status == MessageStatus.READ

    @Test
    fun aTrimmedVideoAndItsCoverReachTheOtherPerson() = runBlocking {
        startFresh()
        val pw = "Passw0rd1"
        val suffix = uniqueSuffix()
        c.authRepository.register("Bob", "vid_b$suffix", pw, pw)
        val bob = awaitSignedIn()
        switchDevice()
        c.authRepository.register("Alice", "vid_a$suffix", pw, pw)
        awaitSignedIn()
        val cid = c.chatRepository.openDirectChat(bob)

        // The editor's building blocks on a real video.
        val source = TestMedia.sampleVideo(context)
        val trimmer = c.videoTrimmer
        val info = trimmer.probe(source)
        assertEquals(TestMedia.SAMPLE_DURATION_MS.toDouble(), info.durationMs.toDouble(), 150.0)
        assertEquals(TestMedia.SAMPLE_KEYFRAMES_MS, info.keyframesMs)
        assertTrue(info.hasAudio)
        val (cut, cutUri) = c.media.newCaptureFile("mp4")
        val realStart = trimmer.trim(source, 1_700L, 4_200L, cut)
        assertEquals("the cut starts on the keyframe before the chosen start", 1_000L, realStart)
        val cutInfo = trimmer.probe(cutUri)
        assertEquals(3_200.0, cutInfo.durationMs.toDouble(), 200.0)
        assertTrue(cutInfo.hasAudio)
        assertTrue(cut.length() in 1 until info.sizeBytes)
        val frame = trimmer.frame(cutUri, 2_000L, VideoEditMath.COVER_MAX_DIM, exact = true)
        assertNotNull(frame)
        val jpeg = trimmer.coverJpeg(frame!!)!!
        assertTrue(jpeg.size <= VideoEditMath.COVER_MAX_BYTES)
        val cover = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        // Sent with its cover; an unedited video gets a cover from near its start.
        c.chatRepository.sendMediaUri(cid, cutUri, MessageType.VIDEO, "From the walk-through", null, VideoCover(2_000L, cover))
        c.chatRepository.sendMediaUri(cid, source, MessageType.VIDEO, "", null)
        await("both videos uploaded and sent", 120_000) {
            c.db.messageDao().observeRecent(cid, 0L, 10).first().count { it.type == MessageType.VIDEO && it.onServer() } == 2
        }
        val sent = c.db.messageDao().observeRecent(cid, 0L, 10).first().first { it.type == MessageType.VIDEO && it.content == "From the walk-through" }
        assertEquals(2_000L, sent.mediaCoverMs)
        assertEquals(cover, sent.mediaThumb)
        val remote = c.firestore.collection("conversations").document(cid).collection("messages").document(sent.messageId).get(Source.SERVER).await()
        val media = remote.get("media") as Map<*, *>
        assertEquals(2_000L, (media["coverMs"] as Number).toLong())
        assertEquals(jpeg.size, (media["thumb"] as Blob).toBytes().size)

        // The other person sees the chosen cover before downloading anything.
        switchDevice()
        c.authRepository.signInWithPassword("vid_b$suffix", pw)
        awaitSignedIn()
        await("videos synced to Bob") { c.db.messageDao().observeRecent(cid, 0L, 10).first().count { it.type == MessageType.VIDEO } == 2 }
        val received = c.db.messageDao().observeRecent(cid, 0L, 10).first()
        val edited = received.first { it.content == "From the walk-through" }
        assertEquals(2_000L, edited.mediaCoverMs)
        assertEquals("the same cover image", cover, edited.mediaThumb)
        assertEquals(3, edited.mediaDurationSeconds)
        assertNull("nothing downloaded yet", edited.localMediaPath)
        val plain = received.first { it.type == MessageType.VIDEO && it.content.isEmpty() }
        assertNotNull("unedited videos travel with a cover too", plain.mediaThumb)
        assertEquals(VideoEditMath.defaultCoverMs(TestMedia.SAMPLE_DURATION_MS), plain.mediaCoverMs)
        assertEquals(6, plain.mediaDurationSeconds)
        c.authRepository.signOut()
    }

    @Test
    fun aFileThatIsNotAVideoIsReportedNotCrashed() = runBlocking {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val bad = File(dir, "not_a_video_${System.nanoTime()}.mp4").apply { writeText("this is not a video") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", bad)
        assertEquals(0L, c.videoTrimmer.probe(uri).durationMs)
        val out = File(dir, "out_${System.nanoTime()}.mp4")
        try {
            c.videoTrimmer.trim(uri, 0L, 1_000L, out)
            fail("a broken file must not produce a cut")
        } catch (e: VideoTrimException) {
            // expected
        }
        assertFalse("no half-written file is left behind", out.exists())
    }
}
