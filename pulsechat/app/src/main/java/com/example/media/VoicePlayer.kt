package com.example.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** App-wide voice message player: one message at a time, seekable, 1x/1.5x/2x, honours audio focus. */
class VoicePlayer(context: Context) {
    data class State(
        val messageId: String? = null,
        val playing: Boolean = false,
        val preparing: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val speed: Float = 1f,
        val error: Boolean = false,
    )

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    fun toggle(messageId: String, source: String, knownDurationMs: Long) {
        val s = _state.value
        if (s.messageId == messageId && player != null) {
            if (s.playing) pause() else resume()
            return
        }
        release()
        _state.value = State(messageId = messageId, preparing = true, durationMs = knownDurationMs, speed = s.speed)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(source)
            mp.setOnPreparedListener {
                _state.value = _state.value.copy(preparing = false, durationMs = it.duration.toLong().takeIf { d -> d > 0 } ?: knownDurationMs)
                resume()
            }
            mp.setOnCompletionListener { stopTicker(); abandonFocus(); _state.value = _state.value.copy(playing = false, positionMs = 0L); runCatching { it.seekTo(0) } }
            mp.setOnErrorListener { _, _, _ -> release(); _state.value = State(messageId = messageId, error = true); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            release()
            _state.value = State(messageId = messageId, error = true)
        }
    }

    private fun resume() {
        val mp = player ?: return
        if (!requestFocus()) return
        try {
            applySpeed(mp, _state.value.speed)
            mp.start()
        } catch (e: Exception) {
            return
        }
        _state.value = _state.value.copy(playing = true)
        startTicker()
    }

    fun pause() {
        val mp = player ?: return
        runCatching { if (mp.isPlaying) mp.pause() }
        stopTicker()
        abandonFocus()
        _state.value = _state.value.copy(playing = false, positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(_state.value.positionMs))
    }

    fun seekTo(messageId: String, fraction: Float) {
        val s = _state.value
        if (s.messageId != messageId) return
        val mp = player ?: return
        val target = (s.durationMs * fraction.coerceIn(0f, 1f)).toLong()
        runCatching { mp.seekTo(target.toInt()) }
        _state.value = s.copy(positionMs = target)
    }

    fun cycleSpeed() {
        val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        _state.value = _state.value.copy(speed = next)
        val mp = player ?: return
        // Changing PlaybackParams on a paused player starts playback on some devices: apply on resume.
        if (_state.value.playing) applySpeed(mp, next)
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    fun stop() = release()

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                _state.value = _state.value.copy(positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L))
                delay(150)
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { am.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
    }

    private fun release() {
        stopTicker()
        abandonFocus()
        player?.let { runCatching { it.reset(); it.release() } }
        player = null
        _state.value = _state.value.copy(playing = false, preparing = false)
    }
}
