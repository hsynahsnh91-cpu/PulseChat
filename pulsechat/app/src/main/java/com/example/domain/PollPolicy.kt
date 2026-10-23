package com.example.domain

import com.example.domain.model.MessageStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Policy for Advanced Polls (Feature 3):
 * - Anonymous voting: voter identities are hidden from all chat participants and the poll creator.
 *   Votes in anonymous polls are keyed by a deterministic SHA-256 voter token
 *   `sha256("poll:" + messageId + ":" + uid)` enforced by Firestore security rules so each account
 *   can vote at most once while participant UIDs are not exposed in the poll's vote map.
 * - Poll expiration: optional end timestamp (`expiresAtMillis`); expired polls reject new votes both
 *   in the UI and in `firestore.rules` (using server `request.time`).
 * - Editing polls: the poll creator may edit the poll question and options within the edit window
 *   (`15 minutes`) only while the poll is still open and before any votes have been cast, so
 *   existing votes are never corrupted or misattributed.
 */
object PollPolicy {
    const val MAX_QUESTION_LENGTH = 300
    const val MAX_OPTION_LENGTH = 100
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 12
    const val EDIT_WINDOW_MS = MessagePolicy.EDIT_WINDOW_MS

    /** Standard duration options for poll expiration (seconds): None, 15m, 1h, 24h, 7d. */
    val DURATION_OPTIONS_SECONDS: List<Long> = listOf(0L, 900L, 3_600L, 86_400L, 604_800L)

    fun expiresAt(createdAtMs: Long, durationSeconds: Long): Long =
        if (durationSeconds <= 0L) 0L else createdAtMs + durationSeconds * 1000L

    fun isExpired(pollExpiresAtMs: Long, nowMs: Long): Boolean =
        pollExpiresAtMs in 1..nowMs

    /**
     * Deterministic voter key for `poll.votes`:
     * - Non-anonymous poll: the voter's `uid` (so participants can view who voted for which option).
     * - Anonymous poll: `sha256("poll:<messageId>:<uid>")` lowercase hex (matches `firestore.rules`
     *   `hashing.sha256(('poll:' + mid + ':' + uid()).toUtf8()).toHexString().lower()`).
     */
    fun voterKey(messageId: String, uid: String, anonymous: Boolean): String {
        if (!anonymous) return uid
        val input = "poll:$messageId:$uid".toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Resolves the current user's selected option index in [votesMap], supporting both anonymous and public polls. */
    fun myVote(messageId: String, uid: String, anonymous: Boolean, votesMap: Map<String, Int>): Int? {
        if (uid.isBlank()) return null
        val key = voterKey(messageId, uid, anonymous)
        return votesMap[key] ?: votesMap[uid]
    }

    fun canVote(
        isDeleted: Boolean,
        isAwaitingServerFromMe: Boolean,
        pollExpiresAtMs: Long,
        nowMs: Long,
        optionIndex: Int,
        optionCount: Int,
    ): Boolean {
        if (isDeleted || isAwaitingServerFromMe) return false
        if (optionIndex !in 0 until optionCount) return false
        if (isExpired(pollExpiresAtMs, nowMs)) return false
        return true
    }

    /**
     * A poll can be edited only by its creator, within [EDIT_WINDOW_MS], after server acknowledgement,
     * before expiration, and before any vote has been cast (`totalVotes == 0`).
     */
    fun canEditPoll(
        isMine: Boolean,
        status: MessageStatus,
        createdAtMs: Long,
        pollExpiresAtMs: Long,
        totalVotes: Int,
        deletedForEveryone: Boolean,
        nowMs: Long,
    ): Boolean {
        val acked = status in setOf(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ)
        return isMine &&
            acked &&
            !deletedForEveryone &&
            totalVotes == 0 &&
            !isExpired(pollExpiresAtMs, nowMs) &&
            createdAtMs > 0L &&
            nowMs - createdAtMs < EDIT_WINDOW_MS
    }

    fun cleanOptions(options: List<String>): List<String> =
        options.map { it.trim().replace("|", "/").take(MAX_OPTION_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_OPTIONS)

    fun isValidPoll(question: String, options: List<String>): Boolean =
        question.trim().isNotEmpty() &&
            question.trim().length <= MAX_QUESTION_LENGTH &&
            cleanOptions(options).size >= MIN_OPTIONS
}
