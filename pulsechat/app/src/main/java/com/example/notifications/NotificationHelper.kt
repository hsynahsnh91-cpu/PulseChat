package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.prefs.AppPreferences
import com.example.domain.DurationFormat
import com.example.domain.Mentions
import com.example.domain.model.MessageType
import java.util.concurrent.ConcurrentHashMap

/**
 * Notification channels and builders. Message notifications are grouped per conversation and
 * de-duplicated by message ID, so a message delivered by both a live listener and a push only
 * appears once. Previews can be hidden in Settings (content is then never shown).
 */
class NotificationHelper(private val context: Context, private val prefs: AppPreferences, private val db: AppDatabase) {
    private data class Line(val id: String, val sender: String, val senderKey: String, val text: String, val time: Long)

    private val nm = NotificationManagerCompat.from(context)
    private val active = ConcurrentHashMap<String, LinkedHashMap<String, Line>>()

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val channels = listOf(
            NotificationChannel(CH_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_GROUPS, context.getString(R.string.channel_groups), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_CALLS, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(ringtone, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(CH_MISSED, context.getString(R.string.channel_missed_calls), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_ONGOING, context.getString(R.string.channel_ongoing_call), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannels(channels)
    }

    private fun canPost(): Boolean =
        nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun openIntent(extraKey: String, value: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(extraKey, value)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun broadcast(action: String, cid: String, requestCode: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_CONVERSATION, cid)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    fun previewFor(m: Message): String {
        val body = m.content.ifBlank { null }
        return when (m.type) {
            MessageType.IMAGE -> context.getString(R.string.preview_photo) + (body?.let { " · $it" } ?: "")
            MessageType.VIDEO -> context.getString(R.string.preview_video) + (body?.let { " · $it" } ?: "")
            MessageType.AUDIO -> context.getString(R.string.preview_voice) + " " + DurationFormat.clock(m.mediaDurationSeconds * 1000L)
            MessageType.DOCUMENT -> context.getString(R.string.preview_document) + " " + (m.fileName ?: "")
            MessageType.LOCATION -> context.getString(R.string.preview_location)
            MessageType.CONTACT -> context.getString(R.string.preview_contact) + " " + (m.contactName ?: "")
            MessageType.POLL -> context.getString(R.string.preview_poll) + " " + (m.pollQuestion ?: "")
            MessageType.CALL_EVENT -> context.getString(R.string.preview_call)
            else -> m.content
        }
    }

    /** Posts/updates the notification for new incoming messages of one conversation. */
    @SuppressLint("MissingPermission")
    suspend fun showMessages(conv: Conversation, messages: List<Message>, me: String) {
        val settings = prefs.current
        if (!canPost()) return
        if (conv.isGroup && !settings.groupNotifications) return
        if (!conv.isGroup && !settings.messageNotifications) return
        val mentioned = messages.any { Mentions.mentionsUser(it.mentionList(), me) }
        if (conv.isMuted() && !mentioned) return
        val lines = active.getOrPut(conv.conversationId) { LinkedHashMap() }
        for (m in messages) {
            if (m.senderId == me || lines.containsKey(m.messageId)) continue
            val sender = db.contactDao().get(me, m.senderId)?.customNickname ?: db.userDao().get(m.senderId)?.displayName
                ?: context.getString(R.string.unknown_user)
            val text = if (settings.notificationPreviews) previewFor(m) else context.getString(R.string.notification_new_message)
            lines[m.messageId] = Line(m.messageId, sender, m.senderId, text, m.createdAt)
        }
        while (lines.size > 8) lines.remove(lines.keys.first())
        if (lines.isEmpty()) return
        val title = if (conv.isGroup) conv.title else lines.values.last().sender
        val self = Person.Builder().setName(context.getString(R.string.you)).setKey(me).build()
        val style = NotificationCompat.MessagingStyle(self)
        if (conv.isGroup && settings.notificationPreviews) {
            style.conversationTitle = conv.title
            style.isGroupConversation = true
        }
        lines.values.forEach { l ->
            val person = Person.Builder().setName(if (settings.notificationPreviews) l.sender else context.getString(R.string.app_name)).setKey(l.senderKey).build()
            style.addMessage(l.text, l.time, person)
        }
        val requestBase = conv.conversationId.hashCode()
        val replyInput = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.notification_reply_hint)).build()
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.notification_reply),
            broadcast(ACTION_REPLY, conv.conversationId, requestBase + 1, mutable = true))
            .addRemoteInput(replyInput).setAllowGeneratedReplies(false).build()
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.notification_mark_read),
            broadcast(ACTION_MARK_READ, conv.conversationId, requestBase + 2, mutable = false)).build()
        val builder = NotificationCompat.Builder(context, if (conv.isGroup) CH_GROUPS else CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4F46E5.toInt())
            .setContentTitle(if (settings.notificationPreviews) title else context.getString(R.string.app_name))
            .setContentText(lines.values.last().text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup(GROUP_KEY)
            .setWhen(lines.values.last().time)
            .setVisibility(if (settings.notificationPreviews) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openIntent(EXTRA_CONVERSATION, conv.conversationId, requestBase))
            .addAction(reply)
            .addAction(markRead)
        try {
            nm.notify(conv.conversationId, MESSAGE_NOTIFICATION_ID, builder.build())
            postSummary()
        } catch (e: SecurityException) {
            // Notification permission revoked while running.
        }
    }

    @SuppressLint("MissingPermission")
    private fun postSummary() {
        if (active.size < 2) return
        val summary = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4F46E5.toInt())
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.resources.getQuantityString(R.plurals.notification_chats_summary, active.size, active.size))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(SUMMARY_ID, summary) }
    }

    fun cancelConversation(cid: String) {
        active.remove(cid)
        nm.cancel(cid, MESSAGE_NOTIFICATION_ID)
        if (active.isEmpty()) nm.cancel(SUMMARY_ID)
    }

    fun cancelAllMessages() {
        active.keys.toList().forEach { cancelConversation(it) }
        nm.cancel(SUMMARY_ID)
    }

    // ------------------------------------------------------------------ calls

    @SuppressLint("MissingPermission")
    fun showIncomingCall(callId: String, callerName: String, video: Boolean) {
        if (!canPost() || !prefs.current.callNotifications) return
        val fullScreen = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_ID, callId)
        }
        val fullScreenPi = PendingIntent.getActivity(context, 4001, fullScreen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val acceptIntent = Intent(fullScreen).putExtra(EXTRA_CALL_ACTION, CALL_ACTION_ACCEPT)
        val acceptPi = PendingIntent.getActivity(context, 4002, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val declinePi = PendingIntent.getBroadcast(context, 4003,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_DECLINE_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val caller = Person.Builder().setName(callerName).setImportant(true).build()
        val builder = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerName)
            .setContentText(context.getString(if (video) R.string.call_incoming_video else R.string.call_incoming_voice))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(60_000)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, acceptPi))
        runCatching { nm.notify(CALL_NOTIFICATION_ID, builder.build()) }
    }

    fun cancelIncomingCall() = nm.cancel(CALL_NOTIFICATION_ID)

    @SuppressLint("MissingPermission")
    fun showMissedCall(peerId: String, callerName: String, video: Boolean) {
        if (!canPost() || !prefs.current.callNotifications) return
        val builder = NotificationCompat.Builder(context, CH_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (video) R.string.call_missed_video else R.string.call_missed_voice))
            .setContentText(callerName)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent(EXTRA_OPEN_CALLS, peerId, 4100))
        runCatching { nm.notify("missed_$peerId", MISSED_CALL_ID, builder.build()) }
    }

    fun ongoingCallNotification(peerName: String, video: Boolean): Notification {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHOW_CALL, true)
        }
        val openPi = PendingIntent.getActivity(context, 4200, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hangupPi = PendingIntent.getBroadcast(context, 4201,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val person = Person.Builder().setName(peerName.ifBlank { context.getString(R.string.app_name) }).build()
        return NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peerName)
            .setContentText(context.getString(if (video) R.string.call_ongoing_video else R.string.call_ongoing_voice))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openPi)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangupPi))
            .build()
    }

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_GROUPS = "groups"
        const val CH_CALLS = "calls_incoming"
        const val CH_MISSED = "missed_calls"
        const val CH_ONGOING = "ongoing_call"
        const val GROUP_KEY = "pulsechat_messages"
        const val MESSAGE_NOTIFICATION_ID = 100
        const val SUMMARY_ID = 1
        const val CALL_NOTIFICATION_ID = 2
        const val ONGOING_CALL_ID = 3
        const val MISSED_CALL_ID = 4
        const val EXTRA_CONVERSATION = "open_conversation"
        const val EXTRA_CALL_ID = "incoming_call_id"
        const val EXTRA_CALL_ACTION = "call_action"
        const val EXTRA_SHOW_CALL = "show_call"
        const val EXTRA_OPEN_CALLS = "open_calls"
        const val CALL_ACTION_ACCEPT = "accept"
        const val ACTION_REPLY = "com.example.pulsechat.REPLY"
        const val ACTION_MARK_READ = "com.example.pulsechat.MARK_READ"
        const val ACTION_DECLINE_CALL = "com.example.pulsechat.DECLINE_CALL"
        const val ACTION_HANG_UP = "com.example.pulsechat.HANG_UP"
        const val KEY_REPLY = "reply_text"
    }
}
