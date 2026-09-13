/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Applies tone curves to a [Bitmap] entirely on the CPU using per-channel 256-entry LUTs.
 *
 * The four channels are applied in this order:
 *  1. **Luminance** curve — applied uniformly to R, G and B (global brightness/contrast).
 *  2. **R**, **G**, **B** individual curves — applied on top of the luminance-adjusted values.
 *
 * Combined effect per channel: `finalR = lutR[ lutLum[r] ]`.
 *
 * Curve shape is computed via **natural cubic spline interpolation**. Control points are
 * treated as being evenly spaced from x = 0 to x = 1. The resulting spline is sampled at
 * 256 evenly-spaced x positions (0/255 … 255/255). Output values are clamped to [0, 255].
 *
 * If a channel's control-point list is empty **or** the resulting LUT is already the
 * identity mapping `lut[i] == i`, that channel is skipped to save work. If **all four**
 * channels are identity the original bitmap is returned unchanged (no copy made).
 */
object ToneCurvesApplicator {

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Applies tone curves to [bitmap] and returns the result as a new ARGB_8888 bitmap.
     *
     * @param bitmap        source bitmap (any config — internally converted to ARGB_8888)
     * @param controlPoints list of exactly 4 inner lists: [luminance, R, G, B].
     *                      Each inner list holds Y values (0.0–1.0) for evenly-spaced X
     *                      positions.  An empty inner list → identity curve for that channel.
     * @return              new ARGB_8888 bitmap with curves applied, or [bitmap] unchanged
     *                      when all four curves are identity.
     */
    fun apply(bitmap: Bitmap, controlPoints: List<List<Float>>): Bitmap {
        if (controlPoints.size < 4) return bitmap

        val lumValues = controlPoints[0]
        val rValues = controlPoints[1]
        val gValues = controlPoints[2]
        val bValues = controlPoints[3]

        val lutLum = buildLut(lumValues)
        val lutR = buildLut(rValues)
        val lutG = buildLut(gValues)
        val lutB = buildLut(bValues)

        // Fast path: nothing to do
        if (isIdentity(lutLum) && isIdentity(lutR) && isIdentity(lutG) && isIdentity(lutB)) {
            return bitmap
        }

        // Compose luminance + per-channel LUTs into three final lookup tables so we only
        // do three table lookups per pixel in the hot loop instead of six.
        val finalR = composeLuts(lutLum, lutR)
        val finalG = composeLuts(lutLum, lutG)
        val finalB = composeLuts(lutLum, lutB)

        // Ensure the source is ARGB_8888 for direct pixel access
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Apply the LUTs in-place
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = pixel ushr 24 and 0xFF
            val r = pixel ushr 16 and 0xFF
            val g = pixel ushr 8 and 0xFF
            val b = pixel and 0xFF

            val nr = finalR[r].toInt() and 0xFF
            val ng = finalG[g].toInt() and 0xFF
            val nb = finalB[b].toInt() and 0xFF

            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    // -------------------------------------------------------------------------
    // LUT construction
    // -------------------------------------------------------------------------

    /**
     * Builds a 256-entry byte LUT from a list of evenly-spaced Y control-point values in
     * [0, 1]. Each entry `lut[i]` holds the output value for input intensity `i` (0–255).
     *
     * Uses natural cubic spline interpolation when there are at least 2 control points,
     * otherwise falls back to a single constant or identity.
     */
    internal fun buildLut(yValues: List<Float>): ByteArray {
        val lut = ByteArray(256)

        when {
            yValues.isEmpty() -> {
                // Identity
                for (i in 0..255) lut[i] = i.toByte()
            }

            yValues.size == 1 -> {
                // Constant output
                val v = (yValues[0].coerceIn(0f, 1f) * 255f).roundToInt()
                for (i in 0..255) lut[i] = v.toByte()
            }

            yValues.size == 2 -> {
                // Linear interpolation between two points
                val y0 = yValues[0].coerceIn(0f, 1f)
                val y1 = yValues[1].coerceIn(0f, 1f)
                for (i in 0..255) {
                    val t = i / 255f
                    val y = y0 + t * (y1 - y0)
                    lut[i] = (y * 255f).roundToInt().coerceIn(0, 255).toByte()
                }
            }

            else -> {
                // Natural cubic spline
                val n = yValues.size
                val xs = FloatArray(n) { it.toFloat() / (n - 1) }
                val ys = FloatArray(n) { yValues[it].coerceIn(0f, 1f) }
                val spline = naturalCubicSpline(xs, ys)

                for (i in 0..255) {
                    val x = i / 255f
                    val y = evalSpline(spline, xs, x)
                    lut[i] = (y * 255f).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }

        return lut
    }

    // -------------------------------------------------------------------------
    // Cubic spline
    // -------------------------------------------------------------------------

    /**
     * Coefficients for one cubic segment [x_k, x_{k+1}]:
     *   f(x) = a + b*(x−x_k) + c*(x−x_k)^2 + d*(x−x_k)^3
     */
    private data class SplineSegment(val a: Float, val b: Float, val c: Float, val d: Float)

    /**
     * Builds a natural (free-boundary) cubic spline for the given knots.
     *
     * Implementation uses the tridiagonal Thomas algorithm to solve the linear system
     * for the second derivatives at each knot, then converts to [a, b, c, d] form.
     *
     * @param xs  strictly increasing x-values (length n ≥ 2)
     * @param ys  corresponding y-values (length n)
     * @return    list of (n−1) [SplineSegment]s
     */
    private fun naturalCubicSpline(xs: FloatArray, ys: FloatArray): List<SplineSegment> {
        val n = xs.size
        val h = FloatArray(n - 1) { xs[it + 1] - xs[it] }

        // Build right-hand side for the tridiagonal system
        val alpha = FloatArray(n - 1)
        for (i in 1 until n - 1) {
            alpha[i] = 3f / h[i] * (ys[i + 1] - ys[i]) - 3f / h[i - 1] * (ys[i] - ys[i - 1])
        }

        // Thomas algorithm (forward sweep)
        val l = FloatArray(n) { 1f }
        val mu = FloatArray(n)
        val z = FloatArray(n)

        for (i in 1 until n - 1) {
            l[i] = 2f * (xs[i + 1] - xs[i - 1]) - h[i - 1] * mu[i - 1]
            if (l[i] == 0f) l[i] = 1e-9f  // guard against division by zero
            mu[i] = h[i] / l[i]
            z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i]
        }

        // Back substitution for second derivatives (c-coefficients)
        val c = FloatArray(n)
        val b = FloatArray(n - 1)
        val d = FloatArray(n - 1)

        for (j in n - 2 downTo 0) {
            c[j] = z[j] - mu[j] * c[j + 1]
            b[j] = (ys[j + 1] - ys[j]) / h[j] - h[j] * (c[j + 1] + 2f * c[j]) / 3f
            d[j] = (c[j + 1] - c[j]) / (3f * h[j])
        }

        return (0 until n - 1).map { i ->
            SplineSegment(a = ys[i], b = b[i], c = c[i], d = d[i])
        }
    }

    /**
     * Evaluates the spline at position [x], extrapolating linearly outside [xs] range.
     */
    private fun evalSpline(segments: List<SplineSegment>, xs: FloatArray, x: Float): Float {
        if (x <= xs.first()) {
            // Linear extrapolation from the first segment
            val seg = segments.first()
            val dx = x - xs[0]
            return seg.a + seg.b * dx
        }
        if (x >= xs.last()) {
            // Linear extrapolation from the last segment
            val seg = segments.last()
            val dx = x - xs[xs.size - 2]
            return seg.a + seg.b * dx + seg.c * dx * dx + seg.d * dx * dx * dx
        }

        // Binary search for the correct segment
        var lo = 0
        var hi = xs.size - 2
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (xs[mid + 1] < x) lo = mid + 1 else hi = mid
        }
        val seg = segments[lo]
        val dx = x - xs[lo]
        return seg.a + seg.b * dx + seg.c * dx * dx + seg.d * dx * dx * dx
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    /** Returns true when every entry `lut[i] == i` (identity mapping). */
    private fun isIdentity(lut: ByteArray): Boolean {
        for (i in 0..255) {
            if ((lut[i].toInt() and 0xFF) != i) return false
        }
        return true
    }

    /**
     * Composes two LUTs: `result[i] = second[ first[i] ]`.
     *
     * Used to fold the luminance pass into the per-channel pass so the hot pixel loop
     * only performs one lookup per channel.
     */
    private fun composeLuts(first: ByteArray, second: ByteArray): ByteArray {
        return ByteArray(256) { i -> second[first[i].toInt() and 0xFF] }
    }
}
