#include "raw_v3_highlight_recovery.h"
#include <cmath>
#include <algorithm>

namespace raw_v3 {

// Decode a single IEEE-754 half-precision float to float.
static inline float fp16ToFloat(uint16_t h) {
    uint32_t sign     = (h >> 15) & 0x1;
    uint32_t exponent = (h >> 10) & 0x1f;
    uint32_t mantissa = h & 0x3ff;
    uint32_t f;
    if (exponent == 0) {
        if (mantissa == 0) { f = sign << 31; }
        else {
            while (!(mantissa & 0x400)) { mantissa <<= 1; exponent--; }
            exponent++;
            mantissa &= ~0x400;
            f = (sign << 31) | ((exponent + (127 - 15)) << 23) | (mantissa << 13);
        }
    } else if (exponent == 0x1f) {
        f = (sign << 31) | 0x7f800000 | (mantissa << 13);
    } else {
        f = (sign << 31) | ((exponent + (127 - 15)) << 23) | (mantissa << 13);
    }
    float out;
    __builtin_memcpy(&out, &f, 4);
    return out;
}

static inline float smoothstepf(float edge0, float edge1, float x) {
    float t = std::max(0.f, std::min(1.f, (x - edge0) / (edge1 - edge0)));
    return t * t * (3.f - 2.f * t);
}

static inline float linearToSrgb(float c) {
    if (c <= 0.f) return 0.f;
    if (c >= 1.f) return 1.f;
    return c <= 0.0031308f ? 12.92f * c : 1.055f * powf(c, 1.f / 2.4f) - 0.055f;
}

void recoverHighlights(
    const uint16_t* stageAFp16,
    uint32_t*       ahbArgb8,
    int w, int h,
    float recovery)
{
    if (recovery <= 0.f) return;
    // EV shift: recovery=1.0 pulls down by 2 EV to recover clipped data.
    float evShift = -recovery * 2.f;
    float scale   = powf(2.f, evShift);

    for (int i = 0; i < w * h; i++) {
        // RAW FP16 linear light
        float rawR = fp16ToFloat(stageAFp16[i * 4 + 0]) * scale;
        float rawG = fp16ToFloat(stageAFp16[i * 4 + 1]) * scale;
        float rawB = fp16ToFloat(stageAFp16[i * 4 + 2]) * scale;

        // Current AHB pixel (ARGB8)
        uint32_t argb = ahbArgb8[i];
        float curR = ((argb >> 16) & 0xff) / 255.f;
        float curG = ((argb >>  8) & 0xff) / 255.f;
        float curB = ( argb        & 0xff) / 255.f;
        float curLuma = 0.2126f * curR + 0.7152f * curG + 0.0722f * curB;

        // Blend weight: only affects pixels near/above highlight clip.
        float blendW = smoothstepf(0.85f, 1.f, curLuma) * recovery;
        if (blendW < 1e-4f) continue;

        // Recovered pixel in sRGB
        float recR = linearToSrgb(rawR);
        float recG = linearToSrgb(rawG);
        float recB = linearToSrgb(rawB);

        float outR = curR + (recR - curR) * blendW;
        float outG = curG + (recG - curG) * blendW;
        float outB = curB + (recB - curB) * blendW;

        uint8_t r8 = (uint8_t)(std::max(0.f, std::min(1.f, outR)) * 255.f + 0.5f);
        uint8_t g8 = (uint8_t)(std::max(0.f, std::min(1.f, outG)) * 255.f + 0.5f);
        uint8_t b8 = (uint8_t)(std::max(0.f, std::min(1.f, outB)) * 255.f + 0.5f);

        ahbArgb8[i] = (argb & 0xff000000u) | ((uint32_t)r8 << 16) | ((uint32_t)g8 << 8) | b8;
    }
}

} // namespace raw_v3
