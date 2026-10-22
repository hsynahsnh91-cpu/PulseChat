package com.example.ui.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Folder
import com.example.data.repository.SyncState
import com.example.domain.NetworkKind
import com.example.ui.calls.rememberCallStarter
import com.example.ui.common.Banner
import com.example.ui.common.ChoiceDialog
import com.example.ui.common.ConfirmDialog
import com.example.ui.common.MuteDialog
import com.example.ui.common.chatErrorText
import com.example.ui.common.containerFactory
import com.example.ui.components.UserAvatar
import com.example.ui.status.MediaCaptionDialog
import com.example.ui.status.StatusTextComposer
import kotlinx.coroutines.launch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FloatingActionButtonDefaults

/** Navigation callbacks used by the home screen. */
data class HomeNav(
    val openChat: (String) -> Unit,
    val openPeer: (String) -> Unit,
    val openNewChat: () -> Unit,
    val openNewGroup: () -> Unit,
    val openCallPicker: () -> Unit,
    val openSettings: () -> Unit,
    val openProfile: () -> Unit,
    val openStarred: () -> Unit,
    val openArchived: () -> Unit,
    val openSearch: (String) -> Unit,
    val openStatus: (String) -> Unit,
    val openStatusPrivacy: () -> Unit,
    val openCall: () -> Unit,
    val openReminders: () -> Unit = {},
    val openFolders: () -> Unit = {},
    val openLocked: () -> Unit = {},
)

private const val TAB_CHATS = 0
private const val TAB_UPDATES = 1
private const val TAB_GROUPS = 2
private const val TAB_CALLS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: HomeNav) {
    val vm: HomeViewModel = viewModel(factory = containerFactory { HomeViewModel(it) })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(TAB_CHATS) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var muteDialog by remember { mutableStateOf(false) }
    var textStatus by remember { mutableStateOf(false) }
    var pendingMedia by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var clearCalls by remember { mutableStateOf(false) }
    var assignFolders by remember { mutableStateOf(false) }

    val chats by vm.chats.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    val selection by vm.selection.collectAsState()
    val meUser by vm.meUser.collectAsState()
    val updates by vm.updates.collectAsState()
    val groups by vm.groups.collectAsState()
    val calls by vm.calls.collectAsState()
    val myStatusViews by vm.myStatusViews.collectAsState()
    val unread by vm.unreadChats.collectAsState()
    val sync by vm.sync.collectAsState()
    val network by vm.network.collectAsState()
    val folders by vm.folders.collectAsState()
    val foldersWithCounts by vm.foldersWithCounts.collectAsState()
    val selectedFolder by vm.selectedFolder.collectAsState()
    val startCall = rememberCallStarter(onStarted = nav.openCall)

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(chatErrorText(context, it)) } }
    LaunchedEffect(Unit) { vm.statusErrors.collect { snackbar.showSnackbar(context.getString(it)) } }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pendingMedia = uri to (context.contentResolver.getType(uri)?.startsWith("video/") == true)
    }

    BackHandler(enabled = selection.isNotEmpty() || searching || tab != TAB_CHATS) {
        when {
            selection.isNotEmpty() -> vm.clearSelection()
            searching -> { searching = false; vm.query.value = "" }
            else -> tab = TAB_CHATS
        }
    }

    Scaffold(
        topBar = {
            when {
                selection.isNotEmpty() -> {
                    val flags = remember(selection, chats) { vm.selectionState() }
                    TopAppBar(
                        title = { Text(selection.size.toString(), fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) } },
                        actions = {
                            IconButton(onClick = vm::pinSelected) { Icon(Icons.Rounded.PushPin, stringResource(if (flags.allPinned) R.string.action_unpin else R.string.action_pin)) }
                            IconButton(onClick = { if (flags.allMuted) vm.muteSelected(0L) else muteDialog = true }) {
                                Icon(if (flags.allMuted) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff,
                                    stringResource(if (flags.allMuted) R.string.action_unmute else R.string.action_mute))
                            }
                            IconButton(onClick = { vm.archiveSelected(!flags.allArchived) }) {
                                Icon(if (flags.allArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                                    stringResource(if (flags.allArchived) R.string.action_unarchive else R.string.action_archive))
                            }
                            IconButton(onClick = { selectionMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) }
                            DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(if (flags.anyUnread) R.string.action_mark_read else R.string.action_mark_unread)) },
                                    onClick = { selectionMenu = false; vm.markSelected(!flags.anyUnread) })
                                DropdownMenuItem(text = { Text(stringResource(if (flags.allFavorite) R.string.action_remove_favorite else R.string.action_add_favorite)) },
                                    onClick = { selectionMenu = false; vm.favoriteSelected() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.folder_add_to)) }, leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                                    onClick = { selectionMenu = false; assignFolders = true }, modifier = Modifier.testTag("menu_add_to_folder"))
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete_chat)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { selectionMenu = false; confirmDelete = true })
                            }
                        },
                    )
                }
                searching -> {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TopAppBar(
                        title = {
                            TextField(
                                value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                                placeholder = { Text(stringResource(R.string.search_hint)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("home_search_field"),
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { searching = false; vm.query.value = "" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                        },
                        actions = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) } },
                    )
                }
                else -> TopAppBar(
                    title = {
                        // Chats shows the app name; the other tabs show their own name.
                        when (tab) {
                            TAB_CHATS -> Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag("home_title"))
                            else -> Text(stringResource(when (tab) {
                                TAB_UPDATES -> R.string.tab_updates
                                TAB_GROUPS -> R.string.tab_groups
                                else -> R.string.tab_calls
                            }), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("home_title"))
                        }
                    },
                    actions = {
                        IconButton(onClick = { tab = TAB_CHATS; searching = true }, modifier = Modifier.testTag("home_search")) {
                            Icon(Icons.Rounded.Search, stringResource(R.string.action_search))
                        }
                        IconButton(onClick = nav.openProfile) {
                            UserAvatar(meUser?.displayName ?: "", avatarUrl = meUser?.avatarUrl, size = 30.dp, colorHex = meUser?.avatarColorHex)
                        }
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("home_menu")) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Each tab has its own menu; the chat actions stay in Chats.
                            when (tab) {
                                TAB_CHATS -> {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.new_group)) }, leadingIcon = { Icon(Icons.Rounded.GroupAdd, null) },
                                        onClick = { menuOpen = false; nav.openNewGroup() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_assistant_name)) }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch { nav.openChat(vm.openAssistant(context.getString(R.string.ai_assistant_name))) }
                                        })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.starred_messages)) }, leadingIcon = { Icon(Icons.Rounded.Star, null) },
                                        onClick = { menuOpen = false; nav.openStarred() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.reminders_title)) }, leadingIcon = { Icon(Icons.Rounded.NotificationsActive, null) },
                                        onClick = { menuOpen = false; nav.openReminders() }, modifier = Modifier.testTag("menu_reminders"))
                                    DropdownMenuItem(text = { Text(stringResource(R.string.archived_chats)) }, leadingIcon = { Icon(Icons.Rounded.Archive, null) },
                                        onClick = { menuOpen = false; nav.openArchived() })
                                }
                                TAB_UPDATES -> {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.privacy_status)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                                        onClick = { menuOpen = false; nav.openStatusPrivacy() }, modifier = Modifier.testTag("menu_status_privacy"))
                                    DropdownMenuItem(text = { Text(stringResource(R.string.starred_messages)) }, leadingIcon = { Icon(Icons.Rounded.Star, null) },
                                        onClick = { menuOpen = false; nav.openStarred() })
                                }
                                TAB_CALLS -> {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.calls_clear_log)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                        enabled = calls.isNotEmpty(), onClick = { menuOpen = false; clearCalls = true }, modifier = Modifier.testTag("menu_clear_calls"))
                                }
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings_title)) }, leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                onClick = { menuOpen = false; nav.openSettings() }, modifier = Modifier.testTag("menu_settings"))
                        }
                    },
                )
            }
        },
        bottomBar = {
            // Flat bar on the same surface as the list, separated by a hairline instead of a tint.
            Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == TAB_CHATS, onClick = { tab = TAB_CHATS }, label = { Text(stringResource(R.string.tab_chats)) },
                    icon = { BadgedBox(badge = { if (unread > 0) Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text(if (unread > 99) "99+" else unread.toString()) } }) { Icon(painterResource(if (tab == TAB_CHATS) R.drawable.ic_nav_chats_filled else R.drawable.ic_nav_chats), null) } },
                    modifier = Modifier.testTag("tab_chats"),
                )
                NavigationBarItem(
                    selected = tab == TAB_UPDATES, onClick = { tab = TAB_UPDATES }, label = { Text(stringResource(R.string.tab_updates)) },
                    icon = { BadgedBox(badge = { if (updates.recent.isNotEmpty()) Badge(containerColor = MaterialTheme.colorScheme.primary) }) { Icon(painterResource(R.drawable.ic_nav_updates), null) } },
                    modifier = Modifier.testTag("tab_updates"),
                )
                NavigationBarItem(selected = tab == TAB_GROUPS, onClick = { tab = TAB_GROUPS }, label = { Text(stringResource(R.string.tab_groups)) },
                    icon = { Icon(painterResource(if (tab == TAB_GROUPS) R.drawable.ic_nav_groups_filled else R.drawable.ic_nav_groups), null) }, modifier = Modifier.testTag("tab_groups"))
                NavigationBarItem(selected = tab == TAB_CALLS, onClick = { tab = TAB_CALLS }, label = { Text(stringResource(R.string.tab_calls)) },
                    icon = { Icon(painterResource(if (tab == TAB_CALLS) R.drawable.ic_nav_calls_filled else R.drawable.ic_nav_calls), null) }, modifier = Modifier.testTag("tab_calls"))
            }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            if (selection.isEmpty() && !searching) {
                when (tab) {
                    TAB_CHATS -> FloatingActionButton(onClick = nav.openNewChat, elevation = fabElevation(), modifier = Modifier.testTag("fab_new_chat")) {
                        Icon(painterResource(R.drawable.ic_new_chat), stringResource(R.string.action_new_chat))
                    }
                    TAB_UPDATES -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SmallFloatingActionButton(onClick = { textStatus = true }, elevation = fabElevation()) { Icon(Icons.Rounded.Edit, stringResource(R.string.status_new_text)) }
                        FloatingActionButton(onClick = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, elevation = fabElevation()) {
                            Icon(Icons.Rounded.PhotoCamera, stringResource(R.string.status_new_media))
                        }
                    }
                    TAB_GROUPS -> FloatingActionButton(onClick = nav.openNewGroup, elevation = fabElevation()) { Icon(Icons.Rounded.GroupAdd, stringResource(R.string.new_group)) }
                    TAB_CALLS -> FloatingActionButton(onClick = nav.openCallPicker, elevation = fabElevation()) { Icon(Icons.Rounded.Call, stringResource(R.string.calls_new)) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                network == NetworkKind.NONE -> Banner(stringResource(R.string.banner_offline), Icons.Rounded.WifiOff,
                    MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                sync.state == SyncState.ERROR && sync.backendNotReady -> Banner(stringResource(R.string.banner_backend_not_ready), Icons.Rounded.CloudOff,
                    MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                sync.state == SyncState.ERROR -> Banner(stringResource(R.string.banner_sync_error), Icons.Rounded.Sync,
                    MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
            val listPadding = PaddingValues(bottom = 88.dp)
            when (tab) {
                TAB_CHATS -> ChatsTab(
                    ui = chats, filter = filter, query = query, selection = selection, me = vm.me,
                    onFilter = vm::selectFilter, onOpen = nav.openChat, onToggleSelect = vm::toggleSelection,
                    onOpenArchived = nav.openArchived, onSearchMessages = { nav.openSearch(query) }, onNewChat = nav.openNewChat, contentPadding = listPadding,
                    folders = folders, selectedFolder = selectedFolder, onFolder = vm::selectFolder, onManageFolders = nav.openFolders,
                    onOpenLocked = nav.openLocked,
                )
                TAB_UPDATES -> UpdatesTab(
                    ui = updates, me = meUser, onMyStatus = { nav.openStatus(vm.me) }, onAddText = { textStatus = true },
                    onOpen = nav.openStatus, onRetry = vm::retryStatus, myViews = myStatusViews, contentPadding = listPadding,
                )
                TAB_GROUPS -> GroupsTab(groups, onOpen = nav.openChat, onNewGroup = nav.openNewGroup, contentPadding = listPadding)
                TAB_CALLS -> CallsTab(calls, vm.me, onCall = startCall, onDelete = { vm.deleteCall(it) }, onOpenChat = nav.openPeer, contentPadding = listPadding)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = context.resources.getQuantityString(R.plurals.delete_chats_title, selection.size, selection.size),
            text = stringResource(R.string.delete_chats_body), confirmLabel = stringResource(R.string.action_delete), destructive = true,
            onConfirm = { confirmDelete = false; vm.deleteSelected() }, onDismiss = { confirmDelete = false },
        )
    }
    if (muteDialog) {
        MuteDialog(
            isGroup = vm.selectionState().hasActiveGroup,
            onSelect = { until -> muteDialog = false; vm.muteSelected(until) },
            onDismiss = { muteDialog = false },
        )
    }
    if (assignFolders) {
        AssignFoldersDialog(
            folders = foldersWithCounts, initiallyChecked = remember { vm.foldersOfSelection() },
            onSave = { ids -> vm.assignSelectedToFolders(ids); assignFolders = false },
            onNewFolder = { assignFolders = false; nav.openFolders() }, onDismiss = { assignFolders = false },
        )
    }
    if (clearCalls) {
        ConfirmDialog(title = stringResource(R.string.calls_clear_log), text = stringResource(R.string.calls_clear_body), confirmLabel = stringResource(R.string.action_clear),
            destructive = true, onConfirm = { clearCalls = false; vm.clearCalls() }, onDismiss = { clearCalls = false })
    }
    if (textStatus) {
        StatusTextComposer(onDismiss = { textStatus = false }, onPost = { text, bg, font -> textStatus = false; vm.postTextStatus(text, bg, font) })
    }
    pendingMedia?.let { (uri, video) ->
        MediaCaptionDialog(uri, video, stringResource(R.string.status_new_media), onDismiss = { pendingMedia = null }) { caption ->
            pendingMedia = null
            vm.postMediaStatus(uri, video, caption)
        }
    }
}

/** Low, even FAB elevation: enough to lift it off the list without a heavy shadow. */
@Composable
private fun fabElevation() = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 3.dp, focusedElevation = 3.dp, hoveredElevation = 4.dp)
