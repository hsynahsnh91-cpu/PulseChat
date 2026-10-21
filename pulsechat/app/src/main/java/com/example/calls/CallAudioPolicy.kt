package com.example.calls

/** Where call audio is played and captured. */
enum class AudioRoute { EARPIECE, SPEAKER, WIRED_HEADSET, BLUETOOTH }

/** Pure routing rules for calls (unit-tested; no Android types). */
object AudioRoutePolicy {
    /** Route when a call starts: a headset wins, then the speaker for video calls, else the earpiece. */
    fun defaultRoute(available: Set<AudioRoute>, video: Boolean): AudioRoute = when {
        AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
        AudioRoute.WIRED_HEADSET in available -> AudioRoute.WIRED_HEADSET
        video || AudioRoute.EARPIECE !in available -> AudioRoute.SPEAKER
        else -> AudioRoute.EARPIECE
    }

    /**
     * Route after the connected devices changed: a newly connected headset takes over (like a
     * phone call does); if the device in use disappeared, fall back to the default; otherwise the
     * user's choice (or the current route) stays. Never forces the speaker on while a headset is in use.
     */
    fun afterDeviceChange(current: AudioRoute?, userChoice: AudioRoute?, before: Set<AudioRoute>, after: Set<AudioRoute>, video: Boolean): AudioRoute {
        val added = after - before
        if (AudioRoute.BLUETOOTH in added) return AudioRoute.BLUETOOTH
        if (AudioRoute.WIRED_HEADSET in added) return AudioRoute.WIRED_HEADSET
        val wanted = userChoice ?: current
        if (wanted != null && wanted in after) return wanted
        return defaultRoute(after, video)
    }

    /** The speaker button: speaker on, or back to the best private route (headset, else earpiece). */
    fun speakerToggleTarget(current: AudioRoute?, available: Set<AudioRoute>): AudioRoute {
        if (current != AudioRoute.SPEAKER) return AudioRoute.SPEAKER
        return when {
            AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
            AudioRoute.WIRED_HEADSET in available -> AudioRoute.WIRED_HEADSET
            AudioRoute.EARPIECE in available -> AudioRoute.EARPIECE
            else -> AudioRoute.SPEAKER
        }
    }
}

/**
 * Which echo canceller handles a call.
 * - AUTO: the phone's own (vendor) echo canceller when the phone has one, otherwise WebRTC's.
 *   This is WebRTC's recommended default: the phone's canceller is tuned to its own speaker and
 *   microphone, which matters most on speakerphone (video calls).
 * - APP: always WebRTC's software echo canceller (the phone's canceller is switched off).
 */
enum class EchoCancellerMode { AUTO, APP }

/** The audio-processing configuration actually applied to the WebRTC audio device module. */
data class AudioProcessingConfig(
    val mode: EchoCancellerMode,
    /** Phone (vendor) echo canceller attached to the microphone stream; WebRTC's own is then off. */
    val hardwareAec: Boolean,
    /** Phone noise suppressor; WebRTC's own suppressor runs only when this is off. */
    val hardwareNs: Boolean,
    /** WebRTC automatic gain control (a second gain stage on top of the phone's voice processing). */
    val autoGain: Boolean,
) {
    /** Exactly one echo canceller is always active: the phone's, or WebRTC's software one. */
    val softwareAec: Boolean get() = !hardwareAec
    val softwareNs: Boolean get() = !hardwareNs
}

object AudioProcessingPolicy {
    /**
     * Echo cancellation and noise suppression are always on - exactly one implementation of each.
     * Automatic gain control is off unless the user enables it: the phone's voice-communication
     * path already levels the microphone, and a second gain stage raises residual echo, which can
     * make an echo loop grow louder over time.
     */
    fun resolve(mode: EchoCancellerMode, platformAecAvailable: Boolean, platformNsAvailable: Boolean, autoGain: Boolean): AudioProcessingConfig =
        AudioProcessingConfig(
            mode = mode,
            hardwareAec = mode == EchoCancellerMode.AUTO && platformAecAvailable,
            hardwareNs = platformNsAvailable,
            autoGain = autoGain,
        )
}

/** One WebRTC stats entry reduced to plain Kotlin values (so parsing is unit-testable). */
data class StatEntry(val type: String, val members: Map<String, Any?>)

/** Non-sensitive call quality numbers derived from WebRTC statistics. */
data class CallStatsSnapshot(
    val at: Long,
    val rttMs: Int? = null,
    val jitterMs: Int? = null,
    val lossPercent: Double? = null,
    val inboundAudioLevel: Double? = null,
    val outboundAudioLevel: Double? = null,
    val echoReturnLossDb: Double? = null,
    val echoReturnLossEnhancementDb: Double? = null,
    val concealedPercent: Double? = null,
    val inboundAudioStreams: Int = 0,
    val outboundAudioStreams: Int = 0,
    val packetsReceived: Long = 0,
    val packetsSent: Long = 0,
) {
    /** More than one incoming or outgoing audio stream in a one-to-one call means duplicated audio. */
    val duplicateAudio: Boolean get() = inboundAudioStreams > 1 || outboundAudioStreams > 1
}

object CallStatsParser {
    private fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()
    private fun Map<String, Any?>.kind(): String? = (this["kind"] ?: this["mediaType"]) as? String

    fun parse(entries: List<StatEntry>, now: Long): CallStatsSnapshot {
        val inboundAudio = entries.filter { it.type == "inbound-rtp" && it.members.kind() == "audio" }
        val outboundAudio = entries.filter { it.type == "outbound-rtp" && it.members.kind() == "audio" }
        val source = entries.firstOrNull { it.type == "media-source" && it.members.kind() == "audio" }?.members
        val pair = entries.filter { it.type == "candidate-pair" }.let { pairs ->
            pairs.firstOrNull { it.members["nominated"] == true && it.members["state"] == "succeeded" }
                ?: pairs.firstOrNull { it.members["state"] == "succeeded" }
        }?.members
        val remoteRtt = entries.firstOrNull { it.type == "remote-inbound-rtp" && it.members.kind() == "audio" }?.members?.num("roundTripTime")

        val received = inboundAudio.sumOf { it.members.num("packetsReceived") ?: 0.0 }
        val lost = inboundAudio.sumOf { (it.members.num("packetsLost") ?: 0.0).coerceAtLeast(0.0) }
        val concealed = inboundAudio.sumOf { it.members.num("concealedSamples") ?: 0.0 }
        val samples = inboundAudio.sumOf { it.members.num("totalSamplesReceived") ?: 0.0 }
        val rttSeconds = pair?.num("currentRoundTripTime") ?: remoteRtt
        return CallStatsSnapshot(
            at = now,
            rttMs = rttSeconds?.let { (it * 1000).toInt() },
            jitterMs = inboundAudio.mapNotNull { it.members.num("jitter") }.maxOrNull()?.let { (it * 1000).toInt() },
            lossPercent = if (received + lost > 0) lost * 100.0 / (received + lost) else null,
            inboundAudioLevel = inboundAudio.mapNotNull { it.members.num("audioLevel") }.maxOrNull(),
            outboundAudioLevel = source?.num("audioLevel"),
            echoReturnLossDb = source?.num("echoReturnLoss"),
            echoReturnLossEnhancementDb = source?.num("echoReturnLossEnhancement"),
            concealedPercent = if (samples > 0) concealed * 100.0 / samples else null,
            inboundAudioStreams = inboundAudio.size,
            outboundAudioStreams = outboundAudio.size,
            packetsReceived = received.toLong(),
            packetsSent = outboundAudio.sumOf { it.members.num("packetsSent") ?: 0.0 }.toLong(),
        )
    }
}
