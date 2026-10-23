package com.example.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.db.AppDatabase
import com.example.data.model.User
import com.example.data.prefs.AppPreferences
import com.example.domain.Ids
import com.example.domain.MultiAccountPolicy
import com.example.domain.MultiAccountPolicy.StoredAccount
import com.example.domain.MultiAccountPolicy.SwitchDenial
import com.example.domain.MultiAccountPolicy.SwitchEligibility
import com.example.security.DeviceKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages multiple independent PulseChat accounts on a single device.
 *
 * Each account (`uid`) has its own isolated SQLite database file (`pulsechat_account_<uid>.db`)
 * and preferences file (`pulse_prefs_acct_<uid>`), plus a Keystore-wrapped session delegation
 * record in [DeviceKeyManager]. Switching accounts:
 * 1. Snapshots the outgoing account's Room tables and preferences into its isolated per-account store,
 * 2. Stops all active listeners, push tokens, and in-memory state for the outgoing account,
 * 3. Clears the active workspace database and restores the incoming account's isolated tables & prefs,
 * 4. Restores the incoming account's Firebase Auth session (or routes cleanly to re-authentication
 *    if the session token was revoked/expired).
 *
 * Removing an account from the device deletes only local files for that account and never deletes
 * the user's cloud account or cloud messages.
 */
class AccountRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: AppPreferences,
    private val authRepository: AuthRepository,
    private val keyManager: DeviceKeyManager? = null,
) {
    sealed interface SwitchOutcome {
        data class Switched(val uid: String) : SwitchOutcome
        data class NeedsReauth(val uid: String, val username: String) : SwitchOutcome
        data class Denied(val reason: SwitchDenial) : SwitchOutcome
    }

    sealed interface RemoveOutcome {
        data class RemovedInactive(val removedUid: String) : RemoveOutcome
        data class SwitchedToNext(val removedUid: String, val nextUid: String) : RemoveOutcome
        data class SignedOutAll(val removedUid: String) : RemoveOutcome
    }

    private val mutex = Mutex()
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    val savedAccounts: StateFlow<List<StoredAccount>> = prefs.savedAccounts

    @Volatile
    private var activeWorkspaceUid: String? = null

    /**
     * Called when [uid] signs in on this device. Ensures the active Room workspace belongs to [uid]
     * (archiving any previous account's workspace if needed) and registers [uid] in [savedAccounts].
     */
    suspend fun onAccountSignedIn(uid: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previousUid = activeWorkspaceUid
            if (previousUid != null && previousUid != uid) {
                runCatching { snapshotAccountInternal(previousUid) }
                clearSqlTables(db.openHelper.writableDatabase)
                val targetAcctDb = AppDatabase.getDatabaseForAccount(context, uid)
                copySqlTables(targetAcctDb.openHelper.readableDatabase, db.openHelper.writableDatabase)
                db.invalidationTracker.refreshVersionsAsync()
                prefs.restoreAccountPrefs(uid)
            } else if (previousUid == null) {
                // If the active DB has messages/conversations belonging to a different account, isolate first.
                val targetAcctDb = AppDatabase.getDatabaseForAccount(context, uid)
                val activeHasTargetUser = db.userDao().get(uid) != null
                val isolatedHasTargetUser = targetAcctDb.userDao().get(uid) != null
                if (!activeHasTargetUser && isolatedHasTargetUser) {
                    clearSqlTables(db.openHelper.writableDatabase)
                    copySqlTables(targetAcctDb.openHelper.readableDatabase, db.openHelper.writableDatabase)
                    db.invalidationTracker.refreshVersionsAsync()
                    prefs.restoreAccountPrefs(uid)
                }
            }
            activeWorkspaceUid = uid
            val user = db.userDao().get(uid)
            upsertStoredAccount(uid, user, sessionValid = true)
            runCatching { snapshotAccountInternal(uid) }
        }
    }

    /** Updates the stored account card when the user updates their display name, username, or photo. */
    fun refreshAccountProfile(user: User) {
        if (user.userId.isBlank()) return
        upsertStoredAccount(user.userId, user, sessionValid = true)
    }

    private fun upsertStoredAccount(uid: String, user: User?, sessionValid: Boolean) {
        val existing = prefs.savedAccounts.value.firstOrNull { it.uid == uid }
        val username = user?.username?.takeIf { it.isNotBlank() }
            ?: existing?.username.orEmpty()
        val displayName = user?.displayName?.takeIf { it.isNotBlank() }
            ?: existing?.displayName?.takeIf { it.isNotBlank() }
            ?: username.ifBlank { uid.take(8) }
        val email = if (username.isNotBlank()) Ids.authEmailForUsername(username, "pulsechat") else existing?.email.orEmpty()
        val account = StoredAccount(
            uid = uid,
            displayName = displayName,
            username = username,
            email = email,
            avatarUrl = user?.avatarUrl ?: existing?.avatarUrl,
            avatarColorHex = user?.avatarColorHex ?: existing?.avatarColorHex ?: "#4F46E5",
            lastActiveAt = System.currentTimeMillis(),
            sessionValid = sessionValid,
        )
        prefs.setSavedAccounts(MultiAccountPolicy.upsertAccount(prefs.savedAccounts.value, account))
    }

    /** Copies the active account's Room tables and preferences into its isolated per-account store. */
    suspend fun snapshotActiveAccount() = mutex.withLock {
        val uid = authRepository.currentUid ?: activeWorkspaceUid ?: return@withLock
        withContext(Dispatchers.IO) {
            snapshotAccountInternal(uid)
        }
    }

    private fun snapshotAccountInternal(uid: String) {
        if (uid.isBlank()) return
        val accountDb = AppDatabase.getDatabaseForAccount(context, uid)
        copySqlTables(db.openHelper.readableDatabase, accountDb.openHelper.writableDatabase)
        accountDb.invalidationTracker.refreshVersionsAsync()
        prefs.snapshotAccountPrefs(uid)
    }

    /**
     * Switches from the current account to [targetUid] on this device.
     */
    suspend fun switchToAccount(
        targetUid: String,
        isCallActive: Boolean = false,
    ): SwitchOutcome = mutex.withLock {
        val currentUid = authRepository.currentUid ?: activeWorkspaceUid
        val check = MultiAccountPolicy.checkSwitch(
            activeUid = currentUid,
            targetUid = targetUid,
            storedAccounts = prefs.savedAccounts.value,
            isCallActive = isCallActive,
            isSwitchInProgress = _switching.value,
        )
        if (check is SwitchEligibility.Denied) {
            return@withLock SwitchOutcome.Denied(check.reason)
        }
        _switching.value = true
        try {
            withContext(Dispatchers.IO) {
                if (!currentUid.isNullOrBlank()) {
                    val currentUser = db.userDao().get(currentUid)
                    upsertStoredAccount(currentUid, currentUser, sessionValid = true)
                    snapshotAccountInternal(currentUid)
                }
                authRepository.signOutForAccountSwitch()
                clearSqlTables(db.openHelper.writableDatabase)
                val targetDb = AppDatabase.getDatabaseForAccount(context, targetUid)
                copySqlTables(targetDb.openHelper.readableDatabase, db.openHelper.writableDatabase)
                db.invalidationTracker.refreshVersionsAsync()
                prefs.restoreAccountPrefs(targetUid)
                activeWorkspaceUid = targetUid

                val restored = authRepository.restoreAccountSession(targetUid)
                val targetUser = db.userDao().get(targetUid)
                upsertStoredAccount(targetUid, targetUser, sessionValid = restored)
                if (restored) {
                    SwitchOutcome.Switched(targetUid)
                } else {
                    val uname = targetUser?.username
                        ?: prefs.savedAccounts.value.firstOrNull { it.uid == targetUid }?.username.orEmpty()
                    if (uname.isNotBlank()) prefs.rememberAccount(uname)
                    SwitchOutcome.NeedsReauth(targetUid, uname)
                }
            }
        } finally {
            _switching.value = false
        }
    }

    /**
     * Snapshots the current account's data and signs out to the Auth screen so the user can sign in
     * or register an additional account without losing the existing account on this device.
     */
    suspend fun beginAddAccount(isCallActive: Boolean = false): Boolean = mutex.withLock {
        if (isCallActive || _switching.value) return@withLock false
        if (prefs.savedAccounts.value.size >= MultiAccountPolicy.MAX_STORED_ACCOUNTS) return@withLock false
        _switching.value = true
        try {
            withContext(Dispatchers.IO) {
                val currentUid = authRepository.currentUid ?: activeWorkspaceUid
                if (!currentUid.isNullOrBlank()) {
                    val currentUser = db.userDao().get(currentUid)
                    upsertStoredAccount(currentUid, currentUser, sessionValid = true)
                    snapshotAccountInternal(currentUid)
                }
                authRepository.signOutForAccountSwitch()
                clearSqlTables(db.openHelper.writableDatabase)
                db.invalidationTracker.refreshVersionsAsync()
                activeWorkspaceUid = null
                true
            }
        } finally {
            _switching.value = false
        }
    }

    /**
     * Removes [targetUid] from this device only (deleting its isolated SQLite DB, preferences, and
     * Keystore delegation material) while preserving the cloud account and cloud history.
     */
    suspend fun removeAccountFromDevice(
        targetUid: String,
        isCallActive: Boolean = false,
    ): RemoveOutcome = mutex.withLock {
        val currentUid = authRepository.currentUid ?: activeWorkspaceUid
        val removal = MultiAccountPolicy.removeAccount(prefs.savedAccounts.value, targetUid, currentUid)
        prefs.setSavedAccounts(removal.remaining)
        keyManager?.clearAccountDelegation(targetUid)
        withContext(Dispatchers.IO) {
            AppDatabase.deleteAccountDatabase(context, targetUid)
            prefs.deleteAccountPrefs(targetUid)
        }
        if (targetUid != currentUid) {
            return@withLock RemoveOutcome.RemovedInactive(targetUid)
        }
        _switching.value = true
        try {
            withContext(Dispatchers.IO) {
                authRepository.signOutForAccountSwitch()
                clearSqlTables(db.openHelper.writableDatabase)
                db.invalidationTracker.refreshVersionsAsync()
                activeWorkspaceUid = null
                val nextUid = removal.nextActiveUid
                if (nextUid != null && !isCallActive) {
                    val targetDb = AppDatabase.getDatabaseForAccount(context, nextUid)
                    copySqlTables(targetDb.openHelper.readableDatabase, db.openHelper.writableDatabase)
                    db.invalidationTracker.refreshVersionsAsync()
                    prefs.restoreAccountPrefs(nextUid)
                    activeWorkspaceUid = nextUid
                    authRepository.restoreAccountSession(nextUid)
                    RemoveOutcome.SwitchedToNext(targetUid, nextUid)
                } else {
                    RemoveOutcome.SignedOutAll(targetUid)
                }
            }
        } finally {
            _switching.value = false
        }
    }

    companion object {
        private const val TAG = "AccountRepository"

        /** All account-scoped Room tables in [AppDatabase] v7. */
        val ACCOUNT_TABLES = listOf(
            "users",
            "conversations",
            "messages",
            "contacts",
            "calls",
            "statuses",
            "receipts",
            "blocked_users",
            "status_views",
            "status_likes",
            "scheduled_messages",
            "reminders",
            "chat_folders",
            "chat_folder_members",
            "message_translations",
            "message_transcripts",
            "diagnostic_events",
            "chat_notification_settings",
            "locked_chats",
        )

        fun clearSqlTables(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                for (table in ACCOUNT_TABLES) {
                    runCatching { db.execSQL("DELETE FROM `$table`") }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        fun copySqlTables(source: SupportSQLiteDatabase, target: SupportSQLiteDatabase) {
            target.beginTransaction()
            try {
                for (table in ACCOUNT_TABLES) {
                    runCatching {
                        target.execSQL("DELETE FROM `$table`")
                        source.query("SELECT * FROM `$table`").use { cursor ->
                            val colCount = cursor.columnCount
                            val colNames = cursor.columnNames
                            while (cursor.moveToNext()) {
                                val cv = ContentValues(colCount)
                                for (i in 0 until colCount) {
                                    val name = colNames[i]
                                    when (cursor.getType(i)) {
                                        Cursor.FIELD_TYPE_NULL -> cv.putNull(name)
                                        Cursor.FIELD_TYPE_INTEGER -> cv.put(name, cursor.getLong(i))
                                        Cursor.FIELD_TYPE_FLOAT -> cv.put(name, cursor.getDouble(i))
                                        Cursor.FIELD_TYPE_STRING -> cv.put(name, cursor.getString(i))
                                        Cursor.FIELD_TYPE_BLOB -> cv.put(name, cursor.getBlob(i))
                                    }
                                }
                                target.insert(table, SQLiteDatabase.CONFLICT_REPLACE, cv)
                            }
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "copySqlTables skipped $table: ${e.message}")
                    }
                }
                target.setTransactionSuccessful()
            } finally {
                target.endTransaction()
            }
        }
    }
}
