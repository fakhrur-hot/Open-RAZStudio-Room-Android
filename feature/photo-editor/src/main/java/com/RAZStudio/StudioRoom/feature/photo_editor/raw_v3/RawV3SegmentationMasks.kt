/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW v3 — Segmentation masks (M12.2a).
 *
 *  Mirrors v2's [RawSegmentationMasks] structure verbatim so the v3
 *  editor can share data shape with the v2 tab Composables when those
 *  get re-ported at M12.2b. Re-implementing rather than re-using the v2
 *  type keeps v3's package boundary clean: at M12.3 the entire v2
 *  `raw/` package gets deleted and v3 stays compileable.
 *
 *  Both masks live at 320×320 (U2Net's native inference resolution).
 *  Before applying to a full-resolution image, bilinearly upscale.
 *  [backgroundMask] is derived on-demand as `1 − subjectMask`.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

data class RawV3SegmentationMasks(
    /** Subject saliency in [0, 1]. Row-major, 320×320. */
    val subjectMask: FloatArray,
    /** Sobel edge magnitude in [0, 1]. Row-major, 320×320. */
    val edgeMask: FloatArray,
    /**
     * UV rectangle in mask space [0..1] where the actual source image
     * lives — the rest of the mask is letterbox padding. Defaults cover
     * the full mask (legacy callers that don't letterbox). The renderer
     * remaps source UV `(u, v)` into this rectangle before sampling, so
     * the mask aligns 1:1 with the source regardless of aspect ratio.
     */
    val innerRectLeft:   Float = 0f,
    val innerRectTop:    Float = 0f,
    val innerRectRight:  Float = 1f,
    val innerRectBottom: Float = 1f,

    /**
     * Optional guided-filter-refined matte at [REFINED_LONG_SIDE] long-side,
     * computed against a linear-light luma guide from Stage A. AE prefers
     * this over [subjectMask] for edge-sensitive operations.
     */
    val refinedMask:    FloatArray? = null,
    val refinedWidth:   Int = 0,
    val refinedHeight:  Int = 0,
) {
    /**
     * Fraction of the 320² grid whose subject probability exceeds 0.5.
     * Diagnostic + gate: an (almost) empty mask must NOT be treated as a
     * usable subject — with the person-only DeepLab fallback (used whenever
     * BiRefNet is skipped for memory) a non-person subject yields a blank
     * mask, and a blank mask that is "ready" makes Bokeh blur the whole frame
     * and Bloom "Protect subject" protect nothing. Consumers use [hasSubject].
     */
    val subjectCoverage: Float by lazy {
        if (subjectMask.isEmpty()) 0f else {
            var n = 0
            for (v in subjectMask) if (v > 0.5f) n++
            n.toFloat() / subjectMask.size
        }
    }

    /** True when the mask actually contains a subject (see [subjectCoverage]). */
    val hasSubject: Boolean get() = subjectCoverage >= MIN_SUBJECT_COVERAGE

    /**
     * The mask to hand to any subject-GATING consumer (GL upload, Stage B
     * spatial passes, Stage C export): [bestMask] when a subject exists, else
     * null so those paths behave exactly as "no mask" (Bokeh off, effects
     * ungated) instead of gating against an empty matte.
     */
    fun gatingMask(): Triple<FloatArray, Int, Int>? = if (hasSubject) bestMask() else null

    /** Lazy inverse of [subjectMask]. */
    val backgroundMask: FloatArray
        get() = FloatArray(subjectMask.size) { i -> (1f - subjectMask[i]).coerceIn(0f, 1f) }

    /**
     * Alpha-weighted centroid of the subject mask in normalised [0, 1]
     * coordinates. Returns null when the mask is effectively empty so
     * callers (Vignette tab auto-snap, Gradient tab auto-anchor) can
     * fall back to image centre. Same maths as v2.
     */
    val subjectCenterNormalized: Pair<Float, Float>?
        get() {
            var sumX = 0.0
            var sumY = 0.0
            var sumW = 0.0
            var i = 0
            for (y in 0 until MASK_SIZE) {
                for (x in 0 until MASK_SIZE) {
                    val a = subjectMask[i].toDouble()
                    if (a > 0.0) {
                        sumX += x * a
                        sumY += y * a
                        sumW += a
                    }
                    i++
                }
            }
            if (sumW < 1.0) return null
            val cx = (sumX / sumW / (MASK_SIZE - 1)).toFloat().coerceIn(0f, 1f)
            val cy = (sumY / sumW / (MASK_SIZE - 1)).toFloat().coerceIn(0f, 1f)
            return cx to cy
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawV3SegmentationMasks) return false
        return subjectMask.contentEquals(other.subjectMask) &&
               edgeMask.contentEquals(other.edgeMask) &&
               innerRectLeft == other.innerRectLeft &&
               innerRectTop == other.innerRectTop &&
               innerRectRight == other.innerRectRight &&
               innerRectBottom == other.innerRectBottom
    }

    override fun hashCode(): Int {
        var result = subjectMask.contentHashCode()
        result = 31 * result + edgeMask.contentHashCode()
        result = 31 * result + innerRectLeft.hashCode()
        result = 31 * result + innerRectTop.hashCode()
        result = 31 * result + innerRectRight.hashCode()
        result = 31 * result + innerRectBottom.hashCode()
        return result
    }

    /**
     * The mask every CPU consumer should sample — the SAME one the GL preview
     * uploads (RawV3PreviewComposable): the guided-filter refined 1024px
     * image-aspect mask when present, else the raw 320² model output.
     * Returns (data, width, height). Keeps preview = export on subject gating.
     */
    fun bestMask(): Triple<FloatArray, Int, Int> {
        val r = refinedMask
        return if (r != null && refinedWidth > 0 && refinedHeight > 0 && r.size == refinedWidth * refinedHeight)
            Triple(r, refinedWidth, refinedHeight)
        else Triple(subjectMask, MASK_SIZE, MASK_SIZE)
    }

    companion object {
        /** U2Net's native inference resolution. */
        const val MASK_SIZE = 320
        /** Minimum [subjectCoverage] (0.5% of the frame) to count as a real subject. */
        const val MIN_SUBJECT_COVERAGE = 0.005f

        /** Long-side of the optional guided-filter-refined mask. */
        const val REFINED_LONG_SIDE = 1024
    }
}
