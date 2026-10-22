package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.BlockedUser
import com.example.data.model.CallRecord
import com.example.data.model.ChatListRow
import com.example.data.model.Contact
import com.example.data.model.ContactRow
import com.example.data.model.Conversation
import com.example.data.model.MediaTypeCount
import com.example.data.model.Message
import com.example.data.model.MessageSearchRow
import com.example.data.model.Receipt
import com.example.data.model.StarredRow
import com.example.data.model.StatusItem
import com.example.data.model.StatusView
import com.example.data.model.StatusViewRow
import com.example.data.model.StorageRow
import com.example.data.model.User
import com.example.domain.model.MessageStatus
import com.example.domain.model.TransferState
import kotlinx.coroutines.flow.Flow

/** Exact membership test on the comma-separated participant list (no substring false positives). */
private const val MEMBER = "(',' || c.participantIds || ',') LIKE ('%,' || :userId || ',%')"

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE userId = :id")
    suspend fun get(id: String): User?

    @Query("SELECT * FROM users WHERE userId = :id")
    fun observe(id: String): Flow<User?>

    @Query("SELECT * FROM users WHERE userId IN (:ids)")
    suspend fun getMany(ids: List<String>): List<User>

    @Query("SELECT * FROM users WHERE userId IN (:ids)")
    fun observeMany(ids: List<String>): Flow<List<User>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: User)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(users: List<User>)

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): User?

    @Query("SELECT * FROM users WHERE uniqueId10 = :uniqueId LIMIT 1")
    suspend fun findByUniqueId(uniqueId: String): User?

    @Query("SELECT * FROM users WHERE passwordHash IS NOT NULL ORDER BY createdAt DESC")
    suspend fun legacyAccounts(): List<User>

    @Query("UPDATE users SET passwordHash = NULL WHERE userId = :id")
    suspend fun clearLegacyPassword(id: String)

    @Query("DELETE FROM users WHERE userId = :id")
    suspend fun delete(id: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    suspend fun get(id: String): Conversation?

    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    fun observe(id: String): Flow<Conversation?>

    @Query(
        "SELECT c.*, u.displayName AS peerName, u.avatarUrl AS peerAvatar, u.avatarColorHex AS peerColor, " +
            "ct.customNickname AS peerNickname, m.status AS lastStatus, m.deletedForEveryone AS lastDeleted, " +
            "su.displayName AS lastSenderName " +
            "FROM conversations c " +
            "LEFT JOIN users u ON u.userId = c.peerId " +
            "LEFT JOIN contacts ct ON ct.ownerUserId = :userId AND ct.targetUserId = c.peerId " +
            "LEFT JOIN messages m ON m.messageId = c.lastMessageId " +
            "LEFT JOIN users su ON su.userId = c.lastMessageSenderId " +
            "WHERE $MEMBER AND c.isHidden = 0"
    )
    fun observeChatList(userId: String): Flow<List<ChatListRow>>

    @Query("SELECT c.* FROM conversations c WHERE $MEMBER AND c.isLocalOnly = 0")
    suspend fun syncedForUser(userId: String): List<Conversation>

    @Query("SELECT c.* FROM conversations c WHERE $MEMBER AND c.isGroup = 1 AND c.isLeft = 0 AND c.isLocalOnly = 0 ORDER BY c.title COLLATE NOCASE")
    fun observeGroups(userId: String): Flow<List<Conversation>>

    @Query("SELECT c.* FROM conversations c WHERE $MEMBER AND c.isHidden = 0 AND c.isLeft = 0 AND c.isLocalOnly = 0 ORDER BY c.lastMessageTimestamp DESC")
    suspend fun forwardTargets(userId: String): List<Conversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: Conversation)

    @Update
    suspend fun update(conversation: Conversation)

    @Query("UPDATE conversations SET isPinned = :pinned, pinnedAt = :pinnedAt WHERE conversationId = :id")
    suspend fun setPinned(id: String, pinned: Boolean, pinnedAt: Long)

    @Query("UPDATE conversations SET isArchived = :archived WHERE conversationId = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE conversations SET mutedUntil = :until WHERE conversationId = :id")
    suspend fun setMutedUntil(id: String, until: Long)

    @Query("UPDATE conversations SET isFavorite = :favorite WHERE conversationId = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE conversations SET markedUnread = :unread WHERE conversationId = :id")
    suspend fun setMarkedUnread(id: String, unread: Boolean)

    @Query("UPDATE conversations SET draftText = :draft, draftUpdatedAt = :at WHERE conversationId = :id")
    suspend fun setDraft(id: String, draft: String?, at: Long)

    @Query("UPDATE conversations SET lastReadAt = MAX(lastReadAt, :at), markedUnread = 0 WHERE conversationId = :id")
    suspend fun markReadUpTo(id: String, at: Long)

    @Query("UPDATE conversations SET isHidden = :hidden WHERE conversationId = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE conversations SET clearedAt = :at, lastMessageText = NULL, lastMessageId = NULL, unreadCount = 0 WHERE conversationId = :id")
    suspend fun clear(id: String, at: Long)

    @Query("UPDATE conversations SET syncCursor = MAX(syncCursor, :cursor) WHERE conversationId = :id")
    suspend fun advanceCursor(id: String, cursor: Long)

    @Query("UPDATE conversations SET isLeft = :left WHERE conversationId = :id")
    suspend fun setLeft(id: String, left: Boolean)

    @Query("SELECT COUNT(*) FROM conversations c WHERE $MEMBER AND c.isPinned = 1 AND c.isArchived = 0 AND c.isHidden = 0")
    suspend fun pinnedCount(userId: String): Int

    @Query(
        "UPDATE conversations SET unreadCount = (SELECT COUNT(*) FROM messages m WHERE m.conversationId = conversations.conversationId " +
            "AND m.senderId != :userId AND m.createdAt > conversations.lastReadAt AND m.createdAt > conversations.clearedAt " +
            "AND m.deletedForMe = 0 AND m.type != 'SYSTEM' AND m.type != 'CALL_EVENT') WHERE conversationId = :id"
    )
    suspend fun recomputeUnread(id: String, userId: String)

    @Query("SELECT COALESCE(SUM(CASE WHEN c.unreadCount > 0 OR c.markedUnread = 1 THEN 1 ELSE 0 END), 0) FROM conversations c WHERE $MEMBER AND c.isArchived = 0 AND c.isHidden = 0 AND c.mutedUntil < :now")
    fun observeUnreadChats(userId: String, now: Long): Flow<Int>

    @Query("DELETE FROM conversations WHERE conversationId = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE messageId = :id")
    suspend fun get(id: String): Message?

    @Query("SELECT * FROM messages WHERE messageId = :id")
    fun observe(id: String): Flow<Message?>

    @Query("SELECT * FROM messages WHERE messageId IN (:ids)")
    suspend fun getMany(ids: List<String>): List<Message>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND deletedForMe = 0 AND createdAt > :clearedAt " +
            "ORDER BY createdAt DESC, messageId DESC LIMIT :limit"
    )
    fun observeRecent(conversationId: String, clearedAt: Long, limit: Int): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND deletedForMe = 0 AND createdAt > :clearedAt")
    suspend fun countVisible(conversationId: String, clearedAt: Long): Int

    /** Number of visible messages newer than [createdAt]: used to scroll to a specific message. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND deletedForMe = 0 AND createdAt > :clearedAt AND (createdAt > :createdAt OR (createdAt = :createdAt AND messageId > :messageId))")
    suspend fun countNewerThan(conversationId: String, clearedAt: Long, createdAt: Long, messageId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<Message>)

    @Update
    suspend fun update(message: Message)

    @Query("UPDATE messages SET status = :status, failureReason = :reason WHERE messageId = :id")
    suspend fun setStatus(id: String, status: MessageStatus, reason: String?)

    @Query("UPDATE messages SET isStarred = :starred WHERE messageId IN (:ids)")
    suspend fun setStarred(ids: List<String>, starred: Boolean)

    @Query("UPDATE messages SET deletedForMe = 1 WHERE messageId IN (:ids)")
    suspend fun deleteForMe(ids: List<String>)

    @Query("UPDATE messages SET deletedForMe = 1 WHERE conversationId = :conversationId AND (:keepStarred = 0 OR isStarred = 0)")
    suspend fun clearConversation(conversationId: String, keepStarred: Boolean)

    @Query("UPDATE messages SET transferState = :state, transferProgress = :progress WHERE messageId = :id")
    suspend fun setTransfer(id: String, state: TransferState, progress: Int)

    @Query("UPDATE messages SET mediaUrl = :url, transferState = 'UPLOADED', transferProgress = 100 WHERE messageId = :id")
    suspend fun setUploaded(id: String, url: String)

    @Query("UPDATE messages SET mediaUrl = :url, storagePath = :path, transferState = 'UPLOADED', transferProgress = 100 WHERE messageId = :id")
    suspend fun setUploadedRef(id: String, url: String, path: String)

    @Query("UPDATE messages SET localMediaPath = :path, transferState = :state, transferProgress = 100 WHERE messageId = :id")
    suspend fun setLocalMedia(id: String, path: String?, state: TransferState)

    @Query(
        "UPDATE messages SET status = :newStatus WHERE conversationId = :conversationId AND senderId = :me " +
            "AND createdAt <= :upTo AND status IN (:fromStatuses)"
    )
    suspend fun advanceStatuses(conversationId: String, me: String, upTo: Long, newStatus: MessageStatus, fromStatuses: List<String>)

    @Query("SELECT * FROM messages WHERE senderId = :me AND status IN ('PENDING', 'SENDING') ORDER BY createdAt ASC")
    suspend fun pendingOutgoing(me: String): List<Message>

    @Query("SELECT * FROM messages WHERE transferState IN ('UPLOADING', 'DOWNLOADING')")
    suspend fun interruptedTransfers(): List<Message>

    @Query("SELECT * FROM messages WHERE expiresAt > 0 AND expiresAt <= :now")
    suspend fun expired(now: Long): List<Message>

    @Query("DELETE FROM messages WHERE messageId IN (:ids)")
    suspend fun deleteHard(ids: List<String>)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND deletedForMe = 0 AND createdAt > :clearedAt ORDER BY createdAt DESC, messageId DESC LIMIT 1")
    suspend fun latestVisible(conversationId: String, clearedAt: Long): Message?

    @Query("SELECT MIN(createdAt) FROM messages WHERE conversationId = :conversationId")
    suspend fun oldestCreatedAt(conversationId: String): Long?

    @Query("SELECT MAX(createdAt) FROM messages WHERE conversationId = :conversationId")
    suspend fun newestCreatedAt(conversationId: String): Long?

    @Query("SELECT MAX(createdAt) FROM messages WHERE conversationId = :conversationId AND senderId != :me AND status != 'PENDING'")
    suspend fun latestIncomingAt(conversationId: String, me: String): Long?

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND deletedForMe = 0 AND deletedForEveryone = 0 " +
            "AND createdAt > :clearedAt AND (content LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%' " +
            "OR pollQuestion LIKE '%' || :query || '%' OR contactName LIKE '%' || :query || '%') ORDER BY createdAt DESC LIMIT 300"
    )
    suspend fun searchInConversation(conversationId: String, clearedAt: Long, query: String): List<Message>

    @Query(
        "SELECT m.*, c.title AS conversationTitle, c.isGroup AS conversationIsGroup, pu.displayName AS peerName, su.displayName AS senderName " +
            "FROM messages m JOIN conversations c ON c.conversationId = m.conversationId " +
            "LEFT JOIN users pu ON pu.userId = c.peerId LEFT JOIN users su ON su.userId = m.senderId " +
            "WHERE $MEMBER AND m.deletedForMe = 0 AND m.deletedForEveryone = 0 AND m.createdAt > c.clearedAt " +
            "AND (:query = '' OR m.content LIKE '%' || :query || '%' OR m.fileName LIKE '%' || :query || '%') " +
            "AND (:typeFilter = '' OR m.type = :typeFilter) AND (:linksOnly = 0 OR m.content LIKE '%http%' OR m.content LIKE '%www.%') " +
            "ORDER BY m.createdAt DESC LIMIT 200"
    )
    suspend fun searchAll(userId: String, query: String, typeFilter: String, linksOnly: Boolean): List<MessageSearchRow>

    @Query(
        "SELECT m.*, c.title AS conversationTitle, c.isGroup AS conversationIsGroup, su.displayName AS senderName " +
            "FROM messages m JOIN conversations c ON c.conversationId = m.conversationId LEFT JOIN users su ON su.userId = m.senderId " +
            "WHERE $MEMBER AND m.isStarred = 1 AND m.deletedForMe = 0 AND m.deletedForEveryone = 0 ORDER BY m.createdAt DESC"
    )
    fun observeStarred(userId: String): Flow<List<StarredRow>>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND type IN ('IMAGE', 'VIDEO', 'DOCUMENT', 'AUDIO') " +
            "AND deletedForMe = 0 AND deletedForEveryone = 0 AND createdAt > :clearedAt ORDER BY createdAt DESC LIMIT 200"
    )
    fun observeMedia(conversationId: String, clearedAt: Long): Flow<List<Message>>

    @Query(
        "SELECT m.conversationId AS conversationId, c.title AS title, pu.displayName AS peerName, c.isGroup AS isGroup, " +
            "COALESCE(SUM(m.mediaSizeBytes), 0) AS bytes, COUNT(*) AS files FROM messages m " +
            "JOIN conversations c ON c.conversationId = m.conversationId LEFT JOIN users pu ON pu.userId = c.peerId " +
            "WHERE m.localMediaPath IS NOT NULL GROUP BY m.conversationId ORDER BY bytes DESC"
    )
    fun observeStorageByConversation(): Flow<List<StorageRow>>

    @Query("SELECT type, COUNT(*) AS files, COALESCE(SUM(mediaSizeBytes), 0) AS bytes FROM messages WHERE localMediaPath IS NOT NULL GROUP BY type")
    fun observeStorageByType(): Flow<List<MediaTypeCount>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND localMediaPath IS NOT NULL")
    suspend fun withLocalMedia(conversationId: String): List<Message>

    // ---- v4: media & link browser, AI context, diagnostics
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND type IN (:types) AND deletedForMe = 0 " +
            "AND deletedForEveryone = 0 AND createdAt > :clearedAt ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeByTypes(conversationId: String, clearedAt: Long, types: List<String>, limit: Int): Flow<List<Message>>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND deletedForMe = 0 AND deletedForEveryone = 0 " +
            "AND createdAt > :clearedAt AND (content LIKE '%http%' OR content LIKE '%www.%') ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeWithLinks(conversationId: String, clearedAt: Long, limit: Int): Flow<List<Message>>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND deletedForMe = 0 AND deletedForEveryone = 0 " +
            "AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun since(conversationId: String, since: Long, limit: Int): List<Message>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND deletedForMe = 0 AND deletedForEveryone = 0 " +
            "AND createdAt > :clearedAt ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun recent(conversationId: String, clearedAt: Long, limit: Int): List<Message>

    @Query("SELECT COUNT(*) FROM messages WHERE senderId = :me AND status = :status")
    fun observeOwnCount(me: String, status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE transferState = 'UPLOAD_FAILED'")
    fun observeFailedUploads(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND senderId != :me AND type != 'SYSTEM' " +
            "AND deletedForMe = 0 AND deletedForEveryone = 0 AND createdAt > :since"
    )
    suspend fun countIncomingSince(conversationId: String, me: String, since: Long): Int

    @Query("SELECT localMediaPath FROM messages WHERE localMediaPath IS NOT NULL")
    suspend fun allLocalMediaPaths(): List<String>

    @Query("UPDATE messages SET localMediaPath = NULL, transferState = 'NONE', transferProgress = 0 WHERE conversationId = :conversationId AND localMediaPath IS NOT NULL AND mediaUrl IS NOT NULL")
    suspend fun forgetLocalMedia(conversationId: String)
}

@Dao
interface ContactDao {
    @Query(
        "SELECT u.userId AS userId, u.displayName AS displayName, u.username AS username, u.uniqueId10 AS uniqueId10, " +
            "u.avatarUrl AS avatarUrl, u.avatarColorHex AS avatarColorHex, u.statusMessage AS statusMessage, c.customNickname AS customNickname " +
            "FROM contacts c JOIN users u ON u.userId = c.targetUserId WHERE c.ownerUserId = :owner " +
            "ORDER BY COALESCE(c.customNickname, u.displayName) COLLATE NOCASE"
    )
    fun observeContacts(owner: String): Flow<List<ContactRow>>

    /**
     * People that can be shared as a contact card: saved contacts plus the people of my one-to-one
     * chats, without blocked people and without anyone lacking a username.
     */
    @Query(
        "SELECT u.userId AS userId, u.displayName AS displayName, u.username AS username, u.uniqueId10 AS uniqueId10, " +
            "u.avatarUrl AS avatarUrl, u.avatarColorHex AS avatarColorHex, u.statusMessage AS statusMessage, ct.customNickname AS customNickname " +
            "FROM users u LEFT JOIN contacts ct ON ct.targetUserId = u.userId AND ct.ownerUserId = :owner " +
            "WHERE u.userId != :owner AND u.username != '' AND u.isAiAssistant = 0 " +
            "AND u.userId NOT IN (SELECT b.blockedUserId FROM blocked_users b WHERE b.ownerUserId = :owner) " +
            "AND (ct.targetUserId IS NOT NULL OR u.userId IN (SELECT c.peerId FROM conversations c WHERE c.isGroup = 0 AND c.isLocalOnly = 0 " +
            "AND c.peerId IS NOT NULL AND (',' || c.participantIds || ',') LIKE ('%,' || :owner || ',%'))) " +
            "ORDER BY COALESCE(ct.customNickname, u.displayName) COLLATE NOCASE"
    )
    fun observeShareable(owner: String): Flow<List<ContactRow>>

    @Query("SELECT targetUserId FROM contacts WHERE ownerUserId = :owner")
    suspend fun contactIds(owner: String): List<String>

    @Query("SELECT * FROM contacts WHERE ownerUserId = :owner AND targetUserId = :target LIMIT 1")
    suspend fun get(owner: String, target: String): Contact?

    @Query("SELECT * FROM contacts WHERE ownerUserId = :owner AND targetUserId = :target LIMIT 1")
    fun observe(owner: String, target: String): Flow<Contact?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: Contact)

    @Query("DELETE FROM contacts WHERE ownerUserId = :owner AND targetUserId = :target")
    suspend fun delete(owner: String, target: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(blocked: BlockedUser)

    @Query("DELETE FROM blocked_users WHERE ownerUserId = :owner AND blockedUserId = :target")
    suspend fun unblock(owner: String, target: String)

    @Query("SELECT blockedUserId FROM blocked_users WHERE ownerUserId = :owner")
    suspend fun blockedIds(owner: String): List<String>

    @Query("SELECT blockedUserId FROM blocked_users WHERE ownerUserId = :owner")
    fun observeBlockedIds(owner: String): Flow<List<String>>

    @Query("DELETE FROM blocked_users WHERE ownerUserId = :owner")
    suspend fun clearBlocked(owner: String)

    @Query("DELETE FROM contacts WHERE ownerUserId = :owner")
    suspend fun clearContacts(owner: String)
}

@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(receipt: Receipt)

    @Query("SELECT * FROM receipts WHERE conversationId = :conversationId")
    suspend fun forConversation(conversationId: String): List<Receipt>

    @Query("SELECT * FROM receipts WHERE conversationId = :conversationId")
    fun observeForConversation(conversationId: String): Flow<List<Receipt>>
}

@Dao
interface CallDao {
    @Query("SELECT * FROM calls WHERE callerId = :userId OR receiverId = :userId ORDER BY timestamp DESC LIMIT 300")
    fun observeHistory(userId: String): Flow<List<CallRecord>>

    @Query("SELECT * FROM calls WHERE callId = :callId")
    suspend fun get(callId: String): CallRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: CallRecord)

    @Query("DELETE FROM calls WHERE callId = :callId")
    suspend fun delete(callId: String)

    @Query("DELETE FROM calls WHERE callerId = :userId OR receiverId = :userId")
    suspend fun clearFor(userId: String)
}

@Dao
interface StatusDao {
    @Query("SELECT * FROM statuses WHERE expiresAt > :now ORDER BY createdAt ASC")
    fun observeActive(now: Long): Flow<List<StatusItem>>

    @Query("SELECT * FROM statuses WHERE statusId = :id")
    suspend fun get(id: String): StatusItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: StatusItem)

    @Query("UPDATE statuses SET isViewed = 1 WHERE statusId = :id")
    suspend fun markViewed(id: String)

    @Query("UPDATE statuses SET uploadState = :state, uploadProgress = :progress WHERE statusId = :id")
    suspend fun setUpload(id: String, state: String, progress: Int)

    @Query("DELETE FROM statuses WHERE statusId = :id")
    suspend fun delete(id: String)

    @Query("SELECT statusId FROM statuses WHERE userId != :me")
    suspend fun othersIds(me: String): List<String>

    @Query("DELETE FROM statuses WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM statuses")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertView(view: StatusView)

    @Query(
        "SELECT v.viewerId AS viewerId, v.viewedAt AS viewedAt, u.displayName AS displayName, u.avatarUrl AS avatarUrl, " +
            "u.avatarColorHex AS avatarColorHex FROM status_views v LEFT JOIN users u ON u.userId = v.viewerId " +
            "WHERE v.statusId = :statusId ORDER BY v.viewedAt DESC"
    )
    fun observeViews(statusId: String): Flow<List<StatusViewRow>>

    /** Keeps the author name and photo of status updates in step with the author's profile. */
    @Query("UPDATE statuses SET authorName = :name, authorPhotoUrl = :photo WHERE userId = :userId")
    suspend fun updateAuthor(userId: String, name: String, photo: String?)

    /** People who have seen at least one of these status updates (each person counted once). */
    @Query("SELECT COUNT(DISTINCT viewerId) FROM status_views WHERE statusId IN (:statusIds)")
    fun observeViewerCount(statusIds: List<String>): Flow<Int>
}

/** One-time re-keying of a legacy device-local account to its new Firebase Auth UID. */
@Dao
interface LegacyMigrationDao {
    @Query("UPDATE conversations SET participantIds = TRIM(REPLACE(',' || participantIds || ',', ',' || :oldId || ',', ',' || :newId || ','), ',')")
    suspend fun remapParticipants(oldId: String, newId: String)

    @Query("UPDATE conversations SET lastMessageSenderId = :newId WHERE lastMessageSenderId = :oldId")
    suspend fun remapLastSender(oldId: String, newId: String)

    @Query("UPDATE messages SET senderId = :newId WHERE senderId = :oldId")
    suspend fun remapSenders(oldId: String, newId: String)

    @Query("UPDATE messages SET recipientId = :newId WHERE recipientId = :oldId")
    suspend fun remapRecipients(oldId: String, newId: String)

    @Query("UPDATE OR IGNORE contacts SET ownerUserId = :newId WHERE ownerUserId = :oldId")
    suspend fun remapContactOwner(oldId: String, newId: String)

    @Query("UPDATE calls SET callerId = :newId WHERE callerId = :oldId")
    suspend fun remapCaller(oldId: String, newId: String)

    @Query("UPDATE calls SET receiverId = :newId WHERE receiverId = :oldId")
    suspend fun remapReceiver(oldId: String, newId: String)

    @Query("UPDATE statuses SET userId = :newId WHERE userId = :oldId")
    suspend fun remapStatuses(oldId: String, newId: String)
}
