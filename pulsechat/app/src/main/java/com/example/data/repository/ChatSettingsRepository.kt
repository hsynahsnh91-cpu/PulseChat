package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.model.ChatNotificationSettings
import com.example.data.model.LockedChat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Per-chat settings that are not part of the conversation itself:
 *  - notification settings (mute until a time, message preview), synced to the account's other
 *    phones through users/{uid}/chatPrefs/{cid};
 *  - chat locks, which stay on this phone, and the "locked chats are open" state, which ends when
 *    the app goes to the background (except for screens PulseChat opened itself, like the photo picker).
 */
class ChatSettingsRepository(
    private val db: AppDatabase,
    private val sync: SyncManager,
    private val currentUid: () -> String?,
) {
    private val dao get() = db.chatSettingsDao()

    // ------------------------------------------------------------------ notifications

    fun observeNotificationSettings(cid: String): Flow<ChatNotificationSettings> {
        val me = currentUid() ?: return flowOf(ChatNotificationSettings("", cid))
        return dao.observeNotificationSettings(me, cid).map { it ?: ChatNotificationSettings(me, cid) }
    }

    suspend fun notificationSettings(cid: String): ChatNotificationSettings {
        val me = currentUid() ?: return ChatNotificationSettings("", cid)
        return dao.notificationSettings(me, cid) ?: ChatNotificationSettings(me, cid)
    }

    /** [until] 0 turns notifications back on; [ChatNotificationSettings.MUTED_FOREVER] mutes until turned on. */
    suspend fun setMutedUntil(cid: String, until: Long) {
        update(cid) { it.copy(mutedUntil = until.coerceAtLeast(0L)) }
        push(cid, mapOf("mutedUntil" to until.coerceAtLeast(0L)))
    }

    suspend fun setPreview(cid: String, preview: String) {
        require(preview in ChatNotificationSettings.PREVIEWS) { "unknown preview mode" }
        update(cid) { it.copy(preview = preview) }
        push(cid, mapOf("notifyPreview" to preview))
    }

    private suspend fun update(cid: String, change: (ChatNotificationSettings) -> ChatNotificationSettings) {
        val me = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
        db.withTransaction {
            val current = dao.notificationSettings(me, cid) ?: ChatNotificationSettings(me, cid)
            dao.upsertNotificationSettings(change(current).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun push(cid: String, values: Map<String, Any?>) {
        val me = currentUid() ?: return
        val conv = db.conversationDao().get(cid)
        if (conv != null && !conv.isLocalOnly) sync.writeChatPref(me, cid, values)
    }

    /** Mutes stored on conversations before v5 move to the settings table (then the old column is cleared). */
    suspend fun importLegacyMutes(me: String) {
        db.withTransaction {
            dao.importLegacyMutes(me)
            db.conversationDao().clearLegacyMutes(me)
        }
    }

    // ------------------------------------------------------------------ chat lock

    private val _lockedChatsOpen = MutableStateFlow(false)
    /** True after a successful screen-lock check, until the app goes to the background. */
    val lockedChatsOpen: StateFlow<Boolean> = _lockedChatsOpen.asStateFlow()
    @Volatile private var backgroundedAt = 0L

    fun observeIsLocked(cid: String): Flow<Boolean> {
        val me = currentUid() ?: return flowOf(false)
        return dao.observeIsLocked(me, cid)
    }

    fun observeLockedIds(): Flow<Set<String>> {
        val me = currentUid() ?: return flowOf(emptySet())
        return dao.observeLockedIds(me).map { it.toSet() }
    }

    suspend fun isLocked(cid: String): Boolean {
        val me = currentUid() ?: return false
        return dao.isLocked(me, cid)
    }

    /** Call only after the phone's screen-lock check succeeded. */
    suspend fun lock(cid: String) {
        val me = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
        dao.lock(LockedChat(me, cid, System.currentTimeMillis()))
        _lockedChatsOpen.value = true
    }

    /** Call only after the phone's screen-lock check succeeded. */
    suspend fun unlock(cid: String) {
        val me = currentUid() ?: throw ChatException(ChatError.NOT_SIGNED_IN)
        dao.unlock(me, cid)
    }

    fun markLockedChatsOpen() { _lockedChatsOpen.value = true }
    fun closeLockedChats() { _lockedChatsOpen.value = false }

    fun onAppBackground() { backgroundedAt = System.currentTimeMillis() }

    /** Locked chats close again when the user comes back from elsewhere (not from PulseChat's own pickers). */
    fun onAppForeground(returningFromOwnScreen: Boolean) {
        val left = backgroundedAt
        backgroundedAt = 0L
        if (left != 0L && !returningFromOwnScreen) closeLockedChats()
    }
}
