package com.example.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ScheduledMessage
import com.example.data.model.ScheduledState
import com.example.domain.ReminderTimes
import com.example.domain.ScheduledPolicy
import com.example.ui.common.AiBadge
import com.example.ui.common.Formatters
import java.util.Calendar
import java.util.TimeZone

// ---------------------------------------------------------------------------- date & time

/** UTC midnight of the local calendar day containing [t] (what Material's DatePicker expects). */
private fun utcMidnightOfLocalDay(t: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = t }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Two steps: a date, then a time. Days before today can't be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(initial: Long, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val today = remember { utcMidnightOfLocalDay(System.currentTimeMillis()) }
    var pickedDate by remember { mutableStateOf<Long?>(null) }
    val date = pickedDate
    if (date == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = utcMidnightOfLocalDay(initial),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= today
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { pickedDate = state.selectedDateMillis ?: today }) { Text(stringResource(R.string.action_next)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    } else {
        val cal = remember { Calendar.getInstance().apply { timeInMillis = initial } }
        val state = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY), initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pick_time)) },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { onPicked(ReminderTimes.combine(date, state.hour, state.minute)) }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text(stringResource(R.string.action_back)) } },
        )
    }
}

@Composable
private fun presetLabel(p: ReminderTimes.Preset): String = stringResource(when (p) {
    ReminderTimes.Preset.IN_1_HOUR -> R.string.time_in_1_hour
    ReminderTimes.Preset.IN_3_HOURS -> R.string.time_in_3_hours
    ReminderTimes.Preset.THIS_EVENING -> R.string.time_this_evening
    ReminderTimes.Preset.TOMORROW_MORNING -> R.string.time_tomorrow_morning
    ReminderTimes.Preset.NEXT_WEEK -> R.string.time_next_week
})

// ---------------------------------------------------------------------------- scheduled messages

@Composable
fun ScheduleMessageDialog(
    initialText: String,
    initialAt: Long?,
    editing: Boolean,
    onConfirm: (text: String, at: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initialText) }
    var at by remember { mutableStateOf(initialAt ?: ReminderTimes.at(ReminderTimes.Preset.IN_1_HOUR, System.currentTimeMillis())!!) }
    var picking by remember { mutableStateOf(false) }
    val error = ScheduledPolicy.validate(text, at, System.currentTimeMillis())
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
        title = { Text(stringResource(if (editing) R.string.schedule_edit_title else R.string.schedule_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.schedule_message_label)) },
                    minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReminderTimes.presets(System.currentTimeMillis()).take(3).forEach { (p, t) ->
                        AssistChip(onClick = { at = t }, label = { Text(presetLabel(p)) })
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.schedule_send_at), style = MaterialTheme.typography.labelMedium)
                        Text(Formatters.dateTime(context, at), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = { picking = true }) { Text(stringResource(R.string.action_change)) }
                }
                if (error != null && text.isNotBlank()) {
                    Text(
                        stringResource(when (error) {
                            ScheduledPolicy.Error.TOO_SOON -> R.string.schedule_error_too_soon
                            ScheduledPolicy.Error.TOO_FAR -> R.string.schedule_error_too_far
                            ScheduledPolicy.Error.TOO_LONG -> R.string.error_too_long
                            ScheduledPolicy.Error.EMPTY -> R.string.error_empty
                        }),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(R.string.schedule_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text, at) }, enabled = error == null) {
                Text(stringResource(if (editing) R.string.action_save else R.string.schedule_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (picking) DateTimePickerDialog(at, onPicked = { at = it; picking = false }, onDismiss = { picking = false })
}

@Composable
fun scheduledErrorText(reason: String?): String = stringResource(when (reason) {
    "blocked_contact" -> R.string.schedule_fail_blocked
    "left_group" -> R.string.schedule_fail_left
    "not_allowed", "denied" -> R.string.schedule_fail_not_allowed
    "not_found", "local_only" -> R.string.schedule_fail_chat_gone
    "other_account" -> R.string.schedule_fail_account
    "too_many_attempts" -> R.string.schedule_fail_attempts
    else -> R.string.schedule_fail_generic
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesSheet(
    items: List<ScheduledMessage>,
    onEdit: (ScheduledMessage) -> Unit,
    onSendNow: (ScheduledMessage) -> Unit,
    onDelete: (ScheduledMessage) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.scheduled_messages), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (items.isEmpty()) {
                Text(stringResource(R.string.scheduled_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(items, key = { it.id }) { m ->
                    val sending = m.state == ScheduledState.SENDING
                    val failed = m.state == ScheduledState.FAILED
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (failed) Icons.Default.ErrorOutline else Icons.Default.Schedule, contentDescription = null,
                            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    sending -> stringResource(R.string.scheduled_sending)
                                    failed -> stringResource(R.string.scheduled_failed, scheduledErrorText(m.lastError))
                                    m.attempts > 0 -> stringResource(R.string.scheduled_waiting_retry, Formatters.dateTime(context, m.scheduledAt))
                                    else -> stringResource(R.string.scheduled_at, Formatters.dateTime(context, m.scheduledAt))
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onSendNow(m) }, enabled = !sending) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(if (failed) R.string.action_retry else R.string.scheduled_send_now))
                        }
                        IconButton(onClick = { onEdit(m) }, enabled = !sending) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit)) }
                        IconButton(onClick = { onDelete(m) }, enabled = !sending) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- reminders

@Composable
fun ReminderTimeDialog(title: String, onPick: (Long) -> Unit, onDismiss: () -> Unit, exactAllowed: Boolean, onAllowExact: (() -> Unit)?) {
    val context = LocalContext.current
    var custom by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                ReminderTimes.presets(now).forEach { (p, t) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(presetLabel(p), modifier = Modifier.weight(1f))
                        Text(Formatters.dateTime(context, t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { custom = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Event, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.time_pick_custom))
                }
                if (!exactAllowed) {
                    Text(stringResource(R.string.reminder_inexact_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onAllowExact != null) TextButton(onClick = onAllowExact) { Text(stringResource(R.string.reminder_allow_exact)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (custom) DateTimePickerDialog(now + 3_600_000L, onPicked = { custom = false; onPick(it) }, onDismiss = { custom = false })
}

// ---------------------------------------------------------------------------- translation & transcripts

data class TranslationUi(
    val loading: Boolean = false,
    val text: String? = null,
    val sourceLang: String? = null,
    val targetLang: String = "",
    val showOriginal: Boolean = false,
    val error: String? = null,
)

data class TranscriptUi(val loading: Boolean = false, val text: String? = null, val error: String? = null, val hidden: Boolean = false)

/** Shown under a message: the original stays in the bubble; this card holds the translation. */
@Composable
fun TranslationCard(
    ui: TranslationUi,
    languageName: (String) -> String,
    onToggleOriginal: () -> Unit,
    onChangeLanguage: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        ui.loading -> stringResource(R.string.translate_working)
                        ui.error != null -> stringResource(R.string.translate_failed_title)
                        ui.sourceLang != null -> stringResource(R.string.translate_from_to, languageName(ui.sourceLang), languageName(ui.targetLang))
                        else -> stringResource(R.string.translate_title)
                    },
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, stringResource(R.string.translate_hide), Modifier.size(16.dp)) }
            }
            when {
                ui.loading -> CircularProgressIndicator(Modifier.padding(4.dp).size(18.dp), strokeWidth = 2.dp)
                ui.error != null -> {
                    Text(ui.error, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                        TextButton(onClick = onChangeLanguage) { Text(stringResource(R.string.translate_change_language)) }
                    }
                }
                ui.text != null -> {
                    if (!ui.showOriginal) Text(ui.text, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = onToggleOriginal) { Text(stringResource(if (ui.showOriginal) R.string.translate_show_translation else R.string.translate_hide_translation)) }
                        TextButton(onClick = onChangeLanguage) { Text(stringResource(R.string.translate_change_language)) }
                    }
                }
            }
        }
    }
}

@Composable
fun TranscriptCard(ui: TranscriptUi, onRetry: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.transcript_title), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                AiBadge()
            }
            when {
                ui.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(4.dp).size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.transcript_working), style = MaterialTheme.typography.bodySmall)
                }
                ui.error != null -> {
                    Text(ui.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
                ui.text != null -> {
                    if (!ui.hidden) {
                        Text(
                            if (ui.text == NO_SPEECH) stringResource(R.string.transcript_no_speech) else ui.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = if (ui.text == NO_SPEECH) FontStyle.Italic else FontStyle.Normal,
                        )
                    }
                    TextButton(onClick = onToggle) { Text(stringResource(if (ui.hidden) R.string.transcript_show else R.string.transcript_hide)) }
                }
            }
        }
    }
}

const val NO_SPEECH = "[no speech]"

@Composable
fun LanguagePickerDialog(languages: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = languages.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) || it.first.startsWith(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_choose_language)) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.action_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.first }) { (tag, name) ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(tag) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, modifier = Modifier.weight(1f), fontWeight = if (tag == selected) FontWeight.Bold else FontWeight.Normal)
                            if (tag == selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---------------------------------------------------------------------------- AI: summary & tasks

data class SummaryPointUi(val text: String, val messageIds: List<String>)

data class SummaryUi(val busy: Boolean = false, val points: List<SummaryPointUi> = emptyList(), val coveredCount: Int = 0)

/** "N new messages since your last visit" with an explicit Summarize action. */
@Composable
fun NewSinceVisitBanner(count: Int, onSummarize: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(LocalContext.current.resources.getQuantityString(R.plurals.new_since_visit, count, count), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSummarize) { Text(stringResource(R.string.ai_summarize_new)) }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
        }
    }
}

@Composable
fun SummaryPanel(ui: SummaryUi, onJump: (String) -> Unit, onClose: () -> Unit) {
    Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 320.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_summary_since_visit), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AiBadge()
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
            }
            if (ui.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn {
                    items(ui.points) { p ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("• " + p.text, style = MaterialTheme.typography.bodyMedium)
                            if (p.messageIds.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                p.messageIds.forEachIndexed { i, id ->
                                    val label = stringResource(R.string.ai_source_message, i + 1)
                                    AssistChip(onClick = { onJump(id) }, label = { Text(label) }, modifier = Modifier.semantics { contentDescription = label })
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.ai_summary_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

data class ActionCandidateUi(
    val key: Int,
    val event: Boolean,
    val title: String,
    val at: Long?,
    val messageIds: List<String>,
    val checked: Boolean,
)

/** Suggested tasks/events. Nothing is created until the user confirms the checked items. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSuggestionsSheet(initial: List<ActionCandidateUi>, onJump: (String) -> Unit, onCreate: (List<ActionCandidateUi>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rows = remember(initial) { mutableStateListOf<ActionCandidateUi>().apply { addAll(initial) } }
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_tasks_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                AiBadge()
            }
            Text(stringResource(R.string.ai_tasks_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rows.isEmpty()) Text(stringResource(R.string.ai_tasks_none), modifier = Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows, key = { it.key }) { item ->
                    val index = rows.indexOfFirst { it.key == item.key }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(checked = item.checked, onCheckedChange = { c -> rows[index] = item.copy(checked = c) }, enabled = item.at != null)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (item.event) Icons.Default.Event else Icons.Default.TaskAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(if (item.event) R.string.ai_kind_event else R.string.ai_kind_task), style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedTextField(value = item.title, onValueChange = { t -> rows[index] = item.copy(title = t.take(200)) }, singleLine = false, maxLines = 3, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { pickingFor = item.key }) {
                                    Text(item.at?.let { Formatters.dateTime(context, it) } ?: stringResource(R.string.ai_tasks_pick_time))
                                }
                                item.messageIds.firstOrNull()?.let { id -> TextButton(onClick = { onJump(id) }) { Text(stringResource(R.string.ai_show_source)) } }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            val selected = rows.filter { it.checked && it.at != null && it.title.isNotBlank() }
            Button(onClick = { onCreate(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(LocalContext.current.resources.getQuantityString(R.plurals.ai_tasks_create, selected.size, selected.size))
            }
        }
    }
    pickingFor?.let { key ->
        val item = rows.firstOrNull { it.key == key }
        DateTimePickerDialog(item?.at ?: (System.currentTimeMillis() + 3_600_000L), onPicked = { t ->
            val i = rows.indexOfFirst { it.key == key }
            if (i >= 0) rows[i] = rows[i].copy(at = t, checked = true)
            pickingFor = null
        }, onDismiss = { pickingFor = null })
    }
}
