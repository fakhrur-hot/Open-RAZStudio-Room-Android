/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 */

#include "stage_a.h"
#include "rcd_demosaic.h"
#include "lmmse_demosaic.h"
#include "dual_blend.h"
#include "tiff_mmap_io.h"
#include "dng_gainmap.h"
#include "raw_hdr_recovery.h"
#include "raw_shadow_recovery.h"
#include "raw_v3_clahe.h"
#include "rayxie_defringe.h"
#include "../lensfun_android.h"
#include "../../../../feature/ai-enhance/src/main/cpp/lmmse_enhance.h"

#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <vector>
#include <unistd.h>

#include "../libraw/libraw.h"

#define LOG_TAG "RawV3.StageA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

// Chromatic-aberration hook (see stage_a.h). Installed once by the Android JNI
// layer; stays null on desktop, where raw_decoder.cpp isn't compiled.
static CaHook g_caHook = nullptr;
void setCaHook(CaHook hook) { g_caHook = hook; }

namespace {

// ── float32 → IEEE 754 binary16 (round-to-nearest-even) ─────────────────────
inline uint16_t floatToHalf(float f) {
    uint32_t bits; std::memcpy(&bits, &f, 4);
    uint32_t sign = (bits >> 16) & 0x8000;
    int32_t  exp  = int32_t((bits >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = bits & 0x007FFFFF;

    if (((bits >> 23) & 0xFF) == 0xFF) {
        // Inf / NaN
        return uint16_t(sign | 0x7C00 | (mant ? 0x200 : 0));
    }
    if (exp >= 0x1F) {
        return uint16_t(sign | 0x7C00);   // overflow → Inf
    }
    if (exp <= 0) {
        if (exp < -10) return uint16_t(sign); // underflow → 0
        // Subnormal
        mant |= 0x00800000;
        uint32_t shift = uint32_t(14 - exp);
        uint32_t m = (mant >> shift) + ((mant >> (shift - 1)) & 1);  // round half up
        return uint16_t(sign | m);
    }
    uint32_t m = (mant + 0x00001000) >> 13;   // round half up
    if (m & 0x00000400) {                     // mantissa overflow → bump exp
        m = 0;
        ++exp;
        if (exp >= 0x1F) return uint16_t(sign | 0x7C00);
    }
    return uint16_t(sign | (uint32_t(exp) << 10) | (m & 0x3FF));
}

// LibRaw `dcraw_make_mem_image` with `output_color = 1` (sRGB output) returns
// chunky RGB16 ALREADY SCALED to the full uint16 range [0, 65535]. Convert
// straight to FP16 [0, 1].
//
// Previous bug (M2): the comment + code claimed BGR and divided by
// `raw->imgdata.color.maximum` (the *sensor* max, e.g. 13443 for Canon EOS 6D
// at the chosen WB). Both wrong:
//   * Swap → channels were inverted; bundled "channel-swap" test LUT looked
//     correct because it un-swapped the bug.
//   * Wrong divisor → output overshot 1.0 by ~1.2×, clipping highlights and
//     making the canvas look over-exposed.
void rgb16ToRgbaF16(const uint16_t* src, uint16_t* outRgba,
                    int width, int height) {
    constexpr float invMax = 1.0f / 65535.0f;
    const uint16_t one = floatToHalf(1.0f);
    const int N = width * height;
    for (int i = 0; i < N; ++i) {
        float r = float(src[i * 3 + 0]) * invMax;
        float g = float(src[i * 3 + 1]) * invMax;
        float b = float(src[i * 3 + 2]) * invMax;
        outRgba[i * 4 + 0] = floatToHalf(r);
        outRgba[i * 4 + 1] = floatToHalf(g);
        outRgba[i * 4 + 2] = floatToHalf(b);
        outRgba[i * 4 + 3] = one;
    }
}

// Derive the as-shot correlated colour temperature (CCT, Kelvin) from the
// actual per-shot WB multipliers (`cam_mul`) by inverse-lookup against the
// camera body's `WBCT_Coeffs` preset table.
//
// The table is 64 rows of [CCT, R, G1, B, G2] ordered by CCT (a per-body
// constant shipped in the Canon MakerNote — see libraw_types.h). The OLD
// code took WBCT_Coeffs[0][0] as the as-shot temperature, but row 0 is just
// the table's lowest-CCT PRESET entry, identical for every shot from that
// body — so the Temperature slider showed a fixed wrong anchor while the
// preview correctly applied the real cam_mul gains. Matching cam_mul's R/G
// and B/G ratios to the table recovers the true shot CCT.
//
// `cm` is the green-normalised as-shot multiplier vector actually used to
// white-balance the pixels (cam_mul, or the DNG-AsShotNeutral reciprocal).
// Returns 0 when the table is absent (e.g. non-Canon / DNG) so the caller
// keeps its existing 5500K fallback.
static int asShotKelvinFromWbGains(const float wbct[64][5], const float* cm) {
    if (!cm || cm[1] <= 0.f || cm[0] <= 0.f || cm[2] <= 0.f) return 0;
    if (wbct[0][0] <= 0.f) return 0;                 // no preset table
    const float rTarget = cm[0] / cm[1];
    const float bTarget = cm[2] / cm[1];
    // Nearest row in (R/G, B/G) ratio space.
    int best = -1;
    float bestD = 1e30f;
    int rows = 0;
    for (int i = 0; i < 64; ++i) {
        if (wbct[i][0] <= 0.f) break;                // table ends at first 0 CCT
        rows = i + 1;
        const float g1 = wbct[i][2];
        if (g1 <= 0.f) continue;
        const float rr = wbct[i][1] / g1;
        const float bb = wbct[i][3] / g1;
        const float d = (rr - rTarget) * (rr - rTarget) +
                        (bb - bTarget) * (bb - bTarget);
        if (d < bestD) { bestD = d; best = i; }
    }
    if (best < 0) return 0;
    // Refine with linear interpolation between the nearest row and whichever
    // adjacent row brackets bTarget (B/G varies monotonically with CCT).
    auto bgOf = [&](int i) -> float {
        const float g1 = wbct[i][2];
        return g1 > 0.f ? wbct[i][3] / g1 : 0.f;
    };
    const float cct0 = wbct[best][0];
    const float bg0  = bgOf(best);
    for (int step = -1; step <= 1; step += 2) {
        const int n = best + step;
        if (n < 0 || n >= rows || wbct[n][0] <= 0.f) continue;
        const float bgn = bgOf(n);
        const float denom = bgn - bg0;
        if (std::fabs(denom) < 1e-6f) continue;
        const float t = (bTarget - bg0) / denom;     // fraction toward neighbour
        if (t > 0.f && t <= 1.f) {
            return int(cct0 + t * (wbct[n][0] - cct0) + 0.5f);
        }
    }
    return int(cct0 + 0.5f);                          // outside table range
}

/**
 * LibRaw's COMPLETE black-level description, resolved into a uniform base plus
 * per-CFA-channel extras.
 *
 * LibRaw describes black in three places and its own `subtract_black()` uses
 * all of them:
 *   `color.black`          scalar base
 *   `cblack[0..3]`         per-CFA-channel offsets
 *   `cblack[4]`,`cblack[5]` dims of a repeating block, values in `cblack[6...]`
 *
 * The THIRD form is the one Android/phone DNGs use (`BlackLevelRepeatDim 2x2`),
 * and in that case `cblack[0..3]` and often `color.black` stay ZERO. Both v3
 * demosaic paths used to gate on `cblack[0..3]` alone, concluded "no black to
 * subtract", and left the full ~64 DN pedestal in the mosaic. White balance
 * then multiplied that pedestal by each channel's gain — and because blue's
 * gain is ~2x green's, a neutral pedestal came out MAGENTA on top of lifted,
 * hazy blacks. That is the "hazy magenta" phone-DNG cast (owner report
 * 2026-09-07); measured residual 54 DN against the file's stated 64.
 *
 * The AMaZE arm never showed it because it renders through LibRaw's
 * `dcraw_process()`, which calls `subtract_black()` for us.
 *
 * LibRaw's gate is `cblack[0..3] || (cblack[4] && cblack[5])` — mirror it
 * exactly. Call BEFORE `dcraw_process()`: `subtract_black()` ends with
 * `ZERO(C.cblack)`, so reading afterwards yields all zeros.
 */
struct BlackLevels {
    float base     = 0.f;            ///< uniform black, includes color.black
    float perCh[4] = {0, 0, 0, 0};   ///< extra black per CFA channel, >= 0
    bool  fromPattern = false;       ///< a cblack[4]x[5] block contributed
};

BlackLevels resolveBlackLevels(LibRaw* raw) {
    BlackLevels out;
    const auto& c = raw->imgdata.color;
    out.base = float(c.black);
    float ch[4] = {float(c.cblack[0]), float(c.cblack[1]),
                   float(c.cblack[2]), float(c.cblack[3])};

    const unsigned bh = c.cblack[4], bw = c.cblack[5];
    if (bh > 0 && bw > 0 && bh * bw <= 4096) {
        out.fromPattern = true;
        // Every block position lands on exactly one CFA colour; average the
        // values per colour (they are identical on every file seen so far, so
        // this is exact in practice and merely sane when they are not).
        float sum[4] = {0, 0, 0, 0};
        int   n[4]   = {0, 0, 0, 0};
        for (unsigned r = 0; r < bh; ++r) {
            for (unsigned col = 0; col < bw; ++col) {
                const int cc = raw->COLOR(int(r), int(col));
                if (cc < 0 || cc > 3) continue;
                sum[cc] += float(c.cblack[6 + r * bw + col]);
                n[cc]   += 1;
            }
        }
        for (int i = 0; i < 4; ++i) if (n[i] > 0) ch[i] += sum[i] / float(n[i]);
    }

    // Split into "uniform part everyone shares" + "per-channel excess", which
    // is the shape both kernels below can consume (they take a scalar black
    // and fold the excess into camMul).
    const float mn = std::min({ch[0], ch[1], ch[2], ch[3]});
    out.base += mn;
    for (int i = 0; i < 4; ++i) out.perCh[i] = ch[i] - mn;
    return out;
}

// Same as `rgb16ToRgbaF16` but reads B,G,R from `src` — used when the
// source buffer is the v3 RCD kernel's BGR16 output (the kernel inherits
// LibRaw's `dcraw_make_mem_image` channel order and we keep that here so
// the kernel stays byte-compatible with v2 testing).
void bgr16ToRgbaF16(const uint16_t* src, uint16_t* outRgba,
                    int width, int height) {
    constexpr float invMax = 1.0f / 65535.0f;
    const uint16_t one = floatToHalf(1.0f);
    const int N = width * height;
    for (int i = 0; i < N; ++i) {
        float b = float(src[i * 3 + 0]) * invMax;
        float g = float(src[i * 3 + 1]) * invMax;
        float r = float(src[i * 3 + 2]) * invMax;
        outRgba[i * 4 + 0] = floatToHalf(r);
        outRgba[i * 4 + 1] = floatToHalf(g);
        outRgba[i * 4 + 2] = floatToHalf(b);
        outRgba[i * 4 + 3] = one;
    }
}

// Neutralize green-deficient ("pink/magenta") blown highlights in Adobe
// Enhanced DNG decodes. Adobe's neural re-mosaic bakes a strong camera WB
// (camMul ≈ R 1.98, G 1.0, B 1.62); where the sky clips, every raw channel
// saturates together but the WB gains then drive R and B above G, so a pixel
// that should be neutral white comes out green-deficient → pink/magenta (with
// blue cores). LibRaw's highlight-recovery modes can't fix it because Adobe
// rewrites the white-level tag, so the clip point LibRaw uses is wrong.
//
// We fix it AFTER decode by desaturating near-white pixels toward a
// luma-preserving neutral. Two design points that matter (an earlier attempt
// failed on both):
//   • Threshold is a PERCENTILE of this image's own luma, not an absolute
//     cutoff. These decodes are dark (highlights sit well below 1.0), so a
//     fixed threshold like 0.88 misses the magenta entirely.
//   • A min-channel gate protects genuinely saturated bright colours (a bright
//     red flower has min(R,G,B)≈0); only broadly-bright pixels — i.e. clipped
//     whites that merely carry a cast — are pulled to neutral.
// Brightness is preserved (pull toward luma, never toward 0), so this can't
// burn or darken. RGB are chunky uint16 [0,65535] from dcraw_make_mem_image.
static void desaturateBlownHighlightsRgb16(uint16_t* rgb, int width, int height) {
    const size_t N = size_t(width) * size_t(height);
    if (N == 0 || rgb == nullptr) return;

    auto luma = [](float r, float g, float b) -> float {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;   // 0..65535
    };

    // 1) Luma histogram (256 bins over [0,65535]) → highlight knee percentiles.
    constexpr int BINS = 256;
    uint32_t hist[BINS] = {0};
    for (size_t i = 0; i < N; ++i) {
        float L = luma(float(rgb[i * 3 + 0]), float(rgb[i * 3 + 1]), float(rgb[i * 3 + 2]));
        int bin = int(L * (float(BINS) / 65536.0f));
        if (bin < 0) bin = 0; else if (bin >= BINS) bin = BINS - 1;
        ++hist[bin];
    }
    auto percentile = [&](double p) -> float {
        uint64_t target = uint64_t(p * 0.01 * double(N));
        uint64_t acc = 0;
        for (int b = 0; b < BINS; ++b) {
            acc += hist[b];
            if (acc >= target) return (float(b) + 0.5f) * (65535.0f / float(BINS));
        }
        return 65535.0f;
    };
    const float hiT    = percentile(98.0);    // start of the highlight ramp
    float       whiteT = percentile(99.9);     // full-strength point
    if (whiteT <= hiT + 1.0f) whiteT = hiT + 1.0f;
    const float invSpan  = 1.0f / (whiteT - hiT);
    const float minGate  = 0.5f * hiT;          // protect saturated hues
    constexpr float STRENGTH = 1.0f;            // full neutral at the blown end

    // 2) Correct: pull bright, broadly-lit pixels toward neutral gray at luma.
    size_t corrected = 0;
    for (size_t i = 0; i < N; ++i) {
        float r = float(rgb[i * 3 + 0]);
        float g = float(rgb[i * 3 + 1]);
        float b = float(rgb[i * 3 + 2]);
        float L = luma(r, g, b);
        if (L <= hiT) continue;
        float mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
        if (mn <= minGate) continue;            // saturated colour, not a white
        float t = (L - hiT) * invSpan; if (t > 1.0f) t = 1.0f;
        float s = t * STRENGTH;
        float nr = r + (L - r) * s;
        float ng = g + (L - g) * s;
        float nb = b + (L - b) * s;
        rgb[i * 3 + 0] = uint16_t(nr < 0.0f ? 0.0f : (nr > 65535.0f ? 65535.0f : nr + 0.5f));
        rgb[i * 3 + 1] = uint16_t(ng < 0.0f ? 0.0f : (ng > 65535.0f ? 65535.0f : ng + 0.5f));
        rgb[i * 3 + 2] = uint16_t(nb < 0.0f ? 0.0f : (nb > 65535.0f ? 65535.0f : nb + 0.5f));
        ++corrected;
    }
    LOGI("runStageA[AdobeHL]: highlight desat hiT=%.0f whiteT=%.0f minGate=%.0f "
         "(of 65535) corrected=%zu (%.3f%%) strength=%.2f",
         hiT, whiteT, minGate, corrected,
         100.0 * double(corrected) / double(N), double(STRENGTH));
}

// Apply EXIF orientation to a packed RGBA-FP16 buffer.
// Rotates/flips [src] (w×h, 4×uint16 per pixel) into [out], updating [outW]
// and [outH]. Supports the full EXIF orientation set:
//   0/1 = none, 2 = mirror horizontal, 3 = 180°, 4 = mirror vertical,
//   5 = transpose (mirror horiz + rotate 270° CW),
//   6 = rotate 90° CW, 7 = transverse (mirror vert + rotate 270° CW),
//   8 = rotate 270° CW.
// Codes 5/6/7/8 swap width and height.
// Returns true if a transform was applied (out populated), false for no-op.
bool applyFlipRgbaF16(const uint16_t* src, int w, int h, int flip,
                      std::vector<uint16_t>& out, int& outW, int& outH) {
    if (flip == 0 || flip == 1 || flip == -1) return false;
    out.resize(size_t(w) * h * 4);
    uint16_t* dst = out.data();
    auto copyPx = [&](int srcIdx, int dstIdx) {
        const uint16_t* s = src + size_t(srcIdx) * 4;
        uint16_t* d = dst + size_t(dstIdx) * 4;
        d[0] = s[0]; d[1] = s[1]; d[2] = s[2]; d[3] = s[3];
    };
    if (flip == 2) {
        // Mirror horizontal: (x,y) → (w-1-x, y).
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                copyPx(y * w + x, y * w + (w - 1 - x));
            }
        }
        outW = w; outH = h;
        return true;
    }
    if (flip == 3) {
        // 180°: reverse pixel order.
        const int n = w * h;
        for (int i = 0; i < n; ++i) copyPx(i, n - 1 - i);
        outW = w; outH = h;
        return true;
    }
    if (flip == 4) {
        // Mirror vertical: (x,y) → (x, h-1-y).
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                copyPx(y * w + x, (h - 1 - y) * w + x);
            }
        }
        outW = w; outH = h;
        return true;
    }
    // 5/6/7/8 transpose; output is h×w.
    outW = h; outH = w;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            int dx, dy;
            if (flip == 6) {        // 90° CW: (x,y) → (h-1-y, x)
                dx = h - 1 - y; dy = x;
            } else if (flip == 5 || flip == 8) {
                // 8 = rotate 270° CW (90° CCW): (x,y) → (y, w-1-x).
                // 5 = LibRaw's 90° CCW code, same mapping.
                dx = y; dy = w - 1 - x;
            } else if (flip == 7) { // transverse: (x,y) → (h-1-y, w-1-x)
                dx = h - 1 - y; dy = w - 1 - x;
            } else {
                continue;
            }
            copyPx(y * w + x, dy * outW + dx);
        }
    }
    return true;
}

// sRGB transfer curve. The Stage A cache stores GAMMA-ENCODED pixels in
// sRGB space (so the GLES shader can pass them through to the 8-bit
// framebuffer without an extra encode step), matching what
// `dcraw_process()` produces on the LibRaw path with our pinned
// gamm = [1/2.4, 12.92].
static inline float linearToSrgb(float v) {
    if (v <= 0.f)        return 0.f;
    if (v >= 1.f)        return 1.f;
    if (v <= 0.0031308f) return v * 12.92f;
    return 1.055f * std::pow(v, 1.f / 2.4f) - 0.055f;
}

// RCD output (camera-RGB, WB applied, linear) → sRGB-encoded RGBA FP16.
// Applies the 3×3 sub-matrix of LibRaw's `rgb_cam` (which converts camera
// native RGB to sRGB primaries in *linear* space) and then the sRGB
// transfer curve, mirroring what `dcraw_process()` does internally.
//
// `rgbCam33` is the row-major 3×3 sub-matrix. If LibRaw didn't populate
// rgb_cam (all-zero), the caller should pass an identity matrix and rely
// on the file already being close to sRGB primaries (true for many older
// Canon CR2 dailies, not true for Adobe DNGs).
void bgr16ToSrgbRgbaF16(const uint16_t* src, uint16_t* outRgba,
                        int width, int height,
                        const float rgbCam33[9]) {
    constexpr float invMax = 1.0f / 65535.0f;
    const uint16_t one = floatToHalf(1.0f);
    const float m00 = rgbCam33[0], m01 = rgbCam33[1], m02 = rgbCam33[2];
    const float m10 = rgbCam33[3], m11 = rgbCam33[4], m12 = rgbCam33[5];
    const float m20 = rgbCam33[6], m21 = rgbCam33[7], m22 = rgbCam33[8];
    const int N = width * height;
    // Highlight reconstruction is done in the RCD kernel via a dilated clip
    // mask + hard neutral replace (see rcd_demosaic.cpp). Here we just apply
    // the colour matrix + transfer.
    for (int i = 0; i < N; ++i) {
        // RCD writes BGR — read accordingly.
        const float b = float(src[i * 3 + 0]) * invMax;
        const float g = float(src[i * 3 + 1]) * invMax;
        const float r = float(src[i * 3 + 2]) * invMax;
        // camera-RGB → sRGB-linear via rgb_cam.
        float rs = m00 * r + m01 * g + m02 * b;
        float gs = m10 * r + m11 * g + m12 * b;
        float bs = m20 * r + m21 * g + m22 * b;
        // Apply sRGB transfer to match the LibRaw path's encoding.
        rs = linearToSrgb(rs);
        gs = linearToSrgb(gs);
        bs = linearToSrgb(bs);
        // ── Bright magenta-cast suppression (post-matrix sRGB space) ─────
        // The RCD clip-mask fix only catches blown (raw≥white) pixels. But
        // the rgb_cam matrix mixes channels, so a non-clipped specular
        // highlight on a shiny surface can come out green-deficient HERE
        // (R,B high, G low) → a pink/magenta speck — even though it wasn't
        // magenta in camera-RGB. We must correct in this post-matrix sRGB
        // space because that's where the cast actually appears. Lift G back
        // toward min(R,B) on bright pixels, luminance-preserving. Params
        // validated offline against IMG_3898.CR2's sRGB A.tif (R≈0.90 G≈0.56
        // B≈0.96): fixes the speck while touching 0 green-dominant foliage.
        {
            const float minRB = rs < bs ? rs : bs;
            const float deficit = minRB - gs;            // >0 ⇒ magenta
            // Aggressive thresholds (deficit > 0.02, luma onset 0.25) catch
            // even FAINT magenta casts that survive the camera's matrix — e.g.
            // IMG_3932.CR2 wing highlights only fully cleared once exposure
            // was pushed +0.6 EV (clipping to white hid the cast). The
            // luma-preserving rebalance only acts on green-deficient pixels
            // (deficit > 0), so green-dominant scenes (foliage, grass) where
            // G > min(R,B) have negative deficit and never trigger.
            if (deficit > 0.02f) {
                const float lm = 0.299f * rs + 0.587f * gs + 0.114f * bs;
                // Steeper ramp: full correction strength reached at luma 0.45
                // (was 0.80). Photos like IMG_3932.CR2 carry mild magenta at
                // luma ~0.55 — at the old ramp that was only ~55% corrected,
                // leaving residual pink that only cleared with +0.2 EV push.
                float bright = (lm - 0.25f) * (1.0f / 0.20f); // 0@0.25→1@0.45
                bright = bright < 0.f ? 0.f : (bright > 1.f ? 1.f : bright);
                float wm = (deficit - 0.02f) * 10.0f;
                wm = wm < 0.f ? 0.f : (wm > 1.f ? 1.f : wm);
                wm *= bright;
                if (wm > 0.001f) {
                    // Flat 3% overshoot. Bracketing: 5% flat → cyan plateau on
                    // brightest pixels; 1.5% × wm → pink returns AND a yellow-
                    // green halo at the transition zone (wm-scaling makes the
                    // overshoot uneven across the soft mask). 3% flat is the
                    // bisect: enough push to clear residual pink, but not so
                    // much that the brightest pixels flip cyan. The flat
                    // (non-wm-scaled) form avoids the halo.
                    const float target = minRB * 1.03f;
                    float ng = gs + (target - gs) * wm;
                    const float la = 0.299f * rs + 0.587f * ng + 0.114f * bs;
                    if (la > 1e-4f) {
                        const float s = lm / la;
                        rs *= s; ng *= s; bs *= s;
                    }
                    gs = ng;
                    if (rs > 1.f) rs = 1.f;
                    if (gs > 1.f) gs = 1.f;
                    if (bs > 1.f) bs = 1.f;
                }
            }
        }
        outRgba[i * 4 + 0] = floatToHalf(rs);
        outRgba[i * 4 + 1] = floatToHalf(gs);
        outRgba[i * 4 + 2] = floatToHalf(bs);
        outRgba[i * 4 + 3] = one;
    }
}

// "Safe Recovery": fades chroma toward neutral luminance wherever a pixel
// shows a magenta/pink color signature, so LibRaw's highlight=2 "blend"
// mode can still recover brightness/detail without leaving that cast in
// the output — see StageAOptions::highlightDesaturateStrength. Runs in
// place on the sRGB-encoded FP16 buffer via the same native __fp16
// arithmetic path applyClahe<__fp16> already uses below, so no manual bit
// conversion needed.
//
// Diagnosed 2026-08-21 against a real Canon 6D file (RAZ_LOG=1 trace):
// AMaZE arm renders this scene near-neutral (R/G≈1.02, B/G≈1.01) while the
// LMMSE arm — decoding the SAME raw mosaic with the SAME as-shot WB —
// comes out R/G≈1.60, B/G≈1.67 (R and B both well above G = magenta by
// definition) after the camera color matrix amplifies a smaller pre-matrix
// imbalance. This is a hue defect, not a brightness/clipping one: it shows
// up throughout the sky at ANY brightness level, not just near highlights.
// dual_blend.cpp biases toward LMMSE in smooth, low-detail regions (sky,
// clouds), which is why those areas show the cast while the high-detail
// window frame (AMaZE-favored) does not. NOT caused by double-applying
// white balance — the WB multiply happens exactly once per arm (confirmed
// against the log's own "mosaic WB'd means" line); forcing camMulDual to
// identity whenever use_camera_wb is set would disable LMMSE's WB on every
// normal photo, since use_camera_wb=1 is unconditional for AMaZE on the
// standard (non-Adobe-Enhanced) path — not a fix, a regression.
//
// Three independent triggers, combined with max() so none regress another:
//   1. Brightness term — this exact pixel is blown (mx > 0.85). Catches
//      uniformly-blown flat regions (a clipped sky with no local structure).
//      The 0.85→1.35 ramp (not 0.85→1.0) accounts for this buffer's FP16
//      headroom above "white"; a hard 1.0 ceiling would desaturate mildly-
//      bright, not-actually-clipped highlights too.
//   2. Edge term — this pixel sits in a 3×3 neighborhood that BOTH touches
//      a highlight (nbMax > 0.8) AND has high local contrast (nbMax-nbMin
//      large): the dual-blend disagreement right at a cloud/sky boundary.
//   3. Hue term (the one that actually matches the diagnosed bug) — this
//      pixel's R and B are BOTH elevated relative to G, i.e. min(R,B)-G is
//      large. That is the literal RGB definition of a magenta cast, and it
//      fires at any brightness — a mid-toned blue-sky pixel triggers this
//      even nowhere near clipping, unlike triggers 1 and 2.
// Box blur, one axis, via a sliding-window running sum — O(n) regardless of
// radius (as opposed to a naive O(n·r) neighborhood sum). Edge taps are
// dropped rather than mirrored/clamped, so `count` shrinks near borders and
// the average stays a true local mean instead of darkening/lightening edges.
static void boxBlur1D(const float* src, float* dst, int n, int radius) {
    if (radius <= 0) { std::copy(src, src + n, dst); return; }
    float sum = 0.f;
    int count = 0;
    for (int i = 0; i <= std::min(radius, n - 1); ++i) { sum += src[i]; ++count; }
    for (int i = 0; i < n; ++i) {
        dst[i] = sum / float(count);
        const int addIdx = i + radius + 1;
        const int remIdx = i - radius;
        if (addIdx < n)        { sum += src[addIdx]; ++count; }
        if (remIdx >= 0)       { sum -= src[remIdx]; --count; }
    }
}

void neutralizeHighlights(__fp16* rgba, int width, int height, float strength) {
    if (strength <= 0.f) return;
    const int64_t n = int64_t(width) * height;
    const size_t nPx = static_cast<size_t>(n);

    // One pass to precompute luma/max-channel so the neighborhood scan below
    // doesn't repeatedly redo fp16→float conversions and the luma dot product.
    std::vector<float> luma(nPx), maxc(nPx);
    for (int64_t i = 0; i < n; ++i) {
        float r = float(rgba[i * 4 + 0]);
        float g = float(rgba[i * 4 + 1]);
        float b = float(rgba[i * 4 + 2]);
        luma[i] = 0.299f * r + 0.587f * g + 0.114f * b;
        maxc[i] = std::max(r, std::max(g, b));
    }

    // Pass 1: compute the trigger mask (same brightness/edge/hue terms as
    // before) into `mask`, and desaturate in place. Kept separate from the
    // soften pass below because softening needs a BLURRED version of the
    // whole image, which in turn needs the desaturated colors already
    // written (so the blur doesn't re-introduce the magenta cast it's
    // averaging in from neighboring un-masked pixels).
    std::vector<float> mask(nPx, 0.f);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int64_t i = int64_t(y) * width + x;

            float r0 = float(rgba[i * 4 + 0]);
            float g0 = float(rgba[i * 4 + 1]);
            float b0 = float(rgba[i * 4 + 2]);
            // Hue term: normalise by luma so this fires the same way on a
            // dim shadow-toned magenta pixel as a bright one — an absolute
            // (R,B)-G difference would under-react on darker sky pixels.
            const float magentaSignature = (std::min(r0, b0) - g0) / std::max(luma[i], 0.05f);
            const float hueTerm = std::max(0.f, std::min(1.f, magentaSignature * 3.0f));

            float nbMax = maxc[i];
            float nbMin = luma[i];
            for (int dy = -1; dy <= 1; ++dy) {
                int yy = y + dy;
                if (yy < 0 || yy >= height) continue;
                for (int dx = -1; dx <= 1; ++dx) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= width) continue;
                    const int64_t j = int64_t(yy) * width + xx;
                    nbMax = std::max(nbMax, maxc[j]);
                    nbMin = std::min(nbMin, luma[j]);
                }
            }
            if (nbMax <= 0.8f && hueTerm <= 1e-3f) continue;  // nothing to fix here

            const float brightnessTerm = std::max(0.f, std::min(1.f, (maxc[i] - 0.85f) * 2.0f));
            const float contrast       = nbMax - nbMin;
            const float edgeFactor     = std::min(1.f, contrast / 0.35f);
            const float proximity      = std::max(0.f, std::min(1.f, (nbMax - 0.8f) / 0.3f));
            const float edgeTerm       = edgeFactor * proximity;

            const float t = std::max({brightnessTerm, edgeTerm, hueTerm}) * strength;
            if (t <= 1e-3f) continue;
            mask[i] = t;

            const float y_ = luma[i];
            const float satScale = 1.f - t;
            rgba[i * 4 + 0] = (__fp16)(y_ + (r0 - y_) * satScale);
            rgba[i * 4 + 1] = (__fp16)(y_ + (g0 - y_) * satScale);
            rgba[i * 4 + 2] = (__fp16)(y_ + (b0 - y_) * satScale);
        }
    }

    // Pass 2: soften the SAME flagged pixels with a large-radius blur.
    // dual_blend.cpp's AMaZE/LMMSE weight is computed on an 80×80 tile grid
    // and only Gaussian-blurred at radius 20 — in a genuinely flat, low-
    // contrast region (a smooth cloud/sky), per-tile noise can push
    // neighboring tiles' auto-picked blend weight apart enough to leave a
    // visible blotchy/mottled texture that survives the hue fix above (that
    // fix corrects COLOR, not the underlying tile-seam BRIGHTNESS pattern).
    // Skip entirely if nothing was flagged — the common case (no highlight
    // cast anywhere) shouldn't pay for two more full-image float buffers.
    bool anyMasked = false;
    for (size_t i = 0; i < nPx && !anyMasked; ++i) anyMasked = mask[i] > 1e-3f;
    if (!anyMasked) return;

    constexpr int kSoftenRadius = 14;  // ~28px window; wide enough to bridge 80px tiles' worth of local variation without eating real detail, since it's only blended in where `mask` is already > 0.
    std::vector<float> chan(nPx), tmp(nPx), blurred(nPx);
    const size_t heightSz = static_cast<size_t>(height);
    std::vector<float> colSrc(heightSz), colDst(heightSz);  // reused across columns
    for (int ch = 0; ch < 3; ++ch) {
        for (int64_t i = 0; i < n; ++i) chan[i] = float(rgba[i * 4 + ch]);
        for (int y = 0; y < height; ++y) {
            boxBlur1D(&chan[size_t(y) * width], &tmp[size_t(y) * width], width, kSoftenRadius);
        }
        // Transpose-free vertical pass: blur each column by walking with stride=width.
        for (int x = 0; x < width; ++x) {
            for (int y = 0; y < height; ++y) colSrc[y] = tmp[size_t(y) * width + x];
            boxBlur1D(colSrc.data(), colDst.data(), height, kSoftenRadius);
            for (int y = 0; y < height; ++y) blurred[size_t(y) * width + x] = colDst[y];
        }
        for (int64_t i = 0; i < n; ++i) {
            if (mask[i] <= 1e-3f) continue;
            const float orig = chan[i];
            rgba[i * 4 + ch] = (__fp16)(orig + (blurred[i] - orig) * mask[i]);
        }
    }
}

}  // anonymous namespace

StageAMetadata runStageA(
    const std::string& rawFilePath,
    const std::string& outTifPath,
    const StageAOptions& options) {

    StageAMetadata meta;
    auto t0 = std::chrono::steady_clock::now();

    // ── LibRaw open ──────────────────────────────────────────────────────────
    auto raw = std::make_unique<LibRaw>();

    int ret = raw->open_file(rawFilePath.c_str());
    if (ret != LIBRAW_SUCCESS) {
        meta.errorMessage = std::string("open_file: ") + libraw_strerror(ret);
        LOGE("runStageA: %s", meta.errorMessage.c_str());
        return meta;
    }

    // Capture EXIF before unpack so a failure later still leaves metadata.
    {
        const auto& idata = raw->imgdata.idata;
        const auto& other = raw->imgdata.other;
        const auto& lens  = raw->imgdata.lens;
        const auto& color = raw->imgdata.color;
        meta.cameraMake   = idata.make;
        meta.cameraModel  = idata.model;
        meta.lensMake     = lens.LensMake;
        meta.lensModel    = lens.Lens;
        meta.iso          = int(other.iso_speed);
        meta.shutterSpeed = other.shutter;
        meta.aperture     = other.aperture;
        meta.focalLength  = other.focal_len;
        // Canon lens ID from MakerNotes (LIBRAW_LENS_NOT_SET → 0)
        if (lens.makernotes.LensID != LIBRAW_LENS_NOT_SET) {
            meta.lensId = int(lens.makernotes.LensID);
        }
        // As-shot color temperature — inverse-lookup the real cam_mul gains
        // against the body's WBCT_Coeffs preset table. (The old code used
        // WBCT_Coeffs[0][0], the table's lowest-CCT preset row — a body
        // constant, NOT the shot's temperature — which made the WB slider's
        // anchor wrong even though the preview applied the correct gains.)
        int asShotCct = asShotKelvinFromWbGains(color.WBCT_Coeffs, color.cam_mul);
        if (asShotCct > 0) {
            meta.colorTemperature = asShotCct;
        } else {
            // Deliberately report NOTHING rather than WBCT_Coeffs[0][0]: that is
            // the lowest-CCT row of the body's preset table, a camera constant
            // (often ~2000-2500 K) with no relation to this shot. Handing it to
            // the UI put the Temperature slider's anchor thousands of Kelvin off
            // on every body whose cam_mul could not be inverted (2026-09-07).
            // 0 means "unknown"; the caller decides how to anchor.
            meta.colorTemperature = 0;
        }
        // Capture date/time. LibRaw exposes it as a time_t in other.timestamp
        // (NOT other.desc — that's the image DESCRIPTION field and is normally
        // empty, which is why the date used to come back blank). Format it in
        // EXIF's canonical "YYYY:MM:DD HH:MM:SS" so the watermark shows it.
        if (other.timestamp != 0) {
            std::time_t ts = static_cast<std::time_t>(other.timestamp);
            std::tm tmv{};
#if defined(_WIN32)
            localtime_s(&tmv, &ts);
#else
            localtime_r(&ts, &tmv);
#endif
            char buf[32];
            if (std::strftime(buf, sizeof(buf), "%Y:%m:%d %H:%M:%S", &tmv) > 0)
                meta.dateTimeOriginal = buf;
            else
                meta.dateTimeOriginal.clear();
        } else {
            meta.dateTimeOriginal.clear();
        }
        LOGI("runStageA EXIF: make='%s' model='%s' iso=%d shutter=%.4f aperture=%.2f focal=%.1f",
             idata.make, idata.model, int(other.iso_speed),
             other.shutter, other.aperture, other.focal_len);
    }

    // ── Decode params per Plan.md FR-1.1 ────────────────────────────────────
    // We pin a known-good processing config:
    //   • use_camera_wb         (camera white balance baked into Stage A)
    //   • output_color = 1      (sRGB primaries — gamut conversion lives later)
    //   • output_bps  = 16
    //   • gamm[0..1] = 1.0, 1.0 (LINEAR — no γ-encode in Stage A)
    //   • no_auto_bright = 1    (no auto-stretch; we keep the linear range)
    //   • highlight = 0         (clip; we don't recover at this stage)
    //   • user_qual = options.demosaicAlgorithm
    // White-balance source. Three mutually-exclusive choices map to LibRaw
    // params (camera embedded WB / auto-from-image / daylight-neutral).
    switch (options.wbSource) {
        case 1:  // Auto
            raw->imgdata.params.use_camera_wb = 0;
            raw->imgdata.params.use_auto_wb   = 1;
            break;
        case 2:  // Daylight neutral (no per-channel scaling)
            raw->imgdata.params.use_camera_wb = 0;
            raw->imgdata.params.use_auto_wb   = 0;
            raw->imgdata.params.user_mul[0]   = 1.f;
            raw->imgdata.params.user_mul[1]   = 1.f;
            raw->imgdata.params.user_mul[2]   = 1.f;
            raw->imgdata.params.user_mul[3]   = 1.f;
            break;
        case 0:  // Camera (default)
        default:
            raw->imgdata.params.use_camera_wb = 1;
            raw->imgdata.params.use_auto_wb   = 0;
            break;
    }
    raw->imgdata.params.output_color  = 1;        // sRGB primaries
    raw->imgdata.params.output_bps    = 16;
    // sRGB transfer (2.4 / slope=12.92). Pure linear (gamm=1.0) made the
    // autobright stretch ineffective — the canvas rendered ~black because
    // sensor values clustered in the bottom 5%. With sRGB gamma in Stage A,
    // the FP16 cache holds GAMMA-ENCODED pixels (range stretched to fill
    // [0,1]), the GLES shader can sample them and pass-through to the 8-bit
    // sRGB framebuffer with no extra encode. Linear-domain math (exposure,
    // contrast, etc.) in the shader operates on these gamma-encoded samples;
    // the perceptual result is identical to the previous design at slider=0,
    // and remains physically meaningful for the range of values cameras
    // actually capture.
    raw->imgdata.params.gamm[0]       = 1.0 / 2.4;
    raw->imgdata.params.gamm[1]       = 12.92;
    raw->imgdata.params.no_auto_bright = 0;       // autobright ON
    // RAZAMaZE+LMMSE is the only demosaic path (-3). Any other value that
    // arrives (e.g. from a legacy sidecar) is treated as -3.
    // DEAD VARIABLE — declared here and never read anywhere in this file
    // (verified 2026-08-18: `grep useDualVng` returns only this line). Setting
    // it false changes NOTHING; the dual AMaZE+LMMSE path runs unconditionally
    // and is instead selected by DATA — whether `rawMosaic` came back non-null
    // (see the "AMaZE-only" branch further down). Do not use this as a switch
    // to disable the dual path for diagnostics; it has no effect.
    const bool useDualVng = true;
    raw->imgdata.params.user_qual = 3; // placeholder; overridden inside DualVNG path
    // Highlight reconstruction mode 2 = "blend": when one or two channels
    // clip but the third is still in range, LibRaw blends the unclipped
    // channel to neutral grey in the clipped region. Without this (mode 0
    // = hard clip), saturated highlights show magenta/purple/green fringes
    // along their edges because the clipped channels demosaic from
    // incorrect neighbour samples. Verified on Canon EOS 6D 2026-05-25.
    //
    // Mode 1 (unclip) preserves the saturated values verbatim, leading to
    // the same fringe issue. Mode 3..9 (rebuild) is the more sophisticated
    // option but is much slower and overkill for our M5.5 baseline; we'll
    // expose it as a Stage A option if users need lossless highlights.
    // Highlight reconstruction mode (see header comment in stage_a.h for
    // the semantics of each LibRaw value). Default = 2 (blend).
    raw->imgdata.params.highlight     = options.highlightMode;
    // adjust_maximum_thr: tells LibRaw to use the actual in-image maximum
    // (rather than the camera-reported sensor cap) when that actual value
    // exceeds thr×sensor_max. Without this, per-channel WB multipliers can
    // push near-white pixels past the clipping boundary before reconstruction
    // has a chance to act. 0.75 matches dcraw; 0.0 disables (no protection).
    raw->imgdata.params.adjust_maximum_thr = options.adjustMaximumThr;
    // whiteLevelDelta → LibRaw user_sat on every dcraw_process arm (AMaZE /
    // DHT). Custom RCD/LMMSE already folds the same delta into their white
    // level for norm; without user_sat, AMaZE ignored the slider entirely.
    // Only when delta != 0: user_sat overrides C.maximum *after* adjust_maximum(),
    // so leaving it at -1 preserves adjust_maximum_thr when the slider is idle.
    // HDR/GainMap already use color.maximum + whiteLevelDelta on the mosaic.
    if (options.whiteLevelDelta != 0.f) {
        float wl = float(raw->imgdata.color.maximum) + options.whiteLevelDelta;
        if (wl < 1.f)      wl = 1.f;
        if (wl > 65535.f)  wl = 65535.f;
        raw->imgdata.params.user_sat = int(wl + (wl >= 0.f ? 0.5f : -0.5f));
    }
    LOGI("runStageA: LibRaw HL cfg highlightMode=%d adjust_maximum_thr=%.3f "
         "maximum=%d black=%d whiteDelta=%.0f blackDelta=%.0f desat=%.2f user_sat=%d",
         options.highlightMode, options.adjustMaximumThr,
         int(raw->imgdata.color.maximum), int(raw->imgdata.color.black),
         options.whiteLevelDelta, options.blackLevelDelta,
         options.highlightDesaturateStrength,
         int(raw->imgdata.params.user_sat));
    // user_flip = -1 keeps LibRaw's own demosaic output in raw sensor
    // orientation. We apply the EXIF orientation (sizes.flip) ourselves to
    // the final RGBA buffer below, AFTER both the LibRaw and RAZAmaze (RCD)
    // branches produce pixels — the RCD path operates on the raw mosaic and
    // never sees LibRaw's flip, so doing it uniformly post-demosaic is the
    // single source of truth and avoids the two paths disagreeing.
    raw->imgdata.params.user_flip     = -1;       // we rotate post-demosaic

    // Pre-demosaic exposure shift. LibRaw's exp_correc gate must be 1 for
    // exp_shift/exp_preser to take effect. exp_shift is a linear multiplier
    // in [0.25, 8.0] mapping roughly to ±3 EV; we convert from "EV stops"
    // so the UI can present it as -2..+3 EV. exp_preser=1.0 keeps the
    // highlight knee in place (LibRaw default 0.0 = no protection).
    if (options.exposureShift != 0.f) {
        const float clamped = options.exposureShift < -2.f ? -2.f
                            : (options.exposureShift >  3.f ?  3.f
                            :  options.exposureShift);
        raw->imgdata.params.exp_correc = 1;
        raw->imgdata.params.exp_shift  = std::pow(2.f, clamped);
        raw->imgdata.params.exp_preser = 1.f;
    }

    // FBDD chroma+luma noise reduction (pre-demosaic, LibRaw-internal).
    // 0 = off, 1 = light pass, 2 = full pass. Independent of the
    // wavelet/median NR below.
    raw->imgdata.params.fbdd_noiserd = options.fbddNoise;

    if (options.nrEnabled) {
        raw->imgdata.params.threshold = float(options.nrLuma);  // wavelet luma NR
        // Chroma NR via two-pass median filter (LibRaw param):
        raw->imgdata.params.med_passes = options.nrChroma > 50 ? 2 : 1;
    }

    ret = raw->unpack();
    if (ret != LIBRAW_SUCCESS) {
        meta.errorMessage = std::string("unpack: ") + libraw_strerror(ret);
        LOGE("runStageA: %s", meta.errorMessage.c_str());
        return meta;
    }

    // ── DNG lens-shading (OpcodeList2 GainMap) ───────────────────────────
    // Android Camera2 DNGs ship shading correction as opcodes and leave the
    // Bayer data uncorrected; LibRaw executes no DNG opcodes without the Adobe
    // SDK. Skipping them cost more than dark corners: the per-plane gains
    // differ (measured R 1.589 vs G 1.661), so red stayed relatively strong
    // away from centre and the picture read PINK (owner report 2026-09-07).
    // Runs before every other RAW-domain pass so recovery/demosaic see a
    // shading-corrected mosaic.
    if (raw->imgdata.idata.dng_version != 0 && raw->imgdata.rawdata.raw_image) {
        // Use the RESOLVED black, not color.black: on these files color.black
        // is 0 and the real level lives in the cblack[4]x[5] block, so the old
        // code gained the PEDESTAL along with the signal (up to 3.5x at the
        // corners), adding its own lifted-black haze on top.
        const BlackLevels blGm = resolveBlackLevels(raw.get());
        const auto& color = raw->imgdata.color;
        const auto  gm = applyDngGainMaps(
            rawFilePath,
            raw->imgdata.rawdata.raw_image,
            raw->imgdata.sizes.raw_width,
            raw->imgdata.sizes.raw_height,
            int(raw->imgdata.sizes.left_margin),
            int(raw->imgdata.sizes.top_margin),
            blGm.base + options.blackLevelDelta,
            float(color.maximum) + options.whiteLevelDelta);
        // Log EVERY outcome, not just failures-with-maps: the first cut stayed
        // silent when findOpcodeList2() came up empty, which cost an entire
        // debugging round guessing whether the code had run at all.
        if (!gm.applied) {
            LOGE("runStageA: DNG GainMaps not applied (found=%d, %s)",
                 gm.mapsFound, gm.note.c_str());
        }
    }

    // ── RAW-domain HDR highlight recovery (pre-demosaic) ─────────────────
    if (!options.hdrModelData.empty()) {
        const auto& color  = raw->imgdata.color;
        const float wl     = float(color.maximum) + options.whiteLevelDelta;
        const float bl     = float(color.black)   + options.blackLevelDelta;
        applyRawHdrRecovery(
            raw->imgdata.rawdata.raw_image,
            raw->imgdata.sizes.raw_width,
            raw->imgdata.sizes.raw_height,
            raw->imgdata.idata.filters,
            wl, bl,
            options.hdrModelData.data(),
            options.hdrModelData.size());
    }

    // ── RAW-domain shadow/black recovery (pre-demosaic, after HDR pass) ──
    if (!options.shadowModelData.empty()) {
        const auto& color  = raw->imgdata.color;
        const float wl     = float(color.maximum) + options.whiteLevelDelta;
        const float bl     = float(color.black)   + options.blackLevelDelta;
        applyRawShadowRecovery(
            raw->imgdata.rawdata.raw_image,
            raw->imgdata.sizes.raw_width,
            raw->imgdata.sizes.raw_height,
            raw->imgdata.idata.filters,
            wl, bl,
            options.shadowModelData.data(),
            options.shadowModelData.size());
    }

    // ── Inset-crop promotion (LibRaw 0.21+) ──────────────────────────────
    // For cameras where LibRaw's camera table sets left_margin/top_margin
    // incorrectly (Sony ILCE-7M2 masked columns, Olympus E-M5 III 32-px
    // shading-compensation band, etc.), promote raw_inset_crops[0] — the
    // camera-declared active-sensor rectangle — into the sizes fields so
    // that the demosaic path reads from the correct origin.
    //
    // mask=1  → only rICC[0] (active-area crop; not the LCD display crop).
    // maxcrop=0.55 → inset must cover ≥55 % of raw_width/raw_height;
    //               prevents a stale or bogus crop from nuking the image.
    // No-op when raw_inset_crops[0] is unpopulated (ctop/cleft == 0xffff)
    // or its geometry overflows the raw buffer — all cameras already handled
    // by the existing iwidth/iheight trim remain unchanged.
    {
        const auto& ic = raw->imgdata.sizes.raw_inset_crops[0];
        if (ic.ctop < 0xffffu && ic.cleft < 0xffffu) {
            const int ret2 = raw->adjust_to_raw_inset_crop(1, 0.55f);
            if (ret2 > 0) {
                LOGI("runStageA: adjust_to_raw_inset_crop applied "
                     "→ margins L=%d T=%d  active %dx%d",
                     raw->imgdata.sizes.left_margin,
                     raw->imgdata.sizes.top_margin,
                     raw->imgdata.sizes.width,
                     raw->imgdata.sizes.height);
            }
        }
    }

    // ── Demosaic dispatch: RAZAMaZE+LMMSE only ───────────────────────────
    // Capture EXIF flip now — dcraw_process(user_flip=0) inside the DualVNG
    // block will clobber sizes.flip to 0 via raw2image.cpp:28-29.
    const int exifFlip = raw->imgdata.sizes.flip;
    int W = 0, H = 0;
    std::vector<uint16_t> rgbaF16;

    if (false) { // removed RCD path — kept as placeholder for compiler
        // RAZAmaze (RCD) path. Skip LibRaw's dcraw_process entirely and
        // operate on the post-unpack Bayer mosaic. Mirrors the v2
        // dispatcher (raw_decoder_v2.cpp:789-882). LibRaw's WB / black /
        // white levels are still trusted — the kernel just doesn't run
        // LibRaw's own demosaic.
        const uint16_t* rawImg = raw->imgdata.rawdata.raw_image;
        if (!rawImg) {
            // Non-Bayer sensor (X-Trans, Fuji etc.). v2 falls back to
            // LibRaw user_qual=13 (DHT). Mirror that.
            LOGI("runStageA[RCD]: non-Bayer source — falling back to LibRaw DHT");
            raw->imgdata.params.user_qual = 13;
            ret = raw->dcraw_process();
            if (ret != LIBRAW_SUCCESS) {
                meta.errorMessage = std::string("dcraw_process (DHT fallback): ")
                                  + libraw_strerror(ret);
                LOGE("runStageA: %s", meta.errorMessage.c_str());
                return meta;
            }
            libraw_processed_image_t* img = raw->dcraw_make_mem_image(&ret);
            if (!img || ret != LIBRAW_SUCCESS || img->bits != 16 || img->colors != 3) {
                meta.errorMessage = "dcraw_make_mem_image (DHT fallback) failed";
                LOGE("runStageA: %s", meta.errorMessage.c_str());
                if (img) LibRaw::dcraw_clear_mem(img);
                return meta;
            }
            W = img->width; H = img->height;
            rgbaF16.resize(size_t(W) * H * 4);
            rgb16ToRgbaF16(reinterpret_cast<const uint16_t*>(img->data),
                           rgbaF16.data(), W, H);
            LibRaw::dcraw_clear_mem(img);
        } else {
            const int rawW  = raw->imgdata.sizes.raw_width;
            const int rawH  = raw->imgdata.sizes.raw_height;
            int cropL = raw->imgdata.sizes.left_margin;
            int cropT = raw->imgdata.sizes.top_margin;
            int outW = raw->imgdata.sizes.iwidth;
            int outH = raw->imgdata.sizes.iheight;
            if (outW <= 0 || outH <= 0) {
                outW = raw->imgdata.sizes.width;
                outH = raw->imgdata.sizes.height;
            }
            // Canon CR2/CR3 (and some others) pad the demosaic buffer with
            // masked/black BORDER rows+cols beyond the real image — LibRaw
            // reports iwidth/iheight INCLUDING that slop (Canon 6D: 5496x3670),
            // while the true visible frame is `raw_inset_crops[0]` (5472x3648).
            // The padding sits on the BOTTOM + RIGHT, so we keep the read origin
            // at the active-area margins (cropL/cropT) and only SHRINK outW/outH
            // to the inset size. We do NOT add the inset's cleft/ctop: those are
            // a separate vendor display-crop in raw-buffer space and would
            // mis-shift the window (Canon's cleft/ctop=84/50 overflow iwidth).
            // See libraw.org/node/2760 + lclevy.free.fr/cr2.
            {
                const auto& inset = raw->imgdata.sizes.raw_inset_crops[0];
                const int cw = inset.cwidth, ch = inset.cheight;
                if (cw > 0 && ch > 0 && cw < outW) outW = cw;
                if (ch > 0 && ch <= outH && ch < outH) outH = ch;
                if (cw > 0 && ch > 0)
                    LOGI("runStageA[RCD]: trimmed sensor border → %dx%d "
                         "(was iwidth=%d iheight=%d)", outW, outH,
                         raw->imgdata.sizes.iwidth, raw->imgdata.sizes.iheight);
            }
            if (outW <= 0 || outH <= 0 || outW > rawW || outH > rawH) {
                meta.errorMessage = "RCD: invalid output dims";
                LOGE("runStageA[RCD]: invalid dims out=%dx%d raw=%dx%d",
                     outW, outH, rawW, rawH);
                return meta;
            }

            // WB multipliers, normalised by dmax (LibRaw scale_colors() style).
            //
            // Source selection (fixes a yellow/red cast on plain CR2):
            //   • `cam_mul` is the camera's as-shot WB and is authoritative for
            //     native raws (CR2/ARW/NEF). It's what "Camera WB" must use.
            //   • `pre_mul` was used unconditionally before, but for some CR2
            //     `pre_mul` diverges hard from `cam_mul` (e.g. pre_mul green is
            //     < 1 and its dmax-normalisation suppresses G/B → yellow cast).
            //     Verified on Canon EOS 6D IMG_3965: cam_mul→[1.89,1,1.70],
            //     pre_mul/dmax→[1,0.45,0.64] (the wrong, yellowish result).
            //   • Adobe DNGs bake WB differently and populate asshotneutral;
            //     for those, `pre_mul` (built from the DNG matrices) is right
            //     and `cam_mul` over-boosts blue → magenta. So: prefer cam_mul,
            //     fall back to pre_mul only when the DNG neutral tag is present
            //     or cam_mul is unusable.
            const auto& colorRef = raw->imgdata.color;
            const float* asn = colorRef.dng_levels.asshotneutral;
            const bool dngNeutral = asn[0] > 0.f || asn[1] > 0.f || asn[2] > 0.f;
            const float* camRef = colorRef.cam_mul;
            const bool camMulUsable = camRef[0] > 0.f && camRef[1] > 0.f && camRef[2] > 0.f;

            // WB source priority:
            //   1. DNG AsShotNeutral (when present) — the DNG-spec-authoritative
            //      as-shot WB. The multiplier is its RECIPROCAL (1/neutral). This
            //      is correct for GCam / computational DNGs whose pre_mul is ~1/1/1
            //      (no correction → greenish cast) AND for Adobe DNGs. Earlier we
            //      wrongly routed every DNG to pre_mul, leaving GCam shots uncorrected.
            //   2. cam_mul — native CR2/ARW/NEF as-shot WB.
            //   3. pre_mul — last resort (missing cam_mul, no neutral).
            float wbFromNeutral[4] = {0,0,0,0};
            bool useNeutral = false;
            if (dngNeutral && asn[0] > 0.f && asn[1] > 0.f && asn[2] > 0.f) {
                wbFromNeutral[0] = 1.f / asn[0];
                wbFromNeutral[1] = 1.f / asn[1];
                wbFromNeutral[2] = 1.f / asn[2];
                wbFromNeutral[3] = asn[3] > 0.f ? 1.f / asn[3] : wbFromNeutral[1];
                useNeutral = true;
            }
            const float* cm = useNeutral
                ? wbFromNeutral             // DNG AsShotNeutral (GCam/Adobe)
                : (camMulUsable ? colorRef.cam_mul   // native raw as-shot WB
                                : colorRef.pre_mul); // fallback
            // Green-normalise, then divide by the max multiplier so all gains
            // sit in (0,1]. This keeps a properly-exposed NEUTRAL sensel neutral
            // (the sensor's raw R/G/B already differ; the gains compensate) and
            // guarantees no channel exceeds 1.0 for unclipped light. Blown
            // (sensor-clipped) pixels are handled separately by the highlight
            // reconstruction in the RCD kernel — see clipWhite below.
            float gRef = cm[1] > 0.f ? cm[1] : 1.f;
            float pm[4] = {
                cm[0] / gRef,
                1.f,
                cm[2] / gRef,
                cm[3] > 0.f ? cm[3] / gRef : 1.f,
            };
            float camMul[4];
            float dmax = pm[0];
            if (pm[1] > dmax) dmax = pm[1];
            if (pm[2] > dmax) dmax = pm[2];
            if (pm[3] > dmax) dmax = pm[3];
            if (dmax <= 0.f) dmax = 1.f;
            camMul[0] = pm[0] / dmax;
            camMul[1] = pm[1] / dmax;
            camMul[2] = pm[2] / dmax;
            camMul[3] = pm[3] / dmax;
            const char* wbSrcName = useNeutral ? "asShotNeutral"
                                  : (camMulUsable ? "cam_mul" : "pre_mul");

            // Adobe Enhanced NR / Super Resolution DNGs bake WB into the pixel
            // data during their neural-net re-mosaic step (as_shot_wb_applied=1).
            // Applying camMul a second time over-boosts R relative to G/B → the
            // pink/magenta tint users see. When WB is already applied, use an
            // identity multiplier so the kernel does no WB adjustment.
            if (colorRef.as_shot_wb_applied) {
                camMul[0] = camMul[1] = camMul[2] = camMul[3] = 1.f;
                LOGI("runStageA[RCD]: as_shot_wb_applied=1 → camMul forced to identity (WB already in pixels)");
            }

            LOGI("runStageA[RCD]: WB src=%s/dmax = [%.3f,%.3f,%.3f,%.3f] (dngNeutral=%d camMulUsable=%d as_shot_wb_applied=%d)",
                 wbSrcName, camMul[0], camMul[1], camMul[2], camMul[3], dngNeutral, camMulUsable,
                 colorRef.as_shot_wb_applied);

            float whiteLevel = (float) raw->imgdata.color.maximum;
            if (whiteLevel < 1.f) whiteLevel = 65535.f;
            const BlackLevels bl = resolveBlackLevels(raw.get());
            float blackLevel = bl.base;
            // Apply user override deltas. LibRaw's per-camera defaults are
            // sometimes off by a few counts → ±deltas let the user fine-tune.
            // Clamp to physically sensible bounds (black ≥ 0, white > black,
            // both within 16-bit DN). Order matters: black moves first so the
            // white clamp uses the adjusted black floor.
            blackLevel += options.blackLevelDelta;
            if (blackLevel < 0.f)        blackLevel = 0.f;
            if (blackLevel > 16383.f)    blackLevel = 16383.f;
            whiteLevel += options.whiteLevelDelta;
            if (whiteLevel <= blackLevel + 1.f) whiteLevel = blackLevel + 1.f;
            if (whiteLevel > 65535.f)    whiteLevel = 65535.f;
            const unsigned filters = raw->imgdata.idata.filters;

            // ── Per-channel cblack correction ─────────────────────────────
            // LibRaw's cblack[0..3] = per-channel (R,G1,B,G2) additional black
            // offsets, in addition to the scalar `black`. Both must be subtracted
            // per-sensel; the kernels only support a scalar blackLevel, so we
            // absorb the per-channel residual by adjusting camMul.
            //
            // Derivation: the kernel computes
            //   v = (raw - blackLevel) * scale * camMul[ch]
            // but the correct formula is
            //   v = (raw - blackLevel - cblack[ch]) * scale * camMul[ch]
            // Rearranging:
            //   v = (raw - blackLevel) * scale * camMul[ch]
            //     - cblack[ch] * scale * camMul[ch]
            // We want to produce this without changing the kernel. We can't
            // subtract the per-channel cblack inside the kernel, but we can
            // raise the shared blackLevel so that the excess per-channel pedestal
            // is zeroed out on average, AND scale camMul[ch] down by the factor
            // (range_adj / range_base) to keep the white point aligned.
            //
            // Simpler approach used here: raise blackLevel by min(cblack[0..3])
            // (the common offset) and correct each channel's camMul by
            // (range_base / range_adj) where range_adj = white - blackLevel - cblack[ch].
            // This eliminates the inter-channel DC shift that causes pink tint.
            // `bl.base` (folded into blackLevel above) already carries the
            // uniform floor; only the per-channel EXCESS is left to absorb.
            {
                const float rangeBase = whiteLevel - blackLevel;
                bool anyExcess = false;
                for (int ch = 0; ch < 4; ++ch) anyExcess |= bl.perCh[ch] > 0.f;
                if (anyExcess) {
                    for (int ch = 0; ch < 4; ++ch) {
                        const float adj = rangeBase - bl.perCh[ch];
                        if (adj > 1.f) camMul[ch] *= adj / rangeBase;
                    }
                }
                // Always logged: a silent "no black found" is exactly what hid
                // the phone-DNG pedestal for an entire debugging round.
                LOGI("runStageA[RCD]: black=%.1f (libraw black=%d pattern=%d) "
                     "excess=[%.1f,%.1f,%.1f,%.1f] white=%.0f",
                     blackLevel, raw->imgdata.color.black, bl.fromPattern ? 1 : 0,
                     bl.perCh[0], bl.perCh[1], bl.perCh[2], bl.perCh[3], whiteLevel);
            }

            LOGI("runStageA[RCD]: %dx%d (raw %dx%d crop=%d,%d filters=0x%x "
                 "black=%.0f white=%.0f camMul=[%.3f,%.3f,%.3f,%.3f] "
                 "blackDelta=%.0f whiteDelta=%.0f clipThr=%.3f)",
                 outW, outH, rawW, rawH, cropL, cropT, filters,
                 blackLevel, whiteLevel,
                 camMul[0], camMul[1], camMul[2], camMul[3],
                 options.blackLevelDelta, options.whiteLevelDelta, options.clipThreshold);

            // ── DNG-diagnosis dump ────────────────────────────────────────
            // We are chasing a pink/magenta cast on Adobe-Enhanced DNGs
            // (e.g. files with "-Enhanced-NR.dng" suffix). The data we
            // need to see:
            //
            //   • colors   : if 3 then `raw_image` is RGB triplets, not a
            //                Bayer mosaic, and RCD will scramble channels
            //   • cblack[] : Adobe DNGs typically write a non-zero
            //                per-channel cblack[] *in addition to* black.
            //                We currently subtract only `black`, so a
            //                non-zero cblack residual would shift R, G,
            //                G2, B unequally → exactly the pink-cast
            //                symptom we're seeing.
            //   • cam_mul vs pre_mul vs dng_levels.analogbalance — three
            //     candidate WB sources LibRaw exposes; if Adobe baked the
            //     WB into the pixel data, cam_mul should be 1/1/1/1 and
            //     analogbalance might carry the original camera values.
            //   • raw samples at four CFA positions in the centre of the
            //     image, after black subtraction: tells us channel-wise
            //     mean levels. On a clean grey card, post-WB means
            //     should be roughly equal across R/G/B/G2.
            {
                const auto& idata = raw->imgdata.idata;
                const auto& color = raw->imgdata.color;
                const auto& sizes = raw->imgdata.sizes;
                LOGI("runStageA[RCD-diag]: colors=%d filters=0x%x cdesc=%s dng_version=%u",
                     idata.colors, idata.filters, idata.cdesc, idata.dng_version);
                // Adobe Enhanced NR / Super Res DNGs leave the file looking
                // like a Bayer mosaic (filters non-zero, raw_image populated)
                // but the data has already been demosaiced + re-mosaiced by
                // Adobe's net. Two reliable LibRaw signals:
                //   • color3_image — when non-null, LibRaw decoded the file
                //     as RGB triplets, not Bayer.
                //   • OriginalRawFileName — Adobe Enhanced writes the source
                //     filename (e.g. "_MG_9632.CR2") here. Plain CR2-→-DNG
                //     conversions don't.
                //   • dng_levels.rawopcodes[N].opcode — Adobe Enhanced writes
                //     OpcodeList opcodes for the re-mosaic step.
                LOGI("runStageA[RCD-diag]: raw_image=%p color3_image=%p "
                     "OriginalRawFileName='%s' UniqueCameraModel='%s' "
                     "as_shot_wb_applied=%d",
                     (void*)raw->imgdata.rawdata.raw_image,
                     (void*)raw->imgdata.rawdata.color3_image,
                     color.OriginalRawFileName,
                     color.UniqueCameraModel,
                     color.as_shot_wb_applied);
                LOGI("runStageA[RCD-diag]: raw_count=%u raw_inset_crops[0]=%dx%d "
                     "iwidth=%d iheight=%d width=%d height=%d",
                     idata.raw_count,
                     sizes.raw_inset_crops[0].cwidth,
                     sizes.raw_inset_crops[0].cheight,
                     sizes.iwidth, sizes.iheight,
                     sizes.width, sizes.height);
                LOGI("runStageA[RCD-diag]: rawopcodes byte-lens=[%u,%u,%u] "
                     "data ptrs=[%p,%p,%p]",
                     color.dng_levels.rawopcodes[0].len,
                     color.dng_levels.rawopcodes[1].len,
                     color.dng_levels.rawopcodes[2].len,
                     color.dng_levels.rawopcodes[0].data,
                     color.dng_levels.rawopcodes[1].data,
                     color.dng_levels.rawopcodes[2].data);
                LOGI("runStageA[RCD-diag]: maximum=%d black=%d cblack=[%u,%u,%u,%u] "
                     "linear_max=[%u,%u,%u,%u]",
                     color.maximum, color.black,
                     color.cblack[0], color.cblack[1], color.cblack[2], color.cblack[3],
                     color.linear_max[0], color.linear_max[1],
                     color.linear_max[2], color.linear_max[3]);
                LOGI("runStageA[RCD-diag]: cam_mul=[%.3f,%.3f,%.3f,%.3f] "
                     "pre_mul=[%.3f,%.3f,%.3f,%.3f]",
                     color.cam_mul[0], color.cam_mul[1], color.cam_mul[2], color.cam_mul[3],
                     color.pre_mul[0], color.pre_mul[1], color.pre_mul[2], color.pre_mul[3]);
                LOGI("runStageA[RCD-diag]: dng_levels.analogbalance=[%.3f,%.3f,%.3f,%.3f] "
                     "asshotneutral=[%.3f,%.3f,%.3f,%.3f]",
                     color.dng_levels.analogbalance[0],
                     color.dng_levels.analogbalance[1],
                     color.dng_levels.analogbalance[2],
                     color.dng_levels.analogbalance[3],
                     color.dng_levels.asshotneutral[0],
                     color.dng_levels.asshotneutral[1],
                     color.dng_levels.asshotneutral[2],
                     color.dng_levels.asshotneutral[3]);
                // Probe 4 raw samples at a 2×2 CFA block near image centre.
                // Use the active-area offset (cropL, cropT) so we read from
                // image content, not optical-black borders.
                const int cy = cropT + outH / 2;
                const int cx = cropL + outW / 2;
                // Force the 2×2 to align to the CFA grid by snapping to
                // even offsets relative to the active area.
                const int ay = cy - (cy & 1);
                const int ax = cx - (cx & 1);
                const uint16_t s00 = rawImg[(long long)(ay    ) * rawW + ax    ];
                const uint16_t s01 = rawImg[(long long)(ay    ) * rawW + ax + 1];
                const uint16_t s10 = rawImg[(long long)(ay + 1) * rawW + ax    ];
                const uint16_t s11 = rawImg[(long long)(ay + 1) * rawW + ax + 1];
                LOGI("runStageA[RCD-diag]: centre 2x2 raw samples = [%u,%u,%u,%u] "
                     "(fc pattern: %d,%d,%d,%d where 0=R,1=G,2=B,3=G2)",
                     s00, s01, s10, s11,
                     (int)((filters >> 0) & 3),
                     (int)((filters >> 2) & 3),
                     (int)((filters >> 4) & 3),
                     (int)((filters >> 6) & 3));
            }

            std::vector<uint16_t> rcdBuf;
            try {
                rcdBuf.resize(size_t(outW) * outH * 3);
            } catch (const std::bad_alloc&) {
                meta.errorMessage = "RCD: OOM allocating output buffer";
                LOGE("runStageA[RCD]: bad_alloc (%zu bytes)", size_t(outW) * outH * 6);
                return meta;
            }

            bool ok = rcd_demosaic_to_buf(rawImg, rawW, rawH, cropL, cropT,
                                          outW, outH, filters,
                                          blackLevel, whiteLevel, camMul,
                                          rcdBuf.data(), options.clipThreshold);
            if (!ok) {
                meta.errorMessage = "RCD: kernel returned false";
                LOGE("runStageA[RCD]: kernel failed");
                return meta;
            }

            W = outW; H = outH;
            rgbaF16.resize(size_t(W) * H * 4);

            // ── Camera-RGB → sRGB-linear matrix (rgb_cam) ─────────────
            // LibRaw exposes the camera→sRGB primaries matrix at
            // `imgdata.color.rgb_cam[3][4]` — populated during open_file()
            // from the embedded DNG colour matrices or from LibRaw's
            // hardcoded per-camera profile. The 4th column is for a
            // 4-colour sensor and we collapse it into G alongside G2
            // (matches the v2 RawBitmapConverter logic).
            //
            // Falls back to identity if rgb_cam is unpopulated (zero
            // matrix). That covers the rare "no profile" case; verified
            // populated on every Canon EOS 6D file we tested, including
            // Lightroom-Enhanced DNGs.
            float rgbCam33[9];
            {
                const auto (&rc)[3][4] = raw->imgdata.color.rgb_cam;
                // Fold the 4th column into the green column (G/G2 share
                // it) — same convention as the v2 RawBitmapConverter.
                rgbCam33[0] = rc[0][0]; rgbCam33[1] = rc[0][1] + rc[0][3]; rgbCam33[2] = rc[0][2];
                rgbCam33[3] = rc[1][0]; rgbCam33[4] = rc[1][1] + rc[1][3]; rgbCam33[5] = rc[1][2];
                rgbCam33[6] = rc[2][0]; rgbCam33[7] = rc[2][1] + rc[2][3]; rgbCam33[8] = rc[2][2];
                bool anyNonZero = false;
                for (int k = 0; k < 9; ++k) if (rgbCam33[k] != 0.f) { anyNonZero = true; break; }
                if (!anyNonZero) {
                    LOGI("runStageA[RCD]: rgb_cam is zero — falling back to identity");
                    rgbCam33[0] = 1.f; rgbCam33[1] = 0.f; rgbCam33[2] = 0.f;
                    rgbCam33[3] = 0.f; rgbCam33[4] = 1.f; rgbCam33[5] = 0.f;
                    rgbCam33[6] = 0.f; rgbCam33[7] = 0.f; rgbCam33[8] = 1.f;
                } else {
                    LOGI("runStageA[RCD]: rgb_cam = "
                         "[[%.3f,%.3f,%.3f],[%.3f,%.3f,%.3f],[%.3f,%.3f,%.3f]]",
                         rgbCam33[0], rgbCam33[1], rgbCam33[2],
                         rgbCam33[3], rgbCam33[4], rgbCam33[5],
                         rgbCam33[6], rgbCam33[7], rgbCam33[8]);
                }
            }
            // Kernel writes BGR (v2-compatible); convert + matrix + sRGB
            // transfer in one pass to match the LibRaw path's output.
            bgr16ToSrgbRgbaF16(rcdBuf.data(), rgbaF16.data(), W, H, rgbCam33);
        }
    } else {
        // ── AMaZE+LMMSE dual-decode path ─────────────────────────────────────
        //
        // ╔════════════════════════════════════════════════════════════════════╗
        // ║  DO NOT MODIFY THIS SECTION — LOCKED RAZAMaZE+LMMSE ALGORITHM    ║
        // ║                                                                    ║
        // ║  Invariants that must never change (each was a real bug fix):     ║
        // ║  1. LMMSE WB = camMulDual (G-norm cam_mul), NOT pre_mul.          ║
        // ║     pre_mul is fixed per sensor; cam_mul is per-photo as-shot.    ║
        // ║  2. AMaZE: user_qual=12, no_auto_bright=1, use_camera_wb=1.       ║
        // ║     no_auto_bright=1 prevents 3-4× brightness mismatch.           ║
        // ║  3. pmMax normalisation before rgb_cam (Step 2a).                 ║
        // ║  4. G2 folded into G1 in rgb_cam (col 3 → col 1).                ║
        // ║  5. LMMSE crop: cropL=left_margin, cropT=top_margin; outW/outH   ║
        // ║     locked to amazeW/amazeH.                                      ║
        // ║  6. dR/dB interpolation uses per-site offsets (see lmmse file).  ║
        // ╚════════════════════════════════════════════════════════════════════╝
        //
        // Step 1: AMaZE via LibRaw (user_qual=12) with camera WB.
        //   Both AMaZE and VNG must operate on the same scale so the blend
        //   lerp is meaningful.  We disable LibRaw's scale_colors WB and
        //   apply it ourselves post-blend.  WB is only applied to the raw
        //   mosaic copy that we feed into vng_demosaic_to_planes — there it's
        //   baked in per-sensel, which is the correct place (pre-interpolation).
        //   For AMaZE we let LibRaw produce a WB-applied image (its scale_colors
        //   step runs as part of dcraw_process), but we override the WB to
        //   camera WB so both arms agree on colour space.
        //
        //   camMul[4] is computed using the same priority chain as the RCD
        //   path above (DNG asshotneutral → cam_mul → pre_mul).
        //
        //   AMaZE output → linear sRGB FP32 planes (R,G,B).
        //   VNG output   → linear camera-RGB FP32 planes (R,G,B), WB applied.
        //   Blend        → sigmoid contrast mask, then lerp.
        //   Final        → apply rgb_cam colour matrix + sRGB gamma → RGBA FP16.

        // ── Derive camMul (same as RCD path) ─────────────────────────────
        const auto& colorRef2 = raw->imgdata.color;
        const float* asn2 = colorRef2.dng_levels.asshotneutral;
        const bool dngNeutral2 = asn2[0] > 0.f || asn2[1] > 0.f || asn2[2] > 0.f;
        const float* camRef2 = colorRef2.cam_mul;
        const bool camMulUsable2 = camRef2[0] > 0.f && camRef2[1] > 0.f && camRef2[2] > 0.f;

        float wbFromNeutral2[4] = {0,0,0,0};
        bool useNeutral2 = false;
        if (dngNeutral2 && asn2[0] > 0.f && asn2[1] > 0.f && asn2[2] > 0.f) {
            wbFromNeutral2[0] = 1.f / asn2[0];
            wbFromNeutral2[1] = 1.f / asn2[1];
            wbFromNeutral2[2] = 1.f / asn2[2];
            wbFromNeutral2[3] = asn2[3] > 0.f ? 1.f / asn2[3] : wbFromNeutral2[1];
            useNeutral2 = true;
        }
        // pm2 = LibRaw-equivalent scale_colors multipliers for LMMSE.
        // LibRaw scale_colors multiplies each raw sensel by pre_mul[ch] (not
        // WB multipliers for LMMSE — must use per-photo as-shot WB (cam_mul),
        // not pre_mul which is a fixed sensor characteristic identical across
        // all shots. Using pre_mul gave wrong yellow/orange on photos whose WB
        // differs from the sensor reference (most real-world shots).
        const float* cm2 = useNeutral2
            ? wbFromNeutral2
            : (camMulUsable2 ? colorRef2.cam_mul : colorRef2.pre_mul);
        float gRef2 = cm2[1] > 0.f ? cm2[1] : 1.f;
        // G-normalised per-photo WB — LMMSE uses this so colours match AMaZE.
        float camMulDual[4] = {
            cm2[0] / gRef2, 1.f, cm2[2] / gRef2,
            cm2[3] > 0.f ? cm2[3] / gRef2 : 1.f,
        };
        // Adobe Enhanced NR DNGs bake WB into pixels (as_shot_wb_applied=1).
        // Applying camMul again would over-boost R → pink tint. Force identity.
        if (colorRef2.as_shot_wb_applied) {
            camMulDual[0] = camMulDual[1] = camMulDual[2] = camMulDual[3] = 1.f;
            LOGI("runStageA[DualVNG]: as_shot_wb_applied=1 → camMul forced to identity");
        }

        // pm2 = camMulDual (kept as alias for the LMMSE call site below).
        float* pm2 = camMulDual;
        LOGI("runStageA[DualVNG]: camMul=[%.3f,%.3f,%.3f,%.3f] (dngNeutral=%d as_shot_wb_applied=%d)",
             camMulDual[0], camMulDual[1], camMulDual[2], camMulDual[3], dngNeutral2,
             colorRef2.as_shot_wb_applied);
        LOGI("runStageA[DualVNG]: pre_mul=[%.3f,%.3f,%.3f,%.3f] cam_mul=[%.3f,%.3f,%.3f,%.3f] asn=[%.4f,%.4f,%.4f]",
             colorRef2.pre_mul[0], colorRef2.pre_mul[1], colorRef2.pre_mul[2], colorRef2.pre_mul[3],
             colorRef2.cam_mul[0], colorRef2.cam_mul[1], colorRef2.cam_mul[2], colorRef2.cam_mul[3],
             asn2[0], asn2[1], asn2[2]);

        // Check for Bayer mosaic — bail out to plain AMaZE if non-Bayer.
        const uint16_t* rawMosaic = raw->imgdata.rawdata.raw_image;
        LOGI("runStageA[DualVNG]: raw_image=%s colors=%d filters=0x%08x isLinearRaw=%d",
             rawMosaic ? "non-null" : "null",
             raw->imgdata.idata.colors,
             raw->imgdata.idata.filters,
             int(options.isLinearRaw));
        // Adobe Enhanced DNGs (Enhance Details / AI Denoise / Super Resolution)
        // write the source raw's filename into the DNG OriginalRawFileName tag;
        // native raws and plain camera DNGs leave it empty. as_shot_wb_applied
        // catches the subset that also bakes WB into the pixels. Either marker
        // ⇒ Adobe-processed DNG.
        const bool isAdobeEnhanced =
            colorRef2.OriginalRawFileName[0] != '\0' || colorRef2.as_shot_wb_applied;
        if (!rawMosaic || isAdobeEnhanced) {
            // AMaZE-only path. Taken when:
            //   • no raw_image (non-Bayer / linear raw), OR
            //   • Adobe Enhanced DNG. These STILL carry a Bayer mosaic, but the
            //     dual AMaZE+LMMSE blend renders them magenta — the LMMSE arm
            //     comes out ~40× too bright and green-deficient, and the rgb_cam
            //     matrix then crushes green. LibRaw's AMaZE alone decodes them
            //     correctly, so route Adobe-processed DNGs to AMaZE-only.
            //     Scoped to the Adobe markers: normal camera Bayer files keep
            //     the full dual blend unchanged.
            LOGI("runStageA[DualVNG]: AMaZE-only (raw_image=%s adobeEnhanced=%d origRaw='%s' as_shot_wb_applied=%d)",
                 rawMosaic ? "non-null" : "null", int(isAdobeEnhanced),
                 colorRef2.OriginalRawFileName, colorRef2.as_shot_wb_applied);
            // LinearRaw / Enhanced NR DNG: WB is already baked into pixels
            // (PhotometricInterpretation=34892, detected by RawV3SourceProbe).
            // Force neutral user_mul so LibRaw scale_colors is a no-op.
            // Without this, scale_colors applies camMul again → double WB → pink cast.
            // Guard on isLinearRaw ONLY — do NOT use filters==0 as a proxy because
            // Canon mRAW/sRAW also produces raw_image=null + filters=0 but needs
            // normal camera WB applied (stripping it causes all-green output).
            if (options.isLinearRaw) {
                raw->imgdata.params.use_camera_wb = 0;
                raw->imgdata.params.use_auto_wb   = 0;
                raw->imgdata.params.user_mul[0]   = 1.f;
                raw->imgdata.params.user_mul[1]   = 1.f;
                raw->imgdata.params.user_mul[2]   = 1.f;
                raw->imgdata.params.user_mul[3]   = 1.f;
                LOGI("runStageA[DualVNG]: isLinearRaw=1 → user_mul forced to identity (skip scale_colors WB)");
            } else if (isAdobeEnhanced) {
                // Adobe Enhanced Bayer DNG. Decode with camera WB exactly like
                // the dual path's own AMaZE arm does (use_camera_wb=1), which
                // produced a neutral, correctly-coloured result in the logs.
                // no_auto_bright matches the rest of the scene-linear pipeline.
                raw->imgdata.params.use_camera_wb  = 1;
                raw->imgdata.params.use_auto_wb    = 0;
                raw->imgdata.params.no_auto_bright = 1;
                LOGI("runStageA[DualVNG]: Adobe Enhanced Bayer DNG → AMaZE-only, camera WB");
            }
            raw->imgdata.params.user_qual = 12;
            ret = raw->dcraw_process();
            if (ret != LIBRAW_SUCCESS) {
                meta.errorMessage = std::string("dcraw_process (DualVNG AMaZE): ")
                                  + libraw_strerror(ret);
                LOGE("runStageA: %s", meta.errorMessage.c_str());
                return meta;
            }
            libraw_processed_image_t* img = raw->dcraw_make_mem_image(&ret);
            if (!img || ret != LIBRAW_SUCCESS || img->bits != 16 || img->colors != 3) {
                meta.errorMessage = "dcraw_make_mem_image (DualVNG fallback) failed";
                LOGE("runStageA: %s", meta.errorMessage.c_str());
                if (img) LibRaw::dcraw_clear_mem(img);
                return meta;
            }
            W = img->width; H = img->height;
            // Adobe Enhanced DNGs decode with pink/magenta blown highlights
            // (strong baked WB on clipped data). Neutralize them in-place on the
            // RGB16 output before packing to FP16. Scoped to Adobe DNGs only —
            // the linear-raw arm (!rawMosaic) and all normal images are untouched.
            if (isAdobeEnhanced) {
                desaturateBlownHighlightsRgb16(
                    reinterpret_cast<uint16_t*>(img->data), W, H);
            }
            rgbaF16.resize(size_t(W) * H * 4);
            rgb16ToRgbaF16(reinterpret_cast<const uint16_t*>(img->data),
                           rgbaF16.data(), W, H);
            LibRaw::dcraw_clear_mem(img);
        } else {
            // ── Compute crop / dim / levels (same as RCD path) ───────────
            const int rawW2  = raw->imgdata.sizes.raw_width;
            const int rawH2  = raw->imgdata.sizes.raw_height;
            int cropL2 = raw->imgdata.sizes.left_margin;
            int cropT2 = raw->imgdata.sizes.top_margin;
            int outW2  = raw->imgdata.sizes.iwidth;
            int outH2  = raw->imgdata.sizes.iheight;
            if (outW2 <= 0 || outH2 <= 0) {
                outW2 = raw->imgdata.sizes.width;
                outH2 = raw->imgdata.sizes.height;
            }
            {
                const auto& inset2 = raw->imgdata.sizes.raw_inset_crops[0];
                const int cw2 = inset2.cwidth, ch2 = inset2.cheight;
                if (cw2 > 0 && ch2 > 0 && cw2 < outW2) outW2 = cw2;
                if (ch2 > 0 && ch2 <= outH2 && ch2 < outH2) outH2 = ch2;
            }
            if (outW2 <= 0 || outH2 <= 0 || outW2 > rawW2 || outH2 > rawH2) {
                meta.errorMessage = "DualVNG: invalid output dims";
                LOGE("runStageA[DualVNG]: invalid dims %dx%d (raw %dx%d)",
                     outW2, outH2, rawW2, rawH2);
                return meta;
            }

            float whiteLevel2 = float(raw->imgdata.color.maximum);
            if (whiteLevel2 < 1.f) whiteLevel2 = 65535.f;
            const BlackLevels bl2 = resolveBlackLevels(raw.get());
            float blackLevel2 = bl2.base;
            blackLevel2 += options.blackLevelDelta;
            if (blackLevel2 < 0.f)     blackLevel2 = 0.f;
            if (blackLevel2 > 16383.f) blackLevel2 = 16383.f;
            whiteLevel2 += options.whiteLevelDelta;
            if (whiteLevel2 <= blackLevel2 + 1.f) whiteLevel2 = blackLevel2 + 1.f;
            if (whiteLevel2 > 65535.f) whiteLevel2 = 65535.f;

            const unsigned filters2 = raw->imgdata.idata.filters;
            const int N2 = outW2 * outH2;

            // Per-channel black excess (mirrors the RCD path above). The
            // uniform floor is already in blackLevel2 via bl2.base.
            {
                const float rangeBase2 = whiteLevel2 - blackLevel2;
                bool anyExcess2 = false;
                for (int ch = 0; ch < 4; ++ch) anyExcess2 |= bl2.perCh[ch] > 0.f;
                if (anyExcess2) {
                    for (int ch = 0; ch < 4; ++ch) {
                        const float adj = rangeBase2 - bl2.perCh[ch];
                        if (adj > 1.f) camMulDual[ch] *= adj / rangeBase2;
                    }
                }
                LOGI("runStageA[DualVNG]: black=%.1f (libraw black=%d pattern=%d) "
                     "excess=[%.1f,%.1f,%.1f,%.1f] white=%.0f",
                     blackLevel2, raw->imgdata.color.black, bl2.fromPattern ? 1 : 0,
                     bl2.perCh[0], bl2.perCh[1], bl2.perCh[2], bl2.perCh[3], whiteLevel2);
            }

            LOGI("runStageA[DualVNG]: exifFlip=%d (captured before AMaZE decode)", exifFlip);
            // ── Step 1: AMaZE via LibRaw user_qual=12 ────────────────────
            // Re-configure WB to camera-WB so LibRaw bakes the same WB
            // into the AMaZE output as we apply ourselves in the VNG path.
            raw->imgdata.params.use_camera_wb  = 1;
            raw->imgdata.params.use_auto_wb    = 0;
            raw->imgdata.params.user_qual      = 12;   // AMaZE
            raw->imgdata.params.no_auto_bright = 1;    // disable auto-brightness so
            // AMaZE and LMMSE share the same exposure normalisation. Without this,
            // LibRaw scales AMaZE output to fill [0,65535] while LMMSE uses the
            // raw pre_mul scale — causing LMMSE to be 3-4× darker in highlights.
            //
            // INVARIANT: user_flip=0 for AMaZE decode. LibRaw raw2image.cpp writes
            // sizes.flip = user_flip whenever user_flip >= 0 (line 28-29). With the
            // global user_flip=-1 (set above), dcraw_process() would apply the EXIF
            // rotation and return a portrait image for a portrait shot — but LMMSE
            // operates on the raw mosaic in sensor/landscape coords. Blending a
            // rotated AMaZE against a landscape LMMSE produces a scrambled image.
            // Force user_flip=0 so AMaZE output stays in sensor orientation, matching
            // LMMSE. The single applyFlipRgbaF16 call at the end of this function
            // rotates the blended result to the correct display orientation.
            raw->imgdata.params.user_flip = 0;
            ret = raw->dcraw_process();
            if (ret != LIBRAW_SUCCESS) {
                meta.errorMessage = std::string("dcraw_process (DualVNG AMaZE): ")
                                  + libraw_strerror(ret);
                LOGE("runStageA[DualVNG]: AMaZE dcraw_process failed: %s",
                     libraw_strerror(ret));
                return meta;
            }
            libraw_processed_image_t* amazeImg = raw->dcraw_make_mem_image(&ret);
            if (!amazeImg || ret != LIBRAW_SUCCESS ||
                amazeImg->bits != 16 || amazeImg->colors != 3) {
                meta.errorMessage = "DualVNG: AMaZE dcraw_make_mem_image failed";
                LOGE("runStageA[DualVNG]: AMaZE mem_image failed");
                if (amazeImg) LibRaw::dcraw_clear_mem(amazeImg);
                return meta;
            }
            // AMaZE returns RGB16 in [0,65535] sRGB-gamma space.
            // Convert to linear FP32 [0,1] for blending.
            const uint16_t* aData = reinterpret_cast<const uint16_t*>(amazeImg->data);
            const int amazeW = amazeImg->width;
            const int amazeH = amazeImg->height;
            const int Nout   = amazeW * amazeH;
            constexpr float inv65535 = 1.f / 65535.f;
            // Diagnostic: log AMaZE centre pixel raw uint16 value before linearisation.
            {
                const int ci = (amazeH / 2) * amazeW * 3 + (amazeW / 2) * 3;
                LOGI("runStageA[DualVNG]: AMaZE centre raw uint16=(%u,%u,%u) / 65535",
                     aData[ci], aData[ci+1], aData[ci+2]);
            }

            std::unique_ptr<float[]> amazeR(new float[Nout]);
            std::unique_ptr<float[]> amazeG(new float[Nout]);
            std::unique_ptr<float[]> amazeB(new float[Nout]);
            // AMaZE output is sRGB-gamma-encoded from dcraw_process. Convert
            // back to linear so the blend lerp operates in linear light, then
            // we re-encode to sRGB in the final pack step. Gamma removal:
            // the sRGB EOTF f(x) = x/12.92 for x≤0.04045, else ((x+0.055)/1.055)^2.4.
            // We use a fast piecewise approximation.
            auto srgbToLinear = [](float v) -> float {
                if (v <= 0.f)        return 0.f;
                if (v >= 1.f)        return 1.f;
                if (v <= 0.04045f)   return v * (1.f / 12.92f);
                return std::pow((v + 0.055f) * (1.f / 1.055f), 2.4f);
            };
            for (int i = 0; i < Nout; i++) {
                amazeR[i] = srgbToLinear(float(aData[i * 3 + 0]) * inv65535);
                amazeG[i] = srgbToLinear(float(aData[i * 3 + 1]) * inv65535);
                amazeB[i] = srgbToLinear(float(aData[i * 3 + 2]) * inv65535);
            }
            LibRaw::dcraw_clear_mem(amazeImg);
            // Restore user_flip=-1 so sizes.flip is not permanently clobbered.
            raw->imgdata.params.user_flip = -1;
            LOGI("runStageA[DualVNG]: AMaZE %dx%d done (sensor-orientation, user_flip=0 restored)", amazeW, amazeH);
            // Diagnostic: log raw layout so we can verify LMMSE Bayer phase.
            LOGI("runStageA[DualVNG]: rawW=%d rawH=%d cropL=%d cropT=%d filters=0x%08x flip=%d",
                 rawW2, rawH2, cropL2, cropT2, filters2,
                 raw->imgdata.sizes.flip);
            // Log fcol at AMaZE output corners to verify phase agreement.
            auto fcol2 = [&](int r, int c) {
                return int((filters2 >> (((r << 1 & 14) | (c & 1)) << 1)) & 3);
            };
            LOGI("runStageA[DualVNG]: Bayer phase at crop origin (%d,%d): fcol=%d (0=R,1=G,2=B,3=G2)",
                 cropL2, cropT2, fcol2(cropT2, cropL2));
            LOGI("runStageA[DualVNG]: Bayer phase at output (0,0)=raw(%d,%d): fcol=%d",
                 cropT2, cropL2, fcol2(cropT2, cropL2));

            // Force LMMSE to decode the identical pixel region as AMaZE.
            // LibRaw dcraw_make_mem_image returns amazeW/amazeH which may
            // exceed iwidth/iheight by a few pixels (Canon 6D: 5496 vs 5472).
            // The active area starts at left_margin/top_margin regardless of
            // output size — so keep cropL2/cropT2 as-is (= left_margin,
            // top_margin) and only override the output dimensions to match
            // AMaZE exactly. This ensures both grids start at the same raw
            // pixel and any size difference is accommodated at the right/bottom
            // edge (blank-clamped by PX macro) rather than shifted.
            outW2 = amazeW;
            outH2 = amazeH;
            LOGI("runStageA[DualVNG]: LMMSE crop locked to AMaZE: %dx%d (crop %d,%d)",
                 outW2, outH2, cropL2, cropT2);
            const int N2adj = outW2 * outH2;

            // ── Step 2: VNG via our native port ─────────────────────────
            // vng_demosaic_to_planes applies camMul WB per-sensel before
            // interpolation, matching what the RCD kernel does.  Output
            // is linear camera-RGB in [0,1] BUT without the rgb_cam matrix
            // applied — so it's in the same (camera) colour space as the
            // raw AMaZE linear planes above (which also haven't had the
            // camera→sRGB matrix applied yet; that's only done by
            // bgr16ToSrgbRgbaF16 in the RCD path).
            // For the dual path we apply rgb_cam + sRGB gamma AFTER the
            // blend so both arms are in camera-RGB linear when we lerp.
            //
            // NOTE: AMaZE above goes through dcraw_process which applies
            // scale_colors (WB) and the gamm/output_color transforms.
            // That means amazeR/G/B are already in sRGB-primary SPACE
            // (output_color=1) and sRGB-gamma ENCODED, then we decoded
            // them back to LINEAR sRGB above.  So amazeR/G/B = linear sRGB.
            // vngR/G/B after vng_demosaic_to_planes = linear camera-RGB.
            // These are DIFFERENT colour spaces, so a direct lerp would
            // produce a colour error in the blended region.
            //
            // To unify: apply the rgb_cam colour matrix to the VNG output
            // so it also becomes linear sRGB, THEN lerp.
            std::unique_ptr<float[]> vngR(new float[N2adj]);
            std::unique_ptr<float[]> vngG(new float[N2adj]);
            std::unique_ptr<float[]> vngB(new float[N2adj]);

            // pm2 is camMulDual: G-normalised per-photo WB (see ~line 1062).
            // The stale comment that used to sit here claimed pre_mul was passed;
            // it is not, and that mislead cost a debugging cycle.
            //
            // ── PRE-DIVIDE BY pmMax (was: divide the OUTPUT afterwards) ──────
            // The kernel does `clamp((raw-black)*scale*wb, 0, 1)`. With
            // camMul[R]=2.0 every red sample above HALF scale hit that clamp,
            // and the old post-hoc `/pmMax` then halved it to 0.5 — so red was
            // compressed relative to green (×1.0) and the render came out
            // GREEN. Folding 1/pmMax into the multipliers keeps every gain
            // <= 1.0, so nothing clips on the way in and the ratios survive.
            //
            // Mathematically identical to the old code for unclipped samples
            // (both yield v/pmMax); it only stops destroying the ones that used
            // to clip.
            //
            // SCOPE — measured, not assumed. On IMG_4084 (Canon 6D) this moved
            // whole-image R/G only 0.798 -> 0.802, so on that file almost
            // nothing was clipping. It is a genuine latent fix for RED
            // HIGHLIGHT clipping (camMul[R]=2.0 clipped any red sample above
            // half scale) but it is NOT the cause of the green cast that file
            // shows, which remains unexplained. Do not cite this as the
            // colour-cast fix.
            float pmMaxIn = pm2[0];
            for (int i = 1; i < 4; ++i) if (pm2[i] > pmMaxIn) pmMaxIn = pm2[i];
            if (pmMaxIn <= 0.f) pmMaxIn = 1.f;
            float pmScaled[4] = {
                pm2[0] / pmMaxIn, pm2[1] / pmMaxIn,
                pm2[2] / pmMaxIn, pm2[3] / pmMaxIn,
            };
            LOGI("runStageA[DualVNG]: LMMSE wb pre-scaled by 1/%.4f -> [%.3f,%.3f,%.3f,%.3f] (no pre-clamp)",
                 pmMaxIn, pmScaled[0], pmScaled[1], pmScaled[2], pmScaled[3]);
            bool vngOk = lmmse_demosaic_to_planes(
                rawMosaic, rawW2, rawH2, cropL2, cropT2,
                outW2, outH2, filters2,
                blackLevel2, whiteLevel2, pmScaled,
                vngR.get(), vngG.get(), vngB.get());
            if (!vngOk) {
                meta.errorMessage = "DualVNG: lmmse_demosaic_to_planes failed";
                LOGE("runStageA[DualVNG]: LMMSE kernel failed");
                return meta;
            }
            LOGI("runStageA[DualVNG]: LMMSE %dx%d done", outW2, outH2);

            // ── Step 2a: Normalise LMMSE by max(pm2) ─────────────────────
            // pm2 values > 1.0 (e.g. R=2.22) can push LMMSE pixels above 1.0.
            // rgb_cam clamps to [0,1], clipping highlights before the matrix.
            // Dividing by max(pm2) brings the LMMSE range to [0,1] so the
            // matrix operates on the same scale as the AMaZE linear input,
            // and no highlights are clipped going in.
            // AMaZE with no_auto_bright=1 outputs in the same normalised space
            // (LibRaw divides by its internal white multiplier which equals
            // max(pre_mul) for camera-WB shots).
            // (Step 2a's post-hoc `/pmMax` loop lived here. It is GONE on
            // purpose: 1/pmMax is now folded into the multipliers handed to the
            // kernel, above. Dividing here as well would darken the LMMSE arm
            // by pmMax a second time and pull the blend toward AMaZE.)

            // Log LMMSE centre pixel BEFORE rgb_cam to isolate matrix vs demosaic error.
            {
                const int ci = (outH2 / 2) * outW2 + outW2 / 2;
                LOGI("runStageA[DualVNG]: LMMSE pre-matrix centre=(%.4f,%.4f,%.4f)",
                     vngR[ci], vngG[ci], vngB[ci]);
            }
            // WHOLE-IMAGE plane means. Centre-pixel logs mis-attributed this
            // bug twice; these means are the numbers to trust. Emitted before
            // the matrix so "LMMSE kernel wrong" separates from "matrix wrong".
            {
                double sr = 0, sg = 0, sb = 0;
                for (int i = 0; i < N2adj; i++) { sr += vngR[i]; sg += vngG[i]; sb += vngB[i]; }
                LOGI("runStageA[DualVNG]: LMMSE pre-matrix MEANS=(%.5f,%.5f,%.5f) R/G=%.3f B/G=%.3f",
                     sr / N2adj, sg / N2adj, sb / N2adj,
                     sg > 0 ? sr / sg : 0.0, sg > 0 ? sb / sg : 0.0);
            }

            // Apply rgb_cam to VNG output → linear sRGB, so both arms
            // of the blend live in the same colour space.
            float rgbCam33d[9];
            {
                const auto (&rc)[3][4] = raw->imgdata.color.rgb_cam;
                LOGI("runStageA[DualVNG]: rgb_cam=[[%.4f,%.4f,%.4f,%.4f],[%.4f,%.4f,%.4f,%.4f],[%.4f,%.4f,%.4f,%.4f]]",
                     rc[0][0],rc[0][1],rc[0][2],rc[0][3],
                     rc[1][0],rc[1][1],rc[1][2],rc[1][3],
                     rc[2][0],rc[2][1],rc[2][2],rc[2][3]);
                rgbCam33d[0] = rc[0][0]; rgbCam33d[1] = rc[0][1] + rc[0][3]; rgbCam33d[2] = rc[0][2];
                rgbCam33d[3] = rc[1][0]; rgbCam33d[4] = rc[1][1] + rc[1][3]; rgbCam33d[5] = rc[1][2];
                rgbCam33d[6] = rc[2][0]; rgbCam33d[7] = rc[2][1] + rc[2][3]; rgbCam33d[8] = rc[2][2];
                bool anyNz = false;
                for (int k = 0; k < 9; ++k) if (rgbCam33d[k] != 0.f) { anyNz = true; break; }
                if (!anyNz) {
                    rgbCam33d[0] = 1.f; rgbCam33d[1] = 0.f; rgbCam33d[2] = 0.f;
                    rgbCam33d[3] = 0.f; rgbCam33d[4] = 1.f; rgbCam33d[5] = 0.f;
                    rgbCam33d[6] = 0.f; rgbCam33d[7] = 0.f; rgbCam33d[8] = 1.f;
                }
            }
            // If the matrix is nearly identity (< 1% deviation) or the VNG
            // dims differ from AMaZE dims, skip the matrix step. The AMaZE
            // output from dcraw_process already has the matrix applied; for
            // VNG we only need to apply it when rgb_cam is non-trivial.
            // After crop realignment outW2/outH2 == amazeW/amazeH always.
            const bool matricesMatch = true;
            if (matricesMatch) {
                const float m00 = rgbCam33d[0], m01 = rgbCam33d[1], m02 = rgbCam33d[2];
                const float m10 = rgbCam33d[3], m11 = rgbCam33d[4], m12 = rgbCam33d[5];
                const float m20 = rgbCam33d[6], m21 = rgbCam33d[7], m22 = rgbCam33d[8];
                for (int i = 0; i < N2adj; i++) {
                    const float r = vngR[i], g = vngG[i], b = vngB[i];
                    float rs = m00*r + m01*g + m02*b;
                    float gs = m10*r + m11*g + m12*b;
                    float bs = m20*r + m21*g + m22*b;
                    if (rs < 0.f) rs = 0.f; else if (rs > 1.f) rs = 1.f;
                    if (gs < 0.f) gs = 0.f; else if (gs > 1.f) gs = 1.f;
                    if (bs < 0.f) bs = 0.f; else if (bs > 1.f) bs = 1.f;
                    vngR[i] = rs; vngG[i] = gs; vngB[i] = bs;
                }
            }

            // Post-matrix + AMaZE whole-image means: with the pre-matrix means
            // above, these bracket exactly one transform each.
            {
                double sr = 0, sg = 0, sb = 0, ar = 0, ag = 0, ab = 0;
                for (int i = 0; i < N2adj; i++) { sr += vngR[i]; sg += vngG[i]; sb += vngB[i]; }
                for (int i = 0; i < Nout;  i++) { ar += amazeR[i]; ag += amazeG[i]; ab += amazeB[i]; }
                LOGI("runStageA[DualVNG]: LMMSE post-matrix MEANS=(%.5f,%.5f,%.5f) R/G=%.3f B/G=%.3f",
                     sr / N2adj, sg / N2adj, sb / N2adj,
                     sg > 0 ? sr / sg : 0.0, sg > 0 ? sb / sg : 0.0);
                LOGI("runStageA[DualVNG]: AMaZE linear    MEANS=(%.5f,%.5f,%.5f) R/G=%.3f B/G=%.3f",
                     ar / Nout, ag / Nout, ab / Nout,
                     ag > 0 ? ar / ag : 0.0, ag > 0 ? ab / ag : 0.0);
            }

            // Step 2b removed — pmMax normalisation in 2a now handles exposure alignment.

            // Step 2c: chroma boost removed — it amplified LMMSE interpolation
            // errors at high-contrast edges into bright colour streaks.

            // ── Step 3: Luminance from AMaZE (for blend mask) ─────────────
            // Use BT.709 luma of the AMaZE linear sRGB output.
            std::unique_ptr<float[]> luma(new float[Nout]);
            for (int i = 0; i < Nout; i++) {
                luma[i] = 0.2126f * amazeR[i]
                         + 0.7152f * amazeG[i]
                         + 0.0722f * amazeB[i];
            }

            // ── Step 4: Blend mask ─────────────────────────────────────────
            std::unique_ptr<float[]> blend(new float[Nout]);
            float contrastThr = options.dualContrastThreshold;
            buildBlendMask(luma.get(), blend.get(), amazeW, amazeH,
                           &contrastThr, options.dualAutoContrast);
            meta.dualContrastThreshold = contrastThr;
            LOGI("runStageA[DualVNG]: blend mask done, threshold=%.3f", contrastThr);

            // ── Step 5: Per-pixel blend + sRGB gamma encode → RGBA FP16 ──
            // dual_blend.cpp convention: blend=1 → AMaZE (detail), blend=0 → VNG.
            W = amazeW; H = amazeH;
            rgbaF16.resize(size_t(W) * H * 4);
            const uint16_t oneHalf = floatToHalf(1.0f);
            // Sample centre pixel for diagnostic.
            {
                const int ci = (H / 2) * W + W / 2;
                LOGI("runStageA[DualVNG]: centre pixel amaze=(%.4f,%.4f,%.4f) vng=(%.4f,%.4f,%.4f) blend=%.4f",
                     amazeR[ci], amazeG[ci], amazeB[ci],
                     vngR[ci], vngG[ci], vngB[ci], blend[ci]);
            }
            for (int i = 0; i < W * H; i++) {
                const float t   = blend[i];
                const int vi = i;
                float r = t * amazeR[i] + (1.f - t) * vngR[vi];
                float g = t * amazeG[i] + (1.f - t) * vngG[vi];
                float b = t * amazeB[i] + (1.f - t) * vngB[vi];
                // sRGB gamma encode.
                r = linearToSrgb(r);
                g = linearToSrgb(g);
                b = linearToSrgb(b);

                // LMMSE highlight clip-push: LMMSE-dominant near-white pixels
                // produce pink speckles. Push them cleanly to white so the chroma
                // tint clips away rather than showing as visible speckles.
                // AE typically adds ~+0.42 EV; account for that in the threshold.
                // lmmseWeight=1 → pure LMMSE, =0 → pure AMaZE.
                const float lmmseWeight = 1.f - t;
                if (lmmseWeight > 0.2f) {
                    const float mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
                    // With AE +0.42 EV, pixels at sRGB≈0.78 clip to white.
                    // Ramp: 0 at mx=0.72, full at mx=0.95.
                    const float lo = 0.72f, hi2 = 0.95f;
                    float push = mx > lo
                        ? (mx < hi2 ? (mx - lo) / (hi2 - lo) : 1.f)
                        : 0.f;
                    push = push * push * (3.f - 2.f * push); // smoothstep
                    // Scale by LMMSE dominance (gate at 0.2, full at 1.0).
                    push *= (lmmseWeight - 0.2f) / 0.8f;
                    push = push > 1.f ? 1.f : push;
                    if (push > 0.f) {
                        // Desaturate toward luma (kills chroma tint) and lift to white.
                        const float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        // Target: push luma fully to 1.0 at full push strength.
                        const float targetLuma = luma + (1.f - luma) * push;
                        const float scale = luma > 1e-4f ? targetLuma / luma : 1.f;
                        r = luma + (r - luma) * (1.f - push);
                        g = luma + (g - luma) * (1.f - push);
                        b = luma + (b - luma) * (1.f - push);
                        r *= scale; g *= scale; b *= scale;
                    }
                }

                rgbaF16[size_t(i) * 4 + 0] = floatToHalf(r);
                rgbaF16[size_t(i) * 4 + 1] = floatToHalf(g);
                rgbaF16[size_t(i) * 4 + 2] = floatToHalf(b);
                rgbaF16[size_t(i) * 4 + 3] = oneHalf;
            }
            LOGI("runStageA[DualVNG]: blend complete → RGBA FP16 %dx%d", W, H);
        }
    }

    // Use the flip captured before AMaZE decode — dcraw_process(user_flip=0)
    // clobbers sizes.flip to 0, so reading it here would always give 0.
    int flip = exifFlip;

    // Apply EXIF orientation to the demosaiced buffer so portrait shots are
    // upright.
    //
    // Subtlety verified on-device: for SOME Canon CR2 the RCD path's output
    // buffer already comes out in the FLIPPED orientation (W<H for a portrait
    // shot) even though sizes.flip is still 5/6 — applying the transpose then
    // double-rotates back to landscape. For others the buffer is landscape and
    // the transpose is needed. So for the 90° codes (4-bit set), we GUARD on
    // the actual buffer aspect vs. the raw sensor aspect: a 90° rotation must
    // produce an aspect opposite to the sensor's. If the buffer is already in
    // that target aspect, it's pre-rotated — neutralise the flip to a no-op.
    const int rawW = raw->imgdata.sizes.raw_width;
    const int rawH = raw->imgdata.sizes.raw_height;
    if ((flip == 5 || flip == 6 || flip == 7 || flip == 8) && rawW > 0 && rawH > 0) {
        const bool sensorLandscape = rawW >= rawH;
        const bool bufferLandscape = W >= H;
        // 90° turns sensor-landscape → portrait buffer (and vice versa). If the
        // buffer orientation already differs from the sensor, it's been rotated.
        if (bufferLandscape != sensorLandscape) {
            LOGI("runStageA: flip=%d but buffer %dx%d already rotated vs sensor %dx%d — skipping",
                 flip, W, H, rawW, rawH);
            flip = 0;
        }
    }

    {
        std::vector<uint16_t> rotated;
        int rW = W, rH = H;
        if (applyFlipRgbaF16(rgbaF16.data(), W, H, flip, rotated, rW, rH)) {
            rgbaF16.swap(rotated);
            W = rW; H = rH;
            LOGI("runStageA: applied EXIF flip=%d → %dx%d", flip, W, H);
        }
    }

    meta.width       = uint32_t(W);
    meta.height      = uint32_t(H);
    meta.orientation = 1;  // upright after the flip above

    auto tDecode = std::chrono::steady_clock::now();
    LOGI("runStageA: decode %dx%d in %lld ms (RAZAMaZE+LMMSE)",
         W, H,
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tDecode - t0).count());

    // ── Lensfun lens correction (devignette + distortion + TCA) ──────────────
    // Placement per RawTherapee (improccoordinator: demosaic → transform) and
    // darktable: geometric corrections run on the demosaiced image, NOT on the
    // Bayer mosaic — resampling CFA data would destroy the pattern. This is
    // the earliest post-demosaic point: upright orientation, before CLAHE /
    // enhancement, so every later stage (preview + export share this A.tif)
    // sees corrected pixels. Vignetting is corrected in linear light inside
    // lfa_correct_rgba_f16 (the buffer here is sRGB-encoded FP16).
    //
    // Camera + lens are matched from the RAW's own metadata with a STRICT
    // matcher — if either side can't be confidently resolved, the import
    // continues without correction (product rule: only auto-retrieved-complete
    // configurations correct; nothing is ever guessed).
    bool lensRanThisImport = false;
    const char* lensSkipReason = "lens correction off";
    if (!options.lensfunDbDir.empty()) {
        lensSkipReason = "no confident camera+lens match";
        const LfDatabase* lfaDbPtr = lfa_cached_database(options.lensfunDbDir.c_str());
        const char* camMaker  = raw->imgdata.idata.make;
        const char* camModel  = raw->imgdata.idata.model;
        const char* lensMaker = raw->imgdata.lens.LensMake;
        const char* lensModel = raw->imgdata.lens.Lens;
        // Manual/adapted lenses report focal 0 in EXIF — the UI supplies the
        // override; the math is impossible without a focal length.
        const float focalMm   = options.lensfunFocalOverrideMm > 0.f
                              ? options.lensfunFocalOverrideMm
                              : raw->imgdata.other.focal_len;
        const float aperture  = raw->imgdata.other.aperture;
        // UI-confirmed overrides win over raw EXIF when provided.
        const std::string ovCam  = options.lensfunCameraId;
        const std::string ovLens = options.lensfunLensId;
        LfaMatch m;
        if (lfaDbPtr) {
            m = lfa_match_strict(
                *lfaDbPtr, camMaker,
                !ovCam.empty()  ? ovCam.c_str()  : camModel,
                lensMaker,
                !ovLens.empty() ? ovLens.c_str() : lensModel);
        }
        if (m.ok()) {
            auto tLf0 = std::chrono::steady_clock::now();
            const bool applied = lfa_correct_rgba_f16(
                rgbaF16.data(), W, H, m, focalMm, aperture);
            lensRanThisImport = true;
            auto tLf1 = std::chrono::steady_clock::now();
            LOGI("runStageA: lensfun %s ('%s' + '%s') in %lld ms",
                 applied ? "APPLIED" : "no-op (no usable calibration)",
                 m.cam->model.c_str(), m.lens->model.c_str(),
                 (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tLf1 - tLf0).count());
        } else {
            LOGI("runStageA: lensfun SKIPPED — no confident match (cam='%s %s' lens='%s') "
                 "— importing without correction",
                 camMaker ? camMaker : "", camModel ? camModel : "",
                 lensModel ? lensModel : "");
        }
    }

    // ── Chromatic aberration (Rayxie) ────────────────────────────────────────
    // ORDER (2026-09-04, owner decision): runs AFTER the Lensfun pass above.
    // Lensfun TCA is the lens's CALIBRATED per-channel geometric warp and
    // belongs with the distortion warp; Rayxie is an edge heuristic that
    // should only see the residual (purple fringe, uncalibrated lenses) —
    // same order as darktable (lens correction → defringe). Invoked through a
    // hook because the implementation lives in raw_decoder.cpp, which the
    // desktop razbatch target doesn't compile (see setCaHook in stage_a.h) —
    // null hook on desktop simply means no CA, exactly as before.
    bool rayxieClipApplied = false, rayxieDefringeApplied = false;
    if (options.caCorrectionEnabled && g_caHook) {
        const auto tCa0 = std::chrono::steady_clock::now();
        g_caHook(rgbaF16.data(), (int)W, (int)H, 2000);
        rayxieClipApplied = true;
        const auto tCa1 = std::chrono::steady_clock::now();
        LOGI("runStageA: rayxie CA applied in %lld ms",
             (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tCa1 - tCa0).count());
    } else {
        LOGI("runStageA: rayxie CA skipped (enabled=%d hook=%d)",
             options.caCorrectionEnabled ? 1 : 0, g_caHook ? 1 : 0);
    }

    // Guided-filter defringe: a second, independent residual pass for what the
    // span clip above cannot reach. Called DIRECTLY rather than through a hook
    // because rayxie_defringe.cpp is in both the Android and the desktop target,
    // so razbatch gets identical pixels. Runs before the Lensfun warp for the
    // same reason the clip does — correct fringes before resampling smears them.
    if (options.caGuidedStrength > 0.f) {
        RayxieDefringeParams gp;
        gp.strength = std::min(1.0f, options.caGuidedStrength);
        const auto tG0 = std::chrono::steady_clock::now();
        const bool did = rayxie_defringe_f16(rgbaF16.data(), (int)W, (int)H, gp);
        rayxieDefringeApplied = did;
        const auto tG1 = std::chrono::steady_clock::now();
        LOGI("runStageA: guided defringe %s in %lld ms (strength %.2f)",
             did ? "applied" : "no-op", (long long)
             std::chrono::duration_cast<std::chrono::milliseconds>(tG1 - tG0).count(),
             gp.strength);
    }

    // ── LENS-REPORT: one grep-able line per import ────────────────────────────
    {
        char lensLine[512];
        if (lensRanThisImport) lfa_report_string(lensLine, sizeof lensLine);
        else snprintf(lensLine, sizeof lensLine, "lens=NONE (%s)", lensSkipReason);
        LOGI("LENS-REPORT [RAW %ux%u]: %s | rayxie-clip=%s guided-defringe=%s(%.2f) | order=profile->rayxie",
             (unsigned)W, (unsigned)H, lensLine,
             rayxieClipApplied ? "applied" : (options.caCorrectionEnabled ? "no-hook" : "off"),
             rayxieDefringeApplied ? "applied" : "off",
             options.caGuidedStrength);
    }

    // ── Highlight desaturation ("Safe Recovery") ──────────────────────────────
    // Independent of the lensfun block above — fixes color-cast blotches in
    // blown highlights, not edge fringing. See StageAOptions comment.
    if (options.highlightDesaturateStrength > 0.f) {
        neutralizeHighlights(reinterpret_cast<__fp16*>(rgbaF16.data()), W, H,
                             options.highlightDesaturateStrength);
        LOGI("runStageA: highlight desaturate strength=%.2f applied",
             options.highlightDesaturateStrength);
    }

    // ── Post-demosaic CLAHE highlight recovery ────────────────────────────────
    // Runs directly on the sRGB FP16 buffer — applyClahe is templated on T and
    // handles __fp16 natively (same path as Stage B). shadowsBoost=0 so only
    // the highlight zone is touched; highlightsBoost lifts local contrast in
    // specular-blown regions that the HDR U-Net partially recovered pre-demosaic.
    if (options.claheHighlightsBoost > 0.f) {
        raw_v3::applyClahe<__fp16>(
            reinterpret_cast<__fp16*>(rgbaF16.data()), W, H, W, 4,
            0.f, options.claheHighlightsBoost);
        LOGI("runStageA: CLAHE highlight boost=%.2f applied", options.claheHighlightsBoost);
    }

    // ── Post-demosaic LMMSE + USM enhancement (ai-enhance, Option B FP32 path) ─
    // Bulk decode FP16→float32 once, run enhanceFp32RGBA (zero per-pixel FP16
    // conversions inside the spatial loops), then bulk encode float32→FP16 once.
    // All three passes are embarrassingly parallel and NEON-vectorisable.
    //
    // The guided-filter pass is independent: it can run on its own (no denoise,
    // no USM) when the user enables it without AI Enhance.
    // "AI Denoise" (enhanceEnabled) now means the DnCNN neural denoise at export
    // (RawV3Coordinator) is the SOLE denoiser. So when it's on we skip ALL native
    // post-demosaic smoothing here — the math LMMSE + USM (+guided) previously
    // stacked and smeared fine detail (hair, thin lines). The guided filter still
    // reaches this block on its own when AI Denoise is off.
    if (!options.enhanceEnabled && options.enhanceGuidedFilter) {
        const size_t nPx = size_t(W) * H;
        std::vector<float> rgbaF32(nPx * 4);

        // Bulk decode: uint16_t FP16 → float32
        for (size_t i = 0; i < nPx * 4; ++i) {
            // halfToFloat inline — compiler will auto-vectorise this loop
            const uint16_t h = rgbaF16[i];
            const uint32_t sign  = uint32_t(h & 0x8000u) << 16;
            const uint32_t exp16 = (h >> 10) & 0x1Fu;
            const uint32_t mant  = h & 0x3FFu;
            uint32_t r32;
            if (exp16 == 0)       r32 = sign | (mant == 0 ? 0 : ((1u + 127u - 15u) << 23) | (mant << 13));
            else if (exp16 == 31) r32 = sign | 0x7F800000u | (mant << 13);
            else                  r32 = sign | ((exp16 + (127u - 15u)) << 23) | (mant << 13);
            std::memcpy(&rgbaF32[i], &r32, 4);
        }

        lmmse_enhance::Params ep{};
        ep.useGuidedFilter = options.enhanceGuidedFilter;
        if (options.enhanceEnabled) {
            ep.windowSize         = options.enhanceWindowSize;
            ep.noiseVariance      = options.enhanceNoiseVar;
            ep.usmRadius          = options.enhanceUsmRadius;
            ep.usmAmount          = options.enhanceUsmAmount;
            ep.usmThreshold       = options.enhanceUsmThreshold;
            ep.usmEdgeThreshold   = options.enhanceUsmEdgeThreshold;
            ep.skipDenoise        = options.enhanceSkipDenoise;
            ep.skipSharpen        = options.enhanceSkipSharpen;
        } else {
            // Guided-filter only: skip both LMMSE denoise and USM sharpening.
            ep.skipDenoise  = true;
            ep.skipSharpen  = true;
        }
        lmmse_enhance::enhanceFp32RGBA(rgbaF32.data(), W, H, ep);

        // Bulk encode: float32 → uint16_t FP16
        for (size_t i = 0; i < nPx * 4; ++i) {
            uint32_t x32; std::memcpy(&x32, &rgbaF32[i], 4);
            const uint32_t sign = (x32 >> 16) & 0x8000u;
            int32_t  exp = int32_t((x32 >> 23) & 0xFFu) - 127 + 15;
            uint32_t mnt = x32 & 0x7FFFFFu;
            if (exp <= 0)    rgbaF16[i] = uint16_t(sign);
            else if (exp >= 0x1F) rgbaF16[i] = uint16_t(sign | 0x7C00u);
            else             rgbaF16[i] = uint16_t(sign | (uint32_t(exp) << 10) | (mnt >> 13));
        }
        LOGI("runStageA: ai-enhance applied (enabled=%d guided=%d window=%d noiseVar=%.6f usmAmt=%.2f)",
             int(options.enhanceEnabled), int(options.enhanceGuidedFilter),
             ep.windowSize, ep.noiseVariance, ep.usmAmount);
    }

    auto tConv = std::chrono::steady_clock::now();
    LOGI("runStageA: RGBA_F16 conversion in %lld ms",
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tConv - tDecode).count());

    if (!writeStageATiff(outTifPath, rgbaF16.data(), uint32_t(W), uint32_t(H))) {
        meta.errorMessage = "writeStageATiff failed";
        return meta;
    }

    auto tWrite = std::chrono::steady_clock::now();
    LOGI("runStageA: TIFF write in %lld ms; total %lld ms",
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tWrite - tConv).count(),
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tWrite - t0).count());

    meta.success = true;
    return meta;
}

// Serialise in-place A.tif rewrites vs mmap readers lives in
// tiff_mmap_io.cpp (shared_mutex + writeStageATiffAtomic). See GOTCHAS /
// tombstone 2026-09-09 SIGBUS BUS_ADRERR on Infinix.

// ── Bake a luma-scale map into the FP16 Stage A TIFF, in place ───────────────
// See stage_a.h. Reads every strip into a contiguous buffer, multiplies each
// pixel's RGB by the bilinearly-resampled scale, then rewrites the file.
bool applyLumaScaleToStageA(const std::string& tifPath,
                            const float* scale, int scaleW, int scaleH) {
    if (!scale || scaleW <= 0 || scaleH <= 0) return false;
    StageATiffReader* rd = openStageATiff(tifPath);
    if (!rd) { LOGE("applyLumaScaleToStageA: open failed %s", tifPath.c_str()); return false; }
    const StageATiffHeader hdr = getStageATiffHeader(rd);
    const uint32_t W = hdr.width, H = hdr.height, rps = hdr.rowsPerStrip;
    if (W == 0 || H == 0 || rps == 0) { closeStageATiff(rd); return false; }
    if (!stageATiffPayloadInBounds(rd)) {
        LOGE("applyLumaScaleToStageA: truncated A.tif %s", tifPath.c_str());
        closeStageATiff(rd);
        return false;
    }

    // IEEE binary16 → float32 (mirrors runStageA's bulk decode).
    auto h2f = [](uint16_t h) -> float {
        const uint32_t sign  = uint32_t(h & 0x8000u) << 16;
        const uint32_t exp16 = (h >> 10) & 0x1Fu;
        const uint32_t mant  = h & 0x3FFu;
        uint32_t r32;
        if (exp16 == 0)       r32 = sign | (mant == 0 ? 0u : (((1u + 127u - 15u) << 23) | (mant << 13)));
        else if (exp16 == 31) r32 = sign | 0x7F800000u | (mant << 13);
        else                  r32 = sign | ((exp16 + (127u - 15u)) << 23) | (mant << 13);
        float f; std::memcpy(&f, &r32, 4); return f;
    };

    std::vector<uint16_t> out(size_t(W) * H * 4);
    for (uint32_t y = 0; y < H; ++y) {
        const uint32_t strip = y / rps;
        const uint32_t rowInStrip = y % rps;
        const uint16_t* srcRow = getStageATiffStrip(rd, strip) + size_t(rowInStrip) * W * 4;
        uint16_t* dstRow = out.data() + size_t(y) * W * 4;
        const float fy = (H > 1) ? (float(y) * float(scaleH - 1) / float(H - 1)) : 0.f;
        int y0 = int(fy); if (y0 < 0) y0 = 0; if (y0 > scaleH - 1) y0 = scaleH - 1;
        int y1 = (y0 + 1 < scaleH) ? y0 + 1 : scaleH - 1;
        const float wy = fy - float(y0);
        for (uint32_t x = 0; x < W; ++x) {
            const float fx = (W > 1) ? (float(x) * float(scaleW - 1) / float(W - 1)) : 0.f;
            int x0 = int(fx); if (x0 < 0) x0 = 0; if (x0 > scaleW - 1) x0 = scaleW - 1;
            int x1 = (x0 + 1 < scaleW) ? x0 + 1 : scaleW - 1;
            const float wx = fx - float(x0);
            const float s00 = scale[size_t(y0) * scaleW + x0];
            const float s10 = scale[size_t(y0) * scaleW + x1];
            const float s01 = scale[size_t(y1) * scaleW + x0];
            const float s11 = scale[size_t(y1) * scaleW + x1];
            float s = (s00 * (1.f - wx) + s10 * wx) * (1.f - wy)
                    + (s01 * (1.f - wx) + s11 * wx) * wy;
            if (s < 0.5f) s = 0.5f; else if (s > 2.0f) s = 2.0f;
            const size_t i = size_t(x) * 4;
            dstRow[i + 0] = floatToHalf(h2f(srcRow[i + 0]) * s);
            dstRow[i + 1] = floatToHalf(h2f(srcRow[i + 1]) * s);
            dstRow[i + 2] = floatToHalf(h2f(srcRow[i + 2]) * s);
            dstRow[i + 3] = srcRow[i + 3];  // alpha unchanged
        }
    }
    closeStageATiff(rd);   // release mmap BEFORE overwriting the file
    const bool ok = writeStageATiffAtomic(tifPath, out.data(), W, H);
    LOGI("applyLumaScaleToStageA: %ux%u (scaleMap %dx%d) → %s", W, H, scaleW, scaleH, ok ? "ok" : "FAIL");
    return ok;
}

// See stage_a.h. Reads every strip, samples each pixel's RGB through the
// per-channel 256×3 LUT (bilinear, matching the Stage C toneCurveLut tap),
// then rewrites the file. Used to bake the Camera Color Profile curve into
// A.tif so Stage B/C inherit it without re-applying the LUT per stage.
bool applyToneCurveToStageA(const std::string& tifPath, const uint8_t* lut768) {
    if (!lut768) return false;
    StageATiffReader* rd = openStageATiff(tifPath);
    if (!rd) { LOGE("applyToneCurveToStageA: open failed %s", tifPath.c_str()); return false; }
    const StageATiffHeader hdr = getStageATiffHeader(rd);
    const uint32_t W = hdr.width, H = hdr.height, rps = hdr.rowsPerStrip;
    if (W == 0 || H == 0 || rps == 0) { closeStageATiff(rd); return false; }
    if (!stageATiffPayloadInBounds(rd)) {
        LOGE("applyToneCurveToStageA: truncated A.tif (file=%zu need off=%llu bytes=%llu) %s",
             getStageATiffFileSize(rd),
             (unsigned long long)hdr.pixelDataOffset,
             (unsigned long long)hdr.pixelDataBytes,
             tifPath.c_str());
        closeStageATiff(rd);
        return false;
    }

    // IEEE binary16 → float32 (mirrors applyLumaScaleToStageA).
    auto h2f = [](uint16_t h) -> float {
        const uint32_t sign  = uint32_t(h & 0x8000u) << 16;
        const uint32_t exp16 = (h >> 10) & 0x1Fu;
        const uint32_t mant  = h & 0x3FFu;
        uint32_t r32;
        if (exp16 == 0)       r32 = sign | (mant == 0 ? 0u : (((1u + 127u - 15u) << 23) | (mant << 13)));
        else if (exp16 == 31) r32 = sign | 0x7F800000u | (mant << 13);
        else                  r32 = sign | ((exp16 + (127u - 15u)) << 23) | (mant << 13);
        float f; std::memcpy(&f, &r32, 4); return f;
    };
    // Per-channel curve tap: clamp v to [0,1], scale to 0..255, bilerp between
    // adjacent LUT entries. ch = 0/1/2 → R/G/B; layout is interleaved (i*3+ch).
    auto tap = [&](int ch, float v) -> float {
        float x = (v < 0.f ? 0.f : (v > 1.f ? 1.f : v)) * 255.f;
        int   i0 = int(x);
        int   i1 = i0 < 255 ? i0 + 1 : 255;
        float t  = x - float(i0);
        float a  = float(lut768[i0 * 3 + ch]) * (1.f / 255.f);
        float bv = float(lut768[i1 * 3 + ch]) * (1.f / 255.f);
        return a + (bv - a) * t;
    };

    std::vector<uint16_t> out;
    try {
        out.resize(size_t(W) * H * 4);
    } catch (const std::bad_alloc&) {
        LOGE("applyToneCurveToStageA: OOM allocating %ux%u buffer", W, H);
        closeStageATiff(rd);
        return false;
    }
    for (uint32_t y = 0; y < H; ++y) {
        const uint32_t strip = y / rps;
        const uint32_t rowInStrip = y % rps;
        const uint16_t* srcRow = getStageATiffStrip(rd, strip) + size_t(rowInStrip) * W * 4;
        uint16_t* dstRow = out.data() + size_t(y) * W * 4;
        for (uint32_t x = 0; x < W; ++x) {
            const size_t i = size_t(x) * 4;
            dstRow[i + 0] = floatToHalf(tap(0, h2f(srcRow[i + 0])));
            dstRow[i + 1] = floatToHalf(tap(1, h2f(srcRow[i + 1])));
            dstRow[i + 2] = floatToHalf(tap(2, h2f(srcRow[i + 2])));
            dstRow[i + 3] = srcRow[i + 3];  // alpha unchanged
        }
    }
    closeStageATiff(rd);   // release mmap BEFORE overwriting the file
    // Atomic rewrite under Stage A file lock (see writeStageATiffAtomic).
    const bool ok = writeStageATiffAtomic(tifPath, out.data(), W, H);
    LOGI("applyToneCurveToStageA: %ux%u → %s", W, H, ok ? "ok" : "FAIL");
    return ok;
}

bool applyLensfunToStageA(const std::string& tifPath,
                          const std::string& camMaker, const std::string& camModel,
                          const std::string& lensMaker, const std::string& lensModel,
                          float focalMm, float aperture,
                          const std::string& lensfunDbDir) {
    if (lensfunDbDir.empty()) return false;
    const LfDatabase* db = lfa_cached_database(lensfunDbDir.c_str());
    if (!db) { LOGE("applyLensfunToStageA: db load failed %s", lensfunDbDir.c_str()); return false; }
    // Same STRICT matcher the RAW path uses; empty makers are fine (no demote).
    LfaMatch m = lfa_match_strict(*db,
                                  camMaker.empty()  ? nullptr : camMaker.c_str(),
                                  camModel.empty()  ? nullptr : camModel.c_str(),
                                  lensMaker.empty() ? nullptr : lensMaker.c_str(),
                                  lensModel.empty() ? nullptr : lensModel.c_str());
    if (!m.ok()) {
        LOGI("applyLensfunToStageA: no confident match (cam='%s %s' lens='%s %s') — skip",
             camMaker.c_str(), camModel.c_str(), lensMaker.c_str(), lensModel.c_str());
        return false;
    }
    StageATiffReader* rd = openStageATiff(tifPath);
    if (!rd) { LOGE("applyLensfunToStageA: open failed %s", tifPath.c_str()); return false; }
    const StageATiffHeader hdr = getStageATiffHeader(rd);
    const uint32_t W = hdr.width, H = hdr.height, rps = hdr.rowsPerStrip;
    if (W == 0 || H == 0 || rps == 0) { closeStageATiff(rd); return false; }
    if (!stageATiffPayloadInBounds(rd)) {
        LOGE("applyLensfunToStageA: truncated A.tif %s", tifPath.c_str());
        closeStageATiff(rd);
        return false;
    }
    // lfa_correct_rgba_f16 operates directly on FP16 (uint16) RGBA — no float
    // round-trip needed. Copy strips into a contiguous buffer, correct, write back.
    std::vector<uint16_t> buf(size_t(W) * H * 4);
    for (uint32_t y = 0; y < H; ++y) {
        const uint16_t* srcRow = getStageATiffStrip(rd, y / rps) + size_t(y % rps) * W * 4;
        std::memcpy(buf.data() + size_t(y) * W * 4, srcRow, size_t(W) * 4 * sizeof(uint16_t));
    }
    closeStageATiff(rd);   // release mmap BEFORE overwriting the file
    const bool applied = lfa_correct_rgba_f16(buf.data(), int(W), int(H), m, focalMm, aperture);
    if (!applied) {
        LOGI("applyLensfunToStageA: matched '%s'+'%s' but no-op (no usable calibration)",
             m.cam->model.c_str(), m.lens->model.c_str());
        return false;
    }
    const bool ok = writeStageATiffAtomic(tifPath, buf.data(), W, H);
    LOGI("applyLensfunToStageA: %ux%u ('%s' + '%s') focal=%.1f → %s",
         W, H, m.cam->model.c_str(), m.lens->model.c_str(), focalMm, ok ? "ok" : "FAIL");
    return ok;
}

}  // namespace raw_v3
