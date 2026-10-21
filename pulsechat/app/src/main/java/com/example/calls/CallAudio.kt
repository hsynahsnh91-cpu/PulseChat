package com.example.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build

/** Audio routing for calls: communication mode, focus, speaker/earpiece and ring-back tone. */
class CallAudio(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var tone: ToneGenerator? = null
    private var active = false

    /**
     * Puts the phone in voice-call mode before the call's microphone and speaker streams open:
     * MODE_IN_COMMUNICATION routes audio through the platform's voice-call path, which is what
     * makes the phone's echo canceller and noise suppressor work. Safe to call again.
     */
    fun start(speaker: Boolean) {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (am.isMicrophoneMute) am.isMicrophoneMute = false
        if (active) { setSpeaker(speaker); return }
        active = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        setSpeaker(speaker)
    }

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    fun startRingback() {
        if (tone != null) return
        tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    fun stopRingback() {
        tone?.let { runCatching { it.stopTone(); it.release() } }
        tone = null
    }

    fun stop() {
        stopRingback()
        if (!active) return
        active = false
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { am.abandonAudioFocusRequest(it) } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        am.mode = AudioManager.MODE_NORMAL
    }
}
