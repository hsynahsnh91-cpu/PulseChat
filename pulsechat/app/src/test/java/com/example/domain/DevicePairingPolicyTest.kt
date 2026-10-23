package com.example.domain

import com.example.domain.DevicePairingPolicy.DeviceKind
import com.example.domain.DevicePairingPolicy.LinkedDeviceStatus
import com.example.domain.DevicePairingPolicy.PairingError
import com.example.domain.DevicePairingPolicy.PairingException
import com.example.domain.DevicePairingPolicy.PairingStatus
import com.example.domain.DevicePairingPolicy.QrPairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePairingPolicyTest {

    @Test
    fun qrPayloadRoundTripAndStrictValidation() {
        val sessionId = DevicePairingPolicy.generateRandomToken(24)
        val verifier = DevicePairingPolicy.generateRandomToken(24)
        val kp = DevicePairingPolicy.generateEphemeralP256KeyPair()
        val fp = DevicePairingPolicy.keyFingerprint(DevicePairingPolicy.encodePublicKeySpki(kp.public))

        val payload = QrPairingPayload(sessionId = sessionId, codeVerifier = verifier, keyFingerprint = fp)
        val uri = DevicePairingPolicy.encodeQrUri(payload)
        assertFalse(uri.contains("password"))
        assertFalse(uri.contains("token"))

        val parsed = DevicePairingPolicy.parseQrUri(uri).getOrThrow()
        assertEquals(payload, parsed)

        // Reject forbidden or sensitive parameters
        val withToken = "$uri&token=eyJhbGciOi"
        val err1 = DevicePairingPolicy.parseQrUri(withToken).exceptionOrNull() as? PairingException
        assertEquals(PairingError.INVALID_QR, err1?.error)

        // Reject unsupported version
        val badVer = uri.replace("v=1", "v=99")
        val err2 = DevicePairingPolicy.parseQrUri(badVer).exceptionOrNull() as? PairingException
        assertEquals(PairingError.UNSUPPORTED_VERSION, err2?.error)

        // Reject wrong scheme/host or malformed IDs
        assertTrue(DevicePairingPolicy.parseQrUri("https://evil.example/pair?v=1&s=$sessionId&k=$verifier&f=$fp").isFailure)
        assertTrue(DevicePairingPolicy.parseQrUri("pulsechat://pair?v=1&s=short&k=$verifier&f=$fp").isFailure)
        assertTrue(DevicePairingPolicy.parseQrUri("").isFailure)
    }

    @Test
    fun pairingStateTransitionsAndExpirationEnforced() {
        val now = 1_000_000L
        val exp = now + DevicePairingPolicy.PAIRING_TTL_MS

        // Valid transitions before expiry
        assertTrue(DevicePairingPolicy.canTransition(PairingStatus.PENDING, PairingStatus.APPROVED, now, exp, consumed = false))
        assertTrue(DevicePairingPolicy.canTransition(PairingStatus.PENDING, PairingStatus.REJECTED, now, exp, consumed = false))
        assertTrue(DevicePairingPolicy.canTransition(PairingStatus.PENDING, PairingStatus.CANCELLED, now, exp, consumed = false))
        assertTrue(DevicePairingPolicy.canTransition(PairingStatus.APPROVED, PairingStatus.COMPLETED, now, exp, consumed = false))
        assertTrue(DevicePairingPolicy.canTransition(PairingStatus.APPROVED, PairingStatus.REVOKED, now, exp, consumed = false))
        assertTrue(DevicePairingPolicy.canTransition(PairingStatus.APPROVED, PairingStatus.CANCELLED, now, exp, consumed = false))

        // Expired sessions cannot be approved or completed
        assertFalse(DevicePairingPolicy.canTransition(PairingStatus.PENDING, PairingStatus.APPROVED, exp + 1, exp, consumed = false))
        assertFalse(DevicePairingPolicy.canTransition(PairingStatus.APPROVED, PairingStatus.COMPLETED, exp, exp, consumed = false))

        // Consumed or terminal sessions cannot transition again (one-time consumption)
        assertFalse(DevicePairingPolicy.canTransition(PairingStatus.APPROVED, PairingStatus.COMPLETED, now, exp, consumed = true))
        assertFalse(DevicePairingPolicy.canTransition(PairingStatus.COMPLETED, PairingStatus.APPROVED, now, exp, consumed = false))
        assertFalse(DevicePairingPolicy.canTransition(PairingStatus.REJECTED, PairingStatus.APPROVED, now, exp, consumed = false))
        assertFalse(DevicePairingPolicy.canTransition(PairingStatus.CANCELLED, PairingStatus.APPROVED, now, exp, consumed = false))
        assertFalse(DevicePairingPolicy.canTransition(PairingStatus.EXPIRED, PairingStatus.APPROVED, now, exp, consumed = false))
        assertFalse(DevicePairingPolicy.canTransition(PairingStatus.REVOKED, PairingStatus.COMPLETED, now, exp, consumed = false))
    }

    @Test
    fun ecdsaChallengeAndEcdhEnvelopeRoundTripAndTamperRejection() {
        val deviceKp = DevicePairingPolicy.generateEphemeralP256KeyPair()
        val requesterEphKp = DevicePairingPolicy.generateEphemeralP256KeyPair()
        val approverEphKp = DevicePairingPolicy.generateEphemeralP256KeyPair()

        val sessionId = DevicePairingPolicy.generateRandomToken(24)
        val secret = DevicePairingPolicy.generateRandomToken(32)
        val secretHash = DevicePairingPolicy.sha256Hex(secret)
        val verifier = DevicePairingPolicy.generateRandomToken(24)
        val codeChallenge = DevicePairingPolicy.sha256Hex(verifier)
        val deviceId = "dev_new_123"
        val ownerUid = "user_alice_99"
        val ts = 1_700_000_000_000L

        val pubSpki = DevicePairingPolicy.encodePublicKeySpki(deviceKp.public)
        val sig = DevicePairingPolicy.signExchangeChallenge(deviceKp.private, sessionId, secretHash, deviceId, ts)
        assertTrue(DevicePairingPolicy.verifyExchangeChallenge(pubSpki, sessionId, secretHash, deviceId, ts, sig))

        // Tampered sessionId, secretHash, deviceId, or wrong key must fail verification
        assertFalse(DevicePairingPolicy.verifyExchangeChallenge(pubSpki, sessionId, secretHash, "other_device", ts, sig))
        val wrongKp = DevicePairingPolicy.generateEphemeralP256KeyPair()
        assertFalse(
            DevicePairingPolicy.verifyExchangeChallenge(
                DevicePairingPolicy.encodePublicKeySpki(wrongKp.public), sessionId, secretHash, deviceId, ts, sig,
            )
        )

        // ECDH + HKDF-SHA256 + AES-256-GCM one-time envelope
        val requesterEphPub = DevicePairingPolicy.encodePublicKeySpki(requesterEphKp.public)
        val payloadJson = """{"type":"customToken","token":"mock_custom_token_xyz"}"""
        val sealed = DevicePairingPolicy.sealAuthEnvelope(
            approverEphKeyPair = approverEphKp,
            requesterEphPubSpkiBase64 = requesterEphPub,
            sessionId = sessionId,
            ownerUid = ownerUid,
            requesterDeviceId = deviceId,
            secretHash = secretHash,
            codeChallenge = codeChallenge,
            plaintext = payloadJson,
        )
        val opened = DevicePairingPolicy.openAuthEnvelope(
            requesterEphPrivateKey = requesterEphKp.private,
            envelope = sealed,
            sessionId = sessionId,
            ownerUid = ownerUid,
            requesterDeviceId = deviceId,
            secretHash = secretHash,
            codeChallenge = codeChallenge,
        )
        assertEquals(payloadJson, opened)

        // Wrong private key or wrong secretHash / ownerUid fails GCM authentication
        val wrongEph = DevicePairingPolicy.generateEphemeralP256KeyPair()
        assertTrue(
            runCatching {
                DevicePairingPolicy.openAuthEnvelope(
                    wrongEph.private, sealed, sessionId, ownerUid, deviceId, secretHash, codeChallenge,
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                DevicePairingPolicy.openAuthEnvelope(
                    requesterEphKp.private, sealed, sessionId, "wrong_uid", deviceId, secretHash, codeChallenge,
                )
            }.isFailure
        )
    }

    @Test
    fun manualCodeAndRateLimiterAndDeviceOnlinePolicy() {
        val code = DevicePairingPolicy.generateManualCode()
        assertEquals(8, code.length)
        val formatted = DevicePairingPolicy.formatManualCode(code)
        assertEquals(9, formatted.length)
        assertEquals(code, DevicePairingPolicy.normalizeManualCode("  ${formatted.lowercase()} "))
        assertNull(DevicePairingPolicy.normalizeManualCode("123"))
        assertNull(DevicePairingPolicy.normalizeManualCode("0000-1111")) // 0 and 1 excluded

        val limiter = DevicePairingPolicy.RateLimiter(windowMs = 60_000L, maxCreate = 2, maxScan = 2, maxExchangeFailures = 2)
        assertTrue(limiter.tryAcquireCreate(1_000L))
        assertTrue(limiter.tryAcquireCreate(2_000L))
        assertFalse(limiter.tryAcquireCreate(3_000L))
        assertTrue(limiter.tryAcquireCreate(62_000L))

        assertTrue(limiter.isExchangeAllowed("s1"))
        assertTrue(limiter.recordExchangeFailure("s1"))
        assertTrue(limiter.recordExchangeFailure("s1"))
        assertFalse(limiter.isExchangeAllowed("s1"))

        val friendly = DevicePairingPolicy.friendlyDeviceId("550e8400-e29b-41d4-a716-446655440000", "abcdef1234567890")
        assertTrue(friendly.startsWith("PC-"))
        assertFalse(friendly.contains("550e8400"))

        val now = 500_000L
        assertTrue(DevicePairingPolicy.isDeviceOnline(LinkedDeviceStatus.ACTIVE, onlineFlag = true, lastActiveAtMs = now - 30_000L, nowMs = now))
        assertFalse(DevicePairingPolicy.isDeviceOnline(LinkedDeviceStatus.ACTIVE, onlineFlag = false, lastActiveAtMs = now - 1_000L, nowMs = now))
        assertFalse(DevicePairingPolicy.isDeviceOnline(LinkedDeviceStatus.ACTIVE, onlineFlag = true, lastActiveAtMs = 0L, nowMs = now))
        assertFalse(DevicePairingPolicy.isDeviceOnline(LinkedDeviceStatus.REVOKED, onlineFlag = true, lastActiveAtMs = now - 1_000L, nowMs = now))
        assertFalse(DevicePairingPolicy.isDeviceOnline(LinkedDeviceStatus.ACTIVE, onlineFlag = true, lastActiveAtMs = now - 600_000L, nowMs = now))
        assertEquals(DeviceKind.ANDROID_TABLET, DeviceKind.fromWire("android_tablet"))
    }

    @Test
    fun qrCodecEncodesAndDecodesMatrixAndLuminanceFrames() {
        val payload = QrPairingPayload(
            sessionId = DevicePairingPolicy.generateRandomToken(24),
            codeVerifier = DevicePairingPolicy.generateRandomToken(24),
            keyFingerprint = "0123456789abcdef01234567",
        )
        val uri = DevicePairingPolicy.encodeQrUri(payload)
        val qr = QrCodec.encode(uri)
        assertEquals(uri, QrCodec.decodeMatrix(qr.modules))

        // Render into a simulated camera Y-plane with quiet zone = 6 modules and scale = 5 px/module
        val quiet = 6
        val scale = 5
        val imgSize = (qr.size + quiet * 2) * scale
        val yPlane = ByteArray(imgSize * imgSize) { 240.toByte() } // light background
        for (my in 0 until qr.size) {
            for (mx in 0 until qr.size) {
                if (qr[mx, my]) {
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            val py = (my + quiet) * scale + dy
                            val px = (mx + quiet) * scale + dx
                            yPlane[py * imgSize + px] = 16.toByte()
                        }
                    }
                }
            }
        }
        val decodedFromLuminance = QrCodec.decodeFromLuminance(yPlane, imgSize, imgSize, imgSize)
        assertNotNull(decodedFromLuminance)
        assertEquals(uri, decodedFromLuminance)
    }
}
