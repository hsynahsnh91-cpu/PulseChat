package com.example

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.ScheduledState
import com.example.data.repository.ChatRepository
import com.example.data.repository.TranslationException
import com.example.domain.model.MessageStatus
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 features end to end, against the Firebase emulators running this repo's rules:
 * - a scheduled message is delivered exactly once despite racing attempts (WorkManager + direct
 *   calls), a simulated crash after sending and an explicit retry; the recipient gets one copy;
 * - a scheduled message to a contact blocked meanwhile fails with a reason and can be deleted;
 * - chat folders filter the chat list, the media browser lists links, the diagnostics screen
 *   checks the server, and on-device language identification drives the translation flow.
 */
@RunWith(AndroidJUnit4::class)
class Phase1FeaturesE2ETest : EmulatorE2ETest() {
    private val pw = "Passw0rd1"

    private suspend fun twoUsers(prefix: String): Triple<String, String, String> {
        startFresh()
        val suffix = uniqueSuffix()
        val aliceName = "${prefix}_a$suffix"
        c.authRepository.register("Alice", aliceName, pw, pw)
        val alice = awaitSignedIn()
        switchDevice()
        c.authRepository.register("Bob", "${prefix}_b$suffix", pw, pw)
        awaitSignedIn()
        return Triple(alice, aliceName, suffix)
    }

    @Test
    fun scheduledMessageIsSentExactlyOnceDespiteRetriesAndRestarts() = runBlocking {
        val (alice, aliceName, suffix) = twoUsers("sch")
        val bob = c.authRepository.currentUid!!
        val cid = c.chatRepository.openDirectChat(alice) // brand-new chat: no server document yet
        val sm = c.scheduledMessages.schedule(cid, "scheduled hello $suffix", System.currentTimeMillis() + 2 * 60_000)
        val text = "scheduled hello $suffix (edited)"
        assertTrue("editable while pending", c.scheduledMessages.edit(sm.id, text, System.currentTimeMillis() + 3 * 60_000))

        // Due now: WorkManager's job races three direct attempts.
        assertTrue(c.scheduledMessages.sendNow(sm.id))
        (1..3).map { async(Dispatchers.IO) { c.scheduledMessages.deliver(sm.id) } }.awaitAll()
        await("scheduled message sent", 90_000) { c.db.scheduledMessageDao().get(sm.id)?.state == ScheduledState.SENT }

        suspend fun serverCopies(): Int = c.firestore.collection("conversations").document(cid).collection("messages")
            .whereEqualTo("text", text).get(Source.SERVER).await().size()
        assertEquals("exactly one message on the server", 1, serverCopies())

        // A crash right after the server accepted it leaves the row "sending": the next run must not send again.
        c.db.openHelper.writableDatabase.execSQL("UPDATE scheduled_messages SET state = 'SENDING', updatedAt = 0 WHERE id = ?", arrayOf<Any?>(sm.id))
        c.scheduledMessages.deliver(sm.id)
        assertEquals(ScheduledState.SENT, c.db.scheduledMessageDao().get(sm.id)?.state)
        assertEquals(ChatRepository.ScheduledResult.ALREADY_SENT, c.chatRepository.sendScheduledText(sm.id, cid, text, bob).result)
        assertEquals("still exactly one message on the server", 1, serverCopies())
        assertEquals(MessageStatus.SENT, c.db.messageDao().get(sm.id)?.status)
        switchDevice()

        // The recipient gets it once.
        c.authRepository.signInWithPassword(aliceName, pw)
        awaitSignedIn()
        await("scheduled message delivered") { c.db.messageDao().get(sm.id) != null }
        assertEquals(1, c.db.messageDao().observeRecent(cid, 0L, 50).first().count { it.content == text })
        c.authRepository.signOut()
    }

    @Test
    fun scheduledMessageToAContactBlockedMeanwhileFailsWithAReason() = runBlocking {
        val (alice, _, _) = twoUsers("schb")
        val bob = c.authRepository.currentUid!!
        val cid = c.chatRepository.openDirectChat(alice)
        val sm = c.scheduledMessages.schedule(cid, "will not be sent", System.currentTimeMillis() + 2 * 60_000)
        c.userRepository.block(alice)
        await("blocked") { alice in c.db.contactDao().blockedIds(bob) }
        assertTrue(c.scheduledMessages.sendNow(sm.id))
        c.scheduledMessages.deliver(sm.id)
        await("marked failed") { c.db.scheduledMessageDao().get(sm.id)?.state == ScheduledState.FAILED }
        assertEquals("blocked_contact", c.db.scheduledMessageDao().get(sm.id)?.lastError)
        assertTrue("failed messages can be deleted", c.scheduledMessages.cancel(sm.id))
        assertNull(c.db.scheduledMessageDao().get(sm.id))
        c.authRepository.signOut()
    }

    @Test
    fun foldersMediaBrowserDiagnosticsAndTranslationFlow() = runBlocking {
        val (alice, _, _) = twoUsers("ph1")
        val cid = c.chatRepository.openDirectChat(alice)
        val sent = c.chatRepository.sendText(cid, "The guide is at https://example.org/guide, see you tomorrow", null, emptyList())
        await("message sent") { c.db.messageDao().get(sent.messageId)?.status == MessageStatus.SENT }

        // On-device language identification: English into English needs no translation; Arabic into
        // English needs a language pack, which is only downloaded after the user agrees.
        val same = runCatching { c.translations.translate(sent, "en", allowDownload = false) }.exceptionOrNull() as? TranslationException
        assertEquals(TranslationException.Reason.SAME_LANGUAGE, same?.reason)
        val arabic = sent.copy(messageId = "local-ar", content = "مرحبا، كيف حالك اليوم؟ أراك غدا في الاجتماع")
        val needs = runCatching { c.translations.translate(arabic, "en", allowDownload = false) }.exceptionOrNull() as? TranslationException
        assertTrue("Arabic needs a language pack first (got ${needs?.reason})",
            needs?.reason == TranslationException.Reason.NEEDS_DOWNLOAD && "ar" in needs.languages)

        // Folders: a chip filters the chat list.
        val folder = c.folders.create("Work", setOf(cid))
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Alice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Work").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Alice").fetchSemanticsNodes().isNotEmpty() }
        c.folders.update(folder.folderId, "Work", emptySet())
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.folder_empty_title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_ALL").performClick()

        // Media browser: the link appears under Links.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Alice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alice").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat_menu").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_menu").performClick()
        compose.onNodeWithTag("menu_media_browser").performClick()
        compose.onNodeWithText(context.getString(R.string.media_tab_links)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("example.org").fetchSemanticsNodes().isNotEmpty() }
        Espresso.pressBack()
        Espresso.pressBack()

        // Diagnostics: the server check reaches the (emulated) backend.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home_menu").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home_menu").performClick()
        compose.onNodeWithTag("menu_settings").performClick()
        compose.onNodeWithText(context.getString(R.string.diag_title)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.diag_run_check)).performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(context.getString(R.string.diag_server_ok, 0).substringBefore("("), substring = true).fetchSemanticsNodes().isNotEmpty() }
        c.authRepository.signOut()
    }
}
