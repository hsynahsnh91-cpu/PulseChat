package com.example.ui.misc

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.MediaLimits
import com.example.domain.MediaRefs
import com.example.domain.model.MessageType
import com.example.domain.model.TransferState
import com.example.ui.common.Formatters
import com.example.ui.common.appContainer
import com.example.ui.common.playerUri
import com.example.ui.status.VideoPlayerView
import kotlinx.coroutines.launch
import java.io.File
import com.example.ui.common.LightSystemBarIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(messageId: String, onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message by remember { container.chatRepository.observeMessage(messageId) }.collectAsState(initial = null)
    val sender by remember(message?.senderId) { container.userRepository.observeUser(message?.senderId.orEmpty()) }.collectAsState(initial = null)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val m = message
    val local = m?.localMediaPath?.takeIf { File(it).exists() }
    // Videos in the built-in media store cannot be streamed: fetch them as soon as the viewer opens.
    LaunchedEffect(m?.messageId, local == null) {
        if (m != null && local == null && m.type == MessageType.VIDEO && MediaRefs.isPulse(m.mediaUrl) && m.transferState != TransferState.DOWNLOADING) {
            runCatching { container.chatRepository.downloadMedia(m.messageId) }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LightSystemBarIcons()
        if (m != null) {
            when {
                m.type == MessageType.VIDEO && local != null ->
                    VideoPlayerView(playerUri(local), Modifier.fillMaxWidth().align(Alignment.Center), autoPlay = true)
                m.type == MessageType.VIDEO && m.mediaUrl != null && !MediaRefs.isPulse(m.mediaUrl) ->
                    VideoPlayerView(m.mediaUrl, Modifier.fillMaxWidth().align(Alignment.Center), autoPlay = true)
                m.type == MessageType.IMAGE && local != null -> AsyncImage(
                    model = File(local), contentDescription = m.content, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
                m.transferState == TransferState.DOWNLOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                m.mediaUrl != null -> Button(onClick = { scope.launch { container.chatRepository.downloadMedia(m.messageId) } }, modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Default.Download, null)
                    Text(" " + stringResource(R.string.action_download) + " · " + MediaLimits.humanSize(m.mediaSizeBytes))
                }
                else -> Text(stringResource(R.string.message_unavailable), color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
        TopAppBar(
            modifier = Modifier.statusBarsPadding(),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.4f), titleContentColor = Color.White,
                navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
            title = {
                Column {
                    Text(if (m?.senderId == container.authRepository.currentUid) stringResource(R.string.you) else sender?.displayName ?: "")
                    if (m != null) Text(Formatters.dateTime(context, m.createdAt), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            actions = {
                if (local != null && m != null) {
                    IconButton(onClick = {
                        val uri = container.media.contentUriFor(local)
                        val send = Intent(Intent.ACTION_SEND).setType(m.mediaMimeType ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null))
                    }) { Icon(Icons.Default.Share, stringResource(R.string.action_share)) }
                    IconButton(onClick = {
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(container.media.contentUriFor(local), m.mediaMimeType ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try { context.startActivity(view) } catch (e: ActivityNotFoundException) { Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_SHORT).show() }
                    }) { Icon(Icons.Default.OpenInNew, stringResource(R.string.action_open_with)) }
                }
            },
        )
    }
}
