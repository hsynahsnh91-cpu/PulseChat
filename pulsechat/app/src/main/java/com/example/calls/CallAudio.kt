package com.example.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioRouteState(val current: AudioRoute? = null, val available: Set<AudioRoute> = emptySet())

/**
 * The phone's audio session for one call: communication mode, audio focus and routing
 * (earpiece, speaker, wired/USB headset, Bluetooth). It only routes audio: it never changes
 * volumes or gains. [start] remembers the phone's previous audio state and [stop] restores it.
 * Starting twice is ignored, so a call can never register a second session or device callback.
 */
class CallAudio(context: Context, private val log: (String) -> Unit) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var tone: ToneGenerator? = null
    private var active = false
    private var video = false
    private var userChoice: AudioRoute? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var savedMicMute = false
    private var legacyScoStarted = false
    private var deviceCallbackRegistered = false

    private val _routes = MutableStateFlow(AudioRouteState())
    val routes: StateFlow<AudioRouteState> = _routes.asStateFlow()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        log("audio focus changed: ${focusName(change)}")
        // Another app (e.g. a phone call) may have changed the mode/route while it held focus.
        if (change == AudioManager.AUDIOFOCUS_GAIN) main.post { reapply() }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { main.post { onDevicesChanged() } }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { main.post { onDevicesChanged() } }
    }

    /**
     * Voice-call mode, focus and the initial route. Must run before WebRTC opens the microphone
     * and speaker streams, so they open on the phone's voice-communication path (that path is
     * what feeds the phone's echo canceller with the speaker signal).
     */
    @Synchronized
    fun start(video: Boolean) {
        if (active) { log("audio session already active: start ignored"); return }
        active = true
        this.video = video
        userChoice = null
        savedMode = am.mode
        savedMicMute = am.isMicrophoneMute
        @Suppress("DEPRECATION")
        savedSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (am.isMicrophoneMute) am.isMicrophoneMute = false
        requestFocus()
        val available = detect()
        apply(AudioRoutePolicy.defaultRoute(available, video), available)
        runCatching { am.registerAudioDeviceCallback(deviceCallback, main); deviceCallbackRegistered = true }
        log("audio session started: mode=IN_COMMUNICATION video=$video routes=$available")
    }

    /** User picked a route (speaker button or route menu). */
    @Synchronized
    fun select(route: AudioRoute) {
        if (!active) return
        val available = detect()
        if (route !in available) { log("route $route not available"); return }
        userChoice = route
        apply(route, available)
    }

    fun toggleSpeaker() {
        val s = _routes.value
        select(AudioRoutePolicy.speakerToggleTarget(s.current, s.available))
    }

    @Synchronized
    private fun onDevicesChanged() {
        if (!active) return
        val before = _routes.value.available
        val after = detect()
        if (before == after) return
        val next = AudioRoutePolicy.afterDeviceChange(_routes.value.current, userChoice, before, after, video)
        if (userChoice != null && userChoice !in after) userChoice = null
        log("audio devices changed: $before -> $after, route -> $next")
        apply(next, after)
    }

    @Synchronized
    private fun reapply() {
        if (!active) return
        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
        val available = detect()
        val route = _routes.value.current?.takeIf { it in available } ?: AudioRoutePolicy.defaultRoute(available, video)
        apply(route, available)
    }

    private fun routeOf(type: Int): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRoute.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.BLUETOOTH
        else -> when {
            Build.VERSION.SDK_INT >= 26 && type == AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED_HEADSET
            Build.VERSION.SDK_INT >= 28 && type == AudioDeviceInfo.TYPE_HEARING_AID -> AudioRoute.BLUETOOTH
            Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRoute.BLUETOOTH
            else -> null
        }
    }

    private fun devices(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices
        else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    private fun detect(): Set<AudioRoute> {
        val found = runCatching { devices().mapNotNull { routeOf(it.type) }.toMutableSet() }.getOrDefault(mutableSetOf())
        found += AudioRoute.SPEAKER
        return found
    }

    private fun apply(route: AudioRoute, available: Set<AudioRoute>) {
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 31) applyModern(route) else { applyLegacy(route); true }
        }.getOrElse { e -> log("route $route failed: ${e.javaClass.simpleName}"); false }
        _routes.value = AudioRouteState(current = route, available = available)
        log("audio route -> $route (${if (ok) "applied" else "not applied"})")
    }

    private fun applyModern(route: AudioRoute): Boolean {
        val device = am.availableCommunicationDevices.firstOrNull { routeOf(it.type) == route }
        if (device == null) {
            am.clearCommunicationDevice()
            return false
        }
        return am.setCommunicationDevice(device)
    }

    @Suppress("DEPRECATION")
    private fun applyLegacy(route: AudioRoute) {
        if (route != AudioRoute.BLUETOOTH && legacyScoStarted) {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
            legacyScoStarted = false
        }
        when (route) {
            AudioRoute.SPEAKER -> am.isSpeakerphoneOn = true
            AudioRoute.EARPIECE, AudioRoute.WIRED_HEADSET -> am.isSpeakerphoneOn = false // wired headsets take over automatically
            AudioRoute.BLUETOOTH -> {
                am.isSpeakerphoneOn = false
                if (!legacyScoStarted) { am.startBluetoothSco(); legacyScoStarted = true }
                am.isBluetoothScoOn = true
            }
        }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusRequest = req
            log("audio focus request: ${am.requestAudioFocus(req)}")
        } else {
            @Suppress("DEPRECATION")
            log("audio focus request: ${am.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)}")
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { am.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
        focusRequest = null
    }

    fun startRingback() {
        if (tone != null) return
        tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    fun stopRingback() {
        tone?.let { runCatching { it.stopTone(); it.release() } }
        tone = null
    }

    /** Releases routing and focus and restores the phone's previous audio state. Safe to call twice. */
    @Synchronized
    fun stop() {
        stopRingback()
        if (!active) return
        active = false
        if (deviceCallbackRegistered) { runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }; deviceCallbackRegistered = false }
        if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
        else {
            @Suppress("DEPRECATION")
            runCatching {
                if (legacyScoStarted) { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
                am.isSpeakerphoneOn = savedSpeaker
            }
        }
        legacyScoStarted = false
        abandonFocus()
        // A stale IN_COMMUNICATION (e.g. left by a crashed session) is not worth restoring.
        am.mode = if (savedMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else savedMode
        if (am.isMicrophoneMute != savedMicMute) am.isMicrophoneMute = savedMicMute
        userChoice = null
        _routes.value = AudioRouteState()
        log("audio session stopped: mode, route and focus restored")
    }

    private fun focusName(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "gain"
        AudioManager.AUDIOFOCUS_LOSS -> "loss"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss_transient"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "loss_transient_can_duck"
        else -> change.toString()
    }
}
