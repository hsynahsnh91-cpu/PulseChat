package com.example.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.data.db.AppDatabase
import com.example.data.model.Message
import com.example.data.model.Reminder
import com.example.data.model.ReminderSource
import com.example.domain.Ids
import com.example.domain.TextTools
import com.example.notifications.NotificationHelper
import com.example.notifications.ReminderReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Schedules reminder notifications with AlarmManager (RTC_WAKEUP, allowed while idle).
 * With the "Alarms & reminders" permission the alarm is exact; without it Android may deliver it
 * a few minutes late (the screen explains this and links to the setting). Alarms are re-registered
 * after reboot, app update and clock/time-zone changes by [com.example.notifications.TimeChangeReceiver].
 */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun schedule(id: String, at: Long) {
        val pi = pendingIntent(id)
        try {
            if (canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked between the check and the call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(id: String) = alarms.cancel(pendingIntent(id))

    private fun pendingIntent(id: String): PendingIntent {
        // A per-reminder data URI makes every PendingIntent distinct (extras alone do not).
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("pulsechat://reminder/$id"))
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ReminderRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val notifications: NotificationHelper,
    private val diagnostics: DiagnosticsLog,
    private val currentUid: () -> String?,
) {
    private val dao get() = db.reminderDao()
    val scheduler = ReminderScheduler(context)

    fun observeUpcoming(): Flow<List<Reminder>> = currentUid()?.let { dao.observeUpcoming(it) } ?: emptyFlow()
    fun observePast(): Flow<List<Reminder>> = currentUid()?.let { dao.observePast(it) } ?: emptyFlow()
    fun observeForMessages(ids: List<String>): Flow<List<Reminder>> = dao.observeForMessages(ids)
    fun observeScheduledCount(): Flow<Int> = dao.observeScheduledCount()

    suspend fun createForMessage(m: Message, at: Long): Reminder {
        val preview = TextTools.preview(notifications.previewFor(m).ifBlank { m.fileName.orEmpty() }, 140)
        return create(preview, m.conversationId, m.messageId, at, ReminderSource.MESSAGE)
    }

    suspend fun create(title: String, conversationId: String?, messageId: String?, at: Long, source: String): Reminder {
        val me = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
        val r = Reminder(
            id = Ids.newId("rem"), ownerId = me, conversationId = conversationId, messageId = messageId,
            title = title.trim().take(200), remindAt = at, createdAt = System.currentTimeMillis(), source = source,
        )
        dao.upsert(r)
        arm(r)
        return r
    }

    suspend fun reschedule(id: String, at: Long) {
        val r = dao.get(id) ?: return
        dao.reschedule(id, at)
        notifications.cancelReminder(id)
        arm(r.copy(remindAt = at))
    }

    suspend fun snooze(id: String, minutes: Int) = reschedule(id, System.currentTimeMillis() + minutes * 60_000L)

    suspend fun markDone(id: String) {
        dao.markDone(id)
        scheduler.cancel(id)
        notifications.cancelReminder(id)
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        scheduler.cancel(id)
        notifications.cancelReminder(id)
    }

    /** Alarm received: show the notification once (a cancelled or already-fired reminder is ignored). */
    suspend fun fire(id: String) {
        val r = dao.get(id) ?: return
        if (dao.markFired(id, System.currentTimeMillis()) != 1) return
        val chatTitle = r.conversationId?.let { db.conversationDao().get(it) }?.let { c ->
            if (c.isGroup) c.title else c.peerId?.let { db.userDao().get(it)?.displayName } ?: c.title
        }
        if (!notifications.showReminder(r, chatTitle)) diagnostics.record(DiagnosticsLog.REMINDER, "reminder_not_shown")
    }

    /** Re-arms every scheduled reminder; overdue ones (e.g. the phone was off) fire now. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        dao.scheduled().forEach { r -> if (r.remindAt <= now) fire(r.id) else arm(r) }
    }

    suspend fun clearForSignOut(owner: String) {
        dao.scheduledFor(owner).forEach { scheduler.cancel(it.id); notifications.cancelReminder(it.id) }
        dao.deleteFor(owner)
    }

    private fun arm(r: Reminder) {
        scheduler.schedule(r.id, r.remindAt)
        if (!scheduler.canScheduleExact()) diagnostics.record(DiagnosticsLog.REMINDER, "reminder_inexact")
    }
}
