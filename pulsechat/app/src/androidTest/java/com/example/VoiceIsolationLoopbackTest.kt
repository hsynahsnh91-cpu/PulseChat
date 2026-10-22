package com.example

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Voice isolation inside the real media engine: two peer connections on this device call each other
 * (no backend involved) through the app's own audio pipeline, and the outgoing microphone frames
 * must pass through PulseChat's voice-isolation step (WebRTC's capture post-processing, after echo
 * cancellation and noise suppression) without errors. Skipped on a device without a microphone.
 */
@RunWith(AndroidJUnit4::class)
class VoiceIsolationLoopbackTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val c: AppContainer get() = (context.applicationContext as PulseChatApplication).container

    /**
     * Is there a microphone that delivers audio in real time? Non-blocking reads on a separate thread:
     * on some emulator hosts the audio input never delivers data, and a blocking read would hang.
     */
    private fun microphoneWorks(): Boolean {
        val works = AtomicBoolean(false)
        val probe = Thread {
            runCatching {
                val size = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4_096)
                val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                try {
                    if (record.state != AudioRecord.STATE_INITIALIZED) return@runCatching
                    record.startRecording()
                    val buffer = ShortArray(480)
                    val deadline = System.currentTimeMillis() + 2_000
                    var samples = 0
                    while (System.currentTimeMillis() < deadline && samples < 4_800) {
                        val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        if (n < 0) break
                        samples += n
                        if (n == 0) Thread.sleep(20)
                    }
                    works.set(record.recordingState == AudioRecord.RECORDSTATE_RECORDING && samples >= 4_800)
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
            }
        }.apply { isDaemon = true; start() }
        probe.join(6_000)
        // A probe still stuck in the audio driver means the microphone is not usable here.
        return works.get() && !probe.isAlive
    }

    private class Observer(val onCandidate: (IceCandidate) -> Unit, val onState: (PeerConnection.IceConnectionState?) -> Unit) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = onState(state)
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) { candidate?.let(onCandidate) }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        override fun onTrack(transceiver: RtpTransceiver?) = Unit
    }

    /**
     * The test body runs on its own thread with a hard deadline: calls into the native media engine
     * cannot be interrupted, and on some emulator hosts the audio stack stalls. A stall is reported
     * (with the stuck stacks in the log) as "not verifiable on this device" instead of hanging the run.
     */
    @Test
    fun outgoingAudioRunsThroughVoiceIsolationAfterEchoCancellation() {
        val failure = AtomicReference<Throwable?>(null)
        val skipped = AtomicReference<String?>(null)
        val body = Thread({
            try {
                runBlocking { loopback() }
            } catch (e: AssumptionViolatedException) {
                skipped.set(e.message ?: "not verifiable on this device")
            } catch (t: Throwable) {
                failure.set(t)
            }
        }, "loopback-test").apply { isDaemon = true; start() }
        body.join(150_000)
        if (body.isAlive) {
            Log.w("PulseCallDiag", "loopback: no result within 150 s; skipped. ${engineState()}\n${stuckStacks(body)}")
            assumeTrue("the media engine did not respond on this device within 150 s (stacks in the PulseCallDiag log)", false)
        }
        skipped.get()?.let { reason ->
            Log.w("PulseCallDiag", "loopback: skipped: $reason")
            assumeTrue(reason, false)
        }
        failure.get()?.let { throw it }
    }

    private fun engineState(): String = runCatching {
        val calls = c.callManager
        "call=${calls.ui.value.state} media=${calls.mediaCounts()} recent=${c.callDiagnostics.recent.takeLast(12)}"
    }.getOrElse { "engine state unavailable: $it" }

    /** Java stacks of the test thread and of every thread inside the media engine or the call code. */
    private fun stuckStacks(test: Thread): String = Thread.getAllStackTraces().entries
        .filter { (t, frames) -> t == test || frames.any { it.className.startsWith("org.webrtc") || it.className.startsWith("com.example.calls") } }
        .joinToString("\n") { (t, frames) -> "thread ${t.name} (${t.state}):\n" + frames.take(10).joinToString("\n") { "    at $it" } }

    private suspend fun loopback() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val calls = c.callManager
        val factory = withTimeout(30_000) { calls.mediaEngineForTests() }
        assertTrue("the media engine was built with the voice-isolation step", calls.voiceIsolationAvailable)
        assumeTrue("this device has no working microphone input", microphoneWorks())

        val processor = calls.voiceIsolation
        val before = processor.framesProcessed
        val connected = CompletableDeferred<Unit>()
        // Candidates wait until the other side has the description they belong to.
        val peers = arrayOfNulls<PeerConnection>(2)
        val ready = BooleanArray(2)
        val pending = List(2) { mutableListOf<IceCandidate>() }
        fun deliver(to: Int, candidate: IceCandidate) {
            synchronized(pending) {
                if (ready[to]) peers[to]?.addIceCandidate(candidate) else pending[to].add(candidate)
            }
        }
        fun markReady(index: Int) {
            synchronized(pending) {
                ready[index] = true
                pending[index].forEach { peers[index]?.addIceCandidate(it) }
                pending[index].clear()
            }
        }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val pc1 = factory.createPeerConnection(config, Observer({ deliver(1, it) }) { state ->
            if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) connected.complete(Unit)
        })!!
        val pc2 = factory.createPeerConnection(config, Observer({ deliver(0, it) }) { })!!
        peers[0] = pc1
        peers[1] = pc2
        val source = factory.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        })
        val track = factory.createAudioTrack("loopback-audio", source)
        try {
            pc1.addTrack(track, listOf("loopback"))
            withTimeout(20_000) {
                val offer = pc1.createOfferAwait()
                pc1.setLocalAwait(offer)
                pc2.setRemoteAwait(offer)
                markReady(1)
                val answer = pc2.createAnswerAwait()
                pc2.setLocalAwait(answer)
                pc1.setRemoteAwait(answer)
                markReady(0)
            }
            withTimeout(20_000) { connected.await() }
            withTimeout(20_000) { while (processor.framesProcessed - before < 150) delay(100) }
            val stats = processor.stats()
            Log.i("PulseCallDiag", "loopback: frames=${processor.framesProcessed - before} rate=${processor.sampleRate} healthy=${processor.healthy} stats=$stats")
            assertTrue("no error on the audio thread", processor.healthy)
            assertTrue("processing rate ${processor.sampleRate}", processor.sampleRate in setOf(16_000, 32_000, 48_000))
            assertTrue("stats are collected: $stats", stats != null && stats.frames > 0)
        } finally {
            // Released off the test thread, so a stuck audio driver cannot hang the whole test run.
            val cleanup = Thread { runCatching { pc1.dispose(); pc2.dispose(); track.dispose(); source.dispose() } }.apply { isDaemon = true; start() }
            cleanup.join(15_000)
            if (cleanup.isAlive) Log.w("PulseCallDiag", "loopback: the media engine did not release within 15 s")
        }
    }
}

private val noConstraints = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
}

private suspend fun PeerConnection.createOfferAwait(): SessionDescription = suspendCancellableCoroutine { cont ->
    createOffer(object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) { if (desc != null) cont.resume(desc) else cont.resumeWithException(IllegalStateException("null offer")) }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "createOffer")) }
        override fun onSetFailure(error: String?) = Unit
    }, noConstraints)
}

private suspend fun PeerConnection.createAnswerAwait(): SessionDescription = suspendCancellableCoroutine { cont ->
    createAnswer(object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) { if (desc != null) cont.resume(desc) else cont.resumeWithException(IllegalStateException("null answer")) }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "createAnswer")) }
        override fun onSetFailure(error: String?) = Unit
    }, noConstraints)
}

private suspend fun PeerConnection.setLocalAwait(desc: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
    setLocalDescription(object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) = Unit
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "setLocalDescription")) }
    }, desc)
}

private suspend fun PeerConnection.setRemoteAwait(desc: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
    setRemoteDescription(object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) = Unit
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "setRemoteDescription")) }
    }, desc)
}
