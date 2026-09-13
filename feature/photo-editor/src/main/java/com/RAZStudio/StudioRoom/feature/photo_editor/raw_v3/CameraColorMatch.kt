/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * RAW Pipeline v3 — Camera Color Profile (route A) auto-matched curve.
 *
 * Route A keeps the FULL 16-bit RAW decode (RAW detail) but adopts the
 * in-camera colour by matching the neutral Stage-A render to the camera's
 * embedded JPEG preview. We derive a per-channel R/G/B tone curve by classic
 * histogram specification (CDF inversion): for each input level the output is
 * the embedded-JPEG level whose cumulative distribution first reaches the
 * source's. Matching all three channels independently simultaneously fixes
 * tone (brightness) AND colour (white-balance / camera-profile cast) without
 * any perceptual colour model — it simply reproduces what the camera itself
 * produced for this exact frame (the look the user sees in the gallery).
 *
 * This is the RawTherapee "Auto-Matched Curve" technique. The curve is emitted
 * in the SAME 256×3 interleaved byte layout as [RawV3ToneCurve.buildLut] so it
 * drops straight into the existing tone-curve slot (GL preview uploadToneCurve
 * + Stage C export toneCurveLut), and composes UNDER the user's own tone curve.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object CameraColorMatch {

    const val LUT_SIZE = 256

    /**
     * Per-channel histogram-specification LUT mapping [src] (the neutral
     * route-A Stage-A render) onto [ref] (the camera's embedded JPEG). Returns
     * a 256×3 interleaved byte LUT (R,G,B per input index), or null if either
     * bitmap is unusable. Bitmaps may be any size — only their global
     * per-channel histograms matter, so orientation/crop differences between
     * the RAW render and the embedded preview are irrelevant.
     */
    fun matchPerChannel(src: Bitmap, ref: Bitmap): ByteArray? {
        val srcHist = histogram(src) ?: return null
        val refHist = histogram(ref) ?: return null
        val out = ByteArray(LUT_SIZE * 3)
        for (c in 0 until 3) {
            val map = matchChannel(srcHist[c], refHist[c])
            for (i in 0 until LUT_SIZE) out[i * 3 + c] = map[i].toByte()
        }
        return out
    }

    /**
     * Compose a base per-channel LUT [base] (768 bytes) UNDER the user's tone
     * curve [userLut] (768 bytes, or null = identity): the camera match runs
     * first, then the user's Tone Curves edits stack on top —
     *     final_c[i] = userLut_c[ base_c[i] ].
     * Returns [userLut] when [base] is null (route B), [base] when [userLut] is
     * null (route A, no user curve).
     */
    fun compose(base: ByteArray?, userLut: ByteArray?): ByteArray? {
        if (base == null) return userLut
        if (userLut == null) return base
        if (base.size < LUT_SIZE * 3 || userLut.size < LUT_SIZE * 3) return base
        val out = ByteArray(LUT_SIZE * 3)
        for (i in 0 until LUT_SIZE) {
            for (c in 0 until 3) {
                val b = base[i * 3 + c].toInt() and 0xFF
                out[i * 3 + c] = userLut[b * 3 + c]
            }
        }
        return out
    }

    /** Decode embedded-JPEG bytes to a bounded-size bitmap for cheap matching. */
    fun decodeJpegBounded(bytes: ByteArray, maxLongSide: Int = 512): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= maxLongSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /**
     * Apply edge-aware chroma guided-filter smoothing to [bitmap] (ARGB_8888).
     * BT.601 luma is used as the guide; Cb and Cr are filtered independently so
     * luminance/edge structure is preserved while colour-cast transitions (flat
     * sky banding, skin cast from histogram-spec matching) are smoothed.
     *
     * Uses [RawV3Engine.guidedFilterRefine] (fast guided filter, scale=4) so the
     * cost scales with pixel count / 16 rather than full resolution. Returns a
     * fresh mutable bitmap — the caller is responsible for recycling [bitmap].
     */
    fun applyChromaGuidedFilter(
        bitmap: Bitmap,
        radius: Int = 16,
        eps: Float = 0.01f,
    ): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val Y  = FloatArray(n)
        val Cb = FloatArray(n)
        val Cr = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = ((p ushr 16) and 0xFF) * (1f / 255f)
            val g = ((p ushr 8)  and 0xFF) * (1f / 255f)
            val b = (p           and 0xFF) * (1f / 255f)
            // BT.601 YCbCr — offset chroma to [0,1] range
            val y  =  0.299f  * r + 0.587f  * g + 0.114f  * b
            val cb = -0.16874f * r - 0.33126f * g + 0.5f   * b + 0.5f
            val cr =  0.5f    * r - 0.41869f * g - 0.08131f * b + 0.5f
            Y[i]  = y
            Cb[i] = cb
            Cr[i] = cr
        }

        val filteredCb = RawV3Engine.guidedFilterRefine(Y, Cb, w, h, radius, eps, scale = 4) ?: Cb
        val filteredCr = RawV3Engine.guidedFilterRefine(Y, Cr, w, h, radius, eps, scale = 4) ?: Cr

        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        for (i in 0 until n) {
            val y  = Y[i]
            val cb = filteredCb[i] - 0.5f
            val cr = filteredCr[i] - 0.5f
            // BT.601 inverse
            val r = (y                      + 1.40200f * cr).coerceIn(0f, 1f)
            val g = (y - 0.34414f * cb - 0.71414f * cr).coerceIn(0f, 1f)
            val b = (y + 1.77200f * cb                ).coerceIn(0f, 1f)
            pixels[i] = (pixels[i] and 0xFF000000.toInt()) or
                ((r * 255f + 0.5f).toInt() shl 16) or
                ((g * 255f + 0.5f).toInt() shl  8) or
                 (b * 255f + 0.5f).toInt()
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun histogram(bmp: Bitmap): Array<LongArray>? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = Array(3) { LongArray(256) }
        for (p in px) {
            hist[0][(p ushr 16) and 0xFF]++
            hist[1][(p ushr 8) and 0xFF]++
            hist[2][p and 0xFF]++
        }
        return hist
    }

    // Histogram specification by CDF inversion: for each source level i, find
    // the ref level whose CDF first reaches srcCdf[i]. The result is inherently
    // monotonic. A light box smooth removes posterising flat-then-jump steps
    // that pure specification produces on sparse histograms.
    private fun matchChannel(srcH: LongArray, refH: LongArray): IntArray {
        val srcCdf = cdf(srcH)
        val refCdf = cdf(refH)
        val raw = IntArray(256)
        var j = 0
        for (i in 0 until 256) {
            val target = srcCdf[i]
            while (j < 255 && refCdf[j] < target) j++
            raw[i] = j
        }
        return smoothMonotonic(raw)
    }

    private fun cdf(h: LongArray): DoubleArray {
        var total = 0L
        for (v in h) total += v
        val out = DoubleArray(256)
        if (total == 0L) {
            for (i in 0 until 256) out[i] = i / 255.0
            return out
        }
        var acc = 0L
        for (i in 0 until 256) {
            acc += h[i]
            out[i] = acc.toDouble() / total
        }
        return out
    }

    // Radius-2 box smooth, then clamp to non-decreasing so the curve stays a
    // valid monotonic tone map (smoothing can only blur, not invert order).
    private fun smoothMonotonic(map: IntArray): IntArray {
        val r = 2
        val out = IntArray(256)
        for (i in 0 until 256) {
            var sum = 0
            var n = 0
            for (k in -r..r) {
                val idx = i + k
                if (idx in 0..255) { sum += map[idx]; n++ }
            }
            out[i] = (sum.toDouble() / n + 0.5).toInt().coerceIn(0, 255)
        }
        for (i in 1 until 256) if (out[i] < out[i - 1]) out[i] = out[i - 1]
        return out
    }
}
