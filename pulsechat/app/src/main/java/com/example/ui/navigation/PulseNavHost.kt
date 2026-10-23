package com.example.ui.navigation

import com.example.ui.settings.DiagnosticsScreen

import com.example.ui.home.FoldersScreen

import com.example.ui.reminders.RemindersScreen

import com.example.ui.chat.ChatMediaScreen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.AppContainer
import com.example.data.repository.AccountRepository
import com.example.domain.CallDirection
import com.example.domain.MultiAccountPolicy
import com.example.domain.model.CallState
import com.example.notifications.NotificationHelper
import com.example.ui.calls.CallScreen
import com.example.ui.chat.ChatNav
import com.example.ui.chat.ChatScreen
import com.example.ui.contacts.PeopleNav
import com.example.ui.contacts.PeoplePickerScreen
import com.example.ui.contacts.PickMode
import com.example.ui.home.HomeNav
import com.example.ui.home.HomeScreen
import com.example.ui.info.ConversationInfoScreen
import com.example.ui.info.InfoNav
import com.example.ui.misc.ArchivedScreen
import com.example.ui.misc.LockedChatsScreen
import com.example.ui.location.LiveLocationScreen
import com.example.ui.security.ChatLockGuard
import com.example.ui.misc.GlobalSearchScreen
import com.example.ui.misc.MediaViewerScreen
import com.example.ui.misc.StarredScreen
import com.example.ui.settings.AboutScreen
import com.example.ui.settings.AiSettingsScreen
import com.example.ui.settings.BlockedUsersScreen
import com.example.ui.settings.ChatSettingsScreen
import com.example.ui.settings.NotificationSettingsScreen
import com.example.ui.settings.PrivacySettingsScreen
import com.example.ui.settings.StatusPrivacyScreen
import com.example.ui.settings.ProfileSettingsScreen
import com.example.ui.settings.SettingsHomeScreen
import com.example.ui.settings.SettingsNav
import com.example.ui.settings.StorageSettingsScreen
import com.example.ui.auth.DeviceLinkingViewModel
import com.example.ui.auth.LinkedDevicesScreen
import com.example.ui.common.containerFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.status.StatusViewerScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.example.ui.theme.LocalLiteMode
import com.example.ui.theme.Motion
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import androidx.navigation.NavController
import android.util.Log
import com.example.BuildConfig
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val CALL = "call"
    fun chat(cid: String, messageId: String? = null) = if (messageId == null) "chat/$cid" else "chat/$cid?msg=$messageId"
    fun info(cid: String) = "info/$cid"
    fun pick(mode: PickMode, cid: String? = null) = if (cid == null) "pick/${mode.name}" else "pick/${mode.name}?cid=$cid"
    fun search(q: String) = "search?q=" + android.net.Uri.encode(q)
    fun status(owner: String) = "status/$owner"
    fun media(mid: String) = "media/$mid"
    fun live(cid: String, liveId: String) = "live/$cid/$liveId"
    const val LOCKED = "locked"
}

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun PulseNavHost(container: AppContainer, pendingIntent: StateFlow<Intent?>, consumeIntent: () -> Unit) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backStack by nav.currentBackStackEntryAsState()
    val callUi by container.callManager.ui.collectAsStateWithLifecycle()
    val intent by pendingIntent.collectAsStateWithLifecycle()

    fun openPeer(peerId: String) {
        scope.launch {
            val cid = container.chatRepository.openDirectChat(peerId)
            withContext(Dispatchers.Main.immediate) { nav.go(Routes.chat(cid)) } // navigation on the main thread only
        }
    }
    /** Opens a chat at a message; an already open chat screen receives the jump through chatJumps. */
    fun openChatAt(cid: String, messageId: String?) {
        if (messageId != null) container.chatJumps.value = cid to messageId
        nav.go(Routes.chat(cid, messageId))
    }

    // Incoming calls while the app is open go straight to the call screen.
    LaunchedEffect(callUi.state, callUi.callId) {
        val ringingIn = callUi.state == CallState.RINGING && callUi.direction == CallDirection.INCOMING
        if (ringingIn && backStack?.destination?.route != Routes.CALL) nav.go(Routes.CALL)
    }
    // Notification taps / call actions.
    LaunchedEffect(intent) {
        val i = intent ?: return@LaunchedEffect
        val targetAccountUid = i.getStringExtra(NotificationHelper.EXTRA_ACCOUNT_UID)
        val route = MultiAccountPolicy.routeNotification(
            activeUid = container.authRepository.currentUid,
            notificationAccountUid = targetAccountUid,
            storedAccounts = container.accountRepository.savedAccounts.value,
        )
        when (route) {
            MultiAccountPolicy.NotificationRoute.Ignore -> {
                consumeIntent()
                return@LaunchedEffect
            }
            is MultiAccountPolicy.NotificationRoute.SwitchThenOpen -> {
                val res = container.accountRepository.switchToAccount(
                    targetUid = route.targetAccountUid,
                    isCallActive = container.callManager.ui.value.isLive,
                )
                if (res !is AccountRepository.SwitchOutcome.Switched) {
                    consumeIntent()
                    return@LaunchedEffect
                }
            }
            MultiAccountPolicy.NotificationRoute.OpenDirectly -> Unit
        }
        i.getStringExtra(NotificationHelper.EXTRA_CONVERSATION)?.let { openChatAt(it, i.getStringExtra(NotificationHelper.EXTRA_MESSAGE)) }
        i.getStringExtra(NotificationHelper.EXTRA_STATUS_OWNER)?.let { nav.go(Routes.status(it)) }
        if (i.getBooleanExtra(NotificationHelper.EXTRA_OPEN_REMINDERS, false)) nav.go("reminders")
        if (i.getStringExtra(NotificationHelper.EXTRA_OPEN_LINKED_DEVICES) != null) nav.go("settings/linked-devices")
        i.getStringExtra(NotificationHelper.EXTRA_CALL_ID)?.let { callId ->
            container.callManager.handleIncoming(callId)
            val ui = container.callManager.ui.value
            if (ui.callId == callId && ui.isLive) {
                nav.go(Routes.CALL)
                val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val camGranted = !ui.isVideo || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (i.getStringExtra(NotificationHelper.EXTRA_CALL_ACTION) == NotificationHelper.CALL_ACTION_ACCEPT && micGranted && camGranted) container.callManager.accept()
            }
        }
        if (i.getBooleanExtra(NotificationHelper.EXTRA_SHOW_CALL, false) && container.callManager.ui.value.isLive) nav.go(Routes.CALL)
        i.getStringExtra(NotificationHelper.EXTRA_OPEN_CALLS)?.let { openPeer(it) }
        consumeIntent()
    }
    NotificationPermissionRequest()

    val settingsNav = SettingsNav(back = { nav.popBackStack() }, open = { nav.go(it) })
    if (BuildConfig.DEBUG) {
        // Debug builds only: a navigation trace for diagnosing screen-flow problems from logcat.
        DisposableEffect(nav) {
            val listener = NavController.OnDestinationChangedListener { _, destination, _ -> Log.d("PulseNav", "destination=${destination.route}") }
            nav.addOnDestinationChangedListener(listener)
            onDispose { nav.removeOnDestinationChangedListener(listener) }
        }
    }
    // Short, direction-aware transitions (the default is a 700 ms cross-fade, which made every screen
    // change feel slow). Start/End follow the layout direction, so Arabic slides the other way.
    // Android 11 and older / low-memory phones: a shorter fade only, so both screens are drawn
    // together for less time (the slide costs a full redraw of both on every frame).
    val lite = LocalLiteMode.current
    // Shared elements (chat-list avatar -> chat header, message photo -> media viewer) fly between
    // destinations that provide LocalNavVisibilityScope; see ui/navigation/SharedElement.kt.
    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
    NavHost(
        navController = nav, startDestination = Routes.HOME,
        enterTransition = {
            if (lite) fadeIn(Motion.enter(Motion.NAVIGATION_LITE))
            else fadeIn(Motion.enter(Motion.NAVIGATION)) +
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, Motion.enter(Motion.NAVIGATION)) { it / 10 }
        },
        exitTransition = { fadeOut(Motion.exit(if (lite) Motion.NAVIGATION_LITE / 2 else Motion.QUICK)) },
        // Symmetric with enter: the screen underneath slides back in from the opposite edge
        // instead of only fading, so Back reads as the exact reverse of opening the screen.
        popEnterTransition = {
            if (lite) fadeIn(Motion.enter(Motion.NAVIGATION_LITE))
            else fadeIn(Motion.enter(Motion.NAVIGATION)) +
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, Motion.enter(Motion.NAVIGATION)) { it / 10 }
        },
        popExitTransition = {
            if (lite) fadeOut(Motion.exit(Motion.NAVIGATION_LITE / 2))
            else fadeOut(Motion.exit(Motion.NAVIGATION)) +
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, Motion.exit(Motion.NAVIGATION)) { it / 10 }
        },
    ) {
        composable(Routes.HOME) {
            CompositionLocalProvider(LocalNavVisibilityScope provides this) {
            HomeScreen(
                HomeNav(
                    openChat = { nav.go(Routes.chat(it)) },
                    openPeer = ::openPeer,
                    openNewChat = { nav.go(Routes.pick(PickMode.NEW_CHAT)) },
                    openNewGroup = { nav.go(Routes.pick(PickMode.NEW_GROUP)) },
                    openCallPicker = { nav.go(Routes.pick(PickMode.CALL)) },
                    openSettings = { nav.go("settings") },
                    openProfile = { nav.go("settings/profile") },
                    openStarred = { nav.go("starred") },
                    openArchived = { nav.go("archived") },
                    openSearch = { nav.go(Routes.search(it)) },
                    openStatus = { nav.go(Routes.status(it)) },
                    openStatusPrivacy = { nav.go("settings/status-privacy") },
                    openCall = { nav.go(Routes.CALL) },
                    openReminders = { nav.go("reminders") },
                    openFolders = { nav.go("folders") },
                    openLocked = { nav.go(Routes.LOCKED) },
                    openLinkedDevices = { nav.go("settings/linked-devices") },
                )
            )
            }
        }
        // Locked chats (and the list of them) open only after the phone's screen-lock check.
        composable(Routes.LOCKED) {
            ChatLockGuard(null, onBack = { nav.popBackStack() }) {
                LockedChatsScreen(onBack = { nav.popBackStack() }, openChat = { nav.go(Routes.chat(it)) })
            }
        }
        composable(
            "live/{cid}/{liveId}",
            arguments = listOf(navArgument("cid") { type = NavType.StringType }, navArgument("liveId") { type = NavType.StringType }),
        ) { entry ->
            val cid = entry.arguments?.getString("cid") ?: return@composable
            val liveId = entry.arguments?.getString("liveId") ?: return@composable
            ChatLockGuard(cid, onBack = { nav.popBackStack() }) {
                LiveLocationScreen(cid, liveId, onBack = { nav.popBackStack() })
            }
        }
        composable(
            "chat/{cid}?msg={msg}",
            arguments = listOf(navArgument("cid") { type = NavType.StringType }, navArgument("msg") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val cid = entry.arguments?.getString("cid") ?: return@composable
            CompositionLocalProvider(LocalNavVisibilityScope provides this) {
            ChatLockGuard(cid, onBack = { nav.popBackStack() }) {
                ChatScreen(cid, entry.arguments?.getString("msg"), ChatNav(
                    back = { nav.popBackStack() },
                    openInfo = { nav.go(Routes.info(it)) },
                    openMedia = { nav.go(Routes.media(it)) },
                    openCall = { nav.go(Routes.CALL) },
                    openMediaBrowser = { nav.go("chat-media/$it") },
                    // A new entry (not single-top), so Back returns to this chat.
                    openChat = { target -> if (target != cid) nav.navigate(Routes.chat(target)) },
                    openLiveLocation = { c, live -> nav.go(Routes.live(c, live)) },
                ))
            }
            }
        }
        composable("chat-media/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
            val cid = entry.arguments?.getString("cid") ?: return@composable
            ChatLockGuard(cid, onBack = { nav.popBackStack() }) {
                ChatMediaScreen(cid, onBack = { nav.popBackStack() }, openMedia = { nav.go(Routes.media(it)) },
                    showInChat = { mid -> container.chatJumps.value = cid to mid; nav.popBackStack() })
            }
        }
        composable("reminders") { RemindersScreen(onBack = { nav.popBackStack() }, openMessage = { cid, mid -> openChatAt(cid, mid) }) }
        composable("folders") { FoldersScreen(onBack = { nav.popBackStack() }) }
        composable("info/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
            val cid = entry.arguments?.getString("cid") ?: return@composable
            CompositionLocalProvider(LocalNavVisibilityScope provides this) {
            ChatLockGuard(cid, onBack = { nav.popBackStack() }) { ConversationInfoScreen(cid, InfoNav(
                back = { nav.popBackStack() },
                openChat = { target -> if (target == cid) nav.popBackStack() else nav.go(Routes.chat(target)) },
                openMedia = { nav.go(Routes.media(it)) },
                addMembers = { nav.go(Routes.pick(PickMode.ADD_MEMBERS, it)) },
                openCall = { nav.go(Routes.CALL) },
                closeChat = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )) }
            }
        }
        composable(
            "pick/{mode}?cid={cid}",
            arguments = listOf(navArgument("mode") { type = NavType.StringType }, navArgument("cid") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val mode = runCatching { PickMode.valueOf(entry.arguments?.getString("mode") ?: "") }.getOrDefault(PickMode.NEW_CHAT)
            PeoplePickerScreen(mode, entry.arguments?.getString("cid"), PeopleNav(
                back = { nav.popBackStack() },
                openChat = { cid -> nav.navigate(Routes.chat(cid)) { popUpTo(Routes.HOME); launchSingleTop = true } },
                openNewGroup = { nav.go(Routes.pick(PickMode.NEW_GROUP)) },
                openCall = { nav.go(Routes.CALL) },
            ))
        }
        composable("starred") { StarredScreen(onBack = { nav.popBackStack() }, openMessage = { cid, mid -> nav.go(Routes.chat(cid, mid)) }) }
        composable("archived") { ArchivedScreen(onBack = { nav.popBackStack() }, openChat = { nav.go(Routes.chat(it)) }) }
        composable("search?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { entry ->
            GlobalSearchScreen(entry.arguments?.getString("q").orEmpty(), onBack = { nav.popBackStack() }, openMessage = { cid, mid -> nav.go(Routes.chat(cid, mid)) })
        }
        composable(
            "status/{owner}", arguments = listOf(navArgument("owner") { type = NavType.StringType }),
            enterTransition = { fadeIn(Motion.enter(Motion.NAVIGATION)) }, popExitTransition = { fadeOut(Motion.exit(Motion.QUICK)) },
        ) { entry ->
            StatusViewerScreen(entry.arguments?.getString("owner").orEmpty(), onClose = { nav.popBackStack() }, openReply = { owner -> nav.popBackStack(); openPeer(owner) })
        }
        composable(
            "media/{mid}", arguments = listOf(navArgument("mid") { type = NavType.StringType }),
            enterTransition = { fadeIn(Motion.enter(Motion.NAVIGATION)) }, popExitTransition = { fadeOut(Motion.exit(Motion.QUICK)) },
        ) { entry ->
            val mid = entry.arguments?.getString("mid").orEmpty()
            // A photo or video of a locked chat is covered by the same check as the chat.
            val owner by produceState<String?>(null, mid) { value = container.db.messageDao().get(mid)?.conversationId.orEmpty() }
            CompositionLocalProvider(LocalNavVisibilityScope provides this) {
            owner?.let { cid ->
                if (cid.isEmpty()) MediaViewerScreen(mid, onBack = { nav.popBackStack() })
                else ChatLockGuard(cid, onBack = { nav.popBackStack() }) { MediaViewerScreen(mid, onBack = { nav.popBackStack() }) }
            }
            }
        }
        composable(
            Routes.CALL,
            enterTransition = { fadeIn(Motion.enter(Motion.NAVIGATION)) }, exitTransition = { fadeOut(Motion.exit(Motion.QUICK)) },
            popEnterTransition = { fadeIn(Motion.enter(Motion.NAVIGATION)) }, popExitTransition = { fadeOut(Motion.exit(Motion.QUICK)) },
        ) { entry ->
            CallScreen(onClose = {
                scope.launch {
                    // Pop only once navigation has finished bringing this screen up: popping an entry that
                    // is still being created (a call that ended before its screen appeared) can leave it
                    // half-initialised, which crashes the next activity recreation.
                    val state = entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.DESTROYED || it.isAtLeast(Lifecycle.State.RESUMED) }
                    if (state != Lifecycle.State.DESTROYED && nav.currentBackStackEntry?.id == entry.id) nav.popBackStack()
                }
            })
        }
        composable("settings") { SettingsHomeScreen(settingsNav) }
        composable("settings/linked-devices") {
            val linkVm: DeviceLinkingViewModel = viewModel(
                factory = containerFactory { DeviceLinkingViewModel(it.deviceLinkRepository, it.connectivity) }
            )
            LinkedDevicesScreen(
                viewModel = linkVm,
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings/profile") { ProfileSettingsScreen(settingsNav) }
        composable("settings/privacy") { PrivacySettingsScreen(settingsNav) }
        composable("settings/status-privacy") { StatusPrivacyScreen(settingsNav) }
        composable("settings/blocked") { BlockedUsersScreen(settingsNav) }
        composable("settings/chats") { ChatSettingsScreen(settingsNav) }
        composable("settings/notifications") { NotificationSettingsScreen(settingsNav) }
        composable("settings/storage") { StorageSettingsScreen(settingsNav) }
        composable("settings/ai") { AiSettingsScreen(settingsNav) }
        composable("settings/about") { AboutScreen(settingsNav) }
        composable("settings/diagnostics") { DiagnosticsScreen(settingsNav) }
    }
    }
    }
}

/** Android 13+: ask for notification permission once per process after sign-in. */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!asked && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private var asked = false
