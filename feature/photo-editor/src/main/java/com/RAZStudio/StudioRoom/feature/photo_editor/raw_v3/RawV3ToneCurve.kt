/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * RAW Pipeline v3 — Tone Curve → LUT bridge.
 *
 * The Tone Curves tab stores 4 curves (master/luminance, R, G, B), each as 5
 * Y-values at fixed X = {0, 0.25, 0.5, 0.75, 1.0} (see ImageCurvesEditorState).
 * Optional [FilmCurve] parametric (sigmoid + highlight knee + shadow toe) is
 * applied UNDER the freehand points, then both bake into the same 256×3 LUT
 * used by the GL uber-shader and Stage C export.
 *
 * GPUImage's GPUImageToneCurveFilter (what the curve widget previews with)
 * composes the per-channel curve THEN the master curve:
 *     out_c = master( channel_c( in_c ) )
 * With film parametric: out_c = master( channel_c( film( in_c ) ) ).
 *
 * Interpolation: monotone cubic (Fritsch–Carlson). Photo curve tools use a
 * monotone spline so dragging a midpoint can't introduce overshoot/wiggle
 * between control points.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmCurve
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import kotlin.math.exp
import kotlin.math.pow

object RawV3ToneCurve {

    const val LUT_SIZE = 256

    /** True when all 4 curves are the identity line (no effect → skip upload). */
    fun isDefault(points: List<List<Float>>): Boolean {
        if (points.size < 4) return true
        // Identity control points: y == x at {0,.25,.5,.75,1}.
        val ident = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for (curve in points) {
            if (curve.size < 5) continue
            for (i in 0 until 5) {
                if (kotlin.math.abs(curve[i] - ident[i]) > 1e-4f) return false
            }
        }
        return true
    }

    fun isIdentity(points: List<List<Float>>, film: FilmCurve): Boolean =
        isDefault(points) && !film.isActive

    /**
     * Bake the 4 curves (+ optional film parametric) into a 256×3 interleaved
     * byte LUT (R,G,B per input index). Returns null when identity.
     */
    fun buildLut(
        points: List<List<Float>>,
        film: FilmCurve = FilmCurve(),
    ): ByteArray? {
        if (points.size < 4 || isIdentity(points, film)) return null
        val masterY = points[0]
        val rY = points[1]
        val gY = points[2]
        val bY = points[3]
        val master = sampleCurve(masterY)   // [256]
        val rCh = sampleCurve(rY)
        val gCh = sampleCurve(gY)
        val bCh = sampleCurve(bY)
        val filmLut = if (film.isActive) sampleFilmCurve(film) else null

        val out = ByteArray(LUT_SIZE * 3)
        for (i in 0 until LUT_SIZE) {
            val x0 = i.toFloat() / (LUT_SIZE - 1)
            val x = if (filmLut != null) applyThrough(filmLut, x0) else x0
            // channel curve first, then master (composite) — GPUImage order.
            val r = applyThrough(master, applyThrough(rCh, x))
            val g = applyThrough(master, applyThrough(gCh, x))
            val b = applyThrough(master, applyThrough(bCh, x))
            out[i * 3 + 0] = toByte(r)
            out[i * 3 + 1] = toByte(g)
            out[i * 3 + 2] = toByte(b)
        }
        return out
    }

    /** Convenience: build the LUT for a macro's tone curve + film parametric. */
    fun buildLut(macro: UserMacro): ByteArray? =
        buildLut(macro.toneCurvePoints, macro.filmCurve)

    /** Stable signature for live republish guards. */
    fun signature(macro: UserMacro): Int =
        31 * macro.toneCurvePoints.hashCode() + macro.filmCurve.hashCode()

    // Look up master[] at a [0,1] value with linear interpolation between
    // its 256 entries (the channel curve output feeds the master curve).
    private fun applyThrough(table: FloatArray, v: Float): Float {
        val x = v.coerceIn(0f, 1f) * (LUT_SIZE - 1)
        val i0 = x.toInt()
        val i1 = (i0 + 1).coerceAtMost(LUT_SIZE - 1)
        val t = x - i0
        return table[i0] + (table[i1] - table[i0]) * t
    }

    private fun toByte(v: Float): Byte =
        (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    /**
     * Bake [FilmCurve] into a 256-entry [0,1] table.
     * Order: sigmoid contrast → highlight knee → shadow toe.
     * Original math inspired by common logistic / power-knee tone mapping
     * (not a line-for-line port of any DCTL).
     */
    internal fun sampleFilmCurve(film: FilmCurve): FloatArray {
        val out = FloatArray(LUT_SIZE)
        val contrast = (film.contrast / 100f).coerceIn(0f, 1f)
        val pivot = film.pivot.coerceIn(0.05f, 0.95f)
        val knee = (film.highlightKnee / 100f).coerceIn(0f, 1f)
        val toe = (film.shadowToe / 100f).coerceIn(0f, 1f)
        for (k in 0 until LUT_SIZE) {
            var x = k.toFloat() / (LUT_SIZE - 1)
            if (contrast > 0f) x = sigmoidContrast(x, contrast, pivot)
            if (knee > 0f) x = highlightKnee(x, knee)
            if (toe > 0f) x = shadowToe(x, toe)
            out[k] = x.coerceIn(0f, 1f)
        }
        return out
    }

    /** Normalized logistic S-curve mixed toward identity by [amount]. */
    private fun sigmoidContrast(x: Float, amount: Float, pivot: Float): Float {
        val steep = 1f + amount * 11f // ~1..12; mid settings ≈ Resolve-style “6”
        fun sig(t: Float): Float = 1f / (1f + exp(-steep * (t - pivot)))
        val s0 = sig(0f)
        val s1 = sig(1f)
        val denom = (s1 - s0).coerceAtLeast(1e-6f)
        val shaped = ((sig(x) - s0) / denom).coerceIn(0f, 1f)
        return x + (shaped - x) * amount
    }

    /**
     * Soft highlight shoulder (power-knee family). Below [toe] unchanged;
     * above it, compress toward a gentle roll-off. [amount] mixes vs identity.
     */
    private fun highlightKnee(x: Float, amount: Float): Float {
        val t = 0.72f - amount * 0.12f // toe rises slightly as knee strengthens
        if (x <= t) return x
        val p = 1.6f + amount * 1.4f   // power
        val length = 1.02f + amount * 0.35f
        val s = (1f - t) / (
            (((1f - t) / (length - t)).pow(-p) - 1f).pow(1f / p)
            ).coerceAtLeast(1e-6f)
        val u = (x - t) / s.coerceAtLeast(1e-6f)
        val kn = t + s * (u / (1f + u.pow(p)).pow(1f / p))
        return x + (kn - x) * amount
    }

    /** Soft black lift / film fade in the lower tones. */
    private fun shadowToe(x: Float, amount: Float): Float {
        val lift = amount * 0.12f
        val shaped = x + lift * (1f - x).pow(2.2f) * (1f - x)
        // Mild compression of deep shadows so the lift doesn't blow midtones.
        val soft = shaped.pow(1f / (1f + amount * 0.35f))
        return x + (soft - x) * amount
    }

    // Evaluate a 5-control-point curve (Y at fixed X) into a 256-entry table
    // via monotone cubic interpolation.
    private fun sampleCurve(ys: List<Float>): FloatArray {
        val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val n = 5
        val y = FloatArray(n) { ys.getOrElse(it) { xs[it] } }
        // Secant slopes.
        val d = FloatArray(n - 1)
        for (i in 0 until n - 1) d[i] = (y[i + 1] - y[i]) / (xs[i + 1] - xs[i])
        // Tangents (Fritsch–Carlson).
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val h = a * a + b * b
            if (h > 9f) {
                val tt = 3f / kotlin.math.sqrt(h)
                m[i] = tt * a * d[i]
                m[i + 1] = tt * b * d[i]
            }
        }
        val out = FloatArray(LUT_SIZE)
        for (k in 0 until LUT_SIZE) {
            val xv = k.toFloat() / (LUT_SIZE - 1)
            // Find segment.
            var seg = 0
            while (seg < n - 2 && xv > xs[seg + 1]) seg++
            val h = xs[seg + 1] - xs[seg]
            val t = ((xv - xs[seg]) / h).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2f * t3 - 3f * t2 + 1f
            val h10 = t3 - 2f * t2 + t
            val h01 = -2f * t3 + 3f * t2
            val h11 = t3 - t2
            out[k] = (h00 * y[seg] + h10 * h * m[seg] +
                      h01 * y[seg + 1] + h11 * h * m[seg + 1]).coerceIn(0f, 1f)
        }
        return out
    }
}
