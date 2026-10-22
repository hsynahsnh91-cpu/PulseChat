package com.example.ui.calls

import com.example.calls.AudioRoute
import com.example.calls.CallStatsSnapshot
import com.example.calls.QualityState
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.ui.common.LightSystemBarIcons
import com.example.ui.common.rememberReducedMotion
import com.example.ui.theme.CallPalette
import com.example.ui.theme.Motion
import com.example.ui.theme.Radii
import com.example.ui.theme.Spacing
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.calls.CallManager
import com.example.calls.CallUiState
import com.example.domain.CallDirection
import com.example.domain.DurationFormat
import com.example.domain.model.CallState
import com.example.ui.common.appContainer
import com.example.ui.components.UserAvatar
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(onClose: () -> Unit) {
    val container = appContainer()
    val manager = container.callManager
    val context = LocalContext.current
    val ui by manager.ui.collectAsState()
    val routes by manager.audioRoutes.collectAsState()
    val stats by manager.stats.collectAsState()
    val quality by manager.quality.collectAsState()
    val settings by container.prefs.settings.collectAsState()
    var routeMenu by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(ui.state, ui.callId) { if (ui.state == CallState.IDLE || ui.callId == null) onClose() }
    BackHandler(enabled = ui.isLive) { /* keep the call screen while a call is live; use End to hang up */ }

    val acceptPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) manager.accept() else Toast.makeText(context, R.string.call_permission_needed, Toast.LENGTH_LONG).show()
    }
    fun acceptWithPermissions() {
        val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (ui.isVideo) Manifest.permission.CAMERA else null)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) manager.accept() else acceptPermissions.launch(needed.toTypedArray())
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.state) { while (ui.state == CallState.CONNECTED) { now = System.currentTimeMillis(); delay(1000) } }

    LightSystemBarIcons()
    Box(Modifier.fillMaxSize().background(CallPalette.Background)) {
        val showVideo = ui.isVideo && (ui.state.isActive || ui.state == CallState.RINGING && ui.direction == CallDirection.OUTGOING || ui.state == CallState.INITIATING)
        // If the other side's video stops arriving (weak network), their photo replaces a frozen frame.
        val remoteVideoShown = showVideo && ui.remoteVideo && ui.state.isActive && !ui.remoteVideoPaused
        if (remoteVideoShown) {
            VideoRenderer(manager, remote = true, modifier = Modifier.fillMaxSize())
        }
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val compact = maxHeight < 600.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = Spacing.xxl, vertical = if (compact) Spacing.lg else Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (compact) Spacing.sm else Spacing.xxxl))
                if (!remoteVideoShown) {
                    val ringing = ui.state == CallState.INITIATING || ui.state == CallState.RINGING
                    CallerAvatar(ui.peerName, ui.peerAvatar, size = if (compact) 96.dp else 120.dp, ringing = ringing)
                    Spacer(Modifier.height(if (compact) Spacing.lg else Spacing.xl))
                }
                Text(
                    ui.peerName.ifBlank { stringResource(R.string.unknown_user) }, color = CallPalette.Text,
                    style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.sm))
                // The state line cross-fades when the call state changes; the running clock updates in place.
                AnimatedContent(
                    targetState = ui.state,
                    transitionSpec = { fadeIn(Motion.enter(Motion.STANDARD)) togetherWith fadeOut(Motion.exit(Motion.QUICK)) },
                    label = "call-state",
                ) { state ->
                    Text(statusText(ui.copy(state = state), now), color = CallPalette.TextSecondary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                if (ui.state == CallState.FAILED) {
                    Text(stringResource(R.string.call_failed_hint), color = CallPalette.TextSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.sm))
                }
                Spacer(Modifier.weight(1f))
                when {
                    ui.state == CallState.RINGING && ui.direction == CallDirection.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(Icons.Rounded.CallEnd, stringResource(R.string.call_decline), CallPalette.Decline, CallPalette.Text, large = true) { manager.decline() }
                        RoundButton(if (ui.isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call, stringResource(R.string.call_accept), CallPalette.Accept, CallPalette.Text, large = true) { acceptWithPermissions() }
                    }
                    ui.isLive -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                            ToggleButton(if (ui.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic, stringResource(if (ui.micMuted) R.string.call_unmute else R.string.call_mute),
                                active = ui.micMuted) { manager.toggleMute() }
                            // Speaker toggle; with a headset or Bluetooth device connected it becomes a route picker.
                            val hasHeadset = AudioRoute.WIRED_HEADSET in routes.available || AudioRoute.BLUETOOTH in routes.available
                            Box {
                                ToggleButton(
                                    routeIcon(if (hasHeadset) routes.current else AudioRoute.SPEAKER),
                                    if (hasHeadset) routeName(routes.current ?: AudioRoute.EARPIECE)
                                    else stringResource(if (ui.speakerOn) R.string.call_speaker_off else R.string.call_speaker_on),
                                    active = ui.speakerOn && !hasHeadset,
                                ) { if (hasHeadset) routeMenu = true else manager.toggleSpeaker() }
                                DropdownMenu(expanded = routeMenu, onDismissRequest = { routeMenu = false }) {
                                    routes.available.sortedBy { it.ordinal }.forEach { r ->
                                        DropdownMenuItem(
                                            text = { Text(routeName(r)) },
                                            leadingIcon = { Icon(routeIcon(r), contentDescription = null) },
                                            trailingIcon = { if (r == routes.current) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.call_route_selected)) },
                                            onClick = { routeMenu = false; manager.selectAudioRoute(r) },
                                        )
                                    }
                                }
                            }
                            if (ui.isVideo) {
                                ToggleButton(if (ui.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, stringResource(if (ui.cameraOn) R.string.call_camera_off else R.string.call_camera_on),
                                    active = !ui.cameraOn) { manager.toggleCamera() }
                                ToggleButton(Icons.Rounded.FlipCameraAndroid, stringResource(R.string.call_switch_camera), active = false) { manager.switchCamera() }
                            }
                        }
                        Spacer(Modifier.height(if (compact) Spacing.lg else Spacing.xxl))
                        RoundButton(Icons.Rounded.CallEnd, stringResource(R.string.call_end), CallPalette.Decline, CallPalette.Text, large = true, modifier = Modifier.testTag("call_end")) { manager.hangUp() }
                    }
                }
                Spacer(Modifier.height(if (compact) Spacing.sm else Spacing.lg))
            }
        }
        if (settings.callShowDetails && ui.isLive) {
            CallDetailsPanel(stats, quality, ui.isVideo, routes.current, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Spacing.md))
        }
        if (ui.isVideo && ui.cameraOn && (ui.state.isActive || ui.state == CallState.RINGING || ui.state == CallState.INITIATING)) {
            VideoRenderer(manager, remote = false, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Spacing.lg).size(width = 110.dp, height = 160.dp).clip(RoundedCornerShape(Radii.md)))
        }
    }
}

/**
 * Caller photo. While the call is ringing a soft ring expands behind it — it is tied to the real call
 * state, stops as soon as the call is answered or ends, and is skipped when animations are turned off.
 */
@Composable
private fun CallerAvatar(name: String, avatarUrl: String?, size: Dp, ringing: Boolean) {
    val animate = ringing && !rememberReducedMotion()
    Box(Modifier.size(size * 1.3f), contentAlignment = Alignment.Center) {
        if (animate) {
            val transition = rememberInfiniteTransition(label = "ring")
            val progress by transition.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1600, easing = Motion.StandardEasing), RepeatMode.Restart), label = "ring-progress",
            )
            Box(
                Modifier.size(size).graphicsLayer {
                    val scale = 1f + 0.28f * progress
                    scaleX = scale; scaleY = scale; alpha = 0.35f * (1f - progress)
                }.clip(CircleShape).background(CallPalette.TextSecondary),
            )
        }
        UserAvatar(name, avatarUrl = avatarUrl, size = size, ringColor = CallPalette.Background)
    }
}

@Composable
private fun VideoRenderer(manager: CallManager, remote: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(manager.eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setMirror(!remote)
            if (!remote) setZOrderMediaOverlay(true)
        }
    }
    DisposableEffect(renderer) {
        if (remote) manager.attachRemoteRenderer(renderer) else manager.attachLocalRenderer(renderer)
        onDispose {
            if (remote) manager.attachRemoteRenderer(null) else manager.attachLocalRenderer(null)
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

@Composable
private fun statusText(ui: CallUiState, now: Long): String = when (ui.state) {
    CallState.INITIATING -> stringResource(R.string.call_state_calling)
    CallState.RINGING -> if (ui.direction == CallDirection.OUTGOING) stringResource(R.string.call_state_ringing)
        else stringResource(if (ui.isVideo) R.string.call_incoming_video else R.string.call_incoming_voice)
    CallState.ACCEPTED, CallState.CONNECTING -> stringResource(R.string.call_state_connecting)
    CallState.CONNECTED -> DurationFormat.clock(now - ui.connectedAt)
    CallState.RECONNECTING -> stringResource(R.string.call_state_reconnecting)
    CallState.REJECTED -> stringResource(R.string.call_state_rejected)
    CallState.BUSY -> stringResource(R.string.call_state_busy)
    CallState.MISSED -> stringResource(if (ui.direction == CallDirection.OUTGOING) R.string.call_state_no_answer else R.string.call_missed)
    CallState.CANCELLED -> stringResource(R.string.call_state_cancelled)
    CallState.ENDED -> stringResource(R.string.call_state_ended)
    CallState.FAILED -> stringResource(R.string.call_state_failed)
    else -> ""
}

@Composable
private fun RoundButton(
    icon: ImageVector, label: String, container: Color, content: Color, large: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(modifier.widthIn(max = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(if (large) 68.dp else 60.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(if (large) 30.dp else 26.dp))
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(label, color = CallPalette.TextSecondary, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Mute / speaker / camera: a light circle while the option is on, so its state reads at a glance. */
@Composable
private fun ToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(if (active) CallPalette.Text else CallPalette.Raised, Motion.standard(Motion.QUICK), label = "toggle-bg")
    val content by animateColorAsState(if (active) CallPalette.Background else CallPalette.Text, Motion.standard(Motion.QUICK), label = "toggle-fg")
    RoundButton(icon, label, container, content, onClick = onClick)
}

@Composable
private fun routeName(route: AudioRoute): String = stringResource(when (route) {
    AudioRoute.EARPIECE -> R.string.call_route_earpiece
    AudioRoute.SPEAKER -> R.string.call_route_speaker
    AudioRoute.WIRED_HEADSET -> R.string.call_route_headset
    AudioRoute.BLUETOOTH -> R.string.call_route_bluetooth
})

private fun routeIcon(route: AudioRoute?): ImageVector = when (route) {
    AudioRoute.SPEAKER, null -> Icons.AutoMirrored.Rounded.VolumeUp
    AudioRoute.EARPIECE -> Icons.Rounded.PhoneInTalk
    AudioRoute.WIRED_HEADSET -> Icons.Rounded.Headset
    AudioRoute.BLUETOOTH -> Icons.Rounded.Bluetooth
}

/** Live call-quality numbers, shown only when "Show call details" is on (Settings › Diagnostics). */
@Composable
private fun CallDetailsPanel(stats: CallStatsSnapshot?, quality: QualityState?, video: Boolean, route: AudioRoute?, modifier: Modifier) {
    fun fmt(v: Double?, digits: Int = 1) = v?.let { String.format(java.util.Locale.US, "%.${digits}f", it) } ?: "–"
    Column(modifier.clip(RoundedCornerShape(Radii.sm)).background(CallPalette.Scrim).padding(10.dp)) {
        val style = MaterialTheme.typography.labelSmall
        Text(stringResource(R.string.call_details_title), color = Color.White, style = style, fontWeight = FontWeight.SemiBold)
        if (stats == null) {
            Text(stringResource(R.string.call_details_waiting), color = Color.White, style = style)
            return@Column
        }
        Text(stringResource(R.string.call_details_network, stats.rttMs?.toString() ?: "–", stats.jitterMs?.toString() ?: "–", fmt(stats.lossPercent)), color = Color.White, style = style)
        Text(stringResource(R.string.call_details_levels, fmt(stats.inboundAudioLevel, 3), fmt(stats.outboundAudioLevel, 3)), color = Color.White, style = style)
        if (stats.echoReturnLossEnhancementDb != null) Text(stringResource(R.string.call_details_echo, fmt(stats.echoReturnLossEnhancementDb)), color = Color.White, style = style)
        Text(stringResource(R.string.call_details_streams, stats.inboundAudioStreams, stats.outboundAudioStreams),
            color = if (stats.duplicateAudio) CallPalette.Warning else Color.White, style = style)
        if (quality != null) {
            val bandwidth = stats.availableOutKbps?.toString() ?: "–"
            Text(
                if (video) stringResource(R.string.call_details_quality_video, quality.video.label, quality.audio.maxKbps, bandwidth)
                else stringResource(R.string.call_details_quality_voice, quality.audio.maxKbps, bandwidth),
                color = Color.White, style = style,
            )
        }
        if (route != null) Text(routeName(route), color = Color.White, style = style)
    }
}
