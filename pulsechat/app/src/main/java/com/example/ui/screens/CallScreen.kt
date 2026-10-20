package com.example.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallState
import com.example.data.repository.ActiveCall
import com.example.ui.components.UserAvatar
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.SecondaryTeal

@Composable
fun CallOverlay(
    activeCall: ActiveCall,
    durationSeconds: Int,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideoEnabled: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeCall.isIncoming && activeCall.state == CallState.RINGING) {
        // Incoming Call Banner / Dialog
        IncomingCallBanner(
            activeCall = activeCall,
            onAccept = onAccept,
            onReject = onReject
        )
    } else {
        // Full-screen Active / Outgoing Call UI
        FullScreenCallView(
            activeCall = activeCall,
            durationSeconds = durationSeconds,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            isVideoEnabled = isVideoEnabled,
            onEndCall = onEndCall,
            onToggleMute = onToggleMute,
            onToggleSpeaker = onToggleSpeaker,
            onToggleVideo = onToggleVideo
        )
    }
}

@Composable
fun IncomingCallBanner(
    activeCall: ActiveCall,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UserAvatar(
                    displayName = activeCall.callerName,
                    size = 72.dp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = activeCall.callerName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = if (activeCall.isVideo) "مكالمة مرئية واردة…" else "مكالمة صوتية واردة…",
                    fontSize = 14.sp,
                    color = SecondaryTeal,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = OnlineGreen,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "مشفرة تماماً بتقنية AES-256",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Reject Button
                    IconButton(
                        onClick = onReject,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(ErrorRed)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "رفض",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Accept Button
                    IconButton(
                        onClick = onAccept,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(OnlineGreen)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "قبول",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FullScreenCallView(
    activeCall: ActiveCall,
    durationSeconds: Int,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideoEnabled: Boolean,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ring_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "call_ring_pulse"
    )

    val targetName = if (activeCall.isIncoming) activeCall.callerName else activeCall.receiverName

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0F172A),
                        Color(0xFF1E1B4B),
                        Color(0xFF0B0F19)
                    )
                )
            )
            .padding(horizontal = 24.dp, vertical = 40.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header / Status
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(30.dp))
                Text(
                    text = targetName,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))

                val statusText = when (activeCall.state) {
                    CallState.CALLING -> "جارٍ الاتصال…"
                    CallState.RINGING -> "رنين…"
                    CallState.ACCEPTED -> {
                        val mins = durationSeconds / 60
                        val secs = durationSeconds % 60
                        String.format("%02d:%02d", mins, secs)
                    }
                    CallState.REJECTED -> "تم رفض المكالمة"
                    CallState.ENDED -> "انتهت المكالمة"
                    CallState.FAILED -> "فشل الاتصال"
                    CallState.IDLE -> ""
                }

                Text(
                    text = statusText,
                    fontSize = 16.sp,
                    color = if (activeCall.state == CallState.ACCEPTED) SecondaryTeal else Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = OnlineGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "مكالمة مشفرة تماماً بين الطرفين (AES-256)",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Center Avatar with Pulse effect
            Box(
                modifier = Modifier.size(160.dp),
                contentAlignment = Alignment.Center
            ) {
                if (activeCall.state == CallState.CALLING || activeCall.state == CallState.RINGING) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(Color(0xFF8B5CF6).copy(alpha = 0.25f))
                    )
                }
                UserAvatar(
                    displayName = targetName,
                    size = 110.dp
                )
            }

            // Controls
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute Toggle
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) Color.White else Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "كتم الصوت",
                            tint = if (isMuted) Color.Black else Color.White
                        )
                    }

                    // Video Toggle
                    if (activeCall.isVideo) {
                        IconButton(
                            onClick = onToggleVideo,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(if (!isVideoEnabled) Color.White else Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                imageVector = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "الكاميرا",
                                tint = if (!isVideoEnabled) Color.Black else Color.White
                            )
                        }
                    }

                    // Speaker Toggle
                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerOn) Color.White else Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                            contentDescription = "مكبر الصوت",
                            tint = if (isSpeakerOn) Color.Black else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(36.dp))

                // End Call Button
                IconButton(
                    onClick = onEndCall,
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(ErrorRed)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "إنهاء المكالمة",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
