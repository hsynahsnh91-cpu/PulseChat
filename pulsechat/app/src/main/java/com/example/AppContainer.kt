package com.example

import android.app.Application
import android.util.Log
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
        FirebaseFirestore.getInstance(FirebaseApp.getInstance(), app.getString(R.string.firestore_database_id))
    }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    val media = MediaRepository(app) { FirebaseStorage.getInstance() }
    val authRepository: AuthRepository by lazy {
        AuthRepository(auth, firestore, db, prefs, FirebaseApp.getInstance().options.projectId ?: "pulsechat", appScope)
    }
    private val uid: () -> String? = { authRepository.currentUid }
    val userRepository by lazy { UserRepository(firestore, db, prefs, media, serverClock, appScope, uid) }
    val syncManager by lazy { SyncManager(firestore, db, userRepository, prefs, serverClock, appScope) }
    val chatRepository by lazy { ChatRepository(firestore, db, syncManager, media, userRepository, prefs, connectivity, serverClock, appScope, uid) }
    val statusRepository by lazy { StatusRepository(firestore, db, media, userRepository, prefs, appScope, uid) }
    val callRepository by lazy { CallRepository(db) }
    val aiRepository by lazy { AiRepository() }
    val notifications by lazy { NotificationHelper(app, prefs, db) }
    val pushTokens by lazy { PushTokenManager(firestore, prefs, appScope, uid) }
    val callManager by lazy { CallManager(app, firestore, db, callRepository, userRepository, notifications, appScope, uid) }
    val voicePlayer by lazy { VoicePlayer(app) }
    val appLock by lazy { AppLockManager(prefs) }
    val autoReply by lazy { AutoReplyManager(app) }

    private var backgroundJob: Job? = null

    fun initialize() {
        notifications.createChannels()
        if (!firebaseAvailable) {
            Log.e(TAG, "Firebase is not configured (google-services.json missing)")
            return
        }
        installAppCheck()
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
                    appLock.onForeground()
                    uid()?.let { startRealtime(it) }
                } else {
                    appLock.onBackground()
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
            if (BuildConfig.DEBUG) appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
            else appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }.onFailure { Log.w(TAG, "App Check not installed: ${it.message}") }
    }

    private suspend fun onSignedIn(id: String) {
        startRealtime(id)
        userRepository.startPresence()
        pushTokens.registerCurrentToken()
        userRepository.pullOwnSettings()
        runCatching { chatRepository.resendPending() }
        runCatching { chatRepository.purgeExpired() }
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
