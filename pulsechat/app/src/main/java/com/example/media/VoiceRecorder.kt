package com.example.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.example.domain.MediaLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/** Records voice notes as AAC/M4A and samples the input level for a real waveform. */
class VoiceRecorder(private val context: Context) {
    data class State(val recording: Boolean = false, val elapsedMs: Long = 0L, val levels: List<Int> = emptyList())
    data class Result(val file: File, val durationMs: Long, val waveform: List<Int>)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var job: Job? = null
    private val samples = mutableListOf<Int>()

    fun start(scope: CoroutineScope): Boolean {
        if (recorder != null) return false
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val out = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            return false
        }
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
        samples.clear()
        _state.value = State(recording = true)
        job = scope.launch {
            while (isActive) {
                delay(100)
                val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                val level = (sqrt(amp / 32767.0) * 100).toInt().coerceIn(0, 100)
                samples += level
                val elapsed = System.currentTimeMillis() - startedAt
                _state.value = State(recording = true, elapsedMs = elapsed, levels = samples.takeLast(60))
                if (elapsed >= MediaLimits.MAX_VOICE_MS) break
            }
        }
        return true
    }

    /** Stops and returns the recording, or null if it was too short or failed. */
    fun stop(): Result? {
        val r = recorder ?: return null
        job?.cancel()
        val duration = System.currentTimeMillis() - startedAt
        val ok = try { r.stop(); true } catch (e: RuntimeException) { false }
        runCatching { r.release() }
        recorder = null
        _state.value = State()
        val f = file
        file = null
        if (!ok || f == null || duration < MediaLimits.MIN_VOICE_MS) { f?.delete(); return null }
        return Result(f, duration, downsample(samples, 48))
    }

    fun cancel() {
        val r = recorder ?: return
        job?.cancel()
        runCatching { r.stop() }
        runCatching { r.release() }
        recorder = null
        file?.delete()
        file = null
        _state.value = State()
    }

    companion object {
        fun downsample(values: List<Int>, bars: Int): List<Int> {
            if (values.isEmpty()) return List(bars) { 4 }
            return List(bars) { i ->
                val from = i * values.size / bars
                val to = maxOf(from + 1, (i + 1) * values.size / bars)
                values.subList(from.coerceAtMost(values.size - 1), to.coerceAtMost(values.size)).maxOrNull() ?: 0
            }.map { it.coerceIn(4, 100) }
        }
    }
}
