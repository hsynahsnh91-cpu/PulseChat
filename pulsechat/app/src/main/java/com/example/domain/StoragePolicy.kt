package com.example.domain

import com.example.domain.model.MessageType

/** What PulseChat keeps on the phone, as shown in Settings › Storage. */
enum class StorageCategory { PHOTOS, VIDEOS, VOICE, DOCUMENTS, STATUSES, UNUSED, TEMPORARY, APP_DATA }

/** One file (path, size, last change). */
data class FileEntry(val path: String, val bytes: Long, val modifiedAt: Long)

data class StorageReport(
    val sizes: Map<StorageCategory, Long>,
    val counts: Map<StorageCategory, Int>,
    /** Free space on the phone and its total size. */
    val freeBytes: Long,
    val deviceBytes: Long,
) {
    fun size(c: StorageCategory): Long = sizes[c] ?: 0L
    fun count(c: StorageCategory): Int = counts[c] ?: 0
    val totalBytes: Long get() = sizes.values.sum()
    val mediaBytes: Long get() = MEDIA.sumOf { size(it) }
    /** What "Clear temporary files" can free: caches, unfinished captures, files no message uses. */
    val clearableBytes: Long get() = size(StorageCategory.TEMPORARY) + size(StorageCategory.UNUSED)
    fun share(c: StorageCategory): Float = if (totalBytes > 0) size(c).toFloat() / totalBytes else 0f

    companion object {
        val MEDIA = listOf(StorageCategory.PHOTOS, StorageCategory.VIDEOS, StorageCategory.VOICE, StorageCategory.DOCUMENTS, StorageCategory.STATUSES)
        val EMPTY = StorageReport(emptyMap(), emptyMap(), 0L, 0L)
    }
}

object StoragePolicy {
    /** A file in the media folder is "unused" only if no message or status points to it and it is this old. */
    const val UNUSED_MIN_AGE_MS = 60 * 60_000L
    /** Camera captures, recordings and video edits younger than this may still be in use: kept. */
    const val CAPTURE_MIN_AGE_MS = 10 * 60_000L

    fun categoryFor(type: MessageType): StorageCategory = when (type) {
        MessageType.IMAGE -> StorageCategory.PHOTOS
        MessageType.VIDEO -> StorageCategory.VIDEOS
        MessageType.AUDIO -> StorageCategory.VOICE
        else -> StorageCategory.DOCUMENTS
    }

    /** Media-folder files no message or status refers to (old enough not to be a send in progress). */
    fun unusedFiles(files: List<FileEntry>, referenced: Set<String>, now: Long): List<FileEntry> =
        files.filter { it.path !in referenced && now - it.modifiedAt >= UNUSED_MIN_AGE_MS }

    /** May this temporary file be deleted now? Captures in progress (recent) are kept. */
    fun deletableTemp(entry: FileEntry, inCaptureFolder: Boolean, now: Long): Boolean =
        !inCaptureFolder || now - entry.modifiedAt >= CAPTURE_MIN_AGE_MS

    fun report(
        media: Map<StorageCategory, List<FileEntry>>,
        unused: List<FileEntry>,
        temporaryBytes: Long,
        temporaryFiles: Int,
        appDataBytes: Long,
        freeBytes: Long,
        deviceBytes: Long,
    ): StorageReport {
        val sizes = mutableMapOf<StorageCategory, Long>()
        val counts = mutableMapOf<StorageCategory, Int>()
        for ((c, files) in media) {
            sizes[c] = (sizes[c] ?: 0L) + files.sumOf { it.bytes }
            counts[c] = (counts[c] ?: 0) + files.size
        }
        sizes[StorageCategory.UNUSED] = unused.sumOf { it.bytes }
        counts[StorageCategory.UNUSED] = unused.size
        sizes[StorageCategory.TEMPORARY] = temporaryBytes.coerceAtLeast(0L)
        counts[StorageCategory.TEMPORARY] = temporaryFiles.coerceAtLeast(0)
        sizes[StorageCategory.APP_DATA] = appDataBytes.coerceAtLeast(0L)
        return StorageReport(sizes, counts, freeBytes.coerceAtLeast(0L), deviceBytes.coerceAtLeast(0L))
    }
}
