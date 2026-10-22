package com.example.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.location.LiveLocationBubble
import com.example.ui.location.LiveLocationSupport
import com.example.R
import com.example.data.model.Message
import com.example.domain.DurationFormat
import com.example.domain.MediaLimits
import com.example.domain.Mentions
import com.example.domain.TextTools
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.domain.model.TransferState
import com.example.media.VoicePlayer
import com.example.ui.common.AiBadge
import com.example.ui.common.DeliveryTicks
import com.example.ui.common.Formatters
import com.example.ui.common.typeLabel
import com.example.ui.theme.PulseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.min
import com.example.ui.common.rememberLocalFile
import com.example.ui.theme.Spacing
import com.example.ui.theme.LocalLiteMode
import com.example.ui.theme.Radii
import com.example.ui.theme.MessageTextStyle
import com.example.ui.theme.EmojiOnlyTextStyle
import com.example.ui.common.isolateIfForeign
import com.example.ui.common.ltrIsolate
import androidx.compose.material.icons.automirrored.rounded.Shortcut

class BubbleCallbacks(
    val onClick: (Message) -> Unit,
    val onLongClick: (Message) -> Unit,
    val onSwipeReply: (Message) -> Unit,
    val onReplyQuote: (String) -> Unit,
    val onRetry: (Message) -> Unit,
    val onDownload: (Message) -> Unit,
    val onCancel: (Message) -> Unit,
    val onOpenMedia: (Message) -> Unit,
    val onOpenDocument: (Message) -> Unit,
    val onOpenLocation: (Message) -> Unit,
    val onDialContact: (Message) -> Unit,
    val onToggleVoice: (Message) -> Unit,
    val onSeekVoice: (Message, Float) -> Unit,
    val onVoiceSpeed: () -> Unit,
    val onVote: (Message, Int) -> Unit,
    val onReactions: (Message) -> Unit,
    /** Opens a chat with the PulseChat user of a shared contact card. */
    val onMessageContact: (Message) -> Unit = {},
    /** Opens the earlier versions of an edited message. */
    val onShowEdits: (Message) -> Unit = {},
    /** Live-location sessions (map, last update, stop); without it the starting point is shown. */
    val live: LiveLocationSupport? = null,
)

private val REACTION_SHAPE = RoundedCornerShape(50)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    item: MessageItem,
    me: String,
    names: Map<String, String>,
    isGroup: Boolean,
    isAi: Boolean,
    hideTicks: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    highlighted: Boolean,
    searchQuery: String,
    /** Read only by voice messages, so playback progress recomposes just those bubbles. */
    voice: State<VoicePlayer.State>,
    showReadReceipts: Boolean,
    canSwipeReply: Boolean,
    callbacks: BubbleCallbacks,
) {
    val m = item.message
    if (m.type == MessageType.SYSTEM) { SystemChip(m, me, names); return }
    val mine = m.senderId == me
    val pulse = PulseTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var drag by remember(m.messageId) { mutableFloatStateOf(0f) }
    val animatedDrag by animateFloatAsState(drag, label = "swipe")
    val rowBackground = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Box(
        Modifier.fillMaxWidth().background(rowBackground)
            .combinedClickable(onClick = { callbacks.onClick(m) }, onLongClick = { callbacks.onLongClick(m) })
            .let { mod ->
                if (!canSwipeReply || selectionMode || m.deletedForEveryone) mod
                else mod.pointerInput(m.messageId) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (kotlin.math.abs(drag) > 140f) callbacks.onSwipeReply(m); drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { change, amount ->
                        val directed = if (rtl) -amount else amount
                        drag = if (rtl) (drag - directed).coerceIn(-220f, 0f) else (drag + directed).coerceIn(0f, 220f)
                        change.consume()
                    }
                }
            }
            .padding(horizontal = Spacing.sm, vertical = if (item.groupedWithOlder) 1.dp else 3.dp),
    ) {
        if (animatedDrag != 0f) {
            Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, tint = pulse.metaText,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm).size(22.dp)
                    .graphicsLayer { alpha = (kotlin.math.abs(animatedDrag) / 140f).coerceIn(0f, 1f) })
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().offset { IntOffset(animatedDrag.roundToInt(), 0) },
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            // Comfortable reading width: most of a phone screen, capped on tablets and in landscape.
            val maxBubble = min(maxWidth * 0.84f, Spacing.bubbleMaxWidth)
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = maxBubble)) {
                Bubble(item, mine, names, isGroup, isAi, hideTicks, searchQuery, voice, showReadReceipts, callbacks, me)
                val reactions = remember(m.reactions) { m.reactionMap() }
                if (reactions.isNotEmpty()) {
                    // The chip overlaps the bubble's lower edge; the ring in the chat background colour
                    // separates them without a shadow.
                    Surface(
                        shape = REACTION_SHAPE, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(2.dp, pulse.chatBackground),
                        modifier = Modifier.offset(y = (-4).dp).padding(horizontal = Spacing.sm).clip(REACTION_SHAPE).clickable { callbacks.onReactions(m) },
                    ) {
                        val distinct = remember(reactions) { reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value } }
                        Row(Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            distinct.take(3).forEach { Text(it.key, fontSize = 14.sp) }
                            if (reactions.size > 1) Text(" ${reactions.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (mine && m.status == MessageStatus.FAILED) {
                    TextButton(onClick = { callbacks.onRetry(m) }) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(when (m.failureReason) {
                            "denied" -> R.string.message_not_allowed_retry
                            "too_large" -> R.string.message_too_large_retry
                            else -> R.string.message_failed_retry
                        }),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Convenience overload for callers holding a plain playback state (previews, tests). */
@Composable
fun MessageRow(
    item: MessageItem, me: String, names: Map<String, String>, isGroup: Boolean, isAi: Boolean, hideTicks: Boolean,
    selected: Boolean, selectionMode: Boolean, highlighted: Boolean, searchQuery: String, voice: VoicePlayer.State,
    showReadReceipts: Boolean, canSwipeReply: Boolean, callbacks: BubbleCallbacks,
) = MessageRow(
    item, me, names, isGroup, isAi, hideTicks, selected, selectionMode, highlighted, searchQuery, rememberUpdatedState(voice),
    showReadReceipts, canSwipeReply, callbacks,
)

/** Colours of one bubble: background, text, secondary text (time, ticks, labels) and links. */
@Immutable
private data class BubbleColors(val container: Color, val content: Color, val meta: Color, val link: Color)

@Composable
@ReadOnlyComposable
private fun bubbleColors(type: MessageType, mine: Boolean): BubbleColors {
    val pulse = PulseTheme.colors
    return when {
        type == MessageType.AI_RESPONSE -> BubbleColors(pulse.aiBubble, pulse.onAiBubble, pulse.incomingMeta, pulse.incomingLink)
        mine -> BubbleColors(pulse.outgoingBubble, pulse.onOutgoingBubble, pulse.outgoingMeta, pulse.outgoingLink)
        else -> BubbleColors(pulse.incomingBubble, pulse.onIncomingBubble, pulse.incomingMeta, pulse.incomingLink)
    }
}

private val LocalBubbleColors = staticCompositionLocalOf { BubbleColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified) }

@Composable
private fun Bubble(
    item: MessageItem, mine: Boolean, names: Map<String, String>, isGroup: Boolean, isAi: Boolean, hideTicks: Boolean,
    searchQuery: String, voice: State<VoicePlayer.State>, showReadReceipts: Boolean, callbacks: BubbleCallbacks, me: String,
) {
    val m = item.message
    val colors = bubbleColors(m.type, mine)
    val big = Radii.bubble
    val tail = Radii.bubbleTail
    val shape = if (item.groupedWithOlder) RoundedCornerShape(big) else if (mine)
        RoundedCornerShape(topStart = big, topEnd = tail, bottomStart = big, bottomEnd = big)
    else RoundedCornerShape(topStart = tail, topEnd = big, bottomStart = big, bottomEnd = big)
    // Light theme: a hairline shadow separates white bubbles from the light background. Dark theme, and
    // older or low-memory phones (where every shadow costs scrolling smoothness): none.
    val shadow = !PulseTheme.colors.isDark && !LocalLiteMode.current
    CompositionLocalProvider(LocalBubbleColors provides colors) {
        Surface(shape = shape, color = colors.container, contentColor = colors.content, shadowElevation = if (shadow) 0.5.dp else 0.dp) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
                if (item.showSender) {
                    val name = names[m.senderId] ?: stringResource(R.string.unknown_user)
                    Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = senderColor(m.senderId),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                }
                if (m.type == MessageType.AI_RESPONSE) { AiBadge(); Spacer(Modifier.height(Spacing.xs)) }
                if (m.isForwarded && !m.deletedForEveryone) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.Shortcut, contentDescription = null, tint = colors.meta, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.forwarded), style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = colors.meta)
                    }
                }
                if (m.replyToMessageId != null && !m.deletedForEveryone) {
                    ReplyQuote(
                        senderName = if (m.replyToSenderId == me) stringResource(R.string.you) else names[m.replyToSenderId] ?: stringResource(R.string.unknown_user),
                        senderId = m.replyToSenderId.orEmpty(), type = m.replyToType ?: MessageType.TEXT, preview = m.replyToPreview.orEmpty(),
                        onClick = { callbacks.onReplyQuote(m.replyToMessageId) },
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
                val footer: @Composable () -> Unit = { Footer(m, mine, hideTicks || isAi, showReadReceipts, onEdited = { callbacks.onShowEdits(m) }) }
                if (m.deletedForEveryone) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Block, contentDescription = null, tint = colors.meta, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (mine) R.string.message_deleted_by_you else R.string.message_deleted), fontStyle = FontStyle.Italic,
                            color = colors.meta, style = MessageTextStyle)
                    }
                    Box(Modifier.align(Alignment.End).padding(top = 2.dp)) { footer() }
                } else {
                    when (m.type) {
                        MessageType.IMAGE, MessageType.VIDEO -> VisualMedia(m, mine, callbacks)
                        MessageType.AUDIO -> VoiceMessage(m, mine, voice, callbacks)
                        MessageType.DOCUMENT -> DocumentContent(m, mine, callbacks)
                        MessageType.LOCATION -> if (m.liveId != null) LiveLocationBubble(m, mine, callbacks.live, colors.meta) else LocationContent(m, callbacks)
                        MessageType.CONTACT -> ContactContent(m, callbacks)
                        MessageType.POLL -> PollContent(m, me, callbacks)
                        else -> Unit
                    }
                    val caption = if (m.type == MessageType.POLL || m.type == MessageType.CONTACT || m.type == MessageType.DOCUMENT && m.content == m.fileName) "" else m.content
                    if (caption.isNotBlank()) {
                        if (m.type.isMedia) Spacer(Modifier.height(Spacing.xs))
                        TextWithFooter(footer) { onLayout -> RichText(caption, m, names, searchQuery, onLayout) }
                    } else {
                        Box(Modifier.align(Alignment.End).padding(top = 2.dp)) { footer() }
                    }
                }
            }
        }
    }
}

/**
 * Message text with its time/ticks footer. The footer sits at the end of the last line when it fits
 * there (as in most messengers) and moves to its own line otherwise. It also moves to its own line
 * when the paragraph's direction differs from the layout's (e.g. Arabic text in the English UI), so
 * it can never overlap the text.
 */
@Composable
private fun TextWithFooter(footer: @Composable () -> Unit, text: @Composable (onLayout: (TextLayoutResult) -> Unit) -> Unit) {
    // Written by onTextLayout while the text is measured (inside this layout's measure pass) and read
    // right after; a plain holder rather than State, so measuring never schedules another measure.
    val layoutHolder = remember { arrayOfNulls<TextLayoutResult>(1) }
    val direction = LocalLayoutDirection.current
    Layout(content = {
        Box { text { layoutHolder[0] = it } }
        Box { footer() }
    }) { measurables, constraints ->
        val textPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        val footerPlaceable = measurables[1].measure(constraints.copy(minWidth = 0))
        val gap = 6.dp.roundToPx()
        val result = layoutHolder[0]
        val lastLine = result?.let { it.lineCount - 1 } ?: -1
        val sameDirection = result != null && lastLine >= 0 &&
            (result.getParagraphDirection(result.getLineStart(lastLine)) == ResolvedTextDirection.Rtl) == (direction == LayoutDirection.Rtl)
        val lastLineWidth = if (result != null && lastLine >= 0) (result.getLineRight(lastLine) - result.getLineLeft(lastLine)).roundToInt() else textPlaceable.width
        val inlineWidth = lastLineWidth + gap + footerPlaceable.width
        val inline = sameDirection && inlineWidth <= constraints.maxWidth
        val width = if (inline) maxOf(textPlaceable.width, inlineWidth) else maxOf(textPlaceable.width, footerPlaceable.width)
        val footerY = if (inline) {
            // Align the footer's baseline area with the bottom of the last text line.
            (textPlaceable.height - footerPlaceable.height).coerceAtLeast(0)
        } else textPlaceable.height + 2.dp.roundToPx()
        val height = if (inline) maxOf(textPlaceable.height, footerPlaceable.height) else footerY + footerPlaceable.height
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            textPlaceable.placeRelative(0, 0)
            footerPlaceable.placeRelative(width - footerPlaceable.width, footerY)
        }
    }
}

@Composable
@ReadOnlyComposable
private fun senderColor(id: String): Color = PulseTheme.colors.senderColor(id)

/** Secondary text colour for the bubble being drawn (falls back to the screen's secondary text). */
@Composable
@ReadOnlyComposable
private fun bubbleMeta(): Color = LocalBubbleColors.current.meta.takeIf { it.isSpecified } ?: PulseTheme.colors.metaText

@Composable
private fun RichText(text: String, m: Message, names: Map<String, String>, searchQuery: String, onLayout: (TextLayoutResult) -> Unit) {
    val pulse = PulseTheme.colors
    val colors = LocalBubbleColors.current
    val link = if (colors.link.isSpecified) colors.link else pulse.mention
    val emojiOnly = remember(text) { TextTools.isEmojiOnly(text) }
    val annotated: AnnotatedString = remember(text, m.mentions, names, searchQuery, link, pulse.searchMatch) {
        val mentionRanges = Mentions.highlightRanges(text, m.mentionList().mapNotNull { id -> names[id]?.let { Mentions.Candidate(id, it) } })
        val searchRanges = if (searchQuery.isBlank()) emptyList() else TextTools.occurrences(text, searchQuery)
        val links = TextTools.findLinks(text)
        buildAnnotatedString {
            append(text)
            mentionRanges.forEach { r -> addStyle(SpanStyle(color = link, fontWeight = FontWeight.SemiBold), r.first, r.last + 1) }
            searchRanges.forEach { r -> addStyle(SpanStyle(background = pulse.searchMatch), r.first, r.last + 1) }
            links.forEach { l ->
                addLink(LinkAnnotation.Url(l.url, TextLinkStyles(style = SpanStyle(color = link, textDecoration = TextDecoration.Underline))), l.range.first, l.range.last + 1)
            }
        }
    }
    Text(annotated, style = if (emojiOnly) EmojiOnlyTextStyle else MessageTextStyle, onTextLayout = onLayout)
}

@Composable
private fun Footer(m: Message, mine: Boolean, hideTicks: Boolean, showReadReceipts: Boolean, onEdited: (() -> Unit)? = null) {
    val context = LocalContext.current
    val meta = LocalBubbleColors.current.meta.takeIf { it.isSpecified } ?: PulseTheme.colors.metaText
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (m.expiresAt > 0) Icon(Icons.Rounded.Timer, contentDescription = stringResource(R.string.disappearing_title), tint = meta, modifier = Modifier.size(12.dp).padding(end = 2.dp))
        if (m.isStarred) Icon(Icons.Rounded.Star, contentDescription = stringResource(R.string.starred), tint = meta, modifier = Modifier.size(12.dp).padding(end = 2.dp))
        if (m.editedAt > 0 && !m.deletedForEveryone) {
            // "edited" opens the earlier versions (also available from the message's actions).
            val historyLabel = stringResource(R.string.a11y_edited_show_history)
            Text(
                stringResource(R.string.edited) + " ", style = MaterialTheme.typography.labelSmall, color = meta, fontStyle = FontStyle.Italic,
                modifier = if (onEdited == null) Modifier else Modifier.testTag("edited_label")
                    .clickable(onClickLabel = historyLabel, role = Role.Button, onClick = onEdited)
                    .semantics { contentDescription = historyLabel },
            )
        }
        Text(Formatters.time(context, m.createdAt), style = MaterialTheme.typography.labelSmall, color = meta, maxLines = 1)
        if (mine && !hideTicks && !m.deletedForEveryone) {
            Spacer(Modifier.width(3.dp))
            DeliveryTicks(m.status, size = 15.dp, showRead = showReadReceipts, tint = meta)
        }
    }
}

@Composable
fun ReplyQuote(senderName: String, senderId: String, type: MessageType, preview: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val accent = senderColor(senderId)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.sm)).background(accent.copy(alpha = 0.12f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.heightIn(min = 44.dp).height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            Text(senderName, color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = typeLabel(type)
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Text(
                listOfNotNull(label, preview.ifBlank { null }?.let { isolateIfForeign(it, rtl) }).joinToString(" · ")
                    .ifBlank { stringResource(R.string.message_unavailable) },
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------- media

@Composable
private fun TransferOverlay(m: Message, mine: Boolean, callbacks: BubbleCallbacks, modifier: Modifier = Modifier) {
    val local = rememberLocalFile(m.localMediaPath) != null
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            m.transferState == TransferState.UPLOADING || m.transferState == TransferState.DOWNLOADING -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { m.transferProgress / 100f }, modifier = Modifier.size(48.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { callbacks.onCancel(m) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.White)
                }
            }
            mine && m.status == MessageStatus.FAILED && m.mediaUrl == null -> RoundAction(Icons.Rounded.Refresh, stringResource(R.string.action_retry)) { callbacks.onRetry(m) }
            !local && m.mediaUrl != null -> RoundAction(Icons.Rounded.Download, stringResource(R.string.action_download) + " · " + MediaLimits.humanSize(m.mediaSizeBytes)) { callbacks.onDownload(m) }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun VisualMedia(m: Message, mine: Boolean, callbacks: BubbleCallbacks) {
    val local = rememberLocalFile(m.localMediaPath)
    val ratio = if (m.mediaWidth > 0 && m.mediaHeight > 0) (m.mediaWidth.toFloat() / m.mediaHeight).coerceIn(0.6f, 1.7f) else 1f
    Box(
        Modifier.widthIn(min = 180.dp, max = 280.dp).aspectRatio(ratio).clip(RoundedCornerShape(Radii.sm)).background(PulseTheme.colors.mediaPlaceholder)
            .clickable(enabled = local != null || m.type == MessageType.VIDEO && m.mediaUrl != null) { callbacks.onOpenMedia(m) },
        contentAlignment = Alignment.Center,
    ) {
        if (m.type == MessageType.IMAGE) {
            if (local != null) AsyncImage(model = File(local), contentDescription = m.content.ifBlank { stringResource(R.string.preview_photo) }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), modifier = Modifier.size(56.dp))
        } else {
            // The cover chosen by the sender travels with the message, so it shows at once (even before
            // the download); once the video is on this phone, the same frame is shown at full sharpness.
            val thumb by produceState<Bitmap?>(null, local, m.mediaThumb, m.mediaCoverMs) {
                value = m.mediaThumb?.let { t ->
                    withContext(Dispatchers.IO) { runCatching { Base64.decode(t, Base64.NO_WRAP).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }.getOrNull() }
                }
                if (local != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val r = MediaMetadataRetriever()
                            try {
                                r.setDataSource(local)
                                if (m.mediaCoverMs > 0) r.getFrameAtTime(m.mediaCoverMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) else r.getFrameAtTime(0)
                            } finally { r.release() }
                        }.getOrNull()
                    }?.let { value = it }
                }
            }
            thumb?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (m.transferState != TransferState.UPLOADING && m.transferState != TransferState.DOWNLOADING && (local != null || m.mediaUrl != null)) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.action_play), tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            if (m.mediaDurationSeconds > 0) {
                Text(DurationFormat.clock(m.mediaDurationSeconds * 1000L), color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape).padding(horizontal = 6.dp))
            }
        }
        if (m.type == MessageType.IMAGE || local == null) TransferOverlay(m, mine, callbacks)
    }
}

@Composable
private fun VoiceMessage(m: Message, mine: Boolean, voiceState: State<VoicePlayer.State>, callbacks: BubbleCallbacks) {
    val bubbleMeta = bubbleMeta()
    val voice = voiceState.value
    val current = voice.messageId == m.messageId
    val duration = if (current && voice.durationMs > 0) voice.durationMs else m.mediaDurationSeconds * 1000L
    val progress = if (current && duration > 0) (voice.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val available = rememberLocalFile(m.localMediaPath) != null || m.mediaUrl != null
    val bars = remember(m.waveform) { m.waveform?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() } ?: List(32) { 20 } }
    Row(Modifier.widthIn(min = 220.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when {
                m.transferState == TransferState.UPLOADING || m.transferState == TransferState.DOWNLOADING -> {
                    CircularProgressIndicator(progress = { m.transferProgress / 100f }, modifier = Modifier.size(36.dp))
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel), modifier = Modifier.size(18.dp).clickable { callbacks.onCancel(m) })
                }
                mine && m.status == MessageStatus.FAILED && m.mediaUrl == null ->
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_retry), modifier = Modifier.size(32.dp).clickable { callbacks.onRetry(m) })
                current && voice.preparing -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                available -> Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { callbacks.onToggleVoice(m) }, contentAlignment = Alignment.Center) {
                    Icon(if (current && voice.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (current && voice.playing) R.string.action_pause else R.string.action_play), tint = MaterialTheme.colorScheme.onPrimary)
                }
                else -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = bubbleMeta)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().height(32.dp).pointerInput(m.messageId, current) {
                    detectTapGestures { offset -> if (current) callbacks.onSeekVoice(m, offset.x / size.width) }
                },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                bars.forEachIndexed { i, level ->
                    val played = (i.toFloat() / bars.size) < progress
                    Box(Modifier.weight(1f).height((4 + level * 0.26f).dp).clip(CircleShape)
                        .background(if (played) MaterialTheme.colorScheme.primary else bubbleMeta.copy(alpha = 0.45f)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DurationFormat.clock(if (current && voice.positionMs > 0) voice.positionMs else duration), style = MaterialTheme.typography.labelSmall, color = bubbleMeta)
                if (current) {
                    val speedLabel = stringResource(R.string.voice_speed)
                    Spacer(Modifier.width(8.dp))
                    Text("${if (voice.speed == 1f) "1" else voice.speed.toString().removeSuffix(".0")}×", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).background(bubbleMeta.copy(alpha = 0.2f)).clickable { callbacks.onVoiceSpeed() }
                            .padding(horizontal = 6.dp, vertical = 1.dp).semantics { contentDescription = speedLabel })
                }
            }
        }
    }
}

@Composable
private fun DocumentContent(m: Message, mine: Boolean, callbacks: BubbleCallbacks) {
    val bubbleMeta = bubbleMeta()
    val local = rememberLocalFile(m.localMediaPath) != null
    Row(
        Modifier.widthIn(min = 220.dp).clip(RoundedCornerShape(Radii.sm)).background(bubbleMeta.copy(alpha = 0.12f))
            .clickable { if (local) callbacks.onOpenDocument(m) else if (m.mediaUrl != null) callbacks.onDownload(m) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(m.fileName ?: stringResource(R.string.preview_document), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val ext = m.fileName?.substringAfterLast('.', "")?.uppercase().orEmpty()
            Text(listOf(MediaLimits.humanSize(m.mediaSizeBytes), ext).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = bubbleMeta)
            if (m.transferState == TransferState.UPLOADING || m.transferState == TransferState.DOWNLOADING) {
                LinearProgressIndicator(progress = { m.transferProgress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        when {
            m.transferState == TransferState.UPLOADING || m.transferState == TransferState.DOWNLOADING ->
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel), modifier = Modifier.size(24.dp).clickable { callbacks.onCancel(m) })
            mine && m.status == MessageStatus.FAILED && m.mediaUrl == null ->
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_retry), modifier = Modifier.size(24.dp).clickable { callbacks.onRetry(m) })
            !local && m.mediaUrl != null -> Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.action_download), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun LocationContent(m: Message, callbacks: BubbleCallbacks) {
    val bubbleMeta = bubbleMeta()
    Row(
        Modifier.widthIn(min = 220.dp).clip(RoundedCornerShape(Radii.sm)).background(bubbleMeta.copy(alpha = 0.12f)).clickable { callbacks.onOpenLocation(m) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stringResource(R.string.location_shared), fontWeight = FontWeight.Medium)
            Text(String.format(java.util.Locale.US, "%.5f, %.5f", m.locationLat ?: 0.0, m.locationLng ?: 0.0), style = MaterialTheme.typography.labelSmall, color = bubbleMeta)
            Text(stringResource(R.string.location_open_maps), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ContactContent(m: Message, callbacks: BubbleCallbacks) {
    val bubbleMeta = bubbleMeta()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Cards shared from PulseChat carry the person's @username and open a chat with them; older
    // cards may carry a phone number, which can still be called.
    val pulseUser = ContactSearch.isPulseHandle(m.contactPhone)
    Column(Modifier.widthIn(min = 220.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(m.contactName ?: m.content, fontWeight = FontWeight.SemiBold)
                if (!m.contactPhone.isNullOrBlank()) Text(ltrIsolate(m.contactPhone, rtl), style = MaterialTheme.typography.labelMedium, color = bubbleMeta)
            }
        }
        when {
            pulseUser -> TextButton(onClick = { callbacks.onMessageContact(m) }) { Text(stringResource(R.string.contact_message)) }
            !m.contactPhone.isNullOrBlank() -> TextButton(onClick = { callbacks.onDialContact(m) }) { Text(stringResource(R.string.contact_call_number)) }
        }
    }
}

@Composable
private fun PollContent(m: Message, me: String, callbacks: BubbleCallbacks) {
    val bubbleMeta = bubbleMeta()
    val options = m.pollOptionList()
    val counts = m.pollCounts()
    val total = counts.sum()
    val myVote = m.pollVoteMap()[me]
    val canVote = !(m.senderId == me && (m.status == MessageStatus.PENDING || m.status == MessageStatus.SENDING || m.status == MessageStatus.FAILED))
    Column(Modifier.widthIn(min = 230.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Poll, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(m.pollQuestion ?: m.content, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        options.forEachIndexed { i, option ->
            val count = counts.getOrElse(i) { 0 }
            val fraction = if (total > 0) count.toFloat() / total else 0f
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.sm)).clickable(enabled = canVote) { callbacks.onVote(m, i) }.padding(vertical = 6.dp, horizontal = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(if (myVote == i) MaterialTheme.colorScheme.primary else bubbleMeta.copy(alpha = 0.25f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(option, modifier = Modifier.weight(1f))
                    Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = bubbleMeta)
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 26.dp))
            }
        }
        Text(pluralStringResource(R.plurals.poll_votes, total, total), style = MaterialTheme.typography.labelSmall, color = bubbleMeta)
    }
}

// ---------------------------------------------------------------------- system messages

@Composable
fun systemText(m: Message, me: String, names: Map<String, String>): String {
    val context = LocalContext.current
    val json = runCatching { JSONObject(m.systemEvent ?: "{}") }.getOrDefault(JSONObject())
    fun who(id: String) = if (id == me) context.getString(R.string.you) else names[id] ?: context.getString(R.string.unknown_user)
    val actor = who(json.optString("actor", m.senderId))
    val targetsArray = json.optJSONArray("targets")
    val targets = (0 until (targetsArray?.length() ?: 0)).map { who(targetsArray!!.getString(it)) }.joinToString(", ")
    val value = json.optString("value", "")
    return when (json.optString("event")) {
        "created" -> context.getString(R.string.system_created, actor, value)
        "added" -> context.getString(R.string.system_added, actor, targets)
        "removed" -> context.getString(R.string.system_removed, actor, targets)
        "left" -> if (targets.isBlank()) context.getString(R.string.system_left, actor) else context.getString(R.string.system_left_promoted, actor, targets)
        "promoted" -> context.getString(R.string.system_promoted, actor, targets)
        "demoted" -> context.getString(R.string.system_demoted, actor, targets)
        "title" -> context.getString(R.string.system_title, actor, value)
        "description" -> context.getString(R.string.system_description, actor)
        "photo" -> context.getString(R.string.system_photo, actor)
        "settings" -> context.getString(R.string.system_settings, actor)
        "disappearing" -> {
            val seconds = value.toLongOrNull() ?: 0L
            if (seconds <= 0) context.getString(R.string.system_disappearing_off, actor)
            else context.getString(R.string.system_disappearing_on, actor, Formatters.disappearingLabel(context, seconds))
        }
        else -> context.getString(R.string.preview_group_update)
    }
}

@Composable
private fun SystemChip(m: Message, me: String, names: Map<String, String>) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            systemText(m, me, names), style = MaterialTheme.typography.labelMedium, color = PulseTheme.colors.metaText,
            modifier = Modifier.clip(RoundedCornerShape(Radii.md)).background(PulseTheme.colors.systemChip).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
