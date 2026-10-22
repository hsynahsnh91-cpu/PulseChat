package com.example.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChatNotificationSettings
import com.example.domain.NotificationPolicy
import com.example.domain.NotificationPolicy.MuteOption

/** "Mute notifications for…": 1 hour, 8 hours, 1 day or until turned back on (this chat only). */
@Composable
fun MuteDialog(isGroup: Boolean, onSelect: (mutedUntil: Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mute_title)) },
        text = {
            Column {
                MuteOption.entries.forEach { option ->
                    Box(Modifier.testTag("mute_option_${option.name}")) {
                        RadioRow(muteLabel(option), selected = false, onSelect = { onSelect(NotificationPolicy.mutedUntil(option, System.currentTimeMillis())) })
                    }
                }
                Text(
                    stringResource(if (isGroup) R.string.mute_note_group else R.string.mute_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun muteLabel(option: MuteOption): String = stringResource(
    when (option) {
        MuteOption.ONE_HOUR -> R.string.duration_1h
        MuteOption.EIGHT_HOURS -> R.string.duration_8h
        MuteOption.ONE_DAY -> R.string.duration_1d
        MuteOption.UNTIL_TURNED_ON -> R.string.mute_until_on
    }
)

/** "Not muted", "Muted until Today, 18:30" or "Muted until you turn them back on". */
fun muteSummary(context: Context, mutedUntil: Long, now: Long = System.currentTimeMillis()): String = when {
    mutedUntil <= now -> context.getString(R.string.info_not_muted)
    mutedUntil == ChatNotificationSettings.MUTED_FOREVER -> context.getString(R.string.muted_forever)
    else -> context.getString(R.string.muted_until, Formatters.dayAndTime(context, mutedUntil))
}

fun previewSummary(context: Context, preview: String, appPreviews: Boolean): String = when (preview) {
    ChatNotificationSettings.PREVIEW_SHOW -> context.getString(R.string.notif_preview_show)
    ChatNotificationSettings.PREVIEW_HIDE -> context.getString(R.string.notif_preview_hide)
    else -> context.getString(R.string.notif_preview_default, context.getString(if (appPreviews) R.string.notif_preview_state_shown else R.string.notif_preview_state_hidden))
}

/** Message preview for this chat: follow the app setting, always show, or always hide the text. */
@Composable
fun PreviewDialog(current: String, appPreviews: Boolean, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ChoiceDialog(
        title = stringResource(R.string.notif_preview_title),
        options = listOf(
            ChatNotificationSettings.PREVIEW_DEFAULT to previewSummary(context, ChatNotificationSettings.PREVIEW_DEFAULT, appPreviews),
            ChatNotificationSettings.PREVIEW_SHOW to stringResource(R.string.notif_preview_show),
            ChatNotificationSettings.PREVIEW_HIDE to stringResource(R.string.notif_preview_hide),
        ),
        selected = current,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}
