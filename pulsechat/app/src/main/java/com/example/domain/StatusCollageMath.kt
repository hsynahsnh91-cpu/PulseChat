package com.example.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure geometry and layout math for the Status Photo Collage builder (Feature 1).
 *
 * All cell coordinates are normalized in `0f..1f` of the output canvas so preview and final
 * bitmap export match pixel-for-pixel without stretching or distorting source photos.
 */
object StatusCollageMath {
    const val MIN_IMAGES = 2
    const val MAX_IMAGES = 6
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 3f

    enum class Layout {
        SPLIT_VERTICAL,
        SPLIT_HORIZONTAL,
        THREE_FEATURE_LEFT,
        THREE_FEATURE_TOP,
        GRID_2X2,
        FLEX_GRID,
    }

    /** Normalized rectangle `[left, top, right, bottom]` inside `0f..1f`. */
    data class CellRect(
        val index: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val aspectRatio: Float get() = if (height <= 0f) 1f else width / height
    }

    /** Per-cell user adjustment: zoom (`1f..3f`) and pan (`-1f..1f`). */
    data class CellTransform(
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
    ) {
        fun clamped(): CellTransform = CellTransform(
            zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
            panX = panX.coerceIn(-1f, 1f),
            panY = panY.coerceIn(-1f, 1f),
        )
    }

    /** Source crop rectangle in source bitmap pixel coordinates. */
    data class SourceCrop(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Available layouts for a given number of selected images. */
    fun availableLayouts(imageCount: Int): List<Layout> = when (imageCount.coerceIn(MIN_IMAGES, MAX_IMAGES)) {
        2 -> listOf(Layout.SPLIT_VERTICAL, Layout.SPLIT_HORIZONTAL)
        3 -> listOf(Layout.THREE_FEATURE_LEFT, Layout.THREE_FEATURE_TOP, Layout.FLEX_GRID)
        4 -> listOf(Layout.GRID_2X2, Layout.FLEX_GRID)
        else -> listOf(Layout.FLEX_GRID)
    }

    fun defaultLayout(imageCount: Int): Layout = availableLayouts(imageCount).first()

    /**
     * Computes normalized cell rectangles for [imageCount] images using [layout] and [spacingNorm]
     * (`0f..0.06f` gap between cells and around the border).
     */
    fun computeCells(
        layout: Layout,
        imageCount: Int,
        spacingNorm: Float = 0.015f,
    ): List<CellRect> {
        val count = imageCount.coerceIn(MIN_IMAGES, MAX_IMAGES)
        val g = spacingNorm.coerceIn(0f, 0.06f)
        val half = g / 2f

        return when {
            count == 2 && layout == Layout.SPLIT_HORIZONTAL -> listOf(
                CellRect(0, g, g, 1f - g, 0.5f - half),
                CellRect(1, g, 0.5f + half, 1f - g, 1f - g),
            )
            count == 2 -> listOf(
                CellRect(0, g, g, 0.5f - half, 1f - g),
                CellRect(1, 0.5f + half, g, 1f - g, 1f - g),
            )
            count == 3 && layout == Layout.THREE_FEATURE_TOP -> listOf(
                CellRect(0, g, g, 1f - g, 0.55f - half),
                CellRect(1, g, 0.55f + half, 0.5f - half, 1f - g),
                CellRect(2, 0.5f + half, 0.55f + half, 1f - g, 1f - g),
            )
            count == 3 && layout == Layout.THREE_FEATURE_LEFT -> listOf(
                CellRect(0, g, g, 0.55f - half, 1f - g),
                CellRect(1, 0.55f + half, g, 1f - g, 0.5f - half),
                CellRect(2, 0.55f + half, 0.5f + half, 1f - g, 1f - g),
            )
            count == 4 && layout == Layout.GRID_2X2 -> listOf(
                CellRect(0, g, g, 0.5f - half, 0.5f - half),
                CellRect(1, 0.5f + half, g, 1f - g, 0.5f - half),
                CellRect(2, g, 0.5f + half, 0.5f - half, 1f - g),
                CellRect(3, 0.5f + half, 0.5f + half, 1f - g, 1f - g),
            )
            else -> flexGridCells(count, g)
        }
    }

    private fun flexGridCells(count: Int, g: Float): List<CellRect> {
        val rows = if (count <= 3) count else if (count == 4) 2 else 3
        val out = ArrayList<CellRect>(count)
        var idx = 0
        val rowHeight = (1f - g * (rows + 1)) / rows
        for (r in 0 until rows) {
            val remainingRows = rows - r
            val remainingItems = count - idx
            val colsInRow = (remainingItems + remainingRows - 1) / remainingRows
            val colWidth = (1f - g * (colsInRow + 1)) / colsInRow
            val top = g + r * (rowHeight + g)
            val bottom = top + rowHeight
            for (c in 0 until colsInRow) {
                val left = g + c * (colWidth + g)
                val right = left + colWidth
                out += CellRect(idx++, left, top, right, bottom)
            }
        }
        return out
    }

    /**
     * Computes the center-cropped source pixel rectangle for an image of `[srcWidth] x [srcHeight]`
     * placed into a destination cell of aspect ratio [dstAspect] (`dstWidth / dstHeight`) with
     * [transform] (zoom & pan). Guarantees no aspect-ratio distortion.
     */
    fun sourceCropRect(
        srcWidth: Int,
        srcHeight: Int,
        dstAspect: Float,
        transform: CellTransform = CellTransform(),
    ): SourceCrop {
        if (srcWidth <= 1 || srcHeight <= 1) return SourceCrop(0, 0, max(1, srcWidth), max(1, srcHeight))
        val safeAspect = if (dstAspect <= 0f || dstAspect.isNaN()) 1f else dstAspect
        val t = transform.clamped()
        val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()

        // Base center-crop window matching dstAspect
        val baseW: Float
        val baseH: Float
        if (srcAspect > safeAspect) {
            baseH = srcHeight.toFloat()
            baseW = baseH * safeAspect
        } else {
            baseW = srcWidth.toFloat()
            baseH = baseW / safeAspect
        }

        val cropW = (baseW / t.zoom).coerceIn(1f, srcWidth.toFloat())
        val cropH = (baseH / t.zoom).coerceIn(1f, srcHeight.toFloat())

        val maxShiftX = (srcWidth - cropW) / 2f
        val maxShiftY = (srcHeight - cropH) / 2f
        val centerX = srcWidth / 2f - t.panX * maxShiftX
        val centerY = srcHeight / 2f - t.panY * maxShiftY

        val left = (centerX - cropW / 2f).roundToInt().coerceIn(0, srcWidth - 1)
        val top = (centerY - cropH / 2f).roundToInt().coerceIn(0, srcHeight - 1)
        val right = (left + cropW.roundToInt()).coerceIn(left + 1, srcWidth)
        val bottom = (top + cropH.roundToInt()).coerceIn(top + 1, srcHeight)
        return SourceCrop(left, top, right, bottom)
    }

    /** Reorders an item in [items] from [fromIndex] to [toIndex]. */
    fun <T> moveItem(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return items
        val mutable = items.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }

    /** Replaces the item at [index] in [items]. */
    fun <T> replaceItem(items: List<T>, index: Int, newItem: T): List<T> {
        if (index !in items.indices) return items
        val mutable = items.toMutableList()
        mutable[index] = newItem
        return mutable
    }

    /** Removes the item at [index] when more than [MIN_IMAGES] remain. */
    fun <T> removeItem(items: List<T>, index: Int): List<T> {
        if (index !in items.indices || items.size <= MIN_IMAGES) return items
        val mutable = items.toMutableList()
        mutable.removeAt(index)
        return mutable
    }
}
