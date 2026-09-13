/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Auto-exposure analyser. Runs a percentile histogram on a small sRGB
 * Bitmap (typically the editor's neutralBitmap, decoded once from
 * Stage A at file open) and derives UserMacro slider values that bring
 * the image into a "balanced" exposure: 0.5th percentile lands near
 * pure black, 99.5th percentile lands near pure white, midtones lift
 * proportionally.
 *
 * Why percentile rather than min/max:
 *   • A single bright specular highlight or a single deep-shadow pixel
 *     would otherwise drive the whole scale and produce a flat image.
 *   • 0.5 / 99.5 ignores the top + bottom 0.5% — same algorithm
 *     Lightroom's `Auto` button uses.
 *
 * Why on the small Bitmap rather than full-res Stage A:
 *   • The histogram for exposure decisions doesn't need 24 MP precision.
 *   • The 512-long-side neutralBitmap is already in RAM; full-res would
 *     cost another disk pass and an extra ~80 MB allocation.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks
import kotlin.math.ln
import kotlin.math.pow

object RawAutoExposure {

    /**
     * Auto-bright FACTOR — the full LibRaw auto-bright multiplier (`AB`),
     * computed CPU-side from the preview [bitmap]. This is the gain the "Smart
     * Bright" slider interpolates toward: effective multiplier =
     * 1 + (AB − 1) · (slider / 4).
     *
     * Mirrors LibRaw's `dcraw_make_mem_image` auto-bright (mem_image.cpp):
     *   • Build per-channel histograms (R, G, B).
     *   • `perc = totalPixels · auto_bright_thr` (LibRaw default 0.01 = top 1%).
     *   • For each channel, walk from white downward until the cumulative
     *     top-count exceeds `perc` → that channel's white point.
     *   • Take the BRIGHTEST channel's white point (`t_white = max`) so no
     *     channel clips (matches LibRaw picking the max val across channels).
     *   • Gain that maps that white point to ~white (0.98 in linear, a hair
     *     below clip).
     *
     * Returns AB ≥ 1 (brighten-only), capped at 16× (≈ +4 EV, the shader's
     * exposure ceiling). Returns 1.0 (no gain) when the image is already bright.
     */
    /**
     * Per-image auto-bright factor for the Smart Bright slider.
     *
     * Method = LibRaw-style highlight white point (top-1% → full white, so the
     * slider can genuinely clip). When [masks] is supplied it becomes
     * SUBJECT-WEIGHTED: each pixel's contribution to the white-point histogram is
     * scaled by its segmentation saliency, so the white point is chosen from the
     * SUBJECT's highlights. A blown sky / backlit window can then no longer pin
     * the white point and leave the subject dark — the gain rises until the
     * subject's highlights reach white, and the bright background simply clips
     * (it still receives the global gain; it's only excluded from *choosing* the
     * white point). With no mask it's the whole-scene white point (unchanged).
     *
     * NB: there is intentionally NO background weight floor. Any meaningful floor
     * lets a large bright background dominate the 1% percentile and defeats the
     * rescue — e.g. a 0.2 floor with a 50%-sky still pins the white point on the
     * sky. So background pixels (mask≈0) are simply omitted from the percentile.
     */
    fun autoBrightMultiplier(
        bitmap: Bitmap,
        masks: RawSegmentationMasks? = null,
    ): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 1f
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Float bins so each pixel can be weighted by saliency in [0,1].
        val histR = FloatArray(256)
        val histG = FloatArray(256)
        val histB = FloatArray(256)
        var totalW = 0f

        if (masks != null) {
            // Mirror analyse(): prefer the full-res refined mask, fall back to the
            // coarse subject mask. Bilinear-sample it to each bitmap pixel.
            val refined = masks.refinedMask
            val rw = masks.refinedWidth
            val rh = masks.refinedHeight
            val useRefined = refined != null && rw > 0 && rh > 0 && refined.size >= rw * rh
            val maskW = if (useRefined) rw else RawSegmentationMasks.MASK_SIZE
            val maskH = if (useRefined) rh else RawSegmentationMasks.MASK_SIZE
            val mask = if (useRefined) refined!! else masks.subjectMask
            if (mask.size >= maskW * maskH) {
                val sx = (maskW - 1f) / (w - 1).coerceAtLeast(1)
                val sy = (maskH - 1f) / (h - 1).coerceAtLeast(1)
                for (y in 0 until h) {
                    val mfy = y * sy; val my0 = mfy.toInt()
                    val my1 = (my0 + 1).coerceAtMost(maskH - 1); val ty = mfy - my0
                    val rowBase = y * w
                    for (x in 0 until w) {
                        val mfx = x * sx; val mx0 = mfx.toInt()
                        val mx1 = (mx0 + 1).coerceAtMost(maskW - 1); val tx = mfx - mx0
                        val m = ((mask[my0 * maskW + mx0] * (1 - tx) + mask[my0 * maskW + mx1] * tx) * (1 - ty)
                               + (mask[my1 * maskW + mx0] * (1 - tx) + mask[my1 * maskW + mx1] * tx) * ty)
                            .coerceIn(0f, 1f)
                        if (m <= 0f) continue
                        val px = pixels[rowBase + x]
                        histR[(px shr 16) and 0xFF] += m
                        histG[(px shr 8) and 0xFF] += m
                        histB[px and 0xFF] += m
                        totalW += m
                    }
                }
            }
        }

        // No mask, or subject coverage negligible (mask failed / no salient
        // subject) → whole-scene white point (every pixel weight 1).
        if (totalW < pixels.size * 0.005f) {
            histR.fill(0f); histG.fill(0f); histB.fill(0f)
            for (px in pixels) {
                histR[(px shr 16) and 0xFF] += 1f
                histG[(px shr 8) and 0xFF] += 1f
                histB[px and 0xFF] += 1f
            }
            totalW = pixels.size.toFloat()
        }
        if (totalW <= 0f) return 1f

        val perc = totalW * 0.01f   // LibRaw auto_bright_thr default (top 1%)
        fun channelWhite(hist: FloatArray): Int {
            var acc = 0f
            for (v in 255 downTo 1) {
                acc += hist[v]
                if (acc > perc) return v
            }
            return 1
        }
        val white = maxOf(channelWhite(histR), channelWhite(histG), channelWhite(histB))
            .coerceIn(1, 255)

        // Work in linear light (the exposure uniform multiplies linear values).
        fun srgbToLinear(v: Float): Float =
            if (v <= 0.04045f) v / 12.92f
            else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

        val cur = srgbToLinear(white / 255f).coerceAtLeast(1e-4f)
        // Map the (subject) top-1% white point to FULL white (1.0 linear) so Smart
        // Bright at max genuinely clips highlights — matches LibRaw
        // auto_bright_thr=0.01. Was srgbToLinear(0.98) = anti-clip.
        val ab = 1.0f / cur
        return ab.coerceIn(1f, 16f)
    }

    /**
     * "Half-strength" multiplier applied to every derived slider value
     * before clamping. Why: the percentile math runs in 8-bit gamma-
     * encoded sRGB *bucket space*, but the sliders feed shaders that
     * work in linear / log-exposure / non-linear curve domains. A
     * full-strength move that looks correct in bucket space over-cooks
     * once the shader applies its own curve. 0.5 anchors the auto
     * pass at ~Lightroom-Auto "balanced" rather than "deeply
     * processed". Users still tune from there.
     */
    private const val TUNING_FACTOR = 0.75f

    private const val TARGET_MEAN_LUMA = 128f

    /** Tonemap-exposure bias seeded when highlights are hot (p995 > 240).
     *  A small whole-image pull-down that smooths the near-clip transition
     *  the highlights slider alone can't. */
    private const val HOT_TONEMAP_BIAS = -0.02f

    /** When hot, scale the highlights-slider pull-down to this fraction —
     *  the remaining recovery comes from [HOT_TONEMAP_BIAS]. */
    private const val HOT_HL_SOFTEN = 0.8f

    /**
     * Maximum `tonemapHighlights` bake-in when Auto Expo lands at full
     * strength (the highlights slider is at its max negative pull,
     * −60). User-observed: every Auto Expo result needs an extra
     * −10 Tonemap-Highlights pull to look right; rather than ask the
     * user to apply it manually each time, we bake it in here scaled
     * by how strong the Auto Expo move was.
     *
     * Concretely: if Auto Expo only pulled highlights to −30 (half
     * strength), the bake is −5; at −60 (max) the bake is −10; at 0
     * (Auto Expo decided no highlights move was needed) the bake is 0.
     */
    private const val AUTO_TONEMAP_HL_BAKE_MAX = -10f

    /**
     * Pixel-count fraction above which a region is considered "channel-clipped"
     * — counting any-of-R/G/B at 254 or 255. This catches the case where the
     * luma p99.5 looks fine (say 200) but the red channel of a saturated sky
     * is already crammed against 255, leaving a magenta cast at the corner.
     * The old luma-only test missed that completely.
     *
     * 0.5% chosen so a single specular highlight doesn't trip the test, but
     * a sky region that occupies even a small portion of the frame does.
     */
    private const val CHANNEL_CLIP_FRACTION = 0.005f

    /**
     * Additional highlight-pull amount applied when channel clip is detected
     * but luma p99.5 isn't hot. This is the "saturated-sky / saturated-red"
     * case: luma reads OK, but one channel is already maxed → user sees
     * colour-clipping. Pull the highlights slider down enough to walk the
     * worst channel back to 250.
     */
    private const val CHANNEL_CLIP_HL_BIAS = -20f

    /** Per-segment whites slider extreme used when a region is severely
     *  channel-clipped (>2% of segment pixels). Pulls only the affected
     *  region's white-point back so the OTHER region's highlights aren't
     *  flattened. */
    private const val SEGMENT_WHITES_PULL = -25f

    // ── Adaptive subject-highlight knee — EMA state ──────────────────
    // Persists across analyse() calls so repeated Auto Expo taps on the
    // same or similar images converge smoothly rather than jumping.
    // Reset to NaN so the first call bootstraps from raw percentiles.
    // α = 0.2: adapts within ~5 calls but ignores single-frame outliers.
    private const val EMA_ALPHA = 0.2f
    @Volatile private var emaThreshold: Float = Float.NaN   // subject P95 luma (0–255)
    @Volatile private var emaRolloff:   Float = Float.NaN   // stddev/2, floored at 10

    /** Resets EMA state — call when the source image changes so stale
     *  smoothing from a previous photo doesn't bleed into the new one. */
    fun resetEma() {
        emaThreshold = Float.NaN
        emaRolloff   = Float.NaN
    }

    /**
     * Auto-derive sensible NR strengths from EXIF ISO. Returns the original
     * fields untouched when [iso] is 0 (no EXIF) or when the user has
     * already set NR sliders (we don't override their choice).
     *
     * Calibrated for the Canon 6D's noise floor: clean below 800, mild grain
     * 800-3200, heavy grain at 6400+, falls apart at 25600+. Blue channel
     * is always pushed slightly harder than luma — Bayer blue has the
     * worst SNR.
     */
    private fun deriveIsoNr(base: UserMacro, iso: Int): UserMacro {
        if (iso <= 0) return base
        // Skip if user already touched any NR slider — respect explicit choice.
        if (base.luminanceNR > 0f || base.colorNR > 0f ||
            base.blueNR > 0f || base.redNR > 0f) return base
        // log2(ISO/100) is the standard "stops above ISO 100" metric.
        // ISO 100 → 0; 800 → 3; 3200 → 5; 12800 → 7; 25600 → 8.
        val stops = (kotlin.math.ln(iso / 100f) / kotlin.math.ln(2f))
            .coerceIn(0f, 9f)
        // Below 3 stops (ISO ≤ 800) leave NR off; above that, ramp linearly
        // up to ~70% at ISO 25600. Caps below 1.0 so users can still push
        // further manually if they want destructive smoothing.
        val t = ((stops - 3f) / 6f).coerceIn(0f, 1f)
        val luma   = t * 0.55f
        val color  = t * 0.65f
        val blue   = t * 0.40f      // extra Cb on top of color
        val red    = t * 0.20f      // mild Cr — red Bayer cells are less noisy
        return base.copy(
            luminanceNR = luma,
            colorNR     = color,
            blueNR      = blue,
            redNR       = red,
        )
    }

    /**
     * Analyse [bitmap] and return a copy of [base] with auto-derived
     * exposure / highlights / shadows / blacks / whites values. If
     * [masks] is non-null, also writes per-segment `whitesSubject` /
     * `whitesBackground` so a saturated-sky-but-subject-is-fine scene
     * pulls back only the sky, not the subject.
     *
     * When [iso] > 0 and the user hasn't set NR sliders, auto-derives
     * luminance/color/blue/red NR strengths from the EXIF ISO.
     */
    fun analyse(
        bitmap: Bitmap,
        base: UserMacro,
        masks: RawSegmentationMasks? = null,
        iso: Int = 0,
        /**
         * Zero-DCE low-light probe score (~0..1). Null = no probe data.
         * When the channel-clip path fires AND this score is non-trivial,
         * AE pushes `claheHighlightsBoost` one step further negative —
         * the "bright bulb in a dim room" case the user wants treated more
         * aggressively than a normal hot-highlight scene.
         */
        zeroDceLightScore: Float? = null,
        /**
         * Subject-protection level from the UI slider (0..1).
         * 0   = no cap — background mean drives exposure freely.
         * 0.5 = mean cap only — subject average luma ≤ MEAN_CAP_CEILING.
         * 1.0 = mean + P95 cap — subject highlights also protected.
         * Interpolated continuously so the user can find the right balance
         * between background lift and subject highlight safety.
         * Defaults to 0.7 (mean cap fully active, P95 cap 40% active).
         */
        subjectProtection: Float = base.aeSubjectProtection,
    ): UserMacro {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return base
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Build a 256-bucket luma histogram + a separate per-channel clip
        // counter. The clip counter is the darktable-style "is any channel
        // saturated?" check the old AE was missing — a luma-only histogram
        // can't tell red-channel-clipped sky apart from neutral midtones.
        val hist = IntArray(256)
        // Per-channel histograms so we can take p99.5 of each — using
        // raw max(R,G,B) was the bug: a single specular pixel would pin
        // it to 255 and falsely report "channel clipped" on every photo,
        // dragging the AE result up. p99.5 (top 0.5% ignored) matches
        // what the luma histogram already does.
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var channelClip = 0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr  8) and 0xFF
            val b =  px         and 0xFF
            val l = (r * 299 + g * 587 + b * 114) / 1000
            hist[l]++
            histR[r]++; histG[g]++; histB[b]++
            if (r >= 254 || g >= 254 || b >= 254) channelClip++
        }
        val total = pixels.size
        val lowCut  = (total * 0.005f).toInt()    // 0.5th percentile
        val highCut = (total * 0.995f).toInt()    // 99.5th percentile

        fun percentile(h: IntArray, target: Int): Int {
            var acc = 0
            for (i in 0..255) {
                acc += h[i]
                if (acc >= target) return i
            }
            return 255
        }
        val p995R = percentile(histR, highCut)
        val p995G = percentile(histG, highCut)
        val p995B = percentile(histB, highCut)

        var p005 = 0
        var p995 = 255
        var cum = 0
        for (i in 0..255) {
            cum += hist[i]
            if (cum >= lowCut)  { p005 = i; break }
        }
        cum = 0
        for (i in 0..255) {
            cum += hist[i]
            if (cum >= highCut) { p995 = i; break }
        }

        // Mean luma — used to decide if the scene is generally
        // underexposed (push exposure up) or balanced.
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        val meanGlobal = (sum / total.coerceAtLeast(1)).toInt().coerceIn(0, 255)

        // When masks are available, compute a mask-weighted background mean
        // so the exposure target is driven by the background/midtones, not
        // a bright subject. A portrait against a dark room has a dark mean
        // globally but the bright subject shouldn't suppress the lift for
        // the rest of the scene — and vice-versa, a bright scene with a
        // dark subject shouldn't over-lift the subject.
        // When masks are available, compute background mean luma (for exposure target),
        // subject mean luma (for exposure cap), and a soft subject luma histogram
        // (for adaptive highlight knee via P95 + stddev).
        var meanForExposure = meanGlobal
        var subjectMeanLuma = meanGlobal  // fallback when no mask
        // Adaptive knee output — filled below when mask is valid, otherwise
        // the EMA state carries its last value (or NaN on first call).
        var subjectP95Raw   = Float.NaN
        var subjectStdRaw   = Float.NaN

        if (masks != null) {
            val refined = masks.refinedMask
            val rw = masks.refinedWidth
            val rh = masks.refinedHeight
            val useRefined = refined != null && rw > 0 && rh > 0 && refined.size >= rw * rh
            val maskW = if (useRefined) rw else RawSegmentationMasks.MASK_SIZE
            val maskH = if (useRefined) rh else RawSegmentationMasks.MASK_SIZE
            val mask  = if (useRefined) refined!! else masks.subjectMask
            if (mask.size >= maskW * maskH) {
                val sx = (maskW - 1f) / (w - 1).coerceAtLeast(1)
                val sy = (maskH - 1f) / (h - 1).coerceAtLeast(1)
                var bgSum = 0.0; var bgWeight = 0.0
                var subjSum = 0.0; var subjWeight = 0.0
                // Soft weighted histogram for subject: accumulate fractional
                // counts so partially-masked edge pixels contribute proportionally.
                val subjHist = FloatArray(256)
                for (y in 0 until h) {
                    val mfy = y * sy; val my0 = mfy.toInt()
                    val my1 = (my0 + 1).coerceAtMost(maskH - 1); val ty = mfy - my0
                    for (x in 0 until w) {
                        val mfx = x * sx; val mx0 = mfx.toInt()
                        val mx1 = (mx0 + 1).coerceAtMost(maskW - 1); val tx = mfx - mx0
                        val m = ((mask[my0*maskW+mx0]*(1-tx)+mask[my0*maskW+mx1]*tx)*(1-ty)
                               + (mask[my1*maskW+mx0]*(1-tx)+mask[my1*maskW+mx1]*tx)*ty)
                            .coerceIn(0f, 1f)
                        val px2 = pixels[y * w + x]
                        val r2 = (px2 shr 16) and 0xFF
                        val g2 = (px2 shr  8) and 0xFF
                        val b2 =  px2         and 0xFF
                        val l2 = (r2 * 299 + g2 * 587 + b2 * 114) / 1000
                        bgSum   += l2 * (1.0 - m); bgWeight   += (1.0 - m)
                        subjSum += l2 * m.toDouble(); subjWeight += m.toDouble()
                        subjHist[l2] += m
                    }
                }
                val bgMean = if (bgWeight > 1.0) (bgSum / bgWeight).toInt().coerceIn(0, 255)
                             else meanGlobal
                subjectMeanLuma = if (subjWeight > 1.0) (subjSum / subjWeight).toInt().coerceIn(0, 255)
                                  else meanGlobal
                // Drive exposure from 70% background mean so a bright subject doesn't
                // suppress the lift the background needs, and a dark background doesn't
                // over-lift an already-bright subject.
                meanForExposure = ((bgMean * 0.70f + meanGlobal * 0.30f).toInt()).coerceIn(1, 255)
                android.util.Log.i("AE_Subject", "masks used: globalMean=$meanGlobal subjMean=$subjectMeanLuma bgMean=$bgMean meanForExp=$meanForExposure")

                // Derive P95 and stddev from the soft subject histogram.
                if (subjWeight > 10.0) {
                    val p95Cut = (subjWeight * 0.95f).toFloat()
                    var acc = 0f; var p95Bin = 255
                    for (i in 0..255) { acc += subjHist[i]; if (acc >= p95Cut) { p95Bin = i; break } }
                    subjectP95Raw = p95Bin.toFloat()
                    // Stddev in luma — Welford-style via E[x²] - E[x]²
                    var sumSq = 0.0
                    for (i in 0..255) sumSq += subjHist[i] * i.toDouble() * i.toDouble()
                    val mean2 = subjectMeanLuma.toDouble()
                    val variance = (sumSq / subjWeight) - mean2 * mean2
                    subjectStdRaw = kotlin.math.sqrt(variance.coerceAtLeast(0.0)).toFloat()
                }
            }
        }

        // ── Derive slider values ────────────────────────────────────
        // Each raw delta is computed in 8-bit bucket space, then scaled
        // by TUNING_FACTOR before clamping. See the constant's doc for
        // why; short version: prevents the auto pass from looking
        // over-cooked once the shader applies its own non-linear curve.
        val ratio = (TARGET_MEAN_LUMA / meanForExposure.coerceAtLeast(1))
            .coerceIn(0.25f, 4f)
        val rawExposure = kotlin.math.ln(ratio) / kotlin.math.ln(2f)
        // ── Dual-cap subject protection ───────────────────────────────
        // Controlled by [subjectProtection] slider (0..1):
        //   0.0 → no cap (background drives freely)
        //   0.5 → mean cap fully engaged
        //   1.0 → mean cap + P95 percentile cap both fully engaged
        //
        // Mean cap ceiling: subject mean luma must not exceed 185 after lift.
        // P95 cap ceiling:  subject P95 luma must not exceed 230 after lift.
        // Both ceilings are applied as EV limits, then the tightest wins.
        //
        // TUNING_FACTOR is NOT applied to the caps — the caps live in raw
        // EV space and are compared against rawExposure before TUNING_FACTOR
        // halves it, so the constraint is correctly tight.
        val prot = subjectProtection.coerceIn(0f, 1f)
        val MEAN_CAP_CEILING = 185f
        val P95_CAP_CEILING  = 230f

        // Subject-protection caps are only meaningful when a segmentation mask
        // has identified which pixels are the subject. Without a mask,
        // subjectMeanLuma == meanGlobal — using the global mean as a cap
        // boundary incorrectly suppresses exposure on photos where the whole
        // scene is dark (normal outdoor/indoor shots). Disable both caps when
        // no mask is available; the luma histogram already prevents blow-out
        // via the hot-highlight path below.
        val hasMask = masks != null

        // Mean cap: active when prot >= 0 (interpolated from 0 to full at prot=0.5)
        val meanCapStrength = if (hasMask) (prot / 0.5f).coerceIn(0f, 1f) else 0f
        val meanCapEv = if (hasMask && subjectMeanLuma > 0 && subjectMeanLuma < MEAN_CAP_CEILING)
            kotlin.math.ln(MEAN_CAP_CEILING / subjectMeanLuma).toFloat() / kotlin.math.ln(2f)
        else 0f
        // No cap = unlimited (large value), full cap = meanCapEv
        val effectiveMeanCap = meanCapEv + (1f - meanCapStrength) * 10f  // lerp toward ∞ when prot=0

        // P95 cap: active when prot > 0.5 (interpolated from 0 to full at prot=1.0)
        val p95CapStrength = if (hasMask) ((prot - 0.5f) / 0.5f).coerceIn(0f, 1f) else 0f
        val p95Luma = if (subjectP95Raw.isFinite() && subjectP95Raw > 0f) subjectP95Raw
                      else subjectMeanLuma.toFloat()
        val p95CapEv = if (hasMask && p95Luma > 0 && p95Luma < P95_CAP_CEILING)
            kotlin.math.ln(P95_CAP_CEILING / p95Luma).toFloat() / kotlin.math.ln(2f)
        else 0f
        val effectiveP95Cap = p95CapEv + (1f - p95CapStrength) * 10f

        val maxExposureForSubject = minOf(effectiveMeanCap, effectiveP95Cap).coerceAtLeast(0f)
        val exposure = (rawExposure * TUNING_FACTOR)
            .coerceIn(-2f, minOf(2f, maxExposureForSubject * TUNING_FACTOR))

        // Highlights: if 99.5p is hot (>240), pull down. If it's dim
        // (<180) leave alone — we already lifted via exposure.
        //
        // When the scene is hot we don't put ALL the recovery into the
        // highlights slider. A small slice goes into a Tonemap-exposure
        // bias (-0.02 EV) instead: the highlights curve only bends the top
        // end, so it can leave a faint magenta/over-bright shoulder right at
        // the clip point, whereas a whole-image tonemap nudge keeps the
        // transition linear. We therefore soften the highlights pull by
        // HOT_HL_SOFTEN when hot and let the tonemap bias carry the rest.
        val isLumaHot = p995 > 220
        // Channel clip awareness: if >0.5% of pixels have any channel at
        // 254-255, the scene is colour-clipped even if luma looks fine.
        val isChannelClipped = channelClip > (total * CHANNEL_CLIP_FRACTION).toInt()
        val isHot = isLumaHot || isChannelClipped

        // Highlights pull. The old branch only considered luma-p995. If
        // channels are clipped but luma isn't, add a fixed bias so the
        // saturated-red/blue case (sky, sunset, neon) gets walked back.
        //
        // Highlight guard (2026-08-28, mirror of the black-floor guard): when
        // the top end still CARRIES data (hot p99.5 but below true clip), the
        // recovery must be a SLIGHT, linear pull confined to the highlight
        // section — slope halved (×2, was ×4) and cap −25 (was −60) — so a
        // well-exposed midtone is never dragged down with it. Truly clipped
        // pixels have nothing to recover and are handled by `whites` below.
        val rawHighlights = when {
            isLumaHot              -> -((p995 - 240) * 2f) * HOT_HL_SOFTEN
            isChannelClipped       -> CHANNEL_CLIP_HL_BIAS / TUNING_FACTOR
            p995 < 180             -> ((180 - p995) * 1.5f)
            else                   -> 0f
        }
        // Same containment as whites — cap positive lift to 0. AE can pull
        // highlights down (recovery), never push them up.
        val highlights = (rawHighlights * TUNING_FACTOR).coerceIn(-25f, 0f)

        // Tonemap-exposure safety bias. Fires on EITHER luma-hot OR
        // channel-clipped scenes — both want the linear top-end nudge.
        val tonemapExposure = if (isHot) HOT_TONEMAP_BIAS else 0f

        // Tonemap-Highlights bake-in. Proportional to how strongly Auto
        // Expo pulled the Highlights slider down: at full strength
        // (highlights ≈ −60 after clamping), bake the max value
        // (−10). Lighter Auto Expo moves bake proportionally less.
        // Auto-Expo moves that don't pull highlights at all (dim
        // scenes) bake 0 so we don't crush highlights that didn't
        // need pulling. Strength is derived from the *negative*
        // portion of `highlights` only — positive highlights values
        // (Auto Expo lifting a dim scene) don't trigger a bake.
        val hlStrength = ((-highlights) / 60f).coerceIn(0f, 1f)
        val tonemapHighlights = (AUTO_TONEMAP_HL_BAKE_MAX * hlStrength)
            .coerceIn(AUTO_TONEMAP_HL_BAKE_MAX, 0f)

        // Shadows: if 0.5p is too deep (<10), lift; if floating (>30),
        // crush a bit so blacks read black. Crush HALVED and floored at −12
        // (was ×1.5 / −30): together with the old blacks nudge below it drove
        // lifted-black JPEGs to solid 0 (see the black-floor note there).
        val rawShadows = when {
            p005 < 10 -> ((10 - p005) * 4f)
            p005 > 30 -> -((p005 - 30) * 0.75f)
            else      -> 0f
        }
        val shadows = (rawShadows * TUNING_FACTOR).coerceIn(-12f, 60f)

        // Whites / Blacks: estimate where p005/p995 will land AFTER the
        // (already-tuned) exposure lift, then nudge those points toward
        // 250 / 0 respectively. Using the final exposure value (not the
        // raw ratio) prevents the whites slider from over-pulling once
        // exposure has already brought the highlights closer to 250.
        // Use the brightest per-channel p99.5 so a 200-luma-but-red-
        // channel-clipped sky still pulls the whites slider down. p99.5
        // ignores the top 0.5% of pixels per channel, so a single hot
        // spot can't tip this — only a meaningful region of clipping
        // does. Was previously raw max(R,G,B), which pinned to 255 on
        // virtually every photo and over-pulled the white-point.
        val effectiveTop = maxOf(p995, p995R, p995G, p995B)
        val exposureScale = Math.pow(2.0, exposure.toDouble()).toFloat()
        val estimatedP995 = effectiveTop * exposureScale
        val rawWhites = (250f - estimatedP995)
        // Whites (2026-08-28 highlight guard — split by whether the top end
        // carries recoverable data):
        //
        //  • NO pixel data (a meaningful region truly clipped, or the
        //    brightest channel's p99.5 at the ceiling): preserve TOTAL WHITE.
        //    Keep the +40 push so near-clip tinted speckles (LMMSE demosaic)
        //    clip to clean white instead of dirtying the blown area — greying
        //    an empty highlight only makes mud.
        //
        //  • Pixel data present: never push it over the clip edge (the old
        //    behaviour applied +40 unconditionally, destroying recoverable
        //    detail). If exposure would carry p99.5 past 250, recover with a
        //    SLIGHT linear pull (×0.5, cap −20) confined to the top of the
        //    histogram; if it stays under 250, leave the highlights alone —
        //    a correctly exposed top end needs neither push nor pull.
        val topTrulyClipped = isChannelClipped || effectiveTop >= 254f
        val whites = when {
            topTrulyClipped   -> 40f
            rawWhites < 0f    -> (rawWhites * 0.5f * TUNING_FACTOR).coerceIn(-20f, 0f)
            else              -> 0f
        }
        android.util.Log.i("RawAutoExposure", "whites: effectiveTop=$effectiveTop estimatedP995=$estimatedP995 rawWhites=$rawWhites → whites=$whites")

        val estimatedP005 = p005 * exposureScale
        // Black-floor guard (2026-08-28). The old rule was `rawBlacks =
        // -estimatedP005` capped at −30 — i.e. deliberately drive the darkest
        // 0.5% of pixels to EXACTLY 0. Measured on a real lifted-black JPEG
        // (cat photo): toggling AI Expose took the preview's p0.5 from 11 to
        // 0 and pushed 9% of the whole frame below luma 8 — solid black
        // shadows, which reads as a defect, not an exposure fix. Photographic
        // norm wants blacks ANCHORED, not obliterated: nudge p005 toward a
        // soft floor of ~8 (deep shadow that still carries tonal separation,
        // CLAHE-style, into the dark midtones) and cap the move at −8 slider
        // units so the crush is always "slightly darker", never "black hole".
        val rawBlacks = -(estimatedP005 - 8f).coerceAtLeast(0f)
        val blacks = (rawBlacks * TUNING_FACTOR).coerceIn(-8f, 0f)

        // Ambiance write REMOVED from the recovery path (2026-08-28). It was a
        // −0.16 local-contrast flatten on every hot scene — a detail/clarity
        // side effect riding along with what should be a tone-only recovery
        // (highlight recovery must not alter colour, detail, or clarity).
        val ambianceGlobal = 0f

        // ── Per-segment channel-clip distribution ───────────────────
        // When masks are present, route the global highlights / whites /
        // ambiance writes into per-segment slots scaled by each region's
        // channel-clip percentage. The region with the most clipping gets
        // the full pull; the other gets a fraction proportional to its own
        // clip%. This is the "subject not dragged down with sky" fix made
        // continuous instead of a binary threshold.
        var highlightsForGlobal     = highlights
        var whitesForGlobal         = whites
        var ambianceForGlobal       = ambianceGlobal
        var highlightsSubject       = base.highlightsSubject
        var highlightsBackground    = base.highlightsBackground
        var whitesSubject           = base.whitesSubject
        var whitesBackground        = base.whitesBackground
        var ambianceSubject         = base.ambianceSubject
        var ambianceBackground      = base.ambianceBackground

        if (masks != null) {
            val (subjClipFrac, bgClipFrac) = computeSegmentClipFractions(pixels, w, h, masks)

            // ── Adaptive highlight knee for subject ──────────────────────
            // When we have a valid P95 from the subject histogram, run EMA
            // smoothing and derive the subject highlight/whites pulls from
            // the knee rather than from the global clip-fraction weighting.
            // This is scene-aware: a bright outdoor portrait lifts the knee
            // to ~220 (preserving sparkle); a dim indoor shot brings it down
            // to ~190 (protecting skin midtones).
            val hasAdaptiveKnee = subjectP95Raw.isFinite() && subjectStdRaw.isFinite()
            if (hasAdaptiveKnee) {
                val rolloffRaw = maxOf(10f, subjectStdRaw / 2f)
                // Bootstrap EMA on first call; update every subsequent call.
                val tSmooth = if (emaThreshold.isNaN()) subjectP95Raw
                              else EMA_ALPHA * subjectP95Raw + (1f - EMA_ALPHA) * emaThreshold
                val rSmooth = if (emaRolloff.isNaN()) rolloffRaw
                              else EMA_ALPHA * rolloffRaw   + (1f - EMA_ALPHA) * emaRolloff
                emaThreshold = tSmooth
                emaRolloff   = rSmooth

                // Translate knee into slider-domain pulls.
                //   • highlightsSubject: proportional to how far P95 is below 255.
                //     High knee (bright scene) → mild pull; low knee (dark scene) → stronger pull.
                //     Mapped to the −60..0 highlights range.
                //   • whitesSubject: soft rolloff width drives how gentle the white-point
                //     compression is. Wide rolloff (high contrast) → gentler whites pull.
                //     Narrow rolloff (flat scene) → tighter pull.
                // Both capped so AE never pushes highlights/whites UP on the subject.
                val kneeGap = (255f - tSmooth).coerceIn(0f, 255f)   // how far below clip
                // Pull strength: full pull at knee=0 (subject is already clipping), zero
                // pull at knee=255 (no headroom needed). Normalised to -60 slider range.
                val hlPullSubj = (-kneeGap / 255f * 60f * TUNING_FACTOR).coerceIn(-60f, 0f)
                // Whites: rolloff factor softens the pull — wide rolloff = high contrast =
                // halve the whites bite; narrow rolloff = flat scene = full whites pull.
                val rolloffFactor = (rSmooth / 50f).coerceIn(0.5f, 1.5f)  // 50 = "medium" stddev/2
                // Positive whites: push through without rolloff dampening (push is uniform).
                val whitesPullSubj = if (whites >= 0f) whites
                                     else (whites / rolloffFactor).coerceIn(-30f, 0f)

                highlightsSubject = hlPullSubj
                whitesSubject     = whitesPullSubj
                ambianceSubject   = ambianceGlobal

                // Background still routed via clip-fraction weighting so sky/background
                // recovery is independent of the adaptive subject knee.
                val peak = maxOf(subjClipFrac, bgClipFrac)
                if (peak > 0.005f) {
                    val wBg = (bgClipFrac / peak).coerceIn(0f, 1f)
                    highlightsBackground = highlights * wBg
                    whitesBackground     = whites * wBg
                    ambianceBackground   = ambianceGlobal * wBg
                } else {
                    highlightsBackground = highlights
                    whitesBackground     = whites
                    ambianceBackground   = ambianceGlobal
                }
                highlightsForGlobal = 0f
                whitesForGlobal     = 0f
                ambianceForGlobal   = 0f
            } else {
                // No subject histogram available — fall back to the original
                // clip-fraction routing for both segments.
                val peak = maxOf(subjClipFrac, bgClipFrac)
                if (peak > 0.005f) {
                    val wSubj = (subjClipFrac / peak).coerceIn(0f, 1f)
                    val wBg   = (bgClipFrac   / peak).coerceIn(0f, 1f)
                    highlightsSubject    = highlights * wSubj
                    highlightsBackground = highlights * wBg
                    whitesSubject        = whites * wSubj
                    whitesBackground     = whites * wBg
                    ambianceSubject      = ambianceGlobal * wSubj
                    ambianceBackground   = ambianceGlobal * wBg
                    highlightsForGlobal  = 0f
                    whitesForGlobal      = 0f
                    ambianceForGlobal    = 0f
                }
            }
        }

        // Zero-DCE low-light + channel-clip combo: when both signals fire,
        // pull CLAHE highlights one extra step (~ -0.15 on top of the
        // CAMERA_STYLE_FINISH baseline of -0.30). Scale by the low-light
        // score so a moderately-dark scene gets a small extra pull and a
        // very-dark scene gets the full step. Only triggers when channels
        // are actually clipping — bright outdoor low-light shouldn't bake
        // in a heavy CLAHE pull just because the scene is sunny.
        val claheBoostExtra = if (isChannelClipped && zeroDceLightScore != null) {
            // Map score 0.15..0.40 → 0.0..-0.15 extra pull. Anything below
            // 0.15 is "bright" so no extra; anything above 0.40 caps.
            val t = ((zeroDceLightScore - 0.15f) / 0.25f).coerceIn(0f, 1f)
            -0.15f * t
        } else 0f

        Log.i("AE_DIAG", "gMean=$meanGlobal bgMean=$meanForExposure sbjMean=$subjectMeanLuma " +
            "p995=$p995 expo=${"%.3f".format(exposure)} maxExpoSubj=${"%.3f".format(maxExposureForSubject)} " +
            "hlSubj=${"%.1f".format(highlightsSubject)} wSubj=${"%.1f".format(whitesSubject)} " +
            "P95=${"%.0f".format(subjectP95Raw)} std=${"%.0f".format(subjectStdRaw)}")
        android.util.Log.i("AE_Seg", "hlSubj=$highlightsSubject hlBg=$highlightsBackground wSubj=$whitesSubject wBg=$whitesBackground exp=$exposure hasMasks=${masks != null}")
        val withTone = base.copy(
            exposure          = exposure,
            highlights        = highlightsForGlobal,
            shadows           = shadows,
            whites            = whitesForGlobal,
            blacks            = blacks,
            tonemapExposure   = tonemapExposure,
            tonemapHighlights = tonemapHighlights,
            ambiance          = ambianceForGlobal,
            whitesSubject     = whitesSubject,
            whitesBackground  = whitesBackground,
            highlightsSubject    = highlightsSubject,
            highlightsBackground = highlightsBackground,
            ambianceSubject      = ambianceSubject,
            ambianceBackground   = ambianceBackground,
            aeSubjectProtection  = subjectProtection,
            claheHighlightsBoost = (base.claheHighlightsBoost + claheBoostExtra)
                .coerceIn(-0.5f, 0.5f),
        )
        return deriveIsoNr(withTone, iso)
    }

    /**
     * Fixed, always-on highlight/whites-only variant of [analyse]. Never touches
     * exposure, shadows, blacks, tonemap, or ambiance — see
     * highlight-protection-pass spec Requirement 2.
     *
     * Delegates all histogram work to [analyse] with a neutral base and then
     * masks the output down to the allowed field set. The `highlights` value is
     * defensively clamped to ≤ 0 (recovery only, never positive push) even though
     * [analyse]'s existing coerceIn already guarantees this — kept as a boundary
     * safety net in case [analyse]'s clamp range changes for full-AE callers.
     *
     * Does NOT call [deriveIsoNr] — NR sliders are out of scope for the
     * highlight protection pass.
     */
    fun analyseHighlightsOnly(
        bitmap: Bitmap,
        masks: RawSegmentationMasks? = null,
    ): UserMacro {
        val raw = analyse(
            bitmap = bitmap,
            base = UserMacro(),
            masks = masks,
            iso = 0,
            zeroDceLightScore = null,
            subjectProtection = 0f,
        )
        // Whites deliberately left at 0 — the old HPP path often wrote
        // whites≈-20 via analyse()'s hot-top coerceIn, which feathered
        // recoverable highlights on every fresh open. Highlights pulls stay;
        // user macros / saved workspaces that store whites explicitly are
        // untouched (this only affects the auto baseline).
        return UserMacro(
            highlights = raw.highlights.coerceAtMost(0f),
            whites = 0f,
            highlightsSubject = raw.highlightsSubject,
            highlightsBackground = raw.highlightsBackground,
            whitesSubject = 0f,
            whitesBackground = 0f,
        )
    }

    /**
     * Walk every pixel and accumulate two channel-clip counters, one per
     * segment (subject vs background). The mask is bilinearly sampled
     * because it lives at a fixed 320×320 resolution while the analysis
     * bitmap can be any size (typically the 512-long-side neutral).
     * Returns (subjectClipFraction, backgroundClipFraction).
     */
    private fun computeSegmentClipFractions(
        pixels: IntArray, w: Int, h: Int,
        masks: RawSegmentationMasks,
    ): Pair<Float, Float> {
        // Prefer the guided-filter-refined mask when present — its edges
        // snap to actual photo boundaries, so per-segment clip counts pick
        // up only pixels that really sit inside the subject silhouette.
        val refined = masks.refinedMask
        val rw = masks.refinedWidth
        val rh = masks.refinedHeight
        val useRefined = refined != null && rw > 0 && rh > 0 && refined.size >= rw * rh
        val maskW = if (useRefined) rw else RawSegmentationMasks.MASK_SIZE
        val maskH = if (useRefined) rh else RawSegmentationMasks.MASK_SIZE
        val mask  = if (useRefined) refined!! else masks.subjectMask
        if (mask.size < maskW * maskH) return 0f to 0f
        var subjClip = 0f; var subjArea = 0f
        var bgClip   = 0f; var bgArea   = 0f
        val sx = (maskW - 1f) / (w - 1).coerceAtLeast(1)
        val sy = (maskH - 1f) / (h - 1).coerceAtLeast(1)
        for (y in 0 until h) {
            val mfy = y * sy
            val my0 = mfy.toInt()
            val my1 = (my0 + 1).coerceAtMost(maskH - 1)
            val ty = mfy - my0
            val row = y * w
            for (x in 0 until w) {
                val mfx = x * sx
                val mx0 = mfx.toInt()
                val mx1 = (mx0 + 1).coerceAtMost(maskW - 1)
                val tx = mfx - mx0
                val m00 = mask[my0 * maskW + mx0]
                val m10 = mask[my0 * maskW + mx1]
                val m01 = mask[my1 * maskW + mx0]
                val m11 = mask[my1 * maskW + mx1]
                val m  = ((m00 * (1 - tx) + m10 * tx) * (1 - ty)
                       + (m01 * (1 - tx) + m11 * tx) * ty).coerceIn(0f, 1f)
                val bm = 1f - m
                val px = pixels[row + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr  8) and 0xFF
                val b =  px         and 0xFF
                val clipped = if (r >= 254 || g >= 254 || b >= 254) 1f else 0f
                subjClip += m  * clipped; subjArea += m
                bgClip   += bm * clipped; bgArea   += bm
            }
        }
        val subjFrac = if (subjArea > 1f) subjClip / subjArea else 0f
        val bgFrac   = if (bgArea   > 1f) bgClip   / bgArea   else 0f
        return subjFrac to bgFrac
    }
}
