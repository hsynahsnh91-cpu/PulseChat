package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.repository.AutoReplyManager
import com.example.ui.common.RadioRow
import com.example.ui.common.SectionHeader
import com.example.ui.common.SwitchItem

/** Away-message settings. AI-written replies are only available after explicit AI consent. */
@Composable
fun AutoReplySettingsDialog(autoReplyManager: AutoReplyManager, aiAllowed: Boolean, onDismiss: () -> Unit) {
    val enabled by autoReplyManager.isEnabled.collectAsState()
    val message by autoReplyManager.customMessage.collectAsState()
    val aiEnabled by autoReplyManager.isAiEnabled.collectAsState()
    val condition by autoReplyManager.condition.collectAsState()
    val scheduleOnly by autoReplyManager.isScheduleOnly.collectAsState()
    var draft by remember(message) { mutableStateOf(message) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_reply_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchItem(null, stringResource(R.string.auto_reply_enable), stringResource(R.string.auto_reply_enable_sub), enabled, onChange = autoReplyManager::setEnabled)
                OutlinedTextField(value = draft, onValueChange = { draft = it.take(500) }, label = { Text(stringResource(R.string.auto_reply_message)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                SectionHeader(stringResource(R.string.auto_reply_templates))
                AutoReplyManager.PRESET_TEMPLATES.forEach { t ->
                    Text(t, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable { draft = t }.padding(vertical = 6.dp))
                }
                SectionHeader(stringResource(R.string.auto_reply_who))
                RadioRow(stringResource(R.string.auto_reply_everyone), condition == AutoReplyManager.CONDITION_ALL, { autoReplyManager.setCondition(AutoReplyManager.CONDITION_ALL) })
                RadioRow(stringResource(R.string.auto_reply_contacts), condition == AutoReplyManager.CONDITION_CONTACTS_ONLY, { autoReplyManager.setCondition(AutoReplyManager.CONDITION_CONTACTS_ONLY) })
                RadioRow(stringResource(R.string.auto_reply_non_contacts), condition == AutoReplyManager.CONDITION_NON_CONTACTS, { autoReplyManager.setCondition(AutoReplyManager.CONDITION_NON_CONTACTS) })
                SwitchItem(null, stringResource(R.string.auto_reply_schedule), stringResource(R.string.auto_reply_schedule_sub), scheduleOnly, onChange = autoReplyManager::setScheduleOnly)
                SwitchItem(null, stringResource(R.string.auto_reply_ai), stringResource(if (aiAllowed) R.string.auto_reply_ai_sub else R.string.auto_reply_ai_needs_consent),
                    aiEnabled && aiAllowed, enabled = aiAllowed, onChange = autoReplyManager::setAiEnabled)
                Text(stringResource(R.string.auto_reply_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { autoReplyManager.setCustomMessage(draft); onDismiss() }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
