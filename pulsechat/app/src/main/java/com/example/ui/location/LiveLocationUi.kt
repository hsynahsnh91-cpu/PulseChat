package com.example.ui.location

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Message
import com.example.data.repository.LiveLocationRepository
import com.example.data.repository.LiveLocationView
import com.example.domain.LiveLocationPolicy
import com.example.domain.LiveLocationPolicy.Phase
import com.example.domain.MapMath
import com.example.ui.chat.openLocation
import com.example.ui.common.BackTopBar
import com.example.ui.common.Formatters
import com.example.ui.common.appContainer
import com.example.ui.theme.Radii
import com.example.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** What message bubbles need to show live locations (absent in previews and tests: the start point is shown). */
class LiveLocationSupport(
    val observe: (Message) -> Flow<LiveLocationView?>,
    val now: () -> Long,
    val onOpen: (Message) -> Unit,
    val onStop: (Message) -> Unit,
    /** The session this phone is sharing now: only that one can be stopped from here. */
    val activeLiveId: StateFlow<String?> = MutableStateFlow(null),
)

private val STALE_GREY = Color(0xFF8A8F98)
private val NO_ACTIVE_SESSION: StateFlow<String?> = MutableStateFlow(null)

/** Current time, refreshed while shown (relative times like "updated 2 minutes ago" stay right). */
@Composable
private fun tickingNow(now: () -> Long, everyMs: Long = 15_000L): Long {
    val value by produceState(now()) {
        while (true) {
            delay(everyMs)
            value = now()
        }
    }
    return value
}

private fun phaseOf(view: LiveLocationView?, until: Long, now: Long): Phase? =
    view?.let { LiveLocationPolicy.phase(now, it.fixAt, it.expiresAt, it.endedAt, it.fromCache) }
        ?: if (until in 1..now) Phase.ENDED else null

@Composable
private fun untilText(until: Long): String {
    val context = LocalContext.current
    return if (Formatters.isToday(until)) Formatters.time(context, until) else Formatters.dayAndTime(context, until)
}

/** One line describing the session: live until, last update, stale or ended. Never calls an old point live. */
@Composable
fun liveStatusLine(view: LiveLocationView?, until: Long, now: Long): String {
    val context = LocalContext.current
    val end = view?.expiresAt?.takeIf { it > 0 } ?: until
    val sep = stringResource(R.string.list_separator)
    return when (phaseOf(view, until, now)) {
        Phase.LIVE -> stringResource(R.string.live_until, untilText(end)) + sep +
            stringResource(R.string.live_updated, Formatters.statusTime(context, view!!.fixAt, now))
        Phase.STALE -> stringResource(R.string.live_stale, Formatters.statusTime(context, view!!.fixAt, now)) +
            if (view.fromCache) sep + stringResource(R.string.live_offline) else ""
        Phase.ENDED -> stringResource(R.string.live_ended) +
            (view?.takeIf { it.fixAt > 0 }?.let { sep + stringResource(R.string.live_last_update, Formatters.dayAndTime(context, it.fixAt)) } ?: "")
        null -> stringResource(R.string.live_until, untilText(end))
    }
}

/** Chat bubble content of a live-location message. */
@Composable
fun LiveLocationBubble(
    message: Message,
    mine: Boolean,
    support: LiveLocationSupport?,
    metaColor: Color,
    map: @Composable (lat: Double, lng: Double, live: Boolean, modifier: Modifier) -> Unit = { lat, lng, live, modifier ->
        TileMap(lat, lng, zoom = 15, modifier = modifier, markerColor = if (live) Color(0xFFD93025) else STALE_GREY)
    },
) {
    val flow = remember(message.messageId, message.liveId, support) { support?.observe?.invoke(message) ?: flowOf(null) }
    val view by flow.collectAsState(initial = null)
    val now = tickingNow(support?.now ?: { System.currentTimeMillis() })
    val phase = phaseOf(view, message.liveUntil, now)
    val activeLiveId by (support?.activeLiveId ?: NO_ACTIVE_SESSION).collectAsState()
    val lat = view?.lat ?: message.locationLat
    val lng = view?.lng ?: message.locationLng
    Column(Modifier.widthIn(min = 230.dp, max = 300.dp).testTag("live_location_bubble")) {
        Box(
            Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(Radii.sm)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = support != null) { support?.onOpen?.invoke(message) },
        ) {
            if (lat != null && lng != null) map(lat, lng, phase == Phase.LIVE, Modifier.fillMaxSize())
            if (phase == Phase.LIVE) {
                Text(
                    stringResource(R.string.live_badge), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.padding(Spacing.sm).clip(CircleShape).background(Color(0xFFD93025)).padding(horizontal = Spacing.sm, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NearMe, contentDescription = null, tint = if (phase == Phase.LIVE) MaterialTheme.colorScheme.primary else metaColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.live_location_title_short), fontWeight = FontWeight.SemiBold)
                Text(liveStatusLine(view, message.liveUntil, now), style = MaterialTheme.typography.labelSmall, color = metaColor, modifier = Modifier.testTag("live_status"))
            }
        }
        if (mine && support != null && phase != Phase.ENDED && activeLiveId == message.liveId) {
            TextButton(onClick = { support.onStop(message) }, modifier = Modifier.testTag("live_stop")) {
                Text(stringResource(R.string.live_stop), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Full-screen map of one live-location session. */
@Composable
fun LiveLocationScreen(conversationId: String, liveId: String, onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val me = container.authRepository.currentUid.orEmpty()
    val view by remember(conversationId, liveId) { container.liveLocation.observe(conversationId, liveId) }.collectAsState(initial = null)
    val message by remember(liveId) { container.db.messageDao().observeByLiveId(liveId) }.collectAsState(initial = null)
    val ownerId = view?.ownerId ?: message?.senderId
    val owner by remember(ownerId) { if (ownerId == null) flowOf(null) else container.userRepository.observeUser(ownerId) }.collectAsState(initial = null)
    val now = tickingNow({ container.serverClock.now() }, everyMs = 10_000L)
    val until = message?.liveUntil ?: 0L
    val phase = phaseOf(view, until, now)
    var zoom by rememberSaveable { mutableStateOf(16) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val lat = view?.lat ?: message?.locationLat
    val lng = view?.lng ?: message?.locationLng
    val mine = ownerId == me
    val active by container.liveLocation.active.collectAsState()
    val name = if (mine) stringResource(R.string.you) else owner?.displayName?.ifBlank { null } ?: stringResource(R.string.unknown_user)
    Scaffold(topBar = { BackTopBar(stringResource(R.string.live_location_title_short), onBack, subtitle = name) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).testTag("live_location_screen")) {
            if (lat != null && lng != null) {
                TileMap(
                    lat, lng, zoom, Modifier.fillMaxSize(), pan = pan, markerColor = if (phase == Phase.LIVE) Color(0xFFD93025) else STALE_GREY,
                    interactive = true, onPan = { pan += it }, onZoom = { z -> if (z != zoom) { pan *= 2f; zoom = z } },
                )
            }
            Column(Modifier.align(Alignment.TopEnd).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilledTonalIconButton(onClick = { if (zoom < MapMath.MAX_ZOOM) { pan *= 2f; zoom++ } }) { Icon(Icons.Rounded.Add, stringResource(R.string.map_zoom_in)) }
                FilledTonalIconButton(onClick = { if (zoom > MapMath.MIN_ZOOM) { pan /= 2f; zoom-- } }) { Icon(Icons.Rounded.Remove, stringResource(R.string.map_zoom_out)) }
                FilledTonalIconButton(onClick = { pan = Offset.Zero }) { Icon(Icons.Rounded.MyLocation, stringResource(R.string.map_recenter)) }
            }
            Surface(
                shape = RoundedCornerShape(Radii.lg), tonalElevation = 2.dp, shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.md).padding(bottom = 18.dp).navigationBarsPadding(),
            ) {
                Column(Modifier.padding(Spacing.lg).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (view == null && message == null) {
                        Text(stringResource(R.string.live_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(liveStatusLine(view, until, now), color = if (phase == Phase.LIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("live_screen_status"))
                        view?.accuracyMeters?.takeIf { it > 0 && phase == Phase.LIVE }?.let {
                            Text(stringResource(R.string.live_accuracy, it.toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (lat != null && lng != null) OutlinedButton(onClick = { openLocation(context, lat, lng) }) { Text(stringResource(R.string.location_open_maps)) }
                        if (mine && phase != Phase.ENDED && active?.liveId == liveId) {
                            Button(
                                onClick = { container.liveLocation.stop(LiveLocationRepository.StopReason.USER) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                                modifier = Modifier.testTag("live_screen_stop"),
                            ) { Text(stringResource(R.string.live_stop)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Attach > Location: send one fixed point, or share a live location for a chosen time after an
 * explicit explanation (what is shared, with whom, for how long, battery use). Nothing starts
 * until the user presses Share; the system then asks for the location permission if needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationShareSheet(
    chatName: String,
    activeHere: LiveLocationRepository.Session?,
    activeElsewhere: Boolean,
    onSendCurrent: () -> Unit,
    onShareLive: (Long) -> Unit,
    onStopLive: () -> Unit,
    onDismiss: () -> Unit,
) {
    var duration by rememberSaveable { mutableStateOf(LiveLocationPolicy.DURATIONS.first()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl).testTag("location_share_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.location_share_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.md)).clickable(onClick = onSendCurrent).heightIn(min = 56.dp)
                    .padding(vertical = Spacing.sm).testTag("location_send_current"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(Spacing.lg))
                Column {
                    Text(stringResource(R.string.location_send_current), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.location_send_current_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NearMe, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(Spacing.lg))
                Text(stringResource(R.string.live_share_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (activeHere != null) {
                Text(stringResource(R.string.live_sharing_here, untilText(activeHere.expiresAt)), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = onStopLive, modifier = Modifier.fillMaxWidth().testTag("live_sheet_stop"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.live_stop)) }
            } else {
                Text(stringResource(R.string.live_share_consent, chatName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    LiveLocationPolicy.DURATIONS.forEachIndexed { i, d ->
                        FilterChip(
                            selected = duration == d, onClick = { duration = d },
                            label = { Text(stringResource(durationLabel(i))) }, modifier = Modifier.testTag("live_duration_$i"),
                        )
                    }
                }
                if (activeElsewhere) Text(stringResource(R.string.live_replaces_other), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onShareLive(duration) }, modifier = Modifier.fillMaxWidth().testTag("live_share_start")) {
                    Text(stringResource(R.string.live_share_action))
                }
            }
        }
    }
}

private fun durationLabel(index: Int): Int = when (index) {
    0 -> R.string.duration_15m
    1 -> R.string.duration_1h
    else -> R.string.duration_8h
}
