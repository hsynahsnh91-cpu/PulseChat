package com.example.notifications

import com.example.PulseChatApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives data-only FCM messages sent by the Cloud Functions in `pulsechat/firebase/functions`.
 * Payloads carry IDs only; content is fetched with the signed-in user's credentials, so Firestore
 * rules still decide what this device may read.
 */
class PulseMessagingService : FirebaseMessagingService() {
    private val container get() = (application as PulseChatApplication).container

    override fun onNewToken(token: String) {
        container.pushTokens.register(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val activeUid = container.authRepository.currentUid
        val targetAccountUid = data["accountUid"]
        // Never fetch or write push data into the wrong active account's database.
        if (!targetAccountUid.isNullOrBlank() && activeUid != null && targetAccountUid != activeUid) {
            return
        }
        when (data["type"]) {
            "message" -> {
                val cid = data["conversationId"] ?: return
                val mid = data["messageId"] ?: return
                runBlocking { withTimeoutOrNull(9_000) { container.chatRepository.fetchFromPush(cid, mid) } }
            }
            "status_mention" -> {
                val sid = data["statusId"] ?: return
                val me = activeUid ?: return
                if (!container.prefs.markStatusMentionNotified(me, sid)) return
                runBlocking {
                    withTimeoutOrNull(9_000) {
                        val s = container.db.statusDao().get(sid)
                        if (s != null) container.notifications.showStatusMention(s, me)
                    }
                }
            }
            "call" -> {
                val callId = data["callId"] ?: return
                runBlocking { withTimeoutOrNull(9_000) { container.callManager.handleIncoming(callId) } }
            }
            "call_ended" -> {
                val callId = data["callId"] ?: return
                container.callManager.onRemoteCallEnded(callId)
            }
            "device_linked" -> {
                val deviceId = data["deviceId"].orEmpty()
                if (deviceId.isNotBlank() && deviceId != container.prefs.deviceId) {
                    container.notifications.showDeviceLinkedNotification(data["deviceName"].orEmpty())
                }
            }
            "device_revoked" -> {
                val deviceId = data["deviceId"] ?: return
                if (deviceId == container.prefs.deviceId) {
                    runBlocking { withTimeoutOrNull(9_000) { container.deviceLinkRepository.handleRemoteRevocationPush() } }
                }
            }
        }
    }
}
