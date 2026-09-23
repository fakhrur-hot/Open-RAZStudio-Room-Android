/*
 * StudioRoom — RAW Pipeline v3 — Stage C export (M8).
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

#include "stage_c_export.h"
#include "tiff_mmap_io.h"
#include "apply_macro.h"
#include "selective_bokeh_stage_c.h"
#include "raw_v3_clahe.h"
#include "jpeg_dual_recon.h"
#include "raw_v3_nr.h"
#include "raw_v3_detail.h"
#include "soft_diffusion.h"
// GPU Karis bloom for Orton parity. Pulls in EGL/GLES, which desktop batch
// builds do not have — the desktop target defines RAZ_NO_EGL to skip it.
//
// NOTE the polarity: this is opt-OUT, not opt-in. An opt-in macro (USE_EGL)
// would have to be added to the Android build too, and forgetting it would
// silently drop GPU bloom on the phone. Defaulting to "EGL present" means only
// the new desktop target can lose it, and it does so deliberately.
#ifndef RAZ_NO_EGL
#include "offscreen_save_renderer.h"
#endif

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "RawV3.StageC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

namespace {

// Read the Detail-tab spatial params from the ShaderParams float blob
// (slots [149..156]). Safe on shorter (legacy) blobs.
inline DetailParams readDetailParams(const float* p, int count) {
    DetailParams d{};
    if (!p) return d;
    if (count > 149) d.sharpness           = p[149];
    if (count > 150) d.smartSharpness      = p[150];
    if (count > 151) d.clarity             = p[151];
    if (count > 152) d.texture             = p[152];
    if (count > 153) d.filmGrain           = p[153];
    if (count > 154) d.filmGrainSize       = p[154];
    if (count > 155) d.filmGrainUniformity = p[155];
    if (count > 156) d.filmGrainWashOut    = p[156];
    if (count > 178) d.smoothBackground    = p[178];
    return d;
}

// Tiny parallel-for over rows. Mirrors the helper in raw_v3_nr.cpp so the
// blur scales with available cores without pulling a heavyweight scheduler.
template <typename Fn>
inline void parallelRows(int count, Fn&& body) {
    if (count <= 0) return;
    unsigned hw = std::thread::hardware_concurrency();
    int workers = int(hw == 0 ? 1 : hw);
    workers = std::min(workers, std::max(1, count / 32));
    if (workers <= 1) { body(0, count); return; }
    std::vector<std::thread> pool;
    pool.reserve(workers - 1);
    const int chunk = (count + workers - 1) / workers;
    for (int wk = 0; wk < workers; ++wk) {
        const int begin = wk * chunk;
        const int end   = std::min(begin + chunk, count);
        if (begin >= end) break;
        if (wk == workers - 1) body(begin, end);
        else pool.emplace_back([&body, begin, end] { body(begin, end); });
    }
    for (auto& t : pool) t.join();
}

// Separable Gaussian blur over an interleaved RGB float buffer. Mirrors the
// shader's `uBlurTex` pass so the Ambiance + Orton kernels in apply_macro.cpp
// can sample a matching reference image. One blur, two consumers (matches
// the shader where both effects pull from the same texture).
//
// radius is in pixels at the export resolution. The shader uses a 4-8px
// radius at preview (2048-long-side), scaling that linearly to the export's
// long side keeps the perceptual feel matched across resolutions.
//
// On ARM the inner loop auto-vectorises under -O3 (Clang emits NEON FMA);
// hand-rolling NEON intrinsics here would buy maybe 20% more on top — not
// worth the complexity vs. plain C++ that compiles cleanly on any target.
static void gaussianBlurSeparable(const float* src, float* dst,
                                  int w, int h, int radius) {
    if (w <= 0 || h <= 0 || radius <= 0) {
        std::memcpy(dst, src, size_t(w) * h * 3 * sizeof(float));
        return;
    }
    // Precompute the Gaussian kernel. σ = radius/2 gives the same falloff
    // shape the shader's separable pass uses.
    const float sigma = radius * 0.5f;
    const float invTwoSigSq = 1.0f / (2.0f * sigma * sigma);
    const int kSize = radius * 2 + 1;
    std::vector<float> kernel(kSize);
    float sum = 0.0f;
    for (int i = -radius; i <= radius; ++i) {
        const float v = std::exp(-(i * i) * invTwoSigSq);
        kernel[i + radius] = v;
        sum += v;
    }
    const float invSum = 1.0f / sum;
    for (auto& k : kernel) k *= invSum;

    // Horizontal pass into a scratch buffer.
    std::vector<float> tmp(size_t(w) * h * 3);
    parallelRows(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const float* srcRow = src + size_t(y) * w * 3;
            float* tmpRow = tmp.data() + size_t(y) * w * 3;
            for (int x = 0; x < w; ++x) {
                float r = 0.f, g = 0.f, b = 0.f;
                for (int k = -radius; k <= radius; ++k) {
                    int xx = x + k;
                    if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
                    const float* p = srcRow + xx * 3;
                    const float wgt = kernel[k + radius];
                    r += p[0] * wgt;
                    g += p[1] * wgt;
                    b += p[2] * wgt;
                }
                tmpRow[x * 3 + 0] = r;
                tmpRow[x * 3 + 1] = g;
                tmpRow[x * 3 + 2] = b;
            }
        }
    });
    // Vertical pass — read from tmp, write to dst.
    parallelRows(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            float* dstRow = dst + size_t(y) * w * 3;
            for (int x = 0; x < w; ++x) {
                float r = 0.f, g = 0.f, b = 0.f;
                for (int k = -radius; k <= radius; ++k) {
                    int yy = y + k;
                    if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
                    const float* p = tmp.data() + (size_t(yy) * w + x) * 3;
                    const float wgt = kernel[k + radius];
                    r += p[0] * wgt;
                    g += p[1] * wgt;
                    b += p[2] * wgt;
                }
                dstRow[x * 3 + 0] = r;
                dstRow[x * 3 + 1] = g;
                dstRow[x * 3 + 2] = b;
            }
        }
    });
}

// 8×8 ordered Bayer dither matrix (values 0..63, normalised to 0..1 below).
// Same table the GL preview shader uses (gles_renderer.cpp ~L295); having
// both paths share the pattern means previewed banding and exported banding
// look identical when off, and identical when on — no surprise at export.
// Used by quantize8Dithered() to add a sub-LSB ordered noise pattern before
// truncation to 8-bit, which breaks up gradient banding on smooth skies /
// out-of-focus backgrounds.
static const uint8_t kBayer8[64] = {
     0, 32,  8, 40,  2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44,  4, 36, 14, 46,  6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
     3, 35, 11, 43,  1, 33,  9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47,  7, 39, 13, 45,  5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
};

// Map one [0,1] channel value through a 256-entry curve LUT (one of R/G/B
// interleaved in [lut], channel offset [c]) with linear interpolation between
// entries. Mirrors the GL shader's linear-filtered LUT sample.
inline float applyToneCurveChannel(const uint8_t* lut, int c, float v) {
    float x = (v < 0.f ? 0.f : (v > 1.f ? 1.f : v)) * 255.f;
    int i0 = int(x);
    int i1 = i0 < 255 ? i0 + 1 : 255;
    float t = x - float(i0);
    float a = float(lut[i0 * 3 + c]) / 255.f;
    float b = float(lut[i1 * 3 + c]) / 255.f;
    return a + (b - a) * t;
}
inline void applyToneCurveRGB(const uint8_t* lut, float* rgb) {
    rgb[0] = applyToneCurveChannel(lut, 0, rgb[0]);
    rgb[1] = applyToneCurveChannel(lut, 1, rgb[1]);
    rgb[2] = applyToneCurveChannel(lut, 2, rgb[2]);
}

// Cinematic film grain (Matt DesLauriers / Martins Upitis) — IDENTICAL 3D-noise
// math + fixed reference grid + seed as the GL uber-shader, so the exported
// file matches the live preview. [grain],[size],[unif],[wash] are [0..1].
//
// kGrainSeed MUST equal the renderer's kGrainSeed (gles_renderer.cpp).
static constexpr float kGrainSeed = 37.0f;

inline float grainFract(float x) { return x - std::floor(x); }
inline float grainHash3(float px, float py, float pz) {
    px = grainFract(px * 0.3183099f + 0.1f);
    py = grainFract(py * 0.3183099f + 0.1f);
    pz = grainFract(pz * 0.3183099f + 0.1f);
    px *= 17.f; py *= 17.f; pz *= 17.f;
    return grainFract(px * py * pz * (px + py + pz));
}
inline float grainNoise3D(float x, float y, float z) {
    float ix = std::floor(x), iy = std::floor(y), iz = std::floor(z);
    float fx = x - ix, fy = y - iy, fz = z - iz;
    fx = fx * fx * (3.f - 2.f * fx);
    fy = fy * fy * (3.f - 2.f * fy);
    fz = fz * fz * (3.f - 2.f * fz);
    auto H = [&](float dx, float dy, float dz) { return grainHash3(ix + dx, iy + dy, iz + dz); };
    auto lerp = [](float a, float b, float t) { return a + (b - a) * t; };
    float x00 = lerp(H(0,0,0), H(1,0,0), fx);
    float x10 = lerp(H(0,1,0), H(1,1,0), fx);
    float x01 = lerp(H(0,0,1), H(1,0,1), fx);
    float x11 = lerp(H(0,1,1), H(1,1,1), fx);
    float y0 = lerp(x00, x10, fy);
    float y1 = lerp(x01, x11, fy);
    return lerp(y0, y1, fz);
}
struct GrainParams {
    float grain = 0.f, size = 0.5f, unif = 0.f, wash = 0.f;
    bool any() const { return grain > 0.f || wash > 0.f; }
};
inline void applyFilmGrain(float* rgb, float u, float v, float aspect,
                           const GrainParams& gp) {
    if (gp.grain > 0.f) {
        const float REF = 2048.f;
        float multiplier = 1.f + gp.size * 2.f;
        float mx = u * (REF * aspect) / multiplier;
        float my = v * REF / multiplier;
        float offset = grainNoise3D(mx / 2.5f, my / 2.5f, kGrainSeed);
        float n1 = grainNoise3D(mx, my, offset * 10.f);
        float bn = n1 * 2.f - 1.f;
        float maxGrain = gp.grain * (40.f / 255.f);
        auto cl = [](float x){ return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); };
        float lum = cl(rgb[0] * 0.299f + rgb[1] * 0.587f + rgb[2] * 0.114f);
        float midW = cl(1.f - (lum - 0.5f) * (lum - 0.5f) * 4.f);
        float uni = cl(gp.unif);
        float lumW = midW + (1.f - midW) * uni;       // mix(midW, 1, unif)
        float g = bn * maxGrain * lumW;
        rgb[0] += g; rgb[1] += g; rgb[2] += g;
    }
    if (gp.wash > 0.f) {
        float lift   = gp.wash * (60.f / 255.f);
        float wscale = 1.f - gp.wash * 0.3f;
        rgb[0] = lift + rgb[0] * wscale;
        rgb[1] = lift + rgb[1] * wscale;
        rgb[2] = lift + rgb[2] * wscale;
    }
    auto cl = [](float x){ return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); };
    rgb[0] = cl(rgb[0]); rgb[1] = cl(rgb[1]); rgb[2] = cl(rgb[2]);
}
inline GrainParams readGrain(const float* p, int count) {
    GrainParams g{};
    if (count > 153) g.grain = p[153];
    if (count > 154) g.size  = p[154];
    if (count > 155) g.unif  = p[155];
    if (count > 156) g.wash  = p[156];
    return g;
}

// ── Cubic Chromatic Aberration — CPU parity with GLSL computeUV (Req 15) ───
// Mirrors gles_renderer.cpp computeUV() exactly:
//   vec2 t = uv - 0.5; float r2 = dot(t,t);
//   float f = (kcube==0) ? (1+r2*k) : (1+r2*(k+kcube*sqrt(r2)));
//   return clamp(f*t+0.5, 0, 1);
static inline void computeUV_cpu(float ux, float uy, float k, float kcube,
                                  float& outX, float& outY) {
    float tx = ux - 0.5f;
    float ty = uy - 0.5f;
    float r2 = tx * tx + ty * ty;
    float f = (kcube == 0.0f) ? (1.0f + r2 * k)
                               : (1.0f + r2 * (k + kcube * std::sqrt(r2)));
    outX = std::clamp(f * tx + 0.5f, 0.0f, 1.0f);
    outY = std::clamp(f * ty + 0.5f, 0.0f, 1.0f);
}

// Bilinear sample of a row-major RGB float buffer at normalised (u,v).
// Mirrors GPU texture() filtering for sub-pixel accuracy.
static inline void bilinearSampleRGB(const float* buf, int w, int h,
                                     float u, float v,
                                     float& outR, float& outG, float& outB) {
    float fx = u * float(w) - 0.5f;
    float fy = v * float(h) - 0.5f;
    int x0 = int(std::floor(fx));
    int y0 = int(std::floor(fy));
    float tx = fx - float(x0);
    float ty = fy - float(y0);
    int x0c = x0 < 0 ? 0 : (x0 >= w ? w - 1 : x0);
    int x1c = (x0 + 1) < 0 ? 0 : ((x0 + 1) >= w ? w - 1 : (x0 + 1));
    int y0c = y0 < 0 ? 0 : (y0 >= h ? h - 1 : y0);
    int y1c = (y0 + 1) < 0 ? 0 : ((y0 + 1) >= h ? h - 1 : (y0 + 1));
    const float* p00 = buf + (size_t(y0c) * w + x0c) * 3;
    const float* p10 = buf + (size_t(y0c) * w + x1c) * 3;
    const float* p01 = buf + (size_t(y1c) * w + x0c) * 3;
    const float* p11 = buf + (size_t(y1c) * w + x1c) * 3;
    for (int c = 0; c < 3; ++c) {
        float a0 = p00[c] + (p10[c] - p00[c]) * tx;
        float a1 = p01[c] + (p11[c] - p01[c]) * tx;
        float val = a0 + (a1 - a0) * ty;
        if (c == 0) outR = val;
        else if (c == 1) outG = val;
        else outB = val;
    }
}

// Apply cubic chromatic aberration to a whole-image RGB float buffer in-place.
// Coefficient derivation identical to GLSL: k = amp*0.9*sep, kcube = 0.5*amp,
// offset = amp*0.05*sep.  Per-pixel: R sampled at (k+offset, kcube), G at
// (k, kcube), B at (k-offset, kcube).
static void applyCubicCA_cpu(float* rgb, int w, int h,
                             float strength, float separation) {
    if (strength == 0.0f || w <= 0 || h <= 0) return;
    const float k      = strength * 0.9f * separation;
    const float kcube  = 0.5f * strength;
    const float offset = strength * 0.05f * separation;
    // Work into a copy (we read source positions that differ per channel).
    std::vector<float> src(size_t(w) * h * 3);
    std::memcpy(src.data(), rgb, size_t(w) * h * 3 * sizeof(float));
    parallelRows(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const float uy = (float(y) + 0.5f) / float(h);
            for (int x = 0; x < w; ++x) {
                const float ux = (float(x) + 0.5f) / float(w);
                float ruv_x, ruv_y, guv_x, guv_y, buv_x, buv_y;
                computeUV_cpu(ux, uy, k + offset, kcube, ruv_x, ruv_y);
                computeUV_cpu(ux, uy, k,          kcube, guv_x, guv_y);
                computeUV_cpu(ux, uy, k - offset, kcube, buv_x, buv_y);
                float rR, rG, rB, gR, gG, gB, bR, bG, bB;
                bilinearSampleRGB(src.data(), w, h, ruv_x, ruv_y, rR, rG, rB);
                bilinearSampleRGB(src.data(), w, h, guv_x, guv_y, gR, gG, gB);
                bilinearSampleRGB(src.data(), w, h, buv_x, buv_y, bR, bG, bB);
                size_t i = (size_t(y) * w + x) * 3;
                rgb[i + 0] = rR;  // R from red-channel UV sample
                rgb[i + 1] = gG;  // G from green-channel UV sample
                rgb[i + 2] = bB;  // B from blue-channel UV sample
            }
        }
    });
    LOGI("applyCubicCA_cpu: strength=%.4f sep=%.4f k=%.4f kcube=%.4f offset=%.4f",
         strength, separation, k, kcube, offset);
}

// ── Bilateral Denoise — CPU parity with GLSL smartDeNoise (Req 15) ─────────
// Identical double-loop structure, circular kernel, same constants.
// sigma/threshold scaling from slots: sigma = slot147/100*5.0,
// threshold = slot148/100*0.1.
static constexpr double INV_SQRT_OF_2PI = 0.39894228040143267793994605993439;
static constexpr double INV_PI          = 0.31830988618379067153776752674503;

// smartDeNoise_cpu: Bilateral filter on an RGB float buffer, in-place.
// Mirrors the GLSL kBilateralFrag exactly — same circular kernel, same
// weight formula, same range weighting.
static void smartDeNoise_cpu(float* rgb, int w, int h,
                             float sigma, float threshold) {
    if ((sigma <= 0.0f && threshold <= 0.0f) || w <= 0 || h <= 0) return;
    const float sig = std::max(sigma, 0.001f);
    const float thr = std::max(threshold, 0.001f);
    const float kSigma = 2.0f;  // matches GLSL uKSigma = 2.0
    const float radius = std::round(kSigma * sig);
    const float radQ   = radius * radius;
    const float invSigmaQx2      = 0.5f / (sig * sig);
    const float invSigmaQx2PI    = float(INV_PI) * invSigmaQx2;
    const float invThresholdSqx2    = 0.5f / (thr * thr);
    const float invThresholdSqrt2PI = float(INV_SQRT_OF_2PI) / thr;
    // Work from a copy (reads overlap with writes for neighbouring pixels).
    std::vector<float> src(size_t(w) * h * 3);
    std::memcpy(src.data(), rgb, size_t(w) * h * 3 * sizeof(float));
    parallelRows(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            for (int x = 0; x < w; ++x) {
                const size_t ci = (size_t(y) * w + x) * 3;
                const float cR = src[ci], cG = src[ci + 1], cB = src[ci + 2];
                float zBuff = 0.0f;
                float aR = 0.0f, aG = 0.0f, aB = 0.0f;
                for (float dx = -radius; dx <= radius; dx += 1.0f) {
                    float pt = std::sqrt(radQ - dx * dx);
                    for (float dy = -pt; dy <= pt; dy += 1.0f) {
                        float dotD = dx * dx + dy * dy;
                        float blurFactor = std::exp(-dotD * invSigmaQx2) * invSigmaQx2PI;
                        int wx = x + int(dx);
                        int wy = y + int(dy);
                        if (wx < 0) wx = 0; else if (wx >= w) wx = w - 1;
                        if (wy < 0) wy = 0; else if (wy >= h) wy = h - 1;
                        const size_t wi = (size_t(wy) * w + wx) * 3;
                        float wR = src[wi], wG = src[wi + 1], wB = src[wi + 2];
                        float dR = wR - cR, dG = wG - cG, dB = wB - cB;
                        float dotC = dR * dR + dG * dG + dB * dB;
                        float deltaFactor = std::exp(-dotC * invThresholdSqx2)
                                          * invThresholdSqrt2PI * blurFactor;
                        zBuff += deltaFactor;
                        aR += deltaFactor * wR;
                        aG += deltaFactor * wG;
                        aB += deltaFactor * wB;
                    }
                }
                float inv = (zBuff > 0.0f) ? (1.0f / zBuff) : 1.0f;
                rgb[ci + 0] = aR * inv;
                rgb[ci + 1] = aG * inv;
                rgb[ci + 2] = aB * inv;
            }
        }
    });
    LOGI("smartDeNoise_cpu: sigma=%.4f threshold=%.6f radius=%.0f",
         sig, thr, radius);
}

// Bilinear sample of a row-major [0,1] alpha plane at normalised (u,v).
inline float sampleAlphaPlane(const float* data, int mw, int mh, float u, float v) {
    if (!data || mw <= 0 || mh <= 0) return 0.f;
    float fx = u * float(mw) - 0.5f;
    float fy = v * float(mh) - 0.5f;
    int x0 = int(std::floor(fx)), y0 = int(std::floor(fy));
    float tx = fx - float(x0), ty = fy - float(y0);
    auto cl = [](int a, int lo, int hi) { return a < lo ? lo : (a > hi ? hi : a); };
    int x0c = cl(x0, 0, mw - 1), x1c = cl(x0 + 1, 0, mw - 1);
    int y0c = cl(y0, 0, mh - 1), y1c = cl(y0 + 1, 0, mh - 1);
    float a00 = data[size_t(y0c) * mw + x0c];
    float a10 = data[size_t(y0c) * mw + x1c];
    float a01 = data[size_t(y1c) * mw + x0c];
    float a11 = data[size_t(y1c) * mw + x1c];
    float a0 = a00 + (a10 - a00) * tx;
    float a1 = a01 + (a11 - a01) * tx;
    return a0 + (a1 - a0) * ty;
}

// Masked local-contrast clarity for Stage C export — parity with the GL
// uber-shader's per-mask-layer clarity (radius-blur unsharp, midtone-gated,
// 2× strength), gated by each layer's brush alpha × opacity. Runs on the
// whole RGB float [preBuf] in place. clarity is the layer value in [-100..100].
void applyMaskedClarity(float* rgb, int w, int h,
                        const ApplyMacroParams& params,
                        const ApplyMacroMaskLayers& maskLayers) {
    // Precompute a radius-5 box blur of luma-weighted RGB (per channel) so the
    // unsharp matches the Detail-tab clarity radius. Separable box blur.
    const size_t n = size_t(w) * h;
    std::vector<float> tmp(n * 3), blur(n * 3);
    const int R = 5;
    const float inv = 1.f / float(2 * R + 1);
    // Horizontal.
    for (int y = 0; y < h; ++y) {
        const float* src = rgb + size_t(y) * w * 3;
        float* dst = tmp.data() + size_t(y) * w * 3;
        for (int c = 0; c < 3; ++c) {
            float acc = 0.f;
            for (int x = -R; x <= R; ++x) {
                int xc = x < 0 ? 0 : (x >= w ? w - 1 : x);
                acc += src[xc * 3 + c];
            }
            for (int x = 0; x < w; ++x) {
                dst[x * 3 + c] = acc * inv;
                int xo = x - R; xo = xo < 0 ? 0 : (xo >= w ? w - 1 : xo);
                int xi = x + R + 1; xi = xi < 0 ? 0 : (xi >= w ? w - 1 : xi);
                acc += src[xi * 3 + c] - src[xo * 3 + c];
            }
        }
    }
    // Vertical.
    for (int x = 0; x < w; ++x) {
        for (int c = 0; c < 3; ++c) {
            float acc = 0.f;
            for (int y = -R; y <= R; ++y) {
                int yc = y < 0 ? 0 : (y >= h ? h - 1 : y);
                acc += tmp[(size_t(yc) * w + x) * 3 + c];
            }
            for (int y = 0; y < h; ++y) {
                blur[(size_t(y) * w + x) * 3 + c] = acc * inv;
                int yo = y - R; yo = yo < 0 ? 0 : (yo >= h ? h - 1 : yo);
                int yi = y + R + 1; yi = yi < 0 ? 0 : (yi >= h ? h - 1 : yi);
                acc += tmp[(size_t(yi) * w + x) * 3 + c] - tmp[(size_t(yo) * w + x) * 3 + c];
            }
        }
    }
    auto cl01 = [](float v) { return v < 0.f ? 0.f : (v > 1.f ? 1.f : v); };
    for (int y = 0; y < h; ++y) {
        float v = (float(y) + 0.5f) / float(h);
        for (int x = 0; x < w; ++x) {
            float u = (float(x) + 0.5f) / float(w);
            const size_t i = (size_t(y) * w + x) * 3;
            float r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
            const float lum = cl01(r * 0.299f + g * 0.587f + b * 0.114f);
            const float midZone = 4.f * lum * (1.f - lum);
            for (int li = 0; li < ApplyMacroMaskLayers::kCount; ++li) {
                const auto& mlp = params.maskLayer[li];
                const float clar = mlp.clarity / 100.f;
                if (clar == 0.f) continue;
                // Mask source: luminance mask generates the selection from the
                // pixel luma (replaces brush); otherwise use the brush plane.
                float a;
                if (mlp.lumSpread > 0.f) {
                    const float d = std::abs(lum - mlp.lumTarget);
                    const float fL = mlp.lumFeather > 1e-4f ? mlp.lumFeather : 1e-4f;
                    const float e0 = mlp.lumSpread, e1 = mlp.lumSpread + fL;
                    float t = (d - e0) / (e1 - e0);
                    t = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
                    a = 1.f - (t * t * (3.f - 2.f * t));
                } else {
                    const auto& mk = maskLayers.layer[li];
                    if (!mk.data) continue;
                    a = sampleAlphaPlane(mk.data, mk.w, mk.h, u, v);
                }
                if (a <= 0.f) continue;
                float opac = params.maskLayer[li].opacity;
                opac = opac < 0.f ? 0.f : (opac > 1.f ? 1.f : opac);
                const float wgt = clar * 2.0f * midZone * a * opac;
                r += wgt * (r - blur[i]);
                g += wgt * (g - blur[i + 1]);
                b += wgt * (b - blur[i + 2]);
            }
            rgb[i]     = r < 0.f ? 0.f : r;
            rgb[i + 1] = g < 0.f ? 0.f : g;
            rgb[i + 2] = b < 0.f ? 0.f : b;
        }
    }
}

// Masked high-frequency sharpen for Stage C export — parity with the GL
// uber-shader's per-mask-layer sharpness (tight-radius unsharp, luma delta
// broadcast to R/G/B), gated by each layer's brush alpha × opacity. Runs on
// the whole RGB float [preBuf] in place. sharpness is the layer value
// [-100..100]. Keep in sync with the shrp block in gles_renderer.cpp.
void applyMaskedSharpness(float* rgb, int w, int h,
                          const ApplyMacroParams& params,
                          const ApplyMacroMaskLayers& maskLayers) {
    // Radius-1 (3×3) separable box blur = tight low-pass reference, matching
    // the shader's 1px 5-tap sample.
    const size_t n = size_t(w) * h;
    std::vector<float> tmp(n * 3), blur(n * 3);
    const int R = 1;
    const float inv = 1.f / float(2 * R + 1);
    for (int y = 0; y < h; ++y) {
        const float* src = rgb + size_t(y) * w * 3;
        float* dst = tmp.data() + size_t(y) * w * 3;
        for (int c = 0; c < 3; ++c) {
            float acc = 0.f;
            for (int x = -R; x <= R; ++x) {
                int xc = x < 0 ? 0 : (x >= w ? w - 1 : x);
                acc += src[xc * 3 + c];
            }
            for (int x = 0; x < w; ++x) {
                dst[x * 3 + c] = acc * inv;
                int xo = x - R; xo = xo < 0 ? 0 : (xo >= w ? w - 1 : xo);
                int xi = x + R + 1; xi = xi < 0 ? 0 : (xi >= w ? w - 1 : xi);
                acc += src[xi * 3 + c] - src[xo * 3 + c];
            }
        }
    }
    for (int x = 0; x < w; ++x) {
        for (int c = 0; c < 3; ++c) {
            float acc = 0.f;
            for (int y = -R; y <= R; ++y) {
                int yc = y < 0 ? 0 : (y >= h ? h - 1 : y);
                acc += tmp[(size_t(yc) * w + x) * 3 + c];
            }
            for (int y = 0; y < h; ++y) {
                blur[(size_t(y) * w + x) * 3 + c] = acc * inv;
                int yo = y - R; yo = yo < 0 ? 0 : (yo >= h ? h - 1 : yo);
                int yi = y + R + 1; yi = yi < 0 ? 0 : (yi >= h ? h - 1 : yi);
                acc += tmp[(size_t(yi) * w + x) * 3 + c] - tmp[(size_t(yo) * w + x) * 3 + c];
            }
        }
    }
    auto cl01 = [](float v) { return v < 0.f ? 0.f : (v > 1.f ? 1.f : v); };
    for (int y = 0; y < h; ++y) {
        float v = (float(y) + 0.5f) / float(h);
        for (int x = 0; x < w; ++x) {
            float u = (float(x) + 0.5f) / float(w);
            const size_t i = (size_t(y) * w + x) * 3;
            float r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
            const float sLum = cl01(r * 0.299f + g * 0.587f + b * 0.114f);
            const float bLum = blur[i] * 0.299f + blur[i + 1] * 0.587f + blur[i + 2] * 0.114f;
            const float hi = sLum - bLum;   // high-frequency luma detail
            for (int li = 0; li < ApplyMacroMaskLayers::kCount; ++li) {
                const auto& mlp = params.maskLayer[li];
                const float shrp = mlp.sharpness / 100.f;
                if (shrp == 0.f) continue;
                float a;
                if (mlp.lumSpread > 0.f) {
                    const float d = std::abs(sLum - mlp.lumTarget);
                    const float fL = mlp.lumFeather > 1e-4f ? mlp.lumFeather : 1e-4f;
                    const float e0 = mlp.lumSpread, e1 = mlp.lumSpread + fL;
                    float t = (d - e0) / (e1 - e0);
                    t = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
                    a = 1.f - (t * t * (3.f - 2.f * t));
                } else {
                    const auto& mk = maskLayers.layer[li];
                    if (!mk.data) continue;
                    a = sampleAlphaPlane(mk.data, mk.w, mk.h, u, v);
                }
                if (a <= 0.f) continue;
                float opac = mlp.opacity;
                opac = opac < 0.f ? 0.f : (opac > 1.f ? 1.f : opac);
                const float dL = shrp * a * opac * hi;  // luma delta, broadcast
                r += dL; g += dL; b += dL;
            }
            rgb[i]     = cl01(r);
            rgb[i + 1] = cl01(g);
            rgb[i + 2] = cl01(b);
        }
    }
}

// ── IEEE 754 binary16 ↔ binary32 ───────────────────────────────────────────
inline float halfToFloat(uint16_t h) {
    uint32_t sign = (uint32_t(h) & 0x8000) << 16;
    uint32_t exp  = (h >> 10) & 0x1F;
    uint32_t mant = h & 0x3FF;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) bits = sign;
        else {
            int e = -1;
            do { ++e; mant <<= 1; } while ((mant & 0x400) == 0);
            mant &= 0x3FF;
            bits = sign | (uint32_t(127 - 15 - e) << 23) | (mant << 13);
        }
    } else if (exp == 0x1F) {
        bits = sign | 0x7F800000 | (mant << 13);
    } else {
        bits = sign | (uint32_t(exp - 15 + 127) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}

inline uint16_t floatToHalf(float f) {
    uint32_t bits; std::memcpy(&bits, &f, 4);
    uint32_t sign = (bits >> 16) & 0x8000;
    int32_t  exp  = int32_t((bits >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = bits & 0x007FFFFF;
    if (((bits >> 23) & 0xFF) == 0xFF) {
        return uint16_t(sign | 0x7C00 | (mant ? 0x200 : 0));
    }
    if (exp >= 0x1F) return uint16_t(sign | 0x7C00);
    if (exp <= 0) {
        if (exp < -10) return uint16_t(sign);
        mant |= 0x00800000;
        uint32_t shift = uint32_t(14 - exp);
        uint32_t m = (mant >> shift) + ((mant >> (shift - 1)) & 1);
        return uint16_t(sign | m);
    }
    uint32_t m = (mant + 0x00001000) >> 13;
    if (m & 0x00000400) { m = 0; ++exp; if (exp >= 0x1F) return uint16_t(sign | 0x7C00); }
    return uint16_t(sign | (uint32_t(exp) << 10) | (m & 0x3FF));
}

// ── BigTIFF writer for uncompressed RGB16, 256-row strips ──────────────────
//
// We can't reuse v3/tiff_mmap_io.cpp's writer because it emits RGBA_F16
// (4-channel, IEEE half-float). Stage C output is RGB16 (3-channel,
// unsigned 16-bit, gamma-encoded sRGB) — that's the universal "16-bit TIFF"
// most photo editors expect. Same BigTIFF header shape, different
// SamplesPerPixel + BitsPerSample + SampleFormat values.
//
// Layout (matches tiff_mmap_io.cpp § "Custom TIFF reader" except for the
// per-sample values noted above):
//
//   0       BigTIFF header (II 0x002B 8 0 + IFD offset = 16)
//   16      IFD: 8-byte tag count + 11 entries + 8-byte next-IFD offset
//   ~236    StripOffsets table (uint64 × nStrips)
//   ~252+   StripByteCounts table (uint64 × nStrips)
//   data    pixel bands: 256-row strips, RGB16 row-major

constexpr uint16_t BYTE_ORDER_LITTLE     = 0x4949;
constexpr uint16_t BIGTIFF_MAGIC         = 0x002B;
constexpr uint16_t BIGTIFF_OFFSET_SIZE   = 8;
constexpr uint16_t TAG_IMAGE_WIDTH       = 256;
constexpr uint16_t TAG_IMAGE_LENGTH      = 257;
constexpr uint16_t TAG_BITS_PER_SAMPLE   = 258;
constexpr uint16_t TAG_COMPRESSION       = 259;
constexpr uint16_t TAG_PHOTOMETRIC       = 262;
constexpr uint16_t TAG_STRIP_OFFSETS     = 273;
constexpr uint16_t TAG_SAMPLES_PER_PIXEL = 277;
constexpr uint16_t TAG_ROWS_PER_STRIP    = 278;
constexpr uint16_t TAG_STRIP_BYTE_COUNTS = 279;
constexpr uint16_t TAG_PLANAR_CONFIG     = 284;
constexpr uint16_t TAG_SAMPLE_FORMAT     = 339;
constexpr uint16_t TAG_ICC_PROFILE       = 34675;
// Baseline metadata tags (main IFD). TIFF requires entries in ASCENDING tag
// order, so these are sorted inserts into the list above, not appends —
// note TAG_EXIF_IFD (34665) must precede TAG_ICC_PROFILE (34675).
constexpr uint16_t TAG_MAKE              = 271;
constexpr uint16_t TAG_MODEL             = 272;
constexpr uint16_t TAG_DATETIME          = 306;
constexpr uint16_t TAG_ARTIST            = 315;
constexpr uint16_t TAG_COPYRIGHT         = 33432;
constexpr uint16_t TAG_EXIF_IFD          = 34665;   // 0x8769
// EXIF sub-IFD tags.
constexpr uint16_t EXIF_EXPOSURE_TIME    = 0x829A;
constexpr uint16_t EXIF_FNUMBER          = 0x829D;
constexpr uint16_t EXIF_ISO              = 0x8827;
constexpr uint16_t EXIF_VERSION          = 0x9000;
constexpr uint16_t EXIF_DATETIME_ORIG    = 0x9003;
constexpr uint16_t EXIF_FOCAL_LENGTH     = 0x920A;
constexpr uint16_t EXIF_LENS_MODEL       = 0xA434;
constexpr uint16_t TYPE_ASCII  = 2;
constexpr uint16_t TYPE_SHORT  = 3;
constexpr uint16_t TYPE_LONG   = 4;
constexpr uint16_t TYPE_RATIONAL = 5;
constexpr uint16_t TYPE_LONG8  = 16;
constexpr uint16_t TYPE_UNDEFINED = 7;
constexpr uint16_t PHOTOMETRIC_RGB       = 2;
constexpr uint16_t COMPRESSION_NONE      = 1;
constexpr uint16_t PLANAR_CHUNKY         = 1;
constexpr uint16_t SAMPLE_FORMAT_UINT    = 1;
constexpr uint32_t ROWS_PER_STRIP_C      = 256;
constexpr uint32_t SAMPLES_PER_PIXEL_C   = 3;     // RGB
constexpr uint32_t BITS_PER_SAMPLE_C     = 16;
constexpr size_t   IFD_ENTRY_SIZE        = 20;

inline void put16(uint8_t* p, uint16_t v) { p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; }
inline void put32(uint8_t* p, uint32_t v) {
    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF;
    p[2] = (v >> 16) & 0xFF; p[3] = (v >> 24) & 0xFF;
}
inline void put64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = (v >> (i * 8)) & 0xFF;
}
void writeEntry(uint8_t* dst, uint16_t tag, uint16_t type, uint64_t count, uint64_t valueOrOffset) {
    put16(dst, tag); put16(dst + 2, type);
    put64(dst + 4, count); put64(dst + 12, valueOrOffset);
}

/*
 * Optional EXIF payload for the BigTIFF writer.
 *
 * OPT-IN BY DESIGN: when the caller passes nullptr (which every existing
 * caller, including all of Android, does) the planner and writer take exactly
 * the paths they took before, producing a BYTE-IDENTICAL file. This is
 * verified against golden SHA-256s rather than assumed — see
 * WinBatch/golden/*.sha256. That matters because this writer is shared with
 * the Android build and a bad IFD offset would corrupt every export.
 *
 * Empty strings / non-positive numbers are simply omitted, so a partially
 * populated struct is safe.
 */
struct TiffExif {
    std::string make, model, dateTime, artist, copyright;
    std::string lensModel, dateTimeOriginal;
    float  exposureTime = 0.f;   // seconds
    float  fNumber      = 0.f;
    float  focalLength  = 0.f;   // mm
    int    iso          = 0;
};

// A rational is 2 x uint32 (num/den) stored in the payload area.
struct Rational { uint32_t num, den; };
inline Rational toRational(float v, uint32_t maxDen = 1000000u) {
    if (v <= 0.f) return {0u, 1u};
    // Exposure times are conventionally 1/N; keep that shape when it is close.
    if (v < 1.0f) {
        const uint32_t den = static_cast<uint32_t>(std::lround(1.0 / double(v)));
        if (den > 0 && den <= maxDen) return {1u, den};
    }
    return {static_cast<uint32_t>(std::lround(double(v) * 1000.0)), 1000u};
}

// Layout planner — same shape as tiff_mmap_io.cpp::writeStageATiff.
struct TiffPlan {
    uint32_t rowsPerStrip;
    uint32_t stripCount;
    uint64_t bytesPerRow;
    uint64_t stripOffsetsArrayPos;
    uint64_t stripCountsArrayPos;
    uint64_t iccProfileOffset;
    uint64_t iccProfileSize;
    uint64_t pixelDataOffset;
};
/*
 * An ASCII IFD value: BigTIFF stores it inline when it fits the 8-byte value
 * field (count includes the NUL), otherwise out-of-line at an offset. The
 * TIFF spec requires inline when it fits, so both paths are implemented.
 */
struct AsciiVal {
    bool     used = false;
    bool     isInline = false;
    uint64_t count = 0;      // bytes incl. NUL
    uint64_t packed = 0;     // inline payload
    uint64_t offset = 0;     // absolute file offset when out-of-line
};

/*
 * Everything that follows the main IFD when EXIF is requested: out-of-line
 * ASCII payloads, the EXIF sub-IFD, and the EXIF sub-IFD's own payloads.
 */
struct MetaBlock {
    std::vector<uint8_t> bytes;          // written straight after the main IFD
    uint64_t baseOffset = 0;             // absolute file offset of bytes[0]
    uint64_t exifIfdOffset = 0;          // absolute; 0 = no EXIF sub-IFD
    AsciiVal make, model, dateTime, artist, copyright;
    int extraMainTags = 0;               // entries to add to the main IFD
};

// How many extra MAIN-IFD entries a given EXIF struct contributes. Must agree
// with buildMetaBlock/writeTiff16HeaderAndIFD or the IFD length is wrong.
int countExtraMainTags(const TiffExif* x) {
    if (!x) return 0;
    int n = 0;
    if (!x->make.empty())      ++n;
    if (!x->model.empty())     ++n;
    if (!x->dateTime.empty())  ++n;
    if (!x->artist.empty())    ++n;
    if (!x->copyright.empty()) ++n;
    // The EXIF sub-IFD pointer is emitted whenever any sub-IFD tag exists.
    if (x->exposureTime > 0.f || x->fNumber > 0.f || x->focalLength > 0.f ||
        x->iso > 0 || !x->lensModel.empty() || !x->dateTimeOriginal.empty()) ++n;
    return n;
}

TiffPlan planTiff16(uint32_t width, uint32_t height, size_t iccSize = 0,
                    const TiffExif* exif = nullptr, size_t metaBlockSize = 0) {
    TiffPlan t{};
    t.rowsPerStrip = ROWS_PER_STRIP_C;
    t.stripCount   = (height + t.rowsPerStrip - 1) / t.rowsPerStrip;
    t.bytesPerRow  = uint64_t(width) * SAMPLES_PER_PIXEL_C * 2;
    const uint64_t tagCount        = uint64_t((iccSize > 0) ? 12 : 11)
                                   + uint64_t(countExtraMainTags(exif));
    constexpr uint64_t HEADER_BYTES = 16;
    const uint64_t IFD_BYTES       = 8 + tagCount * IFD_ENTRY_SIZE + 8;
    // metaBlockSize is 0 when exif == nullptr, so the layout below is
    // bit-for-bit what it was before EXIF support existed.
    const uint64_t META_BYTES      = HEADER_BYTES + IFD_BYTES + metaBlockSize;
    t.stripOffsetsArrayPos = META_BYTES;
    t.stripCountsArrayPos  = t.stripOffsetsArrayPos + t.stripCount * 8;
    t.iccProfileOffset     = t.stripCountsArrayPos + t.stripCount * 8;
    t.iccProfileSize       = iccSize;
    t.pixelDataOffset      = t.iccProfileOffset + iccSize;
    return t;
}

/*
 * Build the post-main-IFD metadata region. Returns an empty block (and does
 * nothing) when [x] is null, which keeps the no-EXIF layout untouched.
 *
 * [baseOffset] must be the absolute file offset where this block will be
 * written, i.e. 16 + mainIfdBytes — the caller computes the main IFD length
 * from countExtraMainTags() BEFORE calling, because the ASCII/rational
 * payload offsets baked in here are absolute.
 */
MetaBlock buildMetaBlock(const TiffExif* x, uint64_t baseOffset) {
    MetaBlock mb;
    if (!x) return mb;
    mb.baseOffset    = baseOffset;
    mb.extraMainTags = countExtraMainTags(x);

    // Reserve ASCII payloads that do not fit inline, in main-IFD tag order.
    auto addAscii = [&](const std::string& s) -> AsciiVal {
        AsciiVal a;
        if (s.empty()) return a;
        a.used  = true;
        a.count = s.size() + 1;                     // include NUL
        if (a.count <= 8) {
            a.isInline = true;
            uint8_t tmp[8] = {};
            std::memcpy(tmp, s.data(), s.size());   // trailing NULs already zero
            std::memcpy(&a.packed, tmp, 8);
        } else {
            a.offset = baseOffset + mb.bytes.size();
            mb.bytes.insert(mb.bytes.end(), s.begin(), s.end());
            mb.bytes.push_back(0);
            if (mb.bytes.size() & 1) mb.bytes.push_back(0);   // keep 2-byte alignment
        }
        return a;
    };
    mb.make      = addAscii(x->make);
    mb.model     = addAscii(x->model);
    mb.dateTime  = addAscii(x->dateTime);
    mb.artist    = addAscii(x->artist);
    mb.copyright = addAscii(x->copyright);

    // ── EXIF sub-IFD ────────────────────────────────────────────────────
    struct SubEntry { uint16_t tag; uint16_t type; uint64_t count; uint64_t val; };
    std::vector<SubEntry> sub;
    std::vector<uint8_t>  subPayload;     // rationals + long ASCII, after the sub-IFD

    const bool hasSub =
        x->exposureTime > 0.f || x->fNumber > 0.f || x->focalLength > 0.f ||
        x->iso > 0 || !x->lensModel.empty() || !x->dateTimeOriginal.empty();
    if (!hasSub) return mb;

    // ExifVersion is UNDEFINED[4] "0230"; fits inline.
    {
        uint8_t v[8] = {'0','2','3','0',0,0,0,0};
        uint64_t packed; std::memcpy(&packed, v, 8);
        sub.push_back({EXIF_VERSION, TYPE_UNDEFINED, 4, packed});
    }

    // Sub-IFD size is fixed once the entry list is known, so payload offsets
    // can be resolved before the entries are serialised.
    const uint64_t subCountPlaceholder =
        1                                                        // ExifVersion
        + (x->exposureTime > 0.f ? 1 : 0)
        + (x->fNumber > 0.f ? 1 : 0)
        + (x->iso > 0 ? 1 : 0)
        + (!x->dateTimeOriginal.empty() ? 1 : 0)
        + (x->focalLength > 0.f ? 1 : 0)
        + (!x->lensModel.empty() ? 1 : 0);
    const uint64_t subIfdBytes  = 8 + subCountPlaceholder * IFD_ENTRY_SIZE + 8;
    const uint64_t subIfdOffset = baseOffset + mb.bytes.size();
    const uint64_t payloadBase  = subIfdOffset + subIfdBytes;

    auto addRational = [&](uint16_t tag, float v) {
        if (v <= 0.f) return;
        const Rational r = toRational(v);
        const uint64_t off = payloadBase + subPayload.size();
        uint8_t buf[8];
        put32(buf, r.num); put32(buf + 4, r.den);
        subPayload.insert(subPayload.end(), buf, buf + 8);
        sub.push_back({tag, TYPE_RATIONAL, 1, off});
    };
    auto addSubAscii = [&](uint16_t tag, const std::string& s) {
        if (s.empty()) return;
        const uint64_t count = s.size() + 1;
        if (count <= 8) {
            uint8_t tmp[8] = {};
            std::memcpy(tmp, s.data(), s.size());
            uint64_t packed; std::memcpy(&packed, tmp, 8);
            sub.push_back({tag, TYPE_ASCII, count, packed});
        } else {
            const uint64_t off = payloadBase + subPayload.size();
            subPayload.insert(subPayload.end(), s.begin(), s.end());
            subPayload.push_back(0);
            if (subPayload.size() & 1) subPayload.push_back(0);
            sub.push_back({tag, TYPE_ASCII, count, off});
        }
    };

    // MUST stay in ascending tag order (0x829A < 0x829D < 0x8827? no —
    // 0x8827 > 0x829D, so ISO follows FNumber; the sort below enforces it
    // regardless of insertion order).
    addRational(EXIF_EXPOSURE_TIME, x->exposureTime);
    addRational(EXIF_FNUMBER,       x->fNumber);
    if (x->iso > 0) sub.push_back({EXIF_ISO, TYPE_SHORT, 1, uint64_t(x->iso)});
    addSubAscii(EXIF_DATETIME_ORIG, x->dateTimeOriginal);
    addRational(EXIF_FOCAL_LENGTH,  x->focalLength);
    addSubAscii(EXIF_LENS_MODEL,    x->lensModel);

    std::sort(sub.begin(), sub.end(),
              [](const SubEntry& a, const SubEntry& b) { return a.tag < b.tag; });

    // Serialise: sub-IFD, then its payload.
    std::vector<uint8_t> ifdBuf(subIfdBytes, 0);
    put64(ifdBuf.data(), uint64_t(sub.size()));
    uint8_t* e = ifdBuf.data() + 8;
    for (const auto& s : sub) { writeEntry(e, s.tag, s.type, s.count, s.val); e += IFD_ENTRY_SIZE; }
    put64(e, 0);   // next-IFD = none

    mb.exifIfdOffset = subIfdOffset;
    mb.bytes.insert(mb.bytes.end(), ifdBuf.begin(), ifdBuf.end());
    mb.bytes.insert(mb.bytes.end(), subPayload.begin(), subPayload.end());
    return mb;
}

// Write only header + IFD + strip arrays + optional ICC. Caller streams pixel data after.
bool writeTiff16HeaderAndIFD(FILE* fp, uint32_t width, uint32_t height,
                             const TiffPlan& t,
                             const uint8_t* iccProfile, size_t iccSize,
                             const MetaBlock* meta = nullptr) {
    const bool hasIcc = iccProfile != nullptr && iccSize > 0;
    const int  extraTags = meta ? meta->extraMainTags : 0;
    const uint64_t tagCount = uint64_t(hasIcc ? 12 : 11) + uint64_t(extraTags);

    // Header.
    uint8_t header[16];
    put16(header,     BYTE_ORDER_LITTLE);
    put16(header + 2, BIGTIFF_MAGIC);
    put16(header + 4, BIGTIFF_OFFSET_SIZE);
    put16(header + 6, 0);
    put64(header + 8, 16);
    if (std::fwrite(header, 1, 16, fp) != 16) return false;

    // IFD.
    const uint64_t ifdBytes = 8 + tagCount * IFD_ENTRY_SIZE + 8;
    std::vector<uint8_t> ifd(ifdBytes, 0);
    put64(ifd.data(), tagCount);
    uint8_t* e = ifd.data() + 8;

    writeEntry(e, TAG_IMAGE_WIDTH,       TYPE_LONG,  1, width);              e += IFD_ENTRY_SIZE;
    writeEntry(e, TAG_IMAGE_LENGTH,      TYPE_LONG,  1, height);             e += IFD_ENTRY_SIZE;

    // BitsPerSample (SHORT × 3 — 16, 16, 16 — fits inline 6 bytes ≤ 8).
    {
        uint8_t inline8[8] = {};
        for (int i = 0; i < 3; ++i) put16(inline8 + i * 2, BITS_PER_SAMPLE_C);
        uint64_t packed; std::memcpy(&packed, inline8, 8);
        writeEntry(e, TAG_BITS_PER_SAMPLE, TYPE_SHORT, 3, packed);
    }
    e += IFD_ENTRY_SIZE;
    writeEntry(e, TAG_COMPRESSION,       TYPE_SHORT, 1, COMPRESSION_NONE);   e += IFD_ENTRY_SIZE;
    writeEntry(e, TAG_PHOTOMETRIC,       TYPE_SHORT, 1, PHOTOMETRIC_RGB);    e += IFD_ENTRY_SIZE;
    // 271/272 slot in here to keep the IFD ascending (262 < 271 < 272 < 273).
    auto emitAscii = [&](uint16_t tag, const AsciiVal& a) {
        if (!a.used) return;
        writeEntry(e, tag, TYPE_ASCII, a.count, a.isInline ? a.packed : a.offset);
        e += IFD_ENTRY_SIZE;
    };
    if (meta) { emitAscii(TAG_MAKE, meta->make); emitAscii(TAG_MODEL, meta->model); }
    if (t.stripCount == 1) {
        writeEntry(e, TAG_STRIP_OFFSETS, TYPE_LONG8, 1, t.pixelDataOffset);
    } else {
        writeEntry(e, TAG_STRIP_OFFSETS, TYPE_LONG8, t.stripCount, t.stripOffsetsArrayPos);
    }
    e += IFD_ENTRY_SIZE;
    writeEntry(e, TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, 1, SAMPLES_PER_PIXEL_C); e += IFD_ENTRY_SIZE;
    writeEntry(e, TAG_ROWS_PER_STRIP,    TYPE_LONG,  1, t.rowsPerStrip);     e += IFD_ENTRY_SIZE;
    if (t.stripCount == 1) {
        writeEntry(e, TAG_STRIP_BYTE_COUNTS, TYPE_LONG8, 1, uint64_t(height) * t.bytesPerRow);
    } else {
        writeEntry(e, TAG_STRIP_BYTE_COUNTS, TYPE_LONG8, t.stripCount, t.stripCountsArrayPos);
    }
    e += IFD_ENTRY_SIZE;
    writeEntry(e, TAG_PLANAR_CONFIG,     TYPE_SHORT, 1, PLANAR_CHUNKY);      e += IFD_ENTRY_SIZE;
    // 306/315 slot in here (284 < 306 < 315 < 339).
    if (meta) { emitAscii(TAG_DATETIME, meta->dateTime); emitAscii(TAG_ARTIST, meta->artist); }
    // SampleFormat (SHORT × 3 — UINT for each channel).
    {
        uint8_t inline8[8] = {};
        for (int i = 0; i < 3; ++i) put16(inline8 + i * 2, SAMPLE_FORMAT_UINT);
        uint64_t packed; std::memcpy(&packed, inline8, 8);
        writeEntry(e, TAG_SAMPLE_FORMAT, TYPE_SHORT, 3, packed);
    }
    e += IFD_ENTRY_SIZE;
    // 33432 Copyright and 34665 ExifIFD precede 34675 ICCProfile.
    if (meta) {
        emitAscii(TAG_COPYRIGHT, meta->copyright);
        if (meta->exifIfdOffset != 0) {
            writeEntry(e, TAG_EXIF_IFD, TYPE_LONG8, 1, meta->exifIfdOffset);
            e += IFD_ENTRY_SIZE;
        }
    }
    if (hasIcc) {
        writeEntry(e, TAG_ICC_PROFILE, TYPE_UNDEFINED, iccSize, t.iccProfileOffset);
        e += IFD_ENTRY_SIZE;
    }
    put64(e, 0);

    if (std::fwrite(ifd.data(), 1, ifdBytes, fp) != ifdBytes) return false;

    // Metadata region: out-of-line ASCII payloads + EXIF sub-IFD + its payloads.
    // Sits immediately after the main IFD, which is exactly the baseOffset the
    // absolute offsets above were computed against.
    if (meta && !meta->bytes.empty()) {
        if (std::fwrite(meta->bytes.data(), 1, meta->bytes.size(), fp) != meta->bytes.size())
            return false;
    }

    // Strip arrays (only if stripCount > 1; otherwise the values fit inline).
    if (t.stripCount > 1) {
        uint8_t buf[8];
        for (uint32_t i = 0; i < t.stripCount; ++i) {
            uint64_t off = t.pixelDataOffset + uint64_t(i) * t.rowsPerStrip * t.bytesPerRow;
            put64(buf, off);
            if (std::fwrite(buf, 1, 8, fp) != 8) return false;
        }
        for (uint32_t i = 0; i < t.stripCount; ++i) {
            uint32_t rows = t.rowsPerStrip;
            if (i == t.stripCount - 1) {
                uint32_t remaining = height - i * t.rowsPerStrip;
                if (remaining < t.rowsPerStrip) rows = remaining;
            }
            uint64_t bytes = uint64_t(rows) * t.bytesPerRow;
            put64(buf, bytes);
            if (std::fwrite(buf, 1, 8, fp) != 8) return false;
        }
    }

    // ICC profile payload (written between metadata and pixel data).
    if (hasIcc) {
        if (std::fwrite(iccProfile, 1, iccSize, fp) != iccSize) return false;
    }
    return true;
}

}  // anonymous namespace

StageCResult runStageC(const std::string& stageATifPath,
                       const std::string& outputPath,
                       const StageCOptions& options) {
    StageCResult r{};
    auto t0 = std::chrono::steady_clock::now();

    if (options.format != StageCFormat::Tiff16) {
        r.error = "M8 supports TIFF-16 only";
        return r;
    }
    if (!options.params || options.paramsCount <= 0) {
        r.error = "params missing";
        return r;
    }

    StageATiffReader* reader = openStageATiff(stageATifPath);
    if (!reader) { r.error = "openStageATiff failed"; return r; }
    const auto& hdr = getStageATiffHeader(reader);
    const uint32_t srcW = hdr.width;
    const uint32_t srcH = hdr.height;
    if (srcW == 0 || srcH == 0) {
        r.error = "zero source dims";
        closeStageATiff(reader);
        return r;
    }

    // M8: no resize, output dims = source dims. Resize support lands when
    // Stage C grows its scaling kernel (M9+ candidate).
    if ((options.targetWidth  != 0 && options.targetWidth  != srcW) ||
        (options.targetHeight != 0 && options.targetHeight != srcH)) {
        LOGI("runStageC: requested resize %ux%u → ignored, M8 keeps source dims %ux%u",
             options.targetWidth, options.targetHeight, srcW, srcH);
    }

    FILE* fp = std::fopen(outputPath.c_str(), "wb");
    if (!fp) {
        r.error = std::string("fopen failed: ") + outputPath;
        closeStageATiff(reader);
        return r;
    }
    // EXIF is opt-in: options.exif == nullptr (every Android caller) means the
    // MetaBlock is empty and the layout below is byte-identical to pre-EXIF
    // output. Verified against golden SHA-256s, not assumed.
    const TiffExif* exifIn = reinterpret_cast<const TiffExif*>(options.exif);
    const uint64_t mainIfdBytes =
        8 + (uint64_t((options.iccProfileSize > 0) ? 12 : 11)
             + uint64_t(countExtraMainTags(exifIn))) * IFD_ENTRY_SIZE + 8;
    MetaBlock meta = buildMetaBlock(exifIn, 16 + mainIfdBytes);
    TiffPlan plan = planTiff16(srcW, srcH, options.iccProfileSize, exifIn, meta.bytes.size());
    if (!writeTiff16HeaderAndIFD(fp, srcW, srcH, plan,
                                 options.iccProfile, options.iccProfileSize,
                                 exifIn ? &meta : nullptr)) {
        r.error = "header write failed";
        std::fclose(fp); std::remove(outputPath.c_str());
        closeStageATiff(reader);
        return r;
    }

    // Decode params + LUT.
    ApplyMacroParams params = ApplyMacroParams::fromFloatArray(
        options.params, options.paramsCount);
    // Wire tone curve into the kernel so it runs at the correct pipeline
    // position (after WB-trims, before gamutCompress+knee) — matching GL.
    params.toneCurveLut = options.toneCurveLut;
    ApplyMacroLut lut{};
    lut.data = options.lutData;
    lut.size = options.lutSize;
    for (int i = 0; i < 3; ++i) {
        lut.domainMin[i] = options.lutDomainMin[i];
        lut.domainMax[i] = options.lutDomainMax[i];
    }
    // M12.2c.2b — brush-mask layers for per-pixel multi-layer Mask tab.
    ApplyMacroMaskLayers maskLayers{};
    bool anyMaskLayer = false;
    for (int i = 0; i < ApplyMacroMaskLayers::kCount && i < StageCOptions::kMaskLayers; ++i) {
        maskLayers.layer[i].data = options.maskLayerData[i];
        maskLayers.layer[i].w    = options.maskLayerW[i];
        maskLayers.layer[i].h    = options.maskLayerH[i];
        if (options.maskLayerData[i]) anyMaskLayer = true;
    }
    const ApplyMacroMaskLayers* maskLayersPtr = anyMaskLayer ? &maskLayers : nullptr;
    // Subject mask for per-segment routing (vignette vigEffect, gradient
    // per-side, per-segment highlights/whites, ambiance per-segment, smart
    // sharpness). Editor preview always has this; Stage C previously
    // hard-nulled the per-pixel mask arg → per-segment features broke on
    // save (most visible: vigEffect=2 returned vigGate=0 → vignette gone).
    ApplyMacroSubjectMask subjectMaskStruct{};
    if (options.subjectMask != nullptr && options.subjectMaskSize > 0) {
        subjectMaskStruct.data  = options.subjectMask;
        subjectMaskStruct.w     = options.subjectMaskSize;
        subjectMaskStruct.h     = options.subjectMaskSize;
        subjectMaskStruct.rectU0 = options.subjectMaskRectU0;
        subjectMaskStruct.rectV0 = options.subjectMaskRectV0;
        subjectMaskStruct.rectU1 = options.subjectMaskRectU1;
        subjectMaskStruct.rectV1 = options.subjectMaskRectV1;
    }
    const ApplyMacroSubjectMask* subjectMaskPtr =
        (subjectMaskStruct.data != nullptr) ? &subjectMaskStruct : nullptr;
    LOGI("runStageC: src %ux%u → %s; lut=%dx%dx%d (%s) exposure=%.3f xmpEnabled=%d subjMask=%s",
         srcW, srcH, outputPath.c_str(),
         lut.size, lut.size, lut.size,
         params.lutEnabled && lut.size > 0 ? "active" : "off",
         params.exposure, params.xmpEnabled,
         subjectMaskPtr ? "present" : "null");

    // ── Whole-image spatial pre-pass (CLAHE + NR; native-only) ───────────
    // Both CLAHE (slots [144..146]) and NR (slots [147..148]) are whole-image
    // ops that can't run in the streaming row loop below. When either is on,
    // decode the entire Stage A image into one transient FP32 RGB buffer,
    // run the kernels in place (CLAHE first — it can amplify noise — then NR),
    // and the row loop reads from this buffer instead of the Stage A strips.
    const bool claheOn = options.paramsCount > 144 && options.params[144] > 0.5f;
    const float jpegStr = (options.paramsCount > 458) ? options.params[458] : 0.0f;
    const float jpegClean = (options.paramsCount > 459) ? options.params[459] : 0.5f;
    const float jpegDet = (options.paramsCount > 460) ? options.params[460] : 0.5f;
    const bool jpegOn = jpegStr > 0.001f;
    const float lumaNR   = (options.paramsCount > 147) ? options.params[147] : 0.0f;
    const float chromaNR = (options.paramsCount > 148) ? options.params[148] : 0.0f;
    const float blueNR   = (options.paramsCount > 233) ? options.params[233] : 0.0f;
    const float redNR    = (options.paramsCount > 234) ? options.params[234] : 0.0f;
    const bool nrOn = lumaNR > 0.f || chromaNR > 0.f || blueNR > 0.f || redNR > 0.f;
    const DetailParams detail = readDetailParams(options.params, options.paramsCount);
    const bool detailOn = detail.any();
    // Film grain + wash-out (blue-noise) — per-pixel after grading, in lock-step
    // with the GL shader (same tile, same fixed reference grid).
    const GrainParams grainParams = readGrain(options.params, options.paramsCount);
    const bool grainOn = grainParams.any();
    const float grainAspect = (srcH > 0) ? float(srcW) / float(srcH) : 1.f;
    bool maskClarityOn = false;
    bool maskSharpnessOn = false;
    for (int i = 0; i < ApplyMacroMaskLayers::kCount; ++i) {
        const bool hasSource = maskLayers.layer[i].data || params.maskLayer[i].lumSpread > 0.f;
        if (!hasSource) continue;
        if (params.maskLayer[i].clarity   != 0.f) maskClarityOn   = true;
        if (params.maskLayer[i].sharpness != 0.f) maskSharpnessOn = true;
    }
    // Ambiance + FX blur: Gaussian uBlurTex plane. Soft diffusion (Orton/Glow)
    // uses a dedicated softDiffBuf baked into Karis bloom — not this plane.
    const float ambianceSlider = (options.paramsCount > 208) ? options.params[208] : 0.0f;
    const float ortonSlider    = (options.paramsCount > 209) ? options.params[209] : 0.0f;
    const float ambSubj  = (options.paramsCount > 231) ? options.params[231] : 0.0f;
    const float ambBg    = (options.paramsCount > 232) ? options.params[232] : 0.0f;
    const bool ambianceOn = (ambianceSlider != 0.f) || (ambSubj != 0.f) || (ambBg != 0.f);
    const bool ortonOn    = ortonSlider > 0.f;
    // FX tab blur (slots 352-364) needs the same Gaussian-blurred reference
    // the editor uses for its uBlurTex pre-pass.
    const float fxBlurStyle = (options.paramsCount > 363) ? options.params[363] : 0.f;
    const bool fxBlurOn     = fxBlurStyle > 0.5f;
    const float fxGlowStrengthEarly = (options.paramsCount > 372) ? options.params[372] : 0.f;
    const bool fxGlowOnEarly = fxGlowStrengthEarly > 0.f;
    const bool softDiffOn   = ortonOn || fxGlowOnEarly;
    const bool needBlur     = ambianceOn || fxBlurOn;
    // Bilateral denoise (GPU parity) — same sigma/threshold scaling as the
    // GLSL kBilateralFrag shader: sigma = slot147/100*5.0, threshold = slot148/100*0.1.
    const float bilateralSigma     = lumaNR / 100.0f * 5.0f;
    const float bilateralThreshold = chromaNR / 100.0f * 0.1f;
    const bool bilateralOn = bilateralSigma > 0.f || bilateralThreshold > 0.f;
    // Cubic chromatic aberration (GPU parity) — reads slots 352/353.
    const float aberStrength   = (options.paramsCount > 352) ? options.params[352] : 0.0f;
    const float aberSeparation = (options.paramsCount > 353) ? options.params[353] : 0.0f;
    const bool cubicCAOn = aberStrength != 0.0f;
    const bool preOn = claheOn || jpegOn || nrOn || detailOn || maskClarityOn || maskSharpnessOn || needBlur
                     || softDiffOn || bilateralOn || cubicCAOn;
    std::vector<float> preBuf;
    if (preOn) {
        preBuf.resize(size_t(srcW) * srcH * 3);
        for (uint32_t y = 0; y < srcH; ++y) {
            uint32_t stripIdx   = y / hdr.rowsPerStrip;
            uint32_t rowInStrip = y - stripIdx * hdr.rowsPerStrip;
            const uint16_t* strip = getStageATiffStrip(reader, stripIdx);
            const uint16_t* src   = strip + size_t(rowInStrip) * size_t(srcW) * 4;
            float* dst = preBuf.data() + size_t(y) * srcW * 3;
            for (uint32_t x = 0; x < srcW; ++x) {
                dst[x * 3 + 0] = halfToFloat(src[x * 4 + 0]);
                dst[x * 3 + 1] = halfToFloat(src[x * 4 + 1]);
                dst[x * 3 + 2] = halfToFloat(src[x * 4 + 2]);
            }
        }
        if (claheOn) {
            const float shBoost = (options.paramsCount > 145) ? options.params[145] : 0.0f;
            const float hiBoost = (options.paramsCount > 146) ? options.params[146] : 0.0f;
            raw_v3::applyClahe<float>(preBuf.data(), int(srcW), int(srcH),
                                      int(srcW), 3, shBoost, hiBoost);
            LOGI("runStageC: CLAHE pre-pass applied (sh=%.2f hi=%.2f)", shBoost, hiBoost);
        }
        if (jpegOn) {
            raw_v3::applyJpegDualRecon<float>(preBuf.data(), int(srcW), int(srcH),
                                              int(srcW), 3, jpegStr, jpegClean, jpegDet);
            LOGI("runStageC: JPEG Refine pre-pass applied (str=%.2f clean=%.2f detail=%.2f)",
                 jpegStr, jpegClean, jpegDet);
        }
        if (nrOn) {
            raw_v3::applyNoiseReduction<float>(preBuf.data(), int(srcW), int(srcH),
                                               int(srcW), 3, lumaNR, chromaNR, blueNR, redNR,
                                               options.subjectMask,
                                               options.subjectMaskSize,
                                               options.subjectMaskSize);
            LOGI("runStageC: NR pre-pass applied (lum=%.2f chroma=%.2f blue=%.2f red=%.2f mask=%d)",
                 lumaNR, chromaNR, blueNR, redNR, options.subjectMask ? 1 : 0);
        }
        // Bilateral denoise — GPU parity (Req 15). Runs after legacy NR,
        // mirrors the GLSL kBilateralFrag circular-kernel bilateral exactly.
        if (bilateralOn) {
            smartDeNoise_cpu(preBuf.data(), int(srcW), int(srcH),
                            bilateralSigma, bilateralThreshold);
        }
        if (detailOn) {
            raw_v3::applyDetail<float>(preBuf.data(), int(srcW), int(srcH),
                                       int(srcW), 3, detail,
                                       options.subjectMask, options.subjectMaskSize,
                                       options.subjectMaskSize);
            LOGI("runStageC: Detail pre-pass applied");
        }
        if (maskClarityOn) {
            applyMaskedClarity(preBuf.data(), int(srcW), int(srcH), params, maskLayers);
            LOGI("runStageC: masked clarity pre-pass applied");
        }
        if (maskSharpnessOn) {
            applyMaskedSharpness(preBuf.data(), int(srcW), int(srcH), params, maskLayers);
            LOGI("runStageC: masked sharpness pre-pass applied");
        }
        // Cubic chromatic aberration — GPU parity (Req 15). Runs after
        // spatial pre-passes (mirrors GPU: bilateral → uber-shader CA).
        if (cubicCAOn) {
            applyCubicCA_cpu(preBuf.data(), int(srcW), int(srcH),
                            aberStrength, aberSeparation);
        }
    }

    // Ambiance/FX blur reference. Soft diffusion uses a separate buffer
    // (softDiffBuf) at 2+bloomRadius*1.15 — never shares this plane.
    std::vector<float> blurBuf;
    if (needBlur) {
        const int longSide = int(std::max(srcW, srcH));
        constexpr float kEditorRefLongSide = 2560.f;
        float editorPx = 4.0f;  // ambiance default
        if (fxBlurOn) {
            // Match preview fxBlur radius scaling loosely via style amount.
            const float amt = std::max({
                (options.paramsCount > 354) ? options.params[354] : 0.f,
                (options.paramsCount > 355) ? options.params[355] : 0.f,
                (options.paramsCount > 357) ? options.params[357] : 0.f,
                (options.paramsCount > 360) ? options.params[360] : 0.f});
            editorPx = 2.0f + amt * 14.0f;
        }
        int radius = std::max(2, int(std::round(editorPx * longSide / kEditorRefLongSide)));
        blurBuf.resize(size_t(srcW) * srcH * 3);
        LOGI("runStageC: blur enabled (ambiance=%d fxBlur=%d), src=%ux%u, radius=%dpx (editorPx=%.1f)",
             ambianceOn ? 1 : 0, fxBlurOn ? 1 : 0, srcW, srcH, radius, editorPx);
        auto tB0 = std::chrono::steady_clock::now();
        gaussianBlurSeparable(preBuf.data(), blurBuf.data(),
                              int(srcW), int(srcH), radius);
        auto tB1 = std::chrono::steady_clock::now();
        LOGI("runStageC: Gaussian blur for Ambiance/FX (r=%d) %lld ms",
             radius,
             (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tB1 - tB0).count());
        LOGI("runStageC: first blurPix tap = (%.4f, %.4f, %.4f)",
             blurBuf[0], blurBuf[1], blurBuf[2]);
    }

    // Soft-diffusion Gaussian at bloomRadius scale (dedicated; not uBlurTex).
    std::vector<float> softDiffBuf;
    if (softDiffOn) {
        const int longSide = int(std::max(srcW, srcH));
        constexpr float kEditorRefLongSide = 2560.f;
        const float userBloomRadius =
            (options.paramsCount > 205) ? options.params[205] : 0.f;
        const float editorPx = 2.0f + std::max(0.f, std::min(24.f, userBloomRadius)) * 1.15f;
        int radius = std::max(2, int(std::round(editorPx * longSide / kEditorRefLongSide)));
        softDiffBuf.resize(size_t(srcW) * srcH * 3);
        gaussianBlurSeparable(preBuf.data(), softDiffBuf.data(),
                              int(srcW), int(srcH), radius);
        LOGI("runStageC: softDiff Gaussian r=%dpx (editorPx=%.1f)", radius, editorPx);
    }

    // ── Karis pyramid bloom (GPU) for Orton parity with editor ──────────
    //   The editor's GL shader runs a 6-mip threshold + tent-upsample
    //   bloom pyramid for Orton (uBloomTex). The Gaussian blur above is
    //   the WRONG source for Orton — it would produce a flat warm cast.
    //   We instead run the SAME shader code through
    //   OffscreenSaveRenderer::computeKarisBloom() so editor and save
    //   share the bloom algorithm exactly. Ambiance still uses the
    //   Gaussian above (matches editor's separate uBlurTex).
    std::vector<float> bloomBuf;
    const float fxGlowStrength = (options.paramsCount > 372) ? options.params[372] : 0.f;
    const bool fxGlowOn = fxGlowStrength > 0.f;
    if (ortonOn || fxGlowOn) {
        // Resolution-invariant tent radius. The editor's preview is ~2048
        // long-side; Stage C save can be 5500+. A tent in raw texels would
        // cover 2.5× more relative image area at preview res than at save
        // res, producing the "editor glowy, save sharp" divergence on
        // bloom photos. Anchor to 1080-long-side and scale per stage.
        const float userBloomRadius = (options.paramsCount > 205) ? options.params[205] : 0.f;
        // Soft vertical-oval Karis; spread 0.22 (preview=export vs gles_renderer).
        const float baseTent = 1.0f + std::max(0.f, std::min(24.f, userBloomRadius)) * 0.22f;
        const int longSide = int(std::max(srcW, srcH));
        const float tentRadius = baseTent * float(longSide) / 1080.0f;
        const float thresholdLuma = 0.65f;   // hardcoded in editor (gles_renderer.cpp:3479)
        const float mistTightness = (options.paramsCount > 447) ? options.params[447] : 0.55f;
        const float bloomShape = (options.paramsCount > 206) ? options.params[206] : 1.f;
        bloomBuf.resize(size_t(srcW) * srcH * 3);
        auto tB0 = std::chrono::steady_clock::now();
        bool ok = false;
#ifndef RAZ_NO_EGL
        OffscreenSaveRenderer karis;
        ok = karis.init(int(srcW), int(srcH));
        if (ok) {
            ok = karis.computeKarisBloom(preBuf.data(), int(srcW), int(srcH),
                                          thresholdLuma, tentRadius,
                                          bloomBuf.data(), mistTightness, bloomShape);
            karis.release();
        }
#endif
        auto tB1 = std::chrono::steady_clock::now();
        if (ok) {
            LOGI("runStageC: Karis bloom (GPU) %lld ms (tent=%.2f thr=%.2f) first=(%.4f,%.4f,%.4f)",
                 (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tB1 - tB0).count(),
                 tentRadius, thresholdLuma,
                 bloomBuf[0], bloomBuf[1], bloomBuf[2]);
            if (!softDiffBuf.empty()) {
                bakeSoftDiffusionIntoBloom(bloomBuf.data(), softDiffBuf.data(),
                                           int(srcW) * int(srcH),
                                           ortonSlider, fxGlowStrength);
                LOGI("runStageC: softDiff baked into Karis bloom");
            }
        } else {
            LOGE("runStageC: Karis bloom FAILED; Orton will fall back to Gaussian (editor↔save divergence)");
            r.karisBloomFallback = true;
            bloomBuf.clear();
        }
    }

    // Streaming: read one row of Stage A (RGBA_F16) → apply kernel → write
    // one row of RGB16 to fp. No band buffer needed; the row scratch is
    // ~3 × 4 B × 5500 = 66 KB.
    const uint32_t W = srcW;
    std::vector<float>    rowFloat(W * 3);
    std::vector<uint16_t> rowU16  (W * 3);
    std::vector<uint16_t> fullU16Bokeh(size_t(W) * size_t(srcH) * 3u);

    auto tHeader = std::chrono::steady_clock::now();
    LOGI("runStageC: header + IFD written in %lld ms",
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tHeader - t0).count());

    for (uint32_t y = 0; y < srcH; ++y) {
        if (preOn) {
            // Spatial pre-pass active: row already in preBuf (CLAHE/NR applied).
            std::memcpy(rowFloat.data(),
                        preBuf.data() + size_t(y) * W * 3,
                        size_t(W) * 3 * sizeof(float));
        } else {
            // Locate this row in the Stage A strip layout.
            uint32_t stripIdx   = y / hdr.rowsPerStrip;
            uint32_t rowInStrip = y - stripIdx * hdr.rowsPerStrip;
            const uint16_t* strip = getStageATiffStrip(reader, stripIdx);
            const uint16_t* src   = strip + size_t(rowInStrip) * size_t(W) * 4;

            // Read RGBA_F16 → RGB float.
            for (uint32_t x = 0; x < W; ++x) {
                rowFloat[x * 3 + 0] = halfToFloat(src[x * 4 + 0]);
                rowFloat[x * 3 + 1] = halfToFloat(src[x * 4 + 1]);
                rowFloat[x * 3 + 2] = halfToFloat(src[x * 4 + 2]);
            }
        }

        // Apply per-pixel kernel. When the blur buffer is present (ambiance
        // or Orton active) we pass the corresponding pixel from it so
        // applyMacroPixel can do the shader-matching detail-layer boost +
        // screen-blend without smuggling a global blur sampler around.
        // The full blur buffer is also passed for multi-tap FX blur.
        const float vNorm = (srcH > 1) ? float(y) / float(srcH - 1) : 0.5f;
        const float* blurRow  = needBlur          ? (blurBuf.data()  + size_t(y) * W * 3) : nullptr;
        const float* bloomRow = !bloomBuf.empty() ? (bloomBuf.data() + size_t(y) * W * 3) : nullptr;
        const float* blurFull = needBlur          ? blurBuf.data() : nullptr;
        for (uint32_t x = 0; x < W; ++x) {
            const float uNorm = (W > 1) ? float(x) / float(W - 1) : 0.5f;
            const float* blurPx  = blurRow  ? &blurRow [x * 3] : nullptr;
            const float* bloomPx = bloomRow ? &bloomRow[x * 3] : nullptr;
            applyMacroPixel(&rowFloat[x * 3], uNorm, vNorm,
                            params, lut.size > 0 ? &lut : nullptr,
                            subjectMaskPtr, maskLayersPtr,
                            blurPx, bloomPx,
                            blurFull, int(W), int(srcH));
            if (grainOn)
                applyFilmGrain(&rowFloat[x * 3], uNorm, vNorm, grainAspect, grainParams);
        }

        // Quantize float[0,1] → uint16[0,65535]. Output is gamma-encoded sRGB
        // already (Stage A wrote it that way), so a straight linear quantize
        // is correct.
        //
        // 16-bit dither: at native 16-bit a sub-LSB shake is invisible, but
        // downstream pipes commonly truncate to 10/12-bit (HDR phone displays,
        // some monitors, some video pipelines) where the banding reappears.
        // We use the SAME Bayer pattern as the 8-bit path so a render shipped
        // through both bit-depths shows a consistent texture if a downstream
        // viewer downshifts. Strength comes from slot 28 (`ditherStrength`);
        // 0 disables. Sub-LSB float shake at 16-bit = 1.0 (one DN).
        const float dStr16 = (options.paramsCount > 28) ? options.params[28] : 1.f;
        const bool ditherOn16 = dStr16 > 0.f;
        for (uint32_t x = 0; x < W; ++x) {
            float fr = rowFloat[x * 3 + 0] * 65535.f;
            float fg = rowFloat[x * 3 + 1] * 65535.f;
            float fb = rowFloat[x * 3 + 2] * 65535.f;
            if (ditherOn16) {
                const int tx = int(x);
                const int ty = int(y);
                const float t = (float(kBayer8[(ty & 7) * 8 + (tx & 7)]) + 0.5f) / 64.0f;
                const float shake = (t - 0.5f) * dStr16;  // ±0.5 LSB at 16-bit DN
                fr += shake; fg += shake; fb += shake;
            } else {
                fr += 0.5f; fg += 0.5f; fb += 0.5f;
            }
            // Match the 8-bit path: post-shake round half-up via +0.5 (already
            // baked into the non-dither branch above; dither branch needs it).
            if (ditherOn16) { fr += 0.5f; fg += 0.5f; fb += 0.5f; }
            rowU16[x * 3 + 0] = uint16_t(fr < 0.f ? 0.f : (fr > 65535.f ? 65535.f : fr));
            rowU16[x * 3 + 1] = uint16_t(fg < 0.f ? 0.f : (fg > 65535.f ? 65535.f : fg));
            rowU16[x * 3 + 2] = uint16_t(fb < 0.f ? 0.f : (fb > 65535.f ? 65535.f : fb));
        }
        std::memcpy(fullU16Bokeh.data() + size_t(y) * size_t(W) * 3u,
                    rowU16.data(), size_t(W) * 3u * sizeof(uint16_t));
    }

    // Selective disc bokeh on full graded RGB16 before TIFF write (Stage C).
    {
        const float bokehBlurP = (options.paramsCount > 179) ? options.params[179] : 0.f;
        const float bokehBallsP = (options.paramsCount > 180) ? options.params[180] : 0.f;
        const float bokehSpreadP = (options.paramsCount > 181) ? options.params[181] : 0.f;
        if (bokehBlurP > 0.f && options.subjectMask != nullptr && options.subjectMaskSize > 0) {
            ApplyMacroSubjectMask subj{};
            subj.data = options.subjectMask;
            subj.w = options.subjectMaskSize;
            subj.h = (options.subjectMaskH > 0) ? options.subjectMaskH : options.subjectMaskSize;
            subj.rectU0 = options.subjectMaskRectU0; subj.rectV0 = options.subjectMaskRectV0;
            subj.rectU1 = options.subjectMaskRectU1; subj.rectV1 = options.subjectMaskRectV1;
            ApplyMacroSubjectMask atten{};
            const ApplyMacroSubjectMask* attenPtr = nullptr;
            if (options.attenMask != nullptr && options.attenMaskSize > 0) {
                atten.data = options.attenMask;
                atten.w = options.attenMaskSize;
                atten.h = (options.attenMaskH > 0) ? options.attenMaskH : options.attenMaskSize;
                atten.rectU0 = options.subjectMaskRectU0; atten.rectV0 = options.subjectMaskRectV0;
                atten.rectU1 = options.subjectMaskRectU1; atten.rectV1 = options.subjectMaskRectV1;
                attenPtr = &atten;
            }
            SelectiveBokehInputs bin;
            bin.subject = &subj;
            bin.atten = attenPtr;
            bin.depthMap = options.depthMap;
            bin.depthW = options.depthMapW;
            bin.depthH = options.depthMapH;
            bin.focusDepth = options.focusDepth;
            bin.bokehBlur = bokehBlurP;
            bin.bokehSpread = bokehSpreadP;
            bin.bokehBalls = bokehBallsP;
            applySelectiveBokehDiscRGB16(fullU16Bokeh.data(), int(W), int(srcH), bin);
            LOGI("runStageC: selective bokeh disc applied blur=%.3f depth=%dx%d focus=%.3f",
                 bokehBlurP, options.depthMapW, options.depthMapH, options.focusDepth);
        }
    }

    for (uint32_t y = 0; y < srcH; ++y) {
        if (std::fwrite(fullU16Bokeh.data() + size_t(y) * size_t(W) * 3u,
                        1, plan.bytesPerRow, fp) != plan.bytesPerRow) {
            r.error = "row write failed";
            std::fclose(fp); std::remove(outputPath.c_str());
            closeStageATiff(reader);
            return r;
        }
    }
    std::fflush(fp);
    std::fclose(fp);
    closeStageATiff(reader);

    auto t1 = std::chrono::steady_clock::now();
    r.success      = true;
    r.outWidth     = srcW;
    r.outHeight    = srcH;
    r.outputPath   = outputPath;
    r.durationMs   = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("runStageC: ok %ux%u in %lld ms → %s",
         srcW, srcH, (long long) r.durationMs, outputPath.c_str());
    return r;
}

// ─────────────────────────────────────────────────────────────────────────────
//  M9 — RGBA_8888 export path. Same kernel as runStageC; instead of writing
//  TIFF-16 to disk, we fill caller-allocated RGBA8 bytes (typically an
//  Android Bitmap's locked pixel buffer). Kotlin then runs Bitmap.compress
//  / HeifWriter on the resulting Bitmap to produce JPEG / WebP / PNG-8 /
//  HEIC. PNG-16 + AVIF live in an NDK follow-up.
// ─────────────────────────────────────────────────────────────────────────────
StageCResult runStageCToRGBA8(const std::string& stageATifPath,
                              const StageCOptions& options,
                              uint8_t* outPixels,
                              uint32_t outStride) {
    StageCResult r{};
    auto t0 = std::chrono::steady_clock::now();
    if (!outPixels) { r.error = "outPixels null"; return r; }
    if (!options.params || options.paramsCount <= 0) {
        r.error = "params missing";
        return r;
    }

    StageATiffReader* reader = openStageATiff(stageATifPath);
    if (!reader) { r.error = "openStageATiff failed"; return r; }
    const auto& hdr = getStageATiffHeader(reader);
    const uint32_t srcW = hdr.width;
    const uint32_t srcH = hdr.height;
    if (srcW == 0 || srcH == 0) {
        r.error = "zero source dims";
        closeStageATiff(reader);
        return r;
    }
    if (outStride < srcW * 4) {
        r.error = "outStride too small for RGBA_8888";
        closeStageATiff(reader);
        return r;
    }

    ApplyMacroParams params = ApplyMacroParams::fromFloatArray(
        options.params, options.paramsCount);
    // Wire tone curve into the kernel so it runs at the correct pipeline
    // position (after WB-trims, before gamutCompress+knee) — matching GL.
    params.toneCurveLut = options.toneCurveLut;
    ApplyMacroLut lut{};
    lut.data = options.lutData;
    lut.size = options.lutSize;
    for (int i = 0; i < 3; ++i) {
        lut.domainMin[i] = options.lutDomainMin[i];
        lut.domainMax[i] = options.lutDomainMax[i];
    }
    // M12.2c.2b — brush-mask layers for per-pixel multi-layer Mask tab.
    ApplyMacroMaskLayers maskLayers{};
    bool anyMaskLayer = false;
    for (int i = 0; i < ApplyMacroMaskLayers::kCount && i < StageCOptions::kMaskLayers; ++i) {
        maskLayers.layer[i].data = options.maskLayerData[i];
        maskLayers.layer[i].w    = options.maskLayerW[i];
        maskLayers.layer[i].h    = options.maskLayerH[i];
        if (options.maskLayerData[i]) anyMaskLayer = true;
    }
    const ApplyMacroMaskLayers* maskLayersPtr = anyMaskLayer ? &maskLayers : nullptr;
    // Subject mask — see runStageC for rationale.
    ApplyMacroSubjectMask subjectMaskStruct{};
    if (options.subjectMask != nullptr && options.subjectMaskSize > 0) {
        subjectMaskStruct.data  = options.subjectMask;
        subjectMaskStruct.w     = options.subjectMaskSize;
        subjectMaskStruct.h     = options.subjectMaskSize;
        subjectMaskStruct.rectU0 = options.subjectMaskRectU0;
        subjectMaskStruct.rectV0 = options.subjectMaskRectV0;
        subjectMaskStruct.rectU1 = options.subjectMaskRectU1;
        subjectMaskStruct.rectV1 = options.subjectMaskRectV1;
    }
    const ApplyMacroSubjectMask* subjectMaskPtr =
        (subjectMaskStruct.data != nullptr) ? &subjectMaskStruct : nullptr;
    LOGI("runStageCToRGBA8: src %ux%u stride=%u; lut=%dx%dx%d (%s) exposure=%.3f subjMask=%s",
         srcW, srcH, outStride, lut.size, lut.size, lut.size,
         params.lutEnabled && lut.size > 0 ? "active" : "off",
         params.exposure, subjectMaskPtr ? "present" : "null");

    // CLAHE pre-pass — see runStageC for rationale. Whole-image, so it must
    // resolve before the streaming row loop. Decode once into FP32, equalize
    // in place, then the loop reads from claheBuf.
    const bool claheOn = options.paramsCount > 144 && options.params[144] > 0.5f;
    const float jpegStr = (options.paramsCount > 458) ? options.params[458] : 0.0f;
    const float jpegClean = (options.paramsCount > 459) ? options.params[459] : 0.5f;
    const float jpegDet = (options.paramsCount > 460) ? options.params[460] : 0.5f;
    const bool jpegOn = jpegStr > 0.001f;
    const float lumaNR   = (options.paramsCount > 147) ? options.params[147] : 0.0f;
    const float chromaNR = (options.paramsCount > 148) ? options.params[148] : 0.0f;
    const float blueNR   = (options.paramsCount > 233) ? options.params[233] : 0.0f;
    const float redNR    = (options.paramsCount > 234) ? options.params[234] : 0.0f;
    const bool nrOn = lumaNR > 0.f || chromaNR > 0.f || blueNR > 0.f || redNR > 0.f;
    const DetailParams detail = readDetailParams(options.params, options.paramsCount);
    const bool detailOn = detail.any();
    // Film grain + wash-out (blue-noise) — per-pixel after grading, in lock-step
    // with the GL shader (same tile, same fixed reference grid).
    const GrainParams grainParams = readGrain(options.params, options.paramsCount);
    const bool grainOn = grainParams.any();
    const float grainAspect = (srcH > 0) ? float(srcW) / float(srcH) : 1.f;
    bool maskClarityOn = false;
    bool maskSharpnessOn = false;
    for (int i = 0; i < ApplyMacroMaskLayers::kCount; ++i) {
        const bool hasSource = maskLayers.layer[i].data || params.maskLayer[i].lumSpread > 0.f;
        if (!hasSource) continue;
        if (params.maskLayer[i].clarity   != 0.f) maskClarityOn   = true;
        if (params.maskLayer[i].sharpness != 0.f) maskSharpnessOn = true;
    }
    // Ambiance + FX blur Gaussian; softDiff is a dedicated plane baked into bloom.
    const float ambianceSliderRGBA = (options.paramsCount > 208) ? options.params[208] : 0.0f;
    const float ortonSliderRGBA    = (options.paramsCount > 209) ? options.params[209] : 0.0f;
    const float ambSubjRGBA  = (options.paramsCount > 231) ? options.params[231] : 0.0f;
    const float ambBgRGBA    = (options.paramsCount > 232) ? options.params[232] : 0.0f;
    const bool ambianceOnRGBA = (ambianceSliderRGBA != 0.f) || (ambSubjRGBA != 0.f) || (ambBgRGBA != 0.f);
    const bool ortonOnRGBA    = ortonSliderRGBA > 0.f;
    const float fxBlurStyleRGBA = (options.paramsCount > 363) ? options.params[363] : 0.f;
    const bool fxBlurOnRGBA     = fxBlurStyleRGBA > 0.5f;
    const float fxGlowStrengthRGBAEarly = (options.paramsCount > 372) ? options.params[372] : 0.f;
    const bool fxGlowOnRGBAEarly = fxGlowStrengthRGBAEarly > 0.f;
    const bool softDiffOnRGBA   = ortonOnRGBA || fxGlowOnRGBAEarly;
    const bool needBlurRGBA     = ambianceOnRGBA || fxBlurOnRGBA;
    // Bilateral denoise (GPU parity) — same sigma/threshold scaling as the
    // GLSL kBilateralFrag shader: sigma = slot147/100*5.0, threshold = slot148/100*0.1.
    const float bilateralSigmaRGBA     = lumaNR / 100.0f * 5.0f;
    const float bilateralThresholdRGBA = chromaNR / 100.0f * 0.1f;
    const bool bilateralOnRGBA = bilateralSigmaRGBA > 0.f || bilateralThresholdRGBA > 0.f;
    // Cubic chromatic aberration (GPU parity) — reads slots 352/353.
    const float aberStrengthRGBA   = (options.paramsCount > 352) ? options.params[352] : 0.0f;
    const float aberSeparationRGBA = (options.paramsCount > 353) ? options.params[353] : 0.0f;
    const bool cubicCAOnRGBA = aberStrengthRGBA != 0.0f;
    const bool preOn = claheOn || jpegOn || nrOn || detailOn || maskClarityOn || maskSharpnessOn || needBlurRGBA
                     || softDiffOnRGBA || bilateralOnRGBA || cubicCAOnRGBA;
    std::vector<float> preBuf;
    if (preOn) {
        preBuf.resize(size_t(srcW) * srcH * 3);
        for (uint32_t y = 0; y < srcH; ++y) {
            uint32_t stripIdx   = y / hdr.rowsPerStrip;
            uint32_t rowInStrip = y - stripIdx * hdr.rowsPerStrip;
            const uint16_t* strip = getStageATiffStrip(reader, stripIdx);
            const uint16_t* src   = strip + size_t(rowInStrip) * size_t(srcW) * 4;
            float* dst = preBuf.data() + size_t(y) * srcW * 3;
            for (uint32_t x = 0; x < srcW; ++x) {
                dst[x * 3 + 0] = halfToFloat(src[x * 4 + 0]);
                dst[x * 3 + 1] = halfToFloat(src[x * 4 + 1]);
                dst[x * 3 + 2] = halfToFloat(src[x * 4 + 2]);
            }
        }
        if (claheOn) {
            const float shBoost = (options.paramsCount > 145) ? options.params[145] : 0.0f;
            const float hiBoost = (options.paramsCount > 146) ? options.params[146] : 0.0f;
            raw_v3::applyClahe<float>(preBuf.data(), int(srcW), int(srcH),
                                      int(srcW), 3, shBoost, hiBoost);
            LOGI("runStageCToRGBA8: CLAHE pre-pass applied (sh=%.2f hi=%.2f)", shBoost, hiBoost);
        }
        if (jpegOn) {
            raw_v3::applyJpegDualRecon<float>(preBuf.data(), int(srcW), int(srcH),
                                              int(srcW), 3, jpegStr, jpegClean, jpegDet);
            LOGI("runStageCToRGBA8: JPEG Refine pre-pass applied (str=%.2f)", jpegStr);
        }
        if (nrOn) {
            raw_v3::applyNoiseReduction<float>(preBuf.data(), int(srcW), int(srcH),
                                               int(srcW), 3, lumaNR, chromaNR, blueNR, redNR,
                                               options.subjectMask,
                                               options.subjectMaskSize,
                                               options.subjectMaskSize);
            LOGI("runStageCToRGBA8: NR pre-pass applied (lum=%.2f chroma=%.2f blue=%.2f red=%.2f mask=%d)",
                 lumaNR, chromaNR, blueNR, redNR, options.subjectMask ? 1 : 0);
        }
        // Bilateral denoise — GPU parity (Req 15). Runs after legacy NR,
        // mirrors the GLSL kBilateralFrag circular-kernel bilateral exactly.
        if (bilateralOnRGBA) {
            smartDeNoise_cpu(preBuf.data(), int(srcW), int(srcH),
                            bilateralSigmaRGBA, bilateralThresholdRGBA);
        }
        if (detailOn) {
            raw_v3::applyDetail<float>(preBuf.data(), int(srcW), int(srcH),
                                       int(srcW), 3, detail,
                                       options.subjectMask, options.subjectMaskSize,
                                       options.subjectMaskSize);
            LOGI("runStageCToRGBA8: Detail pre-pass applied");
        }
        if (maskClarityOn) {
            applyMaskedClarity(preBuf.data(), int(srcW), int(srcH), params, maskLayers);
            LOGI("runStageCToRGBA8: masked clarity pre-pass applied");
        }
        if (maskSharpnessOn) {
            applyMaskedSharpness(preBuf.data(), int(srcW), int(srcH), params, maskLayers);
            LOGI("runStageCToRGBA8: masked sharpness pre-pass applied");
        }
        // Cubic chromatic aberration — GPU parity (Req 15). Runs after
        // spatial pre-passes (mirrors GPU: bilateral → uber-shader CA).
        if (cubicCAOnRGBA) {
            applyCubicCA_cpu(preBuf.data(), int(srcW), int(srcH),
                            aberStrengthRGBA, aberSeparationRGBA);
        }
    }

    // Ambiance/FX blur reference (not softDiff).
    std::vector<float> blurBuf;
    if (needBlurRGBA) {
        const int longSide = int(std::max(srcW, srcH));
        constexpr float kEditorRefLongSide = 2560.f;
        float editorPx = 4.0f;
        if (fxBlurOnRGBA) {
            const float amt = std::max({
                (options.paramsCount > 354) ? options.params[354] : 0.f,
                (options.paramsCount > 355) ? options.params[355] : 0.f,
                (options.paramsCount > 357) ? options.params[357] : 0.f,
                (options.paramsCount > 360) ? options.params[360] : 0.f});
            editorPx = 2.0f + amt * 14.0f;
        }
        int radius = std::max(2, int(std::round(editorPx * longSide / kEditorRefLongSide)));
        blurBuf.resize(size_t(srcW) * srcH * 3);
        LOGI("runStageCToRGBA8: blur enabled (ambiance=%d fxBlur=%d), src=%ux%u, radius=%dpx (editorPx=%.1f)",
             ambianceOnRGBA ? 1 : 0, fxBlurOnRGBA ? 1 : 0, srcW, srcH, radius, editorPx);
        auto tB0 = std::chrono::steady_clock::now();
        gaussianBlurSeparable(preBuf.data(), blurBuf.data(),
                              int(srcW), int(srcH), radius);
        auto tB1 = std::chrono::steady_clock::now();
        LOGI("runStageCToRGBA8: Gaussian blur for Ambiance/FX (r=%d) %lld ms",
             radius,
             (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tB1 - tB0).count());
        LOGI("runStageCToRGBA8: first blurPix tap = (%.4f, %.4f, %.4f)",
             blurBuf[0], blurBuf[1], blurBuf[2]);
    }

    std::vector<float> softDiffBufRGBA;
    if (softDiffOnRGBA) {
        const int longSide = int(std::max(srcW, srcH));
        constexpr float kEditorRefLongSide = 2560.f;
        const float userBloomRadius =
            (options.paramsCount > 205) ? options.params[205] : 0.f;
        const float editorPx = 2.0f + std::max(0.f, std::min(24.f, userBloomRadius)) * 1.15f;
        int radius = std::max(2, int(std::round(editorPx * longSide / kEditorRefLongSide)));
        softDiffBufRGBA.resize(size_t(srcW) * srcH * 3);
        gaussianBlurSeparable(preBuf.data(), softDiffBufRGBA.data(),
                              int(srcW), int(srcH), radius);
        LOGI("runStageCToRGBA8: softDiff Gaussian r=%dpx", radius);
    }

    // ── Karis pyramid bloom for Orton parity (RGBA8 path) ──
    std::vector<float> bloomBuf;
    const float fxGlowStrengthRGBA = (options.paramsCount > 372) ? options.params[372] : 0.f;
    const bool fxGlowOnRGBA = fxGlowStrengthRGBA > 0.f;
    if (ortonOnRGBA || fxGlowOnRGBA) {
        const float userBloomRadius = (options.paramsCount > 205) ? options.params[205] : 0.f;
        // Soft vertical-oval Karis; spread 0.22 (preview=export vs gles_renderer).
        const float baseTent = 1.0f + std::max(0.f, std::min(24.f, userBloomRadius)) * 0.22f;
        const int longSideRGBA = int(std::max(srcW, srcH));
        const float tentRadius = baseTent * float(longSideRGBA) / 1080.0f;
        const float thresholdLuma = 0.65f;
        const float mistTightness = (options.paramsCount > 447) ? options.params[447] : 0.55f;
        const float bloomShape = (options.paramsCount > 206) ? options.params[206] : 1.f;
        bloomBuf.resize(size_t(srcW) * srcH * 3);
        auto tBK0 = std::chrono::steady_clock::now();
        bool ok = false;
#ifndef RAZ_NO_EGL
        OffscreenSaveRenderer karis;
        ok = karis.init(int(srcW), int(srcH));
        if (ok) {
            ok = karis.computeKarisBloom(preBuf.data(), int(srcW), int(srcH),
                                          thresholdLuma, tentRadius,
                                          bloomBuf.data(), mistTightness, bloomShape);
            karis.release();
        }
#endif
        auto tBK1 = std::chrono::steady_clock::now();
        if (ok) {
            LOGI("runStageCToRGBA8: Karis bloom (GPU) %lld ms (tent=%.2f thr=%.2f)",
                 (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tBK1 - tBK0).count(),
                 tentRadius, thresholdLuma);
            if (!softDiffBufRGBA.empty()) {
                bakeSoftDiffusionIntoBloom(bloomBuf.data(), softDiffBufRGBA.data(),
                                           int(srcW) * int(srcH),
                                           ortonSliderRGBA, fxGlowStrengthRGBA);
            }
        } else {
            LOGE("runStageCToRGBA8: Karis bloom FAILED; fallback to Gaussian for Orton");
            r.karisBloomFallback = true;
            bloomBuf.clear();
        }
    }

    // Parallel kernel: each thread processes disjoint row ranges.
    // rowFloat is thread-local (declared inside the lambda) so there is
    // no write conflict. preBuf / blurBuf / bloomBuf are read-only here.
    // getStageATiffStrip is pure pointer arithmetic on an mmap — safe to
    // call from multiple threads simultaneously.
    parallelRows(int(srcH), [&](int yBegin, int yEnd) {
    std::vector<float> rowFloat(srcW * 3);
    for (int yi = yBegin; yi < yEnd; ++yi) {
        const uint32_t y = uint32_t(yi);
        if (preOn) {
            std::memcpy(rowFloat.data(),
                        preBuf.data() + size_t(y) * srcW * 3,
                        size_t(srcW) * 3 * sizeof(float));
        } else {
            // Locate this row in Stage A's strip layout (same code as runStageC).
            uint32_t stripIdx   = y / hdr.rowsPerStrip;
            uint32_t rowInStrip = y - stripIdx * hdr.rowsPerStrip;
            const uint16_t* strip = getStageATiffStrip(reader, stripIdx);
            const uint16_t* src   = strip + size_t(rowInStrip) * size_t(srcW) * 4;

            for (uint32_t x = 0; x < srcW; ++x) {
                rowFloat[x * 3 + 0] = halfToFloat(src[x * 4 + 0]);
                rowFloat[x * 3 + 1] = halfToFloat(src[x * 4 + 1]);
                rowFloat[x * 3 + 2] = halfToFloat(src[x * 4 + 2]);
            }
        }

        const float vNorm = (srcH > 1) ? float(y) / float(srcH - 1) : 0.5f;
        const float* blurRow  = needBlurRGBA      ? (blurBuf.data()  + size_t(y) * srcW * 3) : nullptr;
        const float* bloomRow = !bloomBuf.empty() ? (bloomBuf.data() + size_t(y) * srcW * 3) : nullptr;
        const float* blurFull = needBlurRGBA      ? blurBuf.data() : nullptr;
        for (uint32_t x = 0; x < srcW; ++x) {
            const float uNorm = (srcW > 1) ? float(x) / float(srcW - 1) : 0.5f;
            const float* blurPx  = blurRow  ? &blurRow [x * 3] : nullptr;
            const float* bloomPx = bloomRow ? &bloomRow[x * 3] : nullptr;
            applyMacroPixel(&rowFloat[x * 3], uNorm, vNorm,
                            params, lut.size > 0 ? &lut : nullptr,
                            subjectMaskPtr, maskLayersPtr,
                            blurPx, bloomPx,
                            blurFull, int(srcW), int(srcH));
            if (grainOn)
                applyFilmGrain(&rowFloat[x * 3], uNorm, vNorm, grainAspect, grainParams);
        }

        // Quantise to 8-bit RGBA_8888 (alpha = 0xFF). Stage A is already
        // gamma-encoded sRGB, so the float→byte conversion is direct.
        //
        // Banding fix: rounding `*255 + 0.5` produces stair-stepping on smooth
        // gradients (sky, OOF backgrounds) because adjacent float values round
        // to the same byte until the threshold flips. An 8×8 Bayer ordered
        // dither pattern (kBayer8 above) adds a sub-LSB offset that breaks up
        // the contour. Strength comes from slot 28 (`ditherStrength`, default
        // 1.0). When 0, falls back to the original deterministic rounding.
        uint8_t* dstRow = outPixels + size_t(y) * size_t(outStride);
        const float dStr = (options.paramsCount > 28) ? options.params[28] : 1.f;
        const bool ditherOn = dStr > 0.f;
        for (uint32_t x = 0; x < srcW; ++x) {
            const float fR = rowFloat[x * 3 + 0];
            const float fG = rowFloat[x * 3 + 1];
            const float fB = rowFloat[x * 3 + 2];
            uint8_t r8, g8, b8;
            if (ditherOn) {
                // dStr scales the sub-LSB shake; values < 1 weaken the effect
                // (lerp between deterministic round at 0 and full dither at 1).
                const int tx = int(x);
                const int ty = int(y);
                const float t = (float(kBayer8[(ty & 7) * 8 + (tx & 7)]) + 0.5f) / 64.0f;
                const float shake = (t - 0.5f) * dStr;  // ±0.5 LSB at full strength
                float xr = fR * 255.f + shake;
                float xg = fG * 255.f + shake;
                float xb = fB * 255.f + shake;
                if (xr < 0.f) xr = 0.f; else if (xr > 255.f) xr = 255.f;
                if (xg < 0.f) xg = 0.f; else if (xg > 255.f) xg = 255.f;
                if (xb < 0.f) xb = 0.f; else if (xb > 255.f) xb = 255.f;
                r8 = uint8_t(xr + 0.5f);
                g8 = uint8_t(xg + 0.5f);
                b8 = uint8_t(xb + 0.5f);
            } else {
                float fr = fR * 255.f + 0.5f;
                float fg = fG * 255.f + 0.5f;
                float fb = fB * 255.f + 0.5f;
                if (fr < 0.f) fr = 0.f; else if (fr > 255.f) fr = 255.f;
                if (fg < 0.f) fg = 0.f; else if (fg > 255.f) fg = 255.f;
                if (fb < 0.f) fb = 0.f; else if (fb > 255.f) fb = 255.f;
                r8 = uint8_t(fr); g8 = uint8_t(fg); b8 = uint8_t(fb);
            }
            dstRow[x * 4 + 0] = r8;
            dstRow[x * 4 + 1] = g8;
            dstRow[x * 4 + 2] = b8;
            dstRow[x * 4 + 3] = 0xFF;
        }
    }  // end row loop
    });  // end parallelRows
    closeStageATiff(reader);

    // --- Selective disc bokeh (Phase 2) --- Stage C mirror of kFragSrc ---
    // Must run BEFORE Laplacian so subject sharpen still gates on bg.
    {
        const float bokehBlurP = (options.paramsCount > 179) ? options.params[179] : 0.f;
        const float bokehBallsP = (options.paramsCount > 180) ? options.params[180] : 0.f;
        const float bokehSpreadP = (options.paramsCount > 181) ? options.params[181] : 0.f;
        if (bokehBlurP > 0.f && options.subjectMask != nullptr && options.subjectMaskSize > 0) {
            ApplyMacroSubjectMask subj{};
            subj.data = options.subjectMask;
            subj.w = options.subjectMaskSize;
            subj.h = (options.subjectMaskH > 0) ? options.subjectMaskH : options.subjectMaskSize;
            subj.rectU0 = options.subjectMaskRectU0; subj.rectV0 = options.subjectMaskRectV0;
            subj.rectU1 = options.subjectMaskRectU1; subj.rectV1 = options.subjectMaskRectV1;
            ApplyMacroSubjectMask atten{};
            const ApplyMacroSubjectMask* attenPtr = nullptr;
            if (options.attenMask != nullptr && options.attenMaskSize > 0) {
                atten.data = options.attenMask;
                atten.w = options.attenMaskSize;
                atten.h = (options.attenMaskH > 0) ? options.attenMaskH : options.attenMaskSize;
                atten.rectU0 = options.subjectMaskRectU0; atten.rectV0 = options.subjectMaskRectV0;
                atten.rectU1 = options.subjectMaskRectU1; atten.rectV1 = options.subjectMaskRectV1;
                attenPtr = &atten;
            }
            SelectiveBokehInputs bin;
            bin.subject = &subj;
            bin.atten = attenPtr;
            bin.depthMap = options.depthMap;
            bin.depthW = options.depthMapW;
            bin.depthH = options.depthMapH;
            bin.focusDepth = options.focusDepth;
            bin.bokehBlur = bokehBlurP;
            bin.bokehSpread = bokehSpreadP;
            bin.bokehBalls = bokehBallsP;
            applySelectiveBokehDiscRGBA8(outPixels, int(srcW), int(srcH), int(outStride), bin);
            LOGI("runStageCToRGBA8: selective bokeh disc applied blur=%.3f depth=%dx%d focus=%.3f",
                 bokehBlurP, options.depthMapW, options.depthMapH, options.focusDepth);
        }
    }

    // ── Laplacian post-sharpen (slot 379) — parity with GL preview ────────────
    // The GL renderer applies a 3×3 Laplacian sharpen as a final post-pass
    // (shader_sharpen.h). Stage C skipped it, making saved photos softer than
    // the preview at 100% pixel peep. We replicate the same kernel here on the
    // completed RGBA8 buffer. Cost: ~1 ms per megapixel on a mid-range SoC.
    //
    // GL kernel (uSharpness = slot 379 in [0,1]):
    //   fragColor = n[4]*9 - (n[0]+n[1]+n[2]+n[3]+n[5]+n[6]+n[7]+n[8])
    // where n[i] are the 9 texels of a 3×3 neighbourhood, offset by
    // uSharpness*texelSize. At uSharpness==1 that equals exactly one texel step
    // so offsets are just ±1 pixel (nearest-neighbour style).
    float sharpenAmount = (options.paramsCount > 379) ? options.params[379] : 0.f;
    const float bokehBlurP = (options.paramsCount > 179) ? options.params[179] : 0.f;
    const float filmicP    = (options.paramsCount > 451) ? options.params[451] : 0.f;
    const float oklabP     = (options.paramsCount > 452) ? options.params[452] : 0.f;
    // Mirror GL selective-bokeh: auto subject sharpen when bokeh/filmic look on.
    const bool selBokehSubjectSharpen =
        (bokehBlurP > 0.f || filmicP > 0.f || oklabP > 0.f) &&
        options.subjectMask != nullptr && options.subjectMaskSize > 0;
    // auto subject sharpen removed
    if (srcW >= 3 && srcH >= 3) {
        // Baseline boost so even sharpenAmount==0 gives subtle crispness matching
        // the GL preview's always-on Laplacian. Selective-bokeh subject gate
        // below zeros the blend on background so OOF stays soft.
        const float sBase = sharpenAmount + 0.1f;
        const int maskW = options.subjectMaskSize;
        const int maskH = (options.subjectMaskH > 0) ? options.subjectMaskH : maskW;
        const float* mask = options.subjectMask;
        const float ru0 = options.subjectMaskRectU0, rv0 = options.subjectMaskRectV0;
        const float ru1 = options.subjectMaskRectU1, rv1 = options.subjectMaskRectV1;

        // Float-precision copy to avoid rounding jitter during neighbourhood ops.
        std::vector<float> src(size_t(srcH) * outStride);
        for (size_t i = 0; i < size_t(srcH) * outStride; ++i)
            src[i] = float(outPixels[i]);

        parallelRows(int(srcH), [&](int yBegin, int yEnd) {
            for (int y = yBegin; y < yEnd; ++y) {
                const int ym1 = (y > 0)              ? y - 1 : 0;
                const int yp1 = (y < int(srcH) - 1)  ? y + 1 : int(srcH) - 1;
                for (uint32_t x = 0; x < srcW; ++x) {
                    const int xm1 = (x > 0)        ? int(x) - 1 : 0;
                    const int xp1 = (x < srcW - 1) ? int(x) + 1 : int(srcW) - 1;

                    auto px = [&](int py, int qx) -> const float* {
                        return src.data() + size_t(py) * outStride + size_t(qx) * 4;
                    };

                    for (int c = 0; c < 3; ++c) {
                        const float centre = px(y,   int(x))[c];
                        const float sum8   = px(ym1, xm1)[c] + px(ym1, int(x))[c] + px(ym1, xp1)[c]
                                           + px(y,   xm1)[c]                       + px(y,   xp1)[c]
                                           + px(yp1, xm1)[c] + px(yp1, int(x))[c] + px(yp1, xp1)[c];
                        const float laplacian = centre * 9.f - sum8;
                        // Edge-aware: stronger blend on edges, gentler in flat areas.
                        const float edgeMag = std::fabs(laplacian - centre);
                        const float weight  = (edgeMag > 8.f) ? sBase : sBase * 0.5f;
                        float blended = centre + weight * (laplacian - centre);
                        if (selBokehSubjectSharpen && mask && maskW > 0 && maskH > 0) {
                            const float u = (float(x) + 0.5f) / float(srcW);
                            const float v = (float(y) + 0.5f) / float(srcH);
                            const float mu = ru0 + (ru1 - ru0) * u;
                            const float mv = rv0 + (rv1 - rv0) * v;
                            int mx = int(mu * float(maskW - 1) + 0.5f);
                            int my = int(mv * float(maskH - 1) + 0.5f);
                            if (mx < 0) mx = 0; else if (mx > maskW - 1) mx = maskW - 1;
                            if (my < 0) my = 0; else if (my > maskH - 1) my = maskH - 1;
                            const float p = mask[my * maskW + mx];
                            const float gate = p * p;  // subjectGate(1)
                            blended = centre + (blended - centre) * gate;
                        }
                        const int   out8    = int(std::round(std::min(std::max(blended, 0.f), 255.f)));
                        outPixels[size_t(y) * outStride + size_t(x) * 4 + c] = uint8_t(out8);
                    }
                }
            }
        });
        LOGI("runStageCToRGBA8: Laplacian sharpen sBase=%.3f applied", sBase);
    }
    // ─────────────────────────────────────────────────────────────────────────

    auto t1 = std::chrono::steady_clock::now();
    r.success    = true;
    r.outWidth   = srcW;
    r.outHeight  = srcH;
    r.durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("runStageCToRGBA8: ok %ux%u in %lld ms", srcW, srcH, (long long) r.durationMs);
    return r;
}

// ─────────────────────────────────────────────────────────────────────────────
//  16-bit lossless encoder — reads the Stage C RGB16 TIFF intermediate,
//  applies crop + area-filter downscale + Laplacian post-sharpen (slot 379),
//  then writes either:
//    • 16-bit PNG  (outputFormat == 0) — hand-rolled, no libpng dependency
//    • 16-bit TIFF (outputFormat == 1) — reuses writeTiff16HeaderAndIFD
//
//  Called by Kotlin when the user picks PNG-16 or TIFF-16 output.  The 8-bit
//  ARGB_8888 Bitmap is still used for bokeh/fx/crop-geometry/watermark; this
//  function handles only the pixel-data path.
//
//  Parameters
//  ----------
//  rgb16TifPath   : Stage C intermediate TIFF (output of runStageC).
//  outputPath     : Destination file path (written by this function).
//  outputFormat   : 0 = PNG-16, 1 = TIFF-16.
//  cropX/Y/W/H    : Source crop rect (pixels, pre-resize).  0,0,0,0 = no crop.
//  dstW/dstH      : Resize target.  0,0 = keep post-crop dims.
//  sharpenAmount  : Laplacian strength [0,1] (slot 379 value).
//  resizeSharpen  : Post-resize unsharp-mask strength (mirrors Kotlin side).
//  watermarkRgba  : Optional ARGB_8888 pixel bytes to composite on top
//                   (same dims as dstW×dstH).  null = no watermark.
// ─────────────────────────────────────────────────────────────────────────────

// ── Minimal hand-rolled PNG-16 writer ─────────────────────────────────────
// Writes a 16-bit RGB PNG with no alpha.  No libpng: we only need
// IHDR + IDAT (uncompressed, filter-type 0) + IEND.
// zlib wrap: CMF=0x78 FLG=0x01 (deflate, no dict, check=compliant).
// IDAT uses non-compressed deflate blocks (BTYPE=00).
// CRC-32 uses the standard polynomial 0xEDB88320.
//
// Memory: one IDAT block per row (avoids >64 KB deflate block limit).
// Performance: ~250 ms for 20 MP at full 16-bit (pure I/O bound).

static const uint32_t kCrcTable[256] = {
    0x00000000,0x77073096,0xee0e612c,0x990951ba,0x076dc419,0x706af48f,
    0xe963a535,0x9e6495a3,0x0edb8832,0x79dcb8a4,0xe0d5e91b,0x97d2d988,
    0x09b64c2b,0x7eb17cbf,0xe7b82d09,0x90bf1d3b,0x1db71064,0x6ab020f2,
    0xf3b97148,0x84be41de,0x1adad47d,0x6ddde4eb,0xf4d4b551,0x83d385c7,
    0x136c9856,0x646ba8c0,0xfd62f97a,0x8a65c9ec,0x14015c4f,0x63066cd9,
    0xfa0f3d63,0x8d080df5,0x3b6e20c8,0x4c69105e,0xd56041e4,0xa2677172,
    0x3c03e4d1,0x4b04d447,0xd20d85fd,0xa50ab56b,0x35b5a8fa,0x42b2986c,
    0xdbbbc9d6,0xacbcb940,0x32d86ce3,0x45df5c75,0xdcd60dcf,0xabd13d59,
    0x26d930ac,0x51de003a,0xc8d75180,0xbfd06116,0x21b4f927,0x56b3c9b1,
    0xcfba9c0b,0xb8bda50f,0x2802b89e,0x5f058808,0xc60cd9b2,0xb10be924,
    0x2f6f7c87,0x58684c11,0xc1611dab,0xb6662d3d,0x76dc4190,0x01db7106,
    0x98d220bc,0xefd5102a,0x71b18589,0x06b6b51f,0x9fbfe4a5,0xe8b8d433,
    0x7807c9a2,0x0f00f934,0x9609a88e,0xe10e9818,0x7f6ad2bb,0x086d3d2d,
    0x91646c97,0xe6635c01,0x6b6b51f4,0x1c6c6162,0x856530d8,0xf262004e,
    0x6c0695ed,0x1b01a57b,0x8208f4c1,0xf50fc457,0x65b0d9c6,0x12b7e950,
    0x8bbeb8ea,0xfcb9887c,0x62dd1ddf,0x15da2d49,0x8cd37cf3,0xfbd44c65,
    0x4db26158,0x3ab551ce,0xa3bc0074,0xd4bb30e2,0x4adfa541,0x3dd895d7,
    0xa4d1c46d,0xd3d6f4fb,0x4369e96a,0x346ed9fc,0xad678846,0xda60b8d0,
    0x44042d73,0x33031de5,0xaa0a4c5f,0xdd0d7cc9,0x5005713c,0x270241aa,
    0xbe0b1010,0xc90c2086,0x5768b525,0x206f85b3,0xb966d409,0xce61e49f,
    0x5edef90e,0x29d9c998,0xb0d09822,0xc7d7a8b4,0x59b33d17,0x2eb40d81,
    0xb7bd5c3b,0xc0ba6cad,0xedb88320,0x9abfb3b6,0x03b6e20c,0x74b1d29a,
    0xead54739,0x9dd277af,0x04db2615,0x73dc1683,0xe3630b12,0x94643b84,
    0x0d6d6a3e,0x7a6a5aa8,0xe40ecf0b,0x9309ff9d,0x0a00ae27,0x7d079eb1,
    0xf00f9344,0x8708a3d2,0x1e01f268,0x6906c2fe,0xf762575d,0x806567cb,
    0x196c3671,0x6e6b06e7,0xfed41b76,0x89d32be0,0x10da7a5a,0x67dd4acc,
    0xf9b9df6f,0x8ebeeff9,0x17b7be43,0x60b08ed5,0xd6d6a3e8,0xa1d1937e,
    0x38d8c2c4,0x4fdff252,0xd1bb67f1,0xa6bc5767,0x3fb506dd,0x48b2364b,
    0xd80d2bda,0xaf0a1b4c,0x36034af6,0x41047a60,0xdf60efc3,0xa8670955,
    0x316658ef,0x466ae9ab,0xb40bbe37,0xc30c8ea1,0x5a05df1b,0x2d02ef8d,
};
static uint32_t pngCrc(const uint8_t* buf, size_t len, uint32_t crc = 0xFFFFFFFFu) {
    for (size_t i = 0; i < len; ++i) crc = kCrcTable[(crc ^ buf[i]) & 0xFF] ^ (crc >> 8);
    return crc ^ 0xFFFFFFFFu;
}
static void w32be(uint8_t* p, uint32_t v) {
    p[0]=v>>24; p[1]=(v>>16)&0xFF; p[2]=(v>>8)&0xFF; p[3]=v&0xFF;
}
static void w16be(uint8_t* p, uint16_t v) { p[0]=v>>8; p[1]=v&0xFF; }

// Write a 4-byte big-endian length, 4-byte type, data, 4-byte CRC chunk.
static void writePngChunk(FILE* fp, const char type[4],
                          const uint8_t* data, uint32_t len) {
    uint8_t hdr[8]; w32be(hdr, len); std::memcpy(hdr+4, type, 4);
    std::fwrite(hdr, 1, 8, fp);
    if (len > 0) std::fwrite(data, 1, len, fp);
    uint32_t crc = pngCrc(reinterpret_cast<const uint8_t*>(type), 4);
    if (len > 0) crc = pngCrc(data, len, crc ^ 0xFFFFFFFFu);
    uint8_t crcBuf[4]; w32be(crcBuf, crc); std::fwrite(crcBuf, 1, 4, fp);
}

// Write an IDAT chunk containing one PNG row: filter byte (0x00) +
// big-endian uint16 samples.  Non-compressed deflate block (BTYPE=00).
// Each row is its own deflate block so block size stays under 65535 bytes.
static void writePngIdatRow(FILE* fp, const uint16_t* row, uint32_t w,
                            bool isFirst, bool isLast,
                            uint32_t& adler_s1, uint32_t& adler_s2) {
    // Filter byte + w*3 uint16s big-endian.
    const uint32_t rowBytes = 1 + w * 6;
    std::vector<uint8_t> rowBuf(rowBytes);
    rowBuf[0] = 0;  // filter None
    for (uint32_t x = 0; x < w; ++x) {
        w16be(&rowBuf[1 + x*6 + 0], row[x*3+0]);
        w16be(&rowBuf[1 + x*6 + 2], row[x*3+1]);
        w16be(&rowBuf[1 + x*6 + 4], row[x*3+2]);
    }
    // Update Adler-32 for zlib checksum.
    for (uint8_t b : rowBuf) {
        adler_s1 = (adler_s1 + b) % 65521u;
        adler_s2 = (adler_s2 + adler_s1) % 65521u;
    }
    // Non-compressed deflate block: BFINAL | BTYPE=00.
    const uint8_t bfinal = isLast ? 0x01 : 0x00;
    const uint16_t blen  = static_cast<uint16_t>(rowBytes);
    const uint16_t bnlen = ~blen;
    // IDAT payload = [zlib_hdr?] + deflate_block + [adler?]
    // We write one IDAT chunk per row (each a self-contained deflate stream
    // with its own zlib header/trailer) to keep things simple.
    // zlib header: CMF=0x78 FLG: must satisfy (CMF*256+FLG) % 31 == 0 → FLG=0x01.
    std::vector<uint8_t> idat;
    if (isFirst) { idat.push_back(0x78); idat.push_back(0x01); }
    idat.push_back(bfinal);
    idat.push_back(blen & 0xFF); idat.push_back(blen >> 8);
    idat.push_back(bnlen & 0xFF); idat.push_back(bnlen >> 8);
    idat.insert(idat.end(), rowBuf.begin(), rowBuf.end());
    if (isLast) {
        // Adler-32 big-endian.
        idat.push_back((adler_s2 >> 8) & 0xFF);
        idat.push_back(adler_s2 & 0xFF);
        idat.push_back((adler_s1 >> 8) & 0xFF);
        idat.push_back(adler_s1 & 0xFF);
    }
    writePngChunk(fp, "IDAT", idat.data(), static_cast<uint32_t>(idat.size()));
}

// ── Area-filter (box) downscale on uint16 RGB ─────────────────────────────
// Averages a box of src pixels per output pixel.  Preserves 16-bit precision
// by accumulating in uint32 before dividing.  No gamma correction — Stage C
// output is already gamma-encoded sRGB so integer averaging is correct here
// (same domain as sRGB JPEG downscaling, acceptable for photo exports).
static std::vector<uint16_t> areaDownscale16(
        const uint16_t* src, uint32_t srcW, uint32_t srcH,
        uint32_t dstW, uint32_t dstH) {
    std::vector<uint16_t> dst(size_t(dstW) * dstH * 3);
    const float sx = float(srcW) / float(dstW);
    const float sy = float(srcH) / float(dstH);
    for (uint32_t dy = 0; dy < dstH; ++dy) {
        const uint32_t y0 = uint32_t(dy * sy);
        const uint32_t y1 = std::min(uint32_t(ceilf((dy + 1) * sy)), srcH);
        for (uint32_t dx = 0; dx < dstW; ++dx) {
            const uint32_t x0 = uint32_t(dx * sx);
            const uint32_t x1 = std::min(uint32_t(ceilf((dx + 1) * sx)), srcW);
            uint64_t sumR = 0, sumG = 0, sumB = 0, n = 0;
            for (uint32_t y = y0; y < y1; ++y)
                for (uint32_t x = x0; x < x1; ++x) {
                    const uint16_t* p = src + (size_t(y) * srcW + x) * 3;
                    sumR += p[0]; sumG += p[1]; sumB += p[2]; ++n;
                }
            if (n == 0) n = 1;
            dst[(size_t(dy) * dstW + dx) * 3 + 0] = uint16_t(sumR / n);
            dst[(size_t(dy) * dstW + dx) * 3 + 1] = uint16_t(sumG / n);
            dst[(size_t(dy) * dstW + dx) * 3 + 2] = uint16_t(sumB / n);
        }
    }
    return dst;
}

// ── Laplacian 3×3 post-sharpen on uint16 RGB ──────────────────────────────
static void laplacianSharpen16(std::vector<uint16_t>& buf,
                                uint32_t w, uint32_t h, float strength) {
    if (strength <= 0.f || w < 3 || h < 3) return;
    const std::vector<uint16_t> src = buf;  // copy
    parallelRows(int(h), [&](int yBegin, int yEnd) {
        for (int y = yBegin; y < yEnd; ++y) {
            const int ym1 = y > 0        ? y - 1 : 0;
            const int yp1 = y < int(h)-1 ? y + 1 : int(h)-1;
            for (uint32_t x = 0; x < w; ++x) {
                const int xm1 = x > 0      ? int(x)-1 : 0;
                const int xp1 = x < w-1    ? int(x)+1 : int(w)-1;
                auto px = [&](int py, int qx) -> const uint16_t* {
                    return src.data() + (size_t(py) * w + qx) * 3;
                };
                for (int c = 0; c < 3; ++c) {
                    const float ctr  = float(px(y,   int(x))[c]);
                    const float sum8 = float(px(ym1,xm1)[c]) + float(px(ym1,int(x))[c]) + float(px(ym1,xp1)[c])
                                     + float(px(y,  xm1)[c])                             + float(px(y,  xp1)[c])
                                     + float(px(yp1,xm1)[c]) + float(px(yp1,int(x))[c]) + float(px(yp1,xp1)[c]);
                    const float lap  = ctr * 9.f - sum8;
                    const float out  = ctr + strength * (lap - ctr);
                    const int   i16  = int(out + 0.5f);
                    buf[(size_t(y)*w + x)*3 + c] = uint16_t(i16 < 0 ? 0 : i16 > 65535 ? 65535 : i16);
                }
            }
        }
    });
}

// ── Read the Stage C RGB16 TIFF into a flat vector<uint16_t> ──────────────
// Returns empty on failure.
static std::vector<uint16_t> readRgb16Tiff(const std::string& path,
                                            uint32_t& outW, uint32_t& outH) {
    outW = outH = 0;
    FILE* fp = std::fopen(path.c_str(), "rb");
    if (!fp) return {};
    uint8_t head[16]; std::fread(head, 1, 16, fp);
    if (head[0] != 0x49 || head[1] != 0x49 ||
        (head[2]&0xFF) != 0x2B || (head[3]&0xFF) != 0x00) {
        std::fclose(fp); return {};
    }
    uint64_t ifd0Off = 0;
    std::memcpy(&ifd0Off, head+8, 8);
    std::fseek(fp, long(ifd0Off), SEEK_SET);
    uint64_t tagCount = 0; std::fread(&tagCount, 8, 1, fp);
    if (tagCount > 64) { std::fclose(fp); return {}; }
    uint32_t width=0, height=0;
    uint64_t firstStripOff=0, stripOffsetsField=0;
    uint64_t nStrips=0, rowsPerStrip=0;
    std::vector<uint8_t> entries(tagCount * 20);
    std::fread(entries.data(), 1, entries.size(), fp);
    for (uint64_t i=0; i<tagCount; ++i) {
        const uint8_t* e = entries.data() + i*20;
        uint16_t tag; std::memcpy(&tag, e, 2);
        uint64_t cnt; std::memcpy(&cnt, e+4, 8);
        uint64_t vol; std::memcpy(&vol, e+12, 8);
        switch(tag) {
            case 256: width  = uint32_t(vol); break;
            case 257: height = uint32_t(vol); break;
            case 273: stripOffsetsField=vol; nStrips=cnt; break;
            case 278: rowsPerStrip=vol; break;
        }
    }
    if (!width || !height || !nStrips) { std::fclose(fp); return {}; }
    if (!rowsPerStrip) rowsPerStrip = height;
    // Resolve strip offsets.
    std::vector<uint64_t> stripOffsets(nStrips);
    if (nStrips <= 1) {
        stripOffsets[0] = stripOffsetsField;
    } else {
        std::fseek(fp, long(stripOffsetsField), SEEK_SET);
        std::fread(stripOffsets.data(), 8, nStrips, fp);
    }
    // Read all pixels.
    const size_t rowBytes = size_t(width) * 3 * 2;  // RGB16
    std::vector<uint16_t> pixels(size_t(width) * height * 3);
    for (uint64_t s=0; s<nStrips; ++s) {
        const uint32_t y0 = uint32_t(s * rowsPerStrip);
        const uint32_t y1 = std::min(y0 + uint32_t(rowsPerStrip), height);
        std::fseek(fp, long(stripOffsets[s]), SEEK_SET);
        for (uint32_t y=y0; y<y1; ++y)
            std::fread(pixels.data() + size_t(y)*width*3, 1, rowBytes, fp);
    }
    std::fclose(fp);
    outW = width; outH = height;
    return pixels;
}

// ── Shared 16-bit post-processing (read, crop, resize, sharpen, watermark) ──
static std::vector<uint16_t> processRgb16TiffPixels(
        const std::string& rgb16TifPath,
        uint32_t cropX, uint32_t cropY, uint32_t cropW, uint32_t cropH,
        uint32_t dstW, uint32_t dstH,
        float sharpenAmount,
        const uint8_t* watermarkArgb, uint32_t wmW, uint32_t wmH,
        uint32_t& outW, uint32_t& outH) {
    outW = outH = 0;
    uint32_t srcW = 0, srcH = 0;
    std::vector<uint16_t> pixels = readRgb16Tiff(rgb16TifPath, srcW, srcH);
    if (pixels.empty()) return {};

    // 1) Crop.
    if (cropW > 0 && cropH > 0 && (cropX > 0 || cropY > 0 || cropW < srcW || cropH < srcH)) {
        cropX = std::min(cropX, srcW - 1);
        cropY = std::min(cropY, srcH - 1);
        cropW = std::min(cropW, srcW - cropX);
        cropH = std::min(cropH, srcH - cropY);
        std::vector<uint16_t> cropped(size_t(cropW) * cropH * 3);
        for (uint32_t y = 0; y < cropH; ++y)
            std::memcpy(cropped.data() + size_t(y) * cropW * 3,
                        pixels.data() + (size_t(cropY + y) * srcW + cropX) * 3,
                        cropW * 3 * 2);
        pixels = std::move(cropped);
        srcW = cropW; srcH = cropH;
    }

    // 2) Resize (area filter).
    if (dstW > 0 && dstH > 0 && (dstW != srcW || dstH != srcH)) {
        pixels = areaDownscale16(pixels.data(), srcW, srcH, dstW, dstH);
        srcW = dstW; srcH = dstH;
    }

    // 3) Laplacian post-sharpen (slot 379 parity with GL + 8-bit path).
    laplacianSharpen16(pixels, srcW, srcH, sharpenAmount);

    // 4) Composite 8-bit watermark (ARGB_8888, alpha pre-multiplied) on top.
    //    We down-convert only the watermark pixels to 16-bit and alpha-blend.
    if (watermarkArgb && wmW == srcW && wmH == srcH) {
        for (size_t i = 0; i < size_t(srcW) * srcH; ++i) {
            const uint8_t a = watermarkArgb[i * 4 + 3];
            if (a == 0) continue;
            const float alpha = a / 255.f;
            for (int c = 0; c < 3; ++c) {
                const uint8_t wm8 = watermarkArgb[i * 4 + c];  // R=0 G=1 B=2
                const float wm16f = wm8 * 257.f;  // 0..255 → 0..65535
                const float bg16f = float(pixels[i * 3 + c]);
                const float blended = bg16f * (1.f - alpha) + wm16f * alpha;
                const int v = int(blended + 0.5f);
                pixels[i * 3 + c] = uint16_t(v < 0 ? 0 : v > 65535 ? 65535 : v);
            }
        }
    }

    outW = srcW; outH = srcH;
    return pixels;
}

// ── Public entry point ────────────────────────────────────────────────────

Encode16BitResult encodeRgb16TiffTo16bit(
        const std::string& rgb16TifPath,
        const std::string& outputPath,
        int outputFormat,
        uint32_t cropX, uint32_t cropY, uint32_t cropW, uint32_t cropH,
        uint32_t dstW, uint32_t dstH,
        float sharpenAmount,
        const uint8_t* watermarkArgb, uint32_t wmW, uint32_t wmH,
        const uint8_t* iccProfile, size_t iccProfileSize) {
    Encode16BitResult r{};
    auto t0 = std::chrono::steady_clock::now();

    uint32_t srcW = 0, srcH = 0;
    std::vector<uint16_t> pixels = processRgb16TiffPixels(
        rgb16TifPath, cropX, cropY, cropW, cropH, dstW, dstH,
        sharpenAmount, watermarkArgb, wmW, wmH, srcW, srcH);
    if (pixels.empty()) { r.error = "read/process failed"; return r; }

    // 5) Encode.
    if (outputFormat == 1) {
        // ── 16-bit TIFF ──────────────────────────────────────────────────
        FILE* fp = std::fopen(outputPath.c_str(), "wb");
        if (!fp) { r.error = "fopen failed: " + outputPath; return r; }
        TiffPlan plan = planTiff16(srcW, srcH, iccProfileSize);
        if (!writeTiff16HeaderAndIFD(fp, srcW, srcH, plan, iccProfile, iccProfileSize)) {
            std::fclose(fp); std::remove(outputPath.c_str());
            r.error = "TIFF header write failed"; return r;
        }
        const size_t rowBytes = size_t(srcW) * 3 * 2;
        for (uint32_t y=0; y<srcH; ++y) {
            if (std::fwrite(pixels.data() + size_t(y)*srcW*3, 1, rowBytes, fp) != rowBytes) {
                std::fclose(fp); std::remove(outputPath.c_str());
                r.error = "TIFF row write failed"; return r;
            }
        }
        std::fflush(fp); std::fclose(fp);
    } else {
        // ── 16-bit PNG ───────────────────────────────────────────────────
        static const uint8_t kPngSig[8] = {137,80,78,71,13,10,26,10};
        FILE* fp = std::fopen(outputPath.c_str(), "wb");
        if (!fp) { r.error = "fopen failed: " + outputPath; return r; }
        std::fwrite(kPngSig, 1, 8, fp);
        // IHDR: width, height, bit_depth=16, color_type=2 (RGB), compress=0, filter=0, interlace=0
        uint8_t ihdr[13];
        w32be(ihdr+0, srcW); w32be(ihdr+4, srcH);
        ihdr[8]=16; ihdr[9]=2; ihdr[10]=0; ihdr[11]=0; ihdr[12]=0;
        writePngChunk(fp, "IHDR", ihdr, 13);
        // IDAT: one chunk per row (non-compressed deflate).
        uint32_t adler_s1=1, adler_s2=0;
        for (uint32_t y=0; y<srcH; ++y)
            writePngIdatRow(fp, pixels.data() + size_t(y)*srcW*3, srcW,
                            y==0, y==srcH-1, adler_s1, adler_s2);
        // IEND
        writePngChunk(fp, "IEND", nullptr, 0);
        std::fflush(fp); std::fclose(fp);
    }

    auto t1 = std::chrono::steady_clock::now();
    r.success    = true;
    r.outWidth   = srcW;
    r.outHeight  = srcH;
    r.outputPath = outputPath;
    r.durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("encodeRgb16TiffTo16bit: fmt=%d %ux%u in %lld ms → %s",
         outputFormat, srcW, srcH, (long long)r.durationMs, outputPath.c_str());
    return r;
}

// ── 16-bit HEIC/AVIF source: render processed RGB into an RGBA_F16 Bitmap ───
// [outPixels] is the locked pixel buffer of an Android Bitmap configured as
// ANDROID_BITMAP_FORMAT_RGBA_F16. Stride is in *bytes* per row.
Encode16BitResult encodeRgb16TiffToF16Bitmap(
        const std::string& rgb16TifPath,
        uint8_t* outPixels, uint32_t outStride,
        uint32_t cropX, uint32_t cropY, uint32_t cropW, uint32_t cropH,
        uint32_t dstW, uint32_t dstH,
        float sharpenAmount,
        const uint8_t* watermarkArgb, uint32_t wmW, uint32_t wmH) {
    Encode16BitResult r{};
    auto t0 = std::chrono::steady_clock::now();

    uint32_t srcW = 0, srcH = 0;
    std::vector<uint16_t> pixels = processRgb16TiffPixels(
        rgb16TifPath, cropX, cropY, cropW, cropH, dstW, dstH,
        sharpenAmount, watermarkArgb, wmW, wmH, srcW, srcH);
    if (pixels.empty()) { r.error = "read/process failed"; return r; }

    for (uint32_t y = 0; y < srcH; ++y) {
        uint16_t* dstRow = reinterpret_cast<uint16_t*>(outPixels + size_t(y) * outStride);
        const uint16_t* srcRow = pixels.data() + size_t(y) * srcW * 3;
        for (uint32_t x = 0; x < srcW; ++x) {
            dstRow[x * 4 + 0] = floatToHalf(srcRow[x * 3 + 0] / 65535.f);
            dstRow[x * 4 + 1] = floatToHalf(srcRow[x * 3 + 1] / 65535.f);
            dstRow[x * 4 + 2] = floatToHalf(srcRow[x * 3 + 2] / 65535.f);
            dstRow[x * 4 + 3] = floatToHalf(1.f);
        }
    }

    auto t1 = std::chrono::steady_clock::now();
    r.success    = true;
    r.outWidth   = srcW;
    r.outHeight  = srcH;
    r.durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("encodeRgb16TiffToF16Bitmap: %ux%u in %lld ms", srcW, srcH, (long long)r.durationMs);
    return r;
}

}  // namespace raw_v3
