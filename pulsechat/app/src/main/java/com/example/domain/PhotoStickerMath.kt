package com.example.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure placement, transform, hit-testing, and undo/redo math for Photo Stickers in Status updates (Feature 1).
 *
 * Positions are normalized (`0f..1f`) relative to the status canvas so interactive preview and
 * final exported bitmap match accurately.
 */
object PhotoStickerMath {
    const val MAX_STICKERS = 10
    const val MIN_SCALE = 0.12f
    const val MAX_SCALE = 0.85f
    const val DEFAULT_SCALE = 0.28f

    enum class StickerShape { ROUNDED, CIRCLE, FRAMED }

    data class Sticker(
        val id: String,
        val uriString: String,
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f,
        val scale: Float = DEFAULT_SCALE,
        val rotationDegrees: Float = 0f,
        val aspectRatio: Float = 1f,
        val shape: StickerShape = StickerShape.ROUNDED,
    ) {
        val x: Float get() = centerX
        val y: Float get() = centerY
        val rotationDeg: Float get() = rotationDegrees
        val halfWidth: Float get() = (scale * (if (aspectRatio >= 1f) 1f else aspectRatio)) / 2f
        val halfHeight: Float get() = (scale / (if (aspectRatio >= 1f) aspectRatio else 1f)) / 2f
    }

    typealias StickerItem = Sticker

    fun createSticker(
        id: String,
        uriString: String,
        aspectRatio: Float = 1f,
        existingCount: Int = 0,
    ): Sticker {
        val offset = ((existingCount % 5) - 2) * 0.04f
        return clampSticker(
            Sticker(
                id = id,
                uriString = uriString,
                centerX = (0.5f + offset).coerceIn(0.2f, 0.8f),
                centerY = (0.5f + offset).coerceIn(0.2f, 0.8f),
                scale = DEFAULT_SCALE,
                rotationDegrees = 0f,
                aspectRatio = aspectRatio.coerceIn(0.4f, 2.5f),
            )
        )
    }

    /**
     * Clamps a sticker's scale, rotation, and center position so it stays inside the visible canvas
     * (`[margin..1-margin]`) and never clips off-screen.
     */
    fun clampSticker(item: Sticker): Sticker {
        val clampedScale = item.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val halfExtent = (clampedScale * 0.35f).coerceIn(0.05f, 0.45f)
        val cx = item.centerX.coerceIn(halfExtent, 1f - halfExtent)
        val cy = item.centerY.coerceIn(halfExtent, 1f - halfExtent)
        val rot = ((item.rotationDegrees + 180f).mod(360f)) - 180f
        return item.copy(
            centerX = cx,
            centerY = cy,
            scale = clampedScale,
            rotationDegrees = rot,
            aspectRatio = item.aspectRatio.coerceIn(0.4f, 2.5f),
        )
    }

    fun clamp(item: Sticker): Sticker = clampSticker(item)

    /** Translates [item] by normalized delta `(dxNorm, dyNorm)` and clamps within bounds. */
    fun moveSticker(item: Sticker, dxNorm: Float, dyNorm: Float): Sticker =
        clampSticker(item.copy(centerX = item.centerX + dxNorm, centerY = item.centerY + dyNorm))

    fun move(item: Sticker, dx: Float, dy: Float): Sticker = moveSticker(item, dx, dy)

    /** Applies zoom multiplier and rotation delta (degrees) to [item]. */
    fun transformSticker(item: Sticker, scaleFactor: Float, rotationDeltaDeg: Float): Sticker =
        clampSticker(item.copy(scale = item.scale * scaleFactor, rotationDegrees = item.rotationDegrees + rotationDeltaDeg))

    fun transform(item: Sticker, zoomChange: Float, rotationDeltaDeg: Float): Sticker =
        transformSticker(item, zoomChange, rotationDeltaDeg)

    /** Duplicates [item] with a new [newId] and slight offset so both are visible. */
    fun duplicateSticker(item: Sticker, newId: String, currentCount: Int = 0): Sticker? {
        if (currentCount >= MAX_STICKERS) return null
        return clampSticker(item.copy(id = newId, centerX = item.centerX + 0.06f, centerY = item.centerY + 0.06f))
    }

    fun duplicate(item: Sticker, newId: String): Sticker =
        clampSticker(item.copy(id = newId, centerX = item.centerX + 0.06f, centerY = item.centerY + 0.06f))

    fun removeSticker(stickers: List<Sticker>, id: String): List<Sticker> =
        stickers.filterNot { it.id == id }

    /**
     * Returns the top-most sticker hit by normalized touch point `(normX, normY)`, or null.
     * Accounts for the sticker's rotation and aspect ratio.
     */
    fun hitTestTopmost(stickers: List<Sticker>, normX: Float, normY: Float): Sticker? {
        for (i in stickers.indices.reversed()) {
            val s = stickers[i]
            val dx = normX - s.centerX
            val dy = normY - s.centerY
            val rad = Math.toRadians(-s.rotationDegrees.toDouble())
            val localX = (dx * cos(rad) - dy * sin(rad)).toFloat()
            val localY = (dx * sin(rad) + dy * cos(rad)).toFloat()
            val halfW = s.halfWidth + 0.03f
            val halfH = s.halfHeight + 0.03f
            if (abs(localX) <= halfW && abs(localY) <= halfH) {
                return s
            }
        }
        return null
    }

    fun hitTest(stickers: List<Sticker>, normX: Float, normY: Float, canvasAspectRatio: Float = 1f): String? =
        hitTestTopmost(stickers, normX, normY)?.id

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
