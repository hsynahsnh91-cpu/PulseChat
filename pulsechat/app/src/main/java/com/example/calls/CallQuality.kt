package com.example.calls

import kotlin.math.roundToInt

/** The kind of network a call runs on (read from the system, see CallManager). */
enum class LinkType { WIFI, ETHERNET, CELLULAR, OTHER, NONE }

/** The network right now: its kind, whether it is metered, and the system's link estimates (0 = unknown). */
data class CallNetwork(
    val type: LinkType,
    val metered: Boolean = type == LinkType.CELLULAR,
    val upKbps: Int = 0,
    val downKbps: Int = 0,
)

/**
 * Outgoing video steps. The camera keeps capturing 1280×720; each step only tells the encoder how
 * much to scale down, the highest frame rate and the bitrate cap, so a change never restarts the
 * camera or the call. [PAUSED] stops sending video while the voice continues.
 */
enum class VideoTier(val label: String, val scaleDown: Double, val maxFps: Int, val maxKbps: Int) {
    HD("720p", 1.0, 30, 1_700),
    SD("540p", 1.3333, 30, 900),
    LOW("360p", 2.0, 24, 450),
    MIN("180p", 4.0, 15, 150),
    PAUSED("paused", 4.0, 15, 150);

    val sending: Boolean get() = this != PAUSED
    fun lower(): VideoTier = if (this == PAUSED) PAUSED else entries[ordinal + 1]
    fun higher(): VideoTier = if (this == HD) HD else entries[ordinal - 1]
    fun isBetterThan(other: VideoTier): Boolean = ordinal < other.ordinal
}

/** Outgoing voice (Opus) bitrate caps. Opus stays clear at 12 kbps and gains little above 40. */
enum class AudioTier(val maxKbps: Int) {
    HIGH(40), NORMAL(32), LOW(20), MIN(12);

    fun lower(): AudioTier = if (this == MIN) MIN else entries[ordinal + 1]
    fun higher(): AudioTier = if (this == HIGH) HIGH else entries[ordinal - 1]
}

/** One check's inputs, from WebRTC statistics of the live call. */
data class QualitySample(
    /** WebRTC's estimate of the bandwidth available for sending (kbps). */
    val availableOutKbps: Int? = null,
    /** Share of our packets the other phone reported lost (percent). */
    val lossPercent: Double? = null,
    val rttMs: Int? = null,
)

data class QualityState(
    val video: VideoTier,
    val audio: AudioTier,
    val goodStreak: Int = 0,
    val badStreak: Int = 0,
    val severeStreak: Int = 0,
    val lastChangeAt: Long = 0L,
    /** Last time the video stepped down / anything (video or voice) stepped down. */
    val lastVideoDownAt: Long = NEVER,
    val lastDownAt: Long = NEVER,
    val reason: String = "",
) {
    companion object {
        const val NEVER = Long.MIN_VALUE / 4
    }
}

/**
 * Automatic call quality: picks the first video and voice quality from the network the phone is
 * actually on, then follows the call's real numbers every [CHECK_INTERVAL_MS]: bandwidth estimate,
 * reported packet loss and round-trip time. It steps down fast when the network struggles (voice is
 * protected first: video drops before the voice does, and video pauses rather than the call breaking
 * up) and steps up slowly, one step at a time, only after a quiet period, so quality does not
 * flicker. Nothing is shown or asked: the user just keeps talking.
 */
object CallQualityPolicy {
    const val CHECK_INTERVAL_MS = 2_000L
    /** Packet headers of a voice stream (50 packets/s). */
    const val AUDIO_OVERHEAD_KBPS = 16
    const val SEVERE_BANDWIDTH_KBPS = 120
    const val BAD_LOSS_PERCENT = 7.0
    const val VERY_BAD_LOSS_PERCENT = 15.0
    const val SEVERE_LOSS_PERCENT = 20.0
    const val GOOD_LOSS_PERCENT = 2.0
    const val BAD_RTT_MS = 700
    const val GOOD_RTT_MS = 350
    /** Checks in a row before stepping down (bad) / pausing video (severe) / stepping up (good). */
    const val BAD_CHECKS = 2
    const val SEVERE_CHECKS_TO_PAUSE = 3
    const val GOOD_CHECKS = 3
    const val MIN_DOWNGRADE_GAP_MS = 4_000L
    const val UPGRADE_COOLDOWN_MS = 10_000L

    /** Highest video step for a network: mobile and metered data never send 720p (data use). */
    fun ceiling(net: CallNetwork): VideoTier = when (net.type) {
        LinkType.WIFI, LinkType.ETHERNET -> if (net.metered) VideoTier.SD else VideoTier.HD
        else -> VideoTier.SD
    }

    private fun worse(a: VideoTier, b: VideoTier): VideoTier = if (a.ordinal >= b.ordinal) a else b
    private fun worse(a: AudioTier, b: AudioTier): AudioTier = if (a.ordinal >= b.ordinal) a else b

    /** The first choice, from the network itself (no call statistics exist yet). */
    fun initial(net: CallNetwork, now: Long): QualityState {
        val video = when {
            net.type == LinkType.NONE -> VideoTier.LOW
            net.upKbps in 1 until 600 -> VideoTier.MIN
            net.upKbps in 600 until 2_000 -> VideoTier.LOW
            // Start one step below the ceiling; the first good checks raise it within seconds.
            else -> VideoTier.SD
        }
        val audio = when {
            net.upKbps in 1 until 150 -> AudioTier.LOW
            (net.type == LinkType.WIFI || net.type == LinkType.ETHERNET) && !net.metered -> AudioTier.HIGH
            else -> AudioTier.NORMAL
        }
        return QualityState(worse(video, ceiling(net)), audio, lastChangeAt = now, reason = "network ${net.type.name.lowercase()}")
    }

    /** Voice quality the numbers call for. */
    fun audioFor(sample: QualitySample, net: CallNetwork): AudioTier {
        val bw = sample.availableOutKbps
        val loss = sample.lossPercent ?: 0.0
        return when {
            (bw != null && bw < 60) || loss >= SEVERE_LOSS_PERCENT -> AudioTier.MIN
            (bw != null && bw < 150) || loss >= 10.0 -> AudioTier.LOW
            (net.type == LinkType.WIFI || net.type == LinkType.ETHERNET) && !net.metered &&
                loss < GOOD_LOSS_PERCENT && (bw == null || bw >= 500) -> AudioTier.HIGH
            else -> AudioTier.NORMAL
        }
    }

    /** Highest video step whose bitrate (plus the voice) fits in [kbps] with some headroom. */
    fun videoFitting(kbps: Int, audio: AudioTier): VideoTier {
        val room = kbps * 0.85 - audio.maxKbps - AUDIO_OVERHEAD_KBPS
        return VideoTier.entries.firstOrNull { it.sending && it.maxKbps <= room } ?: VideoTier.MIN
    }

    fun next(state: QualityState, sample: QualitySample, net: CallNetwork, now: Long, video: Boolean): QualityState {
        val bw = sample.availableOutKbps
        val loss = sample.lossPercent ?: 0.0
        val rtt = sample.rttMs ?: 0
        val needed = (if (video && state.video.sending) state.video.maxKbps else 0) + state.audio.maxKbps + AUDIO_OVERHEAD_KBPS
        val severe = (bw != null && bw < SEVERE_BANDWIDTH_KBPS) || loss >= SEVERE_LOSS_PERCENT
        val bad = severe || loss >= BAD_LOSS_PERCENT || rtt >= BAD_RTT_MS || (bw != null && bw < needed * 0.85)
        val good = !bad && loss < GOOD_LOSS_PERCENT && rtt < GOOD_RTT_MS
        var s = state.copy(
            goodStreak = if (good) state.goodStreak + 1 else 0,
            badStreak = if (bad) state.badStreak + 1 else 0,
            severeStreak = if (severe) state.severeStreak + 1 else 0,
        )
        val why = when {
            bw != null && bw < needed * 0.85 -> "bandwidth $bw kbps"
            loss >= BAD_LOSS_PERCENT -> "loss ${loss.roundToInt()}%"
            rtt >= BAD_RTT_MS -> "delay $rtt ms"
            else -> "network recovered"
        }

        // ---- voice: protected first; it steps down at once when the numbers call for it, up slowly.
        val wantedAudio = audioFor(sample, net)
        val audio = when {
            wantedAudio.ordinal > s.audio.ordinal -> wantedAudio
            wantedAudio.ordinal < s.audio.ordinal && s.goodStreak >= GOOD_CHECKS && now - s.lastDownAt >= UPGRADE_COOLDOWN_MS -> s.audio.higher()
            else -> s.audio
        }

        // ---- video
        var videoTier = s.video
        val ceiling = ceiling(net)
        if (videoTier.isBetterThan(ceiling)) videoTier = ceiling // e.g. moved from Wi-Fi to mobile data
        if (video) {
            val mayDowngrade = now - s.lastVideoDownAt >= MIN_DOWNGRADE_GAP_MS
            val mayUpgrade = s.goodStreak >= GOOD_CHECKS && now - s.lastDownAt >= UPGRADE_COOLDOWN_MS
            fun downTo(): VideoTier {
                var target = videoTier.lower()
                if (loss >= VERY_BAD_LOSS_PERCENT) target = target.lower()
                if (bw != null) target = worse(target, videoFitting(bw, audio))
                // Video pauses only after several severe checks in a row; until then it goes to the lowest step.
                return if (target == VideoTier.PAUSED && s.severeStreak < SEVERE_CHECKS_TO_PAUSE) VideoTier.MIN else target
            }
            when {
                s.severeStreak >= SEVERE_CHECKS_TO_PAUSE && mayDowngrade && videoTier.sending -> videoTier = VideoTier.PAUSED
                (s.badStreak >= BAD_CHECKS || severe) && mayDowngrade && videoTier.isBetterThan(VideoTier.MIN) -> videoTier = downTo()
                mayUpgrade && ceiling.isBetterThan(videoTier) -> {
                    val up = videoTier.higher()
                    val fits = bw == null || bw >= (up.maxKbps + audio.maxKbps + AUDIO_OVERHEAD_KBPS) * 1.2
                    val resumeOk = videoTier != VideoTier.PAUSED || ((bw == null || bw >= 250) && loss < 5.0)
                    if (fits && resumeOk) videoTier = up
                }
            }
        }

        val videoChanged = videoTier != state.video
        val audioChanged = audio != state.audio
        if (!videoChanged && !audioChanged) return s
        val videoDown = videoTier.ordinal > state.video.ordinal
        val down = videoDown || audio.ordinal > state.audio.ordinal
        s = s.copy(
            video = videoTier,
            audio = audio,
            lastChangeAt = now,
            lastVideoDownAt = if (videoDown) now else s.lastVideoDownAt,
            lastDownAt = if (down) now else s.lastDownAt,
            goodStreak = 0,
            reason = if (down) why else "network recovered",
        )
        return s
    }
}

/** The other side's video counts as paused after this many checks without a new decoded frame. */
object RemoteVideoWatch {
    const val STALL_CHECKS = 2

    fun stalledChecks(previousFrames: Long?, frames: Long?, stalled: Int): Int = when {
        frames == null || previousFrames == null -> 0
        frames > previousFrames -> 0
        else -> stalled + 1
    }

    fun paused(stalled: Int): Boolean = stalled >= STALL_CHECKS
}
