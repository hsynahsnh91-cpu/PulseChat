package com.example.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TaskAlt
import com.example.ui.reminders.openExactAlarmSettings
import com.example.data.model.ScheduledState
import com.example.data.model.ScheduledMessage
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.domain.DisappearingPolicy
import com.example.domain.GroupPolicy
import com.example.domain.Ids
import com.example.domain.MessagePolicy
import com.example.domain.NetworkKind
import com.example.domain.TextTools
import com.example.domain.model.MessageType
import com.example.domain.model.Visibility
import com.example.ui.calls.rememberCallStarter
import com.example.ui.common.AiBadge
import com.example.ui.common.AiConsentDialog
import com.example.ui.common.Banner
import com.example.ui.common.ChoiceDialog
import com.example.ui.common.ConfirmDialog
import com.example.ui.common.MuteDialog
import com.example.ui.location.LiveLocationSupport
import com.example.ui.location.LocationShareSheet
import com.example.ui.security.DeviceAuth
import com.example.ui.security.LockChatDialog
import com.example.ui.security.NoScreenLockDialog
import com.example.ui.security.rememberDeviceAuth
import com.example.ui.common.Formatters
import com.example.ui.common.appContainer
import com.example.ui.common.containerFactory
import com.example.ui.components.EmojiPickerView
import com.example.ui.components.UserAvatar
import com.example.ui.status.MediaCaptionDialog
import com.example.ui.theme.PulseTheme
import com.example.util.LocationHelper
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.style.TextAlign
import com.example.ui.theme.Motion
import com.example.ui.theme.Radii
import com.example.ui.theme.Spacing
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.automirrored.rounded.Shortcut

data class ChatNav(
    val back: () -> Unit,
    val openInfo: (String) -> Unit,
    val openMedia: (String) -> Unit,
    val openCall: () -> Unit,
    val openMediaBrowser: (String) -> Unit = {},
    /** Opens another chat (from a shared contact card). */
    val openChat: (String) -> Unit = {},
    /** Opens the full-screen map of a live location (conversation ID, live ID). */
    val openLiveLocation: (String, String) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(conversationId: String, jumpTo: String?, nav: ChatNav) {
    val vm: ChatViewModel = viewModel(key = "chat_$conversationId", factory = containerFactory { ChatViewModel(it, conversationId, jumpTo) })
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val conversation by vm.conversation.collectAsState()
    val items by vm.items.collectAsState()
    val participants by vm.participants.collectAsState()
    val typing by vm.typing.collectAsState()
    val presence by vm.presence.collectAsState()
    val blocked by vm.blockedIds.collectAsState()
    val settings by vm.settings.collectAsState()
    val network by vm.network.collectAsState()
    // Fast-changing state (voice playback position, recording level, the draft being typed) is
    // handed down as State objects and read only by the composables that show it, so typing or
    // playing a voice note does not recompose the whole screen.
    val voiceState = vm.voice.collectAsState()
    val recordingState = vm.recording.collectAsState()
    val draftState = vm.draft.collectAsState()
    val replyTo by vm.replyTo.collectAsState()
    val editing by vm.editing.collectAsState()
    val selection by vm.selection.collectAsState()
    val searchActive by vm.searchActive.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val searchIndex by vm.searchIndex.collectAsState()
    val highlighted by vm.highlighted.collectAsState()
    val mentionState = vm.mentionSuggestions.collectAsState()
    val loadingOlder by vm.loadingOlder.collectAsState()
    val aiPanel by vm.ai.collectAsState()
    val aiTyping by vm.aiTyping.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val peerContact by vm.peerContact.collectAsState()
    val scheduled by vm.scheduled.collectAsState()
    val translations by vm.translations.collectAsState()
    val transcripts by vm.transcripts.collectAsState()
    val summary by vm.summary.collectAsState()
    val suggestions by vm.actionSuggestions.collectAsState()
    val newSinceVisit by vm.newSinceVisit.collectAsState()
    val newBannerDismissed by vm.newBannerDismissed.collectAsState()
    val notificationSettings by vm.notificationSettings.collectAsState()
    val chatLocked by vm.locked.collectAsState()
    val liveSession by vm.liveSession.collectAsState()

    var emojiOpen by rememberSaveable { mutableStateOf(false) }
    // Emoji panel and keyboard replace each other instead of stacking (which pushed the composer
    // to the top of small screens).
    val keyboard = LocalSoftwareKeyboardController.current
    val composerFocus = remember { FocusRequester() }
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) emojiOpen = false }
    var attachOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    var reactionPickerFor by remember { mutableStateOf<Message?>(null) }
    var reactionsDetailFor by remember { mutableStateOf<Message?>(null) }
    var infoFor by remember { mutableStateOf<Message?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var forwardIds by remember { mutableStateOf<Set<String>?>(null) }
    var forwardTargets by remember { mutableStateOf<List<Pair<Conversation, String>>>(emptyList()) }
    var pendingMedia by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var pendingDocument by remember { mutableStateOf<Uri?>(null) }
    var pendingContact by remember { mutableStateOf<Pair<String, String>?>(null) }
    var contactSheet by remember { mutableStateOf(false) }
    var pendingLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var pollOpen by remember { mutableStateOf(false) }
    var aiToolsOpen by remember { mutableStateOf(false) }
    var consentAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var muteOpen by remember { mutableStateOf(false) }
    var disappearingOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var blockOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    var captureVideo by remember { mutableStateOf(false) }
    var scheduleDialog by remember { mutableStateOf<Pair<String, ScheduledMessage?>?>(null) }
    var scheduledSheet by remember { mutableStateOf(false) }
    var reminderFor by remember { mutableStateOf<Message?>(null) }
    var languagePickerFor by remember { mutableStateOf<Message?>(null) }
    var downloadPrompt by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var editImage by remember { mutableStateOf<Uri?>(null) }
    var editHistoryFor by remember { mutableStateOf<Message?>(null) }
    var lockConfirmOpen by remember { mutableStateOf(false) }
    var noScreenLockOpen by remember { mutableStateOf(false) }
    var locationSheet by remember { mutableStateOf(false) }
    var pendingLiveDuration by remember { mutableStateOf<Long?>(null) }
    val deviceAuth = rememberDeviceAuth()
    val muted = notificationSettings?.isMuted() == true

    val conv = conversation
    val me = vm.me
    val usersById = remember(participants) { participants.associateBy { it.userId } }
    val names = remember(participants) { participants.associate { it.userId to it.displayName } }
    val isAi = conv?.isAiConversation == true
    val isGroup = conv?.isGroup == true
    val peerId = conv?.peerId
    val peer = peerId?.let { usersById[it] }
    val peerBlocked = !isGroup && peerId != null && peerId in blocked
    val canSendInGroup = !isGroup || conv?.let { GroupPolicy.canSend(it.roster(), me) } == true
    val readOnlyLegacy = conv?.isLocalOnly == true && !isAi
    val title = when {
        conv == null -> ""
        isGroup -> conv.title
        isAi -> stringResource(R.string.ai_assistant_name)
        !peerContact?.customNickname.isNullOrBlank() -> peerContact?.customNickname.orEmpty()
        else -> peer?.displayName ?: conv.title.ifBlank { stringResource(R.string.unknown_user) }
    }
    val showReadReceipts = settings.readReceipts

    // ---------------------------------------------------------------- effects
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.onVisible(true)
                Lifecycle.Event.ON_PAUSE -> vm.onVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); vm.onVisible(false) }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ChatEvent.Toast -> snackbar.showSnackbar(e.text)
                is ChatEvent.NeedAiConsent -> consentAction = e.action
                is ChatEvent.NeedLanguageDownload -> downloadPrompt = e.messageId to e.languages
                is ChatEvent.OpenChat -> nav.openChat(e.conversationId)
            }
        }
    }
    LaunchedEffect(Unit) {
        container.chatJumps.collect { j -> if (j != null && j.first == conversationId) { container.chatJumps.value = null; vm.jumpTo(j.second) } }
    }
    LaunchedEffect(Unit) { vm.scrollTo.collect { idx -> if (idx >= 0) listState.animateScrollToItem(idx) } }
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (items.isNotEmpty() && last >= items.size - 6) vm.loadMore()
        }
    }
    var initialPositioned by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (!initialPositioned && items.isNotEmpty()) {
            initialPositioned = true
            val unreadIndex = items.indexOfFirst { it is UnreadItem }
            if (unreadIndex > 3 && jumpTo == null) listState.scrollToItem(unreadIndex)
        } else if (listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }

    // ---------------------------------------------------------------- pickers & permissions
    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pendingMedia = uri to (context.contentResolver.getType(uri)?.startsWith("video/") == true)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pendingDocument = uri }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        if (ok && uri != null) pendingMedia = uri to false
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = captureUri
        if (ok && uri != null) pendingMedia = uri to true
    }
    fun launchCamera(video: Boolean) {
        val (_, uri) = container.media.newCaptureFile(if (video) "mp4" else "jpg")
        captureUri = uri
        captureVideo = video
        if (video) takeVideo.launch(uri) else takePicture.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera(captureVideo) else Toast.makeText(context, R.string.permission_camera_denied, Toast.LENGTH_LONG).show()
    }
    fun requestCamera(video: Boolean) {
        captureVideo = video
        if (has(Manifest.permission.CAMERA)) launchCamera(video) else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else Toast.makeText(context, R.string.permission_mic_denied, Toast.LENGTH_LONG).show()
    }
    fun fetchLocation() {
        LocationHelper.current(context) { loc ->
            if (loc == null) Toast.makeText(context, R.string.location_unavailable, Toast.LENGTH_LONG).show()
            else pendingLocation = loc.latitude to loc.longitude
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) fetchLocation() else Toast.makeText(context, R.string.permission_location_denied, Toast.LENGTH_LONG).show()
    }
    /** Live sharing starts from the first real fix; without location services nothing is started. */
    fun startLive(durationMs: Long) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) { Toast.makeText(context, R.string.location_unavailable, Toast.LENGTH_LONG).show(); return }
        LocationHelper.current(context) { loc ->
            if (loc == null) Toast.makeText(context, R.string.location_unavailable, Toast.LENGTH_LONG).show()
            else vm.startLiveLocation(durationMs, loc.latitude, loc.longitude, loc.accuracy, loc.time)
        }
    }
    val livePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val duration = pendingLiveDuration
        pendingLiveDuration = null
        if (result.values.any { it } && duration != null) startLive(duration)
        else if (!result.values.any { it }) Toast.makeText(context, R.string.permission_location_denied, Toast.LENGTH_LONG).show()
    }
    val startCall = rememberCallStarter(onStarted = nav.openCall)

    BackHandler(enabled = selection.isNotEmpty() || searchActive || emojiOpen) {
        when {
            selection.isNotEmpty() -> vm.clearSelection()
            searchActive -> vm.setSearchActive(false)
            else -> emojiOpen = false
        }
    }

    // ---------------------------------------------------------------- UI
    Scaffold(
        topBar = {
            when {
                selection.isNotEmpty() -> SelectionBar(
                    count = selection.size,
                    onClose = vm::clearSelection,
                    onStar = { scope.launch { val msgs = vm.messagesFor(selection); vm.star(selection, msgs.any { !it.isStarred }) } },
                    onCopy = {
                        scope.launch {
                            val text = vm.messagesFor(selection).filter { MessagePolicy.canCopy(vm.facts(it)) }.joinToString("\n") { it.content }
                            copyToClipboard(context, text)
                            vm.clearSelection()
                        }
                    },
                    onForward = {
                        scope.launch {
                            forwardTargets = vm.forwardTargets().map { it to conversationTitle(context, it, names) }
                            forwardIds = selection
                        }
                    },
                    onDelete = { deleteIds = selection },
                )
                searchActive -> SearchBar(
                    query = searchQuery, onQuery = vm::search, position = searchIndex, total = searchResults.size,
                    onOlder = { vm.searchStep(1) }, onNewer = { vm.searchStep(-1) }, onClose = { vm.setSearchActive(false) },
                )
                else -> TopAppBar(
                    title = {
                        Row(Modifier.clickable(enabled = conv != null && !isAi) { nav.openInfo(conversationId) }, verticalAlignment = Alignment.CenterVertically) {
                            if (isGroup && conv?.avatarUrl == null) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Groups, contentDescription = null)
                                }
                            } else {
                                UserAvatar(title, avatarUrl = if (isGroup) conv?.avatarUrl else peer?.avatarUrl, size = 40.dp, isAiAssistant = isAi, colorHex = peer?.avatarColorHex)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                val subtitle = when {
                                    isAi -> if (aiTyping) stringResource(R.string.ai_thinking) else stringResource(R.string.ai_subtitle)
                                    typing.isNotEmpty() && isGroup -> stringResource(R.string.typing_named, typing.mapNotNull { names[it] }.joinToString(", "))
                                    typing.isNotEmpty() -> stringResource(R.string.typing)
                                    isGroup -> participants.sortedBy { if (it.userId == me) 1 else 0 }.joinToString(", ") { if (it.userId == me) context.getString(R.string.you) else it.displayName }
                                    readOnlyLegacy -> stringResource(R.string.chat_local_only_short)
                                    else -> Formatters.lastSeen(context, presence)
                                }
                                if (!subtitle.isNullOrBlank()) {
                                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                        color = if (typing.isNotEmpty() || aiTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                    actions = {
                        val canCall = !isGroup && !isAi && !readOnlyLegacy && peerId != null && !peerBlocked
                        // On narrow screens the video call moves into the menu so the name stays readable.
                        val narrowBar = LocalConfiguration.current.screenWidthDp < 360
                        if (canCall) {
                            if (!narrowBar) IconButton(onClick = { startCall(peerId, true) }) { Icon(Icons.Rounded.Videocam, stringResource(R.string.action_video_call)) }
                            IconButton(onClick = { startCall(peerId, false) }, modifier = Modifier.testTag("chat_voice_call")) { Icon(Icons.Rounded.Call, stringResource(R.string.action_voice_call)) }
                        }
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("chat_menu")) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (canCall && narrowBar) DropdownMenuItem(text = { Text(stringResource(R.string.action_video_call)) },
                                leadingIcon = { Icon(Icons.Rounded.Videocam, null) }, onClick = { menuOpen = false; startCall(peerId, true) })
                            if (!isAi) DropdownMenuItem(text = { Text(stringResource(if (isGroup) R.string.group_info else R.string.contact_info)) },
                                onClick = { menuOpen = false; nav.openInfo(conversationId) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_search)) }, onClick = { menuOpen = false; vm.setSearchActive(true) })
                            if (!isAi) DropdownMenuItem(text = { Text(stringResource(R.string.media_browser_title)) }, leadingIcon = { Icon(Icons.Rounded.PermMedia, null) },
                                onClick = { menuOpen = false; nav.openMediaBrowser(conversationId) }, modifier = Modifier.testTag("menu_media_browser"))
                            if (!isAi && !readOnlyLegacy) {
                                DropdownMenuItem(text = { Text(stringResource(if (muted) R.string.action_unmute else R.string.action_mute)) },
                                    leadingIcon = { Icon(if (muted) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff, null) },
                                    onClick = { menuOpen = false; if (muted) vm.setMuted(0L) else muteOpen = true }, modifier = Modifier.testTag("menu_mute"))
                                DropdownMenuItem(text = { Text(stringResource(R.string.disappearing_title)) }, onClick = { menuOpen = false; disappearingOpen = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.ai_summarize)) }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                                    onClick = { menuOpen = false; vm.aiSummarize() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.ai_find_tasks)) }, leadingIcon = { Icon(Icons.Rounded.TaskAlt, null) },
                                    onClick = { menuOpen = false; vm.findTasksAndEvents() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.scheduled_messages)) }, leadingIcon = { Icon(Icons.Rounded.Schedule, null) },
                                    onClick = { menuOpen = false; scheduledSheet = true }, modifier = Modifier.testTag("menu_scheduled"))
                            }
                            // Chat lock: changing it always needs the phone's screen-lock check.
                            DropdownMenuItem(
                                text = { Text(stringResource(if (chatLocked) R.string.chat_lock_remove else R.string.chat_lock_add)) },
                                leadingIcon = { Icon(if (chatLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                                modifier = Modifier.testTag("menu_lock"),
                                onClick = {
                                    menuOpen = false
                                    when {
                                        !DeviceAuth.isAvailable(context) -> noScreenLockOpen = true
                                        chatLocked -> deviceAuth.authenticate(context.getString(R.string.chat_lock_remove), context.getString(R.string.chat_lock_prompt_subtitle)) { r ->
                                            if (r == DeviceAuth.Result.SUCCESS) vm.setLocked(false) else if (r == DeviceAuth.Result.UNAVAILABLE) noScreenLockOpen = true
                                        }
                                        else -> lockConfirmOpen = true
                                    }
                                },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_clear_chat)) }, onClick = { menuOpen = false; clearOpen = true })
                            if (!isGroup && !isAi && peerId != null && !readOnlyLegacy) {
                                DropdownMenuItem(text = { Text(stringResource(if (peerBlocked) R.string.action_unblock else R.string.action_block)) },
                                    onClick = { menuOpen = false; if (peerBlocked) vm.unblockPeer() else blockOpen = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_report)) }, onClick = { menuOpen = false; reportOpen = true })
                            }
                        }
                    },
                )
            }
        },
        // Bottom insets are applied once, by the composer area below (navigation bar or keyboard,
        // whichever is taller), so the message list ends right above the composer.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).background(PulseTheme.colors.chatBackground)) {
            if (network == NetworkKind.NONE && !isAi) Banner(stringResource(R.string.banner_offline_chat), Icons.Rounded.WifiOff,
                MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            if (readOnlyLegacy) Banner(stringResource(R.string.chat_local_only), Icons.Rounded.History, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            if (isAi) Banner(stringResource(R.string.ai_chat_notice), Icons.Rounded.AutoAwesome, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f), MaterialTheme.colorScheme.onSurface)
            if ((conv?.disappearingSeconds ?: 0L) > 0) Banner(
                stringResource(R.string.disappearing_banner, Formatters.disappearingLabel(context, conv!!.disappearingSeconds)), Icons.Rounded.Timer,
                MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isAi && !readOnlyLegacy && newSinceVisit >= 3 && !newBannerDismissed && summary == null) {
                NewSinceVisitBanner(newSinceVisit, onSummarize = vm::summarizeSinceLastVisit, onDismiss = { vm.newBannerDismissed.value = true })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (items.isEmpty() && conv != null) {
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (isAi) Icons.Rounded.AutoAwesome else Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(if (isAi) R.string.ai_empty else R.string.chat_empty), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val callbacks = remember(vm) {
                    BubbleCallbacks(
                        onClick = { m -> if (vm.selection.value.isNotEmpty()) vm.toggleSelect(m.messageId) },
                        onLongClick = { m -> if (vm.selection.value.isNotEmpty()) vm.toggleSelect(m.messageId) else actionTarget = m },
                        onSwipeReply = { m -> if (MessagePolicy.canReply(vm.facts(m))) vm.startReply(m) },
                        onReplyQuote = { id -> vm.jumpTo(id) },
                        onRetry = { m -> vm.retry(m.messageId) },
                        onDownload = { m -> vm.download(m.messageId) },
                        onCancel = { m -> vm.cancelTransfer(m) },
                        onOpenMedia = { m -> nav.openMedia(m.messageId) },
                        onOpenDocument = { m -> openDocument(context, container.media.contentUriFor(m.localMediaPath!!), m.mediaMimeType) },
                        onOpenLocation = { m -> openLocation(context, m.locationLat ?: 0.0, m.locationLng ?: 0.0) },
                        onDialContact = { m -> dial(context, m.contactPhone.orEmpty()) },
                        onToggleVoice = { m -> vm.toggleVoice(m) },
                        onSeekVoice = { m, f -> vm.seekVoice(m, f) },
                        onVoiceSpeed = { vm.cycleVoiceSpeed() },
                        onVote = { m, i -> vm.vote(m.messageId, i) },
                        onReactions = { m -> reactionsDetailFor = m },
                        onMessageContact = { m -> vm.messageContact(m) },
                        onShowEdits = { m -> editHistoryFor = m },
                        live = LiveLocationSupport(
                            observe = vm::observeLive, now = vm::serverNow,
                            onOpen = { m -> m.liveId?.let { nav.openLiveLocation(m.conversationId, it) } },
                            onStop = { vm.stopLiveLocation() },
                        ),
                    )
                }
                LazyColumn(
                    state = listState, reverseLayout = true, contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.sm),
                    modifier = Modifier.fillMaxSize().testTag("message_list"),
                ) {
                    if (aiTyping) item(key = "ai_typing") {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = vm::cancelAi) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                    items(items, key = { it.key }, contentType = { it::class.simpleName }) { item ->
                        when (item) {
                            // New messages fade in once; items never slide around when the list changes.
                            is MessageItem -> Column(Modifier.animateItem(fadeInSpec = Motion.enter(Motion.STANDARD), placementSpec = null, fadeOutSpec = null)) {
                                MessageRow(
                                    item = item, me = me, names = names, isGroup = isGroup, isAi = isAi, hideTicks = readOnlyLegacy,
                                    selected = item.message.messageId in selection, selectionMode = selection.isNotEmpty(),
                                    highlighted = highlighted == item.message.messageId, searchQuery = if (searchActive) searchQuery else "",
                                    voice = voiceState, showReadReceipts = showReadReceipts, canSwipeReply = !readOnlyLegacy && !isAi, callbacks = callbacks,
                                )
                                MessageExtras(
                                    m = item.message, mine = item.message.senderId == me,
                                    translation = translations[item.message.messageId], transcript = transcripts[item.message.messageId],
                                    vm = vm, onPickLanguage = { languagePickerFor = it },
                                )
                            }
                            is DayItem -> Box(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), contentAlignment = Alignment.Center) {
                                Text(Formatters.dayHeader(context, item.at), style = MaterialTheme.typography.labelMedium, color = PulseTheme.colors.onSystemChip,
                                    modifier = Modifier.clip(CircleShape).background(PulseTheme.colors.systemChip).padding(horizontal = Spacing.md, vertical = Spacing.xs))
                            }
                            is UnreadItem -> Box(Modifier.fillMaxWidth().padding(vertical = Spacing.sm).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                                Text(context.resources.getQuantityString(R.plurals.unread_messages, item.count, item.count), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(6.dp))
                            }
                        }
                    }
                    if (loadingOlder) item(key = "loading_older") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    }
                }
                // The plain overload (inside this Box the Column's scoped variant is not available).
                androidx.compose.animation.AnimatedVisibility(
                    visible = showJump,
                    enter = fadeIn(Motion.enter(Motion.QUICK)) + scaleIn(Motion.enter(Motion.QUICK), initialScale = 0.85f),
                    exit = fadeOut(Motion.exit(Motion.QUICK)) + scaleOut(Motion.exit(Motion.QUICK), targetScale = 0.85f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.action_jump_latest)) }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            // Everything under the list: panels, reply bar and the composer. It is lifted by the
            // keyboard or the navigation bar (whichever is taller) — exactly once.
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))) {
                summary?.let { SummaryPanel(it, onJump = { id -> vm.jumpTo(id) }, onClose = vm::closeSummary) }
                if (scheduled.isNotEmpty() && !isAi) {
                    ScheduledBar(count = scheduled.size, failed = scheduled.count { it.state == ScheduledState.FAILED }, onOpen = { scheduledSheet = true })
                }
                // AI results panel
                if (aiPanel.busy || aiPanel.suggestions.isNotEmpty() || aiPanel.text != null) {
                    AiResultPanel(aiPanel, onUse = vm::useSuggestion, onClose = vm::cancelAi, onCopy = { copyToClipboard(context, it) })
                }
                val reply = replyTo
                val edit = editing
                val replyBar = when {
                    edit != null -> ReplyBarData("edit_" + edit.messageId, stringResource(R.string.editing_message), edit.content, editing = true)
                    reply != null -> ReplyBarData(
                        "reply_" + reply.messageId,
                        if (reply.senderId == me) stringResource(R.string.you) else names[reply.senderId] ?: stringResource(R.string.unknown_user),
                        listOfNotNull(com.example.ui.common.typeLabel(reply.type), reply.content.ifBlank { reply.fileName }).joinToString(" · "),
                        editing = false,
                    )
                    else -> null
                }
                // Slides open/closed with its height, so the composer moves instead of jumping.
                AnimatedContent(
                    targetState = replyBar,
                    contentKey = { it?.key },
                    transitionSpec = {
                        (fadeIn(Motion.enter(Motion.QUICK)) togetherWith fadeOut(Motion.exit(Motion.QUICK)))
                            .using(SizeTransform(clip = true) { _, _ -> Motion.standard(Motion.STANDARD) })
                    },
                    label = "reply-bar",
                ) { bar ->
                    if (bar != null) ReplyOrEditBar(bar.title, bar.preview, editing = bar.editing, onCancel = if (bar.editing) vm::cancelEdit else vm::cancelReply)
                }
                MentionSuggestions(mentionState, participants, onPick = vm::pickMention)
                when {
                    conv == null -> Unit
                    readOnlyLegacy -> Unit
                    conv.isLeft -> Notice(stringResource(R.string.chat_left_group))
                    peerBlocked -> Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Rounded.Block, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_blocked_notice), modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = vm::unblockPeer) { Text(stringResource(R.string.action_unblock)) }
                        }
                    }
                    !canSendInGroup -> Notice(stringResource(R.string.chat_only_admins))
                    else -> Column {
                        Composer(
                            value = draftState, onValueChange = vm::onDraftChange, onSend = vm::send,
                            onAttach = { attachOpen = true }, onCamera = { requestCamera(false) },
                            emojiOpen = emojiOpen,
                            onEmojiToggle = {
                                if (emojiOpen) { emojiOpen = false; runCatching { composerFocus.requestFocus() }; keyboard?.show() }
                                else { keyboard?.hide(); emojiOpen = true }
                            },
                            recording = recordingState, focusRequester = composerFocus,
                            onStartRecording = { if (has(Manifest.permission.RECORD_AUDIO)) vm.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            onFinishRecording = vm::finishRecording, enterToSend = settings.enterToSend, isAiChat = isAi, editing = edit != null,
                            onSchedule = if (!isAi && edit == null) ({ scheduleDialog = vm.draft.value.text to null }) else null,
                        )
                        if (emojiOpen) EmojiPickerView(onEmojiSelected = { emoji ->
                            val v = vm.draft.value
                            val pos = v.selection.end.coerceIn(0, v.text.length)
                            val text = v.text.substring(0, pos) + emoji + v.text.substring(pos)
                            vm.onDraftChange(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(pos + emoji.length)))
                        }, modifier = Modifier.height(280.dp))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- sheets & dialogs
    actionTarget?.let { m ->
        MessageActionsSheet(
            message = m, facts = vm.facts(m), now = vm.now(), me = me, onDismiss = { actionTarget = null },
            actions = MessageActions(
                onReact = { e -> vm.react(m.messageId, e); actionTarget = null },
                onMoreReactions = { reactionPickerFor = m; actionTarget = null },
                onReply = { vm.startReply(m); actionTarget = null },
                onCopy = { copyToClipboard(context, if (m.type == MessageType.LOCATION) "${m.locationLat},${m.locationLng}" else if (m.type == MessageType.CONTACT) "${m.contactName} ${m.contactPhone}" else m.content); actionTarget = null },
                onForward = {
                    actionTarget = null
                    scope.launch { forwardTargets = vm.forwardTargets().map { it to conversationTitle(context, it, names) }; forwardIds = setOf(m.messageId) }
                },
                onStar = { vm.star(listOf(m.messageId), !m.isStarred); actionTarget = null },
                onEdit = { vm.startEdit(m); actionTarget = null },
                onDelete = { deleteIds = setOf(m.messageId); actionTarget = null },
                onSelect = { vm.toggleSelect(m.messageId); actionTarget = null },
                onInfo = { infoFor = m; actionTarget = null },
                onRetry = { vm.retry(m.messageId); actionTarget = null },
                onTranslate = if (m.content.isNotBlank() && !m.deletedForEveryone && m.type != MessageType.SYSTEM && m.type != MessageType.POLL) ({ actionTarget = null; vm.translate(m) }) else null,
                onRemind = if (!m.deletedForEveryone && m.type != MessageType.SYSTEM && !readOnlyLegacy) ({ actionTarget = null; reminderFor = m }) else null,
                onTranscribe = if (m.type == MessageType.AUDIO && !m.deletedForEveryone && !isAi) ({ actionTarget = null; vm.transcribe(m) }) else null,
                onEditHistory = if (m.editedAt > 0 && !m.deletedForEveryone) ({ actionTarget = null; editHistoryFor = m }) else null,
            ),
        )
    }
    reactionPickerFor?.let { m -> ReactionPickerSheet(onPick = { e -> vm.react(m.messageId, e); reactionPickerFor = null }, onDismiss = { reactionPickerFor = null }) }
    reactionsDetailFor?.let { m -> ReactionsDetailSheet(m, usersById, me, onRemoveMine = { vm.react(m.messageId, null); reactionsDetailFor = null }, onDismiss = { reactionsDetailFor = null }) }
    infoFor?.let { m ->
        MessageInfoSheet(m, participants.filter { it.userId != me && it.userId != Ids.AI_ASSISTANT_ID }, receipts, showReadReceipts, onDismiss = { infoFor = null })
    }
    deleteIds?.let { ids ->
        var canEveryone by remember(ids) { mutableStateOf(false) }
        LaunchedEffect(ids) { canEveryone = vm.messagesFor(ids).let { list -> list.isNotEmpty() && list.all { MessagePolicy.canDeleteForEveryone(vm.facts(it), vm.now()) } } }
        DeleteMessagesDialog(
            count = ids.size, canDeleteForEveryone = canEveryone,
            onDeleteForMe = { vm.deleteForMe(ids); deleteIds = null },
            onDeleteForEveryone = { vm.deleteForEveryone(ids); deleteIds = null },
            onDismiss = { deleteIds = null },
        )
    }
    forwardIds?.let { ids -> ForwardSheet(forwardTargets, onSend = { targets -> vm.forward(ids, targets); forwardIds = null }, onDismiss = { forwardIds = null }) }
    if (attachOpen) {
        AttachmentSheet(showPoll = isGroup || !isAi, showAi = !isAi, onDismiss = { attachOpen = false }) { option ->
            attachOpen = false
            when (option) {
                AttachOption.GALLERY -> galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                AttachOption.CAMERA_PHOTO -> requestCamera(false)
                AttachOption.CAMERA_VIDEO -> requestCamera(true)
                AttachOption.DOCUMENT -> documentPicker.launch(arrayOf("*/*"))
                AttachOption.CONTACT -> contactSheet = true
                AttachOption.LOCATION -> locationSheet = true
                AttachOption.POLL -> pollOpen = true
                AttachOption.AI_TOOLS -> aiToolsOpen = true
            }
        }
    }
    pendingMedia?.let { (uri, video) ->
        MediaCaptionDialog(uri, video, title, onDismiss = { pendingMedia = null }, onEdit = if (!video) ({ editImage = uri }) else null) { caption ->
            pendingMedia = null
            vm.sendMedia(uri, if (video) MessageType.VIDEO else MessageType.IMAGE, caption)
        }
    }
    pendingDocument?.let { uri ->
        ConfirmDialog(title = stringResource(R.string.document_send_title), text = uri.lastPathSegment, confirmLabel = stringResource(R.string.action_send),
            onConfirm = { vm.sendMedia(uri, MessageType.DOCUMENT, ""); pendingDocument = null }, onDismiss = { pendingDocument = null })
    }
    if (contactSheet) {
        // In-app contacts only (saved contacts and people I chat with); the phone's address book is not used.
        val shareable by vm.shareableContacts.collectAsState()
        ShareContactSheet(shareable, onDismiss = { contactSheet = false }) { contact ->
            contactSheet = false
            pendingContact = contact.shownName to ContactSearch.handleOf(contact)
        }
    }
    pendingContact?.let { (name, phone) ->
        ConfirmDialog(title = stringResource(R.string.contact_send_title), text = "$name\n$phone", confirmLabel = stringResource(R.string.action_send),
            onConfirm = { vm.sendContact(name, phone); pendingContact = null }, onDismiss = { pendingContact = null })
    }
    if (locationSheet) {
        LocationShareSheet(
            chatName = title,
            activeHere = liveSession?.takeIf { it.conversationId == conversationId },
            activeElsewhere = liveSession != null && liveSession?.conversationId != conversationId,
            onSendCurrent = {
                locationSheet = false
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) fetchLocation()
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onShareLive = { duration ->
                locationSheet = false
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) startLive(duration)
                else { pendingLiveDuration = duration; livePermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            },
            onStopLive = { locationSheet = false; vm.stopLiveLocation() },
            onDismiss = { locationSheet = false },
        )
    }
    pendingLocation?.let { (lat, lng) ->
        ConfirmDialog(title = stringResource(R.string.location_send_title), text = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng),
            confirmLabel = stringResource(R.string.action_send), onConfirm = { vm.sendLocation(lat, lng); pendingLocation = null }, onDismiss = { pendingLocation = null })
    }
    editImage?.let { src ->
        ImageEditorDialog(src, onDone = { out -> editImage = null; pendingMedia = out to false }, onDismiss = { editImage = null })
    }
    scheduleDialog?.let { (text, existing) ->
        ScheduleMessageDialog(
            initialText = existing?.text ?: text, initialAt = existing?.scheduledAt, editing = existing != null,
            onConfirm = { t, at -> scheduleDialog = null; if (existing != null) vm.editScheduled(existing.id, t, at) else vm.schedule(t, at) },
            onDismiss = { scheduleDialog = null },
        )
    }
    if (scheduledSheet) {
        ScheduledMessagesSheet(
            scheduled, onEdit = { scheduledSheet = false; scheduleDialog = it.text to it },
            onSendNow = { vm.sendScheduledNow(it.id) }, onDelete = { vm.cancelScheduled(it.id) }, onDismiss = { scheduledSheet = false },
        )
    }
    reminderFor?.let { m ->
        ReminderTimeDialog(stringResource(R.string.reminder_title), onPick = { at -> reminderFor = null; vm.remindAbout(m, at) }, onDismiss = { reminderFor = null },
            exactAllowed = vm.exactAlarmsAllowed, onAllowExact = { openExactAlarmSettings(context) })
    }
    languagePickerFor?.let { m ->
        LanguagePickerDialog(vm.languageChoices(), vm.translationTarget(), onPick = { tag -> languagePickerFor = null; vm.setTranslationTarget(tag, m) },
            onDismiss = { languagePickerFor = null })
    }
    downloadPrompt?.let { (id, langs) ->
        ConfirmDialog(
            title = stringResource(R.string.translate_download_title),
            text = stringResource(R.string.translate_download_body, langs.joinToString { vm.languageName(it) }),
            confirmLabel = stringResource(R.string.translate_download_confirm),
            onConfirm = { downloadPrompt = null; vm.translateById(id, allowDownload = true) }, onDismiss = { downloadPrompt = null },
        )
    }
    suggestions?.let { list ->
        ActionSuggestionsSheet(list, onJump = { id -> vm.dismissSuggestions(); vm.jumpTo(id) }, onCreate = vm::createRemindersFrom, onDismiss = vm::dismissSuggestions)
    }
    if (pollOpen) PollDialog(onCreate = { q, o -> vm.sendPoll(q, o); pollOpen = false }, onDismiss = { pollOpen = false })
    if (aiToolsOpen) {
        ModalBottomSheet(onDismissRequest = { aiToolsOpen = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_tools), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    AiBadge()
                }
                Text(stringResource(R.string.ai_tools_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
                TextButton(onClick = { aiToolsOpen = false; vm.aiSuggestReplies() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.ai_suggest_replies)) }
                TextButton(onClick = { aiToolsOpen = false; vm.aiPolish() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.ai_polish)) }
                TextButton(onClick = { aiToolsOpen = false; vm.aiSummarize() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.ai_summarize)) }
            }
        }
    }
    consentAction?.let { action ->
        AiConsentDialog(onAccept = { vm.grantAiConsent(); consentAction = null; action() }, onDecline = { consentAction = null })
    }
    if (muteOpen) {
        MuteDialog(isGroup = isGroup, onSelect = { until -> muteOpen = false; vm.setMuted(until) }, onDismiss = { muteOpen = false })
    }
    if (lockConfirmOpen) {
        LockChatDialog(
            onConfirm = {
                lockConfirmOpen = false
                deviceAuth.authenticate(context.getString(R.string.chat_lock_confirm_prompt), context.getString(R.string.chat_lock_prompt_subtitle)) { r ->
                    if (r == DeviceAuth.Result.SUCCESS) vm.setLocked(true) else if (r == DeviceAuth.Result.UNAVAILABLE) noScreenLockOpen = true
                }
            },
            onDismiss = { lockConfirmOpen = false },
        )
    }
    if (noScreenLockOpen) {
        NoScreenLockDialog(
            onOpenSettings = {
                noScreenLockOpen = false
                container.appLock.onExternalActivityLaunched()
                runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            },
            onDismiss = { noScreenLockOpen = false },
        )
    }
    editHistoryFor?.let { m -> EditHistorySheet(m, onDismiss = { editHistoryFor = null }) }
    if (disappearingOpen) {
        ChoiceDialog(stringResource(R.string.disappearing_title), DisappearingPolicy.OPTIONS.map { it to Formatters.disappearingLabel(context, it) }, conv?.disappearingSeconds,
            onSelect = { s -> disappearingOpen = false; vm.setDisappearing(s) }, onDismiss = { disappearingOpen = false })
    }
    if (clearOpen) {
        ConfirmDialog(title = stringResource(R.string.clear_chat_title), text = stringResource(R.string.clear_chat_body), confirmLabel = stringResource(R.string.action_clear),
            destructive = true, checkboxLabel = stringResource(R.string.clear_keep_starred), checkboxDefault = true,
            onConfirm = { keep -> clearOpen = false; vm.clearChat(keep) }, onDismiss = { clearOpen = false })
    }
    if (blockOpen) {
        ConfirmDialog(title = stringResource(R.string.block_title, title), text = stringResource(R.string.block_body), confirmLabel = stringResource(R.string.action_block),
            destructive = true, onConfirm = { blockOpen = false; vm.blockPeer() }, onDismiss = { blockOpen = false })
    }
    if (reportOpen) {
        ConfirmDialog(title = stringResource(R.string.report_title, title), text = stringResource(R.string.report_body), confirmLabel = stringResource(R.string.action_report),
            destructive = true, checkboxLabel = stringResource(R.string.report_and_block), checkboxDefault = true,
            onConfirm = { alsoBlock -> reportOpen = false; vm.reportPeer(alsoBlock) }, onDismiss = { reportOpen = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onStar: () -> Unit, onCopy: () -> Unit, onForward: () -> Unit, onDelete: () -> Unit) {
    TopAppBar(
        title = { Text(count.toString(), fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) } },
        actions = {
            IconButton(onClick = onStar) { Icon(Icons.Rounded.Star, stringResource(R.string.action_star)) }
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy)) }
            IconButton(onClick = onForward) { Icon(Icons.AutoMirrored.Rounded.Shortcut, stringResource(R.string.action_forward)) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, position: Int, total: Int, onOlder: () -> Unit, onNewer: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    var text by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search_in_chat)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onQuery(text) }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
        actions = {
            if (total > 0) Text("${position + 1}/$total", style = MaterialTheme.typography.labelMedium)
            else if (query.isNotBlank()) Text(stringResource(R.string.search_no_results), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onOlder, enabled = total > 0 && position < total - 1) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.search_older)) }
            IconButton(onClick = onNewer, enabled = total > 0 && position > 0) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.search_newer)) }
        },
    )
}

@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.lg), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AiResultPanel(panel: AiPanel, onUse: (String) -> Unit, onClose: () -> Unit, onCopy: (String) -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (panel.title != 0) Text(stringResource(panel.title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                AiBadge()
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
            }
            when {
                panel.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_thinking))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
                }
                panel.suggestions.isNotEmpty() -> Column {
                    panel.suggestions.forEach { s ->
                        Text(s, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.sm)).clickable { onUse(s) }
                            .background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp))
                        Spacer(Modifier.height(6.dp))
                    }
                }
                panel.text != null -> Column {
                    Text(panel.text, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        if (panel.replaceDraft) TextButton(onClick = { onUse(panel.text) }) { Text(stringResource(R.string.ai_use_text)) }
                        TextButton(onClick = { onCopy(panel.text) }) { Text(stringResource(R.string.action_copy)) }
                    }
                }
            }
        }
    }
}

private fun conversationTitle(context: Context, c: Conversation, names: Map<String, String>): String = when {
    c.isGroup -> c.title
    c.isAiConversation -> context.getString(R.string.ai_assistant_name)
    else -> context.appContainerNameFallback(c, names)
}

private fun Context.appContainerNameFallback(c: Conversation, names: Map<String, String>): String =
    names[c.peerId] ?: c.title.ifBlank { getString(R.string.unknown_user) }

fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PulseChat", text))
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
}

fun openDocument(context: Context, uri: Uri, mime: String?) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_SHORT).show() }
}

fun openLocation(context: Context, lat: Double, lng: Double) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))
    try { context.startActivity(geo) } catch (e: ActivityNotFoundException) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng"))) }
        catch (e2: ActivityNotFoundException) { Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_SHORT).show() }
    }
}

fun dial(context: Context, phone: String) {
    if (phone.isBlank()) return
    try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)))) }
    catch (e: ActivityNotFoundException) { Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_SHORT).show() }
}

/** Translation and transcript cards under a message, aligned with its bubble (RTL-aware). */
@Composable
private fun MessageExtras(m: Message, mine: Boolean, translation: TranslationUi?, transcript: TranscriptUi?, vm: ChatViewModel, onPickLanguage: (Message) -> Unit) {
    if (translation == null && transcript == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        val width = Modifier.fillMaxWidth(0.85f)
        transcript?.let { TranscriptCard(it, onRetry = { vm.transcribe(m) }, onToggle = { vm.toggleTranscript(m.messageId) }, modifier = width) }
        translation?.let {
            TranslationCard(
                it, languageName = vm::languageName, onToggleOriginal = { vm.toggleOriginal(m.messageId) }, onChangeLanguage = { onPickLanguage(m) },
                onRetry = { vm.translate(m) }, onClose = { vm.closeTranslation(m.messageId) }, modifier = width.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ScheduledBar(count: Int, failed: Int, onOpen: () -> Unit) {
    val context = LocalContext.current
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("scheduled_bar")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(context.resources.getQuantityString(R.plurals.scheduled_count, count, count), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (failed > 0) Text(context.resources.getQuantityString(R.plurals.scheduled_failed_count, failed, failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** What the bar above the composer shows while replying to or editing a message. */
private data class ReplyBarData(val key: String, val title: String, val preview: String, val editing: Boolean)
