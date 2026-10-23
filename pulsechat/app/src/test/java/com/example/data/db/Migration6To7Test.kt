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
import com.example.data.model.StatusLike
import kotlinx.coroutines.flow.first
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
 * Builds the exact version-6 schema from schemas/.../6.json, seeds statuses, conversations, and
 * messages, then opens it with the current v7 AppDatabase so Room runs MIGRATION_6_7 and validates
 * every table and index against the v7 schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Migration6To7Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-6-7-test.db"

    @Before fun setUp() { context.deleteDatabase(dbName) }
    @After fun tearDown() { context.deleteDatabase(dbName) }

    private val v6 = JSONObject(File("schemas/com.example.data.db.AppDatabase/6.json").readText()).getJSONObject("database")

    private fun createV6(seed: (SupportSQLiteDatabase) -> Unit) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = v6.getJSONArray("entities")
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
                    val setup = v6.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        seed(helper.writableDatabase)
        helper.close()
    }

    private fun insert(db: SupportSQLiteDatabase, table: String, values: Map<String, Any?>) {
        val entities = v6.getJSONArray("entities")
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
                else -> cv.put(k, v.toString())
            }
        }
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
    }

    @Test
    fun migrate6To7PreservesDataAndAddsStatusMusicLikesGroupMentionAllPollAndMediaQualityColumns() {
        createV6 { db ->
            insert(db, "users", mapOf("userId" to "u1", "uniqueId10" to "1234567890", "username" to "alice", "displayName" to "Alice"))
            insert(db, "conversations", mapOf("conversationId" to "g1", "isGroup" to 1, "title" to "Zürich Team", "participantIds" to "u1,u2"))
            insert(db, "messages", mapOf("messageId" to "p1", "conversationId" to "g1", "senderId" to "u1", "content" to "Lunch?", "type" to "POLL",
                "status" to "SENT", "createdAt" to 1500L, "transferState" to "NONE", "pollQuestion" to "Lunch?", "pollOptions" to "Fondue|Rösti"))
            insert(db, "statuses", mapOf("statusId" to "s1", "userId" to "u1", "authorName" to "Alice", "contentText" to "Sunny day",
                "type" to "TEXT", "createdAt" to 1000L, "expiresAt" to 90_000L))
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            val conv = db.conversationDao().get("g1")!!
            assertEquals("Zürich Team", conv.title)
            assertTrue(conv.onlyAdminsCanMentionAll)
            assertEquals(0L, conv.lastMentionAllAt)

            val msg = db.messageDao().get("p1")!!
            assertEquals("Lunch?", msg.pollQuestion)
            assertFalse(msg.pollAnonymous)
            assertEquals(0L, msg.pollExpiresAt)
            assertEquals("STANDARD", msg.mediaQuality)
            assertEquals(0L, msg.originalSizeBytes)

            val status = db.statusDao().get("s1")!!
            assertEquals("Sunny day", status.contentText)
            assertTrue(status.allowReshare)
            assertNull(status.musicTrackId)
            assertEquals(0, status.likeCount)
            assertFalse(status.isLikedByMe)

            // Verify status_likes table and status like counters work after migration
            db.statusDao().insertLike(StatusLike(statusId = "s1", userId = "u1", likedAt = 2500L))
            db.statusDao().setLikeState("s1", likeCount = 1, isLikedByMe = true)
            val updatedStatus = db.statusDao().get("s1")!!
            assertEquals(1, updatedStatus.likeCount)
            assertTrue(updatedStatus.isLikedByMe)
            val likes = db.statusDao().observeLikes("s1").first()
            assertEquals(1, likes.size)
            assertEquals("u1", likes.first().userId)
            assertEquals("Alice", likes.first().displayName)
        }
        db.close()
    }
}
