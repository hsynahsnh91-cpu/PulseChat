package com.example.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.domain.ImageEditMath
import com.example.domain.PhotoStickerMath
import com.example.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.example.ui.common.LightSystemBarIcons

private enum class EditTool { CROP, DRAW, TEXT, ARROW, BLUR, STICKER }

/** Edit operations in normalized image coordinates (0..1), so preview and export match exactly. */
private sealed class EditOp {
    data class Stroke(val points: List<Offset>, val color: Color, val width: Float) : EditOp()
    data class Arrow(val start: Offset, val end: Offset, val color: Color, val width: Float) : EditOp()
    data class Label(val text: String, val at: Offset, val color: Color, val size: Float) : EditOp()
    data class Blur(val box: ImageEditMath.Box) : EditOp()
    data class Crop(val before: ImageEditMath.Box) : EditOp() // undo record for crop changes
    data class StickersChange(val before: List<PhotoStickerMath.Sticker>, val after: List<PhotoStickerMath.Sticker>) : EditOp()
}

private val PALETTE = com.example.ui.theme.DrawingPalette.Colors

/**
 * Photo editor shown before sending or posting a status: crop, draw, text, arrows, privacy blur,
 * and movable/resizable/rotatable photo stickers with undo/redo.
 * [onDone] receives a new JPEG (content:// URI) or the original URI if nothing changed.
 */
@Composable
fun ImageEditorDialog(source: Uri, onDone: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val ops = remember { mutableStateListOf<EditOp>() }
    val redoOps = remember { mutableStateListOf<EditOp>() }
    var stickers by remember { mutableStateOf<List<PhotoStickerMath.Sticker>>(emptyList()) }
    val stickerBitmaps = remember { mutableMapOf<String, Bitmap>() }
    var selectedStickerId by remember { mutableStateOf<String?>(null) }
    var crop by remember { mutableStateOf(ImageEditMath.FULL) }
    var tool by remember { mutableStateOf(EditTool.DRAW) }
    var color by remember { mutableStateOf(PALETTE[2]) }
    var width by remember { mutableStateOf(0.012f) }
    var drawing by remember { mutableStateOf<EditOp?>(null) }
    var textAt by remember { mutableStateOf<Offset?>(null) }

    fun pushOp(op: EditOp) {
        ops += op
        redoOps.clear()
    }

    fun applyStickersTransform(next: List<PhotoStickerMath.Sticker>, recordUndo: Boolean = true) {
        if (next == stickers) return
        if (recordUndo) {
            pushOp(EditOp.StickersChange(before = stickers, after = next))
        }
        stickers = next
    }

    val pickStickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { loadForEditing(context, uri, maxDim = 720) }
                if (bmp != null) {
                    val uriKey = uri.toString()
                    stickerBitmaps[uriKey] = bmp
                    val aspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                    val newSticker = PhotoStickerMath.createSticker(
                        id = UUID.randomUUID().toString(),
                        uriString = uriKey,
                        aspectRatio = aspect,
                        existingCount = stickers.size,
                    )
                    applyStickersTransform(stickers + newSticker, recordUndo = true)
                    selectedStickerId = newSticker.id
                    tool = EditTool.STICKER
                }
            }
        }
    }

    LaunchedEffect(source) {
        val bmp = withContext(Dispatchers.IO) { loadForEditing(context, source) }
        if (bmp == null) loadFailed = true else { base = bmp; preview = bmp }
    }
    // The preview shows the real blur result (same code as the export).
    val blurKey = ops.filterIsInstance<EditOp.Blur>()
    LaunchedEffect(blurKey, base) {
        val b = base ?: return@LaunchedEffect
        val boxes = blurKey.map { it.box }
        preview = if (boxes.isEmpty()) b else withContext(Dispatchers.Default) { applyBlurs(b, boxes) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            LightSystemBarIcons()
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel), tint = Color.White) }
                Text(stringResource(R.string.editor_title), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val last = ops.removeLastOrNull() ?: return@IconButton
                    redoOps += last
                    when (last) {
                        is EditOp.Crop -> crop = last.before
                        is EditOp.StickersChange -> {
                            stickers = last.before
                            if (selectedStickerId != null && stickers.none { it.id == selectedStickerId }) {
                                selectedStickerId = stickers.lastOrNull()?.id
                            }
                        }
                        else -> Unit
                    }
                }, enabled = ops.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.editor_undo), tint = if (ops.isNotEmpty()) Color.White else Color.Gray) }
                IconButton(onClick = {
                    val next = redoOps.removeLastOrNull() ?: return@IconButton
                    ops += next
                    when (next) {
                        is EditOp.StickersChange -> {
                            stickers = next.after
                            selectedStickerId = stickers.lastOrNull()?.id
                        }
                        else -> Unit
                    }
                }, enabled = redoOps.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.action_next), tint = if (redoOps.isNotEmpty()) Color.White else Color.Gray) }
                IconButton(onClick = {
                    val b = preview ?: return@IconButton
                    if (ops.isEmpty() && stickers.isEmpty() && crop == ImageEditMath.FULL) { onDone(source); return@IconButton }
                    saving = true
                    scope.launch {
                        val uri = withContext(Dispatchers.Default) {
                            runCatching {
                                val out = renderFinal(b, ops.toList(), stickers, stickerBitmaps, crop)
                                val (file, uri) = container.media.newCaptureFile("jpg")
                                file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                uri
                            }.getOrNull()
                        }
                        saving = false
                        if (uri != null) onDone(uri) else loadFailed = true
                    }
                }, enabled = preview != null && !saving) { Icon(Icons.Rounded.Check, stringResource(R.string.action_done), tint = Color.White) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val img = preview
                val density = LocalDensity.current
                when {
                    loadFailed -> Text(stringResource(R.string.editor_failed), color = Color.White)
                    img == null || saving -> CircularProgressIndicator()
                    else -> {
                        val cw = with(density) { maxWidth.toPx() }
                        val ch = with(density) { maxHeight.toPx() }
                        val shown = ImageEditMath.fit(cw, ch, img.width.toFloat(), img.height.toFloat())
                        fun norm(p: Offset): Offset = ImageEditMath.normalize(p.x, p.y, shown).let { Offset(it.first, it.second) }
                        val tolerance = with(density) { 28.dp.toPx() } / max(1f, min(shown.width, shown.height))
                        var handle by remember { mutableStateOf(ImageEditMath.Handle.MOVE) }
                        var cropStart by remember { mutableStateOf(crop) }
                        var stickersBeforeDrag by remember { mutableStateOf(stickers) }
                        var draggingStickerId by remember { mutableStateOf<String?>(null) }
                        var lastNorm by remember { mutableStateOf(Offset.Zero) }
                        var dragStart by remember { mutableStateOf(Offset.Zero) }
                        Image(img.asImageBitmap(), contentDescription = stringResource(R.string.editor_photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        Canvas(
                            Modifier.fillMaxSize()
                                .pointerInput(tool, shown, stickers) {
                                    detectTapGestures { p ->
                                        val n = norm(p)
                                        val hitSticker = PhotoStickerMath.hitTestTopmost(stickers, n.x, n.y)
                                        if (hitSticker != null) {
                                            selectedStickerId = hitSticker.id
                                            tool = EditTool.STICKER
                                        } else if (tool == EditTool.TEXT) {
                                            textAt = n
                                        }
                                    }
                                }
                                .pointerInput(tool, shown, color, width, stickers) {
                                    detectDragGestures(
                                        onDragStart = { p ->
                                            val n = norm(p)
                                            lastNorm = n
                                            dragStart = n
                                            val hitSticker = if (tool == EditTool.STICKER) {
                                                PhotoStickerMath.hitTestTopmost(stickers, n.x, n.y)
                                            } else null
                                            if (hitSticker != null) {
                                                selectedStickerId = hitSticker.id
                                                draggingStickerId = hitSticker.id
                                                stickersBeforeDrag = stickers
                                                drawing = null
                                            } else {
                                                draggingStickerId = null
                                                drawing = when (tool) {
                                                    EditTool.DRAW -> EditOp.Stroke(listOf(n), color, width)
                                                    EditTool.ARROW -> EditOp.Arrow(n, n, color, width)
                                                    EditTool.BLUR -> EditOp.Blur(ImageEditMath.boxOf(n.x, n.y, n.x, n.y))
                                                    EditTool.CROP -> { handle = ImageEditMath.handleAt(n.x, n.y, crop, tolerance); cropStart = crop; null }
                                                    EditTool.TEXT -> {
                                                        val hit = ops.indexOfLast { it is EditOp.Label && labelHit(it, n) }
                                                        if (hit >= 0) ops.removeAt(hit) as EditOp.Label else null
                                                    }
                                                    EditTool.STICKER -> null
                                                }
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            val n = norm(change.position)
                                            val activeStickerId = draggingStickerId
                                            if (activeStickerId != null) {
                                                val dx = n.x - lastNorm.x
                                                val dy = n.y - lastNorm.y
                                                stickers = stickers.map { s ->
                                                    if (s.id == activeStickerId) PhotoStickerMath.moveSticker(s, dx, dy) else s
                                                }
                                            } else {
                                                when (val d = drawing) {
                                                    is EditOp.Stroke -> drawing = d.copy(points = d.points + n)
                                                    is EditOp.Arrow -> drawing = d.copy(end = n)
                                                    is EditOp.Blur -> drawing = EditOp.Blur(ImageEditMath.boxOf(dragStart.x, dragStart.y, n.x, n.y))
                                                    is EditOp.Label -> drawing = d.copy(at = Offset(n.x.coerceIn(0f, 0.95f), n.y.coerceIn(0f, 0.95f)))
                                                    else -> if (tool == EditTool.CROP) crop = ImageEditMath.dragCrop(crop, handle, n.x - lastNorm.x, n.y - lastNorm.y)
                                                }
                                            }
                                            lastNorm = n
                                            change.consume()
                                        },
                                        onDragEnd = {
                                            if (draggingStickerId != null) {
                                                if (stickers != stickersBeforeDrag) {
                                                    pushOp(EditOp.StickersChange(stickersBeforeDrag, stickers))
                                                }
                                                draggingStickerId = null
                                            } else {
                                                when (val d = drawing) {
                                                    is EditOp.Stroke -> if (d.points.size > 1) pushOp(d)
                                                    is EditOp.Arrow -> if ((d.end - d.start).getDistance() > 0.02f) pushOp(d)
                                                    is EditOp.Blur -> if (d.box.width > 0.02f && d.box.height > 0.02f) pushOp(d)
                                                    is EditOp.Label -> pushOp(d)
                                                    else -> if (tool == EditTool.CROP && crop != cropStart) pushOp(EditOp.Crop(cropStart))
                                                }
                                            }
                                            drawing = null
                                        },
                                        onDragCancel = {
                                            draggingStickerId = null
                                            (drawing as? EditOp.Label)?.let { pushOp(it) }
                                            drawing = null
                                        },
                                    )
                                }
                                .semantics { contentDescription = context.getString(R.string.editor_canvas) },
                        ) {
                            (ops + listOfNotNull(drawing)).forEach { op -> drawOp(op, shown, preview = true) }
                            stickers.forEach { st ->
                                val bmp = stickerBitmaps[st.uriString]
                                if (bmp != null) {
                                    drawStickerPreview(st, bmp, shown, selected = st.id == selectedStickerId && tool == EditTool.STICKER)
                                }
                            }
                            if (tool == EditTool.CROP || crop != ImageEditMath.FULL) drawCropOverlay(crop, shown, active = tool == EditTool.CROP)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                if (tool == EditTool.DRAW || tool == EditTool.ARROW || tool == EditTool.TEXT) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PALETTE.forEach { c ->
                            val label = stringResource(R.string.editor_color)
                            Box(
                                Modifier.size(30.dp).background(c, CircleShape)
                                    .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                    .clickable { color = c }
                                    .semantics { contentDescription = label; selected = c == color; role = Role.RadioButton },
                            )
                        }
                    }
                    if (tool != EditTool.TEXT) Slider(value = width, onValueChange = { width = it }, valueRange = 0.004f..0.04f, modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (tool == EditTool.STICKER) {
                    val activeSticker = stickers.firstOrNull { it.id == selectedStickerId } ?: stickers.lastOrNull()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { pickStickerLauncher.launch("image/*") }) {
                            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.status_photo_sticker_add), color = Color.White)
                        }
                        if (activeSticker != null) {
                            Row {
                                IconButton(
                                    onClick = {
                                        val dup = PhotoStickerMath.duplicateSticker(activeSticker, UUID.randomUUID().toString(), stickers.size)
                                        if (dup != null) {
                                            applyStickersTransform(stickers + dup)
                                            selectedStickerId = dup.id
                                        }
                                    },
                                    enabled = stickers.size < PhotoStickerMath.MAX_STICKERS,
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.status_photo_sticker_duplicate), tint = Color.White)
                                }
                                IconButton(
                                    onClick = {
                                        val next = PhotoStickerMath.removeSticker(stickers, activeSticker.id)
                                        applyStickersTransform(next)
                                        selectedStickerId = next.lastOrNull()?.id
                                    },
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.status_photo_sticker_remove), tint = Color(0xFFFFB4A9))
                                }
                            }
                        }
                    }
                    if (activeSticker != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.status_photo_sticker_scale), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(76.dp))
                            Slider(
                                value = activeSticker.scale,
                                onValueChange = { s ->
                                    stickers = stickers.map {
                                        if (it.id == activeSticker.id) PhotoStickerMath.clampSticker(it.copy(scale = s)) else it
                                    }
                                },
                                valueRange = PhotoStickerMath.MIN_SCALE..PhotoStickerMath.MAX_SCALE,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.status_photo_sticker_rotate), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(76.dp))
                            Slider(
                                value = activeSticker.rotationDegrees,
                                onValueChange = { deg ->
                                    stickers = stickers.map {
                                        if (it.id == activeSticker.id) PhotoStickerMath.clampSticker(it.copy(rotationDegrees = deg)) else it
                                    }
                                },
                                valueRange = -180f..180f,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (tool == EditTool.BLUR) Text(stringResource(R.string.editor_blur_hint), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                if (tool == EditTool.TEXT) Text(stringResource(R.string.editor_text_hint), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolButton(Icons.Rounded.Crop, stringResource(R.string.editor_crop), tool == EditTool.CROP) { tool = EditTool.CROP }
                    ToolButton(Icons.Rounded.Brush, stringResource(R.string.editor_draw), tool == EditTool.DRAW) { tool = EditTool.DRAW }
                    ToolButton(Icons.Rounded.TextFields, stringResource(R.string.editor_text), tool == EditTool.TEXT) { tool = EditTool.TEXT }
                    ToolButton(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.editor_arrow), tool == EditTool.ARROW) { tool = EditTool.ARROW }
                    ToolButton(Icons.Rounded.BlurOn, stringResource(R.string.editor_blur), tool == EditTool.BLUR) { tool = EditTool.BLUR }
                    ToolButton(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.status_photo_sticker_add), tool == EditTool.STICKER) {
                        tool = EditTool.STICKER
                        if (stickers.isEmpty()) pickStickerLauncher.launch("image/*")
                    }
                }
            }
        }
    }
    textAt?.let { at ->
        var value by remember(at) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textAt = null },
            title = { Text(stringResource(R.string.editor_add_text)) },
            text = { OutlinedTextField(value = value, onValueChange = { value = it.take(120) }, singleLine = false, maxLines = 3) },
            confirmButton = {
                TextButton(onClick = {
                    if (value.isNotBlank()) pushOp(EditOp.Label(value.trim(), Offset(at.x.coerceIn(0f, 0.9f), at.y.coerceIn(0f, 0.95f)), color, 0.06f))
                    textAt = null
                }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(6.dp).semantics { this.selected = selected; role = Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else Color.White)
        Spacer(Modifier.width(2.dp))
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

private fun labelHit(op: EditOp, n: Offset): Boolean {
    val l = op as? EditOp.Label ?: return false
    val w = (l.text.length * l.size * 0.55f).coerceAtMost(1f)
    return n.x >= l.at.x - 0.02f && n.x <= l.at.x + w && n.y >= l.at.y - 0.02f && n.y <= l.at.y + l.size * 1.3f
}

// ---------------------------------------------------------------------------- drawing (preview)

private fun DrawScope.drawOp(op: EditOp, shown: ImageEditMath.Box, preview: Boolean) {
    fun p(o: Offset) = Offset(shown.left + o.x * shown.width, shown.top + o.y * shown.height)
    val unit = min(shown.width, shown.height)
    when (op) {
        is EditOp.Stroke -> {
            val path = Path().apply { op.points.firstOrNull()?.let { moveTo(p(it).x, p(it).y) }; op.points.drop(1).forEach { lineTo(p(it).x, p(it).y) } }
            drawPath(path, op.color, style = Stroke(width = op.width * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        is EditOp.Arrow -> {
            val s = p(op.start); val e = p(op.end); val w = op.width * unit
            drawLine(op.color, s, e, strokeWidth = w, cap = StrokeCap.Round)
            arrowHead(s, e, w).forEach { tip -> drawLine(op.color, e, tip, strokeWidth = w, cap = StrokeCap.Round) }
        }
        is EditOp.Label -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = op.color.toArgb(); textSize = op.size * shown.height; isFakeBoldText = true
                setShadowLayer(4f, 0f, 0f, if (op.color == Color.Black) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            }
            val at = p(op.at)
            drawContext.canvas.nativeCanvas.drawText(op.text, at.x, at.y + paint.textSize, paint)
        }
        is EditOp.Blur -> if (preview) {
            // The pixels are already blurred in the preview bitmap; outline the area while editing.
            drawRect(Color.White.copy(alpha = 0.6f), topLeft = p(Offset(op.box.left, op.box.top)),
                size = Size(op.box.width * shown.width, op.box.height * shown.height), style = Stroke(width = 2f))
        }
        is EditOp.Crop, is EditOp.StickersChange -> Unit
    }
}

private fun DrawScope.drawStickerPreview(
    sticker: PhotoStickerMath.Sticker,
    bitmap: Bitmap,
    shown: ImageEditMath.Box,
    selected: Boolean,
) {
    val cx = shown.left + sticker.centerX * shown.width
    val cy = shown.top + sticker.centerY * shown.height
    val w = sticker.halfWidth * 2f * shown.width
    val h = sticker.halfHeight * 2f * shown.height
    val nc = drawContext.canvas.nativeCanvas
    nc.save()
    nc.translate(cx, cy)
    nc.rotate(sticker.rotationDegrees)
    val dst = android.graphics.RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    nc.drawBitmap(bitmap, null, dst, paint)
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (selected) 5f else 3f
        color = if (selected) android.graphics.Color.CYAN else android.graphics.Color.WHITE
    }
    nc.drawRoundRect(dst, 12f, 12f, borderPaint)
    nc.restore()
}

private fun arrowHead(s: Offset, e: Offset, width: Float): List<Offset> {
    val angle = atan2(e.y - s.y, e.x - s.x)
    val len = max(18f, width * 4f)
    val spread = Math.toRadians(28.0).toFloat()
    return listOf(angle + Math.PI.toFloat() - spread, angle + Math.PI.toFloat() + spread).map { a -> Offset(e.x + len * cos(a), e.y + len * sin(a)) }
}

private fun DrawScope.drawCropOverlay(crop: ImageEditMath.Box, shown: ImageEditMath.Box, active: Boolean) {
    val l = shown.left + crop.left * shown.width
    val t = shown.top + crop.top * shown.height
    val r = shown.left + crop.right * shown.width
    val b = shown.top + crop.bottom * shown.height
    val dim = Color.Black.copy(alpha = if (active) 0.55f else 0.75f)
    drawRect(dim, Offset(shown.left, shown.top), Size(shown.width, t - shown.top))
    drawRect(dim, Offset(shown.left, b), Size(shown.width, shown.bottom - b))
    drawRect(dim, Offset(shown.left, t), Size(l - shown.left, b - t))
    drawRect(dim, Offset(r, t), Size(shown.right - r, b - t))
    if (active) {
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 3f))
        listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { drawCircle(Color.White, radius = 14f, center = it) }
    }
}

// ---------------------------------------------------------------------------- bitmap work

/** Decodes the photo upright (EXIF orientation applied), at most [maxDim] px on its long side. */
private fun loadForEditing(context: Context, uri: Uri, maxDim: Int = 1600): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = max(w, h).toFloat() / maxDim
            if (scale > 1f) decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    } else {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val orientation = resolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        val degrees = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        val upright = if (degrees == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
        val scale = max(upright.width, upright.height).toFloat() / maxDim
        if (scale > 1f) Bitmap.createScaledBitmap(upright, (upright.width / scale).toInt(), (upright.height / scale).toInt(), true) else upright
    }.let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
}.getOrNull()

/** Copy of [base] with every blur area pixelated (irreversibly replaces those pixels). */
private fun applyBlurs(base: Bitmap, boxes: List<ImageEditMath.Box>): Bitmap {
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    boxes.forEach { box ->
        val px = ImageEditMath.toPixels(box, out.width, out.height)
        val pixels = IntArray(px.width * px.height)
        out.getPixels(pixels, 0, px.width, px.left, px.top, px.width, px.height)
        val block = ImageEditMath.pixelateBlock(px.width, px.height)
        ImageEditMath.pixelate(pixels, px.width, px.height, block)
        // A second, offset pass removes any detail left at the block edges.
        ImageEditMath.pixelate(pixels, px.width, px.height, block + block / 2)
        out.setPixels(pixels, 0, px.width, px.left, px.top, px.width, px.height)
    }
    return out
}

/** Draws strokes, arrows, text, and photo stickers onto the (already blurred) bitmap, then crops. */
private fun renderFinal(
    blurred: Bitmap,
    ops: List<EditOp>,
    stickers: List<PhotoStickerMath.Sticker>,
    stickerBitmaps: Map<String, Bitmap>,
    crop: ImageEditMath.Box,
): Bitmap {
    val out = blurred.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val w = out.width.toFloat(); val h = out.height.toFloat(); val unit = min(w, h)
    fun p(o: Offset) = Offset(o.x * w, o.y * h)
    ops.forEach { op ->
        when (op) {
            is EditOp.Stroke -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = op.color.toArgb(); style = Paint.Style.STROKE; strokeWidth = op.width * unit
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                }
                val path = android.graphics.Path()
                op.points.firstOrNull()?.let { path.moveTo(p(it).x, p(it).y) }
                op.points.drop(1).forEach { path.lineTo(p(it).x, p(it).y) }
                canvas.drawPath(path, paint)
            }
            is EditOp.Arrow -> {
                val sw = op.width * unit
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = op.color.toArgb(); strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
                val s = p(op.start); val e = p(op.end)
                canvas.drawLine(s.x, s.y, e.x, e.y, paint)
                arrowHead(s, e, sw).forEach { tip -> canvas.drawLine(e.x, e.y, tip.x, tip.y, paint) }
            }
            is EditOp.Label -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = op.color.toArgb(); textSize = op.size * h; isFakeBoldText = true
                    setShadowLayer(4f, 0f, 0f, if (op.color == Color.Black) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                }
                val at = p(op.at)
                canvas.drawText(op.text, at.x, at.y + paint.textSize, paint)
            }
            is EditOp.Blur, is EditOp.Crop, is EditOp.StickersChange -> Unit
        }
    }
    stickers.forEach { st ->
        val bmp = stickerBitmaps[st.uriString] ?: return@forEach
        val cx = st.centerX * w
        val cy = st.centerY * h
        val sw = st.halfWidth * 2f * w
        val sh = st.halfHeight * 2f * h
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(st.rotationDegrees)
        val dst = android.graphics.RectF(-sw / 2f, -sh / 2f, sw / 2f, sh / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, null, dst, paint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, unit * 0.004f)
            color = android.graphics.Color.WHITE
        }
        canvas.drawRoundRect(dst, 12f, 12f, borderPaint)
        canvas.restore()
    }
    if (crop == ImageEditMath.FULL) return out
    val px = ImageEditMath.toPixels(crop, out.width, out.height)
    return Bitmap.createBitmap(out, px.left, px.top, px.width, px.height)
}
