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
#include <cmath>

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

/**
 * Optical Spread contribution radii in UV fractions of the frame.
 * Base bloom keeps filmicBloomOvalRadii. Direction is ignored when amount is 0.
 * 0 Off (portrait oval), 1 Horizontal, 2 Radial.
 */
inline void filmicOpticalSpreadRadii(float amount, float direction,
                                     float& outRx, float& outRy) {
    outRx = 0.004f;
    outRy = 0.006f;
    if (amount <= 1e-4f) return;
    if (direction > 1.5f) {
        outRx = 0.008f;
        outRy = 0.008f;
    } else if (direction > 0.5f) {
        outRx = 0.014f;
        outRy = 0.0035f;
    }
    const float reach = 0.35f + amount * 2.65f;
    outRx *= reach;
    outRy *= reach;
}

/** Same sqrt long-side curve as CinematicBloomProcessor.densityMul. */
inline float opticalSpreadDensity(int longSide) {
    const float side = longSide > 1 ? float(longSide) : 1.f;
    float t = std::sqrt(side / 2048.f);
    if (t < 0.75f) t = 0.75f;
    if (t > 1.35f) t = 1.35f;
    return t;
}

/**
 * Frequency-aware remix of an already-built bloom: wide taps are the low band,
 * the tight center minus wide is the mid band (choked). Red-weighted halation
 * then local-contrast subtraction so flat skin does not lift.
 * No-op when amount and halation are both ~0 (caller should skip the samples).
 * MUST match the GLSL block in shader_sources.cpp.
 */
inline void opticalSpreadAdd(float& r, float& g, float& b,
                             float wideR, float wideG, float wideB,
                             float tightR, float tightG, float tightB,
                             float amount, float halation) {
    if (amount < 0.f) amount = 0.f; else if (amount > 1.f) amount = 1.f;
    if (halation < 0.f) halation = 0.f; else if (halation > 1.f) halation = 1.f;
    if (amount <= 1e-4f && halation <= 1e-4f) return;
    float midR = tightR - wideR; if (midR < 0.f) midR = 0.f;
    float midG = tightG - wideG; if (midG < 0.f) midG = 0.f;
    float midB = tightB - wideB; if (midB < 0.f) midB = 0.f;
    float lowR = wideR - midR * 0.65f; if (lowR < 0.f) lowR = 0.f;
    float lowG = wideG - midG * 0.65f; if (lowG < 0.f) lowG = 0.f;
    float lowB = wideB - midB * 0.65f; if (lowB < 0.f) lowB = 0.f;
    float gR = lowR * amount + wideR * halation;
    float gG = lowG * amount + wideG * 0.45f * halation;
    float gB = lowB * amount + wideB * 0.15f * halation;
    const float srcL = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    const float bloomL = 0.2126f * tightR + 0.7152f * tightG + 0.0722f * tightB;
    float local = std::fabs(srcL - bloomL) * 3.f;
    if (local > 1.f) local = 1.f;
    float h = (srcL - 0.45f) / 0.40f;
    if (h < 0.f) h = 0.f; else if (h > 1.f) h = 1.f;
    h = h * h * (3.f - 2.f * h);
    const float keep = local > h ? local : h;
    r += gR * keep * 0.65f;
    g += gG * keep * 0.65f;
    b += gB * keep * 0.65f;
}

}  // namespace raw_v3
