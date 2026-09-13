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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.core.graphics.createBitmap
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawGradientBlendMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.SegmentTarget
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.LocalAdjustmentFusionProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.segmentation.LmmseFusionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Applies a [UserMacro] to the neutral Stage-C sRGB workspace bitmap,
 * producing an adjusted preview bitmap without touching the neutral original.
 *
 * Pipeline order:
 *   1. Per-channel 1-D LUT: exposure, white-balance, tint, tone (highlights/shadows/
 *      whites/blacks), contrast S-curve, dehaze, tone-curve zone anchors.
 *   2. Per-pixel HSL pass: saturation, vibrance, per-range HSL.
 *   3. Spatial passes: noise reduction (box blur), sharpness/clarity/texture (unsharp mask).
 *   4. Vignette radial pass.
 *   5. Linear gradient overlay.
 *   6. CPU LUT trilinear interpolation (if lutCubeUri set).
 */
object MacroProcessor {

    // Fusion processor cache for local adjustments
    private var fusionProcessor: LocalAdjustmentFusionProcessor? = null
    private var fusionContext: Context? = null

    /**
     * Initialize fusion processor for local adjustments.
     * Call once per editing session.
     */
    fun initializeFusionProcessor(context: Context) {
        if (fusionContext !== context) {
            fusionProcessor?.close()
            fusionProcessor = LocalAdjustmentFusionProcessor(context)
            fusionContext = context
            Log.i("MacroProcessor", "Fusion processor initialized for local adjustments")
        }
    }

    /**
     * Release fusion processor resources.
     * Call when done editing.
     */
    fun releaseFusionProcessor() {
        fusionProcessor?.close()
        fusionProcessor = null
        fusionContext = null
        Log.i("MacroProcessor", "Fusion processor released")
    }

    /**
     * Apply [macro] to [neutral] with offline fusion masks for local adjustments.
     *
     * @param context Android context for fusion processor
     * @param neutral Bitmap to adjust
     * @param macro Adjustment parameters
     * @param previewBitmap Optional preview for mask computation
     * @param releaseNeutral Allow recycling neutral mid-pipeline
     */
    suspend fun applyWithFusion(
        context: Context,
        neutral: Bitmap,
        macro: UserMacro,
        previewBitmap: Bitmap? = null,
        releaseNeutral: Boolean = false,
    ): Bitmap = withContext(Dispatchers.Default) {
        try {
            initializeFusionProcessor(context)

            val preview = previewBitmap ?: neutral
            val fusionMask = fusionProcessor?.detectRegionalMask(
                preview = preview,
                targetWidth = neutral.width,
                targetHeight = neutral.height,
                useCache = true  // Reuse mask across multiple adjustments
            )

            if (fusionMask != null) {
                val segmentationMasks = fusionProcessor?.convertToSegmentationMasks(
                    fusionMask,
                    neutral.width,
                    neutral.height
                )
                Log.i("MacroProcessor", "Applying macro with fusion masks (sharp boundaries)")
                apply(neutral, macro, segmentationMasks, releaseNeutral = releaseNeutral)
            } else {
                Log.w("MacroProcessor", "Fusion unavailable, applying macro with generic masks")
                apply(neutral, macro, releaseNeutral = releaseNeutral)
            }
        } catch (e: Exception) {
            Log.e("MacroProcessor", "applyWithFusion failed: ${e.message}", e)
            apply(neutral, macro, releaseNeutral = releaseNeutral)
        }
    }

    /**
     * Apply [macro] to [neutral] and return a new bitmap.
     *
     * @param releaseNeutral When true, the implementation is allowed to recycle
     *   [neutral] mid-pipeline to free heap headroom before the 242 MB FloatArray
     *   that spatial ops require. Pass `true` ONLY when the caller doesn't share
     *   `neutral` with the canvas (save / export paths). Default `false` keeps
     *   the slider re-render + idle-full-res paths safe.
     */
    suspend fun apply(
        neutral: Bitmap,
        macro: UserMacro,
        masks: RawSegmentationMasks? = null,
        maskBitmap: Bitmap? = null,
        releaseNeutral: Boolean = false,
    ): Bitmap {
        if (macro == UserMacro()) return neutral
        val t0 = android.os.SystemClock.uptimeMillis()
        val cfg = neutral.config
        val result = if (cfg == Bitmap.Config.RGBA_F16) {
            applyFloat(neutral, macro, masks, maskBitmap, releaseNeutral)
        } else {
            applyInt(neutral, macro, masks, maskBitmap)
        }
        val dt = android.os.SystemClock.uptimeMillis() - t0
        android.util.Log.i(
            "MacroProcessor",
            "apply ${cfg?.name ?: "?"} ${neutral.width}x${neutral.height} took ${dt}ms",
        )
        return applySubjectPop(result, neutral, macro, masks)
    }

    /**
     * Final-pass "Subject Pop": a subject-masked shadow/highlight/saturation
     * grade via the native fusion tone-mapper. Runs only when
     * [UserMacro.subjectPopEnabled] is set, at least one strength is non-zero,
     * and a subject mask is available. No-op (returns [result] unchanged)
     * otherwise, so existing renders are untouched.
     *
     * The native path needs ARGB_8888; an FP16 [result] is down-converted by
     * [LmmseFusionBridge.enhanceWithMask] (the grade is the final look — note
     * that a 16-bit TIFF export loses precision once Subject Pop is enabled).
     * The replaced intermediate is recycled unless it is the shared [neutral].
     */
    private fun applySubjectPop(
        result: Bitmap,
        neutral: Bitmap,
        macro: UserMacro,
        masks: RawSegmentationMasks?,
    ): Bitmap {
        if (!macro.subjectPopEnabled) return result
        val s = macro.subjectPopShadow
        val h = macro.subjectPopHighlight
        val sat = macro.subjectPopSaturation
        if (s == 0f && h == 0f && sat == 0f) return result
        val m = masks ?: run {
            Log.w("MacroProcessor", "Subject Pop enabled but no mask available — skipping")
            return result
        }
        // Prefer the refined (sharper, source-aspect) matte when present.
        val refined = m.refinedMask
        val mask: FloatArray
        val mw: Int
        val mh: Int
        if (refined != null && m.refinedWidth > 0 && m.refinedHeight > 0) {
            mask = refined; mw = m.refinedWidth; mh = m.refinedHeight
        } else {
            mask = m.subjectMask; mw = RawSegmentationMasks.MASK_SIZE; mh = RawSegmentationMasks.MASK_SIZE
        }
        val out = LmmseFusionBridge.enhanceWithMask(result, mask, mw, mh, s, h, sat)
        if (out !== result && result !== neutral && !result.isRecycled) result.recycle()
        Log.i("MacroProcessor", "Subject Pop applied (s=$s h=$h sat=$sat, mask=${mw}x$mh)")
        return out
    }

    /**
     * Apply ONE brush-mask layer on top of [base]: read the per-pixel adjustments
     * from [macro.maskXxx] fields, gated by the alpha channel of [maskBitmap]. The
     * function runs only the localized-edit math (no global Group A/B/C stages),
     * so callers can stack multiple mask layers by calling this once per layer
     * with each action's own (mask bitmap, macro) pair.
     *
     * Returns [base] unchanged if no mask field is non-default — saves a copy.
     */
    suspend fun applyMaskLayer(
        base: Bitmap,
        macro: UserMacro,
        maskBitmap: Bitmap,
    ): Bitmap = withContext(Dispatchers.Default) {
        val hasMaskEffect = macro.maskBrightness != 0f || macro.maskContrast != 0f ||
            macro.maskTemperature != 0 || macro.maskTint != 0f ||
            macro.maskSaturation != 0f || macro.maskClarity != 0f ||
            macro.maskTone.highlights != 0f || macro.maskTone.shadows != 0f ||
            macro.maskTone.whites != 0f || macro.maskTone.blacks != 0f
        if (!hasMaskEffect) return@withContext base

        val t0 = android.os.SystemClock.uptimeMillis()
        val result = when (base.config) {
            Bitmap.Config.RGBA_F16 -> applyMaskLayerFloat(base, macro, maskBitmap)
            else                    -> applyMaskAdjustments(base, macro, maskBitmap)
        }
        val dt = android.os.SystemClock.uptimeMillis() - t0
        android.util.Log.i(
            "MacroProcessor",
            "applyMaskLayer ${base.config?.name} ${base.width}x${base.height} took ${dt}ms",
        )
        result
    }

    /** Float-domain wrapper around [applyMaskAdjustmentsFloat] returning a fresh FP16 bitmap. */
    private suspend fun applyMaskLayerFloat(
        base: Bitmap,
        macro: UserMacro,
        maskBitmap: Bitmap,
    ): Bitmap = withContext(Dispatchers.Default) {
        val w = base.width
        val h = base.height
        // Read FP16 → float
        val rgb = readFloatPixelsRgb(base, w, h)
        applyMaskAdjustmentsFloat(rgb, w, h, macro, maskBitmap)
        writeFloatPixelsRgbToFp16(rgb, w, h, base.colorSpace)
    }

    /**
     * 8-bit `ARGB_8888` macro pipeline. Renamed from the pre-Step-3 `apply` body —
     * untouched math, used for the 8-bit workspace path AND as the spatial-stage
     * fallback for the 16-bit path until those stages are ported in later turns.
     */
    private suspend fun applyInt(
        neutral: Bitmap,
        macro: UserMacro,
        masks: RawSegmentationMasks? = null,
        maskBitmap: Bitmap? = null,
    ): Bitmap = withContext(Dispatchers.Default) {
            // Smart Color Enhancement pre-pass: auto-WB + CLAHE on Lab L +
            // adaptive chroma boost, all before the per-pixel macro loop.
            val src = if (macro.smartColorEnhance > 0f) SmartColorEnhancer.enhance(neutral) else neutral
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            if (src !== neutral) src.recycle()

            // ── Stage 1: per-channel LUT ──────────────────────────────────────
            val (rLut, gLut, bLut) = buildChannelLuts(macro)

            val satF = macro.saturation / 100f
            val vibF = macro.vibrance   / 100f
            val hasHslRange = macro.hslRedHue != 0f || macro.hslRedSat != 0f || macro.hslRedLum != 0f ||
                macro.hslOrangeHue != 0f || macro.hslOrangeSat != 0f || macro.hslOrangeLum != 0f ||
                macro.hslYellowHue != 0f || macro.hslYellowSat != 0f || macro.hslYellowLum != 0f ||
                macro.hslGreenHue != 0f  || macro.hslGreenSat != 0f  || macro.hslGreenLum != 0f  ||
                macro.hslAquaHue != 0f   || macro.hslAquaSat != 0f   || macro.hslAquaLum != 0f   ||
                macro.hslBlueHue != 0f   || macro.hslBlueSat != 0f   || macro.hslBlueLum != 0f
            val hasHsl = satF != 0f || vibF != 0f || hasHslRange

            // Precomputed tables so the HSL pass doesn't call pow() per pixel.
            // LUT outputs are gamma-encoded (sRGB ≈ 2.2); HSL saturation math must run
            // in linear light to avoid asymmetric clipping and quantisation artefacts.
            val gammaToLinear = if (hasHsl) FloatArray(256) { i -> (i / 255f).pow(2.2f) } else null
            val linearToGamma = if (hasHsl) IntArray(1024) { i ->
                ((i / 1023f).pow(1f / 2.2f) * 255.5f).toInt().coerceIn(0, 255)
            } else null

            for (i in pixels.indices) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                val px = pixels[i]
                var r = rLut[(px shr 16) and 0xFF]
                var g = gLut[(px shr 8)  and 0xFF]
                var b = bLut[ px         and 0xFF]

                if (hasHsl) {
                    // Full LCHab round-trip — single pass for saturation, vibrance, AND
                    // per-range hue/chroma/luminance adjustments. L (perceived luminance)
                    // is preserved exactly through chroma scaling. Per-range hue centers
                    // use LCHab angles (red≈38°, orange≈55°, yellow≈99°, green≈137°,
                    // aqua≈193°, blue≈282°) instead of HSL's evenly-spaced 60° grid.
                    val rf = gammaToLinear!![r]; val gf = gammaToLinear[g]; val bf = gammaToLinear[b]
                    val (iL, iC, iH) = linearRgbToLch(rf, gf, bf)
                    var nL = iL
                    var nC = adjustChromaLCH(iC, satF, vibF)
                    var nH = iH

                    if (hasHslRange) {
                        // Guard: fade per-hue shifts on achromatic pixels (low chroma)
                        // where the hue angle is numerically unstable.
                        val satMask = smoothstep01(5f, 20f, iC)
                        val rW = hueRangeWeight(nH, 38f,  65f)
                        val oW = hueRangeWeight(nH, 55f,  40f)
                        val yW = hueRangeWeight(nH, 99f,  50f)
                        val gW = hueRangeWeight(nH, 137f, 85f)
                        val aW = hueRangeWeight(nH, 193f, 60f)
                        val bW = hueRangeWeight(nH, 282f, 85f)
                        val totalW = (rW + oW + yW + gW + aW + bW).coerceAtLeast(1e-6f)

                        // Hue shift: macro values in degrees (no /360 needed — LCHab H is [0,360]).
                        val hShift = (macro.hslRedHue*rW + macro.hslOrangeHue*oW + macro.hslYellowHue*yW +
                            macro.hslGreenHue*gW + macro.hslAquaHue*aW + macro.hslBlueHue*bW) / totalW
                        // Chroma shift: macro /100 → fraction of maxC (150 chroma units).
                        val cShift = (macro.hslRedSat*rW + macro.hslOrangeSat*oW + macro.hslYellowSat*yW +
                            macro.hslGreenSat*gW + macro.hslAquaSat*aW + macro.hslBlueSat*bW) / totalW / 100f * 150f
                        // Luminance shift: macro values map directly to L-units [0,100].
                        val lShift = (macro.hslRedLum*rW + macro.hslOrangeLum*oW + macro.hslYellowLum*yW +
                            macro.hslGreenLum*gW + macro.hslAquaLum*aW + macro.hslBlueLum*bW) / totalW

                        nH = ((nH + hShift * satMask) + 360f).mod(360f)
                        nC = (nC + cShift * satMask).coerceIn(0f, 150f)
                        nL = (nL + lShift * satMask).coerceIn(0f, 100f)
                    }

                    val (nr, ngr, nb) = lchToLinearRgb(nL, nC, nH)
                    r = linearToGamma!![(nr  * 1023f).toInt().coerceIn(0, 1023)]
                    g = linearToGamma[(ngr * 1023f).toInt().coerceIn(0, 1023)]
                    b = linearToGamma[(nb  * 1023f).toInt().coerceIn(0, 1023)]
                }
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            var result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(pixels, 0, w, 0, 0, w, h)

            // ── Stage 2: spatial frequency adjustments ────────────────────────
            if (macro.noiseReduction > 0f) {
                val radius = ((macro.noiseReduction / 100f) * 3f).toInt().coerceIn(1, 3)
                result = applyBoxBlurBitmap(result, radius)
            }
            if (macro.luminanceNR > 0f || macro.colorNR > 0f) {
                result = applyNoiseReduction(result, macro.luminanceNR, macro.colorNR)
            }
            if (macro.sharpness > 0f || macro.clarity != 0f || macro.texture != 0f) {
                result = applySpatialAdjustments(result, macro.sharpness, macro.clarity, macro.texture)
            }
            if (macro.smartSharpness > 0f) {
                result = applySmartSharpness(result, macro.smartSharpness)
            }
            if (macro.vignetteAmount != 0f) {
                result = applyVignette(result, macro, masks)
            }

            // ── Stage 3: gradient + film grain ───────────────────────────────
            val hasGradient = macro.gradientTopIntensity != 0f || macro.gradientBottomIntensity != 0f ||
                macro.gradientLeftIntensity != 0f || macro.gradientRightIntensity != 0f
            if (hasGradient) result = applyEdgeGradients(result, macro, masks)

            if (macro.filmGrain > 0f) {
                result = applyFilmGrain(result, macro.filmGrain, macro.filmGrainSize, 0f)
            }
            if (macro.filmGrainWashOut > 0f) {
                result = applyWashOut(result, macro.filmGrainWashOut)
            }

            // ── Stage 4: brush mask local adjustments ─────────────────────────
            val hasMaskEffect = maskBitmap != null && (
                macro.maskBrightness != 0f || macro.maskContrast != 0f ||
                macro.maskTemperature != 0 || macro.maskTint != 0f ||
                macro.maskSaturation != 0f || macro.maskClarity != 0f ||
                macro.maskTone.highlights != 0f || macro.maskTone.shadows != 0f ||
                macro.maskTone.whites != 0f || macro.maskTone.blacks != 0f
            )
            if (hasMaskEffect) result = applyMaskAdjustments(result, macro, maskBitmap!!)

            result
        }

    // ── 16-bit (RGBA_F16) macro pipeline ──────────────────────────────────────
    //
    // Turn 1 of the float port: Group A (channel curves: exposure / WB / tint /
    // highlights / shadows / whites / blacks / contrast / dehaze / tone curves
    // + HSL / saturation / vibrance) runs natively in float-linear space. The
    // FP16 bitmap arrives already linear-light (it was tagged LINEAR_EXTENDED_SRGB
    // by Stage C), so no γ-decode/encode is needed at the boundaries — the math
    // operates directly on the half-floats. This eliminates the precision loss
    // and ~500-900 ms overhead of the FP16↔8 round-trip for Group-A-only edits.
    //
    // Stages 2-5 (spatial / vignette / gradient / mask) are not ported in this
    // turn. When the macro contains those adjustments, we fall back to the
    // 8-bit bridge path so behavior is identical to today.

    private suspend fun applyFloat(
        neutral: Bitmap,
        macro: UserMacro,
        masks: RawSegmentationMasks?,
        maskBitmap: Bitmap?,
        releaseNeutral: Boolean = false,
    ): Bitmap = withContext(Dispatchers.Default) {
        // Turn 3: all stages now have native float implementations. The 8-bit
        // bridge (downsample16To8 + applyInt + upsample8To16) is retained as
        // dead code for safety but should never run on this path now. If you
        // see `bridge` in the log below, it's a regression flag worth chasing.
        val nonGroupA = hasNonGroupAAdjustments(macro)
        android.util.Log.i(
            "MacroProcessor",
            "applyFloat: nonGroupA=$nonGroupA (true = bridge unexpected, false = native float)",
        )
        if (nonGroupA) {
            android.util.Log.w(
                "MacroProcessor",
                "applyFloat: UNEXPECTED bridge fallback — should be unreachable in Turn 3",
            )
            val downsampled = downsample16To8(neutral)
            val processed = applyInt(downsampled, macro, masks, maskBitmap)
            if (downsampled !== neutral && downsampled !== processed) downsampled.recycle()
            return@withContext upsample8To16(processed, neutral.colorSpace)
        }

        // ── Pure-float Group A pipeline ────────────────────────────────────
        val w = neutral.width
        val h = neutral.height
        val pixelCount = w * h

        // Memory budget guard: the whole-image FloatArray is `pixelCount * 3 * 4`
        // bytes. At 20MP that's 242MB — larger than the JVM heap headroom on
        // 4GB phones with the 512MB cap. When we exceed the threshold, take the
        // banded path: process Group A pixel-local stages in row bands, then
        // (only if needed) run Group B/C/D/E as a single full-image pass.
        //
        // Threshold: 60MB = 5MP * 3 floats * 4 bytes. Anything larger streams.
        val wholeImageBytes = pixelCount.toLong() * 3L * 4L
        val useBanded = wholeImageBytes > 60_000_000L
        if (useBanded) {
            return@withContext applyFloatBanded(neutral, macro, masks, maskBitmap, releaseNeutral)
        }

        // Read FP16 pixels into a FloatArray of interleaved RGB triples.
        // Alpha is discarded — re-emitted as 1.0 on write.
        val rgb = readFloatPixelsRgb(neutral, w, h)

        // Build the same channel-curve functions as the 8-bit pipeline, but
        // evaluated per-pixel in float instead of via 256-entry IntArray LUTs.
        val curve = buildFloatChannelCurves(macro)

        // sat/vib/hsl flags — same as applyInt's checks
        val satF = macro.saturation / 100f
        val vibF = macro.vibrance   / 100f
        val hasHslRange = macro.hslRedHue != 0f || macro.hslRedSat != 0f || macro.hslRedLum != 0f ||
            macro.hslOrangeHue != 0f || macro.hslOrangeSat != 0f || macro.hslOrangeLum != 0f ||
            macro.hslYellowHue != 0f || macro.hslYellowSat != 0f || macro.hslYellowLum != 0f ||
            macro.hslGreenHue != 0f  || macro.hslGreenSat != 0f  || macro.hslGreenLum != 0f  ||
            macro.hslAquaHue != 0f   || macro.hslAquaSat != 0f   || macro.hslAquaLum != 0f   ||
            macro.hslBlueHue != 0f   || macro.hslBlueSat != 0f   || macro.hslBlueLum != 0f
        val hasHsl = satF != 0f || vibF != 0f || hasHslRange

        // OkLab scratch buffer reused per-pixel — three floats only, no allocation in hot loop.
        val okLab = FloatArray(3)
        val okRgb = FloatArray(3)

        var i = 0
        while (i < pixelCount) {
            if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
            val base = i * 3
            // Input is linear-light from the tagged FP16 bitmap; the channel
            // curve operates in linear space directly (no γ-decode needed).
            var r = curve.evalR(rgb[base])
            var g = curve.evalG(rgb[base + 1])
            var b = curve.evalB(rgb[base + 2])

            if (hasHsl) {
                // ── HDR-safe Saturation / Vibrance (Rec.709 luma blend) ──────────
                // For sat/vib without per-hue: just lerp toward Rec.709 luma in
                // linear light. Highlights >1.0 stay above 1.0 — never clamped.
                // satF and vibF are in roughly [-1, +1]. The "vibrance" weight
                // pushes already-saturated pixels less than near-grey ones.
                if (satF != 0f || vibF != 0f) {
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    // chroma magnitude as fraction of luma — used by vibrance to
                    // attenuate the effect on already-saturated pixels.
                    val absMax = kotlin.math.max(
                        kotlin.math.max(kotlin.math.abs(r - luma), kotlin.math.abs(g - luma)),
                        kotlin.math.abs(b - luma),
                    )
                    val chromaNorm = if (luma > 1e-4f) (absMax / luma).coerceIn(0f, 1f) else 0f
                    // Effective gain = (1 + satF) for sat slider, plus a vibrance
                    // contribution that fades out as chromaNorm → 1.
                    val gain = (1f + satF) * (1f + vibF * (1f - chromaNorm))
                    r = luma + (r - luma) * gain
                    g = luma + (g - luma) * gain
                    b = luma + (b - luma) * gain
                }

                // ── HDR-safe per-hue HSL adjustments in OkLab ────────────────────
                // Hue rotation / per-band saturation / per-band luminance shifts
                // happen on (a, b) chroma + L axis, all extended-range. Skip
                // entirely when no per-hue field is non-zero (cheap fast path).
                if (hasHslRange) {
                    OkLabMath.linearSrgbToOklab(r, g, b, okLab)
                    var L = okLab[0]
                    var a = okLab[1]
                    var bb = okLab[2]

                    val C = OkLabMath.chroma(a, bb)

                    // Per-hue weights use the OkLab hue angle. Map our six
                    // "named" hues (red 0°, orange 30°, yellow 60°, green 120°,
                    // aqua 180°, blue 240°) — same convention as the legacy
                    // HSL path — but evaluated in OkLab rather than sRGB hue.
                    // Result: hue boundaries are perceptually uniform.
                    val hDeg = if (C > 1e-6f) {
                        val deg = (OkLabMath.hueRadians(a, bb) * (180f / kotlin.math.PI.toFloat()))
                        if (deg < 0f) deg + 360f else deg
                    } else 0f

                    val rW = hueRangeWeight(hDeg, 0f,   65f)
                    val oW = hueRangeWeight(hDeg, 30f,  40f)
                    val yW = hueRangeWeight(hDeg, 60f,  50f)
                    val gW = hueRangeWeight(hDeg, 120f, 85f)
                    val aW = hueRangeWeight(hDeg, 180f, 60f)
                    val bW = hueRangeWeight(hDeg, 240f, 85f)
                    val totalW = (rW + oW + yW + gW + aW + bW).coerceAtLeast(1e-6f)

                    // Attenuate per-hue effects on near-grey pixels where the
                    // hue is numerically unstable. C in OkLab caps at ~0.4 for
                    // saturated sRGB; smoothstep gives a clean fade-in.
                    val chromaGate = smoothstep01(0.005f, 0.04f, C)

                    val hShiftDeg = (macro.hslRedHue*rW + macro.hslOrangeHue*oW + macro.hslYellowHue*yW +
                        macro.hslGreenHue*gW + macro.hslAquaHue*aW + macro.hslBlueHue*bW) / totalW
                    val sShift = (macro.hslRedSat*rW + macro.hslOrangeSat*oW + macro.hslYellowSat*yW +
                        macro.hslGreenSat*gW + macro.hslAquaSat*aW + macro.hslBlueSat*bW) / totalW / 100f
                    val lShift = (macro.hslRedLum*rW + macro.hslOrangeLum*oW + macro.hslYellowLum*yW +
                        macro.hslGreenLum*gW + macro.hslAquaLum*aW + macro.hslBlueLum*bW) / totalW / 100f

                    // Apply hue rotation as a 2D rotation in the (a, b) plane —
                    // perfect HDR safety because rotations preserve |C|.
                    if (hShiftDeg != 0f && chromaGate > 0f) {
                        val theta = hShiftDeg * chromaGate * (kotlin.math.PI.toFloat() / 180f)
                        val cs = kotlin.math.cos(theta)
                        val sn = kotlin.math.sin(theta)
                        val newA = a * cs - bb * sn
                        val newB = a * sn + bb * cs
                        a = newA
                        bb = newB
                    }
                    // Per-band saturation scales chroma vector magnitude. >1.0
                    // amplifies, <1.0 desaturates. Negative input from user is
                    // mapped to [0, 1] range; positive extends past 1.
                    if (sShift != 0f && chromaGate > 0f) {
                        val sScale = (1f + sShift * chromaGate).coerceAtLeast(0f)
                        a *= sScale
                        bb *= sScale
                    }
                    // Per-band luminance shifts L additively. OkLab L is
                    // perceptually linear so equal shifts produce equal-looking
                    // brightness changes. L stays >= 0 but is otherwise unbounded.
                    if (lShift != 0f) {
                        L = (L + lShift * chromaGate).coerceAtLeast(0f)
                    }

                    OkLabMath.oklabToLinearSrgb(L, a, bb, okRgb)
                    r = okRgb[0]
                    g = okRgb[1]
                    b = okRgb[2]
                }
            }

            // Allow extended-range values (above 1.0) for the LINEAR_EXTENDED_SRGB
            // FP16 tag. The screen quantizes at present time; the precision is
            // preserved at export. Sanitize non-finite values (NaN/Inf) to 0 —
            // they otherwise survive Float.coerceAtLeast and render as sparkly
            // pixels.
            rgb[base    ] = if (r.isFinite()) r.coerceAtLeast(0f) else 0f
            rgb[base + 1] = if (g.isFinite()) g.coerceAtLeast(0f) else 0f
            rgb[base + 2] = if (b.isFinite()) b.coerceAtLeast(0f) else 0f
            i++
        }

        // ── Group B: spatial / NR / sharpness / clarity / texture / smartSharpness / film grain / wash out ──
        // Turn 2 port: every stage mirrors the IntArray path's math but operates
        // on the float `rgb` buffer directly. No γ-encode/decode required since
        // both input and intermediate state are linear-light extended-sRGB.
        applyGroupBSpatialFloat(rgb, w, h, macro)

        // ── Group C/D/E: vignette / edge gradients / brush-mask local adjustments ──
        // Turn 3 port: remaining stages from the 8-bit pipeline. Each runs only
        // if its trigger fields are non-default.
        applyVignetteFloat(rgb, w, h, macro, masks)
        applyEdgeGradientsFloat(rgb, w, h, macro, masks)
        if (maskBitmap != null) applyMaskAdjustmentsFloat(rgb, w, h, macro, maskBitmap)

        writeFloatPixelsRgbToFp16(rgb, w, h, neutral.colorSpace)
    }

    /**
     * Group B spatial passes on a packed `FloatArray` of RGB triples. Mirrors the
     * order and math of [applyInt] stages 2-3: box blur for noiseReduction, then
     * `applyNoiseReduction`, then `applySpatialAdjustments` (sharpness/clarity/
     * texture), then `applySmartSharpness`, then film grain + wash out.
     *
     * Modifies [rgb] in place. Each stage runs only if its slider is non-default.
     */
    private suspend fun applyGroupBSpatialFloat(rgb: FloatArray, w: Int, h: Int, macro: UserMacro) {
        // Stage 2a: global noise reduction box blur
        if (macro.noiseReduction > 0f) {
            val radius = ((macro.noiseReduction / 100f) * 3f).toInt().coerceIn(1, 3)
            val blurred = boxBlurFloat(rgb, w, h, radius)
            System.arraycopy(blurred, 0, rgb, 0, rgb.size)
        }

        // Stage 2b: luminance + color noise reduction
        if (macro.luminanceNR > 0f || macro.colorNR > 0f) {
            val lumRadius   = (macro.luminanceNR * 3f).toInt().coerceIn(1, 5)
            val colorRadius = (macro.colorNR     * 5f).toInt().coerceIn(1, 8)
            val blurLum   = if (macro.luminanceNR > 0f) boxBlurFloat(rgb, w, h, lumRadius)   else null
            val blurColor = if (macro.colorNR     > 0f) boxBlurFloat(rgb, w, h, colorRadius) else null
            val n = w * h
            var i = 0
            while (i < n) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                val base = i * 3
                var r = rgb[base]; var g = rgb[base + 1]; var b = rgb[base + 2]
                if (blurLum != null) {
                    val lumOrig = r * 0.299f + g * 0.587f + b * 0.114f
                    val lumBlur = blurLum[base]     * 0.299f +
                                  blurLum[base + 1] * 0.587f +
                                  blurLum[base + 2] * 0.114f
                    val shift = (lumBlur - lumOrig) * macro.luminanceNR
                    r += shift; g += shift; b += shift
                }
                if (blurColor != null) {
                    val lumOrig = r * 0.299f + g * 0.587f + b * 0.114f
                    val br = blurColor[base]; val bg = blurColor[base + 1]; val bb = blurColor[base + 2]
                    val blendR = r + (br - r) * macro.colorNR
                    val blendG = g + (bg - g) * macro.colorNR
                    val blendB = b + (bb - b) * macro.colorNR
                    val lumAfter = blendR * 0.299f + blendG * 0.587f + blendB * 0.114f
                    val lumCorr = lumOrig - lumAfter
                    r = blendR + lumCorr; g = blendG + lumCorr; b = blendB + lumCorr
                }
                rgb[base]     = r.coerceAtLeast(0f)
                rgb[base + 1] = g.coerceAtLeast(0f)
                rgb[base + 2] = b.coerceAtLeast(0f)
                i++
            }
        }

        // Stage 2c: sharpness / clarity / texture via unsharp mask
        if (macro.sharpness > 0f || macro.clarity != 0f || macro.texture != 0f) {
            val sharpF = macro.sharpness / 100f
            val clarF  = macro.clarity   / 100f
            val texF   = macro.texture   / 100f
            val blur1 = if (sharpF != 0f || texF != 0f) boxBlurFloat(rgb, w, h, 1) else null
            val blur5 = if (clarF  != 0f)               boxBlurFloat(rgb, w, h, 5) else null
            val n = w * h
            var i = 0
            while (i < n) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                val base = i * 3
                var r = rgb[base]; var g = rgb[base + 1]; var b = rgb[base + 2]
                if (blur1 != null) {
                    val weight = sharpF * 0.6f + texF * 0.4f
                    r += weight * (r - blur1[base])
                    g += weight * (g - blur1[base + 1])
                    b += weight * (b - blur1[base + 2])
                }
                if (blur5 != null) {
                    // Clarity = unsharp mask weighted by a mid-tone bell. The
                    // bell `4 * lum * (1 - lum)` peaks at lum=0.5 and goes
                    // negative outside [0,1] — clamping the **bell input** keeps
                    // the weight non-negative, while the actual r/g/b update
                    // (`r + weight * (r - blur)`) remains extended-range.
                    val lum = (r * 0.2126f + g * 0.7152f + b * 0.0722f).coerceIn(0f, 1f)
                    val midZone = 4f * lum * (1f - lum)
                    val weight = clarF * midZone * 0.5f
                    r += weight * (r - blur5[base])
                    g += weight * (g - blur5[base + 1])
                    b += weight * (b - blur5[base + 2])
                }
                // HDR-safe: only sanity-clamp negative values. The unsharp mask
                // overshoot past 1.0 is the perceptually desired "snap" on
                // specular edges and is preserved.
                rgb[base]     = r.coerceAtLeast(0f)
                rgb[base + 1] = g.coerceAtLeast(0f)
                rgb[base + 2] = b.coerceAtLeast(0f)
                i++
            }
        }

        // Stage 2d: smart edge-adaptive sharpness
        if (macro.smartSharpness > 0f) {
            val blur = boxBlurFloat(rgb, w, h, 1)
            val sharpStrength = macro.smartSharpness * 1.5f
            val n = w * h
            var i = 0
            while (i < n) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                val base = i * 3
                val r = rgb[base]; val g = rgb[base + 1]; val b = rgb[base + 2]
                val edgeR = r - blur[base]
                val edgeG = g - blur[base + 1]
                val edgeB = b - blur[base + 2]
                val edgeMag = (abs(edgeR) + abs(edgeG) + abs(edgeB)) / 3f
                val weight = sharpStrength * (edgeMag * 4f).coerceIn(0f, 1f)
                rgb[base]     = (r + weight * edgeR).coerceAtLeast(0f)
                rgb[base + 1] = (g + weight * edgeG).coerceAtLeast(0f)
                rgb[base + 2] = (b + weight * edgeB).coerceAtLeast(0f)
                i++
            }
        }

        // Stage 3a: film grain — Poisson shot-noise model (HDR-safe).
        //
        // Real sensor noise scales as sqrt(signal) — photon counting follows a
        // Poisson distribution whose variance equals the mean. For HDR-safe
        // scene-referred grain we use a Gaussian approximation with
        //   σ(pixel) = grainAmount × sqrt(max(luma, ε))
        // The additive noise then grows naturally with highlights (matches how
        // film + digital sensors actually behave), and importantly never
        // crushes extended-range values into [0,1].
        //
        // `filmGrainUniformity` (0..1) controls how much the noise σ depends on
        // luma:
        //   uniformity=1 → constant σ (uniform "screen door" noise)
        //   uniformity=0 → pure sqrt(luma) shot noise
        if (macro.filmGrain > 0f) {
            val baseSigma = macro.filmGrain * (40f / 255f)
            val blockSize = (1 + macro.filmGrainSize * 3f).toInt().coerceIn(1, 4)
            val uniformity = 0f  // uniformity slider removed; pure shot-noise behavior.
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    // Single noise draw per block (chunky-grain look). Box-Muller
                    // pair would be more "Gaussian" but uniform[-1,1] is fine
                    // here — what matters is that it's signed and zero-mean.
                    val noise = (Random.nextFloat() * 2f - 1f)
                    for (dy in 0 until blockSize) {
                        for (dx in 0 until blockSize) {
                            val py = (y + dy).coerceIn(0, h - 1)
                            val px = (x + dx).coerceIn(0, w - 1)
                            val base = (py * w + px) * 3
                            val r = rgb[base]; val g = rgb[base + 1]; val b = rgb[base + 2]
                            val lum = (r * 0.2126f + g * 0.7152f + b * 0.0722f).coerceAtLeast(0f)
                            // sqrt(luma) for shot-noise scaling. Blend with the
                            // uniform-σ component via `uniformity`.
                            val shotSigma = kotlin.math.sqrt(lum + 1e-4f)
                            val sigma = baseSigma * (uniformity + (1f - uniformity) * shotSigma)
                            val g2 = noise * sigma
                            rgb[base]     = (r + g2).coerceAtLeast(0f)
                            rgb[base + 1] = (g + g2).coerceAtLeast(0f)
                            rgb[base + 2] = (b + g2).coerceAtLeast(0f)
                        }
                    }
                    x += blockSize
                }
                y += blockSize
            }
        }

        // Stage 3b: wash out (lift blacks + compress contrast). Already linear
        // and additive — HDR-safe by construction. Drop the legacy implicit
        // clip (we only sanity-clamp negatives).
        if (macro.filmGrainWashOut > 0f) {
            val lift  = macro.filmGrainWashOut * (60f / 255f)
            val scale = 1f - macro.filmGrainWashOut * 0.3f
            val n = w * h * 3
            var i = 0
            while (i < n) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                rgb[i] = (lift + rgb[i] * scale).coerceAtLeast(0f)
                i++
            }
        }
    }

    // ── Group C: radial vignette (float-domain mirror of applyVignette) ───────
    private suspend fun applyVignetteFloat(
        rgb: FloatArray,
        w: Int,
        h: Int,
        macro: UserMacro,
        masks: RawSegmentationMasks?,
    ) {
        if (macro.vignetteAmount == 0f) return

        val cx = macro.vignetteCenterX * w
        val cy = macro.vignetteCenterY * h
        val maxDist = maxOf(
            Math.hypot(cx.toDouble(),         cy.toDouble()),
            Math.hypot((w - cx).toDouble(),   cy.toDouble()),
            Math.hypot(cx.toDouble(),         (h - cy).toDouble()),
            Math.hypot((w - cx).toDouble(),   (h - cy).toDouble()),
        ).toFloat().coerceAtLeast(1f)

        val strength = (macro.vignetteAmount / 100f) * macro.vignetteIntensity
        val exponent = (2f - macro.vignetteFeather * 1.5f).coerceIn(0.5f, 2f)

        val maskSize = RawSegmentationMasks.MASK_SIZE
        val bgMask = if (masks != null && macro.vignetteSegmentation == SegmentTarget.Background)
            masks.backgroundMask else null

        // Per-pixel work is independent across rows. Fan-out to 4 workers.
        parallelRowBands(h) { y0, y1 ->
            for (y in y0 until y1) {
                if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
                val dy = y - cy
                val rowBase = y * w * 3
                for (x in 0 until w) {
                    val dx = x - cx
                    val normDist = (Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / maxDist)
                        .coerceIn(0f, 1f)
                    val distCurve = normDist.toDouble().pow(exponent.toDouble()).toFloat()
                    val rawFactor = (1f + strength * distCurve).coerceAtLeast(0f)

                    val segFactor = if (bgMask != null) {
                        val weight = sampleMask(bgMask, maskSize, w, h, x, y)
                        1f + (rawFactor - 1f) * weight
                    } else rawFactor

                    val base = rowBase + x * 3
                    val r = rgb[base]; val g = rgb[base + 1]; val b = rgb[base + 2]

                    val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(0f)
                    val effectWeight = when (macro.vignetteEffect) {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect.All            -> 1f
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect.HighlightsOnly ->
                            ((lum - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect.ShadowsOnly    ->
                            ((0.6f - lum) / 0.6f).coerceIn(0f, 1f)
                    }
                    val factor = 1f + (segFactor - 1f) * effectWeight

                    rgb[base]     = (r * factor).coerceAtLeast(0f)
                    rgb[base + 1] = (g * factor).coerceAtLeast(0f)
                    rgb[base + 2] = (b * factor).coerceAtLeast(0f)
                }
            }
        }
    }

    // ── Group D: 4-sided edge gradients with tint blending ───────────────────
    private suspend fun applyEdgeGradientsFloat(
        rgb: FloatArray,
        w: Int,
        h: Int,
        macro: UserMacro,
        masks: RawSegmentationMasks?,
    ) {
        val hasGradient = macro.gradientTopIntensity    != 0f ||
                          macro.gradientBottomIntensity != 0f ||
                          macro.gradientLeftIntensity   != 0f ||
                          macro.gradientRightIntensity  != 0f ||
                          macro.gradientTopTintLuminosity    > 0f ||
                          macro.gradientBottomTintLuminosity > 0f ||
                          macro.gradientLeftTintLuminosity   > 0f ||
                          macro.gradientRightTintLuminosity  > 0f ||
                          (macro.gradientTopEnable2    && (macro.gradientTopIntensity2    != 0f || macro.gradientTopTintLuminosity2    > 0f)) ||
                          (macro.gradientBottomEnable2 && (macro.gradientBottomIntensity2 != 0f || macro.gradientBottomTintLuminosity2 > 0f)) ||
                          (macro.gradientLeftEnable2   && (macro.gradientLeftIntensity2   != 0f || macro.gradientLeftTintLuminosity2   > 0f)) ||
                          (macro.gradientRightEnable2  && (macro.gradientRightIntensity2  != 0f || macro.gradientRightTintLuminosity2  > 0f))
        if (!hasGradient) return

        val angleRad = macro.gradientAngle * (Math.PI / 180.0).toFloat()
        val cosA = cos(angleRad); val sinA = sin(angleRad)
        val maskSize = RawSegmentationMasks.MASK_SIZE

        val needsBg = masks != null && (
            macro.gradientTopApplyTo    == SegmentTarget.Background ||
            macro.gradientBottomApplyTo == SegmentTarget.Background ||
            macro.gradientLeftApplyTo   == SegmentTarget.Background ||
            macro.gradientRightApplyTo  == SegmentTarget.Background
        )
        val bgMask = if (needsBg) masks!!.backgroundMask else null
        fun pickMask(target: SegmentTarget): FloatArray? = when {
            masks == null || target == SegmentTarget.All -> null
            target == SegmentTarget.Subject -> masks.subjectMask
            else -> bgMask
        }
        val topMask    = pickMask(macro.gradientTopApplyTo)
        val bottomMask = pickMask(macro.gradientBottomApplyTo)
        val leftMask   = pickMask(macro.gradientLeftApplyTo)
        val rightMask  = pickMask(macro.gradientRightApplyTo)

        // Pre-decode tint colors to linear floats once. The Color ints are sRGB-encoded;
        // for in-pipeline math we work in linear-light, so γ-decode here.
        fun decodeTintLinear(color: Int): Triple<Float, Float, Float> {
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8)  and 0xFF) / 255f
            val b = ( color         and 0xFF) / 255f
            return Triple(
                r.toDouble().pow(2.2).toFloat(),
                g.toDouble().pow(2.2).toFloat(),
                b.toDouble().pow(2.2).toFloat(),
            )
        }
        val (tTopR, tTopG, tTopB)         = decodeTintLinear(macro.gradientTopTintColor)
        val (tBotR, tBotG, tBotB)         = decodeTintLinear(macro.gradientBottomTintColor)
        val (tLeftR, tLeftG, tLeftB)      = decodeTintLinear(macro.gradientLeftTintColor)
        val (tRightR, tRightG, tRightB)   = decodeTintLinear(macro.gradientRightTintColor)
        // Second-tint colors (only used when the corresponding enable2 flag is on).
        val (tTopR2, tTopG2, tTopB2)         = decodeTintLinear(macro.gradientTopTintColor2)
        val (tBotR2, tBotG2, tBotB2)         = decodeTintLinear(macro.gradientBottomTintColor2)
        val (tLeftR2, tLeftG2, tLeftB2)      = decodeTintLinear(macro.gradientLeftTintColor2)
        val (tRightR2, tRightG2, tRightB2)   = decodeTintLinear(macro.gradientRightTintColor2)

        parallelRowBands(h) { y0, y1 ->
        for (y in y0 until y1) {
            if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
            val ny = if (h > 1) y.toFloat() / (h - 1) else 0.5f
            val rowBase = y * w * 3
            for (x in 0 until w) {
                val nx = if (w > 1) x.toFloat() / (w - 1) else 0.5f
                val dx = nx - 0.5f; val dy = ny - 0.5f
                val rx = (dx * cosA - dy * sinA + 0.5f).coerceIn(0f, 1f)
                val ry = (dx * sinA + dy * cosA + 0.5f).coerceIn(0f, 1f)

                val rawTop    = if (macro.gradientTopIntensity    != 0f || macro.gradientTopTintLuminosity    > 0f) edgeFalloff(ry,       macro.gradientTopLength,    macro.gradientTopFeather)    else 0f
                val rawBottom = if (macro.gradientBottomIntensity != 0f || macro.gradientBottomTintLuminosity > 0f) edgeFalloff(1f - ry,  macro.gradientBottomLength, macro.gradientBottomFeather) else 0f
                val rawLeft   = if (macro.gradientLeftIntensity   != 0f || macro.gradientLeftTintLuminosity   > 0f) edgeFalloff(rx,       macro.gradientLeftLength,   macro.gradientLeftFeather)   else 0f
                val rawRight  = if (macro.gradientRightIntensity  != 0f || macro.gradientRightTintLuminosity  > 0f) edgeFalloff(1f - rx,  macro.gradientRightLength,  macro.gradientRightFeather)  else 0f

                // Second-color falloffs — anchored at `length1`, fade over `length2`.
                val rawTop2    = if (macro.gradientTopEnable2    && (macro.gradientTopIntensity2    != 0f || macro.gradientTopTintLuminosity2    > 0f)) edgeFalloff2(ry,      macro.gradientTopLength,    macro.gradientTopLength2,    macro.gradientTopFeather2)    else 0f
                val rawBottom2 = if (macro.gradientBottomEnable2 && (macro.gradientBottomIntensity2 != 0f || macro.gradientBottomTintLuminosity2 > 0f)) edgeFalloff2(1f - ry, macro.gradientBottomLength, macro.gradientBottomLength2, macro.gradientBottomFeather2) else 0f
                val rawLeft2   = if (macro.gradientLeftEnable2   && (macro.gradientLeftIntensity2   != 0f || macro.gradientLeftTintLuminosity2   > 0f)) edgeFalloff2(rx,      macro.gradientLeftLength,   macro.gradientLeftLength2,   macro.gradientLeftFeather2)   else 0f
                val rawRight2  = if (macro.gradientRightEnable2  && (macro.gradientRightIntensity2  != 0f || macro.gradientRightTintLuminosity2  > 0f)) edgeFalloff2(1f - rx, macro.gradientRightLength,  macro.gradientRightLength2,  macro.gradientRightFeather2)  else 0f

                val topFalloff    = if (topMask    != null) rawTop    * sampleMask(topMask,    maskSize, w, h, x, y) else rawTop
                val bottomFalloff = if (bottomMask != null) rawBottom * sampleMask(bottomMask, maskSize, w, h, x, y) else rawBottom
                val leftFalloff   = if (leftMask   != null) rawLeft   * sampleMask(leftMask,   maskSize, w, h, x, y) else rawLeft
                val rightFalloff  = if (rightMask  != null) rawRight  * sampleMask(rightMask,  maskSize, w, h, x, y) else rawRight
                val topFalloff2    = if (topMask    != null) rawTop2    * sampleMask(topMask,    maskSize, w, h, x, y) else rawTop2
                val bottomFalloff2 = if (bottomMask != null) rawBottom2 * sampleMask(bottomMask, maskSize, w, h, x, y) else rawBottom2
                val leftFalloff2   = if (leftMask   != null) rawLeft2   * sampleMask(leftMask,   maskSize, w, h, x, y) else rawLeft2
                val rightFalloff2  = if (rightMask  != null) rawRight2  * sampleMask(rightMask,  maskSize, w, h, x, y) else rawRight2

                // First + second color contribute additively to the exposure (darkness).
                val darkness = (macro.gradientTopIntensity    * topFalloff +
                                macro.gradientBottomIntensity * bottomFalloff +
                                macro.gradientLeftIntensity   * leftFalloff +
                                macro.gradientRightIntensity  * rightFalloff +
                                macro.gradientTopIntensity2    * topFalloff2 +
                                macro.gradientBottomIntensity2 * bottomFalloff2 +
                                macro.gradientLeftIntensity2   * leftFalloff2 +
                                macro.gradientRightIntensity2  * rightFalloff2)

                val base = rowBase + x * 3
                var r = rgb[base]; var g = rgb[base + 1]; var b = rgb[base + 2]

                if (darkness != 0f) {
                    val stops = darkness * 1.4f
                    val factor = 2f.pow(-stops)
                    r *= factor; g *= factor; b *= factor
                }

                // Tint blends per side — cinematic light-leak screen + soft add
                // (mirrors GLSL/apply_macro blendGradTint Solid mode).
                fun blendLightLeak(
                    tintR: Float, tintG: Float, tintB: Float,
                    tintLum: Float, falloff: Float,
                ) {
                    if (tintLum <= 0f || falloff <= 0f) return
                    val w = (tintLum * falloff).coerceIn(0f, 1f)
                    fun scr(c: Float, t: Float) = 1f - (1f - c) * (1f - t)
                    val sR = scr(r, tintR); val sG = scr(g, tintG); val sB = scr(b, tintB)
                    val aR = r + tintR * (0.55f * w)
                    val aG = g + tintG * (0.55f * w)
                    val aB = b + tintB * (0.55f * w)
                    val lR = sR + (maxOf(sR, aR) - sR) * 0.35f
                    val lG = sG + (maxOf(sG, aG) - sG) * 0.35f
                    val lB = sB + (maxOf(sB, aB) - sB) * 0.35f
                    r = r + (lR - r) * w
                    g = g + (lG - g) * w
                    b = b + (lB - b) * w
                }
                blendLightLeak(tTopR,   tTopG,   tTopB,   macro.gradientTopTintLuminosity,    topFalloff)
                blendLightLeak(tBotR,   tBotG,   tBotB,   macro.gradientBottomTintLuminosity, bottomFalloff)
                blendLightLeak(tLeftR,  tLeftG,  tLeftB,  macro.gradientLeftTintLuminosity,   leftFalloff)
                blendLightLeak(tRightR, tRightG, tRightB, macro.gradientRightTintLuminosity,  rightFalloff)
                if (macro.gradientTopEnable2)    blendLightLeak(tTopR2,   tTopG2,   tTopB2,   macro.gradientTopTintLuminosity2,    topFalloff2)
                if (macro.gradientBottomEnable2) blendLightLeak(tBotR2,   tBotG2,   tBotB2,   macro.gradientBottomTintLuminosity2, bottomFalloff2)
                if (macro.gradientLeftEnable2)   blendLightLeak(tLeftR2,  tLeftG2,  tLeftB2,  macro.gradientLeftTintLuminosity2,   leftFalloff2)
                if (macro.gradientRightEnable2)  blendLightLeak(tRightR2, tRightG2, tRightB2, macro.gradientRightTintLuminosity2,  rightFalloff2)

                rgb[base]     = r.coerceAtLeast(0f)
                rgb[base + 1] = g.coerceAtLeast(0f)
                rgb[base + 2] = b.coerceAtLeast(0f)
            }
        }
        }
    }

    // ── Group E: brush-mask local adjustments ─────────────────────────────────
    private suspend fun applyMaskAdjustmentsFloat(
        rgb: FloatArray,
        w: Int,
        h: Int,
        macro: UserMacro,
        maskBitmap: Bitmap,
    ) {
        val hasMaskEffect = macro.maskBrightness != 0f || macro.maskContrast != 0f ||
                            macro.maskTemperature != 0 || macro.maskTint != 0f ||
                            macro.maskSaturation != 0f || macro.maskClarity != 0f ||
                            macro.maskTone.highlights != 0f || macro.maskTone.shadows != 0f ||
                            macro.maskTone.whites != 0f || macro.maskTone.blacks != 0f
        if (!hasMaskEffect) return

        val mw = maskBitmap.width; val mh = maskBitmap.height
        val maskPixels = IntArray(mw * mh)
        maskBitmap.getPixels(maskPixels, 0, mw, 0, 0, mw, mh)

        // Reuse the float channel curves with a synthetic mini-macro that maps
        // the maskBrightness/maskContrast/maskTemperature/maskTint into the
        // regular channel-curve parameters.
        //
        // Temperature mapping: `maskTemperature` is a Kelvin *delta* in
        // [-2000, +2000] where positive = warmer (slider UX convention),
        // while `whiteBalance` in the global Color tab is an *absolute* Kelvin
        // in [2000, 10000] with the same warmer-up convention centered on 5500.
        // `wbTintMultipliers` expects the absolute form. Convert: 0 → 0 (no
        // shift; only tint applies), nonzero → 5500 + delta. Without this
        // conversion +2000K was producing bluish output because the function
        // treated 2000 as "very cool sub-daylight" and shifted the opposite way.
        val miniMacro = UserMacro(
            exposure     = macro.maskBrightness / 100f,
            contrast     = macro.maskContrast,
            whiteBalance = if (macro.maskTemperature != 0) 5500 + macro.maskTemperature else 0,
            tint         = macro.maskTint,
            saturation   = macro.maskSaturation,
            // Tone regions inside the mask — folded through the same channel
            // curves as the global Tone tab so the CPU path mirrors the native
            // v3 mask tone (apply_macro.cpp applyToneRegionsP).
            highlights   = macro.maskTone.highlights,
            shadows      = macro.maskTone.shadows,
            whites       = macro.maskTone.whites,
            blacks       = macro.maskTone.blacks,
        )
        val curve = buildFloatChannelCurves(miniMacro)
        val satF = miniMacro.saturation / 100f
        val hasSat = satF != 0f
        val clarF = macro.maskClarity / 100f
        val blur5 = if (clarF != 0f) boxBlurFloat(rgb, w, h, 5) else null

        for (y in 0 until h) {
            if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
            val rowBase = y * w * 3
            for (x in 0 until w) {
                // Bilinear sample of the mask alpha at this pixel
                val mx  = x.toFloat() * (mw - 1) / (w - 1).coerceAtLeast(1)
                val my  = y.toFloat() * (mh - 1) / (h - 1).coerceAtLeast(1)
                val mx0 = mx.toInt().coerceIn(0, mw - 2)
                val my0 = my.toInt().coerceIn(0, mh - 2)
                val ddx = mx - mx0; val ddy = my - my0
                val a00 = (maskPixels[ my0      * mw + mx0    ] ushr 24) and 0xFF
                val a10 = (maskPixels[ my0      * mw + mx0 + 1] ushr 24) and 0xFF
                val a01 = (maskPixels[(my0 + 1) * mw + mx0    ] ushr 24) and 0xFF
                val a11 = (maskPixels[(my0 + 1) * mw + mx0 + 1] ushr 24) and 0xFF
                val weight = (a00 * (1f - ddx) * (1f - ddy) +
                              a10 * ddx        * (1f - ddy) +
                              a01 * (1f - ddx) * ddy        +
                              a11 * ddx        * ddy        ) / 255f
                if (weight <= 0f) continue

                val base = rowBase + x * 3
                val origR = rgb[base]; val origG = rgb[base + 1]; val origB = rgb[base + 2]
                var r = curve.evalR(origR)
                var g = curve.evalG(origG)
                var b = curve.evalB(origB)

                if (hasSat) {
                    // HDR-safe saturation: Rec.709 luma blend in linear light.
                    // Same model as Phase 1 sat/vib in [applyFloat] — no HSL
                    // round-trip, no [0,1] clamp, extended-range survives.
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val gain = 1f + satF
                    r = luma + (r - luma) * gain
                    g = luma + (g - luma) * gain
                    b = luma + (b - luma) * gain
                }

                if (blur5 != null) {
                    // Clarity = unsharp mask weighted by a mid-tone bell so the
                    // effect concentrates around lum=0.5. The bell uses lum in
                    // linear light; values above 1.0 produce a negative bell
                    // which would invert the effect, so clamp the **bell input**
                    // to [0,1] while leaving the actual r/g/b updates uncapped.
                    val lum = (r * 0.299f + g * 0.587f + b * 0.114f).coerceIn(0f, 1f)
                    val midZone = 4f * lum * (1f - lum)
                    val cw = clarF * midZone * 0.5f
                    r += cw * (r - blur5[base])
                    g += cw * (g - blur5[base + 1])
                    b += cw * (b - blur5[base + 2])
                }

                // Mix with original by the mask alpha weight
                rgb[base]     = (origR + (r - origR) * weight).coerceAtLeast(0f)
                rgb[base + 1] = (origG + (g - origG) * weight).coerceAtLeast(0f)
                rgb[base + 2] = (origB + (b - origB) * weight).coerceAtLeast(0f)
            }
        }
    }

    /**
     * Turn 1 → 2 → 3 progressively expanded the native-float pipeline.
     * Turn 3 ports the final stages (vignette / edge gradients / brush mask),
     * so this predicate now always returns false — the bridge path is dead
     * code retained only as a safety net.
     *
     * Returns true only if a future macro field is added without a float
     * implementation; the warning in [applyFloat] flags any such regression.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun hasNonGroupAAdjustments(macro: UserMacro): Boolean = false

    /**
     * Memory-bounded variant of [applyFloat] for large bitmaps. Processes the
     * pixel-local Group A stages (channel curves + saturation/vibrance + OkLab
     * HSL) in row bands so the working FloatArray stays ~17MB instead of the
     * 242MB whole-image alloc that OOMs at 20MP on 4GB phones.
     *
     * Group B (spatial NR / clarity / sharpness / texture / film grain) and
     * Group C/D/E (vignette / edge gradients / mask) are NOT banded because
     * they have spatial dependencies that don't tile cleanly. If any of those
     * fields are non-default we fall back to the whole-image path AFTER Group
     * A is already done in place — that means peak memory is `band buffer +
     * Group B scratch buffers`, not `whole image + everything`.
     *
     * Math is identical to [applyFloat]; the only difference is loop structure.
     */
    private suspend fun applyFloatBanded(
        neutral: Bitmap,
        macro: UserMacro,
        masks: RawSegmentationMasks?,
        maskBitmap: Bitmap?,
        releaseNeutral: Boolean = false,
    ): Bitmap = withContext(Dispatchers.Default) {
        val w = neutral.width
        val h = neutral.height
        val pixelCount = w * h

        // Read the whole FP16 bitmap into ONE DirectByteBuffer (161MB at 20MP).
        // The DirectByteBuffer lives in off-heap "direct" memory — does NOT
        // count against the JVM heap cap (verified API 26+). We process bands
        // directly via `asShortBuffer().get(offset, dst)` — no on-heap copy of
        // the full short array. JVM heap pressure during the loop is the
        // band-sized FloatArray (~17MB) plus the band's short scratch (~1MB).
        val srcBuf = java.nio.ByteBuffer.allocateDirect(pixelCount * 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        neutral.copyPixelsToBuffer(srcBuf)
        srcBuf.rewind()
        val srcShortView = srcBuf.asShortBuffer()

        // Build channel curves once.
        val curve = buildFloatChannelCurves(macro)

        val satF = macro.saturation / 100f
        val vibF = macro.vibrance   / 100f
        val hasHslRange = macro.hslRedHue != 0f || macro.hslRedSat != 0f || macro.hslRedLum != 0f ||
            macro.hslOrangeHue != 0f || macro.hslOrangeSat != 0f || macro.hslOrangeLum != 0f ||
            macro.hslYellowHue != 0f || macro.hslYellowSat != 0f || macro.hslYellowLum != 0f ||
            macro.hslGreenHue != 0f  || macro.hslGreenSat != 0f  || macro.hslGreenLum != 0f  ||
            macro.hslAquaHue != 0f   || macro.hslAquaSat != 0f   || macro.hslAquaLum != 0f   ||
            macro.hslBlueHue != 0f   || macro.hslBlueSat != 0f   || macro.hslBlueLum != 0f
        val hasHsl = satF != 0f || vibF != 0f || hasHslRange

        // Detect Group B/C/D/E activity — drives the decision to allocate the
        // whole-image FloatArray after Group A or not.
        val hasGroupBSpatial = macro.noiseReduction > 0f ||
            macro.luminanceNR > 0f || macro.colorNR > 0f ||
            macro.sharpness > 0f || macro.clarity != 0f ||
            macro.texture != 0f || macro.smartSharpness > 0f ||
            macro.filmGrain > 0f || macro.filmGrainWashOut > 0f
        val hasVignette = macro.vignetteAmount != 0f
        val hasGradient = macro.gradientTopIntensity > 0f ||
            macro.gradientBottomIntensity > 0f ||
            macro.gradientLeftIntensity > 0f ||
            macro.gradientRightIntensity > 0f
        val hasGroupBToE = hasGroupBSpatial || hasVignette || hasGradient || (maskBitmap != null)

        // Band Group A: 256 rows at a time (17MB FloatArray at 5496 wide).
        // Parallelized 2026-05-25: bands are independent (Group A is pixel-local),
        // so we fan out across 4 workers. Workers pull bands from an atomic
        // counter; each owns its own scratch arrays + a fresh ShortBuffer view
        // (ByteBuffer.asShortBuffer() has internal cursor state — not safe to
        // share across threads, but reading the same underlying memory through
        // separate views is fine since the byte ranges don't overlap).
        //
        // Expected speedup: 77s → ~22s on a Helio G99 (4 big cores). Verified
        // adb 2026-05-25 IMG_3913.CR2 BIT_16/P3 5496×3669 save.
        val bandRows = 256
        val bandPixels = bandRows * w
        val totalBands = (h + bandRows - 1) / bandRows
        val oneHalf = floatToHalfBitsLocal(1f)
        // Don't hold srcShortView from the outer scope — workers each create one.
        // (Kept a local reference earlier only for `srcShortView.put` write-back.)
        @Suppress("UNUSED_VARIABLE")
        val unusedView = srcShortView  // explicit drop hint

        // Atomic band-index dispenser. Each worker `getAndIncrement`s to claim
        // its next band.
        val nextBand = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            val workers = (0 until kotlin.math.min(4, totalBands)).map {
                async(Dispatchers.Default) {
                    // Per-worker scratch — never shared.
                    val bandFloats = FloatArray(bandPixels * 3)
                    val bandShorts = ShortArray(bandPixels * 4)
                    val okLab = FloatArray(3)
                    val okRgb = FloatArray(3)
                    val myShortView = srcBuf.asShortBuffer()

                    while (true) {
                        val bandIdx = nextBand.getAndIncrement()
                        if (bandIdx >= totalBands) break
                        currentCoroutineContext().ensureActive()

                        val y0 = bandIdx * bandRows
                        val rowsThisBand = minOf(bandRows, h - y0)
                        val pixelsThisBand = rowsThisBand * w
                        val srcShortOffset = y0 * w * 4

                        myShortView.position(srcShortOffset)
                        myShortView.get(bandShorts, 0, pixelsThisBand * 4)

                        // Decode this band: ShortArray → FloatArray (R/G/B only)
                        var srcIdx = 0
                        var dstIdx = 0
                        var p = 0
                        while (p < pixelsThisBand) {
                            bandFloats[dstIdx]     = halfBitsToFloatLocal(bandShorts[srcIdx])
                            bandFloats[dstIdx + 1] = halfBitsToFloatLocal(bandShorts[srcIdx + 1])
                            bandFloats[dstIdx + 2] = halfBitsToFloatLocal(bandShorts[srcIdx + 2])
                            srcIdx += 4
                            dstIdx += 3
                            p++
                        }

                        // Apply Group A pixel-local pipeline.
                        var i = 0
                        while (i < pixelsThisBand) {
                            if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                            val base = i * 3
                            var r = curve.evalR(bandFloats[base])
                            var g = curve.evalG(bandFloats[base + 1])
                            var b = curve.evalB(bandFloats[base + 2])

                            if (hasHsl) {
                                if (satF != 0f || vibF != 0f) {
                                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                    val absMax = kotlin.math.max(
                                        kotlin.math.max(kotlin.math.abs(r - luma), kotlin.math.abs(g - luma)),
                                        kotlin.math.abs(b - luma),
                                    )
                                    val chromaNorm = if (luma > 1e-4f) (absMax / luma).coerceIn(0f, 1f) else 0f
                                    val gain = (1f + satF) * (1f + vibF * (1f - chromaNorm))
                                    r = luma + (r - luma) * gain
                                    g = luma + (g - luma) * gain
                                    b = luma + (b - luma) * gain
                                }

                                if (hasHslRange) {
                                    OkLabMath.linearSrgbToOklab(r, g, b, okLab)
                                    var L = okLab[0]
                                    var a = okLab[1]
                                    var bb = okLab[2]

                                    val C = OkLabMath.chroma(a, bb)
                                    val hDeg = if (C > 1e-6f) {
                                        val deg = (OkLabMath.hueRadians(a, bb) * (180f / kotlin.math.PI.toFloat()))
                                        if (deg < 0f) deg + 360f else deg
                                    } else 0f

                                    val rW = hueRangeWeight(hDeg, 0f,   65f)
                                    val oW = hueRangeWeight(hDeg, 30f,  40f)
                                    val yW = hueRangeWeight(hDeg, 60f,  50f)
                                    val gW = hueRangeWeight(hDeg, 120f, 85f)
                                    val aW = hueRangeWeight(hDeg, 180f, 60f)
                                    val bW = hueRangeWeight(hDeg, 240f, 85f)
                                    val totalW = (rW + oW + yW + gW + aW + bW).coerceAtLeast(1e-6f)

                                    val chromaGate = smoothstep01(0.005f, 0.04f, C)

                                    val hShiftDeg = (macro.hslRedHue*rW + macro.hslOrangeHue*oW + macro.hslYellowHue*yW +
                                        macro.hslGreenHue*gW + macro.hslAquaHue*aW + macro.hslBlueHue*bW) / totalW
                                    val sShift = (macro.hslRedSat*rW + macro.hslOrangeSat*oW + macro.hslYellowSat*yW +
                                        macro.hslGreenSat*gW + macro.hslAquaSat*aW + macro.hslBlueSat*bW) / totalW / 100f
                                    val lShift = (macro.hslRedLum*rW + macro.hslOrangeLum*oW + macro.hslYellowLum*yW +
                                        macro.hslGreenLum*gW + macro.hslAquaLum*aW + macro.hslBlueLum*bW) / totalW / 100f

                                    if (hShiftDeg != 0f && chromaGate > 0f) {
                                        val theta = hShiftDeg * chromaGate * (kotlin.math.PI.toFloat() / 180f)
                                        val cs = kotlin.math.cos(theta)
                                        val sn = kotlin.math.sin(theta)
                                        val newA = a * cs - bb * sn
                                        val newB = a * sn + bb * cs
                                        a = newA
                                        bb = newB
                                    }
                                    if (sShift != 0f && chromaGate > 0f) {
                                        val sScale = (1f + sShift * chromaGate).coerceAtLeast(0f)
                                        a *= sScale
                                        bb *= sScale
                                    }
                                    if (lShift != 0f) {
                                        L = (L + lShift * chromaGate).coerceAtLeast(0f)
                                    }

                                    OkLabMath.oklabToLinearSrgb(L, a, bb, okRgb)
                                    r = okRgb[0]
                                    g = okRgb[1]
                                    b = okRgb[2]
                                }
                            }

                            bandFloats[base    ] = if (r.isFinite()) r.coerceAtLeast(0f) else 0f
                            bandFloats[base + 1] = if (g.isFinite()) g.coerceAtLeast(0f) else 0f
                            bandFloats[base + 2] = if (b.isFinite()) b.coerceAtLeast(0f) else 0f
                            i++
                        }

                        // Encode FloatArray → bandShorts.
                        srcIdx = 0
                        dstIdx = 0
                        p = 0
                        while (p < pixelsThisBand) {
                            bandShorts[srcIdx]     = floatToHalfBitsLocal(bandFloats[dstIdx])
                            bandShorts[srcIdx + 1] = floatToHalfBitsLocal(bandFloats[dstIdx + 1])
                            bandShorts[srcIdx + 2] = floatToHalfBitsLocal(bandFloats[dstIdx + 2])
                            bandShorts[srcIdx + 3] = oneHalf
                            srcIdx += 4
                            dstIdx += 3
                            p++
                        }
                        // Write back to the shared DirectByteBuffer through our
                        // own view. Non-overlapping byte ranges, no race.
                        myShortView.position(srcShortOffset)
                        myShortView.put(bandShorts, 0, pixelsThisBand * 4)
                    }
                }
            }
            workers.awaitAll()
        }

        // ── Group B/C/D/E ────────────────────────────────────────────────────
        // Common case (no spatial / no vignette / no gradient / no mask) → no work.
        // Build the FP16 result bitmap directly from srcBuf and return it.
        if (!hasGroupBToE) {
            srcBuf.rewind()
            val resultBmp = Bitmap.createBitmap(
                w, h, Bitmap.Config.RGBA_F16, true,
                neutral.colorSpace
                    ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB),
            )
            resultBmp.copyPixelsFromBuffer(srcBuf)
            return@withContext resultBmp
        }

        // Spatial / vignette / gradient / mask path. Goal: 16-bit precision
        // end-to-end without OOM on a 20MP (242 MB FloatArray) image.
        //
        // Memory accounting on a 512 MB Dalvik heap cap:
        //   • srcBuf:        161 MB direct (off-heap) — pinned via srcShortView ref
        //   • Group A scratch: ~70 MB total across 4 workers (was just released)
        //   • FloatArray:    242 MB on-heap (target allocation)
        //   • neutral:       native bitmap (off-heap)
        //
        // Previously we also built a 161 MB resultBmp BEFORE the FloatArray alloc,
        // pushing peak heap past the cap → OOM → 8-bit bridge fallback → banding.
        //
        // New plan: read FloatArray directly from srcBuf via halfBitsToFloat,
        // never materialize an intermediate FP16 bitmap. The FP16 bitmap is
        // built ONCE at the very end from writeFloatPixelsRgbToFp16. Peak
        // on-heap is now just the FloatArray (242 MB) + scratch shorts — fits
        // comfortably under the cap.
        @Suppress("UNUSED_VALUE")
        var dropRefs: Any? = srcShortView  // explicit drop hint
        dropRefs = null

        // Free the source FP16 bitmap BEFORE allocating the 242 MB FloatArray.
        // Its pixels are already in `srcBuf` (line 1019), so we never read from
        // `neutral` again. On a 20 MP save the bitmap is ~161 MB of native /
        // graphics memory; recycling it frees the OS-level region the GC was
        // refusing to consider for the heap.
        //
        // Previously, with `neutral` still alive, the 242 MB allocation OOM'd
        // (50 MB free + 188 MB heap-grow headroom = only 238 MB available).
        // Recycling frees ~161 MB → total ~400 MB available → allocation fits.
        // Verified adb 2026-05-25 (save: 50 s with 8-bit bridge → expected ~10 s
        // with float path completing).
        //
        // Canvas behavior during save: the canvas displays `previewBitmap`
        // (screen-fit FP16) which is a separate bitmap and unaffected. The
        // idle full-res quality upgrade is gone for ~5 s during save, then
        // restored on the next `renderIdleFullRes` tick.
        // Snapshot any field we still need from `neutral` BEFORE optionally
        // recycling it — the helpers below reference `neutral.colorSpace`.
        val neutralColorSpace = neutral.colorSpace

        if (releaseNeutral && !neutral.isRecycled) {
            android.util.Log.i(
                "MacroProcessor",
                "applyFloatBanded: recycling source FP16 bitmap (${neutral.width}x${neutral.height}) to free heap for FloatArray",
            )
            neutral.recycle()
        }
        System.gc()
        System.runFinalization()
        System.gc()
        kotlinx.coroutines.delay(150L)

        // Heap budget pre-check: skip the float path entirely if the FloatArray
        // (134 MB for 11 MP, 242 MB for 20 MP) clearly cannot fit. The runtime
        // would retry the allocation 3 times (5 s each) before throwing OOM —
        // a 15 s wait per save with no benefit.
        //
        // Budget math: we need a CONTIGUOUS heap allocation. The relevant figure
        // is `growthLimit - currentFootprint`, i.e. how much the heap can grow
        // from where it sits NOW. `Runtime.totalMemory()` returns the current
        // footprint, `maxMemory()` returns the growth limit. `freeMemory()` is
        // misleading — it includes already-reserved-but-unused space that's
        // typically too fragmented for a 134 MB contiguous allocation.
        //
        // Verified adb 2026-05-25: the previous check used
        // `maxMemory - (total - free)` which reports 408 MB available while the
        // actual contiguous headroom was 153 MB. The 134 MB alloc still tried
        // and failed because of fragmentation. The new metric reports the
        // accurate "until-OOM" number that matches what the runtime's own
        // OutOfMemoryError message prints.
        val floatArrayBytes = pixelCount.toLong() * 3L * 4L
        val rt = Runtime.getRuntime()
        val growthHeadroom = rt.maxMemory() - rt.totalMemory()
        // Add the per-allocation slack the runtime tries to keep — empirically
        // ~16 MB on Android 15. If headroom is below this we definitely OOM.
        val canFitFloatPath = growthHeadroom > floatArrayBytes + 16L * 1024 * 1024
        android.util.Log.i(
            "MacroProcessor",
            "applyFloatBanded: heap budget check growthHeadroom=${growthHeadroom / (1024 * 1024)}MB needs=${(floatArrayBytes + 16L * 1024 * 1024) / (1024 * 1024)}MB → ${if (canFitFloatPath) "FLOAT PATH" else "SKIP TO BRIDGE"}",
        )

        if (canFitFloatPath) {
            try {
                // Read FloatArray directly from the srcBuf direct buffer (skips
                // the 161 MB resultBmp intermediate).
                val rgb = readFloatPixelsRgbFromShortBuffer(srcBuf, w, h)
                applyVignetteFloat(rgb, w, h, macro, masks)
                applyEdgeGradientsFloat(rgb, w, h, macro, masks)
                if (maskBitmap != null) applyMaskAdjustmentsFloat(rgb, w, h, macro, maskBitmap)
                if (hasGroupBSpatial) {
                    applyGroupBSpatialFloat(rgb, w, h, macro)
                }
                android.util.Log.i(
                    "MacroProcessor",
                    "applyFloatBanded: Group B/C/D/E completed in 16-bit float (${pixelCount / 1_000_000}MP)",
                )
                return@withContext writeFloatPixelsRgbToFp16(rgb, w, h, neutralColorSpace)
            } catch (oom: OutOfMemoryError) {
                android.util.Log.w(
                    "MacroProcessor",
                    "applyFloatBanded: float path OOM at ${pixelCount / 1_000_000}MP — falling back to 8-bit bridge (will posterize)",
                    oom,
                )
                // Fall through to bridge.
            }
        }

        // Bridge fallback needs a real FP16 bitmap as the downsample source.
        srcBuf.rewind()
        // Sanity-check: srcBuf should contain Group-A-processed FP16 pixels at
        // this point. If it's all-zero, the saved file will be black. Log a
        // mid-pixel sample so we can spot the bug in adb.
        run {
            val mid = (pixelCount / 2) * 8
            val r = srcBuf.getShort(mid).toInt() and 0xFFFF
            val g = srcBuf.getShort(mid + 2).toInt() and 0xFFFF
            val b = srcBuf.getShort(mid + 4).toInt() and 0xFFFF
            android.util.Log.i(
                "MacroProcessor",
                "applyFloatBanded: bridge entry srcBuf mid-pixel halfBits R=$r G=$g B=$b (all-zero → black bug)",
            )
            srcBuf.rewind()
        }
        val resultBmp = Bitmap.createBitmap(
            w, h, Bitmap.Config.RGBA_F16, true,
            neutralColorSpace
                ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB),
        )
        resultBmp.copyPixelsFromBuffer(srcBuf)

        // 8-bit bridge fallback — posterizes 16-bit gradients. Only fires when
        // the heap genuinely can't fit the 242 MB FloatArray even after GC.
        // Group A is already baked into resultBmp's FP16 pixels; downsample,
        // run Group B/C/D/E via applyInt with a "spatial-only" macro (Group A
        // fields zeroed so applyInt doesn't double-apply them), then re-
        // promote to FP16.
        // 8-bit bridge with strict guarantee: never lose the Group-A pixels.
        // Pre-fix bug (adb 2026-05-25): if `applyInt` OOM'd, the `resultBmp`
        // had ALREADY been recycled at line 1344, so the getOrElse fallback
        // hit `resultBmp.takeIf { !it.isRecycled }` → null → freshly-created
        // empty bitmap → BLACK saved file.
        //
        // New flow:
        //   1. Build `rgb8` (an ARGB_8888 copy of resultBmp). If THAT OOMs,
        //      keep resultBmp and bail with it.
        //   2. Run applyInt on `rgb8`. If that OOMs, bail with `rgb8`
        //      upsampled to FP16 (Group A only but 8-bit-quantized).
        //   3. On success: recycle both resultBmp and rgb8 (or processed if
        //      applyInt returned a fresh bitmap), return the spatial result.
        // Bridge memory budget pre-check using the same growthHeadroom metric
        // as the float-path check above. applyInt allocates IntArray(w*h) =
        // pixelCount * 4 bytes for getPixels.
        val intArrayBytes = pixelCount.toLong() * 4L
        val bridgeHeadroom = rt.maxMemory() - rt.totalMemory()
        if (bridgeHeadroom < intArrayBytes + 8L * 1024 * 1024) {
            android.util.Log.w(
                "MacroProcessor",
                "applyFloatBanded: skipping 8-bit bridge — growthHeadroom=${bridgeHeadroom / (1024 * 1024)}MB < ${(intArrayBytes + 8L * 1024 * 1024) / (1024 * 1024)}MB needed. Returning Group A only.",
            )
            return@withContext resultBmp
        }

        var rgb8: Bitmap? = null
        return@withContext runCatching {
            rgb8 = downsample16To8(resultBmp)
            val src8 = rgb8 ?: throw OutOfMemoryError("downsample16To8 returned null")
            val spatialOnlyMacro = macro.copy(
                exposure = 0f, contrast = 0f, highlights = 0f, shadows = 0f,
                whites = 0f, blacks = 0f,
                saturation = 0f, vibrance = 0f,
                hslRedHue = 0f, hslRedSat = 0f, hslRedLum = 0f,
                hslOrangeHue = 0f, hslOrangeSat = 0f, hslOrangeLum = 0f,
                hslYellowHue = 0f, hslYellowSat = 0f, hslYellowLum = 0f,
                hslGreenHue = 0f, hslGreenSat = 0f, hslGreenLum = 0f,
                hslAquaHue = 0f, hslAquaSat = 0f, hslAquaLum = 0f,
                hslBlueHue = 0f, hslBlueSat = 0f, hslBlueLum = 0f,
                whiteBalance = 0, tint = 0f,
            )
            val processed = applyInt(src8, spatialOnlyMacro, masks, maskBitmap)
            // applyInt completed without OOM — safe to recycle the inputs.
            resultBmp.takeIf { !it.isRecycled }?.recycle()
            if (src8 !== processed) src8.recycle()
            rgb8 = null  // ownership transferred to `processed`
            upsample8To16(processed, neutralColorSpace)
        }.getOrElse { e ->
            android.util.Log.w(
                "MacroProcessor",
                "applyFloatBanded: bridge also failed; returning Group A only",
                e,
            )
            // Recovery priority:
            //   1. resultBmp still alive → return the FP16 Group-A result (best)
            //   2. rgb8 alive → upsample 8-bit Group-A back to FP16
            //   3. nothing alive → return an empty FP16 (shouldn't happen now)
            val recovered = resultBmp.takeIf { !it.isRecycled }
                ?: rgb8?.takeIf { !it.isRecycled }?.let { upsample8To16(it, neutralColorSpace) }
                ?: Bitmap.createBitmap(
                    w, h, Bitmap.Config.RGBA_F16, true,
                    neutralColorSpace
                        ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB),
                )
            android.util.Log.i(
                "MacroProcessor",
                "applyFloatBanded: recovered=${recovered.width}x${recovered.height} config=${recovered.config} (resultBmpAlive=${!resultBmp.isRecycled} rgb8Alive=${rgb8 != null && !rgb8!!.isRecycled})",
            )
            recovered
        }
    }

    /**
     * Read RGB samples from an `RGBA_F16` bitmap into a tightly-packed `FloatArray`
     * (3 floats per pixel, R, G, B). Each FP16 sample is decoded to a full Float;
     * alpha is discarded.
     */
    private suspend fun readFloatPixelsRgb(bitmap: Bitmap, w: Int, h: Int): FloatArray {
        val pixelCount = w * h
        val buf = java.nio.ByteBuffer.allocateDirect(pixelCount * 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bitmap.copyPixelsToBuffer(buf)
        buf.rewind()
        // Bulk-read every half-float into a JVM ShortArray in one native copy.
        // Replaces pixelCount * 4 individual `ByteBuffer.short` calls — each of
        // which crosses an NIO/JNI boundary with bounds checks.
        val srcShorts = ShortArray(pixelCount * 4)
        buf.asShortBuffer().get(srcShorts)
        val out = FloatArray(pixelCount * 3)
        var oi = 0
        var si = 0
        var i = 0
        while (i < pixelCount) {
            if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
            out[oi]     = halfBitsToFloatLocal(srcShorts[si])
            out[oi + 1] = halfBitsToFloatLocal(srcShorts[si + 1])
            out[oi + 2] = halfBitsToFloatLocal(srcShorts[si + 2])
            // srcShorts[si + 3] = alpha — discarded
            oi += 3
            si += 4
            i++
        }
        return out
    }

    /**
     * Variant that reads half-floats directly from an existing direct
     * ByteBuffer (e.g. the Group A output `srcBuf`), skipping the
     * `Bitmap.createBitmap` + `copyPixelsFromBuffer` round-trip. Saves 161 MB
     * of peak heap on a 20MP save by avoiding the intermediate FP16 bitmap.
     */
    private suspend fun readFloatPixelsRgbFromShortBuffer(
        srcBuf: java.nio.ByteBuffer,
        w: Int,
        h: Int,
    ): FloatArray {
        val pixelCount = w * h
        srcBuf.rewind()
        val srcShorts = ShortArray(pixelCount * 4)
        srcBuf.asShortBuffer().get(srcShorts)
        val out = FloatArray(pixelCount * 3)
        var oi = 0
        var si = 0
        var i = 0
        while (i < pixelCount) {
            if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
            out[oi]     = halfBitsToFloatLocal(srcShorts[si])
            out[oi + 1] = halfBitsToFloatLocal(srcShorts[si + 1])
            out[oi + 2] = halfBitsToFloatLocal(srcShorts[si + 2])
            oi += 3
            si += 4
            i++
        }
        return out
    }

    /**
     * Build an `RGBA_F16` bitmap from a packed RGB float array. Each float is
     * encoded as a half-float (little-endian); alpha set to 1.0. The output
     * bitmap is tagged with [colorSpace] if non-null, otherwise the platform
     * default for RGBA_F16 (`LINEAR_EXTENDED_SRGB`).
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private suspend fun writeFloatPixelsRgbToFp16(
        rgb: FloatArray,
        w: Int,
        h: Int,
        colorSpace: android.graphics.ColorSpace?,
    ): Bitmap {
        val pixelCount = w * h
        val out = java.nio.ByteBuffer.allocateDirect(pixelCount * 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val oneHalf = floatToHalfBitsLocal(1f)
        // Bulk-write: stage every half-float in a JVM ShortArray, then one
        // native put() into the direct buffer. Replaces pixelCount * 4 individual
        // `out.putShort` calls.
        val dstShorts = ShortArray(pixelCount * 4)
        var base = 0
        var si = 0
        var i = 0
        while (i < pixelCount) {
            if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
            dstShorts[si]     = floatToHalfBitsLocal(rgb[base])
            dstShorts[si + 1] = floatToHalfBitsLocal(rgb[base + 1])
            dstShorts[si + 2] = floatToHalfBitsLocal(rgb[base + 2])
            dstShorts[si + 3] = oneHalf
            base += 3
            si += 4
            i++
        }
        out.asShortBuffer().put(dstShorts)
        out.rewind()
        val bmp = Bitmap.createBitmap(
            w, h, Bitmap.Config.RGBA_F16, true,
            colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB),
        )
        bmp.copyPixelsFromBuffer(out)
        return bmp
    }

    // ── Float-domain box blur (parallels boxBlurPixels) ──────────────────────
    //
    // Separable two-pass blur over a tightly-packed `FloatArray` of RGB triples
    // (3 floats per pixel, no alpha). Mirrors the math of `boxBlurPixels` so the
    // float and 8-bit Group B stages produce visually equivalent output. Clamped
    // border handling: reads outside `[0, w-1]` / `[0, h-1]` clamp to the nearest
    // valid index, same as the IntArray path.
    private suspend fun boxBlurFloat(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val d = 2 * radius + 1
        val invD = 1f / d
        val tmp = FloatArray(w * h * 3)

        // Horizontal pass — each row reads only its own row of [src], writes
        // only its own row of [tmp]. Row-parallel.
        parallelRowBands(h) { y0, y1 ->
            for (y in y0 until y1) {
                if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
                val row = y * w * 3
                for (x in 0 until w) {
                    var sumR = 0f; var sumG = 0f; var sumB = 0f
                    for (k in -radius..radius) {
                        val xi = (x + k).coerceIn(0, w - 1) * 3
                        sumR += src[row + xi]
                        sumG += src[row + xi + 1]
                        sumB += src[row + xi + 2]
                    }
                    val outIdx = row + x * 3
                    tmp[outIdx]     = sumR * invD
                    tmp[outIdx + 1] = sumG * invD
                    tmp[outIdx + 2] = sumB * invD
                }
            }
        }

        // Vertical pass — reads from [tmp] (any row), writes to [out] only own
        // row. Workers only write to their own row band, so no write race;
        // reads outside the band (rows y±radius from band boundary) are fine
        // because the horizontal pass completed before this fan-out starts.
        val out = FloatArray(w * h * 3)
        parallelRowBands(h) { y0, y1 ->
            for (y in y0 until y1) {
                if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
                for (x in 0 until w) {
                    var sumR = 0f; var sumG = 0f; var sumB = 0f
                    for (k in -radius..radius) {
                        val yi = (y + k).coerceIn(0, h - 1) * w * 3
                        sumR += tmp[yi + x * 3]
                        sumG += tmp[yi + x * 3 + 1]
                        sumB += tmp[yi + x * 3 + 2]
                    }
                    val outIdx = (y * w + x) * 3
                    out[outIdx]     = sumR * invD
                    out[outIdx + 1] = sumG * invD
                    out[outIdx + 2] = sumB * invD
                }
            }
        }
        return out
    }

    // ── Float channel curves (parallels buildChannelLuts / buildLut) ──────────
    //
    // The 8-bit pipeline precomputes a 256-entry IntArray per channel because
    // it's iterating over IntArray pixels. The float pipeline operates per-pixel
    // directly — but the curve is monotonic and called millions of times per
    // bitmap, so we keep a 4096-entry FloatArray lookup table per channel for
    // speed. Each sample is clamped to [0, 1] before lookup; values above 1.0
    // (extended-range) are passed through linearly above the LUT clamp.

    private class FloatChannelCurves(
        val rTable: FloatArray,
        val gTable: FloatArray,
        val bTable: FloatArray,
    ) {
        // Slope at v=1 for each channel, captured from the last two LUT entries.
        // Used to extrapolate past v=1 with the curve's actual derivative, so
        // an exposure of +2 stops at v=2.0 yields ~8.0 instead of pass-through.
        // Computed once at construction; cheap eval afterward.
        private val rSlopeTop = topSlope(rTable)
        private val gSlopeTop = topSlope(gTable)
        private val bSlopeTop = topSlope(bTable)

        fun evalR(v: Float): Float = sample(rTable, rSlopeTop, v)
        fun evalG(v: Float): Float = sample(gTable, gSlopeTop, v)
        fun evalB(v: Float): Float = sample(bTable, bSlopeTop, v)

        private fun sample(table: FloatArray, slopeAtTop: Float, v: Float): Float {
            // Negative inputs: extrapolate the lower-end slope as well. We treat
            // the curve as linear from the first two LUT entries past v=0 so the
            // extrapolation is consistent with the upper end.
            val n = table.size
            if (v <= 0f) {
                val slopeBot = (table[1] - table[0]) * (n - 1)
                return table[0] + v * slopeBot
            }
            if (v >= 1f) {
                // Above 1.0 (extended-range half-float). Linearly extrapolate
                // using the curve's true slope at v=1 — keeps highlight gain
                // matching the slider's intended response (e.g. +2EV gives 4× at
                // v=1, so v=2 → ~8×, not pass-through).
                return table[n - 1] + (v - 1f) * slopeAtTop
            }
            val pos = v * (n - 1)
            val i = pos.toInt()
            val t = pos - i
            return table[i] * (1f - t) + table[i + 1] * t
        }

        private companion object {
            /** Slope at v=1, in "value per unit-of-v" — i.e. derivative wrt v. */
            fun topSlope(table: FloatArray): Float {
                val n = table.size
                return (table[n - 1] - table[n - 2]) * (n - 1)
            }
        }
    }

    private fun buildFloatChannelCurves(macro: UserMacro): FloatChannelCurves {
        val (rMult, gMult, bMult) = wbTintMultipliers(macro.whiteBalance, macro.tint)
        val expScale  = 2f.pow(macro.exposure)
        val highlights = macro.highlights / 100f
        val shadows    = macro.shadows    / 100f
        val whites     = macro.whites     / 100f
        val blacks     = macro.blacks     / 100f
        val contrast   = macro.contrast   / 100f
        val dehaze     = macro.dehaze     / 100f

        val rTable = buildFloatChannelLut(expScale * rMult, highlights, shadows, whites, blacks, contrast, dehaze)
        val gTable = buildFloatChannelLut(expScale * gMult, highlights, shadows, whites, blacks, contrast, dehaze)
        val bTable = buildFloatChannelLut(expScale * bMult, highlights, shadows, whites, blacks, contrast, dehaze)

        // Apply tone curves on top (channel 0 = Luminance / all, 1 = R, 2 = G, 3 = B).
        val defaultCurve = UserMacro.DEFAULT_CURVE_POINTS[0]
        val curveL = macro.toneCurvePoints.getOrNull(0)
        val curveR = macro.toneCurvePoints.getOrNull(1)
        val curveG = macro.toneCurvePoints.getOrNull(2)
        val curveB = macro.toneCurvePoints.getOrNull(3)
        var rt = rTable; var gt = gTable; var bt = bTable
        if (curveL != null && curveL != defaultCurve) {
            rt = applyToneCurveToFloatLut(rt, curveL)
            gt = applyToneCurveToFloatLut(gt, curveL)
            bt = applyToneCurveToFloatLut(bt, curveL)
        }
        if (curveR != null && curveR != defaultCurve) rt = applyToneCurveToFloatLut(rt, curveR)
        if (curveG != null && curveG != defaultCurve) gt = applyToneCurveToFloatLut(gt, curveG)
        if (curveB != null && curveB != defaultCurve) bt = applyToneCurveToFloatLut(bt, curveB)

        return FloatChannelCurves(rt, gt, bt)
    }

    /**
     * Build a 4096-entry float LUT matching the 8-bit [buildLut] math, but the
     * input is **linear-light** [0,1] (not gamma-encoded) and the output is also
     * linear-light. The 8-bit pipeline does γ-encode/decode inside the curve
     * because its samples are gamma-encoded sRGB; the FP16 pipeline's samples are
     * already linear, so we skip those γ steps.
     *
     * The shapes of highlights/shadows/whites/blacks/contrast remain the same so
     * the slider feel matches the 8-bit path.
     */
    private fun buildFloatChannelLut(
        channelScale: Float,
        highlights:   Float,
        shadows:      Float,
        whites:       Float,
        blacks:       Float,
        contrast:     Float,
        dehaze:       Float,
    ): FloatArray {
        val n = 4096
        val out = FloatArray(n)
        for (i in 0 until n) {
            // Input is already linear; the 8-bit path's `(i/255)^2.2` decode
            // simulates that transformation. Here we start linear directly.
            var L = i / (n - 1).toFloat()
            L *= channelScale
            if (dehaze != 0f) {
                val bell = 4f * L * (1f - L)
                L -= dehaze * 0.15f * bell
            }
            if (highlights != 0f) {
                // The 8-bit path uses gamma-space thresholding via pow(L, 1/2.2).
                // We replicate the same perceptual threshold here for slider parity.
                val Lg = L.coerceAtLeast(0f).toDouble().pow(1.0 / 2.2).toFloat()
                val t = ((Lg - 0.5f) / 0.5f).coerceIn(0f, 1f)
                L += highlights * 0.5f * t * t
            }
            if (shadows != 0f) {
                val Lg = L.coerceAtLeast(0f).toDouble().pow(1.0 / 2.2).toFloat()
                val t = ((0.5f - Lg) / 0.5f).coerceIn(0f, 1f)
                L += shadows * 0.4f * t * t * L
            }
            if (whites != 0f || blacks != 0f) {
                // HDR-safe: whites/blacks act as a pure linear remap. Both can
                // push values past 1.0 (whites>0) or below 0 (blacks<0) without
                // a hard clip — the FP16 working buffer carries the extended
                // range. We only clamp at the very end via `coerceAtLeast(0f)`.
                val wp = (1f - whites * 0.25f).coerceAtLeast(0.1f)
                val bp = (blacks * 0.12f).coerceIn(-0.15f, 0.15f)
                L = L / wp
                L = bp + L * (1f - bp)
            }
            if (contrast != 0f) {
                // S-curve contrast: gentle past v=1 because (1-L) goes negative,
                // pulling the curve back toward the diagonal at high values —
                // which is exactly what we want (no runaway gain on highlights).
                L = L + contrast * 0.5f * L * (1f - L) * (2f * L - 1f)
            }
            out[i] = L.coerceAtLeast(0f)
        }
        return out
    }

    /**
     * Apply a 5-point Catmull-Rom tone curve to a float LUT. Same math as
     * [applyToneCurveToLut] but the input/output stay in linear-light. Input
     * values past 1.0 are mapped via extrapolation in [FloatChannelCurves.sample],
     * so we only need the curve to define behavior on `[0, 1]` here.
     */
    private fun applyToneCurveToFloatLut(lut: FloatArray, points: List<Float>): FloatArray {
        val ys = points.toFloatArray()
        val out = FloatArray(lut.size)
        for (i in lut.indices) {
            // Sample the curve at lut[i] without clipping the input — the
            // Catmull-Rom interpolator is well-defined past 1.0 too (it just
            // continues the polynomial). Output sanity-clamped to non-negative.
            out[i] = interpolateCurve(ys, lut[i]).coerceAtLeast(0f)
        }
        return out
    }

    // ── Half-float ↔ Float (parallel of WideGamutConverter helpers) ───────────
    //
    // Inlined so the float-mode pixel loop doesn't pay a static-method call cost.
    // Same IEEE 754 binary16 decoding as `WideGamutConverter.float16ToFloat`.

    private fun halfBitsToFloatLocal(s: Short): Float {
        val bits = s.toInt() and 0xFFFF
        val sign = (bits ushr 15) and 0x1
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        val signI = sign shl 31
        return when {
            exp == 0 && mant == 0 -> java.lang.Float.intBitsToFloat(signI)
            exp == 0 -> {
                var m = mant
                var e = -14
                while ((m and 0x400) == 0) { m = m shl 1; e-- }
                m = m and 0x3FF
                java.lang.Float.intBitsToFloat(signI or ((e + 127) shl 23) or (m shl 13))
            }
            // Half-float Inf (mant == 0) or NaN (mant != 0). Sanitize to 0 so
            // downstream math (HSL, sat/vib, gradient blends) cannot propagate
            // garbage values that render as sparkle pixels. Legitimate pixel
            // data should never carry these encodings; they're either decoder
            // bugs or pre-saturated camera hot-pixels.
            exp == 0x1F -> 0f
            else -> java.lang.Float.intBitsToFloat(signI or ((exp + 112) shl 23) or (mant shl 13))
        }
    }

    private fun floatToHalfBitsLocal(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF
        // NaN or Inf at the encode side — emit zero rather than a half-float
        // NaN/Inf that would tint the pixel sparkly. Combined with the read-side
        // sanitization, this guarantees the FP16 buffer never carries non-finite
        // samples between pipeline stages.
        if (exp == 0xFF) {
            return 0
        }
        val newExp = exp - 127 + 15
        if (newExp >= 0x1F) return (sign or 0x7C00).toShort()
        if (newExp <= 0) {
            if (newExp < -10) return sign.toShort()
            val m = (mant or 0x800000) shr (1 - newExp + 13)
            return (sign or m).toShort()
        }
        val rounded = mant + 0x1000
        return if (rounded and 0x800000 != 0) {
            if (newExp + 1 >= 0x1F) (sign or 0x7C00).toShort()
            else (sign or ((newExp + 1) shl 10)).toShort()
        } else {
            (sign or (newExp shl 10) or (rounded shr 13)).toShort()
        }
    }

    // ── CPU LUT (trilinear interpolation) ─────────────────────────────────────

    suspend fun applyCpuLut(
        bitmap: Bitmap,
        lutTable: FloatArray,
        lutSize: Int,
        intensity: Float,
    ): Bitmap = withContext(Dispatchers.Default) {
        if (intensity == 0f) return@withContext bitmap
        // Precompute Lab (L,a,b) for every LUT output node once per unique lutTable
        // reference. Interpolation then happens in Lab space rather than RGB —
        // the path between two LUT grid nodes is perceptually straight instead
        // of an RGB diagonal that can produce unexpected hue shifts.
        val labLut = precomputeLabLut(lutTable, lutSize)
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val s = lutSize - 1
        for (i in pixels.indices) {
            if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
            val px = pixels[i]
            val ri = (px shr 16) and 0xFF
            val gi = (px shr 8)  and 0xFF
            val bi =  px         and 0xFF
            val rf = ri / 255f * s
            val gf = gi / 255f * s
            val bf = bi / 255f * s
            val r0 = rf.toInt().coerceIn(0, s - 1)
            val g0 = gf.toInt().coerceIn(0, s - 1)
            val b0 = bf.toInt().coerceIn(0, s - 1)
            val dr = rf - r0; val dg = gf - g0; val db = bf - b0
            // Trilinear in Lab space (L=ch0, a=ch1, b=ch2)
            val lL = trilinear(labLut, lutSize, r0, g0, b0, dr, dg, db, 0)
            val lA = trilinear(labLut, lutSize, r0, g0, b0, dr, dg, db, 1)
            val lB = trilinear(labLut, lutSize, r0, g0, b0, dr, dg, db, 2)
            // Lab → linear RGB → gamma-encode
            val (rLin, gLin, bLin) = labToLinearRgb(lL, lA, lB)
            val lr = rLin.pow(1f / 2.2f)
            val lg = gLin.pow(1f / 2.2f)
            val lb = bLin.pow(1f / 2.2f)
            val outR = (ri + (lr * 255f - ri) * intensity).toInt().coerceIn(0, 255)
            val outG = (gi + (lg * 255f - gi) * intensity).toInt().coerceIn(0, 255)
            val outB = (bi + (lb * 255f - bi) * intensity).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
        }
        val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        out
    }

    private fun trilinear(
        lut: FloatArray, size: Int,
        r0: Int, g0: Int, b0: Int,
        dr: Float, dg: Float, db: Float,
        ch: Int,
    ): Float {
        fun idx(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3 + ch
        val c000 = lut[idx(r0,   g0,   b0  )]
        val c100 = lut[idx(r0+1, g0,   b0  )]
        val c010 = lut[idx(r0,   g0+1, b0  )]
        val c110 = lut[idx(r0+1, g0+1, b0  )]
        val c001 = lut[idx(r0,   g0,   b0+1)]
        val c101 = lut[idx(r0+1, g0,   b0+1)]
        val c011 = lut[idx(r0,   g0+1, b0+1)]
        val c111 = lut[idx(r0+1, g0+1, b0+1)]
        val c00 = c000 + dr * (c100 - c000)
        val c10 = c010 + dr * (c110 - c010)
        val c01 = c001 + dr * (c101 - c001)
        val c11 = c011 + dr * (c111 - c011)
        val c0  = c00  + dg * (c10  - c00 )
        val c1  = c01  + dg * (c11  - c01 )
        return c0 + db * (c1 - c0)
    }

    // ── LUT construction ──────────────────────────────────────────────────────

    private fun buildChannelLuts(macro: UserMacro): Triple<IntArray, IntArray, IntArray> {
        val (rMult, gMult, bMult) = wbTintMultipliers(macro.whiteBalance, macro.tint)
        val expScale  = 2f.pow(macro.exposure)
        val highlights = macro.highlights / 100f
        val shadows    = macro.shadows    / 100f
        val whites     = macro.whites     / 100f
        val blacks     = macro.blacks     / 100f
        val contrast   = macro.contrast   / 100f
        val dehaze     = macro.dehaze     / 100f

        var rLut = buildLut(expScale * rMult, highlights, shadows, whites, blacks, contrast, dehaze)
        var gLut = buildLut(expScale * gMult, highlights, shadows, whites, blacks, contrast, dehaze)
        var bLut = buildLut(expScale * bMult, highlights, shadows, whites, blacks, contrast, dehaze)

        // Apply interactive tone curves: channel 0 = Luminance (all), 1 = R, 2 = G, 3 = B
        val defaultCurve = UserMacro.DEFAULT_CURVE_POINTS[0]
        val curveL = macro.toneCurvePoints.getOrNull(0)
        val curveR = macro.toneCurvePoints.getOrNull(1)
        val curveG = macro.toneCurvePoints.getOrNull(2)
        val curveB = macro.toneCurvePoints.getOrNull(3)
        if (curveL != null && curveL != defaultCurve) {
            rLut = applyToneCurveToLut(rLut, curveL)
            gLut = applyToneCurveToLut(gLut, curveL)
            bLut = applyToneCurveToLut(bLut, curveL)
        }
        if (curveR != null && curveR != defaultCurve) rLut = applyToneCurveToLut(rLut, curveR)
        if (curveG != null && curveG != defaultCurve) gLut = applyToneCurveToLut(gLut, curveG)
        if (curveB != null && curveB != defaultCurve) bLut = applyToneCurveToLut(bLut, curveB)

        return Triple(rLut, gLut, bLut)
    }

    private fun buildLut(
        channelScale: Float,
        highlights:   Float,
        shadows:      Float,
        whites:       Float,
        blacks:       Float,
        contrast:     Float,
        dehaze:       Float,
    ): IntArray {
        val lut = IntArray(256)
        for (i in 0..255) {
            var L = (i / 255.0).pow(2.2).toFloat()
            L *= channelScale
            if (dehaze != 0f) {
                val bell = 4f * L * (1f - L)
                L -= dehaze * 0.15f * bell
            }
            if (highlights != 0f) {
                // Threshold in gamma space: affects perceived brightness > 50% (pixels > ~128/255).
                // Without this, the linear-space threshold of L>0.5 only triggers above ~186/255
                // (73% gamma brightness), making the slider nearly invisible on most photos.
                val Lg = L.toDouble().pow(1.0 / 2.2).toFloat()
                val t = ((Lg - 0.5f) / 0.5f).coerceIn(0f, 1f)
                L += highlights * 0.5f * t * t
            }
            if (shadows != 0f) {
                val Lg = L.toDouble().pow(1.0 / 2.2).toFloat()
                val t = ((0.5f - Lg) / 0.5f).coerceIn(0f, 1f)
                L += shadows * 0.4f * t * t * L
            }
            if (whites != 0f || blacks != 0f) {
                val wp = (1f - whites * 0.25f).coerceAtLeast(0.1f)
                val bp = (blacks * 0.12f).coerceIn(-0.15f, 0.15f)
                L = (L / wp).coerceIn(0f, 1f)
                L = bp + L * (1f - bp)
            }
            if (contrast != 0f) {
                L = (L + contrast * 0.5f * L * (1f - L) * (2f * L - 1f)).coerceIn(0f, 1f)
            }
            lut[i] = (L.coerceIn(0f, 1f).toDouble().pow(1.0 / 2.2) * 255.5).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /** Applies a 5-point Catmull-Rom tone curve (y-values at x=0,0.25,0.5,0.75,1) to [lut]. */
    private fun applyToneCurveToLut(lut: IntArray, points: List<Float>): IntArray {
        val ys = points.toFloatArray()
        val out = IntArray(256)
        for (i in 0..255) {
            val x = lut[i] / 255f
            out[i] = (interpolateCurve(ys, x).coerceIn(0f, 1f) * 255.5f).toInt().coerceIn(0, 255)
        }
        return out
    }

    /**
     * Catmull-Rom cubic interpolation over 5 control points at x = 0, 0.25, 0.5, 0.75, 1.
     * [ys] contains the y-values at those x positions.
     */
    private fun interpolateCurve(ys: FloatArray, x: Float): Float {
        val n = ys.size
        if (x <= 0f) return ys[0]
        if (x >= 1f) return ys[n - 1]
        val tGlobal = x * (n - 1)
        val seg = tGlobal.toInt().coerceIn(0, n - 2)
        val t = tGlobal - seg
        val y0 = ys[seg]
        val y1 = ys[seg + 1]
        val m0 = if (seg == 0) y1 - y0 else (ys[seg + 1] - ys[seg - 1]) * 0.5f
        val m1 = if (seg == n - 2) y1 - y0 else (ys[seg + 2] - ys[seg]) * 0.5f
        val t2 = t * t; val t3 = t2 * t
        return (2f * t3 - 3f * t2 + 1f) * y0 +
               (t3 - 2f * t2 + t) * m0 +
               (-2f * t3 + 3f * t2) * y1 +
               (t3 - t2) * m1
    }

    private fun wbTintMultipliers(kelvin: Int, tint: Float): Triple<Float, Float, Float> {
        val tNorm = tint / 150f
        // Tint strength bumped 2.5× over the original 0.04 / 0.08 multipliers
        // so the slider has a perceptible green↔magenta swing at moderate
        // positions (~±50). The previous coefficients produced barely-visible
        // colour shifts even at the slider's extreme. New coefficients clamp
        // at ±15% red / ±20% green at full tint=±150, well short of crushing.
        if (kelvin == 0) {
            return Triple(
                (1f + 0.10f * tNorm).coerceIn(0.1f, 3f),
                (1f - 0.20f * tNorm).coerceIn(0.1f, 3f),
                1f,
            )
        }
        val delta = (kelvin - 5500f) / 5500f
        val rMult = (1f + 0.30f * delta + 0.10f * tNorm).coerceIn(0.1f, 3f)
        val gMult = (1f - 0.20f * tNorm).coerceIn(0.1f, 3f)
        val bMult = (1f - 0.30f * delta).coerceIn(0.1f, 3f)
        return Triple(rMult, gMult, bMult)
    }

    // ── HSL helpers ───────────────────────────────────────────────────────────

    private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val cMax = max(r, max(g, b))
        val cMin = min(r, min(g, b))
        val delta = cMax - cMin
        val l = (cMax + cMin) * 0.5f
        // Guard against division by a near-zero `1 - |2l - 1|`. The denominator
        // can also go to zero when l hits exactly 0 or 1 (pure black or pure
        // white); preserving the old `delta < 1e-6` threshold to keep the 8-bit
        // path bit-identical, but adding the denom guard for safety.
        val denom = 1f - abs(2f * l - 1f)
        val s = if (delta < 1e-6f || denom < 1e-6f) 0f else delta / denom
        val h = when {
            delta < 1e-6f -> 0f
            cMax == r     -> ((g - b) / delta).mod(6f) / 6f
            cMax == g     -> ((b - r) / delta + 2f) / 6f
            else          -> ((r - g) / delta + 4f) / 6f
        }
        return Triple(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
        val c  = (1f - abs(2f * l - 1f)) * s
        val x  = c * (1f - abs((h * 6f).mod(2f) - 1f))
        val m  = l - c * 0.5f
        val (r1, g1, b1) = when ((h * 6f).toInt().coerceIn(0, 5)) {
            0    -> Triple(c, x, 0f)
            1    -> Triple(x, c, 0f)
            2    -> Triple(0f, c, x)
            3    -> Triple(0f, x, c)
            4    -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    private fun adjustSatVibrance(s: Float, satF: Float, vibF: Float): Float {
        var ns = if (satF >= 0f) s + satF * (1f - s) else s + satF * s
        if (vibF != 0f) {
            val w = 1f - ns
            ns += if (vibF >= 0f) vibF * w * (1f - ns) else vibF * w * ns
        }
        return ns
    }

    // ── LCHab (perceptual color space) helpers ────────────────────────────────
    // Used for: (1) luminance-preserving saturation/vibrance — adjusts Chroma C
    // while holding L and H constant so perceived brightness never shifts; and
    // (2) Lab-space LUT interpolation — converts the 8 surrounding LUT output
    // nodes to Lab before trilinear weighting so the interpolated path through
    // color space is perceptually straight instead of RGB-diagonal.
    //
    // All math D65 sRGB (IEC 61966-2-1); operates on linear-light [0,1] values.

    // sRGB D65 forward + inverse matrices
    private val M_RGB_XYZ = floatArrayOf(
        0.4124564f, 0.3575761f, 0.1804375f,
        0.2126729f, 0.7151522f, 0.0721750f,
        0.0193339f, 0.1191920f, 0.9503041f,
    )
    private val M_XYZ_RGB = floatArrayOf(
         3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f,  1.8760108f,  0.0415560f,
         0.0556434f, -0.2040259f,  1.0572252f,
    )
    private const val XN = 0.95047f; private const val ZN = 1.08883f

    // f(t) for XYZ→Lab: cube-root above the inflection point, linear below.
    // 2049 entries covering t ∈ [0, 2] at step 1/1024. Lazy so it's only built
    // when LCH math is first needed, not at class-load time.
    private val LAB_F_LUT: FloatArray by lazy {
        FloatArray(2049) { i ->
            val t = i / 1024f
            if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat()
            else 7.787f * t + 16f / 116f
        }
    }

    private fun labF(t: Float): Float =
        LAB_F_LUT[((t.coerceIn(0f, 2f)) * 1024f + 0.5f).toInt().coerceIn(0, 2048)]

    private fun labFInv(f: Float): Float {
        val f3 = f * f * f; return if (f3 > 0.008856f) f3 else (f - 16f / 116f) / 7.787f
    }

    private fun linearRgbToLab(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val x = M_RGB_XYZ[0]*r + M_RGB_XYZ[1]*g + M_RGB_XYZ[2]*b
        val y = M_RGB_XYZ[3]*r + M_RGB_XYZ[4]*g + M_RGB_XYZ[5]*b
        val z = M_RGB_XYZ[6]*r + M_RGB_XYZ[7]*g + M_RGB_XYZ[8]*b
        val fx = labF(x / XN); val fy = labF(y); val fz = labF(z / ZN)
        return Triple(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    private fun labToLinearRgb(L: Float, a: Float, bv: Float): Triple<Float, Float, Float> {
        val fy = (L + 16f) / 116f
        val x = XN * labFInv(a / 500f + fy)
        val y = labFInv(fy)
        val z = ZN * labFInv(fy - bv / 200f)
        return Triple(
            (M_XYZ_RGB[0]*x + M_XYZ_RGB[1]*y + M_XYZ_RGB[2]*z).coerceIn(0f, 1f),
            (M_XYZ_RGB[3]*x + M_XYZ_RGB[4]*y + M_XYZ_RGB[5]*z).coerceIn(0f, 1f),
            (M_XYZ_RGB[6]*x + M_XYZ_RGB[7]*y + M_XYZ_RGB[8]*z).coerceIn(0f, 1f),
        )
    }

    private fun linearRgbToLch(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val (L, a, bv) = linearRgbToLab(r, g, b)
        val C = kotlin.math.sqrt((a * a + bv * bv).toDouble()).toFloat()
        var H = Math.toDegrees(kotlin.math.atan2(bv.toDouble(), a.toDouble())).toFloat()
        if (H < 0f) H += 360f
        return Triple(L, C, H)
    }

    private fun lchToLinearRgb(L: Float, C: Float, H: Float): Triple<Float, Float, Float> {
        val hRad = Math.toRadians(H.toDouble())
        return labToLinearRgb(L,
            (C * kotlin.math.cos(hRad)).toFloat(),
            (C * kotlin.math.sin(hRad)).toFloat())
    }

    // Chroma-only adjustment in LCHab. `satF` = saturation/100, `vibF` = vibrance/100.
    // Both in [-1, +1]. Vibrance boosts less-saturated colors more (like Lightroom);
    // saturation is a uniform chroma scale from current toward maxC (or toward 0).
    private fun adjustChromaLCH(C: Float, satF: Float, vibF: Float): Float {
        val maxC = 150f
        var nc = C
        if (vibF != 0f) {
            val norm = (C / maxC).coerceIn(0f, 1f)
            nc += if (vibF >= 0f) vibF * (1f - norm) * 50f
                  else            vibF * norm          * 50f
        }
        if (satF != 0f) {
            nc = if (satF >= 0f) nc + satF * (maxC - nc) else nc + satF * nc
        }
        return nc.coerceIn(0f, maxC)
    }

    // Cache for the Lab-converted version of the last LUT table (by identity).
    // Saves repeated precomputation across redraws on the same LUT.
    private var labLutCache: Pair<FloatArray, FloatArray>? = null

    // Returns a FloatArray (same layout as lutTable) where RGB triples are
    // replaced by (L, a, b) triples. Input lutTable values are [0,1] gamma-encoded.
    private fun precomputeLabLut(lutTable: FloatArray, lutSize: Int): FloatArray {
        val cached = labLutCache
        if (cached != null && cached.first === lutTable) return cached.second
        val n = lutSize * lutSize * lutSize
        val labLut = FloatArray(n * 3)
        for (i in 0 until n) {
            val rg = lutTable[i * 3].pow(2.2f)
            val gg = lutTable[i * 3 + 1].pow(2.2f)
            val bg = lutTable[i * 3 + 2].pow(2.2f)
            val (L, a, bv) = linearRgbToLab(rg, gg, bg)
            labLut[i * 3] = L; labLut[i * 3 + 1] = a; labLut[i * 3 + 2] = bv
        }
        labLutCache = Pair(lutTable, labLut)
        return labLut
    }

    /**
     * Squared bell-curve weight for a hue range. [center] and [hDeg] in degrees.
     * Returns (1 - dist/halfWidth)² for dist < halfWidth, else 0.
     * Handles 0°/360° wraparound.
     */
    private fun hueRangeWeight(hDeg: Float, center: Float, halfWidth: Float): Float {
        var dist = abs(hDeg - center)
        if (dist > 180f) dist = 360f - dist
        return if (dist >= halfWidth) 0f else (1f - dist / halfWidth).let { it * it }
    }

    /**
     * Standard Hermite smoothstep on `[lo, hi]`, returning a value in `[0, 1]`.
     * Used by the FP16 HSL path to gate per-hue effects on near-grey pixels
     * where the hue angle is numerically unstable.
     */
    private fun smoothstep01(lo: Float, hi: Float, x: Float): Float {
        if (hi <= lo) return if (x >= hi) 1f else 0f
        val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ── Spatial adjustments ───────────────────────────────────────────────────

    private suspend fun applyBoxBlurBitmap(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        // in-place: pixels is the only consumer of this blur; mutating it
        // saves a full w*h IntArray allocation (~80 MB on a 24 MP image).
        val out = boxBlurPixels(pixels, w, h, radius, inPlace = true)
        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private suspend fun applySpatialAdjustments(src: Bitmap, sharpness: Float, clarity: Float, texture: Float): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val sharpF = sharpness / 100f
        val clarF  = clarity   / 100f
        val texF   = texture   / 100f
        // Same memory-saving sequential pattern as applyNoiseReduction:
        // process sharp/texture (radius-1 blur) first, mix in-place into
        // `pixels`, drop that blur reference, then process clarity (radius-5
        // blur) on the now-sharpened pixels. Peak `2 * w*h` ints instead of
        // `4 * w*h` (pixels + blur1 + blur5 + out).
        if (sharpF != 0f || texF != 0f) {
            val blur1 = boxBlurPixels(pixels, w, h, 1)
            val weight = sharpF * 0.6f + texF * 0.4f
            for (i in pixels.indices) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                val px = pixels[i]
                val bp = blur1[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8)  and 0xFF
                val b =  px         and 0xFF
                val nr = (r + (weight * (r - ((bp shr 16) and 0xFF))).toInt()).coerceIn(0, 255)
                val ng = (g + (weight * (g - ((bp shr 8)  and 0xFF))).toInt()).coerceIn(0, 255)
                val nb = (b + (weight * (b - ( bp         and 0xFF))).toInt()).coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        if (clarF != 0f) {
            val blur5 = boxBlurPixels(pixels, w, h, 5)
            for (i in pixels.indices) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                val px = pixels[i]
                val bp = blur5[i]
                var r = (px shr 16) and 0xFF
                var g = (px shr 8)  and 0xFF
                var b =  px         and 0xFF
                val lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                val midZone = 4f * lum * (1f - lum)
                val weight  = clarF * midZone * 0.5f
                r = (r + (weight * (r - ((bp shr 16) and 0xFF))).toInt()).coerceIn(0, 255)
                g = (g + (weight * (g - ((bp shr 8)  and 0xFF))).toInt()).coerceIn(0, 255)
                b = (b + (weight * (b - ( bp         and 0xFF))).toInt()).coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Radial vignette with center offset, feather, intensity, and effect filter.
     *
     * - amount    [-100, 100]: negative = dark edges, positive = light edges
     * - feather   [0, 1]: 0 = hard edge, 1 = very soft transition
     * - intensity [0, 1]: scales the maximum darkening/lightening at the edge
     * - effect    All | HighlightsOnly | ShadowsOnly: tonal range filter
     * - centerX/Y [0, 1]: normalized vignette center (0.5,0.5 = image center)
     *
     * When [vignetteSegmentation] == Background, effect is scaled by the
     * background-mask weight per-pixel so the subject stays unaffected.
     */
    private suspend fun applyVignette(src: Bitmap, macro: UserMacro, masks: RawSegmentationMasks?): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val cx = macro.vignetteCenterX * w
        val cy = macro.vignetteCenterY * h
        // maxDist: largest possible distance from center to a corner, used for normalization
        val maxDist = maxOf(
            Math.hypot((cx).toDouble(),         (cy).toDouble()),
            Math.hypot((w - cx).toDouble(),     (cy).toDouble()),
            Math.hypot((cx).toDouble(),         (h - cy).toDouble()),
            Math.hypot((w - cx).toDouble(),     (h - cy).toDouble()),
        ).toFloat().coerceAtLeast(1f)

        val strength  = (macro.vignetteAmount / 100f) * macro.vignetteIntensity
        // feather controls the exponent of the distance curve:
        //   feather=0 → exponent very high → nearly hard edge
        //   feather=1 → exponent=1 → linear (very soft)
        val exponent  = (2f - macro.vignetteFeather * 1.5f).coerceIn(0.5f, 2f)

        val maskSize = RawSegmentationMasks.MASK_SIZE
        val bgMask = if (masks != null && macro.vignetteSegmentation == SegmentTarget.Background)
            masks.backgroundMask else null

        for (y in 0 until h) {
            if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                val normDist = (Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / maxDist)
                    .coerceIn(0f, 1f)
                val distCurve = normDist.toDouble().pow(exponent.toDouble()).toFloat()
                val rawFactor = (1f + strength * distCurve).coerceIn(0f, 2f)

                val segFactor = if (bgMask != null) {
                    val weight = sampleMask(bgMask, maskSize, w, h, x, y)
                    1f + (rawFactor - 1f) * weight
                } else rawFactor

                val i = y * w + x
                val px = pixels[i]
                val ri = (px shr 16) and 0xFF
                val gi = (px shr  8) and 0xFF
                val bi =  px         and 0xFF

                // Effect filter: skip pixels outside the target tonal range
                val lum = (0.2126f * ri + 0.7152f * gi + 0.0722f * bi) / 255f
                val effectWeight = when (macro.vignetteEffect) {
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect.All            -> 1f
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect.HighlightsOnly -> (lum - 0.4f).coerceIn(0f, 0.6f) / 0.6f
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect.ShadowsOnly    -> (0.6f - lum).coerceIn(0f, 0.6f) / 0.6f
                }
                val factor = 1f + (segFactor - 1f) * effectWeight

                pixels[i] = (0xFF shl 24) or
                    ((ri * factor).toInt().coerceIn(0, 255) shl 16) or
                    ((gi * factor).toInt().coerceIn(0, 255) shl  8) or
                     (bi * factor).toInt().coerceIn(0, 255)
            }
        }
        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 4-sided edge gradients. Each side (top/bottom/left/right) darkens pixels from its edge
     * inward by intensity, over length (0–1 fraction), with feather controlling falloff shape.
     * gradientAngle rotates the entire effect.
     *
     * When segmentation masks are available, each side's [applyTo] target (Subject/Background)
     * scales the per-pixel falloff by the corresponding mask weight so the effect is confined
     * to the intended region.
     */
    private suspend fun applyEdgeGradients(src: Bitmap, macro: UserMacro, masks: RawSegmentationMasks?): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val angleRad = macro.gradientAngle * (Math.PI / 180.0).toFloat()
        val cosA = cos(angleRad); val sinA = sin(angleRad)
        val maskSize = RawSegmentationMasks.MASK_SIZE

        // Pre-resolve per-side mask arrays (null = All, no masking). backgroundMask allocated once.
        val needsBg = masks != null && (
            macro.gradientTopApplyTo    == SegmentTarget.Background ||
            macro.gradientBottomApplyTo == SegmentTarget.Background ||
            macro.gradientLeftApplyTo   == SegmentTarget.Background ||
            macro.gradientRightApplyTo  == SegmentTarget.Background
        )
        val bgMask = if (needsBg) masks!!.backgroundMask else null
        fun pickMask(target: SegmentTarget): FloatArray? = when {
            masks == null || target == SegmentTarget.All -> null
            target == SegmentTarget.Subject -> masks.subjectMask
            else -> bgMask
        }
        val topMask    = pickMask(macro.gradientTopApplyTo)
        val bottomMask = pickMask(macro.gradientBottomApplyTo)
        val leftMask   = pickMask(macro.gradientLeftApplyTo)
        val rightMask  = pickMask(macro.gradientRightApplyTo)

        for (y in 0 until h) {
            if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
            val ny = if (h > 1) y.toFloat() / (h - 1) else 0.5f
            for (x in 0 until w) {
                val nx = if (w > 1) x.toFloat() / (w - 1) else 0.5f
                val dx = nx - 0.5f; val dy = ny - 0.5f
                val rx = (dx * cosA - dy * sinA + 0.5f).coerceIn(0f, 1f)
                val ry = (dx * sinA + dy * cosA + 0.5f).coerceIn(0f, 1f)

                val rawTop    = if (macro.gradientTopIntensity    != 0f) edgeFalloff(ry,       macro.gradientTopLength,    macro.gradientTopFeather)    else 0f
                val rawBottom = if (macro.gradientBottomIntensity != 0f) edgeFalloff(1f - ry,  macro.gradientBottomLength, macro.gradientBottomFeather) else 0f
                val rawLeft   = if (macro.gradientLeftIntensity   != 0f) edgeFalloff(rx,       macro.gradientLeftLength,   macro.gradientLeftFeather)   else 0f
                val rawRight  = if (macro.gradientRightIntensity  != 0f) edgeFalloff(1f - rx,  macro.gradientRightLength,  macro.gradientRightFeather)  else 0f

                // Second-color falloffs — anchored at `length1`, fade over `length2`.
                val rawTop2    = if (macro.gradientTopEnable2    && (macro.gradientTopIntensity2    != 0f || macro.gradientTopTintLuminosity2    > 0f)) edgeFalloff2(ry,      macro.gradientTopLength,    macro.gradientTopLength2,    macro.gradientTopFeather2)    else 0f
                val rawBottom2 = if (macro.gradientBottomEnable2 && (macro.gradientBottomIntensity2 != 0f || macro.gradientBottomTintLuminosity2 > 0f)) edgeFalloff2(1f - ry, macro.gradientBottomLength, macro.gradientBottomLength2, macro.gradientBottomFeather2) else 0f
                val rawLeft2   = if (macro.gradientLeftEnable2   && (macro.gradientLeftIntensity2   != 0f || macro.gradientLeftTintLuminosity2   > 0f)) edgeFalloff2(rx,      macro.gradientLeftLength,   macro.gradientLeftLength2,   macro.gradientLeftFeather2)   else 0f
                val rawRight2  = if (macro.gradientRightEnable2  && (macro.gradientRightIntensity2  != 0f || macro.gradientRightTintLuminosity2  > 0f)) edgeFalloff2(1f - rx, macro.gradientRightLength,  macro.gradientRightLength2,  macro.gradientRightFeather2)  else 0f

                val topFalloff    = if (topMask    != null) rawTop    * sampleMask(topMask,    maskSize, w, h, x, y) else rawTop
                val bottomFalloff = if (bottomMask != null) rawBottom * sampleMask(bottomMask, maskSize, w, h, x, y) else rawBottom
                val leftFalloff   = if (leftMask   != null) rawLeft   * sampleMask(leftMask,   maskSize, w, h, x, y) else rawLeft
                val rightFalloff  = if (rightMask  != null) rawRight  * sampleMask(rightMask,  maskSize, w, h, x, y) else rawRight
                val topFalloff2    = if (topMask    != null) rawTop2    * sampleMask(topMask,    maskSize, w, h, x, y) else rawTop2
                val bottomFalloff2 = if (bottomMask != null) rawBottom2 * sampleMask(bottomMask, maskSize, w, h, x, y) else rawBottom2
                val leftFalloff2   = if (leftMask   != null) rawLeft2   * sampleMask(leftMask,   maskSize, w, h, x, y) else rawLeft2
                val rightFalloff2  = if (rightMask  != null) rawRight2  * sampleMask(rightMask,  maskSize, w, h, x, y) else rawRight2

                val darkness = (macro.gradientTopIntensity    * topFalloff +
                                macro.gradientBottomIntensity * bottomFalloff +
                                macro.gradientLeftIntensity   * leftFalloff +
                                macro.gradientRightIntensity  * rightFalloff +
                                macro.gradientTopIntensity2    * topFalloff2 +
                                macro.gradientBottomIntensity2 * bottomFalloff2 +
                                macro.gradientLeftIntensity2   * leftFalloff2 +
                                macro.gradientRightIntensity2  * rightFalloff2)

                val idx = y * w + x
                var px = pixels[idx]

                // Step 1: darkness darkening
                if (darkness > 0f) {
                    val factor = (1f - darkness.coerceIn(0f, 1f))
                    val r = (((px shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
                    val g = (((px shr 8)  and 0xFF) * factor).toInt().coerceIn(0, 255)
                    val b = (( px         and 0xFF) * factor).toInt().coerceIn(0, 255)
                    px = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                // Step 2: tint blending per side (masked falloffs carry through to tint weight).
                // Tint 1 first, then tint 2 (when enabled) blends on top — the per-side
                // feather1 band controls the soft overlap between the two colors.
                px = applyTintBlend(px, macro.gradientTopTintColor,    macro.gradientTopTintLuminosity,    macro.gradientTopBlendMode,    topFalloff)
                px = applyTintBlend(px, macro.gradientBottomTintColor, macro.gradientBottomTintLuminosity, macro.gradientBottomBlendMode, bottomFalloff)
                px = applyTintBlend(px, macro.gradientLeftTintColor,   macro.gradientLeftTintLuminosity,   macro.gradientLeftBlendMode,   leftFalloff)
                px = applyTintBlend(px, macro.gradientRightTintColor,  macro.gradientRightTintLuminosity,  macro.gradientRightBlendMode,  rightFalloff)
                if (macro.gradientTopEnable2)    px = applyTintBlend(px, macro.gradientTopTintColor2,    macro.gradientTopTintLuminosity2,    macro.gradientTopBlendMode,    topFalloff2)
                if (macro.gradientBottomEnable2) px = applyTintBlend(px, macro.gradientBottomTintColor2, macro.gradientBottomTintLuminosity2, macro.gradientBottomBlendMode, bottomFalloff2)
                if (macro.gradientLeftEnable2)   px = applyTintBlend(px, macro.gradientLeftTintColor2,   macro.gradientLeftTintLuminosity2,   macro.gradientLeftBlendMode,   leftFalloff2)
                if (macro.gradientRightEnable2)  px = applyTintBlend(px, macro.gradientRightTintColor2,  macro.gradientRightTintLuminosity2,  macro.gradientRightBlendMode,  rightFalloff2)

                pixels[idx] = px
            }
        }
        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Blend tint colour into a pixel based on [blendMode] and [blendWeight] (tintLuminosity * falloff).
     * Returns the pixel unchanged if [tintLuminosity] is zero or [falloff] is zero.
     */
    private fun applyTintBlend(
        pixel: Int,
        tintColor: Int,
        tintLuminosity: Float,
        blendMode: RawGradientBlendMode,
        falloff: Float,
    ): Int {
        if (tintLuminosity <= 0f || falloff <= 0f) return pixel
        val blendWeight = (tintLuminosity * falloff).coerceIn(0f, 1f)

        val pixelR = (pixel shr 16) and 0xFF
        val pixelG = (pixel shr 8)  and 0xFF
        val pixelB =  pixel         and 0xFF

        val tintR = (tintColor shr 16) and 0xFF
        val tintG = (tintColor shr 8)  and 0xFF
        val tintB =  tintColor         and 0xFF

        val newR: Int
        val newG: Int
        val newB: Int

        when (blendMode) {
            RawGradientBlendMode.Solid -> {
                // Cinematic light-leak: screen + soft additive (parity with native).
                fun scr(c: Int, t: Int) = (255f - (255f - c) * (255f - t) / 255f)
                val sR = scr(pixelR, tintR); val sG = scr(pixelG, tintG); val sB = scr(pixelB, tintB)
                val aR = pixelR + tintR * (0.55f * blendWeight)
                val aG = pixelG + tintG * (0.55f * blendWeight)
                val aB = pixelB + tintB * (0.55f * blendWeight)
                val lR = sR + (maxOf(sR, aR) - sR) * 0.35f
                val lG = sG + (maxOf(sG, aG) - sG) * 0.35f
                val lB = sB + (maxOf(sB, aB) - sB) * 0.35f
                newR = (pixelR + (lR - pixelR) * blendWeight).toInt().coerceIn(0, 255)
                newG = (pixelG + (lG - pixelG) * blendWeight).toInt().coerceIn(0, 255)
                newB = (pixelB + (lB - pixelB) * blendWeight).toInt().coerceIn(0, 255)
            }
            RawGradientBlendMode.Fused -> {
                // Keep pixel luminance, adopt tint hue/saturation
                val pixelHsl = FloatArray(3)
                AndroidColor.RGBToHSV(pixelR, pixelG, pixelB, pixelHsl)
                val tintHsl = FloatArray(3)
                AndroidColor.RGBToHSV(tintR, tintG, tintB, tintHsl)

                // Fused: tint H and S, keep pixel V (brightness)
                val fusedH = tintHsl[0]
                val fusedS = tintHsl[1]
                val fusedV = pixelHsl[2]
                val fusedColor = AndroidColor.HSVToColor(floatArrayOf(fusedH, fusedS, fusedV))
                val fusedR = (fusedColor shr 16) and 0xFF
                val fusedG = (fusedColor shr 8)  and 0xFF
                val fusedB =  fusedColor          and 0xFF

                newR = (pixelR + (fusedR - pixelR) * blendWeight).toInt().coerceIn(0, 255)
                newG = (pixelG + (fusedG - pixelG) * blendWeight).toInt().coerceIn(0, 255)
                newB = (pixelB + (fusedB - pixelB) * blendWeight).toInt().coerceIn(0, 255)
            }
        }

        return (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
    }

    /**
     * Apply brightness/contrast/temperature/tint/saturation adjustments to pixels where
     * [maskBitmap] alpha > 0. The effect is weighted by the mask's per-pixel alpha so
     * fully-painted pixels receive the full adjustment and partially-painted pixels
     * receive it proportionally.
     *
     * [maskBitmap] may differ in size from [src]; coordinates are bilinearly interpolated.
     */
    private suspend fun applyMaskAdjustments(src: Bitmap, macro: UserMacro, maskBitmap: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val mw = maskBitmap.width; val mh = maskBitmap.height
        val maskPixels = IntArray(mw * mh)
        maskBitmap.getPixels(maskPixels, 0, mw, 0, 0, mw, mh)

        // Build LUTs from the mask adjustment fields reused as standard channel params.
        //
        // `maskTemperature` is a Kelvin delta; `whiteBalance` (consumed by
        // wbTintMultipliers via buildChannelLuts) is an absolute Kelvin. Convert
        // delta → absolute centered on 5500. Mirror of the float-pipeline fix.
        val miniMacro = UserMacro(
            exposure     = macro.maskBrightness / 100f,
            contrast     = macro.maskContrast,
            whiteBalance = if (macro.maskTemperature != 0) 5500 + macro.maskTemperature else 0,
            tint         = macro.maskTint,
            saturation   = macro.maskSaturation,
            highlights   = macro.maskTone.highlights,
            shadows      = macro.maskTone.shadows,
            whites       = macro.maskTone.whites,
            blacks       = macro.maskTone.blacks,
        )
        val (rLut, gLut, bLut) = buildChannelLuts(miniMacro)
        val satF    = miniMacro.saturation / 100f
        val hasSat  = satF != 0f
        val clarF   = macro.maskClarity / 100f
        val blur5   = if (clarF != 0f) boxBlurPixels(pixels, w, h, 5) else null

        for (y in 0 until h) {
            if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
            for (x in 0 until w) {
                // Bilinear sample of the mask alpha at this pixel
                val mx  = x.toFloat() * (mw - 1) / (w - 1).coerceAtLeast(1)
                val my  = y.toFloat() * (mh - 1) / (h - 1).coerceAtLeast(1)
                val mx0 = mx.toInt().coerceIn(0, mw - 2)
                val my0 = my.toInt().coerceIn(0, mh - 2)
                val dx  = mx - mx0; val dy = my - my0
                val a00 = (maskPixels[ my0      * mw + mx0    ] ushr 24) and 0xFF
                val a10 = (maskPixels[ my0      * mw + mx0 + 1] ushr 24) and 0xFF
                val a01 = (maskPixels[(my0 + 1) * mw + mx0    ] ushr 24) and 0xFF
                val a11 = (maskPixels[(my0 + 1) * mw + mx0 + 1] ushr 24) and 0xFF
                val weight = (a00 * (1 - dx) * (1 - dy) +
                              a10 * dx        * (1 - dy) +
                              a01 * (1 - dx)  * dy       +
                              a11 * dx        * dy       ) / 255f
                if (weight <= 0f) continue

                val i  = y * w + x
                val px = pixels[i]
                var r  = rLut[(px shr 16) and 0xFF]
                var g  = gLut[(px shr 8)  and 0xFF]
                var b  = bLut[ px         and 0xFF]

                if (hasSat) {
                    val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
                    val (hue, sat, lit) = rgbToHsl(rf, gf, bf)
                    val ns = adjustSatVibrance(sat, satF, 0f)
                    val (nr, ng, nb) = hslToRgb(hue, ns.coerceIn(0f, 1f), lit)
                    r = (nr * 255.5f).toInt().coerceIn(0, 255)
                    g = (ng * 255.5f).toInt().coerceIn(0, 255)
                    b = (nb * 255.5f).toInt().coerceIn(0, 255)
                }

                if (blur5 != null) {
                    val bp  = blur5[i]
                    val lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                    val midZone = 4f * lum * (1f - lum)
                    val cw  = clarF * midZone * 0.5f
                    r = (r + (cw * (r - ((bp shr 16) and 0xFF))).toInt()).coerceIn(0, 255)
                    g = (g + (cw * (g - ((bp shr 8)  and 0xFF))).toInt()).coerceIn(0, 255)
                    b = (b + (cw * (b - ( bp         and 0xFF))).toInt()).coerceIn(0, 255)
                }

                val origR = (px shr 16) and 0xFF
                val origG = (px shr 8)  and 0xFF
                val origB =  px         and 0xFF
                val outR  = (origR + (r - origR) * weight).toInt().coerceIn(0, 255)
                val outG  = (origG + (g - origG) * weight).toInt().coerceIn(0, 255)
                val outB  = (origB + (b - origB) * weight).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }

        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Bilinear sample of a [maskSize]×[maskSize] flat float mask at bitmap pixel (x, y).
     * Maps bitmap coordinates to mask space and interpolates the four surrounding samples.
     */
    private fun sampleMask(mask: FloatArray, maskSize: Int, bitmapW: Int, bitmapH: Int, x: Int, y: Int): Float {
        val mx = x.toFloat() / (bitmapW - 1).coerceAtLeast(1) * (maskSize - 1)
        val my = y.toFloat() / (bitmapH - 1).coerceAtLeast(1) * (maskSize - 1)
        val x0 = mx.toInt().coerceIn(0, maskSize - 2)
        val y0 = my.toInt().coerceIn(0, maskSize - 2)
        val dx = mx - x0; val dy = my - y0
        val v00 = mask[y0 * maskSize + x0]
        val v10 = mask[y0 * maskSize + x0 + 1]
        val v01 = mask[(y0 + 1) * maskSize + x0]
        val v11 = mask[(y0 + 1) * maskSize + x0 + 1]
        return v00 * (1 - dx) * (1 - dy) + v10 * dx * (1 - dy) + v01 * (1 - dx) * dy + v11 * dx * dy
    }

    /** Returns falloff weight 1→0 from edge (dist=0) to length boundary, feather controls cubic/linear blend. */
    private fun edgeFalloff(dist: Float, length: Float, feather: Float): Float {
        val nd = (dist / length.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        val linear = 1f - nd
        val smooth = 1f - nd * nd * (3f - 2f * nd)
        return smooth * feather + linear * (1f - feather)
    }

    /**
     * Falloff curve for the SECOND tint in a two-color linear sweep. The second
     * color is anchored at `length1` from the edge (where the first color ends),
     * peaks there, and falls off over `length2` with `feather2` softness. The
     * `feather1` band controls how wide the OVERLAP between color 1 and color 2
     * is (handled by the caller via the regular `edgeFalloff(dist, length1,
     * feather1)` result).
     *
     * Result: 0 at dist < length1, 1 at dist == length1, fading to 0 by
     * dist == length1 + length2.
     */
    private fun edgeFalloff2(
        dist: Float, length1: Float, length2: Float, feather2: Float,
    ): Float {
        // Shift origin to length1: anything before that is 0 (color 1 territory).
        val shifted = dist - length1
        if (shifted < 0f) return 0f
        return edgeFalloff(shifted, length2, feather2)
    }

    /**
     * Separate luma/chroma noise reduction via box blur blending.
     * [luminanceNR] blends luma toward blurred luma; [colorNR] blends chroma while preserving luma.
     *
     * Memory shape: the two NR passes used to allocate `blurLum`, `blurColor`,
     * `out`, and the input `pixels` all at once — peak `4 * w*h` ints alive,
     * which on a 24 MP image is ~320 MB inside this function and triggers
     * OOM on a 512 MB heap when combined with the upstream LibRaw buffer.
     *
     * Now the two NR passes run **sequentially**: compute `blurLum`, apply
     * the luma shift into `pixels` in-place, drop `blurLum` reference, then
     * compute `blurColor` and apply the chroma shift back into `pixels` again.
     * Peak goes from `4 * w*h` to `2 * w*h` ints (~160 MB on 24 MP). The
     * blur kernels stay non-destructive on `pixels` because they each read
     * `pixels` and write to a fresh `blurXxx` IntArray — the in-place writes
     * happen in the pixel-mixing loops, after the blur has finished reading.
     */
    private suspend fun applyNoiseReduction(src: Bitmap, luminanceNR: Float, colorNR: Float): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val lumRadius   = (luminanceNR * 3f).toInt().coerceIn(1, 5)
        val colorRadius = (colorNR     * 5f).toInt().coerceIn(1, 8)

        // Stage 1: luminance NR — blur, mix luma shift into `pixels` in place,
        // drop the blur reference so its 80 MB is reclaimable before color NR.
        if (luminanceNR > 0f) {
            val blurLum = boxBlurPixels(pixels, w, h, lumRadius)
            for (i in pixels.indices) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                val px = pixels[i]
                val bpx = blurLum[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8)  and 0xFF
                val b =  px         and 0xFF
                val lumOrig = r * 0.299f + g * 0.587f + b * 0.114f
                val lumBlur = ((bpx shr 16) and 0xFF) * 0.299f +
                              ((bpx shr 8)  and 0xFF) * 0.587f +
                              ( bpx         and 0xFF) * 0.114f
                val shift = (lumBlur - lumOrig) * luminanceNR
                val nr = (r + shift).toInt().coerceIn(0, 255)
                val ng = (g + shift).toInt().coerceIn(0, 255)
                val nb = (b + shift).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            // blurLum goes out of scope at the end of this if-block — ~80 MB
            // becomes garbage-collectable before the color-NR allocation below.
        }

        // Stage 2: color NR — blur is freshly computed from the (now luma-NR'd)
        // `pixels`. Same in-place trick — apply chroma shift back into `pixels`.
        if (colorNR > 0f) {
            val blurColor = boxBlurPixels(pixels, w, h, colorRadius)
            for (i in pixels.indices) {
                if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
                val px = pixels[i]
                val bpx = blurColor[i]
                var r = ((px shr 16) and 0xFF).toFloat()
                var g = ((px shr 8)  and 0xFF).toFloat()
                var b = ( px         and 0xFF).toFloat()
                val lumOrig = r * 0.299f + g * 0.587f + b * 0.114f
                val br = ((bpx shr 16) and 0xFF).toFloat()
                val bg = ((bpx shr 8)  and 0xFF).toFloat()
                val bb = ( bpx         and 0xFF).toFloat()
                val blendR = r + (br - r) * colorNR
                val blendG = g + (bg - g) * colorNR
                val blendB = b + (bb - b) * colorNR
                val lumAfter = blendR * 0.299f + blendG * 0.587f + blendB * 0.114f
                val lumCorr = lumOrig - lumAfter
                val nr = (blendR + lumCorr).toInt().coerceIn(0, 255)
                val ng = (blendG + lumCorr).toInt().coerceIn(0, 255)
                val nb = (blendB + lumCorr).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /** Edge-adaptive unsharp mask: amplifies high-frequency residual scaled by local edge magnitude. */
    private suspend fun applySmartSharpness(src: Bitmap, strength: Float): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val blur = boxBlurPixels(pixels, w, h, 1)
        val out  = IntArray(w * h)
        val sharpStrength = strength * 1.5f
        for (i in pixels.indices) {
            if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
            val px  = pixels[i]; val bpx = blur[i]
            var r = (px shr 16) and 0xFF
            var g = (px shr 8)  and 0xFF
            var b =  px         and 0xFF
            val edgeR = r - ((bpx shr 16) and 0xFF)
            val edgeG = g - ((bpx shr 8)  and 0xFF)
            val edgeB = b - ( bpx         and 0xFF)
            val edgeMag = (abs(edgeR) + abs(edgeG) + abs(edgeB)) / (3f * 255f)
            val weight  = sharpStrength * (edgeMag * 4f).coerceIn(0f, 1f)
            r = (r + (weight * edgeR)).toInt().coerceIn(0, 255)
            g = (g + (weight * edgeG)).toInt().coerceIn(0, 255)
            b = (b + (weight * edgeB)).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Film grain: random per-block noise scaled by [amount], grain [size] controls block footprint,
     * [uniformity] 0=midtone-weighted, 1=uniform distribution across tones.
     */
    private suspend fun applyFilmGrain(src: Bitmap, amount: Float, size: Float, uniformity: Float): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val maxGrain  = (amount * 40f).toInt().coerceIn(1, 40)
        val blockSize = (1 + size * 3f).toInt().coerceIn(1, 4)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val grain = Random.nextInt(-maxGrain, maxGrain + 1)
                for (dy in 0 until blockSize) {
                    for (dx in 0 until blockSize) {
                        val py = (y + dy).coerceIn(0, h - 1)
                        val px = (x + dx).coerceIn(0, w - 1)
                        val idx = py * w + px
                        val pixel = pixels[idx]
                        var r = (pixel shr 16) and 0xFF
                        var g = (pixel shr 8)  and 0xFF
                        var b =  pixel         and 0xFF
                        val lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                        // Midtone-weighted unless uniformity=1
                        val midW = 1f - (lum - 0.5f) * (lum - 0.5f) * 4f
                        val lumW = (uniformity + (1f - uniformity) * midW.coerceIn(0f, 1f))
                        val g2   = (grain * lumW).toInt()
                        r = (r + g2).coerceIn(0, 255)
                        g = (g + g2).coerceIn(0, 255)
                        b = (b + g2).coerceIn(0, 255)
                        pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                x += blockSize
            }
            y += blockSize
        }
        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /** Faded-film look: lift blacks and compress contrast by [amount] ∈ [0, 1]. */
    private suspend fun applyWashOut(src: Bitmap, amount: Float): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val lift  = (amount * 60f).toInt().coerceIn(0, 80)
        val scale = 1f - amount * 0.3f
        val lut   = IntArray(256) { i -> (lift + i * scale).toInt().coerceIn(0, 255) }
        for (i in pixels.indices) {
            if ((i and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
            val px = pixels[i]
            val r = lut[(px shr 16) and 0xFF]
            val g = lut[(px shr 8)  and 0xFF]
            val b = lut[ px         and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val result = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Separable box blur with clamped-border handling, O(w·h·(2r+1)) per axis.
     *
     * Memory shape:
     *  - Always allocates one scratch IntArray ([tmp], `w*h` ints) for the
     *    horizontal pass.
     *  - Output destination depends on [inPlace]:
     *      * `inPlace = false` (default, **backwards-compatible**): allocates a
     *        fresh `out` IntArray for the vertical pass and returns it.
     *        Caller's `src` is untouched — safe for dual-blur callers that
     *        compute two different blurs from the same input.
     *      * `inPlace = true`: writes the vertical pass back into the
     *        caller-supplied `src` array, returning that same reference.
     *        Caller MUST be OK with `src` being mutated. Saves `w*h` ints
     *        of peak memory — on a 24 MP image, ~80 MB.
     *
     * The single-blur callers in the spatial chain (applyNoiseReduction,
     * applyBoxBlurBitmap, applySmartSharpness) pass `inPlace = true`. Dual-blur
     * callers (applySpatialAdjustments computing blur1+blur5, applyNoiseReduction
     * computing blurLum+blurColor) keep the default to preserve correctness.
     */
    private suspend fun boxBlurPixels(
        src: IntArray,
        w: Int,
        h: Int,
        radius: Int,
        inPlace: Boolean = false,
    ): IntArray {
        val d   = 2 * radius + 1
        // Horizontal pass: src → tmp.
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
            val row = y * w
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0
                for (k in -radius..radius) {
                    val px = src[row + (x + k).coerceIn(0, w - 1)]
                    sumR += (px shr 16) and 0xFF
                    sumG += (px shr 8)  and 0xFF
                    sumB +=  px         and 0xFF
                }
                tmp[row + x] = (0xFF shl 24) or ((sumR / d) shl 16) or ((sumG / d) shl 8) or (sumB / d)
            }
        }
        // Vertical pass destination — either `src` (in-place) or a fresh `out`.
        val dest = if (inPlace) src else IntArray(w * h)
        for (y in 0 until h) {
            if ((y and 0x1F) == 0) currentCoroutineContext().ensureActive()
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0
                for (k in -radius..radius) {
                    val px = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    sumR += (px shr 16) and 0xFF
                    sumG += (px shr 8)  and 0xFF
                    sumB +=  px         and 0xFF
                }
                dest[y * w + x] = (0xFF shl 24) or ((sumR / d) shl 16) or ((sumG / d) shl 8) or (sumB / d)
            }
        }
        return dest
    }

    // ── 16-bit bridges ─────────────────────────────────────────────────────────
    //
    // `apply()` ingests both 8-bit (ARGB_8888) and 16-bit float (RGBA_F16) input
    // bitmaps. For the 16-bit path, full float-domain slider math is the goal of
    // a follow-up Step 3.3 turn. For now, this turn delivers real 16-bit at Stage
    // C and at export — the macro stage briefly downsamples to 8-bit, runs the
    // existing pipeline, then upsamples back to RGBA_F16 carrying the same
    // ColorSpace tag. No banding is introduced beyond what 8-bit slider math
    // already implies, and the export encoders see a real 16-bit container.

    /**
     * Convert an `RGBA_F16` bitmap to `ARGB_8888` for the slider pipeline.
     * Uses Android's built-in pixel conversion via `Bitmap.copy(...)` so the
     * ColorSpace transformation (e.g. linear → sRGB γ-encode) is handled by
     * the OS — we don't re-derive the curve here.
     */
    private fun downsample16To8(src: Bitmap): Bitmap {
        // `copy(ARGB_8888)` on a tagged RGBA_F16 source asks the platform to
        // produce a perceptually equivalent 8-bit sRGB rendition. This is
        // exactly what the existing IntArray macro pipeline expects.
        return src.copy(Bitmap.Config.ARGB_8888, false) ?: src
    }

    /**
     * Promote an `ARGB_8888` bitmap back to `RGBA_F16`, re-tagging with
     * [targetColorSpace] so the canvas doesn't flip gamut on the OOM bridge
     * fallback path.
     *
     * Background: `Bitmap.copy(RGBA_F16, false)` returns a bitmap tagged with
     * `LINEAR_EXTENDED_SRGB` regardless of the source. When the workspace is
     * DCI-P3 and the OOM bridge fires, the canvas would receive a linear-sRGB
     * bitmap mid-render, then later receive a linear-P3 bitmap from the idle
     * upgrade — visually that's a "color flip" the user reports as inconsistent.
     *
     * Fix: when [targetColorSpace] is non-null and differs from the platform
     * default, blit the upsampled bitmap into a freshly-created bitmap that
     * carries the target ColorSpace tag. `Canvas.drawBitmap` triggers Android's
     * color management — sRGB sample values are correctly transformed into
     * linear-P3 / linear-ProPhoto destination space. We deliberately avoid
     * `setColorSpace(target)` which the platform rejects when the new space
     * has a smaller component minimum than the current one
     * (LINEAR_EXTENDED_SRGB has negative minimum, plain linear P3 doesn't).
     */
    private fun upsample8To16(
        src: Bitmap,
        targetColorSpace: android.graphics.ColorSpace?,
    ): Bitmap {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return src
        val fp16 = src.copy(Bitmap.Config.RGBA_F16, false) ?: return src
        // Platform default for FP16 promotion is LINEAR_EXTENDED_SRGB. If the
        // caller asks for that (or didn't specify), we're already done.
        val target = targetColorSpace ?: return fp16
        if (target == fp16.colorSpace) return fp16

        // Re-tag via Canvas blit: create a destination tagged with [target]
        // and draw the source into it. Android color-manages the transfer.
        return runCatching {
            val tagged = Bitmap.createBitmap(fp16.width, fp16.height, Bitmap.Config.RGBA_F16, true, target)
            android.graphics.Canvas(tagged).drawBitmap(fp16, 0f, 0f, null)
            fp16.recycle()
            tagged
        }.getOrElse {
            android.util.Log.w(
                "MacroProcessor",
                "upsample8To16: re-tag to ${target.name} failed (${it.message}); returning LINEAR_EXTENDED_SRGB",
                it,
            )
            fp16
        }
    }

    /**
     * Run [body] over the rows `[0, h)` in 4 parallel coroutine bands.
     *
     * The body receives the half-open row range `(y0, y1)` for its band. Bands
     * never overlap, so a body that only writes to its own (y0..y1-1) rows of
     * a shared array has no data race.
     *
     * Falls back to single-threaded execution when `h < 4` or when running under
     * a context that can't fan out (caller already exhausted the dispatcher).
     * The caller's coroutine must be a suspend context — we use [coroutineScope]
     * so cancellation propagates correctly.
     */
    private suspend inline fun parallelRowBands(
        h: Int,
        crossinline body: suspend (y0: Int, y1: Int) -> Unit,
    ) {
        if (h < 4) { body(0, h); return }
        coroutineScope {
            val n = 4
            val band = (h + n - 1) / n
            (0 until n).map { t ->
                async(Dispatchers.Default) {
                    val y0 = t * band
                    val y1 = minOf(y0 + band, h)
                    if (y0 < y1) body(y0, y1)
                }
            }.awaitAll()
        }
    }
}
