package com.example.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure placement, transform, hit-testing, and undo/redo math for Photo Stickers in Status updates (Feature 1).
 *
 * Positions are normalized (`0f..1f`) relative to the status canvas so interactive preview and
 * final exported bitmap match accurately.
 */
object PhotoStickerMath {
    const val MIN_SCALE = 0.12f
    const val MAX_SCALE = 0.85f
    const val DEFAULT_SCALE = 0.28f

    enum class StickerShape { ROUNDED, CIRCLE, FRAMED }

    data class StickerItem(
        val id: String,
        val uriString: String,
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f,
        val scale: Float = DEFAULT_SCALE,
        val rotationDeg: Float = 0f,
        val aspectRatio: Float = 1f,
        val shape: StickerShape = StickerShape.ROUNDED,
    )

    /**
     * Clamps a sticker's scale, rotation, and center position so it stays inside the visible canvas
     * (`[margin..1-margin]`) and never clips off-screen.
     */
    fun clamp(item: StickerItem): StickerItem {
        val clampedScale = item.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val halfExtent = (clampedScale * 0.35f).coerceIn(0.05f, 0.45f)
        val cx = item.centerX.coerceIn(halfExtent, 1f - halfExtent)
        val cy = item.centerY.coerceIn(halfExtent, 1f - halfExtent)
        val rot = ((item.rotationDeg + 180f).mod(360f)) - 180f
        return item.copy(
            centerX = cx,
            centerY = cy,
            scale = clampedScale,
            rotationDeg = rot,
            aspectRatio = item.aspectRatio.coerceIn(0.4f, 2.5f),
        )
    }

    /** Translates [item] by normalized delta `(dx, dy)` and clamps within bounds. */
    fun move(item: StickerItem, dx: Float, dy: Float): StickerItem =
        clamp(item.copy(centerX = item.centerX + dx, centerY = item.centerY + dy))

    /** Applies zoom multiplier and rotation delta (degrees) to [item]. */
    fun transform(item: StickerItem, zoomChange: Float, rotationDeltaDeg: Float): StickerItem =
        clamp(item.copy(scale = item.scale * zoomChange, rotationDeg = item.rotationDeg + rotationDeltaDeg))

    /** Duplicates [item] with a new [newId] and slight offset so both are visible. */
    fun duplicate(item: StickerItem, newId: String): StickerItem =
        clamp(item.copy(id = newId, centerX = item.centerX + 0.06f, centerY = item.centerY + 0.06f))

    /**
     * Returns the top-most sticker hit by normalized touch point `(normX, normY)`, or null.
     * Accounts for the sticker's rotation and aspect ratio.
     */
    fun hitTest(stickers: List<StickerItem>, normX: Float, normY: Float): StickerItem? {
        for (i in stickers.indices.reversed()) {
            val s = stickers[i]
            val dx = normX - s.centerX
            val dy = normY - s.centerY
            val rad = Math.toRadians(-s.rotationDeg.toDouble())
            val localX = (dx * cos(rad) - dy * sin(rad)).toFloat()
            val localY = (dx * sin(rad) + dy * cos(rad)).toFloat()
            val halfW = (s.scale * (if (s.aspectRatio >= 1f) 1f else s.aspectRatio)) / 2f + 0.03f
            val halfH = (s.scale / (if (s.aspectRatio >= 1f) s.aspectRatio else 1f)) / 2f + 0.03f
            if (kotlin.math.abs(localX) <= halfW && kotlin.math.abs(localY) <= halfH) {
                return s
            }
        }
        return null
    }

    /** Immutable Undo/Redo history for a list of stickers (or editor states). */
    data class History<T>(
        val current: T,
        val undoStack: List<T> = emptyList(),
        val redoStack: List<T> = emptyList(),
        val maxDepth: Int = 30,
    ) {
        val canUndo: Boolean get() = undoStack.isNotEmpty()
        val canRedo: Boolean get() = redoStack.isNotEmpty()

        fun push(next: T): History<T> {
            if (next == current) return this
            val updatedUndo = (undoStack + current).takeLast(maxDepth)
            return copy(current = next, undoStack = updatedUndo, redoStack = emptyList())
        }

        fun undo(): History<T> {
            if (undoStack.isEmpty()) return this
            val prev = undoStack.last()
            return copy(
                current = prev,
                undoStack = undoStack.dropLast(1),
                redoStack = (redoStack + current).takeLast(maxDepth),
            )
        }

        fun redo(): History<T> {
            if (redoStack.isEmpty()) return this
            val next = redoStack.last()
            return copy(
                current = next,
                undoStack = (undoStack + current).takeLast(maxDepth),
                redoStack = redoStack.dropLast(1),
            )
        }
    }
}
