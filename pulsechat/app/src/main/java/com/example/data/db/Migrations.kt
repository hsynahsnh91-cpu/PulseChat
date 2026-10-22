package com.example.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room schema migrations. Each step preserves all existing rows. */
object Migrations {

    /**
     * v2 -> v3: cloud sync, per-chat preferences, message features (edit/delete/reactions/
     * replies/mentions/media transfer/disappearing), receipts, blocking and status views.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `users` ADD COLUMN `profileSyncedAt` INTEGER NOT NULL DEFAULT 0")

            val conversationColumns = listOf(
                "`description` TEXT",
                "`adminIds` TEXT NOT NULL DEFAULT ''",
                "`createdBy` TEXT",
                "`onlyAdminsCanSend` INTEGER NOT NULL DEFAULT 0",
                "`onlyAdminsCanEditInfo` INTEGER NOT NULL DEFAULT 1",
                "`disappearingSeconds` INTEGER NOT NULL DEFAULT 0",
                "`lastMessageId` TEXT",
                "`lastMessageType` TEXT",
                "`peerId` TEXT",
                "`pinnedAt` INTEGER NOT NULL DEFAULT 0",
                "`isArchived` INTEGER NOT NULL DEFAULT 0",
                "`mutedUntil` INTEGER NOT NULL DEFAULT 0",
                "`isFavorite` INTEGER NOT NULL DEFAULT 0",
                "`markedUnread` INTEGER NOT NULL DEFAULT 0",
                "`draftText` TEXT",
                "`draftUpdatedAt` INTEGER NOT NULL DEFAULT 0",
                "`lastReadAt` INTEGER NOT NULL DEFAULT 0",
                "`clearedAt` INTEGER NOT NULL DEFAULT 0",
                "`isHidden` INTEGER NOT NULL DEFAULT 0",
                "`syncCursor` INTEGER NOT NULL DEFAULT 0",
                "`remoteUpdatedAt` INTEGER NOT NULL DEFAULT 0",
                "`isLeft` INTEGER NOT NULL DEFAULT 0",
                "`isLocalOnly` INTEGER NOT NULL DEFAULT 0",
            )
            conversationColumns.forEach { db.execSQL("ALTER TABLE `conversations` ADD COLUMN $it") }

            val messageColumns = listOf(
                "`updatedAt` INTEGER NOT NULL DEFAULT 0",
                "`editedAt` INTEGER NOT NULL DEFAULT 0",
                "`deletedForEveryone` INTEGER NOT NULL DEFAULT 0",
                "`deletedForMe` INTEGER NOT NULL DEFAULT 0",
                "`isStarred` INTEGER NOT NULL DEFAULT 0",
                "`isForwarded` INTEGER NOT NULL DEFAULT 0",
                "`reactions` TEXT NOT NULL DEFAULT ''",
                "`mentions` TEXT NOT NULL DEFAULT ''",
                "`replyToSenderId` TEXT",
                "`replyToPreview` TEXT",
                "`replyToType` TEXT",
                "`localMediaPath` TEXT",
                "`mediaMimeType` TEXT",
                "`mediaSizeBytes` INTEGER NOT NULL DEFAULT 0",
                "`mediaWidth` INTEGER NOT NULL DEFAULT 0",
                "`mediaHeight` INTEGER NOT NULL DEFAULT 0",
                "`waveform` TEXT",
                "`transferState` TEXT NOT NULL DEFAULT 'NONE'",
                "`transferProgress` INTEGER NOT NULL DEFAULT 0",
                "`storagePath` TEXT",
                "`contactName` TEXT",
                "`contactPhone` TEXT",
                "`pollVotesByUser` TEXT NOT NULL DEFAULT ''",
                "`expiresAt` INTEGER NOT NULL DEFAULT 0",
                "`failureReason` TEXT",
                "`systemEvent` TEXT",
            )
            messageColumns.forEach { db.execSQL("ALTER TABLE `messages` ADD COLUMN $it") }
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` ON `messages` (`conversationId`, `createdAt`)")

            val statusColumns = listOf(
                "`localMediaPath` TEXT",
                "`uploadState` TEXT NOT NULL DEFAULT 'DONE'",
                "`uploadProgress` INTEGER NOT NULL DEFAULT 0",
                "`audience` TEXT NOT NULL DEFAULT ''",
                "`storagePath` TEXT",
            )
            statusColumns.forEach { db.execSQL("ALTER TABLE `statuses` ADD COLUMN $it") }

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `receipts` (`conversationId` TEXT NOT NULL, `userId` TEXT NOT NULL, " +
                    "`deliveredUpTo` INTEGER NOT NULL, `readUpTo` INTEGER NOT NULL, PRIMARY KEY(`conversationId`, `userId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `blocked_users` (`ownerUserId` TEXT NOT NULL, `blockedUserId` TEXT NOT NULL, " +
                    "`blockedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerUserId`, `blockedUserId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `status_views` (`statusId` TEXT NOT NULL, `viewerId` TEXT NOT NULL, " +
                    "`viewedAt` INTEGER NOT NULL, PRIMARY KEY(`statusId`, `viewerId`))"
            )

            // ---- Data back-fill (no row is deleted) ----
            // Conversations created before cloud sync only ever existed on this device.
            db.execSQL("UPDATE `conversations` SET `isLocalOnly` = 1")
            db.execSQL("UPDATE `conversations` SET `pinnedAt` = `lastMessageTimestamp` WHERE `isPinned` = 1")
            // Keep the read state users already had: fully read chats get a read watermark.
            db.execSQL("UPDATE `conversations` SET `lastReadAt` = `lastMessageTimestamp` WHERE `unreadCount` = 0")
            db.execSQL(
                "UPDATE `conversations` SET `lastMessageId` = (SELECT m.`messageId` FROM `messages` m " +
                    "WHERE m.`conversationId` = `conversations`.`conversationId` ORDER BY m.`createdAt` DESC LIMIT 1)"
            )
            db.execSQL("UPDATE `messages` SET `updatedAt` = `createdAt`")
            // Blocked flags move to the dedicated table (the legacy column is kept for compatibility).
            db.execSQL(
                "INSERT OR IGNORE INTO `blocked_users` (`ownerUserId`, `blockedUserId`, `blockedAt`) " +
                    "SELECT `ownerUserId`, `targetUserId`, `addedAt` FROM `contacts` WHERE `isBlocked` = 1"
            )
        }
    }

    /**
     * v3 -> v4: scheduled messages, reminders, chat folders, translation/transcript caches and the
     * diagnostics log. Only new tables: existing tables and rows are untouched.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scheduled_messages` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                    "`text` TEXT NOT NULL, `scheduledAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "`state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, `sentAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_messages_state_scheduledAt` ON `scheduled_messages` (`state`, `scheduledAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_messages_conversationId` ON `scheduled_messages` (`conversationId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `conversationId` TEXT, `messageId` TEXT, " +
                    "`title` TEXT NOT NULL, `remindAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `firedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_state_remindAt` ON `reminders` (`state`, `remindAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_messageId` ON `reminders` (`messageId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_folders` (`folderId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`folderId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_folders_ownerId` ON `chat_folders` (`ownerId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_folder_members` (`folderId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`folderId`, `conversationId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_folder_members_conversationId` ON `chat_folder_members` (`conversationId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `message_translations` (`messageId` TEXT NOT NULL, `targetLang` TEXT NOT NULL, `sourceLang` TEXT NOT NULL, " +
                    "`sourceHash` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `targetLang`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `message_transcripts` (`messageId` TEXT NOT NULL, `text` TEXT NOT NULL, `engine` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `diagnostic_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, " +
                    "`category` TEXT NOT NULL, `code` TEXT NOT NULL, `detail` TEXT)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnostic_events_at` ON `diagnostic_events` (`at`)")
        }
    }

    /**
     * v4 -> v5: per-chat notification settings and chat locks (new tables, keyed by account so two
     * accounts on one phone never share them), plus edit history and live-location columns on
     * messages. Existing rows are untouched; mutes kept on conversations are moved to the new table
     * at the next sign-in (the migration cannot know which account they belong to).
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_notification_settings` (`ownerId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                    "`mutedUntil` INTEGER NOT NULL, `preview` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `conversationId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `locked_chats` (`ownerId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                    "`lockedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `conversationId`))"
            )
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `editHistory` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `liveId` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `liveUntil` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
