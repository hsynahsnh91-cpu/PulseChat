package com.example.domain

import com.example.domain.model.MessageType

/** Per-conversation disappearing messages. */
object DisappearingPolicy {
    /** Off, 24 hours, 7 days, 90 days (seconds). */
    val OPTIONS: List<Long> = listOf(0L, 86_400L, 604_800L, 7_776_000L)

    fun expiresAt(createdAt: Long, seconds: Long): Long = if (seconds <= 0L) 0L else createdAt + seconds * 1000L
    fun isExpired(expiresAt: Long, now: Long): Boolean = expiresAt in 1..now
    fun isValidOption(seconds: Long): Boolean = seconds in OPTIONS
}

/** Status/"Updates" visibility, interactions (likes, mentions, resharing) and ordering. */
object StatusPolicy {
    const val LIFETIME_MS = 24 * 60 * 60 * 1000L
    const val MAX_TEXT = 700
    const val MAX_MENTIONS = 20

    enum class AudienceMode { MY_CONTACTS, CONTACTS_EXCEPT, ONLY_SHARE_WITH }

    fun audience(mode: AudienceMode, contacts: Set<String>, selected: Set<String>): Set<String> = when (mode) {
        AudienceMode.MY_CONTACTS -> contacts
        AudienceMode.CONTACTS_EXCEPT -> contacts - selected
        AudienceMode.ONLY_SHARE_WITH -> selected
    }

    fun isActive(expiresAt: Long, now: Long): Boolean = expiresAt > now

    /**
     * Filters structured status mentions so:
     * - the author cannot mention themselves or blocked users,
     * - at most [MAX_MENTIONS] unique user IDs are kept,
     * - only users included in [allowedAudience] may be notified/stored on the status (never widening
     *   a restricted status's audience).
     */
    data class MentionValidation(
        val allowedMentionIds: List<String>,
        val excludedByPrivacyIds: List<String>,
    )

    fun validateMentions(
        requestedIds: Collection<String>,
        authorId: String,
        blockedIds: Set<String>,
        allowedAudience: Set<String>,
    ): MentionValidation {
        val eligible = requestedIds
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != authorId && it !in blockedIds }
            .distinct()
            .take(MAX_MENTIONS)
        val allowed = eligible.filter { it in allowedAudience }
        val excluded = eligible.filter { it !in allowedAudience }
        return MentionValidation(allowed, excluded)
    }

    /** Whether [viewerId] can like or unlike a status update. */
    fun canLike(
        ownerId: String,
        audience: Set<String>,
        blockedByOwner: Set<String>,
        expiresAt: Long,
        viewerId: String,
        now: Long,
    ): Boolean {
        if (viewerId.isBlank() || !isActive(expiresAt, now)) return false
        if (viewerId == ownerId) return true
        return viewerId in audience && viewerId !in blockedByOwner
    }

    enum class ReshareDenial {
        DISABLED_BY_AUTHOR,
        EXPIRED,
        NOT_IN_AUDIENCE,
        BLOCKED,
        OWN_STATUS,
        ALREADY_RESHARED_BY_USER,
    }

    /**
     * Checks whether [viewerId] is permitted to reshare a status update, enforcing the original
     * author's `allowReshare` setting, expiration, audience membership, and block list.
     */
    fun checkReshareEligibility(
        ownerId: String,
        allowReshare: Boolean,
        audience: Set<String>,
        blockedByOwner: Set<String>,
        expiresAt: Long,
        viewerId: String,
        now: Long,
        alreadyResharedIdsByViewer: Set<String> = emptySet(),
        statusId: String = "",
    ): ReshareDenial? = when {
        viewerId == ownerId -> ReshareDenial.OWN_STATUS
        !isActive(expiresAt, now) -> ReshareDenial.EXPIRED
        viewerId in blockedByOwner -> ReshareDenial.BLOCKED
        viewerId !in audience -> ReshareDenial.NOT_IN_AUDIENCE
        !allowReshare -> ReshareDenial.DISABLED_BY_AUTHOR
        statusId.isNotEmpty() && statusId in alreadyResharedIdsByViewer -> ReshareDenial.ALREADY_RESHARED_BY_USER
        else -> null
    }

    /**
     * Computes the effective audience and expiration time for a reshared status so a reshare NEVER
     * exposes the status to anyone outside the original status's permitted audience and NEVER
     * outlives the original status's expiration timestamp.
     */
    fun computeReshareScope(
        resharerAudience: Set<String>,
        originalAudience: Set<String>,
        originalOwnerId: String,
        originalExpiresAt: Long,
        resharerBlockedIds: Set<String>,
        now: Long,
    ): Pair<Set<String>, Long> {
        val allowedFromOriginal = originalAudience + originalOwnerId
        val effectiveAudience = (resharerAudience intersect allowedFromOriginal) - resharerBlockedIds
        val effectiveExpiresAt = minOf(originalExpiresAt, now + LIFETIME_MS).coerceAtLeast(now + 1_000L)
        return effectiveAudience to effectiveExpiresAt
    }

    data class Item(val statusId: String, val ownerId: String, val createdAt: Long, val expiresAt: Long, val viewed: Boolean)
    data class OwnerGroup(val ownerId: String, val items: List<Item>, val hasUnviewed: Boolean, val latest: Long)

    /** Groups active statuses per author: authors with unviewed updates first, newest first. */
    fun group(items: List<Item>, now: Long, excludeOwner: String?): List<OwnerGroup> =
        items.filter { isActive(it.expiresAt, now) && it.ownerId != excludeOwner }
            .groupBy { it.ownerId }
            .map { (owner, list) ->
                val sorted = list.sortedBy { it.createdAt }
                OwnerGroup(owner, sorted, sorted.any { !it.viewed }, sorted.last().createdAt)
            }
            .sortedWith(compareByDescending<OwnerGroup> { it.hasUnviewed }.thenByDescending { it.latest })

    /** Index of the first unviewed item so the viewer resumes where the user left off. */
    fun startIndex(group: OwnerGroup): Int = group.items.indexOfFirst { !it.viewed }.let { if (it < 0) 0 else it }

    /**
     * Authors played one after another when [start]'s updates are opened: [start], then the authors
     * listed after it in the same section of the Updates list (recent or viewed). My own updates
     * (or an author not in [groups]) play alone.
     */
    fun playOrder(groups: List<OwnerGroup>, start: String): List<String> {
        val section = groups.firstOrNull { it.ownerId == start }?.hasUnviewed ?: return listOf(start)
        val owners = groups.filter { it.hasUnviewed == section }.map { it.ownerId }
        return owners.drop(owners.indexOf(start))
    }

    /** How long a photo or text update stays on screen; longer texts get a little more time to read. */
    fun displayMillis(isText: Boolean, textLength: Int): Long =
        if (!isText) 5_000L else (4_000L + 45L * textLength).coerceIn(5_000L, 10_000L)
}

enum class NetworkKind { NONE, WIFI, CELLULAR, ROAMING }

/** Media auto-download rules (Settings > Storage and data). */
data class AutoDownloadSettings(
    val wifi: Set<MessageType> = setOf(MessageType.IMAGE, MessageType.AUDIO, MessageType.VIDEO, MessageType.DOCUMENT),
    val cellular: Set<MessageType> = setOf(MessageType.IMAGE, MessageType.AUDIO),
    val roaming: Set<MessageType> = emptySet(),
)

object AutoDownloadPolicy {
    const val CELLULAR_MAX_BYTES = 16L * 1024 * 1024

    fun shouldAutoDownload(type: MessageType, network: NetworkKind, settings: AutoDownloadSettings, sizeBytes: Long): Boolean =
        when (network) {
            NetworkKind.NONE -> false
            NetworkKind.WIFI -> type in settings.wifi
            NetworkKind.CELLULAR -> type in settings.cellular && (sizeBytes <= 0 || sizeBytes <= CELLULAR_MAX_BYTES)
            NetworkKind.ROAMING -> type in settings.roaming && (sizeBytes <= 0 || sizeBytes <= CELLULAR_MAX_BYTES)
        }
}

/** Upload size limits enforced in the client (and by storage.rules). */
object MediaLimits {
    const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 64L * 1024 * 1024
    const val MAX_DOCUMENT_BYTES = 100L * 1024 * 1024
    const val MAX_VOICE_MS = 15 * 60 * 1000L
    const val MIN_VOICE_MS = 1_000L

    fun maxBytes(type: MessageType): Long = when (type) {
        MessageType.IMAGE -> MAX_IMAGE_BYTES
        MessageType.VIDEO -> MAX_VIDEO_BYTES
        else -> MAX_DOCUMENT_BYTES
    }

    fun isAllowedSize(type: MessageType, bytes: Long): Boolean = bytes in 1..maxBytes(type)

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** Formats durations like 0:07, 1:05:09. */
object DurationFormat {
    fun clock(totalMs: Long): String {
        val totalSeconds = (totalMs.coerceAtLeast(0L) / 1000L)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}
