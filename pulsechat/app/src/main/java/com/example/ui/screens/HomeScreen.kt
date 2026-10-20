package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallRecord
import com.example.data.model.CallState
import com.example.data.model.Conversation
import com.example.data.model.User
import com.example.ui.ChatViewModel
import com.example.ui.components.UserAvatar
import com.example.ui.theme.AiAccentColor
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.SecondaryTeal
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSwitchUserSheet by remember { mutableStateOf(false) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }

    val currentUser by viewModel.currentUser.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val calls by viewModel.calls.collectAsState()
    val allUsers by viewModel.allUsers.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearchingUser by viewModel.isSearchingUser.collectAsState()
    val userNotFound by viewModel.userNotFound.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("بحث في الرسائل والجهات…", fontSize = 13.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("home_search_input"),
                            shape = RoundedCornerShape(20.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    viewModel.setSearchQuery("")
                                    isSearchActive = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "إغلاق البحث")
                                }
                            }
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "PulseChat",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "بحث")
                        }

                        // Current active user badge chip (click to switch accounts)
                        currentUser?.let { user ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { showSwitchUserSheet = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("switch_user_top_chip"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    displayName = user.displayName,
                                    size = 28.dp,
                                    colorHex = user.avatarColorHex
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = user.displayName.split(" ").firstOrNull() ?: "",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.SwitchAccount,
                                    contentDescription = "تبديل",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    label = { Text("المحادثات", fontSize = 11.sp) },
                    modifier = Modifier.testTag("tab_chats")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.DonutLarge, contentDescription = null) },
                    label = { Text("الحالة", fontSize = 11.sp) },
                    modifier = Modifier.testTag("tab_status")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.PhoneInTalk, contentDescription = null) },
                    label = { Text("المكالمات", fontSize = 11.sp) },
                    modifier = Modifier.testTag("tab_calls")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text("جهات الاتصال", fontSize = 11.sp) },
                    modifier = Modifier.testTag("tab_contacts")
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AiAccentColor) },
                    label = { Text("Gemini AI", fontSize = 11.sp, color = AiAccentColor) },
                    modifier = Modifier.testTag("tab_ai")
                )
                NavigationBarItem(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("الإعدادات", fontSize = 11.sp) },
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showNewChatDialog = true },
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("fab_new_chat")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "محادثة جديدة")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatsTab(
                    conversations = conversations,
                    searchQuery = searchQuery,
                    allUsers = allUsers,
                    currentUserId = currentUser?.userId,
                    isSearchingUser = isSearchingUser,
                    userNotFound = userNotFound,
                    onOpenConversation = onOpenConversation,
                    onStartDirectChat = { targetUserId -> viewModel.startDirectChat(targetUserId) }
                )
                1 -> StatusScreen(
                    viewModel = viewModel,
                    currentUser = currentUser
                )
                2 -> CallsTab(
                    calls = calls,
                    onStartCall = { targetUserId, isVideo -> viewModel.startCall(targetUserId, isVideo) }
                )
                3 -> ContactsTab(
                    users = allUsers.filter { it.userId != currentUser?.userId },
                    searchQuery = searchQuery,
                    onStartChat = { userId -> viewModel.startDirectChat(userId) },
                    onStartCall = { userId, isVideo -> viewModel.startCall(userId, isVideo) }
                )
                4 -> AiStudioTab(
                    viewModel = viewModel
                )
                5 -> SettingsTab(
                    currentUser = currentUser,
                    viewModel = viewModel,
                    onSwitchUser = { showSwitchUserSheet = true }
                )
            }
        }
    }

    if (showSwitchUserSheet) {
        SwitchUserSheet(
            users = allUsers,
            currentUser = currentUser,
            onDismiss = { showSwitchUserSheet = false },
            onSelectUser = { viewModel.switchUser(it) },
            onCreateAccount = { username, name, status ->
                viewModel.createNewAccount(username, name, status) { success, error ->
                    if (!success) Toast.makeText(context, error ?: "خطأ", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showNewChatDialog) {
        NewChatDialog(
            users = allUsers,
            currentUserId = currentUser?.userId,
            onDismiss = { showNewChatDialog = false },
            onStartDirectChat = { targetUserId ->
                viewModel.startDirectChat(targetUserId)
            },
            onCreateGroup = { title, memberIds ->
                viewModel.createGroup(title, memberIds)
            }
        )
    }
}

@Composable
fun ChatsTab(
    conversations: List<Conversation>,
    searchQuery: String,
    allUsers: List<User> = emptyList(),
    currentUserId: String? = null,
    isSearchingUser: Boolean = false,
    userNotFound: Boolean = false,
    onOpenConversation: (String) -> Unit,
    onStartDirectChat: (String) -> Unit
) {
    val cleanQuery = searchQuery.trim().removePrefix("#").removePrefix("@").removePrefix("id:").removePrefix("ID:").trim()

    val filteredConversations = conversations.filter {
        if (cleanQuery.isBlank()) true
        else it.title.contains(cleanQuery, ignoreCase = true) ||
                it.lastMessageText?.contains(cleanQuery, ignoreCase = true) == true
    }

    val matchingUsers = if (cleanQuery.isBlank()) emptyList() else {
        allUsers.filter { user ->
            user.userId != currentUserId && (
                user.uniqueId10.contains(cleanQuery, ignoreCase = true) ||
                user.username.contains(cleanQuery, ignoreCase = true) ||
                user.displayName.contains(cleanQuery, ignoreCase = true)
            )
        }
    }

    val hasAnyResults = filteredConversations.isNotEmpty() || matchingUsers.isNotEmpty()

    if (!hasAnyResults) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                if (isSearchingUser) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = PrimaryIndigo
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "جارٍ البحث عن المستخدم بالمعرف…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                } else if (cleanQuery.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "لم يتم العثور على أي مستخدم بالمعرف أو الاسم «$cleanQuery»",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "لا توجد محادثات حتى الآن",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (filteredConversations.isNotEmpty()) {
                if (cleanQuery.isNotBlank()) {
                    item {
                        Text(
                            text = "المحادثات (${filteredConversations.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                items(filteredConversations, key = { it.conversationId }) { conv ->
                    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    val formattedTime = timeFormat.format(Date(conv.lastMessageTimestamp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenConversation(conv.conversationId) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            displayName = conv.title,
                            size = 52.dp,
                            isAiAssistant = conv.title.contains("Gemini"),
                            isOnline = conv.title.contains("Gemini") || conv.title.contains("سارة")
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = conv.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = formattedTime,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = conv.lastMessageText ?: "لا توجد رسائل",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )

                                if (conv.unreadCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(PrimaryIndigo)
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = conv.unreadCount.toString(),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (cleanQuery.isNotBlank() && matchingUsers.isNotEmpty()) {
                item {
                    Text(
                        text = "المستخدمون وجهات الاتصال (${matchingUsers.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryTeal,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
                    )
                }

                items(matchingUsers, key = { "user_${it.userId}" }) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStartDirectChat(user.userId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            displayName = user.displayName,
                            size = 48.dp,
                            isAiAssistant = user.isAiAssistant,
                            isOnline = user.isOnline,
                            colorHex = user.avatarColorHex
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "@${user.username} • المعرف: ${user.uniqueId10}",
                                fontSize = 12.sp,
                                color = PrimaryIndigo,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = user.statusMessage,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }

                        Button(
                            onClick = { onStartDirectChat(user.userId) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("chat_user_${user.uniqueId10}")
                        ) {
                            Text("محادثة", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CallsTab(
    calls: List<CallRecord>,
    onSimulateIncomingCall: ((isVideo: Boolean) -> Unit)? = null,
    onStartCall: (targetUserId: String, isVideo: Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Call Log History Header
        Text(
            text = "سجل المكالمات",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (calls.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("لا توجد مكالمات سابقة", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(calls, key = { it.callId }) { call ->
                    val timeFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                    val formatted = timeFormat.format(Date(call.timestamp))
                    val durationMin = call.durationSeconds / 60
                    val durationSec = call.durationSeconds % 60
                    val durationStr = if (call.durationSeconds > 0) String.format("%02d:%02d", durationMin, durationSec) else "لم يرد"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            displayName = call.receiverName,
                            size = 46.dp
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = call.receiverName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (call.state == CallState.REJECTED) Icons.Default.CallEnd else Icons.Default.CallReceived,
                                    contentDescription = null,
                                    tint = if (call.state == CallState.REJECTED) ErrorRed else OnlineGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$formatted • $durationStr",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = { onStartCall(call.receiverId, call.isVideo) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                imageVector = if (call.isVideo) Icons.Default.Videocam else Icons.Default.Call,
                                contentDescription = "معاودة الاتصال",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactsTab(
    users: List<User>,
    searchQuery: String,
    onStartChat: (userId: String) -> Unit,
    onStartCall: (userId: String, isVideo: Boolean) -> Unit
) {
    val filtered = users.filter {
        if (searchQuery.isBlank()) true
        else it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.username.contains(searchQuery, ignoreCase = true) ||
                it.uniqueId10.contains(searchQuery, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = "دليل المستخدمين المتاحين (${filtered.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        items(filtered, key = { it.userId }) { user ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStartChat(user.userId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(
                    displayName = user.displayName,
                    size = 46.dp,
                    isAiAssistant = user.isAiAssistant,
                    isOnline = user.isOnline,
                    colorHex = user.avatarColorHex
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "@${user.username} • ID: ${user.uniqueId10}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row {
                    IconButton(
                        onClick = { onStartCall(user.userId, false) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "اتصال", modifier = Modifier.size(19.dp))
                    }
                    IconButton(
                        onClick = { onStartCall(user.userId, true) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = "فيديو", modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AiStudioTab(
    viewModel: ChatViewModel
) {
    val scope = rememberCoroutineScope()
    var userPrompt by remember { mutableStateOf("") }
    var aiResponse by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val quickQuestions = listOf(
        "اقترح رسالة ترحيبية احترافية لعميل جديد",
        "لخص لي أفضل ممارسات هندسة برمجيات المحادثات الفورية",
        "صياغة اعتذار رسمي ومهذب عن التأخر في الرد",
        "شرح خوارزميات تزامن الرسائل وحالات التسليم"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(AiAccentColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Gemini AI Workspace",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                text = "مفتاح API نشط ومدعوم بنموذج Gemini 3.8 Flash",
                                fontSize = 12.sp,
                                color = SecondaryTeal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "اسأل الذكاء الاصطناعي أو اطلب توليد نصوص وصياغة ردود مباشرة:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = userPrompt,
                        onValueChange = { userPrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_prompt_field"),
                        placeholder = { Text("اكتب طلبك أو استفسارك هنا…", fontSize = 14.sp) },
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (userPrompt.isNotBlank() && !isLoading) {
                                isLoading = true
                                aiResponse = ""
                                scope.launch {
                                    val res = viewModel.geminiApi.generateContent(userPrompt)
                                    isLoading = false
                                    aiResponse = res.getOrElse { "خطأ: ${it.localizedMessage}" }
                                }
                            }
                        },
                        enabled = userPrompt.isNotBlank() && !isLoading,
                        modifier = Modifier.fillMaxWidth().testTag("ai_generate_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = AiAccentColor)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جارٍ المعالجة…")
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("توليد الإجابة")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "اقتراحات سريعة جاهزة:",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        items(quickQuestions) { q ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .clickable {
                        userPrompt = q
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(text = "✨ $q", fontSize = 13.sp)
            }
        }

        if (aiResponse.isNotBlank()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AiAccentColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("رد Gemini AI:", fontWeight = FontWeight.Bold, color = AiAccentColor)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = aiResponse,
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    currentUser: User?,
    viewModel: ChatViewModel,
    onSwitchUser: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPhotoOptionsDialog by remember { mutableStateOf(false) }
    var showAutoReplyDialog by remember { mutableStateOf(false) }
    val autoReplyEnabled by viewModel.autoReplyManager.isEnabled.collectAsState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                viewModel.authRepo.updateProfilePhoto(uri.toString())
                Toast.makeText(context, "تم تحديث الصورة الشخصية", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            currentUser?.let { user ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // WhatsApp style profile photo with placeholder and camera badge
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clickable { showPhotoOptionsDialog = true }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!user.avatarUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = user.avatarUrl,
                                        contentDescription = "الصورة الشخصية",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "صورة الحساب",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                            }

                            // Camera edit icon badge overlay (WhatsApp style)
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(CircleShape)
                                    .background(PrimaryIndigo),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "تعديل الصورة",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 10-digit ID displayed above their name
                        Text(
                            text = "المعرف الفريد (10 أرقام): ${user.uniqueId10}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryIndigo,
                            modifier = Modifier.testTag("settings_unique_id_label")
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Display name
                        Text(
                            text = user.displayName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "@${user.username}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = user.statusMessage,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showPhotoOptionsDialog = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تعديل الصورة", fontSize = 12.sp)
                            }

                            Button(
                                onClick = onSwitchUser,
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("تبديل الحساب", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Auto-Reply Settings Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAutoReplyDialog = true }
                    .testTag("settings_autoreply_item")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SecondaryTeal.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = SecondaryTeal,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "الرد التلقائي",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (autoReplyEnabled) "مُفعل • انقر لتعديل الخيارات والرسالة" else "مُعطل • إرسال ردود تلقائية عند عدم التفرغ",
                            fontSize = 12.sp,
                            color = if (autoReplyEnabled) SecondaryTeal else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoReplyEnabled,
                        onCheckedChange = { viewModel.autoReplyManager.setEnabled(it) },
                        modifier = Modifier.testTag("settings_autoreply_quick_switch")
                    )
                }
            }
        }

        // Version & Updates Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Version Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp), tint = PrimaryIndigo)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "معلومات التطبيق", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = "الإصدار الحالي للنظام", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = "Version 1.0",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryIndigo,
                            modifier = Modifier.testTag("app_version_field")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Updates Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(20.dp), tint = SecondaryTeal)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "التحديثات", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "You will be notified of everything new.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("app_updates_field")
                            )
                        }
                    }
                }
            }
        }

        // Logout Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.authRepo.logout() }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ErrorRed.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "تسجيل الخروج",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ErrorRed
                    )
                }
            }
        }
    }

    if (showPhotoOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoOptionsDialog = false },
            title = { Text("صورة الملف الشخصي", fontWeight = FontWeight.Bold) },
            text = { Text("يمكنك إضافة صورة جديدة أو تعديلها أو حذف الصورة الحالية.") },
            confirmButton = {
                Button(
                    onClick = {
                        showPhotoOptionsDialog = false
                        photoPickerLauncher.launch("image/*")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                ) {
                    Text("اختيار صورة جديدة")
                }
            },
            dismissButton = {
                if (!currentUser?.avatarUrl.isNullOrEmpty()) {
                    TextButton(
                        onClick = {
                            showPhotoOptionsDialog = false
                            scope.launch {
                                viewModel.authRepo.removeProfilePhoto()
                                Toast.makeText(context, "تم حذف الصورة الشخصية", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("حذف الصورة", color = ErrorRed)
                    }
                } else {
                    TextButton(onClick = { showPhotoOptionsDialog = false }) {
                        Text("إلغاء")
                    }
                }
            }
        )
    }

    if (showAutoReplyDialog) {
        com.example.ui.components.AutoReplySettingsDialog(
            autoReplyManager = viewModel.autoReplyManager,
            onDismiss = { showAutoReplyDialog = false }
        )
    }
}
