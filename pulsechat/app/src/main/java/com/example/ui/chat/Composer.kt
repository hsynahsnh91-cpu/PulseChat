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
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import com.example.ui.theme.Motion
import com.example.ui.theme.Radii
import com.example.ui.theme.Spacing
import com.example.ui.common.rememberPulseHaptics
import com.example.ui.common.rememberReducedMotion
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextDirection
import com.example.ui.common.isolateIfForeign
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity

@Composable
fun Composer(
    value: State<TextFieldValue>,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    emojiOpen: Boolean,
    onEmojiToggle: () -> Unit,
    recording: State<VoiceRecorder.State>,
    onStartRecording: () -> Unit,
    onFinishRecording: (send: Boolean) -> Unit,
    enterToSend: Boolean,
    isAiChat: Boolean,
    editing: Boolean,
    onSchedule: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    // Only the switch between typing and recording recomposes here; the level meter and the text
    // are read further down.
    val isRecording by remember { derivedStateOf { recording.value.recording } }
    AnimatedContent(
        targetState = isRecording,
        transitionSpec = { fadeIn(Motion.enter(Motion.QUICK)) togetherWith fadeOut(Motion.exit(Motion.QUICK)) },
        label = "composer-mode",
    ) { recordingNow ->
        if (recordingNow) RecordingBar(recording, onFinishRecording)
        else InputRow(value, onValueChange, onSend, onAttach, onCamera, emojiOpen, onEmojiToggle, onStartRecording, enterToSend, isAiChat, editing, onSchedule, focusRequester)
    }
}

/** Convenience overload for callers holding plain values (previews, tests). */
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
    onSchedule: (() -> Unit)? = null,
) = Composer(
    rememberUpdatedState(value), onValueChange, onSend, onAttach, onCamera, emojiOpen, onEmojiToggle, rememberUpdatedState(recording),
    onStartRecording, onFinishRecording, enterToSend, isAiChat, editing, onSchedule,
)

@Composable
private fun InputRow(
    value: State<TextFieldValue>,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    emojiOpen: Boolean,
    onEmojiToggle: () -> Unit,
    onStartRecording: () -> Unit,
    enterToSend: Boolean,
    isAiChat: Boolean,
    editing: Boolean,
    onSchedule: (() -> Unit)?,
    focusRequester: FocusRequester?,
) {
    val v = value.value
    val haptics = rememberPulseHaptics()
    // What is typed sets the direction (English in the Arabic UI and vice versa); the hint follows the UI.
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val inputStyle = textStyle.copy(textDirection = TextDirection.Content)
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        // Growing from one line to several is a size change: spring it (critically damped, so the
        // text never overshoots) instead of snapping the pill taller.
        Surface(shape = RoundedCornerShape(Radii.lg), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).animateContentSize(Motion.settle())) {
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onEmojiToggle) {
                    Icon(if (emojiOpen) Icons.Rounded.Keyboard else Icons.Rounded.EmojiEmotions,
                        contentDescription = stringResource(if (emojiOpen) R.string.action_keyboard else R.string.action_emoji),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val placeholder = stringResource(if (isAiChat) R.string.ai_composer_hint else R.string.composer_hint)
                BasicTextField(
                    value = v, onValueChange = onValueChange,
                    textStyle = inputStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { haptics.confirm(); onSend() }),
                    modifier = Modifier.weight(1f).let { if (focusRequester != null) it.focusRequester(focusRequester) else it }.testTag("composer_input"),
                    decorationBox = { inner ->
                        Box(Modifier.heightIn(min = 48.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (v.text.isEmpty()) {
                                Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    },
                )
                if (!isAiChat && !editing) {
                    IconButton(onClick = onAttach, modifier = Modifier.testTag("composer_attach")) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.action_attach), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (v.text.isEmpty()) IconButton(onClick = onCamera) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = stringResource(R.string.attach_camera), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else if (onSchedule != null && v.text.isNotBlank()) {
                        IconButton(onClick = onSchedule, modifier = Modifier.testTag("composer_schedule")) {
                            Icon(Icons.Rounded.Schedule, contentDescription = stringResource(R.string.schedule_title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else Spacer(Modifier.width(Spacing.xs))
                } else Spacer(Modifier.width(Spacing.md))
            }
        }
        Spacer(Modifier.width(6.dp))
        val showSend = v.text.isNotBlank() || isAiChat || editing
        // Sending clears the text, which turns this button into the mic; a fast double tap must not
        // start a voice recording right after a message was sent.
        var lastSendAt by remember { mutableLongStateOf(0L) }
        FilledIconButton(
            onClick = {
                val now = SystemClock.uptimeMillis()
                if (showSend) { lastSendAt = now; haptics.confirm(); onSend() }
                else if (now - lastSendAt > 700) { haptics.tick(); onStartRecording() }
            },
            enabled = !showSend || v.text.isNotBlank(),
            modifier = Modifier.size(48.dp).testTag(if (showSend) "composer_send" else "composer_mic"),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            // Mic ↔ send: a quick scale/fade so the change is noticed without slowing anything down.
            AnimatedContent(
                targetState = showSend,
                transitionSpec = {
                    (fadeIn(Motion.enter(Motion.QUICK)) + scaleIn(Motion.enter(Motion.QUICK), initialScale = 0.6f)) togetherWith
                        (fadeOut(Motion.exit(Motion.QUICK)) + scaleOut(Motion.exit(Motion.QUICK), targetScale = 0.6f))
                },
                label = "send-mic",
            ) { send ->
                Icon(
                    if (send) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic,
                    contentDescription = stringResource(if (send) (if (editing) R.string.action_save else R.string.action_send) else R.string.action_record_voice),
                )
            }
        }
    }
}

@Composable
private fun RecordingBar(recording: State<VoiceRecorder.State>, onFinish: (Boolean) -> Unit) {
    val state = recording.value
    val haptics = rememberPulseHaptics()
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { haptics.tick(); onFinish(false) }) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.voice_discard), tint = MaterialTheme.colorScheme.error) }
        RecordingDot()
        Spacer(Modifier.width(Spacing.sm))
        Text(DurationFormat.clock(state.elapsedMs), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(Spacing.md))
        Row(Modifier.weight(1f).height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val levels = state.levels.takeLast(40)
            levels.forEach { level ->
                Box(Modifier.weight(1f).height((4 + level * 0.3f).dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
            repeat((40 - levels.size).coerceAtLeast(0)) { Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) }
        }
        Spacer(Modifier.width(Spacing.sm))
        FilledIconButton(onClick = { haptics.confirm(); onFinish(true) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.voice_send))
        }
    }
}

/** Red dot that pulses while the microphone is recording (static when animations are turned off). */
@Composable
private fun RecordingDot() {
    val reduced = rememberReducedMotion()
    val alpha = if (reduced) 1f else {
        val transition = rememberInfiniteTransition(label = "rec")
        val a by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700, easing = Motion.StandardEasing), RepeatMode.Reverse), label = "rec-alpha")
        a
    }
    Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(MaterialTheme.colorScheme.error))
}

/** Reply / edit preview shown right above the composer. */
@Composable
fun ReplyOrEditBar(title: String, preview: String, editing: Boolean, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(Radii.lg),
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.xs),
    ) {
        Row(Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (editing) Icons.Rounded.Edit else Icons.AutoMirrored.Rounded.Reply, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Row(Modifier.weight(1f).clip(RoundedCornerShape(Radii.sm)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)).height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                Column(Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp)) {
                    Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(isolateIfForeign(preview, LocalLayoutDirection.current == LayoutDirection.Rtl), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
fun MentionSuggestions(candidates: State<List<Mentions.Candidate>>, participants: List<com.example.data.model.User>, onPick: (Mentions.Candidate) -> Unit) {
    val list = candidates.value
    if (list.isEmpty()) return
    val avatars = remember(participants) { participants.associate { it.userId to it.avatarUrl } }
    val allLabel = stringResource(R.string.mention_all_label)
    val allSub = stringResource(R.string.mention_all_subtitle)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(Radii.lg), shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs).heightIn(max = 220.dp),
    ) {
        LazyColumn {
            items(list, key = { it.userId }) { c ->
                val isAll = c.userId == Mentions.ALL_ID
                val displayLabel = if (isAll) "@${Mentions.ALL_ID} · $allLabel" else c.displayName
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPick(if (isAll) Mentions.Candidate(Mentions.ALL_ID, Mentions.ALL_ID) else c)
                        }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = Spacing.lg, vertical = 6.dp)
                        .testTag("mention_suggestion_${c.userId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isAll) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        UserAvatar(c.displayName, avatarUrl = avatars[c.userId], size = 32.dp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isAll) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isAll) {
                            Text(
                                allSub,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
            add(Triple(AttachOption.GALLERY, Icons.Rounded.Image, R.string.attach_gallery))
            add(Triple(AttachOption.CAMERA_PHOTO, Icons.Rounded.CameraAlt, R.string.attach_camera))
            add(Triple(AttachOption.CAMERA_VIDEO, Icons.Rounded.Videocam, R.string.attach_video))
            add(Triple(AttachOption.DOCUMENT, Icons.Rounded.Description, R.string.attach_document))
            add(Triple(AttachOption.CONTACT, Icons.Rounded.Person, R.string.attach_contact))
            add(Triple(AttachOption.LOCATION, Icons.Rounded.LocationOn, R.string.attach_location))
            if (showPoll) add(Triple(AttachOption.POLL, Icons.Rounded.Poll, R.string.attach_poll))
            if (showAi) add(Triple(AttachOption.AI_TOOLS, Icons.Rounded.AutoAwesome, R.string.ai_tools))
        }
        // Three columns on narrow screens or with large text, so labels such as "Document" are not split.
        BoxWithConstraints(Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.xxl)) {
            val columns = if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.15f) 3 else 4
            Column {
                options.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { (opt, icon, label) -> AttachTile(icon, stringResource(label), Modifier.weight(1f)) { onPick(opt) } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }
    }
}

@Composable
private fun AttachTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(Radii.md)).clickable(onClick = onClick).padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        // Two lines so longer labels (and Arabic, or large text) wrap instead of being cut off.
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
