package com.example.ui.status

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.StatusPolicy

val STATUS_COLORS = listOf("#3730A3", "#0F766E", "#BE185D", "#B45309", "#6D28D9", "#1D4ED8", "#047857", "#334155")
val STATUS_FONTS = listOf("DEFAULT", "SERIF", "SANS", "MONO", "CURSIVE")

fun statusFont(name: String): FontFamily = when (name) {
    "SERIF" -> FontFamily.Serif
    "SANS" -> FontFamily.SansSerif
    "MONO" -> FontFamily.Monospace
    "CURSIVE" -> FontFamily.Cursive
    else -> FontFamily.Default
}

fun parseHexColor(hex: String, fallback: Color = Color(0xFF3730A3)): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/** Full-screen text status editor (PulseChat palette). */
@Composable
fun StatusTextComposer(onDismiss: () -> Unit, onPost: (text: String, background: String, font: String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var colorIndex by rememberSaveable { mutableIntStateOf(0) }
    var fontIndex by rememberSaveable { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(parseHexColor(STATUS_COLORS[colorIndex])).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White) }
                Row {
                    IconButton(onClick = { fontIndex = (fontIndex + 1) % STATUS_FONTS.size }) {
                        Icon(Icons.Default.TextFields, contentDescription = stringResource(R.string.status_change_font), tint = Color.White)
                    }
                    IconButton(onClick = { colorIndex = (colorIndex + 1) % STATUS_COLORS.size }) {
                        Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.status_change_color), tint = Color.White)
                    }
                }
            }
            BasicTextField(
                value = text, onValueChange = { text = it.take(StatusPolicy.MAX_TEXT) },
                textStyle = TextStyle(color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center, fontFamily = statusFont(STATUS_FONTS[fontIndex])),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (text.isEmpty()) Text(stringResource(R.string.status_type_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 30.sp, textAlign = TextAlign.Center)
                        inner()
                    }
                },
            )
            if (text.isNotBlank()) {
                FloatingActionButton(
                    onClick = { onPost(text.trim(), STATUS_COLORS[colorIndex], STATUS_FONTS[fontIndex]) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send)) }
            }
        }
    }
}

/** Preview with caption before posting a photo/video status or sending media in chat. */
@Composable
fun MediaCaptionDialog(uri: Uri, isVideo: Boolean, title: String, onDismiss: () -> Unit, onEdit: (() -> Unit)? = null, onSend: (caption: String) -> Unit) {
    var caption by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White) }
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onEdit != null && !isVideo) {
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.editor_open), tint = Color.White) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (isVideo) {
                    VideoPlayerView(source = uri.toString(), modifier = Modifier.fillMaxWidth(), autoPlay = false)
                } else {
                    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = caption, onValueChange = { caption = it.take(1024) }, placeholder = { Text(stringResource(R.string.caption_hint)) },
                    modifier = Modifier.weight(1f).heightIn(max = 140.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.White, unfocusedBorderColor = Color.Gray,
                    ),
                )
                FloatingActionButton(onClick = { onSend(caption.trim()) }, modifier = Modifier.padding(start = 12.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                }
            }
        }
    }
}
