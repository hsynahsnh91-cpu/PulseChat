package com.example.ui.location

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.core.graphics.drawable.toBitmap
import com.example.BuildConfig
import com.example.R
import com.example.domain.MapMath

/**
 * OpenStreetMap tiles, as the tile usage policy asks: the standard HTTPS URL, a User-Agent naming
 * this app, the server's cache headers honoured (Coil's disk cache), only the tiles on screen, and
 * the attribution always visible. Change [URL_TEMPLATE] to use another tile provider.
 */
object MapTiles {
    const val URL_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    val USER_AGENT = "PulseChat/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"

    fun url(tile: MapMath.Tile): String =
        URL_TEMPLATE.replace("{z}", tile.zoom.toString()).replace("{x}", tile.wrappedX.toString()).replace("{y}", tile.y.toString())
}

private val LAND = Color(0xFFE8E4DC)

/**
 * A simple map: tiles around [lat]/[lng] at [zoom], moved by [pan] (map pixels = dp), with a marker
 * at the point. [interactive] adds drag to pan and double-tap to zoom in ([onPan], [onZoom]).
 * The map itself is always laid out left to right, also in Arabic: east stays on the right.
 */
@Composable
fun TileMap(
    lat: Double,
    lng: Double,
    zoom: Int,
    modifier: Modifier = Modifier,
    pan: Offset = Offset.Zero,
    markerColor: Color = Color(0xFFD93025),
    interactive: Boolean = false,
    onPan: (Offset) -> Unit = {},
    onZoom: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier.clipToBounds().background(LAND)
                .let { m ->
                    if (!interactive) m
                    else m.pointerInput(zoom) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onPan(Offset(drag.x / density.density, drag.y / density.density))
                        }
                    }.pointerInput(zoom) {
                        detectTapGestures(onDoubleTap = { onZoom((zoom + 1).coerceAtMost(MapMath.MAX_ZOOM)) })
                    }
                },
        ) {
            val width = maxWidth.value.toDouble()
            val height = maxHeight.value.toDouble()
            val pointX = MapMath.x(lng, zoom)
            val pointY = MapMath.y(lat, zoom)
            val centerX = pointX - pan.x
            val centerY = pointY - pan.y
            val tiles = remember(centerX, centerY, width, height, zoom) { MapMath.visibleTiles(centerX, centerY, width, height, zoom) }
            // Tiles are fetched one by one (Coil: memory + disk cache honouring the server's cache
            // headers) and drawn on one canvas at exact positions, partly outside the map's bounds.
            val loaded = remember { mutableStateMapOf<String, ImageBitmap>() }
            val shown = tiles.map { MapTiles.url(it) }
            shown.forEach { url ->
                key(url) {
                    LaunchedEffect(url) {
                        if (loaded[url] != null) return@LaunchedEffect
                        val request = ImageRequest.Builder(context).data(url).setHeader("User-Agent", MapTiles.USER_AGENT).allowHardware(false).build()
                        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
                        if (bitmap != null) loaded[url] = bitmap.asImageBitmap()
                    }
                }
            }
            LaunchedEffect(shown) { loaded.keys.filter { it !in shown }.forEach { loaded.remove(it) } }
            Canvas(Modifier.fillMaxSize()) {
                val side = MapMath.TILE_SIZE.dp.roundToPx()
                tiles.forEach { tile ->
                    val image = loaded[MapTiles.url(tile)] ?: return@forEach
                    drawImage(
                        image, dstOffset = IntOffset(tile.left.dp.roundToPx(), tile.top.dp.roundToPx()), dstSize = IntSize(side, side),
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            // Marker: the pin's tip sits on the point.
            val markerX = pointX - (centerX - width / 2)
            val markerY = pointY - (centerY - height / 2)
            Icon(
                Icons.Rounded.LocationOn, contentDescription = null, tint = markerColor,
                modifier = Modifier.offset { IntOffset((markerX - 18).dp.roundToPx(), (markerY - 34).dp.roundToPx()) }.size(36.dp),
            )
            Text(
                stringResource(R.string.map_attribution), fontSize = 10.sp, color = Color(0xFF333333),
                // Always visible; a link on the full-screen map only (in a chat bubble a tap opens the map).
                modifier = Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(topStart = 6.dp)).background(Color.White.copy(alpha = 0.85f))
                    .let { m ->
                        if (!interactive) m
                        else m.clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MapTiles.COPYRIGHT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
