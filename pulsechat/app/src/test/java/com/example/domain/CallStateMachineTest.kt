package com.example.domain

import com.example.domain.CallDirection.INCOMING
import com.example.domain.CallDirection.OUTGOING
import com.example.domain.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {
    private fun run(direction: CallDirection, vararg events: CallEvent): CallState {
        var state = CallState.IDLE
        for (e in events) state = CallStateMachine.next(state, direction, e) ?: state
        return state
    }

    @Test fun outgoingHappyPath() {
        assertEquals(CallState.CONNECTED, run(OUTGOING, CallEvent.Dial, CallEvent.Published, CallEvent.RemoteAccept,
            CallEvent.TransportConnecting, CallEvent.TransportConnected))
        assertEquals(CallState.ENDED, run(OUTGOING, CallEvent.Dial, CallEvent.Published, CallEvent.RemoteAccept,
            CallEvent.TransportConnected, CallEvent.TransportInterrupted, CallEvent.TransportConnected, CallEvent.LocalHangup))
    }

    @Test fun neverAutoAccepts() {
        // An outgoing call cannot be accepted locally and stays ringing without a remote answer.
        assertNull(CallStateMachine.next(CallState.RINGING, OUTGOING, CallEvent.LocalAccept))
        assertEquals(CallState.RINGING, run(OUTGOING, CallEvent.Dial, CallEvent.Published, CallEvent.TransportConnected))
        // An incoming call cannot be accepted by the remote side.
        assertNull(CallStateMachine.next(CallState.RINGING, INCOMING, CallEvent.RemoteAccept))
        assertNull(CallStateMachine.next(CallState.IDLE, INCOMING, CallEvent.Dial))
    }

    @Test fun terminalOutcomes() {
        assertEquals(CallState.REJECTED, run(OUTGOING, CallEvent.Dial, CallEvent.Published, CallEvent.RemoteReject))
        assertEquals(CallState.BUSY, run(OUTGOING, CallEvent.Dial, CallEvent.Published, CallEvent.RemoteBusy))
        assertEquals(CallState.MISSED, run(OUTGOING, CallEvent.Dial, CallEvent.Published, CallEvent.RingTimeout))
        assertEquals(CallState.CANCELLED, run(OUTGOING, CallEvent.Dial, CallEvent.LocalCancel))
        assertEquals(CallState.MISSED, run(INCOMING, CallEvent.IncomingInvite, CallEvent.RemoteCancel))
        assertEquals(CallState.REJECTED, run(INCOMING, CallEvent.IncomingInvite, CallEvent.LocalReject))
        assertEquals(CallState.FAILED, run(INCOMING, CallEvent.IncomingInvite, CallEvent.LocalAccept, CallEvent.Failure("ice")))
        assertTrue(CallStateMachine.isMissed(CallState.MISSED, INCOMING))
        assertFalse(CallStateMachine.isMissed(CallState.MISSED, OUTGOING))
    }

    @Test fun terminalStatesAbsorbEvents() {
        for (s in CallState.entries.filter { it.isTerminal }) {
            assertNull(CallStateMachine.next(s, OUTGOING, CallEvent.RemoteAccept))
            assertNull(CallStateMachine.next(s, INCOMING, CallEvent.Failure("x")))
        }
    }

    @Test fun remoteStateMapping() {
        assertEquals(CallEvent.RemoteAccept, CallStateMachine.eventForRemoteState(OUTGOING, RemoteCallState.ACCEPTED, false))
        assertEquals(CallEvent.RemoteCancel, CallStateMachine.eventForRemoteState(INCOMING, RemoteCallState.CANCELLED, false))
        assertNull(CallStateMachine.eventForRemoteState(INCOMING, RemoteCallState.ENDED, endedByMe = true))
        assertNull(CallStateMachine.eventForRemoteState(INCOMING, RemoteCallState.ACCEPTED, false))
        assertEquals(RemoteCallState.ACCEPTED, CallStateMachine.remoteStateFor(CallState.ACCEPTED, INCOMING))
        assertNull(CallStateMachine.remoteStateFor(CallState.ACCEPTED, OUTGOING))
        assertEquals(RemoteCallState.CANCELLED, CallStateMachine.remoteStateFor(CallState.CANCELLED, OUTGOING))
    }
}
