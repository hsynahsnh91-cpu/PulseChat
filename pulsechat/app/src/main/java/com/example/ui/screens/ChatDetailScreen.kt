package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.AuthRepository
import com.example.ui.ChatViewModel
import com.example.ui.components.MessageBubble
import com.example.ui.components.UserAvatar
import com.example.ui.components.VoiceRecorderSheet
import com.example.ui.theme.AiAccentColor
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.SecondaryTeal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val currentUser by viewModel.currentUser.collectAsState()
    val activeConv by viewModel.activeConversation.collectAsState()
    val messages by viewModel.activeMessages.collectAsState()
    val typingMap by viewModel.typingStatus.collectAsState()
    val smartReplies by viewModel.smartReplies.collectAsState()
    val isSummarizing by viewModel.isSummarizing.collectAsState()
    val summaryContent by viewModel.summaryContent.collectAsState()
    val isPolishing by viewModel.isPolishing.collectAsState()
    val allUsers by viewModel.allUsers.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showVoiceRecorder by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showAttachmentPicker by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val conversationId = activeConv?.conversationId.orEmpty()
    val typingUser = typingMap[conversationId]

    // Determine target recipient for 1-on-1 call
    val targetUserId = activeConv?.getParticipantsList()?.firstOrNull { it != currentUser?.userId }
    val isAiChat = targetUserId == AuthRepository.AI_ASSISTANT_ID || activeConv?.title?.contains("Gemini") == true

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(
                            displayName = activeConv?.title ?: "محادثة",
                            size = 38.dp,
                            isAiAssistant = isAiChat,
                            isOnline = isAiChat || true
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = activeConv?.title ?: "محادثة",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val statusSubtitle = when {
                                typingUser != null -> "يكتب الآن…"
                                isAiChat -> "مدعوم بنموذج Gemini 3.8 Flash"
                                else -> "متصل الآن"
                            }
                            Text(
                                text = statusSubtitle,
                                fontSize = 12.sp,
                                color = if (typingUser != null) SecondaryTeal else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (!isAiChat && targetUserId != null) {
                        IconButton(
                            onClick = { viewModel.startCall(targetUserId, isVideo = false) },
                            modifier = Modifier.testTag("audio_call_button")
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "مكالمة صوتية")
                        }
                        IconButton(
                            onClick = { viewModel.startCall(targetUserId, isVideo = true) },
                            modifier = Modifier.testTag("video_call_button")
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = "مكالمة مرئية")
                        }
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "خيارات إضافية")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("لخص المحادثة بـ AI") },
                                onClick = {
                                    showMenu = false
                                    viewModel.summarizeConversation()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("اقترح ردوداً ذكية") },
                                onClick = {
                                    showMenu = false
                                    viewModel.requestSmartReplies()
                                }
                            )
                            if (inputText.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("إعادة صياغة المسودة احترافياً") },
                                    onClick = {
                                        showMenu = false
                                        viewModel.polishDraft(inputText) { polished ->
                                            inputText = polished
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                // Smart Replies Bar
                if (smartReplies.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        smartReplies.forEach { reply ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(AiAccentColor.copy(alpha = 0.15f))
                                    .clickable {
                                        viewModel.sendMessage(reply)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = reply,
                                    fontSize = 12.sp,
                                    color = AiAccentColor,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Input Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment button
                    IconButton(
                        onClick = { showAttachmentPicker = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "مرفقات",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field"),
                        placeholder = { Text("اكتب رسالة…", fontSize = 14.sp) },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryIndigo,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Emoji icon directly next to typing activity area and right beside voice recording icon
                    IconButton(
                        onClick = { showEmojiPicker = !showEmojiPicker },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("emoji_picker_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.EmojiEmotions,
                            contentDescription = "الرموز التعبيرية",
                            tint = if (showEmojiPicker) PrimaryIndigo else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    if (inputText.isBlank()) {
                        // Voice note recorder button
                        IconButton(
                            onClick = { showVoiceRecorder = true },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("mic_record_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "تسجيل صوتي",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Send button
                        IconButton(
                            onClick = {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(PrimaryIndigo)
                                .testTag("send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "إرسال",
                                tint = Color.White
                            )
                        }
                    }
                }

                if (showEmojiPicker) {
                    com.example.ui.components.EmojiPickerView(
                        onEmojiSelected = { emoji ->
                            inputText += emoji
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(messages, key = { it.messageId }) { msg ->
                    val isOutgoing = msg.senderId == currentUser?.userId
                    val senderName = if (!isOutgoing && activeConv?.isGroup == true) {
                        allUsers.find { it.userId == msg.senderId }?.displayName
                    } else null

                    MessageBubble(
                        message = msg,
                        isOutgoing = isOutgoing,
                        senderName = senderName
                    )
                }

                if (typingUser != null) {
                    item {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = SecondaryTeal
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$typingUser يكتب الآن…",
                                fontSize = 12.sp,
                                color = SecondaryTeal
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }
            }

            // Summarizing indicator
            if (isSummarizing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(24.dp)
                    ) {
                        CircularProgressIndicator(color = AiAccentColor)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("جارٍ تلخيص المحادثة بواسطة Gemini…", fontSize = 14.sp)
                    }
                }
            }
        }
    }

    // Attachment Picker Modal Sheet
    if (showAttachmentPicker) {
        com.example.ui.components.AttachmentPickerSheet(
            onDismiss = { showAttachmentPicker = false },
            onSendPhoto = { uri -> viewModel.sendPhoto(uri) },
            onSendVideo = { uri -> viewModel.sendVideo(uri) },
            onSendDocument = { name, uri -> viewModel.sendDocument(name, uri) },
            onSendLocation = { lat, lng, addr -> viewModel.sendLocation(lat, lng, addr) },
            onSendPoll = { question, opts -> viewModel.sendPoll(question, opts) }
        )
    }

    // Voice Recorder Modal Sheet
    if (showVoiceRecorder) {
        VoiceRecorderSheet(
            onDismiss = { showVoiceRecorder = false },
            onSendRecording = { durationSecs ->
                viewModel.sendVoiceNote(durationSecs)
            }
        )
    }

    // Summary Result Dialog
    if (summaryContent != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSummary() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = AiAccentColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ملخص المحادثة الذكي")
                }
            },
            text = {
                Text(
                    text = summaryContent.orEmpty(),
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSummary() }) {
                    Text("إغلاق")
                }
            }
        )
    }
}
