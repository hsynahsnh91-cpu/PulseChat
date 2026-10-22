package com.example.calls

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Speech-aware downward expander for the outgoing call signal (PulseChat's "voice isolation" stage).
 *
 * It runs inside WebRTC's audio processing, after the echo canceller and the noise suppressor, on
 * 10 ms frames of FloatS16 samples (the range of 16-bit audio, as WebRTC keeps them internally).
 * Pure Kotlin, no allocation per frame, so it is unit-tested on the JVM.
 *
 * - **Noise isolation.** It measures the background noise that is left after noise suppression
 *   (minimum of the frame levels over the last [FLOOR_WINDOW_FRAMES], which follows a room getting
 *   louder or quieter) and, between words, lowers the signal by a depth that grows with that noise:
 *   about [MIN_DEPTH_DB] in a quiet room (nothing to remove, nothing to hear) up to [MAX_DEPTH_DB]
 *   in a noisy one. It attenuates; it never mutes, so the line never sounds dead.
 * - **Never cuts speech.** Speech is detected per 2.5 ms block (level clearly above the noise floor,
 *   with hysteresis). One frame (10 ms) of lookahead opens the gate *before* the first syllable is
 *   played out, a [HOLD_MS] hold keeps word endings and short pauses, and every gain change is a
 *   smooth ramp (fast attack, slow release), so there are no clicks and no "pumping".
 *
 * The output is the input delayed by one frame (10 ms), multiplied by a gain between the depth and 1.
 * The gain never exceeds 1: nothing is ever amplified.
 */
class VoiceGate {
    /** Numbers for diagnostics (no audio content). */
    data class Stats(
        val frames: Long,
        val speechFrames: Long,
        val attenuatedFrames: Long,
        val averageAttenuationDb: Double,
        val noiseFloorDb: Double,
        val depthDb: Double,
    ) {
        val speechPercent: Int get() = if (frames > 0) (speechFrames * 100 / frames).toInt() else 0
        val attenuatedPercent: Int get() = if (frames > 0) (attenuatedFrames * 100 / frames).toInt() else 0
    }

    private var frameLength = 0
    private var current = FloatArray(MAX_FRAME)
    private var delayed = FloatArray(MAX_FRAME)
    private var hasDelayed = false
    private var gain = 1f
    private var attackCoef = 0f
    private var releaseCoef = 0f
    private var highPassCoef = 0f
    private var hpLastIn = 0f
    private var hpLastOut = 0f

    private val levels = FloatArray(FLOOR_WINDOW_FRAMES)
    private var levelCount = 0
    private var levelIndex = 0
    private var floorDb = INITIAL_FLOOR_DB
    private var speaking = false
    private var holdLeftMs = 0
    private var previousTarget = 1f

    private var frames = 0L
    private var speechFrames = 0L
    private var attenuatedFrames = 0L
    private var attenuationSumDb = 0.0

    /** Frame size in samples for [sampleRate] (10 ms). */
    fun frameSizeFor(sampleRate: Int): Int = (sampleRate / 100).coerceIn(1, MAX_FRAME)

    /** Starts over for a new call or a new sample rate (the first frame afterwards is silence). */
    fun reset(sampleRate: Int) {
        frameLength = frameSizeFor(sampleRate)
        val rate = frameLength * 100f
        attackCoef = (1.0 - exp(-1.0 / (ATTACK_MS / 1000.0 * rate))).toFloat()
        releaseCoef = (1.0 - exp(-1.0 / (RELEASE_MS / 1000.0 * rate))).toFloat()
        highPassCoef = exp(-2.0 * Math.PI * DETECTOR_HIGH_PASS_HZ / rate).toFloat()
        hasDelayed = false
        gain = 1f
        hpLastIn = 0f
        hpLastOut = 0f
        levelCount = 0
        levelIndex = 0
        floorDb = INITIAL_FLOOR_DB
        speaking = false
        holdLeftMs = 0
        previousTarget = 1f
        frames = 0L
        speechFrames = 0L
        attenuatedFrames = 0L
        attenuationSumDb = 0.0
    }

    fun stats(): Stats = Stats(
        frames = frames,
        speechFrames = speechFrames,
        attenuatedFrames = attenuatedFrames,
        averageAttenuationDb = if (frames > 0) attenuationSumDb / frames else 0.0,
        noiseFloorDb = floorDb.toDouble(),
        depthDb = depthFor(floorDb).toDouble(),
    )

    /**
     * Processes one 10 ms frame in place: [samples] holds [n] input samples and receives the output
     * (the previous frame, gain-shaped). A frame of a different size restarts the gate.
     */
    fun process(samples: FloatArray, n: Int) {
        if (n <= 0 || n > MAX_FRAME || n > samples.size) return
        if (n != frameLength) reset(n * 100)
        System.arraycopy(samples, 0, current, 0, n)

        // ---- detector on the incoming frame (it decides the gain of the frame played out before it)
        var frameEnergy = 0.0
        var peakBlockEnergy = 0.0
        val blockLength = max(1, n / BLOCKS_PER_FRAME)
        var blockEnergy = 0.0
        var inBlock = 0
        var lastIn = hpLastIn
        var lastOut = hpLastOut
        for (i in 0 until n) {
            val x = current[i]
            // First-order high-pass for the detector only: low rumble does not hide or fake speech.
            val e = highPassCoef * (lastOut + x - lastIn)
            lastIn = x
            lastOut = e
            val p = e.toDouble() * e
            frameEnergy += p
            blockEnergy += p
            if (++inBlock == blockLength) {
                peakBlockEnergy = max(peakBlockEnergy, blockEnergy / blockLength)
                blockEnergy = 0.0
                inBlock = 0
            }
        }
        if (inBlock > 0) peakBlockEnergy = max(peakBlockEnergy, blockEnergy / inBlock)
        hpLastIn = lastIn
        hpLastOut = lastOut
        val frameDb = toDb(frameEnergy / n)
        val peakDb = toDb(peakBlockEnergy)

        updateFloor(frameDb)

        val openAt = max(floorDb + OPEN_ABOVE_FLOOR_DB, MIN_SPEECH_DB)
        val stayAt = max(floorDb + STAY_ABOVE_FLOOR_DB, MIN_SPEECH_DB - 6f)
        val speech = peakDb > openAt || (speaking && frameDb > stayAt)
        if (speech) {
            speaking = true
            holdLeftMs = HOLD_MS
        } else if (holdLeftMs > 0) {
            holdLeftMs -= FRAME_MS
        } else {
            speaking = false
        }
        val open = speech || holdLeftMs > 0
        val depthDb = depthFor(floorDb)
        val closed = 10f.pow(-depthDb / 20f)
        val target = if (open) 1f else closed

        // ---- output: the delayed frame, opened early if speech starts in the incoming one
        if (!hasDelayed) {
            java.util.Arrays.fill(samples, 0, n, 0f)
        } else {
            val outTarget = max(previousTarget, target)
            var minGain = 1f
            var g = gain
            for (i in 0 until n) {
                g += (outTarget - g) * (if (outTarget > g) attackCoef else releaseCoef)
                if (g > 1f) g = 1f
                samples[i] = delayed[i] * g
                if (g < minGain) minGain = g
            }
            gain = g
            frames++
            if (open) speechFrames++
            if (minGain < 0.9f) attenuatedFrames++
            attenuationSumDb += -20.0 * log10(max(g, 1e-4f).toDouble())
        }
        previousTarget = target
        val swap = delayed
        delayed = current
        current = swap
        hasDelayed = true
    }

    /** Minimum of the frame levels over the window: follows the room, ignores the words. */
    private fun updateFloor(frameDb: Float) {
        levels[levelIndex] = frameDb
        levelIndex = (levelIndex + 1) % levels.size
        if (levelCount < levels.size) levelCount++
        var windowMin = Float.MAX_VALUE
        for (i in 0 until levelCount) if (levels[i] < windowMin) windowMin = levels[i]
        val target = (windowMin + FLOOR_BIAS_DB).coerceIn(MIN_FLOOR_DB, MAX_FLOOR_DB)
        floorDb += (target - floorDb) * (if (target < floorDb) FLOOR_FALL else FLOOR_RISE)
    }

    private fun toDb(meanSquare: Double): Float =
        (10.0 * log10(meanSquare / FULL_SCALE_SQUARED + 1e-12)).toFloat()

    companion object {
        /** 10 ms at 48 kHz is 480 samples; room for 96 kHz. */
        const val MAX_FRAME = 960
        const val FRAME_MS = 10
        const val HOLD_MS = 300
        const val ATTACK_MS = 1.5
        const val RELEASE_MS = 150.0
        const val MIN_DEPTH_DB = 3f
        const val MAX_DEPTH_DB = 15f
        /** Noise floor (dBFS) at which the depth starts to grow, and where it reaches the maximum. */
        const val QUIET_FLOOR_DB = -72f
        const val NOISY_FLOOR_DB = -48f
        const val OPEN_ABOVE_FLOOR_DB = 9f
        const val STAY_ABOVE_FLOOR_DB = 5f
        /** Nothing quieter than this is treated as speech, however quiet the room. */
        const val MIN_SPEECH_DB = -60f
        const val FLOOR_WINDOW_FRAMES = 200 // 2 s
        private const val FLOOR_BIAS_DB = 1.5f
        private const val FLOOR_FALL = 0.5f
        private const val FLOOR_RISE = 0.05f
        private const val INITIAL_FLOOR_DB = -70f
        private const val MIN_FLOOR_DB = -96f
        private const val MAX_FLOOR_DB = -20f
        private const val BLOCKS_PER_FRAME = 4
        private const val DETECTOR_HIGH_PASS_HZ = 150.0
        private const val FULL_SCALE_SQUARED = 32768.0 * 32768.0

        /** Depth between words for a measured noise floor: little in a quiet room, more in a noisy one. */
        fun depthFor(floorDb: Float): Float {
            val t = ((floorDb - QUIET_FLOOR_DB) / (NOISY_FLOOR_DB - QUIET_FLOOR_DB)).coerceIn(0f, 1f)
            return MIN_DEPTH_DB + t * (MAX_DEPTH_DB - MIN_DEPTH_DB)
        }
    }
}
