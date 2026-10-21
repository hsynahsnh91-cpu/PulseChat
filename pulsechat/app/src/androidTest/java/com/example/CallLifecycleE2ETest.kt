package com.example

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.RemoteCallState
import com.example.domain.model.CallState
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Call engine lifecycle with real WebRTC on the emulator and real signalling through the Firestore
 * emulator (the callee never answers, so no audio flows - audio quality needs two physical phones,
 * see docs/CALL_AUDIO.md). Verifies the invariants behind the echo/feedback fix:
 * - a double tap on "call" builds exactly one call (one peer connection, one microphone track);
 * - hanging up releases every peer connection and microphone track and cancels the call on the server;
 * - five back-to-back calls never accumulate peer connections, tracks or duplicate-pipeline warnings.
 */
@RunWith(AndroidJUnit4::class)
class CallLifecycleE2ETest : EmulatorE2ETest() {

    @Test
    fun doubleStartAndRepeatedCallsNeverStackMediaPipelines() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        startFresh()
        val pw = "Passw0rd1"
        val suffix = uniqueSuffix()
        c.authRepository.register("Bob", "call_b$suffix", pw, pw)
        val bob = awaitSignedIn()
        switchDevice()
        c.authRepository.register("Alice", "call_a$suffix", pw, pw)
        awaitSignedIn()
        val calls = c.callManager

        // Double tap: two concurrent starts, one call.
        val started = (1..2).map { async(Dispatchers.Default) { calls.startCall(bob, video = false) } }.awaitAll()
        assertEquals("exactly one start succeeds: $started", 1, started.count { it })
        await("ringing") { calls.ui.value.state == CallState.RINGING }
        assertEquals(com.example.calls.CallManager.MediaCounts(1, 1), calls.mediaCounts())
        val firstCall = calls.ui.value.callId!!
        calls.hangUp()
        await("call cleared") { calls.ui.value.state == CallState.IDLE }
        assertEquals(com.example.calls.CallManager.MediaCounts(0, 0), calls.mediaCounts())
        assertEquals(RemoteCallState.CANCELLED, c.firestore.collection("calls").document(firstCall).get(Source.SERVER).await().getString("state"))

        // Back-to-back calls do not accumulate anything.
        repeat(5) { round ->
            assertTrue("start #$round", calls.startCall(bob, video = false))
            await("ringing #$round") { calls.ui.value.state == CallState.RINGING }
            assertEquals("one pipeline in call #$round", com.example.calls.CallManager.MediaCounts(1, 1), calls.mediaCounts())
            calls.hangUp()
            await("cleared #$round") { calls.ui.value.state == CallState.IDLE }
            assertEquals("released after call #$round", com.example.calls.CallManager.MediaCounts(0, 0), calls.mediaCounts())
        }
        val duplicateWarnings = c.callDiagnostics.recent.filter {
            "more than one" in it || "started twice" in it || "duplicate audio" in it || "leftover media" in it
        }
        assertTrue("no duplicate-pipeline warnings: $duplicateWarnings", duplicateWarnings.isEmpty())
        assertTrue("the duplicate start was recorded", c.callDiagnostics.recent.any { "duplicate call start ignored" in it || "another call is active" in it })
        c.authRepository.signOut()
    }
}
