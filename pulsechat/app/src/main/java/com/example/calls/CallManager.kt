package com.example.calls

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.model.CallRecord
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
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
    val failure: String? = null,
) {
    val isLive: Boolean get() = state != CallState.IDLE && !state.isTerminal
}

/**
 * One-to-one voice/video calls: WebRTC media with Firestore signalling (`calls/{id}` + ICE
 * candidates). Every state change goes through [CallStateMachine]; nothing is simulated: a call
 * rings until the callee answers, declines, is busy, or the ring timeout expires.
 * NAT traversal uses public STUN; TURN servers can be provided in Firestore `config/webrtc`.
 */
class CallManager(
    private val context: Context,
    private val firestore: FirebaseFirestore,
    private val db: AppDatabase,
    private val calls: CallRepository,
    private val users: UserRepository,
    private val notifications: NotificationHelper,
    private val scope: CoroutineScope,
    private val currentUid: () -> String?,
) {
    private val _ui = MutableStateFlow(CallUiState())
    val ui: StateFlow<CallUiState> = _ui.asStateFlow()

    private val callDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val eglBase: EglBase by lazy { EglBase.create() }
    private val audio = CallAudio(context)

    private var factory: PeerConnectionFactory? = null
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

    private var docReg: ListenerRegistration? = null
    private var candReg: ListenerRegistration? = null
    private var incomingReg: ListenerRegistration? = null
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var callDocReady = false
    private var offer: Map<*, *>? = null
    private var timeoutJob: Job? = null
    private var reconnectJob: Job? = null

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

    private fun transition(event: CallEvent) {
        val s = _ui.value
        val next = CallStateMachine.next(s.state, s.direction, event) ?: return
        val failure = (event as? CallEvent.Failure)?.reason
        _ui.value = s.copy(
            state = next,
            connectedAt = if (next == CallState.CONNECTED && s.connectedAt == 0L) System.currentTimeMillis() else s.connectedAt,
            failure = failure ?: s.failure,
        )
        when {
            next == CallState.RINGING && s.direction == CallDirection.OUTGOING -> audio.startRingback()
            next == CallState.ACCEPTED || next == CallState.CONNECTING -> { audio.stopRingback(); timeoutJob?.cancel() }
            next == CallState.CONNECTED -> { audio.stopRingback(); timeoutJob?.cancel(); reconnectJob?.cancel() }
            next == CallState.RECONNECTING -> {
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

    suspend fun startCall(peerId: String, video: Boolean): Boolean {
        val me = currentUid() ?: return false
        if (_ui.value.isLive) return false
        if (peerId in db.contactDao().blockedIds(me)) return false
        val peer = users.getUser(peerId)
        val callId = Ids.newId("call")
        resetSession()
        _ui.value = CallUiState(callId, CallDirection.OUTGOING, peerId, peer?.displayName.orEmpty(), peer?.avatarUrl, video,
            CallState.IDLE, speakerOn = video, startedAt = System.currentTimeMillis())
        transition(CallEvent.Dial)
        CallService.start(context, video)
        return try {
            setupPeer(video, me, callId)
            val connection = pc ?: throw IllegalStateException("peer connection")
            val sdp = connection.createOfferAwait(constraints(video))
            connection.setLocalAwait(sdp)
            ref(callId).set(
                mapOf(
                    "callerId" to me, "calleeId" to peerId, "video" to video, "state" to RemoteCallState.RINGING,
                    "offer" to mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description),
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            callDocReady = true
            flushLocalCandidates(callId, me)
            scope.launch(callDispatcher) { transition(CallEvent.Published) }
            watchCallDoc(callId, me)
            watchCandidates(callId, me)
            timeoutJob = scope.launch(callDispatcher) {
                delay(CallStateMachine.RING_TIMEOUT_MS)
                if (_ui.value.callId == callId && _ui.value.state == CallState.RINGING) transition(CallEvent.RingTimeout)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "startCall failed", e)
            dispatch(CallEvent.Failure(e.message ?: "start_failed"))
            false
        }
    }

    // ------------------------------------------------------------------ incoming

    suspend fun handleIncoming(callId: String) {
        val me = currentUid() ?: return
        val doc = try { ref(callId).get().await() } catch (e: Exception) { return }
        if (!doc.exists() || doc.getString("calleeId") != me || doc.getString("state") != RemoteCallState.RINGING) return
        val callerId = doc.getString("callerId") ?: return
        val created = doc.millis("createdAt")
        if (created > 0 && System.currentTimeMillis() - created > CallStateMachine.RING_TIMEOUT_MS + 60_000) return
        if (_ui.value.callId == callId) return
        if (callerId in db.contactDao().blockedIds(me)) return
        val video = doc.getBoolean("video") ?: false
        if (_ui.value.isLive) {
            // Genuine busy signal from this device: we are already in another call.
            runCatching { ref(callId).update(mapOf("state" to RemoteCallState.BUSY, "endedAt" to FieldValue.serverTimestamp(), "endedBy" to me)) }
            recordHistory(callId, callerId, me, video, CallState.MISSED, created, 0)
            return
        }
        users.refreshProfile(callerId)
        val caller = users.getUser(callerId)
        resetSession()
        offer = doc.get("offer") as? Map<*, *>
        _ui.value = CallUiState(callId, CallDirection.INCOMING, callerId, caller?.displayName.orEmpty(), caller?.avatarUrl, video,
            CallState.IDLE, speakerOn = video, startedAt = if (created > 0) created else System.currentTimeMillis())
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
            notifications.cancelIncomingCall()
            transition(CallEvent.LocalAccept)
            CallService.start(context, s.isVideo)
            try {
                setupPeer(s.isVideo, me, callId)
                val connection = pc ?: throw IllegalStateException("peer connection")
                val o = offer ?: (ref(callId).get().await().get("offer") as? Map<*, *>) ?: throw IllegalStateException("missing offer")
                connection.setRemoteAwait(SessionDescription(SessionDescription.Type.fromCanonicalForm(o["type"] as String), o["sdp"] as String))
                remoteDescriptionSet = true
                drainRemoteCandidates()
                val answer = connection.createAnswerAwait(constraints(s.isVideo))
                connection.setLocalAwait(answer)
                ref(callId).update(
                    mapOf(
                        "state" to RemoteCallState.ACCEPTED,
                        "answer" to mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description),
                        "answeredAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
                callDocReady = true
                flushLocalCandidates(callId, me)
                watchCandidates(callId, me)
                transition(CallEvent.TransportConnecting)
            } catch (e: Exception) {
                Log.w(TAG, "accept failed", e)
                transition(CallEvent.Failure(e.message ?: "accept_failed"))
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

    fun toggleMute() {
        val muted = !_ui.value.micMuted
        audioTrack?.setEnabled(!muted)
        _ui.value = _ui.value.copy(micMuted = muted)
    }

    fun toggleSpeaker() {
        val on = !_ui.value.speakerOn
        audio.setSpeaker(on)
        _ui.value = _ui.value.copy(speakerOn = on)
    }

    fun toggleCamera() {
        val on = !_ui.value.cameraOn
        videoTrack?.setEnabled(on)
        runCatching { if (on) capturer?.startCapture(1280, 720, 30) else capturer?.stopCapture() }
        _ui.value = _ui.value.copy(cameraOn = on)
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
        _ui.value = _ui.value.copy(frontCamera = !_ui.value.frontCamera)
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
                    try {
                        pc?.setRemoteAwait(SessionDescription(SessionDescription.Type.fromCanonicalForm(answer["type"] as String), answer["sdp"] as String))
                        remoteDescriptionSet = true
                        drainRemoteCandidates()
                        transition(CallEvent.RemoteAccept)
                        transition(CallEvent.TransportConnecting)
                    } catch (e: Exception) {
                        transition(CallEvent.Failure(e.message ?: "answer_failed"))
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

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val created = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release()
        factory = created
        return created
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

    private suspend fun setupPeer(video: Boolean, me: String, callId: String) {
        val f = ensureFactory()
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = f.createPeerConnection(config, observer(callId, me)) ?: throw IllegalStateException("createPeerConnection")
        pc = connection
        val aSource = f.createAudioSource(MediaConstraints())
        audioSource = aSource
        val aTrack = f.createAudioTrack("audio0", aSource)
        audioTrack = aTrack
        connection.addTrack(aTrack, listOf("stream0"))
        if (video) startCamera(f)?.let { connection.addTrack(it, listOf("stream0")) }
        audio.start(speaker = video)
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

    private fun observer(callId: String, me: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> dispatch(CallEvent.TransportConnected)
                PeerConnection.IceConnectionState.DISCONNECTED -> dispatch(CallEvent.TransportInterrupted)
                PeerConnection.IceConnectionState.FAILED -> dispatch(CallEvent.Failure("ice_failed"))
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
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
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            scope.launch(callDispatcher) {
                remoteVideoTrack = track
                remoteSink?.let { track.addSink(it) }
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
        if (callId != null) {
            CallStateMachine.remoteStateFor(state, s.direction)?.let { remote ->
                runCatching { ref(callId).update(mapOf("state" to remote, "endedAt" to FieldValue.serverTimestamp(), "endedBy" to me)) }
            }
            val duration = if (s.connectedAt > 0) ((System.currentTimeMillis() - s.connectedAt) / 1000L).toInt() else 0
            val callerId = if (s.direction == CallDirection.OUTGOING) me.orEmpty() else s.peerId
            val receiverId = if (s.direction == CallDirection.OUTGOING) s.peerId else me.orEmpty()
            scope.launch { recordHistory(callId, callerId, receiverId, s.isVideo, state, s.startedAt, duration) }
            if (CallStateMachine.isMissed(state, s.direction)) notifications.showMissedCall(s.peerId, s.peerName, s.isVideo)
        }
        notifications.cancelIncomingCall()
        docReg?.remove(); docReg = null
        candReg?.remove(); candReg = null
        releaseMedia()
        audio.stop()
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

    private fun resetSession() {
        remoteDescriptionSet = false
        callDocReady = false
        offer = null
        pendingRemoteCandidates.clear()
        synchronized(pendingLocalCandidates) { pendingLocalCandidates.clear() }
    }

    private fun releaseMedia() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        localSink?.let { s -> runCatching { videoTrack?.removeSink(s) } }
        remoteSink?.let { s -> runCatching { remoteVideoTrack?.removeSink(s) } }
        runCatching { pc?.dispose() }
        pc = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        audioTrack = null
        videoTrack = null
        remoteVideoTrack = null
    }

    companion object { private const val TAG = "CallManager" }
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
