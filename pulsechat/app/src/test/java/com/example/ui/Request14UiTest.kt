package com.example.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.ContactRow
import com.example.data.model.Message
import com.example.data.model.StatusItem
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.media.VoicePlayer
import com.example.ui.chat.BubbleCallbacks
import com.example.ui.chat.ContactSearch
import com.example.ui.chat.MessageItem
import com.example.ui.chat.MessageRow
import com.example.ui.home.UpdatesTab
import com.example.ui.home.UpdatesUi
import com.example.ui.theme.PulseChatTheme
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
class Request14UiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()

    private fun contact(name: String, username: String, nickname: String? = null) =
        ContactRow(userId = "u_$username", displayName = name, username = username, uniqueId10 = "0123456789", avatarUrl = null,
            avatarColorHex = "#326A9A", statusMessage = "", customNickname = nickname)

    @Test
    fun contactSearchMatchesNamesNicknamesAndUsernames() {
        val list = listOf(contact("Omar Haddad", "omar"), contact("Sara Lee", "sara_l", nickname = "Sister"), contact("Bob", "bobby"))
        assertEquals(list, ContactSearch.filter(list, "  "))
        assertEquals(listOf("omar"), ContactSearch.filter(list, "hadd").map { it.username })
        assertEquals(listOf("sara_l"), ContactSearch.filter(list, "sis").map { it.username })
        assertEquals(listOf("sara_l"), ContactSearch.filter(list, "sara").map { it.username })
        assertEquals(listOf("bobby"), ContactSearch.filter(list, "@BOB").map { it.username })
        assertTrue(ContactSearch.filter(list, "zz").isEmpty())
        assertEquals("@omar", ContactSearch.handleOf(list[0]))
    }

    @Test
    fun onlyUsernameCardsCountAsPulseChatContacts() {
        assertTrue(ContactSearch.isPulseHandle("@omar"))
        assertTrue(ContactSearch.isPulseHandle(" @sara_l "))
        assertFalse(ContactSearch.isPulseHandle("+41 79 000 00 00"))
        assertFalse(ContactSearch.isPulseHandle("0123456789"))
        assertFalse(ContactSearch.isPulseHandle("@"))
        assertFalse(ContactSearch.isPulseHandle(null))
    }

    @Test
    fun sharedPulseChatContactOffersMessageAndPhoneContactOffersCall() {
        var messaged: Message? = null
        var dialed = false
        val callbacks = BubbleCallbacks(
            onClick = {}, onLongClick = {}, onSwipeReply = {}, onReplyQuote = {}, onRetry = {}, onDownload = {}, onCancel = {},
            onOpenMedia = {}, onOpenDocument = {}, onOpenLocation = {}, onDialContact = { dialed = true }, onToggleVoice = {},
            onSeekVoice = { _, _ -> }, onVoiceSpeed = {}, onVote = { _, _ -> }, onReactions = {}, onMessageContact = { messaged = it },
        )
        val pulse = Message(messageId = "k1", conversationId = "c", senderId = "bob", type = MessageType.CONTACT, content = "Omar", contactName = "Omar",
            contactPhone = "@omar", status = MessageStatus.SENT, createdAt = now)
        compose.setContent {
            PulseChatTheme {
                Column {
                    MessageRow(
                        item = MessageItem(pulse, showSender = false, groupedWithOlder = false), me = "me", names = emptyMap(),
                        isGroup = false, isAi = false, hideTicks = false, selected = false, selectionMode = false, highlighted = false,
                        searchQuery = "", voice = VoicePlayer.State(), showReadReceipts = true, canSwipeReply = true, callbacks = callbacks,
                    )
                }
            }
        }
        compose.onNodeWithText("@omar").assertExists()
        compose.onNodeWithText("Call").assertDoesNotExist()
        compose.onNodeWithText("Message").performClick()
        assertEquals("k1", messaged?.messageId)
        assertFalse(dialed)
    }

    @Test
    fun myStatusRowShowsOnlyTheNumberOfViewers() {
        val mine = StatusItem(statusId = "s1", userId = "me", authorName = "Me", text = "Hello", mediaType = "TEXT", createdAt = now, expiresAt = now + 3_600_000L)
        compose.setContent {
            PulseChatTheme {
                UpdatesTab(
                    ui = UpdatesUi(mine = listOf(mine)), me = null, onMyStatus = {}, onAddText = {}, onOpen = {}, onRetry = {},
                    myViews = 3, contentPadding = PaddingValues(),
                )
            }
        }
        // The row is one TalkBack item ("My status, <time>, 3 views"); the count itself has no action.
        compose.onNodeWithTag("my_status_views", useUnmergedTree = true).assertContentDescriptionEquals("3 views").assertHasNoClickAction()
        compose.onNodeWithText("3").assertDoesNotExist() // read as "3 views", never as a bare number
    }

    @Test
    fun noViewersCountWithoutAStatus() {
        compose.setContent {
            PulseChatTheme {
                UpdatesTab(ui = UpdatesUi(), me = null, onMyStatus = {}, onAddText = {}, onOpen = {}, onRetry = {}, myViews = 0, contentPadding = PaddingValues())
            }
        }
        compose.onNodeWithTag("my_status_views", useUnmergedTree = true).assertDoesNotExist()
    }
}
