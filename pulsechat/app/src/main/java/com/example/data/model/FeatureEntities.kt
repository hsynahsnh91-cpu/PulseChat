package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room schema v4 (phase 1 features). All tables are local to this device; nothing here is synced.
 * Timestamps are UTC epoch milliseconds.
 */

/** States of a scheduled message. */
object ScheduledState {
    const val PENDING = "PENDING"   // waiting for its time (or for a retry)
    const val SENDING = "SENDING"   // claimed by exactly one sender
    const val SENT = "SENT"         // the server acknowledged the message
    const val FAILED = "FAILED"     // permanent failure; the user can retry, edit or delete it
}

/**
 * A text message to be sent later. [id] is also the Firestore message document ID, so every
 * attempt (retries, restarts, a second device run, "send now") writes the same document: the
 * message can exist at most once on the server.
 */
@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["state", "scheduledAt"]), Index("conversationId")],
)
data class ScheduledMessage(
    @PrimaryKey val id: String,
    val ownerId: String,
    val conversationId: String,
    val text: String,
    val scheduledAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String = ScheduledState.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val sentAt: Long = 0L,
)

object ReminderState {
    const val SCHEDULED = "SCHEDULED"
    const val FIRED = "FIRED"       // notification shown, not yet marked done
    const val DONE = "DONE"
}

object ReminderSource {
    const val MESSAGE = "MESSAGE"   // "Remind me" on a message
    const val AI_TASK = "AI_TASK"   // confirmed suggestion from "Find tasks & events"
    const val AI_EVENT = "AI_EVENT"
}

/** A local reminder (notification at [remindAt]), optionally pointing at a message. */
@Entity(
    tableName = "reminders",
    indices = [Index(value = ["state", "remindAt"]), Index("messageId")],
)
data class Reminder(
    @PrimaryKey val id: String,
    val ownerId: String,
    val conversationId: String?,
    val messageId: String?,
    val title: String,
    val remindAt: Long,
    val createdAt: Long,
    val state: String = ReminderState.SCHEDULED,
    val source: String = ReminderSource.MESSAGE,
    val firedAt: Long = 0L,
)

/** A user-defined chat folder (shown as a filter chip on the Chats tab). */
@Entity(tableName = "chat_folders", indices = [Index("ownerId")])
data class ChatFolder(
    @PrimaryKey val folderId: String,
    val ownerId: String,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "chat_folder_members",
    primaryKeys = ["folderId", "conversationId"],
    indices = [Index("conversationId")],
)
data class ChatFolderMember(
    val folderId: String,
    val conversationId: String,
    val addedAt: Long,
)

/** Cached on-device translation; [sourceHash] invalidates it when the message is edited. */
@Entity(tableName = "message_translations", primaryKeys = ["messageId", "targetLang"])
data class MessageTranslation(
    val messageId: String,
    val targetLang: String,
    val sourceLang: String,
    val sourceHash: String,
    val text: String,
    val createdAt: Long,
)

/** Transcript of a voice message, created on the user's request. */
@Entity(tableName = "message_transcripts")
data class MessageTranscript(
    @PrimaryKey val messageId: String,
    val text: String,
    val engine: String,
    val createdAt: Long,
)

/** A problem shown in Settings › Diagnostics (codes and short non-personal details only). */
@Entity(tableName = "diagnostic_events", indices = [Index("at")])
data class DiagnosticEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val category: String,
    val code: String,
    val detail: String? = null,
)

/** Chat folder with its member count (for the folder manager). */
data class FolderWithCount(
    val folderId: String,
    val name: String,
    val sortOrder: Int,
    val members: Int,
)
