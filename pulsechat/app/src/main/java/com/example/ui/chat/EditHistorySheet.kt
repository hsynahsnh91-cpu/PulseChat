package com.example.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Message
import com.example.domain.EditHistory
import com.example.ui.common.Formatters
import com.example.ui.theme.Radii
import com.example.ui.theme.Spacing

/** Earlier versions of an edited message with the time of each version (newest first). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHistorySheet(message: Message, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        EditHistoryContent(message)
    }
}

@Composable
fun EditHistoryContent(message: Message) {
    val context = LocalContext.current
    val history = remember(message.editHistory) { EditHistory.parse(message.editHistory) }
    val versions = remember(message.content, message.createdAt, message.editedAt, history) {
        EditHistory.versions(message.content, message.createdAt, message.editedAt, history)
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl).testTag("edit_history"),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.md))
            Text(stringResource(R.string.edit_history_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        versions.forEachIndexed { index, v ->
            val label = when {
                v.current -> stringResource(R.string.edit_history_current)
                v.original -> stringResource(R.string.edit_history_original)
                else -> stringResource(R.string.edit_history_earlier)
            }
            val time = Formatters.dayAndTime(context, v.at)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.md))
                    .background(if (v.current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    .testTag("edit_version_$index"),
            ) {
                Text(
                    "$label · " + stringResource(if (v.original) R.string.edit_history_sent_at else R.string.edit_history_edited_at, time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(2.dp))
                Text(v.text, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
            }
        }
        if (history.isEmpty()) {
            Text(stringResource(R.string.edit_history_not_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.edit_history_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
