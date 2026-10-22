package com.example.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.Message
import com.example.data.repository.LiveLocationView
import com.example.domain.EditHistory
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.media.VoicePlayer
import com.example.ui.chat.BubbleCallbacks
import com.example.ui.chat.EditHistoryContent
import com.example.ui.chat.MessageItem
import com.example.ui.chat.MessageRow
import com.example.ui.common.MuteDialog
import com.example.ui.location.LiveLocationBubble
import com.example.ui.location.LiveLocationSupport
import com.example.ui.security.ChatLockGate
import com.example.ui.theme.PulseChatTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Phase2UiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    // ------------------------------------------------------------------ chat lock

    @Test
    fun aLockedChatShowsNothingOfItsContentUntilTheCheckSucceeds() {
        var open by mutableStateOf(false)
        var prompts = 0
        compose.setContent {
            PulseChatTheme {
                ChatLockGate(locked = true, open = open, deviceSecure = true, forList = false, onUnlock = { prompts++ }, onBack = {}, onOpenSecuritySettings = {}) {
                    Text("Secret message", Modifier.testTag("chat_content"))
                }
            }
        }
        compose.onNodeWithTag("chat_lock_gate").assertExists()
        compose.onNodeWithTag("chat_content").assertDoesNotExist()
        assertEquals("the phone's check is requested right away", 1, prompts)
        compose.onNodeWithTag("chat_lock_unlock").performClick()
        assertEquals(2, prompts)
        open = true // the check succeeded
        compose.onNodeWithTag("chat_content").assertExists()
        compose.onNodeWithTag("chat_lock_gate").assertDoesNotExist()
    }

    @Test
    fun withoutAScreenLockTheChatStaysClosedAndExplainsWhy() {
        var prompts = 0
        compose.setContent {
            PulseChatTheme {
                ChatLockGate(locked = true, open = false, deviceSecure = false, forList = false, onUnlock = { prompts++ }, onBack = {}, onOpenSecuritySettings = {}) {
                    Text("Secret message", Modifier.testTag("chat_content"))
                }
            }
        }
        compose.onNodeWithTag("chat_content").assertDoesNotExist()
        compose.onNodeWithText("Open phone settings").assertExists()
        assertEquals(0, prompts)
    }

    @Test
    fun unlockedChatsOpenDirectlyAndUnknownStateShowsNothing() {
        var locked by mutableStateOf<Boolean?>(null)
        compose.setContent {
            PulseChatTheme {
                ChatLockGate(locked = locked, open = false, deviceSecure = true, forList = false, onUnlock = {}, onBack = {}, onOpenSecuritySettings = {}) {
                    Text("Chat", Modifier.testTag("chat_content"))
                }
            }
        }
        compose.onNodeWithTag("chat_content").assertDoesNotExist()
        compose.onNodeWithTag("chat_lock_gate").assertDoesNotExist()
        locked = false
        compose.onNodeWithTag("chat_content").assertExists()
    }

    // ------------------------------------------------------------------ edit history

    private fun edited(): Message {
        val history = EditHistory.encode(listOf(EditHistory.Entry("See you at 9", now - 120_000L), EditHistory.Entry("See you at 10", now - 60_000L)))
        return Message(
            messageId = "e1", conversationId = "c", senderId = "omar", type = MessageType.TEXT, content = "See you at 10:30",
            status = MessageStatus.DELIVERED, createdAt = now - 180_000L, editedAt = now - 60_000L, editHistory = history,
        )
    }

    @Test
    fun editHistoryListsEveryVersionNewestFirst() {
        compose.setContent { PulseChatTheme { EditHistoryContent(edited()) } }
        compose.onNodeWithTag("edit_version_0").assertTextContains("See you at 10:30", substring = true).assertTextContains("Current", substring = true)
        compose.onNodeWithTag("edit_version_1").assertTextContains("See you at 10", substring = true)
        compose.onNodeWithTag("edit_version_2").assertTextContains("See you at 9", substring = true).assertTextContains("Original", substring = true)
        compose.onNodeWithTag("edit_version_3").assertDoesNotExist()
    }

    @Test
    fun theEditedLabelOpensTheHistory() {
        var opened: Message? = null
        val callbacks = BubbleCallbacks(
            onClick = {}, onLongClick = {}, onSwipeReply = {}, onReplyQuote = {}, onRetry = {}, onDownload = {}, onCancel = {},
            onOpenMedia = {}, onOpenDocument = {}, onOpenLocation = {}, onDialContact = {}, onToggleVoice = {},
            onSeekVoice = { _, _ -> }, onVoiceSpeed = {}, onVote = { _, _ -> }, onReactions = {}, onShowEdits = { opened = it },
        )
        compose.setContent {
            PulseChatTheme {
                Column {
                    MessageRow(
                        item = MessageItem(edited(), showSender = false, groupedWithOlder = false), me = "me", names = emptyMap(),
                        isGroup = false, isAi = false, hideTicks = false, selected = false, selectionMode = false, highlighted = false,
                        searchQuery = "", voice = VoicePlayer.State(), showReadReceipts = true, canSwipeReply = true, callbacks = callbacks,
                    )
                }
            }
        }
        compose.onNodeWithTag("edited_label", useUnmergedTree = true).performClick()
        assertEquals("e1", opened?.messageId)
    }

    // ------------------------------------------------------------------ live location

    private fun liveMessage(mine: Boolean) = Message(
        messageId = "l1", conversationId = "c", senderId = if (mine) "me" else "omar", type = MessageType.LOCATION, status = MessageStatus.SENT,
        createdAt = now - 60_000L, locationLat = 52.01, locationLng = 4.36, liveId = "s1", liveUntil = now + hour,
    )

    private fun view(fixAt: Long, endedAt: Long = 0L) = LiveLocationView(
        ownerId = "omar", lat = 52.02, lng = 4.37, accuracyMeters = 10.0, fixAt = fixAt, expiresAt = now + hour, endedAt = endedAt, fromCache = false,
    )

    private fun showBubble(view: LiveLocationView?, mine: Boolean, onStop: () -> Unit = {}) {
        // This phone shares session "s1" when the message is mine.
        val support = LiveLocationSupport(observe = { flowOf(view) }, now = { now }, onOpen = {}, onStop = { onStop() },
            activeLiveId = MutableStateFlow(if (mine) "s1" else null))
        compose.setContent {
            PulseChatTheme {
                LiveLocationBubble(liveMessage(mine), mine = mine, support = support, metaColor = Color.Gray, map = { _, _, _, modifier -> Box(modifier) })
            }
        }
    }

    @Test
    fun aFreshPointIsLiveAndTheSenderCanStop() {
        var stopped = false
        showBubble(view(fixAt = now - 10_000L), mine = true, onStop = { stopped = true })
        compose.onNodeWithTag("live_status").assertTextContains("Live until", substring = true)
        compose.onNodeWithText("LIVE").assertExists()
        compose.onNodeWithTag("live_stop").performClick()
        assertTrue(stopped)
    }

    @Test
    fun anOldPointIsShownWithItsTimeNotAsLive() {
        showBubble(view(fixAt = now - 6 * 60_000L), mine = false)
        compose.onNodeWithTag("live_status").assertTextContains("Not live", substring = true)
        compose.onNodeWithText("LIVE").assertDoesNotExist()
        compose.onNodeWithTag("live_stop").assertDoesNotExist()
    }

    @Test
    fun aStoppedSessionShowsThatItEnded() {
        showBubble(view(fixAt = now - 30_000L, endedAt = now - 20_000L), mine = true)
        compose.onNodeWithTag("live_status").assertTextContains("Live location ended", substring = true)
        compose.onNodeWithTag("live_stop").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ mute

    @Test
    fun muteOffersOneHourEightHoursOneDayAndUntilTurnedOn() {
        var chosen = 0L
        compose.setContent { PulseChatTheme { MuteDialog(isGroup = true, onSelect = { chosen = it }, onDismiss = {}) } }
        listOf("1 hour", "8 hours", "1 day", "Until I turn them back on").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("In a group you're still notified when someone mentions you.", substring = true).assertExists()
        compose.onNodeWithTag("mute_option_ONE_HOUR").performClick()
        val expected = System.currentTimeMillis() + hour
        assertTrue("muted for about an hour", kotlin.math.abs(chosen - expected) < 60_000L)
        assertFalse(chosen == Long.MAX_VALUE)
    }
}
