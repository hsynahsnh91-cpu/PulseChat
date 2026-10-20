package com.example.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import com.example.R
import com.example.data.model.ChatListRow
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.media.VoicePlayer
import com.example.media.VoiceRecorder
import com.example.ui.chat.BubbleCallbacks
import com.example.ui.chat.Composer
import com.example.ui.chat.MessageItem
import com.example.ui.chat.MessageRow
import com.example.ui.chat.ReplyQuote
import com.example.ui.common.ConfirmDialog
import com.example.ui.home.ChatRow
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
class ComponentsUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()

    private fun callbacks(onRetry: (Message) -> Unit = {}, onReplyQuote: (String) -> Unit = {}, onReactions: (Message) -> Unit = {}) = BubbleCallbacks(
        onClick = {}, onLongClick = {}, onSwipeReply = {}, onReplyQuote = onReplyQuote, onRetry = onRetry, onDownload = {}, onCancel = {},
        onOpenMedia = {}, onOpenDocument = {}, onOpenLocation = {}, onDialContact = {}, onToggleVoice = {}, onSeekVoice = { _, _ -> },
        onVoiceSpeed = {}, onVote = { _, _ -> }, onReactions = onReactions,
    )

    private fun row(conversation: Conversation, status: MessageStatus? = MessageStatus.READ) =
        ChatListRow(conversation, peerName = "Omar", peerAvatar = null, peerColor = null, peerNickname = null, lastStatus = status, lastDeleted = false, lastSenderName = null)

    @Test
    fun chatRowShowsUnreadPinnedMutedAndTicks() {
        val conv = Conversation(
            conversationId = "c", participantIds = "me,omar", peerId = "omar", lastMessageText = "See you", lastMessageSenderId = "me",
            lastMessageId = "m", lastMessageType = MessageType.TEXT, lastMessageTimestamp = now, unreadCount = 3, isPinned = true, mutedUntil = Long.MAX_VALUE,
        )
        compose.setContent { PulseChatTheme { ChatRow(row(conv), "me", emptySet(), selected = false, selectionMode = false, onClick = {}, onLongClick = {}) } }
        compose.onNodeWithText("Omar").assertIsDisplayed()
        compose.onNodeWithText("You: See you").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pinned").assertExists()
        compose.onNodeWithContentDescription("Muted").assertExists()
        compose.onNodeWithContentDescription("Read").assertExists()
        compose.onNodeWithText("3").assertExists()
    }

    @Test
    fun chatRowShowsTypingAndDraft() {
        val conv = Conversation(conversationId = "c", participantIds = "me,omar", peerId = "omar", lastMessageText = "x", lastMessageTimestamp = now, draftText = "half-written")
        compose.setContent {
            PulseChatTheme {
                Column {
                    ChatRow(row(conv), "me", setOf("omar"), false, false, {}, {})
                    ChatRow(row(conv.copy(conversationId = "d")), "me", emptySet(), false, false, {}, {})
                }
            }
        }
        compose.onNodeWithText("typing…").assertExists()
        compose.onNodeWithText("Draft: half-written").assertExists()
    }

    @Test
    fun composerSwitchesBetweenMicAndSend() {
        var value by mutableStateOf(TextFieldValue(""))
        var sent = 0
        compose.setContent {
            PulseChatTheme {
                Composer(
                    value = value, onValueChange = { value = it }, onSend = { sent++ }, onAttach = {}, onCamera = {}, emojiOpen = false,
                    onEmojiToggle = {}, recording = VoiceRecorder.State(), onStartRecording = {}, onFinishRecording = {},
                    enterToSend = false, isAiChat = false, editing = false,
                )
            }
        }
        compose.onNodeWithTag("composer_mic").assertExists()
        compose.onNodeWithTag("composer_input").performTextInput("Hello")
        compose.onNodeWithTag("composer_send").assertExists().performClick()
        assertEquals(1, sent)
        assertEquals("Hello", value.text)
    }

    @Test
    fun deletedFailedAndReactedMessages() {
        var retried = false
        var reactionsOpened = false
        val deleted = Message(messageId = "1", conversationId = "c", senderId = "bob", deletedForEveryone = true, status = MessageStatus.SENT, createdAt = now)
        val failed = Message(messageId = "2", conversationId = "c", senderId = "me", content = "Hi", status = MessageStatus.FAILED, createdAt = now)
        val reacted = Message(messageId = "3", conversationId = "c", senderId = "bob", content = "Nice", status = MessageStatus.SENT, createdAt = now,
            reactions = """{"me":"👍","carol":"👍"}""")
        compose.setContent {
            PulseChatTheme {
                Column {
                    listOf(deleted, failed, reacted).forEach { m ->
                        MessageRow(
                            item = MessageItem(m, showSender = false, groupedWithOlder = false), me = "me", names = emptyMap(), isGroup = false, isAi = false,
                            hideTicks = false, selected = false, selectionMode = false, highlighted = false, searchQuery = "", voice = VoicePlayer.State(),
                            showReadReceipts = true, canSwipeReply = true,
                            callbacks = callbacks(onRetry = { retried = true }, onReactions = { reactionsOpened = true }),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("This message was deleted").assertExists()
        compose.onNodeWithText("Not sent. Tap to retry").performClick()
        assertTrue(retried)
        compose.onNodeWithText("👍").performClick()
        assertTrue(reactionsOpened)
    }

    @Test
    fun replyQuoteNavigatesToOriginal() {
        var target: String? = null
        compose.setContent { PulseChatTheme { ReplyQuote("Sara", "u1", MessageType.TEXT, "original text", onClick = { target = "m1" }) } }
        compose.onNodeWithText("original text").performClick()
        assertEquals("m1", target)
    }

    @Test
    fun confirmDialogPassesCheckboxChoice() {
        var result: Boolean? = null
        compose.setContent {
            PulseChatTheme {
                ConfirmDialog(title = "Clear this chat?", text = null, confirmLabel = "Clear", onConfirm = { result = it }, onDismiss = {},
                    checkboxLabel = "Keep starred messages", checkboxDefault = true)
            }
        }
        compose.onNodeWithText("Keep starred messages").performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(false, result)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "ar-ldrtl")
class ArabicLocaleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun arabicResourcesRenderRightToLeft() {
        var direction: LayoutDirection? = null
        compose.setContent {
            PulseChatTheme {
                direction = LocalLayoutDirection.current
                Column {
                    Text(stringResource(R.string.tab_chats))
                    Text(pluralStringResource(R.plurals.unread_messages, 2, 2))
                }
            }
        }
        compose.onNodeWithText("الدردشات").assertExists()
        compose.onNodeWithText("رسالتان غير مقروءتين").assertExists()
        assertEquals(LayoutDirection.Rtl, direction)
    }
}
