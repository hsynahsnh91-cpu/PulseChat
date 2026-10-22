package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.ChatNotificationSettings
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.repository.LiveLocationRepository
import com.example.domain.EditHistory
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.location.LiveLocationService
import com.example.notifications.NotificationHelper
import com.google.firebase.firestore.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-chat notifications, chat lock, edit history and live location end to end, against the
 * Firebase emulators running this repo's rules.
 */
@RunWith(AndroidJUnit4::class)
class Phase2FeaturesE2ETest : EmulatorE2ETest() {
    private val pw = "Passw0rd1"
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val hour = 3_600_000L

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private data class Setup(val alice: String, val cid: String, val suffix: String, val aliceName: String, val bobName: String)

    /** Alice and Bob; Bob is signed in with a direct chat to Alice that already exists on the server. */
    private suspend fun bobWithChat(prefix: String): Setup {
        startFresh()
        val suffix = uniqueSuffix()
        val aliceName = "${prefix}_a$suffix"
        val bobName = "${prefix}_b$suffix"
        c.authRepository.register("Alice", aliceName, pw, pw)
        val alice = awaitSignedIn()
        switchDevice()
        c.authRepository.register("Bob", bobName, pw, pw)
        awaitSignedIn()
        val cid = c.chatRepository.openDirectChat(alice)
        val hello = c.chatRepository.sendText(cid, "hello $suffix", null, emptyList())
        await("first message on the server", 60_000) { c.db.messageDao().get(hello.messageId)?.status in setOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ) }
        return Setup(alice, cid, suffix, aliceName, bobName)
    }

    /** Texts of the lines inside a message notification (MessagingStyle). */
    private fun lines(n: Notification): List<String> =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages?.map { it.text.toString() } ?: emptyList()

    private fun activeNotification(tag: String): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.tag == tag && it.id == NotificationHelper.MESSAGE_NOTIFICATION_ID }?.notification

    private fun incoming(cid: String, sender: String, text: String) = Message(
        messageId = "n_${System.nanoTime()}", conversationId = cid, senderId = sender, type = MessageType.TEXT, content = text,
        status = MessageStatus.DELIVERED, createdAt = System.currentTimeMillis(),
    )

    @Test
    fun perChatNotificationSettingsAffectOnlyThatChatAndAreKept() = runBlocking {
        val (alice, cid, _, _, bobName) = bobWithChat("ntf")
        val bob = c.authRepository.currentUid!!
        c.notifications.cancelAllMessages()
        // Two more chats only on this phone, to see that settings of one never touch the others.
        val other = Conversation(conversationId = "local_other", participantIds = "$bob,carol", peerId = "carol")
        val locked = Conversation(conversationId = "local_locked", participantIds = "$bob,dave", peerId = "dave")
        val dm = c.db.conversationDao().get(cid)!!

        // Muted for an hour: nothing is posted for this chat; the other chat still notifies.
        c.chatSettings.setMutedUntil(cid, System.currentTimeMillis() + hour)
        c.notifications.showMessages(dm, listOf(incoming(cid, alice, "muted text")), bob)
        c.notifications.showMessages(other, listOf(incoming(other.conversationId, "carol", "visible text")), bob)
        delay(500)
        assertNull("a muted chat posts nothing", activeNotification(cid))
        val shown = activeNotification(other.conversationId)
        assertNotNull("other chats are not muted", shown)
        assertEquals("visible text", shown!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())

        // Hidden preview for one chat: who wrote, but not what.
        c.chatSettings.setPreview(other.conversationId, ChatNotificationSettings.PREVIEW_HIDE)
        c.notifications.showMessages(other, listOf(incoming(other.conversationId, "carol", "private text")), bob)
        delay(500)
        val hidden = activeNotification(other.conversationId)!!
        assertEquals(context.getString(R.string.notification_new_message), hidden.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertFalse(lines(hidden).contains("private text"))

        // A locked chat: no name, no text, no reply action.
        c.chatSettings.lock(locked.conversationId)
        c.notifications.showMessages(locked, listOf(incoming(locked.conversationId, "dave", "locked text")), bob)
        delay(500)
        val lockedShown = activeNotification(locked.conversationId)!!
        assertEquals(context.getString(R.string.app_name), lockedShown.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertFalse(lines(lockedShown).contains("locked text"))
        assertTrue("no reply from a locked chat's notification", lockedShown.actions.isNullOrEmpty())

        // Unmuted: the chat notifies again.
        c.chatSettings.setMutedUntil(cid, 0L)
        c.notifications.showMessages(dm, listOf(incoming(cid, alice, "back again")), bob)
        delay(500)
        assertNotNull(activeNotification(cid))
        c.notifications.cancelAllMessages()

        // Kept: the mute is stored for this account and restored on a fresh install of the same account.
        c.chatSettings.setMutedUntil(cid, ChatNotificationSettings.MUTED_FOREVER)
        assertEquals(ChatNotificationSettings.MUTED_FOREVER, c.db.chatSettingsDao().notificationSettings(bob, cid)?.mutedUntil)
        switchDevice()
        c.authRepository.signInWithPassword(bobName, pw)
        awaitSignedIn()
        await("mute restored after reinstall") { c.chatSettings.notificationSettings(cid).mutedUntil == ChatNotificationSettings.MUTED_FOREVER }
        switchDevice()
    }

    @Test
    fun editHistoryReachesTheChatAndIsDeletedWithTheMessage() = runBlocking {
        val (_, cid, suffix, aliceName, bobName) = bobWithChat("edh")
        val m = c.chatRepository.sendText(cid, "Helo $suffix", null, emptyList())
        await("sent") { c.db.messageDao().get(m.messageId)?.status != MessageStatus.SENDING && c.db.messageDao().get(m.messageId)?.status != MessageStatus.PENDING }
        c.chatRepository.editMessage(m.messageId, "Hello $suffix")
        c.chatRepository.editMessage(m.messageId, "Hello there $suffix")
        val ref = c.firestore.collection("conversations").document(cid).collection("messages").document(m.messageId)
        await("both edits on the server") {
            val doc = ref.get(Source.SERVER).await()
            doc.getString("text") == "Hello there $suffix" && (doc.get("edits") as? List<*>)?.size == 2
        }
        switchDevice()

        // Alice sees the current text and both earlier versions, in order, with their times.
        c.authRepository.signInWithPassword(aliceName, pw)
        awaitSignedIn()
        await("edited message synced to Alice", 60_000) {
            val synced = c.db.messageDao().get(m.messageId)
            synced?.content == "Hello there $suffix" && EditHistory.parse(synced.editHistory).size == 2
        }
        val history = EditHistory.parse(c.db.messageDao().get(m.messageId)!!.editHistory)
        assertEquals(listOf("Helo $suffix", "Hello $suffix"), history.map { it.text })
        assertTrue(history.all { it.at > 0 })
        switchDevice()

        // Bob deletes it for everyone: the history goes with it.
        c.authRepository.signInWithPassword(bobName, pw)
        awaitSignedIn()
        await("message back on Bob's phone", 60_000) { c.db.messageDao().get(m.messageId) != null }
        c.chatRepository.deleteForEveryone(listOf(m.messageId))
        await("history removed on the server") {
            val doc = ref.get(Source.SERVER).await()
            doc.getBoolean("deletedForEveryone") == true && !doc.contains("edits")
        }
        assertEquals("", c.db.messageDao().get(m.messageId)!!.editHistory)
        switchDevice()
    }

    /** Mock location for the providers the service may use (fused on Android 12+, otherwise GPS/network). */
    private class MockLocations(private val lm: LocationManager) {
        private val providers = mutableListOf<String>()

        @Suppress("DEPRECATION")
        fun install() {
            for (p in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                runCatching {
                    lm.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                    lm.setTestProviderEnabled(p, true)
                    providers += p
                }.onFailure { Log.w("Phase2E2E", "mock provider $p: ${it.message}") }
            }
        }

        fun set(lat: Double, lng: Double) {
            providers.forEach { p ->
                runCatching {
                    lm.setTestProviderLocation(p, Location(p).apply {
                        latitude = lat; longitude = lng; accuracy = 5f
                        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    })
                }
            }
        }

        fun remove() { providers.forEach { runCatching { lm.removeTestProvider(it) } } }
    }

    @Test
    fun liveLocationSendsRealPointsAndStopsOnRequestAndAtTheEnd() = runBlocking {
        val (_, cid, _, aliceName, _) = bobWithChat("liv")
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        shell("appops set ${context.packageName} android:mock_location allow")
        val lm = context.getSystemService(LocationManager::class.java)
        val mock = MockLocations(lm)
        mock.install()
        try {
            // Start (the explicit choice in the share sheet ends in exactly these two calls).
            var lat = 52.0116
            val session = c.liveLocation.start(cid, 15 * 60_000L, lat, 4.3571, 8f, System.currentTimeMillis())
            LiveLocationService.start(context)
            await("service running") { LiveLocationService.running }
            val ref = c.firestore.collection("conversations").document(cid).collection("live").document(session.liveId)
            await("session on the server") { ref.get(Source.SERVER).await().exists() }

            // The phone moves: new points reach the server (not just the first one).
            await("moving points published", 90_000) {
                lat += 0.0006 // about 65 m
                mock.set(lat, 4.3571)
                delay(2_000)
                (ref.get(Source.SERVER).await().getDouble("lat") ?: 0.0) > 52.0125
            }

            // Stop now: updates end and nothing is collected afterwards.
            c.liveLocation.stop(LiveLocationRepository.StopReason.USER)
            await("ended on the server") { ref.get(Source.SERVER).await().get("endedAt") != null }
            await("service stopped") { !LiveLocationService.running }
            val frozen = ref.get(Source.SERVER).await().getDouble("lat")
            repeat(6) { mock.set(53.0, 5.0); delay(2_000) }
            assertEquals("no point after stopping", frozen, ref.get(Source.SERVER).await().getDouble("lat"))

            // A session ends by itself when its time is over.
            val short = c.liveLocation.start(cid, 20_000L, 52.02, 4.36, 8f, System.currentTimeMillis())
            LiveLocationService.start(context)
            val shortRef = c.firestore.collection("conversations").document(cid).collection("live").document(short.liveId)
            await("ends at the chosen time", 90_000) {
                mock.set(52.02, 4.36)
                delay(1_000)
                shortRef.get(Source.SERVER).await().get("endedAt") != null
            }
            await("service stopped at the end") { !LiveLocationService.running }
            assertNull(c.liveLocation.active.value)

            // Alice sees the last point and that it ended.
            switchDevice()
            c.authRepository.signInWithPassword(aliceName, pw)
            awaitSignedIn()
            val view = withTimeout(60_000) { c.liveLocation.observe(cid, session.liveId).first { it != null && !it.fromCache } }!!
            assertTrue(view.endedAt > 0)
            assertTrue(view.lat > 52.0125)
            switchDevice()
        } finally {
            mock.remove()
            c.liveLocation.stop(LiveLocationRepository.StopReason.USER)
        }
    }

    @Test
    fun lockedChatsStayOutOfListsSearchAndStarredUntilUnlocked() = runBlocking {
        val (_, cid, suffix) = bobWithChat("lck")
        val secret = c.chatRepository.sendText(cid, "secret plan $suffix", null, emptyList())
        c.chatRepository.setStarred(listOf(secret.messageId), true)
        assertEquals(1, c.chatRepository.searchAll("secret plan $suffix", null, false).size)

        c.chatSettings.lock(cid)
        c.chatSettings.closeLockedChats()
        assertTrue(c.chatRepository.searchAll("secret plan $suffix", null, false).isEmpty())
        assertTrue(c.db.messageDao().observeStarred(c.authRepository.currentUid!!).first().none { it.message.messageId == secret.messageId })

        // The chat list shows only the "Locked chats" entry; opening it needs the screen-lock check.
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("locked_chats_entry").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("secret plan $suffix", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("locked_chats_entry").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat_lock_gate").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("secret plan $suffix", substring = true).fetchSemanticsNodes().isEmpty())

        // After a successful check (simulated here: the emulator has no screen lock) the list opens.
        c.chatSettings.markLockedChatsOpen()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("locked_chats_list").fetchSemanticsNodes().isNotEmpty() }
        // Going to the background closes locked chats again.
        c.chatSettings.onAppBackground()
        c.chatSettings.onAppForeground(returningFromOwnScreen = false)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat_lock_gate").fetchSemanticsNodes().isNotEmpty() }

        c.chatSettings.unlock(cid)
        assertEquals(1, c.chatRepository.searchAll("secret plan $suffix", null, false).size)
        switchDevice()
    }
}
