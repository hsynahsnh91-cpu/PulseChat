package com.example.ui.calls

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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0B1220))))) {
        val showVideo = ui.isVideo && (ui.state.isActive || ui.state == CallState.RINGING && ui.direction == CallDirection.OUTGOING || ui.state == CallState.INITIATING)
        if (showVideo && ui.remoteVideo && ui.state.isActive) {
            VideoRenderer(manager, remote = true, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            if (!(showVideo && ui.remoteVideo && ui.state.isActive)) {
                UserAvatar(ui.peerName, avatarUrl = ui.peerAvatar, size = 120.dp)
                Spacer(Modifier.height(20.dp))
            }
            Text(ui.peerName.ifBlank { stringResource(R.string.unknown_user) }, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(statusText(ui, now), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (ui.state == CallState.FAILED) {
                Text(stringResource(R.string.call_failed_hint), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            when {
                ui.state == CallState.RINGING && ui.direction == CallDirection.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(Icons.Default.CallEnd, stringResource(R.string.call_decline), Color(0xFFDC2626)) { manager.decline() }
                    RoundButton(if (ui.isVideo) Icons.Default.Videocam else Icons.Default.Call, stringResource(R.string.call_accept), Color(0xFF059669)) { acceptWithPermissions() }
                }
                ui.isLive -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(if (ui.micMuted) Icons.Default.MicOff else Icons.Default.Mic, stringResource(if (ui.micMuted) R.string.call_unmute else R.string.call_mute),
                            if (ui.micMuted) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.15f)) { manager.toggleMute() }
                        RoundButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(if (ui.speakerOn) R.string.call_speaker_off else R.string.call_speaker_on),
                            if (ui.speakerOn) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.15f)) { manager.toggleSpeaker() }
                        if (ui.isVideo) {
                            RoundButton(if (ui.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, stringResource(if (ui.cameraOn) R.string.call_camera_off else R.string.call_camera_on),
                                Color.White.copy(alpha = 0.15f)) { manager.toggleCamera() }
                            RoundButton(Icons.Default.FlipCameraAndroid, stringResource(R.string.call_switch_camera), Color.White.copy(alpha = 0.15f)) { manager.switchCamera() }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    RoundButton(Icons.Default.CallEnd, stringResource(R.string.call_end), Color(0xFFDC2626)) { manager.hangUp() }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (ui.isVideo && ui.cameraOn && (ui.state.isActive || ui.state == CallState.RINGING || ui.state == CallState.INITIATING)) {
            VideoRenderer(manager, remote = false, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(width = 110.dp, height = 160.dp).clip(RoundedCornerShape(12.dp)))
        }
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
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(64.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
