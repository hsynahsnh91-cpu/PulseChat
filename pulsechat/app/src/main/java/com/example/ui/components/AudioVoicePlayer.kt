package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AudioVoicePlayer(
    durationSeconds: Int,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying && currentSeconds < durationSeconds) {
                delay(1000)
                currentSeconds++
            }
            if (currentSeconds >= durationSeconds) {
                isPlaying = false
                currentSeconds = 0
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val waveAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bars"
    )

    val contentColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val barBaseColor = if (isOutgoing) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                isPlaying = !isPlaying
                if (currentSeconds >= durationSeconds) currentSeconds = 0
            },
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isOutgoing) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column {
            // Waveform bars
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.height(24.dp)
            ) {
                val heights = listOf(10, 18, 14, 22, 16, 24, 12, 20, 15, 23, 11, 19, 13)
                heights.forEachIndexed { index, h ->
                    val factor = if (isPlaying && (index % 2 == 0)) waveAnim else 1f
                    val barHeight = (h * factor).coerceIn(4f, 24f)
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(barHeight.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(barBaseColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Duration display (MM:SS)
            val displaySecs = if (isPlaying) currentSeconds else durationSeconds
            val minutes = displaySecs / 60
            val remainingSecs = displaySecs % 60
            Text(
                text = String.format("%02d:%02d", minutes, remainingSecs),
                color = contentColor.copy(alpha = 0.75f),
                fontSize = 11.sp
            )
        }
    }
}
