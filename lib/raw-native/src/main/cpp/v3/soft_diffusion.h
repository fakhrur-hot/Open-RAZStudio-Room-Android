// Soft-diffusion bake into the Karis bloom plane.
//
// Why bake (not a second main-pass sampler): GLES fragment shaders here are
// hard-capped at 16 texture image units; every unit is already claimed
// (uBlurTex=8 is shared by Bokeh/Ambiance/Clarity/FX). Soft diffusion used
// to sample that same uBlurTex, so when Bokeh won the radius branch the
// softDiff mix replaced chromatic/mist halation with a wide bokeh plane.
//
// Preview: GlesRenderer runs a dedicated Gaussian into softDiffTex_, then
// composites into bloomTex_[0] before the uber-shader. Export: Stage C /
// OffscreenSaveRenderer call bakeSoftDiffusionIntoBloom() on the Karis
// buffer with a matching Gaussian. apply_macro / uber-shader softDiff
// blocks are removed — bloom already carries the enhancement.

#pragma once

#include <algorithm>
#include <cmath>

namespace raw_v3 {

inline float softDiffSmoothstep(float a, float b, float x) {
    if (b <= a) return x >= b ? 1.f : 0.f;
    float t = (x - a) / (b - a);
    if (t < 0.f) t = 0.f;
    else if (t > 1.f) t = 1.f;
    return t * t * (3.f - 2.f * t);
}

/** Mix soft-diffusion Gaussian into Karis bloom in-place (RGBF32 planar).
 *  Mirrors the former uber-shader Orton (0.65) + Glow (0.55) softDiff blocks. */
inline void bakeSoftDiffusionIntoBloom(float* bloomRgb,
                                       const float* softDiffRgb,
                                       int pixelCount,
                                       float ortonStrength,
                                       float glowStrength) {
    if (!bloomRgb || !softDiffRgb || pixelCount <= 0) return;
    const bool doOrton = ortonStrength > 0.f;
    const bool doGlow  = glowStrength > 0.f;
    if (!doOrton && !doGlow) return;

    for (int i = 0; i < pixelCount; ++i) {
        float* b = bloomRgb + size_t(i) * 3;
        const float* s = softDiffRgb + size_t(i) * 3;
        const float softL = s[0] * 0.2126f + s[1] * 0.7152f + s[2] * 0.0722f;
        const float softGate = softDiffSmoothstep(0.25f, 0.70f, softL);

        auto mixMax = [&](float strengthScale) {
            const float softAmt = strengthScale * softGate;
            if (softAmt <= 0.f) return;
            for (int c = 0; c < 3; ++c) {
                const float mx = b[c] > s[c] ? b[c] : s[c];
                b[c] = b[c] + (mx - b[c]) * softAmt;
            }
        };
        if (doOrton) mixMax(std::min(1.f, ortonStrength) * 0.65f);
        if (doGlow)  mixMax(std::min(1.f, glowStrength)  * 0.55f);
    }
}

}  // namespace raw_v3
