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
 * users/{uid}/chatPrefs/{cid}      pin/archive/mute/favourite/unread/cleared (owner only)
 * usernames/{username}             username -> uid reservation
 * uniqueIds/{id10}                 10-digit ID -> uid/username reservation
 * presence/{uid}                   heartbeat presence (privacy-controlled)
 * conversations/{cid}              participants, admins, group info, lastMessage, receipts, typing
 * conversations/{cid}/messages/{messageId}
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
    const val CHAT_PREFS = "chatPrefs"
    const val USERNAMES = "usernames"
    const val UNIQUE_IDS = "uniqueIds"
    const val PRESENCE = "presence"
    const val CONVERSATIONS = "conversations"
    const val MESSAGES = "messages"
    const val STATUSES = "statuses"
    const val VIEWS = "views"
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
