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
import com.example.data.model.ChatFolder
import com.example.data.model.ChatFolderMember
import com.example.data.model.ScheduledMessage
import com.example.domain.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds the exact version-3 schema from the exported schema file (schemas/.../3.json), fills it
 * with data, then opens it with the current AppDatabase. Room runs MIGRATION_3_4 and validates
 * every table (old and new) against the v4 entities, so a wrong column, nullability, primary key or
 * index in the migration fails this test. Existing rows must survive unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Migration3To4Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-3-4-test.db"

    @Before fun setUp() { context.deleteDatabase(dbName) }
    @After fun tearDown() { context.deleteDatabase(dbName) }

    private val v3 = JSONObject(File("schemas/com.example.data.db.AppDatabase/3.json").readText()).getJSONObject("database")

    private fun createV3(seed: (SupportSQLiteDatabase) -> Unit) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = v3.getJSONArray("entities")
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
                    val setup = v3.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        seed(helper.writableDatabase)
        helper.close()
    }

    /** Inserts a v3 row: every NOT NULL column gets a neutral value unless given in [values]. */
    private fun insert(db: SupportSQLiteDatabase, table: String, values: Map<String, Any?>) {
        val entity = (0 until v3.getJSONArray("entities").length()).map { v3.getJSONArray("entities").getJSONObject(it) }.first { it.getString("tableName") == table }
        val cv = ContentValues()
        val fields = entity.getJSONArray("fields")
        for (i in 0 until fields.length()) {
            val f = fields.getJSONObject(i)
            val col = f.getString("columnName")
            if (col in values) continue
            if (!f.optBoolean("notNull")) continue
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
    fun migrate3To4AddsFeatureTablesAndKeepsAllData() {
        createV3 { db ->
            insert(db, "users", mapOf("userId" to "u1", "username" to "sara", "displayName" to "Sara", "uniqueId10" to "1234567890"))
            insert(db, "conversations", mapOf("conversationId" to "c1", "title" to "Omar", "participantIds" to "u1,u2", "peerId" to "u2", "lastMessageTimestamp" to 2000L))
            insert(db, "messages", mapOf("messageId" to "m1", "conversationId" to "c1", "senderId" to "u2", "content" to "hello https://a.io", "type" to "TEXT",
                "status" to "READ", "createdAt" to 1500L, "transferState" to "NONE", "reactions" to "{\"u1\":\"👍\"}"))
            insert(db, "receipts", mapOf("conversationId" to "c1", "userId" to "u2", "deliveredUpTo" to 1500L, "readUpTo" to 1500L))
            insert(db, "blocked_users", mapOf("ownerUserId" to "u1", "blockedUserId" to "u9", "blockedAt" to 10L))
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            assertEquals("Sara", db.userDao().get("u1")!!.displayName)
            assertEquals("Omar", db.conversationDao().get("c1")!!.title)
            val m = db.messageDao().get("m1")!!
            assertEquals("hello https://a.io", m.content)
            assertEquals(MessageStatus.READ, m.status)
            assertEquals(mapOf("u1" to "👍"), m.reactionMap())
            assertEquals(listOf("u9"), db.contactDao().blockedIds("u1"))

            // The new tables work.
            db.scheduledMessageDao().insert(ScheduledMessage("s1", "u1", "c1", "later", 5000L, 1L, 1L))
            assertEquals(1, db.scheduledMessageDao().claim("s1", 6000L, 0L))
            db.folderDao().insertFolder(ChatFolder("f1", "u1", "Work", 0, 1L))
            db.folderDao().addMembers(listOf(ChatFolderMember("f1", "c1", 2L)))
            assertEquals(setOf("c1"), db.folderDao().memberIds("f1").toSet())
            assertTrue(db.reminderDao().scheduled().isEmpty())
        }
        db.close()
    }
}
