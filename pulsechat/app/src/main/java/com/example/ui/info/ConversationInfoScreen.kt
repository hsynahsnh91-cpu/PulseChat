package com.example.ui.info

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.model.ChatNotificationSettings
import com.example.R
import com.example.data.model.User
import com.example.domain.DisappearingPolicy
import com.example.domain.GroupPolicy
import com.example.domain.model.MessageType
import com.example.ui.calls.rememberCallStarter
import com.example.ui.common.BackTopBar
import com.example.ui.common.ChoiceDialog
import com.example.ui.common.ConfirmDialog
import com.example.ui.common.MuteDialog
import com.example.ui.common.PreviewDialog
import com.example.ui.common.SettingsNote
import com.example.ui.common.muteSummary
import com.example.ui.common.previewSummary
import com.example.ui.common.Formatters
import com.example.ui.common.SectionHeader
import com.example.ui.common.SettingsGroup
import com.example.ui.theme.Radii
import com.example.ui.theme.Spacing
import com.example.ui.common.SettingsItem
import com.example.ui.common.SwitchItem
import com.example.ui.common.containerFactory
import com.example.ui.components.UserAvatar
import kotlinx.coroutines.launch
import java.io.File

data class InfoNav(
    val back: () -> Unit,
    val openChat: (String) -> Unit,
    val openMedia: (String) -> Unit,
    val addMembers: (String) -> Unit,
    val openCall: () -> Unit,
    val closeChat: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationInfoScreen(conversationId: String, nav: InfoNav) {
    val vm: InfoViewModel = viewModel(key = "info_$conversationId", factory = containerFactory { InfoViewModel(it, conversationId) })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val conv by vm.conversation.collectAsState()
    val members by vm.members.collectAsState()
    val peer by vm.peer.collectAsState()
    val contact by vm.contact.collectAsState()
    val presence by vm.presence.collectAsState()
    val media by vm.media.collectAsState()
    val blocked by vm.blocked.collectAsState()
    val notifSettings by vm.notificationSettings.collectAsState()
    val appPreviews by vm.appPreviews.collectAsState()
    val startCall = rememberCallStarter(onStarted = nav.openCall)

    var dialog by remember { mutableStateOf<String?>(null) }
    var memberSheet by remember { mutableStateOf<User?>(null) }
    var nicknameDraft by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }
    var newPhoto by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) newPhoto = uri }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.closed.collect { nav.closeChat() } }

    val c = conv ?: return
    val me = vm.me
    val roster = c.roster()
    val isAdmin = GroupPolicy.isAdmin(roster, me)
    val canEdit = c.isGroup && !c.isLeft && GroupPolicy.canEditInfo(roster, me)
    val peerBlocked = !c.isGroup && c.peerId in blocked
    val title = if (c.isGroup) c.title else contact?.customNickname?.takeIf { it.isNotBlank() } ?: peer?.displayName ?: stringResource(R.string.unknown_user)

    // Grouped look: rounded cards on the page background, like Settings.
    val groupColor = MaterialTheme.colorScheme.surfaceContainerLow
    val sortedMembers = members.sortedWith(compareBy<User>({ it.userId != me }, { it.userId !in roster.admins }, { it.displayName.lowercase() }))
    val canAddMembers = c.isGroup && isAdmin && !c.isLeft
    Scaffold(
        topBar = { BackTopBar(stringResource(if (c.isGroup) R.string.group_info else R.string.contact_info), nav.back, containerColor = MaterialTheme.colorScheme.background) },
        snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        if (c.isGroup && c.avatarUrl == null) {
                            Box(Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(56.dp))
                            }
                        } else UserAvatar(title, avatarUrl = if (c.isGroup) c.avatarUrl else peer?.avatarUrl, size = 112.dp, colorHex = peer?.avatarColorHex)
                        if (canEdit) {
                            Box(Modifier.size(36.dp).align(Alignment.BottomEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                                .clickable { editName = c.title; editDescription = c.description.orEmpty(); newPhoto = null; dialog = "editGroup" }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.group_edit), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    if (c.isGroup) {
                        Text(stringResource(R.string.group_members_count, members.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        peer?.let { p ->
                            Text("@${p.username} · ${p.uniqueId10}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            Formatters.lastSeen(context, presence)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (!c.isGroup && c.peerId != null && !peerBlocked && !c.isLocalOnly) {
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TextButton(onClick = { startCall(c.peerId!!, false) }) { Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_voice_call)) }
                            TextButton(onClick = { startCall(c.peerId!!, true) }) { Icon(Icons.Rounded.Videocam, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_video_call)) }
                        }
                    }
                }
            }
            item {
                val about = if (c.isGroup) c.description else peer?.statusMessage
                if (!about.isNullOrBlank()) {
                    SettingsGroup(stringResource(if (c.isGroup) R.string.group_description else R.string.profile_about)) {
                        Text(about, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (media.isNotEmpty()) item {
                SectionHeader(stringResource(R.string.info_media, media.size), Modifier.padding(start = Spacing.lg))
                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(media.take(30), key = { it.messageId }) { m ->
                        Box(Modifier.size(84.dp).clip(RoundedCornerShape(Radii.md)).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                            if (m.type == MessageType.IMAGE || m.type == MessageType.VIDEO) nav.openMedia(m.messageId) else nav.openChat(conversationId)
                        }, contentAlignment = Alignment.Center) {
                            val local = m.localMediaPath?.takeIf { File(it).exists() }
                            when {
                                m.type == MessageType.IMAGE && local != null -> AsyncImage(File(local), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                m.type == MessageType.VIDEO -> Icon(Icons.Rounded.PlayArrow, null)
                                m.type == MessageType.AUDIO -> Icon(Icons.Rounded.Mic, null)
                                else -> Icon(Icons.Rounded.Description, null)
                            }
                        }
                    }
                }
            }
            if (!c.isLocalOnly && !c.isLeft) item {
                // Notifications of this chat only (other chats are not affected).
                val muted = notifSettings?.isMuted() == true
                SettingsGroup(stringResource(R.string.info_notifications)) {
                    SettingsItem(
                        if (muted) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications,
                        stringResource(if (muted) R.string.action_unmute else R.string.info_mute),
                        subtitle = muteSummary(context, notifSettings?.mutedUntil ?: 0L),
                        modifier = Modifier.testTag("info_mute"),
                    ) { if (muted) vm.setMuted(0L) else dialog = "mute" }
                    SettingsItem(
                        Icons.Rounded.Visibility, stringResource(R.string.notif_preview_title),
                        subtitle = previewSummary(context, notifSettings?.preview ?: ChatNotificationSettings.PREVIEW_DEFAULT, appPreviews),
                        modifier = Modifier.testTag("info_preview"),
                    ) { dialog = "preview" }
                }
                SettingsNote(stringResource(R.string.notif_chat_note))
            }
            item { SettingsGroup(stringResource(R.string.info_settings)) {
                if (!c.isLocalOnly && !c.isLeft) {
                    val canChangeTimer = !c.isGroup || canEdit
                    SettingsItem(Icons.Rounded.Timer, stringResource(R.string.disappearing_title), subtitle = Formatters.disappearingLabel(context, c.disappearingSeconds),
                        enabled = canChangeTimer) { dialog = "disappearing" }
                }
                if (c.isGroup && isAdmin && !c.isLeft) {
                    SettingsItem(Icons.Rounded.Settings, stringResource(R.string.group_settings), subtitle = stringResource(R.string.group_settings_subtitle)) { dialog = "permissions" }
                }
                if (!c.isGroup && c.peerId != null && !c.isLocalOnly) {
                    if (contact == null) SettingsItem(Icons.Rounded.PersonAdd, stringResource(R.string.contact_save)) { nicknameDraft = peer?.displayName.orEmpty(); dialog = "nickname" }
                    else {
                        SettingsItem(Icons.Rounded.Edit, stringResource(R.string.contact_edit_nickname), subtitle = contact?.customNickname) {
                            nicknameDraft = contact?.customNickname ?: peer?.displayName.orEmpty(); dialog = "nickname"
                        }
                        SettingsItem(Icons.Rounded.PersonRemove, stringResource(R.string.contact_remove)) { dialog = "removeContact" }
                    }
                }
            } }
            if (c.isGroup) {
                item {
                    SectionHeader(stringResource(R.string.group_members_count, members.size), Modifier.padding(start = Spacing.lg))
                    if (canAddMembers) {
                        val bottom = if (sortedMembers.isEmpty()) Radii.lg else 0.dp
                        Box(Modifier.padding(horizontal = Spacing.lg).clip(RoundedCornerShape(Radii.lg, Radii.lg, bottom, bottom)).background(groupColor)) {
                            SettingsItem(Icons.Rounded.PersonAdd, stringResource(R.string.group_add_members)) { nav.addMembers(conversationId) }
                        }
                    }
                }
                itemsIndexed(sortedMembers, key = { _, u -> u.userId }) { i, u ->
                    // The member rows continue the rounded card started by the header row.
                    val top = if (i == 0 && !canAddMembers) Radii.lg else 0.dp
                    val bottom = if (i == sortedMembers.lastIndex) Radii.lg else 0.dp
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).clip(RoundedCornerShape(top, top, bottom, bottom)).background(groupColor)
                            .clickable(enabled = u.userId != me) { memberSheet = u }.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UserAvatar(u.displayName, avatarUrl = u.avatarUrl, size = 42.dp, colorHex = u.avatarColorHex)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (u.userId == me) stringResource(R.string.you) else u.displayName.ifBlank { stringResource(R.string.unknown_user) }, fontWeight = FontWeight.Medium)
                            if (u.statusMessage.isNotBlank()) Text(u.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (u.userId in roster.admins) Text(stringResource(R.string.group_admin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
            item { SettingsGroup(modifier = Modifier.padding(top = Spacing.sm)) {
                val danger = MaterialTheme.colorScheme.error
                SettingsItem(Icons.Rounded.DeleteSweep, stringResource(R.string.action_clear_chat), titleColor = danger) { dialog = "clear" }
                if (!c.isGroup && c.peerId != null && !c.isLocalOnly) {
                    SettingsItem(Icons.Rounded.Block, stringResource(if (peerBlocked) R.string.action_unblock else R.string.action_block), titleColor = danger) {
                        if (peerBlocked) vm.unblock() else dialog = "block"
                    }
                }
                if (!c.isLocalOnly) SettingsItem(Icons.Rounded.Report, stringResource(R.string.action_report), titleColor = danger) { dialog = "report" }
                if (c.isGroup && !c.isLeft && !c.isLocalOnly) SettingsItem(Icons.AutoMirrored.Rounded.ExitToApp, stringResource(R.string.group_exit), titleColor = danger) { dialog = "exit" }
                if (!c.isGroup || c.isLeft || c.isLocalOnly) SettingsItem(Icons.Rounded.Delete, stringResource(R.string.action_delete_chat), titleColor = danger) { dialog = "delete" }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    when (dialog) {
        "mute" -> MuteDialog(isGroup = c.isGroup, onSelect = { until -> dialog = null; vm.setMuted(until) }, onDismiss = { dialog = null })
        "preview" -> PreviewDialog(notifSettings?.preview ?: ChatNotificationSettings.PREVIEW_DEFAULT, appPreviews,
            onSelect = { p -> dialog = null; vm.setPreview(p) }, onDismiss = { dialog = null })
        "disappearing" -> ChoiceDialog(stringResource(R.string.disappearing_title), DisappearingPolicy.OPTIONS.map { it to Formatters.disappearingLabel(context, it) },
            c.disappearingSeconds, onSelect = { s -> dialog = null; vm.setDisappearing(s) }, onDismiss = { dialog = null })
        "clear" -> ConfirmDialog(stringResource(R.string.clear_chat_title), stringResource(R.string.clear_chat_body), stringResource(R.string.action_clear),
            onConfirm = { keep -> dialog = null; vm.clearChat(keep) }, onDismiss = { dialog = null }, destructive = true,
            checkboxLabel = stringResource(R.string.clear_keep_starred), checkboxDefault = true)
        "block" -> ConfirmDialog(stringResource(R.string.block_title, title), stringResource(R.string.block_body), stringResource(R.string.action_block),
            onConfirm = { dialog = null; vm.block() }, onDismiss = { dialog = null }, destructive = true)
        "report" -> ConfirmDialog(stringResource(R.string.report_title, title), stringResource(R.string.report_body), stringResource(R.string.action_report),
            onConfirm = { also -> dialog = null; vm.report(also) }, onDismiss = { dialog = null }, destructive = true,
            checkboxLabel = stringResource(if (c.isGroup) R.string.report_and_exit else R.string.report_and_block), checkboxDefault = !c.isGroup)
        "exit" -> ConfirmDialog(stringResource(R.string.group_exit_title, c.title), stringResource(R.string.group_exit_body), stringResource(R.string.group_exit),
            onConfirm = { dialog = null; vm.leaveGroup() }, onDismiss = { dialog = null }, destructive = true)
        "delete" -> ConfirmDialog(stringResource(R.string.delete_chat_title), stringResource(R.string.delete_chats_body), stringResource(R.string.action_delete),
            onConfirm = { dialog = null; vm.deleteChat() }, onDismiss = { dialog = null }, destructive = true)
        "removeContact" -> ConfirmDialog(stringResource(R.string.contact_remove), null, stringResource(R.string.action_remove),
            onConfirm = { dialog = null; vm.removeContact() }, onDismiss = { dialog = null }, destructive = true)
        "nickname" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.contact_nickname_title)) },
            text = { OutlinedTextField(value = nicknameDraft, onValueChange = { nicknameDraft = it.take(50) }, singleLine = true, label = { Text(stringResource(R.string.contact_nickname)) }) },
            confirmButton = { TextButton(onClick = { dialog = null; vm.saveContact(nicknameDraft) }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        "permissions" -> {
            var onlyAdminsSend by remember { mutableStateOf(c.onlyAdminsCanSend) }
            var onlyAdminsEdit by remember { mutableStateOf(c.onlyAdminsCanEditInfo) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.group_settings)) },
                text = {
                    Column {
                        SwitchItem(null, stringResource(R.string.group_only_admins_send), checked = onlyAdminsSend, onChange = { onlyAdminsSend = it })
                        SwitchItem(null, stringResource(R.string.group_only_admins_edit), checked = onlyAdminsEdit, onChange = { onlyAdminsEdit = it })
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null; vm.setPermissions(onlyAdminsSend, onlyAdminsEdit) }) { Text(stringResource(R.string.action_save)) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        "editGroup" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.group_edit)) },
            text = {
                Column {
                    TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (newPhoto == null) R.string.group_change_photo else R.string.group_photo_selected))
                    }
                    OutlinedTextField(value = editName, onValueChange = { editName = it.take(GroupPolicy.MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.group_name)) })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = editDescription, onValueChange = { editDescription = it.take(GroupPolicy.MAX_DESCRIPTION) }, label = { Text(stringResource(R.string.group_description)) })
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null; vm.updateGroup(editName, editDescription, newPhoto) }, enabled = editName.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    memberSheet?.let { u ->
        ModalBottomSheet(onDismissRequest = { memberSheet = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(u.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SettingsItem(null, stringResource(R.string.member_message, u.displayName)) {
                    memberSheet = null
                    scope.launch { nav.openChat(vm.openDirect(u.userId)) }
                }
                if (GroupPolicy.canPromote(roster, me, u.userId)) SettingsItem(Icons.Rounded.AdminPanelSettings, stringResource(R.string.member_make_admin)) { memberSheet = null; vm.setAdmin(u.userId, true) }
                if (GroupPolicy.canDemote(roster, me, u.userId)) SettingsItem(Icons.Rounded.AdminPanelSettings, stringResource(R.string.member_dismiss_admin)) { memberSheet = null; vm.setAdmin(u.userId, false) }
                if (GroupPolicy.canRemove(roster, me, u.userId)) SettingsItem(Icons.Rounded.PersonRemove, stringResource(R.string.member_remove, u.displayName), titleColor = MaterialTheme.colorScheme.error) {
                    memberSheet = null; vm.removeMember(u.userId)
                }
            }
        }
    }
}
