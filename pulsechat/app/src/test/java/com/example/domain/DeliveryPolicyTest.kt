package com.example.domain

import com.example.domain.model.MessageStatus.DELIVERED
import com.example.domain.model.MessageStatus.FAILED
import com.example.domain.model.MessageStatus.PENDING
import com.example.domain.model.MessageStatus.READ
import com.example.domain.model.MessageStatus.SENDING
import com.example.domain.model.MessageStatus.SENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryPolicyTest {
    @Test fun statesOnlyMoveForward() {
        assertEquals(SENT, DeliveryPolicy.advance(SENDING, SENT))
        assertEquals(READ, DeliveryPolicy.advance(DELIVERED, READ))
        assertEquals(READ, DeliveryPolicy.advance(READ, SENT))        // late "sent" cannot undo "read"
        assertEquals(DELIVERED, DeliveryPolicy.advance(DELIVERED, SENDING))
        assertEquals(SENDING, DeliveryPolicy.advance(PENDING, SENDING))
    }

    @Test fun failureOnlyReplacesUnacknowledgedStates() {
        assertEquals(FAILED, DeliveryPolicy.advance(PENDING, FAILED))
        assertEquals(FAILED, DeliveryPolicy.advance(SENDING, FAILED))
        assertEquals(SENT, DeliveryPolicy.advance(SENT, FAILED))
        assertEquals(READ, DeliveryPolicy.advance(READ, FAILED))
    }

    @Test fun retryOrLateAckReplacesFailure() {
        assertEquals(SENDING, DeliveryPolicy.advance(FAILED, SENDING))
        assertEquals(SENT, DeliveryPolicy.advance(FAILED, SENT))
        assertTrue(DeliveryPolicy.isAwaitingServer(PENDING))
        assertFalse(DeliveryPolicy.isAwaitingServer(SENT))
    }

    @Test fun directReceipts() {
        val r = listOf("bob")
        assertEquals(SENT, DeliveryPolicy.fromReceipts(100, r, mapOf("bob" to 99), emptyMap()))
        assertEquals(DELIVERED, DeliveryPolicy.fromReceipts(100, r, mapOf("bob" to 100), mapOf("bob" to 50)))
        assertEquals(READ, DeliveryPolicy.fromReceipts(100, r, mapOf("bob" to 100), mapOf("bob" to 150)))
        // a read watermark implies delivery even if the delivered marker lags behind
        assertEquals(READ, DeliveryPolicy.fromReceipts(100, r, emptyMap(), mapOf("bob" to 100)))
    }

    @Test fun groupReceiptsRequireEveryRecipient() {
        val r = listOf("bob", "carol")
        assertEquals(SENT, DeliveryPolicy.fromReceipts(100, r, mapOf("bob" to 200), emptyMap()))
        assertEquals(DELIVERED, DeliveryPolicy.fromReceipts(100, r, mapOf("bob" to 200, "carol" to 100), mapOf("bob" to 200)))
        assertEquals(READ, DeliveryPolicy.fromReceipts(100, r, emptyMap(), mapOf("bob" to 200, "carol" to 101)))
        assertEquals(SENT, DeliveryPolicy.fromReceipts(100, emptyList(), emptyMap(), emptyMap()))
    }

    @Test fun recipientStateForMessageInfo() {
        assertEquals(READ, DeliveryPolicy.recipientState(10, 0, 10))
        assertEquals(DELIVERED, DeliveryPolicy.recipientState(10, 12, 5))
        assertEquals(SENT, DeliveryPolicy.recipientState(10, 9, 9))
    }
}
