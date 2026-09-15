/*
 * StudioRoom — RAW Pipeline v3 — Shared per-pixel kernel (M8).
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Mirrors gles_renderer.cpp's uber.frag. Any change to the GLSL math must
 * be reflected here in lock-step or saved files diverge from the editor.
 */

#include "apply_macro.h"
#include "v3_debug_log.h"

#include <algorithm>
#include <android/log.h>
#include <cmath>

#define LOG_TAG_AM "RawV3.ApplyMacro"

namespace raw_v3 {

namespace {

inline float clamp01(float v) {
    return v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
}

inline float exp2f_fast(float x) {
    // The C++ stdlib's std::exp2 is fine for our throughput. No need for
    // a fast-path approximation.
    return std::exp2(x);
}

// ── HSL helpers (mirror GLSL rgbToHsl / hslToRgb) ───────────────────────────
struct Hsl { float h, s, l; };
Hsl rgbToHsl(float r, float g, float b) {
    float maxC = std::max(r, std::max(g, b));
    float minC = std::min(r, std::min(g, b));
    float d = maxC - minC;
    float h = 0.f, s = 0.f, l = (maxC + minC) * 0.5f;
    if (d > 1e-6f) {
        s = (l > 0.5f) ? d / (2.f - maxC - minC) : d / (maxC + minC);
        if (maxC == r) {
            h = (g - b) / d + (g < b ? 6.f : 0.f);
        } else if (maxC == g) {
            h = (b - r) / d + 2.f;
        } else {
            h = (r - g) / d + 4.f;
        }
        h /= 6.f;
    }
    return {h, s, l};
}

inline float hueToRgb(float p, float q, float t) {
    t = t - std::floor(t);  // fract
    if (t < 1.f / 6.f) return p + (q - p) * 6.f * t;
    if (t < 0.5f)     return q;
    if (t < 2.f / 3.f) return p + (q - p) * (2.f / 3.f - t) * 6.f;
    return p;
}

void hslToRgb(const Hsl& hsl, float& r, float& g, float& b) {
    if (hsl.s < 1e-6f) { r = g = b = hsl.l; return; }
    float q = (hsl.l < 0.5f) ? hsl.l * (1.f + hsl.s) : hsl.l + hsl.s - hsl.l * hsl.s;
    float p = 2.f * hsl.l - q;
    r = hueToRgb(p, q, hsl.h + 1.f / 3.f);
    g = hueToRgb(p, q, hsl.h);
    b = hueToRgb(p, q, hsl.h - 1.f / 3.f);
}

constexpr float HUE_RED           = 0.000f;
constexpr float HUE_ORANGE        = 0.083f;
constexpr float HUE_YELLOW        = 0.167f;
constexpr float HUE_YELLOW_GREEN  = 0.250f; //  90°
constexpr float HUE_GREEN         = 0.333f;
constexpr float HUE_SPRING_GREEN  = 0.417f; // 150°
constexpr float HUE_AQUA          = 0.500f;
constexpr float HUE_SKY_BLUE      = 0.583f; // 210°
constexpr float HUE_BLUE          = 0.667f;
constexpr float HUE_PURPLE        = 0.750f; // 270°
constexpr float HUE_MAGENTA       = 0.833f; // 300°
constexpr float HUE_PINK          = 0.917f; // 330°

inline float hueWeight(float hue, float center) {
    // Gaussian hue weight, mirrors gles_renderer.cpp's RapidRAW-style
    // exp(-1.5 * (dist / half_width)^2) with half-width = 1/12 (30°).
    // Replaces the older triangular tent so adjacent anchors blend
    // smoothly without visible band edges.
    float d  = std::fabs(hue - center);
    float d2 = std::fabs(hue - center - 1.f);
    float d3 = std::fabs(hue - center + 1.f);
    float dist = std::min(d, std::min(d2, d3));
    float n = dist * 12.f;  // dist / (width*0.5)
    return std::exp(-1.5f * n * n);
}

inline float smoothstep01(float a, float b, float x) {
    // Degenerate guard must use |b-a|: the old `b - a < 1e-6f` also caught
    // every INVERTED-edge call (b < a — legal in GLSL smoothstep and used by
    // the cg-shadows mask, colorDensity's upper band, the skintone hue mask,
    // and dust), collapsing them to an inverted hard threshold. That made
    // exports diverge from the GL preview (razparity: cg wheels wrong zone,
    // colorDensity a silent no-op). GLSL semantics: t=(x-e0)/(e1-e0), clamp.
    if (std::fabs(b - a) < 1e-6f) return x < a ? 0.f : 1.f;
    float t = (x - a) / (b - a);
    if (t < 0.f) t = 0.f; else if (t > 1.f) t = 1.f;
    return t * t * (3.f - 2.f * t);
}

// ── Linear-light helpers (mirrors gles_renderer.cpp srgbToLinear/linearToSrgb) ─
static inline float srgbToLinear1(float c) {
    c = c < 0.f ? 0.f : (c > 1.f ? 1.f : c);
    return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);
}
static inline float linearToSrgb1(float c) {
    c = c < 0.f ? 0.f : (c > 1.f ? 1.f : c);
    return c <= 0.0031308f ? c * 12.92f : 1.055f * std::pow(c, 1.f / 2.4f) - 0.055f;
}
static inline void srgbToLinear3(float& r, float& g, float& b) {
    r = srgbToLinear1(r); g = srgbToLinear1(g); b = srgbToLinear1(b);
}
static inline void linearToSrgb3(float& r, float& g, float& b) {
    r = linearToSrgb1(r); g = linearToSrgb1(g); b = linearToSrgb1(b);
}

// ── Smart Color Enhancement ("Color Pop") — CPU port of the GL pass ──────────
// Faithful C++ mirror of gles_renderer.cpp applySmartColorEnhancement() + its
// Lab helpers (slots [384..390]). Lab uses the D65 illuminant and the same
// sRGB↔XYZ matrices as the shader so the saved file matches the canvas exactly.
static inline void linearRgbToXyzAM(float r, float g, float b,
                                    float& X, float& Y, float& Z) {
    X = 0.4124564f * r + 0.3575761f * g + 0.1804375f * b;
    Y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b;
    Z = 0.0193339f * r + 0.1191920f * g + 0.9503041f * b;
}
static inline void xyzToLinearRgbAM(float X, float Y, float Z,
                                    float& r, float& g, float& b) {
    r =  3.2404542f * X - 1.5371385f * Y - 0.4985314f * Z;
    g = -0.9692660f * X + 1.8760108f * Y + 0.0415560f * Z;
    b =  0.0556434f * X - 0.2040259f * Y + 1.0572252f * Z;
}
static inline float labFAM(float t) {
    const float d = 6.0f / 29.0f;
    return t > d * d * d ? powf(t, 1.0f / 3.0f) : t / (3.0f * d * d) + 4.0f / 29.0f;
}
static inline float labFInvAM(float f) {
    const float d = 6.0f / 29.0f;
    return f > d ? f * f * f : 3.0f * d * d * (f - 4.0f / 29.0f);
}
// sRGB [0,1] → Lab (L 0..100, a/b centered at 0).
static inline void srgbToLabAM(float r, float g, float b,
                               float& L, float& A, float& B) {
    float lr = srgbToLinear1(r), lg = srgbToLinear1(g), lb = srgbToLinear1(b);
    float X, Y, Z; linearRgbToXyzAM(lr, lg, lb, X, Y, Z);
    X /= 0.95047f; Y /= 1.00000f; Z /= 1.08883f;
    float fx = labFAM(X), fy = labFAM(Y), fz = labFAM(Z);
    L = 116.0f * fy - 16.0f;
    A = 500.0f * (fx - fy);
    B = 200.0f * (fy - fz);
}
// Lab → sRGB [0,1] (linear RGB clamped before encode, matching the shader).
static inline void labToSrgbAM(float L, float A, float B,
                               float& r, float& g, float& b) {
    float fy = (L + 16.0f) / 116.0f;
    float fx = A / 500.0f + fy;
    float fz = fy - B / 200.0f;
    float X = labFInvAM(fx) * 0.95047f;
    float Y = labFInvAM(fy) * 1.00000f;
    float Z = labFInvAM(fz) * 1.08883f;
    float lr, lg, lb; xyzToLinearRgbAM(X, Y, Z, lr, lg, lb);
    r = linearToSrgb1(lr); g = linearToSrgb1(lg); b = linearToSrgb1(lb);
}
// Chroma boost — mirrors GLSL smartBoostChroma() / SmartColorEnhancer.kt.
static inline float smartBoostChromaAM(float chan) {
    const float SAT_SCALE = 1.3f, THRESH = 80.0f, MAX_C = 127.0f;
    float mag = fabsf(chan);
    float scale;
    if (mag >= THRESH) {
        float excess = mag - THRESH;
        scale = SAT_SCALE * (1.0f - excess / (MAX_C - THRESH));
        if (scale < 1.0f) scale = 1.0f;
    } else {
        scale = SAT_SCALE;
    }
    float out = chan * scale;
    return out < -128.0f ? -128.0f : (out > 127.0f ? 127.0f : out);
}
// Full Color Pop pass: per-channel auto-WB stretch → sigmoidal L boost →
// adaptive a/b chroma boost. Mutates r,g,b in place (sRGB [0,1]).
inline void applySmartColorEnhanceP(float& r, float& g, float& b,
                                    const ApplyMacroParams& p) {
    auto clamp01 = [](float x) { return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); };
    float rangeR = p.smartWbRMax - p.smartWbRMin; if (rangeR < 0.001f) rangeR = 0.001f;
    float rangeG = p.smartWbGMax - p.smartWbGMin; if (rangeG < 0.001f) rangeG = 0.001f;
    float rangeB = p.smartWbBMax - p.smartWbBMin; if (rangeB < 0.001f) rangeB = 0.001f;
    float cr = clamp01((r - p.smartWbRMin) / rangeR);
    float cg = clamp01((g - p.smartWbGMin) / rangeG);
    float cb = clamp01((b - p.smartWbBMin) / rangeB);
    float L, A, Bb; srgbToLabAM(cr, cg, cb, L, A, Bb);
    float Ln = L / 100.0f;
    // Highlight-protected L boost — MUST mirror applySmartColorEnhancement()
    // in shader_sources.cpp (2026-08-28): taper the sigmoid lift above the
    // upper midtones so Color Pop stops pumping highlights per step.
    float hpT = (Ln - 0.60f) / 0.35f;
    hpT = hpT < 0.f ? 0.f : (hpT > 1.f ? 1.f : hpT);
    const float hlProtect = 1.0f - 0.85f * (hpT * hpT * (3.0f - 2.0f * hpT));
    L = L + 15.0f * sinf(3.14159265f * Ln) * hlProtect;
    if (L < 0.f) L = 0.f; else if (L > 100.f) L = 100.f;
    A = smartBoostChromaAM(A);
    Bb = smartBoostChromaAM(Bb);
    float orr, og, ob; labToSrgbAM(L, A, Bb, orr, og, ob);
    r = clamp01(orr); g = clamp01(og); b = clamp01(ob);
}

// ── Phase-1 backport helpers (mirror gles_renderer.cpp exactly) ──────────────

// Headroom-aware LUT sample coordinate — mirrors mapLutSampleCoord() GLSL.
// Identity on [0,1]; soft-compress ONLY channels > 1 into (0,1].
inline float mapLutSampleCoordP(float x) {
    if (x <= 0.f) return 0.f;
    if (x <= 1.f) return x;
    const float e = x - 1.f;
    return 1.f / (1.f + e);
}


// Mirrors applyFilmicLuma / applyOklabHlChroma in shader_sources.cpp.
inline float filmicLumaCurveP(float x) {
    float v = x < 0.f ? 0.f : (x > 1.f ? 1.f : x);
    float shadow = v + 0.025f * (1.f - v);
    float sCurve = shadow * shadow * (3.f - 2.f * shadow);
    float out = sCurve / (sCurve + 0.18f);
    return out < 0.f ? 0.f : (out > 1.f ? 1.f : out);
}
inline void applyFilmicLumaP(float& r, float& g, float& b, float strength) {
    if (strength <= 0.f) return;
    float s = strength < 0.f ? 0.f : (strength > 1.f ? 1.f : strength);
    float Y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    float mapped = filmicLumaCurveP(Y);
    float scale = mapped / (Y > 1e-4f ? Y : 1e-4f);
    float nr = r * scale, ng = g * scale, nb = b * scale;
    r = r + (nr - r) * s;
    g = g + (ng - g) * s;
    b = b + (nb - b) * s;
}
inline float srgbToLinP(float c) {
    return (c <= 0.04045f) ? (c / 12.92f) : std::pow((c + 0.055f) / 1.055f, 2.4f);
}
inline float linToSrgbP(float c) {
    if (c <= 0.0031308f) return 12.92f * c;
    return 1.055f * std::pow(c > 0.f ? c : 0.f, 1.f / 2.4f) - 0.055f;
}
inline void applyOklabHlChromaP(float& r, float& g, float& b, float strength) {
    if (strength <= 0.f) return;
    float s = strength < 0.f ? 0.f : (strength > 1.f ? 1.f : strength);
    float lr = srgbToLinP(r < 0.f ? 0.f : (r > 1.f ? 1.f : r));
    float lg = srgbToLinP(g < 0.f ? 0.f : (g > 1.f ? 1.f : g));
    float lb = srgbToLinP(b < 0.f ? 0.f : (b > 1.f ? 1.f : b));
    float l = 0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb;
    float m = 0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb;
    float ss = 0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb;
    float l_ = std::cbrt(l > 0.f ? l : 0.f);
    float m_ = std::cbrt(m > 0.f ? m : 0.f);
    float s_ = std::cbrt(ss > 0.f ? ss : 0.f);
    float L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_;
    float a = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_;
    float bb = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_;
    float midBoost = 1.f;
    {
        float t1 = (L - 0.15f) / 0.25f; if (t1 < 0.f) t1 = 0.f; else if (t1 > 1.f) t1 = 1.f;
        t1 = t1 * t1 * (3.f - 2.f * t1);
        float t2 = (L - 0.45f) / 0.15f; if (t2 < 0.f) t2 = 0.f; else if (t2 > 1.f) t2 = 1.f;
        t2 = t2 * t2 * (3.f - 2.f * t2);
        midBoost = 1.f + 0.08f * t1 * (1.f - t2);
    }
    float hl = (L - 0.55f) / 0.45f; if (hl < 0.f) hl = 0.f; else if (hl > 1.f) hl = 1.f;
    hl = hl * hl * (3.f - 2.f * hl);
    float hlMul = 1.f + (0.40f - 1.f) * hl;
    float mul = midBoost + (hlMul - midBoost) * hl;
    a *= mul; bb *= mul;
    float l2 = L + 0.3963377774f * a + 0.2158037573f * bb;
    float m2 = L - 0.1055613458f * a - 0.0638541728f * bb;
    float s2 = L - 0.0894789779f * a - 1.2914855480f * bb;
    l2 = l2 * l2 * l2; m2 = m2 * m2 * m2; s2 = s2 * s2 * s2;
    float nr =  4.0767416621f * l2 - 3.3077115913f * m2 + 0.2309699292f * s2;
    float ng = -1.2684380046f * l2 + 2.6097574011f * m2 - 0.3413193965f * s2;
    float nb = -0.0041960863f * l2 - 0.7034186147f * m2 + 1.7076147010f * s2;
    nr = linToSrgbP(nr); ng = linToSrgbP(ng); nb = linToSrgbP(nb);
    if (nr < 0.f) nr = 0.f; else if (nr > 1.f) nr = 1.f;
    if (ng < 0.f) ng = 0.f; else if (ng > 1.f) ng = 1.f;
    if (nb < 0.f) nb = 0.f; else if (nb > 1.f) nb = 1.f;
    r = r + (nr - r) * s;
    g = g + (ng - g) * s;
    b = b + (nb - b) * s;
}

// filmRolloff: S-curve highlight rolloff. Mirrors applyFilmRolloff() GLSL.
// Slot 207. Runs in light tab, after tone regions, before ambiance.
//
// Headroom fix (2026-08-21): a channel >= 1.0 (blown highlight data the
// FP16 pipeline intentionally preserves above "white") used to get clamped
// to exactly 1.0 BEFORE the curve ran, so every headroom value collapsed
// to the identical target regardless of how far above white it actually
// was — a flat white plateau with a visible seam against the still-graded
// sub-1.0 neighbors next to it. There's no sensible curve shape for
// headroom without also letting OUTPUT exceed 1.0, so instead a channel
// already >= 1 now passes straight through unchanged. Applied identically
// in shader_sources.cpp's applyFilmRolloff() GLSL — this function must
// stay in lock-step with that shader (see file header).
inline void applyFilmRolloffP(float& r, float& g, float& b, float strength) {
    if (strength <= 0.f) return;
    float N = 1.f + strength * 4.f;
    float s = strength < 0.f ? 0.f : (strength > 1.f ? 1.f : strength);
    auto curve = [N](float c) -> float {
        if (c >= 1.f) return c;                       // headroom: identity, no collapse
        if (c < 0.f) c = 0.f;
        return 1.f - std::pow(1.f - c, N);
    };
    r = r + (curve(r) - r) * s;
    g = g + (curve(g) - g) * s;
    b = b + (curve(b) - b) * s;
}

// Color grading — 4-way Lift/Gamma/Gain (+Global offset), DaVinci/ASC-CDL style.
// Mirrors applyColorGrading() GLSL EXACTLY (preview == export). Slots 240–251
// (shadows=Lift / midtones=Gamma / highlights=Gain) + 426–429 (global=Offset).
inline void applyColorGradingP(float& r, float& g, float& b,
                                float sR, float sG, float sB, float sSat,   // Lift  (shadows)
                                float mR, float mG, float mB, float mSat,   // Gamma (midtones)
                                float hR, float hG, float hB, float hSat,   // Gain  (highlights)
                                float gR, float gG, float gB, float gSat) { // Offset (global)
    if (sSat == 0.f && mSat == 0.f && hSat == 0.f && gSat == 0.f) return;
    // Per-channel grade: lift → offset → gain → gamma. push = (tint-0.5)*2*sat.
    auto grade = [](float x, float lift, float off, float gain, float gam) -> float {
        x = x + lift * (1.f - x);
        x = x + off;
        x = x * (1.f + gain);
        float gg = 1.f + gam;
        if (gg < 0.1f) gg = 0.1f; else if (gg > 4.f) gg = 4.f;
        x = (x <= 0.f) ? 0.f : std::pow(x, 1.f / gg);
        if (x < 0.f) x = 0.f; else if (x > 4.f) x = 4.f;
        return x;
    };
    r = grade(r, (sR - 0.5f) * 2.f * sSat, (gR - 0.5f) * 2.f * gSat, (hR - 0.5f) * 2.f * hSat, (mR - 0.5f) * 2.f * mSat);
    g = grade(g, (sG - 0.5f) * 2.f * sSat, (gG - 0.5f) * 2.f * gSat, (hG - 0.5f) * 2.f * hSat, (mG - 0.5f) * 2.f * mSat);
    b = grade(b, (sB - 0.5f) * 2.f * sSat, (gB - 0.5f) * 2.f * gSat, (hB - 0.5f) * 2.f * hSat, (mB - 0.5f) * 2.f * mSat);
}


// ── Film response: Adobe Recovery / FillLight + the B&W GrayMixer ──────────
// EXACT mirror of applyFilmResponse() in shader_sources.cpp. Read the rationale
// there. Runs right after the 3D LUT and before the tonal-zone WB trims.

// Adobe's eight HSL band centres, in degrees. NON-uniform on purpose — NOT the
// uniform 45-degree anchors applyHslFullP() uses. Do not conflate them.
static const float kFilmBandDeg[8] = {0.f, 30.f, 60.f, 120.f, 180.f, 240.f, 285.f, 330.f};

static inline void filmBandWeights(float hue01, float* w) {
    float h = hue01 - std::floor(hue01);
    h *= 360.f;
    for (int i = 0; i < 8; ++i) w[i] = 0.f;
    int lo = 7, hi = 0;
    for (int i = 0; i < 8; ++i) {
        if (kFilmBandDeg[i] <= h) { lo = i; hi = (i + 1) % 8; }
    }
    if (h < kFilmBandDeg[0]) { lo = 7; hi = 0; }
    const float cLo = kFilmBandDeg[lo];
    const float cHi = kFilmBandDeg[hi] + (hi <= lo ? 360.f : 0.f);
    const float hh  = (h < cLo) ? h + 360.f : h;
    float span = cHi - cLo;
    if (span < 1e-9f) span = 1e-9f;
    float t = (hh - cLo) / span;
    if (t < 0.f) t = 0.f; else if (t > 1.f) t = 1.f;
    const float sm = t * t * (3.f - 2.f * t);
    w[lo] = 1.f - sm;
    w[hi] = sm;
}

static inline void applyFilmResponseP(float& r, float& g, float& b,
                                      float recovery, float fillLight,
                                      int monochrome, const float* grayMix) {
    if (recovery == 0.f && fillLight == 0.f && monochrome == 0) return;
    if (recovery != 0.f || fillLight != 0.f) {
        auto push = [&](float c) -> float {
            float wHi = (c - 0.5f) * 2.f;
            if (wHi < 0.f) wHi = 0.f; else if (wHi > 1.f) wHi = 1.f;
            float wSh = (0.5f - c) * 2.f;
            if (wSh < 0.f) wSh = 0.f; else if (wSh > 1.f) wSh = 1.f;
            return clamp01(c - recovery * 0.25f * wHi + fillLight * 0.25f * wSh);
        };
        r = push(r); g = push(g); b = push(b);
    }
    if (monochrome != 0) {
        Hsl hsl = rgbToHsl(clamp01(r), clamp01(g), clamp01(b));
        float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        float w[8];
        filmBandWeights(hsl.h, w);
        float mixAmt = 0.f;
        for (int i = 0; i < 8; ++i) mixAmt += grayMix[i] * w[i];
        y = clamp01(y + mixAmt * hsl.s * 0.5f);
        r = y; g = y; b = y;
    }
}

// hslFull: 8-anchor per-hue H/S/L shifts. Mirrors applyHslFull() GLSL.
// Slots 253–276 (8×vec3, layout: [hShift, sShift, lShift] per anchor).
// Runs in color tab, after color grading wheels, before colorDensity.
inline void applyHslFullP(float& r, float& g, float& b, const float* hslFull8) {
    // Quick-exit: all 24 floats zero means no-op.
    bool anyNonZero = false;
    for (int i = 0; i < 24 && !anyNonZero; ++i) anyNonZero = (hslFull8[i] != 0.f);
    if (!anyNonZero) return;
    Hsl hsl = rgbToHsl(r, g, b);
    float h = hsl.h, s = hsl.s, l = hsl.l;
    float satMask = smoothstep01(0.05f, 0.20f, s);
    for (int i = 0; i < 8; ++i) {
        float anchorH = float(i) / 8.f;
        float dist = std::fabs(h - anchorH);
        if (dist > 0.5f) dist = 1.f - dist;  // wrap-around
        float w = 1.f - dist * 8.f;
        if (w <= 0.f) continue;
        w = w * w * (3.f - 2.f * w);  // smoothstep shaping
        const float* sh = hslFull8 + i * 3;
        h = h + (sh[0] / 6.f) * w * satMask;
        h = h - std::floor(h);  // fract
        s += sh[1] * w * satMask;
        if (s < 0.f) s = 0.f; else if (s > 1.f) s = 1.f;
        l += sh[2] * w * satMask;
        if (l < 0.f) l = 0.f; else if (l > 1.f) l = 1.f;
    }
    hslToRgb({h, s, l}, r, g, b);
}

// colorDensity: mid-saturation boost/cut. Mirrors applyColorDensity() GLSL.
// Slot 343. Runs in color tab, after hslFull, before skintone.
inline void applyColorDensityP(float& r, float& g, float& b, float density) {
    if (density == 0.f) return;
    Hsl hsl = rgbToHsl(r, g, b);
    float midMask = smoothstep01(0.0f, 0.3f, hsl.s) * smoothstep01(1.0f, 0.6f, hsl.s);
    hsl.s += density * 0.5f * midMask;
    if (hsl.s < 0.f) hsl.s = 0.f; else if (hsl.s > 1.f) hsl.s = 1.f;
    hslToRgb(hsl, r, g, b);
}

// skintone: warm/smooth/luma adjust in skin hue range. Mirrors applySkintone() GLSL.
// Slots 344–346. Runs in color tab, after colorDensity, before fan-out composite.
inline void applySkintoneP(float& r, float& g, float& b,
                            float warm, float smooth, float luma) {
    if (warm == 0.f && smooth == 0.f && luma == 0.f) return;
    Hsl hsl = rgbToHsl(r, g, b);
    float skinMask = smoothstep01(0.02f, 0.05f, hsl.h) * smoothstep01(0.12f, 0.08f, hsl.h);
    skinMask *= smoothstep01(0.1f, 0.3f, hsl.s);
    if (skinMask <= 0.f) return;
    hsl.h = hsl.h + warm * 0.05f * skinMask;
    hsl.h = hsl.h - std::floor(hsl.h);
    hsl.s -= smooth * 0.3f * skinMask;
    if (hsl.s < 0.f) hsl.s = 0.f; else if (hsl.s > 1.f) hsl.s = 1.f;
    hsl.l += luma * 0.3f * skinMask;
    if (hsl.l < 0.f) hsl.l = 0.f; else if (hsl.l > 1.f) hsl.l = 1.f;
    hslToRgb(hsl, r, g, b);
}

// pushPull: simple EV shift after curves. Mirrors applyPushPull() GLSL.
// Slot 349. Runs after curves, before detailGrainRoughness.
inline void applyPushPullP(float& r, float& g, float& b, float ev) {
    if (ev == 0.f) return;
    float scale = std::exp2(ev);
    r *= scale; if (r < 0.f) r = 0.f; else if (r > 4.f) r = 4.f;
    g *= scale; if (g < 0.f) g = 0.f; else if (g > 4.f) g = 4.f;
    b *= scale; if (b < 0.f) b = 0.f; else if (b > 4.f) b = 4.f;
}

// detailGrainRoughness: UV-hash noise. Mirrors applyDetailGrain() GLSL.
// Slot 341. Runs after pushPull. Uses pixel UV so grain pattern matches GL
// preview geometry (both tile at 1/120 UV). Pass u,v in [0..1].
inline void applyDetailGrainP(float& r, float& g, float& b,
                               float u, float v, float roughness) {
    if (roughness == 0.f) return;
    // hash21 mirror: sin(dot(floor(uv*120), vec2(127.1,311.7))) * 43758.5453
    float fx = std::floor(u * 120.f) * 127.1f + std::floor(v * 120.f) * 311.7f;
    float n = std::fabs(std::sin(fx) * 43758.5453f);
    n = n - std::floor(n);  // fract
    float noise = (n - 0.5f) * roughness * 0.04f;
    r += noise; if (r < 0.f) r = 0.f; else if (r > 4.f) r = 4.f;
    g += noise; if (g < 0.f) g = 0.f; else if (g > 4.f) g = 4.f;
    b += noise; if (b < 0.f) b = 0.f; else if (b > 4.f) b = 4.f;
}

// ── Haxademic film grain — CPU mirror of GLSL haxGrain() (Req 15) ──────────
// Deterministic seed (not time-varying) for export parity with the GL preview.
static constexpr float kGrainSeed = 37.0f;

// fract(sin(dot(st, vec2(17.0, 180.0))) * 2500.0 + seed)
static inline float haxGrain_cpu(float ux, float uy, float seed) {
    float dot = ux * 17.0f + uy * 180.0f;
    float v = std::sin(dot) * 2500.0f + seed;
    return v - std::floor(v);  // fract: x - floor(x), mirrors GLSL fract() exactly
}

// Apply Haxademic grain layer. Mirrors the GLSL haxGrain block in kFragSrc.
// Backwards-compatible: at defaults (crossfade=0.0, grainScale=1.0,
// chromaAmp=0.0) this is a guaranteed no-op — the early-exit below fires
// immediately, leaving r/g/b untouched.  Existing presets that never set
// slots 375–378 produce bit-identical output to the pre-grain-extension path.
inline void applyHaxGrainP(float& r, float& g, float& b,
                            float u, float v,
                            float crossfade, float grainScale,
                            float lumaAmp, float chromaAmp) {
    // Default haxGrainCrossfade = 0.0 → immediate return → no pixel change.
    if (crossfade <= 0.0f) return;
    float gux = u * grainScale;
    float guy = v * grainScale;
    float gVal = haxGrain_cpu(gux, guy, kGrainSeed) * lumaAmp;
    float grainR = gVal, grainG = gVal, grainB = gVal;
    if (chromaAmp > 0.0f) {
        // Per-channel offsets match GPU: R at uv + (0.1, 0), B at uv + (0, 0.1)
        grainR = haxGrain_cpu(gux + 0.1f, guy, kGrainSeed) * chromaAmp;
        grainB = haxGrain_cpu(gux, guy + 0.1f, kGrainSeed) * chromaAmp;
    }
    // Blend: mix(pixel, grainValue, crossfade) — same as GPU
    r = r + (grainR - r) * crossfade;
    g = g + (grainG - g) * crossfade;
    b = b + (grainB - b) * crossfade;
}

// gamutCompress: soft-knee chroma compression. Mirrors applyGamutCompress() GLSL.
// Slot 237. Runs LAST, after LUT + tonal WB trims.
inline void applyGamutCompressP(float& r, float& g, float& b, float strength) {
    if (strength <= 0.f) return;
    float s = strength < 1.f ? strength : 1.f;
    float achro  = std::max(r, std::max(g, b));
    float darkst = std::min(r, std::min(g, b));
    if (achro <= 0.0001f) return;
    float sat = (achro - darkst) / achro;
    if (sat <= 0.0001f) return;
    float globalDesat    = 1.f - 0.6f * s;
    float linearSat      = sat * globalDesat;
    float capTarget      = 1.f - 0.4f * s;
    float compressedSat  = (linearSat <= 0.0001f) ? 0.f
        : capTarget * linearSat / (capTarget + linearSat);
    float ratio = compressedSat / sat;
    r = achro - (achro - r) * ratio;
    g = achro - (achro - g) * ratio;
    b = achro - (achro - b) * ratio;
}

// applyLocalContrastCpu: log-space local contrast for centerPop.
// Mirrors applyLocalContrast() GLSL. Needs a blurred reference pixel.
inline void applyLocalContrastCpu(float& r, float& g, float& b,
                                   float bR, float bG, float bB, float amt) {
    if (amt == 0.f) return;
    float yc = r * 0.2627f + g * 0.6780f + b * 0.0593f;
    float yb = bR * 0.2627f + bG * 0.6780f + bB * 0.0593f;
    if (yc < 1e-4f) yc = 1e-4f;
    if (yb < 1e-4f) yb = 1e-4f;
    float logRatio = std::log2(yc / yb);
    float prot = smoothstep01(0.f, 0.03f, yc) * (1.f - smoothstep01(0.9f, 1.f, yc));
    float gainR = r * std::exp2(logRatio * amt);
    float gainG = g * std::exp2(logRatio * amt);
    float gainB = b * std::exp2(logRatio * amt);
    r = r + (gainR - r) * prot;
    g = g + (gainG - g) * prot;
    b = b + (gainB - b) * prot;
}

void applyExposureContrastP(float& r, float& g, float& b, float ev, float c) {
    // Linear-light domain: sRGB→linear → scale → contrast → linear→sRGB.
    srgbToLinear3(r, g, b);
    float scale = exp2f_fast(ev);
    r *= scale; g *= scale; b *= scale;
    // Contrast pivot at linear 0.18 (18% grey — standard photographic midtone).
    float bias = 0.18f;
    float k = 1.f + c;
    r = bias + (r - bias) * k;
    g = bias + (g - bias) * k;
    b = bias + (b - bias) * k;
    linearToSrgb3(r, g, b);
}

// Exact CPU mirror of the shader's applyWbTintP (shader_sources.cpp). See there
// for why the flat ±15% gain was replaced by a real colour-temperature model.
static inline void wbWhitePointXyz(float T, float& X, float& Y, float& Z) {
    float t = T < 1667.f ? 1667.f : (T > 25000.f ? 25000.f : T);
    float t2 = t * t, t3 = t2 * t;
    float x;
    if (t < 4000.f) {
        x = -0.2661239e9f / t3 - 0.2343589e6f / t2 + 0.8776956e3f / t + 0.179910f;
    } else if (t <= 7000.f) {
        x = 0.244063f + 0.09911e3f / t + 2.9678e6f / t2 - 4.6070e9f / t3;
    } else {
        x = 0.237040f + 0.24748e3f / t + 1.9018e6f / t2 - 2.0064e9f / t3;
    }
    float y;
    if (t < 4000.f) {
        y = -1.1063814f * x * x * x - 1.34811020f * x * x + 2.18555832f * x - 0.20219683f;
    } else {
        y = -3.000f * x * x + 2.870f * x - 0.275f;
    }
    if (y < 1e-4f) y = 1e-4f;
    X = x / y; Y = 1.f; Z = (1.f - x - y) / y;
}

static inline void wbGainForKelvin(float T, float& gr, float& gg, float& gb) {
    float X, Y, Z;
    wbWhitePointXyz(T, X, Y, Z);
    float r =  3.2404542f * X - 1.5371385f * Y - 0.4985314f * Z;
    float g = -0.9692660f * X + 1.8760108f * Y + 0.0415560f * Z;
    float b =  0.0556434f * X - 0.2040259f * Y + 1.0572252f * Z;
    if (r < 1e-4f) r = 1e-4f;
    if (g < 1e-4f) g = 1e-4f;
    if (b < 1e-4f) b = 1e-4f;
    gr = r / g; gg = 1.f; gb = b / g;
}

void applyWbTint(float& r, float& g, float& b, float wbDelta, float tint) {
    srgbToLinear3(r, g, b);
    if (std::fabs(wbDelta) > 1e-4f) {
        float tK = 6500.f + wbDelta * 2500.f;
        if (tK < 1667.f) tK = 1667.f;
        if (tK > 25000.f) tK = 25000.f;
        float rr, rg, rb, tr, tg, tb;
        wbGainForKelvin(6500.f, rr, rg, rb);
        wbGainForKelvin(tK, tr, tg, tb);
        float gainR = rr / tr, gainG = rg / tg, gainB = rb / tb;
        const float nrm = (gainG > 1e-4f) ? gainG : 1e-4f;
        r *= gainR / nrm; g *= gainG / nrm; b *= gainB / nrm;
    }
    if (std::fabs(tint) > 1e-4f) {
        const float gg = 1.f + tint * 0.12f;
        const float comp = 1.f / std::sqrt(gg > 1e-4f ? gg : 1e-4f);
        g *= gg; r *= comp; b *= comp;
    }
    linearToSrgb3(r, g, b);
}

// Ambiance — exact CPU mirror of the shader's effAmb block (gles_renderer.cpp
// lines 728-738). Inputs:
//   r,g,b   — post-toneregion lightTab pixel, mutated in place
//   bR,bG,bB — corresponding pixel from the Gaussian-blurred reference image
//              (same blur the shader's uBlurTex provides)
//   amb     — effective ambiance value (already blended subject/background
//              by the caller when masks are present)
inline void applyAmbianceP(float& r, float& g, float& b,
                           float bR, float bG, float bB, float amb) {
    if (amb == 0.f) return;
    const float detR = r - bR;
    const float detG = g - bG;
    const float detB = b - bB;
    // Mirror gles_renderer.cpp clamp: clamp(1 + 0.6*amb, 0, 3). Already
    // present, kept for clarity.
    float k = 1.f + 0.6f * amb;
    if (k < 0.f) k = 0.f; else if (k > 3.f) k = 3.f;
    r = bR + detR * k;
    g = bG + detG * k;
    b = bB + detB * k;
    // Snapseed-style adaptive shadow fill — MUST mirror shader_sources.cpp
    // exactly (preview=export). Lift where the local neighbourhood (bR,bG,bB =
    // tonalBlur) is dark, gently compress local highlights, so ambiance evens
    // the tonal range. Highlight-safe via (1 - x).
    const float bLuma = bR * 0.2627f + bG * 0.6780f + bB * 0.0593f;
    auto smooth01 = [](float e0, float e1, float x) {
        float t = (x - e0) / (e1 - e0);
        if (t < 0.f) t = 0.f; else if (t > 1.f) t = 1.f;
        return t * t * (3.f - 2.f * t);
    };
    const float shadowMask = 1.f - smooth01(0.0f, 0.5f, bLuma);
    const float highMask   = smooth01(0.55f, 1.0f, bLuma);
    // Saturation-preserving shadow fill via per-channel gamma (anchors 0/1).
    float g_ = amb * 1.0f * shadowMask;
    if (g_ < -0.8f) g_ = -0.8f; else if (g_ > 3.0f) g_ = 3.0f;
    const float invGamma = 1.0f / (1.0f + g_);
    auto clamp01 = [](float x){ return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); };
    r = std::pow(clamp01(r), invGamma);
    g = std::pow(clamp01(g), invGamma);
    b = std::pow(clamp01(b), invGamma);
    // Gentle highlight compression (multiplicative → chroma-preserving).
    const float hc = 1.f - amb * 0.12f * highMask;
    r = clamp01(r * hc);
    g = clamp01(g * hc);
    b = clamp01(b * hc);
    const float aLuma = r * 0.2627f + g * 0.6780f + b * 0.0593f;
    float midW = 1.f - std::fabs(aLuma - 0.5f) * 2.f;
    if (midW < 0.f) midW = 0.f; else if (midW > 1.f) midW = 1.f;
    float sScale = 1.f + 0.4f * amb * midW;
    if (sScale < 0.f) sScale = 0.f; else if (sScale > 2.f) sScale = 2.f;
    r = aLuma + (r - aLuma) * sScale;
    g = aLuma + (g - aLuma) * sScale;
    b = aLuma + (b - aLuma) * sScale;
}

// Orton — filmic / Pro-Mist composite. MUST mirror shader_sources.cpp Orton block
// (preview=export). Screen-blend + shadowMask + optional pre-halation sample.
// GLSL does mix(c, screenC, glowAmt) with the UNCLAMPED c so HDR headroom
// survives into the LUT sample path — writing clamp(c) here flattened saves.
inline void applyOrtonP(float& r, float& g, float& b,
                        float bR, float bG, float bB, float strength) {
    if (strength <= 0.f) return;
    const float k = std::min(1.f, strength);
    if (k <= 0.f) return;
    auto c01 = [](float x){ return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); };
    float bloomLuma = bR * 0.2126f + bG * 0.7152f + bB * 0.0722f;
    float sR = bR + (bloomLuma - bR) * 0.55f;
    float sG = bG + (bloomLuma - bG) * 0.55f;
    float sB = bB + (bloomLuma - bB) * 0.55f;
    float warmMag = (sR + sG) * 0.5f - sB;
    float warmGate = warmMag * 3.0f;
    if (warmGate < 0.f) warmGate = 0.f; else if (warmGate > 1.f) warmGate = 1.f;
    sR *= (1.0f + 0.45f * warmGate);
    sG *= (1.0f + 0.30f * warmGate);
    if (sR < 0.f) sR = 0.f;
    if (sG < 0.f) sG = 0.f;
    if (sB < 0.f) sB = 0.f;
    sR = c01(sR); sG = c01(sG); sB = c01(sB);
    const float cr = c01(r), cg = c01(g), cb = c01(b);
    const float scR = 1.f - (1.f - cr) * (1.f - sR);
    const float scG = 1.f - (1.f - cg) * (1.f - sG);
    const float scB = 1.f - (1.f - cb) * (1.f - sB);
    const float origL = cr * 0.2126f + cg * 0.7152f + cb * 0.0722f;
    float shadowMask = smoothstep01(0.05f, 0.35f, origL);
    // 2× prior Orton mix (was 0.50f) — must match shader_sources.cpp.
    float glowAmt = k * 1.00f * shadowMask;
    glowAmt *= 1.f - smoothstep01(0.82f, 1.0f, origL) * 0.65f;
    // mix(original, screenC, amt) — preserve HDR like GLSL.
    r = r + (scR - r) * glowAmt;
    g = g + (scG - g) * glowAmt;
    b = b + (scB - b) * glowAmt;
}

void applyToneRegionsP(float& r, float& g, float& b,
                       float hi, float sh, float wh, float bl) {
    // Linear-light domain — mirrors the updated GLSL applyToneRegionsP.
    srgbToLinear3(r, g, b);
    float L = r * 0.2627f + g * 0.6780f + b * 0.0593f;
    // Mask thresholds in linear space (match GLSL: 0.18/0.72 and 0.003/0.18).
    float highlightMask = smoothstep01(0.18f, 0.72f, L);
    float shadowMask    = 1.f - smoothstep01(0.003f, 0.18f, L);
    float mul = (1.f + hi * highlightMask * 0.5f)
              * (1.f + sh * shadowMask    * 0.8f);
    r *= mul; g *= mul; b *= mul;
    if (wh != 0.f || bl != 0.f) {
        const float wp = std::max(1.f - wh * 0.25f, 0.1f);
        float bp = bl * 0.12f;
        if (bp < -0.15f) bp = -0.15f;
        else if (bp > 0.15f) bp = 0.15f;
        const float scale = (1.f - bp) / wp;
        r = bp + r * scale;
        g = bp + g * scale;
        b = bp + b * scale;
    }
    linearToSrgb3(r, g, b);
}

void applySaturationVibrance(float& r, float& g, float& b, float sat, float vib) {
    float L = r * 0.2627f + g * 0.6780f + b * 0.0593f;
    float ks = 1.f + sat;
    r = L + (r - L) * ks;
    g = L + (g - L) * ks;
    b = L + (b - L) * ks;
    if (vib != 0.f) {
        float maxC = std::max(r, std::max(g, b));
        float minC = std::min(r, std::min(g, b));
        float curSat = (maxC - minC) / std::max(1e-5f, maxC);
        float w = 1.f - curSat;
        float kv = 1.f + vib * w;
        r = L + (r - L) * kv;
        g = L + (g - L) * kv;
        b = L + (b - L) * kv;
    }
}

void applyHslShiftsP(float& r, float& g, float& b, const float* hsl18) {
    // Skip if every range vector is zero.
    bool allZero = true;
    for (int i = 0; i < 18; ++i) if (hsl18[i] != 0.f) { allZero = false; break; }
    if (allZero) return;

    Hsl in = rgbToHsl(clamp01(r), clamp01(g), clamp01(b));

    float wR = hueWeight(in.h, HUE_RED);
    float wO = hueWeight(in.h, HUE_ORANGE);
    float wY = hueWeight(in.h, HUE_YELLOW);
    float wG = hueWeight(in.h, HUE_GREEN);
    float wA = hueWeight(in.h, HUE_AQUA);
    float wB = hueWeight(in.h, HUE_BLUE);
    float totalW = std::max(1e-5f, wR + wO + wY + wG + wA + wB);

    auto blend = [&](int hueIdx) {
        return (hsl18[0 + hueIdx]  * wR + hsl18[3 + hueIdx]  * wO +
                hsl18[6 + hueIdx]  * wY + hsl18[9 + hueIdx]  * wG +
                hsl18[12 + hueIdx] * wA + hsl18[15 + hueIdx] * wB) / totalW;
    };
    float hShift = blend(0);
    float sShift = blend(1);
    float lShift = blend(2);

    // Saturation mask — skip near-neutral pixels so color-specific sliders
    // never tint or darken whites/grays. Matches GLSL applyHslShiftsP.
    float satMask = smoothstep01(0.05f, 0.20f, in.s);
    Hsl out;
    out.h = in.h + (hShift / 6.f) * satMask + 1.f;
    out.h = out.h - std::floor(out.h);    // fract
    out.s = clamp01(in.s + sShift * satMask);
    out.l = clamp01(in.l + lShift * satMask);
    hslToRgb(out, r, g, b);
}

// 12-anchor named HSL shifts. Mirrors GLSL applyHslShifts() (uHslRed…uHslPink).
// [hsl18]  = slots 10–27  (R/O/Y/G/A/B × H/S/L)
// [hsl2_18]= slots 211–228 (YG/SG/SB/Pu/Ma/Pi × H/S/L)
void applyHsl12ShiftsP(float& r, float& g, float& b,
                       const float* hsl18, const float* hsl2_18) {
    bool allZero = true;
    for (int i = 0; i < 18; ++i) {
        if (hsl18[i] != 0.f || hsl2_18[i] != 0.f) { allZero = false; break; }
    }
    if (allZero) return;

    Hsl in = rgbToHsl(clamp01(r), clamp01(g), clamp01(b));

    float wR  = hueWeight(in.h, HUE_RED);
    float wO  = hueWeight(in.h, HUE_ORANGE);
    float wY  = hueWeight(in.h, HUE_YELLOW);
    float wYG = hueWeight(in.h, HUE_YELLOW_GREEN);
    float wG  = hueWeight(in.h, HUE_GREEN);
    float wSG = hueWeight(in.h, HUE_SPRING_GREEN);
    float wA  = hueWeight(in.h, HUE_AQUA);
    float wSB = hueWeight(in.h, HUE_SKY_BLUE);
    float wB  = hueWeight(in.h, HUE_BLUE);
    float wPu = hueWeight(in.h, HUE_PURPLE);
    float wMa = hueWeight(in.h, HUE_MAGENTA);
    float wPi = hueWeight(in.h, HUE_PINK);
    float totalW = std::max(1e-5f,
        wR + wO + wY + wYG + wG + wSG + wA + wSB + wB + wPu + wMa + wPi);

    auto blend = [&](int hueIdx) {
        return (hsl18[0  + hueIdx] * wR  + hsl18[3  + hueIdx] * wO  +
                hsl18[6  + hueIdx] * wY  + hsl2_18[0 + hueIdx] * wYG +
                hsl18[9  + hueIdx] * wG  + hsl2_18[3 + hueIdx] * wSG +
                hsl18[12 + hueIdx] * wA  + hsl2_18[6 + hueIdx] * wSB +
                hsl18[15 + hueIdx] * wB  + hsl2_18[9 + hueIdx] * wPu +
                hsl2_18[12 + hueIdx] * wMa + hsl2_18[15 + hueIdx] * wPi) / totalW;
    };
    float hShift = blend(0);
    float sShift = blend(1);
    float lShift = blend(2);

    float satMask = smoothstep01(0.05f, 0.20f, in.s);
    Hsl out;
    out.h = in.h + (hShift / 6.f) * satMask + 1.f;
    out.h = out.h - std::floor(out.h);    // fract
    out.s = clamp01(in.s + sShift * satMask);
    out.l = clamp01(in.l + lShift * satMask);
    hslToRgb(out, r, g, b);
}

// Highlight soft-knee. Mirrors gles_renderer.cpp pre-FX block.
inline void applyHighlightKneeP(float& r, float& g, float& b) {
    constexpr float knee = 0.90f;
    constexpr float head = 1.0f - knee;
    float overR = r - knee; if (overR < 0.f) overR = 0.f;
    float overG = g - knee; if (overG < 0.f) overG = 0.f;
    float overB = b - knee; if (overB < 0.f) overB = 0.f;
    r = (r < knee ? r : knee) + head * (overR / (overR + head));
    g = (g < knee ? g : knee) + head * (overG / (overG + head));
    b = (b < knee ? b : knee) + head * (overB / (overB + head));
}

// ── FX tab helpers (mirror gles_renderer.cpp Effects tab) ───────────────────

struct vec2 {
    float x, y;
    vec2() = default;
    vec2(float xx, float yy) : x(xx), y(yy) {}
};
inline vec2 operator+(const vec2& a, const vec2& b) { return vec2(a.x + b.x, a.y + b.y); }

// Forward decl — defined later with the other mask helpers.
inline float sampleSubjectMask(const ApplyMacroSubjectMask* m, float u, float v);

inline float hash21P(vec2 p) {
    float v = std::sin(p.x * 127.1f + p.y * 311.7f) * 43758.5453f;
    return v - std::floor(v);
}

// Mist overlay. strength [0..1], warmth [-0.5..+0.5].
// MUST mirror applyMist in shader_sources.cpp (warmth coeff 0.20, not 0.04).
inline void applyMistP(float& r, float& g, float& b, float strength, float warmth) {
    if (strength == 0.f) return;
    float mistR = 0.98f + warmth * 0.20f;
    float mistG = 0.96f;
    float mistB = 0.94f - warmth * 0.20f;
    float w = strength * 0.4f;
    r += (mistR - r) * w;
    g += (mistG - g) * w;
    b += (mistB - b) * w;
}

// Haxademic-style radial vignette used by vintage FX.
inline void applyHaxVignetteP(float& r, float& g, float& b,
                              float u, float v, float amount) {
    if (amount == 0.f) return;
    float dx = (u - 0.5f) * 2.f;
    float dy = (v - 0.5f) * 2.f;
    float d2 = dx * dx + dy * dy;
    float mask = smoothstep01(0.0f, 1.8f, d2) * amount;
    float mul = 1.f - mask * 0.5f;
    r *= mul; g *= mul; b *= mul;
}

// Vintage — clean tonal vintage look (desaturate + mild cast + gentle channel
// shift + faded blacks + corner vignette). CPU mirror of applyVintage in
// shader_sources.cpp. The old AnalogTape line artifacts (scanlines, hum bands,
// bottom tracking stripe) were REMOVED at the user's request — no streak lines.
inline void applyVintageP(float& r, float& g, float& b,
                          float u, float v,
                          float strength, float fade, float vig) {
    if (strength == 0.f) return;
    float k = strength;
    float Y = r * 0.299f + g * 0.587f + b * 0.114f;
    r += (Y - r) * 0.35f * k;  g += (Y - g) * 0.35f * k;  b += (Y - b) * 0.35f * k;
    r += 0.020f * k;  g += -0.012f * k;  b += 0.028f * k;   // mild warm/cool cast
    r += 0.020f * k;  b += -0.015f * k;                     // gentle channel shift
    float w2 = fade * strength * 0.6f;                      // fade / lift blacks
    r += (0.12f - r) * w2;  g += (0.12f - g) * w2;  b += (0.12f - b) * w2;
    float dx = (u - 0.5f) * 2.f;
    float dy = (v - 0.5f) * 2.f;
    float rr = dx * dx + dy * dy;
    float vigMask = smoothstep01(0.5f, 1.8f, rr) * vig * strength;
    float mul = 1.f - vigMask * 0.6f;
    r *= mul; g *= mul; b *= mul;
    if (r < 0.f) r = 0.f; else if (r > 4.f) r = 4.f;
    if (g < 0.f) g = 0.f; else if (g > 4.f) g = 4.f;
    if (b < 0.f) b = 0.f; else if (b > 4.f) b = 4.f;
}

// Filmic / Pro-Mist glow — MUST mirror applyGlowWithSpread in shader_sources.cpp.
// GLSL returns mix(c, screenC, amt) with unclamped c (HDR preserved).
inline void applyGlowWithSpreadP(float& r, float& g, float& b,
                                 float bloomR, float bloomG, float bloomB,
                                 float strength, float spread, float warmth) {
    if (strength == 0.f) return;
    float s = strength;
    if (s < 0.f) s = 0.f; else if (s > 1.f) s = 1.f;
    float bloomL = (bloomR + bloomG + bloomB) * 0.3333f;
    float sp = spread;
    if (sp < 0.f) sp = 0.f; else if (sp > 1.f) sp = 1.f;
    float glowR = bloomR + (bloomL - bloomR) * (sp * 0.5f);
    float glowG = bloomG + (bloomL - bloomG) * (sp * 0.5f);
    float glowB = bloomB + (bloomL - bloomB) * (sp * 0.5f);
    if (glowR < 0.f) glowR = 0.f;
    if (glowG < 0.f) glowG = 0.f;
    if (glowB < 0.f) glowB = 0.f;
    glowR *= (1.f + warmth * 0.55f);
    glowB *= (1.f - warmth * 0.55f);
    auto c01 = [](float x){ return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); };
    const float cr = c01(r), cg = c01(g), cb = c01(b);
    const float baseL = cr * 0.2126f + cg * 0.7152f + cb * 0.0722f;
    float shadowMask = smoothstep01(0.05f, 0.35f, baseL);
    const float scR = 1.f - (1.f - cr) * (1.f - c01(glowR));
    const float scG = 1.f - (1.f - cg) * (1.f - c01(glowG));
    const float scB = 1.f - (1.f - cb) * (1.f - c01(glowB));
    // 2× prior glow mix (was 0.55f) — must match shader_sources.cpp.
    float amt = s * 1.10f * shadowMask;
    amt *= 1.f - smoothstep01(0.85f, 1.0f, baseL) * 0.55f;
    r = r + (scR - r) * amt;
    g = g + (scG - g) * amt;
    b = b + (scB - b) * amt;
}

// Lens flare — CPU mirror of applyLensFlare in shader_sources.cpp.
// Light-leak blend (screen + soft add), same family as blendGradTint Solid.
// uv-space (no aspect correction) so preview == export exactly.
inline void lfLeakBlendP(float& r, float& g, float& b,
                         float tr, float tg, float tb, float p) {
    if (p < 0.f) p = 0.f; else if (p > 1.f) p = 1.f;
    if (p <= 0.f) return;
    auto screenCh = [](float c, float t) {
        return 1.f - (1.f - c) * (1.f - t);
    };
    float sR = screenCh(r, tr), sG = screenCh(g, tg), sB = screenCh(b, tb);
    float aR = r + tr * (0.55f * p);
    float aG = g + tg * (0.55f * p);
    float aB = b + tb * (0.55f * p);
    float lR = sR + (std::max(sR, aR) - sR) * 0.35f;
    float lG = sG + (std::max(sG, aG) - sG) * 0.35f;
    float lB = sB + (std::max(sB, aB) - sB) * 0.35f;
    r += (lR - r) * p;
    g += (lG - g) * p;
    b += (lB - b) * p;
}
inline void applyLensFlareP(float& r, float& g, float& b, float u, float v,
                            float fx, float fy, float bright, float size, float spread,
                            float warmth) {
    if (bright <= 0.f) return;
    const float flx = fx * 0.5f + 0.5f;
    const float fly = fy * 0.5f + 0.5f;
    // Warmth 0 = soft warm-white; 1 = sunlight yellowish-orange → amber/peach.
    const float w = warmth < 0.f ? 0.f : (warmth > 1.f ? 1.f : warmth);
    const float coolR = 1.00f, coolG = 0.97f, coolB = 0.92f;
    const float sunR = 1.00f, sunG = 0.72f, sunB = 0.28f;
    const float hotR = 1.00f, hotG = 0.48f, hotB = 0.18f;
    const float mR = sunR + (hotR - sunR) * w;
    const float mG = sunG + (hotG - sunG) * w;
    const float mB = sunB + (hotB - sunB) * w;
    float cr = coolR + (mR - coolR) * w;
    float cg = coolG + (mG - coolG) * w;
    float cb = coolB + (mB - coolB) * w;
    const float rw = w * 0.22f;
    cr = cr + (1.00f - cr) * rw;
    cg = cg + (0.55f - cg) * rw;
    cb = cb + (0.42f - cb) * rw;
    const float sr = r, sg = g, sb = b;         // original, for the brightness mix
    auto dist = [&](float ax, float ay) {
        float dx = u - ax, dy = v - ay; return std::sqrt(dx * dx + dy * dy);
    };
    const float d = dist(flx, fly);
    const float scolor = 0.0375f * size, sglow = 0.078125f * size,
                sinner = 0.1796875f * size, souter = 0.3359375f * size,
                shalo = 0.084375f * size;
    if (d < scolor) { float p = (scolor - d) / scolor; lfLeakBlendP(r, g, b, cr, cg, cb, p * p); }
    if (d < sglow)  { float p = (sglow - d) / sglow;   lfLeakBlendP(r, g, b, cr, cg, cb, p * p * 0.6f); }
    if (d < sinner) { float p = (sinner - d) / sinner; lfLeakBlendP(r, g, b, cr, cg, cb, p * p * 0.25f); }
    if (d < souter) { float p = (souter - d) / souter; lfLeakBlendP(r, g, b, cr, cg, cb, p * 0.12f); }
    { float a = std::fabs(d - shalo) / (shalo * 0.15f);
      float p = 1.f - (a < 0.f ? 0.f : (a > 1.f ? 1.f : a));
      lfLeakBlendP(r, g, b, cr, cg, cb, p * 0.2f); }
    const float tcx = (0.5f - flx) * spread, tcy = (0.5f - fly) * spread;
    for (int i = 0; i < 8; i++) {
        float t = ((float)i - 3.f) * 0.28f;
        float gx = flx + tcx * t, gy = fly + tcy * t;
        float gd = dist(gx, gy);
        float gr = (0.02f + 0.015f * std::fabs(t)) * size;
        if (gd < gr) {
            float p = (gr - gd) / gr;
            float gtr, gtg, gtb;
            if ((i & 1) == 0) {
                gtr = 1.0f; gtg = 0.85f + (0.62f - 0.85f) * w; gtb = 0.70f + (0.38f - 0.70f) * w;
            } else {
                gtr = 0.70f + (1.0f - 0.70f) * w;
                gtg = 0.85f + (0.58f - 0.85f) * w;
                gtb = 1.00f + (0.48f - 1.00f) * w;
            }
            lfLeakBlendP(r, g, b, gtr, gtg, gtb, p * p * 0.35f);
        }
    }
    r = sr + (r - sr) * bright;
    g = sg + (g - sg) * bright;
    b = sb + (b - sb) * bright;
}

// Procedural dust — Voronoi-like particle overlay.
inline void applyDustP(float& r, float& g, float& b,
                       float u, float v, float amount, float size) {
    if (amount == 0.f) return;
    float scale = 20.f + (1.f - size) * 60.f; // mix(80,20,size) → scale=20..80
    vec2 cell = vec2(std::floor(u * scale), std::floor(v * scale));
    float minDist = 1.f;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            vec2 nc = cell + vec2(float(dx), float(dy));
            float h1 = hash21P(vec2(nc.x, nc.y));
            float h2 = hash21P(vec2(nc.x + 0.5f, nc.y + 0.5f));
            vec2 pt = nc + vec2(h1, h2);
            float dx_ = u * scale - pt.x;
            float dy_ = v * scale - pt.y;
            float dist = std::sqrt(dx_ * dx_ + dy_ * dy_);
            if (dist < minDist) minDist = dist;
        }
    }
    float dust = smoothstep01(0.05f, 0.0f, minDist - 0.45f) * amount * 0.35f;
    r -= dust; g -= dust; b -= dust;
    if (r < 0.f) r = 0.f;
    if (g < 0.f) g = 0.f;
    if (b < 0.f) b = 0.f;
}

// Bilinear sample of a W×H RGB float buffer at normalized (u,v).
// Clamp-to-edge. Returns (r,g,b) tuple.
inline void sampleBlurBuffer(const float* buf, int w, int h,
                             float u, float v,
                             float& outR, float& outG, float& outB) {
    if (!buf || w <= 0 || h <= 0) { outR = outG = outB = 0.f; return; }
    if (u < 0.f) u = 0.f; else if (u > 1.f) u = 1.f;
    if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
    const float fx = u * float(w - 1);
    const float fy = v * float(h - 1);
    int x0 = int(fx); int y0 = int(fy);
    int x1 = x0 + 1; if (x1 > w - 1) x1 = w - 1;
    int y1 = y0 + 1; if (y1 > h - 1) y1 = h - 1;
    const float dx = fx - float(x0);
    const float dy = fy - float(y0);
    auto px = [&](int x, int y) { return buf + (size_t(y) * w + x) * 3; };
    const float* c00 = px(x0, y0);
    const float* c10 = px(x1, y0);
    const float* c01 = px(x0, y1);
    const float* c11 = px(x1, y1);
    const float w00 = (1.f - dx) * (1.f - dy);
    const float w10 = dx * (1.f - dy);
    const float w01 = (1.f - dx) * dy;
    const float w11 = dx * dy;
    outR = c00[0]*w00 + c10[0]*w10 + c01[0]*w01 + c11[0]*w11;
    outG = c00[1]*w00 + c10[1]*w10 + c01[1]*w01 + c11[1]*w11;
    outB = c00[2]*w00 + c10[2]*w10 + c01[2]*w01 + c11[2]*w11;
}

// FX blur. Samples the pre-computed Gaussian-blurred buffer (uBlurTex equivalent).
// Directional/radial/zoom styles perform multi-tap sampling to match the GL shader.
inline void applyFxBlurP(float& r, float& g, float& b,
                         const float* blurBuf, int blurW, int blurH,
                         const ApplyMacroParams& p,
                         const ApplyMacroSubjectMask* mask,
                         float u, float v) {
    int style = int(p.fxBlurStyle + 0.5f);
    if (style <= 0 || !blurBuf || blurW <= 0 || blurH <= 0) return;

    float blurR = 0.f, blurG = 0.f, blurB = 0.f;
    if (style == 1) {
        // Gaussian — direct sample.
        sampleBlurBuffer(blurBuf, blurW, blurH, u, v, blurR, blurG, blurB);
    } else if (style == 2) {
        // Directional — 9 taps along angle direction.
        const float dirU = std::cos(p.fxDirBlurAngle);
        const float dirV = std::sin(p.fxDirBlurAngle);
        // texel size in UV space, then scale by amount * 24 (matches GLSL).
        const float step = (blurW > 1 && blurH > 1)
            ? p.fxDirBlurAmt * 24.0f / std::sqrt(float(blurW*blurW + blurH*blurH))
            : 0.f;
        float sumR = 0.f, sumG = 0.f, sumB = 0.f;
        for (int i = -4; i <= 4; ++i) {
            float su = u + dirU * step * float(i);
            float sv = v + dirV * step * float(i);
            float tr, tg, tb;
            sampleBlurBuffer(blurBuf, blurW, blurH, su, sv, tr, tg, tb);
            sumR += tr; sumG += tg; sumB += tb;
        }
        blurR = sumR / 9.f; blurG = sumG / 9.f; blurB = sumB / 9.f;
    } else if (style == 3) {
        // Radial — 8 samples along radial direction from center.
        float du = u - p.fxRadBlurCx;
        float dv = v - p.fxRadBlurCy;
        float sumR = 0.f, sumG = 0.f, sumB = 0.f;
        for (int i = 0; i < 8; ++i) {
            float t = float(i) / 7.f;
            float scale = t * p.fxRadBlurAmt * 0.5f;
            float su = u + du * scale;
            float sv = v + dv * scale;
            float tr, tg, tb;
            sampleBlurBuffer(blurBuf, blurW, blurH, su, sv, tr, tg, tb);
            sumR += tr; sumG += tg; sumB += tb;
        }
        blurR = sumR / 8.f; blurG = sumG / 8.f; blurB = sumB / 8.f;
    } else {
        // Zoom — 8 samples along zoom vector from center.
        float du = u - p.fxZoomBlurCx;
        float dv = v - p.fxZoomBlurCy;
        float sumR = 0.f, sumG = 0.f, sumB = 0.f;
        for (int i = 0; i < 8; ++i) {
            float t = float(i) / 7.f;
            float scale = t * p.fxZoomBlurAmt * 0.3f;
            float su = u + du * scale;
            float sv = v + dv * scale;
            float tr, tg, tb;
            sampleBlurBuffer(blurBuf, blurW, blurH, su, sv, tr, tg, tb);
            sumR += tr; sumG += tg; sumB += tb;
        }
        blurR = sumR / 8.f; blurG = sumG / 8.f; blurB = sumB / 8.f;
    }

    float blurExclude = 0.f;
    if (p.fxBlurExcludeSubject > 0.5f && mask && mask->data && mask->w > 0 && mask->h > 0) {
        blurExclude = sampleSubjectMask(mask, u, v);
        if (blurExclude < 0.f) blurExclude = 0.f;
        if (blurExclude > 1.f) blurExclude = 1.f;
    }
    float mixedR = r + (blurR - r) * 1.f;
    float mixedG = g + (blurG - g) * 1.f;
    float mixedB = b + (blurB - b) * 1.f;
    float w = 1.f - blurExclude;
    r += (mixedR - r) * w;
    g += (mixedG - g) * w;
    b += (mixedB - b) * w;
}

// ── 3D LUT trilinear ────────────────────────────────────────────────────────
//   Matches GLES sampler3D with GL_LINEAR + GL_CLAMP_TO_EDGE. The LUT data
//   layout in lut3d.cpp is row-major by .cube spec: R fastest, then G,
//   then B (matches GL_TEXTURE_3D's R=x, G=y, B=z).
inline int clampIdx(int i, int hi) {
    if (i < 0) return 0;
    if (i > hi) return hi;
    return i;
}

// ── Gamut conversion matrices (linear-light, matching GLSL gles_renderer.cpp) ──

static const float kProPhotoToSrgb[9] = {
     1.3459433f, -0.2556073f, -0.0511118f,
    -0.5445989f,  1.5081673f,  0.0205351f,
     0.0000000f,  0.0000000f,  1.2118128f
};
static const float kSrgbToProPhoto[9] = {
     0.7976749f,  0.1351917f,  0.0313534f,
     0.2880402f,  0.7118741f,  0.0000857f,
     0.0000000f,  0.0000000f,  0.8252100f
};
static const float kAdobeRgbToSrgb[9] = {
     1.3985264f, -0.3982625f,  0.0000000f,
    -0.0982505f,  1.0968684f, -0.0012520f,
     0.0000000f,  0.0000000f,  1.0000000f
};
static const float kSrgbToAdobeRgb[9] = {
     0.7152882f,  0.2848026f, -0.0000906f,
     0.0640952f,  0.9359048f,  0.0000000f,
     0.0000000f,  0.0000000f,  1.0000000f
};
static const float kDciP3ToSrgb[9] = {
     1.2249401f, -0.2249401f,  0.0000000f,
    -0.0420569f,  1.0420569f,  0.0000000f,
     0.0000000f,  0.0000000f,  1.0000000f
};
static const float kSrgbToDciP3[9] = {
     0.8224621f,  0.1775379f,  0.0000000f,
     0.0331941f,  0.9668059f,  0.0000000f,
     0.0000000f,  0.0000000f,  1.0000000f
};
static const float kRec2020ToSrgb[9] = {
     1.6604910f, -0.5876411f, -0.0728499f,
    -0.1245505f,  1.1328999f, -0.0083494f,
    -0.0181508f, -0.1005789f,  1.1187296f
};
static const float kSrgbToRec2020[9] = {
     0.6274040f,  0.3292820f,  0.0433136f,
     0.0690970f,  0.9195400f,  0.0113612f,
     0.0163916f,  0.0880132f,  0.8955952f
};

static void mulMat3(const float* m, float& r, float& g, float& b) {
    float or_ = m[0]*r + m[1]*g + m[2]*b;
    float og  = m[3]*r + m[4]*g + m[5]*b;
    float ob  = m[6]*r + m[7]*g + m[8]*b;
    r = or_; g = og; b = ob;
}

// Convert from workspace space (libraw value) to sRGB linear.
// space: 1=sRGB(no-op), 2=AdobeRGB, 4=ProPhoto, 7=DCI-P3, 8=Rec.2020
static void lutToSrgb(float& r, float& g, float& b, int space) {
    switch (space) {
        case 2: mulMat3(kAdobeRgbToSrgb, r, g, b); break;
        case 4: mulMat3(kProPhotoToSrgb, r, g, b); break;
        case 7: mulMat3(kDciP3ToSrgb,   r, g, b); break;
        case 8: mulMat3(kRec2020ToSrgb,  r, g, b); break;
        default: break; // sRGB or unknown → no-op
    }
}

// Convert from sRGB linear to workspace space.
static void lutFromSrgb(float& r, float& g, float& b, int space) {
    switch (space) {
        case 2: mulMat3(kSrgbToAdobeRgb, r, g, b); break;
        case 4: mulMat3(kSrgbToProPhoto, r, g, b); break;
        case 7: mulMat3(kSrgbToDciP3,   r, g, b); break;
        case 8: mulMat3(kSrgbToRec2020,  r, g, b); break;
        default: break;
    }
}

// LUT authored-space ordinal → libraw space value.
// 0=Rec.709(sRGB→1), 1=ProPhoto→4, 2=ACES(→sRGB no-op→1), 3=DCI-P3→7
static int lutAuthoredToLibraw(int ordinal) {
    switch (ordinal) {
        case 1: return 4; // ProPhoto
        case 3: return 7; // DCI-P3
        default: return 1; // sRGB / ACES both map through sRGB
    }
}

// ── Tetrahedral LUT interpolation (matches GLSL tetrahedralLut3D) ─────────────
// Kirk & Vorhies 6-tetrahedra decomposition: selects one tetrahedron based on
// sort order of (dr, dg, db) and uses 4 corners only, avoiding bowing on smooth
// gradients that trilinear 8-corner produces.
void sampleLut3d(float& r, float& g, float& b, const ApplyMacroLut& lut) {
    const int n = lut.size;
    if (n < 2 || lut.data == nullptr) return;

    // DOMAIN_MIN/MAX remapping (Bug 3 fix).
    float rr = (r - lut.domainMin[0]) / (lut.domainMax[0] - lut.domainMin[0]);
    float gg = (g - lut.domainMin[1]) / (lut.domainMax[1] - lut.domainMin[1]);
    float bb = (b - lut.domainMin[2]) / (lut.domainMax[2] - lut.domainMin[2]);

    const float scale = float(n - 1);
    float xf = clamp01(rr) * scale;
    float yf = clamp01(gg) * scale;
    float zf = clamp01(bb) * scale;

    int x0 = int(std::floor(xf));
    int y0 = int(std::floor(yf));
    int z0 = int(std::floor(zf));
    x0 = clampIdx(x0, n - 1);
    y0 = clampIdx(y0, n - 1);
    z0 = clampIdx(z0, n - 1);
    const int x1 = clampIdx(x0 + 1, n - 1);
    const int y1 = clampIdx(y0 + 1, n - 1);
    const int z1 = clampIdx(z0 + 1, n - 1);

    const float dr = xf - float(x0);
    const float dg = yf - float(y0);
    const float db = zf - float(z0);

    auto T = [&](int x, int y, int z) -> const float* {
        return lut.data + (size_t(z) * n * n + size_t(y) * n + size_t(x)) * 3;
    };

    const float* c000 = T(x0, y0, z0);
    const float* c100 = T(x1, y0, z0);
    const float* c010 = T(x0, y1, z0);
    const float* c110 = T(x1, y1, z0);
    const float* c001 = T(x0, y0, z1);
    const float* c101 = T(x1, y0, z1);
    const float* c011 = T(x0, y1, z1);
    const float* c111 = T(x1, y1, z1);

    // Select tetrahedron by sort order of (dr, dg, db) — exactly as GLSL.
    float w0, w1, w2, w3;
    const float* a; const float* b_; const float* c_; const float* d;
    if (dr >= dg && dg >= db) {
        // dr >= dg >= db
        w0 = 1.f - dr; w1 = dr - dg; w2 = dg - db; w3 = db;
        a = c000; b_ = c100; c_ = c110; d = c111;
    } else if (dr >= db && db >= dg) {
        // dr >= db >= dg
        w0 = 1.f - dr; w1 = dr - db; w2 = db - dg; w3 = dg;
        a = c000; b_ = c100; c_ = c101; d = c111;
    } else if (db >= dr && dr >= dg) {
        // db >= dr >= dg
        w0 = 1.f - db; w1 = db - dr; w2 = dr - dg; w3 = dg;
        a = c000; b_ = c001; c_ = c101; d = c111;
    } else if (dg >= dr && dr >= db) {
        // dg >= dr >= db
        w0 = 1.f - dg; w1 = dg - dr; w2 = dr - db; w3 = db;
        a = c000; b_ = c010; c_ = c110; d = c111;
    } else if (dg >= db && db >= dr) {
        // dg >= db >= dr
        w0 = 1.f - dg; w1 = dg - db; w2 = db - dr; w3 = dr;
        a = c000; b_ = c010; c_ = c011; d = c111;
    } else {
        // db >= dg >= dr
        w0 = 1.f - db; w1 = db - dg; w2 = dg - dr; w3 = dr;
        a = c000; b_ = c001; c_ = c011; d = c111;
    }

    r = w0*a[0] + w1*b_[0] + w2*c_[0] + w3*d[0];
    g = w0*a[1] + w1*b_[1] + w2*c_[1] + w3*d[1];
    b = w0*a[2] + w1*b_[2] + w2*c_[2] + w3*d[2];
}

// ── M12.2b.2 Gradient helpers — solid band + smoothstep soft edge.
// Must stay op-for-op with shader_sources.cpp (preview = export).
// Feather=0 hard cut at length; Feather=1 fully soft 0→length; in-between
// keeps a full-strength core then Hermite falloff (width ∝ feather).
inline float edgeFalloff(float dist, float length, float feather) {
    float L = length < 0.f ? 0.f : (length > 1.f ? 1.f : length);
    float f = feather < 0.f ? 0.f : (feather > 1.f ? 1.f : feather);
    if (L <= 0.f) return 0.f;
    if (f <= 0.f) return dist <= L ? 1.f : 0.f;
    const float softStart = L * (1.f - f);
    const float softEnd   = L > softStart + 1e-4f ? L : softStart + 1e-4f;
    return 1.f - smoothstep01(softStart, softEnd, dist);
}
inline float edgeFalloff2(float dist, float length1,
                          float /*feather1*/, float length2, float feather2) {
    // Layer-2 anchored at length1 — see GLSL twin for rationale.
    if (dist < length1) return 0.f;
    return edgeFalloff(dist - length1, length2, feather2);
}

// Compute the per-side falloff pair (layer1, layer2) for one edge.
//   side[0..14] layout matches ShaderParams.kt comment.
//   dist     = normalized distance from that edge (0 at edge, 1 at far side).
inline void sideFalloffs(const float* side, float dist, float& f1, float& f2) {
    const float intensity1 = side[0];
    const float length1    = side[1];
    const float feather1   = side[2];
    const float tintLum1   = side[6];
    const float enable2    = side[7];
    const float intensity2 = side[8];
    const float length2_   = side[9];
    const float feather2   = side[10];
    const float tintLum2   = side[14];
    f1 = (intensity1 != 0.f || tintLum1 > 0.f)
        ? edgeFalloff(dist, length1, feather1) : 0.f;
    f2 = (enable2 > 0.5f && (intensity2 != 0.f || tintLum2 > 0.f))
        ? edgeFalloff2(dist, length1, feather1, length2_, feather2) : 0.f;
}

// In-place gradient tint blend weighted by tintLum * falloff.
//   mode 0 = Solid / light-leak (screen + soft add).
//   mode 1 = Fused (overlay, screen-biased). Mirrors GLSL blendGradTint.
inline void blendGradTint(float& r, float& g, float& b,
                          float tR, float tG, float tB,
                          float tintLum, float falloff, int mode) {
    if (tintLum <= 0.f || falloff <= 0.f) return;
    float w = tintLum * falloff;
    if (w < 0.f) w = 0.f; else if (w > 1.f) w = 1.f;
    auto screenCh = [](float c, float t) {
        return 1.f - (1.f - c) * (1.f - t);
    };
    if (mode == 1) {
        auto ov = [](float c, float t) {
            return c < 0.5f ? 2.f * c * t : 1.f - 2.f * (1.f - c) * (1.f - t);
        };
        float oR = ov(r, tR), oG = ov(g, tG), oB = ov(b, tB);
        float sR = screenCh(r, tR), sG = screenCh(g, tG), sB = screenCh(b, tB);
        oR = oR + (std::max(oR, sR) - oR) * 0.45f;
        oG = oG + (std::max(oG, sG) - oG) * 0.45f;
        oB = oB + (std::max(oB, sB) - oB) * 0.45f;
        r += (oR - r) * w;
        g += (oG - g) * w;
        b += (oB - b) * w;
    } else {
        float sR = screenCh(r, tR), sG = screenCh(g, tG), sB = screenCh(b, tB);
        float aR = r + tR * (0.55f * w);
        float aG = g + tG * (0.55f * w);
        float aB = b + tB * (0.55f * w);
        float lR = sR + (std::max(sR, aR) - sR) * 0.35f;
        float lG = sG + (std::max(sG, aG) - sG) * 0.35f;
        float lB = sB + (std::max(sB, aB) - sB) * 0.35f;
        r += (lR - r) * w;
        g += (lG - g) * w;
        b += (lB - b) * w;
    }
}

}  // anonymous namespace

ApplyMacroParams ApplyMacroParams::fromFloatArray(const float* arr, int count) {
    ApplyMacroParams p{};
    auto get = [&](int i) -> float { return (i < count) ? arr[i] : 0.f; };
    p.exposure     = get(0);
    p.contrast     = get(1);
    p.highlights   = get(2);
    p.shadows      = get(3);
    p.whites       = get(4);
    p.blacks       = get(5);
    p.saturation   = get(6);
    p.vibrance     = get(7);
    p.whiteBalance = get(8);
    p.tint         = get(9);
    for (int i = 0; i < 18; ++i) p.hsl[i] = get(10 + i);
    for (int i = 0; i < 18; ++i) p.hsl2[i] = get(211 + i);
    p.toneCurveLumaMode = get(210) > 0.5f;
    p.lutEnabled   = get(29) > 0.5f;
    // slot[31] = LUT intensity in [0, 1]. Mirrors the GLSL shader's
    // `mix(c, lutColor, uLutIntensity)`. Pre-M11 .params sidecars left
    // this 0; that combined with their lutEnabled=0 gives identity, so
    // legacy blobs still render correctly.
    {
        const float intensity = get(31);
        p.lutIntensity = (intensity < 0.f) ? 0.f : (intensity > 1.f) ? 1.f : intensity;
    }
    p.lutBwForce = get(450) > 0.5f ? 1 : 0;
    {
        const float v = get(200);
        p.lutHighlightVibrancy = (v < -1.f) ? -1.f : (v > 1.f) ? 1.f : v;
    }
    auto clamp11 = [](float v) -> float {
        return v < -1.f ? -1.f : (v > 1.f ? 1.f : v);
    };
    p.highlightTemperature = clamp11(get(201));
    p.highlightTint        = clamp11(get(202));
    p.shadowTemperature    = clamp11(get(203));
    p.shadowTint           = clamp11(get(204));
    // Slots 205/206 repurposed for bloomRadius/bloomShape (decoded below).
    // glowWarmth removed; slot 207 repurposed for filmRolloff.
    p.glowWarmth = 0.f;
    p.ambiance       = clamp11(get(208));
    p.ortonStrength       = std::max(0.f, std::min(1.f, get(209)));
    p.bloomExcludeSubject = get(238) > 0.5f ? 1.f : 0.f;
    p.subjectBloom        = std::max(0.f, std::min(1.f, get(239)));
    {
        const float br = get(205);
        // Older blobs (pre-bloom-radius) leave slot 205 at 0. Use 8 as the
        // historical default so saves don't lose their bloom blur radius.
        p.bloomRadius = (br > 0.f) ? std::min(24.f, br) : 8.f;
        const float bs = get(206);
        p.bloomShape  = (bs > 0.f) ? std::max(0.4f, std::min(1.6f, bs)) : 1.f;
    }
    p.xmpEnabled   = get(32) > 0.5f;
    p.xmpExposure  = get(33);
    p.xmpContrast  = get(34);
    p.xmpHighlights = get(35);
    p.xmpShadows   = get(36);
    p.xmpWhites    = get(37);
    p.xmpBlacks    = get(38);
    for (int i = 0; i < 18; ++i) p.xmpHsl[i] = get(39 + i);
    // M12.1 — tab opacities default to 1.0 for pre-M12.1 (57-float) blobs.
    auto getOr = [&](int i, float fallback) -> float {
        return (i < count) ? arr[i] : fallback;
    };
    p.lightTabOpacity = getOr(57, 1.f);
    p.colorTabOpacity = getOr(58, 1.f);
    p.xmpTabOpacity   = getOr(59, 1.f);
    p.dehaze          = getOr(60, 0.f);
    p.vigAmount       = getOr(61, 0.f);
    p.vigCenterX      = getOr(62, 0.5f);
    p.vigCenterY      = getOr(63, 0.5f);
    p.vigFeather      = getOr(64, 0.5f);
    p.vigIntensity    = getOr(65, 1.f);
    p.vigEffect       = getOr(66, 0.f);
    p.vigTabOpacity   = getOr(67, 1.f);
    p.gradAngle       = getOr(68, 0.f);
    for (int i = 0; i < 15; ++i) p.gradTop[i]    = getOr(69  + i, 0.f);
    for (int i = 0; i < 15; ++i) p.gradBottom[i] = getOr(84  + i, 0.f);
    for (int i = 0; i < 15; ++i) p.gradLeft[i]   = getOr(99  + i, 0.f);
    for (int i = 0; i < 15; ++i) p.gradRight[i]  = getOr(114 + i, 0.f);
    p.gradTabOpacity  = getOr(129, 1.f);
    p.gradTopApplyTo    = getOr(130, 0.f);
    p.gradBottomApplyTo = getOr(131, 0.f);
    p.gradLeftApplyTo   = getOr(132, 0.f);
    p.gradRightApplyTo  = getOr(133, 0.f);
    // Slots 391-394, NOT 380-383. ShaderParams.kt writes the blend modes at
    // 391-394 (toFloatArray) and gles_renderer.cpp reads them there too; this
    // was the only consumer reading 380-383, which are viewZoom / viewPanX /
    // viewPanY / filmicHlProtect. Because viewZoom defaults to 1.0, every CPU
    // export saw gradTopBlendMode = 1 (Fused) instead of 0 (Solid) — a silent
    // preview != export break on any gradient with non-zero intensity.
    p.gradTopBlendMode    = getOr(391, 0.f);
    p.gradBottomBlendMode = getOr(392, 0.f);
    p.gradLeftBlendMode   = getOr(393, 0.f);
    p.gradRightBlendMode  = getOr(394, 0.f);
    p.maskBrightness    = getOr(134, 0.f);
    p.maskContrast      = getOr(135, 0.f);
    p.maskTemperature   = getOr(136, 0.f);
    p.maskTint          = getOr(137, 0.f);
    p.maskSaturation    = getOr(138, 0.f);
    p.maskClarity       = getOr(139, 0.f);
    p.maskTabOpacity    = getOr(140, 1.f);
    p.tonemapExposure   = getOr(141, 0.f);
    p.tonemapHighlights = getOr(142, 0.f);
    p.tonemapShadows    = getOr(143, 0.f);
    // Multi-layer mask. Layer 0 mirrors the scalar mask block ([134..140]).
    // Layers 1..3 occupy the appended tail at [157..177] (after the
    // native-only CLAHE/NR/Detail slots [144..156]).
    p.maskLayer[0].brightness  = p.maskBrightness;
    p.maskLayer[0].contrast    = p.maskContrast;
    p.maskLayer[0].temperature = p.maskTemperature;
    p.maskLayer[0].tint        = p.maskTint;
    p.maskLayer[0].saturation  = p.maskSaturation;
    p.maskLayer[0].clarity     = p.maskClarity;
    p.maskLayer[0].opacity     = p.maskTabOpacity;
    for (int li = 1; li < kMaskLayers; ++li) {
        const int b = 157 + (li - 1) * 7;
        p.maskLayer[li].brightness  = getOr(b + 0, 0.f);
        p.maskLayer[li].contrast    = getOr(b + 1, 0.f);
        p.maskLayer[li].temperature = getOr(b + 2, 0.f);
        p.maskLayer[li].tint        = getOr(b + 3, 0.f);
        p.maskLayer[li].saturation  = getOr(b + 4, 0.f);
        p.maskLayer[li].clarity     = getOr(b + 5, 0.f);
        p.maskLayer[li].opacity     = getOr(b + 6, 1.f);
    }
    // Per-layer masked sharpness appended at [396..399] (append-only ABI).
    for (int li = 0; li < kMaskLayers; ++li) {
        p.maskLayer[li].sharpness = getOr(396 + li, 0.f);
    }
    // Per-layer masked tone regions appended at [410..425] (append-only ABI):
    // highlights [410..413], shadows [414..417], whites [418..421],
    // blacks [422..425]. Old blobs shorter than 426 → 0 (inert).
    for (int li = 0; li < kMaskLayers; ++li) {
        p.maskLayer[li].highlights = getOr(410 + li, 0.f);
        p.maskLayer[li].shadows    = getOr(414 + li, 0.f);
        p.maskLayer[li].whites     = getOr(418 + li, 0.f);
        p.maskLayer[li].blacks     = getOr(422 + li, 0.f);
    }
    // Luminance mask per layer: [182..184] L0 … [191..193] L3 = target,
    // spread, feather. spread 0 (old blob) → off.
    for (int li = 0; li < kMaskLayers; ++li) {
        const int b = 182 + li * 3;
        p.maskLayer[li].lumTarget  = getOr(b + 0, 0.f);
        p.maskLayer[li].lumSpread  = getOr(b + 1, 0.f);
        p.maskLayer[li].lumFeather = getOr(b + 2, 0.f);
    }
    // Luma↔bitmap combine mode for the mask-tab editing layer (layer 0) @ [395].
    p.maskLayer[0].lumCombine = (int)(getOr(395, 0.f) + 0.5f);
    // Per-segment levels (Normalize for 3Dlut). Old blobs → 0 (inert).
    p.whitesSubject    = getOr(194, 0.f);
    p.blacksSubject    = getOr(195, 0.f);
    p.whitesBackground = getOr(196, 0.f);
    p.blacksBackground = getOr(197, 0.f);
    p.shadowsSubject    = getOr(198, 0.f);
    p.shadowsBackground = getOr(199, 0.f);
    p.purpleFringeMode  = getOr(449, 0.f);  // was wrongly sharing [199]
    p.highlightsSubject    = getOr(229, 0.f);
    p.highlightsBackground = getOr(230, 0.f);
    p.ambianceSubject      = getOr(231, 0.f);
    p.ambianceBackground   = getOr(232, 0.f);
    // Phase-1 backport slots.
    p.filmRolloff       = getOr(207, 0.f);
    p.filmicLuma        = getOr(451, 0.f);
    p.oklabHlChroma     = getOr(452, 0.f);
    p.gamutCompress     = getOr(237, 0.f);
    p.cgShadowsR        = getOr(240, 0.5f);
    p.cgShadowsG        = getOr(241, 0.5f);
    p.cgShadowsB        = getOr(242, 0.5f);
    p.cgShadowsSat      = getOr(243, 0.f);
    p.cgMidtonesR       = getOr(244, 0.5f);
    p.cgMidtonesG       = getOr(245, 0.5f);
    p.cgMidtonesB       = getOr(246, 0.5f);
    p.cgMidtonesSat     = getOr(247, 0.f);
    p.cgHighlightsR     = getOr(248, 0.5f);
    p.cgHighlightsG     = getOr(249, 0.5f);
    p.cgHighlightsB     = getOr(250, 0.5f);
    p.cgHighlightsSat   = getOr(251, 0.f);
    // [436..446]. NOT 435 — that slot is lensFlareWarmth, which only the
    // native side declares (Kotlin never writes it), so the clash would have
    // been invisible from Kotlin and shown up as lens-flare warmth turning
    // itself on whenever Recovery moved.
    p.filmRecovery   = getOr(436, 0.f);
    p.filmFillLight  = getOr(437, 0.f);
    p.filmMonochrome = getOr(438, 0.f) > 0.5f ? 1 : 0;
    for (int i = 0; i < 8; ++i) p.filmGrayMix[i] = getOr(439 + i, 0.f);

    // Global (Offset) wheel — appended at [426..429] (append-only ABI).
    p.cgGlobalR         = getOr(426, 0.5f);
    p.cgGlobalG         = getOr(427, 0.5f);
    p.cgGlobalB         = getOr(428, 0.5f);
    p.cgGlobalSat       = getOr(429, 0.f);
    p.centerPop         = getOr(252, 0.f);
    for (int i = 0; i < 24; ++i) p.hslFull[i] = getOr(253 + i, 0.f);
    p.detailGrainRoughness = getOr(341, 0.f);
    p.colorDensity      = getOr(343, 0.f);
    p.skintoneWarm      = getOr(344, 0.f);
    p.skintoneSmooth    = getOr(345, 0.f);
    p.skintoneLuma      = getOr(346, 0.f);
    p.pushPull          = getOr(349, 0.f);
    // FX tab (slots 352–374).
    p.aberStrength       = getOr(352, 0.f);
    p.aberFringeReduce   = getOr(353, 0.f);
    p.fxGaussBlur        = getOr(354, 0.f);
    p.fxDirBlurAmt       = getOr(355, 0.f);
    p.fxDirBlurAngle     = getOr(356, 0.f);
    p.fxRadBlurAmt       = getOr(357, 0.f);
    p.fxRadBlurCx        = getOr(358, 0.5f);
    p.fxRadBlurCy        = getOr(359, 0.5f);
    p.fxZoomBlurAmt      = getOr(360, 0.f);
    p.fxZoomBlurCx       = getOr(361, 0.5f);
    p.fxZoomBlurCy       = getOr(362, 0.5f);
    p.fxBlurStyle        = getOr(363, 0.f);
    p.fxBlurExcludeSubject = getOr(364, 0.f);
    p.fxMist             = getOr(365, 0.f);
    p.fxMistWarmth       = getOr(366, 0.f);
    p.fxDust             = getOr(367, 0.f);
    p.fxDustSize         = getOr(368, 0.f);
    p.fxVintageStrength  = getOr(369, 0.f);
    p.fxVintageFade      = getOr(370, 0.f);
    p.fxVintageVig       = getOr(371, 0.f);
    p.fxGlowStrength     = getOr(372, 0.f);
    p.fxGlowSpread       = getOr(373, 0.f);
    p.fxGlowWarmth       = getOr(374, 0.f);
    p.mistTightness      = getOr(447, 0.55f);
    p.mistHalation       = getOr(448, 0.f);
    p.lensFlareX          = getOr(400, -0.5f);
    p.lensFlareY          = getOr(401, -0.5f);
    p.lensFlareBrightness = getOr(409, 0.f);
    p.lensFlareSize       = getOr(430, 1.f);
    p.lensFlareSpread     = getOr(431, 1.f);
    p.lensFlareWarmth     = getOr(435, 0.f);
    p.colorShiftRedX      = getOr(432, 0.f);
    p.colorShiftGreenX    = getOr(433, 0.f);
    p.colorShiftBlueX     = getOr(434, 0.f);
    // Haxademic film grain extension — slots 375–378.
    p.haxGrainCrossfade = getOr(375, 0.f);
    p.haxGrainScale     = getOr(376, 1.f);
    p.haxGrainLumaAmp   = getOr(377, 1.f);
    p.haxGrainChromaAmp = getOr(378, 0.f);
    // LUT gamut spaces — match GLSL slots 235/236.
    p.workspaceSpace   = static_cast<int>(getOr(235, 1.f));
    p.lutAuthoredSpace = static_cast<int>(getOr(236, 0.f));
    // Smart Color Enhancement ("Color Pop") — slots 384..390.
    p.smartColorEnhance = getOr(384, 0.f);
    p.smartWbRMin       = getOr(385, 0.f);
    p.smartWbRMax       = getOr(386, 1.f);
    p.smartWbGMin       = getOr(387, 0.f);
    p.smartWbGMax       = getOr(388, 1.f);
    p.smartWbBMin       = getOr(389, 0.f);
    p.smartWbBMax       = getOr(390, 1.f);

    // ── Verbose Stage-C adjustment debug dump ────────────────────────────────
    // Fires on every Stage C export call so logcat shows exactly what params
    // apply_macro received versus what the GL preview had. Reset on workspace
    // selector open (generation bump).
    {
        static int s_lastGen = -1;
        const int curGen = g_debugLogGeneration.load(std::memory_order_relaxed);
        if (curGen != s_lastGen) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG_AM,
                "[AM-ADJ] --- workspace selector opened (gen=%d) — log reset ---", curGen);
            s_lastGen = curGen;
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG_AM,
            "[AM-ADJ] LIGHT: exp=%.3f con=%.3f hi=%.3f sh=%.3f wh=%.3f bl=%.3f sat=%.3f vib=%.3f wb=%.1f tint=%.3f",
            p.exposure, p.contrast, p.highlights, p.shadows,
            p.whites, p.blacks, p.saturation, p.vibrance,
            p.whiteBalance, p.tint);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG_AM,
            "[AM-ADJ] TONE: tonemapExp=%.3f ambiance=%.3f dehaze=%.3f vig=%.3f filmRolloff=%.3f",
            p.tonemapExposure, p.ambiance,
            p.dehaze, p.vigAmount, p.filmRolloff);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG_AM,
            "[AM-ADJ] LUT: enabled=%d intensity=%.3f hlVibrancy=%.3f",
            p.lutEnabled ? 1 : 0, p.lutIntensity, p.lutHighlightVibrancy);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG_AM,
            "[AM-ADJ] FILM: recovery=%.3f fillLight=%.3f mono=%d",
            p.filmRecovery, p.filmFillLight, p.filmMonochrome);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG_AM,
            "[AM-ADJ] COLOR: hlTemp=%.3f hlTint=%.3f shTemp=%.3f shTint=%.3f",
            p.highlightTemperature, p.highlightTint,
            p.shadowTemperature, p.shadowTint);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG_AM,
            "[AM-ADJ] SUBJ-TONE: hiSubj=%.3f shSubj=%.3f whSubj=%.3f ambSubj=%.3f | hiBg=%.3f shBg=%.3f whBg=%.3f ambBg=%.3f",
            p.highlightsSubject, p.shadowsSubject, p.whitesSubject, p.ambianceSubject,
            p.highlightsBackground, p.shadowsBackground, p.whitesBackground, p.ambianceBackground);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG_AM,
            "[AM-ADJ] BLOOM: orton=%.3f excludeSubj=%d subjBloom=%.3f",
            p.ortonStrength, int(p.bloomExcludeSubject > 0.5f), p.subjectBloom);
    }

    return p;
}

namespace {

// M12.2c.1 — bilinear sample of U2Net subject mask, with squaring profile
// to match the GLSL `subjectGate()` helper.
inline float sampleSubjectMaskSquared(const ApplyMacroSubjectMask* m,
                                      float u, float v) {
    if (!m || !m->data || m->w <= 0 || m->h <= 0) return 1.f;
    // Mirror GL: mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord)
    u = m->rectU0 + u * (m->rectU1 - m->rectU0);
    v = m->rectV0 + v * (m->rectV1 - m->rectV0);
    if (u < 0.f) u = 0.f; else if (u > 1.f) u = 1.f;
    if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
    const float fx = u * float(m->w - 1);
    const float fy = v * float(m->h - 1);
    int x0 = int(fx); int y0 = int(fy);
    int x1 = x0 + 1; if (x1 > m->w - 1) x1 = m->w - 1;
    int y1 = y0 + 1; if (y1 > m->h - 1) y1 = m->h - 1;
    const float dx = fx - float(x0);
    const float dy = fy - float(y0);
    const float c00 = m->data[y0 * m->w + x0];
    const float c10 = m->data[y0 * m->w + x1];
    const float c01 = m->data[y1 * m->w + x0];
    const float c11 = m->data[y1 * m->w + x1];
    const float top    = c00 * (1.f - dx) + c10 * dx;
    const float bottom = c01 * (1.f - dx) + c11 * dx;
    float p = top * (1.f - dy) + bottom * dy;
    if (p < 0.f) p = 0.f; else if (p > 1.f) p = 1.f;
    return p * p;
}

// Linear subject-mask sample (no squaring) for FX blur exclusion.
inline float sampleSubjectMask(const ApplyMacroSubjectMask* m,
                               float u, float v) {
    if (!m || !m->data || m->w <= 0 || m->h <= 0) return 1.f;
    u = m->rectU0 + u * (m->rectU1 - m->rectU0);
    v = m->rectV0 + v * (m->rectV1 - m->rectV0);
    if (u < 0.f) u = 0.f; else if (u > 1.f) u = 1.f;
    if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
    const float fx = u * float(m->w - 1);
    const float fy = v * float(m->h - 1);
    int x0 = int(fx); int y0 = int(fy);
    int x1 = x0 + 1; if (x1 > m->w - 1) x1 = m->w - 1;
    int y1 = y0 + 1; if (y1 > m->h - 1) y1 = m->h - 1;
    const float dx = fx - float(x0);
    const float dy = fy - float(y0);
    const float c00 = m->data[y0 * m->w + x0];
    const float c10 = m->data[y0 * m->w + x1];
    const float c01 = m->data[y1 * m->w + x0];
    const float c11 = m->data[y1 * m->w + x1];
    const float top    = c00 * (1.f - dx) + c10 * dx;
    const float bottom = c01 * (1.f - dx) + c11 * dx;
    float p = top * (1.f - dy) + bottom * dy;
    if (p < 0.f) p = 0.f; else if (p > 1.f) p = 1.f;
    return p;
}

// Protect-subject soft near-band for mistHalation R/B offset.
// Returns 1 far from subject, 0 on subject + soft ramp over ~0.10*longSide
// outside the silhouette. MUST mirror shader_sources.cpp::halationProtectScale().
// imgW/imgH = graded buffer dims (aspect-correct UV radius); if unknown, fall
// back to isotropic 0.10 image-UV.
inline float halationProtectScaleP(const ApplyMacroSubjectMask* mask,
                                   float u, float v, int imgW, int imgH) {
    if (!mask || !mask->data || mask->w <= 0 || mask->h <= 0) return 1.f;
    float stepU, stepV;
    if (imgW > 0 && imgH > 0) {
        const float w = float(imgW);
        const float h = float(imgH);
        const float longSide = w > h ? w : h;
        const float radiusPx = 0.10f * longSide;
        stepU = (radiusPx / w) * 0.5f;
        stepV = (radiusPx / h) * 0.5f;
    } else {
        stepU = stepV = 0.10f * 0.5f;
    }
    float acc = 0.f;
    for (int ty = -2; ty <= 2; ++ty)
        for (int tx = -2; tx <= 2; ++tx)
            acc += sampleSubjectMask(mask, u + float(tx) * stepU, v + float(ty) * stepV);
    const float near = acc / 25.f;
    return 1.f - smoothstep01(0.05f, 0.45f, near);
}

// Pure target-vs-subjectSquared gate. Callers MUST short-circuit to 1.0
// when no subject mask is present (see applyMacroPixelImpl) so that
// per-segment effects (target=1 Subject, target=2 Background) apply at
// full strength without a mask — matches GL shader's subjectGate().
inline float segGate(int target, float subjectSquared) {
    if (target == 0) return 1.f;
    if (target == 1) return subjectSquared;        // Subject only
    if (target == 2) return 1.f - subjectSquared;  // Background only
    return 1.f;
}

// M12.2c.2b — bilinear sample of a brush-mask layer alpha at (u,v). No
// squaring (unlike the subject mask) — the painted alpha is used directly,
// matching the GLSL `texture(uBrushMaskN, vTexCoord).r`.
inline float sampleMaskAlpha(const ApplyMacroMaskLayer& m, float u, float v) {
    if (!m.data || m.w <= 0 || m.h <= 0) return 0.f;
    if (u < 0.f) u = 0.f; else if (u > 1.f) u = 1.f;
    if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
    const float fx = u * float(m.w - 1);
    const float fy = v * float(m.h - 1);
    int x0 = int(fx); int y0 = int(fy);
    int x1 = x0 + 1; if (x1 > m.w - 1) x1 = m.w - 1;
    int y1 = y0 + 1; if (y1 > m.h - 1) y1 = m.h - 1;
    const float dx = fx - float(x0);
    const float dy = fy - float(y0);
    const float c00 = m.data[y0 * m.w + x0];
    const float c10 = m.data[y0 * m.w + x1];
    const float c01 = m.data[y1 * m.w + x0];
    const float c11 = m.data[y1 * m.w + x1];
    const float top    = c00 * (1.f - dx) + c10 * dx;
    const float bottom = c01 * (1.f - dx) + c11 * dx;
    return top * (1.f - dy) + bottom * dy;
}

}  // anonymous namespace

// Core kernel — used by every applyMacroPixel overload. Pass mask=nullptr
// to disable segmentation gating (kernel falls back to "All" everywhere).
// `blurRgb` is the corresponding pixel from a Gaussian-blurred copy of the
// source — same blur the shader's `uBlurTex` provides. Used by ambiance and
// Orton. Pass nullptr to skip both passes (matches the no-ambiance/no-orton
// case the previous export hit silently).
static void applyMacroPixelImpl(float* io, float u, float v,
                                const ApplyMacroParams& p,
                                const ApplyMacroLut* lut,
                                const ApplyMacroSubjectMask* mask,
                                const ApplyMacroMaskLayers* maskLayers,
                                const float* blurRgb = nullptr,
                                const float* bloomRgb = nullptr,
                                const float* blurBuf = nullptr,
                                int blurW = 0, int blurH = 0,
                                const float* bloomBuf = nullptr,
                                const ApplyMacroSubjectMask* atten = nullptr,
                                const float* srcBuf = nullptr,
                                int srcW = 0, int srcH = 0) {
    // ── Smart Color Enhancement ("Color Pop", slots 384..390) ────────────────
    // Applied FIRST on the input sRGB color — exactly as the GL preview does
    // (gles_renderer main() blends it before any other op) — so the saved file
    // matches the canvas. smartColorEnhance is a STRENGTH in [0..1] (Off/Low/Med/
    // High = 0/0.4/0.7/1.0); blend the full effect toward the original by it.
    if (p.smartColorEnhance > 0.f) {
        const float orr = io[0], og = io[1], ob = io[2];
        applySmartColorEnhanceP(io[0], io[1], io[2], p);
        const float s = p.smartColorEnhance < 0.f ? 0.f
                      : (p.smartColorEnhance > 1.f ? 1.f : p.smartColorEnhance);
        io[0] = orr + (io[0] - orr) * s;
        io[1] = og  + (io[1] - og ) * s;
        io[2] = ob  + (io[2] - ob ) * s;
    }

    // ── Purple-fringe desat (slot [449] Strong=2) — mirrors shader_sources.cpp.
    // Neighbour clip gate samples the SOURCE buffer at centre UV (±3 px),
    // while purpleScore uses the running pixel (post smart-color / geometry).
    if ((int)(p.purpleFringeMode + 0.5f) == 2 && srcBuf && srcW > 0 && srcH > 0) {
        float& r = io[0]; float& g = io[1]; float& b = io[2];
        const float mag = (r + b) * 0.5f - g;
        const float vv  = (r < b) ? r : b;
        float purpleScore = mag * 8.f;
        if (purpleScore < 0.f) purpleScore = 0.f; else if (purpleScore > 1.f) purpleScore = 1.f;
        float vGate = (vv - g) * 8.f;
        if (vGate < 0.f) vGate = 0.f; else if (vGate > 1.f) vGate = 1.f;
        purpleScore *= vGate;
        if (purpleScore > 0.f) {
            auto sampleSrc = [&](float su, float sv, float& oR, float& oG, float& oB) {
                if (su < 0.f) su = 0.f; else if (su > 1.f) su = 1.f;
                if (sv < 0.f) sv = 0.f; else if (sv > 1.f) sv = 1.f;
                const float fx = su * float(srcW - 1);
                const float fy = sv * float(srcH - 1);
                const int x0 = (int)fx, y0 = (int)fy;
                const int x1 = (x0 + 1 < srcW) ? x0 + 1 : x0;
                const int y1 = (y0 + 1 < srcH) ? y0 + 1 : y0;
                const float tx = fx - float(x0), ty = fy - float(y0);
                auto pix = [&](int x, int y) -> const float* {
                    return srcBuf + (size_t(y) * srcW + x) * 3;
                };
                const float* p00 = pix(x0, y0); const float* p10 = pix(x1, y0);
                const float* p01 = pix(x0, y1); const float* p11 = pix(x1, y1);
                oR = p00[0] + (p10[0] - p00[0]) * tx; oR += (p01[0] + (p11[0] - p01[0]) * tx - oR) * ty;
                oG = p00[1] + (p10[1] - p00[1]) * tx; oG += (p01[1] + (p11[1] - p01[1]) * tx - oG) * ty;
                oB = p00[2] + (p10[2] - p00[2]) * tx; oB += (p01[2] + (p11[2] - p01[2]) * tx - oB) * ty;
            };
            const float px = 3.f / float(srcW), py = 3.f / float(srcH);
            float nR, nG, nB;
            float maxN = 0.f;
            sampleSrc(u + px, v + py, nR, nG, nB); maxN = std::max(maxN, std::max(nR, std::max(nG, nB)));
            sampleSrc(u - px, v + py, nR, nG, nB); maxN = std::max(maxN, std::max(nR, std::max(nG, nB)));
            sampleSrc(u + px, v - py, nR, nG, nB); maxN = std::max(maxN, std::max(nR, std::max(nG, nB)));
            sampleSrc(u - px, v - py, nR, nG, nB); maxN = std::max(maxN, std::max(nR, std::max(nG, nB)));
            float clipNear = smoothstep01(0.85f, 0.98f, maxN);
            float strength = purpleScore * clipNear;
            if (strength > 0.f) {
                const float luma = r * 0.2126f + g * 0.7152f + b * 0.0722f;
                r = r + (luma - r) * strength;
                g = g + (luma - g) * strength;
                b = b + (luma - b) * strength;
            }
        }
    }

    const float baseR = io[0], baseG = io[1], baseB = io[2];

    // M12.2c.1 — Sample subject mask once per pixel and reuse across
    // Vignette + 4 gradient sides. Returns 1.0 if no mask is bound.
    const float subjSq = sampleSubjectMaskSquared(mask, u, v);
    // Match GL shader's subjectGate() semantics: when no mask exists,
    // return 1.0 regardless of target so per-segment effects apply at
    // full strength (the user sees no-mask = act-as-if-everything-is-target).
    // CPU's `1 - subjSq` would produce 0 for target=2 when mask is null,
    // gating Background-only vignette/gradient off entirely.
    const bool hasSubjectMask = (mask && mask->data && mask->w > 0 && mask->h > 0);
    // Block Subject/Background-targeted vignette when mask is absent so it
    // doesn't apply to the entire frame (mirrors the GLSL fix for Cause 3).
    const int vigEffectInt = int(p.vigEffect);
    const float vigGate = hasSubjectMask
        ? segGate(vigEffectInt, subjSq)
        : (vigEffectInt != 0 ? 0.f : 1.f);

    // Tab opacities. Used by the SEQUENTIAL chain below — each tab grades the
    // running image and is blended via mix(img, tab(img), opacity), mirroring
    // the GL preview shader (which was migrated off the old parallel delta-sum).
    auto clampOpacity = [](float t) -> float { return t < 0.f ? 0.f : (t > 1.f ? 1.f : t); };
    const float lOp = clampOpacity(p.lightTabOpacity);
    const float cOp = clampOpacity(p.colorTabOpacity);
    const float xOp = p.xmpEnabled ? clampOpacity(p.xmpTabOpacity) : 0.f;
    const float vOp = clampOpacity(p.vigTabOpacity);
    const float gOp = clampOpacity(p.gradTabOpacity);

    // ── Light tab — stacked internally ──────────────────────────────────
    //   Tonemap-tab tone values stack on top inside this pass; same
    //   helpers, independent uniforms (matches GLSL).
    float lR = baseR, lG = baseG, lB = baseB;
    // bloomExpoComp removed — matches gles_renderer.cpp where the compensation
    // was also removed (the highlight-protect gate prevents bloom from clipping
    // highlights, so pre-darkening the whole image is unnecessary and causes
    // the Stage B kernel to diverge from the GL shader, producing a visible
    // brightness pop on every pre-graded AHB swap).
    applyExposureContrastP(lR, lG, lB, p.exposure, 0.f);
    if (p.tonemapExposure != 0.f) {
        applyExposureContrastP(lR, lG, lB, p.tonemapExposure, 0.f);
    }
    applyWbTint           (lR, lG, lB, p.whiteBalance, p.tint);
    applyToneRegionsP     (lR, lG, lB, p.highlights, p.shadows, p.whites, p.blacks);
    // Per-segment levels (Normalize for 3Dlut). Compute subject- and bg-targeted
    // remaps, blend by raw subject probability. Runs only when a subject mask
    // is present AND at least one of the four sliders is non-zero.
    const bool hasMask = (mask && mask->data && mask->w > 0 && mask->h > 0);
    if (hasMask && (p.whitesSubject != 0.f || p.blacksSubject != 0.f ||
                    p.whitesBackground != 0.f || p.blacksBackground != 0.f ||
                    p.shadowsSubject != 0.f || p.shadowsBackground != 0.f ||
                    p.highlightsSubject != 0.f || p.highlightsBackground != 0.f)) {
        // hi-slot now carries per-segment highlights (was hardcoded 0.f) so
        // export matches the GL preview's applyToneRegionsP call shape.
        float sR = lR, sG = lG, sB = lB;
        applyToneRegionsP(sR, sG, sB, p.highlightsSubject, p.shadowsSubject,
            p.whitesSubject, p.blacksSubject);
        float bR = lR, bG = lG, bB = lB;
        applyToneRegionsP(bR, bG, bB, p.highlightsBackground, p.shadowsBackground,
            p.whitesBackground, p.blacksBackground);
        const float pm = std::sqrt(subjSq);   // raw subject probability [0..1]
        lR = bR + (sR - bR) * pm;
        lG = bG + (sG - bG) * pm;
        lB = bB + (sB - bB) * pm;
    }
    if (p.tonemapHighlights != 0.f || p.tonemapShadows != 0.f) {
        applyToneRegionsP (lR, lG, lB, p.tonemapHighlights, p.tonemapShadows, 0.f, 0.f);
    }
    // filmRolloff — after tone regions, before ambiance. Mirrors GL pipeline order.
    applyFilmRolloffP(lR, lG, lB, p.filmRolloff);
    // Auto filmic when depth+bokeh would be live on GL — CPU Stage C
    // has no depth gate yet; honor explicit strengths only here.
    applyFilmicLumaP(lR, lG, lB, p.filmicLuma);
    // Ambiance — mirrors shader gles_renderer.cpp:1211-1224. Critical: the
    // blur sample passed in is UNGRADED (raw Gaussian of source), so before
    // doing detail = light - blur, we must re-apply the same upstream grade
    // to the blur sample so both are in the same tonal space. Otherwise the
    // detail layer leaks exposure/WB/tone-region offsets and ambiance ends
    // up amplifying the graded delta rather than just local contrast.
    if (blurRgb != nullptr) {
        float effAmb = p.ambiance;
        const bool hasPerSegAmb = (p.ambianceSubject != 0.f || p.ambianceBackground != 0.f);
        if (hasMask && hasPerSegAmb) {
            const float pm = std::sqrt(subjSq);  // raw subject probability
            // Add per-segment delta on top of global (mirrors gles_renderer.cpp fix).
            effAmb = p.ambiance + p.ambianceBackground + (p.ambianceSubject - p.ambianceBackground) * pm;
        }
        if (effAmb != 0.f) {
            // Re-grade blur sample to match lR/lG/lB tonal space. Mirrors
            // gles_renderer.cpp: 1) exposure (no bloomExpoComp — removed),
            // 2) tonemap exposure if set, 3) WB+tint,
            // 4) tone regions (global only — per-segment skipped, same as GL).
            float tbR = blurRgb[0], tbG = blurRgb[1], tbB = blurRgb[2];
            applyExposureContrastP(tbR, tbG, tbB, p.exposure, 0.f);
            if (p.tonemapExposure != 0.f) {
                applyExposureContrastP(tbR, tbG, tbB, p.tonemapExposure, 0.f);
            }
            applyWbTint      (tbR, tbG, tbB, p.whiteBalance, p.tint);
            applyToneRegionsP(tbR, tbG, tbB, p.highlights, p.shadows, p.whites, p.blacks);
            applyAmbianceP(lR, lG, lB, tbR, tbG, tbB, effAmb);
        }
        // centerPop — radial-masked local contrast. Uses the same tonal blur.
        // Mirrors gles_renderer.cpp:1480-1487. Requires UV coords.
        if (p.centerPop != 0.f) {
            float tbR2 = blurRgb[0], tbG2 = blurRgb[1], tbB2 = blurRgb[2];
            applyExposureContrastP(tbR2, tbG2, tbB2, p.exposure, 0.f);
            if (p.tonemapExposure != 0.f) applyExposureContrastP(tbR2, tbG2, tbB2, p.tonemapExposure, 0.f);
            applyWbTint      (tbR2, tbG2, tbB2, p.whiteBalance, p.tint);
            applyToneRegionsP(tbR2, tbG2, tbB2, p.highlights, p.shadows, p.whites, p.blacks);
            // Radial mask: d = distance from centre, scaled by aspect.
            // Image aspect not available per-pixel — approximate 1:1 (square).
            // For most photos this is close enough; the effect is subtle at edges.
            const float dx2 = (u - 0.5f) * 2.f;
            const float dy2 = (v - 0.5f) * 2.f;
            const float d = std::sqrt(dx2 * dx2 + dy2 * dy2) * 0.5f;
            const float mask2 = 1.f - smoothstep01(0.025f, 0.775f, d);
            const float strength = p.centerPop * (2.f * mask2 - 1.f) * 0.9f;
            applyLocalContrastCpu(lR, lG, lB, tbR2, tbG2, tbB2, strength);
        }
    }
    applyExposureContrastP(lR, lG, lB, 0.f, p.contrast);
    // Dehaze — atmospheric-scattering model, byte-for-byte mirror of the GL
    // shader (gles_renderer.cpp:1530-1542). Neutral atmospheric light A=(1,1,1):
    //   J = (I - A)/t + A, with dark-channel proxy min(R,G,B), highlight gate,
    //   and t clamped to 0.15. Negative dehaze interpolates back toward A.
    // (Previous CPU port used a crude per-channel midtone bell that did NOT
    //  match the preview — fixed here so dehaze saves match the canvas.)
    if (p.dehaze != 0.f) {
        const float A = 1.0f;
        if (p.dehaze > 0.f) {
            const float darkCh = std::min(std::min(lR, lG), lB);
            const float luma   = 0.2627f * lR + 0.6780f * lG + 0.0593f * lB;
            // Noise-robust dark-channel proxy (see gles_renderer.cpp): raw
            // min(R,G,B) latches onto the per-channel noise trough in shadows,
            // speckling the transmission map; averaging with luma cancels it.
            const float dark   = 0.5f * darkCh + 0.5f * luma;
            const float hiGate = 1.f - smoothstep01(0.75f, 0.98f, dark);
            const float t      = std::max(1.f - p.dehaze * (dark / (dark + 0.2f)) * 0.85f * hiGate, 0.15f);
            const float dR = (lR - A) / t + A;
            const float dG = (lG - A) / t + A;
            const float dB = (lB - A) / t + A;
            // Shadow-protect: (·)/t amplifies shadow noise by 1/t, so fade the
            // dehaze back toward the original as luma approaches black.
            const float shadowGate = smoothstep01(0.02f, 0.22f, luma);
            lR += (dR - lR) * shadowGate;
            lG += (dG - lG) * shadowGate;
            lB += (dB - lB) * shadowGate;
        } else {
            float m = -p.dehaze; if (m < 0.f) m = 0.f; else if (m > 1.f) m = 1.f;
            m *= 0.5f;
            lR += (A - lR) * m;
            lG += (A - lG) * m;
            lB += (A - lB) * m;
        }
    }

    // ── Sequential chain begins: Light tab blended onto the base ─────────
    // Subsequent tabs grade THIS running image (not the raw base), so the
    // export matches the GL preview's chained compositing.
    float outR = baseR + (lR - baseR) * lOp;
    float outG = baseG + (lG - baseG) * lOp;
    float outB = baseB + (lB - baseB) * lOp;

    // ── Color tab — grades the running (lit) image ──────────────────────
    float cR = outR, cG = outG, cB = outB;
    applySaturationVibrance(cR, cG, cB, p.saturation, p.vibrance);
    applyHsl12ShiftsP      (cR, cG, cB, p.hsl, p.hsl2);
    // Phase-1 backport: color grading wheels → hslFull → colorDensity → skintone.
    // Pipeline order mirrors gles_renderer.cpp (after HSL shifts, before fan-out).
    applyColorGradingP(cR, cG, cB,
        p.cgShadowsR,    p.cgShadowsG,    p.cgShadowsB,    p.cgShadowsSat,
        p.cgMidtonesR,   p.cgMidtonesG,   p.cgMidtonesB,   p.cgMidtonesSat,
        p.cgHighlightsR, p.cgHighlightsG, p.cgHighlightsB, p.cgHighlightsSat,
        p.cgGlobalR,     p.cgGlobalG,     p.cgGlobalB,     p.cgGlobalSat);
    applyHslFullP    (cR, cG, cB, p.hslFull);
    applyColorDensityP(cR, cG, cB, p.colorDensity);
    applySkintoneP   (cR, cG, cB, p.skintoneWarm, p.skintoneSmooth, p.skintoneLuma);
    outR = outR + (cR - outR) * cOp;
    outG = outG + (cG - outG) * cOp;
    outB = outB + (cB - outB) * cOp;

    // ── XMP overlay tab — grades the running image ──────────────────────
    float xR = outR, xG = outG, xB = outB;
    if (p.xmpEnabled) {
        applyExposureContrastP(xR, xG, xB, p.xmpExposure, p.xmpContrast);
        applyToneRegionsP     (xR, xG, xB, p.xmpHighlights, p.xmpShadows,
                               p.xmpWhites, p.xmpBlacks);
        applyHslShiftsP       (xR, xG, xB, p.xmpHsl);
    }
    outR = outR + (xR - outR) * xOp;
    outG = outG + (xG - outG) * xOp;
    outB = outB + (xB - outB) * xOp;

    // ── M12.2b Vignette tab — radial darken/lighten, identical to GLSL.
    float vR = outR, vG = outG, vB = outB;
    if (p.vigAmount != 0.f && p.vigIntensity > 0.f && vigGate > 0.f) {
        const float dx = u - p.vigCenterX;
        const float dy = v - p.vigCenterY;
        const float r2 = std::sqrt(dx*dx + dy*dy);
        // Mirror GL shader exactly (gles_renderer.cpp:1326-1327):
        //   softness = 1 - vigFeather
        //   innerR   = mix(0.7, 0.0, softness) = 0.7 * vigFeather
        // Earlier CPU port had `innerR = (1 - vigFeather) * 0.7` which
        // is the INVERSE — at default vigFeather=0 it produced a thin
        // hard ring (invisible) instead of the wide soft vignette the
        // editor renders. Vignette stored value matches v2 convention
        // (0 = soft / fills frame, 1 = hard / edge-only ring).
        const float vf = (p.vigFeather < 0.f) ? 0.f : (p.vigFeather > 1.f ? 1.f : p.vigFeather);
        const float innerR = 0.7f * vf;
        const float outerR = 0.7071068f;  // sqrt(2)/2
        float t = (r2 - innerR) / std::max(outerR - innerR, 1e-6f);
        if (t < 0.f) t = 0.f; else if (t > 1.f) t = 1.f;
        // smoothstep
        t = t * t * (3.f - 2.f * t);
        float mask = t * (p.vigIntensity < 0.f ? 0.f
                        : (p.vigIntensity > 1.f ? 1.f : p.vigIntensity));
        // Fold the segmentation gate into the radial mask so feathering
        // works across both spatial AND semantic boundaries (matches GLSL).
        mask *= vigGate;
        const float mul = 1.f + p.vigAmount * mask;
        vR = outR * mul;
        vG = outG * mul;
        vB = outB * mul;
    }
    outR = outR + (vR - outR) * vOp;
    outG = outG + (vG - outG) * vOp;
    outB = outB + (vB - outB) * vOp;

    // ── M12.2b.2 Gradient tab — 4 sides × 2 layers, global rotation ────
    float gR = outR, gG = outG, gB = outB;
    {
        const bool any =
            p.gradTop[0] != 0.f || p.gradBottom[0] != 0.f ||
            p.gradLeft[0] != 0.f || p.gradRight[0] != 0.f ||
            p.gradTop[6] > 0.f || p.gradBottom[6] > 0.f ||
            p.gradLeft[6] > 0.f || p.gradRight[6] > 0.f ||
            (p.gradTop[7]    > 0.5f && (p.gradTop[8]    != 0.f || p.gradTop[14]    > 0.f)) ||
            (p.gradBottom[7] > 0.5f && (p.gradBottom[8] != 0.f || p.gradBottom[14] > 0.f)) ||
            (p.gradLeft[7]   > 0.5f && (p.gradLeft[8]   != 0.f || p.gradLeft[14]   > 0.f)) ||
            (p.gradRight[7]  > 0.5f && (p.gradRight[8]  != 0.f || p.gradRight[14]  > 0.f));
        static bool s_gradLogged = false;
        if (!s_gradLogged) {
            s_gradLogged = true;
            __android_log_print(ANDROID_LOG_INFO, "RawV3.ApplyMacro",
                "gradient: any=%d angle=%.1f top[i1=%.3f tl=%.3f] bot[%.3f] "
                "left[%.3f] right[%.3f] opac=%.3f",
                any ? 1 : 0, p.gradAngle, p.gradTop[0], p.gradTop[6],
                p.gradBottom[0], p.gradLeft[0], p.gradRight[0], p.gradTabOpacity);
        }
        if (any) {
            const float rad = p.gradAngle * 3.14159265f / 180.f;
            const float cosA = std::cos(rad), sinA = std::sin(rad);
            const float dx = u - 0.5f, dy = v - 0.5f;
            float rx = dx * cosA - dy * sinA + 0.5f;
            float ry = dx * sinA + dy * cosA + 0.5f;
            rx = clamp01(rx); ry = clamp01(ry);
            float tF1, tF2, bF1, bF2, lF1, lF2, rF1, rF2;
            sideFalloffs(p.gradTop,    ry,        tF1, tF2);
            sideFalloffs(p.gradBottom, 1.f - ry,  bF1, bF2);
            sideFalloffs(p.gradLeft,   rx,        lF1, lF2);
            sideFalloffs(p.gradRight,  1.f - rx,  rF1, rF2);
            // Per-side semantic gating — fold into the falloff so the
            // tint blend and darkness scale together (matches GLSL).
            // Same null-mask short-circuit as vigGate above.
            const float gT  = hasSubjectMask ? segGate(int(p.gradTopApplyTo),    subjSq) : 1.f;
            const float gBo = hasSubjectMask ? segGate(int(p.gradBottomApplyTo), subjSq) : 1.f;
            const float gL  = hasSubjectMask ? segGate(int(p.gradLeftApplyTo),   subjSq) : 1.f;
            const float gRi = hasSubjectMask ? segGate(int(p.gradRightApplyTo),  subjSq) : 1.f;
            tF1 *= gT;  tF2 *= gT;
            bF1 *= gBo; bF2 *= gBo;
            lF1 *= gL;  lF2 *= gL;
            rF1 *= gRi; rF2 *= gRi;
            const float darkness =
                p.gradTop[0]    * tF1 + p.gradBottom[0] * bF1 +
                p.gradLeft[0]   * lF1 + p.gradRight[0]  * rF1 +
                p.gradTop[8]    * tF2 + p.gradBottom[8] * bF2 +
                p.gradLeft[8]   * lF2 + p.gradRight[8]  * rF2;
            if (darkness != 0.f) {
                const float factor = std::exp2(-darkness * 1.4f);
                gR *= factor; gG *= factor; gB *= factor;
            }
            // Tint blends — layer1 then layer2 per side.
            const int tBM = int(p.gradTopBlendMode),  bBM = int(p.gradBottomBlendMode);
            const int lBM = int(p.gradLeftBlendMode),  rBM = int(p.gradRightBlendMode);
            blendGradTint(gR, gG, gB, p.gradTop[3],    p.gradTop[4],    p.gradTop[5],    p.gradTop[6],    tF1, tBM);
            blendGradTint(gR, gG, gB, p.gradBottom[3], p.gradBottom[4], p.gradBottom[5], p.gradBottom[6], bF1, bBM);
            blendGradTint(gR, gG, gB, p.gradLeft[3],   p.gradLeft[4],   p.gradLeft[5],   p.gradLeft[6],   lF1, lBM);
            blendGradTint(gR, gG, gB, p.gradRight[3],  p.gradRight[4],  p.gradRight[5],  p.gradRight[6],  rF1, rBM);
            if (p.gradTop[7]    > 0.5f) blendGradTint(gR, gG, gB, p.gradTop[11],    p.gradTop[12],    p.gradTop[13],    p.gradTop[14],    tF2, tBM);
            if (p.gradBottom[7] > 0.5f) blendGradTint(gR, gG, gB, p.gradBottom[11], p.gradBottom[12], p.gradBottom[13], p.gradBottom[14], bF2, bBM);
            if (p.gradLeft[7]   > 0.5f) blendGradTint(gR, gG, gB, p.gradLeft[11],   p.gradLeft[12],   p.gradLeft[13],   p.gradLeft[14],   lF2, lBM);
            if (p.gradRight[7]  > 0.5f) blendGradTint(gR, gG, gB, p.gradRight[11],  p.gradRight[12],  p.gradRight[13],  p.gradRight[14],  rF2, rBM);
            if (gR < 0.f) gR = 0.f;
            if (gG < 0.f) gG = 0.f;
            if (gB < 0.f) gB = 0.f;
        }
    }
    outR = outR + (gR - outR) * gOp;
    outG = outG + (gG - outG) * gOp;
    outB = outB + (gB - outB) * gOp;

    // ── Sequential compositing result (replaces the old parallel delta-sum
    //     that dragged stacked edits back toward the raw base → flat/bright/
    //     desaturated saves). Now mirrors the GL preview's chained model. ──
    float r = outR;
    float g = outG;
    float b = outB;

    // ── M12.2c.2b — Mask tab layers (Stage C parity with GLSL) ──────────
    //   Each layer reads its own painted alpha + adjustment set, computes a
    //   stacked exposure→WB→sat result from baseRGB, and accumulates the
    //   delta weighted by alpha × layer opacity. Sum is added to the fan-out
    //   exactly like the shader's maskDeltaSum (folded in before clamp/LUT).
    {
        auto clampTo = [](float x, float lo, float hi) {
            return x < lo ? lo : (x > hi ? hi : x);
        };
        // Pre-mask graded luma — the tone the crosshair "sees" (matches GLSL).
        const float gradedLum = clampTo(r * 0.299f + g * 0.587f + b * 0.114f, 0.f, 1.f);
        for (int li = 0; li < ApplyMacroMaskLayers::kCount; ++li) {
            const auto& ml = p.maskLayer[li];
            const bool lumActive = ml.lumSpread > 0.f;
            const bool brushActive = maskLayers &&
                                     maskLayers->layer[li].data != nullptr;
            if (!lumActive && !brushActive) continue;
            // Live luma factor (mirrors GLSL lumMask()).
            float lf = 0.f;
            if (lumActive) {
                const float d = std::abs(gradedLum - ml.lumTarget);
                const float f = ml.lumFeather > 1e-4f ? ml.lumFeather : 1e-4f;
                const float e0 = ml.lumSpread, e1 = ml.lumSpread + f;
                float t = (d - e0) / (e1 - e0);
                t = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
                lf = 1.f - (t * t * (3.f - 2.f * t));   // 1 inside, feathered out
            }
            // Combine luma with the brush/object bitmap — mirrors the GLSL
            // maskLayerAlpha() so preview == export.
            float a;
            if (!lumActive) {
                a = sampleMaskAlpha(maskLayers->layer[li], u, v);
            } else if (!brushActive) {
                a = lf;
            } else {
                const float bf = sampleMaskAlpha(maskLayers->layer[li], u, v);
                switch (ml.lumCombine) {
                    case 1:  a = lf * (1.f - bf);           break; // luma base − bitmap
                    case 2:  a = bf * (1.f - lf);           break; // bitmap base − luma
                    case 3:  a = lf > bf ? lf : bf;         break; // union
                    case 4:  a = lf * bf;                   break; // intersect
                    default: a = lf;                        break; // 0: legacy
                }
            }
            if (a <= 0.f) continue;
            const float ev   = ml.brightness  / 100.f;
            const float cont = ml.contrast    / 100.f;
            const float wb   = clampTo(ml.temperature / 2500.f, -1.f, 1.f);
            const float tn   = clampTo(ml.tint / 200.f,         -1.f, 1.f);
            const float sat  = ml.saturation  / 100.f;
            // Tone regions (mirror global Tone tab); -100..100 → ~-1..1.
            const float hi   = ml.highlights  / 100.f;
            const float shd  = ml.shadows     / 100.f;
            const float wht  = ml.whites      / 100.f;
            const float blk  = ml.blacks      / 100.f;
            if (ev == 0.f && cont == 0.f && wb == 0.f && tn == 0.f && sat == 0.f &&
                hi == 0.f && shd == 0.f && wht == 0.f && blk == 0.f) continue;
            float mR = baseR, mG = baseG, mB = baseB;
            applyExposureContrastP (mR, mG, mB, ev, cont);
            // Tone regions before WB/sat — same order as the GL mask loop.
            if (hi != 0.f || shd != 0.f || wht != 0.f || blk != 0.f) {
                applyToneRegionsP  (mR, mG, mB, hi, shd, wht, blk);
            }
            applyWbTint            (mR, mG, mB, wb, tn);
            applySaturationVibrance(mR, mG, mB, sat, 0.f);
            const float w = a * clampTo(ml.opacity, 0.f, 1.f);
            r += (mR - baseR) * w;
            g += (mG - baseG) * w;
            b += (mB - baseB) * w;
        }
    }

    // Orton bloom — mirrors the GL shader's Karis pyramid composite.
    // When caller supplies a `bloomRgb` (Karis-pyramid output from
    // OffscreenSaveRenderer::computeKarisBloom), use that — that's the
    // editor-equivalent path. Otherwise fall back to the Gaussian
    // `blurRgb` for the legacy code path (visually different, but
    // functional). When neither is present, skip.
    // Halation: R/B channel offset in UV (uMistHalation * 0.005 — prior 0.015
    // was 3× too wide). Matching the GL sample. Needs the full bloomBuf;
    // falls back to centered sample.
    // Soft diffusion: pre-baked into bloomRgb by Stage C / GL
    // (bakeSoftDiffusionIntoBloom / runSoftDiffBloomComposite). Do NOT mix
    // blurRgb here — that shared Gaussian is Bokeh/Ambiance and would kill
    // mistHalation when Bokeh wins the radius (preview bug, 2026-09).
    const float* ortonSrc = (bloomRgb != nullptr) ? bloomRgb : blurRgb;
    if (ortonSrc != nullptr && (p.ortonStrength > 0.f || p.subjectBloom > 0.f)) {
        float strengthHere = p.ortonStrength;
        if (p.bloomExcludeSubject > 0.5f && hasSubjectMask) {
            const float kBloomFeather = 0.02f;
            const float step = kBloomFeather * 0.5f;
            float acc = 0.f;
            for (int ty = -2; ty <= 2; ++ty)
                for (int tx = -2; tx <= 2; ++tx)
                    acc += sampleSubjectMask(mask, u + float(tx) * step, v + float(ty) * step);
            const float subjectFeathered = acc / 25.f;
            const float t = std::max(0.f, std::min(1.f, (subjectFeathered - 0.55f) / 0.43f));
            const float subjectSharp = t * t * (3.f - 2.f * t);
            strengthHere = p.ortonStrength + (p.subjectBloom - p.ortonStrength) * subjectSharp;
        }
        // Sky/terrain attenuation — mirrors shader uBokehAttenuation gate.
        if (atten && atten->data && atten->w > 0 && atten->h > 0) {
            const float skyAmt = sampleSubjectMask(atten, u, v);
            const float blueExcess = b - ((r > g) ? r : g);
            float colorSkyGate = blueExcess * 8.f;
            if (colorSkyGate < 0.f) colorSkyGate = 0.f;
            else if (colorSkyGate > 1.f) colorSkyGate = 1.f;
            const float skyGate = smoothstep01(0.75f, 0.95f, skyAmt) * colorSkyGate;
            strengthHere *= (1.f - skyGate);
        }
        if (strengthHere > 0.f) {
            float bR = ortonSrc[0], bG = ortonSrc[1], bB = ortonSrc[2];
            if (bloomBuf != nullptr && blurW > 0 && blurH > 0 && p.mistHalation > 1e-5f) {
                float halaScale = 1.f;
                if (p.bloomExcludeSubject > 0.5f && hasSubjectMask) {
                    const int dimW = (srcW > 0) ? srcW : blurW;
                    const int dimH = (srcH > 0) ? srcH : blurH;
                    halaScale = halationProtectScaleP(mask, u, v, dimW, dimH);
                }
                const float hOff = p.mistHalation * 0.005f * halaScale;
                float rR, rG, rB, cR, cG, cB, lR, lG, lB;
                sampleBlurBuffer(bloomBuf, blurW, blurH, u + hOff, v, rR, rG, rB);
                sampleBlurBuffer(bloomBuf, blurW, blurH, u,         v, cR, cG, cB);
                sampleBlurBuffer(bloomBuf, blurW, blurH, u - hOff, v, lR, lG, lB);
                bR = rR; bG = cG; bB = lB;
            }
            applyOrtonP(r, g, b, bR, bG, bB, strengthHere);
        }
    }

    // LUT sample coords — headroom-aware map (FEATURES.md) + optional
    // lutHighlightVibrancy overlay. Mirrors shader_sources.cpp:
    //   v = 0 → headroom map only (identity [0,1], compress >1).
    //   v > 0 → boost LUT-result saturation in highlight territory.
    //   v < 0 → blend toward full Reinhard on HDR (creative knob).
    const float hr = r, hg = g, hb = b;
    r = clamp01(r); g = clamp01(g); b = clamp01(b);

    // ── LUT tab (samples from composited graded pixel) ──────────────────
    if (p.lutEnabled && lut && lut->size > 0 && lut->data) {
        float v = p.lutHighlightVibrancy;
        if (v < -1.f) v = -1.f; else if (v > 1.f) v = 1.f;
        // B&W LUT: sample along neutral grey diagonal — matches GLSL fix.
        // Collapses all shadow pixels to the same luma axis so hue-dependent
        // HSL luminance shifts don't cause patchy inter-cell variation.
        float lutSrcR = hr, lutSrcG = hg, lutSrcB = hb;
        if (p.lutBwForce) {
            const float bwY = clamp01(0.2627f * hr + 0.6780f * hg + 0.0593f * hb);
            lutSrcR = bwY; lutSrcG = bwY; lutSrcB = bwY;
        }
        float lr = mapLutSampleCoordP(lutSrcR);
        float lg = mapLutSampleCoordP(lutSrcG);
        float lb = mapLutSampleCoordP(lutSrcB);
        if (v < 0.f) {
            const float k = -v;
            const float rRein = lutSrcR / (1.0f + lutSrcR);
            const float gRein = lutSrcG / (1.0f + lutSrcG);
            const float bRein = lutSrcB / (1.0f + lutSrcB);
            lr = lr + (rRein - lr) * k;
            lg = lg + (gRein - lg) * k;
            lb = lb + (bRein - lb) * k;
        }
        // Gamut transform: workspace → LUT authored space before sampling,
        // then authored → workspace after. Matches GLSL toSrgb/fromSrgb path.
        {
            const int authored = lutAuthoredToLibraw(p.lutAuthoredSpace);
            lutToSrgb(lr, lg, lb, p.workspaceSpace);
            lutFromSrgb(lr, lg, lb, authored);
            sampleLut3d(lr, lg, lb, *lut);
            lutToSrgb(lr, lg, lb, authored);
            lutFromSrgb(lr, lg, lb, p.workspaceSpace);
        }
        if (v > 0.f) {
            const float maxHdr = std::max(std::max(hr, hg), hb);
            const float hiAmt  = std::min(1.0f, std::max(0.0f, maxHdr - 1.0f)) * v;
            if (hiAmt > 0.0f) {
                // Match the GL editor formula exactly:
                //   mix(lutColor, lutLuma + (lutColor - lutLuma) * 1.5, hiAmt)
                // At hiAmt < 1 this is gentler than the previous
                //   luma + (lutColor - luma) * (1 + 0.5*hiAmt)
                // shape (0.75x vs 1.25x boost at hiAmt=0.5). User preferred
                // the editor look, so save is brought into line.
                const float luma = 0.2627f * lr + 0.6780f * lg + 0.0593f * lb;
                const float boostedR = luma + (lr - luma) * 1.5f;
                const float boostedG = luma + (lg - luma) * 1.5f;
                const float boostedB = luma + (lb - luma) * 1.5f;
                lr = lr + (boostedR - lr) * hiAmt;
                lg = lg + (boostedG - lg) * hiAmt;
                lb = lb + (boostedB - lb) * hiAmt;
            }
        }
        const float t = clampOpacity(p.lutIntensity);
        if (p.lutBwForce) {
            // Mirror GLSL uLutBwForce: mix from achromatic source luma.
            const float srcY = 0.2627f * r + 0.6780f * g + 0.0593f * b;
            r = srcY + (lr - srcY) * t;
            g = srcY + (lg - srcY) * t;
            b = srcY + (lb - srcY) * t;
        } else {
            r = r + (lr - r) * t;
            g = g + (lg - g) * t;
            b = b + (lb - b) * t;
        }
        if (v > 0.f) {
            const float er = hr > 1.0f ? (hr - 1.0f) : 0.0f;
            const float eg = hg > 1.0f ? (hg - 1.0f) : 0.0f;
            const float eb = hb > 1.0f ? (hb - 1.0f) : 0.0f;
            r += er * t * 0.3f * v;
            g += eg * t * 0.3f * v;
            b += eb * t * 0.3f * v;
        }
    }

    // Film response — mirror of applyFilmResponse() in shader_sources.cpp,
    // at the same point in the chain (after the LUT, before the WB trims).
    applyFilmResponseP(r, g, b, p.filmRecovery, p.filmFillLight,
                       p.filmMonochrome, p.filmGrayMix);
    applyOklabHlChromaP(r, g, b, p.oklabHlChroma);

    // Tonal-zone WB trims — mirror of the GLSL block in gles_renderer.cpp.
    {
        const float luma = 0.2627f * r + 0.6780f * g + 0.0593f * b;
        const float clampL = luma < 0.f ? 0.f : (luma > 1.f ? 1.f : luma);
        auto smoothstepF = [](float e0, float e1, float x) -> float {
            const float t = (x - e0) / (e1 - e0);
            const float tt = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
            return tt * tt * (3.0f - 2.0f * tt);
        };
        const float hiW = smoothstepF(0.55f, 0.95f, clampL);
        const float shW = 1.0f - smoothstepF(0.05f, 0.45f, clampL);
        const float K = 0.15f;
        r += p.highlightTemperature * hiW * K;
        b -= p.highlightTemperature * hiW * K;
        g -= p.highlightTint        * hiW * K;
        r += p.highlightTint        * hiW * K * 0.5f;
        b += p.highlightTint        * hiW * K * 0.5f;
        r += p.shadowTemperature    * shW * K;
        b -= p.shadowTemperature    * shW * K;
        g -= p.shadowTint           * shW * K;
        r += p.shadowTint           * shW * K * 0.5f;
        b += p.shadowTint           * shW * K * 0.5f;
    }

    // Tone curve — applied here to match GL pipeline: after WB-trims, BEFORE
    // gamutCompress + knee. Camera-profile shoulder runs at the right moment
    // so it can pull down near-blown highlights before the Reinhard knee acts.
    // Mirrors gles_renderer.cpp lines 2091-2107 (per-channel LUT texture tap).
    if (p.toneCurveLut) {
        auto tcCh = [&](int ch, float v) -> float {
            float x = (v < 0.f ? 0.f : (v > 1.f ? 1.f : v)) * 255.f;
            int   i0 = int(x);
            int   i1 = i0 < 255 ? i0 + 1 : 255;
            float t  = x - float(i0);
            float a  = float(p.toneCurveLut[i0 * 3 + ch]) * (1.f / 255.f);
            float bv = float(p.toneCurveLut[i1 * 3 + ch]) * (1.f / 255.f);
            return a + (bv - a) * t;
        };
        if (p.toneCurveLumaMode) {
            // Mirrors shader_sources.cpp `uToneCurveLumaMode == 1`: sample the
            // master curve (LUT channel 0, the shader's `.r` tap) with luma,
            // then shift every channel by the same delta and clamp at zero.
            const float L  = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            const float dL = tcCh(0, L) - L;
            r += dL; g += dL; b += dL;
            if (r < 0.f) r = 0.f;
            if (g < 0.f) g = 0.f;
            if (b < 0.f) b = 0.f;
        } else {
            r = tcCh(0, r); g = tcCh(1, g); b = tcCh(2, b);
        }
    }

    // Phase-1 backport: pushPull → detailGrain → haxGrain → gamutCompress.
    // Pipeline order: after curves/LUT/WB-trims, before final write.
    // Mirrors gles_renderer.cpp pipeline order (pushPull after curves,
    // grain after pushPull, gamutCompress last of all).
    applyPushPullP(r, g, b, p.pushPull);
    applyDetailGrainP(r, g, b, u, v, p.detailGrainRoughness);
    // Haxademic grain extension — second grain layer (Req 15).
    // Backwards-compatible: haxGrainCrossfade defaults to 0.0, so
    // applyHaxGrainP returns immediately without modifying r/g/b.
    // grainScale=1.0 and chromaAmp=0.0 are irrelevant when crossfade==0.
    applyHaxGrainP(r, g, b, u, v, p.haxGrainCrossfade, p.haxGrainScale,
                   p.haxGrainLumaAmp, p.haxGrainChromaAmp);
    applyGamutCompressP(r, g, b, p.gamutCompress);
    // Clamp [0,1] before knee — mirrors GL line 2144 (c = clamp(c, 0.0, 1.0)).
    // Ensures the Reinhard knee operates on the same [0,1] input range as GL.
    r = clamp01(r); g = clamp01(g); b = clamp01(b);

    // Pre-encode highlight soft-knee — mirrors gles_renderer.cpp. Touches
    // only top ~10% of the signal, compressing values above 0.90 so the FX
    // tab (which follows) sees the same range the GL preview does.
    applyHighlightKneeP(r, g, b);

    // ── PREQ-Port: Effects tab ────────────────────────────────────────────
    //   Runs after gamut compress + highlight knee (GL pipeline order).
    //   CPU path uses the pre-computed Gaussian blur / Karis bloom samples
    //   passed by Stage C. Directional/radial/zoom styles sample the full
    //   blur buffer; Gaussian style uses a single sample.
    if (p.fxBlurStyle > 0.5f && blurBuf != nullptr && blurW > 0 && blurH > 0) {
        applyFxBlurP(r, g, b, blurBuf, blurW, blurH, p, mask, u, v);
    }
    applyMistP(r, g, b, p.fxMist, p.fxMistWarmth);
    applyVintageP(r, g, b, u, v,
                  p.fxVintageStrength, p.fxVintageFade, p.fxVintageVig);
    // GLSL gates on uFxVintageVig > 0.0 (not 0.5) — see shader_sources.cpp.
    if (p.fxVintageVig > 0.f) {
        applyHaxVignetteP(r, g, b, u, v, p.fxVintageVig);
    }
    if (bloomRgb != nullptr && p.fxGlowStrength > 0.f) {
        float bR = bloomRgb[0], bG = bloomRgb[1], bB = bloomRgb[2];
        if (bloomBuf != nullptr && blurW > 0 && blurH > 0 && p.mistHalation > 1e-5f) {
            float halaScale = 1.f;
            if (p.bloomExcludeSubject > 0.5f && hasSubjectMask) {
                const int dimW = (srcW > 0) ? srcW : blurW;
                const int dimH = (srcH > 0) ? srcH : blurH;
                halaScale = halationProtectScaleP(mask, u, v, dimW, dimH);
            }
            const float hOff = p.mistHalation * 0.005f * halaScale;
            float rR, rG, rB, cR, cG, cB, lR, lG, lB;
            sampleBlurBuffer(bloomBuf, blurW, blurH, u + hOff, v, rR, rG, rB);
            sampleBlurBuffer(bloomBuf, blurW, blurH, u,         v, cR, cG, cB);
            sampleBlurBuffer(bloomBuf, blurW, blurH, u - hOff, v, lR, lG, lB);
            bR = rR; bG = cG; bB = lB;
        }
        // Soft diffusion pre-baked into bloomRgb (see Orton block).
        applyGlowWithSpreadP(r, g, b, bR, bG, bB,
                             p.fxGlowStrength, p.fxGlowSpread, p.fxGlowWarmth);
    }
    applyDustP(r, g, b, u, v, p.fxDust, p.fxDustSize);
    // Lens flare (procedural additive, above dust) — mirrors shader main().
    if (p.lensFlareBrightness > 0.f) {
        applyLensFlareP(r, g, b, u, v, p.lensFlareX, p.lensFlareY,
                        p.lensFlareBrightness, p.lensFlareSize, p.lensFlareSpread,
                        p.lensFlareWarmth);
    }

    io[0] = r; io[1] = g; io[2] = b;
}

// UV-aware overload, no mask — used by Stage C when segmentation absent.
void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut) {
    applyMacroPixelImpl(io, u, v, p, lut, nullptr, nullptr);
}

// Back-compat overload — defaults UV to image centre, no mask.
void applyMacroPixel(float* io, const ApplyMacroParams& p, const ApplyMacroLut* lut) {
    applyMacroPixelImpl(io, 0.5f, 0.5f, p, lut, nullptr, nullptr);
}

// M12.2c.1 — UV-aware overload WITH subject mask.
void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask) {
    applyMacroPixelImpl(io, u, v, p, lut, mask, nullptr);
}

// M12.2c.2b — full overload WITH subject mask AND brush-mask layers.
void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers) {
    applyMacroPixelImpl(io, u, v, p, lut, mask, maskLayers, /*blurRgb*/nullptr);
}

// Ambiance/Orton-aware overload. [blurRgb] is the corresponding 3-float
// pixel from a Gaussian-blurred copy of the input — same blur the shader's
// uBlurTex provides. Caller is responsible for computing it before the
// per-pixel loop and passing the matching pixel each tap. Pass nullptr to
// skip ambiance + Orton (matches the legacy export path).
void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb) {
    applyMacroPixelImpl(io, u, v, p, lut, mask, maskLayers, blurRgb);
}

// Ambiance/Orton-aware overload WITH separate Karis bloom source.
//   [blurRgb]  : Gaussian-blurred low-pass (drives ambiance — matches the
//                editor's uBlurTex). Pass nullptr to skip ambiance.
//   [bloomRgb] : Karis-pyramid bloom (drives Orton — matches the editor's
//                uBloomTex). Pass nullptr to fall back to [blurRgb] for
//                Orton (legacy behaviour).
// Used by Stage C to drive editor↔save bloom parity through
// OffscreenSaveRenderer::computeKarisBloom.
void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb) {
    applyMacroPixelImpl(io, u, v, p, lut, mask, maskLayers, blurRgb, bloomRgb);
}

// Full editor-equivalent overload that exposes the complete Gaussian blur
// buffer for multi-tap FX blur effects.
void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb,
                     const float* blurBuf,
                     int blurW, int blurH) {
    applyMacroPixelImpl(io, u, v, p, lut, mask, maskLayers, blurRgb, bloomRgb,
                        blurBuf, blurW, blurH, /*bloomBuf*/nullptr);
}

void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb,
                     const float* blurBuf,
                     int blurW, int blurH,
                     const float* bloomBuf) {
    applyMacroPixelImpl(io, u, v, p, lut, mask, maskLayers, blurRgb, bloomRgb,
                        blurBuf, blurW, blurH, bloomBuf);
}

void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb,
                     const float* blurBuf,
                     int blurW, int blurH,
                     const float* bloomBuf,
                     const ApplyMacroSubjectMask* atten) {
    applyMacroPixelImpl(io, u, v, p, lut, mask, maskLayers, blurRgb, bloomRgb,
                        blurBuf, blurW, blurH, bloomBuf, atten,
                        /*srcBuf*/blurBuf, blurW, blurH);
}

void applyMacroPixel(float* io, float u, float v,
                     const ApplyMacroParams& p, const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb,
                     const float* blurBuf,
                     int blurW, int blurH,
                     const float* bloomBuf,
                     const ApplyMacroSubjectMask* atten,
                     const float* srcBuf,
                     int srcW, int srcH) {
    applyMacroPixelImpl(io, u, v, p, lut, mask, maskLayers, blurRgb, bloomRgb,
                        blurBuf, blurW, blurH, bloomBuf, atten,
                        srcBuf, srcW, srcH);
}

}  // namespace raw_v3
