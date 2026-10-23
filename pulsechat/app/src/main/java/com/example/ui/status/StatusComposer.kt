package com.example.ui.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ContactRow
import com.example.domain.DurationFormat
import com.example.domain.MediaQuality
import com.example.domain.MediaQualityPolicy
import com.example.domain.StatusCollageMath
import com.example.domain.StatusMusicPolicy
import com.example.domain.StatusPolicy
import com.example.media.StatusMusicPlayer
import com.example.ui.common.Formatters
import com.example.ui.common.LightSystemBarIcons
import com.example.ui.common.appContainer
import com.example.ui.components.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

val STATUS_COLORS = com.example.ui.theme.StatusPalette.Hex
val STATUS_FONTS = listOf("DEFAULT", "SERIF", "SANS", "MONO", "CURSIVE")

fun statusFont(name: String): FontFamily = when (name) {
    "SERIF" -> FontFamily.Serif
    "SANS" -> FontFamily.SansSerif
    "MONO" -> FontFamily.Monospace
    "CURSIVE" -> FontFamily.Cursive
    else -> FontFamily.Default
}

fun parseHexColor(hex: String, fallback: Color = com.example.ui.theme.StatusPalette.Fallback): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/** Extra metadata authored in the Status Editor (music, mentions, reshare permission). */
data class StatusComposeExtras(
    val musicSelection: StatusMusicPolicy.Selection? = null,
    val mentionedUserIds: List<String> = emptyList(),
    val allowReshare: Boolean = true,
)

/**
 * Full-screen text status editor with:
 * - Font & background palette controls
 * - Music picker (search, preview, segment selection, attribution pill)
 * - Mention people selector
 * - Allow reshare toggle
 * - Preview mode & duplicate-submit protection
 */
@Composable
fun StatusTextComposer(
    onDismiss: () -> Unit,
    contacts: List<ContactRow> = emptyList(),
    onPostWithExtras: ((text: String, background: String, font: String, extras: StatusComposeExtras) -> Unit)? = null,
    onPost: (text: String, background: String, font: String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var colorIndex by rememberSaveable { mutableIntStateOf(0) }
    var fontIndex by rememberSaveable { mutableIntStateOf(0) }
    var musicSelection by remember { mutableStateOf<StatusMusicPolicy.Selection?>(null) }
    var mentionedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var allowReshare by rememberSaveable { mutableStateOf(true) }
    var showMusicPicker by remember { mutableStateOf(false) }
    var showMentionPicker by remember { mutableStateOf(false) }
    var previewMode by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    val musicPlayer = remember { StatusMusicPlayer(context) }
    DisposableEffect(Unit) {
        onDispose { musicPlayer.stop() }
    }
    LaunchedEffect(musicSelection, previewMode) {
        val sel = musicSelection
        if (sel != null && previewMode) {
            musicPlayer.playSelection(sel)
        } else if (sel == null) {
            musicPlayer.stop()
        }
    }

    Dialog(onDismissRequest = { musicPlayer.stop(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize()
                .background(parseHexColor(STATUS_COLORS[colorIndex]))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .testTag("status_text_composer")
        ) {
            LightSystemBarIcons()
            // Top compact toolbar
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { musicPlayer.stop(); onDismiss() }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!previewMode) {
                        IconButton(onClick = { fontIndex = (fontIndex + 1) % STATUS_FONTS.size }) {
                            Icon(Icons.Rounded.TextFields, contentDescription = stringResource(R.string.status_change_font), tint = Color.White)
                        }
                        IconButton(onClick = { colorIndex = (colorIndex + 1) % STATUS_COLORS.size }) {
                            Icon(Icons.Rounded.Palette, contentDescription = stringResource(R.string.status_change_color), tint = Color.White)
                        }
                        IconButton(onClick = { showMusicPicker = true }, modifier = Modifier.testTag("status_open_music")) {
                            Icon(
                                Icons.Rounded.MusicNote,
                                contentDescription = stringResource(R.string.status_add_music),
                                tint = if (musicSelection != null) Color(0xFF67E8F9) else Color.White,
                            )
                        }
                        IconButton(onClick = { showMentionPicker = true }, modifier = Modifier.testTag("status_open_mentions")) {
                            Icon(
                                Icons.Rounded.AlternateEmail,
                                contentDescription = stringResource(R.string.status_mention_people),
                                tint = if (mentionedIds.isNotEmpty()) Color(0xFF67E8F9) else Color.White,
                            )
                        }
                        IconButton(
                            onClick = { allowReshare = !allowReshare },
                            modifier = Modifier.testTag("status_toggle_reshare"),
                        ) {
                            Icon(
                                Icons.Rounded.Repeat,
                                contentDescription = stringResource(R.string.status_allow_reshare),
                                tint = if (allowReshare) Color.White else Color.White.copy(alpha = 0.4f),
                            )
                        }
                    }
                    if (text.isNotBlank()) {
                        TextButton(onClick = { previewMode = !previewMode }) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(if (previewMode) R.string.status_editor_edit else R.string.status_editor_preview),
                                color = Color.White,
                            )
                        }
                    }
                }
            }

            // Music & Mention badges at top of canvas
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                musicSelection?.let { sel ->
                    StatusMusicAttributionPill(
                        title = sel.title,
                        artist = sel.artist,
                        onRemove = if (previewMode) null else ({ musicSelection = null; musicPlayer.stop() }),
                    )
                }
                if (mentionedIds.isNotEmpty()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable(enabled = !previewMode) { showMentionPicker = true },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.AlternateEmail, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.status_mentions_selected, mentionedIds.size),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            if (previewMode) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 30.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = statusFont(STATUS_FONTS[fontIndex]),
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                )
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(StatusPolicy.MAX_TEXT) },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 30.sp,
                        textAlign = TextAlign.Center,
                        fontFamily = statusFont(STATUS_FONTS[fontIndex]),
                    ),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (text.isEmpty()) {
                                Text(
                                    stringResource(R.string.status_type_hint),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 30.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            if (text.isNotBlank()) {
                FloatingActionButton(
                    onClick = {
                        if (submitting) return@FloatingActionButton
                        submitting = true
                        musicPlayer.stop()
                        val clean = text.trim()
                        val bg = STATUS_COLORS[colorIndex]
                        val font = STATUS_FONTS[fontIndex]
                        val extras = StatusComposeExtras(
                            musicSelection = musicSelection,
                            mentionedUserIds = mentionedIds,
                            allowReshare = allowReshare,
                        )
                        if (onPostWithExtras != null) {
                            onPostWithExtras(clean, bg, font, extras)
                        } else {
                            onPost(clean, bg, font)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("status_post_send"),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.action_send))
                }
            }
        }
    }

    if (showMusicPicker) {
        StatusMusicPickerSheet(
            current = musicSelection,
            player = musicPlayer,
            onSelect = {
                musicSelection = it
                showMusicPicker = false
            },
            onDismiss = { showMusicPicker = false },
        )
    }

    if (showMentionPicker) {
        StatusMentionsSheet(
            contacts = contacts,
            selectedIds = mentionedIds.toSet(),
            allowReshare = allowReshare,
            onAllowReshareChange = { allowReshare = it },
            onDone = { ids ->
                mentionedIds = ids.toList()
                showMentionPicker = false
            },
            onDismiss = { showMentionPicker = false },
        )
    }
}

/**
 * Preview with caption before posting a photo/video status or sending media in chat.
 * Supports:
 * - Status Editor tools (Add music, Mention people, Allow reshare toggle) when [isStatusComposer] is true
 * - Media quality selector (`Standard`, `HD`, `Original`) with size estimates & source info when [onQualityChange] is provided
 * - Duplicate-submit protection
 */
@Composable
fun MediaCaptionDialog(
    uri: Uri,
    isVideo: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    /** Shown above the caption: what the video editor did, or why the video can't be sent as it is. */
    note: String? = null,
    noteIsWarning: Boolean = false,
    sendEnabled: Boolean = true,
    isStatusComposer: Boolean = false,
    contacts: List<ContactRow> = emptyList(),
    selectedQuality: MediaQuality? = null,
    qualityOptions: List<MediaQualityPolicy.QualityOptionInfo> = emptyList(),
    sourceProbe: MediaQualityPolicy.MediaProbe? = null,
    onQualityChange: ((MediaQuality) -> Unit)? = null,
    onSendWithStatusExtras: ((caption: String, extras: StatusComposeExtras) -> Unit)? = null,
    onSend: (caption: String) -> Unit = {},
) {
    val context = LocalContext.current
    var caption by rememberSaveable { mutableStateOf("") }
    var musicSelection by remember { mutableStateOf<StatusMusicPolicy.Selection?>(null) }
    var mentionedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var allowReshare by rememberSaveable { mutableStateOf(true) }
    var showMusicPicker by remember { mutableStateOf(false) }
    var showMentionPicker by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    val musicPlayer = remember { StatusMusicPlayer(context) }
    DisposableEffect(Unit) {
        onDispose { musicPlayer.stop() }
    }

    val activeQualityInfo = remember(selectedQuality, qualityOptions) {
        qualityOptions.firstOrNull { it.quality == selectedQuality }
    }
    val effectiveSendEnabled = sendEnabled && !submitting && (activeQualityInfo?.exceedsSizeLimit != true)

    Dialog(onDismissRequest = { musicPlayer.stop(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .testTag("media_caption")
        ) {
            LightSystemBarIcons()
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { musicPlayer.stop(); onDismiss() }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
                }
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isStatusComposer) {
                    IconButton(onClick = { showMusicPicker = true }, modifier = Modifier.testTag("status_media_music")) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = stringResource(R.string.status_add_music),
                            tint = if (musicSelection != null) Color(0xFF67E8F9) else Color.White,
                        )
                    }
                    IconButton(onClick = { showMentionPicker = true }, modifier = Modifier.testTag("status_media_mentions")) {
                        Icon(
                            Icons.Rounded.AlternateEmail,
                            contentDescription = stringResource(R.string.status_mention_people),
                            tint = if (mentionedIds.isNotEmpty()) Color(0xFF67E8F9) else Color.White,
                        )
                    }
                    IconButton(onClick = { allowReshare = !allowReshare }, modifier = Modifier.testTag("status_media_reshare")) {
                        Icon(
                            Icons.Rounded.Repeat,
                            contentDescription = stringResource(R.string.status_allow_reshare),
                            tint = if (allowReshare) Color.White else Color.White.copy(alpha = 0.4f),
                        )
                    }
                }
                if (onEdit != null) {
                    IconButton(onClick = onEdit, modifier = Modifier.testTag("media_edit")) {
                        if (isVideo) Icon(Icons.Rounded.ContentCut, contentDescription = stringResource(R.string.video_edit_open), tint = Color.White)
                        else Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.editor_open), tint = Color.White)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (isVideo) {
                    VideoPlayerView(source = uri.toString(), modifier = Modifier.fillMaxWidth(), autoPlay = false)
                } else {
                    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    musicSelection?.let { sel ->
                        StatusMusicAttributionPill(
                            title = sel.title,
                            artist = sel.artist,
                            onRemove = { musicSelection = null; musicPlayer.stop() },
                        )
                    }
                    if (mentionedIds.isNotEmpty()) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.clickable { showMentionPicker = true },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.AlternateEmail, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.status_mentions_selected, mentionedIds.size),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            // Feature 5: Media Quality Selector (Standard / HD / Original)
            if (onQualityChange != null && selectedQuality != null && qualityOptions.isNotEmpty()) {
                MediaQualitySelectorBar(
                    selected = selectedQuality,
                    options = qualityOptions,
                    sourceProbe = sourceProbe,
                    onSelect = onQualityChange,
                )
            }

            if (note != null) {
                Text(
                    note,
                    color = if (noteIsWarning) Color(0xFFFFB4A9) else Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("media_note"),
                )
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(1024) },
                    placeholder = { Text(stringResource(R.string.caption_hint)) },
                    modifier = Modifier.weight(1f).heightIn(max = 140.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.Gray,
                    ),
                )
                FloatingActionButton(
                    onClick = {
                        if (!effectiveSendEnabled) return@FloatingActionButton
                        submitting = true
                        musicPlayer.stop()
                        val clean = caption.trim()
                        if (onSendWithStatusExtras != null) {
                            onSendWithStatusExtras(
                                clean,
                                StatusComposeExtras(
                                    musicSelection = musicSelection,
                                    mentionedUserIds = mentionedIds,
                                    allowReshare = allowReshare,
                                )
                            )
                        } else {
                            onSend(clean)
                        }
                    },
                    containerColor = if (effectiveSendEnabled) MaterialTheme.colorScheme.primaryContainer else Color.DarkGray,
                    modifier = Modifier.padding(start = 12.dp).testTag("media_send"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.action_send),
                        tint = if (effectiveSendEnabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray,
                    )
                }
            }
        }
    }

    if (showMusicPicker) {
        StatusMusicPickerSheet(
            current = musicSelection,
            player = musicPlayer,
            onSelect = {
                musicSelection = it
                showMusicPicker = false
            },
            onDismiss = { showMusicPicker = false },
        )
    }

    if (showMentionPicker) {
        StatusMentionsSheet(
            contacts = contacts,
            selectedIds = mentionedIds.toSet(),
            allowReshare = allowReshare,
            onAllowReshareChange = { allowReshare = it },
            onDone = { ids ->
                mentionedIds = ids.toList()
                showMentionPicker = false
            },
            onDismiss = { showMentionPicker = false },
        )
    }
}

@Composable
fun StatusMusicAttributionPill(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val label = StatusMusicPolicy.attributionLabel(title, artist)
    if (label.isBlank()) return
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.testTag("status_music_pill"),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFF67E8F9), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onRemove != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.status_music_remove),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp).clickable(onClick = onRemove),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusMusicPickerSheet(
    current: StatusMusicPolicy.Selection?,
    player: StatusMusicPlayer,
    onSelect: (StatusMusicPolicy.Selection?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTrack by remember {
        mutableStateOf(
            current?.trackId?.let { StatusMusicPolicy.findTrack(it) }
                ?: StatusMusicPolicy.BUILT_IN_TRACKS.first()
        )
    }
    var customUri by remember { mutableStateOf(current?.localUri) }
    var customTitle by remember { mutableStateOf(current?.title?.takeIf { current.isLocalCustom }.orEmpty()) }
    var startMs by remember { mutableStateOf(current?.startMs ?: 0L) }
    val isPlaying by player.isPlaying.collectAsState()
    val unavailable by player.unavailable.collectAsState()

    val pickAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            customUri = uri.toString()
            customTitle = uri.lastPathSegment?.substringAfterLast('/')?.take(60) ?: "Custom Audio"
            startMs = 0L
            player.play(
                trackId = null,
                audioUrlOrUri = customUri,
                startMs = 0L,
                durationMs = StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS,
            )
        }
    }

    val tracks = remember(query) { StatusMusicPolicy.searchTracks(query) }

    ModalBottomSheet(
        onDismissRequest = { player.stop(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("status_music_sheet")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.status_music_picker_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (current != null) {
                    TextButton(onClick = { player.stop(); onSelect(null) }) {
                        Text(stringResource(R.string.status_music_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                placeholder = { Text(stringResource(R.string.status_music_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { pickAudioLauncher.launch("audio/*") }) {
                Icon(Icons.Rounded.AudioFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.status_music_local_audio))
            }
            if (unavailable) {
                Text(
                    stringResource(R.string.status_music_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(tracks, key = { it.id }) { track ->
                    val isSelected = customUri == null && selectedTrack.id == track.id
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
                            .clickable {
                                customUri = null
                                selectedTrack = track
                                startMs = StatusMusicPolicy.clampStartMs(startMs, track.durationMs)
                                player.play(track.id, null, startMs, StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                if (isSelected && isPlaying) {
                                    player.stop()
                                } else {
                                    customUri = null
                                    selectedTrack = track
                                    player.play(track.id, null, startMs, StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS)
                                }
                            }
                        ) {
                            Icon(
                                if (isSelected && isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(if (isSelected && isPlaying) R.string.status_music_preview_stop else R.string.status_music_preview_play),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("${track.artist} · ${track.genre}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            StatusMusicPolicy.mmSs(track.durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val totalDurationMs = if (customUri != null) 60_000L else selectedTrack.durationMs
            val clipSeconds = (StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS / 1000L).toInt()
            val maxStartMs = (totalDurationMs - StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS).coerceAtLeast(0L)
            Text(
                stringResource(
                    R.string.status_music_segment_label,
                    StatusMusicPolicy.mmSs(startMs),
                    clipSeconds,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            if (maxStartMs > 0L) {
                Slider(
                    value = startMs.toFloat(),
                    onValueChange = {
                        startMs = StatusMusicPolicy.clampStartMs(it.toLong(), totalDurationMs)
                    },
                    valueRange = 0f..maxStartMs.toFloat(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { player.stop(); onDismiss() }) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        player.stop()
                        val sel = if (customUri != null) {
                            StatusMusicPolicy.Selection(
                                trackId = null,
                                title = customTitle.ifBlank { "Custom Audio" },
                                artist = "",
                                localUri = customUri,
                                startMs = startMs,
                                durationMs = StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS,
                                trackDurationMs = totalDurationMs,
                            )
                        } else {
                            StatusMusicPolicy.Selection(
                                trackId = selectedTrack.id,
                                title = selectedTrack.title,
                                artist = selectedTrack.artist,
                                localUri = null,
                                startMs = StatusMusicPolicy.clampStartMs(startMs, selectedTrack.durationMs),
                                durationMs = StatusMusicPolicy.DEFAULT_SEGMENT_DURATION_MS,
                                trackDurationMs = selectedTrack.durationMs,
                            )
                        }
                        onSelect(sel)
                    },
                    modifier = Modifier.testTag("status_music_confirm"),
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusMentionsSheet(
    contacts: List<ContactRow>,
    selectedIds: Set<String>,
    allowReshare: Boolean,
    onAllowReshareChange: (Boolean) -> Unit,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var currentSelection by remember { mutableStateOf(selectedIds) }

    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts
        else contacts.filter {
            it.shownName.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("status_mentions_sheet")) {
            Text(stringResource(R.string.status_mention_people), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.status_mentions_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.status_allow_reshare), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.status_allow_reshare_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = allowReshare, onCheckedChange = onAllowReshareChange)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(filtered, key = { it.userId }) { contact ->
                    val checked = contact.userId in currentSelection
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                currentSelection = if (checked) {
                                    currentSelection - contact.userId
                                } else if (currentSelection.size < StatusPolicy.MAX_MENTIONS) {
                                    currentSelection + contact.userId
                                } else currentSelection
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UserAvatar(contact.shownName, avatarUrl = contact.avatarUrl, size = 40.dp, colorHex = contact.avatarColorHex)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contact.shownName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("@${contact.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDone(currentSelection) }, modifier = Modifier.testTag("status_mentions_confirm")) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

/**
 * Feature 5: Media Quality Selector bar shown in `MediaCaptionDialog` for chat attachments.
 */
@Composable
private fun MediaQualitySelectorBar(
    selected: MediaQuality,
    options: List<MediaQualityPolicy.QualityOptionInfo>,
    sourceProbe: MediaQualityPolicy.MediaProbe?,
    onSelect: (MediaQuality) -> Unit,
) {
    val context = LocalContext.current
    val currentInfo = options.firstOrNull { it.quality == selected }
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("media_quality_selector")
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.HighQuality, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.media_quality_label), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            if (sourceProbe != null && sourceProbe.originalSizeBytes > 0L) {
                val origSizeStr = Formatters.bytes(context, sourceProbe.originalSizeBytes)
                val sourceText = if (sourceProbe.width > 0 && sourceProbe.height > 0) {
                    stringResource(R.string.media_quality_source_size, origSizeStr, sourceProbe.width, sourceProbe.height)
                } else {
                    stringResource(R.string.media_quality_source_size_no_dim, origSizeStr)
                }
                Text(sourceText, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { opt ->
                val isSelected = opt.quality == selected
                val label = when (opt.quality) {
                    MediaQuality.STANDARD -> stringResource(R.string.media_quality_standard)
                    MediaQuality.HD -> stringResource(R.string.media_quality_hd)
                    MediaQuality.ORIGINAL -> stringResource(R.string.media_quality_original)
                }
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(opt.quality) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        labelColor = Color.White,
                    ),
                    modifier = Modifier.testTag("quality_chip_${opt.quality.name.lowercase()}"),
                )
            }
        }
        if (currentInfo != null) {
            val sizeStr = Formatters.bytes(context, currentInfo.sizeBytes)
            val desc = when (currentInfo.quality) {
                MediaQuality.STANDARD -> stringResource(R.string.media_quality_standard_desc, sizeStr)
                MediaQuality.HD -> stringResource(
                    R.string.media_quality_hd_desc,
                    currentInfo.targetWidth,
                    currentInfo.targetHeight,
                    sizeStr,
                )
                MediaQuality.ORIGINAL -> stringResource(R.string.media_quality_original_desc, sizeStr)
            }
            Text(desc, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            if (currentInfo.sourceBelowHdThreshold) {
                Text(
                    stringResource(R.string.media_quality_hd_low_res_note),
                    color = Color(0xFFFDE68A),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (currentInfo.exceedsSizeLimit) {
                Text(
                    stringResource(R.string.media_quality_exceeds_limit, Formatters.bytes(context, currentInfo.maxAllowedBytes)),
                    color = Color(0xFFFFB4A9),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Feature 1: Multi-Photo Status Collage Builder Dialog.
 * Supports 2..6 photos, 5 layouts, cell selection, replace/remove/reorder, zoom & drag-pan without
 * aspect-ratio distortion, and renders a high-resolution publishable JPEG URI.
 */
@Composable
fun StatusCollageEditorDialog(
    initialUris: List<Uri>,
    onDone: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val uris = remember { mutableStateListOf<Uri>().apply { addAll(initialUris.take(StatusCollageMath.MAX_IMAGES)) } }
    val cellStates = remember {
        mutableStateListOf<StatusCollageMath.CellState>().apply {
            repeat(initialUris.size.coerceAtMost(StatusCollageMath.MAX_IMAGES)) { add(StatusCollageMath.CellState()) }
        }
    }
    val bitmaps = remember { mutableMapOf<String, Bitmap>() }
    var layout by remember { mutableStateOf(StatusCollageMath.defaultLayoutForCount(initialUris.size.coerceAtLeast(2))) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    val addPhotosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { picked ->
        val remaining = StatusCollageMath.MAX_IMAGES - uris.size
        val toAdd = picked.take(remaining.coerceAtLeast(0))
        toAdd.forEach { u ->
            uris.add(u)
            cellStates.add(StatusCollageMath.CellState())
        }
        if (uris.size >= 2) {
            layout = StatusCollageMath.defaultLayoutForCount(uris.size)
        }
    }

    val replacePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null && selectedIndex in uris.indices) {
            uris[selectedIndex] = picked
            cellStates[selectedIndex] = StatusCollageMath.CellState()
        }
    }

    LaunchedEffect(uris.toList()) {
        withContext(Dispatchers.IO) {
            uris.forEach { u ->
                val key = u.toString()
                if (!bitmaps.containsKey(key)) {
                    decodeCollageBitmap(context, u, maxDim = 1280)?.let { bmp -> bitmaps[key] = bmp }
                }
            }
        }
    }

    val layouts = remember(uris.size) { StatusCollageMath.availableLayoutsForCount(uris.size.coerceAtLeast(2)) }
    LaunchedEffect(layouts) {
        if (layout !in layouts) layout = layouts.first()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("status_collage_dialog")
        ) {
            LightSystemBarIcons()
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.White)
                }
                Text(
                    stringResource(R.string.status_collage_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (uris.size < StatusCollageMath.MAX_IMAGES) {
                    IconButton(onClick = { addPhotosLauncher.launch("image/*") }) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.status_collage_add_photo), tint = Color.White)
                    }
                }
                IconButton(
                    onClick = {
                        if (uris.size < StatusCollageMath.MIN_IMAGES || saving) return@IconButton
                        saving = true
                        scope.launch {
                            val resultUri = withContext(Dispatchers.Default) {
                                runCatching {
                                    val rendered = renderCollageBitmap(
                                        uris = uris.toList(),
                                        states = cellStates.toList(),
                                        bitmaps = bitmaps,
                                        layout = layout,
                                    )
                                    val (file, outUri) = container.media.newCaptureFile("jpg")
                                    file.outputStream().use { rendered.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                    outUri
                                }.getOrNull()
                            }
                            saving = false
                            if (resultUri != null) onDone(resultUri)
                        }
                    },
                    enabled = uris.size >= StatusCollageMath.MIN_IMAGES && !saving,
                    modifier = Modifier.testTag("status_collage_done"),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.action_done), tint = Color.White)
                }
            }

            // Central 9:16 collage preview canvas
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                if (uris.size < StatusCollageMath.MIN_IMAGES) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.status_collage_select_photos), color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        AssistChip(
                            onClick = { addPhotosLauncher.launch("image/*") },
                            label = { Text(stringResource(R.string.status_collage_add_photo), color = Color.White) },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White) },
                        )
                    }
                } else if (saving) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    val slots = remember(layout, uris.size) { StatusCollageMath.slots(layout, uris.size) }
                    Canvas(
                        Modifier.aspectRatio(9f / 16f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF111827))
                            .pointerInput(slots, uris.size) {
                                detectTapGestures { pos ->
                                    val nx = pos.x / size.width.toFloat().coerceAtLeast(1f)
                                    val ny = pos.y / size.height.toFloat().coerceAtLeast(1f)
                                    val hit = slots.indexOfFirst { nx in it.left..it.right && ny in it.top..it.bottom }
                                    if (hit in uris.indices) selectedIndex = hit
                                }
                            }
                            .pointerInput(slots, selectedIndex, uris.size) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val idx = selectedIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0))
                                    if (idx in cellStates.indices) {
                                        val cur = cellStates[idx]
                                        val dx = -(dragAmount.x / size.width.toFloat().coerceAtLeast(1f)) * 2f
                                        val dy = -(dragAmount.y / size.height.toFloat().coerceAtLeast(1f)) * 2f
                                        cellStates[idx] = StatusCollageMath.clampState(
                                            cur.copy(panX = cur.panX + dx, panY = cur.panY + dy)
                                        )
                                    }
                                }
                            }
                    ) {
                        val cw = size.width
                        val ch = size.height
                        val nc = drawContext.canvas.nativeCanvas
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        slots.forEachIndexed { idx, slot ->
                            val uri = uris.getOrNull(idx) ?: return@forEachIndexed
                            val bmp = bitmaps[uri.toString()] ?: return@forEachIndexed
                            val state = cellStates.getOrNull(idx) ?: StatusCollageMath.CellState()
                            val dst = RectF(slot.left * cw, slot.top * ch, slot.right * cw, slot.bottom * ch)
                            val cellAspect = (dst.width() / dst.height().coerceAtLeast(1f))
                            val srcRect = StatusCollageMath.computeSourceRect(bmp.width, bmp.height, cellAspect, state)
                            val src = Rect(srcRect.left, srcRect.top, srcRect.right, srcRect.bottom)
                            nc.save()
                            nc.clipRect(dst)
                            nc.drawBitmap(bmp, src, dst, paint)
                            nc.restore()
                            if (idx == selectedIndex) {
                                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeWidth = 6f
                                    color = android.graphics.Color.CYAN
                                }
                                nc.drawRect(dst, borderPaint)
                            }
                        }
                    }
                }
            }

            // Selected cell controls (zoom, reorder, replace, remove)
            if (uris.size >= StatusCollageMath.MIN_IMAGES && selectedIndex in uris.indices) {
                val activeState = cellStates.getOrNull(selectedIndex) ?: StatusCollageMath.CellState()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.status_collage_zoom), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
                    Slider(
                        value = activeState.zoom,
                        onValueChange = { z ->
                            cellStates[selectedIndex] = StatusCollageMath.clampState(activeState.copy(zoom = z))
                        },
                        valueRange = StatusCollageMath.MIN_ZOOM..StatusCollageMath.MAX_ZOOM,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            if (selectedIndex > 0) {
                                val u = uris.removeAt(selectedIndex)
                                val s = cellStates.removeAt(selectedIndex)
                                uris.add(selectedIndex - 1, u)
                                cellStates.add(selectedIndex - 1, s)
                                selectedIndex -= 1
                            }
                        },
                        enabled = selectedIndex > 0,
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.status_collage_move_earlier), tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            if (selectedIndex < uris.lastIndex) {
                                val u = uris.removeAt(selectedIndex)
                                val s = cellStates.removeAt(selectedIndex)
                                uris.add(selectedIndex + 1, u)
                                cellStates.add(selectedIndex + 1, s)
                                selectedIndex += 1
                            }
                        },
                        enabled = selectedIndex < uris.lastIndex,
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.status_collage_move_later), tint = Color.White)
                    }
                    IconButton(onClick = { replacePhotoLauncher.launch("image/*") }) {
                        Icon(Icons.Rounded.FindReplace, contentDescription = stringResource(R.string.status_collage_replace_photo), tint = Color.White)
                    }
                    if (uris.size > StatusCollageMath.MIN_IMAGES) {
                        IconButton(
                            onClick = {
                                uris.removeAt(selectedIndex)
                                cellStates.removeAt(selectedIndex)
                                selectedIndex = selectedIndex.coerceAtMost(uris.lastIndex)
                            }
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.status_collage_remove_photo), tint = Color(0xFFFFB4A9))
                        }
                    }
                }
            }

            // Layout selector strip
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                layouts.forEach { l ->
                    val label = when (l) {
                        StatusCollageMath.Layout.SPLIT_VERTICAL -> stringResource(R.string.status_collage_layout_split_v)
                        StatusCollageMath.Layout.SPLIT_HORIZONTAL -> stringResource(R.string.status_collage_layout_split_h)
                        StatusCollageMath.Layout.THREE_FEATURE_LEFT -> stringResource(R.string.status_collage_layout_three)
                        StatusCollageMath.Layout.GRID_2X2 -> stringResource(R.string.status_collage_layout_grid_4)
                        StatusCollageMath.Layout.FLEX_GRID -> stringResource(R.string.status_collage_layout_flex)
                    }
                    AssistChip(
                        onClick = { layout = l },
                        label = { Text(label, color = if (layout == l) MaterialTheme.colorScheme.primary else Color.White) },
                        leadingIcon = { Icon(Icons.Rounded.GridView, contentDescription = null, tint = if (layout == l) MaterialTheme.colorScheme.primary else Color.White) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (layout == l) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

private fun decodeCollageBitmap(context: Context, uri: Uri, maxDim: Int = 1280): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = max(w, h).toFloat() / maxDim
            if (scale > 1f) {
                decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = resolver.openInputStream(uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        } ?: 1
        val degrees = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (degrees == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
    }
}.getOrNull()

private fun renderCollageBitmap(
    uris: List<Uri>,
    states: List<StatusCollageMath.CellState>,
    bitmaps: Map<String, Bitmap>,
    layout: StatusCollageMath.Layout,
    outWidth: Int = 1080,
    outHeight: Int = 1920,
): Bitmap {
    val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.parseColor("#111827"))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val slots = StatusCollageMath.slots(layout, uris.size)
    slots.forEachIndexed { idx, slot ->
        val uri = uris.getOrNull(idx) ?: return@forEachIndexed
        val bmp = bitmaps[uri.toString()] ?: return@forEachIndexed
        val state = states.getOrNull(idx) ?: StatusCollageMath.CellState()
        val dst = RectF(slot.left * outWidth, slot.top * outHeight, slot.right * outWidth, slot.bottom * outHeight)
        val cellAspect = dst.width() / dst.height().coerceAtLeast(1f)
        val srcRect = StatusCollageMath.computeSourceRect(bmp.width, bmp.height, cellAspect, state)
        val src = Rect(srcRect.left, srcRect.top, srcRect.right, srcRect.bottom)
        canvas.save()
        canvas.clipRect(dst)
        canvas.drawBitmap(bmp, src, dst, paint)
        canvas.restore()
    }
    return out
}
