package com.example

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.VideoEditMath
import com.example.ui.chat.EditedVideo
import com.example.ui.chat.VideoEditorDialog
import com.example.ui.theme.PulseChatTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The video editor on a real device with a real video: trim, cover, the cut file and its cover image;
 * and a video too large to send can only be confirmed once it is short enough.
 */
@RunWith(AndroidJUnit4::class)
class VideoEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val c: AppContainer get() = (context.applicationContext as PulseChatApplication).container

    private fun waitForEditor() = compose.waitUntil(20_000) { compose.onAllNodesWithTag("video_trim_range").fetchSemanticsNodes().isNotEmpty() }

    private fun rangeThumbs() = compose.onNodeWithTag("video_trim_range").onChildren().filter(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    @Test
    fun trimmingAndChoosingACoverGiveAShorterVideoWithThatCover() {
        val source = TestMedia.sampleVideo(context)
        var result: EditedVideo? = null
        compose.setContent { PulseChatTheme { VideoEditorDialog(source, maxBytes = 32L * 1024 * 1024, onDone = { result = it }, onDismiss = {}) } }
        waitForEditor()
        rangeThumbs()[0].performSemanticsAction(SemanticsActions.SetProgress) { it(1_700f) }
        rangeThumbs()[1].performSemanticsAction(SemanticsActions.SetProgress) { it(4_200f) }
        compose.waitForIdle()
        // The start moved back to the keyframe at 1 s: the editor shows the real cut.
        compose.onNodeWithTag("video_edit_summary").assertTextContains("0:01 – 0:04", substring = true)
        compose.onNodeWithTag("video_cover_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(3_000f) }
        compose.onNodeWithTag("video_edit_done").assertIsEnabled().performClick()
        compose.waitUntil(30_000) { result != null }

        val edited = result!!
        assertTrue(edited.trimmed)
        assertEquals(3_200.0, edited.durationMs.toDouble(), 150.0)
        assertEquals("cover position inside the cut", 2_000.0, edited.coverMs.toDouble(), 100.0)
        assertNotNull(edited.cover)
        assertTrue("cover small enough to travel with the message: ${edited.cover!!.size}", edited.cover!!.size in 1..VideoEditMath.COVER_MAX_BYTES)
        val cut = runBlocking { c.videoTrimmer.probe(edited.uri) }
        assertEquals(3_200.0, cut.durationMs.toDouble(), 200.0)
        assertTrue("the voice track is kept", cut.hasAudio)
        assertEquals("the cut starts on a keyframe", 0L, cut.keyframesMs.first())
        assertTrue("shorter than the original", cut.sizeBytes in 1 until runBlocking { c.videoTrimmer.probe(source) }.sizeBytes)
    }

    @Test
    fun aVideoTooLargeToSendCanOnlyBeConfirmedOnceItIsShortEnough() {
        val source = TestMedia.sampleVideo(context)
        val original = runBlocking { c.videoTrimmer.probe(source) }.sizeBytes
        compose.setContent { PulseChatTheme { VideoEditorDialog(source, maxBytes = original / 2, onDone = {}, onDismiss = {}) } }
        waitForEditor()
        compose.onNodeWithTag("video_edit_too_large").assertExists()
        compose.onNodeWithTag("video_edit_done").assertIsNotEnabled()
        rangeThumbs()[1].performSemanticsAction(SemanticsActions.SetProgress) { it(2_000f) }
        compose.waitForIdle()
        compose.onNodeWithTag("video_edit_too_large").assertDoesNotExist()
        compose.onNodeWithTag("video_edit_done").assertIsEnabled()
    }
}
