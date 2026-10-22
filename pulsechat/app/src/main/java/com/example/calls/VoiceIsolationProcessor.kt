package com.example.calls

import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs [VoiceGate] inside WebRTC's audio processing as its capture post-processing step: every
 * 10 ms frame of the outgoing microphone signal passes through it after the echo canceller and the
 * noise suppressor, right before the encoder. It only ever touches our own outgoing signal (the
 * other person's audio is never routed through it), and it runs after echo cancellation, so it
 * cannot disturb the echo canceller.
 *
 * Called on WebRTC's audio thread: it never throws (a Java exception there would end the process),
 * does not allocate per frame, and switches itself off for good after any error so the call simply
 * continues without it.
 */
class VoiceIsolationProcessor : ExternalAudioProcessingFactory.AudioProcessing {
    private val gate = VoiceGate()
    private val frame = FloatArray(VoiceGate.MAX_FRAME)
    @Volatile private var resetRequested = true
    @Volatile private var failed = false
    @Volatile private var lastStats: VoiceGate.Stats? = null

    /** 10 ms frames processed since the call started. */
    @Volatile var framesProcessed = 0L
        private set

    /** Sample rate WebRTC processes the microphone at (0 until the first frame). */
    @Volatile var sampleRate = 0
        private set

    val healthy: Boolean get() = !failed

    /** Fresh noise estimate and counters for a new call. */
    fun startCall() {
        resetRequested = true
        framesProcessed = 0L
        lastStats = null
    }

    fun stats(): VoiceGate.Stats? = lastStats

    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        sampleRate = sampleRateHz
        resetRequested = true
    }

    override fun reset(newRate: Int) {
        sampleRate = newRate
        resetRequested = true
    }

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer?) {
        if (failed || buffer == null) return
        try {
            // The buffer holds the frame's full-band FloatS16 samples (native byte order).
            val n = minOf(numFrames, buffer.capacity() / 4, frame.size)
            if (n <= 0) return
            if (resetRequested) {
                resetRequested = false
                gate.reset(n * 100)
            }
            buffer.order(ByteOrder.nativeOrder())
            for (i in 0 until n) frame[i] = buffer.getFloat(i * 4)
            gate.process(frame, n)
            for (i in 0 until n) buffer.putFloat(i * 4, frame[i])
            framesProcessed++
            if (framesProcessed % STATS_EVERY_FRAMES == 0L) lastStats = gate.stats()
        } catch (t: Throwable) {
            failed = true
        }
    }

    companion object {
        private const val STATS_EVERY_FRAMES = 50L
    }
}
