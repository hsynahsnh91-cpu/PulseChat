package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.ChatFolder
import com.example.data.model.ChatFolderMember
import com.example.data.model.DiagnosticEvent
import com.example.data.model.FolderWithCount
import com.example.data.model.MessageTranscript
import com.example.data.model.MessageTranslation
import com.example.data.model.Reminder
import com.example.data.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: ScheduledMessage)

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun get(id: String): ScheduledMessage?

    @Query("SELECT * FROM scheduled_messages WHERE conversationId = :conversationId AND state != 'SENT' ORDER BY scheduledAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages WHERE ownerId = :owner AND state != 'SENT' ORDER BY scheduledAt ASC")
    fun observeActive(owner: String): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages WHERE state IN ('PENDING', 'SENDING') ORDER BY scheduledAt ASC")
    suspend fun active(): List<ScheduledMessage>

    /**
     * Atomically takes ownership of a due message. Only one caller can win: the row must still be
     * PENDING, or SENDING from an attempt that died (older than [staleBefore]). Returns rows changed.
     */
    @Query(
        "UPDATE scheduled_messages SET state = 'SENDING', attempts = attempts + 1, updatedAt = :now " +
            "WHERE id = :id AND (state = 'PENDING' OR (state = 'SENDING' AND updatedAt < :staleBefore))"
    )
    suspend fun claim(id: String, now: Long, staleBefore: Long): Int

    @Query("UPDATE scheduled_messages SET state = 'SENT', sentAt = :now, updatedAt = :now, lastError = NULL WHERE id = :id")
    suspend fun markSent(id: String, now: Long)

    @Query("UPDATE scheduled_messages SET state = 'FAILED', lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: String, error: String?, now: Long)

    /** Gives a claimed message back for a later retry. */
    @Query("UPDATE scheduled_messages SET state = 'PENDING', lastError = :error, updatedAt = :now WHERE id = :id AND state = 'SENDING'")
    suspend fun release(id: String, error: String?, now: Long): Int

    /** Edits text/time; impossible while the message is being sent or after it was sent. */
    @Query(
        "UPDATE scheduled_messages SET text = :text, scheduledAt = :at, state = 'PENDING', attempts = 0, lastError = NULL, updatedAt = :now " +
            "WHERE id = :id AND state IN ('PENDING', 'FAILED')"
    )
    suspend fun updateContent(id: String, text: String, at: Long, now: Long): Int

    @Query("UPDATE scheduled_messages SET scheduledAt = :at, state = 'PENDING', attempts = 0, lastError = NULL, updatedAt = :now WHERE id = :id AND state IN ('PENDING', 'FAILED')")
    suspend fun reschedule(id: String, at: Long, now: Long): Int

    @Query("DELETE FROM scheduled_messages WHERE id = :id AND state IN ('PENDING', 'FAILED')")
    suspend fun deleteIfEditable(id: String): Int

    @Query("DELETE FROM scheduled_messages WHERE state = 'SENT' AND sentAt < :before")
    suspend fun deleteSentBefore(before: Long)

    @Query("SELECT * FROM scheduled_messages WHERE ownerId = :owner AND state != 'SENT'")
    suspend fun unsentFor(owner: String): List<ScheduledMessage>

    @Query("DELETE FROM scheduled_messages WHERE ownerId = :owner AND state != 'SENT'")
    suspend fun deleteUnsentFor(owner: String)

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE state = :state")
    fun observeCount(state: String): Flow<Int>
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: Reminder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(reminders: List<Reminder>)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): Reminder?

    @Query("SELECT * FROM reminders WHERE ownerId = :owner AND state = 'SCHEDULED' ORDER BY remindAt ASC")
    fun observeUpcoming(owner: String): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE ownerId = :owner AND state != 'SCHEDULED' ORDER BY remindAt DESC LIMIT 100")
    fun observePast(owner: String): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE messageId IN (:messageIds) AND state = 'SCHEDULED'")
    fun observeForMessages(messageIds: List<String>): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE state = 'SCHEDULED'")
    suspend fun scheduled(): List<Reminder>

    /** Marks a reminder as fired; returns 0 when it was cancelled or already handled. */
    @Query("UPDATE reminders SET state = 'FIRED', firedAt = :now WHERE id = :id AND state = 'SCHEDULED'")
    suspend fun markFired(id: String, now: Long): Int

    @Query("UPDATE reminders SET state = 'DONE' WHERE id = :id")
    suspend fun markDone(id: String)

    @Query("UPDATE reminders SET remindAt = :at, state = 'SCHEDULED', firedAt = 0 WHERE id = :id")
    suspend fun reschedule(id: String, at: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM reminders WHERE ownerId = :owner AND state = 'SCHEDULED'")
    suspend fun scheduledFor(owner: String): List<Reminder>

    @Query("DELETE FROM reminders WHERE ownerId = :owner")
    suspend fun deleteFor(owner: String)

    @Query("SELECT COUNT(*) FROM reminders WHERE state = 'SCHEDULED'")
    fun observeScheduledCount(): Flow<Int>
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM chat_folders WHERE ownerId = :owner ORDER BY sortOrder ASC, createdAt ASC")
    fun observeFolders(owner: String): Flow<List<ChatFolder>>

    @Query(
        "SELECT f.folderId AS folderId, f.name AS name, f.sortOrder AS sortOrder, " +
            "(SELECT COUNT(*) FROM chat_folder_members m WHERE m.folderId = f.folderId) AS members " +
            "FROM chat_folders f WHERE f.ownerId = :owner ORDER BY f.sortOrder ASC, f.createdAt ASC"
    )
    fun observeFoldersWithCounts(owner: String): Flow<List<FolderWithCount>>

    @Query("SELECT m.* FROM chat_folder_members m INNER JOIN chat_folders f ON f.folderId = m.folderId WHERE f.ownerId = :owner")
    fun observeMembers(owner: String): Flow<List<ChatFolderMember>>

    @Query("SELECT * FROM chat_folders WHERE folderId = :folderId")
    suspend fun get(folderId: String): ChatFolder?

    @Query("SELECT * FROM chat_folders WHERE ownerId = :owner ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun folders(owner: String): List<ChatFolder>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM chat_folders WHERE ownerId = :owner")
    suspend fun maxOrder(owner: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: ChatFolder)

    @Query("UPDATE chat_folders SET name = :name WHERE folderId = :folderId")
    suspend fun rename(folderId: String, name: String)

    @Query("UPDATE chat_folders SET sortOrder = :order WHERE folderId = :folderId")
    suspend fun setOrder(folderId: String, order: Int)

    @Query("DELETE FROM chat_folders WHERE folderId = :folderId")
    suspend fun deleteFolderRow(folderId: String)

    @Query("DELETE FROM chat_folder_members WHERE folderId = :folderId")
    suspend fun deleteMembersOf(folderId: String)


    @Query("SELECT conversationId FROM chat_folder_members WHERE folderId = :folderId")
    suspend fun memberIds(folderId: String): List<String>

    @Query("SELECT folderId FROM chat_folder_members WHERE conversationId = :conversationId")
    suspend fun foldersOf(conversationId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMembers(members: List<ChatFolderMember>)

    @Query("DELETE FROM chat_folder_members WHERE folderId = :folderId AND conversationId IN (:conversationIds)")
    suspend fun removeMembers(folderId: String, conversationIds: List<String>)


    @Query("DELETE FROM chat_folders WHERE ownerId = :owner")
    suspend fun deleteFoldersFor(owner: String)
}

@Dao
interface TranslationDao {
    @Query("SELECT * FROM message_translations WHERE messageId = :messageId AND targetLang = :target")
    suspend fun get(messageId: String, target: String): MessageTranslation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(translation: MessageTranslation)

    @Query("DELETE FROM message_translations")
    suspend fun clear()
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM message_transcripts WHERE messageId = :messageId")
    suspend fun get(messageId: String): MessageTranscript?

    @Query("SELECT t.* FROM message_transcripts t INNER JOIN messages m ON m.messageId = t.messageId WHERE m.conversationId = :conversationId")
    fun observeForConversation(conversationId: String): Flow<List<MessageTranscript>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transcript: MessageTranscript)

    @Query("DELETE FROM message_transcripts WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}

@Dao
interface DiagnosticDao {
    @Insert
    suspend fun insert(event: DiagnosticEvent)

    @Query("SELECT * FROM diagnostic_events ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticEvent>>

    @Query("DELETE FROM diagnostic_events WHERE id NOT IN (SELECT id FROM diagnostic_events ORDER BY id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM diagnostic_events")
    suspend fun clear()
}
