package com.example.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** Rules of temporary live-location sharing, kept free of Android types so they can be unit-tested. */
object LiveLocationPolicy {
    private const val MINUTE = 60_000L

    /** Durations the user can choose from (the server rejects anything above 8 hours). */
    val DURATIONS: List<Long> = listOf(15 * MINUTE, 60 * MINUTE, 8 * 60 * MINUTE)
    val MAX_DURATION_MS: Long = DURATIONS.last()

    /** Location updates requested from the phone (battery: about one fix every 15 s while sharing). */
    const val UPDATE_INTERVAL_MS = 15_000L
    const val FASTEST_INTERVAL_MS = 5_000L

    /** A new point is sent after this much movement, but not more often than [MIN_PUBLISH_GAP_MS]... */
    const val MIN_MOVE_METERS = 15f
    const val MIN_PUBLISH_GAP_MS = 10_000L
    /** ...and at least this often while fixes keep arriving, so viewers can tell "still live" from "lost". */
    const val HEARTBEAT_MS = 60_000L
    /** Fixes older than this are never sent: an old point must not look current. */
    const val MAX_FIX_AGE_MS = 2 * MINUTE
    /** Viewers stop calling a point live when it is older than this (missed heartbeats). */
    const val STALE_AFTER_MS = 3 * MINUTE

    fun isValidDuration(ms: Long): Boolean = ms in 1..MAX_DURATION_MS

    fun shouldPublish(now: Long, lastPublishedAt: Long, movedMeters: Float?): Boolean = when {
        lastPublishedAt == 0L -> true
        now - lastPublishedAt >= HEARTBEAT_MS -> true
        movedMeters != null && movedMeters >= MIN_MOVE_METERS && now - lastPublishedAt >= MIN_PUBLISH_GAP_MS -> true
        else -> false
    }

    /** With several providers, a fix replaces the previous one when it is newer and not much less precise. */
    fun isBetter(newTime: Long, newAccuracy: Float, oldTime: Long?, oldAccuracy: Float?): Boolean {
        if (oldTime == null || oldAccuracy == null) return true
        val newer = newTime - oldTime
        if (newer > 30_000L) return true
        if (newer <= 0L) return false
        return newAccuracy <= oldAccuracy * 1.5f
    }

    enum class Phase { LIVE, STALE, ENDED }

    /**
     * What a viewer sees: ended when the sender stopped or the time ran out (even if the sender's
     * phone could not report it), stale when no fresh point arrived recently (or this phone is
     * offline and its copy is old), otherwise live.
     */
    fun phase(now: Long, fixAt: Long, expiresAt: Long, endedAt: Long, fromCache: Boolean): Phase = when {
        endedAt > 0L || now >= expiresAt -> Phase.ENDED
        now - fixAt > STALE_AFTER_MS -> Phase.STALE
        fromCache && now - fixAt > HEARTBEAT_MS -> Phase.STALE
        else -> Phase.LIVE
    }
}

/** Web-Mercator maths for the map tiles (256 px tiles, standard XYZ numbering). */
object MapMath {
    const val TILE_SIZE = 256
    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 19
    private const val MAX_LAT = 85.05112878

    fun worldSize(zoom: Int): Double = TILE_SIZE * 2.0.pow(zoom)

    fun x(lng: Double, zoom: Int): Double = (lng + 180.0) / 360.0 * worldSize(zoom)

    fun y(lat: Double, zoom: Int): Double {
        val rad = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * worldSize(zoom)
    }

    /** A tile and where its top-left corner falls in the viewport (map pixels from the viewport's top-left). */
    data class Tile(val x: Int, val y: Int, val zoom: Int, val left: Double, val top: Double) {
        /** Column wrapped around the date line, as the tile server numbers it. */
        val wrappedX: Int get() { val n = 1 shl zoom; return ((x % n) + n) % n }
    }

    /** Only the tiles that intersect the viewport centred on ([centerX], [centerY]): nothing is fetched ahead. */
    fun visibleTiles(centerX: Double, centerY: Double, width: Double, height: Double, zoom: Int): List<Tile> {
        if (width <= 0 || height <= 0) return emptyList()
        val left = centerX - width / 2
        val top = centerY - height / 2
        val n = 1 shl zoom
        val firstX = floor(left / TILE_SIZE).toInt()
        val lastX = floor((left + width - 1) / TILE_SIZE).toInt()
        val firstY = floor(top / TILE_SIZE).toInt().coerceAtLeast(0)
        val lastY = floor((top + height - 1) / TILE_SIZE).toInt().coerceAtMost(n - 1)
        val tiles = ArrayList<Tile>()
        for (ty in firstY..lastY) for (tx in firstX..lastX) {
            tiles += Tile(tx, ty, zoom, tx * TILE_SIZE.toDouble() - left, ty * TILE_SIZE.toDouble() - top)
        }
        return tiles
    }
}
