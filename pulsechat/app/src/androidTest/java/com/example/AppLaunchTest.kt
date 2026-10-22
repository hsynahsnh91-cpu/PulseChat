package com.example

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.repository.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device smoke test: the real Application (Firebase, Room, notification channels, App Check)
 * starts, MainActivity renders the sign-in screen, and local form validation works without network.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun signedOut() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(InstrumentationRegistry.getInstrumentation().targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PulseChatApplication).container
        if (container.authRepository.session.value is SessionState.SignedIn) runBlocking { container.authRepository.signOut() }
    }

    @Test
    fun launchesToSignInAndValidatesRegistrationLocally() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("login_button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_register").performClick()
        compose.onNodeWithTag("register_name").performTextInput("Test User")
        compose.onNodeWithTag("register_username").performTextInput("tester")
        compose.onNodeWithTag("register_password").performTextInput("abcdefg1")
        compose.onNodeWithTag("register_confirm").performTextInput("abcdefg2")
        // The form scrolls (small screens, soft keyboard open): bring the button into view before tapping it.
        compose.onNodeWithTag("register_button").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Passwords don't match.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun authScreenDeviceLinkingEntryAndReturnToLogin() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("login_button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("auth_link_existing_device_button").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("device_link_back_to_login").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("device_link_back_to_login").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("login_button").fetchSemanticsNodes().isNotEmpty() }
    }
}
