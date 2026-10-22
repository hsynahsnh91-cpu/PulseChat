package com.example.ui.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.common.appContainer
import com.example.ui.theme.Spacing
import kotlinx.coroutines.flow.flowOf

/**
 * The phone's own screen-lock check: fingerprint (or another strong biometric) or the PIN, pattern
 * or password, shown by Android itself. PulseChat never sees or stores any of it.
 */
object DeviceAuth {
    enum class Result { SUCCESS, CANCELLED, UNAVAILABLE }

    /** False when the phone has no screen lock at all: there is nothing to check against. */
    fun isAvailable(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun authenticate(activity: Activity, title: String, subtitle: String, legacyLauncher: (Intent) -> Unit, onResult: (Result) -> Unit) {
        if (!isAvailable(activity)) { onResult(Result.UNAVAILABLE); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            runCatching {
                builder.build().authenticate(CancellationSignal(), ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onResult(Result.SUCCESS) }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { onResult(Result.CANCELLED) }
                })
            }.onFailure { onResult(Result.CANCELLED) }
        } else {
            // Android 7-9: the system's confirm-credential screen.
            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) onResult(Result.UNAVAILABLE) else legacyLauncher(intent)
        }
    }
}

fun interface DeviceAuthLauncher {
    fun authenticate(title: String, subtitle: String, onResult: (DeviceAuth.Result) -> Unit)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Starts [DeviceAuth] from a composable (including the Android 7-9 confirm-credential screen). */
@Composable
fun rememberDeviceAuth(): DeviceAuthLauncher {
    val context = LocalContext.current
    val container = appContainer()
    var pending by remember { mutableStateOf<((DeviceAuth.Result) -> Unit)?>(null) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pending
        pending = null
        callback?.invoke(if (result.resultCode == Activity.RESULT_OK) DeviceAuth.Result.SUCCESS else DeviceAuth.Result.CANCELLED)
    }
    return remember(context) {
        DeviceAuthLauncher { title, subtitle, onResult ->
            val activity = context.findActivity()
            if (activity == null) { onResult(DeviceAuth.Result.UNAVAILABLE); return@DeviceAuthLauncher }
            // Coming back from the system's check must not trigger the app lock.
            container.appLock.onExternalActivityLaunched()
            DeviceAuth.authenticate(activity, title, subtitle, legacyLauncher = { intent -> pending = onResult; legacy.launch(intent) }, onResult = onResult)
        }
    }
}

/**
 * Shows [content] only for chats that are not locked, or after the screen-lock check succeeded in
 * this session. Every way into a chat's content (chat list, notifications, search results,
 * reminders, info and media screens) passes through here, so nothing is composed - no messages
 * loaded, no read receipts sent - before the check. [conversationId] null guards the Locked chats list.
 */
@Composable
fun ChatLockGuard(conversationId: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val lockedFlow = remember(conversationId) { if (conversationId == null) flowOf(true) else container.chatSettings.observeIsLocked(conversationId) }
    val locked by lockedFlow.collectAsState(initial = null)
    val open by container.chatSettings.lockedChatsOpen.collectAsState()
    val auth = rememberDeviceAuth()
    var unavailable by remember { mutableStateOf(false) }
    val title = stringResource(if (conversationId == null) R.string.chat_locked_list_title else R.string.chat_lock_prompt_title)
    val subtitle = stringResource(R.string.chat_lock_prompt_subtitle)
    ChatLockGate(
        locked = locked,
        open = open,
        deviceSecure = !unavailable && DeviceAuth.isAvailable(context),
        forList = conversationId == null,
        onUnlock = {
            auth.authenticate(title, subtitle) { result ->
                when (result) {
                    DeviceAuth.Result.SUCCESS -> container.chatSettings.markLockedChatsOpen()
                    DeviceAuth.Result.UNAVAILABLE -> unavailable = true
                    DeviceAuth.Result.CANCELLED -> Unit
                }
            }
        },
        onBack = onBack,
        onOpenSecuritySettings = {
            container.appLock.onExternalActivityLaunched()
            runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        content = content,
    )
}

/** Pure UI of [ChatLockGuard] (testable without a phone lock). */
@Composable
fun ChatLockGate(
    locked: Boolean?,
    open: Boolean,
    deviceSecure: Boolean,
    forList: Boolean,
    onUnlock: () -> Unit,
    onBack: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        // Not known yet: show nothing rather than a flash of the chat.
        locked == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        !locked || open -> content()
        else -> {
            BackHandler(onBack = onBack)
            // Ask right away; the button stays for a second try after cancelling.
            LaunchedEffect(Unit) { if (deviceSecure) onUnlock() }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().testTag("chat_lock_gate"), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(Spacing.xxxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
                    }
                    Text(
                        stringResource(if (forList) R.string.chat_locked_list_title else R.string.chat_locked_title),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(if (!deviceSecure) R.string.chat_lock_no_screen_lock else if (forList) R.string.chat_locked_list_body else R.string.chat_locked_body),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    if (deviceSecure) {
                        Button(onClick = onUnlock, modifier = Modifier.testTag("chat_lock_unlock")) { Text(stringResource(R.string.chat_lock_unlock)) }
                    } else {
                        Button(onClick = onOpenSecuritySettings) { Text(stringResource(R.string.chat_lock_open_settings)) }
                    }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                }
            }
        }
    }
}

/** Explains what locking does (and does not do) before the screen-lock check. */
@Composable
fun LockChatDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.chat_lock_confirm_title)) },
        text = { Text(stringResource(R.string.chat_lock_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("chat_lock_confirm")) { Text(stringResource(R.string.chat_lock_action), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The phone has no screen lock, so chats cannot be locked yet. */
@Composable
fun NoScreenLockDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.chat_lock_add)) },
        text = { Text(stringResource(R.string.chat_lock_no_screen_lock)) },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.chat_lock_open_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
