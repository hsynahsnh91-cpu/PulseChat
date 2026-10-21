package com.example.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.AppContainer
import com.example.PulseChatApplication
import com.example.R
import com.example.data.repository.AiError
import com.example.data.repository.AuthError
import com.example.data.repository.ChatError
import com.example.domain.FieldError
import com.example.domain.GroupError
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.ui.theme.PulseTheme

// ---------------------------------------------------------------------- DI helpers

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as PulseChatApplication).container

fun Context.appContainer(): AppContainer = (applicationContext as PulseChatApplication).container

/** Builds a ViewModel factory that receives the application container. */
inline fun <reified VM : ViewModel> containerFactory(crossinline create: (AppContainer) -> VM): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PulseChatApplication
            create(app.container)
        }
    }

// ---------------------------------------------------------------------- scaffolding pieces

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
        },
        actions = { actions() },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun Banner(text: String, icon: ImageVector, container: Color, content: Color, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(color = container, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = content, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel, color = content, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: (checked: Boolean) -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    checkboxLabel: String? = null,
    checkboxDefault: Boolean = false,
) {
    var checked by remember { mutableStateOf(checkboxDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (text != null) Text(text)
                if (checkboxLabel != null) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it }).padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(checkboxLabel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked) }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsItem(icon: ImageVector?, title: String, subtitle: String? = null, enabled: Boolean = true, titleColor: Color = Color.Unspecified, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (!enabled) MaterialTheme.colorScheme.outline else titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchItem(icon: ImageVector?, title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Single list dialog used for simple choices (mute duration, disappearing timer, visibility...). */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { options.forEach { (value, label) -> RadioRow(label, value == selected, onSelect = { onSelect(value) }) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

// ---------------------------------------------------------------------- chat bits

@Composable
fun DeliveryTicks(status: MessageStatus, modifier: Modifier = Modifier, size: Dp = 16.dp, showRead: Boolean = true) {
    val c = PulseTheme.colors
    val (icon, tint, desc) = when (status) {
        MessageStatus.PENDING -> Triple(Icons.Default.Schedule, c.metaText, R.string.status_pending)
        MessageStatus.SENDING -> Triple(Icons.Default.Schedule, c.metaText, R.string.status_sending)
        MessageStatus.SENT -> Triple(Icons.Default.Check, c.metaText, R.string.status_sent)
        MessageStatus.DELIVERED -> Triple(Icons.Default.DoneAll, c.metaText, R.string.status_delivered)
        MessageStatus.READ -> if (showRead) Triple(Icons.Default.DoneAll, c.readTick, R.string.status_read) else Triple(Icons.Default.DoneAll, c.metaText, R.string.status_delivered)
        MessageStatus.FAILED -> Triple(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, R.string.status_failed)
    }
    Icon(icon, contentDescription = stringResource(desc), tint = tint, modifier = modifier.size(size))
}

@Composable
fun typeLabel(type: MessageType): String? = when (type) {
    MessageType.IMAGE -> stringResource(R.string.preview_photo)
    MessageType.VIDEO -> stringResource(R.string.preview_video)
    MessageType.AUDIO -> stringResource(R.string.preview_voice)
    MessageType.DOCUMENT -> stringResource(R.string.preview_document)
    MessageType.LOCATION -> stringResource(R.string.preview_location)
    MessageType.CONTACT -> stringResource(R.string.preview_contact)
    MessageType.POLL -> stringResource(R.string.preview_poll)
    MessageType.CALL_EVENT -> stringResource(R.string.preview_call)
    MessageType.SYSTEM -> stringResource(R.string.preview_group_update)
    else -> null
}

/** Chat-list style preview: localized type label + raw content. */
@Composable
fun previewLine(type: MessageType?, text: String?, deleted: Boolean): String {
    if (deleted) return stringResource(R.string.message_deleted)
    val label = type?.let { typeLabel(it) }
    val body = text.orEmpty()
    return when {
        label == null -> body
        type == MessageType.SYSTEM -> label
        body.isBlank() -> label
        else -> "$label · $body"
    }
}

@Composable
fun AiBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(stringResource(R.string.ai_label), fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
    }
}

/** Explicit consent before any content is sent to Gemini. */
@Composable
fun AiConsentDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
        title = { Text(stringResource(R.string.ai_consent_title)) },
        text = { Text(stringResource(R.string.ai_consent_body)) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.ai_consent_accept), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.ai_consent_decline)) } },
    )
}

// ---------------------------------------------------------------------- error texts

fun chatErrorText(context: Context, e: ChatError): String = context.getString(
    when (e) {
        ChatError.NOT_SIGNED_IN -> R.string.error_not_signed_in
        ChatError.NOT_ALLOWED -> R.string.error_not_allowed
        ChatError.BLOCKED_CONTACT -> R.string.error_blocked_contact
        ChatError.LOCAL_ONLY -> R.string.error_local_only
        ChatError.LEFT_GROUP -> R.string.error_left_group
        ChatError.EMPTY -> R.string.error_empty
        ChatError.TOO_LONG -> R.string.error_too_long
        ChatError.TOO_LARGE -> R.string.error_too_large
        ChatError.EDIT_WINDOW_PASSED -> R.string.error_edit_window
        ChatError.DELETE_WINDOW_PASSED -> R.string.error_delete_window
        ChatError.PIN_LIMIT -> R.string.error_pin_limit
        ChatError.EXIT_GROUP_FIRST -> R.string.error_exit_group_first
        ChatError.FILE_UNREADABLE -> R.string.error_file_unreadable
        ChatError.SEND_FAILED -> R.string.error_send_failed
        ChatError.UPDATE_FAILED -> R.string.error_update_failed
        ChatError.GROUP_INVALID -> R.string.error_group_invalid
        ChatError.NOT_FOUND -> R.string.error_not_found
    }
)

fun authErrorText(context: Context, e: AuthError, field: FieldError?): String = when {
    field != null && e == AuthError.INVALID_INPUT -> fieldErrorText(context, field)
    else -> context.getString(
        when (e) {
            AuthError.INVALID_CREDENTIALS -> R.string.auth_error_credentials
            AuthError.USERNAME_TAKEN -> R.string.auth_error_username_taken
            AuthError.WEAK_PASSWORD -> R.string.auth_error_weak_password
            AuthError.NETWORK -> R.string.error_network
            AuthError.TOO_MANY_REQUESTS -> R.string.auth_error_too_many
            AuthError.PROVIDER_DISABLED -> R.string.auth_error_provider_disabled
            AuthError.CANCELLED -> R.string.auth_error_cancelled
            AuthError.NO_GOOGLE_ACCOUNT -> R.string.auth_error_no_google
            AuthError.BACKEND_NOT_READY -> R.string.error_backend_not_ready
            AuthError.LEGACY_PASSWORD_TOO_SHORT -> R.string.auth_error_legacy_password
            AuthError.INVALID_INPUT -> R.string.error_empty
            AuthError.UNKNOWN -> R.string.error_unknown
        }
    )
}

fun fieldErrorText(context: Context, e: FieldError): String = context.getString(
    when (e) {
        FieldError.EMPTY -> R.string.field_error_empty
        FieldError.TOO_SHORT -> R.string.field_error_too_short
        FieldError.TOO_LONG -> R.string.field_error_too_long
        FieldError.INVALID_CHARS -> R.string.field_error_invalid_chars
        FieldError.MUST_START_WITH_LETTER -> R.string.field_error_start_letter
        FieldError.WEAK -> R.string.field_error_weak
        FieldError.MISMATCH -> R.string.field_error_mismatch
    }
)

fun aiErrorText(context: Context, e: AiError): String = context.getString(
    when (e) {
        AiError.CONSENT_REQUIRED -> R.string.ai_error_consent
        AiError.RATE_LIMITED -> R.string.ai_error_rate_limited
        AiError.NOT_CONFIGURED -> R.string.ai_error_not_configured
        AiError.BLOCKED -> R.string.ai_error_blocked
        AiError.NETWORK -> R.string.error_network
        AiError.TIMEOUT -> R.string.ai_error_timeout
        AiError.EMPTY -> R.string.ai_error_empty
        AiError.UNKNOWN -> R.string.error_unknown
        AiError.TOO_LARGE -> R.string.ai_error_too_large
        AiError.BAD_RESPONSE -> R.string.ai_error_bad_response
    }
)

fun groupErrorText(context: Context, errors: List<GroupError>): String = errors.joinToString("\n") { e ->
    context.getString(
        when (e) {
            GroupError.NAME_EMPTY -> R.string.group_error_name_empty
            GroupError.NAME_TOO_LONG -> R.string.group_error_name_long
            GroupError.DESCRIPTION_TOO_LONG -> R.string.group_error_description_long
            GroupError.NO_MEMBERS -> R.string.group_error_no_members
            GroupError.TOO_MANY_MEMBERS -> R.string.group_error_too_many
        }
    )
}
