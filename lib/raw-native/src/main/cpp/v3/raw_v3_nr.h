/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Edge-aware noise reduction, baked into the source buffer (Stage B AHB for
 * preview, Stage C decode for export) on slider release — same "spatial
 * pre-op bakes into the source" model as RawV3Clahe. Templated on storage
 * type so __fp16 (Stage B) and float (Stage C) share one implementation.
 *
 * Two independent passes, each gated by its own strength:
 *   • Luminance NR — bilateral-style blur on luma, preserving edges (range
 *     weight keyed on luma difference). Chroma is carried untouched.
 *   • Chroma NR    — wider box blur on the chroma (R-Y / B-Y) channels only;
 *     colour noise is low-frequency so a plain blur is both cheaper and
 *     visually correct. Luma is untouched.
 *
 * Operating space: the buffers are gamma-encoded sRGB (Stage A writes them
 * that way). We denoise in that space — matches where the eye perceives
 * noise and avoids a round-trip through linear.
 */

#ifndef RAW_V3_NR_H
#define RAW_V3_NR_H

namespace raw_v3 {

/**
 * Apply luminance + chroma noise reduction in place over [pixels].
 *
 * Luma strength is spatially modulated by the optional subject mask + an
 * internal edge map so flat background regions get full denoise, sharp edges
 * and the U²-Net subject get progressively less:
 *
 *   background flat:        100%
 *   background sharp edge:   75%
 *   subject flat:            50%
 *   subject sharp edge:      25%
 *
 * Pass nullptr / size 0 for `subjectMask` to disable the modulation (full
 * uniform strength). Chroma NR is unaffected — colour noise is low-frequency
 * and a global blur is correct.
 *
 * @tparam T          Pixel storage type: __fp16 (Stage B) or float (Stage C).
 * @param pixels      Interleaved buffer, [strideInPixels * h] of [channels].
 * @param w,h         Image dims in pixels.
 * @param strideInPixels Row stride in pixels (>= w).
 * @param channels    Elements per pixel (4 = RGBA Stage B, 3 = RGB Stage C).
 * @param lumaNR      [0..1] luminance NR strength. 0 = skip the luma pass.
 * @param chromaNR    [0..1] chroma NR strength. 0 = skip the chroma pass.
 * @param blueNR      [0..1] EXTRA Cb-only smoothing applied on top of
 *                    chromaNR. Bayer-sensor blue channel typically carries
 *                    the most chroma noise (lowest WB gain → lowest SNR);
 *                    routing additional smoothing into Cb attacks that
 *                    specifically without flattening reds/yellows the way
 *                    pushing chromaNR alone would. 0 = same behaviour as
 *                    before.
 * @param redNR       [0..1] EXTRA Cr-only smoothing, mirror of blueNR.
 *                    Some Canon 6D high-ISO shots show red speckle in shadows
 *                    (red Bayer cells have moderate WB gain but still less
 *                    than green); this lets the user knock it back without
 *                    touching Cb.
 * @param subjectMask Low-res U²-Net mask (0=bg, 1=subject) or nullptr.
 * @param maskW,maskH Mask dims; pass 0,0 (or null mask) to disable modulation.
 */
template <typename T>
void applyNoiseReduction(
    T* pixels,
    int w,
    int h,
    int strideInPixels,
    int channels,
    float lumaNR,
    float chromaNR,
    float blueNR = 0.f,
    float redNR = 0.f,
    const float* subjectMask = nullptr,
    int maskW = 0,
    int maskH = 0);

} // namespace raw_v3

#endif // RAW_V3_NR_H
