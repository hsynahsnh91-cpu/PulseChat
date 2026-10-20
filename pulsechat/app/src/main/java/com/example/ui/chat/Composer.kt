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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.DurationFormat
import com.example.domain.Mentions
import com.example.media.VoiceRecorder
import com.example.ui.components.UserAvatar

@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    emojiOpen: Boolean,
    onEmojiToggle: () -> Unit,
    recording: VoiceRecorder.State,
    onStartRecording: () -> Unit,
    onFinishRecording: (send: Boolean) -> Unit,
    enterToSend: Boolean,
    isAiChat: Boolean,
    editing: Boolean,
) {
    if (recording.recording) { RecordingBar(recording, onFinishRecording); return }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onEmojiToggle) {
                    Icon(if (emojiOpen) Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                        contentDescription = stringResource(if (emojiOpen) R.string.action_keyboard else R.string.action_emoji))
                }
                TextField(
                    value = value, onValueChange = onValueChange,
                    placeholder = { Text(stringResource(if (isAiChat) R.string.ai_composer_hint else R.string.composer_hint)) },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("composer_input"),
                )
                if (!isAiChat && !editing) {
                    IconButton(onClick = onAttach, modifier = Modifier.testTag("composer_attach")) { Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.action_attach)) }
                    if (value.text.isEmpty()) IconButton(onClick = onCamera) { Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.attach_camera)) }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        val showSend = value.text.isNotBlank() || isAiChat || editing
        FilledIconButton(
            onClick = { if (showSend) onSend() else onStartRecording() },
            enabled = !showSend || value.text.isNotBlank(),
            modifier = Modifier.size(52.dp).testTag(if (showSend) "composer_send" else "composer_mic"),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                if (showSend) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                contentDescription = stringResource(if (showSend) (if (editing) R.string.action_save else R.string.action_send) else R.string.action_record_voice),
            )
        }
    }
}

@Composable
private fun RecordingBar(state: VoiceRecorder.State, onFinish: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onFinish(false) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.voice_discard), tint = MaterialTheme.colorScheme.error) }
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Spacer(Modifier.width(8.dp))
        Text(DurationFormat.clock(state.elapsedMs), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f).height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val levels = state.levels.takeLast(40)
            levels.forEach { level ->
                Box(Modifier.weight(1f).height((4 + level * 0.3f).dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
            }
            repeat((40 - levels.size).coerceAtLeast(0)) { Box(Modifier.weight(1f).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant)) }
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = { onFinish(true) }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.voice_send))
        }
    }
}

@Composable
fun ReplyOrEditBar(title: String, preview: String, editing: Boolean, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 12.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (editing) Icons.Default.Edit else Icons.AutoMirrored.Filled.Reply, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)).padding(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel)) }
    }
}

@Composable
fun MentionSuggestions(candidates: List<Mentions.Candidate>, avatars: Map<String, String?>, onPick: (Mentions.Candidate) -> Unit) {
    if (candidates.isEmpty()) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
        LazyColumn {
            items(candidates, key = { it.userId }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onPick(c) }.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(c.displayName, avatarUrl = avatars[c.userId], size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(c.displayName, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

enum class AttachOption { GALLERY, CAMERA_PHOTO, CAMERA_VIDEO, DOCUMENT, CONTACT, LOCATION, POLL, AI_TOOLS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(showPoll: Boolean, showAi: Boolean, onDismiss: () -> Unit, onPick: (AttachOption) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val options = buildList {
            add(Triple(AttachOption.GALLERY, Icons.Default.Image, R.string.attach_gallery))
            add(Triple(AttachOption.CAMERA_PHOTO, Icons.Default.CameraAlt, R.string.attach_camera))
            add(Triple(AttachOption.CAMERA_VIDEO, Icons.Default.Videocam, R.string.attach_video))
            add(Triple(AttachOption.DOCUMENT, Icons.Default.Description, R.string.attach_document))
            add(Triple(AttachOption.CONTACT, Icons.Default.Person, R.string.attach_contact))
            add(Triple(AttachOption.LOCATION, Icons.Default.LocationOn, R.string.attach_location))
            if (showPoll) add(Triple(AttachOption.POLL, Icons.Default.Poll, R.string.attach_poll))
            if (showAi) add(Triple(AttachOption.AI_TOOLS, Icons.Default.AutoAwesome, R.string.ai_tools))
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 32.dp)) {
            options.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { (opt, icon, label) -> AttachTile(icon, stringResource(label)) { onPick(opt) } }
                    repeat(4 - row.size) { Spacer(Modifier.width(76.dp)) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AttachTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
