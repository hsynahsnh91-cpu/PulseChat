package com.example.domain

/**
 * Policy for Multiple Independent Accounts on the same device (Feature 4).
 *
 * Defines account metadata records, switching rules, notification routing across accounts,
 * and strict per-account local storage naming so Account A and Account B never share local cached
 * messages, drafts, preferences, or notification actions.
 */
object MultiAccountPolicy {
    const val MAX_STORED_ACCOUNTS = 5
    const val MAX_ACCOUNTS_ON_DEVICE = MAX_STORED_ACCOUNTS

    data class StoredAccount(
        val uid: String,
        val displayName: String,
        val username: String,
        val email: String = "",
        val uniqueId10: String = "",
        val avatarUrl: String? = null,
        val avatarColorHex: String = "#4F46E5",
        val authMethod: String = "primary_login",
        val addedAt: Long = 0L,
        val lastActiveAt: Long = 0L,
        val sessionValid: Boolean = true,
        val needsReauth: Boolean = !sessionValid,
    )

    enum class SwitchDenial {
        ALREADY_ACTIVE,
        CALL_IN_PROGRESS,
        SWITCH_IN_PROGRESS,
        ACCOUNT_NOT_FOUND,
    }

    sealed interface SwitchEligibility {
        data object Allowed : SwitchEligibility
        data class Denied(val reason: SwitchDenial) : SwitchEligibility
    }

    sealed interface NotificationRoute {
        /** Notification belongs to the currently active account: handle normally. */
        data object OpenDirectly : NotificationRoute
        /** Notification belongs to another account saved on this device: switch account first, never execute inline as the wrong user. */
        data class SwitchThenOpen(val targetAccountUid: String) : NotificationRoute
        /** Notification is for an account no longer on this device: ignore and drop. */
        data object Ignore : NotificationRoute
    }

    data class RemovalResult(
        val remaining: List<StoredAccount>,
        val nextActiveUid: String?,
    )

    /** Dedicated Room SQLite database filename for [uid]'s isolated local data. */
    fun databaseNameFor(uid: String): String {
        val safe = uid.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        require(safe.isNotBlank()) { "Account UID must not be blank" }
        return "pulsechat_account_$safe.db"
    }

    fun databaseNameForAccount(uid: String): String = databaseNameFor(uid)

    /** Dedicated SharedPreferences name for [uid]'s isolated local settings. */
    fun prefsNameFor(uid: String): String {
        val safe = uid.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        require(safe.isNotBlank()) { "Account UID must not be blank" }
        return "pulse_prefs_acct_$safe"
    }

    fun prefsNameForAccount(uid: String): String = prefsNameFor(uid)

    /** Upserts [account] in [existing], keeping active/most-recent accounts first and capping at [MAX_STORED_ACCOUNTS]. */
    fun upsertAccount(
        existing: List<StoredAccount>,
        account: StoredAccount,
        activeUid: String? = account.uid,
    ): List<StoredAccount> {
        if (account.uid.isBlank() || account.username.isBlank()) return existing
        val merged = (listOf(account) + existing.filter { it.uid != account.uid && it.username != account.username })
            .sortedWith(compareByDescending<StoredAccount> { it.uid == activeUid }.thenByDescending { it.lastActiveAt })
        return merged.take(MAX_STORED_ACCOUNTS)
    }

    /** Removes [removeUid] from the device's saved accounts list and determines the next active account if any. */
    fun removeAccount(
        existing: List<StoredAccount>,
        removeUid: String,
        currentActiveUid: String? = null,
    ): RemovalResult {
        val remaining = existing.filter { it.uid != removeUid }
        val nextUid = when {
            currentActiveUid != null && currentActiveUid != removeUid -> currentActiveUid
            else -> remaining.maxByOrNull { it.lastActiveAt }?.uid
        }
        return RemovalResult(remaining = remaining, nextActiveUid = nextUid)
    }

    /** Determines whether switching to [targetUid] can proceed or is denied. */
    fun checkSwitch(
        activeUid: String?,
        targetUid: String,
        storedAccounts: List<StoredAccount>,
        isCallActive: Boolean = false,
        isSwitchInProgress: Boolean = false,
    ): SwitchEligibility {
        if (targetUid.isBlank() || storedAccounts.none { it.uid == targetUid }) {
            return SwitchEligibility.Denied(SwitchDenial.ACCOUNT_NOT_FOUND)
        }
        if (targetUid == activeUid) {
            return SwitchEligibility.Denied(SwitchDenial.ALREADY_ACTIVE)
        }
        if (isCallActive) {
            return SwitchEligibility.Denied(SwitchDenial.CALL_IN_PROGRESS)
        }
        if (isSwitchInProgress) {
            return SwitchEligibility.Denied(SwitchDenial.SWITCH_IN_PROGRESS)
        }
        return SwitchEligibility.Allowed
    }

    /**
     * Decides how a notification or background action tagged with [notificationAccountUid] may be routed
     * when [activeUid] is currently signed in.
     */
    fun routeNotification(
        activeUid: String?,
        notificationAccountUid: String?,
        storedAccounts: List<StoredAccount>,
    ): NotificationRoute {
        if (notificationAccountUid.isNullOrBlank()) {
            return if (activeUid != null) NotificationRoute.OpenDirectly else NotificationRoute.Ignore
        }
        if (notificationAccountUid == activeUid) return NotificationRoute.OpenDirectly
        if (storedAccounts.any { it.uid == notificationAccountUid }) {
            return NotificationRoute.SwitchThenOpen(notificationAccountUid)
        }
        return NotificationRoute.Ignore
    }
}
