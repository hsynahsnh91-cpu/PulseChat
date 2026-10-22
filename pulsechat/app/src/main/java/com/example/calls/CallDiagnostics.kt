package com.example.calls

import android.util.Log
import com.example.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Summary of the last call, shown in Settings › Diagnostics (no audio, content, IDs of people or secrets). */
data class CallSummary(
    val endedAt: Long,
    val video: Boolean,
    val finalState: String,
    val durationSec: Int,
    val echoCanceller: String,
    val autoGain: Boolean,
    val route: String?,
    val avgRttMs: Int?,
    val maxLossPercent: Double?,
    val reconnects: Int,
    val warnings: Int,
    val duplicateAudio: Boolean,
    val echoReturnLossEnhancementDb: Double?,
    /** "on", "off" or "unavailable" (this phone's media engine has no processing hook). */
    val voiceIsolation: String = "-",
    /** Share of the call in which background noise between words was lowered. */
    val quietPercent: Int? = null,
    /** Automatic quality: number of changes and the lowest video step used. */
    val qualityChanges: Int = 0,
    val lowestVideo: String? = null,
)

/**
 * Development diagnostics for calls: lifecycle events, audio device/route changes, the audio
 * processing that was applied, track/peer-connection counts and WebRTC quality statistics.
 * Never records audio, message content, tokens or keys. Events go to Logcat only in debug builds
 * or when the user turns on "Show call details" in Settings › Diagnostics; otherwise they stay in
 * a small in-memory buffer.
 */
class CallDiagnostics(private val prefs: AppPreferences, private val debugBuild: Boolean) {
    private val events = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _last = MutableStateFlow(load())
    val lastCall: StateFlow<CallSummary?> = _last.asStateFlow()

    // Counters for the current call.
    @Volatile var reconnects = 0; private set
    @Volatile var warnings = 0; private set
    private var rttSum = 0L
    private var rttCount = 0
    private var maxLoss: Double? = null
    private var lastErle: Double? = null
    private var duplicateAudio = false
    private var config: AudioProcessingConfig? = null
    private var voiceIsolation = "-"
    private var qualityChanges = 0
    private var lowestVideo: VideoTier? = null

    val recent: List<String> get() = synchronized(events) { events.toList() }

    private val verbose: Boolean get() = debugBuild || prefs.current.callShowDetails

    fun event(message: String) {
        val line = "${synchronized(timeFormat) { timeFormat.format(Date()) }} $message"
        synchronized(events) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        if (verbose) Log.i(TAG, message)
    }

    fun warn(message: String) {
        warnings++
        event("WARNING $message")
    }

    fun beginCall(callId: String, video: Boolean, direction: String) {
        reconnects = 0; warnings = 0; rttSum = 0; rttCount = 0; maxLoss = null; lastErle = null; duplicateAudio = false; config = null
        voiceIsolation = "-"; qualityChanges = 0; lowestVideo = null
        event("call ${callId.take(14)} begin: direction=$direction video=$video")
    }

    fun onConfig(c: AudioProcessingConfig, platformAec: Boolean, platformNs: Boolean) {
        config = c
        event(
            "audio processing: mode=${c.mode} echoCanceller=${if (c.hardwareAec) "phone" else "webrtc"} " +
                "noiseSuppressor=${if (c.hardwareNs) "phone" else "webrtc"} agc=${c.autoGain} " +
                "(phone AEC available=$platformAec, phone NS available=$platformNs)"
        )
    }

    fun onReconnecting() { reconnects++; event("reconnecting (#$reconnects)") }

    /** The voice-isolation stage for this call (after the echo canceller and noise suppressor). */
    fun onVoiceIsolation(active: Boolean, available: Boolean) {
        voiceIsolation = when { !available -> "unavailable"; active -> "on"; else -> "off" }
        event("voice isolation: $voiceIsolation")
    }

    /** Automatic quality picked a new video/voice step (or its first one). */
    fun onQuality(state: QualityState, first: Boolean, video: Boolean) {
        if (!first) qualityChanges++
        if (video && (lowestVideo == null || state.video.ordinal > lowestVideo!!.ordinal)) lowestVideo = state.video
        event("quality: ${if (video) "video ${state.video.label}, " else ""}voice ${state.audio.maxKbps} kbps (${state.reason})")
    }

    fun onStats(s: CallStatsSnapshot) {
        s.rttMs?.let { rttSum += it; rttCount++ }
        s.lossPercent?.let { l -> maxLoss = maxOf(maxLoss ?: 0.0, l) }
        s.echoReturnLossEnhancementDb?.let { lastErle = it }
        if (s.duplicateAudio && !duplicateAudio) {
            duplicateAudio = true
            warn("duplicate audio streams: inbound=${s.inboundAudioStreams} outbound=${s.outboundAudioStreams}")
        }
    }

    fun statsLine(s: CallStatsSnapshot): String = buildString {
        append("stats rtt=${s.rttMs ?: "-"}ms jitter=${s.jitterMs ?: "-"}ms loss=${s.lossPercent?.let { "%.1f".format(Locale.US, it) } ?: "-"}% ")
        append("inLevel=${s.inboundAudioLevel?.let { "%.3f".format(Locale.US, it) } ?: "-"} outLevel=${s.outboundAudioLevel?.let { "%.3f".format(Locale.US, it) } ?: "-"} ")
        append("ERL=${s.echoReturnLossDb ?: "-"} ERLE=${s.echoReturnLossEnhancementDb ?: "-"} ")
        append("audioStreams in=${s.inboundAudioStreams} out=${s.outboundAudioStreams}")
    }

    fun endCall(video: Boolean, finalState: String, durationSec: Int, route: AudioRoute?, voice: VoiceGate.Stats? = null) {
        val c = config
        val summary = CallSummary(
            endedAt = System.currentTimeMillis(), video = video, finalState = finalState, durationSec = durationSec,
            echoCanceller = if (c == null) "-" else if (c.hardwareAec) "phone" else "webrtc", autoGain = c?.autoGain ?: false,
            route = route?.name, avgRttMs = if (rttCount > 0) (rttSum / rttCount).toInt() else null, maxLossPercent = maxLoss,
            reconnects = reconnects, warnings = warnings, duplicateAudio = duplicateAudio, echoReturnLossEnhancementDb = lastErle,
            voiceIsolation = voiceIsolation, quietPercent = voice?.takeIf { it.frames > 0 }?.attenuatedPercent,
            qualityChanges = qualityChanges, lowestVideo = if (video) lowestVideo?.label else null,
        )
        voice?.let { v ->
            event("voice isolation stats: frames=${v.frames} speech=${v.speechPercent}% lowered=${v.attenuatedPercent}% " +
                "floor=${"%.0f".format(Locale.US, v.noiseFloorDb)}dBFS depth=${"%.0f".format(Locale.US, v.depthDb)}dB")
        }
        event("call end: state=$finalState duration=${durationSec}s reconnects=$reconnects warnings=$warnings")
        _last.value = summary
        prefs.lastCallSummary = encode(summary)
    }

    private fun encode(s: CallSummary): String = JSONObject().apply {
        put("endedAt", s.endedAt); put("video", s.video); put("state", s.finalState); put("duration", s.durationSec)
        put("echo", s.echoCanceller); put("agc", s.autoGain); put("route", s.route ?: ""); put("rtt", s.avgRttMs ?: -1)
        put("loss", s.maxLossPercent ?: -1.0); put("reconnects", s.reconnects); put("warnings", s.warnings)
        put("duplicate", s.duplicateAudio); put("erle", s.echoReturnLossEnhancementDb ?: -999.0)
        put("isolation", s.voiceIsolation); put("quiet", s.quietPercent ?: -1); put("qualityChanges", s.qualityChanges); put("lowestVideo", s.lowestVideo ?: "")
    }.toString()

    private fun load(): CallSummary? {
        val raw = prefs.lastCallSummary ?: return null
        return runCatching {
            val o = JSONObject(raw)
            CallSummary(
                endedAt = o.getLong("endedAt"), video = o.getBoolean("video"), finalState = o.getString("state"), durationSec = o.getInt("duration"),
                echoCanceller = o.getString("echo"), autoGain = o.optBoolean("agc"), route = o.optString("route").ifBlank { null },
                avgRttMs = o.optInt("rtt", -1).takeIf { it >= 0 }, maxLossPercent = o.optDouble("loss", -1.0).takeIf { it >= 0 },
                reconnects = o.optInt("reconnects"), warnings = o.optInt("warnings"), duplicateAudio = o.optBoolean("duplicate"),
                echoReturnLossEnhancementDb = o.optDouble("erle", -999.0).takeIf { it > -999.0 },
                voiceIsolation = o.optString("isolation", "-").ifBlank { "-" }, quietPercent = o.optInt("quiet", -1).takeIf { it >= 0 },
                qualityChanges = o.optInt("qualityChanges"), lowestVideo = o.optString("lowestVideo").ifBlank { null },
            )
        }.getOrNull()
    }

    companion object {
        const val TAG = "PulseCallDiag"
        private const val MAX_EVENTS = 300
    }
}
