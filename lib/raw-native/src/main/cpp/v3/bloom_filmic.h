/*
 * StudioRoom — filmic / Black Pro-Mist Karis upsample weights.
 * Shared by GlesRenderer::runKarisBloomPass and OffscreenSaveRenderer::computeKarisBloom
 * so preview and Stage C stay bit-identical on the pyramid mix.
 *
 * Target mix (tightness ≈ 0.55): mip0 core left in the extract, then additive
 * contributions roughly mip1~0.45, mip2~0.35, mip3~0.10; mips 4–5 choked.
 * Higher tightness biases toward mip1 (razor halo); lower opens mip2 (softer).
 */
#pragma once

#include <algorithm>

namespace raw_v3 {

inline void filmicBloomUpWeights(float tightness, float wOut[6]) {
    const float t = std::max(0.f, std::min(1.f, tightness));
    // wOut[i] = weight when upsampling mip[i] into mip[i-1] (i >= 1).
    wOut[0] = 0.f;
    wOut[1] = 0.45f + 0.25f * t;
    wOut[2] = 0.35f - 0.20f * t;
    wOut[3] = 0.10f - 0.08f * t;
    wOut[4] = 0.02f * (1.f - t);
    wOut[5] = 0.f;
    float sum = wOut[1] + wOut[2] + wOut[3] + wOut[4] + wOut[5];
    if (sum > 1e-6f) {
        const float k = 0.90f / sum;   // leave ~0.10 energy to mip0 extract
        for (int i = 1; i < 6; ++i) wOut[i] *= k;
    }
}

/**
 * Cinematic vertical-oval radii (taller than wide) for Karis upsample.
 * [bloomShape] 1 = base portrait oval; <1 exaggerates vertical; >1 widens.
 * Shared by preview + Stage C (preview=export).
 */
inline void filmicBloomOvalRadii(float radiusPx, float bloomShape,
                                 float& outRx, float& outRy) {
    const float r = std::max(0.f, radiusPx);
    float shape = bloomShape;
    if (shape <= 0.f) shape = 1.f;
    shape = std::max(0.4f, std::min(1.6f, shape));
    // Base cinematic: ~0.55× wide, 1.0× tall → soft vertical oval.
    float rx = r * 0.55f;
    float ry = r;
    if (shape < 1.f) {
        // More portrait-tall: narrow further.
        const float t = (1.f - shape) / 0.6f; // 0..1 over shape 1→0.4
        rx = r * (0.55f - t * 0.20f);
        ry = r * (1.0f + t * 0.15f);
    } else if (shape > 1.f) {
        // Widen toward circular / mild horizontal.
        const float t = (shape - 1.f) / 0.6f;
        rx = r * (0.55f + t * 0.45f);
        ry = r * (1.0f - t * 0.20f);
    }
    outRx = rx;
    outRy = ry;
}

}  // namespace raw_v3
