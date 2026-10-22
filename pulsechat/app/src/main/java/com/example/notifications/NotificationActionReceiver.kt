package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.PulseChatApplication
import com.example.data.repository.LiveLocationRepository
import kotlinx.coroutines.launch

/** Handles notification actions: inline reply, mark as read, decline/hang up calls, stop live location. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as PulseChatApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val cid = intent.getStringExtra(NotificationHelper.EXTRA_CONVERSATION)
                when (intent.action) {
                    NotificationHelper.ACTION_MARK_READ -> if (cid != null) {
                        container.chatRepository.markRead(cid)
                        container.notifications.cancelConversation(cid)
                    }
                    NotificationHelper.ACTION_REPLY -> if (cid != null) {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationHelper.KEY_REPLY)?.toString()
                        if (!text.isNullOrBlank()) {
                            runCatching { container.chatRepository.sendText(cid, text, null, emptyList()) }
                            container.chatRepository.markRead(cid)
                        }
                        container.notifications.cancelConversation(cid)
                    }
                    NotificationHelper.ACTION_DECLINE_CALL -> container.callManager.decline()
                    NotificationHelper.ACTION_HANG_UP -> container.callManager.hangUp()
                    NotificationHelper.ACTION_STOP_LIVE_LOCATION -> container.liveLocation.stop(LiveLocationRepository.StopReason.USER)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
