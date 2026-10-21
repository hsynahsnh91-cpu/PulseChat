package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.ScheduledMessage
import com.example.data.model.ScheduledState
import com.example.domain.Ids
import com.example.domain.ScheduledPolicy
import com.example.work.ScheduledWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class ScheduleException(val error: ScheduledPolicy.Error) : Exception(error.name)

/**
 * Scheduled text messages. Timing is handled by WorkManager (survives restarts and reboots, waits
 * for a network connection, retries with back-off). Exactly-once delivery is guaranteed by:
 * - a fixed message ID per scheduled message (also the Firestore document ID);
 * - an atomic Room "claim" (PENDING -> SENDING) so only one attempt runs at a time on this device;
 * - [ChatRepository.sendScheduledText], which checks the server before writing and relies on the
 *   rules rejecting a second create of the same document.
 * Clock changes cannot cause an early or duplicate send: an attempt that runs before the scheduled
 * wall-clock time re-queues itself, and the time-change receiver re-queues all pending messages.
 */
class ScheduledMessageRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val chats: ChatRepository,
    private val diagnostics: DiagnosticsLog,
    private val currentUid: () -> String?,
) {
    private val dao get() = db.scheduledMessageDao()

    enum class DeliverResult { DONE, RETRY }

    fun observeForConversation(cid: String): Flow<List<ScheduledMessage>> = dao.observeForConversation(cid)

    fun observeActive(): Flow<List<ScheduledMessage>> = currentUid()?.let { dao.observeActive(it) } ?: emptyFlow()

    fun observeCount(state: String): Flow<Int> = dao.observeCount(state)

    suspend fun schedule(cid: String, text: String, at: Long): ScheduledMessage {
        val me = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
        val now = System.currentTimeMillis()
        ScheduledPolicy.validate(text, at, now)?.let { throw ScheduleException(it) }
        chats.checkCanSchedule(cid)
        val message = ScheduledMessage(
            id = Ids.newMessageId(), ownerId = me, conversationId = cid, text = text.trim(),
            scheduledAt = at, createdAt = now, updatedAt = now,
        )
        dao.insert(message)
        ScheduledWork.enqueue(context, message.id, at)
        return message
    }

    /** False when the message is already being sent (or was sent) and can no longer change. */
    suspend fun edit(id: String, text: String, at: Long): Boolean {
        val now = System.currentTimeMillis()
        ScheduledPolicy.validate(text, at, now)?.let { throw ScheduleException(it) }
        val changed = dao.updateContent(id, text.trim(), at, now) == 1
        if (changed) ScheduledWork.enqueue(context, id, at)
        return changed
    }

    /** Deletes a pending/failed scheduled message. False when it is being sent right now. */
    suspend fun cancel(id: String): Boolean {
        val deleted = dao.deleteIfEditable(id) == 1
        if (deleted) ScheduledWork.cancel(context, id)
        return deleted
    }

    /** "Send now" / "Retry": due immediately; the claim still guarantees a single attempt. */
    suspend fun sendNow(id: String): Boolean {
        val now = System.currentTimeMillis()
        if (dao.reschedule(id, now, now) != 1) return false
        ScheduledWork.enqueue(context, id, now)
        return true
    }

    /** Called by [com.example.work.ScheduledMessageWorker]. */
    suspend fun deliver(id: String): DeliverResult {
        val m = dao.get(id) ?: return DeliverResult.DONE // deleted by the user
        if (m.state == ScheduledState.SENT || m.state == ScheduledState.FAILED) return DeliverResult.DONE
        val now = System.currentTimeMillis()
        if (!ScheduledPolicy.isDue(m.scheduledAt, now)) {
            // Ran early (e.g. the clock was set back): wait for the real time.
            ScheduledWork.enqueue(context, id, m.scheduledAt)
            return DeliverResult.DONE
        }
        if (dao.claim(id, now, now - ScheduledPolicy.STALE_SENDING_MS) != 1) return DeliverResult.DONE // another attempt owns it
        val outcome = try {
            chats.sendScheduledText(m.id, m.conversationId, m.text, m.ownerId)
        } catch (e: Exception) {
            dao.release(id, "error", System.currentTimeMillis())
            throw e
        }
        val done = System.currentTimeMillis()
        return when (outcome.result) {
            ChatRepository.ScheduledResult.SENT, ChatRepository.ScheduledResult.ALREADY_SENT -> {
                dao.markSent(id, done)
                DeliverResult.DONE
            }
            ChatRepository.ScheduledResult.FAILED -> {
                dao.markFailed(id, outcome.reason, done)
                diagnostics.record(DiagnosticsLog.SCHEDULE, "schedule_failed", outcome.reason)
                DeliverResult.DONE
            }
            ChatRepository.ScheduledResult.RETRY -> {
                if (m.attempts + 1 >= ScheduledPolicy.MAX_ATTEMPTS) {
                    dao.markFailed(id, outcome.reason ?: "too_many_attempts", done)
                    diagnostics.record(DiagnosticsLog.SCHEDULE, "schedule_failed", "too_many_attempts")
                    DeliverResult.DONE
                } else {
                    dao.release(id, outcome.reason, done)
                    diagnostics.record(DiagnosticsLog.SCHEDULE, "schedule_retry", outcome.reason)
                    DeliverResult.RETRY
                }
            }
        }
    }

    /** After boot, app update or a clock/time-zone change: queue every pending message again. */
    suspend fun rescheduleAll() {
        dao.active().forEach { ScheduledWork.enqueue(context, it.id, it.scheduledAt) }
        dao.deleteSentBefore(System.currentTimeMillis() - SENT_RETENTION_MS)
    }

    /** Signing out cancels the account's unsent scheduled messages (they must never go out under another account). */
    suspend fun clearForSignOut(owner: String) {
        dao.unsentFor(owner).forEach { ScheduledWork.cancel(context, it.id) }
        dao.deleteUnsentFor(owner)
    }

    companion object {
        private const val SENT_RETENTION_MS = 7L * 24 * 3_600_000L
    }
}
