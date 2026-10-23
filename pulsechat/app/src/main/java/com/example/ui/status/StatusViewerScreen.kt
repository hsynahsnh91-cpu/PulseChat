package com.example.ui.status

import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.StatusItem
import com.example.data.model.StatusLikeRow
import com.example.data.model.StatusViewRow
import com.example.data.repository.ChatException
import com.example.domain.StatusPolicy
import com.example.media.StatusMusicPlayer
import com.example.ui.common.ConfirmDialog
import com.example.ui.common.Formatters
import com.example.ui.common.LightSystemBarIcons
import com.example.ui.common.PlayableSource
import com.example.ui.common.appContainer
import com.example.ui.common.chatErrorText
import com.example.ui.common.rememberPlayableSource
import com.example.ui.components.UserAvatar
import com.example.ui.theme.Motion
import com.example.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val TYPE_TEXT = "TEXT"
private const val TYPE_IMAGE = "IMAGE"
private const val TYPE_VIDEO = "VIDEO"
private val QUICK_REACTIONS = listOf("❤️", "😂", "😮", "😢", "🙏", "👏")
private val TextShadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), blurRadius = 6f)

/**
 * Full-screen status viewer.
 * - Tap the start/end side for the previous/next update; hold to pause (the header and footer hide
 *   while holding); swipe down to close.
 * - My updates: "eye" chip with the number of viewers; tapping it (or swiping up) lists who viewed
 *   it and when.
 * - Others' updates: private reply field and quick reactions (sent to their chat, quoting the status);
 *   after the last update the next author's updates follow, as listed in Updates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusViewerScreen(ownerId: String, onClose: () -> Unit, openReply: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val me = container.authRepository.currentUid.orEmpty()
    val all by remember { container.statusRepository.observeActive() }.collectAsState(initial = null)
    val list = all

    var owner by rememberSaveable { mutableStateOf(ownerId) }
    var enterFromEnd by remember { mutableStateOf(false) }
    // Authors to play one after another, in the Updates order, fixed when the viewer opens.
    val order = remember(list != null) {
        list?.let { l ->
            if (ownerId == me) listOf(me)
            else StatusPolicy.playOrder(
                StatusPolicy.group(l.map { StatusPolicy.Item(it.statusId, it.userId, it.createdAt, it.expiresAt, it.isViewed) }, System.currentTimeMillis(), excludeOwner = me),
                ownerId,
            )
        }
    }
    val items = remember(list, owner) { list?.filter { it.userId == owner }?.sortedBy { it.createdAt } }
    var index by remember(owner, items != null) {
        mutableIntStateOf(
            when {
                items.isNullOrEmpty() -> 0
                enterFromEnd -> items.lastIndex
                owner == me -> 0
                else -> items.indexOfFirst { !it.isViewed }.let { if (it < 0) 0 else it }
            }
        )
    }
    var restartKey by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    var chromeHidden by remember { mutableStateOf(false) }
    var viewersOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var replyText by rememberSaveable { mutableStateOf("") }
    var replyFocused by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sentNotice by remember { mutableIntStateOf(0) }
    var backgrounded by remember { mutableStateOf(false) }
    val replyFocus = remember { FocusRequester() }
    val dragY = remember { Animatable(0f) }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var imeWasVisible by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val musicPlayer = remember { StatusMusicPlayer(context) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { backgrounded = true; musicPlayer.pause() }
                Lifecycle.Event.ON_RESUME -> backgrounded = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            musicPlayer.stop()
        }
    }

    fun goToOwner(next: String?, fromEnd: Boolean) {
        if (next == null) { onClose(); return }
        enterFromEnd = fromEnd
        owner = next
    }

    val onNext: () -> Unit = {
        val its = items.orEmpty()
        if (index < its.lastIndex) index++
        else goToOwner(order?.let { o -> o.getOrNull(o.indexOf(owner) + 1) }, fromEnd = false)
    }
    val onPrevious: () -> Unit = {
        if (index > 0) index--
        else {
            val previous = order?.let { o -> o.indexOf(owner).takeIf { it > 0 }?.let { o[it - 1] } }
            if (previous != null) goToOwner(previous, fromEnd = true) else restartKey++
        }
    }
    val latestNext by rememberUpdatedState(onNext)
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestClose by rememberUpdatedState(onClose)

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("status_viewer")) {
        LightSystemBarIcons()
        when {
            items == null -> Unit
            // Everything of this author expired or was deleted while watching.
            items.isEmpty() -> LaunchedEffect(owner) {
                goToOwner(order?.let { o -> o.getOrNull(o.indexOf(owner) + 1) }?.takeIf { it != owner }, fromEnd = false)
            }
            else -> {
                val current: StatusItem = items[index.coerceIn(0, items.lastIndex)]
                val isMine = current.userId == me
                val progress = remember(current.statusId, restartKey) { mutableFloatStateOf(0f) }
                var ready by remember(current.statusId) { mutableStateOf(current.mediaType == TYPE_TEXT) }
                var failed by remember(current.statusId) { mutableStateOf(false) }
                val replyActive = replyFocused && (imeVisible || replyText.isNotBlank())
                val paused = held || viewersOpen || confirmDelete || replyActive || backgrounded || sending
                // Photos and texts run on a timer; videos report their own playback position.
                val timed = current.mediaType != TYPE_VIDEO || failed

                LaunchedEffect(current.statusId) { container.statusRepository.markViewed(current) }
                LaunchedEffect(current.statusId, current.musicTrackId, current.musicUrl, current.musicStartMs, current.musicDurationMs) {
                    if (current.hasMusic) {
                        musicPlayer.play(
                            trackId = current.musicTrackId,
                            audioUrlOrUri = current.musicUrl,
                            startMs = current.musicStartMs,
                            durationMs = current.musicDurationMs.takeIf { it > 0L } ?: 15_000L,
                        )
                    } else {
                        musicPlayer.stop()
                    }
                }
                LaunchedEffect(paused) {
                    if (current.hasMusic) {
                        if (paused) musicPlayer.pause() else musicPlayer.resume()
                    }
                }
                LaunchedEffect(current.statusId) {
                    // Fetch the next photo while this one is shown, so it appears without a wait.
                    val upcoming = items.getOrNull(items.indexOf(current) + 1)
                    if (upcoming != null && upcoming.mediaType == TYPE_IMAGE) {
                        imageModel(upcoming)?.let { context.imageLoader.enqueue(ImageRequest.Builder(context).data(it).build()) }
                    }
                }
                LaunchedEffect(current.statusId, restartKey, paused, ready, timed) {
                    if (paused || !ready || !timed) return@LaunchedEffect
                    // Frame time rather than an animation, so "Remove animations" does not skip updates.
                    // (The "infinite animation" frame variant behaves the same on phones; UI tests pause it.)
                    val total = StatusPolicy.displayMillis(current.mediaType == TYPE_TEXT, current.text?.length ?: 0).toFloat()
                    var last = withInfiniteAnimationFrameMillis { it }
                    while (progress.floatValue < 1f) {
                        val now = withInfiniteAnimationFrameMillis { it }
                        progress.floatValue = (progress.floatValue + (now - last) / total).coerceAtMost(1f)
                        last = now
                    }
                    latestNext()
                }
                LaunchedEffect(imeVisible) {
                    // The keyboard was closed (Back) while the empty field kept the focus: resume playing.
                    if (imeVisible) imeWasVisible = true
                    else if (imeWasVisible) {
                        imeWasVisible = false
                        if (replyFocused && replyText.isBlank()) focusManager.clearFocus()
                    }
                }

                val chromeAlpha by animateFloatAsState(if (chromeHidden) 0f else 1f, tween(Motion.QUICK), label = "status-chrome")
                val closeThreshold = with(density) { 120.dp.toPx() }
                val nextLabel = stringResource(R.string.action_next)
                val previousLabel = stringResource(R.string.action_previous)

                // ---------------------------------------------------------------- content and gestures
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val y = dragY.value.coerceAtLeast(0f)
                            val f = (y / size.height).coerceIn(0f, 1f)
                            translationY = y
                            alpha = 1f - f * 0.6f
                            scaleX = 1f - f * 0.08f
                            scaleY = 1f - f * 0.08f
                        }
                        .pointerInput(rtl) {
                            detectTapGestures(
                                onPress = {
                                    held = true
                                    tryAwaitRelease()
                                    held = false
                                    chromeHidden = false
                                },
                                onLongPress = { chromeHidden = true },
                                onTap = { offset ->
                                    if (replyFocused) { focusManager.clearFocus(); return@detectTapGestures }
                                    val startSide = if (rtl) offset.x > size.width * 2f / 3f else offset.x < size.width / 3f
                                    if (startSide) latestPrevious() else latestNext()
                                },
                            )
                        }
                        .pointerInput(isMine) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    val y = dragY.value
                                    scope.launch {
                                        when {
                                            y > closeThreshold -> latestClose()
                                            y < -closeThreshold * 0.6f -> if (isMine) viewersOpen = true else replyFocus.requestFocus()
                                        }
                                        dragY.animateTo(0f, tween(Motion.STANDARD))
                                    }
                                },
                                onDragCancel = { scope.launch { dragY.animateTo(0f, tween(Motion.STANDARD)) } },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    scope.launch { dragY.snapTo(dragY.value + dy) }
                                },
                            )
                        }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(nextLabel) { latestNext(); true },
                                CustomAccessibilityAction(previousLabel) { latestPrevious(); true },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when (current.mediaType) {
                        TYPE_TEXT -> TextStatus(current)
                        TYPE_IMAGE -> {
                            val model = remember(current.statusId) { imageModel(current) }
                            AsyncImage(
                                model = model, contentDescription = current.text, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                                onSuccess = { ready = true },
                                onError = { failed = true; ready = true },
                            )
                            if (!ready) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                        }
                        TYPE_VIDEO -> when (val source = rememberPlayableSource(current.localMediaPath, current.mediaUrl)) {
                            is PlayableSource.Ready -> StatusVideo(
                                uri = source.uri, paused = paused, progress = progress,
                                onReady = { ready = true }, onCompleted = { latestNext() }, onError = { failed = true; ready = true },
                            )
                            PlayableSource.Loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                            PlayableSource.Failed, PlayableSource.None -> LaunchedEffect(current.statusId) { failed = true; ready = true }
                        }
                    }
                    if (failed) {
                        Text(stringResource(R.string.message_unavailable), color = Color.White, style = MaterialTheme.typography.bodyLarge.copy(shadow = TextShadow))
                    }
                }

                // ---------------------------------------------------------------- top: progress and author
                Column(
                    Modifier.fillMaxWidth().graphicsLayer { alpha = chromeAlpha }
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                        .statusBarsPadding().padding(top = Spacing.sm, bottom = Spacing.xxl),
                ) {
                    ProgressSegments(
                        count = items.size, index = index.coerceIn(0, items.lastIndex), progress = { progress.floatValue },
                        modifier = Modifier.padding(horizontal = Spacing.sm),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
                        }
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(50))
                                .then(if (isMine) Modifier else Modifier.clickable { openReply(current.userId) })
                                .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(current.authorName, avatarUrl = current.authorPhotoUrl, size = 38.dp)
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (isMine) stringResource(R.string.updates_my_status) else current.authorName.ifBlank { stringResource(R.string.unknown_user) },
                                    color = Color.White, style = MaterialTheme.typography.titleSmall.copy(shadow = TextShadow), fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = buildString {
                                    append(Formatters.statusTime(context, current.createdAt))
                                    if (current.isReshared && !current.resharedFromAuthorName.isNullOrBlank()) {
                                        append(" · ")
                                        append(context.getString(R.string.status_reshared_from, current.resharedFromAuthorName))
                                    }
                                }
                                Text(
                                    subtitle, color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.labelMedium.copy(shadow = TextShadow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (isMine) {
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.action_delete), tint = Color.White)
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (!current.allowReshare) {
                                        Toast.makeText(context, context.getString(R.string.status_reshare_disabled), Toast.LENGTH_SHORT).show()
                                        return@IconButton
                                    }
                                    scope.launch {
                                        container.statusRepository.reshareStatus(current.statusId)
                                            .onSuccess {
                                                Toast.makeText(context, context.getString(R.string.status_reshare_done), Toast.LENGTH_SHORT).show()
                                            }
                                            .onFailure { err ->
                                                val msg = when {
                                                    err.message?.contains("DISABLED_BY_AUTHOR") == true -> context.getString(R.string.status_reshare_disabled)
                                                    err.message?.contains("EXPIRED") == true -> context.getString(R.string.status_reshare_expired)
                                                    err.message?.contains("ALREADY_RESHARED") == true -> context.getString(R.string.status_reshare_already)
                                                    else -> context.getString(R.string.status_reshare_not_allowed)
                                                }
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                    }
                                },
                                modifier = Modifier.testTag("status_reshare_button"),
                            ) {
                                Icon(
                                    Icons.Rounded.Repeat,
                                    contentDescription = stringResource(R.string.status_reshare_action),
                                    tint = if (current.allowReshare) Color.White else Color.White.copy(alpha = 0.45f),
                                )
                            }
                        }
                    }
                    if (current.hasMusic) {
                        StatusMusicAttributionPill(
                            title = current.musicTitle.orEmpty(),
                            artist = current.musicArtist.orEmpty(),
                            modifier = Modifier.padding(start = 56.dp, top = 4.dp),
                        )
                    }
                }

                // ---------------------------------------------------------------- bottom: caption and actions
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { alpha = chromeAlpha }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                        .navigationBarsPadding().imePadding().padding(start = Spacing.md, end = Spacing.md, top = Spacing.xxxl, bottom = Spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val caption = current.text?.takeIf { current.mediaType != TYPE_TEXT && it.isNotBlank() }
                    if (caption != null) {
                        Text(
                            caption, color = Color.White, textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge.copy(shadow = TextShadow, textDirection = TextDirection.Content),
                            modifier = Modifier.widthIn(max = Spacing.contentMaxWidth).padding(horizontal = Spacing.sm, vertical = Spacing.md),
                        )
                    }
                    if (sentNotice > 0) SentNotice(sentNotice) { sentNotice = 0 }
                    if (isMine) {
                        val viewers by remember(current.statusId) { container.statusRepository.observeViewers(current.statusId) }.collectAsState(initial = emptyList())
                        val likers by remember(current.statusId) { container.statusRepository.observeLikes(current.statusId) }.collectAsState(initial = emptyList())
                        ViewsChip(viewers.size, likers.size) { viewersOpen = true }
                        if (viewersOpen) ViewersSheet(viewers, likers) { viewersOpen = false }
                    } else {
                        ReplyBar(
                            text = replyText, onText = { replyText = it.take(1000) }, focusRequester = replyFocus, showReactions = replyActive, sending = sending,
                            isLiked = current.isLikedByMe,
                            onToggleLike = {
                                scope.launch {
                                    container.statusRepository.toggleLike(current.statusId)
                                }
                            },
                            onFocus = { replyFocused = it },
                            onSend = { text ->
                                val status = current
                                val quote = context.getString(
                                    R.string.status_quote,
                                    status.text?.takeIf { status.mediaType == TYPE_TEXT || it.isNotBlank() } ?: context.getString(
                                        if (status.mediaType == TYPE_VIDEO) R.string.preview_video else R.string.preview_photo
                                    ),
                                )
                                sending = true
                                scope.launch {
                                    val error: String? = try {
                                        container.chatRepository.sendStatusReply(status.userId, status.statusId, quote, text)
                                        null
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: ChatException) {
                                        chatErrorText(context, e.error)
                                    } catch (e: Exception) {
                                        context.getString(R.string.error_update_failed)
                                    }
                                    sending = false
                                    if (error == null) {
                                        replyText = ""
                                        focusManager.clearFocus()
                                        sentNotice++
                                    } else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }

                if (confirmDelete) {
                    ConfirmDialog(stringResource(R.string.status_delete_title), null, stringResource(R.string.action_delete),
                        onConfirm = { confirmDelete = false; scope.launch { container.statusRepository.delete(current.statusId) } },
                        onDismiss = { confirmDelete = false }, destructive = true)
                }
            }
        }
    }
}

private fun imageModel(status: StatusItem): Any? = status.localMediaPath?.takeIf { File(it).exists() }?.let { File(it) } ?: status.mediaUrl

@Composable
private fun TextStatus(status: StatusItem) {
    val text = status.text.orEmpty()
    val size = when {
        text.length <= 30 -> 34.sp
        text.length <= 90 -> 28.sp
        text.length <= 200 -> 23.sp
        else -> 19.sp
    }
    Box(
        Modifier.fillMaxSize().background(parseHexColor(status.backgroundColorHex))
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = Spacing.xxxl, vertical = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = Color.White, textAlign = TextAlign.Center,
            style = TextStyle(fontSize = size, lineHeight = size * 1.3f, fontFamily = statusFont(status.fontFamily), fontWeight = FontWeight.Medium,
                textDirection = TextDirection.Content, shadow = Shadow(Color.Black.copy(alpha = 0.18f), Offset(0f, 2f), blurRadius = 8f)),
            modifier = Modifier.widthIn(max = Spacing.contentMaxWidth),
        )
    }
}

/** One rounded segment per update; the current one fills as it plays. Drawn without recomposition. */
@Composable
private fun ProgressSegments(count: Int, index: Int, progress: () -> Float, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(modifier.fillMaxWidth().height(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.weight(1f).fillMaxHeight().drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.35f), cornerRadius = radius)
                    val p = when {
                        i < index -> 1f
                        i > index -> 0f
                        else -> progress().coerceIn(0f, 1f)
                    }
                    if (p > 0f) {
                        val w = size.width * p
                        drawRoundRect(Color.White, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height), cornerRadius = radius)
                    }
                },
            )
        }
    }
}

/**
 * Status video without player controls: plays and pauses with the viewer, reports its position to
 * the progress segment and keeps the video's aspect ratio.
 */
@Composable
private fun StatusVideo(uri: String, paused: Boolean, progress: MutableFloatState, onReady: () -> Unit, onCompleted: () -> Unit, onError: () -> Unit) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var prepared by remember(uri) { mutableStateOf(false) }
    var ratio by remember(uri) { mutableFloatStateOf(0f) }
    val latestReady by rememberUpdatedState(onReady)
    val latestCompleted by rememberUpdatedState(onCompleted)
    val latestError by rememberUpdatedState(onError)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setOnPreparedListener { mp ->
                        if (mp.videoWidth > 0 && mp.videoHeight > 0) ratio = mp.videoWidth.toFloat() / mp.videoHeight
                        prepared = true
                        latestReady()
                    }
                    setOnCompletionListener { progress.floatValue = 1f; latestCompleted() }
                    setOnErrorListener { _, _, _ -> latestError(); true }
                    setVideoURI(Uri.parse(uri))
                    view = this
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
        )
        if (!prepared) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
    }
    LaunchedEffect(view, prepared, paused) {
        val v = view ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        if (paused) { if (v.isPlaying) v.pause(); return@LaunchedEffect }
        if (!v.isPlaying) v.start()
        val duration = v.duration
        if (duration <= 0) return@LaunchedEffect
        // Smooth segment: advance with the frame clock and re-read the real position twice a second
        // (each player query is a call into the media service, too costly for every frame).
        var lastFrame = withInfiniteAnimationFrameMillis { it }
        var lastSync = 0L
        while (true) {
            val now = withInfiniteAnimationFrameMillis { it }
            if (now - lastSync >= 500) {
                progress.floatValue = (v.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                lastSync = now
            } else {
                progress.floatValue = (progress.floatValue + (now - lastFrame).toFloat() / duration).coerceAtMost(1f)
            }
            lastFrame = now
        }
    }
}

/** Number of people who saw or liked my update; opens the list of viewers and likers. */
@Composable
private fun ViewsChip(count: Int, likeCount: Int = 0, onClick: () -> Unit) {
    val label = pluralStringResource(R.plurals.status_views, count, count)
    val open = stringResource(R.string.status_viewed_by)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
        Row(
            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clickable(onClickLabel = open, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = Spacing.xl)
                .semantics(mergeDescendants = true) { contentDescription = label; role = Role.Button }.testTag("status_views"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(count.toString(), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (likeCount > 0) {
                Spacer(Modifier.width(Spacing.md))
                Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(likeCount.toString(), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Who viewed and who liked my update, newest first, with the day and time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewersSheet(
    viewers: List<StatusViewRow>,
    likers: List<StatusLikeRow> = emptyList(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val likerIds = remember(likers) { likers.map { it.userId }.toSet() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().testTag("status_viewers_sheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Spacing.md))
                Text(stringResource(R.string.status_viewed_by_count, viewers.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (likers.isNotEmpty()) {
                    Spacer(Modifier.width(Spacing.lg))
                    Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.status_likes_title, likers.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            if (viewers.isEmpty() && likers.isEmpty()) {
                Text(
                    stringResource(R.string.status_no_views), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl)) {
                    items(viewers, key = { "v_" + it.viewerId }) { v ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.xxl, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(v.displayName ?: "", avatarUrl = v.avatarUrl, size = 44.dp, colorHex = v.avatarColorHex)
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(v.displayName?.ifBlank { null } ?: stringResource(R.string.unknown_user), style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Formatters.dayAndTime(context, v.viewedAt), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (v.viewerId in likerIds) {
                                Icon(Icons.Rounded.Favorite, contentDescription = stringResource(R.string.status_like_action), tint = Color(0xFFF43F5E), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    val extraLikers = likers.filter { l -> viewers.none { it.viewerId == l.userId } }
                    items(extraLikers, key = { "l_" + it.userId }) { l ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.xxl, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(l.displayName ?: "", avatarUrl = l.avatarUrl, size = 44.dp, colorHex = l.avatarColorHex)
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(l.displayName?.ifBlank { null } ?: stringResource(R.string.unknown_user), style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Formatters.dayAndTime(context, l.likedAt), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.Favorite, contentDescription = stringResource(R.string.status_like_action), tint = Color(0xFFF43F5E), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Private reply to someone's update, with quick reactions while typing and a dedicated Like button beside the reply composer. */
@Composable
private fun ReplyBar(
    text: String,
    onText: (String) -> Unit,
    focusRequester: FocusRequester,
    showReactions: Boolean,
    sending: Boolean,
    isLiked: Boolean = false,
    onToggleLike: () -> Unit = {},
    onFocus: (Boolean) -> Unit,
    onSend: (String) -> Unit,
) {
    Column(Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth()) {
        if (showReactions) {
            Row(Modifier.fillMaxWidth().padding(bottom = Spacing.sm), horizontalArrangement = Arrangement.SpaceEvenly) {
                QUICK_REACTIONS.forEach { emoji ->
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !sending) { onSend(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 26.sp) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(24.dp)).padding(horizontal = Spacing.lg, vertical = Spacing.md),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(stringResource(R.string.status_reply_hint), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = text, onValueChange = onText, enabled = !sending, maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocus(it.isFocused) }.testTag("status_reply_field"),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            IconButton(
                onClick = onToggleLike,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .testTag("status_like_button"),
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (isLiked) R.string.status_unlike_action else R.string.status_like_action),
                    tint = if (isLiked) Color(0xFFF43F5E) else Color.White,
                )
            }
            if (text.isNotBlank()) {
                Spacer(Modifier.width(Spacing.sm))
                FilledIconButton(
                    onClick = { onSend(text) }, enabled = !sending, modifier = Modifier.size(48.dp).testTag("status_reply_send"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                    else Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.action_send))
                }
            }
        }
    }
}

/** Short confirmation after a reply was sent. */
@Composable
private fun SentNotice(key: Int, onDone: () -> Unit) {
    LaunchedEffect(key) { delay(1_800); onDone() }
    Row(
        Modifier.padding(bottom = Spacing.sm).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(stringResource(R.string.status_reply_sent), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
