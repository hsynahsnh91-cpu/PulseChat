package com.example.domain

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure domain rules, state-machine validation, QR payload validation, rate-limiting, and
 * cryptographic primitives (ECDSA P-256 device proofs + ECDH P-256 / HKDF-SHA256 / AES-256-GCM
 * one-time session envelopes) for the PulseChat Device Linking System.
 */
object DevicePairingPolicy {
    /** Pairing QR codes and manual codes expire after 2 minutes. */
    const val PAIRING_TTL_MS = 120_000L
    /** Maximum allowed clock skew when checking timestamps locally. */
    const val CLOCK_SKEW_TOLERANCE_MS = 30_000L
    /** Rate-limit window for session creation and scanning. */
    const val RATE_LIMIT_WINDOW_MS = 120_000L
    const val MAX_CREATE_PER_WINDOW = 5
    const val MAX_SCAN_PER_WINDOW = 10
    const val MAX_EXCHANGE_FAILURES = 3

    const val QR_SCHEME = "pulsechat"
    const val QR_HOST = "pair"
    const val QR_VERSION = 1
    const val PROTOCOL_VERSION = QR_VERSION
    const val MAX_QR_LENGTH = 256
    const val MANUAL_CODE_LENGTH = 8

    /** Unambiguous Crockford Base32 alphabet (no 0/O/1/I/L). */
    private const val MANUAL_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val SESSION_ID_REGEX = Regex("^[A-Za-z0-9_-]{20,40}$")
    private val VERIFIER_REGEX = Regex("^[A-Za-z0-9_-]{20,48}$")
    private val FINGERPRINT_REGEX = Regex("^[0-9a-fA-F]{16,64}$")
    private val FORBIDDEN_KEYS = setOf("token", "idtoken", "customtoken", "password", "secret", "privatekey", "jwt", "auth")

    private val secureRandom = SecureRandom()

    enum class PairingStatus(val wire: String) {
        PENDING("pending"),
        APPROVED("approved"),
        COMPLETED("completed"),
        REJECTED("rejected"),
        CANCELLED("cancelled"),
        EXPIRED("expired"),
        REVOKED("revoked");

        val isTerminal: Boolean
            get() = this == COMPLETED || this == REJECTED || this == CANCELLED || this == EXPIRED || this == REVOKED

        companion object {
            fun fromWire(value: String?): PairingStatus =
                entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: EXPIRED
        }
    }

    enum class LinkedDeviceStatus(val wire: String) {
        ACTIVE("active"),
        REVOKED("revoked");

        companion object {
            fun fromWire(value: String?): LinkedDeviceStatus =
                entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: ACTIVE
        }
    }

    enum class DeviceKind(val wire: String) {
        ANDROID_PHONE("android_phone"),
        ANDROID_TABLET("android_tablet"),
        WEB("web"),
        OTHER("other"),
        UNKNOWN("unknown");

        companion object {
            fun fromWire(value: String?): DeviceKind =
                entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: ANDROID_PHONE
        }
    }

    enum class PairingError {
        INVALID_QR,
        UNSUPPORTED_VERSION,
        INVALID_MANUAL_CODE,
        EXPIRED,
        REJECTED,
        CANCELLED,
        ALREADY_CONSUMED,
        UNAUTHORIZED,
        DEVICE_REVOKED,
        DEVICE_MISMATCH,
        INVALID_SECRET_PROOF,
        RATE_LIMITED,
        ALREADY_SIGNED_IN_DIFFERENT_ACCOUNT,
        NETWORK,
        SESSION_NOT_FOUND,
        INVALID_STATE,
        CRYPTO_FAILURE,
        BACKEND_UNAVAILABLE,
        UNKNOWN,
    }

    class PairingException(
        val error: PairingError,
        message: String? = error.name,
        cause: Throwable? = null,
    ) : Exception(message ?: error.name, cause)

    /**
     * Minimal, non-sensitive QR payload displayed on the requesting device.
     * Contains no password, Firebase token, private key, or reusable credential.
     */
    data class QrPairingPayload(
        val version: Int = QR_VERSION,
        val sessionId: String,
        val codeVerifier: String,
        val keyFingerprint: String,
    )

    /** Sealed one-time authentication envelope encrypted via ECDH P-256 + HKDF-SHA256 + AES-256-GCM. */
    data class SealedAuthEnvelope(
        val approverEphemeralPublicKey: String,
        val ivBase64Url: String,
        val ciphertextBase64Url: String,
    ) {
        val approverEphemeralPublicKeySpki: String get() = approverEphemeralPublicKey
    }

    /** Enforce valid state transitions; terminal or consumed sessions can never be approved or completed. */
    fun canTransition(
        from: PairingStatus,
        to: PairingStatus,
        nowMs: Long,
        expiresAtMs: Long,
        consumed: Boolean,
    ): Boolean {
        if (from == to) return false
        if (consumed) return false
        if (from.isTerminal) return false
        val notExpired = expiresAtMs > 0L && nowMs < expiresAtMs
        return when (from) {
            PairingStatus.PENDING -> when (to) {
                PairingStatus.APPROVED -> notExpired
                PairingStatus.REJECTED -> notExpired
                PairingStatus.EXPIRED, PairingStatus.REVOKED, PairingStatus.CANCELLED -> true
                else -> false
            }
            PairingStatus.APPROVED -> when (to) {
                PairingStatus.COMPLETED -> notExpired
                PairingStatus.REVOKED, PairingStatus.EXPIRED, PairingStatus.CANCELLED -> true
                else -> false
            }
            else -> false
        }
    }

    fun isExpired(expiresAtMs: Long, nowMs: Long): Boolean = expiresAtMs <= 0L || nowMs >= expiresAtMs

    fun remainingSeconds(expiresAtMs: Long, nowMs: Long): Int =
        if (expiresAtMs <= nowMs) 0 else ((expiresAtMs - nowMs + 999L) / 1000L).toInt()

    /** Encodes the minimal non-sensitive pairing URI rendered in the QR code. */
    fun encodeQrUri(payload: QrPairingPayload): String {
        require(SESSION_ID_REGEX.matches(payload.sessionId)) { "Invalid sessionId" }
        require(VERIFIER_REGEX.matches(payload.codeVerifier)) { "Invalid codeVerifier" }
        require(FINGERPRINT_REGEX.matches(payload.keyFingerprint)) { "Invalid keyFingerprint" }
        return "$QR_SCHEME://$QR_HOST?v=${payload.version}&s=${payload.sessionId}&k=${payload.codeVerifier}&f=${payload.keyFingerprint.lowercase()}"
    }

    fun buildQrUri(payload: QrPairingPayload): String = encodeQrUri(payload)

    fun verifySecretProof(secretOrVerifier: String, expectedSha256Hex: String): Boolean =
        constantTimeEquals(sha256Hex(secretOrVerifier), expectedSha256Hex.lowercase())

    /**
     * Strictly validates untrusted QR scanner input. Rejects malformed URIs, unknown versions,
     * and any payload that attempts to carry secrets, tokens, or unexpected parameters.
     */
    fun parseQrUri(raw: String): Result<QrPairingPayload> = runCatching {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_QR_LENGTH) {
            throw PairingException(PairingError.INVALID_QR, "QR payload length invalid")
        }
        val prefix = "$QR_SCHEME://$QR_HOST?"
        if (!trimmed.startsWith(prefix, ignoreCase = true)) {
            throw PairingException(PairingError.INVALID_QR, "Unexpected QR scheme or host")
        }
        val query = trimmed.substring(prefix.length)
        if (query.isEmpty() || query.contains('#') || query.contains(' ')) {
            throw PairingException(PairingError.INVALID_QR, "Malformed QR query")
        }
        val params = mutableMapOf<String, String>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0 || eq == part.lastIndex) throw PairingException(PairingError.INVALID_QR, "Malformed query parameter")
            val key = part.substring(0, eq).lowercase()
            val value = part.substring(eq + 1)
            if (key in FORBIDDEN_KEYS) throw PairingException(PairingError.INVALID_QR, "Forbidden parameter in QR")
            if (key !in setOf("v", "s", "k", "f")) throw PairingException(PairingError.INVALID_QR, "Unknown parameter in QR")
            if (params.put(key, value) != null) throw PairingException(PairingError.INVALID_QR, "Duplicate parameter in QR")
        }
        val version = params["v"]?.toIntOrNull() ?: throw PairingException(PairingError.INVALID_QR, "Missing version")
        if (version != QR_VERSION) throw PairingException(PairingError.UNSUPPORTED_VERSION, "Unsupported QR version $version")
        val sessionId = params["s"] ?: throw PairingException(PairingError.INVALID_QR, "Missing session ID")
        val codeVerifier = params["k"] ?: throw PairingException(PairingError.INVALID_QR, "Missing code verifier")
        val fingerprint = params["f"] ?: throw PairingException(PairingError.INVALID_QR, "Missing key fingerprint")
        if (!SESSION_ID_REGEX.matches(sessionId)) throw PairingException(PairingError.INVALID_QR, "Invalid session ID")
        if (!VERIFIER_REGEX.matches(codeVerifier)) throw PairingException(PairingError.INVALID_QR, "Invalid code verifier")
        if (!FINGERPRINT_REGEX.matches(fingerprint)) throw PairingException(PairingError.INVALID_QR, "Invalid key fingerprint")
        QrPairingPayload(version = version, sessionId = sessionId, codeVerifier = codeVerifier, keyFingerprint = fingerprint.lowercase())
    }

    /** Generates a 192-bit (32-char Base64Url) cryptographically unpredictable token. */
    fun generateRandomToken(byteCount: Int = 24): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return UrlBase64.encode(bytes)
    }

    /** Generates an 8-character human-readable fallback pairing code (Crockford Base32). */
    fun generateManualCode(): String {
        val sb = StringBuilder(MANUAL_CODE_LENGTH)
        repeat(MANUAL_CODE_LENGTH) {
            sb.append(MANUAL_ALPHABET[secureRandom.nextInt(MANUAL_ALPHABET.length)])
        }
        return sb.toString()
    }

    fun formatManualCode(code: String): String {
        val clean = normalizeManualCode(code) ?: code.uppercase().filter { it.isLetterOrDigit() }.take(MANUAL_CODE_LENGTH)
        return if (clean.length <= 4) clean else "${clean.substring(0, 4)}-${clean.substring(4)}"
    }

    fun normalizeManualCode(raw: String): String? {
        val cleaned = raw.trim().uppercase().replace("-", "").replace(" ", "")
        if (cleaned.length != MANUAL_CODE_LENGTH) return null
        if (cleaned.any { it !in MANUAL_ALPHABET }) return null
        return cleaned
    }

    /** Lowercase 64-character hex SHA-256 digest of [text] (UTF-8). */
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(StandardCharsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hex)
    }

    /** Short 24-hex-char (96-bit) fingerprint of a public key for QR binding and display. */
    fun keyFingerprint(publicKeySpkiBase64: String): String =
        sha256Hex(publicKeySpkiBase64.trim()).substring(0, 24)

    /** Constant-time string comparison for secret hashes and verifiers. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val ba = a.toByteArray(StandardCharsets.UTF_8)
        val bb = b.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(ba, bb)
    }

    /**
     * Partially masked, user-friendly device identifier (e.g. "PC-4A8F-••9C").
     * Never exposes raw installation UUIDs, FCM tokens, or auth tokens.
     */
    fun friendlyDeviceId(deviceId: String, keyFingerprint: String = ""): String {
        val digest = sha256Hex("$deviceId:${keyFingerprint.lowercase()}").uppercase()
        return "PC-${digest.substring(0, 4)}-••${digest.substring(6, 8)}"
    }

    fun sanitizeDeviceName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
            .take(64)
        return cleaned.ifBlank { "Android Device" }
    }

    /**
     * A linked device is shown as online ONLY when its status is active, its online flag is set,
     * and the backend recorded a recent heartbeat within the online TTL window.
     */
    fun isDeviceOnline(
        status: LinkedDeviceStatus,
        onlineFlag: Boolean,
        lastActiveAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (status != LinkedDeviceStatus.ACTIVE || !onlineFlag || lastActiveAtMs <= 0L) return false
        val age = nowMs - lastActiveAtMs
        return age in -CLOCK_SKEW_TOLERANCE_MS..PresencePolicy.STALE_AFTER_MS
    }

    // ------------------------------------------------------------------ P-256 Cryptography

    fun generateEphemeralP256KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    fun encodePublicKeySpki(publicKey: PublicKey): String = UrlBase64.encode(publicKey.encoded)

    fun decodePublicKeySpki(spkiBase64Url: String): PublicKey {
        val bytes = UrlBase64.decode(spkiBase64Url)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun encodePrivateKeyPkcs8(privateKey: PrivateKey): String = UrlBase64.encode(privateKey.encoded)

    fun decodePrivateKeyPkcs8(pkcs8Base64Url: String): PrivateKey {
        val bytes = UrlBase64.decode(pkcs8Base64Url)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    fun canonicalExchangeTranscript(
        sessionId: String,
        secretHash: String,
        deviceId: String,
        timestampMs: Long,
    ): ByteArray = "pulsechat-exchange:v1:$sessionId:${secretHash.lowercase()}:$deviceId:$timestampMs"
        .toByteArray(StandardCharsets.UTF_8)

    fun signExchangeChallenge(
        privateKey: PrivateKey,
        sessionId: String,
        secretHash: String,
        deviceId: String,
        timestampMs: Long,
    ): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey, secureRandom)
        sig.update(canonicalExchangeTranscript(sessionId, secretHash, deviceId, timestampMs))
        return UrlBase64.encode(sig.sign())
    }

    fun verifyExchangeChallenge(
        publicKeySpkiBase64: String,
        sessionId: String,
        secretHash: String,
        deviceId: String,
        timestampMs: Long,
        signatureBase64Url: String,
    ): Boolean = runCatching {
        val pub = decodePublicKeySpki(publicKeySpkiBase64)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pub)
        sig.update(canonicalExchangeTranscript(sessionId, secretHash, deviceId, timestampMs))
        sig.verify(UrlBase64.decode(signatureBase64Url))
    }.getOrDefault(false)

    /**
     * Encrypts a one-time authentication payload from the approving device (or Cloud Function)
     * to the requesting device's ephemeral P-256 public key using ECDH + HKDF-SHA256 + AES-256-GCM.
     * Bound via HKDF info and GCM AAD to (sessionId, ownerUid, requesterDeviceId, secretHash, codeChallenge).
     */
    fun sealAuthEnvelope(
        approverEphKeyPair: KeyPair,
        requesterEphPubSpkiBase64: String,
        sessionId: String,
        ownerUid: String,
        requesterDeviceId: String,
        secretHash: String,
        codeChallenge: String,
        plaintext: String,
    ): SealedAuthEnvelope {
        val peerPub = decodePublicKeySpki(requesterEphPubSpkiBase64)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(approverEphKeyPair.private)
        ka.doPhase(peerPub, true)
        val sharedSecret = ka.generateSecret()
        val aesKey = hkdfSha256(
            ikm = sharedSecret,
            salt = sessionId.toByteArray(StandardCharsets.UTF_8),
            info = "pulsechat-pair-v1:${secretHash.lowercase()}:${codeChallenge.lowercase()}".toByteArray(StandardCharsets.UTF_8),
        )
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val aad = "$sessionId|$ownerUid|$requesterDeviceId|${secretHash.lowercase()}".toByteArray(StandardCharsets.UTF_8)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        sharedSecret.fill(0)
        aesKey.fill(0)
        return SealedAuthEnvelope(
            approverEphemeralPublicKey = encodePublicKeySpki(approverEphKeyPair.public),
            ivBase64Url = UrlBase64.encode(iv),
            ciphertextBase64Url = UrlBase64.encode(ciphertext),
        )
    }

    fun sealAuthEnvelope(
        requesterEphemeralPublicKeySpki: String,
        sessionId: String,
        ownerUid: String,
        requesterDeviceId: String,
        secretHash: String,
        codeChallenge: String,
        plaintextJson: String,
    ): SealedAuthEnvelope = try {
        sealAuthEnvelope(
            approverEphKeyPair = generateEphemeralP256KeyPair(),
            requesterEphPubSpkiBase64 = requesterEphemeralPublicKeySpki,
            sessionId = sessionId,
            ownerUid = ownerUid,
            requesterDeviceId = requesterDeviceId,
            secretHash = secretHash,
            codeChallenge = codeChallenge,
            plaintext = plaintextJson,
        )
    } catch (e: Exception) {
        throw PairingException(PairingError.CRYPTO_FAILURE, e.message, e)
    }

    /**
     * Decrypts and authenticates a [SealedAuthEnvelope] using the requesting device's in-memory
     * ephemeral P-256 private key and session parameters. Throws on any tampering or mismatch.
     */
    fun openAuthEnvelope(
        requesterEphPrivateKey: PrivateKey,
        envelope: SealedAuthEnvelope,
        sessionId: String,
        ownerUid: String,
        requesterDeviceId: String,
        secretHash: String,
        codeChallenge: String,
    ): String {
        val peerPub = decodePublicKeySpki(envelope.approverEphemeralPublicKey)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(requesterEphPrivateKey)
        ka.doPhase(peerPub, true)
        val sharedSecret = ka.generateSecret()
        val aesKey = hkdfSha256(
            ikm = sharedSecret,
            salt = sessionId.toByteArray(StandardCharsets.UTF_8),
            info = "pulsechat-pair-v1:${secretHash.lowercase()}:${codeChallenge.lowercase()}".toByteArray(StandardCharsets.UTF_8),
        )
        try {
            val iv = UrlBase64.decode(envelope.ivBase64Url)
            require(iv.size == 12) { "Invalid IV size" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            val aad = "$sessionId|$ownerUid|$requesterDeviceId|${secretHash.lowercase()}".toByteArray(StandardCharsets.UTF_8)
            cipher.updateAAD(aad)
            val plainBytes = cipher.doFinal(UrlBase64.decode(envelope.ciphertextBase64Url))
            return String(plainBytes, StandardCharsets.UTF_8)
        } finally {
            sharedSecret.fill(0)
            aesKey.fill(0)
        }
    }

    fun openAuthEnvelope(
        requesterEphemeralPrivateKey: PrivateKey,
        approverEphemeralPublicKeySpki: String,
        sessionId: String,
        ownerUid: String,
        requesterDeviceId: String,
        secretHash: String,
        codeChallenge: String,
        ivBase64Url: String,
        ciphertextBase64Url: String,
    ): String = try {
        openAuthEnvelope(
            requesterEphPrivateKey = requesterEphemeralPrivateKey,
            envelope = SealedAuthEnvelope(
                approverEphemeralPublicKey = approverEphemeralPublicKeySpki,
                ivBase64Url = ivBase64Url,
                ciphertextBase64Url = ciphertextBase64Url,
            ),
            sessionId = sessionId,
            ownerUid = ownerUid,
            requesterDeviceId = requesterDeviceId,
            secretHash = secretHash,
            codeChallenge = codeChallenge,
        )
    } catch (e: Exception) {
        throw PairingException(PairingError.CRYPTO_FAILURE, e.message, e)
    }

    /** RFC 5869 HKDF-SHA256 producing a 32-byte (256-bit) key. */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        val okm = mac.doFinal()
        prk.fill(0)
        return okm.copyOf(32)
    }

    /** Sliding-window rate limiter for pairing creation, QR scans, and failed exchanges. */
    class RateLimiter(
        private val windowMs: Long = RATE_LIMIT_WINDOW_MS,
        private val maxCreate: Int = MAX_CREATE_PER_WINDOW,
        private val maxScan: Int = MAX_SCAN_PER_WINDOW,
        private val maxExchangeFailures: Int = MAX_EXCHANGE_FAILURES,
    ) {
        private val creates = ArrayDeque<Long>()
        private val scans = ArrayDeque<Long>()
        private val exchangeFailures = mutableMapOf<String, Int>()

        @Synchronized
        fun tryAcquireCreate(nowMs: Long): Boolean = acquire(creates, maxCreate, nowMs)

        @Synchronized
        fun tryAcquireScan(nowMs: Long): Boolean = acquire(scans, maxScan, nowMs)

        @Synchronized
        fun recordExchangeFailure(sessionId: String): Boolean {
            val count = (exchangeFailures[sessionId] ?: 0) + 1
            exchangeFailures[sessionId] = count
            return count <= maxExchangeFailures
        }

        @Synchronized
        fun isExchangeAllowed(sessionId: String): Boolean =
            (exchangeFailures[sessionId] ?: 0) < maxExchangeFailures

        @Synchronized
        fun reset() {
            creates.clear()
            scans.clear()
            exchangeFailures.clear()
        }

        private fun acquire(queue: ArrayDeque<Long>, maxCount: Int, nowMs: Long): Boolean {
            while (queue.isNotEmpty() && nowMs - queue.first() >= windowMs) {
                queue.removeFirst()
            }
            if (queue.size >= maxCount) return false
            queue.addLast(nowMs)
            return true
        }
    }

    /** Pure-Kotlin RFC 4648 URL-safe Base64 (no padding), safe on Android API 24+ and JVM tests. */
    object UrlBase64 {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val DECODE_TABLE = IntArray(128) { -1 }.apply {
            for (i in ALPHABET.indices) this[ALPHABET[i].code] = i
            this['+'.code] = 62
            this['/'.code] = 63
        }

        fun encode(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i++].toInt() and 0xFF
                val b1 = if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1
                val b2 = if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1
                out.append(ALPHABET[b0 ushr 2])
                if (b1 < 0) {
                    out.append(ALPHABET[(b0 and 0x03) shl 4])
                } else {
                    out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                    if (b2 < 0) {
                        out.append(ALPHABET[(b1 and 0x0F) shl 2])
                    } else {
                        out.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                        out.append(ALPHABET[b2 and 0x3F])
                    }
                }
            }
            return out.toString()
        }

        fun decode(input: String): ByteArray {
            val clean = input.trim().trimEnd('=')
            if (clean.isEmpty()) return ByteArray(0)
            val out = ByteBuffer.allocate((clean.length * 3) / 4 + 1)
            var buffer = 0
            var bits = 0
            for (ch in clean) {
                val code = ch.code
                val v = if (code in 0..127) DECODE_TABLE[code] else -1
                require(v >= 0) { "Invalid Base64Url character" }
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.put(((buffer ushr bits) and 0xFF).toByte())
                }
            }
            val result = ByteArray(out.position())
            out.flip()
            out.get(result)
            return result
        }
    }
}

typealias PairingSessionStatus = DevicePairingPolicy.PairingStatus
typealias LinkedDeviceStatus = DevicePairingPolicy.LinkedDeviceStatus
typealias DeviceKind = DevicePairingPolicy.DeviceKind
typealias PairingError = DevicePairingPolicy.PairingError
typealias PairingException = DevicePairingPolicy.PairingException
typealias PairingQrPayload = DevicePairingPolicy.QrPairingPayload
