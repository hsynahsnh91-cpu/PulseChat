package com.example.domain

import com.example.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video editor rules and storage accounting (pure Kotlin). */
class Phase3PoliciesTest {
    private val keyframes = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L)

    // ------------------------------------------------------------------ video editor

    @Test
    fun theCutStartsOnTheKeyframeAtOrBeforeTheChosenTime() {
        assertEquals(1_000L, VideoEditMath.snapStart(1_700L, keyframes))
        assertEquals(2_000L, VideoEditMath.snapStart(2_000L, keyframes))
        assertEquals(0L, VideoEditMath.snapStart(400L, keyframes))
        assertEquals(0L, VideoEditMath.snapStart(1_500L, emptyList()))
    }

    @Test
    fun editsStayInsideTheVideoAndAtLeastOneSecondLong() {
        val e = VideoEditMath.normalize(1_700L, 4_200L, 3_000L, 6_000L, keyframes)
        assertEquals(VideoEdit(1_000L, 4_200L, 3_000L), e)
        assertEquals(3_200L, e.durationMs)
        // Too short: the end moves out to one second after the (snapped) start.
        assertEquals(VideoEdit(2_000L, 3_000L, 2_000L), VideoEditMath.normalize(2_300L, 2_500L, 0L, 6_000L, keyframes))
        // At the very end: the start moves back instead.
        val atEnd = VideoEditMath.normalize(5_900L, 6_000L, 6_000L, 6_000L, keyframes)
        assertEquals(5_000L, atEnd.startMs)
        assertEquals(6_000L, atEnd.endMs)
        assertTrue("cover inside the cut", atEnd.coverMs in atEnd.startMs until atEnd.endMs)
        // Out-of-range values are clamped.
        assertEquals(VideoEdit(0L, 6_000L, 0L), VideoEditMath.normalize(-50L, 99_000L, -1L, 6_000L, keyframes))
        // Clips shorter than a second are kept whole.
        assertEquals(VideoEdit(0L, 600L, 0L), VideoEditMath.normalize(0L, 600L, 0L, 600L, listOf(0L)))
    }

    @Test
    fun onlyRealChangesCountAsTrimmed() {
        assertFalse(VideoEditMath.isTrimmed(VideoEdit(0L, 6_000L, 0L), 6_000L))
        assertFalse(VideoEditMath.isTrimmed(VideoEdit(0L, 5_900L, 0L), 6_000L))
        assertTrue(VideoEditMath.isTrimmed(VideoEdit(0L, 5_000L, 0L), 6_000L))
        assertTrue(VideoEditMath.isTrimmed(VideoEdit(1_000L, 6_000L, 1_000L), 6_000L))
    }

    @Test
    fun sizeFilmstripAndCoverHelpers() {
        assertEquals(5_004_096L, VideoEditMath.estimatedBytes(10_000_000L, 6_000L, VideoEdit(0L, 3_000L, 0L)))
        assertEquals(listOf(375L, 1_125L, 1_875L, 2_625L, 3_375L, 4_125L, 4_875L, 5_625L), VideoEditMath.filmstripTimes(6_000L))
        assertTrue(VideoEditMath.filmstripTimes(0L).isEmpty())
        assertEquals(1_000L, VideoEditMath.defaultCoverMs(60_000L))
        assertEquals(200L, VideoEditMath.defaultCoverMs(600L))
        assertEquals(2_000L, VideoEditMath.coverInCut(VideoEdit(1_000L, 4_000L, 3_000L)))
    }

    // ------------------------------------------------------------------ storage

    @Test
    fun mediaTypesMapToStorageCategories() {
        assertEquals(StorageCategory.PHOTOS, StoragePolicy.categoryFor(MessageType.IMAGE))
        assertEquals(StorageCategory.VIDEOS, StoragePolicy.categoryFor(MessageType.VIDEO))
        assertEquals(StorageCategory.VOICE, StoragePolicy.categoryFor(MessageType.AUDIO))
        assertEquals(StorageCategory.DOCUMENTS, StoragePolicy.categoryFor(MessageType.DOCUMENT))
    }

    @Test
    fun unusedFilesAreOnlyOldFilesNoMessageUses() {
        val now = 10_000_000L
        val files = listOf(
            FileEntry("/m/a.jpg", 100, now - 2 * 3_600_000L), // used
            FileEntry("/m/b.jpg", 200, now - 2 * 3_600_000L), // unused, old
            FileEntry("/m/c.mp4", 300, now - 60_000L),        // unused but new: maybe being sent
        )
        val unused = StoragePolicy.unusedFiles(files, setOf("/m/a.jpg"), now)
        assertEquals(listOf("/m/b.jpg"), unused.map { it.path })
    }

    @Test
    fun recentCapturesAreNeverDeleted() {
        val now = 10_000_000L
        assertFalse(StoragePolicy.deletableTemp(FileEntry("/c/cap.mp4", 1, now - 60_000L), inCaptureFolder = true, now = now))
        assertTrue(StoragePolicy.deletableTemp(FileEntry("/c/cap.mp4", 1, now - 11 * 60_000L), inCaptureFolder = true, now = now))
        assertTrue(StoragePolicy.deletableTemp(FileEntry("/c/img", 1, now), inCaptureFolder = false, now = now))
    }

    @Test
    fun theReportAddsUpAndKnowsWhatCanBeCleared() {
        val r = StoragePolicy.report(
            media = mapOf(
                StorageCategory.PHOTOS to listOf(FileEntry("a", 1_000, 0), FileEntry("b", 500, 0)),
                StorageCategory.VIDEOS to listOf(FileEntry("c", 4_000, 0)),
            ),
            unused = listOf(FileEntry("d", 300, 0)),
            temporaryBytes = 700, temporaryFiles = 3, appDataBytes = 2_000, freeBytes = 9_000, deviceBytes = 64_000,
        )
        assertEquals(1_500L, r.size(StorageCategory.PHOTOS))
        assertEquals(2, r.count(StorageCategory.PHOTOS))
        assertEquals(5_500L, r.mediaBytes)
        assertEquals(1_000L, r.clearableBytes)
        assertEquals(8_500L, r.totalBytes)
        assertEquals(4_000f / 8_500f, r.share(StorageCategory.VIDEOS), 0.0001f)
        assertEquals(0L, r.size(StorageCategory.VOICE))
        assertEquals(0f, StorageReport.EMPTY.share(StorageCategory.PHOTOS), 0f)
    }
}
