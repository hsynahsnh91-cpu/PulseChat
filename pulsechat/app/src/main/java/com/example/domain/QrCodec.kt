package com.example.domain

import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code encoder and luminance/matrix decoder (Versions 1..8, ECC Level L,
 * Byte mode). Requires no external dependencies and runs identically on Android and JVM tests.
 */
object QrCodec {

    data class QrMatrix(val size: Int, val modules: Array<BooleanArray>) {
        operator fun get(x: Int, y: Int): Boolean = modules[y][x]
    }

    // Version 1..8 parameters at ECC Level L (single block for v1..v5, 2 blocks for v6..v8)
    private data class VersionSpec(
        val version: Int,
        val totalDataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocks: Int,
        val alignmentCenters: IntArray,
    )

    private val SPECS = arrayOf(
        VersionSpec(1, 19, 7, 1, intArrayOf()),
        VersionSpec(2, 34, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 55, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 80, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 108, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 136, 18, 2, intArrayOf(6, 34)),
        VersionSpec(7, 156, 20, 2, intArrayOf(6, 22, 38)),
        VersionSpec(8, 194, 24, 2, intArrayOf(6, 24, 42)),
    )

    /** Encodes [text] (UTF-8) into a standard ISO/IEC 18004 QR matrix (ECC Level L). */
    fun encode(text: String): QrMatrix {
        val data = text.toByteArray(StandardCharsets.UTF_8)
        val spec = SPECS.firstOrNull { spec ->
            // 4 bits mode + (8 or 16) bits length + 8*N data bits <= totalDataCodewords * 8
            val countBits = if (spec.version <= 9) 8 else 16
            4 + countBits + data.size * 8 <= spec.totalDataCodewords * 8
        } ?: throw IllegalArgumentException("Payload too long for QR code (${data.size} bytes)")

        val codewords = buildCodewords(data, spec)
        val size = 17 + spec.version * 4
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        drawFunctionPatterns(spec, size, modules, isFunction)
        drawCodewords(codewords, size, modules, isFunction)

        // Choose mask 0 (deterministic, standard mask (x + y) % 2 == 0) and write format info
        val mask = 0
        applyMask(mask, size, modules, isFunction)
        drawFormatBits(mask, size, modules, isFunction)
        if (spec.version >= 7) drawVersionBits(spec.version, size, modules, isFunction)

        return QrMatrix(size, modules)
    }

    /** Decodes a UTF-8 string from an unrotated or rotated QR module matrix, or null if invalid. */
    fun decodeMatrix(rawMatrix: Array<BooleanArray>): String? {
        val n = rawMatrix.size
        if (n < 21 || (n - 17) % 4 != 0) return null
        val version = (n - 17) / 4
        val spec = SPECS.getOrNull(version - 1) ?: return null
        for (rotation in 0..3) {
            val rotated = rotateMatrix(rawMatrix, rotation)
            if (!hasFinderPatterns(rotated)) continue
            val decoded = tryDecodeOrientedMatrix(rotated, spec)
            if (decoded != null) return decoded
        }
        return null
    }

    /**
     * Decodes a QR code from an 8-bit grayscale / YUV_420_888 luminance plane.
     * Supports arbitrary scale, quiet zones, and 0/90/180/270-degree orientations.
     */
    fun decodeFromLuminance(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? {
        if (width < 29 || height < 29 || yPlane.size < (height - 1) * rowStride + width) return null
        val binary = binarize(yPlane, width, height, rowStride)
        return decodeFromBinaryGrid(binary, width, height)
    }

    fun decodeLuminance(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? = decodeFromLuminance(yPlane, width, height, rowStride)

    internal fun decodeFromBinaryGrid(binary: Array<BooleanArray>, width: Int, height: Int): String? {
        // 1. Fast bounding-box grid extraction for clean / axis-aligned QR images
        extractAxisAlignedGrid(binary, width, height)?.let { matrix ->
            decodeMatrix(matrix)?.let { return it }
        }
        // 2. Finder-pattern center detection for camera frames
        val centers = findFinderCenters(binary, width, height)
        if (centers.size >= 3) {
            val sampled = sampleFromFinderCenters(binary, width, height, centers)
            if (sampled != null) {
                decodeMatrix(sampled)?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Encoding helpers

    private fun buildCodewords(data: ByteArray, spec: VersionSpec): ByteArray {
        val totalBits = spec.totalDataCodewords * 8
        val bits = BooleanArray(totalBits)
        var pos = 0
        fun putBits(value: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                if (pos < totalBits) bits[pos++] = ((value ushr i) and 1) != 0
            }
        }
        // Byte mode = 0100
        putBits(0b0100, 4)
        putBits(data.size, 8)
        for (b in data) putBits(b.toInt() and 0xFF, 8)
        // Terminator (up to 4 zero bits)
        putBits(0, min(4, totalBits - pos))
        // Byte-align
        while (pos % 8 != 0) putBits(0, 1)
        // Pad bytes 0xEC, 0x11
        val pads = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (pos < totalBits) {
            putBits(pads[padIdx], 8)
            padIdx = (padIdx + 1) and 1
        }
        val dataBytes = ByteArray(spec.totalDataCodewords)
        for (i in dataBytes.indices) {
            var v = 0
            for (b in 0..7) v = (v shl 1) or (if (bits[i * 8 + b]) 1 else 0)
            dataBytes[i] = v.toByte()
        }
        val blockSize = spec.totalDataCodewords / spec.numBlocks
        val dataBlocks = Array(spec.numBlocks) { b ->
            dataBytes.copyOfRange(b * blockSize, (b + 1) * blockSize)
        }
        val ecBlocks = Array(spec.numBlocks) { b ->
            reedSolomonRemainder(dataBlocks[b], spec.ecCodewordsPerBlock)
        }
        val out = ByteArray(spec.totalDataCodewords + spec.ecCodewordsPerBlock * spec.numBlocks)
        var k = 0
        for (i in 0 until blockSize) {
            for (b in 0 until spec.numBlocks) out[k++] = dataBlocks[b][i]
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) out[k++] = ecBlocks[b][i]
        }
        return out
    }

    private fun drawFunctionPatterns(
        spec: VersionSpec,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        fun setFunction(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                modules[y][x] = dark
                isFunction[y][x] = true
            }
        }
        // Timing patterns
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        // 3 Finder patterns + separators
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = max(abs(dx), abs(dy))
                    val dark = dist != 2 && dist != 4
                    setFunction(cx + dx, cy + dy, dark)
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Alignment patterns
        val align = spec.alignmentCenters
        for (ay in align) {
            for (ax in align) {
                if ((ax == 6 && ay == 6) || (ax == 6 && ay == size - 7) || (ax == size - 7 && ay == 6)) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = max(abs(dx), abs(dy))
                        setFunction(ax + dx, ay + dy, dist != 1)
                    }
                }
            }
        }
        // Reserve format info areas + dark module
        for (i in 0..8) {
            if (i != 6) {
                setFunction(8, i, false)
                setFunction(i, 8, false)
            }
        }
        for (i in 0..7) {
            setFunction(size - 1 - i, 8, false)
            setFunction(8, size - 1 - i, false)
        }
        setFunction(8, size - 8, true) // Always-dark module

        if (spec.version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    setFunction(size - 11 + j, i, false)
                    setFunction(i, size - 11 + j, false)
                }
            }
        }
    }

    private fun drawCodewords(
        codewords: ByteArray,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        var upward = true
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    if (!isFunction[y][x]) {
                        val dark = if (bitIdx < totalBits) {
                            ((codewords[bitIdx ushr 3].toInt() ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else false
                        modules[y][x] = dark
                        bitIdx++
                    }
                }
            }
            upward = !upward
            right -= 2
        }
    }

    private fun applyMask(
        mask: Int,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!isFunction[y][x] && maskBit(mask, x, y)) {
                    modules[y][x] = !modules[y][x]
                }
            }
        }
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        else -> false
    }

    private fun computeFormatBits(mask: Int): Int {
        // ECC Level L = 01
        val data = (0b01 shl 3) or (mask and 0b111)
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        return ((data shl 10) or rem) xor 0x5412
    }

    private fun drawFormatBits(
        mask: Int,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        val bits = computeFormatBits(mask)
        fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0
        for (i in 0..5) modules[i][8] = bit(i)
        modules[7][8] = bit(6)
        modules[8][8] = bit(7)
        modules[8][7] = bit(8)
        for (i in 9..14) modules[8][14 - i] = bit(i)
        for (i in 0..7) modules[8][size - 1 - i] = bit(i)
        for (i in 8..14) modules[size - 15 + i][8] = bit(i)
        // Mark as function
        for (i in 0..8) { isFunction[i][8] = true; isFunction[8][i] = true }
        for (i in 0..7) { isFunction[8][size - 1 - i] = true; isFunction[size - 1 - i][8] = true }
    }

    private fun drawVersionBits(
        version: Int,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) != 0
            val a = size - 11 + (i % 3)
            val b = i / 3
            modules[b][a] = dark
            modules[a][b] = dark
            isFunction[b][a] = true
            isFunction[a][b] = true
        }
    }

    // ------------------------------------------------------------------ GF(256) Reed-Solomon

    private fun gfMul(x: Int, y: Int): Int {
        var r = 0
        for (i in 7 downTo 0) {
            r = (r shl 1) xor ((r ushr 7) * 0x11D)
            r = r xor (((y ushr i) and 1) * x)
        }
        return r
    }

    private fun reedSolomonRemainder(data: ByteArray, degree: Int): ByteArray {
        val gen = ByteArray(degree)
        gen[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                gen[j] = (gfMul(gen[j].toInt() and 0xFF, root) xor (if (j + 1 < degree) gen[j + 1].toInt() and 0xFF else 0)).toByte()
            }
            root = gfMul(root, 0x02)
        }
        val result = ByteArray(degree)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            for (i in 0 until degree - 1) result[i] = result[i + 1]
            result[degree - 1] = 0
            for (i in 0 until degree) {
                result[i] = (result[i].toInt() xor gfMul(gen[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    // ------------------------------------------------------------------ Decoding helpers

    private fun rotateMatrix(m: Array<BooleanArray>, quarterTurns: Int): Array<BooleanArray> {
        val n = m.size
        val r = quarterTurns and 3
        if (r == 0) return Array(n) { y -> m[y].clone() }
        return Array(n) { y ->
            BooleanArray(n) { x ->
                when (r) {
                    1 -> m[n - 1 - x][y]
                    2 -> m[n - 1 - y][n - 1 - x]
                    else -> m[x][n - 1 - y]
                }
            }
        }
    }

    private fun hasFinderPatterns(m: Array<BooleanArray>): Boolean {
        val n = m.size
        fun checkCorner(cx: Int, cy: Int): Boolean {
            if (!m[cy][cx]) return false
            var matches = 0
            var total = 0
            for (dy in -3..3) {
                for (dx in -3..3) {
                    val dist = max(abs(dx), abs(dy))
                    val expected = dist != 2
                    total++
                    if (m[cy + dy][cx + dx] == expected) matches++
                }
            }
            return matches >= total - 4
        }
        return checkCorner(3, 3) && checkCorner(n - 4, 3) && checkCorner(3, n - 4)
    }

    private fun tryDecodeOrientedMatrix(m: Array<BooleanArray>, spec: VersionSpec): String? {
        val size = m.size
        val isFunction = Array(size) { BooleanArray(size) }
        val dummy = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(spec, size, dummy, isFunction)

        // Read format bits (primary and backup) and find best-matching mask (0..7)
        var fmt1 = 0
        for (i in 0..5) if (m[i][8]) fmt1 = fmt1 or (1 shl i)
        if (m[7][8]) fmt1 = fmt1 or (1 shl 6)
        if (m[8][8]) fmt1 = fmt1 or (1 shl 7)
        if (m[8][7]) fmt1 = fmt1 or (1 shl 8)
        for (i in 9..14) if (m[8][14 - i]) fmt1 = fmt1 or (1 shl i)

        var fmt2 = 0
        for (i in 0..7) if (m[8][size - 1 - i]) fmt2 = fmt2 or (1 shl i)
        for (i in 8..14) if (m[size - 15 + i][8]) fmt2 = fmt2 or (1 shl i)

        val candidateMasks = (0..7).sortedBy { mask ->
            val target = computeFormatBits(mask)
            min(Integer.bitCount(fmt1 xor target), Integer.bitCount(fmt2 xor target))
        }

        for (mask in candidateMasks.take(2)) {
            val working = Array(size) { y -> m[y].clone() }
            applyMask(mask, size, working, isFunction)
            val totalCodewords = spec.totalDataCodewords + spec.ecCodewordsPerBlock * spec.numBlocks
            val codewords = ByteArray(totalCodewords)
            var bitIdx = 0
            var right = size - 1
            var upward = true
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    val y = if (upward) size - 1 - vert else vert
                    for (j in 0..1) {
                        val x = right - j
                        if (!isFunction[y][x] && bitIdx < totalCodewords * 8) {
                            if (working[y][x]) {
                                codewords[bitIdx ushr 3] = (codewords[bitIdx ushr 3].toInt() or (1 shl (7 - (bitIdx and 7)))).toByte()
                            }
                            bitIdx++
                        }
                    }
                }
                upward = !upward
                right -= 2
            }
            // De-interleave data blocks
            val blockSize = spec.totalDataCodewords / spec.numBlocks
            val dataBytes = ByteArray(spec.totalDataCodewords)
            var k = 0
            for (i in 0 until blockSize) {
                for (b in 0 until spec.numBlocks) {
                    dataBytes[b * blockSize + i] = codewords[k++]
                }
            }
            parseByteModePayload(dataBytes)?.let { return it }
        }
        return null
    }

    private fun parseByteModePayload(dataBytes: ByteArray): String? {
        if (dataBytes.size < 2) return null
        val mode = (dataBytes[0].toInt() and 0xF0) ushr 4
        if (mode != 0b0100) return null
        val len = ((dataBytes[0].toInt() and 0x0F) shl 4) or ((dataBytes[1].toInt() and 0xF0) ushr 4)
        if (len <= 0 || len + 2 > dataBytes.size) return null
        val out = ByteArray(len)
        for (i in 0 until len) {
            val hi = (dataBytes[i + 1].toInt() and 0x0F) shl 4
            val lo = (dataBytes[i + 2].toInt() and 0xF0) ushr 4
            out[i] = (hi or lo).toByte()
        }
        return String(out, StandardCharsets.UTF_8)
    }

    private fun binarize(yPlane: ByteArray, width: Int, height: Int, rowStride: Int): Array<BooleanArray> {
        var sum = 0L
        var minY = 255
        var maxY = 0
        for (y in 0 until height) {
            val rowOff = y * rowStride
            for (x in 0 until width) {
                val v = yPlane[rowOff + x].toInt() and 0xFF
                sum += v
                if (v < minY) minY = v
                if (v > maxY) maxY = v
            }
        }
        val globalThreshold = if (maxY - minY > 30) (minY + maxY) / 2 else (sum / (width * height)).toInt()
        return Array(height) { y ->
            val rowOff = y * rowStride
            BooleanArray(width) { x ->
                (yPlane[rowOff + x].toInt() and 0xFF) < globalThreshold
            }
        }
    }

    private fun extractAxisAlignedGrid(binary: Array<BooleanArray>, width: Int, height: Int): Array<BooleanArray>? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (binary[y][x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX <= minX || maxY <= minY) return null
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        // Estimate module size from top-left finder pattern horizontal scan
        var run = 0
        val startY = minY + max(1, boxH / 40)
        for (dy in 0..min(12, boxH - 1)) {
            var r = 0
            for (x in minX..maxX) {
                if (binary[min(height - 1, startY + dy)][x]) r++ else break
            }
            if (r > run) run = r
        }
        val approxModule = if (run >= 7) run / 7.0 else boxW / 37.0
        if (approxModule < 0.8) return null
        val approxDim = ((boxW + boxH) / (2.0 * approxModule)).roundToInt()
        val bestDim = SPECS.map { 17 + it.version * 4 }.minByOrNull { abs(it - approxDim) } ?: return null
        val stepX = boxW.toDouble() / bestDim
        val stepY = boxH.toDouble() / bestDim
        return Array(bestDim) { my ->
            val py = (minY + (my + 0.5) * stepY).toInt().coerceIn(0, height - 1)
            BooleanArray(bestDim) { mx ->
                val px = (minX + (mx + 0.5) * stepX).toInt().coerceIn(0, width - 1)
                binary[py][px]
            }
        }
    }

    private data class FinderCenter(val x: Double, val y: Double, val moduleSize: Double)

    private fun findFinderCenters(binary: Array<BooleanArray>, width: Int, height: Int): List<FinderCenter> {
        val centers = mutableListOf<FinderCenter>()
        val counts = IntArray(5)
        for (y in 0 until height step max(1, height / 120)) {
            counts.fill(0)
            var state = 0
            for (x in 0 until width) {
                if (binary[y][x]) {
                    if (state and 1 == 1) state++
                    counts[state]++
                } else {
                    if (state and 1 == 1) {
                        counts[state]++
                    } else if (state == 4) {
                        if (isFinderRatio(counts)) {
                            val total = counts.sum()
                            val cx = x - counts[4] - counts[3] - counts[2] / 2.0
                            val cy = crossCheckVertical(binary, width, height, cx.toInt(), y, counts[2])
                            if (!cy.isNaN()) {
                                val mod = total / 7.0
                                val existing = centers.indexOfFirst { hypot(it.x - cx, it.y - cy) < mod * 3 }
                                if (existing >= 0) {
                                    val old = centers[existing]
                                    centers[existing] = FinderCenter((old.x + cx) / 2, (old.y + cy) / 2, (old.moduleSize + mod) / 2)
                                } else {
                                    centers.add(FinderCenter(cx, cy, mod))
                                }
                            }
                        }
                        counts[0] = counts[2]; counts[1] = counts[3]; counts[2] = counts[4]; counts[3] = 1; counts[4] = 0
                        state = 3
                    } else {
                        state++
                        counts[state]++
                    }
                }
            }
        }
        return centers
    }

    private fun isFinderRatio(c: IntArray): Boolean {
        val total = c.sum()
        if (total < 7) return false
        val m = total / 7.0
        val tol = m * 0.65
        return abs(m - c[0]) < tol && abs(m - c[1]) < tol && abs(3 * m - c[2]) < 3 * tol && abs(m - c[3]) < tol && abs(m - c[4]) < tol
    }

    private fun crossCheckVertical(binary: Array<BooleanArray>, width: Int, height: Int, cx: Int, startY: Int, maxCenter: Int): Double {
        if (cx !in 0 until width) return Double.NaN
        val c = IntArray(5)
        var y = startY
        while (y >= 0 && binary[y][cx]) { c[2]++; y-- }
        if (y < 0) return Double.NaN
        while (y >= 0 && !binary[y][cx] && c[1] <= maxCenter) { c[1]++; y-- }
        if (y < 0 || c[1] > maxCenter) return Double.NaN
        while (y >= 0 && binary[y][cx] && c[0] <= maxCenter) { c[0]++; y-- }
        y = startY + 1
        while (y < height && binary[y][cx]) { c[2]++; y++ }
        if (y >= height) return Double.NaN
        while (y < height && !binary[y][cx] && c[3] <= maxCenter) { c[3]++; y++ }
        if (y >= height || c[3] > maxCenter) return Double.NaN
        while (y < height && binary[y][cx] && c[4] <= maxCenter) { c[4]++; y++ }
        if (!isFinderRatio(c)) return Double.NaN
        return y - c[4] - c[3] - c[2] / 2.0
    }

    private fun sampleFromFinderCenters(
        binary: Array<BooleanArray>,
        width: Int,
        height: Int,
        centers: List<FinderCenter>,
    ): Array<BooleanArray>? {
        val top3 = centers.sortedByDescending { it.moduleSize }.take(3)
        if (top3.size < 3) return null
        // Identify top-left (vertex of the right angle) and the other two corners
        val d01 = hypot(top3[0].x - top3[1].x, top3[0].y - top3[1].y)
        val d12 = hypot(top3[1].x - top3[2].x, top3[1].y - top3[2].y)
        val d02 = hypot(top3[0].x - top3[2].x, top3[0].y - top3[2].y)
        val (tl, cA, cB) = when {
            d12 >= d01 && d12 >= d02 -> Triple(top3[0], top3[1], top3[2])
            d02 >= d01 && d02 >= d12 -> Triple(top3[1], top3[0], top3[2])
            else -> Triple(top3[2], top3[0], top3[1])
        }
        val cross = (cA.x - tl.x) * (cB.y - tl.y) - (cA.y - tl.y) * (cB.x - tl.x)
        val (tr, bl) = if (cross > 0) cA to cB else cB to cA
        val avgMod = (tl.moduleSize + tr.moduleSize + bl.moduleSize) / 3.0
        val dist = (hypot(tr.x - tl.x, tr.y - tl.y) + hypot(bl.x - tl.x, bl.y - tl.y)) / 2.0
        val approxDim = (dist / avgMod + 7.0).roundToInt()
        val dim = SPECS.map { 17 + it.version * 4 }.minByOrNull { abs(it - approxDim) } ?: return null
        val span = (dim - 7).toDouble()
        val ux = (tr.x - tl.x) / span
        val uy = (tr.y - tl.y) / span
        val vx = (bl.x - tl.x) / span
        val vy = (bl.y - tl.y) / span
        return Array(dim) { my ->
            BooleanArray(dim) { mx ->
                val px = (tl.x + (mx - 3) * ux + (my - 3) * vx).roundToInt().coerceIn(0, width - 1)
                val py = (tl.y + (mx - 3) * uy + (my - 3) * vy).roundToInt().coerceIn(0, height - 1)
                binary[py][px]
            }
        }
    }

    private fun hypot(dx: Double, dy: Double): Double = sqrt(dx * dx + dy * dy)
}
