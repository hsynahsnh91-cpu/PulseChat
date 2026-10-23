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
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.example.domain.PollPolicy
import com.example.domain.TextTools
import com.example.domain.model.MessageStatus
import com.example.ui.common.DeliveryTicks
import com.example.ui.common.Formatters
import com.example.ui.components.EmojiPickerView
import com.example.ui.components.UserAvatar
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import com.example.ui.theme.Spacing
import androidx.compose.material.icons.automirrored.rounded.Shortcut

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
    val onTranslate: (() -> Unit)? = null,
    val onRemind: (() -> Unit)? = null,
    val onTranscribe: (() -> Unit)? = null,
    val onEditHistory: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(message: Message, facts: MessagePolicy.Facts, now: Long, me: String, actions: MessageActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Scrolls: with every option available the list is taller than a small phone.
        Column(Modifier.padding(bottom = Spacing.xxl).verticalScroll(rememberScrollState())) {
            if (MessagePolicy.canReact(facts)) {
                val mine = message.reactionMap()[me]
                // Each reaction gets an equal share of the width (up to 46dp), so the row never
                // overflows narrow screens.
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    QUICK_REACTIONS.forEach { emoji ->
                        ReactionCell(selected = mine == emoji, onClick = { actions.onReact(emoji) }) { Text(emoji, fontSize = 22.sp) }
                    }
                    ReactionCell(selected = false, onClick = actions.onMoreReactions) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_more_reactions))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (MessagePolicy.canRetry(facts)) ActionRow(Icons.Rounded.Refresh, stringResource(R.string.action_retry), actions.onRetry)
            if (MessagePolicy.canReply(facts)) ActionRow(Icons.AutoMirrored.Rounded.Reply, stringResource(R.string.action_reply), actions.onReply)
            if (MessagePolicy.canCopy(facts)) ActionRow(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy), actions.onCopy)
            if (MessagePolicy.canForward(facts)) ActionRow(Icons.AutoMirrored.Rounded.Shortcut, stringResource(R.string.action_forward), actions.onForward)
            if (MessagePolicy.canStar(facts)) ActionRow(if (message.isStarred) Icons.Rounded.StarBorder else Icons.Rounded.Star,
                stringResource(if (message.isStarred) R.string.action_unstar else R.string.action_star), actions.onStar)
            if (MessagePolicy.canEdit(facts, now)) ActionRow(Icons.Rounded.Edit, stringResource(R.string.action_edit), actions.onEdit)
            actions.onEditHistory?.let { ActionRow(Icons.Rounded.History, stringResource(R.string.edit_history_title), it) }
            actions.onTranslate?.let { ActionRow(Icons.Rounded.Translate, stringResource(R.string.action_translate), it) }
            actions.onTranscribe?.let { ActionRow(Icons.Rounded.GraphicEq, stringResource(R.string.action_transcribe), it) }
            actions.onRemind?.let { ActionRow(Icons.Rounded.NotificationsActive, stringResource(R.string.action_remind_me), it) }
            ActionRow(Icons.Rounded.CheckCircle, stringResource(R.string.action_select), actions.onSelect)
            if (MessagePolicy.canShowInfo(facts)) ActionRow(Icons.Rounded.Info, stringResource(R.string.action_info), actions.onInfo)
            ActionRow(Icons.Rounded.Delete, stringResource(R.string.action_delete), actions.onDelete, destructive = true)
        }
    }
}

@Composable
private fun RowScope.ReactionCell(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = 46.dp).fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = Spacing.xxl), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.xl))
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
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
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
                                Icon(Icons.Rounded.Groups, contentDescription = null)
                            }
                        } else UserAvatar(title, avatarUrl = conv.avatarUrl, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, contentDescription = null,
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
    PollDialog(
        onCreateWithOptions = { q, opts, _, _ -> onCreate(q, opts) },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PollDialog(
    onCreateWithOptions: (question: String, options: List<String>, anonymous: Boolean, durationSeconds: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var anonymous by remember { mutableStateOf(false) }
    var durationSeconds by remember { mutableLongStateOf(0L) }
    val durationLabels = listOf(
        0L to stringResource(R.string.poll_expiration_none),
        900L to stringResource(R.string.poll_expiration_15m),
        3600L to stringResource(R.string.poll_expiration_1h),
        86_400L to stringResource(R.string.poll_expiration_24h),
        604_800L to stringResource(R.string.poll_expiration_7d),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.poll_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(PollPolicy.MAX_QUESTION_LENGTH) },
                    label = { Text(stringResource(R.string.poll_question)) },
                    modifier = Modifier.fillMaxWidth().testTag("poll_question_input"),
                )
                options.forEachIndexed { i, opt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = opt,
                            onValueChange = { options[i] = it.take(PollPolicy.MAX_OPTION_LENGTH) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.poll_option, i + 1)) },
                            modifier = Modifier.weight(1f).padding(top = 6.dp).testTag("poll_option_input_$i"),
                        )
                        if (options.size > PollPolicy.MIN_OPTIONS) {
                            IconButton(onClick = { options.removeAt(i) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
                if (options.size < PollPolicy.MAX_OPTIONS) {
                    TextButton(onClick = { options.add("") }) {
                        Text(stringResource(R.string.poll_add_option))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { anonymous = !anonymous }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.poll_anonymous_voting), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.poll_anonymous_voting_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = anonymous,
                        onCheckedChange = { anonymous = it },
                        modifier = Modifier.testTag("poll_anonymous_switch"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.poll_expiration_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    durationLabels.forEach { (secs, label) ->
                        FilterChip(
                            selected = durationSeconds == secs,
                            onClick = { durationSeconds = secs },
                            label = { Text(label) },
                            modifier = Modifier.testTag("poll_duration_$secs"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clean = PollPolicy.cleanOptions(options)
                    onCreateWithOptions(question.trim(), clean, anonymous, durationSeconds)
                },
                enabled = PollPolicy.isValidPoll(question, options),
                modifier = Modifier.testTag("poll_create_confirm"),
            ) { Text(stringResource(R.string.action_send), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun EditPollDialog(
    initialQuestion: String,
    initialOptions: List<String>,
    onSave: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var question by remember(initialQuestion) { mutableStateOf(initialQuestion) }
    val options = remember(initialOptions) {
        mutableStateListOf<String>().apply {
            addAll(if (initialOptions.size >= 2) initialOptions else listOf("", ""))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.poll_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.poll_edit_locked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(PollPolicy.MAX_QUESTION_LENGTH) },
                    label = { Text(stringResource(R.string.poll_question)) },
                    modifier = Modifier.fillMaxWidth().testTag("edit_poll_question_input"),
                )
                options.forEachIndexed { i, opt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = opt,
                            onValueChange = { options[i] = it.take(PollPolicy.MAX_OPTION_LENGTH) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.poll_option, i + 1)) },
                            modifier = Modifier.weight(1f).padding(top = 6.dp).testTag("edit_poll_option_input_$i"),
                        )
                        if (options.size > PollPolicy.MIN_OPTIONS) {
                            IconButton(onClick = { options.removeAt(i) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
                if (options.size < PollPolicy.MAX_OPTIONS) {
                    TextButton(onClick = { options.add("") }) {
                        Text(stringResource(R.string.poll_add_option))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clean = PollPolicy.cleanOptions(options)
                    onSave(question.trim(), clean)
                },
                enabled = PollPolicy.isValidPoll(question, options),
                modifier = Modifier.testTag("edit_poll_save"),
            ) { Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
