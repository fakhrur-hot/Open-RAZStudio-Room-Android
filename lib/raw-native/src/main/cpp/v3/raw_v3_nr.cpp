/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Frequency-separation noise reduction — the single-image (spatial) analog of
 * GCam's denoise stack. We can't do GCam's temporal multi-frame merge (we have
 * one already-captured image), so we lean on the spatial steps GCam uses after
 * the merge:
 *
 *   • Frequency separation: split luma + chroma into low-freq (base) and
 *     high-freq (detail) bands via a separable box blur at increasing radii
 *     (a 2-level Laplacian-style decomposition).
 *   • Luma denoise (edge-aware soft-threshold on the high-freq band): shrink
 *     the detail residual where its magnitude looks like noise, but keep large
 *     residuals (true edges/texture) intact. This is the guided/NLM-style step
 *     done cheaply with a per-pixel soft threshold scaled by local activity.
 *   • Chroma denoise (wide low-pass on chroma only): the eye tolerates soft
 *     colour transitions, so we aggressively smooth Cb/Cr with a large radius
 *     to wipe shadow blotches without touching luma sharpness.
 *
 * Operating space: gamma-encoded sRGB in [0,1] (may exceed 1.0 in highlights).
 * BT.601 luma matches the CLAHE kernel. Strengths [0,1] map the slider to a
 * smooth blend so 0 is a true no-op.
 *
 * The header contract is unchanged — Stage B / Stage C call sites untouched.
 */

#include "raw_v3_nr.h"

#include <cmath>
#include <algorithm>
#include <thread>
#include <vector>

static_assert(sizeof(__fp16) == 2, "__fp16 must be IEEE binary16 (2 bytes)");

namespace raw_v3 {

namespace {

template <typename Fn>
void parallelFor(int count, Fn&& body) {
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

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

// BT.601 luma/chroma decomposition on the (gamma-encoded) values.
//   Y  = 0.299 R + 0.587 G + 0.114 B
//   Cb = B - Y,  Cr = R - Y   (un-scaled; we only blur and recombine)
inline void rgbToYcc(float r, float g, float b, float& y, float& cb, float& cr) {
    y  = r * 0.299f + g * 0.587f + b * 0.114f;
    cb = b - y;
    cr = r - y;
}
inline void yccToRgb(float y, float cb, float cr, float& r, float& g, float& b) {
    r = y + cr;
    b = y + cb;
    g = (y - 0.299f * r - 0.114f * b) / 0.587f;
}

// Separable box blur of one planar float channel, radius r, clamp borders.
// Output written to dst (resized as needed). Uses tmp scratch internally.
void boxBlurPlane(const std::vector<float>& src, std::vector<float>& dst,
                  int w, int h, int r) {
    if (r <= 0) { dst = src; return; }
    std::vector<float> tmp(size_t(w) * h);
    const float norm = 1.0f / float(2 * r + 1);
    parallelFor(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const float* s = src.data() + size_t(y) * w;
            float* t = tmp.data() + size_t(y) * w;
            // Running-sum sliding window for O(w) per row.
            float acc = 0.f;
            for (int k = -r; k <= r; ++k) acc += s[clampi(k, 0, w - 1)];
            for (int x = 0; x < w; ++x) {
                t[x] = acc * norm;
                const float outv = s[clampi(x - r, 0, w - 1)];
                const float inv  = s[clampi(x + r + 1, 0, w - 1)];
                acc += inv - outv;
            }
        }
    });
    dst.resize(size_t(w) * h);
    parallelFor(w, [&](int xB, int xE) {
        for (int x = xB; x < xE; ++x) {
            float acc = 0.f;
            for (int k = -r; k <= r; ++k) acc += tmp[size_t(clampi(k, 0, h - 1)) * w + x];
            for (int y = 0; y < h; ++y) {
                dst[size_t(y) * w + x] = acc * norm;
                const float outv = tmp[size_t(clampi(y - r, 0, h - 1)) * w + x];
                const float inv  = tmp[size_t(clampi(y + r + 1, 0, h - 1)) * w + x];
                acc += inv - outv;
            }
        }
    });
}

// Soft-shrink: pull a residual toward zero by [thresh], keeping the sign and
// leaving large (edge) residuals mostly intact. Classic wavelet denoise op.
// `noiseSquash` is the residual-keep factor for values below threshold —
// callers ramp this toward 0 as NR strength rises so heavy settings can
// actually erase grain instead of leaving 15% residual.
inline float softShrink(float v, float thresh, float noiseSquash) {
    const float a = std::fabs(v);
    if (a <= thresh) return v * noiseSquash;      // mostly noise — squash
    const float k = (a - thresh) / a;             // edge — keep, minus thresh
    return v * k;
}

// Edge-preserving guided filter (He, Sun, Tang 2010), self-guided. Produces
// the same shape low-pass as a box blur in flat regions, but preserves edges
// because the local linear model (a, b) collapses to (0, mean) on flat areas
// (where variance ≈ 0) and to (1, 0) at high-variance edges (where signal
// equals its own coefficient). Result: flat-area smoothing matches box-blur,
// edges stay sharp. `eps` is the regularisation — larger = smoother flats
// at the cost of mild edge attack. 1e-3 chosen so the low-pass for NR
// matches the original box-blur strength on flat sky while preserving
// edges that the box version smeared.
void guidedFilterSelfNR(const std::vector<float>& p, std::vector<float>& out,
                        int w, int h, int r, float eps) {
    const size_t n = size_t(w) * h;
    std::vector<float> meanP, corrPP, p2(n);
    for (size_t i = 0; i < n; ++i) p2[i] = p[i] * p[i];
    boxBlurPlane(p,  meanP,  w, h, r);
    boxBlurPlane(p2, corrPP, w, h, r);
    std::vector<float> a(n), b(n);
    // Adaptive eps: photon shot noise ∝ sqrt(luma), so the threshold between
    // "noise" and "edge" should scale with the local luma — a fixed eps
    // produces a hard a∈{0,1} step at sharp edges (visible as a thin ring
    // around branches, hair, etc.). Scale eps with sqrt(meanP): larger eps
    // in dark regions (where shot noise dominates and ringing is invisible),
    // smaller eps in bright regions (where rings would show but noise is
    // already low). At meanP=0.5 the scaled eps matches the caller-supplied
    // value; at meanP=0.05 it's ~3× larger, at meanP=1.0 it's ~1.4× larger.
    for (size_t i = 0; i < n; ++i) {
        const float var = corrPP[i] - meanP[i] * meanP[i];
        // Shot-noise model: σ² ≈ k * L, so eps ∝ L. We use sqrt(L) anchored
        // to mid-grey so the multiplier stays near 1 in the working luma
        // range. The floor of 0.02 (~5% reflectance) caps the multiplier so
        // pitch-black pixels don't get unboundedly large eps.
        const float Lref = std::max(meanP[i], 0.02f);
        const float epsScale = std::sqrt(Lref / 0.5f);
        const float epsLocal = eps * epsScale;
        const float ai = var / (var + epsLocal);
        a[i] = ai;
        b[i] = meanP[i] * (1.0f - ai);
    }
    std::vector<float> meanA, meanB;
    boxBlurPlane(a, meanA, w, h, r);
    boxBlurPlane(b, meanB, w, h, r);
    out.resize(n);
    for (size_t i = 0; i < n; ++i) out[i] = meanA[i] * p[i] + meanB[i];
}

} // namespace

// Bilinear lookup of the low-res subject mask at (xNorm,yNorm) in [0,1].
// Returns 0 if mask is null. Clamps to edges.
static inline float sampleMask(const float* mask, int mw, int mh,
                                float xNorm, float yNorm) {
    if (mask == nullptr || mw <= 0 || mh <= 0) return 0.f;
    const float fx = xNorm * float(mw - 1);
    const float fy = yNorm * float(mh - 1);
    const int x0 = std::max(0, std::min(mw - 1, int(fx)));
    const int y0 = std::max(0, std::min(mh - 1, int(fy)));
    const int x1 = std::min(mw - 1, x0 + 1);
    const int y1 = std::min(mh - 1, y0 + 1);
    const float tx = fx - float(x0);
    const float ty = fy - float(y0);
    const float a = mask[y0 * mw + x0];
    const float b = mask[y0 * mw + x1];
    const float c = mask[y1 * mw + x0];
    const float d = mask[y1 * mw + x1];
    const float ab = a + (b - a) * tx;
    const float cd = c + (d - c) * tx;
    return ab + (cd - ab) * ty;
}

static inline float smoothstepF(float a, float b, float v) {
    if (b <= a) return v >= b ? 1.f : 0.f;
    float t = (v - a) / (b - a);
    if (t < 0.f) t = 0.f; else if (t > 1.f) t = 1.f;
    return t * t * (3.f - 2.f * t);
}

template <typename T>
void applyNoiseReduction(
    T* pixels, int w, int h, int strideInPixels, int channels,
    float lumaNR, float chromaNR, float blueNR, float redNR,
    const float* subjectMask, int maskW, int maskH) {

    if (w <= 0 || h <= 0 || pixels == nullptr) return;
    lumaNR   = lumaNR   < 0.f ? 0.f : (lumaNR   > 1.f ? 1.f : lumaNR);
    chromaNR = chromaNR < 0.f ? 0.f : (chromaNR > 1.f ? 1.f : chromaNR);
    blueNR   = blueNR   < 0.f ? 0.f : (blueNR   > 1.f ? 1.f : blueNR);
    redNR    = redNR    < 0.f ? 0.f : (redNR    > 1.f ? 1.f : redNR);
    const bool maskActive = subjectMask != nullptr && maskW > 0 && maskH > 0;
    // Soft-clamp chroma: the chroma blend factor was usable only up to ~0.5
    // (anything stronger smudged colour detail). Squash slider 0..1 → 0..0.5
    // so the full slider range stays useful. Slider 100 = old 50; slider 50
    // = old 25. Old values that wrote chromaNR > 0.5 still saturate at 0.5,
    // which is the correct destructive-limit anyway.
    chromaNR *= 0.25f;
    // blueNR rides on top of chromaNR Cb axis; redNR is the symmetric knob
    // for Cr. Each scales 0..1 → 0..0.25 effective range.
    blueNR *= 0.25f;
    redNR  *= 0.25f;
    if (lumaNR <= 0.f && chromaNR <= 0.f && blueNR <= 0.f && redNR <= 0.f) return;

    const size_t n = size_t(w) * h;
    std::vector<float> Y(n), Cb(n), Cr(n);

    // Decompose once.
    parallelFor(h, [&](int yBegin, int yEnd) {
        for (int yy = yBegin; yy < yEnd; ++yy) {
            const T* row = pixels + size_t(yy) * strideInPixels * channels;
            const size_t base = size_t(yy) * w;
            for (int xx = 0; xx < w; ++xx) {
                const T* px = row + size_t(xx) * channels;
                float y, cb, cr;
                rgbToYcc(float(px[0]), float(px[1]), float(px[2]), y, cb, cr);
                Y[base + xx] = y; Cb[base + xx] = cb; Cr[base + xx] = cr;
            }
        }
    });

    // Scale kernel radii with image size so the effect is resolution-stable
    // (a 2560px preview and a 5500px export get visually matched denoising).
    const int longSide = std::max(w, h);
    // Luma: wider base blur — at full-res a 2-3px radius still leaves grain
    // visible because each grain is multiple pixels. Scale closer to 1/600
    // so a 5500px export gets r≈9, comparable visual smoothing to preview.
    const int rLuma = std::max(2, longSide / 600);    // ~4px @ 2560, ~9px @ 5500
    const int rWide = std::max(3, longSide / 300);    // ~8px @ 2560, ~18px @ 5500

    // ── Luma NR: 3-pass progressive schedule ─────────────────────────────
    //
    //   Pass 1: 25% blend everywhere — initial uniform pass.
    //   Pass 2: 25% blend, EXCLUDE sharp edges via 10%-feathered taper.
    //   Pass 3: 25% blend, EXCLUDE sharp edges AND subject via 10%-feathered
    //           taper (compound).
    //
    // Cumulative effect in flat background: 1-(1-0.25*lumaNR)^3.
    // At slider 100 this lands ≈58% denoised — progressive accumulation
    // avoids the "plastic" look of a single aggressive blend, and the
    // activity map adapts between passes (re-computed on the cleaned data)
    // so each later pass sees a more reliable noise/edge classification.
    //
    // "Feathered taper" means a smoothstep transition band ~10% wide
    // (0.04..0.14 in activity space; 0.45..0.55 in mask probability) so the
    // exclusion ramps smoothly rather than gating on a hard threshold.
    if (lumaNR > 0.f) {
        const float passBlend = 0.125f * lumaNR;
        const float noiseSquash = std::max(0.02f, 0.18f - 0.16f * lumaNR);
        const float kThresh = 0.30f + 0.50f * lumaNR;

        for (int pass = 0; pass < 3; ++pass) {
            // Each pass widens the low-pass slightly so subsequent passes
            // attack progressively lower-frequency grain that survived
            // earlier passes.
            const int rPass = rLuma + pass * std::max(1, rLuma / 2);
            std::vector<float> base;
            // Edge-aware low-pass via self-guided filter — was a plain box
            // blur, which smeared edges and forced the activity-map taper
            // (lines below) to do all the edge-preservation work. The guided
            // filter collapses to (a=0, b=mean) on flat regions (matching
            // box-blur smoothing exactly) and to (a=1, b=0) at high-variance
            // edges (where the "base" tracks the signal, so detail=0 there
            // and no shrinkage fires). Net result: flats clean up like the
            // box version did, edges keep their sharpness instead of bleeding.
            // eps tuned to 1e-3 — same regularisation as the smart-sharpen
            // guided filter in raw_v3_detail.cpp.
            guidedFilterSelfNR(Y, base, w, h, rPass, 1e-3f);
            std::vector<float> detailAbs(n);
            for (size_t i = 0; i < n; ++i) detailAbs[i] = std::fabs(Y[i] - base[i]);
            std::vector<float> activity;
            boxBlurPlane(detailAbs, activity, w, h, rWide);

            const bool excludeEdges   = pass >= 1;
            const bool excludeSubject = pass >= 2 && maskActive;

            parallelFor(h, [&](int yB, int yE) {
                for (int y = yB; y < yE; ++y) {
                    const size_t b = size_t(y) * w;
                    const float yNorm = (h > 1) ? float(y) / float(h - 1) : 0.f;
                    for (int x = 0; x < w; ++x) {
                        const size_t i = b + x;
                        const float detail = Y[i] - base[i];
                        const float thr = activity[i] * kThresh + 1e-4f;
                        const float kept = softShrink(detail, thr, noiseSquash);
                        const float denoised = base[i] + kept;

                        float localK = passBlend;
                        // Feathered edge exclusion (passes 2 & 3): 10%-wide
                        // smoothstep band centred on the activity threshold.
                        if (excludeEdges) {
                            const float edgeW = smoothstepF(0.04f, 0.14f, activity[i]);
                            localK *= (1.f - edgeW);
                        }
                        // Feathered subject exclusion (pass 3 only): 10%-wide
                        // band on the mask probability around 0.5.
                        if (excludeSubject) {
                            const float xNorm = (w > 1) ? float(x) / float(w - 1) : 0.f;
                            const float m = sampleMask(subjectMask, maskW, maskH, xNorm, yNorm);
                            const float subjW = smoothstepF(0.45f, 0.55f, m);
                            localK *= (1.f - subjW);
                        }
                        Y[i] = Y[i] + (denoised - Y[i]) * localK;
                    }
                }
            });
        }
    }

    // ── Chroma NR: wide low-pass on Cb/Cr only ────────────────────────────
    // blueNR adds extra Cb-only smoothing on top of the chromaNR blend; redNR
    // is the symmetric Cr-only knob. Blue and red Bayer cells both have lower
    // WB gain than green and contribute disproportionately to chroma noise on
    // Canon sensors — typically blue worse than red, hence two separate knobs.
    if (chromaNR > 0.f || blueNR > 0.f || redNR > 0.f) {
        std::vector<float> cbLow, crLow;
        if (chromaNR > 0.f || blueNR > 0.f) boxBlurPlane(Cb, cbLow, w, h, rWide);
        if (chromaNR > 0.f || redNR  > 0.f) boxBlurPlane(Cr, crLow, w, h, rWide);
        // Per-axis blends via exponential-compounding so the combined value
        // stays ≤ 1: result = a + (1-a)*b. Pushing past 1 would mean negative
        // weight on the original, which produces ringing.
        const float cbBlend = std::min(1.f, chromaNR + (1.f - chromaNR) * blueNR);
        const float crBlend = std::min(1.f, chromaNR + (1.f - chromaNR) * redNR);
        parallelFor(h, [&](int yB, int yE) {
            for (int y = yB; y < yE; ++y) {
                const size_t b = size_t(y) * w;
                for (int x = 0; x < w; ++x) {
                    const size_t i = b + x;
                    if (cbBlend > 0.f) {
                        Cb[i] = Cb[i] + (cbLow[i] - Cb[i]) * cbBlend;
                    }
                    if (crBlend > 0.f) {
                        Cr[i] = Cr[i] + (crLow[i] - Cr[i]) * crBlend;
                    }
                }
            }
        });
    }

    // Recompose.
    parallelFor(h, [&](int yBegin, int yEnd) {
        for (int yy = yBegin; yy < yEnd; ++yy) {
            T* row = pixels + size_t(yy) * strideInPixels * channels;
            const size_t base = size_t(yy) * w;
            for (int xx = 0; xx < w; ++xx) {
                float r, g, b;
                yccToRgb(Y[base + xx], Cb[base + xx], Cr[base + xx], r, g, b);
                T* px = row + size_t(xx) * channels;
                px[0] = static_cast<T>(std::max(0.0f, r));
                px[1] = static_cast<T>(std::max(0.0f, g));
                px[2] = static_cast<T>(std::max(0.0f, b));
            }
        }
    });
}

template void applyNoiseReduction<__fp16>(__fp16*, int, int, int, int, float, float, float, float,
                                          const float*, int, int);
template void applyNoiseReduction<float>(float*, int, int, int, int, float, float, float, float,
                                         const float*, int, int);

} // namespace raw_v3
