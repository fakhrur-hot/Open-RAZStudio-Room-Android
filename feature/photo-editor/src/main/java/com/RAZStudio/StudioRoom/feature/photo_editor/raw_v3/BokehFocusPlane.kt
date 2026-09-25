/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Focus plane for FX bokeh. One function for preview upload and Stage C.
 * The blur shader still uses abs(depth − focus). This only chooses focus.
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import java.util.ArrayDeque

object BokehFocusPlane {

    private const val CORE_THRESHOLD = 0.85f
    private const val ERODE_PX = 2
    private const val CENTER_FRACTION = 0.35f
    private const val EDGE_BAND = 0.20f
    private const val EDGE_WEIGHT = 0.25f
    private const val BINS = 32

    /**
     * Dominant depth cluster inside the solid subject core.
     * [confidence] is reserved; pass null until a structure or demosaic map exists.
     * Returns 0.5 when [depth] is empty.
     */
    fun solve(
        subject: FloatArray?,
        subjectW: Int,
        subjectH: Int,
        depth: FloatArray,
        depthW: Int,
        depthH: Int,
        confidence: FloatArray? = null,
        previousFocus: Float = 0.5f,
    ): Float {
        if (depth.isEmpty() || depthW <= 0 || depthH <= 0 || depth.size < depthW * depthH) {
            return 0.5f
        }
        val n = depthW * depthH
        val core = BooleanArray(n)
        var any = false
        for (y in 0 until depthH) {
            val my = if (subjectH <= 1) 0 else ((y + 0.5f) * subjectH / depthH).toInt().coerceIn(0, subjectH - 1)
            for (x in 0 until depthW) {
                val mx = if (subjectW <= 1) 0 else ((x + 0.5f) * subjectW / depthW).toInt().coerceIn(0, subjectW - 1)
                val m = if (subject == null || subject.size <= my * subjectW + mx) 0f else subject[my * subjectW + mx]
                if (m > CORE_THRESHOLD) {
                    core[y * depthW + x] = true
                    any = true
                }
            }
        }
        if (!any) return median(depth, n)
        val eroded = erode(core, depthW, depthH, ERODE_PX)
        val blob = largestBlob(eroded, depthW, depthH) ?: return median(depth, n)

        val bins = FloatArray(BINS)
        val spanX = (blob.xmax - blob.xmin).coerceAtLeast(1).toFloat()
        val spanY = (blob.ymax - blob.ymin).coerceAtLeast(1).toFloat()
        val innerHalf = CENTER_FRACTION * 0.5f
        val fall = (0.5f - innerHalf).coerceAtLeast(1e-4f)
        for (y in blob.ymin..blob.ymax) {
            for (x in blob.xmin..blob.xmax) {
                val i = y * depthW + x
                if (!blob.member[i]) continue
                val nx = (x - blob.xmin) / spanX
                val ny = (y - blob.ymin) / spanY
                val dx = (kotlin.math.abs(nx - 0.5f) - innerHalf).coerceAtLeast(0f)
                val dy = (kotlin.math.abs(ny - 0.5f) - innerHalf).coerceAtLeast(0f)
                val t = (maxOf(dx, dy) / fall).coerceIn(0f, 1f)
                var w = 1f - 0.75f * t
                if (blob.touchLeft && nx <= EDGE_BAND) w *= EDGE_WEIGHT
                if (blob.touchRight && nx >= 1f - EDGE_BAND) w *= EDGE_WEIGHT
                if (blob.touchTop && ny <= EDGE_BAND) w *= EDGE_WEIGHT
                if (blob.touchBottom && ny >= 1f - EDGE_BAND) w *= EDGE_WEIGHT
                if (confidence != null && confidence.size > i) w *= confidence[i].coerceIn(0f, 1f)
                val d = depth[i].coerceIn(0f, 1f)
                val bin = (d * BINS).toInt().coerceIn(0, BINS - 1)
                bins[bin] += w
            }
        }
        var best = -1
        var bestScore = -1f
        for (i in 0 until BINS) {
            val score = bins[i] +
                (if (i > 0) bins[i - 1] else 0f) +
                (if (i + 1 < BINS) bins[i + 1] else 0f)
            if (score <= 0f) continue
            if (best < 0 || score > bestScore + 1e-4f) {
                best = i
                bestScore = score
            } else if (kotlin.math.abs(score - bestScore) <= 1e-4f) {
                val c0 = (best + 0.5f) / BINS
                val c1 = (i + 0.5f) / BINS
                if (kotlin.math.abs(c1 - previousFocus) < kotlin.math.abs(c0 - previousFocus)) {
                    best = i
                    bestScore = score
                }
            }
        }
        if (best < 0) return median(depth, n)
        return (best + 0.5f) / BINS
    }

    private fun median(depth: FloatArray, n: Int): Float {
        if (n <= 0) return 0.5f
        val copy = depth.copyOf(n)
        copy.sort()
        return copy[n / 2].coerceIn(0f, 1f)
    }

    private fun erode(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        var cur = src
        repeat(radius) {
            val next = BooleanArray(cur.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!cur[i]) continue
                    val left = x > 0 && cur[i - 1]
                    val right = x + 1 < w && cur[i + 1]
                    val up = y > 0 && cur[i - w]
                    val down = y + 1 < h && cur[i + w]
                    next[i] = left && right && up && down
                }
            }
            cur = next
        }
        return cur
    }

    private class Blob(
        val member: BooleanArray,
        val xmin: Int,
        val ymin: Int,
        val xmax: Int,
        val ymax: Int,
        val touchLeft: Boolean,
        val touchRight: Boolean,
        val touchTop: Boolean,
        val touchBottom: Boolean,
    )

    private fun largestBlob(core: BooleanArray, w: Int, h: Int): Blob? {
        val seen = BooleanArray(core.size)
        var bestCount = 0
        var best: Blob? = null
        val q = ArrayDeque<Int>()
        for (start in core.indices) {
            if (!core[start] || seen[start]) continue
            q.clear()
            q.add(start)
            seen[start] = true
            var count = 0
            var xmin = w
            var ymin = h
            var xmax = 0
            var ymax = 0
            var touchL = false
            var touchR = false
            var touchT = false
            var touchB = false
            val cells = ArrayList<Int>()
            while (q.isNotEmpty()) {
                val i = q.poll() ?: break
                val x = i % w
                val y = i / w
                cells.add(i)
                count++
                if (x < xmin) xmin = x
                if (y < ymin) ymin = y
                if (x > xmax) xmax = x
                if (y > ymax) ymax = y
                if (x == 0) touchL = true
                if (x == w - 1) touchR = true
                if (y == 0) touchT = true
                if (y == h - 1) touchB = true
                fun push(nx: Int, ny: Int) {
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) return
                    val j = ny * w + nx
                    if (!core[j] || seen[j]) return
                    seen[j] = true
                    q.add(j)
                }
                push(x - 1, y)
                push(x + 1, y)
                push(x, y - 1)
                push(x, y + 1)
            }
            if (count > bestCount) {
                val member = BooleanArray(core.size)
                for (c in cells) member[c] = true
                bestCount = count
                best = Blob(member, xmin, ymin, xmax, ymax, touchL, touchR, touchT, touchB)
            }
        }
        return best
    }
}
