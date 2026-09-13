/*
 * StudioRoom — RAW Pipeline v3
 * dual_blend.cpp — port of RawTherapee's `rt_algo.cc` blend-mask helpers
 * for the AMaZE+VNG dual-demosaic path.
 *
 * Substitutions vs RT:
 *   • luminance domain [0..65535] → [0..1] via kContrastScaleFp = 12.5
 *   • minLuminance/maxLuminance rescaled to [0..1]
 *   • OpenMP + SSE branches dropped (single-thread Pass 1)
 *   • RT's vector helpers (LVFU, STVFU, vsqrtf, SQRV) → scalar
 *   • gaussianBlur call → our existing stage_blur.h
 *
 * All other constants (sigmoid -16 + 16/threshold * val, 0.5 contrast
 * weight, tilesize=80, minTileVariance=0.5) are unchanged so the
 * thresholds the user picks in the UI mean the same thing they do in RT.
 */

#include "dual_blend.h"
#include "stage_blur.h"   // gaussianBlurSeparableShared (single-channel via 3-plane buffer)

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

#define LOG_TAG "RawV3.DualBlend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

namespace {

// Adapted constant: RT used `0.0625 / 327.68` against [0..65535] values.
// Our luminance lives in [0..1] so we multiply by 65535 to keep the
// perceptual contrast threshold the same. = 0.0625 / 327.68 * 65535 ≈ 12.5
constexpr float kContrastScaleFp = 0.0625f / 327.68f * 65535.0f;

// Auto-contrast bounds rescaled from RT's 16-bit values.
constexpr float kMinLuminance      = 2000.f / 65535.f;     // ≈ 0.0305
constexpr float kMaxLuminance      = 20000.f / 65535.f;    // ≈ 0.305
constexpr float kMinTileVariance   = 1e-5f;                // unitless ratio (scaled for [0..1] luma)

// RT's sigmoid: result in ]0..1], inflexion at val=threshold yields 0.5.
// Verbatim algebra.
inline float calcBlendFactor(float val, float threshold) {
    const float x = -16.f + (16.f / threshold) * val;
    return 0.5f * (1.f + x / std::sqrt(1.f + x * x));
}

inline float tileAverage(const float* data, int W, int tileY, int tileX,
                         int tilesize) {
    float avg = 0.f;
    for (int y = tileY; y < tileY + tilesize; ++y) {
        const float* row = data + size_t(y) * W;
        for (int x = tileX; x < tileX + tilesize; ++x) {
            avg += row[x];
        }
    }
    return avg / float(tilesize * tilesize);
}

inline float tileVariance(const float* data, int W, int tileY, int tileX,
                          int tilesize, float avg) {
    float var = 0.f;
    for (int y = tileY; y < tileY + tilesize; ++y) {
        const float* row = data + size_t(y) * W;
        for (int x = tileX; x < tileX + tilesize; ++x) {
            const float d = row[x] - avg;
            var += d * d;
        }
    }
    // RT normalises by `tilesize² * avg` so the variance scales with image
    // brightness — keeps the threshold invariant across exposure levels.
    return var / (float(tilesize * tilesize) * (avg > 1e-6f ? avg : 1e-6f));
}

// Per-tile contrast threshold picker. Computes a Sobel-ish 4-point
// gradient at each interior pixel of the tile, then sweeps the
// threshold 1..99 looking for the value where the sigmoid sum lands
// within the tile's "flat" budget. RT verbatim.
float calcContrastThreshold(const float* luminance, int W,
                            int tileY, int tileX, int tilesize) {
    const int rows = tilesize - 4;
    const int cols = tilesize - 4;
    std::vector<std::vector<float>> blend(rows, std::vector<float>(cols));

    const float scale = kContrastScaleFp;

    for (int j = tileY + 2; j < tileY + tilesize - 2; ++j) {
        for (int i = tileX + 2; i < tileX + tilesize - 2; ++i) {
            const float dx1 = luminance[size_t(j) * W + (i + 1)] - luminance[size_t(j) * W + (i - 1)];
            const float dy1 = luminance[size_t(j + 1) * W + i] - luminance[size_t(j - 1) * W + i];
            const float dx2 = luminance[size_t(j) * W + (i + 2)] - luminance[size_t(j) * W + (i - 2)];
            const float dy2 = luminance[size_t(j + 2) * W + i] - luminance[size_t(j - 2) * W + i];
            const float contrast = std::sqrt(dx1 * dx1 + dy1 * dy1 + dx2 * dx2 + dy2 * dy2) * scale;
            blend[j - tileY - 2][i - tileX - 2] = contrast;
        }
    }

    const float limit = float((tilesize - 4) * (tilesize - 4)) / 100.f;

    int c;
    for (c = 1; c < 100; ++c) {
        const float contrastThreshold = float(c) / 100.f;
        float sum = 0.f;
        for (int j = 0; j < rows; ++j) {
            for (int i = 0; i < cols; ++i) {
                sum += calcBlendFactor(blend[j][i], contrastThreshold);
            }
        }
        if (sum <= limit) break;
    }
    return float(c + 1) / 100.f;
}

}  // namespace

void buildBlendMask(const float* luminance, float* blend, int W, int H,
                    float* contrastThreshold, bool autoContrast) {
    // ── Auto-contrast tile search ──────────────────────────────────────
    if (autoContrast) {
        for (int pass = 0; pass < 2; ++pass) {
            const int tilesize = 80 / (pass + 1);
            const int skip = (pass == 0) ? tilesize : tilesize / 4;
            const int numTilesW = std::max(1, W / skip - 3 * pass);
            const int numTilesH = std::max(1, H / skip - 3 * pass);
            std::vector<std::vector<float>> variances(numTilesH, std::vector<float>(numTilesW));

            for (int i = 0; i < numTilesH; ++i) {
                const int tileY = i * skip;
                for (int j = 0; j < numTilesW; ++j) {
                    const int tileX = j * skip;
                    if (tileY + tilesize > H || tileX + tilesize > W) {
                        variances[i][j] = std::numeric_limits<float>::infinity();
                        continue;
                    }
                    const float avg = tileAverage(luminance, W, tileY, tileX, tilesize);
                    if (avg < kMinLuminance || avg > kMaxLuminance) {
                        variances[i][j] = std::numeric_limits<float>::infinity();
                        continue;
                    }
                    float v = tileVariance(luminance, W, tileY, tileX, tilesize, avg);
                    if (v < kMinTileVariance) v = std::numeric_limits<float>::infinity();
                    variances[i][j] = v;
                }
            }

            float minvar = std::numeric_limits<float>::infinity();
            int minI = 0, minJ = 0;
            for (int i = 0; i < numTilesH; ++i) {
                for (int j = 0; j < numTilesW; ++j) {
                    if (variances[i][j] < minvar) {
                        minvar = variances[i][j];
                        minI = i;
                        minJ = j;
                    }
                }
            }

            // Pass 0: if we found a flat tile (variance ≤ 1), commit.
            // Pass 1: accept up to variance 8 (RT's looser fallback).
            const float acceptVar = (pass == 0) ? 1.f : 8.f;
            if (minvar <= acceptVar || pass == 1) {
                const int minY = skip * minI;
                const int minX = skip * minJ;
                if (minvar <= acceptVar) {
                    *contrastThreshold = calcContrastThreshold(
                        luminance, W, minY, minX, tilesize);
                } else {
                    *contrastThreshold = 0.f;   // no flat tile → AMaZE only
                }
                LOGI("buildBlendMask: autoContrast pass %d picked threshold=%.3f (minvar=%.3f at %d,%d)",
                     pass, *contrastThreshold, minvar, minX, minY);
                if (minvar <= 1.f) break;   // good enough; skip pass 1
            }
        }
    }

    // ── Apply threshold to the whole image ─────────────────────────────
    if (*contrastThreshold <= 0.f) {
        // No flat tile found → fall back to pure AMaZE (blend=1 = AMaZE).
        const size_t n = size_t(W) * H;
        for (size_t k = 0; k < n; ++k) blend[k] = 1.f;
        return;
    }

    const float scale = kContrastScaleFp;
    const float thr = *contrastThreshold;

    // Initial scan: interior pixels get sigmoid'd contrast; borders are
    // replicated below.
    for (int j = 2; j < H - 2; ++j) {
        for (int i = 2; i < W - 2; ++i) {
            const float dx1 = luminance[size_t(j) * W + (i + 1)] - luminance[size_t(j) * W + (i - 1)];
            const float dy1 = luminance[size_t(j + 1) * W + i] - luminance[size_t(j - 1) * W + i];
            const float dx2 = luminance[size_t(j) * W + (i + 2)] - luminance[size_t(j) * W + (i - 2)];
            const float dy2 = luminance[size_t(j + 2) * W + i] - luminance[size_t(j - 2) * W + i];
            const float contrast = std::sqrt(dx1 * dx1 + dy1 * dy1 + dx2 * dx2 + dy2 * dy2) * scale;
            // RT semantics: blend=1 at low contrast (use VNG), blend=0
            // at high contrast (use AMaZE). calcBlendFactor returns 0
            // at val=0 and ~1 at val=threshold — that maps as RT does:
            // low contrast → low calcBlendFactor → low blend → primary?
            // Wait — RT actually computes 1 - calcBlendFactor here so
            // that low-contrast pixels get blend=1. Let me trace.
            //
            // Actually RT writes `blend[j][i] = calcBlendFactor(contrast, threshold)`
            // and the consumer (dual_demosaic_RT.cc) does
            // `intp(blend, amaze, vng)` which in RT's `intp` is
            // `mix(a, b, blend) = a*(1-blend) + b*blend`. So blend=0 → AMaZE,
            // blend=1 → VNG. calcBlendFactor goes from 0 (at val=0) to ~1
            // (at val >> threshold). So HIGH contrast → high blend → VNG.
            //
            // Wait that contradicts "AMaZE for detail, VNG for flat".
            // Re-reading RT's dual_demosaic_RT.cc more carefully:
            //   amaze in `red/green/blue`, vng4 in `redTmp/greenTmp/blueTmp`
            //   red[i][j] = intp(blend[i][j], red[i][j], redTmp[i][j])
            // RT's `intp(a, x, y) = a*x + (1-a)*y` per LIM_helpers — so
            // blend=1 means PURE amaze (red), blend=0 means PURE vng4.
            // High contrast → calcBlendFactor→1 → amaze. Low contrast →
            // 0 → vng4. THAT matches the description.
            //
            // So our convention here:
            //   blend=1 = AMaZE (detail areas, high contrast)
            //   blend=0 = VNG   (flat areas, low contrast)
            // And the Stage A caller's lerp goes: `out = lerp(amaze, vng, 1-blend)`
            // or equivalently `out = blend*amaze + (1-blend)*vng`.
            blend[size_t(j) * W + i] = calcBlendFactor(contrast, thr);
        }
    }

    // Border replicate.
    for (int j = 0; j < 2; ++j) {
        for (int i = 2; i < W - 2; ++i) {
            blend[size_t(j) * W + i] = blend[2 * W + i];
        }
    }
    for (int j = H - 2; j < H; ++j) {
        for (int i = 2; i < W - 2; ++i) {
            blend[size_t(j) * W + i] = blend[size_t(H - 3) * W + i];
        }
    }
    for (int j = 0; j < H; ++j) {
        blend[size_t(j) * W + 0] = blend[size_t(j) * W + 2];
        blend[size_t(j) * W + 1] = blend[size_t(j) * W + 2];
        blend[size_t(j) * W + (W - 2)] = blend[size_t(j) * W + (W - 3)];
        blend[size_t(j) * W + (W - 1)] = blend[size_t(j) * W + (W - 3)];
    }

    // RT uses sigma=2 (radius=4). At full RAW resolution (6000×4000) that
    // is only an 8-pixel-wide transition zone — not enough to hide AMaZE/VNG
    // luma differences in flat areas like sky, producing vignette-like banding.
    // sigma=8 (radius=16) gives a ~32-pixel transition zone, which is wide
    // enough to dissolve the seams on flat uniform regions without visibly
    // softening the spatial selectivity of the blend on fine texture.
    std::vector<float> packed(size_t(W) * H * 3);
    std::vector<float> packedOut(size_t(W) * H * 3);
    for (size_t i = 0; i < size_t(W) * H; ++i) {
        packed[i * 3 + 0] = blend[i];
        packed[i * 3 + 1] = blend[i];
        packed[i * 3 + 2] = blend[i];
    }
    gaussianBlurSeparableShared(packed.data(), packedOut.data(), W, H, /*radius=*/20);
    // Clamp blend to [0.15, 0.85] after blurring so no region is ever pure
    // AMaZE or pure VNG. Without this, flat sky (blend≈0, pure VNG) and
    // textured areas (blend≈1, pure AMaZE) differ enough in luma to produce
    // a visible band at the boundary even with a wide Gaussian.
    for (size_t i = 0; i < size_t(W) * H; ++i) {
        float b = packedOut[i * 3 + 0];
        if (b < 0.15f) b = 0.15f;
        else if (b > 0.85f) b = 0.85f;
        blend[i] = b;
    }
}

}  // namespace raw_v3
