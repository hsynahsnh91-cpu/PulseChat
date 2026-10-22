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
import com.example.data.model.ChatNotificationSettings
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.prefs.AppPreferences
import com.example.domain.DurationFormat
import com.example.domain.Mentions
import com.example.domain.NotificationPolicy
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
            NotificationChannel(CH_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH),
            // One channel for the ongoing "sharing live location" notice (never one per chat or message).
            NotificationChannel(CH_LIVE_LOCATION, context.getString(R.string.channel_live_location), NotificationManager.IMPORTANCE_LOW),
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
            MessageType.LOCATION -> context.getString(if (m.liveId != null) R.string.preview_live_location else R.string.preview_location)
            MessageType.CONTACT -> context.getString(R.string.preview_contact) + " " + (m.contactName ?: "")
            MessageType.POLL -> context.getString(R.string.preview_poll) + " " + (m.pollQuestion ?: "")
            MessageType.CALL_EVENT -> context.getString(R.string.preview_call)
            else -> m.content
        }
    }

    /**
     * Posts/updates the notification for new incoming messages of one conversation. This chat's own
     * settings (chat_notification_settings) decide here, before anything is posted: a muted chat
     * posts nothing, and the preview choice or a chat lock limits what the notification may show.
     * Other chats are not affected.
     */
    @SuppressLint("MissingPermission")
    suspend fun showMessages(conv: Conversation, messages: List<Message>, me: String) {
        val settings = prefs.current
        if (!canPost()) return
        if (conv.isGroup && !settings.groupNotifications) return
        if (!conv.isGroup && !settings.messageNotifications) return
        val cid = conv.conversationId
        val chatSettings = db.chatSettingsDao().notificationSettings(me, cid)
        val locked = db.chatSettingsDao().isLocked(me, cid)
        val mentioned = messages.any { it.senderId != me && Mentions.mentionsUser(it.mentionList(), me) }
        if (!NotificationPolicy.shouldNotify(chatSettings?.mutedUntil ?: 0L, System.currentTimeMillis(), conv.isGroup, mentioned)) return
        val content = NotificationPolicy.content(settings.notificationPreviews, chatSettings?.preview ?: ChatNotificationSettings.PREVIEW_DEFAULT, locked)
        val showText = content == NotificationPolicy.Content.FULL
        val showNames = content != NotificationPolicy.Content.HIDDEN
        val hiddenText = context.getString(R.string.notification_new_message)
        val lines = active.getOrPut(cid) { LinkedHashMap() }
        for (m in messages) {
            if (m.senderId == me || lines.containsKey(m.messageId)) continue
            val sender = db.contactDao().get(me, m.senderId)?.customNickname ?: db.userDao().get(m.senderId)?.displayName
                ?: context.getString(R.string.unknown_user)
            lines[m.messageId] = Line(m.messageId, sender, m.senderId, if (showText) previewFor(m) else hiddenText, m.createdAt)
        }
        // Lines kept from earlier messages must not show text the current setting hides.
        if (!showText) for (key in lines.keys.toList()) lines[key]?.let { lines[key] = it.copy(text = hiddenText) }
        while (lines.size > 8) lines.remove(lines.keys.first())
        if (lines.isEmpty()) return
        val appName = context.getString(R.string.app_name)
        val title = if (!showNames) appName else if (conv.isGroup) conv.title else lines.values.last().sender
        val self = Person.Builder().setName(context.getString(R.string.you)).setKey(me).build()
        val style = NotificationCompat.MessagingStyle(self)
        if (conv.isGroup && showNames) {
            style.conversationTitle = conv.title
            style.isGroupConversation = true
        }
        lines.values.forEach { l ->
            val person = Person.Builder().setName(if (showNames) l.sender else appName).setKey(if (showNames) l.senderKey else "hidden").build()
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
            .setColor(ContextCompat.getColor(context, R.color.pulse_accent))
            .setContentTitle(title)
            .setContentText(lines.values.last().text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup(GROUP_KEY)
            .setWhen(lines.values.last().time)
            .setVisibility(if (showNames) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openIntent(EXTRA_CONVERSATION, conv.conversationId, requestBase))
        // A locked chat opens only after the screen-lock check, so it offers no reply from the notification.
        if (!locked) builder.addAction(reply).addAction(markRead)
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
            .setColor(ContextCompat.getColor(context, R.color.pulse_accent))
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

    /** Reminder notification; tapping opens the chat at the message. Returns false if it can't be shown. */
    @SuppressLint("MissingPermission")
    fun showReminder(r: com.example.data.model.Reminder, chatTitle: String?, chatLocked: Boolean = false): Boolean {
        if (!canPost()) return false
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = android.net.Uri.parse("pulsechat://reminder/${r.id}")
            r.conversationId?.let { putExtra(EXTRA_CONVERSATION, it) }
            r.messageId?.let { putExtra(EXTRA_MESSAGE, it) }
            if (r.conversationId == null) putExtra(EXTRA_OPEN_REMINDERS, true)
        }
        val openPi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(action).setData(android.net.Uri.parse("pulsechat://reminder/${r.id}"))
                .putExtra(ReminderReceiver.EXTRA_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Reminders about messages of a locked chat never show the text or the chat.
        val showContent = prefs.current.notificationPreviews && !chatLocked
        val builder = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(if (showContent) r.title else context.getString(R.string.reminder_notification_hidden))
            .setSubText(if (showContent) chatTitle else null)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(0, context.getString(R.string.reminder_done), action(ReminderReceiver.ACTION_DONE))
            .addAction(0, context.getString(R.string.reminder_snooze_hour), action(ReminderReceiver.ACTION_SNOOZE))
        if (showContent) builder.setStyle(NotificationCompat.BigTextStyle().bigText(r.title))
        return runCatching { nm.notify(r.id, REMINDER_NOTIFICATION_ID, builder.build()); true }.getOrDefault(false)
    }

    fun cancelReminder(id: String) = nm.cancel(id, REMINDER_NOTIFICATION_ID)

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

    /** Ongoing notice of [com.example.location.LiveLocationService] with a Stop action. */
    fun liveLocationNotification(conversationId: String?, until: Long): Notification {
        val stop = PendingIntent.getBroadcast(
            context, 4300, Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_STOP_LIVE_LOCATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val until24 = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(until))
        val builder = NotificationCompat.Builder(context, CH_LIVE_LOCATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.pulse_accent))
            .setContentTitle(context.getString(R.string.live_notification_title))
            .setContentText(if (until > 0) context.getString(R.string.live_notification_text, until24) else null)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.live_stop), stop)
        if (conversationId != null) builder.setContentIntent(openIntent(EXTRA_CONVERSATION, conversationId, 4301))
        return builder.build()
    }

    companion object {
        const val CH_LIVE_LOCATION = "live_location"
        const val LIVE_LOCATION_NOTIFICATION_ID = 6
        const val ACTION_STOP_LIVE_LOCATION = "com.example.pulsechat.STOP_LIVE_LOCATION"
        const val CH_MESSAGES = "messages"
        const val CH_GROUPS = "groups"
        const val CH_CALLS = "calls_incoming"
        const val CH_MISSED = "missed_calls"
        const val CH_ONGOING = "ongoing_call"
        const val CH_REMINDERS = "reminders"
        const val REMINDER_NOTIFICATION_ID = 5
        const val EXTRA_MESSAGE = "open_message"
        const val EXTRA_OPEN_REMINDERS = "open_reminders"
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
