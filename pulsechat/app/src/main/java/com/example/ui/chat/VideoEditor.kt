package com.example.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.domain.DurationFormat
import com.example.domain.MediaLimits
import com.example.domain.VideoEditMath
import com.example.media.VideoInfo
import com.example.media.VideoTrimException
import com.example.ui.common.LightSystemBarIcons
import com.example.ui.common.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The video to send after editing: the cut (or the original) and its cover. */
data class EditedVideo(
    val uri: Uri,
    /** Cover position in [uri]'s own timeline. */
    val coverMs: Long,
    /** Small JPEG of the cover, sent with the message so the chat shows it before playing. */
    val cover: ByteArray?,
    val trimmed: Boolean,
    val durationMs: Long,
)

/**
 * Video editor shown before sending: trim the start and the end, and pick the cover frame. The cut
 * copies the original frames (no re-encoding), so it is quick and keeps the quality; it starts on
 * the keyframe at or before the chosen start, and the editor shows that real start. The estimated
 * size is checked against what can be sent, so a long video can be shortened until it fits.
 * The timeline runs left to right in every language, like the video itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorDialog(source: Uri, maxBytes: Long, onDone: (EditedVideo) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val trimmer = container.videoTrimmer
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    var filmstrip by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var range by remember { mutableStateOf(0f..1f) }
    var cover by remember { mutableFloatStateOf(0f) }
    var coverFrame by remember { mutableStateOf<Bitmap?>(null) }
    var startFrame by remember { mutableStateOf<Bitmap?>(null) }
    var playing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var trimFailed by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        val probed = trimmer.probe(source)
        if (probed.durationMs <= 0L) { failed = true; return@LaunchedEffect }
        info = probed
        range = 0f..probed.durationMs.toFloat()
        cover = VideoEditMath.defaultCoverMs(probed.durationMs).toFloat()
        filmstrip = trimmer.filmstrip(source, VideoEditMath.filmstripTimes(probed.durationMs), 160)
    }
    val video = info
    val edit = video?.let { VideoEditMath.normalize(range.start.toLong(), range.endInclusive.toLong(), cover.toLong(), it.durationMs, it.keyframesMs) }
    val estimate = if (video != null && edit != null) VideoEditMath.estimatedBytes(video.sizeBytes, video.durationMs, edit) else 0L
    val tooLarge = video != null && maxBytes > 0 && video.sizeBytes > 0 && estimate > maxBytes
    val currentEdit by rememberUpdatedState(edit)
    // Cover and start previews follow the controls (after a short pause in dragging).
    LaunchedEffect(edit?.coverMs) {
        val c = edit?.coverMs ?: return@LaunchedEffect
        delay(120)
        coverFrame = trimmer.frame(source, c, 360)
    }
    LaunchedEffect(edit?.startMs) {
        val s = edit?.startMs ?: return@LaunchedEffect
        delay(120)
        startFrame = trimmer.frame(source, s, 720)
    }

    fun finish() {
        val v = info ?: return
        val e = edit ?: return
        working = true
        progress = 0
        scope.launch {
            try {
                val trimmed = !trimFailed && VideoEditMath.isTrimmed(e, v.durationMs)
                var uri = source
                var coverMs = e.coverMs
                var duration = v.durationMs
                if (trimmed) {
                    val (file, fileUri) = container.media.newCaptureFile("mp4")
                    val realStart = trimmer.trim(source, e.startMs, e.endMs, file) { progress = it }
                    uri = fileUri
                    coverMs = (e.coverMs - realStart).coerceAtLeast(0L)
                    duration = e.endMs - realStart
                }
                val frame = trimmer.frame(uri, coverMs, VideoEditMath.COVER_MAX_DIM, exact = true)
                onDone(EditedVideo(uri, coverMs, frame?.let { trimmer.coverJpeg(it) }, trimmed, duration))
            } catch (ex: VideoTrimException) {
                // Not every container can be cut without re-encoding: offer to send it whole.
                trimFailed = true
                range = 0f..v.durationMs.toFloat()
            } finally {
                working = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).testTag("video_editor")) {
            LightSystemBarIcons()
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel), tint = Color.White) }
                Text(stringResource(R.string.video_edit_title), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { finish() }, enabled = video != null && !working && !tooLarge, modifier = Modifier.testTag("video_edit_done")) {
                    Text(stringResource(R.string.action_done), color = if (video != null && !working && !tooLarge) Color.White else Color.Gray)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    failed -> Text(stringResource(R.string.video_edit_failed), color = Color.White, modifier = Modifier.padding(24.dp))
                    video == null -> CircularProgressIndicator()
                    else -> {
                        val view = remember(source) { VideoView(context).apply { setVideoURI(source) } }
                        DisposableEffect(view) { onDispose { view.stopPlayback() } }
                        LaunchedEffect(playing) {
                            val e = currentEdit ?: return@LaunchedEffect
                            if (!playing) { runCatching { view.pause() }; return@LaunchedEffect }
                            runCatching { view.seekTo(e.startMs.toInt()); view.start() }
                            while (isActive && playing) {
                                val end = currentEdit?.endMs ?: e.endMs
                                if (runCatching { view.currentPosition }.getOrDefault(0) >= end) { playing = false; runCatching { view.pause() } }
                                delay(50)
                            }
                        }
                        Box(Modifier.fillMaxWidth().aspectRatio(video.displayAspect.coerceIn(0.4f, 2.5f)), contentAlignment = Alignment.Center) {
                            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                            if (!playing) startFrame?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("video_start_frame")) }
                            Box(
                                Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = !working) { playing = !playing },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.action_pause else R.string.action_play), tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
                if (working) {
                    Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.video_edit_preparing, progress), color = Color.White)
                    }
                }
            }
            if (video != null && edit != null) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                    // Time runs left to right in every language, like the picture itself.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column {
                            Text(stringResource(R.string.video_edit_trim), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Filmstrip(filmstrip, video.durationMs, edit.startMs, edit.endMs, edit.coverMs)
                            RangeSlider(
                                value = range,
                                onValueChange = { r -> if (!working) range = r },
                                valueRange = 0f..video.durationMs.toFloat(),
                                onValueChangeFinished = {
                                    // Show the real start (keyframe) and keep the cover inside the clip.
                                    val v = info
                                    if (v != null) {
                                        val e = VideoEditMath.normalize(range.start.toLong(), range.endInclusive.toLong(), cover.toLong(), v.durationMs, v.keyframesMs)
                                        range = e.startMs.toFloat()..e.endMs.toFloat()
                                        cover = e.coverMs.toFloat()
                                    }
                                },
                                enabled = !working && !trimFailed,
                                colors = sliderColors,
                                modifier = Modifier.fillMaxWidth().testTag("video_trim_range").semantics { contentDescription = context.getString(R.string.video_edit_trim_range) },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.video_edit_range, DurationFormat.clock(edit.startMs), DurationFormat.clock(edit.endMs)) + stringResource(R.string.list_separator) +
                            stringResource(R.string.video_edit_length, DurationFormat.clock(edit.durationMs), MediaLimits.humanSize(estimate)),
                        color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("video_edit_summary"),
                    )
                    when {
                        trimFailed -> Text(stringResource(R.string.video_edit_trim_failed), color = Color(0xFFFFB4A9), style = MaterialTheme.typography.bodySmall)
                        tooLarge -> Text(stringResource(R.string.video_edit_too_large, MediaLimits.humanSize(maxBytes)), color = Color(0xFFFFB4A9), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("video_edit_too_large"))
                        edit.startMs > 0 && video.keyframesMs.size > 1 -> Text(stringResource(R.string.video_edit_keyframe_note), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.video_edit_cover), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray).border(1.dp, Color.White, RoundedCornerShape(6.dp))) {
                            coverFrame?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.video_edit_cover), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Spacer(Modifier.width(12.dp))
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Slider(
                                value = cover.coerceIn(edit.startMs.toFloat(), maxOf(edit.startMs, edit.endMs - 1).toFloat()),
                                onValueChange = { if (!working) cover = it },
                                valueRange = edit.startMs.toFloat()..maxOf(edit.startMs + 1, edit.endMs - 1).toFloat(),
                                enabled = !working,
                                colors = sliderColors,
                                modifier = Modifier.weight(1f).testTag("video_cover_slider").semantics { contentDescription = context.getString(R.string.video_edit_cover_slider) },
                            )
                        }
                    }
                    Text(stringResource(R.string.video_edit_cover_hint), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Frames of the whole video; the parts outside the clip are dimmed and the cover is marked. */
@Composable
private fun Filmstrip(frames: List<Bitmap?>, durationMs: Long, startMs: Long, endMs: Long, coverMs: Long) {
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
        Row(Modifier.fillMaxSize()) {
            frames.forEach { f ->
                Box(Modifier.weight(1f).fillMaxSize()) {
                    f?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            if (durationMs <= 0L) return@Canvas
            val x0 = size.width * startMs / durationMs
            val x1 = size.width * endMs / durationMs
            val dim = Color.Black.copy(alpha = 0.6f)
            drawRect(dim, topLeft = Offset.Zero, size = Size(x0, size.height))
            drawRect(dim, topLeft = Offset(x1, 0f), size = Size(size.width - x1, size.height))
            drawRect(Color.White, topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(2f), size.height), style = Stroke(width = 3.dp.toPx()))
            val xc = size.width * coverMs / durationMs
            drawLine(Color.White, Offset(xc, 0f), Offset(xc, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}
