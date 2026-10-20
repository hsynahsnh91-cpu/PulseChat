package com.example.util

import com.example.BuildConfig

/**
 * Debug-only switch that points Firebase Auth/Firestore/Storage at the local Firebase emulators.
 * It is set exclusively by the instrumented-test runner (system property, see androidTest
 * PulseTestRunner); release builds always return null and talk to the real backend.
 */
object EmulatorConfig {
    const val PROPERTY = "pulsechat.emulatorHost"
    const val AUTH_PORT = 9099
    const val FIRESTORE_PORT = 8080
    const val STORAGE_PORT = 9199

    val host: String?
        get() = if (BuildConfig.DEBUG) System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } else null
}
