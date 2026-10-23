package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.BlockedUser
import com.example.data.model.ChatNotificationSettings
import com.example.data.model.LockedChat
import com.example.data.model.ChatFolder
import com.example.data.model.ChatFolderMember
import com.example.data.model.DiagnosticEvent
import com.example.data.model.MessageTranscript
import com.example.data.model.MessageTranslation
import com.example.data.model.Reminder
import com.example.data.model.ScheduledMessage
import com.example.data.model.CallRecord
import com.example.data.model.Contact
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.Receipt
import com.example.data.model.StatusItem
import com.example.data.model.StatusLike
import com.example.data.model.StatusView
import com.example.data.model.User
import com.example.domain.MultiAccountPolicy
import java.util.concurrent.ConcurrentHashMap

@Database(
    entities = [
        User::class,
        Conversation::class,
        Message::class,
        Contact::class,
        CallRecord::class,
        StatusItem::class,
        Receipt::class,
        BlockedUser::class,
        StatusView::class,
        // v4
        ScheduledMessage::class,
        Reminder::class,
        ChatFolder::class,
        ChatFolderMember::class,
        MessageTranslation::class,
        MessageTranscript::class,
        DiagnosticEvent::class,
        // v5
        ChatNotificationSettings::class,
        LockedChat::class,
        // v7
        StatusLike::class,
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun callDao(): CallDao
    abstract fun statusDao(): StatusDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun legacyMigrationDao(): LegacyMigrationDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun reminderDao(): ReminderDao
    abstract fun folderDao(): FolderDao
    abstract fun translationDao(): TranslationDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun diagnosticDao(): DiagnosticDao
    abstract fun chatSettingsDao(): ChatSettingsDao

    companion object {
        const val NAME = "pulsechat_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null
        private val ACCOUNT_DBS = ConcurrentHashMap<String, AppDatabase>()

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, NAME).also { INSTANCE = it }
            }
        }

        /** Dedicated per-account Room SQLite database for strict multi-account local storage isolation. */
        fun getDatabaseForAccount(context: Context, uid: String): AppDatabase {
            val dbName = MultiAccountPolicy.databaseNameForAccount(uid)
            return ACCOUNT_DBS[dbName] ?: synchronized(ACCOUNT_DBS) {
                ACCOUNT_DBS[dbName] ?: build(context, dbName).also { ACCOUNT_DBS[dbName] = it }
            }
        }

        /** Closes and deletes the isolated local Room database file for [uid] when removed from device. */
        fun deleteDatabaseForAccount(context: Context, uid: String): Boolean {
            val dbName = MultiAccountPolicy.databaseNameForAccount(uid)
            synchronized(ACCOUNT_DBS) {
                ACCOUNT_DBS.remove(dbName)?.close()
            }
            return context.applicationContext.deleteDatabase(dbName)
        }

        fun deleteAccountDatabase(context: Context, uid: String): Boolean =
            deleteDatabaseForAccount(context, uid)

        private fun build(context: Context, dbName: String): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, dbName)
                .addMigrations(*Migrations.ALL)
                // Version 1 pre-dates any released schema and has no recoverable layout. Every
                // later version must ship a real Migration: never add a blanket destructive fallback.
                .fallbackToDestructiveMigrationFrom(true, 1)
                .build()
    }
}
