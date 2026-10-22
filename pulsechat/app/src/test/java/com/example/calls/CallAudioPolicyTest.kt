package com.example.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutePolicyTest {
    private val phone = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)

    @Test fun voiceCallsUseTheEarpieceVideoCallsTheSpeaker() {
        assertEquals(AudioRoute.EARPIECE, AudioRoutePolicy.defaultRoute(phone, video = false))
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.defaultRoute(phone, video = true))
    }

    @Test fun headsetsWinAndTheSpeakerIsNeverForcedOverThem() {
        assertEquals(AudioRoute.WIRED_HEADSET, AudioRoutePolicy.defaultRoute(phone + AudioRoute.WIRED_HEADSET, video = true))
        assertEquals(AudioRoute.BLUETOOTH, AudioRoutePolicy.defaultRoute(phone + AudioRoute.WIRED_HEADSET + AudioRoute.BLUETOOTH, video = true))
    }

    @Test fun tabletsWithoutEarpieceUseTheSpeaker() {
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.defaultRoute(setOf(AudioRoute.SPEAKER), video = false))
    }

    @Test fun connectingAHeadsetTakesOverDisconnectingFallsBack() {
        val withBt = phone + AudioRoute.BLUETOOTH
        assertEquals(AudioRoute.BLUETOOTH, AudioRoutePolicy.afterDeviceChange(AudioRoute.SPEAKER, AudioRoute.SPEAKER, phone, withBt, video = true))
        // Bluetooth removed while in use: back to the default for a video call.
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.afterDeviceChange(AudioRoute.BLUETOOTH, null, withBt, phone, video = true))
        assertEquals(AudioRoute.EARPIECE, AudioRoutePolicy.afterDeviceChange(AudioRoute.WIRED_HEADSET, null, phone + AudioRoute.WIRED_HEADSET, phone, video = false))
    }

    @Test fun unrelatedDeviceChangesKeepTheUsersChoice() {
        val withWired = phone + AudioRoute.WIRED_HEADSET
        // User picked the speaker while the headset is plugged in; a Bluetooth device leaving changes nothing.
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.afterDeviceChange(AudioRoute.SPEAKER, AudioRoute.SPEAKER, withWired + AudioRoute.BLUETOOTH, withWired, video = false))
    }

    @Test fun speakerButtonTogglesToTheBestPrivateRoute() {
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.speakerToggleTarget(AudioRoute.EARPIECE, phone))
        assertEquals(AudioRoute.EARPIECE, AudioRoutePolicy.speakerToggleTarget(AudioRoute.SPEAKER, phone))
        assertEquals(AudioRoute.BLUETOOTH, AudioRoutePolicy.speakerToggleTarget(AudioRoute.SPEAKER, phone + AudioRoute.BLUETOOTH))
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.speakerToggleTarget(AudioRoute.SPEAKER, setOf(AudioRoute.SPEAKER)))
    }
}

class AudioProcessingPolicyTest {
    @Test fun automaticUsesThePhonesEchoCancellerWhenAvailable() {
        val c = AudioProcessingPolicy.resolve(EchoCancellerMode.AUTO, platformAecAvailable = true, platformNsAvailable = true, autoGain = false)
        assertTrue(c.hardwareAec)
        assertFalse(c.softwareAec)
        assertTrue(c.hardwareNs)
        assertFalse(c.autoGain)
    }

    @Test fun exactlyOneEchoCancellerIsAlwaysActive() {
        for (mode in EchoCancellerMode.entries) for (aec in listOf(true, false)) for (ns in listOf(true, false)) {
            val c = AudioProcessingPolicy.resolve(mode, aec, ns, autoGain = false)
            assertTrue("one echo canceller for $mode/$aec", c.hardwareAec xor c.softwareAec)
            assertTrue("one noise suppressor for $mode/$ns", c.hardwareNs xor c.softwareNs)
        }
    }

    @Test fun appModeAndMissingPlatformAecUseWebRtc() {
        assertFalse(AudioProcessingPolicy.resolve(EchoCancellerMode.APP, platformAecAvailable = true, platformNsAvailable = false, autoGain = false).hardwareAec)
        assertFalse(AudioProcessingPolicy.resolve(EchoCancellerMode.AUTO, platformAecAvailable = false, platformNsAvailable = false, autoGain = false).hardwareAec)
    }

    @Test fun gainControlOnlyWhenTheUserEnablesIt() {
        assertTrue(AudioProcessingPolicy.resolve(EchoCancellerMode.AUTO, true, true, autoGain = true).autoGain)
        assertFalse(AudioProcessingPolicy.resolve(EchoCancellerMode.AUTO, true, true, autoGain = false).autoGain)
    }
}

class CallStatsParserTest {
    private fun e(type: String, vararg members: Pair<String, Any?>) = StatEntry(type, mapOf(*members))

    @Test fun parsesNetworkAudioAndEchoNumbers() {
        val s = CallStatsParser.parse(
            listOf(
                e("inbound-rtp", "kind" to "audio", "packetsReceived" to 990L, "packetsLost" to 10, "jitter" to 0.012, "audioLevel" to 0.2,
                    "concealedSamples" to java.math.BigInteger.valueOf(480), "totalSamplesReceived" to java.math.BigInteger.valueOf(48_000)),
                e("inbound-rtp", "kind" to "video", "packetsReceived" to 5000L),
                e("outbound-rtp", "kind" to "audio", "packetsSent" to 1000L),
                e("media-source", "kind" to "audio", "audioLevel" to 0.05, "echoReturnLoss" to 12.5, "echoReturnLossEnhancement" to 21.0),
                e("candidate-pair", "state" to "succeeded", "nominated" to true, "currentRoundTripTime" to 0.085),
            ),
            now = 1L,
        )
        assertEquals(85, s.rttMs)
        assertEquals(12, s.jitterMs)
        assertEquals(1.0, s.lossPercent!!, 0.001)
        assertEquals(1.0, s.concealedPercent!!, 0.001)
        assertEquals(21.0, s.echoReturnLossEnhancementDb!!, 0.0)
        assertEquals(1, s.inboundAudioStreams)
        assertEquals(1, s.outboundAudioStreams)
        assertFalse(s.duplicateAudio)
    }

    @Test fun parsesTheNumbersAutomaticQualityUses() {
        val s = CallStatsParser.parse(
            listOf(
                e("candidate-pair", "state" to "succeeded", "nominated" to true, "currentRoundTripTime" to 0.12, "availableOutgoingBitrate" to 845_000.0),
                e("remote-inbound-rtp", "kind" to "audio", "fractionLost" to 0.01, "roundTripTime" to 0.11),
                e("remote-inbound-rtp", "kind" to "video", "fractionLost" to 0.06),
                e("inbound-rtp", "kind" to "video", "framesDecoded" to 1_234L),
                e("outbound-rtp", "kind" to "video", "frameWidth" to 640L, "frameHeight" to 360L, "framesPerSecond" to 24.0, "qualityLimitationReason" to "bandwidth"),
            ),
            now = 1L,
        )
        assertEquals(845, s.availableOutKbps)
        assertEquals(6.0, s.sendLossPercent!!, 0.0001)
        assertEquals(1_234L, s.inboundVideoFramesDecoded)
        assertEquals(640, s.sentVideoWidth)
        assertEquals(360, s.sentVideoHeight)
        assertEquals("bandwidth", s.videoLimitation)
        assertEquals(120, s.rttMs)
    }

    @Test fun twoIncomingAudioStreamsAreFlaggedAsDuplicateAudio() {
        val s = CallStatsParser.parse(listOf(e("inbound-rtp", "kind" to "audio"), e("inbound-rtp", "mediaType" to "audio")), now = 1L)
        assertEquals(2, s.inboundAudioStreams)
        assertTrue(s.duplicateAudio)
    }

    @Test fun missingStatsGiveNulls() {
        val s = CallStatsParser.parse(emptyList(), now = 1L)
        assertNull(s.rttMs)
        assertNull(s.lossPercent)
        assertEquals(0, s.inboundAudioStreams)
    }
}
