package com.example.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.R
import com.example.data.prefs.AppLanguage
import com.example.data.prefs.ThemeMode
import com.example.domain.AutoDownloadSettings
import com.example.domain.DisappearingPolicy
import com.example.domain.MediaLimits
import com.example.domain.StatusPolicy
import com.example.domain.model.MessageType
import com.example.domain.model.Visibility
import com.example.ui.chat.copyToClipboard
import com.example.ui.common.BackTopBar
import com.example.ui.common.ChoiceDialog
import com.example.ui.common.ConfirmDialog
import com.example.ui.common.EmptyState
import com.example.ui.common.Formatters
import com.example.ui.common.SettingsGroup
import com.example.ui.common.SettingsItem
import com.example.ui.common.SettingsNote
import com.example.ui.common.SwitchItem
import com.example.ui.common.containerFactory
import com.example.ui.components.AutoReplySettingsDialog
import com.example.ui.components.UserAvatar
import com.example.ui.security.PinSetupDialog
import com.example.ui.security.PinVerifyDialog
import com.example.util.LocaleHelper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import com.example.ui.theme.Spacing
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Reply

@Composable
private fun settingsVm(): SettingsViewModel = viewModel(factory = containerFactory { SettingsViewModel(it) })

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, vm: SettingsViewModel, content: @Composable () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // The list scrolls behind the transparent navigation bar (one bottom inset, at the end of the
    // content) and stays a readable width on tablets.
    Scaffold(
        topBar = { BackTopBar(title, onBack, containerColor = MaterialTheme.colorScheme.background) },
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.lg).navigationBarsPadding(),
            ) { content() }
        }
    }
}

@Composable
fun visibilityLabel(v: Visibility): String = stringResource(when (v) {
    Visibility.EVERYONE -> R.string.visibility_everyone
    Visibility.CONTACTS -> R.string.visibility_contacts
    Visibility.NOBODY -> R.string.visibility_nobody
})

data class SettingsNav(
    val back: () -> Unit,
    val open: (String) -> Unit,
)

@Composable
fun SettingsHomeScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val user by vm.user.collectAsState()
    var logout by remember { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.settings_title), nav.back, vm) {
        SettingsGroup {
            Row(Modifier.fillMaxWidth().clickable { nav.open("settings/profile") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(user?.displayName ?: "", avatarUrl = user?.avatarUrl, size = 64.dp, colorHex = user?.avatarColorHex)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(user?.displayName ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(user?.statusMessage?.ifBlank { null } ?: stringResource(R.string.profile_about_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text("@${user?.username.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        SettingsGroup {
            SettingsItem(Icons.Rounded.Lock, stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_sub)) { nav.open("settings/privacy") }
            SettingsItem(Icons.Rounded.Palette, stringResource(R.string.settings_chats), stringResource(R.string.settings_chats_sub)) { nav.open("settings/chats") }
            SettingsItem(Icons.Rounded.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_sub)) { nav.open("settings/notifications") }
            SettingsItem(Icons.Rounded.Storage, stringResource(R.string.settings_storage), stringResource(R.string.settings_storage_sub), modifier = Modifier.testTag("settings_storage")) { nav.open("settings/storage") }
            SettingsItem(Icons.Rounded.AutoAwesome, stringResource(R.string.settings_ai), stringResource(R.string.settings_ai_sub)) { nav.open("settings/ai") }
            SettingsItem(Icons.Rounded.Info, stringResource(R.string.settings_about), modifier = Modifier.testTag("settings_about")) { nav.open("settings/about") }
        }
        SettingsGroup {
            SettingsItem(Icons.Rounded.SwapHoriz, stringResource(R.string.settings_switch_account), stringResource(R.string.settings_switch_account_sub)) { logout = true }
            SettingsItem(Icons.AutoMirrored.Rounded.Logout, stringResource(R.string.settings_logout), titleColor = MaterialTheme.colorScheme.error) { logout = true }
        }
    }
    if (logout) ConfirmDialog(stringResource(R.string.settings_logout_title), stringResource(R.string.settings_logout_body), stringResource(R.string.settings_logout),
        onConfirm = { logout = false; vm.signOut() }, onDismiss = { logout = false }, destructive = true)
}

@Composable
fun ProfileSettingsScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val context = LocalContext.current
    val user by vm.user.collectAsState()
    val busy by vm.busy.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.updatePhoto(uri) }
    SettingsScaffold(stringResource(R.string.settings_profile), nav.back, vm) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Box {
                UserAvatar(user?.displayName ?: "", avatarUrl = user?.avatarUrl, size = 128.dp, colorHex = user?.avatarColorHex)
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.align(Alignment.BottomEnd)) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = stringResource(R.string.profile_change_photo), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.xxxl).clip(CircleShape))
        SettingsGroup {
            if (user?.avatarUrl != null) SettingsItem(Icons.Rounded.Delete, stringResource(R.string.profile_remove_photo)) { vm.removePhoto() }
            SettingsItem(Icons.Rounded.Person, stringResource(R.string.profile_name), user?.displayName, trailing = { Icon(Icons.Rounded.Edit, null) }) {
                draft = user?.displayName.orEmpty(); editing = "name"
            }
            SettingsItem(Icons.Rounded.Info, stringResource(R.string.profile_about), user?.statusMessage?.ifBlank { null } ?: stringResource(R.string.profile_about_empty),
                trailing = { Icon(Icons.Rounded.Edit, null) }) { draft = user?.statusMessage.orEmpty(); editing = "about" }
            SettingsItem(null, stringResource(R.string.profile_username), "@${user?.username.orEmpty()}",
                trailing = { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy)) }) { copyToClipboard(context, "@${user?.username.orEmpty()}") }
            SettingsItem(null, stringResource(R.string.profile_id), user?.uniqueId10,
                trailing = { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy)) }) { copyToClipboard(context, user?.uniqueId10.orEmpty()) }
        }
        SettingsNote(stringResource(R.string.profile_share_hint))
    }
    editing?.let { field ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (field == "name") R.string.profile_name else R.string.profile_about)) },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(if (field == "name") 50 else 140) }, singleLine = field == "name") },
            confirmButton = { TextButton(onClick = { editing = null; if (field == "name") vm.updateName(draft) else vm.updateAbout(draft) }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun PrivacySettingsScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val context = LocalContext.current
    val s by vm.settings.collectAsState()
    val blocked by vm.blockedUsers.collectAsState()
    val contacts by vm.contacts.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    val biometricAvailable = remember { vm.biometricAvailable() }
    val options = listOf(Visibility.EVERYONE, Visibility.CONTACTS, Visibility.NOBODY)
    SettingsScaffold(stringResource(R.string.settings_privacy), nav.back, vm) {
        SettingsGroup(stringResource(R.string.privacy_who_can_see)) {
            SettingsItem(null, stringResource(R.string.privacy_last_seen), visibilityLabel(s.lastSeenVisibility)) { dialog = "lastSeen" }
            SettingsItem(null, stringResource(R.string.privacy_photo), visibilityLabel(s.photoVisibility)) { dialog = "photo" }
            SettingsItem(null, stringResource(R.string.privacy_about), visibilityLabel(s.aboutVisibility)) { dialog = "about" }
            SettingsItem(null, stringResource(R.string.privacy_status), stringResource(when (s.statusAudienceMode) {
                StatusPolicy.AudienceMode.MY_CONTACTS -> R.string.status_audience_contacts
                StatusPolicy.AudienceMode.CONTACTS_EXCEPT -> R.string.status_audience_except
                StatusPolicy.AudienceMode.ONLY_SHARE_WITH -> R.string.status_audience_only
            })) { dialog = "status" }
        }
        SettingsNote(stringResource(R.string.privacy_reciprocity_note))
        SettingsGroup(stringResource(R.string.privacy_messaging)) {
            SwitchItem(Icons.Rounded.Visibility, stringResource(R.string.privacy_read_receipts), stringResource(R.string.privacy_read_receipts_sub), s.readReceipts, onChange = vm::setReadReceipts)
            SettingsItem(Icons.Rounded.Timer, stringResource(R.string.privacy_default_timer), Formatters.disappearingLabel(context, s.defaultDisappearingSeconds)) { dialog = "timer" }
            SettingsItem(Icons.Rounded.Block, stringResource(R.string.privacy_blocked), context.resources.getQuantityString(R.plurals.blocked_count, blocked.size, blocked.size)) {
                nav.open("settings/blocked")
            }
        }
        SettingsGroup(stringResource(R.string.privacy_security)) {
            SwitchItem(Icons.Rounded.Lock, stringResource(R.string.app_lock), stringResource(R.string.app_lock_sub), s.appLockEnabled) { on ->
                dialog = when {
                    on -> "pinSetup"
                    s.appLockHasPin -> "pinDisable"
                    else -> { vm.disableAppLock(); null }
                }
            }
            if (s.appLockEnabled) {
                SettingsItem(null, stringResource(R.string.app_lock_timeout), lockTimeoutLabel(s.appLockTimeoutMs)) { dialog = "lockTimeout" }
                if (biometricAvailable) {
                    SwitchItem(Icons.Rounded.Fingerprint, stringResource(R.string.app_lock_biometric_switch), stringResource(R.string.app_lock_biometric_sub),
                        s.appLockBiometric, onChange = vm::setAppLockBiometric)
                }
                SettingsItem(null, stringResource(if (s.appLockHasPin) R.string.app_lock_change_pin else R.string.app_lock_set_pin_title)) {
                    dialog = if (s.appLockHasPin) "pinChangeVerify" else "pinChange"
                }
            }
            SettingsItem(Icons.Rounded.Security, stringResource(R.string.privacy_encryption_title), stringResource(R.string.privacy_encryption_body), onClick = null)
        }
    }
    when (dialog) {
        "lastSeen" -> ChoiceDialog(stringResource(R.string.privacy_last_seen), options.map { it to visibilityLabel(it) }, s.lastSeenVisibility,
            onSelect = { dialog = null; vm.setVisibility(lastSeen = it) }, onDismiss = { dialog = null })
        "photo" -> ChoiceDialog(stringResource(R.string.privacy_photo), options.map { it to visibilityLabel(it) }, s.photoVisibility,
            onSelect = { dialog = null; vm.setVisibility(photo = it) }, onDismiss = { dialog = null })
        "about" -> ChoiceDialog(stringResource(R.string.privacy_about), options.map { it to visibilityLabel(it) }, s.aboutVisibility,
            onSelect = { dialog = null; vm.setVisibility(about = it) }, onDismiss = { dialog = null })
        "timer" -> ChoiceDialog(stringResource(R.string.privacy_default_timer), DisappearingPolicy.OPTIONS.map { it to Formatters.disappearingLabel(context, it) },
            s.defaultDisappearingSeconds, onSelect = { dialog = null; vm.setDefaultDisappearing(it) }, onDismiss = { dialog = null })
        "lockTimeout" -> ChoiceDialog(stringResource(R.string.app_lock_timeout), listOf(0L, 60_000L, 30 * 60_000L).map { it to lockTimeoutLabel(it) }, s.appLockTimeoutMs,
            onSelect = { dialog = null; vm.setAppLock(true, it) }, onDismiss = { dialog = null })
        "pinSetup" -> PinSetupDialog(stringResource(R.string.app_lock_set_pin_title), onDismiss = { dialog = null }) { pin -> dialog = null; vm.enableAppLock(pin) }
        "pinChange" -> PinSetupDialog(stringResource(R.string.app_lock_change_pin), onDismiss = { dialog = null }) { pin -> dialog = null; vm.changeAppLockPin(pin) }
        "pinDisable" -> PinVerifyDialog(onDismiss = { dialog = null }, onCheckPin = vm::checkAppLockPin) { dialog = null; vm.disableAppLock() }
        "pinChangeVerify" -> PinVerifyDialog(onDismiss = { dialog = null }, onCheckPin = vm::checkAppLockPin) { dialog = "pinChange" }
        "status" -> StatusAudienceDialog(s.statusAudienceMode, s.statusAudienceList, contacts.map { it.userId to it.shownName },
            onSave = { mode, list -> dialog = null; vm.setStatusAudience(mode, list) }, onDismiss = { dialog = null })
    }
}

/** Status privacy (opened from the Updates tab menu): who sees new status updates. Saved immediately. */
@Composable
fun StatusPrivacyScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val s by vm.settings.collectAsState()
    val contacts by vm.contacts.collectAsState()
    SettingsScaffold(stringResource(R.string.privacy_status), nav.back, vm) {
        SettingsGroup(stringResource(R.string.status_privacy_who)) {
            Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
                listOf(
                    StatusPolicy.AudienceMode.MY_CONTACTS to R.string.status_audience_contacts,
                    StatusPolicy.AudienceMode.CONTACTS_EXCEPT to R.string.status_audience_except,
                    StatusPolicy.AudienceMode.ONLY_SHARE_WITH to R.string.status_audience_only,
                ).forEach { (mode, label) ->
                    com.example.ui.common.RadioRow(stringResource(label), s.statusAudienceMode == mode, onSelect = { vm.setStatusAudience(mode, s.statusAudienceList) })
                }
            }
        }
        if (s.statusAudienceMode != StatusPolicy.AudienceMode.MY_CONTACTS) SettingsGroup(
            stringResource(if (s.statusAudienceMode == StatusPolicy.AudienceMode.CONTACTS_EXCEPT) R.string.status_privacy_hide_from else R.string.status_privacy_share_with),
        ) {
            if (contacts.isEmpty()) {
                Text(stringResource(R.string.contacts_empty_title), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            contacts.forEach { contact ->
                val checked = contact.userId in s.statusAudienceList
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val list = if (checked) s.statusAudienceList - contact.userId else s.statusAudienceList + contact.userId
                        vm.setStatusAudience(s.statusAudienceMode, list)
                    }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    UserAvatar(contact.shownName, avatarUrl = contact.avatarUrl, size = 36.dp, colorHex = contact.avatarColorHex)
                    Spacer(Modifier.width(12.dp))
                    Text(contact.shownName)
                }
            }
        }
        SettingsNote(stringResource(R.string.status_audience_note))
    }
}

@Composable
private fun lockTimeoutLabel(ms: Long): String = stringResource(when (ms) {
    0L -> R.string.app_lock_immediately
    60_000L -> R.string.app_lock_1min
    else -> R.string.app_lock_30min
})

@Composable
private fun StatusAudienceDialog(mode: StatusPolicy.AudienceMode, list: Set<String>, contacts: List<Pair<String, String>>, onSave: (StatusPolicy.AudienceMode, Set<String>) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(mode) }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(list) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacy_status)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    StatusPolicy.AudienceMode.MY_CONTACTS to R.string.status_audience_contacts,
                    StatusPolicy.AudienceMode.CONTACTS_EXCEPT to R.string.status_audience_except,
                    StatusPolicy.AudienceMode.ONLY_SHARE_WITH to R.string.status_audience_only,
                ).forEach { (m, label) -> com.example.ui.common.RadioRow(stringResource(label), current == m, onSelect = { current = m }) }
                if (current != StatusPolicy.AudienceMode.MY_CONTACTS) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (contacts.isEmpty()) Text(stringResource(R.string.contacts_empty_title))
                    contacts.forEach { (id, name) ->
                        Row(Modifier.fillMaxWidth().clickable { if (id in chosen) chosen.remove(id) else chosen.add(id) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = id in chosen, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(name)
                        }
                    }
                }
                Text(stringResource(R.string.status_audience_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current, chosen.toSet()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun BlockedUsersScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val blocked by vm.blockedUsers.collectAsState()
    SettingsScaffold(stringResource(R.string.privacy_blocked), nav.back, vm) {
        if (blocked.isEmpty()) EmptyState(Icons.Rounded.Block, stringResource(R.string.blocked_empty_title), stringResource(R.string.blocked_empty_body))
        else SettingsGroup { blocked.forEach { u ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(u.displayName, avatarUrl = u.avatarUrl, size = 44.dp, colorHex = u.avatarColorHex)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(u.displayName.ifBlank { stringResource(R.string.unknown_user) }, fontWeight = FontWeight.Medium)
                    if (u.username.isNotBlank()) Text("@${u.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.unblock(u.userId) }) { Text(stringResource(R.string.action_unblock)) }
            }
        } }
        SettingsNote(stringResource(R.string.blocked_note))
    }
}

@Composable
fun ChatSettingsScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val context = LocalContext.current
    val s by vm.settings.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    SettingsScaffold(stringResource(R.string.settings_chats), nav.back, vm) {
        SettingsGroup(stringResource(R.string.settings_display)) {
            SettingsItem(Icons.Rounded.DarkMode, stringResource(R.string.settings_theme), themeLabel(s.themeMode)) { dialog = "theme" }
            SettingsItem(Icons.Rounded.Language, stringResource(R.string.settings_language), languageLabel(s.language)) { dialog = "language" }
        }
        val translations = com.example.ui.common.appContainer().translations
        val target = s.translationTarget.ifBlank { translations.defaultTarget(s.language.tag ?: java.util.Locale.getDefault().language) }
        SettingsGroup(stringResource(R.string.settings_chats)) {
            SwitchItem(Icons.AutoMirrored.Rounded.KeyboardReturn, stringResource(R.string.settings_enter_send), stringResource(R.string.settings_enter_send_sub), s.enterToSend, onChange = vm::setEnterToSend)
            SettingsItem(Icons.Rounded.Translate, stringResource(R.string.settings_translation_language), translations.displayName(target)) { dialog = "translation" }
        }
        SettingsNote(stringResource(R.string.settings_translation_note))
        if (dialog == "translation") {
            com.example.ui.chat.LanguagePickerDialog(
                translations.supportedLanguages().map { it to translations.displayName(it) }, target,
                onPick = { tag -> dialog = null; vm.setTranslationTarget(tag) }, onDismiss = { dialog = null },
            )
        }
    }
    when (dialog) {
        "theme" -> ChoiceDialog(stringResource(R.string.settings_theme), ThemeMode.entries.map { it to themeLabel(it) }, s.themeMode,
            onSelect = { dialog = null; vm.setTheme(it) }, onDismiss = { dialog = null })
        "language" -> ChoiceDialog(stringResource(R.string.settings_language), AppLanguage.entries.map { it to languageLabel(it) }, s.language,
            onSelect = { lang ->
                dialog = null
                vm.setLanguage(lang)
                (context as? android.app.Activity ?: (context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)?.let { LocaleHelper.apply(it, lang) }
            }, onDismiss = { dialog = null })
    }
}

@Composable
private fun themeLabel(m: ThemeMode) = stringResource(when (m) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })

@Composable
private fun languageLabel(l: AppLanguage) = when (l) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ENGLISH -> "English"
    AppLanguage.ARABIC -> "العربية"
}

@Composable
fun NotificationSettingsScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val context = LocalContext.current
    val s by vm.settings.collectAsState()
    SettingsScaffold(stringResource(R.string.settings_notifications), nav.back, vm) {
        SettingsGroup {
            SwitchItem(null, stringResource(R.string.notif_messages), null, s.messageNotifications, onChange = vm::setMessageNotifications)
            SwitchItem(null, stringResource(R.string.notif_groups), null, s.groupNotifications, onChange = vm::setGroupNotifications)
            SwitchItem(null, stringResource(R.string.notif_calls), null, s.callNotifications, onChange = vm::setCallNotifications)
            SwitchItem(null, stringResource(R.string.notif_previews), stringResource(R.string.notif_previews_sub), s.notificationPreviews, onChange = vm::setNotificationPreviews)
            SettingsItem(null, stringResource(R.string.notif_system_settings), stringResource(R.string.notif_system_settings_sub)) {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                runCatching { context.startActivity(intent) }
            }
        }
        SettingsNote(stringResource(R.string.notif_push_note))
    }
}

@Composable
fun StorageSettingsScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val s by vm.settings.collectAsState()
    val byChat by vm.storageByChat.collectAsState()
    val report by vm.storageReport.collectAsState()
    val clearingTemporary by vm.clearingTemporary.collectAsState()
    var clearing by remember { mutableStateOf<String?>(null) }
    var confirmTemporary by remember { mutableStateOf(false) }
    var autoDialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshStorage() }
    SettingsScaffold(stringResource(R.string.settings_storage), nav.back, vm) {
        SettingsGroup(stringResource(R.string.storage_usage)) {
            StorageSummary(report)
        }
        SettingsGroup(stringResource(R.string.storage_temp_title)) {
            TemporaryFilesCard(report, clearingTemporary, onClear = { confirmTemporary = true })
        }
        SettingsGroup(stringResource(R.string.storage_by_chat)) {
            if (byChat.isEmpty()) Text(stringResource(R.string.storage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            byChat.forEach { row ->
                SettingsItem(null, (if (row.isGroup) row.title else row.peerName ?: row.title).orEmpty().ifBlank { stringResource(R.string.unknown_user) },
                    "${row.files} · ${MediaLimits.humanSize(row.bytes)}", trailing = { Icon(Icons.Rounded.Delete, stringResource(R.string.storage_clear_media)) }) { clearing = row.conversationId }
            }
        }
        SettingsGroup(stringResource(R.string.storage_auto_download)) {
            SettingsItem(null, stringResource(R.string.auto_wifi), typesLabel(s.autoDownload.wifi)) { autoDialog = "wifi" }
            SettingsItem(null, stringResource(R.string.auto_cellular), typesLabel(s.autoDownload.cellular)) { autoDialog = "cellular" }
            SettingsItem(null, stringResource(R.string.auto_roaming), typesLabel(s.autoDownload.roaming)) { autoDialog = "roaming" }
        }
        SettingsNote(stringResource(R.string.auto_download_note))
    }
    clearing?.let { cid ->
        ConfirmDialog(stringResource(R.string.storage_clear_media), stringResource(R.string.storage_clear_body), stringResource(R.string.action_clear),
            onConfirm = { clearing = null; vm.clearMedia(cid) }, onDismiss = { clearing = null }, destructive = true)
    }
    if (confirmTemporary) {
        ConfirmDialog(stringResource(R.string.storage_temp_clear_title), stringResource(R.string.storage_temp_clear_body), stringResource(R.string.action_clear),
            onConfirm = { confirmTemporary = false; vm.clearTemporaryFiles() }, onDismiss = { confirmTemporary = false })
    }
    autoDialog?.let { network ->
        val current = when (network) { "wifi" -> s.autoDownload.wifi; "cellular" -> s.autoDownload.cellular; else -> s.autoDownload.roaming }
        val chosen = remember(network) { mutableStateListOf<MessageType>().apply { addAll(current) } }
        AlertDialog(
            onDismissRequest = { autoDialog = null },
            title = { Text(stringResource(when (network) { "wifi" -> R.string.auto_wifi; "cellular" -> R.string.auto_cellular; else -> R.string.auto_roaming })) },
            text = {
                Column {
                    listOf(MessageType.IMAGE, MessageType.AUDIO, MessageType.VIDEO, MessageType.DOCUMENT).forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { if (t in chosen) chosen.remove(t) else chosen.add(t) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = t in chosen, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(typeName(t))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val set = chosen.toSet()
                    val a = s.autoDownload
                    vm.setAutoDownload(when (network) { "wifi" -> a.copy(wifi = set); "cellular" -> a.copy(cellular = set); else -> a.copy(roaming = set) })
                    autoDialog = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { autoDialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun typeName(t: MessageType): String = stringResource(when (t) {
    MessageType.IMAGE -> R.string.media_photos
    MessageType.AUDIO -> R.string.media_audio
    MessageType.VIDEO -> R.string.media_videos
    MessageType.DOCUMENT -> R.string.media_documents
    else -> R.string.media_other
})

@Composable
private fun typesLabel(types: Set<MessageType>): String =
    if (types.isEmpty()) stringResource(R.string.auto_none) else listOf(MessageType.IMAGE, MessageType.AUDIO, MessageType.VIDEO, MessageType.DOCUMENT).filter { it in types }.map { typeName(it) }.joinToString(", ")

@Composable
fun AiSettingsScreen(nav: SettingsNav) {
    val vm = settingsVm()
    val s by vm.settings.collectAsState()
    var autoReplyOpen by remember { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.settings_ai), nav.back, vm) {
        Text(stringResource(R.string.ai_settings_intro), modifier = Modifier.padding(start = Spacing.xxxl, end = Spacing.xxxl, top = Spacing.lg), style = MaterialTheme.typography.bodyMedium)
        SettingsGroup {
            SwitchItem(Icons.Rounded.AutoAwesome, stringResource(R.string.ai_consent_switch), stringResource(R.string.ai_consent_switch_sub), s.aiConsent, onChange = vm::setAiConsent)
            SettingsItem(Icons.AutoMirrored.Rounded.Reply, stringResource(R.string.auto_reply_title), stringResource(R.string.auto_reply_sub)) { autoReplyOpen = true }
        }
        SettingsNote(stringResource(R.string.ai_settings_privacy))
    }
    if (autoReplyOpen) AutoReplySettingsDialog(vm.autoReply, aiAllowed = s.aiConsent, onDismiss = { autoReplyOpen = false })
}

@Composable
fun AboutScreen(nav: SettingsNav) {
    val vm = settingsVm()
    SettingsScaffold(stringResource(R.string.settings_about), nav.back, vm) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            // Diagnostics are not listed in Settings; support can still open them with a long press on
            // the version (also offered to TalkBack as the long-press action).
            val haptic = LocalHapticFeedback.current
            val openDiagnostics by rememberUpdatedState { nav.open("settings/diagnostics") }
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("about_version")
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); openDiagnostics() })
                    }
                    .semantics { onLongClick { openDiagnostics(); true } }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp))
        }
        SettingsGroup(stringResource(R.string.about_features_title)) { listOf(
            Icons.Rounded.Forum to R.string.about_feature_messaging,
            Icons.Rounded.PhotoLibrary to R.string.about_feature_media,
            Icons.Rounded.Groups to R.string.about_feature_groups,
            Icons.Rounded.AutoStories to R.string.about_feature_status,
            Icons.Rounded.Call to R.string.about_feature_calls,
            Icons.Rounded.Timer to R.string.about_feature_disappearing,
            Icons.Rounded.AutoAwesome to R.string.about_feature_assistant,
            Icons.Rounded.Lock to R.string.about_feature_lock,
            Icons.Rounded.Visibility to R.string.about_feature_privacy,
            Icons.Rounded.Language to R.string.about_feature_languages,
        ).forEach { (icon, text) -> SettingsItem(icon, stringResource(text), onClick = null) } }
        Text(stringResource(R.string.about_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
    }
}
