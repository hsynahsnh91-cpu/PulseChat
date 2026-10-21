package com.example.ui.status

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.StatusItem
import com.example.ui.common.ConfirmDialog
import com.example.ui.common.PlayableSource
import com.example.ui.common.Formatters
import com.example.ui.common.appContainer
import com.example.ui.common.rememberPlayableSource
import com.example.ui.components.UserAvatar
import kotlinx.coroutines.launch
import java.io.File
import com.example.ui.common.LightSystemBarIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusViewerScreen(ownerId: String, onClose: () -> Unit, openReply: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = container.authRepository.currentUid.orEmpty()
    val all by remember { container.statusRepository.observeActive() }.collectAsState(initial = null)
    val items = all?.filter { it.userId == ownerId }?.sortedBy { it.createdAt }
    var index by remember { mutableIntStateOf(-1) }
    var paused by remember { mutableStateOf(false) }
    var viewersOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    if (items == null) return
    if (items.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    if (index < 0) index = items.indexOfFirst { !it.isViewed && it.userId != me }.let { if (it < 0) 0 else it }
    val current: StatusItem = items[index.coerceIn(0, items.size - 1)]

    fun next() { if (index < items.size - 1) index++ else onClose() }
    fun previous() { if (index > 0) index-- }

    LaunchedEffect(current.statusId) {
        container.statusRepository.markViewed(current)
        progress.snapTo(0f)
    }
    LaunchedEffect(current.statusId, paused) {
        if (paused || current.mediaType == "VIDEO") return@LaunchedEffect
        val remaining = ((1f - progress.value) * 5_000).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        next()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LightSystemBarIcons()
        Box(
            Modifier.fillMaxSize().pointerInput(current.statusId) {
                detectTapGestures(
                    onPress = { paused = true; tryAwaitRelease(); paused = false },
                    onTap = { offset ->
                        val leftSide = offset.x < size.width / 3f
                        if (leftSide != rtl) previous() else next()
                    },
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            when (current.mediaType) {
                "TEXT" -> Box(Modifier.fillMaxSize().background(parseHexColor(current.backgroundColorHex)).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(current.text.orEmpty(), color = Color.White, fontSize = 28.sp, textAlign = TextAlign.Center, fontFamily = statusFont(current.fontFamily))
                }
                "IMAGE" -> AsyncImage(model = current.localMediaPath?.takeIf { File(it).exists() }?.let { File(it) } ?: current.mediaUrl, contentDescription = current.text,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                "VIDEO" -> when (val source = rememberPlayableSource(current.localMediaPath, current.mediaUrl)) {
                    is PlayableSource.Ready -> VideoPlayerView(source.uri, Modifier.fillMaxWidth(), autoPlay = true, onCompleted = { next() })
                    PlayableSource.Loading -> CircularProgressIndicator(color = Color.White)
                    PlayableSource.Failed -> Text(stringResource(R.string.message_unavailable), color = Color.White)
                    PlayableSource.None -> Unit
                }
            }
            if (current.mediaType != "TEXT" && !current.text.isNullOrBlank()) {
                Text(current.text, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).navigationBarsPadding().padding(16.dp).padding(bottom = 48.dp))
            }
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                items.forEachIndexed { i, _ ->
                    LinearProgressIndicator(
                        progress = { when { i < index -> 1f; i == index -> if (current.mediaType == "VIDEO") 0.5f else progress.value; else -> 0f } },
                        modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.35f),
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(current.authorName, avatarUrl = current.authorPhotoUrl, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (ownerId == me) stringResource(R.string.updates_my_status) else current.authorName, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(Formatters.chatListTime(context, current.createdAt), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                }
                if (ownerId == me) IconButton(onClick = { paused = true; confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = Color.White) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White) }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
            if (ownerId == me) {
                val viewers by remember(current.statusId) { container.statusRepository.observeViewers(current.statusId) }.collectAsState(initial = emptyList())
                TextButton(onClick = { paused = true; viewersOpen = true }) {
                    Icon(Icons.Default.Visibility, null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(pluralStringResource(R.plurals.status_views, viewers.size, viewers.size), color = Color.White)
                }
                if (viewersOpen) {
                    ModalBottomSheet(onDismissRequest = { viewersOpen = false; paused = false }) {
                        Text(stringResource(R.string.status_viewed_by), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                        if (viewers.isEmpty()) Text(stringResource(R.string.status_no_views), modifier = Modifier.padding(24.dp))
                        LazyColumn(Modifier.padding(bottom = 24.dp)) {
                            items(viewers, key = { it.viewerId }) { v ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    UserAvatar(v.displayName ?: "", avatarUrl = v.avatarUrl, size = 40.dp, colorHex = v.avatarColorHex)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(v.displayName ?: stringResource(R.string.unknown_user))
                                        Text(Formatters.chatListTime(context, v.viewedAt), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                TextButton(onClick = { openReply(ownerId) }) {
                    Icon(Icons.AutoMirrored.Filled.Reply, null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_reply), color = Color.White)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.status_delete_title), null, stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; scope.launch { container.statusRepository.delete(current.statusId) } },
            onDismiss = { confirmDelete = false; paused = false }, destructive = true)
    }
}
