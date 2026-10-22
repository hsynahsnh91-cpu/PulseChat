package com.example.data.repository

import android.content.Context
import android.os.StatFs
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.example.data.db.AppDatabase
import com.example.domain.FileEntry
import com.example.domain.StorageCategory
import com.example.domain.StoragePolicy
import com.example.domain.StorageReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What PulseChat keeps on the phone (Settings › Storage): media by kind, temporary files (image
 * cache, previews of media from the built-in store, unfinished captures and video edits), files no
 * message uses any more, and the app's own data (messages database, settings, language packs).
 *
 * "Clear temporary files" only removes things that can be rebuilt or downloaded again: it never
 * touches a file a message or status still uses, a capture made in the last minutes, or the
 * messages themselves.
 */
class StorageManager(private val context: Context, private val db: AppDatabase, private val media: MediaRepository) {

    private val imageCacheDir: File get() = File(context.cacheDir, IMAGE_CACHE_DIR)

    suspend fun scan(): StorageReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val used = HashMap<StorageCategory, MutableList<FileEntry>>()
        val referenced = HashSet<String>()
        for (row in db.messageDao().localMediaFiles()) {
            val f = File(row.localMediaPath)
            if (!f.isFile || !referenced.add(f.absolutePath)) continue
            used.getOrPut(StoragePolicy.categoryFor(row.type)) { mutableListOf() } += entry(f)
        }
        for (path in db.statusDao().localMediaPaths()) {
            val f = File(path)
            if (!f.isFile || !referenced.add(f.absolutePath)) continue
            used.getOrPut(StorageCategory.STATUSES) { mutableListOf() } += entry(f)
        }
        val unused = StoragePolicy.unusedFiles(mediaFolderFiles(), referenced, now)
        val (tempBytes, tempFiles) = treeSize(context.cacheDir)
        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        StoragePolicy.report(
            media = used,
            unused = unused,
            temporaryBytes = tempBytes,
            temporaryFiles = tempFiles,
            appDataBytes = appDataBytes(),
            freeBytes = stat?.availableBytes ?: 0L,
            deviceBytes = stat?.totalBytes ?: 0L,
        )
    }

    /** Deletes temporary and unused files; returns how many bytes were freed. */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearTemporary(): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var freed = 0L
        // The image cache belongs to the image loader: cleared through it, so it stays consistent.
        val loader = context.imageLoader
        val imageBytes = treeSize(imageCacheDir).first
        runCatching { loader.memoryCache?.clear() }
        if (runCatching { loader.diskCache?.clear() }.isSuccess) freed += (imageBytes - treeSize(imageCacheDir).first).coerceAtLeast(0L)
        context.cacheDir.listFiles()?.forEach { child ->
            if (child.absolutePath == imageCacheDir.absolutePath) return@forEach
            freed += deleteFiles(child, inCapture = child.absolutePath == media.captureDir.absolutePath, now = now)
        }
        // Files in the media folder that no message or status refers to (and that are not new).
        val referenced = HashSet<String>()
        db.messageDao().localMediaFiles().forEach { referenced += File(it.localMediaPath).absolutePath }
        db.statusDao().localMediaPaths().forEach { referenced += File(it).absolutePath }
        for (e in StoragePolicy.unusedFiles(mediaFolderFiles(), referenced, now)) {
            val f = File(e.path)
            val length = f.length()
            if (f.delete()) freed += length
        }
        freed
    }

    private fun entry(f: File) = FileEntry(f.absolutePath, f.length(), f.lastModified())

    private fun mediaFolderFiles(): List<FileEntry> =
        media.mediaDir.listFiles()?.filter { it.isFile }?.map { entry(it) }.orEmpty()

    /** Deletes the files (never the folders: other parts of the app write into them) that may go now. */
    private fun deleteFiles(f: File, inCapture: Boolean, now: Long): Long {
        if (f.isDirectory) return if (isLink(f)) 0L else f.listFiles()?.sumOf { deleteFiles(it, inCapture, now) } ?: 0L
        if (!StoragePolicy.deletableTemp(entry(f), inCapture, now)) return 0L
        val length = f.length()
        return if (f.delete()) length else 0L
    }

    /** Bytes and number of files under [dir] (links to other folders are not followed). */
    private fun treeSize(dir: File): Pair<Long, Int> {
        if (!dir.exists()) return 0L to 0
        var bytes = 0L
        var files = 0
        dir.walkTopDown().onEnter { it == dir || !isLink(it) }.forEach { if (it.isFile) { bytes += it.length(); files++ } }
        return bytes to files
    }

    /** A symbolic link (e.g. to the installed app's native libraries) - counted elsewhere or not at all. */
    private fun isLink(f: File): Boolean = runCatching {
        val parent = f.parentFile?.canonicalFile ?: return@runCatching false
        val resolved = File(parent, f.name)
        resolved.canonicalPath != resolved.absolutePath
    }.getOrDefault(false)

    /** The app's own data: database, settings, language packs (everything but media and the cache). */
    private fun appDataBytes(): Long {
        val dataDir = context.filesDir.parentFile ?: return 0L
        var bytes = 0L
        dataDir.listFiles()?.forEach { child ->
            when {
                child.name in SKIPPED_DATA_DIRS || isLink(child) -> Unit
                child.absolutePath == context.filesDir.absolutePath ->
                    child.listFiles()?.forEach { f -> if (f.absolutePath != media.mediaDir.absolutePath) bytes += treeSize(f).first }
                child.isFile -> bytes += child.length()
                else -> bytes += treeSize(child).first
            }
        }
        return bytes
    }

    companion object {
        /** Coil's default disk cache folder inside the app cache. */
        const val IMAGE_CACHE_DIR = "image_cache"
        /** The cache is counted as temporary files; native libraries and compiled code are not app data. */
        private val SKIPPED_DATA_DIRS = setOf("cache", "code_cache", "lib")
    }
}
