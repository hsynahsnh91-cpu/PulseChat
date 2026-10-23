package com.example.domain

import com.example.domain.model.MessageType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Policy for HD and Original-Quality Media Sharing (Feature 5).
 *
 * - [MediaQuality.STANDARD]: fast everyday sharing (images max 1600px @ JPEG 82; videos standard limit).
 * - [MediaQuality.HD]: higher resolution and lower compression (images max 2560px @ JPEG 92; videos HD).
 *   Clearly indicates when a low-resolution source cannot benefit from HD (never upscaling).
 * - [MediaQuality.ORIGINAL]: sends the exact source bytes, resolution, and file format without
 *   re-encoding or silent recompression, subject to explicit size limits.
 */
enum class MediaQuality {
    STANDARD,
    HD,
    ORIGINAL;

    companion object {
        fun fromName(raw: String?): MediaQuality =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: STANDARD
    }
}

object MediaQualityPolicy {
    const val STANDARD_IMAGE_MAX_DIM = 1600
    const val STANDARD_IMAGE_JPEG_QUALITY = 82

    const val HD_IMAGE_MAX_DIM = 2560
    const val HD_IMAGE_JPEG_QUALITY = 92

    const val STANDARD_VIDEO_MAX_DIM = 1280
    const val HD_VIDEO_MAX_DIM = 1920

    data class MediaProbe(
        val type: MessageType,
        val mime: String,
        val originalSizeBytes: Long,
        val width: Int,
        val height: Int,
        val durationMs: Long = 0L,
    ) {
        val longSide: Int get() = max(width, height)
        val shortSide: Int get() = min(width, height)
    }

    data class QualityOptionInfo(
        val quality: MediaQuality,
        val targetWidth: Int,
        val targetHeight: Int,
        /** Exact bytes for ORIGINAL; estimated bytes for STANDARD/HD when re-encoded. */
        val sizeBytes: Long,
        val isEstimate: Boolean,
        val preservesOriginalBytes: Boolean,
        val sourceBelowHdThreshold: Boolean,
        val exceedsSizeLimit: Boolean,
        val maxAllowedBytes: Long,
    )

    /** True when the source image or video has higher resolution than Standard and benefits from HD. */
    fun canBenefitFromHd(probe: MediaProbe): Boolean = when (probe.type) {
        MessageType.IMAGE -> probe.longSide > STANDARD_IMAGE_MAX_DIM
        MessageType.VIDEO -> probe.longSide > STANDARD_VIDEO_MAX_DIM || probe.originalSizeBytes > 8L * 1024 * 1024
        else -> false
    }

    /** Computes output dimensions for [quality], never upscaling a smaller source. */
    fun targetDimensions(width: Int, height: Int, quality: MediaQuality, type: MessageType): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || quality == MediaQuality.ORIGINAL) return width.coerceAtLeast(0) to height.coerceAtLeast(0)
        val maxDim = when (type) {
            MessageType.IMAGE -> if (quality == MediaQuality.HD) HD_IMAGE_MAX_DIM else STANDARD_IMAGE_MAX_DIM
            MessageType.VIDEO -> if (quality == MediaQuality.HD) HD_VIDEO_MAX_DIM else STANDARD_VIDEO_MAX_DIM
            else -> return width to height
        }
        val longSide = max(width, height)
        if (longSide <= maxDim) return width to height
        val scale = maxDim.toFloat() / longSide.toFloat()
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /**
     * Estimates compressed size for [quality] when exact output bytes are not yet generated.
     * For [MediaQuality.ORIGINAL] (or GIFs/documents), returns [probe.originalSizeBytes] with `isEstimate = false`.
     */
    fun describeOption(
        probe: MediaProbe,
        quality: MediaQuality,
        maxAllowedBytes: Long,
    ): QualityOptionInfo {
        val (tw, th) = targetDimensions(probe.width, probe.height, quality, probe.type)
        val isGif = probe.mime.equals("image/gif", ignoreCase = true)
        if (quality == MediaQuality.ORIGINAL || (probe.type == MessageType.IMAGE && isGif) || probe.type == MessageType.DOCUMENT) {
            return QualityOptionInfo(
                quality = quality,
                targetWidth = probe.width,
                targetHeight = probe.height,
                sizeBytes = probe.originalSizeBytes,
                isEstimate = false,
                preservesOriginalBytes = true,
                sourceBelowHdThreshold = false,
                exceedsSizeLimit = probe.originalSizeBytes > maxAllowedBytes,
                maxAllowedBytes = maxAllowedBytes,
            )
        }

        val belowHd = quality == MediaQuality.HD && !canBenefitFromHd(probe)
        val estimatedBytes = when (probe.type) {
            MessageType.IMAGE -> {
                val pixels = (tw.toLong().coerceAtLeast(640L) * th.toLong().coerceAtLeast(480L))
                val bitsPerPixel = if (quality == MediaQuality.HD) 2.2 else 1.15
                val rawEstimate = ((pixels * bitsPerPixel) / 8.0).roundToLong().coerceAtLeast(24_000L)
                if (probe.originalSizeBytes > 0L) min(probe.originalSizeBytes, rawEstimate) else rawEstimate
            }
            MessageType.VIDEO -> {
                // VideoTrim without re-encoding keeps source stream bytes unless trimmed
                probe.originalSizeBytes
            }
            else -> probe.originalSizeBytes
        }

        return QualityOptionInfo(
            quality = quality,
            targetWidth = tw,
            targetHeight = th,
            sizeBytes = estimatedBytes,
            isEstimate = probe.type == MessageType.IMAGE,
            preservesOriginalBytes = false,
            sourceBelowHdThreshold = belowHd,
            exceedsSizeLimit = estimatedBytes > maxAllowedBytes,
            maxAllowedBytes = maxAllowedBytes,
        )
    }
}
