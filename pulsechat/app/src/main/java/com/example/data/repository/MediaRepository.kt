package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.example.data.prefs.AppPreferences
import com.example.data.remote.Fs
import com.example.domain.MediaLimits
import com.example.domain.MediaRefs
import com.example.domain.VideoEditMath
import com.example.domain.model.MessageType
import com.example.media.VideoTrimmer
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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

/** Where an uploaded file lives: [url] is what other clients load, [path] is what deletes it. */
data class UploadResult(val url: String, val path: String)

class MediaTooLargeException(val limitBytes: Long) : Exception("media too large")

/**
 * Media files are copied into app-private storage (`files/media`) so they survive the source app
 * revoking URI permissions, then uploaded. Uploads go to Cloud Storage when the project has a
 * usable bucket; otherwise (no default bucket, or the bucket is not usable on the current plan)
 * they go to PulseChat's built-in media store in Firestore (see [MediaRefs]), so media keeps
 * working either way. Downloads follow the reference each message/status carries.
 * Uploads/downloads report progress and can be cancelled by key (message ID / status ID).
 */
class MediaRepository(
    private val context: Context,
    private val storageProvider: () -> FirebaseStorage,
    private val firestoreProvider: () -> FirebaseFirestore,
    private val prefs: AppPreferences,
    private val currentUid: () -> String?,
) {
    val mediaDir: File = File(context.filesDir, "media").apply { mkdirs() }
    val captureDir: File = File(context.cacheDir, "capture").apply { mkdirs() }
    val remoteCacheDir: File = File(context.cacheDir, "remote-media").apply { mkdirs() }
    /** Video editor support: cuts without re-encoding, frames, covers. */
    val videoTrimmer = VideoTrimmer(context)
    private val uploads = ConcurrentHashMap<String, UploadTask>()
    private val downloads = ConcurrentHashMap<String, FileDownloadTask>()
    private val storeJobs = ConcurrentHashMap<String, Job>()
    private val cacheLocks = ConcurrentHashMap<String, Mutex>()

    /** Tests can force the built-in store (the emulator suite always has Cloud Storage). */
    @VisibleForTesting
    @Volatile
    var forceBuiltInStore: Boolean = false

    fun authority(): String = "${context.packageName}.fileprovider"
    fun contentUriFor(path: String): Uri = FileProvider.getUriForFile(context, authority(), File(path))

    /** A temporary file the camera/recorder can write into, plus its shareable content URI. */
    fun newCaptureFile(extension: String): Pair<File, Uri> {
        val file = File(captureDir, "cap_${System.currentTimeMillis()}.$extension")
        return file to FileProvider.getUriForFile(context, authority(), file)
    }

    /** Size limit for [type] given where uploads currently go. */
    fun effectiveMaxBytes(type: MessageType): Long =
        if (storageLikelyAvailable()) MediaLimits.maxBytes(type) else minOf(MediaLimits.maxBytes(type), MediaRefs.MAX_BYTES)

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
        if (target.length() == 0L) { target.delete(); throw IllegalArgumentException("empty file") }
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

    // ------------------------------------------------------------------ uploads

    /**
     * Uploads [file] for [key] (message/status ID). [storagePath] is the Cloud Storage path the
     * storage rules allow; when Cloud Storage is unavailable the file goes to the built-in store.
     */
    suspend fun upload(
        key: String,
        file: File,
        storagePath: String,
        mime: String,
        customMetadata: Map<String, String> = emptyMap(),
        onProgress: (Int) -> Unit,
    ): UploadResult {
        if (!file.exists() || file.length() == 0L) throw FileNotFoundException("missing media file")
        if (storageLikelyAvailable()) {
            try {
                return UploadResult(uploadToStorage(key, file, storagePath, mime, customMetadata, onProgress), storagePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isStorageUnavailable(e)) throw e
                markStorageUnavailable(e)
            }
        }
        onProgress(0)
        return trackStoreJob(key) {
            uploadToStore(file.length(), { file.inputStream() }, mime, MediaRefs.mediaIdFor(key), storagePath, onProgress)
        }
    }

    /** Uploads a small in-memory file (profile and group photos). */
    suspend fun uploadBytes(
        bytes: ByteArray,
        storagePath: String,
        mime: String,
        customMetadata: Map<String, String> = emptyMap(),
    ): UploadResult {
        if (bytes.isEmpty()) throw IllegalArgumentException("empty media")
        if (storageLikelyAvailable()) {
            try {
                val ref = storageProvider().reference.child(storagePath)
                ref.putBytes(bytes, buildStorageMetadata(mime, customMetadata)).await()
                return UploadResult(ref.downloadUrl.await().toString(), storagePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isStorageUnavailable(e)) throw e
                markStorageUnavailable(e)
            }
        }
        return uploadToStore(bytes.size.toLong(), { ByteArrayInputStream(bytes) }, mime, MediaRefs.newMediaId(), storagePath) { }
    }

    private fun buildStorageMetadata(mime: String, customMetadata: Map<String, String>): StorageMetadata {
        val builder = StorageMetadata.Builder().setContentType(mime)
        for ((k, v) in customMetadata) {
            if (k.isNotBlank()) builder.setCustomMetadata(k, v)
        }
        return builder.build()
    }

    private suspend fun uploadToStorage(
        key: String,
        file: File,
        storagePath: String,
        mime: String,
        customMetadata: Map<String, String>,
        onProgress: (Int) -> Unit,
    ): String {
        val ref = storageProvider().reference.child(storagePath)
        val task = ref.putFile(Uri.fromFile(file), buildStorageMetadata(mime, customMetadata))
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

    /** Writes the metadata document, then the chunks in order (the reader trusts `chunks`). */
    private suspend fun uploadToStore(
        size: Long,
        open: () -> InputStream,
        mime: String,
        mediaId: String,
        storagePath: String? = null,
        onProgress: (Int) -> Unit,
    ): UploadResult =
        withContext(Dispatchers.IO) {
            val owner = currentUid() ?: throw IllegalStateException("not signed in")
            if (!MediaRefs.fits(size)) throw MediaTooLargeException(MediaRefs.MAX_BYTES)
            val chunks = MediaRefs.chunkCount(size)
            val doc = firestoreProvider().collection(Fs.MEDIA).document(mediaId)
            val metaFields = mutableMapOf<String, Any>(
                "owner" to owner,
                "mime" to mime.take(128),
                "size" to size,
                "chunks" to chunks,
                "createdAt" to FieldValue.serverTimestamp(),
            )
            metaFields.putAll(MediaRefs.scopeFieldsForStoragePath(storagePath))
            withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                doc.set(metaFields).await()
                true
            } ?: throw IOException("media upload timed out")
            open().use { input ->
                val buffer = ByteArray(MediaRefs.CHUNK_BYTES)
                for (i in 0 until chunks) {
                    currentCoroutineContext().ensureActive()
                    val n = readFully(input, buffer)
                    if (n <= 0) throw IOException("media file changed while uploading")
                    val data = Blob.fromBytes(if (n == buffer.size) buffer.copyOf() else buffer.copyOf(n))
                    withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                        doc.collection(Fs.MEDIA_CHUNKS).document(i.toString()).set(mapOf("owner" to owner, "data" to data)).await()
                        true
                    } ?: throw IOException("media upload timed out")
                    onProgress(((i + 1) * 100) / chunks)
                }
            }
            val ref = MediaRefs.ref(mediaId)
            UploadResult(ref, ref)
        }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    fun cancelUpload(key: String) {
        uploads.remove(key)?.cancel()
        storeJobs.remove(key)?.cancel()
    }

    fun isUploading(key: String): Boolean = uploads.containsKey(key) || storeJobs.containsKey(key)

    // ------------------------------------------------------------------ downloads

    suspend fun download(key: String, storagePath: String?, url: String?, fileName: String?, mime: String?, onProgress: (Int) -> Unit): File {
        val ext = extensionFor(mime ?: guessMime(fileName ?: ""), fileName ?: "")
        val target = File(mediaDir, "${UUID.randomUUID()}.$ext")
        val storeId = MediaRefs.idOf(url) ?: MediaRefs.idOf(storagePath)
        if (storeId != null) {
            try {
                trackStoreJob(key) { downloadFromStore(storeId, target, onProgress) }
                return target
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
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

    private suspend fun downloadFromStore(mediaId: String, target: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val doc = firestoreProvider().collection(Fs.MEDIA).document(mediaId)
        val meta = withTimeoutOrNull(READ_TIMEOUT_MS) { doc.get().await() } ?: throw IOException("media download timed out")
        if (!meta.exists()) throw FileNotFoundException("media not found")
        val chunks = (meta.getLong("chunks") ?: 0L).toInt()
        if (chunks !in 1..MediaRefs.MAX_CHUNKS) throw IOException("invalid media")
        FileOutputStream(target).use { out ->
            for (i in 0 until chunks) {
                currentCoroutineContext().ensureActive()
                val snap = withTimeoutOrNull(READ_TIMEOUT_MS) { doc.collection(Fs.MEDIA_CHUNKS).document(i.toString()).get().await() }
                    ?: throw IOException("media download timed out")
                val blob = snap.getBlob("data") ?: throw IOException("incomplete media")
                out.write(blob.toBytes())
                onProgress(((i + 1) * 100) / chunks)
            }
        }
    }

    fun cancelDownload(key: String) {
        downloads.remove(key)?.cancel()
        storeJobs.remove(key)?.cancel()
    }

    /**
     * A local copy of remote media for direct display (profile/group photos and statuses, via the
     * image loader and the video player). Built-in store references are cached by media ID (the
     * content never changes); other URLs are returned unchanged by callers.
     */
    suspend fun cachedStoreFile(ref: String): File {
        val mediaId = MediaRefs.idOf(ref) ?: throw IllegalArgumentException("not a built-in media reference")
        val file = File(remoteCacheDir, mediaId)
        if (file.exists() && file.length() > 0) return file
        val lock = cacheLocks.getOrPut(mediaId) { Mutex() }
        return lock.withLock {
            if (file.exists() && file.length() > 0) return@withLock file
            val tmp = File(remoteCacheDir, "$mediaId.part")
            try {
                downloadFromStore(mediaId, tmp) { }
                if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            } finally {
                tmp.delete()
            }
            file
        }
    }

    /**
     * Runs a built-in store transfer as a child job, so [cancelUpload]/[cancelDownload] stop only the
     * transfer: the caller sees a [CancellationException] but stays active to record the outcome.
     */
    private suspend fun <T> trackStoreJob(key: String, block: suspend () -> T): T = coroutineScope {
        val transfer = async { block() }
        storeJobs[key] = transfer
        try {
            transfer.await()
        } finally {
            storeJobs.remove(key, transfer)
        }
    }

    // ------------------------------------------------------------------ cleanup

    fun deleteLocal(paths: Collection<String?>) {
        paths.filterNotNull().forEach { p -> runCatching { File(p).takeIf { it.absolutePath.startsWith(mediaDir.absolutePath) }?.delete() } }
    }

    /** Deletes an uploaded file (Cloud Storage path or built-in store reference). Best effort. */
    suspend fun deleteRemote(storagePath: String?) {
        if (storagePath.isNullOrBlank()) return
        val storeId = MediaRefs.idOf(storagePath)
        if (storeId != null) {
            runCatching {
                val fs = firestoreProvider()
                val doc = fs.collection(Fs.MEDIA).document(storeId)
                val chunks = (doc.get().await().getLong("chunks") ?: 0L).toInt().coerceIn(0, MediaRefs.MAX_CHUNKS)
                val batch = fs.batch()
                for (i in 0 until chunks) batch.delete(doc.collection(Fs.MEDIA_CHUNKS).document(i.toString()))
                batch.delete(doc)
                batch.commit().await()
            }.onFailure { Log.w(TAG, "could not delete media: ${it.message}") }
            File(remoteCacheDir, storeId).delete()
            return
        }
        runCatching { storageProvider().reference.child(storagePath).delete().await() }
    }

    /**
     * Cover image for a video message: the frame at [coverMs] as a small JPEG that travels with the
     * message, so the other side sees it before downloading the video. Null if no frame can be read.
     */
    suspend fun videoCover(path: String, coverMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        val frame = videoTrimmer.frame(Uri.fromFile(File(path)), coverMs, VideoEditMath.COVER_MAX_DIM, exact = true)
            ?: videoTrimmer.frame(Uri.fromFile(File(path)), 0L, VideoEditMath.COVER_MAX_DIM)
        frame?.let { videoTrimmer.coverJpeg(it) }
    }

    fun totalLocalBytes(): Long = mediaDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun freeBytes(): Long = context.filesDir.usableSpace

    /** Removes media files no longer referenced by any message/status row. */
    fun deleteOrphans(referenced: Set<String>) {
        mediaDir.listFiles()?.forEach { f -> if (f.isFile && f.absolutePath !in referenced) f.delete() }
        captureDir.listFiles()?.forEach { f -> if (System.currentTimeMillis() - f.lastModified() > 24 * 3600_000L) f.delete() }
    }

    // ------------------------------------------------------------------ Cloud Storage availability

    private fun storageLikelyAvailable(): Boolean = !forceBuiltInStore && System.currentTimeMillis() >= prefs.storageUnavailableUntil

    private fun markStorageUnavailable(e: Exception) {
        Log.i(TAG, "Cloud Storage is not usable for this project (${e.message}); using the built-in media store")
        prefs.storageUnavailableUntil = System.currentTimeMillis() + STORAGE_RECHECK_MS
    }

    /** Errors that mean "this project has no usable bucket", as opposed to network trouble. */
    private fun isStorageUnavailable(e: Throwable): Boolean {
        if (e is IllegalStateException || e is IllegalArgumentException) return true // no bucket configured
        val se = (e as? StorageException) ?: (e.cause as? StorageException) ?: return false
        return when (se.errorCode) {
            StorageException.ERROR_OBJECT_NOT_FOUND, StorageException.ERROR_BUCKET_NOT_FOUND, StorageException.ERROR_PROJECT_NOT_FOUND,
            StorageException.ERROR_NOT_AUTHORIZED, StorageException.ERROR_QUOTA_EXCEEDED -> true
            StorageException.ERROR_UNKNOWN -> se.httpResultCode in setOf(400, 402, 403, 404, 412)
            else -> false
        }
    }

    companion object {
        private const val TAG = "MediaRepository"
        private const val WRITE_TIMEOUT_MS = 90_000L
        private const val READ_TIMEOUT_MS = 60_000L
        private const val STORAGE_RECHECK_MS = 6 * 60 * 60 * 1000L

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
