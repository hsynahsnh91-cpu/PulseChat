package com.example.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.Message
import com.example.data.model.StatusItem
import com.example.data.prefs.AppPreferences
import com.example.data.repository.MediaRepository
import com.example.data.repository.StorageManager
import com.example.domain.StorageCategory
import com.example.domain.model.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Settings › Storage: counting by kind, and clearing only what can be rebuilt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StorageManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var media: MediaRepository
    private lateinit var storage: StorageManager
    private val hourAgo = System.currentTimeMillis() - 2 * 3_600_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        media = MediaRepository(context, { error("no storage in this test") }, { error("no firestore in this test") }, AppPreferences(context), { "me" })
        storage = StorageManager(context, db, media)
        media.mediaDir.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        media.captureDir.mkdirs()
        media.remoteCacheDir.mkdirs()
    }

    @After fun tearDown() { db.close() }

    private fun file(dir: File, name: String, bytes: Int, modifiedAt: Long = hourAgo): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)); setLastModified(modifiedAt) }

    @Test
    fun countsMediaByKindAndClearsOnlyTemporaryAndUnusedFiles() = runBlocking {
        val photo = file(media.mediaDir, "p.jpg", 1_000)
        val video = file(media.mediaDir, "v.mp4", 5_000)
        val status = file(media.mediaDir, "s.jpg", 700)
        val oldUnused = file(media.mediaDir, "orphan.jpg", 300)
        val newUnused = file(media.mediaDir, "sending.mp4", 400, modifiedAt = System.currentTimeMillis())
        db.messageDao().upsert(Message(messageId = "m1", conversationId = "c1", senderId = "me", type = MessageType.IMAGE, localMediaPath = photo.absolutePath))
        db.messageDao().upsert(Message(messageId = "m2", conversationId = "c1", senderId = "me", type = MessageType.VIDEO, localMediaPath = video.absolutePath))
        db.statusDao().upsert(StatusItem(statusId = "st1", userId = "me", mediaType = "IMAGE", localMediaPath = status.absolutePath))
        val oldCapture = file(media.captureDir, "cap_old.mp4", 2_000)
        val newCapture = file(media.captureDir, "cap_new.mp4", 900, modifiedAt = System.currentTimeMillis())
        val preview = file(media.remoteCacheDir, "md123", 600)

        val report = storage.scan()
        assertEquals(1_000L, report.size(StorageCategory.PHOTOS))
        assertEquals(5_000L, report.size(StorageCategory.VIDEOS))
        assertEquals(700L, report.size(StorageCategory.STATUSES))
        assertEquals("only old files no message uses", 300L, report.size(StorageCategory.UNUSED))
        assertTrue("captures and previews are temporary: ${report.size(StorageCategory.TEMPORARY)}", report.size(StorageCategory.TEMPORARY) >= 3_500L)
        assertEquals(6_700L, report.mediaBytes)

        val freed = storage.clearTemporary()
        assertTrue("freed old capture, preview and unused file: $freed", freed >= 2_900L)
        assertTrue(photo.exists() && video.exists() && status.exists())
        assertTrue("a file being sent is kept", newUnused.exists())
        assertTrue("a capture in progress is kept", newCapture.exists())
        assertFalse(oldCapture.exists())
        assertFalse(preview.exists())
        assertFalse(oldUnused.exists())
        assertTrue("folders stay for the parts of the app that write into them", media.captureDir.isDirectory && media.remoteCacheDir.isDirectory)

        val after = storage.scan()
        assertEquals(0L, after.size(StorageCategory.UNUSED))
        assertEquals(6_700L, after.mediaBytes)
    }
}
