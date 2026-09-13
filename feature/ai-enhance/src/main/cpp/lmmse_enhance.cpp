/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Two-stage image enhancement pipeline:
 *   Stage 1: LMMSE (Wiener) denoiser — self-masking via local variance ratio
 *   Stage 2: Thresholded Unsharp Mask — edge sharpening with noise-gate
 *
 * Design:
 *   • Process Y channel only (BT.601 luma) to avoid color desaturation
 *   • Chroma (Cb, Cr) passes through untouched
 *   • Inner loops are linear, cache-friendly, NEON auto-vectorizable
 *   • Parallelized across CPU cores via std::thread
 *
 * Input: ARGB_8888 (Android Bitmap pixel format: A[31:24] R[23:16] G[15:8] B[7:0])
 * All math in float to avoid quantization artifacts during intermediate steps.
 */

#include "lmmse_enhance.h"

#include <cmath>
#include <cstring>
#include <algorithm>
#include <vector>
#include <thread>

namespace lmmse_enhance {

namespace {

// ── Parallel dispatch ─────────────────────────────────────────────────────────

template <typename Fn>
void parallelFor(int count, Fn&& body) {
    if (count <= 0) return;
    const int hw = static_cast<int>(std::thread::hardware_concurrency());
    int workers = std::max(1, hw == 0 ? 4 : hw);
    workers = std::min(workers, std::max(1, count / 16));
    if (workers <= 1) { body(0, count); return; }
    std::vector<std::thread> pool;
    pool.reserve(workers - 1);
    const int chunk = (count + workers - 1) / workers;
    for (int wk = 0; wk < workers; ++wk) {
        const int begin = wk * chunk;
        const int end = std::min(begin + chunk, count);
        if (begin >= end) break;
        if (wk == workers - 1) body(begin, end);
        else pool.emplace_back([&body, begin, end] { body(begin, end); });
    }
    for (auto& t : pool) t.join();
}

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// ── BT.601 Luma extraction (gamma-domain) ─────────────────────────────────────

inline float rgbToLuma(float r, float g, float b) {
    return r * 0.299f + g * 0.587f + b * 0.114f;
}

// ── Separable Gaussian blur (1D kernel, applied H then V) ─────────────────────

// Build a 1D Gaussian kernel for the given radius (sigma = radius).
// Returns kernel size (always odd). Kernel written to `out` which must be ≥ 2*maxRadius+1.
int buildGaussianKernel(float radius, float* out, int maxKernelSize) {
    const float sigma = std::max(0.3f, radius);
    const int halfSize = std::min(static_cast<int>(std::ceil(sigma * 3.0f)), (maxKernelSize - 1) / 2);
    const int kernelSize = 2 * halfSize + 1;
    float sum = 0.f;
    for (int i = 0; i < kernelSize; ++i) {
        const float x = static_cast<float>(i - halfSize);
        out[i] = std::exp(-(x * x) / (2.f * sigma * sigma));
        sum += out[i];
    }
    // Normalize
    for (int i = 0; i < kernelSize; ++i) out[i] /= sum;
    return kernelSize;
}

// Separable Gaussian blur on a planar float buffer [w×h].
void gaussianBlurPlane(const float* src, float* dst, int w, int h, float radius) {
    float kernel[21]; // max kernel for radius=3 → size ≈ 19
    const int kSize = buildGaussianKernel(radius, kernel, 21);
    const int halfK = kSize / 2;

    // Horizontal pass → tmp
    std::vector<float> tmp(static_cast<size_t>(w) * h);
    parallelFor(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const float* row = src + static_cast<size_t>(y) * w;
            float* tRow = tmp.data() + static_cast<size_t>(y) * w;
            for (int x = 0; x < w; ++x) {
                float acc = 0.f;
                for (int k = -halfK; k <= halfK; ++k) {
                    acc += kernel[k + halfK] * row[clampi(x + k, 0, w - 1)];
                }
                tRow[x] = acc;
            }
        }
    });

    // Vertical pass → dst
    parallelFor(w, [&](int xB, int xE) {
        for (int x = xB; x < xE; ++x) {
            for (int y = 0; y < h; ++y) {
                float acc = 0.f;
                for (int k = -halfK; k <= halfK; ++k) {
                    acc += kernel[k + halfK] * tmp[static_cast<size_t>(clampi(y + k, 0, h - 1)) * w + x];
                }
                dst[static_cast<size_t>(y) * w + x] = acc;
            }
        }
    });
}

// ── Optional Guided Filter (edge-aware smoothing) ─────────────────────────────
//
// Simple guided filter for a single planar float buffer. The input image is
// used as its own guidance map, producing an edge-preserving smoothed result.
// Keeps hard edges intact while flattening small tonal fluctuations.
void guidedFilterPlane(const float* src, float* dst, int w, int h,
                       int radius, float epsilon) {
    const size_t n = static_cast<size_t>(w) * h;

    // Mean of src and src² (Gaussian-window local estimates)
    std::vector<float> mean(n), meanSq(n), srcSq(n);
    gaussianBlurPlane(src, mean.data(), w, h, static_cast<float>(radius));
    for (size_t i = 0; i < n; ++i) srcSq[i] = src[i] * src[i];
    gaussianBlurPlane(srcSq.data(), meanSq.data(), w, h, static_cast<float>(radius));

    // Variance and linear coefficients a, b for q = a·I + b
    std::vector<float> var(n), a(n), b(n);
    for (size_t i = 0; i < n; ++i) {
        var[i] = std::max(0.f, meanSq[i] - mean[i] * mean[i]);
        a[i]   = var[i] / (var[i] + epsilon);
        b[i]   = mean[i] * (1.f - a[i]);
    }

    // Mean of a and b, then reconstruct output
    std::vector<float> meanA(n), meanB(n);
    gaussianBlurPlane(a.data(), meanA.data(), w, h, static_cast<float>(radius));
    gaussianBlurPlane(b.data(), meanB.data(), w, h, static_cast<float>(radius));

    for (size_t i = 0; i < n; ++i) {
        dst[i] = meanA[i] * src[i] + meanB[i];
    }
}

// ── Mid-frequency Unsharp Mask core (shared by both pipeline overloads) ─────────
//
// Instead of sharpening the full high-frequency band H = Y - Yblur, we compute
// two blurs (coarse and fine) and sharpen only their difference:
//
//   mid = Yfine - Ycoarse   (texture / mid-frequency band)
//
// This avoids amplifying very fine noise (below the fine blur) and avoids
// halos at hard step edges (above the coarse blur).
//
// The same soft ramp + local-variance edge suppression from the previous USM
// core is applied to the mid band.
void runMidFreqUsm(float* Y, int width, int height,
                   float radiusLow, float radiusHigh,
                   float amount, float threshold, float edgeThreshold) {
    const size_t n = static_cast<size_t>(width) * height;
    std::vector<float> Ylow(n), Yhigh(n);

    // radiusLow  = larger blur → captures lower frequencies (Ylow)
    // radiusHigh = smaller blur → captures higher frequencies (Yhigh)
    gaussianBlurPlane(Y, Ylow.data(),  width, height, radiusLow);  // coarse
    gaussianBlurPlane(Y, Yhigh.data(), width, height, radiusHigh); // fine

    // Mid-frequency band = fine - coarse
    std::vector<float> mid(n);
    for (size_t i = 0; i < n; ++i) mid[i] = Yhigh[i] - Ylow[i];

    parallelFor(height, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const size_t rowBase = static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                const size_t idx = rowBase + x;
                const float hf    = mid[idx];
                const float absHf = std::fabs(hf);

                // 3×3 local variance for edge detection
                float sum = 0.f, sumSq = 0.f;
                for (int dy = -1; dy <= 1; ++dy) {
                    const size_t sr = static_cast<size_t>(clampi(y + dy, 0, height - 1)) * width;
                    for (int dx = -1; dx <= 1; ++dx) {
                        const float v = Y[sr + clampi(x + dx, 0, width - 1)];
                        sum += v; sumSq += v * v;
                    }
                }
                const float mu         = sum / 9.f;
                const float variance   = std::max(0.f, sumSq / 9.f - mu * mu);
                const float sigmaLocal = std::sqrt(variance);

                // Soft ramp: silent below threshold, full gain at 2×threshold
                const float softRamp = clampf((absHf - threshold) / (threshold + 1e-5f), 0.f, 1.f);

                // Edge suppression: kills gain when local contrast spikes past edgeThreshold
                const float edgeSuppress = clampf((sigmaLocal - edgeThreshold) / (edgeThreshold + 1e-5f), 0.f, 1.f);

                const float adaptiveGain = amount * softRamp * (1.f - edgeSuppress);
                if (adaptiveGain > 0.f)
                    Y[idx] += hf * adaptiveGain;
            }
        }
    });
}

} // namespace

// ══════════════════════════════════════════════════════════════════════════════
// Main pipeline
// ══════════════════════════════════════════════════════════════════════════════

void enhance(uint8_t* pixels, int width, int height, int stride, const Params& params) {
    if (!pixels || width <= 0 || height <= 0) return;
    if (params.skipDenoise && params.skipSharpen) return;

    const size_t n = static_cast<size_t>(width) * height;

    // ── Decompose ARGB_8888 → planar R, G, B, A (float, [0–255]) ──────────
    std::vector<float> R(n), G(n), B(n);
    std::vector<uint8_t> A(n);

    parallelFor(height, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const uint8_t* row = pixels + static_cast<size_t>(y) * stride;
            const size_t base = static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                // ARGB_8888 on Android (little-endian): byte order is [R, G, B, A]
                // Actually stored as: pixel = (A<<24)|(R<<16)|(G<<8)|B
                // Reading as bytes: [B, G, R, A] on little-endian
                const uint8_t* px = row + x * 4;
                R[base + x] = static_cast<float>(px[0]);  // R
                G[base + x] = static_cast<float>(px[1]);  // G
                B[base + x] = static_cast<float>(px[2]);  // B
                A[base + x] = px[3];                       // A
            }
        }
    });

    // ── Extract luma (Y) for processing ────────────────────────────────────
    std::vector<float> Y(n);
    for (size_t i = 0; i < n; ++i) {
        Y[i] = rgbToLuma(R[i], G[i], B[i]);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stage 1: LMMSE Denoise (on Y channel only)
    // ══════════════════════════════════════════════════════════════════════════
    //
    // For each pixel (x,y), over an N×N window W:
    //   μ = mean(W)
    //   σ² = variance(W)
    //   Y_out = μ + max(0, (σ² - σ_n²) / (σ² + ε)) · (Y_in - μ)
    //
    // Self-masking: flat areas → factor ≈ 0 → output = μ (noise erased)
    //               edges     → factor ≈ 1 → output = pixel (preserved)

    if (!params.skipDenoise) {
        const int winSize = params.windowSize | 1; // force odd
        const int halfWin = winSize / 2;
        const float sigmaN2 = params.noiseVariance; // σ_n² in pixel² units
        const float epsilon = 1e-6f;

        std::vector<float> Yout(n);

        parallelFor(height, [&](int yB, int yE) {
            for (int y = yB; y < yE; ++y) {
                const size_t rowBase = static_cast<size_t>(y) * width;
                for (int x = 0; x < width; ++x) {
                    const size_t idx = rowBase + x;

                    // Compute local mean and variance over N×N window
                    float sum = 0.f;
                    float sumSq = 0.f;
                    int count = 0;

                    for (int dy = -halfWin; dy <= halfWin; ++dy) {
                        const int sy = clampi(y + dy, 0, height - 1);
                        const size_t sRowBase = static_cast<size_t>(sy) * width;
                        for (int dx = -halfWin; dx <= halfWin; ++dx) {
                            const int sx = clampi(x + dx, 0, width - 1);
                            const float val = Y[sRowBase + sx];
                            sum += val;
                            sumSq += val * val;
                            ++count;
                        }
                    }

                    const float invN = 1.0f / static_cast<float>(count);
                    const float mu = sum * invN;
                    const float variance = (sumSq * invN) - (mu * mu);

                    // LMMSE formula: output = μ + max(0, (σ² - σ_n²)/(σ² + ε)) · (pixel - μ)
                    const float factor = std::max(0.0f, (variance - sigmaN2) / (variance + epsilon));
                    Yout[idx] = mu + factor * (Y[idx] - mu);
                }
            }
        });

        // Replace Y with denoised result
        Y = std::move(Yout);
    }

    // ── Optional Guided Filter refinement (edge-aware smoothing) ───────────────
    if (params.useGuidedFilter) {
        std::vector<float> Yguided(n);
        guidedFilterPlane(Y.data(), Yguided.data(), width, height, 2, 1e-3f);
        Y = std::move(Yguided);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stage 2: Mid-frequency Unsharp Mask (on denoised / guided Y)
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 1. Gaussian blur Y twice: coarse (radiusLow) and fine (radiusHigh)
    // 2. Extract mid frequencies: M = Yfine - Ycoarse
    // 3. Adaptive gain on M: soft threshold + local-variance edge suppression

    if (!params.skipSharpen && params.usmAmount > 0.f) {
        runMidFreqUsm(Y.data(), width, height,
                      params.usmRadius * 2.f, params.usmRadius,
                      params.usmAmount,
                      params.usmThreshold,
                      params.usmEdgeThreshold);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reconstruct RGB from modified Y + original chroma ratio
    // ══════════════════════════════════════════════════════════════════════════
    //
    // Apply the luma change as a multiplicative ratio so chroma is preserved:
    //   ratio = Y_new / Y_old
    //   R_out = R_in × ratio, G_out = G_in × ratio, B_out = B_in × ratio
    // For very dark pixels (Y_old < 1), use additive delta instead.

    parallelFor(height, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            uint8_t* row = pixels + static_cast<size_t>(y) * stride;
            const size_t base = static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                const size_t idx = base + x;
                const float yOld = rgbToLuma(R[idx], G[idx], B[idx]);
                const float yNew = Y[idx];

                float rOut, gOut, bOut;
                if (yOld > 1.0f) {
                    // Multiplicative ratio preserves color
                    const float ratio = yNew / yOld;
                    rOut = R[idx] * ratio;
                    gOut = G[idx] * ratio;
                    bOut = B[idx] * ratio;
                } else {
                    // Very dark pixel — additive to avoid division instability
                    const float delta = yNew - yOld;
                    rOut = R[idx] + delta;
                    gOut = G[idx] + delta;
                    bOut = B[idx] + delta;
                }

                // Clamp to [0, 255] and write back
                uint8_t* px = row + x * 4;
                px[0] = static_cast<uint8_t>(clampf(rOut, 0.f, 255.f) + 0.5f);
                px[1] = static_cast<uint8_t>(clampf(gOut, 0.f, 255.f) + 0.5f);
                px[2] = static_cast<uint8_t>(clampf(bOut, 0.f, 255.f) + 0.5f);
                px[3] = A[idx]; // Alpha unchanged
            }
        }
    });
}

// ══════════════════════════════════════════════════════════════════════════════
// FP32 path — RAW Stage A post-demosaic (packed RGBA float, [0,1] sRGB)
// ══════════════════════════════════════════════════════════════════════════════
//
// Called on the float staging buffer BEFORE floatToHalf packing, so zero
// scalar FP16 conversions are needed inside any spatial loop.
//
// BT.709 luma coefficients for sRGB-gamma-encoded values — the gamma function
// is monotone so the ratio trick still preserves chromaticity correctly.
// noiseVariance and usmThreshold are pre-scaled to [0,1] domain by caller.

namespace {
inline float rgbToLuma709(float r, float g, float b) {
    return r * 0.2126f + g * 0.7152f + b * 0.0722f;
}
} // anonymous namespace

void enhanceFp32RGBA(float* rgba, int width, int height, const Params& params) {
    if (!rgba || width <= 0 || height <= 0) return;
    if (params.skipDenoise && params.skipSharpen) return;

    const size_t n = static_cast<size_t>(width) * height;

    // ── Extract Y luma plane (single pass, cache-friendly) ───────────────────
    std::vector<float> Y(n);
    parallelFor(height, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const size_t base = static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                const float* px = rgba + (base + x) * 4;
                Y[base + x] = rgbToLuma709(px[0], px[1], px[2]);
            }
        }
    });

    // ── Stage 1: LMMSE Denoise ────────────────────────────────────────────────
    if (!params.skipDenoise) {
        const int   winSize  = params.windowSize | 1;
        const int   halfWin  = winSize / 2;
        const float sigmaN2  = params.noiseVariance;
        const float epsilon  = 1e-10f;
        std::vector<float> Yout(n);
        parallelFor(height, [&](int yB, int yE) {
            for (int y = yB; y < yE; ++y) {
                const size_t rowBase = static_cast<size_t>(y) * width;
                for (int x = 0; x < width; ++x) {
                    float sum = 0.f, sumSq = 0.f;
                    int count = 0;
                    for (int dy = -halfWin; dy <= halfWin; ++dy) {
                        const size_t sr = static_cast<size_t>(clampi(y + dy, 0, height - 1)) * width;
                        for (int dx = -halfWin; dx <= halfWin; ++dx) {
                            const float v = Y[sr + clampi(x + dx, 0, width - 1)];
                            sum += v; sumSq += v * v; ++count;
                        }
                    }
                    const float invN     = 1.f / float(count);
                    const float mu       = sum * invN;
                    const float variance = sumSq * invN - mu * mu;
                    const float factor   = std::max(0.f, (variance - sigmaN2) / (variance + epsilon));
                    Yout[rowBase + x]    = mu + factor * (Y[rowBase + x] - mu);
                }
            }
        });
        Y = std::move(Yout);
    }

    // ── Optional Guided Filter refinement (edge-aware smoothing) ─────────────
    if (params.useGuidedFilter) {
        std::vector<float> Yguided(n);
        guidedFilterPlane(Y.data(), Yguided.data(), width, height, 2, 1e-3f);
        Y = std::move(Yguided);
    }

    // ── Stage 2: Mid-frequency USM ───────────────────────────────────────────
    if (!params.skipSharpen && params.usmAmount > 0.f) {
        runMidFreqUsm(Y.data(), width, height,
                      params.usmRadius * 2.f, params.usmRadius,
                      params.usmAmount,
                      params.usmThreshold,
                      params.usmEdgeThreshold);
    }

    // ── Reconstruct RGB via multiplicative luma ratio ─────────────────────────
    // Dark threshold: 1/255 in [0,1] domain (one 8-bit step).
    constexpr float DARK_THR = 1.f / 255.f;
    parallelFor(height, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const size_t base = static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                float* px = rgba + (base + x) * 4;
                const float yOld = rgbToLuma709(px[0], px[1], px[2]);
                const float yNew = Y[base + x];
                if (yOld > DARK_THR) {
                    const float ratio = yNew / yOld;
                    px[0] = clampf(px[0] * ratio, 0.f, 1.f);
                    px[1] = clampf(px[1] * ratio, 0.f, 1.f);
                    px[2] = clampf(px[2] * ratio, 0.f, 1.f);
                } else {
                    const float delta = yNew - yOld;
                    px[0] = clampf(px[0] + delta, 0.f, 1.f);
                    px[1] = clampf(px[1] + delta, 0.f, 1.f);
                    px[2] = clampf(px[2] + delta, 0.f, 1.f);
                }
                // px[3] alpha unchanged
            }
        }
    });
}

// ══════════════════════════════════════════════════════════════════════════════
// Fusion tone-mapping — subject-masked shadow/highlight/saturation grade
// ══════════════════════════════════════════════════════════════════════════════
//
// Unlike enhance()/enhanceFp32RGBA() (which denoise + sharpen), this applies a
// tonal *look*: it lifts/crushes shadows and highlights and scales saturation,
// weighted by a sharp subject mask so the subject pops while the background is
// only lightly touched. The mask's crisp U2Net→SAM boundary means no haloing.

// Bilinear-sample a maskW×maskH probability grid at normalized (u,v) ∈ [0,1].
inline float sampleMask(const float* mask, int maskW, int maskH, float u, float v) {
    const float fx = clampf(u, 0.f, 1.f) * (maskW - 1);
    const float fy = clampf(v, 0.f, 1.f) * (maskH - 1);
    const int x0 = static_cast<int>(fx); const int x1 = std::min(x0 + 1, maskW - 1);
    const int y0 = static_cast<int>(fy); const int y1 = std::min(y0 + 1, maskH - 1);
    const float dx = fx - x0; const float dy = fy - y0;
    const float m00 = mask[static_cast<size_t>(y0) * maskW + x0];
    const float m10 = mask[static_cast<size_t>(y0) * maskW + x1];
    const float m01 = mask[static_cast<size_t>(y1) * maskW + x0];
    const float m11 = mask[static_cast<size_t>(y1) * maskW + x1];
    const float top = m00 + (m10 - m00) * dx;
    const float bot = m01 + (m11 - m01) * dx;
    return top + (bot - top) * dy;
}

void enhanceFusion(uint8_t* pixels, int width, int height, int stride,
                   const float* subjectMask, int maskW, int maskH,
                   float shadowBoost, float highlightBoost, float saturation) {
    if (!pixels || width <= 0 || height <= 0) return;

    // Background still receives this fraction of every adjustment, so the grade
    // reads as a photo rather than a cut-out subject on an untouched plate.
    constexpr float kBackgroundFloor = 0.25f;
    // Peak luma shift (in [0,1]) at boost = ±1. 0.5 lets a full shadow boost
    // lift pure black to mid-grey — strong, but it's the documented maximum.
    constexpr float kToneScale = 0.5f;

    const float sB = clampf(shadowBoost,    -1.f, 1.f);
    const float hB = clampf(highlightBoost, -1.f, 1.f);
    const float sat = clampf(saturation,    -1.f, 1.f);
    const bool useMask = subjectMask != nullptr && maskW > 0 && maskH > 0;
    const float invW = width  > 1 ? 1.f / (width  - 1) : 0.f;
    const float invH = height > 1 ? 1.f / (height - 1) : 0.f;

    parallelFor(height, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            uint8_t* row = pixels + static_cast<size_t>(y) * stride;
            const float v = y * invH;
            for (int x = 0; x < width; ++x) {
                uint8_t* px = row + x * 4;
                float r = static_cast<float>(px[0]);
                float g = static_cast<float>(px[1]);
                float b = static_cast<float>(px[2]);

                // Subject weight → how strongly this pixel is graded.
                const float m = useMask
                    ? clampf(sampleMask(subjectMask, maskW, maskH, x * invW, v), 0.f, 1.f)
                    : 1.f;
                const float regionW = kBackgroundFloor + (1.f - kBackgroundFloor) * m;

                // ── Tonal grade on luma ──────────────────────────────────────
                const float lOld = rgbToLuma(r, g, b);      // [0,255]
                const float L = lOld * (1.f / 255.f);       // [0,1]
                const float shadowMask = (1.f - L) * (1.f - L); // strong in shadows
                const float highMask   = L * L;                 // strong in highlights
                const float dL = (sB * kToneScale * shadowMask +
                                  hB * kToneScale * highMask) * regionW;
                const float lNew = clampf((L + dL) * 255.f, 0.f, 255.f); // back to [0,255]

                if (lOld > 1.f) {
                    const float ratio = lNew / lOld;   // multiplicative → preserves chroma
                    r *= ratio; g *= ratio; b *= ratio;
                } else {
                    const float delta = lNew - lOld;   // additive in deep shadows
                    r += delta; g += delta; b += delta;
                }

                // ── Saturation around the (re-toned) luma ────────────────────
                if (sat != 0.f) {
                    const float satFactor = 1.f + sat * regionW;
                    const float lp = rgbToLuma(r, g, b);
                    r = lp + (r - lp) * satFactor;
                    g = lp + (g - lp) * satFactor;
                    b = lp + (b - lp) * satFactor;
                }

                px[0] = static_cast<uint8_t>(clampf(r, 0.f, 255.f) + 0.5f);
                px[1] = static_cast<uint8_t>(clampf(g, 0.f, 255.f) + 0.5f);
                px[2] = static_cast<uint8_t>(clampf(b, 0.f, 255.f) + 0.5f);
                // px[3] alpha unchanged
            }
        }
    });
}

} // namespace lmmse_enhance
