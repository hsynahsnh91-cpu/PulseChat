package com.example

import android.app.Application
import android.util.Log
import com.example.calls.CallDiagnostics
import com.example.calls.CallManager
import com.example.data.db.AppDatabase
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.prefs.AppPreferences
import com.example.data.repository.AiRepository
import com.example.data.repository.AuthRepository
import com.example.data.repository.AutoReplyManager
import com.example.data.repository.CallRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ChatSettingsRepository
import com.example.data.repository.LiveLocationRepository
import com.example.data.repository.DiagnosticsLog
import com.example.data.repository.FolderRepository
import com.example.data.repository.ReminderRepository
import com.example.data.repository.StorageManager
import com.example.data.repository.ScheduledMessageRepository
import com.example.data.repository.TranslationRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.SessionState
import com.example.data.repository.StatusRepository
import com.example.data.repository.SyncManager
import com.example.data.repository.UserRepository
import com.example.domain.AutoDownloadPolicy
import com.example.domain.NetworkKind
import com.example.media.VoicePlayer
import com.example.notifications.NotificationHelper
import com.example.notifications.PushTokenManager
import com.example.security.AppLockManager
import com.example.util.AppForeground
import com.example.util.ConnectivityMonitor
import com.example.util.EmulatorConfig
import com.example.util.ServerClock
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manual dependency container (application scoped). */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = AppPreferences(app)
    val db: AppDatabase = AppDatabase.getDatabase(app)
    val connectivity = ConnectivityMonitor(app)
    val serverClock = ServerClock()

    /** False when google-services.json is missing: the UI then shows a configuration error. */
    val firebaseAvailable: Boolean = FirebaseApp.getApps(app).isNotEmpty()

    val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(FirebaseApp.getInstance(), app.getString(R.string.firestore_database_id)).also { fs ->
            EmulatorConfig.host?.let { fs.useEmulator(it, EmulatorConfig.FIRESTORE_PORT) }
        }
    }
    private val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance().also { a -> EmulatorConfig.host?.let { a.useEmulator(it, EmulatorConfig.AUTH_PORT) } }
    }
    private val storage: FirebaseStorage by lazy {
        FirebaseStorage.getInstance().also { st -> EmulatorConfig.host?.let { st.useEmulator(it, EmulatorConfig.STORAGE_PORT) } }
    }

    val media = MediaRepository(app, { storage }, { firestore }, prefs, { authRepository.currentUid })
    /** Video editor: cuts without re-encoding, filmstrip frames and covers. */
    val videoTrimmer get() = media.videoTrimmer
    /** Settings › Storage: what PulseChat keeps on this phone, and clearing temporary files. */
    val storageManager by lazy { StorageManager(app, db, media) }
    val authRepository: AuthRepository by lazy {
        AuthRepository(auth, firestore, db, prefs, FirebaseApp.getInstance().options.projectId ?: "pulsechat", appScope)
    }
    private val uid: () -> String? = { authRepository.currentUid }
    val userRepository by lazy { UserRepository(firestore, db, prefs, media, serverClock, appScope, uid) }
    val syncManager by lazy { SyncManager(firestore, db, userRepository, prefs, serverClock, appScope, uid) }
    val chatRepository by lazy { ChatRepository(firestore, db, syncManager, media, userRepository, prefs, connectivity, serverClock, appScope, uid) }
    val statusRepository by lazy { StatusRepository(firestore, db, media, userRepository, prefs, appScope, uid) }
    val callRepository by lazy { CallRepository(db) }
    val aiRepository by lazy { AiRepository() }
    val notifications by lazy { NotificationHelper(app, prefs, db) }
    val pushTokens by lazy { PushTokenManager(firestore, prefs, appScope, uid) }
    val callDiagnostics by lazy { CallDiagnostics(prefs, BuildConfig.DEBUG) }
    val callManager by lazy { CallManager(app, firestore, db, callRepository, userRepository, notifications, prefs, callDiagnostics, appScope, uid) }
    val voicePlayer by lazy { VoicePlayer(app) }
    val diagnosticsLog by lazy { DiagnosticsLog(db, appScope) }
    val scheduledMessages by lazy { ScheduledMessageRepository(app, db, chatRepository, diagnosticsLog, uid) }
    val reminders by lazy { ReminderRepository(app, db, notifications, diagnosticsLog, uid) }
    val folders by lazy { FolderRepository(db, uid) }
    val translations by lazy { TranslationRepository(db) }
    val appLock by lazy { AppLockManager(prefs) { callManager.ui.value.isLive } }
    /** Per-chat notification settings and chat locks (kept apart from the conversations themselves). */
    val chatSettings by lazy { ChatSettingsRepository(db, syncManager, uid) }
    val liveLocation by lazy { LiveLocationRepository(app, { firestore }, chatRepository, serverClock, uid) }
    val autoReply by lazy { AutoReplyManager(app) }

    /** "Show in chat" requests (conversationId to messageId) for an already open chat screen. */
    val chatJumps = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)

    private var backgroundJob: Job? = null

    fun initialize() {
        notifications.createChannels()
        if (!firebaseAvailable) {
            Log.e(TAG, "Firebase is not configured (google-services.json missing)")
            return
        }
        installAppCheck()
        chatRepository.diagnostics = diagnosticsLog
        chatRepository.onLiveLocationDeleted = { liveId -> liveLocation.onMessageDeleted(liveId) }
        syncManager.diagnostics = diagnosticsLog
        authRepository.addSignOutHook { id -> onSignedOut(id) }
        syncManager.onIncoming = { conv, messages -> handleIncoming(conv, messages) }

        appScope.launch {
            authRepository.session.collect { s ->
                when (s) {
                    is SessionState.SignedIn -> onSignedIn(s.uid)
                    SessionState.SignedOut -> stopRealtime()
                    else -> Unit
                }
            }
        }
        appScope.launch {
            AppForeground.inForeground.collect { foreground ->
                if (foreground) {
                    backgroundJob?.cancel()
                    connectivity.refresh()
                    // Read before the app lock forgets that the user is returning from PulseChat's own picker.
                    chatSettings.onAppForeground(appLock.isReturningFromOwnScreen())
                    appLock.onForeground()
                    uid()?.let { startRealtime(it) }
                } else {
                    appLock.onBackground()
                    chatSettings.onAppBackground()
                    voicePlayer.stop()
                    backgroundJob?.cancel()
                    // Keep listeners briefly (e.g. app switching), then rely on push messages.
                    backgroundJob = appScope.launch {
                        delay(BACKGROUND_GRACE_MS)
                        if (!AppForeground.inForeground.value && !callManager.ui.value.isLive) stopRealtime()
                    }
                }
            }
        }
        appScope.launch {
            connectivity.network.collect { kind ->
                if (kind != NetworkKind.NONE && uid() != null) runCatching { chatRepository.resendPending() }
            }
        }
        appScope.launch {
            while (true) {
                delay(60_000)
                if (uid() != null) {
                    runCatching { chatRepository.purgeExpired() }
                    runCatching { statusRepository.purgeExpired() }
                }
            }
        }
    }

    private fun installAppCheck() {
        runCatching {
            val appCheck = FirebaseAppCheck.getInstance()
            // The fast test build ("optimized": debug key, not debuggable) is installed outside Play
            // like the debug build, so it attests the same way.
            if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "optimized") appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
            else appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }.onFailure { Log.w(TAG, "App Check not installed: ${it.message}") }
    }

    private suspend fun onSignedIn(id: String) {
        runCatching { chatSettings.importLegacyMutes(id) }
        // A live location whose service did not survive a restart is ended, never resumed silently.
        liveLocation.endIfOrphaned()
        startRealtime(id)
        userRepository.startPresence()
        pushTokens.registerCurrentToken()
        userRepository.pullOwnSettings()
        runCatching { chatRepository.resendPending() }
        runCatching { chatRepository.purgeExpired() }
        // Idempotent: re-queues anything a force-stop or an update may have dropped.
        runCatching { scheduledMessages.rescheduleAll() }
        runCatching { reminders.rescheduleAll() }
    }

    private fun startRealtime(id: String) {
        if (!syncManager.isRunning) {
            syncManager.start(id)
            statusRepository.start(id)
        }
        callManager.listenForIncoming(id)
    }

    private fun stopRealtime() {
        syncManager.stop()
        statusRepository.stop()
        callManager.stopListening()
    }

    private suspend fun onSignedOut(id: String) {
        callManager.hangUp()
        liveLocation.stop(LiveLocationRepository.StopReason.SIGNED_OUT)
        chatSettings.closeLockedChats()
        runCatching { scheduledMessages.clearForSignOut(id) }
        runCatching { reminders.clearForSignOut(id) }
        userRepository.goOffline(id)
        userRepository.stopPresence()
        pushTokens.unregister(id)
        stopRealtime()
        notifications.cancelAllMessages()
    }

    private suspend fun handleIncoming(conv: Conversation, messages: List<Message>) {
        val me = uid() ?: return
        val settings = prefs.current
        messages.filter { it.type.isMedia && it.localMediaPath == null && it.mediaUrl != null }.forEach { m ->
            if (AutoDownloadPolicy.shouldAutoDownload(m.type, connectivity.network.value, settings.autoDownload, m.mediaSizeBytes)) {
                appScope.launch { chatRepository.downloadMedia(m.messageId) }
            }
        }
        val visible = syncManager.activeConversationId == conv.conversationId && AppForeground.inForeground.value
        if (!visible) notifications.showMessages(conv, messages, me)
        maybeAutoReply(conv, messages, me)
    }

    /** User-configured away message (direct chats only, with a per-contact cooldown). */
    private suspend fun maybeAutoReply(conv: Conversation, messages: List<Message>, me: String) {
        if (conv.isGroup || conv.isAiConversation || !autoReply.isEnabled.value) return
        val last = messages.lastOrNull { it.senderId != me } ?: return
        if (!autoReply.shouldAutoReplyTo(last.senderId, userRepository.isContact(last.senderId))) return
        autoReply.recordAutoReplySent(last.senderId)
        var text = autoReply.customMessage.value
        if (autoReply.isAiEnabled.value && prefs.current.aiConsent && last.content.isNotBlank()) {
            text = runCatching {
                "🤖 " + aiRepository.complete(
                    "Write a short, polite reply (same language as the message) saying I'm unavailable and will answer later. Message:\n${last.content}",
                    "You write brief away-message replies on behalf of the user. Never make commitments or share personal data.",
                )
            }.getOrDefault(text)
        }
        runCatching { chatRepository.sendText(conv.conversationId, text, null, emptyList()) }
    }

    companion object {
        private const val TAG = "AppContainer"
        private const val BACKGROUND_GRACE_MS = 120_000L
    }
}
