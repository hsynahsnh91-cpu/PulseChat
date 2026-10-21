package com.example.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import com.example.R
import com.example.security.AppLockManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen lock shown over the app. With a PIN: number pad (+ fingerprint/face when enabled).
 * Without a PIN (locks turned on before PINs existed): the phone's own screen lock.
 */
@Composable
fun LockScreen(
    hasPin: Boolean,
    biometric: Boolean,
    onCheckPin: suspend (String) -> AppLockManager.PinResult,
    initialWaitSeconds: Int,
    onUnlocked: () -> Unit,
    onBiometric: () -> Unit,
    onDeviceCredential: () -> Unit,
    onForgotPin: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var wait by remember { mutableIntStateOf(initialWaitSeconds) }
    var forgot by remember { mutableStateOf(false) }

    // Offer the quick method once the screen is really visible (prompts fail while the activity starts).
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
            when {
                !hasPin -> onDeviceCredential()
                biometric -> onBiometric()
                else -> Unit
            }
        }
    }
    LaunchedEffect(wait) {
        if (wait > 0) {
            error = context.getString(R.string.app_lock_too_many, wait)
            delay(1_000)
            wait -= 1
            if (wait == 0) error = null
        }
    }

    fun submit() {
        if (checking || wait > 0 || !AppLockManager.isValidPin(pin)) return
        checking = true
        val attempt = pin
        scope.launch {
            when (val r = onCheckPin(attempt)) {
                AppLockManager.PinResult.Ok -> onUnlocked()
                AppLockManager.PinResult.Wrong -> error = context.getString(R.string.app_lock_wrong_pin)
                is AppLockManager.PinResult.Wait -> wait = r.seconds
            }
            pin = ""
            checking = false
        }
    }

    Surface(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }.testTag("app_lock_screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(if (hasPin) R.string.app_lock_enter_pin else R.string.app_lock_locked_body),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hasPin) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDeviceCredential) { Text(stringResource(R.string.app_lock_unlock)) }
            } else {
                Spacer(Modifier.height(20.dp))
                PinDots(pin.length)
                Text(error ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp).testTag("app_lock_error"))
                Spacer(Modifier.height(8.dp))
                NumberPad(
                    enabled = !checking && wait == 0,
                    onDigit = { d -> if (pin.length < AppLockManager.PIN_MAX) { pin += d; if (wait == 0) error = null } },
                    onBackspace = { pin = pin.dropLast(1) },
                    onSubmit = { submit() },
                    submitEnabled = AppLockManager.isValidPin(pin),
                )
                if (biometric) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onBiometric) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.app_lock_use_biometric))
                    }
                }
                TextButton(onClick = { forgot = true }) { Text(stringResource(R.string.app_lock_forgot)) }
            }
        }
    }
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text(stringResource(R.string.app_lock_forgot_title)) },
            text = { Text(stringResource(R.string.app_lock_forgot_body)) },
            confirmButton = { TextButton(onClick = { forgot = false; onForgotPin() }) { Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PinDots(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(16.dp)) {
        repeat(maxOf(count, AppLockManager.PIN_MIN)) { i ->
            Box(
                Modifier.size(14.dp).clip(CircleShape).background(
                    if (i < count) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
    }
}

@Composable
private fun NumberPad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit, submitEnabled: Boolean) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { row.forEach { d -> DigitKey(d, enabled, onDigit) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBackspace, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.action_delete))
                }
            }
            DigitKey('0', enabled, onDigit)
            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                FilledTonalIconButton(onClick = onSubmit, enabled = enabled && submitEnabled, modifier = Modifier.size(56.dp).testTag("app_lock_submit")) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.app_lock_unlock))
                }
            }
        }
    }
}

@Composable
private fun DigitKey(d: Char, enabled: Boolean, onDigit: (Char) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(68.dp).clip(CircleShape).clickable(enabled = enabled) { onDigit(d) }.testTag("pin_key_$d"),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(d.toString(), fontSize = 26.sp, fontWeight = FontWeight.Medium) }
    }
}

/** Two-step dialog that asks for a new PIN and its confirmation. */
@Composable
fun PinSetupDialog(title: String, onDismiss: () -> Unit, onPinChosen: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(stringResource(if (first == null) R.string.app_lock_set_pin_body else R.string.app_lock_confirm_pin))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> value = v.filter { it in '0'..'9' }.take(AppLockManager.PIN_MAX); error = null },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("pin_input"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    !AppLockManager.isValidPin(value) -> error = context.getString(R.string.app_lock_pin_short)
                    first == null -> { first = value; value = "" }
                    first != value -> { error = context.getString(R.string.app_lock_pin_mismatch); first = null; value = "" }
                    else -> onPinChosen(value)
                }
            }, modifier = Modifier.testTag("pin_next")) { Text(stringResource(if (first == null) R.string.action_continue else R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Asks for the current PIN before turning the lock off or changing the PIN. */
@Composable
fun PinVerifyDialog(onDismiss: () -> Unit, onCheckPin: suspend (String) -> AppLockManager.PinResult, onVerified: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_lock_current_pin)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { v -> value = v.filter { it in '0'..'9' }.take(AppLockManager.PIN_MAX); error = null },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = !checking && AppLockManager.isValidPin(value), onClick = {
                checking = true
                val attempt = value
                scope.launch {
                    when (val r = onCheckPin(attempt)) {
                        AppLockManager.PinResult.Ok -> onVerified()
                        AppLockManager.PinResult.Wrong -> { error = context.getString(R.string.app_lock_wrong_pin); value = "" }
                        is AppLockManager.PinResult.Wait -> { error = context.getString(R.string.app_lock_too_many, r.seconds); value = "" }
                    }
                    checking = false
                }
            }) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
