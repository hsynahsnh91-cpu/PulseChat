package com.example.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import com.example.data.model.ChatFolder
import com.example.data.model.ChatListRow
import com.example.domain.ChatListPolicy
import com.example.domain.Ids
import com.example.domain.model.ChatFilter
import com.example.ui.common.DeliveryTicks
import com.example.ui.common.EmptyState
import com.example.ui.common.Formatters
import com.example.ui.common.LoadingBox
import com.example.ui.common.previewLine
import com.example.ui.components.UserAvatar
import com.example.ui.theme.PulseTheme
import com.example.ui.theme.Spacing

@Composable
fun ChatsTab(
    ui: ChatsUi,
    filter: ChatFilter,
    query: String,
    selection: Set<String>,
    me: String,
    onFilter: (ChatFilter) -> Unit,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onOpenArchived: () -> Unit,
    onSearchMessages: () -> Unit,
    onNewChat: () -> Unit,
    contentPadding: PaddingValues,
    folders: List<ChatFolder> = emptyList(),
    selectedFolder: String? = null,
    onFolder: (String?) -> Unit = {},
    onManageFolders: () -> Unit = {},
) {
    if (!ui.loaded) { LoadingBox(); return }
    LazyColumn(Modifier.fillMaxSize().testTag("chat_list"), contentPadding = contentPadding) {
        item(key = "filters") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                listOf(
                    ChatFilter.ALL to R.string.filter_all, ChatFilter.UNREAD to R.string.filter_unread,
                    ChatFilter.FAVORITES to R.string.filter_favorites, ChatFilter.GROUPS to R.string.filter_groups,
                ).forEach { (f, label) ->
                    FilterChip(selected = selectedFolder == null && filter == f, onClick = { onFilter(f) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("filter_${f.name}"))
                }
                // Custom folders, then the folder manager.
                folders.forEach { folder ->
                    FilterChip(
                        selected = selectedFolder == folder.folderId, onClick = { onFolder(if (selectedFolder == folder.folderId) null else folder.folderId) },
                        label = { Text(folder.name) }, leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("folder_chip"),
                    )
                }
                AssistChip(
                    onClick = onManageFolders,
                    label = { Text(stringResource(if (folders.isEmpty()) R.string.folder_new else R.string.folders_edit)) },
                    leadingIcon = { Icon(if (folders.isEmpty()) Icons.Rounded.CreateNewFolder else Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("folders_manage"),
                )
            }
        }
        if (query.isNotBlank()) {
            item(key = "search_messages") {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onSearchMessages).heightIn(min = 48.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.search_messages_for, query), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (ui.archivedCount > 0 && query.isBlank() && filter == ChatFilter.ALL && selectedFolder == null) {
            item(key = "archived") {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenArchived).heightIn(min = 56.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(24.dp))
                    Text(stringResource(R.string.archived_chats), modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text(ui.archivedCount.toString(), color = if (ui.archivedUnread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ui.chats.isEmpty()) {
            item(key = "empty") {
                when {
                    query.isNotBlank() -> EmptyState(Icons.Rounded.Search, stringResource(R.string.empty_search_title), stringResource(R.string.empty_search_body), avoidFab = true)
                    selectedFolder != null -> EmptyState(Icons.Rounded.Folder, stringResource(R.string.folder_empty_title), stringResource(R.string.folder_empty_body), avoidFab = true) {
                        TextButton(onClick = onManageFolders) { Text(stringResource(R.string.folders_edit)) }
                    }
                    filter != ChatFilter.ALL -> EmptyState(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.empty_filter_title), stringResource(R.string.empty_filter_body), avoidFab = true) {
                        TextButton(onClick = { onFilter(ChatFilter.ALL) }) { Text(stringResource(R.string.filter_show_all)) }
                    }
                    else -> EmptyState(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.empty_chats_title), stringResource(R.string.empty_chats_body), avoidFab = true) {
                        TextButton(onClick = onNewChat) { Text(stringResource(R.string.action_start_chat)) }
                    }
                }
            }
        }
        items(ui.chats, key = { it.conversation.conversationId }) { row ->
            ChatRow(
                row = row, me = me, typing = ui.typing[row.conversation.conversationId].orEmpty(),
                selected = row.conversation.conversationId in selection, selectionMode = selection.isNotEmpty(),
                onClick = { if (selection.isNotEmpty()) onToggleSelect(row.conversation.conversationId) else onOpen(row.conversation.conversationId) },
                onLongClick = { onToggleSelect(row.conversation.conversationId) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    row: ChatListRow,
    me: String,
    typing: Set<String>,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = row.conversation
    val context = LocalContext.current
    val unread = ChatListPolicy.hasUnread(row)
    val badge = ChatListPolicy.unreadBadge(row)
    val title = row.displayTitle.ifBlank { stringResource(R.string.unknown_user) }
    val pulse = PulseTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { if (selectionMode) contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            if (c.isGroup && row.displayAvatar == null) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            } else {
                UserAvatar(displayName = title, avatarUrl = row.displayAvatar, size = 52.dp, isAiAssistant = c.peerId == Ids.AI_ASSISTANT_ID, colorHex = row.peerColor)
            }
            if (selected) {
                Box(
                    Modifier.size(22.dp).align(Alignment.BottomEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.lastMessageId != null || c.lastMessageText != null) {
                    Text(Formatters.chatListTime(context, c.lastMessageTimestamp), style = MaterialTheme.typography.labelSmall,
                        color = if (unread && badge != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val typingNow = typing.isNotEmpty()
                val draft = c.draftText
                when {
                    typingNow -> Text(stringResource(R.string.typing), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                    !draft.isNullOrBlank() -> Text(
                        stringResource(R.string.draft_prefix, draft), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    else -> {
                        val mine = c.lastMessageSenderId == me
                        if (mine && row.lastStatus != null && row.lastDeleted != true && !c.isLocalOnly) {
                            DeliveryTicks(row.lastStatus, size = 16.dp)
                            Spacer(Modifier.width(3.dp))
                        }
                        val sender = when {
                            mine -> stringResource(R.string.you) + ": "
                            c.isGroup && !row.lastSenderName.isNullOrBlank() -> row.lastSenderName + ": "
                            else -> ""
                        }
                        val preview = previewLine(c.lastMessageType, c.lastMessageText, row.lastDeleted == true)
                        Text(
                            if (preview.isBlank()) "" else sender + preview, style = MaterialTheme.typography.bodyMedium,
                            color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (c.isFavorite) Icon(Icons.Rounded.Star, contentDescription = stringResource(R.string.filter_favorites), tint = pulse.metaText, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                if (c.isMuted()) Icon(Icons.Rounded.NotificationsOff, contentDescription = stringResource(R.string.muted), tint = pulse.metaText, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                if (c.isPinned) Icon(Icons.Rounded.PushPin, contentDescription = stringResource(R.string.pinned), tint = pulse.metaText, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                if (!c.draftText.isNullOrBlank() && typing.isEmpty()) Icon(Icons.Rounded.Edit, contentDescription = null, tint = pulse.metaText, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                if (badge != null) {
                    val badgeDesc = if (badge.isEmpty()) stringResource(R.string.a11y_marked_unread) else stringResource(R.string.a11y_unread_count, badge)
                    Spacer(Modifier.width(6.dp))
                    // Muted chats get a neutral badge so their unread count does not compete for attention.
                    val muted = c.isMuted()
                    Box(
                        Modifier.clip(CircleShape).background(if (muted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary)
                            .widthIn(min = 20.dp).padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(badge, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { contentDescription = badgeDesc })
                    }
                }
            }
        }
    }
}
