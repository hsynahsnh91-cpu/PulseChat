package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.MessageType
import com.example.ui.theme.AiAccentColor
import com.example.ui.theme.AiBubbleDark
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.SecondaryTeal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: Message,
    isOutgoing: Boolean,
    senderName: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(message.createdAt))

    if (message.type == MessageType.CALL_EVENT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (message.content.contains("مرئية")) Icons.Default.Videocam else Icons.Default.Phone,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${message.content} • $formattedTime",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    val isAi = message.isAiGenerated || message.type == MessageType.AI_RESPONSE

    // WhatsApp-style Bubble shapes
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    }

    // Outgoing WhatsApp tint: deep emerald/primary teal. Incoming: clean dark surface
    val bubbleColor = when {
        isAi -> AiBubbleDark
        isOutgoing -> Color(0xFF005C4B) // WhatsApp outgoing dark green
        else -> Color(0xFF202C33)     // WhatsApp incoming dark surface
    }

    val contentColor = Color(0xFFE9EDEF)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // WhatsApp-style Green Notification Circle badge for receiver
        if (!isOutgoing && message.status != MessageStatus.READ) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(OnlineGreen)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(min = 90.dp, max = 310.dp)
                .clip(bubbleShape)
                .then(
                    if (isAi) {
                        Modifier.border(
                            1.dp,
                            Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF06B6D4))),
                            bubbleShape
                        )
                    } else Modifier
                )
                .background(bubbleColor)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Column {
                if (isAi) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(AiAccentColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Gemini AI",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SecondaryTeal
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "نسخ",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(message.content))
                                    Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
                                }
                        )
                    }
                } else if (!isOutgoing && senderName != null) {
                    Text(
                        text = senderName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF53BDEB),
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }

                // Render content depending on MessageType
                when (message.type) {
                    MessageType.IMAGE -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                        ) {
                            if (!message.mediaUrl.isNullOrEmpty()) {
                                AsyncImage(
                                    model = message.mediaUrl,
                                    contentDescription = "صورة",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (message.content.isNotBlank() && message.content != "صورة") {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = message.content, color = contentColor, fontSize = 14.sp)
                        }
                    }
                    MessageType.VIDEO -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircleFilled,
                                contentDescription = "تشغيل فيديو",
                                tint = Color.White,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    }
                    MessageType.AUDIO -> {
                        AudioVoicePlayer(
                            durationSeconds = if (message.mediaDurationSeconds > 0) message.mediaDurationSeconds else 6,
                            isOutgoing = isOutgoing
                        )
                    }
                    MessageType.DOCUMENT -> {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = Color(0xFF53BDEB),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = message.fileName ?: "مستند",
                                    fontWeight = FontWeight.SemiBold,
                                    color = contentColor,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = message.fileSize ?: "ملف PDF",
                                    color = contentColor.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    MessageType.LOCATION -> {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "موقع مباشر",
                                    fontWeight = FontWeight.Bold,
                                    color = contentColor,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = message.content,
                                    color = contentColor.copy(alpha = 0.7f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    MessageType.POLL -> {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.15f))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "📊 ${message.pollQuestion ?: message.content}",
                                fontWeight = FontWeight.Bold,
                                color = contentColor,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val options = message.pollOptions?.split("|") ?: listOf("نعم", "لا")
                            options.forEachIndexed { i, opt ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.1f))
                                        .clickable {
                                            Toast.makeText(context, "تم التصويت: $opt", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(text = opt, color = contentColor, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = message.content,
                            color = contentColor,
                            fontSize = 15.sp,
                            lineHeight = 21.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Footer: Time and WhatsApp double checkmark status
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedTime,
                        fontSize = 10.sp,
                        color = contentColor.copy(alpha = 0.65f)
                    )

                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        when (message.status) {
                            MessageStatus.SENDING -> {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "جارٍ الإرسال",
                                    tint = contentColor.copy(alpha = 0.65f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            MessageStatus.SENT -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "تم الإرسال",
                                    tint = contentColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            MessageStatus.DELIVERED -> {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "تم الاستلام",
                                    tint = contentColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            MessageStatus.READ -> {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "تمت القراءة",
                                    tint = Color(0xFF53BDEB), // WhatsApp blue checkmark
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            MessageStatus.FAILED -> {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = "فشل",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
