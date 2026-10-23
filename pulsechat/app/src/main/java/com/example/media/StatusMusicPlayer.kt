package com.example.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.domain.StatusMusicPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plays status background music segments in both the Status Editor (preview) and the Status Viewer.
 *
 * - Built-in royalty-free tracks ([StatusMusicPolicy.BUILT_IN_TRACKS]) are synthesized deterministically
 *   to 16-bit PCM WAV files in `cacheDir/status_music/` so playback works offline on every device
 *   without external paid APIs or scraping.
 * - User-selected local/remote audio files play via [MediaPlayer] from their URI/URL.
 * - Playback seeks to `startMs` and loops or stops cleanly after `durationMs`, and degrades
 *   gracefully (setting [unavailable] = true without crashing) if an audio source cannot be played.
 */
class StatusMusicPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var prepareJob: Job? = null
    private var currentKey: String? = null
    private var segmentStartMs: Int = 0
    private var segmentDurationMs: Int = StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS.toInt()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    private val loopRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            runCatching {
                if (mp.isPlaying) {
                    mp.seekTo(segmentStartMs)
                    handler.postDelayed(this, segmentDurationMs.toLong().coerceAtLeast(1_000L))
                }
            }
        }
    }

    fun playSelection(selection: StatusMusicPolicy.Selection) {
        play(
            trackId = selection.trackId,
            audioUrlOrUri = selection.localUri,
            startMs = selection.startMs,
            durationMs = selection.durationMs,
        )
    }

    fun play(
        trackId: String?,
        audioUrlOrUri: String?,
        startMs: Long = 0L,
        durationMs: Long = StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS,
    ) {
        val key = "${trackId.orEmpty()}|${audioUrlOrUri.orEmpty()}|$startMs|$durationMs"
        if (key == currentKey && mediaPlayer?.isPlaying == true) return
        stop()
        currentKey = key
        _unavailable.value = false

        val builtIn = StatusMusicPolicy.findTrack(trackId)
        val clampedStart = if (builtIn != null) {
            StatusMusicPolicy.clampStartMs(startMs, builtIn.durationMs, durationMs)
        } else {
            startMs.coerceAtLeast(0L)
        }
        val clampedDuration = durationMs.coerceIn(
            StatusMusicPolicy.MIN_SEGMENT_DURATION_MS,
            StatusMusicPolicy.MAX_SEGMENT_DURATION_MS,
        )

        prepareJob = scope.launch {
            val sourceFileOrUri: Any? = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        builtIn != null -> ensureSynthesizedTrackFile(builtIn)
                        !audioUrlOrUri.isNullOrBlank() -> {
                            val f = File(audioUrlOrUri)
                            if (f.exists()) f else Uri.parse(audioUrlOrUri)
                        }
                        else -> null
                    }
                }.getOrNull()
            }
            if (sourceFileOrUri == null) {
                _unavailable.value = true
                _isPlaying.value = false
                return@launch
            }
            startMediaPlayer(sourceFileOrUri, clampedStart.toInt(), clampedDuration.toInt())
        }
    }

    private fun ensureSynthesizedTrackFile(track: StatusMusicPolicy.Track): File {
        val dir = File(appContext.cacheDir, "status_music").apply { mkdirs() }
        val file = File(dir, "${track.id}_v1.wav")
        if (!file.exists() || file.length() < 1024L) {
            val wavBytes = StatusMusicPolicy.synthesizeTrackWav(track, durationMs = track.durationMs)
            file.writeBytes(wavBytes)
        }
        return file
    }

    private fun startMediaPlayer(source: Any, startMs: Int, durationMs: Int) {
        runCatching {
            segmentStartMs = startMs.coerceAtLeast(0)
            segmentDurationMs = durationMs.coerceAtLeast(3_000)
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            when (source) {
                is File -> mp.setDataSource(source.absolutePath)
                is Uri -> mp.setDataSource(appContext, source)
                else -> throw IllegalArgumentException("Unsupported audio source")
            }
            mp.setOnPreparedListener { player ->
                runCatching {
                    val safeStart = segmentStartMs.coerceIn(0, (player.duration - 500).coerceAtLeast(0))
                    segmentStartMs = safeStart
                    player.seekTo(safeStart)
                    player.start()
                    _isPlaying.value = true
                    handler.removeCallbacks(loopRunnable)
                    handler.postDelayed(loopRunnable, segmentDurationMs.toLong())
                }.onFailure {
                    _unavailable.value = true
                    _isPlaying.value = false
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                _unavailable.value = true
                _isPlaying.value = false
                true
            }
            mp.setOnCompletionListener { player ->
                runCatching {
                    player.seekTo(segmentStartMs)
                    player.start()
                }
            }
            mp.prepareAsync()
        }.onFailure {
            _unavailable.value = true
            _isPlaying.value = false
        }
    }

    fun pause() {
        handler.removeCallbacks(loopRunnable)
        runCatching {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        }
        _isPlaying.value = false
    }

    fun resume() {
        val mp = mediaPlayer ?: return
        runCatching {
            if (!mp.isPlaying) {
                mp.start()
                _isPlaying.value = true
                handler.removeCallbacks(loopRunnable)
                handler.postDelayed(loopRunnable, segmentDurationMs.toLong())
            }
        }
    }

    fun stop() {
        prepareJob?.cancel()
        prepareJob = null
        handler.removeCallbacks(loopRunnable)
        currentKey = null
        _isPlaying.value = false
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }
}
