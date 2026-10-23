package com.example.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Music tracks and segment selection for Status updates (Feature 1).
 *
 * Uses built-in procedural royalty-free tracks (synthesized on-device to standard 16-bit PCM WAV)
 * and user-selected local audio files, never scraping external services or requiring paid APIs.
 */
object StatusMusicPolicy {
    const val DEFAULT_SEGMENT_MS = 15_000L
    const val MIN_SEGMENT_MS = 3_000L
    const val MAX_SEGMENT_MS = 30_000L
    const val BUILT_IN_SCHEME = "pulsemusic://"

    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val url: String,
        val tempoBpm: Int = 96,
        val notesHz: List<Double> = listOf(261.63, 329.63, 392.00, 523.25),
    )

    data class Selection(
        val trackId: String,
        val title: String,
        val artist: String,
        val url: String,
        val startMs: Long,
        val durationMs: Long,
        val trackDurationMs: Long = durationMs,
    ) {
        val attribution: String get() = formatAttribution(title, artist)
    }

    val BUILT_IN_TRACKS: List<Track> = listOf(
        Track(
            id = "pulse_dawn",
            title = "Morning Pulse",
            artist = "PulseChat Studio",
            durationMs = 20_000L,
            url = "${BUILT_IN_SCHEME}pulse_dawn",
            tempoBpm = 88,
            notesHz = listOf(261.63, 329.63, 392.00, 493.88, 523.25, 392.00, 329.63, 293.66),
        ),
        Track(
            id = "pulse_lofi",
            title = "Late Night Study",
            artist = "PulseChat Beats",
            durationMs = 24_000L,
            url = "${BUILT_IN_SCHEME}pulse_lofi",
            tempoBpm = 76,
            notesHz = listOf(220.00, 261.63, 329.63, 392.00, 349.23, 293.66, 261.63, 246.94),
        ),
        Track(
            id = "pulse_horizon",
            title = "Golden Horizon",
            artist = "PulseChat Acoustic",
            durationMs = 18_000L,
            url = "${BUILT_IN_SCHEME}pulse_horizon",
            tempoBpm = 102,
            notesHz = listOf(293.66, 369.99, 440.00, 587.33, 554.37, 440.00, 369.99, 329.63),
        ),
        Track(
            id = "pulse_breeze",
            title = "Mediterranean Breeze",
            artist = "PulseChat Ensemble",
            durationMs = 22_000L,
            url = "${BUILT_IN_SCHEME}pulse_breeze",
            tempoBpm = 94,
            notesHz = listOf(246.94, 293.66, 369.99, 493.88, 440.00, 369.99, 329.63, 293.66),
        ),
        Track(
            id = "pulse_synth",
            title = "Neon Skyline",
            artist = "PulseChat Synth",
            durationMs = 25_000L,
            url = "${BUILT_IN_SCHEME}pulse_synth",
            tempoBpm = 112,
            notesHz = listOf(196.00, 246.94, 293.66, 392.00, 440.00, 392.00, 293.66, 246.94),
        ),
        Track(
            id = "pulse_starlight",
            title = "Desert Starlight",
            artist = "PulseChat Strings",
            durationMs = 20_000L,
            url = "${BUILT_IN_SCHEME}pulse_starlight",
            tempoBpm = 84,
            notesHz = listOf(220.00, 277.18, 329.63, 440.00, 392.00, 329.63, 277.18, 246.94),
        ),
    )

    fun isBuiltInUrl(url: String?): Boolean = url != null && url.startsWith(BUILT_IN_SCHEME)

    fun builtInTrackById(id: String?): Track? {
        if (id.isNullOrBlank()) return null
        val clean = id.removePrefix(BUILT_IN_SCHEME)
        return BUILT_IN_TRACKS.firstOrNull { it.id == clean }
    }

    fun searchTracks(query: String, tracks: List<Track> = BUILT_IN_TRACKS): List<Track> {
        val q = query.trim()
        if (q.isEmpty()) return tracks
        return tracks.filter { TextTools.matches(q, it.title, it.artist) }
    }

    /**
     * Clamps [startMs] and [segmentDurationMs] so the chosen segment lies inside `[0, trackDurationMs]`.
     */
    fun clampSegment(
        trackDurationMs: Long,
        requestedStartMs: Long,
        requestedDurationMs: Long = DEFAULT_SEGMENT_MS,
    ): Pair<Long, Long> {
        val total = trackDurationMs.coerceAtLeast(MIN_SEGMENT_MS)
        val seg = requestedDurationMs.coerceIn(MIN_SEGMENT_MS, min(MAX_SEGMENT_MS, total))
        val maxStart = (total - seg).coerceAtLeast(0L)
        val start = requestedStartMs.coerceIn(0L, maxStart)
        return start to seg
    }

    fun selectTrack(
        track: Track,
        startMs: Long = 0L,
        segmentDurationMs: Long = DEFAULT_SEGMENT_MS,
    ): Selection {
        val (clampedStart, clampedDur) = clampSegment(track.durationMs, startMs, segmentDurationMs)
        return Selection(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            url = track.url,
            startMs = clampedStart,
            durationMs = clampedDur,
            trackDurationMs = track.durationMs,
        )
    }

    fun formatAttribution(title: String?, artist: String?): String {
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        return when {
            t.isNotEmpty() && a.isNotEmpty() -> "$t - $a"
            t.isNotEmpty() -> t
            else -> a
        }
    }

    /**
     * Synthesizes a warm multi-harmonic 16-bit mono PCM WAV byte array for a built-in [Track]
     * so playback works offline on every device and emulator without external assets or network APIs.
     */
    fun synthesizeWavBytes(track: Track, sampleRate: Int = 16_000): ByteArray {
        val durationSec = (track.durationMs / 1000.0).coerceIn(3.0, 30.0)
        val totalSamples = (sampleRate * durationSec).toInt()
        val pcm = ByteArray(totalSamples * 2)
        val beatDurationSec = 60.0 / track.tempoBpm.coerceAtLeast(60)
        val notes = track.notesHz.ifEmpty { listOf(261.63, 329.63, 392.00) }

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val beatIndex = (t / beatDurationSec).toInt()
            val posInBeat = (t % beatDurationSec) / beatDurationSec
            val freq = notes[beatIndex % notes.size]
            val fifth = freq * 1.4983

            // Smooth attack and decay envelope per beat so notes sound warm and musical
            val envelope = when {
                posInBeat < 0.08 -> posInBeat / 0.08
                posInBeat > 0.85 -> (1.0 - posInBeat) / 0.15
                else -> 1.0 - 0.25 * ((posInBeat - 0.08) / 0.77)
            }.coerceIn(0.0, 1.0)

            val wave = (
                0.60 * sin(2.0 * PI * freq * t) +
                    0.25 * sin(2.0 * PI * fifth * t) +
                    0.15 * sin(2.0 * PI * (freq * 0.5) * t)
                ) * envelope

            val sample = (wave * 12_000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pcm[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val out = ByteArrayOutputStream(44 + pcm.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(1) // NumChannels = 1 (Mono)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // ByteRate
        header.putShort(2) // BlockAlign
        header.putShort(16) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
