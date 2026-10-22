package com.example.data.db

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatNotificationSettings
import com.example.data.model.LockedChat
import com.example.domain.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds the exact version-4 schema from schemas/.../4.json, fills it, then opens it with the current
 * AppDatabase: Room runs MIGRATION_4_5 and validates every table against the v5 entities. Existing
 * rows must survive; the old per-conversation mute moves to the new settings table at sign-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Migration4To5Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-4-5-test.db"

    @Before fun setUp() { context.deleteDatabase(dbName) }
    @After fun tearDown() { context.deleteDatabase(dbName) }

    private val v4 = JSONObject(File("schemas/com.example.data.db.AppDatabase/4.json").readText()).getJSONObject("database")

    private fun createV4(seed: (SupportSQLiteDatabase) -> Unit) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = v4.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val e = entities.getJSONObject(i)
                        val table = e.getString("tableName")
                        db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = e.optJSONArray("indices") ?: continue
                        for (j in 0 until indices.length()) {
                            val ix = indices.getJSONObject(j)
                            db.execSQL(ix.getString("createSql").replace("\${TABLE_NAME}", table).replace("\${INDEX_NAME}", ix.getString("name")))
                        }
                    }
                    val setup = v4.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        seed(helper.writableDatabase)
        helper.close()
    }

    /** Inserts a v4 row: every NOT NULL column gets a neutral value unless given in [values]. */
    private fun insert(db: SupportSQLiteDatabase, table: String, values: Map<String, Any?>) {
        val entities = v4.getJSONArray("entities")
        val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
        val cv = ContentValues()
        val fields = entity.getJSONArray("fields")
        for (i in 0 until fields.length()) {
            val f = fields.getJSONObject(i)
            val col = f.getString("columnName")
            if (col in values || !f.optBoolean("notNull")) continue
            when (f.getString("affinity")) {
                "INTEGER" -> cv.put(col, 0L)
                "REAL" -> cv.put(col, 0.0)
                else -> cv.put(col, "")
            }
        }
        values.forEach { (k, v) ->
            when (v) {
                null -> cv.putNull(k)
                is Long -> cv.put(k, v)
                is Int -> cv.put(k, v)
                is Boolean -> cv.put(k, if (v) 1 else 0)
                else -> cv.put(k, v.toString())
            }
        }
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
    }

    @Test
    fun migrate4To5AddsSettingsLocksAndMessageColumnsAndKeepsAllData() {
        createV4 { db ->
            insert(db, "users", mapOf("userId" to "u1", "username" to "sara", "displayName" to "Sara", "uniqueId10" to "1234567890"))
            insert(db, "conversations", mapOf("conversationId" to "c1", "title" to "Omar", "participantIds" to "u1,u2", "peerId" to "u2",
                "lastMessageTimestamp" to 2000L, "mutedUntil" to Long.MAX_VALUE))
            insert(db, "conversations", mapOf("conversationId" to "c2", "title" to "Team", "participantIds" to "u1,u3", "isGroup" to 1, "mutedUntil" to 0L))
            insert(db, "messages", mapOf("messageId" to "m1", "conversationId" to "c1", "senderId" to "u2", "content" to "edited text", "type" to "TEXT",
                "status" to "READ", "createdAt" to 1500L, "editedAt" to 1600L, "transferState" to "NONE"))
            insert(db, "messages", mapOf("messageId" to "m2", "conversationId" to "c1", "senderId" to "u1", "content" to "", "type" to "LOCATION",
                "status" to "SENT", "createdAt" to 1700L, "locationLat" to "52.0", "locationLng" to "4.3", "transferState" to "NONE"))
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            // Old rows are intact; new columns have neutral values.
            val m1 = db.messageDao().get("m1")!!
            assertEquals("edited text", m1.content)
            assertEquals(MessageStatus.READ, m1.status)
            assertEquals(1600L, m1.editedAt)
            assertEquals("", m1.editHistory)
            val m2 = db.messageDao().get("m2")!!
            assertNull(m2.liveId)
            assertEquals(0L, m2.liveUntil)
            assertEquals(52.0, m2.locationLat!!, 1e-9)

            // The old mute moves to the per-account settings at sign-in, then the old column is cleared.
            val settings = db.chatSettingsDao()
            assertNull(settings.notificationSettings("u1", "c1"))
            settings.importLegacyMutes("u1")
            db.conversationDao().clearLegacyMutes("u1")
            assertEquals(Long.MAX_VALUE, settings.notificationSettings("u1", "c1")!!.mutedUntil)
            assertEquals(ChatNotificationSettings.PREVIEW_DEFAULT, settings.notificationSettings("u1", "c1")!!.preview)
            assertNull(settings.notificationSettings("u1", "c2"))
            assertEquals(0L, db.conversationDao().get("c1")!!.mutedUntil)

            // Chat locks work and stay per account.
            settings.lock(LockedChat("u1", "c1", 5L))
            assertTrue(settings.isLocked("u1", "c1"))
            assertFalse(settings.isLocked("u9", "c1"))
        }
        db.close()
    }
}
