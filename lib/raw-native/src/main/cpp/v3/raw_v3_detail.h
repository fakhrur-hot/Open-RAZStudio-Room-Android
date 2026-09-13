/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Detail-tab spatial ops, baked into the source buffer (Stage B AHB / Stage C
 * decode) on slider release — same model as RawV3Clahe / RawV3Nr. Ported from
 * the v2 MacroProcessor implementations so the look matches:
 *
 *   • Sharpness + Texture — radius-1 unsharp mask, blended 0.6/0.4 (v2).
 *   • Clarity            — radius-5 unsharp mask gated to midtones by the
 *                          parabolic bell 4·L·(1-L) (v2 local contrast).
 *   • Smart Sharpness    — edge-magnitude-gated unsharp (sharpen only high-
 *                          edge pixels). Optionally feathered by a subject
 *                          mask so the effect concentrates on the subject.
 *   • Film Grain         — per-block random noise, midtone-weighted unless
 *                          uniformity=1, plus a wash-out (lifted-black) LUT.
 *   • Smooth Background  — variance-masked, luma/chroma-split edge-preserving
 *                          smoothing for buttery out-of-focus (bokeh) areas.
 *                          Strength scales with (1 - local detail) and is
 *                          guarded by the subject mask so the in-focus subject
 *                          is never dissolved. Chroma gets a large-radius
 *                          guided blur; luma a gentle one (keeps faint grain).
 *
 * Values are gamma-encoded sRGB in [0,1] (may exceed 1.0 in highlights). The
 * v2 math used 0..255 ints; here we operate in normalised float (÷255 folded
 * into the constants) so highlight headroom is preserved.
 */

#ifndef RAW_V3_DETAIL_H
#define RAW_V3_DETAIL_H

namespace raw_v3 {

struct DetailParams {
    float sharpness          = 0.f;  // [0..1]
    float smartSharpness     = 0.f;  // [0..1]
    float clarity            = 0.f;  // [-1..1]
    // img.ly-style midtone "pop": exposure lift coupled to clarity, gated by the
    // same midtone mask. 0 = classic clarity (local contrast only). [0..1].
    float clarityLift        = 0.f;
    float texture            = 0.f;  // [-1..1]
    float filmGrain          = 0.f;  // [0..1]
    float filmGrainSize      = 0.5f; // [0..1]
    float filmGrainUniformity= 0.f;  // [0..1]
    float filmGrainWashOut   = 0.f;  // [0..1]
    float smoothBackground   = 0.f;  // [0..1] OOF/bokeh smoothing strength

    bool any() const {
        // Film grain + wash-out moved to the GL shader / Stage C apply_macro,
        // so they no longer trigger this CPU detail bake.
        return sharpness > 0.f || smartSharpness > 0.f ||
               clarity != 0.f || texture != 0.f ||
               smoothBackground > 0.f;
    }
};

/**
 * Apply the detail ops in place over [pixels].
 *
 * @tparam T            __fp16 (Stage B) or float (Stage C).
 * @param pixels        Interleaved buffer, [strideInPixels*h] of [channels].
 * @param w,h           Image dims.
 * @param strideInPixels Row stride in pixels.
 * @param channels      Elements per pixel (4 RGBA / 3 RGB).
 * @param p             Effect strengths.
 * @param subjectMask   Optional row-major [0,1] subject probability, sized
 *                      [maskW]×[maskH] (bilinearly sampled). Pass nullptr to
 *                      disable subject feathering (smart sharpness then applies
 *                      uniformly, edge-gated only).
 */
template <typename T>
void applyDetail(
    T* pixels,
    int w,
    int h,
    int strideInPixels,
    int channels,
    const DetailParams& p,
    const float* subjectMask,
    int maskW,
    int maskH);

} // namespace raw_v3

#endif // RAW_V3_DETAIL_H
