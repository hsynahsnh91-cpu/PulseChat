package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.CallRecord
import com.example.data.model.Conversation
import com.example.data.model.StatusItem
import com.example.data.model.User
import com.example.domain.DurationFormat
import com.example.domain.model.CallState
import com.example.ui.common.ConfirmDialog
import com.example.ui.common.EmptyState
import com.example.ui.common.Formatters
import com.example.ui.common.SectionHeader
import com.example.ui.components.UserAvatar
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived

// ---------------------------------------------------------------------- Updates

@Composable
fun UpdatesTab(
    ui: UpdatesUi,
    me: User?,
    onMyStatus: () -> Unit,
    onAddText: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
    onPrivacy: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "mine") {
            SectionHeader(stringResource(R.string.updates_status))
            Row(
                Modifier.fillMaxWidth().clickable(onClick = if (ui.mine.isEmpty()) onAddText else onMyStatus).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    StatusRing(hasUnviewed = false, active = ui.mine.isNotEmpty()) {
                        UserAvatar(me?.displayName ?: "", avatarUrl = me?.avatarUrl, size = 50.dp, colorHex = me?.avatarColorHex)
                    }
                    if (ui.mine.isEmpty()) {
                        Box(Modifier.size(20.dp).align(Alignment.BottomEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.updates_my_status), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val uploading = ui.mine.firstOrNull { it.uploadState == StatusItem.UPLOAD_UPLOADING || it.uploadState == StatusItem.UPLOAD_PENDING }
                    val failed = ui.mine.firstOrNull { it.uploadState == StatusItem.UPLOAD_FAILED }
                    when {
                        failed != null -> TextButton(onClick = { onRetry(failed.statusId) }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.updates_failed_retry), color = MaterialTheme.colorScheme.error)
                        }
                        uploading != null -> Text(stringResource(R.string.updates_uploading, uploading.uploadProgress), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                        ui.mine.isEmpty() -> Text(stringResource(R.string.updates_tap_to_add), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text(Formatters.chatListTime(context, ui.mine.last().createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onPrivacy) { Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.privacy_status)) }
            }
        }
        if (ui.recent.isEmpty() && ui.viewed.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Outlined.PhotoCamera, stringResource(R.string.updates_empty_title), stringResource(R.string.updates_empty_body), avoidFab = true) }
        }
        if (ui.recent.isNotEmpty()) item(key = "recent_h") { SectionHeader(stringResource(R.string.updates_recent)) }
        items(ui.recent, key = { "r_" + it.ownerId }) { owner -> StatusOwnerRow(owner) { onOpen(owner.ownerId) } }
        if (ui.viewed.isNotEmpty()) item(key = "viewed_h") { SectionHeader(stringResource(R.string.updates_viewed)) }
        items(ui.viewed, key = { "v_" + it.ownerId }) { owner -> StatusOwnerRow(owner) { onOpen(owner.ownerId) } }
    }
}

@Composable
fun StatusRing(hasUnviewed: Boolean, active: Boolean, content: @Composable () -> Unit) {
    val color = when {
        hasUnviewed -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.colorScheme.outline
        else -> Color.Transparent
    }
    Box(Modifier.border(BorderStroke(2.5.dp, color), CircleShape).padding(3.dp)) { content() }
}

@Composable
private fun StatusOwnerRow(owner: StatusOwner, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusRing(hasUnviewed = owner.hasUnviewed, active = true) { UserAvatar(owner.name, avatarUrl = owner.avatar, size = 50.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(owner.name.ifBlank { stringResource(R.string.unknown_user) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(Formatters.chatListTime(context, owner.latest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------- Groups

@Composable
fun GroupsTab(groups: List<Conversation>, onOpen: (String) -> Unit, onNewGroup: () -> Unit, contentPadding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "new") {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onNewGroup).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GroupAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.new_group), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        if (groups.isEmpty()) item(key = "empty") { EmptyState(Icons.Default.Groups, stringResource(R.string.groups_empty_title), stringResource(R.string.groups_empty_body), avoidFab = true) }
        items(groups, key = { it.conversationId }) { g ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(g.conversationId) }.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (g.avatarUrl != null) UserAvatar(g.title, avatarUrl = g.avatarUrl, size = 50.dp)
                else Box(Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.group_members_count, g.getParticipantsList().size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Calls

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallsTab(calls: List<CallRecord>, me: String, onCall: (peerId: String, video: Boolean) -> Unit, onDelete: (String) -> Unit, onOpenChat: (String) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<CallRecord?>(null) }
    if (calls.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding)) { EmptyState(Icons.Outlined.Call, stringResource(R.string.calls_empty_title), stringResource(R.string.calls_empty_body), avoidFab = true) }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { SectionHeader(stringResource(R.string.calls_recent)) }
        items(calls, key = { it.callId }) { call ->
            val outgoing = call.callerId == me
            val peerId = if (outgoing) call.receiverId else call.callerId
            val name = (if (outgoing) call.receiverName else call.callerName).ifBlank { stringResource(R.string.unknown_user) }
            val missed = !outgoing && (call.state == CallState.MISSED || call.state == CallState.REJECTED || call.state == CallState.BUSY)
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenChat(peerId) }, onLongClick = { deleting = call })
                    .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(name, size = 50.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when { missed -> Icons.AutoMirrored.Filled.CallMissed; outgoing -> Icons.AutoMirrored.Filled.CallMade; else -> Icons.AutoMirrored.Filled.CallReceived },
                            contentDescription = stringResource(when { missed -> R.string.call_missed; outgoing -> R.string.call_outgoing; else -> R.string.call_incoming }),
                            tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        val detail = buildString {
                            append(Formatters.chatListTime(context, call.timestamp))
                            if (call.durationSeconds > 0) append(" · ").append(DurationFormat.clock(call.durationSeconds * 1000L))
                            else when (call.state) {
                                CallState.CANCELLED -> append(" · ").append(context.getString(R.string.call_state_cancelled))
                                CallState.BUSY -> append(" · ").append(context.getString(R.string.call_state_busy))
                                CallState.FAILED -> append(" · ").append(context.getString(R.string.call_state_failed))
                                CallState.REJECTED -> append(" · ").append(context.getString(R.string.call_state_rejected))
                                else -> Unit
                            }
                        }
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { onCall(peerId, call.isVideo) }) {
                    Icon(if (call.isVideo) Icons.Default.Videocam else Icons.Default.Call,
                        contentDescription = stringResource(if (call.isVideo) R.string.action_video_call else R.string.action_voice_call),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    deleting?.let { call ->
        ConfirmDialog(
            title = stringResource(R.string.calls_delete_title), text = null, confirmLabel = stringResource(R.string.action_delete), destructive = true,
            onConfirm = { onDelete(call.callId); deleting = null }, onDismiss = { deleting = null },
        )
    }
}
