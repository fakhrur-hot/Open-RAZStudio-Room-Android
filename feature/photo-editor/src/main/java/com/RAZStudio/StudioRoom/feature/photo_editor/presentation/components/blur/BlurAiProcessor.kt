/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * AI-aware blur masking: overlays the original (unblurred) subject on top of the
 * bokeh-blurred background using the U2Net subject mask.
 *
 * Compositing (smooth linear blend — same as old app DeepLabBokeh):
 *   keepMap[i] = feather( subjectMask[i] )
 *   output[i]  = original[i] × keepMap[i] + blurred[i] × (1 − keepMap[i])
 *
 * No Sobel edge detection — the U2Net float alpha already provides smooth natural
 * transitions at the subject boundary without extra edge processing.
 *
 * [edgeBlur] (Edge Feather slider, 0–1): controls feather radius.
 *   0 = tight boundary, 1 = wide soft gradient.
 */
class BlurAiProcessor {

    companion object {
        /** Maximum box-blur radius at 25% scale for edge feathering (edgeBlur=1.0). */
        private const val MAX_FEATHER_RADIUS = 12
    }

    /**
     * Overlays [original] (unblurred subject) onto [blurred] background using the
     * U2Net [subjectMask]. The mask is feathered before compositing so the subject
     * edge blends naturally without hard cutoffs.
     *
     * [edgeBlur] 0 = tight subject edge, 1 = wide feathered transition.
     */
    fun applyBlurMask(
        original: Bitmap,
        blurred: Bitmap,
        subjectMask: FloatArray?,
        edgeBlur: Float,
    ): Bitmap {
        val w = original.width
        val h = original.height

        val origPixels = IntArray(w * h)
        original.getPixels(origPixels, 0, w, 0, 0, w, h)

        val blurPixels = IntArray(blurred.width * blurred.height)
        blurred.getPixels(blurPixels, 0, blurred.width, 0, 0, blurred.width, blurred.height)

        // If no subject mask, return blurred as-is
        if (subjectMask == null) return blurred.copy(Bitmap.Config.ARGB_8888, false)

        val featherRadius = (edgeBlur * (MAX_FEATHER_RADIUS - 1) + 1f).toInt().coerceAtLeast(1)
        val keepMap = featherMask(subjectMask, w, h, featherRadius)

        val result = IntArray(w * h)
        for (i in result.indices) {
            val keep = keepMap[i]
            val blur = 1f - keep
            val op = origPixels[i]
            val bp = blurPixels.getOrElse(i) { op }
            result[i] = (0xFF shl 24) or
                (((op shr 16 and 0xFF) * keep + (bp shr 16 and 0xFF) * blur).toInt().coerceIn(0, 255) shl 16) or
                (((op shr 8 and 0xFF) * keep + (bp shr 8 and 0xFF) * blur).toInt().coerceIn(0, 255) shl 8) or
                ((op and 0xFF) * keep + (bp and 0xFF) * blur).toInt().coerceIn(0, 255)
        }

        return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Reshapes bokeh highlights into an N-gon aperture shape.
     *
     * Works at 25 % scale for affordable convolution, then screen-blends the
     * shaped highlight bloom back over the full-resolution [blurred] bitmap.
     *
     * [sides] = 0 → no-op (perfect circle; caller should skip).
     * [sides] = 4 → square bokeh; 16 → near-circle 16-gon.
     *
     * Threshold at which a pixel is considered a "highlight" is 50 % luminance.
     * Kernel radius is fixed at 8 px at 25 % scale (≈ 32 px at full scale).
     */
    suspend fun applyBokehShape(blurred: Bitmap, sides: Int): Bitmap = withContext(Dispatchers.Default) {
        if (sides == 0) return@withContext blurred

        val w = blurred.width
        val h = blurred.height

        val scale = 0.25f
        val sw = maxOf(2, (w * scale).toInt())
        val sh = maxOf(2, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(blurred, sw, sh, true)

        val smallPx = IntArray(sw * sh)
        small.getPixels(smallPx, 0, sw, 0, 0, sw, sh)

        // Extract per-channel highlight layer (pixels brighter than 50 % luma).
        val threshold = 0.50f
        val rH = FloatArray(sw * sh)
        val gH = FloatArray(sw * sh)
        val bH = FloatArray(sw * sh)
        for (i in smallPx.indices) {
            val px = smallPx[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val t = ((luma - threshold) / (1f - threshold)).coerceIn(0f, 1f)
            rH[i] = r * t
            gH[i] = g * t
            bH[i] = b * t
        }

        // Build N-gon kernel and convolve each highlight channel.
        val kRadius = 8
        val kernel = buildNgonKernel(sides, kRadius)
        val kSize = 2 * kRadius + 1
        val rB = convolveChannel(rH, sw, sh, kernel, kSize)
        val gB = convolveChannel(gH, sw, sh, kernel, kSize)
        val bB = convolveChannel(bH, sw, sh, kernel, kSize)

        val bloomSmallPx = IntArray(sw * sh) { i ->
            val r = (rB[i] * 255f).toInt().coerceIn(0, 255)
            val g = (gB[i] * 255f).toInt().coerceIn(0, 255)
            val b = (bB[i] * 255f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bloomSmall = Bitmap.createBitmap(bloomSmallPx, sw, sh, Bitmap.Config.ARGB_8888)
        val bloomFull = Bitmap.createScaledBitmap(bloomSmall, w, h, true)
        bloomSmall.recycle()
        val bloomPx = IntArray(w * h)
        bloomFull.getPixels(bloomPx, 0, w, 0, 0, w, h)
        bloomFull.recycle()

        val origPx = IntArray(w * h)
        blurred.getPixels(origPx, 0, w, 0, 0, w, h)

        // Screen blend: S(a,b) = 1 − (1−a)(1−b) — adds shaped glow without overexposing.
        // Preserve source alpha so transparent corner pixels (from Coil blur edge artefacts)
        // stay transparent instead of becoming opaque-black in the overlay.
        val result = IntArray(w * h) { i ->
            val op = origPx[i]; val bp = bloomPx[i]
            val srcA = (op ushr 24) and 0xFF
            val or_ = (op shr 16) and 0xFF; val og = (op shr 8) and 0xFF; val ob = op and 0xFF
            val br = (bp shr 16) and 0xFF; val bg = (bp shr 8) and 0xFF; val bb = bp and 0xFF
            val r = 255 - (255 - or_) * (255 - br) / 255
            val g = 255 - (255 - og) * (255 - bg) / 255
            val b = 255 - (255 - ob) * (255 - bb) / 255
            (srcA shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Resizes a flat [FloatArray] mask from [srcW]×[srcH] to [dstW]×[dstH] via bilinear interpolation.
     * Used to match U2Net mask dimensions to display-size blend targets.
     */
    fun resizeMask(mask: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray =
        bilinearUpscale(mask, srcW, srcH, dstW, dstH)

    private fun buildNgonKernel(sides: Int, radius: Int): FloatArray {
        val kSize = 2 * radius + 1
        val kernel = FloatArray(kSize * kSize)
        var sum = 0f
        val r = radius.toFloat()
        for (y in -radius..radius) {
            for (x in -radius..radius) {
                val inside = if (sides == 0) {
                    x * x + y * y <= radius * radius
                } else {
                    insideNgon(x.toFloat(), y.toFloat(), sides, r)
                }
                if (inside) {
                    kernel[(y + radius) * kSize + (x + radius)] = 1f
                    sum += 1f
                }
            }
        }
        if (sum > 0f) for (i in kernel.indices) kernel[i] /= sum
        return kernel
    }

    private fun insideNgon(px: Float, py: Float, sides: Int, radius: Float): Boolean {
        val dist = sqrt((px * px + py * py).toDouble()).toFloat()
        if (dist == 0f) return true
        val angle = atan2(py.toDouble(), px.toDouble())
        val sectorAngle = 2.0 * PI / sides
        val halfSector = sectorAngle / 2.0
        val normalizedAngle = ((angle % sectorAngle) + sectorAngle) % sectorAngle
        val localAngle = abs(normalizedAngle - halfSector)
        val apothem = (radius * cos(PI / sides)).toFloat()
        val maxDist = (apothem / cos(localAngle)).toFloat()
        return dist <= maxDist
    }

    private fun convolveChannel(
        channel: FloatArray,
        w: Int, h: Int,
        kernel: FloatArray,
        kSize: Int,
    ): FloatArray {
        val kRadius = kSize / 2
        val result = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (ky in 0 until kSize) {
                    val sy = (y + ky - kRadius).coerceIn(0, h - 1)
                    for (kx in 0 until kSize) {
                        val sx = (x + kx - kRadius).coerceIn(0, w - 1)
                        sum += channel[sy * w + sx] * kernel[ky * kSize + kx]
                    }
                }
                result[y * w + x] = sum
            }
        }
        return result
    }

    /**
     * Feathers the keep mask at 25% scale — two box-blur passes approximate a Gaussian,
     * creating a natural soft gradient at subject/edge boundaries.
     * [radius] controls the feather width at 25% scale (≈ radius×4 px at full scale).
     */
    private fun featherMask(keep: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val scale = 0.25f
        val sw = maxOf(2, (w * scale).toInt())
        val sh = maxOf(2, (h * scale).toInt())
        val small = FloatArray(sw * sh) { i ->
            val sx = ((i % sw) * (w - 1).toFloat() / (sw - 1)).toInt().coerceIn(0, w - 1)
            val sy = ((i / sw) * (h - 1).toFloat() / (sh - 1)).toInt().coerceIn(0, h - 1)
            keep[sy * w + sx]
        }
        // Two box-blur passes ≈ Gaussian with σ ≈ radius × 0.82
        val blurred = boxBlur(boxBlur(small, sw, sh, radius), sw, sh, radius)
        return bilinearUpscale(blurred, sw, sh, w, h)
    }

    /** O(W×H) sliding-window box blur (separable horizontal + vertical passes). */
    private fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val diameter = 2 * radius + 1
        val temp = FloatArray(src.size)
        // Horizontal pass
        for (y in 0 until height) {
            var sum = 0f
            for (dx in -radius..radius) sum += src[y * width + dx.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                temp[y * width + x] = sum / diameter
                val leave = x - radius;  if (leave >= 0)      sum -= src[y * width + leave]
                val enter = x + radius + 1; if (enter < width) sum += src[y * width + enter]
            }
        }
        // Vertical pass
        val result = FloatArray(src.size)
        for (x in 0 until width) {
            var sum = 0f
            for (dy in -radius..radius) sum += temp[dy.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                result[y * width + x] = sum / diameter
                val leave = y - radius;   if (leave >= 0)       sum -= temp[leave * width + x]
                val enter = y + radius + 1; if (enter < height) sum += temp[enter * width + x]
            }
        }
        return result
    }

    private fun bilinearUpscale(
        src: FloatArray,
        srcW: Int, srcH: Int,
        dstW: Int, dstH: Int,
    ): FloatArray {
        val result = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val sx = x * (srcW - 1).toFloat() / (dstW - 1)
                val sy = y * (srcH - 1).toFloat() / (dstH - 1)
                val x0 = sx.toInt().coerceIn(0, srcW - 2)
                val y0 = sy.toInt().coerceIn(0, srcH - 2)
                val fx = sx - x0
                val fy = sy - y0
                result[y * dstW + x] =
                    src[y0 * srcW + x0] * (1 - fx) * (1 - fy) +
                    src[y0 * srcW + x0 + 1] * fx * (1 - fy) +
                    src[(y0 + 1) * srcW + x0] * (1 - fx) * fy +
                    src[(y0 + 1) * srcW + x0 + 1] * fx * fy
            }
        }
        return result
    }

    // ── DoF Mode: Guided Filter ───────────────────────────────────────────────

    /**
     * Guided-filter DoF composite.
     *
     * Refines the U2Net [subjectMask] using the original image's luma as a guide, producing
     * an edge-preserving mask that hugs subject outlines without bleed or halo.
     * Works at 25 % scale for O(N) performance via box-blur based guided filter.
     *
     * Output: [original] subject composited over [blurred] background using the refined mask.
     */
    fun applyGuidedDof(
        original: Bitmap,
        blurred: Bitmap,
        subjectMask: FloatArray?,
        edgeBlur: Float,
    ): Bitmap {
        val w = original.width; val h = original.height
        if (subjectMask == null) return blurred.copy(Bitmap.Config.ARGB_8888, false)

        val sw = maxOf(2, (w * 0.25f).toInt()); val sh = maxOf(2, (h * 0.25f).toInt())

        val origSmall = Bitmap.createScaledBitmap(original, sw, sh, true)
        val origPxSmall = IntArray(sw * sh).also { origSmall.getPixels(it, 0, sw, 0, 0, sw, sh) }
        origSmall.recycle()

        val guide     = extractLuma(origPxSmall)
        val maskSmall = bilinearUpscale(subjectMask, w, h, sw, sh)
        val r         = (edgeBlur * (MAX_FEATHER_RADIUS - 1) + 1f).toInt().coerceAtLeast(1)
        val refined   = bilinearUpscale(guidedFilter(maskSmall, guide, sw, sh, r, 0.01f), sw, sh, w, h)

        val origPx = IntArray(w * h).also { original.getPixels(it, 0, w, 0, 0, w, h) }
        val blurPx = IntArray(blurred.width * blurred.height)
            .also { blurred.getPixels(it, 0, blurred.width, 0, 0, blurred.width, blurred.height) }
        return Bitmap.createBitmap(compositeWithMask(origPx, blurPx, refined, w, h), w, h, Bitmap.Config.ARGB_8888)
    }

    // ── DoF Mode: Bilateral Filter ────────────────────────────────────────────

    /**
     * Bilateral-filter DoF composite.
     *
     * Refines the mask using a joint bilateral filter guided by original luma.
     * Range weights suppress mask averaging across sharp colour boundaries.
     * Tabulated Gaussian weights for O(N·r²) with no transcendental calls in the inner loop.
     */
    fun applyBilateralDof(
        original: Bitmap,
        blurred: Bitmap,
        subjectMask: FloatArray?,
        edgeBlur: Float,
    ): Bitmap {
        val w = original.width; val h = original.height
        if (subjectMask == null) return blurred.copy(Bitmap.Config.ARGB_8888, false)

        val sw = maxOf(2, (w * 0.25f).toInt()); val sh = maxOf(2, (h * 0.25f).toInt())

        val origSmall = Bitmap.createScaledBitmap(original, sw, sh, true)
        val origPxSmall = IntArray(sw * sh).also { origSmall.getPixels(it, 0, sw, 0, 0, sw, sh) }
        origSmall.recycle()

        val guide     = extractLuma(origPxSmall)
        val maskSmall = bilinearUpscale(subjectMask, w, h, sw, sh)
        val r         = (edgeBlur * (MAX_FEATHER_RADIUS - 1) + 2f).toInt().coerceIn(2, MAX_FEATHER_RADIUS)
        val refined   = bilinearUpscale(bilateralFilterMask(maskSmall, guide, sw, sh, r, 0.15f), sw, sh, w, h)

        val origPx = IntArray(w * h).also { original.getPixels(it, 0, w, 0, 0, w, h) }
        val blurPx = IntArray(blurred.width * blurred.height)
            .also { blurred.getPixels(it, 0, blurred.width, 0, 0, blurred.width, blurred.height) }
        return Bitmap.createBitmap(compositeWithMask(origPx, blurPx, refined, w, h), w, h, Bitmap.Config.ARGB_8888)
    }

    // ── DoF Mode: Scatter CoC ─────────────────────────────────────────────────

    /**
     * Variable-radius CoC scatter DoF.
     *
     * Scatters highlights from [blurred] to aperture-shaped disks whose radii are proportional
     * to depth (1 − mask): background highlights produce large bokeh circles/N-gons that
     * shrink naturally as depth approaches the focus plane. Screen-blends the result back
     * over the base blur, then composites the sharp subject with a guided-filter mask.
     *
     * [bokehEdges] controls aperture shape (0 = circle, 4/6/8/… = polygon).
     */
    suspend fun applyScatterCocDof(
        original: Bitmap,
        blurred: Bitmap,
        subjectMask: FloatArray?,
        edgeBlur: Float,
        bokehEdges: Int,
    ): Bitmap = withContext(Dispatchers.Default) {
        val w = blurred.width; val h = blurred.height
        if (subjectMask == null) return@withContext blurred

        val sw = maxOf(2, (w * 0.25f).toInt()); val sh = maxOf(2, (h * 0.25f).toInt())

        val blurSmall = Bitmap.createScaledBitmap(blurred, sw, sh, true)
        val blurPxSmall = IntArray(sw * sh).also { blurSmall.getPixels(it, 0, sw, 0, 0, sw, sh) }
        blurSmall.recycle()

        val origSmall = Bitmap.createScaledBitmap(original, sw, sh, true)
        val origPxSmall = IntArray(sw * sh).also { origSmall.getPixels(it, 0, sw, 0, 0, sw, sh) }
        origSmall.recycle()

        val maskSmall = bilinearUpscale(subjectMask, w, h, sw, sh)
        val maxCoc    = (edgeBlur * 10f + 2f).toInt().coerceIn(2, MAX_FEATHER_RADIUS)

        // Precompute one N-gon/circle kernel per radius (1..maxCoc)
        val kernelCache = Array<FloatArray?>(maxCoc + 1) { r ->
            if (r < 1) null else buildNgonKernel(bokehEdges, r)
        }

        val accR = FloatArray(sw * sh)
        val accG = FloatArray(sw * sh)
        val accB = FloatArray(sw * sh)
        val accW = FloatArray(sw * sh)

        val threshold = 0.5f
        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                val si        = sy * sw + sx
                val depth     = (1f - maskSmall[si]).coerceIn(0f, 1f)
                val cocRadius = (depth * maxCoc).toInt()
                if (cocRadius < 1) continue

                val px = blurPxSmall[si]
                val pr = ((px shr 16) and 0xFF) / 255f
                val pg = ((px shr 8) and 0xFF) / 255f
                val pb = (px and 0xFF) / 255f
                val luma = 0.299f * pr + 0.587f * pg + 0.114f * pb
                val t    = ((luma - threshold) / (1f - threshold)).coerceIn(0f, 1f)
                if (t <= 0f) continue

                val kSize  = 2 * cocRadius + 1
                val kernel = kernelCache[cocRadius]
                for (dy in -cocRadius..cocRadius) {
                    val ty = (sy + dy).coerceIn(0, sh - 1)
                    for (dx in -cocRadius..cocRadius) {
                        val tx = (sx + dx).coerceIn(0, sw - 1)
                        val ti = ty * sw + tx
                        val inShape = if (kernel != null && bokehEdges > 0) {
                            kernel[(dy + cocRadius) * kSize + (dx + cocRadius)] > 0f
                        } else {
                            dx * dx + dy * dy <= cocRadius * cocRadius
                        }
                        if (inShape) {
                            accR[ti] += pr * t
                            accG[ti] += pg * t
                            accB[ti] += pb * t
                            accW[ti] += t
                        }
                    }
                }
            }
        }

        // Build bloom layer and screen-blend over blurred_small
        val screenSmallPx = IntArray(sw * sh) { i ->
            val tw = accW[i]
            val op = blurPxSmall[i]
            val or_ = (op shr 16) and 0xFF; val og = (op shr 8) and 0xFF; val ob = op and 0xFF
            if (tw > 0f) {
                val br = (accR[i] / tw * 255f).toInt().coerceIn(0, 255)
                val bg = (accG[i] / tw * 255f).toInt().coerceIn(0, 255)
                val bb = (accB[i] / tw * 255f).toInt().coerceIn(0, 255)
                val r2 = 255 - (255 - or_) * (255 - br) / 255
                val g2 = 255 - (255 - og) * (255 - bg) / 255
                val b2 = 255 - (255 - ob) * (255 - bb) / 255
                (0xFF shl 24) or (r2.coerceIn(0, 255) shl 16) or (g2.coerceIn(0, 255) shl 8) or b2.coerceIn(0, 255)
            } else {
                (0xFF shl 24) or (or_ shl 16) or (og shl 8) or ob
            }
        }
        val screenSmall = Bitmap.createBitmap(screenSmallPx, sw, sh, Bitmap.Config.ARGB_8888)
        val screenFull  = Bitmap.createScaledBitmap(screenSmall, w, h, true)
        screenSmall.recycle()

        // Guided-filter mask for subject composite (clean edges, no halo)
        val guide     = extractLuma(origPxSmall)
        val r2        = (edgeBlur * (MAX_FEATHER_RADIUS - 1) + 1f).toInt().coerceAtLeast(1)
        val refined   = bilinearUpscale(guidedFilter(maskSmall, guide, sw, sh, r2, 0.01f), sw, sh, w, h)

        val origPx   = IntArray(w * h).also { original.getPixels(it, 0, w, 0, 0, w, h) }
        val screenPx = IntArray(w * h).also { screenFull.getPixels(it, 0, w, 0, 0, w, h) }
        screenFull.recycle()

        Bitmap.createBitmap(compositeWithMask(origPx, screenPx, refined, w, h), w, h, Bitmap.Config.ARGB_8888)
    }

    // ── Private DoF helpers ───────────────────────────────────────────────────

    /** Extracts luma channel [0,1] from packed ARGB [pixels]. */
    private fun extractLuma(pixels: IntArray): FloatArray = FloatArray(pixels.size) { i ->
        val px = pixels[i]
        val r  = ((px shr 16) and 0xFF) / 255f
        val g  = ((px shr 8)  and 0xFF) / 255f
        val b  = ( px         and 0xFF) / 255f
        0.299f * r + 0.587f * g + 0.114f * b
    }

    /**
     * O(N) guided filter (He et al., 2013) on input [p] with guide [I].
     * Uses 6 separable box-blur passes → exactly O(N) regardless of radius.
     * [eps] regularises flat regions (0.01 works well for mask input in [0,1]).
     */
    private fun guidedFilter(p: FloatArray, I: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n   = w * h
        val I2  = FloatArray(n) { i -> I[i] * I[i] }
        val Ip  = FloatArray(n) { i -> I[i] * p[i] }

        val meanI  = boxBlur(I,  w, h, r)
        val meanP  = boxBlur(p,  w, h, r)
        val meanI2 = boxBlur(I2, w, h, r)
        val meanIp = boxBlur(Ip, w, h, r)

        val a = FloatArray(n) { i ->
            val varI  = (meanI2[i] - meanI[i] * meanI[i]).coerceAtLeast(0f)
            val covIp = meanIp[i] - meanI[i] * meanP[i]
            covIp / (varI + eps)
        }
        val b = FloatArray(n) { i -> meanP[i] - a[i] * meanI[i] }

        val meanA = boxBlur(a, w, h, r)
        val meanB = boxBlur(b, w, h, r)
        return FloatArray(n) { i -> (meanA[i] * I[i] + meanB[i]).coerceIn(0f, 1f) }
    }

    /**
     * Joint bilateral filter on [mask] guided by [guide] luma.
     * Range-weight table (256 buckets) eliminates exp() from the inner loop.
     * Spatial weights are precomputed once per call.
     * [sigmaR] = colour sensitivity (0.15 is a good default for portrait separation).
     */
    private fun bilateralFilterMask(
        mask: FloatArray,
        guide: FloatArray,
        w: Int, h: Int,
        r: Int,
        sigmaR: Float,
    ): FloatArray {
        val result = FloatArray(w * h)
        val kSize  = 2 * r + 1
        // Precompute spatial weights once
        val spatW  = FloatArray(kSize * kSize) { idx ->
            val dy = idx / kSize - r; val dx = idx % kSize - r
            exp(-((dx * dx + dy * dy).toDouble()) / (2.0 * r * r)).toFloat()
        }
        // Range-weight lookup table: 256 buckets for guide diff in [0, 1]
        val rangeW = FloatArray(256) { d ->
            val norm = d.toFloat() / 255f
            exp(-(norm * norm).toDouble() / (2.0 * sigmaR * sigmaR)).toFloat()
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i   = y * w + x
                val gi  = guide[i]
                var wSum = 0f; var mSum = 0f
                for (dy in -r..r) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val ky = (dy + r) * kSize
                    for (dx in -r..r) {
                        val nx  = (x + dx).coerceIn(0, w - 1)
                        val j   = ny * w + nx
                        val sw2 = spatW[ky + dx + r]
                        val gd  = ((guide[j] - gi).let { if (it < 0f) -it else it } * 255f).toInt().coerceIn(0, 255)
                        val wij = sw2 * rangeW[gd]
                        mSum += mask[j] * wij
                        wSum += wij
                    }
                }
                result[i] = if (wSum > 0f) (mSum / wSum).coerceIn(0f, 1f) else mask[i]
            }
        }
        return result
    }

    /** Alpha-composites [origPx] over [blurPx] using [keepMap] (1=keep orig, 0=use blur). */
    private fun compositeWithMask(
        origPx: IntArray,
        blurPx: IntArray,
        keepMap: FloatArray,
        w: Int, h: Int,
    ): IntArray = IntArray(w * h) { i ->
        val keep = keepMap[i].coerceIn(0f, 1f)
        val blur = 1f - keep
        val op   = origPx[i]
        val bp   = blurPx.getOrElse(i) { op }
        (0xFF shl 24) or
            (((op shr 16 and 0xFF) * keep + (bp shr 16 and 0xFF) * blur).toInt().coerceIn(0, 255) shl 16) or
            (((op shr 8  and 0xFF) * keep + (bp shr 8  and 0xFF) * blur).toInt().coerceIn(0, 255) shl 8 ) or
            ((op and 0xFF) * keep + (bp and 0xFF) * blur).toInt().coerceIn(0, 255)
    }
}
