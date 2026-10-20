package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.StatusItem
import com.example.data.model.User
import com.example.ui.ChatViewModel
import com.example.ui.components.UserAvatar
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.PrimaryIndigo
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusScreen(
    viewModel: ChatViewModel,
    currentUser: User?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val statuses by viewModel.activeStatuses.collectAsState()
    val myStatuses = statuses.filter { it.userId == currentUser?.userId }
    val contactStatuses = statuses.filter { it.userId != currentUser?.userId }

    var showTextStatusDialog by remember { mutableStateOf(false) }
    var activeViewingStatus by remember { mutableStateOf<StatusItem?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && currentUser != null) {
            viewModel.postStatus(
                text = "صورة جديدة",
                mediaUrl = uri.toString(),
                mediaType = "IMAGE"
            )
            Toast.makeText(context, "تم نشر الحالة", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // MY STATUS ITEM
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (myStatuses.isNotEmpty()) {
                                activeViewingStatus = myStatuses.first()
                            } else {
                                photoPickerLauncher.launch("image/*")
                            }
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        UserAvatar(
                            displayName = currentUser?.displayName ?: "أنا",
                            avatarUrl = currentUser?.avatarUrl,
                            size = 56.dp,
                            modifier = if (myStatuses.isNotEmpty()) {
                                Modifier.border(2.5.dp, OnlineGreen, CircleShape)
                            } else Modifier
                        )
                        if (myStatuses.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(OnlineGreen)
                                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "إضافة حالة",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.my_status),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (myStatuses.isNotEmpty()) "منذ ${myStatuses.size} تحديث" else stringResource(R.string.add_status),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row {
                        IconButton(onClick = { showTextStatusDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "كتابة حالة", tint = PrimaryIndigo)
                        }
                        IconButton(onClick = { photoPickerLauncher.launch("image/*") }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "صورة حالة", tint = PrimaryIndigo)
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.recent_updates),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            if (contactStatuses.isEmpty()) {
                item {
                    Text(
                        text = "لا توجد تحديثات جديدة من جهات الاتصال",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(contactStatuses, key = { it.statusId }) { status ->
                    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    val formatted = timeFormat.format(Date(status.createdAt))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                activeViewingStatus = status
                                viewModel.markStatusViewed(status.statusId)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            displayName = status.authorName,
                            avatarUrl = status.authorPhotoUrl,
                            size = 52.dp,
                            modifier = Modifier.border(
                                2.5.dp,
                                if (status.isViewed) MaterialTheme.colorScheme.outline else OnlineGreen,
                                CircleShape
                            )
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = status.authorName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = formatted,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // WhatsApp-style Floating Action Buttons for Status
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            FloatingActionButton(
                onClick = { showTextStatusDialog = true },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(46.dp)
                    .testTag("fab_text_status")
            ) {
                Icon(Icons.Default.Edit, contentDescription = "حالة نصية", tint = PrimaryIndigo)
            }

            FloatingActionButton(
                onClick = { photoPickerLauncher.launch("image/*") },
                containerColor = OnlineGreen,
                contentColor = Color.White,
                modifier = Modifier
                    .size(56.dp)
                    .testTag("fab_camera_status")
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "صورة حالة")
            }
        }
    }

    // TEXT STATUS CREATOR DIALOG (Change font, change background color)
    if (showTextStatusDialog) {
        TextStatusEditorDialog(
            onDismiss = { showTextStatusDialog = false },
            onPost = { text, font, colorHex ->
                viewModel.postStatus(
                    text = text,
                    mediaType = "TEXT",
                    fontFamily = font,
                    backgroundColorHex = colorHex
                )
                showTextStatusDialog = false
                Toast.makeText(context, "تم نشر الحالة", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // FULL SCREEN STATUS VIEWER
    activeViewingStatus?.let { status ->
        StatusViewModal(
            status = status,
            onDismiss = { activeViewingStatus = null }
        )
    }
}

@Composable
fun TextStatusEditorDialog(
    onDismiss: () -> Unit,
    onPost: (text: String, font: String, colorHex: String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val colors = listOf("#075E54", "#128C7E", "#25D366", "#1E293B", "#8B5CF6", "#EC4899", "#E11D48", "#D97706", "#2563EB")
    var selectedColorIndex by remember { mutableIntStateOf(0) }

    val fonts = listOf("DEFAULT", "SERIF", "SANS", "MONO", "CURSIVE")
    var selectedFontIndex by remember { mutableIntStateOf(0) }

    val currentFontFamily = when (fonts[selectedFontIndex]) {
        "SERIF" -> FontFamily.Serif
        "SANS" -> FontFamily.SansSerif
        "MONO" -> FontFamily.Monospace
        "CURSIVE" -> FontFamily.Cursive
        else -> FontFamily.Default
    }

    val currentBgColor = try {
        Color(android.graphics.Color.parseColor(colors[selectedColorIndex]))
    } catch (e: Exception) {
        Color(0xFF075E54)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(currentBgColor)
                .padding(20.dp)
        ) {
            // Controls header: Close, Font switcher, Color switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                }

                Row {
                    // Switch Font
                    IconButton(onClick = {
                        selectedFontIndex = (selectedFontIndex + 1) % fonts.size
                    }) {
                        Icon(Icons.Default.FontDownload, contentDescription = "تغيير الخط", tint = Color.White)
                    }

                    // Switch Color
                    IconButton(onClick = {
                        selectedColorIndex = (selectedColorIndex + 1) % colors.size
                    }) {
                        Icon(Icons.Default.ColorLens, contentDescription = "تغيير اللون", tint = Color.White)
                    }
                }
            }

            // Text input centered
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            text = "اكتب حالتك هنا…",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        color = Color.White,
                        fontFamily = currentFontFamily,
                        textAlign = TextAlign.Center
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Post button
            FloatingActionButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onPost(text, fonts[selectedFontIndex], colors[selectedColorIndex])
                    }
                },
                containerColor = OnlineGreen,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(56.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "نشر")
            }
        }
    }
}

@Composable
fun StatusViewModal(
    status: StatusItem,
    onDismiss: () -> Unit
) {
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(status.statusId) {
        progress = 0f
        val steps = 50
        for (i in 1..steps) {
            delay(100)
            progress = i / steps.toFloat()
        }
        onDismiss()
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "status_progress"
    )

    val currentFontFamily = when (status.fontFamily) {
        "SERIF" -> FontFamily.Serif
        "SANS" -> FontFamily.SansSerif
        "MONO" -> FontFamily.Monospace
        "CURSIVE" -> FontFamily.Cursive
        else -> FontFamily.Default
    }

    val bgColor = try {
        Color(android.graphics.Color.parseColor(status.backgroundColorHex))
    } catch (e: Exception) {
        Color(0xFF075E54)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (status.mediaType == "IMAGE") Color.Black else bgColor)
                .clickable { onDismiss() }
        ) {
            if (status.mediaType == "IMAGE" && !status.mediaUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = status.mediaUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (!status.text.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = status.text,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontFamily = currentFontFamily,
                        textAlign = TextAlign.Center,
                        lineHeight = 38.sp
                    )
                }
            }

            // Top Status Bar (Progress bar + Author details)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .align(Alignment.TopCenter)
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserAvatar(
                        displayName = status.authorName,
                        avatarUrl = status.authorPhotoUrl,
                        size = 38.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = status.authorName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                }
            }
        }
    }
}
