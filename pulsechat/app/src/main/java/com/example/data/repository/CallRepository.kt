package com.example.data.repository

import com.example.data.db.CallDao
import com.example.data.db.MessageDao
import com.example.data.model.CallRecord
import com.example.data.model.CallState
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.MessageType
import com.example.data.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ActiveCall(
    val callId: String,
    val callerId: String,
    val callerName: String,
    val receiverId: String,
    val receiverName: String,
    val isVideo: Boolean,
    val state: CallState,
    val isIncoming: Boolean
)

class CallRepository(
    private val callDao: CallDao,
    private val messageDao: MessageDao,
    private val scope: CoroutineScope
) {
    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall.asStateFlow()

    private val _callDuration = MutableStateFlow(0)
    val callDuration: StateFlow<Int> = _callDuration.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private var timerJob: Job? = null
    private var simulationJob: Job? = null



    fun startOutgoingCall(
        callerId: String,
        callerName: String,
        receiverId: String,
        receiverName: String,
        isVideo: Boolean
    ) {
        val callId = "call_" + UUID.randomUUID().toString()
        val call = ActiveCall(
            callId = callId,
            callerId = callerId,
            callerName = callerName,
            receiverId = receiverId,
            receiverName = receiverName,
            isVideo = isVideo,
            state = CallState.CALLING,
            isIncoming = false
        )
        _activeCall.value = call
        _callDuration.value = 0
        _isMuted.value = false
        _isVideoEnabled.value = isVideo

        // State progression: CALLING -> RINGING -> auto-answer simulation if target is online
        simulationJob?.cancel()
        simulationJob = scope.launch(Dispatchers.IO) {
            delay(1500)
            if (_activeCall.value?.callId == callId) {
                _activeCall.value = _activeCall.value?.copy(state = CallState.RINGING)
            }
            delay(2500)
            // Simulated receiver acceptance
            if (_activeCall.value?.callId == callId && _activeCall.value?.state == CallState.RINGING) {
                acceptCall()
            }
        }
    }

    fun simulateIncomingCall(caller: User, receiverId: String, isVideo: Boolean) {
        val callId = "call_" + UUID.randomUUID().toString()
        _activeCall.value = ActiveCall(
            callId = callId,
            callerId = caller.userId,
            callerName = caller.displayName,
            receiverId = receiverId,
            receiverName = "أنت",
            isVideo = isVideo,
            state = CallState.RINGING,
            isIncoming = true
        )
        _callDuration.value = 0
    }

    fun acceptCall() {
        val current = _activeCall.value ?: return
        _activeCall.value = current.copy(state = CallState.ACCEPTED)
        startTimer()
    }

    fun rejectCall() {
        val current = _activeCall.value ?: return
        simulationJob?.cancel()
        timerJob?.cancel()
        _activeCall.value = current.copy(state = CallState.REJECTED)

        scope.launch(Dispatchers.IO) {
            callDao.insertCall(
                CallRecord(
                    callId = current.callId,
                    callerId = current.callerId,
                    callerName = current.callerName,
                    receiverId = current.receiverId,
                    receiverName = current.receiverName,
                    isVideo = current.isVideo,
                    state = CallState.REJECTED,
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 0
                )
            )
            delay(1200)
            _activeCall.value = null
        }
    }

    fun endCall() {
        val current = _activeCall.value ?: return
        simulationJob?.cancel()
        timerJob?.cancel()
        val duration = _callDuration.value
        _activeCall.value = current.copy(state = CallState.ENDED)

        scope.launch(Dispatchers.IO) {
            callDao.insertCall(
                CallRecord(
                    callId = current.callId,
                    callerId = current.callerId,
                    callerName = current.callerName,
                    receiverId = current.receiverId,
                    receiverName = current.receiverName,
                    isVideo = current.isVideo,
                    state = CallState.ENDED,
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = duration
                )
            )
            delay(1000)
            _activeCall.value = null
            _callDuration.value = 0
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        _callDuration.value = 0
        timerJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                _callDuration.value += 1
            }
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun toggleSpeaker() {
        _isSpeakerOn.value = !_isSpeakerOn.value
    }

    fun toggleVideo() {
        _isVideoEnabled.value = !_isVideoEnabled.value
    }
}
