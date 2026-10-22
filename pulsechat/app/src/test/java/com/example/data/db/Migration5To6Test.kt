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
import com.example.domain.model.MessageType
import com.example.domain.model.TransferState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds the exact version-5 schema from schemas/.../5.json, fills it, then opens it with the current
 * AppDatabase: Room runs MIGRATION_5_6 and validates every table against the v6 entities. Existing
 * rows survive; video messages get an empty cover until a new one arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Migration5To6Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-5-6-test.db"

    @Before fun setUp() { context.deleteDatabase(dbName) }
    @After fun tearDown() { context.deleteDatabase(dbName) }

    private val v5 = JSONObject(File("schemas/com.example.data.db.AppDatabase/5.json").readText()).getJSONObject("database")

    private fun createV5(seed: (SupportSQLiteDatabase) -> Unit) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = v5.getJSONArray("entities")
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
                    val setup = v5.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        seed(helper.writableDatabase)
        helper.close()
    }

    /** Inserts a v5 row: every NOT NULL column gets a neutral value unless given in [values]. */
    private fun insert(db: SupportSQLiteDatabase, table: String, values: Map<String, Any?>) {
        val entities = v5.getJSONArray("entities")
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
    fun migrate5To6AddsVideoCoverColumnsAndKeepsAllData() {
        createV5 { db ->
            insert(db, "conversations", mapOf("conversationId" to "c1", "title" to "Omar", "participantIds" to "u1,u2", "peerId" to "u2", "lastMessageTimestamp" to 2000L))
            insert(db, "messages", mapOf("messageId" to "v1", "conversationId" to "c1", "senderId" to "u2", "content" to "holiday", "type" to "VIDEO",
                "status" to "DELIVERED", "createdAt" to 1500L, "transferState" to "NONE", "mediaUrl" to "pulsemedia://md1", "mediaDurationSeconds" to 12,
                "mediaWidth" to 1280, "mediaHeight" to 720))
            insert(db, "messages", mapOf("messageId" to "t1", "conversationId" to "c1", "senderId" to "u1", "content" to "nice", "type" to "TEXT",
                "status" to "SENT", "createdAt" to 1600L, "transferState" to "NONE", "editHistory" to ""))
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            val video = db.messageDao().get("v1")!!
            assertEquals(MessageType.VIDEO, video.type)
            assertEquals("holiday", video.content)
            assertEquals(12, video.mediaDurationSeconds)
            assertEquals("pulsemedia://md1", video.mediaUrl)
            assertNull(video.mediaThumb)
            assertEquals(0L, video.mediaCoverMs)
            assertEquals("nice", db.messageDao().get("t1")!!.content)

            // The new columns store a cover.
            db.messageDao().upsert(video.copy(mediaThumb = "AAEC", mediaCoverMs = 1_200L, transferState = TransferState.NONE))
            val updated = db.messageDao().get("v1")!!
            assertEquals("AAEC", updated.mediaThumb)
            assertEquals(1_200L, updated.mediaCoverMs)
            // Storage accounting query (no local files yet).
            assertEquals(0, db.messageDao().localMediaFiles().size)
        }
        db.close()
    }
}
