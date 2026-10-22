package com.example.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.AppPreferences
import com.example.domain.MediaRefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppLockManagerTest {
    private lateinit var prefs: AppPreferences
    private var callLive = false
    private lateinit var lock: AppLockManager

    @Before fun setUp() {
        prefs = AppPreferences(ApplicationProvider.getApplicationContext())
        prefs.resetAppLock()
        callLive = false
        lock = AppLockManager(prefs) { callLive }
    }

    @Test fun pinIsStoredOnlyAsASaltedHashAndVerified() = runBlocking {
        lock.enable("2468", 0L)
        val record = prefs.appLockPinRecord!!
        assertFalse("the PIN itself is never stored", record.contains("2468"))
        assertTrue(prefs.current.appLockEnabled && prefs.current.appLockHasPin)
        assertEquals(AppLockManager.PinResult.Wrong, lock.checkPin("1357"))
        assertEquals(AppLockManager.PinResult.Ok, lock.checkPin("2468"))
        lock.changePin("97531")
        assertNotEquals("a new salt and hash", record, prefs.appLockPinRecord)
        assertEquals(AppLockManager.PinResult.Wrong, lock.checkPin("2468"))
        assertEquals(AppLockManager.PinResult.Ok, lock.checkPin("97531"))
    }

    @Test fun repeatedWrongPinsForceAWaitThatSurvivesRestarts() = runBlocking {
        lock.enable("1234", 0L)
        repeat(4) { assertEquals(AppLockManager.PinResult.Wrong, lock.checkPin("0000")) }
        val wait = lock.checkPin("0000")
        assertTrue("5th wrong PIN starts a wait: $wait", wait is AppLockManager.PinResult.Wait)
        val restarted = AppLockManager(prefs)
        assertTrue("even the right PIN must wait", restarted.checkPin("1234") is AppLockManager.PinResult.Wait)
        assertTrue(restarted.waitSeconds() in 1..30)
    }

    @Test fun locksAfterTheTimeoutButNotForOwnScreensOrCalls() = runBlocking {
        lock.enable("1234", 0L)
        assertFalse("turning the lock on does not lock immediately", lock.locked.value)

        lock.onBackground(); lock.onForeground()
        assertTrue("locks when coming back (timeout: immediately)", lock.locked.value)
        lock.unlock()

        lock.onExternalActivityLaunched(); lock.onBackground(); lock.onForeground()
        assertFalse("returning from a picker/camera PulseChat opened does not lock", lock.locked.value)

        callLive = true
        lock.onBackground(); lock.onForeground()
        assertFalse("no lock during a call", lock.locked.value)
        callLive = false

        prefs.setAppLock(true, 60_000L)
        lock.onBackground(); lock.onForeground()
        assertFalse("a short absence within the timeout does not lock", lock.locked.value)

        lock.disable()
        lock.onBackground(); lock.onForeground()
        assertFalse(lock.locked.value)
        assertNull("turning the lock off forgets the PIN", prefs.appLockPinRecord)
    }

    @Test fun signingOutRemovesTheLock() = runBlocking {
        lock.enable("1234", 0L)
        prefs.clearAccountScoped()
        assertFalse(prefs.current.appLockEnabled)
        assertNull(prefs.appLockPinRecord)
    }

    @Test fun pinFormat() {
        assertTrue(AppLockManager.isValidPin("1234"))
        assertTrue(AppLockManager.isValidPin("12345678"))
        assertFalse(AppLockManager.isValidPin("123"))
        assertFalse(AppLockManager.isValidPin("123456789"))
        assertFalse(AppLockManager.isValidPin("12a4"))
    }
}

class MediaRefsTest {
    @Test fun referencesRoundTripAndRejectForeignUrls() {
        val id = MediaRefs.newMediaId()
        val ref = MediaRefs.ref(id)
        assertTrue(ref.startsWith("pulsemedia://"))
        assertTrue(MediaRefs.isPulse(ref))
        assertEquals(id, MediaRefs.idOf(ref))
        assertFalse(MediaRefs.isPulse("https://firebasestorage.googleapis.com/v0/b/x/o/y"))
        assertFalse(MediaRefs.isPulse("chat-media/c/u/m.jpg"))
        assertFalse(MediaRefs.isPulse("pulsemedia://bad id"))
        assertNull(MediaRefs.idOf(null))
    }

    @Test fun chunkingMatchesTheRules() {
        assertEquals(512 * 1024, MediaRefs.CHUNK_BYTES)
        assertEquals(32L * 1024 * 1024, MediaRefs.MAX_BYTES)
        assertEquals(0, MediaRefs.chunkCount(0))
        assertEquals(1, MediaRefs.chunkCount(1))
        assertEquals(1, MediaRefs.chunkCount(MediaRefs.CHUNK_BYTES.toLong()))
        assertEquals(2, MediaRefs.chunkCount(MediaRefs.CHUNK_BYTES + 1L))
        assertEquals(MediaRefs.MAX_CHUNKS, MediaRefs.chunkCount(MediaRefs.MAX_BYTES))
        assertTrue(MediaRefs.fits(MediaRefs.MAX_BYTES))
        assertFalse(MediaRefs.fits(MediaRefs.MAX_BYTES + 1))
    }

    @Test fun mediaIdsFromMessageIdsAreStableAndValid() {
        val key = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(MediaRefs.mediaIdFor(key), MediaRefs.mediaIdFor(key))
        assertTrue(MediaRefs.isValidId(MediaRefs.mediaIdFor(key)))
        assertTrue(MediaRefs.isValidId(MediaRefs.mediaIdFor("st_0123456789abcdef0123456789abcdef")))
        assertTrue("short keys get a random ID", MediaRefs.isValidId(MediaRefs.mediaIdFor("x")))
    }

    @Test fun scopeFieldsForStoragePathMapsStoragePathsToFirestoreScopes() {
        assertEquals(
            mapOf("scope" to "chat", "conversationId" to "dm_alice_bob"),
            MediaRefs.scopeFieldsForStoragePath("chat-media/dm_alice_bob/alice/m1.jpg"),
        )
        assertEquals(
            mapOf("scope" to "group", "conversationId" to "grp_0123456789abcdef"),
            MediaRefs.scopeFieldsForStoragePath("group-photos/grp_0123456789abcdef/avatar.jpg"),
        )
        assertEquals(
            mapOf("scope" to "status", "statusId" to "st_0123456789abcdef"),
            MediaRefs.scopeFieldsForStoragePath("status-media/alice/st_0123456789abcdef.jpg"),
        )
        assertEquals(
            mapOf("scope" to "profile"),
            MediaRefs.scopeFieldsForStoragePath("profile-photos/alice/avatar.jpg"),
        )
        assertTrue(MediaRefs.scopeFieldsForStoragePath(null).isEmpty())
        assertTrue(MediaRefs.scopeFieldsForStoragePath("unknown/path.jpg").isEmpty())
    }
}
