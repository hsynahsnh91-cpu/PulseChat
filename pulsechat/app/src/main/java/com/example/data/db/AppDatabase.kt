package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.BlockedUser
import com.example.data.model.CallRecord
import com.example.data.model.Contact
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.Receipt
import com.example.data.model.StatusItem
import com.example.data.model.StatusView
import com.example.data.model.User

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
    ],
    version = 3,
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

    companion object {
        const val NAME = "pulsechat_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                // Version 1 pre-dates any released schema and has no recoverable layout. Every
                // later version must ship a real Migration: never add a blanket destructive fallback.
                .fallbackToDestructiveMigrationFrom(true, 1)
                .build()
    }
}
