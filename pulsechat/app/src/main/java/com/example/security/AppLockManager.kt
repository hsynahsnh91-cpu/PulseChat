package com.example.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Base64
import androidx.core.content.ContextCompat
import com.example.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App lock: a PulseChat PIN (works on every phone, with or without a screen lock), optionally
 * unlocked with the phone's fingerprint/face instead. Only a salted PBKDF2 hash of the PIN is kept,
 * in preferences that are excluded from backups. Wrong PINs cause growing waits.
 *
 * Locking: when the app returns to the foreground after the chosen timeout. Returning from screens
 * PulseChat opened itself (photo picker, camera, file picker, share sheet), from the unlock prompt
 * itself, or during a call does not lock.
 */
class AppLockManager(private val prefs: AppPreferences, private val callLive: () -> Boolean = { false }) {
    private val _locked = MutableStateFlow(prefs.current.appLockEnabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile private var backgroundedAt = 0L
    @Volatile private var externalSince = 0L
    @Volatile var authInProgress = false
        private set

    fun onBackground() {
        if (authInProgress) return
        backgroundedAt = System.currentTimeMillis()
    }

    fun onForeground() {
        val now = System.currentTimeMillis()
        val leftAt = backgroundedAt
        val external = externalSince
        backgroundedAt = 0L
        externalSince = 0L
        val s = prefs.current
        if (!s.appLockEnabled || authInProgress || leftAt == 0L || callLive()) return
        if (external != 0L && now - external < EXTERNAL_GRACE_MS) return
        if (now - leftAt >= s.appLockTimeoutMs) _locked.value = true
    }

    /** Called right before PulseChat opens another app's screen (pickers, camera, share sheet, system settings). */
    fun onExternalActivityLaunched() { externalSince = System.currentTimeMillis() }

    /** True while coming back from a screen PulseChat opened itself (read before [onForeground] resets it). */
    fun isReturningFromOwnScreen(): Boolean {
        val external = externalSince
        return external != 0L && System.currentTimeMillis() - external < EXTERNAL_GRACE_MS
    }

    fun unlock() { _locked.value = false }
    fun disableLock() { _locked.value = false }

    /** Turns the lock on with a new PIN (the app stays unlocked now). */
    suspend fun enable(pin: String, timeoutMs: Long) {
        val record = hashRecord(pin)
        prefs.setAppLockPinRecord(record)
        prefs.appLockFailures = 0
        prefs.appLockLockoutUntil = 0L
        prefs.setAppLock(true, timeoutMs)
        _locked.value = false
    }

    suspend fun changePin(pin: String) { prefs.setAppLockPinRecord(hashRecord(pin)) }

    /** Turns the lock off and forgets the PIN. */
    fun disable() {
        prefs.resetAppLock()
        _locked.value = false
    }

    // ------------------------------------------------------------------ PIN

    sealed interface PinResult {
        data object Ok : PinResult
        data object Wrong : PinResult
        data class Wait(val seconds: Int) : PinResult
    }

    fun hasPin(): Boolean = prefs.appLockPinRecord != null

    /** Seconds until the next PIN attempt is allowed (0 when allowed now). */
    fun waitSeconds(): Int {
        val left = prefs.appLockLockoutUntil - System.currentTimeMillis()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    suspend fun checkPin(pin: String): PinResult {
        waitSeconds().takeIf { it > 0 }?.let { return PinResult.Wait(it) }
        val record = prefs.appLockPinRecord ?: return PinResult.Wrong
        if (verify(pin, record)) {
            prefs.appLockFailures = 0
            prefs.appLockLockoutUntil = 0L
            return PinResult.Ok
        }
        val failures = prefs.appLockFailures + 1
        prefs.appLockFailures = failures
        if (failures >= FREE_ATTEMPTS) {
            val seconds = (30L shl (failures - FREE_ATTEMPTS).coerceAtMost(4)).coerceAtMost(MAX_WAIT_SECONDS)
            prefs.appLockLockoutUntil = System.currentTimeMillis() + seconds * 1000L
            return PinResult.Wait(seconds.toInt())
        }
        return PinResult.Wrong
    }

    private suspend fun hashRecord(pin: String): String = withContext(Dispatchers.Default) {
        require(isValidPin(pin)) { "invalid PIN" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val algorithm = pbkdf2Algorithm()
        val hash = pbkdf2(algorithm, pin, salt, ITERATIONS)
        listOf("v1", algorithm, ITERATIONS.toString(), b64(salt), b64(hash)).joinToString(":")
    }

    private suspend fun verify(pin: String, record: String): Boolean = withContext(Dispatchers.Default) {
        val parts = record.split(":")
        if (parts.size != 5 || parts[0] != "v1") return@withContext false
        val iterations = parts[2].toIntOrNull() ?: return@withContext false
        val expected = Base64.decode(parts[4], Base64.NO_WRAP)
        val actual = runCatching { pbkdf2(parts[1], pin, Base64.decode(parts[3], Base64.NO_WRAP), iterations) }.getOrNull() ?: return@withContext false
        MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(algorithm: String, pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun pbkdf2Algorithm(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    // ------------------------------------------------------------------ biometrics

    /** Fingerprint/face unlock is offered on Android 10+ when the phone has biometrics enrolled. */
    fun biometricAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val bm = context.getSystemService(BiometricManager::class.java) ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            @Suppress("DEPRECATION")
            bm.canAuthenticate()
        }
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Shows the system biometric prompt; [usePinLabel] is the button that falls back to the PIN. */
    fun authenticateBiometric(activity: Activity, title: String, subtitle: String, usePinLabel: String, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || authInProgress) return
        val executor = ContextCompat.getMainExecutor(activity)
        val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
            .setNegativeButton(usePinLabel, executor) { _, _ -> finishAuth(false, onResult) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        authInProgress = true
        runCatching {
            builder.build().authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { finishAuth(true, onResult) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { finishAuth(false, onResult) }
            })
        }.onFailure { finishAuth(false, onResult) }
    }

    private fun finishAuth(ok: Boolean, onResult: (Boolean) -> Unit) {
        authInProgress = false
        backgroundedAt = 0L
        onResult(ok)
    }

    // ------------------------------------------------------------------ legacy (locks set up before PINs existed)

    fun isDeviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /**
     * Unlock with the phone's own screen lock, for app locks turned on before PulseChat PINs. On
     * Android 10+ the biometric prompt with device-credential fallback is used; older versions use
     * the confirm-credential screen via [legacyLauncher].
     */
    fun authenticateWithDeviceCredential(activity: Activity, title: String, subtitle: String, legacyLauncher: (Intent) -> Unit, onResult: (Boolean) -> Unit) {
        if (authInProgress) return
        if (!isDeviceSecure(activity)) { onResult(true); return } // nothing left to check against
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            authInProgress = true
            runCatching {
                builder.build().authenticate(CancellationSignal(), ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { finishAuth(true, onResult) }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { finishAuth(false, onResult) }
                })
            }.onFailure { finishAuth(false, onResult) }
        } else {
            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) onResult(true) else legacyLauncher(intent)
        }
    }

    companion object {
        const val PIN_MIN = 4
        const val PIN_MAX = 8
        private const val ITERATIONS = 100_000
        private const val FREE_ATTEMPTS = 5
        private const val MAX_WAIT_SECONDS = 300L
        private const val EXTERNAL_GRACE_MS = 10 * 60 * 1000L

        fun isValidPin(pin: String): Boolean = pin.length in PIN_MIN..PIN_MAX && pin.all { it in '0'..'9' }
    }
}
