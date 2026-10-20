package com.example.domain

import com.example.domain.model.Visibility

sealed class PresenceDisplay {
    object Online : PresenceDisplay()
    data class LastSeen(val at: Long) : PresenceDisplay()
    object Hidden : PresenceDisplay()
}

/**
 * Presence is published as a heartbeat document; a client that stops heart-beating (crash, no
 * network) is shown offline after [STALE_AFTER_MS] instead of appearing online forever.
 */
object PresencePolicy {
    const val HEARTBEAT_INTERVAL_MS = 45_000L
    const val STALE_AFTER_MS = 100_000L
    const val TYPING_TIMEOUT_MS = 6_000L
    const val TYPING_THROTTLE_MS = 3_000L

    fun resolve(
        online: Boolean,
        heartbeatAt: Long,
        lastSeen: Long,
        now: Long,
        readable: Boolean,
        viewerSharesLastSeen: Boolean = true,
    ): PresenceDisplay {
        if (!readable || !viewerSharesLastSeen) return PresenceDisplay.Hidden
        val fresh = heartbeatAt > 0 && now - heartbeatAt <= STALE_AFTER_MS
        if (online && fresh) return PresenceDisplay.Online
        val seen = maxOf(lastSeen, heartbeatAt)
        return if (seen > 0) PresenceDisplay.LastSeen(seen) else PresenceDisplay.Hidden
    }

    /** Can [viewer] see the owner's last seen? Mirrors the firestore.rules check. */
    fun canSee(visibility: Visibility, viewerIsOwner: Boolean, viewerIsContactOfOwner: Boolean): Boolean = when {
        viewerIsOwner -> true
        visibility == Visibility.EVERYONE -> true
        visibility == Visibility.CONTACTS -> viewerIsContactOfOwner
        else -> false
    }

    fun isTypingActive(typingAt: Long, now: Long): Boolean = typingAt > 0 && now - typingAt in 0..TYPING_TIMEOUT_MS
}
