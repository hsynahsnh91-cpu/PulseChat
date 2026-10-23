package com.example.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Date

/**
 * Firestore layout (named database configured in res/values/firebase_applet_config.xml).
 * Every path and field used by the app is declared here and enforced by `firestore.rules`.
 *
 * users/{uid}                      public profile (no photo/about; see restricted/)
 * users/{uid}/restricted/{photo|about}  privacy-controlled profile fields
 * users/{uid}/contacts/{uid}       saved contacts (owner only)
 * users/{uid}/blocked/{uid}        blocked users (owner only; read by rules)
 * users/{uid}/private/settings     read receipts, notification previews (owner + Cloud Functions)
 * users/{uid}/devices/{deviceId}   FCM tokens (owner + Cloud Functions)
 * users/{uid}/linkedDevices/{deviceId}  linked-device authorization & revocation state (owner only)
 * users/{uid}/chatPrefs/{cid}      pin/archive/mute/favourite/unread/cleared (owner only)
 * devicePairings/{sessionId}       short-lived QR pairing session (2 min TTL, one-time consumption)
 * devicePairings/{sessionId}/exchange/envelope  one-time ECDH-P256 AES-256-GCM sealed auth envelope
 * devicePairingCodes/{codeHash}    manual pairing code lookup (SHA-256 hash -> sessionId + verifier)
 * usernames/{username}             username -> uid reservation
 * uniqueIds/{id10}                 10-digit ID -> uid/username reservation
 * presence/{uid}                   heartbeat presence (privacy-controlled)
 * conversations/{cid}              participants, admins, group info, lastMessage, receipts, typing
 * conversations/{cid}/messages/{messageId}   (edits: earlier versions of an edited text)
 * conversations/{cid}/live/{liveId}           live-location session: last point, expiry, end
 * statuses/{statusId}              24h status with explicit audience list
 * statuses/{statusId}/views/{uid}
 * calls/{callId}                   WebRTC signalling (offer/answer/state)
 * calls/{callId}/candidates/{id}   ICE candidates
 * reports/{reportId}               abuse reports (create-only)
 * media/{mediaId}                  built-in media store when Cloud Storage is unavailable (see MediaRefs)
 * media/{mediaId}/chunks/{index}   file content, at most 512 KB per document
 */
object Fs {
    const val USERS = "users"
    const val RESTRICTED = "restricted"
    const val CONTACTS = "contacts"
    const val BLOCKED = "blocked"
    const val PRIVATE = "private"
    const val SETTINGS_DOC = "settings"
    const val DEVICES = "devices"
    const val LINKED_DEVICES = "linkedDevices"
    const val DEVICE_PAIRINGS = "devicePairings"
    const val DEVICE_PAIRING_CODES = "devicePairingCodes"
    const val PAIRING_EXCHANGE = "exchange"
    const val PAIRING_ENVELOPE_DOC = "envelope"
    const val CHAT_PREFS = "chatPrefs"
    const val USERNAMES = "usernames"
    const val UNIQUE_IDS = "uniqueIds"
    const val PRESENCE = "presence"
    const val CONVERSATIONS = "conversations"
    const val MESSAGES = "messages"
    const val LIVE = "live"
    const val STATUSES = "statuses"
    const val VIEWS = "views"
    const val LIKES = "likes"
    const val CALLS = "calls"
    const val CANDIDATES = "candidates"
    const val REPORTS = "reports"
    const val MEDIA = "media"
    const val MEDIA_CHUNKS = "chunks"

    const val PHOTO_DOC = "photo"
    const val ABOUT_DOC = "about"

    const val TYPE_DIRECT = "direct"
    const val TYPE_GROUP = "group"
}

fun Timestamp?.millis(): Long = this?.toDate()?.time ?: 0L
fun Long.toTimestamp(): Timestamp = Timestamp(Date(this))

/** Server timestamps are estimated while a local write is pending, so ordering stays stable. */
fun DocumentSnapshot.millis(field: String): Long =
    getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE).millis()

fun DocumentSnapshot.millisOr(field: String, default: Long): Long =
    millis(field).takeIf { it > 0L } ?: default

fun Map<*, *>.millisOf(key: String): Long = when (val v = this[key]) {
    is Timestamp -> v.millis()
    is Number -> v.toLong()
    else -> 0L
}

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.stringList(field: String): List<String> =
    (get(field) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.map(field: String): Map<String, Any?> = (get(field) as? Map<String, Any?>) ?: emptyMap()
