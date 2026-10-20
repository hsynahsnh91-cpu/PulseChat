package com.example.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shortcut
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.Receipt
import com.example.data.model.User
import com.example.domain.DeliveryPolicy
import com.example.domain.MessagePolicy
import com.example.domain.TextTools
import com.example.domain.model.MessageStatus
import com.example.ui.common.DeliveryTicks
import com.example.ui.common.Formatters
import com.example.ui.components.EmojiPickerView
import com.example.ui.components.UserAvatar

val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

data class MessageActions(
    val onReact: (String) -> Unit,
    val onMoreReactions: () -> Unit,
    val onReply: () -> Unit,
    val onCopy: () -> Unit,
    val onForward: () -> Unit,
    val onStar: () -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val onSelect: () -> Unit,
    val onInfo: () -> Unit,
    val onRetry: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(message: Message, facts: MessagePolicy.Facts, now: Long, me: String, actions: MessageActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            if (MessagePolicy.canReact(facts)) {
                val mine = message.reactionMap()[me]
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    QUICK_REACTIONS.forEach { emoji ->
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(if (mine == emoji) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { actions.onReact(emoji) },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 22.sp) }
                    }
                    Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { actions.onMoreReactions() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_more_reactions))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            if (MessagePolicy.canRetry(facts)) ActionRow(Icons.Default.Refresh, stringResource(R.string.action_retry), actions.onRetry)
            if (MessagePolicy.canReply(facts)) ActionRow(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.action_reply), actions.onReply)
            if (MessagePolicy.canCopy(facts)) ActionRow(Icons.Default.ContentCopy, stringResource(R.string.action_copy), actions.onCopy)
            if (MessagePolicy.canForward(facts)) ActionRow(Icons.Default.Shortcut, stringResource(R.string.action_forward), actions.onForward)
            if (MessagePolicy.canStar(facts)) ActionRow(if (message.isStarred) Icons.Default.StarBorder else Icons.Default.Star,
                stringResource(if (message.isStarred) R.string.action_unstar else R.string.action_star), actions.onStar)
            if (MessagePolicy.canEdit(facts, now)) ActionRow(Icons.Default.Edit, stringResource(R.string.action_edit), actions.onEdit)
            ActionRow(Icons.Default.CheckCircle, stringResource(R.string.action_select), actions.onSelect)
            if (MessagePolicy.canShowInfo(facts)) ActionRow(Icons.Default.Info, stringResource(R.string.action_info), actions.onInfo)
            ActionRow(Icons.Default.Delete, stringResource(R.string.action_delete), actions.onDelete, destructive = true)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        EmojiPickerView(onEmojiSelected = onPick, modifier = Modifier.height(360.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsDetailSheet(message: Message, users: Map<String, User>, me: String, onRemoveMine: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.reactions_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            message.reactionMap().entries.sortedBy { if (it.key == me) 0 else 1 }.forEach { (uid, emoji) ->
                val name = if (uid == me) stringResource(R.string.you) else users[uid]?.displayName ?: stringResource(R.string.unknown_user)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = uid == me) { onRemoveMine() }.heightIn(min = 56.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(name, avatarUrl = users[uid]?.avatarUrl, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name)
                        if (uid == me) Text(stringResource(R.string.reactions_tap_remove), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(emoji, fontSize = 24.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(targets: List<Pair<Conversation, String>>, onSend: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val chosen = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(stringResource(R.string.forward_to), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val filtered = targets.filter { TextTools.matches(query, it.second) }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(filtered, key = { it.first.conversationId }) { (conv, title) ->
                    val selected = conv.conversationId in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable { if (selected) chosen.remove(conv.conversationId) else if (chosen.size < 5) chosen.add(conv.conversationId) }
                            .heightIn(min = 56.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (conv.isGroup && conv.avatarUrl == null) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Groups, contentDescription = null)
                            }
                        } else UserAvatar(title, avatarUrl = conv.avatarUrl, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle, contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Button(onClick = { onSend(chosen.toList()) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.forward_send, chosen.size))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoSheet(message: Message, recipients: List<User>, receipts: List<Receipt>, readReceiptsShown: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val byUser = receipts.associateBy { it.userId }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.message_info), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Text(stringResource(R.string.message_info_sent_at, Formatters.dateTime(context, message.createdAt)), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp))
            if (message.editedAt > 0) Text(stringResource(R.string.message_info_edited_at, Formatters.dateTime(context, message.editedAt)),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            if (message.status == MessageStatus.PENDING || message.status == MessageStatus.SENDING || message.status == MessageStatus.FAILED) {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeliveryTicks(message.status)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(when (message.status) {
                        MessageStatus.PENDING -> R.string.status_pending
                        MessageStatus.SENDING -> R.string.status_sending
                        else -> R.string.status_failed
                    }))
                }
            } else {
                recipients.forEach { u ->
                    val r = byUser[u.userId]
                    val state = DeliveryPolicy.recipientState(message.createdAt, r?.deliveredUpTo ?: 0L, r?.readUpTo ?: 0L)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(u.displayName, avatarUrl = u.avatarUrl, size = 36.dp, colorHex = u.avatarColorHex)
                        Spacer(Modifier.width(12.dp))
                        Text(u.displayName, modifier = Modifier.weight(1f))
                        DeliveryTicks(state, showRead = readReceiptsShown)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(when (state) {
                            MessageStatus.READ -> if (readReceiptsShown) R.string.status_read else R.string.status_delivered
                            MessageStatus.DELIVERED -> R.string.status_delivered
                            else -> R.string.status_sent
                        }), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.message_info_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun DeleteMessagesDialog(count: Int, canDeleteForEveryone: Boolean, onDeleteForMe: () -> Unit, onDeleteForEveryone: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LocalContext.current.resources.getQuantityString(R.plurals.delete_messages_title, count, count)) },
        text = { if (canDeleteForEveryone) Text(stringResource(R.string.delete_for_everyone_note)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canDeleteForEveryone) TextButton(onClick = onDeleteForEveryone) { Text(stringResource(R.string.delete_for_everyone), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDeleteForMe) { Text(stringResource(R.string.delete_for_me), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
fun PollDialog(onCreate: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.poll_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = question, onValueChange = { question = it.take(300) }, label = { Text(stringResource(R.string.poll_question)) }, modifier = Modifier.fillMaxWidth())
                options.forEachIndexed { i, opt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = opt, onValueChange = { options[i] = it.take(100) }, singleLine = true,
                            label = { Text(stringResource(R.string.poll_option, i + 1)) }, modifier = Modifier.weight(1f).padding(top = 6.dp))
                        if (options.size > 2) IconButton(onClick = { options.removeAt(i) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
                    }
                }
                if (options.size < 12) TextButton(onClick = { options.add("") }) { Text(stringResource(R.string.poll_add_option)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(question.trim(), options.map { it.trim() }.filter { it.isNotEmpty() }) },
                enabled = question.isNotBlank() && options.count { it.isNotBlank() } >= 2,
            ) { Text(stringResource(R.string.action_send), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
