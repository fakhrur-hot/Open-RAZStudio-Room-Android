/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Bokeh (background blur + highlight boost + bloom) — ported from Razsizr's
 * mask-tools AndroidMaskToolsProcessor.applyBokehBlur. Operates on a decoded
 * ARGB_8888 bitmap and is subject-mask gated so only the BACKGROUND is blurred.
 *
 * Three stages (all optional, all background-only):
 *   1. Disc blur — convolves a circular aperture kernel over the highlight layer
 *      (luma ≥ 140) → the characteristic crisp "bokeh balls".
 *   2. Bilateral blur — edge-preserving blur on highlight regions.
 *   3. Highlight spread (bloom) — Gaussian-blurred white haze over highlights.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bokeh tool parameters (subset ported from Razsizr BokehBlurParams). */
data class BokehParams(
    val blurLevel: Int = 0,          // bilateral blur strength on highlights, 0..100
    val bokehLevel: Int = 0,         // disc-blur (bokeh balls) strength, 0..100
    val spreadEnabled: Boolean = false,
    val spreadIntensity: Float = 0.5f, // bloom radius/strength, 0..1
    val isolateSubject: Boolean = true,
) {
    val any: Boolean get() = blurLevel > 0 || bokehLevel > 0 || (spreadEnabled && spreadIntensity > 0f)
    companion object { val Default = BokehParams() }
}

object RawV3Bokeh {

    /**
     * Apply bokeh to [image]. [subjectMask] is an optional row-major 0..255
     * per-pixel subject probability (255 = subject). When sized differently
     * from the image it's nearest-sampled. Returns a NEW bitmap, or [image]
     * unchanged when no effect is active.
     */
    fun apply(
        image: Bitmap,
        params: BokehParams,
        subjectMask: FloatArray?,   // 0..1 subject probability (1 = subject)
        maskW: Int,
        maskH: Int,
    ): Bitmap {
        if (!params.any) return image
        // getPixels throws on HARDWARE/non-ARGB bitmaps (the neutral thumbnail
        // used for the live proxy is often Config.HARDWARE). Copy to a software
        // ARGB_8888 buffer first.
        val safe = if (image.config == Bitmap.Config.ARGB_8888) image
                   else image.copy(Bitmap.Config.ARGB_8888, false)
        val scaledDims = PreviewAllocationGuard.capDimensions(safe.width, safe.height)
        val source = if (scaledDims.first != safe.width || scaledDims.second != safe.height) {
            Bitmap.createScaledBitmap(safe, scaledDims.first, scaledDims.second, true)
        } else {
            safe
        }
        val w = source.width
        val h = source.height
        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        var work = srcPixels.copyOf()

        // Resample the (small) subject mask to image resolution once, as 0..1.
        val mask: FloatArray? = if (subjectMask != null && maskW > 0 && maskH > 0)
            resampleMask(subjectMask, maskW, maskH, w, h) else null

        // ── Track A: FULL background Gaussian blur (portrait-mode) ──────────────
        // "Background Blur" now blurs the WHOLE background, not just highlights —
        // the visible depth-of-field effect. The blurred result is composited
        // over the original by (1 - subjectMask) so the subject stays sharp; a
        // soft mask edge feathers the transition. Without a mask we blur all.
        if (params.blurLevel > 0) {
            val t = params.blurLevel / 100f
            // Map 1..100 → odd Gaussian kernel; scale with the longer side so the
            // perceived blur is resolution-independent.
            val longSide = maxOf(w, h)
            val maxRadius = (longSide * 0.06f).roundToInt().coerceAtLeast(3)
            val radius = (t * maxRadius).roundToInt().coerceAtLeast(1)
            val blurred = gaussianBlurRgb(work, w, h, radius)
            for (i in work.indices) {
                // [mask] semantics depend on [params.isolateSubject]:
                //   • true  (legacy U2Net subject mask): bg = 1 - mask, so we
                //     blur where mask=0 (outside the subject) and keep the
                //     subject sharp.
                //   • false (user-painted bokeh mask): bg = mask directly, so
                //     we blur WHERE the user painted and keep unpainted areas
                //     sharp. Same composite formula either way.
                val m = mask?.get(i) ?: 0f
                val bg = if (params.isolateSubject) 1f - m else m
                if (bg <= 0f) continue
                val s = work[i]; val b = blurred[i]
                work[i] = Color.argb(
                    Color.alpha(s),
                    (Color.red(s) * (1f - bg) + Color.red(b) * bg).roundToInt(),
                    (Color.green(s) * (1f - bg) + Color.green(b) * bg).roundToInt(),
                    (Color.blue(s) * (1f - bg) + Color.blue(b) * bg).roundToInt(),
                )
            }
        }

        // ── Track B: Bokeh balls (disc blur of highlights), mask-gated ───
        // Mask semantics mirror the Track A block: with isolateSubject=true
        // the disc blur paints the BACKGROUND (mask=0 area); with
        // isolateSubject=false it paints the PAINTED region (mask>0 area).
        if (params.bokehLevel > 0) {
            val radius = (params.bokehLevel / 100f * 50f).roundToInt().coerceAtLeast(1)
            val disc = applyDiscBlur(srcPixels, w, h, radius)  // from ORIGINAL highlights
            for (i in work.indices) {
                val dr = Color.red(disc[i]); val dg = Color.green(disc[i]); val db = Color.blue(disc[i])
                if (dr > 0 || dg > 0 || db > 0) {
                    val m = mask?.get(i) ?: 0f
                    val bg = if (params.isolateSubject) 1f - m else m
                    if (bg > 0.5f) {
                        work[i] = Color.argb(
                            Color.alpha(work[i]),
                            maxOf(Color.red(work[i]), (dr * bg).roundToInt()),
                            maxOf(Color.green(work[i]), (dg * bg).roundToInt()),
                            maxOf(Color.blue(work[i]), (db * bg).roundToInt()),
                        )
                    }
                }
            }
        }

        // ── Track B: Highlight spread / bloom, subject-feathered ────────────────
        if (params.spreadEnabled && params.spreadIntensity > 0f) {
            val hl = BooleanArray(srcPixels.size) { lumaOf(srcPixels[it]) >= 140f }
            work = applyHighlightSpread(work, w, h, hl, mask, params.spreadIntensity)
        }

        val out = IntArray(w * h) { i ->
            Color.argb(Color.alpha(srcPixels[i]), Color.red(work[i]), Color.green(work[i]), Color.blue(work[i]))
        }
        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (source !== safe && source !== image) source.recycle()
        return result
    }

    /** Full RGB Gaussian blur via OpenCV (Track A background blur). */
    private fun gaussianBlurRgb(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val bytes = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            bytes[i * 3] = Color.red(p).toByte()
            bytes[i * 3 + 1] = Color.green(p).toByte()
            bytes[i * 3 + 2] = Color.blue(p).toByte()
        }
        val mat = Mat(height, width, CvType.CV_8UC3); mat.put(0, 0, bytes)
        val k = (2 * radius + 1).coerceAtMost(199)  // odd, capped
        Imgproc.GaussianBlur(mat, mat, Size(k.toDouble(), k.toDouble()), 0.0)
        val out = ByteArray(width * height * 3); mat.get(0, 0, out); mat.release()
        return IntArray(width * height) { i ->
            Color.rgb(out[i * 3].toInt() and 0xFF, out[i * 3 + 1].toInt() and 0xFF, out[i * 3 + 2].toInt() and 0xFF)
        }
    }

    private fun lumaOf(p: Int): Float =
        0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
    private fun lumaOf(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

    /** Nearest-sample a [mw]×[mh] mask (0..1 floats) up to [w]×[h]. */
    private fun resampleMask(src: FloatArray, mw: Int, mh: Int, w: Int, h: Int): FloatArray {
        if (mw == w && mh == h) return src
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = (y * mh / h).coerceIn(0, mh - 1)
            for (x in 0 until w) {
                val sx = (x * mw / w).coerceIn(0, mw - 1)
                out[y * w + x] = src[sy * mw + sx]
            }
        }
        return out
    }

    private fun applyBilateralBlurRaw(pixels: IntArray, width: Int, height: Int, diameter: Int, sigma: Double): IntArray {
        val bytes = ByteArray(width * height * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            bytes[i * 4] = Color.red(p).toByte()
            bytes[i * 4 + 1] = Color.green(p).toByte()
            bytes[i * 4 + 2] = Color.blue(p).toByte()
            bytes[i * 4 + 3] = Color.alpha(p).toByte()
        }
        val src = Mat(height, width, CvType.CV_8UC4); src.put(0, 0, bytes)
        val src3 = Mat(); Imgproc.cvtColor(src, src3, Imgproc.COLOR_RGBA2RGB); src.release()
        val dst3 = Mat(); Imgproc.bilateralFilter(src3, dst3, diameter, sigma, sigma); src3.release()
        val outBytes = ByteArray(width * height * 3); dst3.get(0, 0, outBytes); dst3.release()
        return IntArray(width * height) { i ->
            Color.rgb(outBytes[i * 3].toInt() and 0xFF, outBytes[i * 3 + 1].toInt() and 0xFF, outBytes[i * 3 + 2].toInt() and 0xFF)
        }
    }

    private fun applyDiscBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val hlBytes = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p).toFloat(); val g = Color.green(p).toFloat(); val b = Color.blue(p).toFloat()
            if (lumaOf(r, g, b) >= 140f) {
                hlBytes[i * 3] = r.roundToInt().toByte()
                hlBytes[i * 3 + 1] = g.roundToInt().toByte()
                hlBytes[i * 3 + 2] = b.roundToInt().toByte()
            }
        }
        val hlMat = Mat(height, width, CvType.CV_8UC3); hlMat.put(0, 0, hlBytes)
        val kSize = radius * 2 + 1
        val inner = radius * 0.98f
        val kData = FloatArray(kSize * kSize)
        for (ky in 0 until kSize) for (kx in 0 until kSize) {
            val dx = (kx - radius).toFloat(); val dy = (ky - radius).toFloat()
            val dist = sqrt(dx * dx + dy * dy)
            kData[ky * kSize + kx] = when {
                dist > radius.toFloat() -> 0f
                dist > inner -> 1f - (dist - inner) / (radius * 0.02f)
                else -> 1f
            }
        }
        val kernel = Mat(kSize, kSize, CvType.CV_32F); kernel.put(0, 0, kData)
        Core.normalize(kernel, kernel, 1.0, 0.0, Core.NORM_L1)
        val dst = Mat()
        Imgproc.filter2D(hlMat, dst, -1, kernel, Point(-1.0, -1.0), 0.0, Core.BORDER_REPLICATE)
        hlMat.release(); kernel.release()
        val outBytes = ByteArray(width * height * 3); dst.get(0, 0, outBytes); dst.release()
        return IntArray(width * height) { i ->
            Color.rgb(outBytes[i * 3].toInt() and 0xFF, outBytes[i * 3 + 1].toInt() and 0xFF, outBytes[i * 3 + 2].toInt() and 0xFF)
        }
    }

    private fun applyHighlightSpread(
        srcPixels: IntArray, width: Int, height: Int,
        highlightMask: BooleanArray, subjectMask: FloatArray?, spreadIntensity: Float,
    ): IntArray {
        val lumaBytes = ByteArray(width * height)
        for (i in srcPixels.indices) {
            if (highlightMask[i]) lumaBytes[i] = lumaOf(srcPixels[i]).roundToInt().coerceIn(0, 255).toByte()
        }
        val radius = (spreadIntensity * 20f).roundToInt().coerceAtLeast(1)
        val blurred = blurGlowLuma(lumaBytes, width, height, radius)
        val bgMax = spreadIntensity * 0.35f
        val subjMax = bgMax * (5f / 15f)
        val edgeMax = subjMax * 1.5f
        return IntArray(width * height) { i ->
            val p = srcPixels[i]
            val luma = (blurred[i].toInt() and 0xFF) / 255f
            val ma = subjectMask?.get(i) ?: 0f
            val maxA = when {
                subjectMask == null -> bgMax
                ma >= 0.88f -> subjMax   // solid subject (≈224/255)
                ma >= 0.125f -> edgeMax  // soft edge (≈32/255)
                else -> bgMax
            }
            val a = luma * maxA
            if (a <= 0f) p else Color.argb(
                Color.alpha(p),
                (Color.red(p) * (1f - a) + 255f * a).roundToInt().coerceIn(0, 255),
                (Color.green(p) * (1f - a) + 255f * a).roundToInt().coerceIn(0, 255),
                (Color.blue(p) * (1f - a) + 255f * a).roundToInt().coerceIn(0, 255),
            )
        }
    }

    private fun blurGlowLuma(lumaBytes: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val mat = Mat(height, width, CvType.CV_8UC1); mat.put(0, 0, lumaBytes)
        val k = (2 * radius + 1).coerceAtMost(151)
        Imgproc.GaussianBlur(mat, mat, Size(k.toDouble(), k.toDouble()), radius.toDouble())
        val out = ByteArray(width * height); mat.get(0, 0, out); mat.release()
        return out
    }
}
