package com.example.ui.status

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/** Platform VideoView with standard controls (no extra media dependency needed). */
@Composable
fun VideoPlayerView(source: String, modifier: Modifier = Modifier, autoPlay: Boolean = true, onCompleted: (() -> Unit)? = null) {
    val context = LocalContext.current
    val view = remember(source) {
        VideoView(context).apply {
            val controller = MediaController(context)
            controller.setAnchorView(this)
            setMediaController(controller)
            setVideoURI(Uri.parse(source))
            setOnPreparedListener { if (autoPlay) start() }
            setOnCompletionListener { onCompleted?.invoke() }
        }
    }
    DisposableEffect(view) { onDispose { view.stopPlayback() } }
    AndroidView(factory = { view }, modifier = modifier)
}
