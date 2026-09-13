/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Fit a 3D LUT's approximable colour/tone remap into StudioRoom slider units.
 *
 * LUTs are RGB→RGB tables. They can approximate temperature/tint (display),
 * contrast, highlights/shadows tonality, and saturation — not true RAW Kelvin
 * sensor WB, sharpness, bloom/blur, or spatial effects. Those stay procedural.
 *
 * Forward kernels here mirror apply_macro.cpp / shader_sources.cpp for the
 * subset we fit, so fitted values land on the same numeric domains as
 * RawV3ActionReplay (UI → /100, WB Kelvin delta → /2500, tint → /200).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmCurve
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

object LutAdjustmentApprox {

    /**
     * Fitted approximable adjustments in [UserMacro] UI units, plus fit quality.
     * [whiteBalanceKelvinDelta] is the RAW macro field (Int Kelvin delta from
     * as-shot / D65 pivot). JPEG UI maps it to a relative slider elsewhere.
     */
    data class Result(
        val whiteBalanceKelvinDelta: Int = 0,
        val tint: Float = 0f,           // [-200..200]
        val contrast: Float = 0f,       // [-100..100]
        val highlights: Float = 0f,     // [-100..100]
        val shadows: Float = 0f,        // [-100..100]
        val saturation: Float = 0f,     // [-100..100]
        val filmRolloff: Float = 0f,    // [0..1] soft HL shoulder proxy
        val filmCurve: FilmCurve = FilmCurve(),
        /** RMS RGB error of LUT vs forward(sliders) on the sample lattice. */
        val residualRms: Float = 0f,
        /** 0..1 how much of the LUT delta the sliders explained. */
        val explained: Float = 0f,
    ) {
        fun applyTo(macro: UserMacro, reduceLutIntensity: Boolean): UserMacro {
            val intensity = if (reduceLutIntensity) {
                (macro.lutIntensity * (1f - explained * 0.85f)).coerceIn(0f, 1f)
            } else macro.lutIntensity
            return macro.copy(
                whiteBalance = whiteBalanceKelvinDelta,
                tint = tint,
                contrast = contrast,
                highlights = highlights,
                shadows = shadows,
                saturation = saturation,
                filmRolloff = if (filmRolloff > macro.filmRolloff) filmRolloff else macro.filmRolloff,
                filmCurve = if (filmCurve.isActive && !macro.filmCurve.isActive) filmCurve
                            else macro.filmCurve,
                lutIntensity = intensity,
            )
        }
    }

    private data class Sample(val r: Float, val g: Float, val b: Float)
    private data class PairSample(val inp: Sample, val out: Sample)

    /** Analyze [cube] at [intensity] mix toward identity. */
    fun analyze(
        cube: RawV3LutStore.ParsedCube,
        intensity: Float = 1f,
    ): Result {
        val pairs = buildSamples(cube, intensity.coerceIn(0f, 1f))
        if (pairs.isEmpty()) return Result()

        val lutDelta = meanAbsDelta(pairs)
        if (lutDelta < 1e-4f) return Result(residualRms = 0f, explained = 1f)

        // Shader-normalized params we optimize, then convert to UI units.
        var wb = 0f      // [-1,1] → Kelvin delta * 2500
        var tintN = 0f   // [-1,1] → tint * 200
        var contrastN = 0f
        var hiN = 0f
        var shN = 0f
        var satN = 0f

        // Seed from direct measurements (fast, stable).
        seedFromMeasurements(pairs)?.let { s ->
            wb = s[0]; tintN = s[1]; contrastN = s[2]
            hiN = s[3]; shN = s[4]; satN = s[5]
        }

        // Coordinate descent against StudioRoom forward kernels.
        val order = intArrayOf(0, 1, 2, 3, 4, 5) // wb, tint, contrast, hi, sh, sat
        repeat(10) {
            for (pi in order) {
                val best = lineSearch(pairs, wb, tintN, contrastN, hiN, shN, satN, pi)
                when (pi) {
                    0 -> wb = best
                    1 -> tintN = best
                    2 -> contrastN = best
                    3 -> hiN = best
                    4 -> shN = best
                    5 -> satN = best
                }
            }
        }

        val rmsFit = rmsError(pairs, wb, tintN, contrastN, hiN, shN, satN)
        val rmsZero = rmsError(pairs, 0f, 0f, 0f, 0f, 0f, 0f)
        val explained = if (rmsZero < 1e-6f) 1f
            else ((1f - rmsFit / rmsZero).coerceIn(0f, 1f))

        // Soft HL roll-off proxy from neutral shoulder (not a full filmRolloff fit).
        val rolloff = estimateFilmRolloff(pairs)
        val filmCurve = estimateFilmCurve(pairs)

        return Result(
            whiteBalanceKelvinDelta = (wb * 2500f).toInt().coerceIn(-2500, 2500),
            tint = (tintN * 200f).coerceIn(-200f, 200f),
            contrast = (contrastN * 100f).coerceIn(-100f, 100f),
            highlights = (hiN * 100f).coerceIn(-100f, 100f),
            shadows = (shN * 100f).coerceIn(-100f, 100f),
            saturation = (satN * 100f).coerceIn(-100f, 100f),
            filmRolloff = rolloff,
            filmCurve = filmCurve,
            residualRms = rmsFit,
            explained = explained,
        )
    }

    fun analyzeFile(file: java.io.File, intensity: Float = 1f): Result? {
        val cube = RawV3LutStore.parseCubeFile(file) ?: return null
        return analyze(cube, intensity)
    }

    // ── Sampling ──────────────────────────────────────────────────────────

    private fun buildSamples(cube: RawV3LutStore.ParsedCube, intensity: Float): List<PairSample> {
        val out = ArrayList<PairSample>(220)
        fun add(r: Float, g: Float, b: Float) {
            val rr = r.coerceIn(0f, 1f); val gg = g.coerceIn(0f, 1f); val bb = b.coerceIn(0f, 1f)
            val or = RawV3LutStore.trilinearSample(cube, rr, gg, bb, 0)
            val og = RawV3LutStore.trilinearSample(cube, rr, gg, bb, 1)
            val ob = RawV3LutStore.trilinearSample(cube, rr, gg, bb, 2)
            val outR = rr + (or - rr) * intensity
            val outG = gg + (og - gg) * intensity
            val outB = bb + (ob - bb) * intensity
            out.add(PairSample(Sample(rr, gg, bb), Sample(outR, outG, outB)))
        }
        // Neutral ramp (tone / contrast / H-S).
        for (i in 0..32) {
            val t = i / 32f
            add(t, t, t)
        }
        // Lattice stride for colour coverage.
        val steps = 5
        for (bi in 0..steps) for (gi in 0..steps) for (ri in 0..steps) {
            add(ri / steps.toFloat(), gi / steps.toFloat(), bi / steps.toFloat())
        }
        // Warm/cool + green/magenta probes around mid-gray.
        val mid = 0.5f
        for (d in floatArrayOf(-0.12f, -0.06f, 0.06f, 0.12f)) {
            add((mid + d).coerceIn(0f, 1f), mid, (mid - d).coerceIn(0f, 1f))
            add(mid, (mid + d).coerceIn(0f, 1f), mid)
        }
        // Saturated primaries / secondaries (saturation fit).
        add(0.85f, 0.15f, 0.15f); add(0.15f, 0.85f, 0.15f); add(0.15f, 0.15f, 0.85f)
        add(0.85f, 0.85f, 0.15f); add(0.15f, 0.85f, 0.85f); add(0.85f, 0.15f, 0.85f)
        return out
    }

    private fun meanAbsDelta(pairs: List<PairSample>): Float {
        var s = 0f
        for (p in pairs) {
            s += kotlin.math.abs(p.out.r - p.inp.r)
            s += kotlin.math.abs(p.out.g - p.inp.g)
            s += kotlin.math.abs(p.out.b - p.inp.b)
        }
        return s / (pairs.size * 3f)
    }

    // ── Seed ──────────────────────────────────────────────────────────────

    private fun seedFromMeasurements(pairs: List<PairSample>): FloatArray? {
        val neutrals = pairs.filter {
            kotlin.math.abs(it.inp.r - it.inp.g) < 1e-4f &&
                kotlin.math.abs(it.inp.g - it.inp.b) < 1e-4f
        }
        if (neutrals.size < 8) return null

        // Temperature: mean (R−B) of mid neutrals after LUT.
        val midN = neutrals.filter { it.inp.r in 0.25f..0.75f }
        var rb = 0f
        for (p in midN) rb += (p.out.r - p.out.b)
        rb /= midN.size.coerceAtLeast(1)
        // Empirical: wb≈±1 ⇒ roughly ±0.08 R−B on mid gray under our Kelvin model.
        val wb = (rb / 0.08f).coerceIn(-1f, 1f)

        // Tint: green excess on mid neutrals.
        var gex = 0f
        for (p in midN) {
            val avg = (p.out.r + p.out.b) * 0.5f
            gex += p.out.g - avg
        }
        gex /= midN.size.coerceAtLeast(1)
        val tintN = (gex / 0.06f).coerceIn(-1f, 1f)

        fun luma(s: Sample) = s.r * 0.2627f + s.g * 0.6780f + s.b * 0.0593f
        val shadowIn = neutrals.filter { it.inp.r < 0.25f }
        val midIn = neutrals.filter { it.inp.r in 0.35f..0.55f }
        val hiIn = neutrals.filter { it.inp.r > 0.70f }
        fun meanGain(list: List<PairSample>): Float {
            if (list.isEmpty()) return 1f
            var g = 0f
            for (p in list) {
                val li = luma(p.inp).coerceAtLeast(1e-4f)
                g += luma(p.out) / li
            }
            return g / list.size
        }
        val gSh = meanGain(shadowIn)
        val gMid = meanGain(midIn)
        val gHi = meanGain(hiIn)
        // Contrast around mid: (gHi - gSh) relative to mid.
        val contrastN = (((gHi - gSh) * 0.5f) / 0.35f).coerceIn(-1f, 1f)
        val hiN = ((gHi - 1f) / 0.35f).coerceIn(-1f, 1f)
        val shN = ((gSh - 1f) / 0.55f).coerceIn(-1f, 1f)

        // Saturation: chroma ratio on vivid probes.
        val vivid = pairs.filter {
            val maxC = maxOf(it.inp.r, it.inp.g, it.inp.b)
            val minC = minOf(it.inp.r, it.inp.g, it.inp.b)
            maxC - minC > 0.35f && maxC > 0.4f
        }
        var satAcc = 0f
        var nSat = 0
        for (p in vivid) {
            fun chroma(s: Sample): Float {
                val L = luma(s)
                val dr = s.r - L; val dg = s.g - L; val db = s.b - L
                return sqrt(dr * dr + dg * dg + db * db).coerceAtLeast(1e-5f)
            }
            satAcc += chroma(p.out) / chroma(p.inp)
            nSat++
        }
        val satRatio = if (nSat > 0) satAcc / nSat else 1f
        val satN = (satRatio - 1f).coerceIn(-1f, 1f)

        // Ignore unused gMid warning by using it lightly to damp contrast seed.
        val cAdj = contrastN * (0.85f + 0.15f * gMid.coerceIn(0.5f, 1.5f))
        return floatArrayOf(wb, tintN, cAdj.coerceIn(-1f, 1f), hiN, shN, satN)
    }

    private fun estimateFilmRolloff(pairs: List<PairSample>): Float {
        val hi = pairs.filter {
            kotlin.math.abs(it.inp.r - it.inp.g) < 1e-4f &&
                it.inp.r > 0.85f
        }
        if (hi.isEmpty()) return 0f
        var pull = 0f
        for (p in hi) {
            val outL = p.out.r * 0.2627f + p.out.g * 0.6780f + p.out.b * 0.0593f
            pull += (p.inp.r - outL).coerceAtLeast(0f)
        }
        return ((pull / hi.size) / 0.12f).coerceIn(0f, 1f)
    }

    private fun estimateFilmCurve(pairs: List<PairSample>): FilmCurve {
        val neutrals = pairs.filter {
            kotlin.math.abs(it.inp.r - it.inp.g) < 1e-4f &&
                kotlin.math.abs(it.inp.g - it.inp.b) < 1e-4f
        }.sortedBy { it.inp.r }
        if (neutrals.size < 10) return FilmCurve()
        // Mid slope vs identity → contrast amount for FilmCurve.
        val a = neutrals.first { it.inp.r >= 0.35f }
        val b = neutrals.first { it.inp.r >= 0.55f }
        val slope = ((b.out.r - a.out.r) / (b.inp.r - a.inp.r).coerceAtLeast(1e-4f))
        val contrast = ((slope - 1f) * 80f).coerceIn(0f, 100f)
        val knee = estimateFilmRolloff(pairs) * 70f
        if (contrast < 5f && knee < 5f) return FilmCurve()
        return FilmCurve(
            contrast = contrast,
            pivot = 0.45f,
            highlightKnee = knee,
            shadowToe = 0f,
        )
    }

    // ── Optimize ──────────────────────────────────────────────────────────

    private fun lineSearch(
        pairs: List<PairSample>,
        wb: Float, tint: Float, contrast: Float, hi: Float, sh: Float, sat: Float,
        paramIndex: Int,
    ): Float {
        var bestVal = when (paramIndex) {
            0 -> wb; 1 -> tint; 2 -> contrast; 3 -> hi; 4 -> sh; else -> sat
        }
        var bestErr = rmsError(pairs, wb, tint, contrast, hi, sh, sat)
        // Coarse then fine.
        for (span in floatArrayOf(0.35f, 0.12f, 0.04f)) {
            val center = bestVal
            var i = -4
            while (i <= 4) {
                val trial = (center + i * span * 0.25f).coerceIn(-1f, 1f)
                var tw = wb; var tt = tint; var tc = contrast; var th = hi; var ts = sh; var tsat = sat
                when (paramIndex) {
                    0 -> tw = trial
                    1 -> tt = trial
                    2 -> tc = trial
                    3 -> th = trial
                    4 -> ts = trial
                    else -> tsat = trial
                }
                val err = rmsError(pairs, tw, tt, tc, th, ts, tsat)
                if (err < bestErr) {
                    bestErr = err
                    bestVal = trial
                }
                i++
            }
        }
        return bestVal
    }

    private fun rmsError(
        pairs: List<PairSample>,
        wb: Float, tint: Float, contrast: Float, hi: Float, sh: Float, sat: Float,
    ): Float {
        var acc = 0.0
        for (p in pairs) {
            var r = p.inp.r; var g = p.inp.g; var b = p.inp.b
            applyWbTint(r, g, b, wb, tint).also { r = it[0]; g = it[1]; b = it[2] }
            applyExposureContrast(r, g, b, 0f, contrast).also { r = it[0]; g = it[1]; b = it[2] }
            applyToneRegions(r, g, b, hi, sh, 0f, 0f).also { r = it[0]; g = it[1]; b = it[2] }
            applySaturation(r, g, b, sat).also { r = it[0]; g = it[1]; b = it[2] }
            val dr = r - p.out.r; val dg = g - p.out.g; val db = b - p.out.b
            acc += dr * dr + dg * dg + db * db
        }
        return sqrt(acc / (pairs.size * 3.0)).toFloat()
    }

    // ── StudioRoom forward kernels (sRGB display-referred mirrors) ────────

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float {
        val x = c.coerceAtLeast(0f)
        return if (x <= 0.0031308f) 12.92f * x
        else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    private fun wbWhitePointXyz(T: Float): FloatArray {
        val t = T.coerceIn(1667f, 25000f)
        val t2 = t * t; val t3 = t2 * t
        val x = when {
            t < 4000f -> -0.2661239e9f / t3 - 0.2343589e6f / t2 + 0.8776956e3f / t + 0.179910f
            t <= 7000f -> 0.244063f + 0.09911e3f / t + 2.9678e6f / t2 - 4.6070e9f / t3
            else -> 0.237040f + 0.24748e3f / t + 1.9018e6f / t2 - 2.0064e9f / t3
        }
        val y = if (t < 4000f) {
            -1.1063814f * x * x * x - 1.34811020f * x * x + 2.18555832f * x - 0.20219683f
        } else {
            -3.000f * x * x + 2.870f * x - 0.275f
        }.coerceAtLeast(1e-4f)
        return floatArrayOf(x / y, 1f, (1f - x - y) / y)
    }

    private fun wbGainForKelvin(T: Float): FloatArray {
        val xyz = wbWhitePointXyz(T)
        val X = xyz[0]; val Y = xyz[1]; val Z = xyz[2]
        var r = 3.2404542f * X - 1.5371385f * Y - 0.4985314f * Z
        var g = -0.9692660f * X + 1.8760108f * Y + 0.0415560f * Z
        var b = 0.0556434f * X - 0.2040259f * Y + 1.0572252f * Z
        r = r.coerceAtLeast(1e-4f); g = g.coerceAtLeast(1e-4f); b = b.coerceAtLeast(1e-4f)
        return floatArrayOf(r / g, 1f, b / g)
    }

    private fun applyWbTint(r0: Float, g0: Float, b0: Float, wbDelta: Float, tint: Float): FloatArray {
        var r = srgbToLinear(r0); var g = srgbToLinear(g0); var b = srgbToLinear(b0)
        if (kotlin.math.abs(wbDelta) > 1e-4f) {
            var tK = 6500f + wbDelta * 2500f
            tK = tK.coerceIn(1667f, 25000f)
            val ref = wbGainForKelvin(6500f)
            val tgt = wbGainForKelvin(tK)
            var gainR = ref[0] / tgt[0]; var gainG = ref[1] / tgt[1]; var gainB = ref[2] / tgt[2]
            val nrm = gainG.coerceAtLeast(1e-4f)
            gainR /= nrm; gainG /= nrm; gainB /= nrm
            r *= gainR; g *= gainG; b *= gainB
        }
        if (kotlin.math.abs(tint) > 1e-4f) {
            val gg = 1f + tint * 0.12f
            val comp = 1f / sqrt(gg.coerceAtLeast(1e-4f))
            g *= gg; r *= comp; b *= comp
        }
        return floatArrayOf(
            linearToSrgb(r).coerceIn(0f, 1.5f),
            linearToSrgb(g).coerceIn(0f, 1.5f),
            linearToSrgb(b).coerceIn(0f, 1.5f),
        )
    }

    private fun applyExposureContrast(
        r0: Float, g0: Float, b0: Float, ev: Float, c: Float,
    ): FloatArray {
        var r = srgbToLinear(r0); var g = srgbToLinear(g0); var b = srgbToLinear(b0)
        val scale = exp(ev * ln(2f))
        r *= scale; g *= scale; b *= scale
        val bias = 0.18f
        val k = 1f + c
        r = bias + (r - bias) * k
        g = bias + (g - bias) * k
        b = bias + (b - bias) * k
        return floatArrayOf(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun applyToneRegions(
        r0: Float, g0: Float, b0: Float,
        hi: Float, sh: Float, wh: Float, bl: Float,
    ): FloatArray {
        var r = srgbToLinear(r0); var g = srgbToLinear(g0); var b = srgbToLinear(b0)
        val L = r * 0.2627f + g * 0.6780f + b * 0.0593f
        val highlightMask = smoothstep(0.18f, 0.72f, L)
        val shadowMask = 1f - smoothstep(0.003f, 0.18f, L)
        val kh = 1f + hi * highlightMask * 0.5f
        val ks = 1f + sh * shadowMask * 0.8f
        r *= kh * ks; g *= kh * ks; b *= kh * ks
        if (wh != 0f || bl != 0f) {
            val wp = (1f - wh * 0.25f).coerceAtLeast(0.1f)
            val bp = (bl * 0.12f).coerceIn(-0.15f, 0.15f)
            val scale = (1f - bp) / wp
            r = bp + r * scale; g = bp + g * scale; b = bp + b * scale
        }
        return floatArrayOf(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
    }

    private fun applySaturation(r0: Float, g0: Float, b0: Float, sat: Float): FloatArray {
        val L = r0 * 0.2627f + g0 * 0.6780f + b0 * 0.0593f
        val k = 1f + sat
        return floatArrayOf(
            L + (r0 - L) * k,
            L + (g0 - L) * k,
            L + (b0 - L) * k,
        )
    }
}
