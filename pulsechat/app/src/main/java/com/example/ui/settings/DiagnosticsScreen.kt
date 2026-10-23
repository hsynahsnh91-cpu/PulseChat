package com.example.ui.settings

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.AppContainer
import com.example.BuildConfig
import com.example.R
import com.example.calls.CallSummary
import com.example.calls.EchoCancellerMode
import com.example.data.model.DiagnosticEvent
import com.example.data.model.ScheduledState
import com.example.data.remote.Fs
import com.example.data.repository.SyncState
import com.example.domain.NetworkKind
import com.example.notifications.NotificationHelper
import com.example.ui.chat.copyToClipboard
import com.example.ui.common.BackTopBar
import com.example.ui.common.ChoiceDialog
import com.example.ui.common.Formatters
import com.example.ui.common.SectionHeader
import com.example.ui.common.SwitchItem
import com.example.ui.common.containerFactory
import com.example.ui.reminders.openExactAlarmSettings
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class NotificationHealth(
    val appEnabled: Boolean = true,
    val permission: Boolean = true,
    val channelsBlocked: List<String> = emptyList(),
    val pushRegistered: Boolean = false,
    val exactAlarms: Boolean = true,
    val batteryUnrestricted: Boolean = true,
    val backgroundRestricted: Boolean = false,
    val doNotDisturb: Boolean = false,
    val micPermission: Boolean = true,
    val cameraPermission: Boolean = true,
)

sealed class ServerCheck {
    object Idle : ServerCheck()
    object Running : ServerCheck()
    data class Ok(val ms: Long) : ServerCheck()
    data class Failed(val reason: String) : ServerCheck()
}

class DiagnosticsViewModel(private val c: AppContainer) : ViewModel() {
    private val me = c.authRepository.currentUid.orEmpty()
    private fun <T> Flowish(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> = flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val network: StateFlow<NetworkKind> = c.connectivity.network
    val sync = c.syncManager.status
    val settings = c.prefs.settings
    val lastCall: StateFlow<CallSummary?> = c.callDiagnostics.lastCall
    val pending = Flowish(c.db.messageDao().observeOwnCount(me, "PENDING"), 0)
    val sending = Flowish(c.db.messageDao().observeOwnCount(me, "SENDING"), 0)
    val failed = Flowish(c.db.messageDao().observeOwnCount(me, "FAILED"), 0)
    val failedUploads = Flowish(c.db.messageDao().observeFailedUploads(), 0)
    val scheduledPending = Flowish(c.scheduledMessages.observeCount(ScheduledState.PENDING), 0)
    val scheduledFailed = Flowish(c.scheduledMessages.observeCount(ScheduledState.FAILED), 0)
    val remindersScheduled = Flowish(c.reminders.observeScheduledCount(), 0)
    val problems: StateFlow<List<DiagnosticEvent>> = Flowish(if (me.isEmpty()) flowOf(emptyList()) else c.diagnosticsLog.observeRecent(50), emptyList())
    val health = MutableStateFlow(NotificationHealth())
    val server = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val lastSync = MutableStateFlow(c.prefs.lastServerSyncAt)

    fun refresh() {
        val ctx = c.app
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val compat = NotificationManagerCompat.from(ctx)
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val blocked = if (Build.VERSION.SDK_INT >= 26) listOf(
            NotificationHelper.CH_MESSAGES, NotificationHelper.CH_GROUPS, NotificationHelper.CH_CALLS, NotificationHelper.CH_REMINDERS,
        ).filter { id -> nm.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE } else emptyList()
        val power = ctx.getSystemService(PowerManager::class.java)
        val activity = ctx.getSystemService(ActivityManager::class.java)
        health.value = NotificationHealth(
            appEnabled = compat.areNotificationsEnabled(),
            permission = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            channelsBlocked = blocked,
            pushRegistered = c.prefs.registeredPushToken != null,
            exactAlarms = c.reminders.scheduler.canScheduleExact(),
            batteryUnrestricted = power?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true,
            backgroundRestricted = Build.VERSION.SDK_INT >= 28 && activity?.isBackgroundRestricted == true,
            doNotDisturb = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
            micPermission = granted(Manifest.permission.RECORD_AUDIO),
            cameraPermission = granted(Manifest.permission.CAMERA),
        )
        lastSync.value = c.prefs.lastServerSyncAt
    }

    /** Reads this account's profile straight from the server (never from the cache) and times it. */
    fun checkServer() { viewModelScope.launch { checkServerNow() } }

    private suspend fun checkServerNow() {
        if (me.isEmpty()) { server.value = ServerCheck.Failed("not_signed_in"); return }
        server.value = ServerCheck.Running
        val start = SystemClock.elapsedRealtime()
        server.value = try {
            val ok = withTimeoutOrNull(12_000) { c.firestore.collection(Fs.USERS).document(me).get(Source.SERVER).await(); true }
            if (ok == true) ServerCheck.Ok(SystemClock.elapsedRealtime() - start) else ServerCheck.Failed("timeout")
        } catch (e: FirebaseFirestoreException) {
            ServerCheck.Failed(when (e.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE -> "offline"
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> "denied"
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> "not_signed_in"
                else -> e.code.name.lowercase()
            })
        } catch (e: Exception) {
            ServerCheck.Failed("error")
        }
        lastSync.value = c.prefs.lastServerSyncAt
    }

    fun retryFailedMessages() { viewModelScope.launch { runCatching { c.chatRepository.resendPending() } } }
    fun clearProblems() { viewModelScope.launch { c.diagnosticsLog.clear() } }
    fun setEchoMode(mode: EchoCancellerMode) = c.prefs.setCallEchoMode(mode)
    fun setAutoGain(on: Boolean) = c.prefs.setCallAutoGain(on)
    fun setShowCallDetails(on: Boolean) = c.prefs.setCallShowDetails(on)
    fun setVoiceIsolation(on: Boolean) = c.prefs.setCallVoiceIsolation(on)
}

private fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openBatterySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** User-facing check-up: connection, sync, notifications, calls and recent problems in plain words. */
@Composable
fun DiagnosticsScreen(nav: SettingsNav) {
    val vm: DiagnosticsViewModel = viewModel(factory = containerFactory { DiagnosticsViewModel(it) })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val network by vm.network.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lastCall by vm.lastCall.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val failedUploads by vm.failedUploads.collectAsStateWithLifecycle()
    val scheduledPending by vm.scheduledPending.collectAsStateWithLifecycle()
    val scheduledFailed by vm.scheduledFailed.collectAsStateWithLifecycle()
    val reminders by vm.remindersScheduled.collectAsStateWithLifecycle()
    val problems by vm.problems.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    var echoDialog by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.diag_title), nav.back) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.diag_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))

            // ---------------------------------------------------------------- connection
            SectionHeader(stringResource(R.string.diag_section_connection))
            CheckRow(
                ok = network != NetworkKind.NONE,
                title = stringResource(R.string.diag_network),
                detail = stringResource(when (network) {
                    NetworkKind.NONE -> R.string.diag_network_none
                    NetworkKind.WIFI -> R.string.diag_network_wifi
                    NetworkKind.CELLULAR -> R.string.diag_network_cellular
                    NetworkKind.ROAMING -> R.string.diag_network_roaming
                }),
            )
            CheckRow(
                ok = when (server) { is ServerCheck.Ok -> true; is ServerCheck.Failed -> false; else -> null },
                title = stringResource(R.string.diag_server),
                detail = when (val s = server) {
                    ServerCheck.Idle -> stringResource(R.string.diag_server_idle)
                    ServerCheck.Running -> stringResource(R.string.diag_server_running)
                    is ServerCheck.Ok -> stringResource(R.string.diag_server_ok, s.ms.toInt())
                    is ServerCheck.Failed -> stringResource(when (s.reason) {
                        "offline" -> R.string.diag_server_offline
                        "denied" -> R.string.diag_server_denied
                        "timeout" -> R.string.diag_server_timeout
                        "not_signed_in" -> R.string.error_not_signed_in
                        else -> R.string.diag_server_error
                    })
                },
                busy = server == ServerCheck.Running,
                action = stringResource(R.string.diag_run_check) to { vm.checkServer() },
            )

            // ---------------------------------------------------------------- sync
            SectionHeader(stringResource(R.string.diag_section_sync))
            CheckRow(
                ok = when (sync.state) { SyncState.LIVE -> true; SyncState.ERROR -> false; else -> null },
                title = stringResource(R.string.diag_live_updates),
                detail = stringResource(when (sync.state) {
                    SyncState.LIVE -> R.string.diag_sync_live
                    SyncState.CONNECTING -> R.string.diag_sync_connecting
                    SyncState.ERROR -> if (sync.backendNotReady) R.string.diag_sync_denied else R.string.diag_sync_error
                    SyncState.STOPPED -> R.string.diag_sync_stopped
                }) + if (lastSync > 0) "\n" + stringResource(R.string.diag_last_sync, Formatters.dateTime(context, lastSync)) else "",
            )
            CheckRow(
                ok = failed == 0 && failedUploads == 0,
                title = stringResource(R.string.diag_outbox),
                detail = stringResource(R.string.diag_outbox_detail, pending + sending, failed, failedUploads),
                action = if (failed + pending + sending > 0) stringResource(R.string.diag_retry_now) to { vm.retryFailedMessages() } else null,
            )
            CheckRow(
                ok = scheduledFailed == 0,
                title = stringResource(R.string.scheduled_messages),
                detail = stringResource(R.string.diag_scheduled_detail, scheduledPending, scheduledFailed),
            )

            // ---------------------------------------------------------------- notifications
            SectionHeader(stringResource(R.string.diag_section_notifications))
            CheckRow(
                ok = health.appEnabled && health.permission && health.channelsBlocked.isEmpty(),
                title = stringResource(R.string.diag_notifications),
                detail = when {
                    !health.permission -> stringResource(R.string.diag_notif_permission)
                    !health.appEnabled -> stringResource(R.string.diag_notif_off)
                    health.channelsBlocked.isNotEmpty() -> stringResource(R.string.diag_notif_channels, health.channelsBlocked.joinToString { channelName(context, it) })
                    else -> stringResource(R.string.diag_notif_ok)
                } + if (health.doNotDisturb) "\n" + stringResource(R.string.diag_dnd) else "",
                action = stringResource(R.string.diag_open_settings) to { openAppNotificationSettings(context) },
            )
            CheckRow(
                ok = health.pushRegistered,
                title = stringResource(R.string.diag_push),
                detail = stringResource(if (health.pushRegistered) R.string.diag_push_ok else R.string.diag_push_missing),
            )
            CheckRow(
                ok = health.exactAlarms,
                title = stringResource(R.string.diag_exact_alarms),
                detail = stringResource(if (health.exactAlarms) R.string.diag_exact_ok else R.string.reminder_inexact_note) +
                    "\n" + context.resources.getQuantityString(R.plurals.diag_reminders_count, reminders, reminders),
                action = if (!health.exactAlarms && Build.VERSION.SDK_INT >= 31) stringResource(R.string.reminder_allow_exact) to { openExactAlarmSettings(context) } else null,
            )
            CheckRow(
                ok = health.batteryUnrestricted && !health.backgroundRestricted,
                title = stringResource(R.string.diag_battery),
                detail = stringResource(when {
                    health.backgroundRestricted -> R.string.diag_battery_restricted
                    !health.batteryUnrestricted -> R.string.diag_battery_optimized
                    else -> R.string.diag_battery_ok
                }),
                action = if (!health.batteryUnrestricted) stringResource(R.string.diag_open_settings) to { openBatterySettings(context) } else null,
            )

            // ---------------------------------------------------------------- calls
            SectionHeader(stringResource(R.string.diag_section_calls))
            CheckRow(
                ok = health.micPermission,
                title = stringResource(R.string.diag_call_permissions),
                detail = stringResource(if (health.micPermission) R.string.diag_mic_ok else R.string.diag_mic_missing) +
                    "\n" + stringResource(if (health.cameraPermission) R.string.diag_camera_ok else R.string.diag_camera_missing),
            )
            CheckRow(ok = null, title = stringResource(R.string.diag_echo_title), detail = stringResource(echoModeLabel(settings.callEchoMode)) + "\n" + stringResource(R.string.diag_echo_help),
                action = stringResource(R.string.action_change) to { echoDialog = true })
            SwitchItem(null, stringResource(R.string.diag_auto_gain), stringResource(R.string.diag_auto_gain_sub), checked = settings.callAutoGain, onChange = vm::setAutoGain)
            SwitchItem(null, stringResource(R.string.diag_voice_isolation), stringResource(R.string.diag_voice_isolation_sub), checked = settings.callVoiceIsolation, onChange = vm::setVoiceIsolation)
            SwitchItem(null, stringResource(R.string.diag_call_details), stringResource(R.string.diag_call_details_sub), checked = settings.callShowDetails, onChange = vm::setShowCallDetails)
            lastCall?.let { s ->
                CheckRow(
                    ok = s.warnings == 0 && !s.duplicateAudio && (s.maxLossPercent ?: 0.0) < 5.0,
                    title = stringResource(R.string.diag_last_call),
                    detail = lastCallText(context, s),
                )
            }
            Text(stringResource(R.string.diag_echo_test_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))

            // ---------------------------------------------------------------- problems
            SectionHeader(stringResource(R.string.diag_section_problems))
            if (problems.isEmpty()) Text(stringResource(R.string.diag_no_problems), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            problems.forEach { e ->
                val (title, body) = problemText(context, e)
                CheckRow(ok = false, title = title, detail = body + "\n" + Formatters.dateTime(context, e.at))
            }
            Row(Modifier.padding(16.dp)) {
                OutlinedButton(onClick = {
                    copyToClipboard(context, buildReport(context, vm, network, sync.state, lastSync, health, server, lastCall, problems))
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.diag_report_copied)) }
                }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.diag_copy_report))
                }
                Spacer(Modifier.width(8.dp))
                if (problems.isNotEmpty()) TextButton(onClick = vm::clearProblems) { Text(stringResource(R.string.diag_clear_problems)) }
            }
            Text(stringResource(R.string.diag_report_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp))
        }
    }
    if (echoDialog) {
        ChoiceDialog(stringResource(R.string.diag_echo_title), EchoCancellerMode.entries.map { it to stringResource(echoModeLabel(it)) }, settings.callEchoMode,
            onSelect = { vm.setEchoMode(it); echoDialog = false }, onDismiss = { echoDialog = false })
    }
}

private fun echoModeLabel(m: EchoCancellerMode): Int = when (m) {
    EchoCancellerMode.AUTO -> R.string.diag_echo_auto
    EchoCancellerMode.APP -> R.string.diag_echo_app
}

private fun channelName(context: Context, id: String): String = context.getString(when (id) {
    NotificationHelper.CH_MESSAGES -> R.string.channel_messages
    NotificationHelper.CH_GROUPS -> R.string.channel_groups
    NotificationHelper.CH_CALLS -> R.string.channel_calls
    else -> R.string.channel_reminders
})

private fun lastCallText(context: Context, s: CallSummary): String = buildString {
    val sep = context.getString(R.string.list_separator)
    append(Formatters.dateTime(context, s.endedAt)).append(sep)
    append(context.getString(if (s.video) R.string.diag_call_video else R.string.diag_call_voice))
    append(sep).append(context.getString(R.string.diag_call_duration, s.durationSec))
    append("\n").append(context.getString(R.string.diag_call_quality, s.avgRttMs?.toString() ?: "–", s.maxLossPercent?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "–", s.reconnects))
    append("\n").append(context.getString(R.string.diag_call_echo, context.getString(if (s.echoCanceller == "phone") R.string.call_echo_phone else R.string.call_echo_app)))
    when (s.voiceIsolation) {
        "on" -> append("\n").append(if (s.quietPercent != null) context.getString(R.string.diag_call_isolation_on, s.quietPercent) else context.getString(R.string.diag_call_isolation_on_short))
        "off" -> append("\n").append(context.getString(R.string.diag_call_isolation_off))
        "unavailable" -> append("\n").append(context.getString(R.string.diag_call_isolation_unavailable))
    }
    if (s.video && s.lowestVideo != null) append("\n").append(context.getString(R.string.diag_call_quality_auto, s.qualityChanges, s.lowestVideo))
    if (s.duplicateAudio) append("\n").append(context.getString(R.string.diag_call_duplicate))
    if (s.warnings > 0) append("\n").append(context.getString(R.string.diag_call_warnings, s.warnings))
}

/** Plain-language title and advice for a recorded problem code. */
private fun problemText(context: Context, e: DiagnosticEvent): Pair<String, String> {
    fun s(id: Int) = context.getString(id)
    return when (e.code) {
        "send_failed" -> s(R.string.problem_send_title) to s(if (e.detail == "denied") R.string.problem_send_denied else R.string.problem_send_retry)
        "sync_denied" -> s(R.string.problem_sync_denied_title) to s(R.string.problem_sync_denied_body)
        "sync_error" -> s(R.string.problem_sync_error_title) to s(R.string.problem_sync_error_body)
        "schedule_failed" -> s(R.string.problem_schedule_failed_title) to s(R.string.problem_schedule_failed_body)
        "schedule_retry" -> s(R.string.problem_schedule_retry_title) to s(R.string.problem_schedule_retry_body)
        "reminder_inexact" -> s(R.string.problem_reminder_inexact_title) to s(R.string.reminder_inexact_note)
        "reminder_not_shown" -> s(R.string.problem_reminder_hidden_title) to s(R.string.diag_notif_off)
        "ai_not_configured" -> s(R.string.problem_ai_title) to s(R.string.ai_error_not_configured)
        "ai_rate_limited" -> s(R.string.problem_ai_title) to s(R.string.ai_error_rate_limited)
        "ai_network", "ai_timeout" -> s(R.string.problem_ai_title) to s(R.string.error_network)
        "translation_download_failed" -> s(R.string.problem_translation_title) to s(R.string.translate_error_download)
        else -> s(R.string.problem_other_title) to e.code
    }
}

private fun buildReport(
    context: Context, vm: DiagnosticsViewModel, network: NetworkKind, sync: SyncState, lastSync: Long, h: NotificationHealth,
    server: ServerCheck, lastCall: CallSummary?, problems: List<DiagnosticEvent>,
): String = buildString {
    appendLine("PulseChat diagnostics ${Formatters.dateTime(context, System.currentTimeMillis())}")
    appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Network: $network · Server check: ${when (server) { is ServerCheck.Ok -> "ok ${server.ms} ms"; is ServerCheck.Failed -> "failed (${server.reason})"; else -> "not run" }}")
    appendLine("Live sync: $sync · last server data: ${if (lastSync > 0) Formatters.dateTime(context, lastSync) else "never"}")
    appendLine("Outbox: pending ${vm.pending.value + vm.sending.value}, failed ${vm.failed.value}, failed uploads ${vm.failedUploads.value}")
    appendLine("Scheduled: pending ${vm.scheduledPending.value}, failed ${vm.scheduledFailed.value} · Reminders scheduled: ${vm.remindersScheduled.value}")
    appendLine("Notifications: enabled ${h.appEnabled}, permission ${h.permission}, blocked channels ${h.channelsBlocked}, push registered ${h.pushRegistered}, DND ${h.doNotDisturb}")
    appendLine("Exact alarms ${h.exactAlarms} · battery unrestricted ${h.batteryUnrestricted} · background restricted ${h.backgroundRestricted}")
    appendLine("Calls: mic ${h.micPermission}, camera ${h.cameraPermission}, echo ${vm.settings.value.callEchoMode}, agc ${vm.settings.value.callAutoGain}")
    lastCall?.let { appendLine("Last call: ${it.finalState}, ${it.durationSec}s, video ${it.video}, echo ${it.echoCanceller}, route ${it.route}, rtt ${it.avgRttMs}, loss ${it.maxLossPercent}, reconnects ${it.reconnects}, warnings ${it.warnings}, duplicate audio ${it.duplicateAudio}, voice isolation ${it.voiceIsolation} (lowered ${it.quietPercent ?: "-"}%), quality changes ${it.qualityChanges}, lowest video ${it.lowestVideo ?: "-"}") }
    appendLine("Recent problems:")
    problems.take(20).forEach { appendLine("- ${Formatters.dateTime(context, it.at)} ${it.category}/${it.code}${it.detail?.let { d -> " ($d)" } ?: ""}") }
}

@Composable
private fun CheckRow(ok: Boolean?, title: String, detail: String, busy: Boolean = false, action: Pair<String, () -> Unit>? = null) {
    val (icon: ImageVector, tint: Color) = when (ok) {
        true -> Icons.Rounded.CheckCircle to com.example.ui.theme.PulseTheme.colors.success
        false -> Icons.Rounded.WarningAmber to MaterialTheme.colorScheme.error
        null -> Icons.Rounded.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val stateLabel = stringResource(when (ok) { true -> R.string.diag_state_ok; false -> R.string.diag_state_attention; null -> R.string.diag_state_info })
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(icon, contentDescription = stateLabel, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.let { (label, onClick) -> TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(label) } }
        }
    }
    HorizontalDivider()
}
