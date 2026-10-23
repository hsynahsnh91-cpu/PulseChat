package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdvancedStatusAndEditorTest {

    @Test
    fun `status music catalog search, segment clamping, attribution, and WAV synthesis`() {
        val all = StatusMusicPolicy.searchTracks("")
        assertEquals(StatusMusicPolicy.BUILT_IN_TRACKS.size, all.size)

        val acoustic = StatusMusicPolicy.searchTracks("Sunrise")
        assertEquals(1, acoustic.size)
        assertEquals("sunrise_acoustic", acoustic.first().id)
        assertNotNull(StatusMusicPolicy.findTrack("lofi_breeze"))

        val clamped = StatusMusicPolicy.clampSelection(
            trackDurationMs = 20_000L,
            requestedStartMs = 15_000L,
            requestedDurationMs = 10_000L,
        )
        assertEquals(10_000L, clamped.durationMs)
        assertEquals(10_000L, clamped.startMs)

        val label = StatusMusicPolicy.formatAttribution("Sunrise Acoustic", "PulseChat Studio", " · ")
        assertEquals("Sunrise Acoustic · PulseChat Studio", label)

        val wav = StatusMusicPolicy.synthesizeTrackWav(StatusMusicPolicy.BUILT_IN_TRACKS.first(), durationMs = 2_000L)
        assertTrue(wav.size > 44)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val dataBytes = ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(wav.size - 44, dataBytes)
    }

    @Test
    fun `status collage layouts, crop rects, and cell reordering`() {
        val cells2H = StatusCollageMath.layoutCells(StatusCollageMath.Layout.SPLIT_2_HORIZONTAL, 2)
        assertEquals(2, cells2H.size)
        assertTrue(cells2H[0].width > 0.45f)
        assertTrue(cells2H[1].left > 0.49f)

        val cells4 = StatusCollageMath.layoutCells(StatusCollageMath.Layout.GRID_4, 4)
        assertEquals(4, cells4.size)

        val crop = StatusCollageMath.computeSourceCrop(
            sourceWidth = 2000,
            sourceHeight = 1000,
            cellAspectRatio = 1f,
            zoom = 1.5f,
            panX = 0.5f,
            panY = -0.5f,
        )
        assertTrue(crop.left >= 0)
        assertTrue(crop.top >= 0)
        assertTrue(crop.right <= 2000)
        assertTrue(crop.bottom <= 1000)
        assertTrue(crop.width < 1000)

        val items = listOf(
            StatusCollageMath.CollageItem("1", "uri://1"),
            StatusCollageMath.CollageItem("2", "uri://2"),
            StatusCollageMath.CollageItem("3", "uri://3"),
        )
        val swapped = StatusCollageMath.swapItems(items, 0, 2)
        assertEquals(listOf("3", "2", "1"), swapped.map { it.id })

        val removed = StatusCollageMath.removeItem(swapped, 1)
        assertEquals(listOf("3", "1"), removed.map { it.id })
    }

    @Test
    fun `photo sticker transforms stay in bounds and History supports undo and redo`() {
        val s0 = PhotoStickerMath.createSticker("stk_1", "file:///sticker.png", existingCount = 0)
        val moved = PhotoStickerMath.moveSticker(s0, dxNorm = 5f, dyNorm = -5f)
        assertTrue(moved.x in 0.05f..0.95f)
        assertTrue(moved.y in 0.05f..0.95f)

        val scaled = PhotoStickerMath.transformSticker(moved, scaleFactor = 10f, rotationDeltaDeg = 370f)
        assertEquals(PhotoStickerMath.MAX_SCALE, scaled.scale, 0.001f)
        assertEquals(10f, scaled.rotationDeg, 0.001f)

        val hit = PhotoStickerMath.hitTest(listOf(s0), s0.x, s0.y, canvasAspectRatio = 1f)
        assertEquals("stk_1", hit)

        val history = PhotoStickerMath.History(listOf(s0))
            .push(listOf(moved))
            .push(listOf(scaled))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        val undone = history.undo()
        assertEquals(moved.x, undone.current.first().x, 0.0001f)
        assertTrue(undone.canRedo)

        val redone = undone.redo()
        assertEquals(scaled.scale, redone.current.first().scale, 0.0001f)
    }

    @Test
    fun `status mentions, likes, and reshare eligibility enforce audience and privacy`() {
        val mentions = StatusPolicy.validateMentions(
            requestedMentionIds = listOf("alice", "bob", "carol", "dave", "bob"),
            ownerId = "alice",
            audience = setOf("bob", "carol"),
            blockedIds = setOf("carol"),
        )
        assertEquals(listOf("bob"), mentions)

        assertTrue(
            StatusPolicy.canLike(
                viewerId = "bob",
                ownerId = "alice",
                audience = setOf("bob"),
                blockedByOwner = emptySet(),
                blockedByViewer = emptySet(),
                expiresAtMs = 2_000L,
                nowMs = 1_000L,
            )
        )
        assertFalse(
            StatusPolicy.canLike(
                viewerId = "carol",
                ownerId = "alice",
                audience = setOf("bob"),
                blockedByOwner = emptySet(),
                blockedByViewer = emptySet(),
                expiresAtMs = 2_000L,
                nowMs = 1_000L,
            )
        )

        assertEquals(
            StatusPolicy.ReshareDenial.DISABLED_BY_AUTHOR,
            StatusPolicy.checkReshareEligibility(
                resharerId = "bob",
                ownerId = "alice",
                allowReshare = false,
                isAlreadyReshared = false,
                originalAudience = setOf("bob"),
                blockedByOwner = emptySet(),
                blockedByResharer = emptySet(),
                originalExpiresAtMs = 5_000L,
                nowMs = 1_000L,
            )
        )
        assertNull(
            StatusPolicy.checkReshareEligibility(
                resharerId = "bob",
                ownerId = "alice",
                allowReshare = true,
                isAlreadyReshared = false,
                originalAudience = setOf("bob", "carol"),
                blockedByOwner = emptySet(),
                blockedByResharer = emptySet(),
                originalExpiresAtMs = 5_000L,
                nowMs = 1_000L,
            )
        )

        val (aud, exp) = StatusPolicy.computeReshareScope(
            resharerAudience = setOf("carol", "dave"),
            originalAudience = setOf("bob", "carol"),
            originalOwnerId = "alice",
            resharerId = "bob",
            blockedByOriginalOwner = emptySet(),
            blockedByResharer = emptySet(),
            originalExpiresAtMs = 5_000L,
            nowMs = 1_000L,
        )
        // Reshare audience is strictly intersected with original audience ("dave" excluded)
        assertEquals(setOf("carol"), aud)
        assertEquals(5_000L, exp)
    }
}
