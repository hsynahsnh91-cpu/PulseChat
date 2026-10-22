package com.example.ui.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.AppContainer
import com.example.R
import com.example.data.model.Reminder
import com.example.data.model.ReminderSource
import com.example.ui.chat.DateTimePickerDialog
import com.example.ui.common.BackTopBar
import com.example.ui.common.Banner
import com.example.ui.common.EmptyState
import com.example.ui.common.Formatters
import com.example.ui.common.LoadingBox
import com.example.ui.common.SectionHeader
import com.example.ui.common.containerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RemindersViewModel(private val c: AppContainer) : ViewModel() {
    val upcoming: StateFlow<List<Reminder>?> = c.reminders.observeUpcoming().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val past: StateFlow<List<Reminder>> = c.reminders.observePast().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val exactAllowed = MutableStateFlow(c.reminders.scheduler.canScheduleExact())

    fun refresh() {
        val before = exactAllowed.value
        exactAllowed.value = c.reminders.scheduler.canScheduleExact()
        // Just granted: re-arm everything as exact alarms.
        if (!before && exactAllowed.value) viewModelScope.launch { c.reminders.rescheduleAll() }
    }

    fun done(id: String) = viewModelScope.launch { c.reminders.markDone(id) }
    fun delete(id: String) = viewModelScope.launch { c.reminders.delete(id) }
    fun reschedule(id: String, at: Long) = viewModelScope.launch { c.reminders.reschedule(id, at) }
}

/** Opens the system screen where the user can allow exact alarms (Android 12+). */
fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 31) return
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun RemindersScreen(onBack: () -> Unit, openMessage: (conversationId: String, messageId: String?) -> Unit) {
    val vm: RemindersViewModel = viewModel(factory = containerFactory { RemindersViewModel(it) })
    val context = LocalContext.current
    val upcoming by vm.upcoming.collectAsState()
    val past by vm.past.collectAsState()
    val exact by vm.exactAllowed.collectAsState()
    var editing by remember { mutableStateOf<Reminder?>(null) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.reminders_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!exact) Banner(
                stringResource(R.string.reminder_inexact_note), Icons.Rounded.AlarmOn,
                MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                actionLabel = stringResource(R.string.reminder_allow_exact), onAction = { openExactAlarmSettings(context) },
            )
            val list = upcoming
            when {
                list == null -> LoadingBox()
                list.isEmpty() && past.isEmpty() -> EmptyState(Icons.Rounded.NotificationsActive, stringResource(R.string.reminders_empty_title), stringResource(R.string.reminders_empty_body))
                else -> LazyColumn {
                    item { SectionHeader(stringResource(R.string.reminders_upcoming)) }
                    if (list.isEmpty()) item { Text(stringResource(R.string.reminders_none_upcoming), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
                    items(list, key = { it.id }) { r -> ReminderRow(r, onOpen = { r.conversationId?.let { openMessage(it, r.messageId) } }, onEdit = { editing = r }, onDone = { vm.done(r.id) }, onDelete = { vm.delete(r.id) }) }
                    if (past.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.reminders_earlier)) }
                        items(past, key = { "p_" + it.id }) { r -> ReminderRow(r, onOpen = { r.conversationId?.let { openMessage(it, r.messageId) } }, onEdit = { editing = r }, onDone = null, onDelete = { vm.delete(r.id) }) }
                    }
                }
            }
        }
    }
    editing?.let { r ->
        DateTimePickerDialog(maxOf(r.remindAt, System.currentTimeMillis() + 3_600_000L), onPicked = { vm.reschedule(r.id, it); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun ReminderRow(r: Reminder, onOpen: () -> Unit, onEdit: () -> Unit, onDone: (() -> Unit)?, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = r.conversationId != null, onClick = onOpen).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (r.source == ReminderSource.MESSAGE) Icons.Rounded.NotificationsActive else Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(Formatters.dateTime(context, r.remindAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.reminder_change_time)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
            if (onDone != null) DropdownMenuItem(text = { Text(stringResource(R.string.reminder_done)) }, leadingIcon = { Icon(Icons.Rounded.Check, null) }, onClick = { menu = false; onDone() })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
        }
    }
    HorizontalDivider()
}
