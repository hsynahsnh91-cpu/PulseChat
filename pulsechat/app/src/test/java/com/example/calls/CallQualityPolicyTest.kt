package com.example.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallQualityPolicyTest {
    private val wifi = CallNetwork(LinkType.WIFI, metered = false, upKbps = 80_000)
    private val mobile = CallNetwork(LinkType.CELLULAR, metered = true, upKbps = 20_000)
    private val slowMobile = CallNetwork(LinkType.CELLULAR, metered = true, upKbps = 400)
    private val step = CallQualityPolicy.CHECK_INTERVAL_MS

    /** Feeds the same sample [checks] times, one check interval apart. */
    private fun feed(start: QualityState, sample: QualitySample, net: CallNetwork, checks: Int, from: Long = 0L, video: Boolean = true): Pair<QualityState, Long> {
        var s = start
        var t = from
        repeat(checks) { t += step; s = CallQualityPolicy.next(s, sample, net, t, video) }
        return s to t
    }

    private val good = QualitySample(availableOutKbps = 3_000, lossPercent = 0.0, rttMs = 60)

    @Test
    fun theFirstChoiceFollowsTheActualNetwork() {
        assertEquals(VideoTier.SD, CallQualityPolicy.initial(wifi, 0).video)
        assertEquals(AudioTier.HIGH, CallQualityPolicy.initial(wifi, 0).audio)
        assertEquals(VideoTier.SD, CallQualityPolicy.initial(mobile, 0).video)
        assertEquals(AudioTier.NORMAL, CallQualityPolicy.initial(mobile, 0).audio)
        assertEquals(VideoTier.MIN, CallQualityPolicy.initial(slowMobile, 0).video)
        assertEquals(VideoTier.LOW, CallQualityPolicy.initial(CallNetwork(LinkType.CELLULAR, upKbps = 1_200), 0).video)
        assertEquals(AudioTier.LOW, CallQualityPolicy.initial(CallNetwork(LinkType.CELLULAR, upKbps = 100), 0).audio)
    }

    @Test
    fun mobileDataNeverSends720p() {
        assertEquals(VideoTier.SD, CallQualityPolicy.ceiling(mobile))
        assertEquals(VideoTier.SD, CallQualityPolicy.ceiling(CallNetwork(LinkType.WIFI, metered = true)))
        assertEquals(VideoTier.HD, CallQualityPolicy.ceiling(wifi))
        val (s, _) = feed(CallQualityPolicy.initial(mobile, 0), good, mobile, 20)
        assertEquals(VideoTier.SD, s.video)
    }

    @Test
    fun aGoodWifiCallRisesTo720pStepByStep() {
        var s = CallQualityPolicy.initial(wifi, 0)
        var t = 0L
        repeat(2) { t += step; s = CallQualityPolicy.next(s, good, wifi, t, true) }
        assertEquals("not after two good checks", VideoTier.SD, s.video)
        t += step; s = CallQualityPolicy.next(s, good, wifi, t, true)
        assertEquals("after three good checks", VideoTier.HD, s.video)
    }

    @Test
    fun lowBandwidthStepsVideoDownToWhatFitsAndKeepsTheVoice() {
        val start = CallQualityPolicy.initial(wifi, 0).copy(video = VideoTier.HD)
        val tight = QualitySample(availableOutKbps = 600, lossPercent = 1.0, rttMs = 120)
        val (s, _) = feed(start, tight, wifi, 2)
        assertEquals(VideoTier.LOW, s.video) // 450 kbps + voice fits in 600 * 0.85
        assertTrue(s.video.sending)
        assertTrue("reason names the bandwidth: ${s.reason}", s.reason.startsWith("bandwidth"))
    }

    @Test
    fun oneBadCheckIsNotEnoughButASevereOneActsAtOnce() {
        val start = CallQualityPolicy.initial(wifi, 0)
        val lossy = QualitySample(availableOutKbps = 3_000, lossPercent = 8.0, rttMs = 100)
        assertEquals(VideoTier.SD, CallQualityPolicy.next(start, lossy, wifi, step, true).video)
        val collapse = QualitySample(availableOutKbps = 90, lossPercent = 3.0, rttMs = 300)
        val after = CallQualityPolicy.next(start, collapse, wifi, step, true)
        assertEquals("severe: straight to the lowest step, still sending", VideoTier.MIN, after.video)
        assertEquals(AudioTier.LOW, after.audio)
    }

    @Test
    fun heavyLossStepsDownTwiceAndVoiceFollows() {
        val start = CallQualityPolicy.initial(wifi, 0).copy(video = VideoTier.HD)
        val heavy = QualitySample(availableOutKbps = null, lossPercent = 16.0, rttMs = 200)
        val (s, _) = feed(start, heavy, wifi, 2)
        assertEquals(VideoTier.LOW, s.video)
        assertEquals(AudioTier.LOW, s.audio)
    }

    @Test
    fun aCollapsedNetworkPausesVideoAndResumesWhenItRecovers() {
        val start = CallQualityPolicy.initial(mobile, 0)
        val collapse = QualitySample(availableOutKbps = 70, lossPercent = 25.0, rttMs = 900)
        var (s, t) = feed(start, collapse, mobile, 4)
        assertEquals(VideoTier.PAUSED, s.video)
        assertEquals(AudioTier.MIN, s.audio)
        // Recovered: not immediately (cooldown), then back step by step.
        val recovered = QualitySample(availableOutKbps = 1_500, lossPercent = 0.0, rttMs = 90)
        t += step; s = CallQualityPolicy.next(s, recovered, mobile, t, true)
        assertEquals(VideoTier.PAUSED, s.video)
        val (later, _) = feed(s, recovered, mobile, 6, t)
        assertTrue("video resumed: ${later.video}", later.video.sending)
        val (end, _) = feed(later, recovered, mobile, 30, t + 6 * step)
        assertEquals(VideoTier.SD, end.video)
    }

    @Test
    fun movingFromWifiToMobileDataDropsTo540pAtOnce() {
        val onWifi = CallQualityPolicy.initial(wifi, 0).copy(video = VideoTier.HD)
        val s = CallQualityPolicy.next(onWifi, good, mobile, step, true)
        assertEquals(VideoTier.SD, s.video)
    }

    @Test
    fun anUnstableNetworkDoesNotMakeQualityFlicker() {
        var s = CallQualityPolicy.initial(wifi, 0).copy(video = VideoTier.SD)
        var t = 0L
        var changes = 0
        val bad = QualitySample(availableOutKbps = 3_000, lossPercent = 9.0, rttMs = 100)
        repeat(60) { i ->
            t += step
            val before = s.video
            s = CallQualityPolicy.next(s, if (i % 2 == 0) good else bad, wifi, t, true)
            if (s.video != before) changes++
        }
        assertEquals("alternating good/bad checks never trigger a change", 0, changes)
    }

    @Test
    fun voiceCallsOnlyAdaptTheVoice() {
        val start = CallQualityPolicy.initial(wifi, 0)
        val (s, _) = feed(start, QualitySample(availableOutKbps = 100, lossPercent = 12.0, rttMs = 300), wifi, 3, video = false)
        assertEquals(start.video, s.video)
        assertEquals(AudioTier.LOW, s.audio)
    }

    @Test
    fun videoFittingLeavesRoomForTheVoice() {
        assertEquals(VideoTier.HD, CallQualityPolicy.videoFitting(3_000, AudioTier.HIGH))
        assertEquals(VideoTier.SD, CallQualityPolicy.videoFitting(1_200, AudioTier.NORMAL))
        assertEquals(VideoTier.MIN, CallQualityPolicy.videoFitting(150, AudioTier.LOW))
    }

    @Test
    fun theOtherSidesVideoCountsAsPausedAfterTwoChecksWithoutFrames() {
        var stalled = 0
        stalled = RemoteVideoWatch.stalledChecks(100, 160, stalled)
        assertEquals(0, stalled)
        stalled = RemoteVideoWatch.stalledChecks(160, 160, stalled)
        assertFalse(RemoteVideoWatch.paused(stalled))
        stalled = RemoteVideoWatch.stalledChecks(160, 160, stalled)
        assertTrue(RemoteVideoWatch.paused(stalled))
        stalled = RemoteVideoWatch.stalledChecks(160, 161, stalled)
        assertFalse(RemoteVideoWatch.paused(stalled))
        assertEquals(0, RemoteVideoWatch.stalledChecks(null, 10, 5))
    }
}
