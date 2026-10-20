package com.example.domain

import com.example.domain.model.MessageStatus

/** Rules for advancing message delivery state. States only move forward on real events. */
object DeliveryPolicy {
    private fun rank(status: MessageStatus): Int = when (status) {
        MessageStatus.PENDING -> 0
        MessageStatus.SENDING, MessageStatus.FAILED -> 1
        MessageStatus.SENT -> 2
        MessageStatus.DELIVERED -> 3
        MessageStatus.READ -> 4
    }

    /**
     * Merges an observed state into the current one.
     * - Normal states never move backwards (an out-of-order "sent" event cannot undo "read").
     * - FAILED may only replace PENDING/SENDING (a late failure cannot undo a server ack).
     * - Any state may replace FAILED (user retry, or the server turned out to have the message).
     */
    fun advance(current: MessageStatus, next: MessageStatus): MessageStatus = when {
        current == next -> current
        next == MessageStatus.FAILED ->
            if (current == MessageStatus.PENDING || current == MessageStatus.SENDING) MessageStatus.FAILED else current
        current == MessageStatus.FAILED -> next
        rank(next) > rank(current) -> next
        else -> current
    }

    fun isAwaitingServer(status: MessageStatus): Boolean = status == MessageStatus.PENDING || status == MessageStatus.SENDING

    /**
     * State of an acknowledged outgoing message derived from recipients' receipt watermarks
     * (the newest message timestamp each recipient has delivered/read). In groups every
     * recipient must have delivered/read the message, as in WhatsApp.
     */
    fun fromReceipts(
        messageCreatedAt: Long,
        recipients: Collection<String>,
        deliveredUpTo: Map<String, Long>,
        readUpTo: Map<String, Long>,
    ): MessageStatus {
        if (recipients.isEmpty()) return MessageStatus.SENT
        if (recipients.all { (readUpTo[it] ?: 0L) >= messageCreatedAt }) return MessageStatus.READ
        val delivered = recipients.all { maxOf(deliveredUpTo[it] ?: 0L, readUpTo[it] ?: 0L) >= messageCreatedAt }
        return if (delivered) MessageStatus.DELIVERED else MessageStatus.SENT
    }

    /** Per-recipient view used by the "Message info" screen. */
    fun recipientState(messageCreatedAt: Long, deliveredUpTo: Long, readUpTo: Long): MessageStatus = when {
        readUpTo >= messageCreatedAt -> MessageStatus.READ
        maxOf(deliveredUpTo, readUpTo) >= messageCreatedAt -> MessageStatus.DELIVERED
        else -> MessageStatus.SENT
    }
}
