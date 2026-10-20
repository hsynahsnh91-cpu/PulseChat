package com.example

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.repository.SessionState
import com.example.util.EmulatorConfig
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import java.io.File

/**
 * Shared plumbing for end-to-end tests that run the real app on an Android device/emulator against
 * the Firebase emulators (Auth, Firestore with this repo's `firestore.rules`, Storage).
 *
 * Accounts take turns on the device: [switchDevice] signs out and wipes local data, so every
 * sign-in behaves like a fresh install and everything must arrive through sync.
 * Tests are skipped unless the runner receives the `firebaseEmulatorHost` argument.
 */
abstract class EmulatorE2ETest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    protected val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected val c: AppContainer get() = (context.applicationContext as PulseChatApplication).container

    @Before fun requireEmulators() {
        assumeTrue("Firebase emulators not configured", EmulatorConfig.host != null)
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    protected fun uniqueSuffix(): String = (System.currentTimeMillis() % 1_000_000).toString()

    protected suspend fun await(what: String, timeoutMs: Long = 45_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(250)
        }
        fail("Timed out waiting for: $what")
    }

    protected suspend fun awaitSignedIn(): String {
        await("signed in") { c.authRepository.session.value is SessionState.SignedIn }
        return c.authRepository.currentUid!!
    }

    /** Signs out and wipes local data, so the next account starts like a new device. */
    protected suspend fun switchDevice() {
        // Let this account's queued writes reach the server first (they are not sent for another user).
        withTimeoutOrNull(30_000) { c.firestore.waitForPendingWrites().await() }
        c.authRepository.signOut()
        await("signed out") { c.authRepository.session.value == SessionState.SignedOut }
        delay(1_500) // let queued sync work drain before wiping
        withContext(Dispatchers.IO) { c.db.clearAllTables() }
    }

    /** Starts the test signed out with empty local storage (earlier tests may have left data behind). */
    protected suspend fun startFresh() {
        if (c.authRepository.session.value !is SessionState.SignedOut) switchDevice()
        else withContext(Dispatchers.IO) { c.db.clearAllTables() }
    }

    /** The server (emulator running this repo's firestore.rules) must reject [block] with PERMISSION_DENIED. */
    protected suspend fun assertDenied(what: String, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertEquals("security rules must deny: $what (got $error)", FirebaseFirestoreException.Code.PERMISSION_DENIED, (error as? FirebaseFirestoreException)?.code)
    }

    /** A small PNG shared through the app's own FileProvider, like a picked/captured photo. */
    protected fun testImage(): Uri {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "e2e_${System.currentTimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(79, 70, 229)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
