package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.PulseChatApplication
import kotlinx.coroutines.launch

/** Reminder alarms and the actions of reminder notifications ("Done", "Snooze 1 hour"). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val container = (context.applicationContext as PulseChatApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> container.reminders.fire(id)
                    ACTION_DONE -> container.reminders.markDone(id)
                    ACTION_SNOOZE -> container.reminders.snooze(id, 60)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.example.pulsechat.REMINDER_FIRE"
        const val ACTION_DONE = "com.example.pulsechat.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.example.pulsechat.REMINDER_SNOOZE"
        const val EXTRA_ID = "reminder_id"
    }
}

/**
 * Re-arms reminders and scheduled messages after a reboot, an app update, a clock or time-zone
 * change, or when the exact-alarm permission changes. Each run is idempotent.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as PulseChatApplication).container
        if (!container.firebaseAvailable) return
        val pending = goAsync()
        container.appScope.launch {
            try {
                runCatching { container.reminders.rescheduleAll() }
                runCatching { container.scheduledMessages.rescheduleAll() }
            } finally {
                pending.finish()
            }
        }
    }
}
