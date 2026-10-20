package com.example.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.example.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional app lock using the platform biometric prompt (fingerprint/face) with the device
 * PIN/pattern/password as fallback. No credentials are stored by the app.
 */
class AppLockManager(private val prefs: AppPreferences) {
    private val _locked = MutableStateFlow(prefs.current.appLockEnabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var backgroundedAt = 0L

    fun onBackground() { backgroundedAt = System.currentTimeMillis() }

    fun onForeground() {
        val s = prefs.current
        if (s.appLockEnabled && backgroundedAt > 0 && System.currentTimeMillis() - backgroundedAt >= s.appLockTimeoutMs) _locked.value = true
    }

    fun unlock() { _locked.value = false }
    fun disableLock() { _locked.value = false }

    fun isDeviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /**
     * Shows the system authentication UI. On Android 10+ the biometric prompt is used (with device
     * credential fallback); older versions use the confirm-credential screen via [legacyLauncher].
     */
    fun authenticate(activity: Activity, title: String, subtitle: String, legacyLauncher: (Intent) -> Unit, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            val prompt = builder.build()
            prompt.authenticate(CancellationSignal(), ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onResult(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { onResult(false) }
            })
        } else {
            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) onResult(true) else legacyLauncher(intent)
        }
    }
}
