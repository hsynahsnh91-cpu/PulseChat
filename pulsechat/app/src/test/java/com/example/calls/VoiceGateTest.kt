package com.example.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voice-isolation stage on synthetic signals at 48 kHz: background noise at a known level and a
 * speech-like signal (harmonics with a 4 Hz syllable rhythm). The output is the input delayed by one
 * 10 ms frame times a gain, so the gain applied to every input sample can be measured exactly.
 */
class VoiceGateTest {
    private val rate = 48_000
    private val frame = 480

    private fun level(db: Double): Double = 32768.0 * 10.0.pow(db / 20.0)

    private fun noise(seconds: Double, db: Double, seed: Long = 1): FloatArray {
        val r = Random(seed)
        val a = level(db)
        return FloatArray((seconds * rate).toInt()) { (r.nextGaussian() * a).toFloat() }
    }

    /** Voiced-speech-like: 180 Hz with harmonics, amplitude following a 4 Hz syllable rhythm. */
    private fun speech(seconds: Double, db: Double, pauseEveryMs: Int = 0, pauseMs: Int = 0): FloatArray {
        val n = (seconds * rate).toInt()
        val raw = DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            val voice = sin(2 * PI * 180 * t) + 0.6 * sin(2 * PI * 360 * t) + 0.4 * sin(2 * PI * 720 * t) + 0.25 * sin(2 * PI * 1440 * t)
            val syllables = 0.55 + 0.45 * sin(2 * PI * 4 * t)
            val paused = pauseEveryMs > 0 && ((i * 1000L / rate) % pauseEveryMs) >= pauseEveryMs - pauseMs
            if (paused) 0.0 else voice * syllables
        }
        val rms = sqrt(raw.filter { it != 0.0 }.sumOf { it * it } / raw.count { it != 0.0 }.coerceAtLeast(1))
        val scale = level(db) / rms
        return FloatArray(n) { (raw[it] * scale).toFloat() }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun mix(a: FloatArray, b: FloatArray, at: Int): FloatArray {
        val out = a.copyOf()
        for (i in b.indices) if (at + i < out.size) out[at + i] += b[i]
        return out
    }

    /** Runs the gate frame by frame; returns the output (frame j of the output is input frame j-1). */
    private fun run(input: FloatArray, gate: VoiceGate = VoiceGate().apply { reset(rate) }, size: Int = frame): FloatArray {
        val out = FloatArray(input.size)
        val buf = FloatArray(size)
        var i = 0
        while (i + size <= input.size) {
            input.copyInto(buf, 0, i, i + size)
            gate.process(buf, size)
            buf.copyInto(out, i)
            i += size
        }
        return out
    }

    /** Gain applied to input sample k (output k + frame), or null where the input is too small to tell. */
    private fun gainAt(input: FloatArray, output: FloatArray, k: Int, size: Int = frame): Double? {
        if (k + size >= output.size || abs(input[k]) < 2f) return null
        return output[k + size].toDouble() / input[k]
    }

    private fun rmsDb(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return 10 * log10(s / (to - from) / (32768.0 * 32768.0) + 1e-12)
    }

    /** Attenuation (dB) over input samples [from, to), aligned with the one-frame delay. */
    private fun attenuationDb(input: FloatArray, output: FloatArray, from: Int, to: Int): Double =
        rmsDb(input, from, to) - rmsDb(output, from + frame, to + frame)

    @Test
    fun lowersBackgroundNoiseBetweenWords() {
        val input = noise(4.0, -55.0)
        val out = run(input)
        val att = attenuationDb(input, out, 3 * rate, 4 * rate - 2 * frame)
        assertTrue("noise between words is lowered: $att dB", att >= 8.0)
        assertTrue("it attenuates, it never mutes: $att dB", att <= VoiceGate.MAX_DEPTH_DB + 1.0)
    }

    @Test
    fun aQuietRoomIsLeftAlmostUntouched() {
        val input = noise(3.0, -85.0)
        val out = run(input)
        val att = attenuationDb(input, out, 2 * rate, 3 * rate - 2 * frame)
        assertTrue("quiet room: $att dB", att <= VoiceGate.MIN_DEPTH_DB + 1.0)
    }

    @Test
    fun theStartOfSpeechIsNeverCut() {
        // Speech starts in the middle of a frame, after 2 s of background noise.
        val start = 2 * rate + frame / 4
        val input = mix(noise(4.0, -55.0), speech(1.2, -26.0), start)
        val out = run(input)
        // The first 30 ms of the word come out at full level (the gate opened before them).
        for (k in start until start + rate * 30 / 1000) {
            val g = gainAt(input, out, k) ?: continue
            assertTrue("gain at onset sample ${k - start}: $g", g >= 0.97)
        }
    }

    @Test
    fun softConsonantsAtTheStartOfAWordAreKept() {
        // An unvoiced "s"/"f" sound only 12 dB above the background, then the voiced part.
        val start = 2 * rate
        val r = Random(9)
        val hiss = FloatArray(rate * 60 / 1000)
        var prev = 0.0
        for (i in hiss.indices) { val w = r.nextGaussian(); hiss[i] = (w - prev).toFloat(); prev = w } // high-frequency noise
        val hissDb = rmsDb(hiss, 0, hiss.size)
        val scale = 10.0.pow((-43.0 - hissDb) / 20.0).toFloat()
        for (i in hiss.indices) hiss[i] *= scale
        val word = concat(hiss, speech(0.8, -26.0))
        val input = mix(noise(4.0, -55.0), word, start)
        val out = run(input)
        for (k in start until start + rate * 20 / 1000) {
            val g = gainAt(input, out, k) ?: continue
            assertTrue("gain on the soft consonant at ${(k - start) * 1000.0 / rate} ms: $g", g >= 0.9)
        }
    }

    @Test
    fun wordEndingsAndShortPausesAreKept() {
        val start = 2 * rate
        val words = speech(1.6, -26.0, pauseEveryMs = 400, pauseMs = 120)
        val input = mix(noise(5.0, -55.0), words, start)
        val out = run(input)
        // Through the words and their 120 ms pauses, and for 250 ms after the last one, nothing is lowered.
        val end = start + words.indexOfLast { it != 0f }
        for (k in start until end + rate / 4) {
            val g = gainAt(input, out, k) ?: continue
            assertTrue("gain ${(k - start) * 1000 / rate} ms after the start: $g", g >= 0.95)
        }
        // A second after the last word the background is lowered again.
        val later = attenuationDb(input, out, end + rate, end + rate + rate / 2)
        assertTrue("lowered again after the words: $later dB", later >= 6.0)
    }

    @Test
    fun gainChangesAreSmoothAndNothingIsAmplified() {
        val input = mix(noise(4.0, -50.0, seed = 3), speech(1.0, -24.0), 2 * rate)
        val out = run(input)
        var previous: Double? = null
        for (k in 0 until input.size - frame) {
            assertTrue("never louder than the input at $k", abs(out[k + frame]) <= abs(input[k]) + 1e-3f)
            val g = gainAt(input, out, k)
            if (g != null && previous != null) assertTrue("smooth at $k: $previous -> $g", abs(g - previous) <= 0.03)
            if (g != null) previous = g
        }
    }

    @Test
    fun followsARoomThatGetsLouder() {
        val input = concat(noise(3.0, -72.0, seed = 5), noise(5.0, -50.0, seed = 6))
        val out = run(input)
        // After the 2 s window the louder noise is recognised as background, not speech.
        val att = attenuationDb(input, out, 7 * rate, 8 * rate - 2 * frame)
        assertTrue("background after the room got louder: $att dB", att >= 8.0)
    }

    @Test
    fun worksAtEverySampleRateAndRestartsWhenItChanges() {
        for (r in listOf(16_000, 32_000, 48_000)) {
            val size = r / 100
            val gate = VoiceGate().apply { reset(r) }
            val buf = FloatArray(size) { (sin(it * 0.1) * 1000).toFloat() }
            repeat(50) { gate.process(buf, size) }
            assertEquals(50L - 1, gate.stats().frames) // the first frame is the lookahead
        }
        val gate = VoiceGate().apply { reset(48_000) }
        val big = FloatArray(480) { 500f }
        repeat(10) { gate.process(big, 480) }
        val small = FloatArray(160) { 500f }
        gate.process(small, 160) // a new rate: restart, first frame is silence
        assertTrue(small.all { it == 0f })
        gate.process(small.apply { fill(500f) }, 160)
        assertTrue(small.any { it != 0f })
        // Out-of-range sizes are ignored, never written.
        val tooBig = FloatArray(4000) { 1f }
        gate.process(tooBig, 4000)
        assertTrue(tooBig.all { it == 1f })
    }

    @Test
    fun depthGrowsWithTheBackgroundNoise() {
        assertEquals(VoiceGate.MIN_DEPTH_DB, VoiceGate.depthFor(-90f), 0.01f)
        assertEquals(VoiceGate.MAX_DEPTH_DB, VoiceGate.depthFor(-30f), 0.01f)
        assertTrue(VoiceGate.depthFor(-60f) > VoiceGate.depthFor(-70f))
    }

    @Test
    fun statsDescribeWhatHappened() {
        val input = mix(noise(4.0, -55.0), speech(1.0, -26.0), 2 * rate)
        val gate = VoiceGate().apply { reset(rate) }
        run(input, gate)
        val s = gate.stats()
        assertTrue(s.frames > 300)
        assertTrue("some speech: ${s.speechPercent}%", s.speechPercent in 20..60)
        assertTrue("some frames lowered: ${s.attenuatedPercent}%", s.attenuatedPercent in 20..80)
        assertTrue("floor near -55 dBFS: ${s.noiseFloorDb}", s.noiseFloorDb in -62.0..-48.0)
    }
}
