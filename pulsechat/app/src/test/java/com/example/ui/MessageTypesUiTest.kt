package com.example.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.Message
import com.example.domain.MessagePolicy
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.media.VoicePlayer
import com.example.ui.chat.BubbleCallbacks
import com.example.ui.chat.MessageActions
import com.example.ui.chat.MessageActionsSheet
import com.example.ui.chat.MessageItem
import com.example.ui.chat.MessageRow
import com.example.ui.common.DeliveryTicks
import com.example.ui.theme.PulseChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MessageTypesUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()

    private var voted = -1
    private var toggledVoice = false
    private var dialed = false
    private var openedLocation = false

    private val callbacks = BubbleCallbacks(
        onClick = {}, onLongClick = {}, onSwipeReply = {}, onReplyQuote = {}, onRetry = {}, onDownload = {}, onCancel = {},
        onOpenMedia = {}, onOpenDocument = {}, onOpenLocation = { openedLocation = true }, onDialContact = { dialed = true },
        onToggleVoice = { toggledVoice = true }, onSeekVoice = { _, _ -> }, onVoiceSpeed = {}, onVote = { _, i -> voted = i }, onReactions = {},
    )

    private fun show(vararg messages: Message, names: Map<String, String> = emptyMap(), isGroup: Boolean = false) {
        compose.setContent {
            PulseChatTheme {
                Column {
                    messages.forEach { m ->
                        MessageRow(
                            item = MessageItem(m, showSender = isGroup && m.senderId != "me", groupedWithOlder = false), me = "me", names = names,
                            isGroup = isGroup, isAi = false, hideTicks = false, selected = false, selectionMode = false, highlighted = false,
                            searchQuery = "", voice = VoicePlayer.State(), showReadReceipts = true, canSwipeReply = true, callbacks = callbacks,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun pollShowsCountsAndVotes() {
        show(Message(messageId = "p", conversationId = "c", senderId = "bob", type = MessageType.POLL, content = "Lunch?", pollQuestion = "Lunch?",
            pollOptions = "Pizza|Salad", pollVotesByUser = """{"bob":0,"carol":0}""", status = MessageStatus.SENT, createdAt = now))
        compose.onNodeWithText("Lunch?").assertExists()
        compose.onNodeWithText("2 votes").assertExists()
        compose.onNodeWithText("Salad").performClick()
        assertEquals(1, voted)
    }

    @Test
    fun voiceContactAndLocationMessagesAreInteractive() {
        show(
            Message(messageId = "v", conversationId = "c", senderId = "bob", type = MessageType.AUDIO, mediaUrl = "https://example.invalid/v.m4a",
                mediaDurationSeconds = 7, waveform = "10,40,80,20", status = MessageStatus.SENT, createdAt = now),
            Message(messageId = "k", conversationId = "c", senderId = "bob", type = MessageType.CONTACT, content = "Omar", contactName = "Omar",
                contactPhone = "+41 79 000 00 00", status = MessageStatus.SENT, createdAt = now),
            Message(messageId = "l", conversationId = "c", senderId = "bob", type = MessageType.LOCATION, locationLat = 47.3769, locationLng = 8.5417,
                status = MessageStatus.SENT, createdAt = now),
        )
        compose.onNodeWithText("0:07").assertExists()
        compose.onNodeWithContentDescription("Play").performClick()
        assertTrue(toggledVoice)
        compose.onNodeWithText("+41 79 000 00 00").assertExists()
        compose.onNodeWithText("Call").performClick()
        assertTrue(dialed)
        compose.onNodeWithText("47.37690, 8.54170").performClick()
        assertTrue(openedLocation)
    }

    @Test
    fun systemEventsAndSenderNamesAreLocalizedWithNames() {
        show(
            Message(messageId = "s", conversationId = "g", senderId = "sara", type = MessageType.SYSTEM, status = MessageStatus.SENT, createdAt = now,
                systemEvent = """{"event":"added","actor":"sara","targets":["omar"],"value":""}"""),
            Message(messageId = "t", conversationId = "g", senderId = "omar", content = "Thanks!", status = MessageStatus.SENT, createdAt = now),
            names = mapOf("sara" to "Sara", "omar" to "Omar"), isGroup = true,
        )
        compose.onNodeWithText("Sara added Omar").assertExists()
        compose.onNodeWithText("Omar").assertExists()
        compose.onNodeWithText("Thanks!").assertExists()
    }

    @Test
    fun footerShowsEditedStarredAndTicks() {
        show(Message(messageId = "e", conversationId = "c", senderId = "me", content = "Fixed typo", status = MessageStatus.DELIVERED, createdAt = now,
            editedAt = now, isStarred = true))
        compose.onNodeWithText("edited", substring = true).assertExists()
        compose.onNodeWithContentDescription("Starred").assertExists()
        compose.onNodeWithContentDescription("Delivered").assertExists()
    }

    @Test
    fun ticksDescribePendingAndFailedStates() {
        compose.setContent {
            PulseChatTheme {
                Column {
                    DeliveryTicks(MessageStatus.PENDING)
                    DeliveryTicks(MessageStatus.FAILED)
                    DeliveryTicks(MessageStatus.READ, showRead = false)
                }
            }
        }
        compose.onNodeWithContentDescription("Waiting for network").assertExists()
        compose.onNodeWithContentDescription("Failed").assertExists()
        compose.onNodeWithContentDescription("Delivered").assertExists() // read receipts hidden when disabled
    }

    @Test
    fun actionSheetOffersEditOnlyInsideTheWindow() {
        val old = Message(messageId = "o", conversationId = "c", senderId = "me", content = "old", status = MessageStatus.READ,
            createdAt = now - MessagePolicy.EDIT_WINDOW_MS - 60_000)
        val facts = MessagePolicy.Facts(isMine = true, type = MessageType.TEXT, status = MessageStatus.READ, createdAt = old.createdAt, deletedForEveryone = false)
        val actions = MessageActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        compose.setContent { PulseChatTheme { MessageActionsSheet(old, facts, now, "me", actions, onDismiss = {}) } }
        compose.onNodeWithText("Reply").assertExists()
        compose.onNodeWithText("Forward").assertExists()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Message info").assertExists()
    }
}
