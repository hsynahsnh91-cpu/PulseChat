package com.example

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.example.util.EmulatorConfig

/**
 * Passes the optional `firebaseEmulatorHost` instrumentation argument to the app before
 * Application.onCreate runs, so the app container connects to the local Firebase emulators.
 * Usage: gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorHost=10.0.2.2
 */
class PulseTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        arguments?.getString("firebaseEmulatorHost")?.takeIf { it.isNotBlank() }?.let { System.setProperty(EmulatorConfig.PROPERTY, it) }
        super.onCreate(arguments)
    }
}
