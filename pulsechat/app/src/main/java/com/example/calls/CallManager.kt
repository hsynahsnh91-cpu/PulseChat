package com.example.calls

import android.content.Context
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.data.db.AppDatabase
import com.example.data.model.CallRecord
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.data.remote.millis
import com.example.data.repository.CallRepository
import com.example.data.repository.UserRepository
import com.example.domain.CallDirection
import com.example.domain.CallEvent
import com.example.domain.CallStateMachine
import com.example.domain.Ids
import com.example.domain.RemoteCallState
import com.example.domain.model.CallState
import com.example.notifications.NotificationHelper
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.ExternalAudioProcessingFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CallUiState(
    val callId: String? = null,
    val direction: CallDirection = CallDirection.OUTGOING,
    val peerId: String = "",
    val peerName: String = "",
    val peerAvatar: String? = null,
    val isVideo: Boolean = false,
    val state: CallState = CallState.IDLE,
    val micMuted: Boolean = false,
    val speakerOn: Boolean = false,
    val cameraOn: Boolean = true,
    val frontCamera: Boolean = true,
    val startedAt: Long = 0L,
    val connectedAt: Long = 0L,
    val remoteVideo: Boolean = false,
    /** The other side's video stopped arriving (network): the call screen shows their photo instead of a frozen frame. */
    val remoteVideoPaused: Boolean = false,
    val failure: String? = null,
) {
    val isLive: Boolean get() = state != CallState.IDLE && !state.isTerminal
}

/**
 * One-to-one voice/video calls: WebRTC media with Firestore signalling (`calls/{id}` + ICE
 * candidates). Every state change goes through [CallStateMachine]; nothing is simulated: a call
 * rings until the callee answers, declines, is busy, or the ring timeout expires.
 * NAT traversal uses public STUN; TURN servers can be provided in Firestore `config/webrtc`.
 *
 * Audio pipeline invariants (see docs/CALL_AUDIO.md):
 * - one process-wide audio device module (one microphone capture, one playout mixer);
 * - at most one peer connection, one outgoing microphone track and one camera track at a time;
 *   remote audio is played only by WebRTC's playout path - the app never routes it anywhere else;
 * - every state mutation runs on one dedicated thread, set-up is single-flight, and callbacks of a
 *   finished peer connection are ignored (session counter), so retries, reconnects, double taps or
 *   duplicate push/listener deliveries cannot stack tracks, peer connections or audio sessions;
 * - exactly one echo canceller and one noise suppressor are active, no volume or gain is ever
 *   changed by the app, and WebRTC's automatic gain control is off unless the user enables it;
 * - voice isolation ([VoiceIsolationProcessor]) only shapes our own outgoing signal, after echo
 *   cancellation, and never raises its level;
 * - automatic quality ([CallQualityPolicy]) only changes encoder limits of the existing senders
 *   (bitrate, frame rate, resolution scale, pause/resume of video): no renegotiation, no camera
 *   restart, so quality changes never interrupt the call.
 */
class CallManager(
    private val context: Context,
    private val firestore: FirebaseFirestore,
    private val db: AppDatabase,
    private val calls: CallRepository,
    private val users: UserRepository,
    private val notifications: NotificationHelper,
    private val prefs: AppPreferences,
    val diagnostics: CallDiagnostics,
    private val scope: CoroutineScope,
    private val currentUid: () -> String?,
) {
    private val _ui = MutableStateFlow(CallUiState())
    val ui: StateFlow<CallUiState> = _ui.asStateFlow()
    private val _stats = MutableStateFlow<CallStatsSnapshot?>(null)
    /** Latest WebRTC quality numbers of the live call (null when idle). */
    val stats: StateFlow<CallStatsSnapshot?> = _stats.asStateFlow()
    private val _quality = MutableStateFlow<QualityState?>(null)
    /** Video/voice quality automatically chosen for the live call (diagnostics; null when idle). */
    val quality: StateFlow<QualityState?> = _quality.asStateFlow()

    private val callDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val eglBase: EglBase by lazy { EglBase.create() }
    private val audio = CallAudio(context) { diagnostics.event(it) }
    /** Current and available audio routes of the live call. */
    val audioRoutes: StateFlow<AudioRouteState> get() = audio.routes

    private val setupMutex = Mutex()
    @Volatile private var session = 0L
    private var factoryInitialized = false
    private var factory: PeerConnectionFactory? = null
    private var factoryConfig: AudioProcessingConfig? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null
    private var audioSender: RtpSender? = null
    private var videoSender: RtpSender? = null
    private var lastRemoteFrames: Long? = null
    private var remoteStalledChecks = 0
    /** Voice isolation: one processor and one processing factory for the whole process. */
    val voiceIsolation = VoiceIsolationProcessor()
    private var voiceProcessing: ExternalAudioProcessingFactory? = null
    private var voiceProcessingUnavailable = false
    private var voiceIsolationOn = false
    /** True once the media engine was built with the voice-isolation hook (diagnostics/tests). */
    val voiceIsolationAvailable: Boolean get() = voiceProcessing != null
    private var livePeerConnections = 0
    private var liveAudioTracks = 0
    private var remoteAudioTracks = 0
    @Volatile private var recording = false

    private var docReg: ListenerRegistration? = null
    private var candReg: ListenerRegistration? = null
    private var incomingReg: ListenerRegistration? = null
    private val incomingInFlight = mutableSetOf<String>()
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var callDocReady = false
    private var answerApplying = false
    private var offer: Map<*, *>? = null
    private var timeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var statsJob: Job? = null

    init {
        // The speaker indicator follows the real route (headsets plugged in or out included).
        scope.launch(callDispatcher) {
            audio.routes.collect { r ->
                val s = _ui.value
                if (s.isLive) _ui.value = s.copy(speakerOn = r.current == AudioRoute.SPEAKER)
            }
        }
    }

    private fun ref(callId: String) = firestore.collection(Fs.CALLS).document(callId)

    // ------------------------------------------------------------------ incoming listener

    /** While signed in and the app runs, ringing calls addressed to us are picked up directly. */
    fun listenForIncoming(me: String) {
        incomingReg?.remove()
        incomingReg = firestore.collection(Fs.CALLS).whereEqualTo("calleeId", me).whereEqualTo("state", RemoteCallState.RINGING)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                snap.documentChanges.filter { it.type == DocumentChange.Type.ADDED }.forEach { ch ->
                    scope.launch(callDispatcher) { handleIncoming(ch.document.id) }
                }
            }
    }

    fun stopListening() { incomingReg?.remove(); incomingReg = null }

    // ------------------------------------------------------------------ state machine driver

    private fun dispatch(event: CallEvent) { scope.launch(callDispatcher) { transition(event) } }

    /** Events from a peer connection only apply while that connection's call is still current. */
    private fun dispatchFor(seq: Long, callId: String, event: CallEvent) {
        scope.launch(callDispatcher) {
            if (seq == session && _ui.value.callId == callId) transition(event)
            else diagnostics.event("ignored stale event ${event.javaClass.simpleName}")
        }
    }

    private fun transition(event: CallEvent) {
        val s = _ui.value
        val next = CallStateMachine.next(s.state, s.direction, event) ?: return
        val failure = (event as? CallEvent.Failure)?.reason
        _ui.value = s.copy(
            state = next,
            connectedAt = if (next == CallState.CONNECTED && s.connectedAt == 0L) System.currentTimeMillis() else s.connectedAt,
            failure = failure ?: s.failure,
        )
        diagnostics.event("state ${s.state} -> $next (${event.javaClass.simpleName}${failure?.let { ": $it" } ?: ""})")
        when {
            next == CallState.RINGING && s.direction == CallDirection.OUTGOING -> audio.startRingback()
            next == CallState.ACCEPTED || next == CallState.CONNECTING -> { audio.stopRingback(); timeoutJob?.cancel() }
            next == CallState.CONNECTED -> { audio.stopRingback(); timeoutJob?.cancel(); reconnectJob?.cancel() }
            next == CallState.RECONNECTING -> {
                diagnostics.onReconnecting()
                reconnectJob?.cancel()
                reconnectJob = scope.launch(callDispatcher) {
                    delay(CallStateMachine.RECONNECT_GRACE_MS)
                    if (_ui.value.state == CallState.RECONNECTING) transition(CallEvent.Failure("connection_lost"))
                }
            }
        }
        if (next.isTerminal) finish(next)
    }

    // ------------------------------------------------------------------ outgoing

    suspend fun startCall(peerId: String, video: Boolean): Boolean = withContext(callDispatcher) {
        val me = currentUid() ?: return@withContext false
        // Single flight: a double tap (or a call racing an incoming call) never builds a second pipeline.
        if (!setupMutex.tryLock()) { diagnostics.warn("duplicate call start ignored"); return@withContext false }
        try {
            if (_ui.value.isLive || pc != null) { diagnostics.warn("call start ignored: another call is active"); return@withContext false }
            if (peerId in db.contactDao().blockedIds(me)) return@withContext false
            val peer = users.getUser(peerId)
            if (_ui.value.isLive) return@withContext false
            val callId = Ids.newId("call")
            resetSession()
            val seq = newSession()
            _ui.value = CallUiState(callId, CallDirection.OUTGOING, peerId, peer?.displayName.orEmpty(), peer?.avatarUrl, video,
                CallState.IDLE, startedAt = System.currentTimeMillis())
            diagnostics.beginCall(callId, video, "outgoing")
            transition(CallEvent.Dial)
            CallService.start(context, video)
            try {
                setupPeer(video, me, callId, seq)
                val connection = pc ?: throw IllegalStateException("peer connection")
                // After every suspension: if the call ended meanwhile, its peer connection is disposed
                // and must not be touched again. Timeouts keep a lost callback from blocking calls.
                val sdp = withTimeoutOrNull(SDP_TIMEOUT_MS) { connection.createOfferAwait(constraints(video)) } ?: throw IllegalStateException("offer_timeout")
                if (seq != session) return@withContext false
                withTimeoutOrNull(SDP_TIMEOUT_MS) { connection.setLocalAwait(sdp) } ?: throw IllegalStateException("local_description_timeout")
                if (seq != session) return@withContext false
                withTimeoutOrNull(SIGNALLING_TIMEOUT_MS) {
                    ref(callId).set(
                        mapOf(
                            "callerId" to me, "calleeId" to peerId, "video" to video, "state" to RemoteCallState.RINGING,
                            "offer" to mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description),
                            "createdAt" to FieldValue.serverTimestamp(),
                        )
                    ).await()
                    true
                } ?: throw IllegalStateException("network_timeout")
                if (seq != session) return@withContext false // hung up meanwhile
                callDocReady = true
                flushLocalCandidates(callId, me)
                transition(CallEvent.Published)
                watchCallDoc(callId, me)
                watchCandidates(callId, me)
                timeoutJob = scope.launch(callDispatcher) {
                    delay(CallStateMachine.RING_TIMEOUT_MS)
                    if (_ui.value.callId == callId && _ui.value.state == CallState.RINGING) transition(CallEvent.RingTimeout)
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "startCall failed", e)
                if (seq == session) transition(CallEvent.Failure(e.message ?: "start_failed"))
                false
            }
        } finally {
            setupMutex.unlock()
        }
    }

    // ------------------------------------------------------------------ incoming

    /** Called by the Firestore listener, push messages and notification taps; duplicates are ignored. */
    suspend fun handleIncoming(callId: String) = withContext(callDispatcher) {
        if (!incomingInFlight.add(callId)) return@withContext
        try { handleIncomingNow(callId) } finally { incomingInFlight.remove(callId) }
    }

    private suspend fun handleIncomingNow(callId: String) {
        val me = currentUid() ?: return
        if (_ui.value.callId == callId) return
        val doc = try { ref(callId).get().await() } catch (e: Exception) { return }
        if (!doc.exists() || doc.getString("calleeId") != me || doc.getString("state") != RemoteCallState.RINGING) return
        val callerId = doc.getString("callerId") ?: return
        val created = doc.millis("createdAt")
        if (created > 0 && System.currentTimeMillis() - created > CallStateMachine.RING_TIMEOUT_MS + 60_000) return
        if (_ui.value.callId == callId) return
        if (callerId in db.contactDao().blockedIds(me)) return
        val video = doc.getBoolean("video") ?: false
        if (_ui.value.isLive || setupMutex.isLocked) {
            // Genuine busy signal from this device: we are already in another call.
            runCatching { ref(callId).update(mapOf("state" to RemoteCallState.BUSY, "endedAt" to FieldValue.serverTimestamp(), "endedBy" to me)) }
            recordHistory(callId, callerId, me, video, CallState.MISSED, created, 0)
            return
        }
        users.refreshProfile(callerId)
        val caller = users.getUser(callerId)
        if (_ui.value.isLive || _ui.value.callId == callId) return
        resetSession()
        newSession()
        offer = doc.get("offer") as? Map<*, *>
        _ui.value = CallUiState(callId, CallDirection.INCOMING, callerId, caller?.displayName.orEmpty(), caller?.avatarUrl, video,
            CallState.IDLE, startedAt = if (created > 0) created else System.currentTimeMillis())
        diagnostics.beginCall(callId, video, "incoming")
        transition(CallEvent.IncomingInvite)
        watchCallDoc(callId, me)
        notifications.showIncomingCall(callId, caller?.displayName ?: "", video)
        timeoutJob = scope.launch(callDispatcher) {
            delay(CallStateMachine.RING_TIMEOUT_MS + 5_000)
            if (_ui.value.callId == callId && _ui.value.state == CallState.RINGING) transition(CallEvent.RingTimeout)
        }
    }

    /** Accepting requires an explicit user action (button or notification action). */
    fun accept() {
        scope.launch(callDispatcher) {
            val s = _ui.value
            val callId = s.callId ?: return@launch
            if (s.direction != CallDirection.INCOMING || s.state != CallState.RINGING) return@launch
            val me = currentUid() ?: return@launch
            if (!setupMutex.tryLock()) { diagnostics.warn("duplicate accept ignored"); return@launch }
            try {
                if (pc != null) { diagnostics.warn("accept ignored: media already set up"); return@launch }
                val seq = session
                notifications.cancelIncomingCall()
                transition(CallEvent.LocalAccept)
                CallService.start(context, s.isVideo)
                try {
                    setupPeer(s.isVideo, me, callId, seq)
                    val connection = pc ?: throw IllegalStateException("peer connection")
                    val o = offer ?: (withTimeoutOrNull(SIGNALLING_TIMEOUT_MS) { ref(callId).get().await() }?.get("offer") as? Map<*, *>)
                        ?: throw IllegalStateException("missing offer")
                    if (seq != session) return@launch
                    withTimeoutOrNull(SDP_TIMEOUT_MS) {
                        connection.setRemoteAwait(SessionDescription(SessionDescription.Type.fromCanonicalForm(o["type"] as String), o["sdp"] as String))
                    } ?: throw IllegalStateException("remote_description_timeout")
                    if (seq != session) return@launch
                    remoteDescriptionSet = true
                    drainRemoteCandidates()
                    val answer = withTimeoutOrNull(SDP_TIMEOUT_MS) { connection.createAnswerAwait(constraints(s.isVideo)) } ?: throw IllegalStateException("answer_timeout")
                    if (seq != session) return@launch
                    withTimeoutOrNull(SDP_TIMEOUT_MS) { connection.setLocalAwait(answer) } ?: throw IllegalStateException("local_description_timeout")
                    if (seq != session) return@launch
                    withTimeoutOrNull(SIGNALLING_TIMEOUT_MS) {
                        ref(callId).update(
                            mapOf(
                                "state" to RemoteCallState.ACCEPTED,
                                "answer" to mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description),
                                "answeredAt" to FieldValue.serverTimestamp(),
                            )
                        ).await()
                        true
                    } ?: throw IllegalStateException("network_timeout")
                    if (seq != session) return@launch
                    callDocReady = true
                    flushLocalCandidates(callId, me)
                    watchCandidates(callId, me)
                    transition(CallEvent.TransportConnecting)
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                    if (seq == session) transition(CallEvent.Failure(e.message ?: "accept_failed"))
                }
            } finally {
                setupMutex.unlock()
            }
        }
    }

    fun decline() = scope.launch(callDispatcher) {
        val s = _ui.value
        if (s.direction == CallDirection.INCOMING && s.state == CallState.RINGING) transition(CallEvent.LocalReject)
    }

    fun hangUp() = scope.launch(callDispatcher) {
        val s = _ui.value
        when {
            s.state == CallState.INITIATING || (s.state == CallState.RINGING && s.direction == CallDirection.OUTGOING) -> transition(CallEvent.LocalCancel)
            s.state == CallState.RINGING -> transition(CallEvent.LocalReject)
            s.state.isActive -> transition(CallEvent.LocalHangup)
        }
    }

    fun onRemoteCallEnded(callId: String) {
        if (_ui.value.callId != callId) { notifications.cancelIncomingCall(); return }
        val s = _ui.value
        dispatch(if (s.state == CallState.RINGING && s.direction == CallDirection.INCOMING) CallEvent.RemoteCancel else CallEvent.RemoteHangup)
    }

    // ------------------------------------------------------------------ controls

    /** Mutes only this call's single microphone track; never touches volumes. */
    fun toggleMute() {
        scope.launch(callDispatcher) {
            val muted = !_ui.value.micMuted
            audioTrack?.setEnabled(!muted)
            _ui.value = _ui.value.copy(micMuted = muted)
            diagnostics.event("microphone ${if (muted) "muted" else "unmuted"}")
        }
    }

    fun toggleSpeaker() { audio.toggleSpeaker() }

    fun selectAudioRoute(route: AudioRoute) { audio.select(route) }

    /** Camera on/off only affects the video track; the audio pipeline is untouched. */
    fun toggleCamera() {
        scope.launch(callDispatcher) {
            val on = !_ui.value.cameraOn
            videoTrack?.setEnabled(on)
            runCatching { if (on) capturer?.startCapture(1280, 720, 30) else capturer?.stopCapture() }
            _ui.value = _ui.value.copy(cameraOn = on)
            diagnostics.event("camera ${if (on) "on" else "off"}")
        }
    }

    fun switchCamera() {
        scope.launch(callDispatcher) {
            capturer?.switchCamera(null)
            _ui.value = _ui.value.copy(frontCamera = !_ui.value.frontCamera)
        }
    }

    fun attachLocalRenderer(sink: VideoSink?) {
        localSink?.let { videoTrack?.removeSink(it) }
        localSink = sink
        sink?.let { videoTrack?.addSink(it) }
    }

    fun attachRemoteRenderer(sink: VideoSink?) {
        remoteSink?.let { remoteVideoTrack?.removeSink(it) }
        remoteSink = sink
        sink?.let { remoteVideoTrack?.addSink(it) }
    }

    /**
     * Tests only: the process-wide media engine (built if needed) with voice isolation armed, while no
     * call is live - lets an instrumented test run a local loopback through the real audio pipeline.
     */
    @VisibleForTesting
    suspend fun mediaEngineForTests(): PeerConnectionFactory = withContext(callDispatcher) {
        check(!_ui.value.isLive) { "a call is live" }
        ensureFactory(currentProcessingConfig()).also { voiceIsolationOn = startVoiceIsolation() }
    }

    /** Live WebRTC objects owned by the call engine (diagnostics/tests: both must be 0 or 1). */
    data class MediaCounts(val peerConnections: Int, val microphoneTracks: Int)

    fun mediaCounts(): MediaCounts = MediaCounts(livePeerConnections, liveAudioTracks)

    /** Clears a finished call from the UI. */
    fun dismiss() {
        if (!_ui.value.isLive) _ui.value = CallUiState()
    }

    // ------------------------------------------------------------------ signalling

    private fun watchCallDoc(callId: String, me: String) {
        docReg?.remove()
        docReg = ref(callId).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) return@addSnapshotListener
            val s = _ui.value
            if (s.callId != callId) return@addSnapshotListener
            val remoteState = snap.getString("state") ?: return@addSnapshotListener
            val endedByMe = snap.getString("endedBy") == me
            if (s.direction == CallDirection.OUTGOING && remoteState == RemoteCallState.ACCEPTED && !remoteDescriptionSet) {
                val answer = snap.get("answer") as? Map<*, *> ?: return@addSnapshotListener
                scope.launch(callDispatcher) {
                    // The listener can fire more than once; the answer is applied exactly once.
                    if (_ui.value.callId != callId || remoteDescriptionSet || answerApplying) return@launch
                    answerApplying = true
                    val seq = session
                    try {
                        val connection = pc ?: return@launch
                        withTimeoutOrNull(SDP_TIMEOUT_MS) {
                            connection.setRemoteAwait(SessionDescription(SessionDescription.Type.fromCanonicalForm(answer["type"] as String), answer["sdp"] as String))
                        } ?: throw IllegalStateException("remote_description_timeout")
                        if (seq != session) return@launch
                        remoteDescriptionSet = true
                        drainRemoteCandidates()
                        transition(CallEvent.RemoteAccept)
                        transition(CallEvent.TransportConnecting)
                    } catch (e: Exception) {
                        if (seq == session) transition(CallEvent.Failure(e.message ?: "answer_failed"))
                    }
                }
                return@addSnapshotListener
            }
            val event = CallStateMachine.eventForRemoteState(s.direction, remoteState, endedByMe) ?: return@addSnapshotListener
            if (event != CallEvent.RemoteAccept) dispatch(event)
        }
    }

    private fun watchCandidates(callId: String, me: String) {
        candReg?.remove()
        candReg = ref(callId).collection(Fs.CANDIDATES).addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            for (ch in snap.documentChanges) {
                if (ch.type != DocumentChange.Type.ADDED) continue
                val d = ch.document
                if (d.getString("from") == me) continue
                val sdp = d.getString("candidate") ?: continue
                val candidate = IceCandidate(d.getString("sdpMid"), (d.getLong("sdpMLineIndex") ?: 0L).toInt(), sdp)
                scope.launch(callDispatcher) {
                    if (_ui.value.callId != callId) return@launch
                    if (remoteDescriptionSet) pc?.addIceCandidate(candidate) else pendingRemoteCandidates += candidate
                }
            }
        }
    }

    private fun drainRemoteCandidates() {
        pendingRemoteCandidates.forEach { pc?.addIceCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    private fun sendCandidate(callId: String, me: String, c: IceCandidate) {
        ref(callId).collection(Fs.CANDIDATES).add(
            mapOf("from" to me, "candidate" to c.sdp, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "createdAt" to FieldValue.serverTimestamp())
        )
    }

    private fun flushLocalCandidates(callId: String, me: String) {
        val list = synchronized(pendingLocalCandidates) { pendingLocalCandidates.toList().also { pendingLocalCandidates.clear() } }
        list.forEach { sendCandidate(callId, me, it) }
    }

    // ------------------------------------------------------------------ media

    private fun platformAecAvailable(): Boolean = runCatching { JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported() }.getOrDefault(false)

    private fun platformNsAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching { JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported() }.getOrDefault(false)

    private fun currentProcessingConfig(): AudioProcessingConfig {
        val s = prefs.current
        return AudioProcessingPolicy.resolve(s.callEchoMode, platformAecAvailable(), platformNsAvailable(), s.callAutoGain)
    }

    /**
     * One audio device module for the whole process (one microphone capture, one playout path).
     * It is rebuilt only between calls, when the user changed the echo-canceller or gain setting.
     */
    private fun ensureFactory(config: AudioProcessingConfig): PeerConnectionFactory {
        val existing = factory
        if (existing != null && factoryConfig == config) return existing
        if (existing != null) {
            diagnostics.event("audio settings changed: rebuilding the media engine")
            runCatching { existing.dispose() }
            factory = null
            factoryConfig = null
        }
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            factoryInitialized = true
        }
        // The microphone uses the voice-communication path and playback the voice-call usage, so the
        // phone applies its voice processing and its echo canceller receives the speaker signal.
        // setUseHardwareAcousticEchoCanceler(false) makes WebRTC switch the phone's canceller off and
        // run its own, so exactly one canceller is ever active.
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setUseHardwareAcousticEchoCanceler(config.hardwareAec)
            .setUseHardwareNoiseSuppressor(config.hardwareNs)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    if (recording) diagnostics.warn("microphone capture started twice")
                    recording = true
                    diagnostics.event("microphone capture started")
                }
                override fun onWebRtcAudioRecordStop() { recording = false; diagnostics.event("microphone capture stopped") }
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() { diagnostics.event("playout started") }
                override fun onWebRtcAudioTrackStop() { diagnostics.event("playout stopped") }
            })
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) { diagnostics.warn("microphone init error: $errorMessage") }
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) {
                    diagnostics.warn("microphone start error: $errorCode $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) { diagnostics.warn("microphone error: $errorMessage") }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) { diagnostics.warn("playout init error: $errorMessage") }
                override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?, errorMessage: String?) {
                    diagnostics.warn("playout start error: $errorCode $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String?) { diagnostics.warn("playout error: $errorMessage") }
            })
            .createAudioDeviceModule()
        val builder = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
        // Voice isolation runs inside WebRTC's audio processing (after echo cancellation and noise
        // suppression). Without the hook, calls work exactly as before.
        voiceProcessingFactory()?.let { builder.setAudioProcessingFactory(it) }
        val created = builder.createPeerConnectionFactory()
        adm.release()
        factory = created
        factoryConfig = config
        return created
    }

    /** The process-wide audio processing with the voice-isolation step, or null if this build lacks the hook. */
    private fun voiceProcessingFactory(): ExternalAudioProcessingFactory? {
        voiceProcessing?.let { return it }
        if (voiceProcessingUnavailable) return null
        return try {
            ExternalAudioProcessingFactory().also {
                it.setCapturePostProcessing(voiceIsolation)
                voiceProcessing = it
            }
        } catch (t: Throwable) {
            voiceProcessingUnavailable = true
            diagnostics.event("voice isolation unavailable: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Turns voice isolation on (automatic) or off (Diagnostics switch) for the call being set up. */
    private fun startVoiceIsolation(): Boolean {
        val processing = voiceProcessing
        val available = processing != null && voiceIsolation.healthy
        val on = available && prefs.current.callVoiceIsolation
        voiceIsolation.startCall()
        if (processing != null) runCatching { processing.setBypassFlagForCapturePost(!on) }
        diagnostics.onVoiceIsolation(active = on, available = available)
        return on
    }

    /** The network the phone is on right now (kind, metered, the system's link estimates). */
    private fun callNetwork(): CallNetwork = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching CallNetwork(LinkType.OTHER)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) CallNetwork(LinkType.NONE)
        else CallNetwork(
            type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LinkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LinkType.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LinkType.CELLULAR
                else -> LinkType.OTHER
            },
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            upKbps = caps.linkUpstreamBandwidthKbps,
            downKbps = caps.linkDownstreamBandwidthKbps,
        )
    }.getOrDefault(CallNetwork(LinkType.OTHER))

    /**
     * Applies a quality step to the existing senders: voice bitrate and priority, video bitrate, frame
     * rate, resolution scale and pause/resume. Encoder limits only - nothing is renegotiated.
     */
    private fun applyQuality(next: QualityState, video: Boolean, first: Boolean) {
        _quality.value = next
        audioSender?.let { sender ->
            setEncoding(sender, "voice", null) { e ->
                e.maxBitrateBps = next.audio.maxKbps * 1000
                e.networkPriority = NETWORK_PRIORITY_HIGH
                e.bitratePriority = AUDIO_BITRATE_PRIORITY
            }
        }
        if (video) videoSender?.let { sender ->
            // Under pressure WebRTC lowers frame rate and resolution together, within these limits.
            setEncoding(sender, "video", RtpParameters.DegradationPreference.BALANCED) { e ->
                e.active = next.video.sending
                e.maxBitrateBps = next.video.maxKbps * 1000
                e.maxFramerate = next.video.maxFps
                e.scaleResolutionDownBy = next.video.scaleDown
            }
        }
        diagnostics.onQuality(next, first, video)
    }

    private fun setEncoding(sender: RtpSender, what: String, degradation: RtpParameters.DegradationPreference?, change: (RtpParameters.Encoding) -> Unit) {
        try {
            var params = sender.parameters
            params.encodings.forEach(change)
            if (degradation != null) params.degradationPreference = degradation
            if (sender.setParameters(params)) return
            if (degradation != null) {
                // A phone that refuses the degradation preference still gets the limits.
                params = sender.parameters
                params.encodings.forEach(change)
                if (sender.setParameters(params)) return
            }
            diagnostics.event("$what quality limits not applied")
        } catch (e: Exception) {
            diagnostics.event("$what quality limits failed: ${e.javaClass.simpleName}")
        }
    }

    /** One quality check (every stats interval of a connected call). */
    private fun adaptQuality(snap: CallStatsSnapshot) {
        val current = _quality.value ?: return
        val s = _ui.value
        if (s.state != CallState.CONNECTED) return
        val next = CallQualityPolicy.next(current, QualitySample(snap.availableOutKbps, snap.sendLossPercent, snap.rttMs), callNetwork(), snap.at, s.isVideo)
        if (next.video != current.video || next.audio != current.audio) applyQuality(next, s.isVideo, first = false)
        else _quality.value = next
    }

    /** No new frame from the other side for a while: show their photo instead of a frozen picture. */
    private fun watchRemoteVideo(snap: CallStatsSnapshot) {
        val s = _ui.value
        if (!s.isVideo || !s.remoteVideo || s.state != CallState.CONNECTED) return
        remoteStalledChecks = RemoteVideoWatch.stalledChecks(lastRemoteFrames, snap.inboundVideoFramesDecoded, remoteStalledChecks)
        lastRemoteFrames = snap.inboundVideoFramesDecoded
        val paused = RemoteVideoWatch.paused(remoteStalledChecks)
        if (paused != s.remoteVideoPaused) {
            _ui.value = s.copy(remoteVideoPaused = paused)
            diagnostics.event(if (paused) "remote video paused (no frames)" else "remote video resumed")
        }
    }

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer()
        )
        val config = withTimeoutOrNull(2_500) { runCatching { firestore.collection("config").document("webrtc").get().await() }.getOrNull() }
        (config?.get("iceServers") as? List<*>)?.forEach { entry ->
            val m = entry as? Map<*, *> ?: return@forEach
            val urls = (m["urls"] as? List<*>)?.mapNotNull { it as? String } ?: listOfNotNull(m["urls"] as? String)
            if (urls.isEmpty()) return@forEach
            val b = PeerConnection.IceServer.builder(urls)
            (m["username"] as? String)?.let { b.setUsername(it) }
            (m["credential"] as? String)?.let { b.setPassword(it) }
            servers += b.createIceServer()
        }
        return servers
    }

    private suspend fun setupPeer(video: Boolean, me: String, callId: String, seq: Long) {
        if (pc != null || audioTrack != null) {
            diagnostics.warn("leftover media found before set-up: released")
            releaseMedia(endSession = false) // keep the current call's session valid
        }
        val config = currentProcessingConfig()
        val f = ensureFactory(config)
        diagnostics.onConfig(config, platformAecAvailable(), platformNsAvailable())
        // Voice-call mode, focus and route first, so the microphone/speaker streams open on the voice path.
        audio.start(video)
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        if (seq != session) throw IllegalStateException("call ended during set-up")
        val connection = f.createPeerConnection(rtcConfig, observer(callId, me, seq)) ?: throw IllegalStateException("createPeerConnection")
        pc = connection
        livePeerConnections++
        remoteAudioTracks = 0
        val aSource = f.createAudioSource(audioConstraints(config))
        audioSource = aSource
        val aTrack = f.createAudioTrack("audio0", aSource)
        audioTrack = aTrack
        liveAudioTracks++
        aTrack.setEnabled(!_ui.value.micMuted)
        audioSender = connection.addTrack(aTrack, listOf("stream0"))
        if (video) startCamera(f)?.let { videoSender = connection.addTrack(it, listOf("stream0")) }
        voiceIsolationOn = startVoiceIsolation()
        // First quality from the network the phone is actually on; the stats checks take over from there.
        applyQuality(CallQualityPolicy.initial(callNetwork(), System.currentTimeMillis()), video, first = true)
        diagnostics.event("media ready: peerConnections=$livePeerConnections micTracks=$liveAudioTracks cameraTrack=${videoTrack != null}")
        if (livePeerConnections > 1 || liveAudioTracks > 1) diagnostics.warn("more than one peer connection or microphone track")
        startStats(seq)
    }

    /**
     * Echo cancellation and noise suppression on (WebRTC disables its own implementation when the
     * phone's is in use), the high-pass filter on, automatic gain control only if the user enabled it.
     */
    private fun audioConstraints(config: AudioProcessingConfig) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", if (config.autoGain) "true" else "false"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    }

    private fun startCamera(f: PeerConnectionFactory): VideoTrack? {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return null
        val cap = enumerator.createCapturer(name, null) ?: return null
        val helper = SurfaceTextureHelper.create("CallCapture", eglBase.eglBaseContext)
        val source = f.createVideoSource(cap.isScreencast)
        cap.initialize(helper, context, source.capturerObserver)
        cap.startCapture(1280, 720, 30)
        capturer = cap
        surfaceHelper = helper
        videoSource = source
        val track = f.createVideoTrack("video0", source)
        videoTrack = track
        localSink?.let { track.addSink(it) }
        return track
    }

    private fun constraints(video: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"))
    }

    private fun startStats(seq: Long) {
        statsJob?.cancel()
        statsJob = scope.launch(callDispatcher) {
            var n = 0
            while (seq == session) {
                delay(STATS_INTERVAL_MS)
                val connection = pc ?: break
                if (!_ui.value.state.isActive) continue
                val entries = connection.statsAwait() ?: continue
                if (seq != session) break
                val snap = CallStatsParser.parse(entries, System.currentTimeMillis())
                _stats.value = snap
                diagnostics.onStats(snap)
                adaptQuality(snap)
                watchRemoteVideo(snap)
                if (n++ % 5 == 0) diagnostics.event(diagnostics.statsLine(snap))
            }
        }
    }

    private fun observer(callId: String, me: String, seq: Long) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            diagnostics.event("ice connection: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> dispatchFor(seq, callId, CallEvent.TransportConnected)
                PeerConnection.IceConnectionState.DISCONNECTED -> dispatchFor(seq, callId, CallEvent.TransportInterrupted)
                PeerConnection.IceConnectionState.FAILED -> dispatchFor(seq, callId, CallEvent.Failure("ice_failed"))
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            if (seq != session) return
            if (callDocReady) sendCandidate(callId, me, candidate)
            else synchronized(pendingLocalCandidates) { pendingLocalCandidates += candidate }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            val kind = track.kind()
            scope.launch(callDispatcher) {
                if (seq != session) return@launch
                diagnostics.event("remote $kind track received")
                // Remote audio is rendered by WebRTC's single playout path; nothing else is attached to it.
                if (kind == "audio" && ++remoteAudioTracks > 1) diagnostics.warn("more than one remote audio track")
                val videoTrack = track as? VideoTrack ?: return@launch
                remoteVideoTrack?.let { old -> if (old !== videoTrack) { remoteSink?.let { runCatching { old.removeSink(it) } }; diagnostics.warn("remote video track replaced") } }
                remoteVideoTrack = videoTrack
                remoteSink?.let { videoTrack.addSink(it) }
                _ui.value = _ui.value.copy(remoteVideo = true)
            }
        }
    }

    // ------------------------------------------------------------------ teardown & history

    private fun finish(state: CallState) {
        val s = _ui.value
        val me = currentUid()
        val callId = s.callId
        timeoutJob?.cancel()
        reconnectJob?.cancel()
        val duration = if (s.connectedAt > 0) ((System.currentTimeMillis() - s.connectedAt) / 1000L).toInt() else 0
        if (callId != null) {
            CallStateMachine.remoteStateFor(state, s.direction)?.let { remote ->
                runCatching { ref(callId).update(mapOf("state" to remote, "endedAt" to FieldValue.serverTimestamp(), "endedBy" to me)) }
            }
            val callerId = if (s.direction == CallDirection.OUTGOING) me.orEmpty() else s.peerId
            val receiverId = if (s.direction == CallDirection.OUTGOING) s.peerId else me.orEmpty()
            scope.launch { recordHistory(callId, callerId, receiverId, s.isVideo, state, s.startedAt, duration) }
            if (CallStateMachine.isMissed(state, s.direction)) notifications.showMissedCall(s.peerId, s.peerName, s.isVideo)
        }
        notifications.cancelIncomingCall()
        docReg?.remove(); docReg = null
        candReg?.remove(); candReg = null
        val route = audio.routes.value.current
        val voice = if (voiceIsolationOn) voiceIsolation.stats() else null
        releaseMedia()
        audio.stop()
        diagnostics.endCall(s.isVideo, state.name, duration, route, voice)
        CallService.stop(context)
        scope.launch(callDispatcher) {
            delay(2_000)
            if (_ui.value.callId == callId && _ui.value.state.isTerminal) _ui.value = CallUiState()
        }
    }

    private suspend fun recordHistory(callId: String, callerId: String, receiverId: String, video: Boolean, state: CallState, startedAt: Long, duration: Int) {
        val callerName = users.getUser(callerId)?.displayName.orEmpty()
        val receiverName = users.getUser(receiverId)?.displayName.orEmpty()
        calls.record(CallRecord(callId, callerId, callerName, receiverId, receiverName, video, state, if (startedAt > 0) startedAt else System.currentTimeMillis(), duration))
    }

    private fun newSession(): Long { session += 1; return session }

    private fun resetSession() {
        remoteDescriptionSet = false
        answerApplying = false
        callDocReady = false
        offer = null
        pendingRemoteCandidates.clear()
        synchronized(pendingLocalCandidates) { pendingLocalCandidates.clear() }
    }

    /** Disposes everything the call created; afterwards no callback of the old connection is applied. */
    private fun releaseMedia(endSession: Boolean = true) {
        if (endSession) session += 1
        statsJob?.cancel()
        statsJob = null
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        localSink?.let { s -> runCatching { videoTrack?.removeSink(s) } }
        remoteSink?.let { s -> runCatching { remoteVideoTrack?.removeSink(s) } }
        val hadPc = pc != null
        runCatching { pc?.dispose() }
        if (hadPc) livePeerConnections--
        pc = null
        // Our own handles of the tracks (the peer connection's senders held separate ones).
        audioTrack?.let { t -> runCatching { t.dispose() }; liveAudioTracks-- }
        audioTrack = null
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        remoteVideoTrack = null
        remoteAudioTracks = 0
        // The senders belong to the peer connection (disposed with it).
        audioSender = null
        videoSender = null
        lastRemoteFrames = null
        remoteStalledChecks = 0
        voiceIsolationOn = false
        _quality.value = null
        _stats.value = null
        if (hadPc) diagnostics.event("media released: peerConnections=$livePeerConnections micTracks=$liveAudioTracks")
    }

    companion object {
        private const val TAG = "CallManager"
        private const val STATS_INTERVAL_MS = 2_000L
        private const val SDP_TIMEOUT_MS = 15_000L
        private const val SIGNALLING_TIMEOUT_MS = 20_000L
        /** org.webrtc.Priority.HIGH: voice packets first on the network (and in the bandwidth split). */
        private const val NETWORK_PRIORITY_HIGH = 3
        private const val AUDIO_BITRATE_PRIORITY = 4.0
    }
}

private suspend fun PeerConnection.statsAwait(): List<StatEntry>? = withTimeoutOrNull(3_000) {
    suspendCancellableCoroutine { cont ->
        getStats(RTCStatsCollectorCallback { report ->
            if (cont.isActive) cont.resume(report.statsMap.values.map { StatEntry(it.type, it.members) })
        })
    }
}

// ---------------------------------------------------------------------- SDP helpers

private suspend fun PeerConnection.createOfferAwait(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) { if (desc != null) cont.resume(desc) else cont.resumeWithException(IllegalStateException("null offer")) }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "createOffer")) }
            override fun onSetFailure(error: String?) = Unit
        }, constraints)
    }

private suspend fun PeerConnection.createAnswerAwait(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) { if (desc != null) cont.resume(desc) else cont.resumeWithException(IllegalStateException("null answer")) }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "createAnswer")) }
            override fun onSetFailure(error: String?) = Unit
        }, constraints)
    }

private suspend fun PeerConnection.setLocalAwait(desc: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
    setLocalDescription(object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) = Unit
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "setLocalDescription")) }
    }, desc)
}

private suspend fun PeerConnection.setRemoteAwait(desc: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
    setRemoteDescription(object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) = Unit
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "setRemoteDescription")) }
    }, desc)
}
