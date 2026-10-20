package com.example.domain

import com.example.domain.model.CallState

enum class CallDirection { OUTGOING, INCOMING }

/** Events that may change a call's state. Only real signalling/media events exist: nothing auto-accepts. */
sealed class CallEvent {
    object Dial : CallEvent()                 // local user starts an outgoing call
    object Published : CallEvent()            // call document + offer written; callee can be notified
    object IncomingInvite : CallEvent()       // a ringing call addressed to us was received
    object LocalAccept : CallEvent()          // the local user tapped "Accept"
    object RemoteAccept : CallEvent()         // the callee accepted (answer written)
    object LocalReject : CallEvent()
    object RemoteReject : CallEvent()
    object RemoteBusy : CallEvent()           // the callee's device reported it is in another call
    object RingTimeout : CallEvent()
    object LocalCancel : CallEvent()          // caller hung up before the answer
    object RemoteCancel : CallEvent()         // the caller hung up before we answered
    object TransportConnecting : CallEvent()
    object TransportConnected : CallEvent()
    object TransportInterrupted : CallEvent()
    object LocalHangup : CallEvent()
    object RemoteHangup : CallEvent()
    data class Failure(val reason: String) : CallEvent()
}

/** Remote call-document states written by the signalling layer (Firestore `calls/{id}.state`). */
object RemoteCallState {
    const val RINGING = "ringing"
    const val ACCEPTED = "accepted"
    const val REJECTED = "rejected"
    const val BUSY = "busy"
    const val CANCELLED = "cancelled"
    const val MISSED = "missed"
    const val ENDED = "ended"
    const val FAILED = "failed"
    val TERMINAL = setOf(REJECTED, BUSY, CANCELLED, MISSED, ENDED, FAILED)
}

object CallStateMachine {
    const val RING_TIMEOUT_MS = 45_000L
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val RECONNECT_GRACE_MS = 20_000L

    /** Returns the next state, or null when the event is not valid in the current state (ignored). */
    fun next(state: CallState, direction: CallDirection, event: CallEvent): CallState? {
        if (state.isTerminal) return null
        if (event is CallEvent.Failure) return if (state == CallState.IDLE) null else CallState.FAILED
        return when (state) {
            CallState.IDLE -> when {
                event == CallEvent.Dial && direction == CallDirection.OUTGOING -> CallState.INITIATING
                event == CallEvent.IncomingInvite && direction == CallDirection.INCOMING -> CallState.RINGING
                else -> null
            }
            CallState.INITIATING -> when (event) {
                CallEvent.Published -> CallState.RINGING
                CallEvent.LocalCancel -> CallState.CANCELLED
                CallEvent.RemoteBusy -> CallState.BUSY
                else -> null
            }
            CallState.RINGING -> if (direction == CallDirection.OUTGOING) {
                when (event) {
                    CallEvent.RemoteAccept -> CallState.ACCEPTED
                    CallEvent.RemoteReject -> CallState.REJECTED
                    CallEvent.RemoteBusy -> CallState.BUSY
                    CallEvent.RingTimeout -> CallState.MISSED
                    CallEvent.LocalCancel -> CallState.CANCELLED
                    else -> null
                }
            } else {
                when (event) {
                    CallEvent.LocalAccept -> CallState.ACCEPTED
                    CallEvent.LocalReject -> CallState.REJECTED
                    CallEvent.RemoteCancel, CallEvent.RingTimeout -> CallState.MISSED
                    else -> null
                }
            }
            CallState.ACCEPTED -> when (event) {
                CallEvent.TransportConnecting -> CallState.CONNECTING
                CallEvent.TransportConnected -> CallState.CONNECTED
                CallEvent.LocalHangup, CallEvent.RemoteHangup -> CallState.ENDED
                else -> null
            }
            CallState.CONNECTING -> when (event) {
                CallEvent.TransportConnected -> CallState.CONNECTED
                CallEvent.LocalHangup, CallEvent.RemoteHangup -> CallState.ENDED
                else -> null
            }
            CallState.CONNECTED -> when (event) {
                CallEvent.TransportInterrupted -> CallState.RECONNECTING
                CallEvent.LocalHangup, CallEvent.RemoteHangup -> CallState.ENDED
                else -> null
            }
            CallState.RECONNECTING -> when (event) {
                CallEvent.TransportConnected -> CallState.CONNECTED
                CallEvent.LocalHangup, CallEvent.RemoteHangup -> CallState.ENDED
                else -> null
            }
            else -> null // legacy states never occur in a live call
        }
    }

    /** Translates a remote call-document state into an event for this side of the call. */
    fun eventForRemoteState(direction: CallDirection, remoteState: String, endedByMe: Boolean): CallEvent? {
        if (endedByMe) return null
        return when (direction) {
            CallDirection.OUTGOING -> when (remoteState) {
                RemoteCallState.ACCEPTED -> CallEvent.RemoteAccept
                RemoteCallState.REJECTED -> CallEvent.RemoteReject
                RemoteCallState.BUSY -> CallEvent.RemoteBusy
                RemoteCallState.ENDED -> CallEvent.RemoteHangup
                RemoteCallState.FAILED -> CallEvent.Failure("remote_failed")
                else -> null
            }
            CallDirection.INCOMING -> when (remoteState) {
                RemoteCallState.CANCELLED -> CallEvent.RemoteCancel
                RemoteCallState.MISSED -> CallEvent.RingTimeout
                RemoteCallState.ENDED -> CallEvent.RemoteHangup
                RemoteCallState.FAILED -> CallEvent.Failure("remote_failed")
                else -> null
            }
        }
    }

    /** Remote state to publish when this side reaches a terminal state, or null if nothing to publish. */
    fun remoteStateFor(state: CallState, direction: CallDirection): String? = when (state) {
        CallState.ACCEPTED -> if (direction == CallDirection.INCOMING) RemoteCallState.ACCEPTED else null
        CallState.REJECTED -> if (direction == CallDirection.INCOMING) RemoteCallState.REJECTED else null
        CallState.BUSY -> if (direction == CallDirection.INCOMING) RemoteCallState.BUSY else null
        CallState.CANCELLED -> RemoteCallState.CANCELLED
        CallState.MISSED -> if (direction == CallDirection.OUTGOING) RemoteCallState.MISSED else null
        CallState.ENDED -> RemoteCallState.ENDED
        CallState.FAILED -> RemoteCallState.FAILED
        else -> null
    }

    /** Whether the call should appear as "missed" in the callee's history. */
    fun isMissed(state: CallState, direction: CallDirection): Boolean =
        direction == CallDirection.INCOMING && state == CallState.MISSED
}
