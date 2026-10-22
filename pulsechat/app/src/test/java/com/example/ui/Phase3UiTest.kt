package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.domain.FileEntry
import com.example.domain.StorageCategory
import com.example.domain.StoragePolicy
import com.example.ui.settings.StorageSummary
import com.example.ui.settings.TemporaryFilesCard
import com.example.ui.status.MediaCaptionDialog
import com.example.ui.theme.PulseChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Phase3UiTest {
    @get:Rule val compose = createComposeRule()

    private val report = StoragePolicy.report(
        media = mapOf(
            StorageCategory.PHOTOS to listOf(FileEntry("a", 3L * 1024 * 1024, 0), FileEntry("b", 1024L * 1024, 0)),
            StorageCategory.VIDEOS to listOf(FileEntry("c", 40L * 1024 * 1024, 0)),
        ),
        unused = listOf(FileEntry("d", 2L * 1024 * 1024, 0)),
        temporaryBytes = 6L * 1024 * 1024, temporaryFiles = 12, appDataBytes = 9L * 1024 * 1024,
        freeBytes = 20L * 1024 * 1024 * 1024, deviceBytes = 128L * 1024 * 1024 * 1024,
    )

    @Test
    fun storageShowsTheTotalEachKindAndTheFreeSpace() {
        compose.setContent { PulseChatTheme { StorageSummary(report) } }
        compose.onNodeWithText("PulseChat uses 61.0 MB on this phone").assertExists()
        compose.onNodeWithText("20.0 GB free of 128.0 GB").assertExists()
        compose.onNodeWithTag("storage_row_PHOTOS", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Photos · 2").assertExists()
        compose.onNodeWithText("40.0 MB").assertExists()
        compose.onNodeWithTag("storage_row_UNUSED", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("storage_row_VOICE", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun storageSaysItIsCountingFirst() {
        compose.setContent { PulseChatTheme { StorageSummary(null) } }
        compose.onNodeWithText("Counting…").assertExists()
    }

    @Test
    fun temporaryFilesCanBeClearedOnlyWhenThereIsSomething() {
        var clears = 0
        compose.setContent { PulseChatTheme { TemporaryFilesCard(report, clearing = false, onClear = { clears++ }) } }
        compose.onNodeWithText("8.0 MB").assertExists() // temporary + unused
        compose.onNodeWithTag("storage_clear_temp").assertIsEnabled().performClick()
        assertEquals(1, clears)
    }

    @Test
    fun nothingToClearMeansNoButtonToPress() {
        val empty = StoragePolicy.report(emptyMap(), emptyList(), 0L, 0, 1_000L, 1L, 2L)
        compose.setContent { PulseChatTheme { TemporaryFilesCard(empty, clearing = false, onClear = {}) } }
        compose.onNodeWithTag("storage_clear_temp").assertIsNotEnabled()
    }

    @Test
    fun aMediaThatCannotBeSentAsItIsExplainsWhyAndDoesNotSend() {
        var sent = 0
        compose.setContent {
            PulseChatTheme {
                MediaCaptionDialog(Uri.parse("content://test/photo.jpg"), isVideo = false, title = "Omar", onDismiss = {}, onEdit = {},
                    note = "Too large to send as it is (up to 32.0 MB). Tap the scissors to shorten it.", noteIsWarning = true, sendEnabled = false) { sent++ }
            }
        }
        compose.onNodeWithTag("media_note").assertTextContains("Too large to send", substring = true)
        compose.onNodeWithTag("media_send").performClick()
        assertEquals(0, sent)
        compose.onNodeWithTag("media_edit").assertExists()
    }
}
