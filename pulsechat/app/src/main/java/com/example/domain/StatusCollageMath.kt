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
    data class CellState(
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
    ) {
        fun clamped(): CellState = CellState(
            zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
            panX = panX.coerceIn(-1f, 1f),
            panY = panY.coerceIn(-1f, 1f),
        )
    }

    data class CollageItem(
        val id: String,
        val uriString: String,
        val state: CellState = CellState(),
    )

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
        3 -> listOf(Layout.THREE_FEATURE_LEFT, Layout.FLEX_GRID)
        4 -> listOf(Layout.GRID_2X2, Layout.FLEX_GRID)
        else -> listOf(Layout.FLEX_GRID)
    }

    fun availableLayoutsForCount(imageCount: Int): List<Layout> = availableLayouts(imageCount)

    fun defaultLayout(imageCount: Int): Layout = availableLayouts(imageCount).first()

    fun defaultLayoutForCount(imageCount: Int): Layout = defaultLayout(imageCount)

    fun clampState(state: CellState): CellState = state.clamped()

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

    fun slots(layout: Layout, imageCount: Int, spacingNorm: Float = 0.015f): List<CellRect> =
        computeCells(layout, imageCount, spacingNorm)

    fun layoutCells(layout: Layout, imageCount: Int, spacingNorm: Float = 0.015f): List<CellRect> =
        computeCells(layout, imageCount, spacingNorm)

    private fun flexGridCells(count: Int, g: Float): List<CellRect> {
        val rows = if (count <= 3) count else if (count == 4) 2 else 3
        val out = ArrayList<CellRect>(count)
        var idx = 0
        val usableH = (1f - g * (rows + 1)).coerceAtLeast(0.1f)
        val cellH = usableH / rows
        for (r in 0 until rows) {
            val remaining = count - idx
            val remainingRows = rows - r
            val colsInRow = ((remaining + remainingRows - 1) / remainingRows).coerceAtLeast(1)
            val usableW = (1f - g * (colsInRow + 1)).coerceAtLeast(0.1f)
            val cellW = usableW / colsInRow
            val top = g + r * (cellH + g)
            val bottom = top + cellH
            for (c in 0 until colsInRow) {
                if (idx >= count) break
                val left = g + c * (cellW + g)
                val right = left + cellW
                out += CellRect(idx++, left, top, right, bottom)
            }
        }
        return out
    }

    /**
     * Computes center-crop source coordinates in the source bitmap (`[0..sourceWidth] x [0..sourceHeight]`)
     * matching [cellAspectRatio] so images never stretch, respecting [transform]'s zoom and pan.
     */
    fun computeSourceCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        cellAspectRatio: Float,
        transform: CellState = CellState(),
    ): SourceCrop {
        val w = sourceWidth.coerceAtLeast(1)
        val h = sourceHeight.coerceAtLeast(1)
        val targetAspect = cellAspectRatio.coerceIn(0.1f, 10f)
        val srcAspect = w.toFloat() / h.toFloat()
        val t = transform.clamped()

        val baseCropW: Float
        val baseCropH: Float
        if (srcAspect > targetAspect) {
            baseCropH = h.toFloat()
            baseCropW = baseCropH * targetAspect
        } else {
            baseCropW = w.toFloat()
            baseCropH = baseCropW / targetAspect
        }

        val cropW = (baseCropW / t.zoom).coerceIn(1f, w.toFloat())
        val cropH = (baseCropH / t.zoom).coerceIn(1f, h.toFloat())
        val maxPanX = max(0f, (w - cropW) / 2f)
        val maxPanY = max(0f, (h - cropH) / 2f)
        val centerX = (w / 2f + t.panX * maxPanX).coerceIn(cropW / 2f, w - cropW / 2f)
        val centerY = (h / 2f + t.panY * maxPanY).coerceIn(cropH / 2f, h - cropH / 2f)

        val left = (centerX - cropW / 2f).roundToInt().coerceIn(0, w - 1)
        val top = (centerY - cropH / 2f).roundToInt().coerceIn(0, h - 1)
        val right = min(w, max(left + 1, (centerX + cropW / 2f).roundToInt()))
        val bottom = min(h, max(top + 1, (centerY + cropH / 2f).roundToInt()))
        return SourceCrop(left, top, right, bottom)
    }

    fun computeSourceCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        cellAspectRatio: Float,
        zoom: Float,
        panX: Float = 0f,
        panY: Float = 0f,
    ): SourceCrop = computeSourceCrop(sourceWidth, sourceHeight, cellAspectRatio, CellState(zoom, panX, panY))

    fun computeSourceRect(
        sourceWidth: Int,
        sourceHeight: Int,
        cellAspectRatio: Float,
        state: CellState = CellState(),
    ): SourceCrop = computeSourceCrop(sourceWidth, sourceHeight, cellAspectRatio, state)

    /** Swaps two items in [items] for reordering cells in the collage editor. */
    fun <T> swapItems(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return items
        val mutable = items.toMutableList()
        val tmp = mutable[fromIndex]
        mutable[fromIndex] = mutable[toIndex]
        mutable[toIndex] = tmp
        return mutable
    }

    /** Replaces the item at [index] in [items]. */
    fun <T> replaceItem(items: List<T>, index: Int, newItem: T): List<T> {
        if (index !in items.indices) return items
        return items.toMutableList().also { it[index] = newItem }
    }

    /** Removes the item at [index] if at least [MIN_IMAGES] remain. */
    fun <T> removeItem(items: List<T>, index: Int): List<T> {
        if (index !in items.indices || items.size <= MIN_IMAGES) return items
        return items.toMutableList().also { it.removeAt(index) }
    }
}
