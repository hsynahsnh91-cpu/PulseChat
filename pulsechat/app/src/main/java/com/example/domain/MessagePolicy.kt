package com.example.domain

import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import java.util.Calendar
import java.util.TimeZone

/**
 * Which actions a message supports. The time windows mirror firestore.rules, which is the real
 * enforcement point (the client check only hides actions the server would reject).
 */
object MessagePolicy {
    const val EDIT_WINDOW_MS = 15 * 60 * 1000L
    const val DELETE_FOR_EVERYONE_WINDOW_MS = 48 * 60 * 60 * 1000L
    const val MAX_TEXT_LENGTH = 4096

    data class Facts(
        val isMine: Boolean,
        val type: MessageType,
        val status: MessageStatus,
        val createdAt: Long,
        val deletedForEveryone: Boolean,
        val hasText: Boolean = true,
        val isAiConversation: Boolean = false,
    )

    private val acked = setOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ)
    private val nonInteractive = setOf(MessageType.SYSTEM, MessageType.CALL_EVENT)

    fun canEdit(f: Facts, now: Long): Boolean =
        f.isMine && f.type == MessageType.TEXT && !f.deletedForEveryone && f.status in acked &&
            !f.isAiConversation && f.createdAt > 0 && now - f.createdAt < EDIT_WINDOW_MS

    fun canDeleteForEveryone(f: Facts, now: Long): Boolean =
        f.isMine && !f.deletedForEveryone && f.status in acked && f.type !in nonInteractive &&
            !f.isAiConversation && f.createdAt > 0 && now - f.createdAt < DELETE_FOR_EVERYONE_WINDOW_MS

    fun canReact(f: Facts): Boolean =
        !f.deletedForEveryone && f.type !in nonInteractive && !f.isAiConversation &&
            (!f.isMine || f.status in acked)

    fun canReply(f: Facts): Boolean = !f.deletedForEveryone && f.type !in nonInteractive

    fun canForward(f: Facts): Boolean =
        !f.deletedForEveryone && f.type !in nonInteractive && f.type != MessageType.POLL &&
            (!f.isMine || f.status != MessageStatus.FAILED)

    fun canCopy(f: Facts): Boolean = !f.deletedForEveryone && f.hasText && f.type !in nonInteractive

    fun canStar(f: Facts): Boolean = !f.deletedForEveryone && f.type != MessageType.SYSTEM

    fun canRetry(f: Facts): Boolean = f.isMine && f.status == MessageStatus.FAILED

    fun canShowInfo(f: Facts): Boolean = f.isMine && !f.deletedForEveryone && f.type !in nonInteractive && !f.isAiConversation

    enum class TextError { EMPTY, TOO_LONG }

    fun validateText(text: String): TextError? = when {
        text.isBlank() -> TextError.EMPTY
        text.length > MAX_TEXT_LENGTH -> TextError.TOO_LONG
        else -> null
    }
}

/** Ordering, grouping and unread-separator logic for the conversation timeline. */
object MessageTimeline {
    const val GROUP_WINDOW_MS = 5 * 60 * 1000L

    data class Entry(val id: String, val senderId: String, val createdAt: Long, val type: MessageType)

    /** Stable display order: server/creation time, then ID as a deterministic tie-breaker. */
    fun <T> ordered(items: List<T>, createdAt: (T) -> Long, id: (T) -> String): List<T> =
        items.sortedWith(compareBy<T>({ createdAt(it) }, { id(it) }))

    /** Drops duplicate deliveries of the same message ID, keeping the most recently updated copy. */
    fun <T> deduplicate(items: List<T>, id: (T) -> String, updatedAt: (T) -> Long): List<T> =
        items.groupBy(id).values.map { copies -> copies.maxByOrNull(updatedAt)!! }

    fun isSameDay(a: Long, b: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val ca = Calendar.getInstance(zone).apply { timeInMillis = a }
        val cb = Calendar.getInstance(zone).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** Consecutive messages from the same sender within a few minutes are visually grouped. */
    fun isGroupedWithPrevious(previous: Entry?, current: Entry, zone: TimeZone = TimeZone.getDefault()): Boolean {
        if (previous == null) return false
        if (previous.type == MessageType.SYSTEM || current.type == MessageType.SYSTEM) return false
        return previous.senderId == current.senderId &&
            current.createdAt - previous.createdAt in 0..GROUP_WINDOW_MS &&
            isSameDay(previous.createdAt, current.createdAt, zone)
    }

    /** Index (in ascending order) of the first message from someone else newer than the read watermark, or -1. */
    fun firstUnreadIndex(ascending: List<Entry>, myId: String, lastReadAt: Long): Int =
        ascending.indexOfFirst { it.senderId != myId && it.type != MessageType.SYSTEM && it.createdAt > lastReadAt }

    /** Out-of-order protection: only apply a remote copy that is at least as new as the stored one. */
    fun shouldApplyRemote(localUpdatedAt: Long, remoteUpdatedAt: Long): Boolean = remoteUpdatedAt >= localUpdatedAt
}
