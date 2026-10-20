package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.domain.model.MessageType
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LocalMedia(
    val path: String,
    val mime: String,
    val sizeBytes: Long,
    val fileName: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
)

class MediaTooLargeException(val limitBytes: Long) : Exception("media too large")

/**
 * Media files are copied into app-private storage (`files/media`) so they survive the source app
 * revoking URI permissions, then uploaded to Firebase Storage. Uploads/downloads report progress
 * and can be cancelled by key (message ID / status ID).
 */
class MediaRepository(private val context: Context, private val storageProvider: () -> FirebaseStorage) {
    val mediaDir: File = File(context.filesDir, "media").apply { mkdirs() }
    private val captureDir: File = File(context.cacheDir, "capture").apply { mkdirs() }
    private val uploads = ConcurrentHashMap<String, UploadTask>()
    private val downloads = ConcurrentHashMap<String, FileDownloadTask>()

    fun authority(): String = "${context.packageName}.fileprovider"
    fun contentUriFor(path: String): Uri = FileProvider.getUriForFile(context, authority(), File(path))

    /** A temporary file the camera/recorder can write into, plus its shareable content URI. */
    fun newCaptureFile(extension: String): Pair<File, Uri> {
        val file = File(captureDir, "cap_${System.currentTimeMillis()}.$extension")
        return file to FileProvider.getUriForFile(context, authority(), file)
    }

    suspend fun importUri(uri: Uri, type: MessageType, maxBytes: Long): LocalMedia = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
        val mime = resolver.getType(uri) ?: guessMime(name)
        if (size > maxBytes && type != MessageType.IMAGE) throw MediaTooLargeException(maxBytes)
        val ext = extensionFor(mime, name)
        val target = File(mediaDir, "${UUID.randomUUID()}.$ext")
        if (type == MessageType.IMAGE && mime != "image/gif") {
            val bytes = compressImage(uri, 1600, 82) ?: throw IllegalArgumentException("unreadable image")
            target.writeBytes(bytes)
        } else {
            resolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                ?: throw IllegalArgumentException("unreadable file")
        }
        if (target.length() > maxBytes) { target.delete(); throw MediaTooLargeException(maxBytes) }
        describe(target, if (type == MessageType.IMAGE && mime != "image/gif") "image/jpeg" else mime, name)
    }

    /** Moves a finished capture/recording into media storage. */
    suspend fun importFile(file: File, mime: String, displayName: String): LocalMedia = withContext(Dispatchers.IO) {
        val target = File(mediaDir, "${UUID.randomUUID()}.${extensionFor(mime, file.name)}")
        if (!file.renameTo(target)) { file.copyTo(target, overwrite = true); file.delete() }
        describe(target, mime, displayName)
    }

    private fun describe(file: File, mime: String, name: String): LocalMedia {
        var width = 0; var height = 0; var duration = 0L
        when {
            mime.startsWith("image/") -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, o)
                width = o.outWidth; height = o.outHeight
            }
            mime.startsWith("video/") || mime.startsWith("audio/") -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                } catch (e: Exception) {
                    // Unreadable metadata is not fatal.
                } finally {
                    runCatching { r.release() }
                }
            }
        }
        return LocalMedia(file.absolutePath, mime, file.length(), name, width, height, duration)
    }

    /** Downscales and re-encodes an image to JPEG (also strips EXIF metadata such as GPS). */
    suspend fun compressImage(uri: Uri, maxDim: Int, quality: Int): ByteArray? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext null
        val scale = minOf(1f, maxDim.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bitmap !== decoded) decoded.recycle()
        bitmap.recycle()
        out.toByteArray()
    }

    suspend fun upload(key: String, file: File, storagePath: String, mime: String, onProgress: (Int) -> Unit): String {
        val ref = storageProvider().reference.child(storagePath)
        val task = ref.putFile(Uri.fromFile(file), StorageMetadata.Builder().setContentType(mime).build())
        uploads[key] = task
        task.addOnProgressListener { snap ->
            val total = snap.totalByteCount
            if (total > 0) onProgress(((snap.bytesTransferred * 100) / total).toInt().coerceIn(0, 100))
        }
        try {
            task.await()
            return ref.downloadUrl.await().toString()
        } catch (e: CancellationException) {
            task.cancel()
            throw e
        } finally {
            uploads.remove(key)
        }
    }

    suspend fun uploadBytes(bytes: ByteArray, storagePath: String, mime: String): String {
        val ref = storageProvider().reference.child(storagePath)
        ref.putBytes(bytes, StorageMetadata.Builder().setContentType(mime).build()).await()
        return ref.downloadUrl.await().toString()
    }

    fun cancelUpload(key: String) { uploads.remove(key)?.cancel() }
    fun isUploading(key: String): Boolean = uploads.containsKey(key)

    suspend fun download(key: String, storagePath: String?, url: String?, fileName: String?, mime: String?, onProgress: (Int) -> Unit): File {
        val ext = extensionFor(mime ?: guessMime(fileName ?: ""), fileName ?: "")
        val target = File(mediaDir, "${UUID.randomUUID()}.$ext")
        val storage = storageProvider()
        val ref = when {
            !storagePath.isNullOrBlank() -> storage.reference.child(storagePath)
            !url.isNullOrBlank() -> storage.getReferenceFromUrl(url)
            else -> throw IllegalArgumentException("no media source")
        }
        val task = ref.getFile(target)
        downloads[key] = task
        task.addOnProgressListener { snap ->
            val total = snap.totalByteCount
            if (total > 0) onProgress(((snap.bytesTransferred * 100) / total).toInt().coerceIn(0, 100))
        }
        try {
            task.await()
            return target
        } catch (e: Exception) {
            target.delete()
            if (e is CancellationException) task.cancel()
            throw e
        } finally {
            downloads.remove(key)
        }
    }

    fun cancelDownload(key: String) { downloads.remove(key)?.cancel() }

    fun deleteLocal(paths: Collection<String?>) {
        paths.filterNotNull().forEach { p -> runCatching { File(p).takeIf { it.absolutePath.startsWith(mediaDir.absolutePath) }?.delete() } }
    }

    suspend fun deleteRemote(storagePath: String?) {
        if (storagePath.isNullOrBlank()) return
        runCatching { storageProvider().reference.child(storagePath).delete().await() }
    }

    fun totalLocalBytes(): Long = mediaDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun freeBytes(): Long = context.filesDir.usableSpace

    /** Removes media files no longer referenced by any message/status row. */
    fun deleteOrphans(referenced: Set<String>) {
        mediaDir.listFiles()?.forEach { f -> if (f.isFile && f.absolutePath !in referenced) f.delete() }
        captureDir.listFiles()?.forEach { f -> if (System.currentTimeMillis() - f.lastModified() > 24 * 3600_000L) f.delete() }
    }

    companion object {
        fun guessMime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        fun extensionFor(mime: String, name: String): String {
            val fromName = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
            return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: fromName ?: "bin"
        }

        fun typeForMime(mime: String): MessageType = when {
            mime.startsWith("image/") -> MessageType.IMAGE
            mime.startsWith("video/") -> MessageType.VIDEO
            mime.startsWith("audio/") -> MessageType.AUDIO
            else -> MessageType.DOCUMENT
        }
    }
}
