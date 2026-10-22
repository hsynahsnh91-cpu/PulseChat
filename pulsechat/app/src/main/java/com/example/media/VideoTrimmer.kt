package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.domain.VideoEditMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** What the video editor needs to know about a video. */
data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val sizeBytes: Long,
    /** Keyframe times in ms, ascending: a cut can only start on one of them. */
    val keyframesMs: List<Long>,
    val hasAudio: Boolean,
) {
    /** Width/height as displayed (rotation applied). */
    val displayAspect: Float get() = if (width <= 0 || height <= 0) 16f / 9f else if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
}

class VideoTrimException(message: String) : Exception(message)

/**
 * Trimming and frames for the video editor, with the platform's own media classes (no extra
 * library). A cut copies the original compressed audio and video samples between the start and
 * the end into a new MP4 - no re-encoding, so it takes seconds, keeps the original quality and
 * does not drain the battery. Such a cut starts on a keyframe (see [VideoEditMath.snapStart]).
 * Location and other metadata of the original are not copied.
 */
class VideoTrimmer(private val context: Context) {

    suspend fun probe(uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
        var duration = 0L
        var width = 0
        var height = 0
        var rotation = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            // Unreadable: reported to the user by the editor (duration 0).
        } finally {
            runCatching { retriever.release() }
        }
        val keyframes = ArrayList<Long>()
        var hasAudio = false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var video = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video < 0) video = i
                if (mime.startsWith("audio/")) hasAudio = true
            }
            if (video >= 0) {
                extractor.selectTrack(video)
                var scanned = 0
                while (scanned < MAX_SAMPLES_SCANNED) {
                    val t = extractor.sampleTime
                    if (t < 0) break
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keyframes += t / 1000
                    if (!extractor.advance()) break
                    scanned++
                }
            }
        } catch (e: Exception) {
            // Keyframes unknown: cuts then start at the beginning of the video.
        } finally {
            runCatching { extractor.release() }
        }
        VideoInfo(duration, width, height, rotation, sizeOf(uri), keyframes.distinct().sorted(), hasAudio)
    }

    /** One frame near [timeMs], at most [maxDim] px on its longer side ([exact] = that very frame, slower). */
    suspend fun frame(uri: Uri, timeMs: Long, maxDim: Int, exact: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            frameAt(retriever, timeMs, maxDim, exact)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Small frames for the editor's filmstrip (one reader for all of them). */
    suspend fun filmstrip(uri: Uri, timesMs: List<Long>, maxDim: Int): List<Bitmap?> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            timesMs.map { t -> runCatching { frameAt(retriever, t, maxDim, exact = false) }.getOrNull() }
        } catch (e: Exception) {
            timesMs.map { null }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeMs: Long, maxDim: Int, exact: Boolean): Bitmap? {
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val us = timeMs.coerceAtLeast(0L) * 1000L
        val raw = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(us, option, maxDim, maxDim) else retriever.getFrameAtTime(us, option)
        return raw?.let { scaledDown(it, maxDim) }
    }

    /**
     * Copies [startMs]..[endMs] of [uri] into [out] (MP4) without re-encoding and returns the real
     * start: the keyframe at or before [startMs]. The output timeline starts at 0.
     */
    suspend fun trim(uri: Uri, startMs: Long, endMs: Long, out: File, onProgress: (Int) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxing = false
        try {
            extractor.setDataSource(context, uri, null)
            val targets = HashMap<Int, Int>()
            var videoTrack = -1
            var rotation = 0
            var bufferSize = 1 shl 20
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val isVideo = mime.startsWith("video/")
                if (!isVideo && !mime.startsWith("audio/")) continue
                if (isVideo && videoTrack >= 0) continue
                if (isVideo) {
                    videoTrack = i
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                targets[i] = m.addTrack(format)
                extractor.selectTrack(i)
            }
            if (videoTrack < 0) throw VideoTrimException("no video track")
            if (rotation == 0) rotation = metadataRotation(uri)
            m.setOrientationHint(rotation)
            m.start()
            muxing = true

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            var baseUs = -1L
            val finished = HashSet<Int>()
            var lastPercent = -1
            while (true) {
                currentCoroutineContext().ensureActive()
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                val target = targets[track]
                val keyframe = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                val copy = when {
                    target == null || track in finished -> false
                    // The cut starts with the first video keyframe; earlier audio is dropped.
                    baseUs < 0 && (track != videoTrack || !keyframe) -> false
                    time > endUs -> { finished += track; false }
                    baseUs >= 0 && time < baseUs -> false
                    else -> true
                }
                if (copy && target != null) {
                    if (baseUs < 0) baseUs = time
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, time - baseUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(target, buffer, info)
                    val percent = (((time - baseUs) * 100) / (endUs - baseUs).coerceAtLeast(1L)).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                }
                if (finished.size == targets.size) break
                if (!extractor.advance()) break
            }
            if (baseUs < 0) throw VideoTrimException("nothing to copy")
            m.stop()
            muxing = false
            onProgress(100)
            baseUs / 1000L
        } catch (e: CancellationException) {
            out.delete()
            throw e
        } catch (e: VideoTrimException) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            throw VideoTrimException(e.message ?: e.javaClass.simpleName)
        } finally {
            if (muxing) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /** JPEG cover for the message (small enough to travel with it), or null if it can't be made small enough. */
    fun coverJpeg(frame: Bitmap, maxBytes: Int = VideoEditMath.COVER_MAX_BYTES, maxDim: Int = VideoEditMath.COVER_MAX_DIM): ByteArray? {
        var bitmap = scaledDown(frame, maxDim)
        var quality = 72
        repeat(8) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes) return out.toByteArray()
            if (quality > 42) quality -= 15 else bitmap = scaledDown(bitmap, (maxOf(bitmap.width, bitmap.height) * 3) / 4)
        }
        return null
    }

    private fun metadataRotation(uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sizeOf(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: 0L
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        /** Keyframe scan limit (about half an hour of 30 fps video). */
        private const val MAX_SAMPLES_SCANNED = 60_000

        fun scaledDown(bitmap: Bitmap, maxDim: Int): Bitmap {
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= maxDim || longest <= 0) return bitmap
            val scale = maxDim.toFloat() / longest
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
    }
}
