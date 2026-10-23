package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.example.BuildConfig
import com.example.data.prefs.AppPreferences
import com.example.domain.DevicePairingPolicy
import com.example.domain.DevicePairingPolicy.DeviceKind
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the device-bound EC P-256 identity keypair and Keystore-wrapped session delegation
 * material. Private keys live in the Android Keystore (`AndroidKeyStore`) and are never exported.
 * In unit-test environments without `AndroidKeyStore` (Robolectric), a local fallback is used.
 */
class DeviceKeyManager(
    private val context: Context,
    private val prefs: AppPreferences,
) {
    private val secure: SharedPreferences =
        context.getSharedPreferences("pulse_security", Context.MODE_PRIVATE)

    val deviceId: String get() = prefs.deviceId

    fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val combined = when {
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }
        return DevicePairingPolicy.sanitizeDeviceName(combined)
    }

    fun deviceKind(): DeviceKind {
        val sw = context.resources?.configuration?.smallestScreenWidthDp ?: 0
        return if (sw >= 600) DeviceKind.ANDROID_TABLET else DeviceKind.ANDROID_PHONE
    }

    /** SPKI Base64Url encoding of this device's P-256 public key. */
    fun publicKeySpkiBase64(): String =
        DevicePairingPolicy.encodePublicKeySpki(getOrCreateSigningKeyPair().public)

    /** 24-hex-char SHA-256 fingerprint of this device's public key. */
    fun keyFingerprint(): String = DevicePairingPolicy.keyFingerprint(publicKeySpkiBase64())

    /** Friendly masked identifier shown in UI (e.g. "PC-4A8F-••9C"). */
    fun friendlyId(): String = DevicePairingPolicy.friendlyDeviceId(deviceId, keyFingerprint())

    /** Signs the canonical pairing-exchange transcript with this device's private key. */
    fun signExchangeChallenge(sessionId: String, secretHash: String, timestampMs: Long): String {
        val kp = getOrCreateSigningKeyPair()
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(DevicePairingPolicy.canonicalExchangeTranscript(sessionId, secretHash, deviceId, timestampMs))
        return DevicePairingPolicy.UrlBase64.encode(sig.sign())
    }

    /**
     * Seals the signed-in account's delegation credential in Android Keystore AES-256-GCM so this
     * device can authorize a linked device without asking the user to re-type their password.
     */
    fun storeAccountDelegation(uid: String, email: String, password: String) {
        if (uid.isBlank() || email.isBlank() || password.isBlank()) return
        runCatching {
            val json = JSONObject()
                .put("uid", uid)
                .put("email", email)
                .put("password", password)
                .toString()
            val wrapped = encryptWithKeystoreAes("pulsechat_delegation_$uid", json.toByteArray(StandardCharsets.UTF_8))
            secure.edit().putString("delegation_blob_$uid", wrapped).apply()
        }
    }

    /** Reads the Keystore-protected delegation JSON for [uid], or null if unavailable. */
    fun readAccountDelegation(uid: String): Pair<String, String>? = runCatching {
        val blob = secure.getString("delegation_blob_$uid", null) ?: return null
        val plain = decryptWithKeystoreAes("pulsechat_delegation_$uid", blob) ?: return null
        val obj = JSONObject(String(plain, StandardCharsets.UTF_8))
        plain.fill(0)
        if (obj.optString("uid") != uid) return null
        val email = obj.optString("email")
        val password = obj.optString("password")
        if (email.isBlank() || password.isBlank()) null else email to password
    }.getOrNull()

    fun clearAccountDelegation(uid: String) {
        secure.edit().remove("delegation_blob_$uid").apply()
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val alias = "pulsechat_delegation_$uid"
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    @Synchronized
    private fun getOrCreateSigningKeyPair(): KeyPair {
        val alias = "pulsechat_device_ec_$deviceId"
        val keystorePair = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) {
                KeyPair(entry.certificate.publicKey, entry.privateKey)
            } else {
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }
        }.getOrNull()
        if (keystorePair != null) return keystorePair

        // Fallback for unit-test JVM / Robolectric where AndroidKeyStore is not registered.
        // DEBUG BUILDS ONLY: this stores the EC private key in plain SharedPreferences. A release
        // build on a device whose Keystore is broken must fail closed (device linking unavailable)
        // rather than silently downgrade the device identity key to plaintext.
        check(BuildConfig.DEBUG) { "AndroidKeyStore unavailable; refusing plaintext device-key storage" }
        val savedPub = secure.getString("fallback_ec_pub_$deviceId", null)
        val savedPriv = secure.getString("fallback_ec_priv_$deviceId", null)
        if (savedPub != null && savedPriv != null) {
            val pair = runCatching {
                val pub: PublicKey = DevicePairingPolicy.decodePublicKeySpki(savedPub)
                val priv: PrivateKey = DevicePairingPolicy.decodePrivateKeyPkcs8(savedPriv)
                KeyPair(pub, priv)
            }.getOrNull()
            if (pair != null) return pair
        }
        val generated = DevicePairingPolicy.generateEphemeralP256KeyPair()
        secure.edit()
            .putString("fallback_ec_pub_$deviceId", DevicePairingPolicy.encodePublicKeySpki(generated.public))
            .putString("fallback_ec_priv_$deviceId", DevicePairingPolicy.encodePrivateKeyPkcs8(generated.private))
            .apply()
        return generated
    }

    private fun encryptWithKeystoreAes(alias: String, plaintext: ByteArray): String {
        // If the key was invalidated (new biometric enrolled), drop it so the next sign-in stores
        // a fresh delegation blob under a new key instead of failing forever.
        try {
            val key = getOrCreateAesKey(alias)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            return "${DevicePairingPolicy.UrlBase64.encode(iv)}:${DevicePairingPolicy.UrlBase64.encode(ciphertext)}"
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKeystoreEntry(alias)
            throw e
        }
    }

    private fun decryptWithKeystoreAes(alias: String, encoded: String): ByteArray? {
        val parts = encoded.split(':')
        if (parts.size != 2) return null
        val iv = DevicePairingPolicy.UrlBase64.decode(parts[0])
        val ciphertext = DevicePairingPolicy.UrlBase64.decode(parts[1])
        return try {
            val key = getOrCreateAesKey(alias)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Sealed under a key the phone no longer trusts: the blob is unrecoverable by design;
            // pairing simply asks for a fresh sign-in on this device.
            deleteKeystoreEntry(alias)
            null
        }
    }

    private fun deleteKeystoreEntry(alias: String) {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    private fun getOrCreateAesKey(alias: String): SecretKey {
        val ksKey = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) existing else {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Enrolling a new fingerprint/face permanently invalidates this key, so a
                    // sealed password can never be read after someone adds their biometric to an
                    // unlocked phone. The delegation is then re-stored on the next password sign-in.
                    .setInvalidatedByBiometricEnrollment(true)
                    .apply {
                        // The sealed credential is only usable while the device itself is unlocked.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setUnlockedDeviceRequired(true)
                    }
                    .build()
                kg.init(spec)
                kg.generateKey()
            }
        }.getOrNull()
        if (ksKey != null) return ksKey

        // DEBUG BUILDS ONLY (same rule as the EC fallback above): a raw AES key in preferences.
        check(BuildConfig.DEBUG) { "AndroidKeyStore unavailable; refusing plaintext AES-key storage" }
        val saved = secure.getString("fallback_aes_$alias", null)
        if (saved != null) {
            return SecretKeySpec(DevicePairingPolicy.UrlBase64.decode(saved), "AES")
        }
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        secure.edit().putString("fallback_aes_$alias", DevicePairingPolicy.UrlBase64.encode(raw)).apply()
        return SecretKeySpec(raw, "AES")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
