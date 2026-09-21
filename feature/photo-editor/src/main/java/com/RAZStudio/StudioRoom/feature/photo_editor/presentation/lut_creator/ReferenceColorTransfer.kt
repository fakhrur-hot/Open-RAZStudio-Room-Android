/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unpaired / reference-conditioned 3D LUT builders for LUT Creator.
 *
 * Design constraints (deep-analog lessons — do NOT violate):
 *   • Photometric look lives in the .cube ONLY. Grain / halation never bake
 *     into the lattice (LUTs are pixel-independent; optical stays a sidecar).
 *   • No multi-LUT neural bank / learned blend — one residual cube per look.
 *   • No StyleLUTNet weights; classical residual lattice + CDF + film-tone.
 *
 * Modes:
 *   • [fitMatch] — source + reference (Lab colour + CDF tone + film-tone).
 *   • [fitFromReference] — reference alone → residual-from-identity look LUT
 *     that ports to other photos (closer to StyleLUTNet's product shape).
 *
 * Craft: fit residual at 17³, upsample to 33³; strength sliders; triangular
 * dither when evaluating the lattice to reduce banding.
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.lut_creator

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object ReferenceColorTransfer {

    private const val WORK_LONG_SIDE = 384
    private const val LOW_SIZE = 17
    private const val OUT_SIZE = 33
    private const val HIST_BINS = 256

    data class Strengths(
        val color: Float = 0.85f,
        val tone: Float = 0.70f,
        val filmTone: Float = 0.70f,
        val grain: Float = 0.50f,
        val includeOptical: Boolean = true,
    )

    data class Result(
        val lut: PairLutFitter.FittedLut,
        val optical: LutOpticalSidecar.Params,
    )

    /**
     * Source → reference look (unpaired). Colour / tone / film-tone strengths
     * blend each stage toward identity so the user can dial intensity.
     */
    fun fitMatch(
        source: Bitmap,
        reference: Bitmap,
        strengths: Strengths = Strengths(),
    ): Result {
        val src = scaleDown(source, WORK_LONG_SIDE)
        val ref = scaleDown(reference, WORK_LONG_SIDE)
        val srcStats = gatherLabStats(src)
        val refStats = gatherLabStats(ref)
        val refTone = analyzeFilmTone(ref)
        val cdf = buildCdfMaps(src, ref)
        val lut = buildResidualCube(
            lowSize = LOW_SIZE,
            outSize = OUT_SIZE,
        ) { r, g, b ->
            var rgb = floatArrayOf(r, g, b)
            rgb = applyLabTransfer(rgb, srcStats, refStats, strengths.color)
            rgb = applyCdfTone(rgb, cdf, strengths.tone)
            rgb = applyFilmTonePixel(rgb, refTone, strengths.filmTone)
            rgb
        }
        return Result(lut, estimateOptical(ref, refTone, strengths))
    }

    /**
     * Reference-only look LUT: residual from identity driven by the reference's
     * tonal / cast signature. No source stats → reusable library LUT.
     */
    fun fitFromReference(
        reference: Bitmap,
        strengths: Strengths = Strengths(),
    ): Result {
        val ref = scaleDown(reference, WORK_LONG_SIDE)
        val refStats = gatherLabStats(ref)
        val refTone = analyzeFilmTone(ref)
        // Neutral digital baseline the residual pushes away from.
        val neutral = LabStats(
            mean = floatArrayOf(50f, 0f, 0f),
            std = floatArrayOf(20f, 12f, 12f),
        )
        val lut = buildResidualCube(
            lowSize = LOW_SIZE,
            outSize = OUT_SIZE,
        ) { r, g, b ->
            var rgb = floatArrayOf(r, g, b)
            // Mild colour pull toward the reference's Lab distribution.
            rgb = applyLabTransfer(rgb, neutral, refStats, strengths.color * 0.65f)
            // Film-tone signature is the main open-set look carrier.
            rgb = applyFilmTonePixel(rgb, refTone, strengths.filmTone)
            // Soft contrast matching via reference black/white as a 1D curve.
            rgb = applyBlackWhiteCurve(rgb, refTone, strengths.tone)
            rgb
        }
        return Result(lut, estimateOptical(ref, refTone, strengths))
    }

    // ── Residual 17³ → 33³ cube ───────────────────────────────────────────────

    private fun buildResidualCube(
        lowSize: Int,
        outSize: Int,
        map: (Float, Float, Float) -> FloatArray,
    ): PairLutFitter.FittedLut {
        val low = FloatArray(lowSize * lowSize * lowSize * 3)
        val fmax = (lowSize - 1).toFloat()
        var seed = 0xC0FFEE
        for (bi in 0 until lowSize) {
            for (gi in 0 until lowSize) {
                for (ri in 0 until lowSize) {
                    val r = ri / fmax
                    val g = gi / fmax
                    val b = bi / fmax
                    val out = map(r, g, b)
                    // Triangular dither (~0.4/255) suppresses lattice banding.
                    seed = ditherSeed(seed)
                    val d = ((seed and 0xFFFF) / 65535f + ((seed ushr 16) and 0xFFFF) / 65535f - 1f) *
                        (0.4f / 255f)
                    val idx = ((bi * lowSize + gi) * lowSize + ri) * 3
                    // Residual from identity — keeps under-sampled colours near passthrough.
                    low[idx] = (out[0] + d - r).coerceIn(-1f, 1f)
                    low[idx + 1] = (out[1] + d - g).coerceIn(-1f, 1f)
                    low[idx + 2] = (out[2] + d - b).coerceIn(-1f, 1f)
                }
            }
        }
        val hi = upsampleResidual3d(low, lowSize, outSize)
        // Identity + residual → final lattice.
        val grid = FloatArray(outSize * outSize * outSize * 3)
        val hmax = (outSize - 1).toFloat()
        for (bi in 0 until outSize) {
            for (gi in 0 until outSize) {
                for (ri in 0 until outSize) {
                    val idx = ((bi * outSize + gi) * outSize + ri) * 3
                    grid[idx] = (ri / hmax + hi[idx]).coerceIn(0f, 1f)
                    grid[idx + 1] = (gi / hmax + hi[idx + 1]).coerceIn(0f, 1f)
                    grid[idx + 2] = (bi / hmax + hi[idx + 2]).coerceIn(0f, 1f)
                }
            }
        }
        return PairLutFitter.FittedLut(grid, outSize)
    }

    private fun upsampleResidual3d(src: FloatArray, srcN: Int, dstN: Int): FloatArray {
        val dst = FloatArray(dstN * dstN * dstN * 3)
        val smax = (srcN - 1).toFloat()
        val dmax = (dstN - 1).toFloat()
        for (bi in 0 until dstN) {
            for (gi in 0 until dstN) {
                for (ri in 0 until dstN) {
                    val rf = ri / dmax * smax
                    val gf = gi / dmax * smax
                    val bf = bi / dmax * smax
                    val r0 = rf.toInt().coerceIn(0, srcN - 1)
                    val g0 = gf.toInt().coerceIn(0, srcN - 1)
                    val b0 = bf.toInt().coerceIn(0, srcN - 1)
                    val r1 = min(r0 + 1, srcN - 1)
                    val g1 = min(g0 + 1, srcN - 1)
                    val b1 = min(b0 + 1, srcN - 1)
                    val fr = rf - r0
                    val fg = gf - g0
                    val fb = bf - b0
                    var or = 0f
                    var og = 0f
                    var ob = 0f
                    for (db in 0..1) {
                        val bb = if (db == 0) b0 else b1
                        val wb = if (db == 0) 1f - fb else fb
                        for (dg in 0..1) {
                            val gg = if (dg == 0) g0 else g1
                            val wg = if (dg == 0) 1f - fg else fg
                            for (dr in 0..1) {
                                val rr = if (dr == 0) r0 else r1
                                val wr = if (dr == 0) 1f - fr else fr
                                val w = wb * wg * wr
                                val i = ((bb * srcN + gg) * srcN + rr) * 3
                                or += src[i] * w
                                og += src[i + 1] * w
                                ob += src[i + 2] * w
                            }
                        }
                    }
                    val o = ((bi * dstN + gi) * dstN + ri) * 3
                    dst[o] = or
                    dst[o + 1] = og
                    dst[o + 2] = ob
                }
            }
        }
        return dst
    }

    private fun ditherSeed(s: Int): Int {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return x
    }

    // ── Lab colour transfer ───────────────────────────────────────────────────

    private fun applyLabTransfer(
        rgb: FloatArray,
        src: LabStats,
        ref: LabStats,
        strength: Float,
    ): FloatArray {
        if (strength <= 1e-4f) return rgb
        val lab = rgbToLab(rgb[0], rgb[1], rgb[2])
        val scaleL = (ref.std[0] / src.std[0].coerceAtLeast(1e-3f)).coerceIn(0.25f, 4f)
        val scaleA = (ref.std[1] / src.std[1].coerceAtLeast(1e-3f)).coerceIn(0.25f, 4f)
        val scaleB = (ref.std[2] / src.std[2].coerceAtLeast(1e-3f)).coerceIn(0.25f, 4f)
        val l2 = (lab[0] - src.mean[0]) * scaleL + ref.mean[0]
        val a2 = (lab[1] - src.mean[1]) * scaleA + ref.mean[1]
        val b2 = (lab[2] - src.mean[2]) * scaleB + ref.mean[2]
        val mapped = labToRgb(l2, a2, b2)
        val t = strength.coerceIn(0f, 1f)
        return floatArrayOf(
            (rgb[0] + (mapped[0] - rgb[0]) * t).coerceIn(0f, 1f),
            (rgb[1] + (mapped[1] - rgb[1]) * t).coerceIn(0f, 1f),
            (rgb[2] + (mapped[2] - rgb[2]) * t).coerceIn(0f, 1f),
        )
    }

    // ── CDF tone matching ─────────────────────────────────────────────────────

    private class CdfMaps(val mapR: FloatArray, val mapG: FloatArray, val mapB: FloatArray)

    private fun buildCdfMaps(src: Bitmap, ref: Bitmap): CdfMaps {
        val sh = histogram(src)
        val rh = histogram(ref)
        return CdfMaps(
            matchChannel(sh[0], rh[0]),
            matchChannel(sh[1], rh[1]),
            matchChannel(sh[2], rh[2]),
        )
    }

    private fun applyCdfTone(rgb: FloatArray, cdf: CdfMaps, strength: Float): FloatArray {
        if (strength <= 1e-4f) return rgb
        fun map(v: Float, m: FloatArray): Float {
            val x = (v.coerceIn(0f, 1f) * 255f)
            val i0 = x.toInt().coerceIn(0, 255)
            val i1 = min(i0 + 1, 255)
            val f = x - i0
            return m[i0] * (1f - f) + m[i1] * f
        }
        val t = strength.coerceIn(0f, 1f)
        val mr = map(rgb[0], cdf.mapR)
        val mg = map(rgb[1], cdf.mapG)
        val mb = map(rgb[2], cdf.mapB)
        return floatArrayOf(
            (rgb[0] + (mr - rgb[0]) * t).coerceIn(0f, 1f),
            (rgb[1] + (mg - rgb[1]) * t).coerceIn(0f, 1f),
            (rgb[2] + (mb - rgb[2]) * t).coerceIn(0f, 1f),
        )
    }

    private fun histogram(bmp: Bitmap): Array<LongArray> {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = Array(3) { LongArray(HIST_BINS) }
        for (p in px) {
            hist[0][(p ushr 16) and 0xFF]++
            hist[1][(p ushr 8) and 0xFF]++
            hist[2][p and 0xFF]++
        }
        return hist
    }

    private fun matchChannel(srcH: LongArray, refH: LongArray): FloatArray {
        val srcCdf = cdf(srcH)
        val refCdf = cdf(refH)
        val raw = IntArray(HIST_BINS)
        var j = 0
        for (i in 0 until HIST_BINS) {
            val target = srcCdf[i]
            while (j < 255 && refCdf[j] < target) j++
            raw[i] = j
        }
        val smooth = smoothMonotonic(raw)
        // Light Gaussian-ish 5-tap blur then re-enforce monotonicity.
        val blurred = FloatArray(HIST_BINS)
        for (i in 0 until HIST_BINS) {
            var sum = 0f
            var w = 0f
            for (k in -2..2) {
                val idx = i + k
                if (idx in 0 until HIST_BINS) {
                    val ww = when (kotlin.math.abs(k)) { 0 -> 0.4f; 1 -> 0.2f; else -> 0.1f }
                    sum += smooth[idx] * ww
                    w += ww
                }
            }
            blurred[i] = sum / w / 255f
        }
        for (i in 1 until HIST_BINS) if (blurred[i] < blurred[i - 1]) blurred[i] = blurred[i - 1]
        return blurred
    }

    private fun cdf(h: LongArray): DoubleArray {
        var total = 0L
        for (v in h) total += v
        val out = DoubleArray(HIST_BINS)
        if (total == 0L) {
            for (i in 0 until HIST_BINS) out[i] = i / 255.0
            return out
        }
        var acc = 0L
        for (i in 0 until HIST_BINS) {
            acc += h[i]
            out[i] = acc.toDouble() / total
        }
        return out
    }

    private fun smoothMonotonic(map: IntArray): IntArray {
        val r = 2
        val out = IntArray(HIST_BINS)
        for (i in 0 until HIST_BINS) {
            var sum = 0
            var n = 0
            for (k in -r..r) {
                val idx = i + k
                if (idx in 0..255) {
                    sum += map[idx]
                    n++
                }
            }
            out[i] = (sum.toDouble() / n + 0.5).toInt().coerceIn(0, 255)
        }
        for (i in 1 until HIST_BINS) if (out[i] < out[i - 1]) out[i] = out[i - 1]
        return out
    }

    // ── Film-tone analytics (classical; not baked as grain) ───────────────────

    private class FilmTone(
        val blackPoint: Float,
        val whitePoint: Float,
        val contrastRange: Float,
        val shadowColor: FloatArray,
        val highlightColor: FloatArray,
    )

    private fun analyzeFilmTone(bmp: Bitmap): FilmTone {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val luma = FloatArray(px.size)
        val rgb = Array(3) { FloatArray(px.size) }
        for (i in px.indices) {
            val c = px[i]
            val r = ((c ushr 16) and 0xFF) / 255f
            val g = ((c ushr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            rgb[0][i] = r
            rgb[1][i] = g
            rgb[2][i] = b
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        val sorted = luma.copyOf().also { it.sort() }
        fun q(p: Float): Float {
            val i = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[i]
        }
        val black = q(0.02f)
        val white = q(0.98f)
        val shadowCut = q(0.15f)
        val highlightCut = q(0.85f)
        var sR = 0.0
        var sG = 0.0
        var sB = 0.0
        var sN = 0
        var hR = 0.0
        var hG = 0.0
        var hB = 0.0
        var hN = 0
        for (i in luma.indices) {
            if (luma[i] < shadowCut) {
                sR += rgb[0][i]
                sG += rgb[1][i]
                sB += rgb[2][i]
                sN++
            }
            if (luma[i] > highlightCut) {
                hR += rgb[0][i]
                hG += rgb[1][i]
                hB += rgb[2][i]
                hN++
            }
        }
        val shadow = if (sN > 0) floatArrayOf(
            (sR / sN).toFloat(),
            (sG / sN).toFloat(),
            (sB / sN).toFloat(),
        ) else floatArrayOf(0f, 0f, 0f)
        val highlight = if (hN > 0) floatArrayOf(
            (hR / hN).toFloat(),
            (hG / hN).toFloat(),
            (hB / hN).toFloat(),
        ) else floatArrayOf(1f, 1f, 1f)
        return FilmTone(
            blackPoint = black,
            whitePoint = white,
            contrastRange = (white - black).coerceAtLeast(0.05f),
            shadowColor = shadow,
            highlightColor = highlight,
        )
    }

    private fun applyFilmTonePixel(rgb: FloatArray, tone: FilmTone, strength: Float): FloatArray {
        if (strength <= 1e-4f) return rgb
        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]
        val s = strength.coerceIn(0f, 1f)

        // Black-point lift — film rarely clips to pure zero.
        if (tone.blackPoint > 0.02f) {
            val lift = tone.blackPoint * s * 0.8f
            r = r * (1f - lift) + lift
            g = g * (1f - lift) + lift
            b = b * (1f - lift) + lift
        }

        // Soft highlight shoulder above knee=0.6.
        if (tone.whitePoint < 0.95f) {
            val compression = 1f - (1f - tone.whitePoint) * s
            val knee = 0.6f
            fun rolloff(v: Float): Float {
                val t = ((v - knee) / (1f - knee)).coerceIn(0f, 1f)
                val amount = t * t * (3f - 2f * t)
                return v * (1f - amount * (1f - compression) * 0.6f)
            }
            r = rolloff(r)
            g = rolloff(g)
            b = rolloff(b)
        }

        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val shadowW = (1f - lum).coerceIn(0f, 1f).let { it * it }
        val shadowMean = (tone.shadowColor[0] + tone.shadowColor[1] + tone.shadowColor[2]) / 3f
        r += (tone.shadowColor[0] - shadowMean) * shadowW * s * 0.5f
        g += (tone.shadowColor[1] - shadowMean) * shadowW * s * 0.5f
        b += (tone.shadowColor[2] - shadowMean) * shadowW * s * 0.5f

        val hiW = lum.coerceIn(0f, 1f).let { it * it }
        val hiMean = (tone.highlightColor[0] + tone.highlightColor[1] + tone.highlightColor[2]) / 3f
        r += (tone.highlightColor[0] - hiMean) * hiW * s * 0.3f
        g += (tone.highlightColor[1] - hiMean) * hiW * s * 0.3f
        b += (tone.highlightColor[2] - hiMean) * hiW * s * 0.3f

        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** Reference-only tone: soft levels toward measured black/white. */
    private fun applyBlackWhiteCurve(rgb: FloatArray, tone: FilmTone, strength: Float): FloatArray {
        if (strength <= 1e-4f) return rgb
        val s = strength.coerceIn(0f, 1f) * 0.55f
        val black = tone.blackPoint
        val white = tone.whitePoint.coerceAtLeast(black + 0.05f)
        fun curve(v: Float): Float {
            val x = ((v - black) / (white - black)).coerceIn(0f, 1f)
            // Gentle S toward film contrast without crushing midtones.
            val y = x * x * (3f - 2f * x)
            return v + (y - v) * s
        }
        return floatArrayOf(
            curve(rgb[0]).coerceIn(0f, 1f),
            curve(rgb[1]).coerceIn(0f, 1f),
            curve(rgb[2]).coerceIn(0f, 1f),
        )
    }

    // ── Optical sidecar (NOT in the cube) ─────────────────────────────────────

    private fun estimateOptical(
        ref: Bitmap,
        tone: FilmTone,
        strengths: Strengths,
    ): LutOpticalSidecar.Params {
        if (!strengths.includeOptical || strengths.grain <= 1e-4f) {
            return LutOpticalSidecar.Params()
        }
        val g = strengths.grain.coerceIn(0f, 1f)
        // Defaults inspired by typical 35mm colour-neg params (deep-analog
        // DEFAULT_FILM_PARAMS), scaled by the grain slider + reference contrast.
        val contrastBoost = (1.2f - tone.contrastRange).coerceIn(0.6f, 1.4f)
        val filmGrain = (0.28f * g * contrastBoost).coerceIn(0f, 1f)
        val filmGrainSize = (0.40f + 0.35f * g).coerceIn(0f, 1f)
        val wash = (0.08f * g).coerceIn(0f, 1f)
        // Halation → existing Glamour Glow slots (UI units).
        val glow = (18f * g * (if (tone.whitePoint > 0.85f) 1.25f else 1f)).coerceIn(0f, 60f)
        val spread = (22f * g).coerceIn(0f, 80f)
        val warmth = ((tone.highlightColor[0] - tone.highlightColor[2]) * 40f * g)
            .coerceIn(-40f, 40f)
        return LutOpticalSidecar.Params(
            filmGrain = filmGrain,
            filmGrainSize = filmGrainSize,
            filmGrainWashOut = wash,
            fxGlowStrength = glow,
            fxGlowSpread = spread,
            fxGlowWarmth = warmth,
        )
    }

    // ── Lab helpers ───────────────────────────────────────────────────────────

    private class LabStats(val mean: FloatArray, val std: FloatArray)

    private fun gatherLabStats(bmp: Bitmap): LabStats {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sumL = 0.0
        var sumA = 0.0
        var sumB = 0.0
        var sumL2 = 0.0
        var sumA2 = 0.0
        var sumB2 = 0.0
        val n = px.size.toDouble().coerceAtLeast(1.0)
        for (c in px) {
            val r = ((c ushr 16) and 0xFF) / 255f
            val g = ((c ushr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val lab = rgbToLab(r, g, b)
            sumL += lab[0]
            sumA += lab[1]
            sumB += lab[2]
            sumL2 += lab[0] * lab[0]
            sumA2 += lab[1] * lab[1]
            sumB2 += lab[2] * lab[2]
        }
        val meanL = (sumL / n).toFloat()
        val meanA = (sumA / n).toFloat()
        val meanB = (sumB / n).toFloat()
        val stdL = sqrt(((sumL2 / n) - meanL * meanL).coerceAtLeast(0.0)).toFloat().coerceAtLeast(1e-3f)
        val stdA = sqrt(((sumA2 / n) - meanA * meanA).coerceAtLeast(0.0)).toFloat().coerceAtLeast(1e-3f)
        val stdB = sqrt(((sumB2 / n) - meanB * meanB).coerceAtLeast(0.0)).toFloat().coerceAtLeast(1e-3f)
        return LabStats(floatArrayOf(meanL, meanA, meanB), floatArrayOf(stdL, stdA, stdB))
    }

    private fun rgbToLab(r: Float, g: Float, b: Float): FloatArray {
        fun lin(c: Float): Float {
            val x = c.coerceIn(0f, 1f)
            return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
        }
        val rl = lin(r)
        val gl = lin(g)
        val bl = lin(b)
        val x = rl * 0.4124564f + gl * 0.3575761f + bl * 0.1804375f
        val y = rl * 0.2126729f + gl * 0.7151522f + bl * 0.0721750f
        val z = rl * 0.0193339f + gl * 0.1191920f + bl * 0.9503041f
        fun f(t: Float): Float {
            val d = 6f / 29f
            return if (t > d * d * d) t.pow(1f / 3f) else t / (3f * d * d) + 4f / 29f
        }
        val fx = f(x / 0.95047f)
        val fy = f(y / 1.00000f)
        val fz = f(z / 1.08883f)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    private fun labToRgb(l: Float, a: Float, b: Float): FloatArray {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        fun invF(t: Float): Float {
            val d = 6f / 29f
            return if (t > d) t * t * t else 3f * d * d * (t - 4f / 29f)
        }
        val x = 0.95047f * invF(fx)
        val y = 1.00000f * invF(fy)
        val z = 1.08883f * invF(fz)
        val rl = x * 3.2404542f + y * -1.5371385f + z * -0.4985314f
        val gl = x * -0.9692660f + y * 1.8760108f + z * 0.0415560f
        val bl = x * 0.0556434f + y * -0.2040259f + z * 1.0572252f
        fun gamma(c: Float): Float {
            val v = c.coerceAtLeast(0f)
            return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
        }
        return floatArrayOf(gamma(rl), gamma(gl), gamma(bl))
    }

    private fun scaleDown(src: Bitmap, maxLongSide: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        val argb = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        if (long <= maxLongSide) return argb
        val s = maxLongSide.toFloat() / long
        return Bitmap.createScaledBitmap(
            argb,
            (argb.width * s).toInt().coerceAtLeast(1),
            (argb.height * s).toInt().coerceAtLeast(1),
            true,
        )
    }
}
