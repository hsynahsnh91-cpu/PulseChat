package com.example.domain

import kotlin.math.max
import kotlin.math.min

/** A trim range and cover frame, in milliseconds of the original video's timeline. */
data class VideoEdit(val startMs: Long, val endMs: Long, val coverMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Rules of the video editor (trim the start and end, pick the cover frame).
 *
 * The cut is a copy of the original's compressed frames (no re-encoding: fast, no quality loss, no
 * battery drain). Such a cut can only start on a keyframe, so the start snaps back to the keyframe
 * at or before the chosen time and the editor shows that real start; the end can be any frame.
 */
object VideoEditMath {
    const val MIN_CLIP_MS = 1_000L
    /** An end this close to the original end counts as "not trimmed". */
    const val END_TOLERANCE_MS = 150L
    /** Cover image sent with the message so the other side sees it before downloading. */
    const val COVER_MAX_DIM = 480
    const val COVER_MAX_BYTES = 48 * 1024
    const val FILMSTRIP_FRAMES = 8

    /** Latest keyframe at or before [requestedMs] ([keyframesMs] ascending); 0 when unknown. */
    fun snapStart(requestedMs: Long, keyframesMs: List<Long>): Long {
        if (keyframesMs.isEmpty()) return 0L
        var best = 0L
        for (k in keyframesMs) if (k <= requestedMs) best = k else break
        return best
    }

    /** A valid edit within [durationMs]: snapped start, at least [MIN_CLIP_MS] long, cover inside it. */
    fun normalize(startMs: Long, endMs: Long, coverMs: Long, durationMs: Long, keyframesMs: List<Long>): VideoEdit {
        val d = durationMs.coerceAtLeast(0L)
        val minLength = min(MIN_CLIP_MS, d)
        var start = snapStart(startMs.coerceIn(0L, d), keyframesMs).coerceIn(0L, d)
        var end = endMs.coerceIn(0L, d)
        if (end - start < minLength) end = min(d, start + minLength)
        if (end - start < minLength) start = snapStart(max(0L, end - minLength), keyframesMs)
        val cover = coverMs.coerceIn(start, max(start, end - 1))
        return VideoEdit(start, end, cover)
    }

    fun isTrimmed(edit: VideoEdit, durationMs: Long): Boolean = edit.startMs > 0 || edit.endMs < durationMs - END_TOLERANCE_MS

    /** Size of the cut, estimated from the original's average bitrate. */
    fun estimatedBytes(totalBytes: Long, durationMs: Long, edit: VideoEdit): Long =
        if (durationMs <= 0L) totalBytes else (totalBytes.toDouble() * edit.durationMs / durationMs).toLong() + 4_096L

    /** Times of [count] filmstrip frames, each in the middle of an equal slice. */
    fun filmstripTimes(durationMs: Long, count: Int = FILMSTRIP_FRAMES): List<Long> =
        if (durationMs <= 0L || count <= 0) emptyList() else List(count) { i -> durationMs * (2 * i + 1) / (2L * count) }

    /** Default cover: a little into the clip (the very first frame is often black). */
    fun defaultCoverMs(durationMs: Long): Long = min(1_000L, durationMs / 3).coerceAtLeast(0L)

    /** Cover position in the cut file, whose timeline starts at the cut's start. */
    fun coverInCut(edit: VideoEdit): Long = (edit.coverMs - edit.startMs).coerceAtLeast(0L)
}
