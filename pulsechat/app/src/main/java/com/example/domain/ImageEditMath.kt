package com.example.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry for the photo editor (pure; unit-tested). Positions are normalized 0..1 of the image. */
object ImageEditMath {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    data class PixelBox(val left: Int, val top: Int, val width: Int, val height: Int)

    val FULL = Box(0f, 0f, 1f, 1f)
    const val MIN_CROP = 0.1f

    /** Where an image of [imageW]x[imageH] is drawn when fitted (letterboxed) into a container. */
    fun fit(containerW: Float, containerH: Float, imageW: Float, imageH: Float): Box {
        if (imageW <= 0f || imageH <= 0f || containerW <= 0f || containerH <= 0f) return Box(0f, 0f, 0f, 0f)
        val scale = min(containerW / imageW, containerH / imageH)
        val w = imageW * scale
        val h = imageH * scale
        val left = (containerW - w) / 2f
        val top = (containerH - h) / 2f
        return Box(left, top, left + w, top + h)
    }

    fun normalize(x: Float, y: Float, shown: Box): Pair<Float, Float> {
        if (shown.width <= 0f || shown.height <= 0f) return 0f to 0f
        return ((x - shown.left) / shown.width).coerceIn(0f, 1f) to ((y - shown.top) / shown.height).coerceIn(0f, 1f)
    }

    /** A box spanning two dragged corners, in any direction. */
    fun boxOf(x1: Float, y1: Float, x2: Float, y2: Float): Box = Box(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))

    /** Keeps a crop box inside the image and at least [MIN_CROP] wide/high. */
    fun clampCrop(b: Box): Box {
        var l = b.left.coerceIn(0f, 1f - MIN_CROP)
        var t = b.top.coerceIn(0f, 1f - MIN_CROP)
        var r = b.right.coerceIn(l + MIN_CROP, 1f)
        var bt = b.bottom.coerceIn(t + MIN_CROP, 1f)
        if (r - l < MIN_CROP) { l = r - MIN_CROP }
        if (bt - t < MIN_CROP) { t = bt - MIN_CROP }
        return Box(l, t, r, bt)
    }

    enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

    /** Which crop handle a touch at (x, y) grabs; corners within [tolerance], otherwise the whole box. */
    fun handleAt(x: Float, y: Float, crop: Box, tolerance: Float): Handle {
        fun near(px: Float, py: Float) = abs(x - px) <= tolerance && abs(y - py) <= tolerance
        return when {
            near(crop.left, crop.top) -> Handle.TOP_LEFT
            near(crop.right, crop.top) -> Handle.TOP_RIGHT
            near(crop.left, crop.bottom) -> Handle.BOTTOM_LEFT
            near(crop.right, crop.bottom) -> Handle.BOTTOM_RIGHT
            else -> Handle.MOVE
        }
    }

    fun dragCrop(crop: Box, handle: Handle, dx: Float, dy: Float): Box = when (handle) {
        Handle.TOP_LEFT -> clampCrop(crop.copy(left = min(crop.left + dx, crop.right - MIN_CROP), top = min(crop.top + dy, crop.bottom - MIN_CROP)))
        Handle.TOP_RIGHT -> clampCrop(crop.copy(right = max(crop.right + dx, crop.left + MIN_CROP), top = min(crop.top + dy, crop.bottom - MIN_CROP)))
        Handle.BOTTOM_LEFT -> clampCrop(crop.copy(left = min(crop.left + dx, crop.right - MIN_CROP), bottom = max(crop.bottom + dy, crop.top + MIN_CROP)))
        Handle.BOTTOM_RIGHT -> clampCrop(crop.copy(right = max(crop.right + dx, crop.left + MIN_CROP), bottom = max(crop.bottom + dy, crop.top + MIN_CROP)))
        Handle.MOVE -> {
            val mx = dx.coerceIn(-crop.left, 1f - crop.right)
            val my = dy.coerceIn(-crop.top, 1f - crop.bottom)
            Box(crop.left + mx, crop.top + my, crop.right + mx, crop.bottom + my)
        }
    }

    /** Normalized box -> pixel rectangle inside a [w]x[h] bitmap (at least 1x1). */
    fun toPixels(b: Box, w: Int, h: Int): PixelBox {
        val l = (b.left * w).roundToInt().coerceIn(0, w - 1)
        val t = (b.top * h).roundToInt().coerceIn(0, h - 1)
        val r = (b.right * w).roundToInt().coerceIn(l + 1, w)
        val bt = (b.bottom * h).roundToInt().coerceIn(t + 1, h)
        return PixelBox(l, t, r - l, bt - t)
    }

    /**
     * Block size for the privacy blur: at least 12 px and about a tenth of the region, so text and
     * faces become unrecognizable. The pixels are replaced (not overlaid), so it can't be undone
     * from the sent image.
     */
    fun pixelateBlock(regionW: Int, regionH: Int): Int = max(12, max(regionW, regionH) / 10)

    /** Averages each [block]x[block] cell of an ARGB region in place. */
    fun pixelate(pixels: IntArray, width: Int, height: Int, block: Int) {
        var by = 0
        while (by < height) {
            var bx = 0
            val bh = min(block, height - by)
            while (bx < width) {
                val bw = min(block, width - bx)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val c = pixels[y * width + x]
                    a += (c ushr 24) and 0xff; r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
                }
                val n = (bw * bh).toLong()
                val avg = (((a / n).toInt() and 0xff) shl 24) or (((r / n).toInt() and 0xff) shl 16) or (((g / n).toInt() and 0xff) shl 8) or ((b / n).toInt() and 0xff)
                for (y in by until by + bh) for (x in bx until bx + bw) pixels[y * width + x] = avg
                bx += block
            }
            by += block
        }
    }
}
