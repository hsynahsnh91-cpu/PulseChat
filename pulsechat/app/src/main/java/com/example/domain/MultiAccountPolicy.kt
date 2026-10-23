package com.example.domain

/**
 * Policy for Multiple Independent Accounts on the same device (Feature 4).
 *
 * Defines account metadata records, switching rules, notification routing across accounts,
 * and strict per-account local storage naming so Account A and Account B never share local cached
 * messages, drafts, preferences, or notification actions.
 */
object MultiAccountPolicy {
    const val MAX_ACCOUNTS_ON_DEVICE = 5

    data class StoredAccount(
        val uid: String,
        val username: String,
        val displayName: String,
        val uniqueId10: String = "",
        val avatarUrl: String? = null,
        val avatarColorHex: String = "#4F46E5",
        val authMethod: String = "primary_login",
        val addedAt: Long = 0L,
        val lastActiveAt: Long = 0L,
        val needsReauth: Boolean = false,
    )

    enum class SwitchEligibility {
        READY,
        ALREADY_ACTIVE,
        ACCOUNT_NOT_FOUND,
        REAUTH_REQUIRED,
    }

    enum class NotificationRoute {
        /** Notification belongs to the currently active account: handle normally. */
        ACTIVE_ACCOUNT,
        /** Notification belongs to another account saved on this device: switch account first, never execute inline as the wrong user. */
        INACTIVE_SAVED_ACCOUNT,
        /** Notification is for an account no longer on this device: ignore and drop. */
        UNKNOWN_OR_REMOVED_ACCOUNT,
    }

    /** Dedicated Room SQLite database filename for [uid]'s isolated local data. */
    fun databaseNameForAccount(uid: String): String {
        val safe = uid.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        require(safe.isNotBlank()) { "Account UID must not be blank" }
        return "pulsechat_account_$safe.db"
    }

    /** Dedicated SharedPreferences name for [uid]'s isolated local settings. */
    fun prefsNameForAccount(uid: String): String {
        val safe = uid.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        require(safe.isNotBlank()) { "Account UID must not be blank" }
        return "pulse_prefs_acct_$safe"
    }

    /** Upserts [account] in [existing], keeping active/most-recent accounts first and capping at [MAX_ACCOUNTS_ON_DEVICE]. */
    fun upsertAccount(
        existing: List<StoredAccount>,
        account: StoredAccount,
        activeUid: String? = account.uid,
    ): List<StoredAccount> {
        if (account.uid.isBlank() || account.username.isBlank()) return existing
        val merged = (listOf(account) + existing.filter { it.uid != account.uid && it.username != account.username })
            .sortedWith(compareByDescending<StoredAccount> { it.uid == activeUid }.thenByDescending { it.lastActiveAt })
        return merged.take(MAX_ACCOUNTS_ON_DEVICE)
    }

    /** Removes [uid] from the device's saved accounts list. */
    fun removeAccount(existing: List<StoredAccount>, uid: String): List<StoredAccount> =
        existing.filter { it.uid != uid }

    /** Determines whether switching to [targetUid] can proceed directly or requires re-authentication. */
    fun checkSwitch(
        accounts: List<StoredAccount>,
        activeUid: String?,
        targetUid: String,
        hasValidCredential: Boolean,
    ): SwitchEligibility {
        if (targetUid.isBlank()) return SwitchEligibility.ACCOUNT_NOT_FOUND
        if (targetUid == activeUid) return SwitchEligibility.ALREADY_ACTIVE
        val target = accounts.firstOrNull { it.uid == targetUid } ?: return SwitchEligibility.ACCOUNT_NOT_FOUND
        if (target.needsReauth || !hasValidCredential) return SwitchEligibility.REAUTH_REQUIRED
        return SwitchEligibility.READY
    }

    /**
     * Decides how a notification or background action tagged with [targetAccountUid] may be routed
     * when [activeUid] is currently signed in.
     */
    fun routeNotification(
        activeUid: String?,
        targetAccountUid: String?,
        savedAccounts: List<StoredAccount>,
    ): NotificationRoute {
        if (targetAccountUid.isNullOrBlank()) {
            return if (activeUid != null) NotificationRoute.ACTIVE_ACCOUNT else NotificationRoute.UNKNOWN_OR_REMOVED_ACCOUNT
        }
        if (targetAccountUid == activeUid) return NotificationRoute.ACTIVE_ACCOUNT
        if (savedAccounts.any { it.uid == targetAccountUid }) return NotificationRoute.INACTIVE_SAVED_ACCOUNT
        return NotificationRoute.UNKNOWN_OR_REMOVED_ACCOUNT
    }
}
