/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * See raw_v3_detail.h. Port of the v2 MacroProcessor detail ops to the v3
 * float buffers. Order of application matches v2's effects stack:
 *   1. Clarity + Texture + Sharpness (unsharp masks)
 *   2. Smart Sharpness (edge-gated unsharp, optional subject feather)
 *   3. Film Grain + Wash-out
 */

#include "raw_v3_detail.h"

#include <cmath>
#include <algorithm>
#include <thread>
#include <vector>
#include <cstdint>

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
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Separable box blur (radius r) over a 3-plane float image held as one
// interleaved RGB buffer (used internally for blurs of the working planes).
// Operates on a planar float buffer [w*h] for one channel at a time.
void boxBlurPlane(const std::vector<float>& src, std::vector<float>& dst,
                  int w, int h, int r) {
    std::vector<float> tmp(size_t(w) * h);
    const float norm = 1.0f / float(2 * r + 1);
    // Horizontal.
    parallelFor(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const float* s = src.data() + size_t(y) * w;
            float* t = tmp.data() + size_t(y) * w;
            for (int x = 0; x < w; ++x) {
                float acc = 0.f;
                for (int dx = -r; dx <= r; ++dx) acc += s[clampi(x + dx, 0, w - 1)];
                t[x] = acc * norm;
            }
        }
    });
    // Vertical.
    dst.resize(size_t(w) * h);
    parallelFor(w, [&](int xB, int xE) {
        for (int x = xB; x < xE; ++x) {
            for (int y = 0; y < h; ++y) {
                float acc = 0.f;
                for (int dy = -r; dy <= r; ++dy)
                    acc += tmp[size_t(clampi(y + dy, 0, h - 1)) * w + x];
                dst[size_t(y) * w + x] = acc * norm;
            }
        }
    });
}

// À trous (holes) wavelet single-level low-pass, separable. Kernel = [1,4,6,4,1]/16
// with sample spacing `h` (1, 2, 4, ...) → equivalent to a wider Gaussian at each
// scale WITHOUT downsampling, so reconstruction is exact (sum of bands + coarsest
// residual == original). Used to build a 3-band detail decomposition where
// band1+band2 are the "texture" mid-frequencies darktable's contrast-equalizer
// boosts (band0 = noise; band3+ = large structure). Edge-aware weighting is
// applied at the reconstruction site, not here.
void atrousLowpass(const std::vector<float>& src, std::vector<float>& dst,
                   int w, int h, int spacing) {
    static const float K[5] = {1.f/16.f, 4.f/16.f, 6.f/16.f, 4.f/16.f, 1.f/16.f};
    std::vector<float> tmp(size_t(w) * h);
    // Horizontal.
    parallelFor(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const float* s = src.data() + size_t(y) * w;
            float* t = tmp.data() + size_t(y) * w;
            for (int x = 0; x < w; ++x) {
                float acc = 0.f;
                for (int k = -2; k <= 2; ++k)
                    acc += K[k + 2] * s[clampi(x + k * spacing, 0, w - 1)];
                t[x] = acc;
            }
        }
    });
    // Vertical.
    dst.resize(size_t(w) * h);
    parallelFor(w, [&](int xB, int xE) {
        for (int x = xB; x < xE; ++x) {
            for (int y = 0; y < h; ++y) {
                float acc = 0.f;
                for (int k = -2; k <= 2; ++k)
                    acc += K[k + 2] * tmp[size_t(clampi(y + k * spacing, 0, h - 1)) * w + x];
                dst[size_t(y) * w + x] = acc;
            }
        }
    });
}

// Edge-preserving guided filter (He et al. 2010), self-guided on one plane.
// Produces an edge-aware smoothed version of [p]: flat regions are smoothed,
// edges are preserved. The detail layer (p - guided) is then the high-freq
// texture/edges WITHOUT the ringing a Gaussian unsharp mask would add — this
// is what makes the sharpening halo-free. [r] is the window radius, [eps] the
// regularisation (larger = more smoothing of low-variance/flat areas).
void guidedFilterSelf(const std::vector<float>& p, std::vector<float>& out,
                      int w, int h, int r, float eps) {
    const size_t n = size_t(w) * h;
    std::vector<float> meanP, corrPP, p2(n);
    for (size_t i = 0; i < n; ++i) p2[i] = p[i] * p[i];
    boxBlurPlane(p,  meanP,  w, h, r);
    boxBlurPlane(p2, corrPP, w, h, r);
    // a = var / (var + eps); b = meanP * (1 - a). Per-pixel.
    std::vector<float> a(n), b(n);
    for (size_t i = 0; i < n; ++i) {
        const float var = corrPP[i] - meanP[i] * meanP[i];
        const float ai = var / (var + eps);
        a[i] = ai;
        b[i] = meanP[i] * (1.0f - ai);
    }
    std::vector<float> meanA, meanB;
    boxBlurPlane(a, meanA, w, h, r);
    boxBlurPlane(b, meanB, w, h, r);
    out.resize(n);
    for (size_t i = 0; i < n; ++i) out[i] = meanA[i] * p[i] + meanB[i];
}

// Deterministic per-pixel hash → [-1, 1]. Avoids std::rand (not thread-safe)
// and gives stable grain that doesn't shimmer between re-bakes.
inline float hashNoise(uint32_t x, uint32_t y, uint32_t seed) {
    uint32_t n = x * 1973u + y * 9277u + seed * 26699u;
    n = (n << 13) ^ n;
    n = n * (n * n * 15731u + 789221u) + 1376312589u;
    return 1.0f - float(n & 0x7fffffffu) / 1073741824.0f; // [-1, 1]
}

inline float bilerpMask(const float* mask, int mw, int mh, float u, float v) {
    if (!mask || mw <= 0 || mh <= 0) return 1.0f;
    float fx = clampf(u, 0.f, 1.f) * (mw - 1);
    float fy = clampf(v, 0.f, 1.f) * (mh - 1);
    int x0 = int(fx), y0 = int(fy);
    int x1 = std::min(x0 + 1, mw - 1), y1 = std::min(y0 + 1, mh - 1);
    float tx = fx - x0, ty = fy - y0;
    float a = mask[size_t(y0) * mw + x0], b = mask[size_t(y0) * mw + x1];
    float c = mask[size_t(y1) * mw + x0], d = mask[size_t(y1) * mw + x1];
    return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
}

} // namespace

template <typename T>
void applyDetail(
    T* pixels, int w, int h, int strideInPixels, int channels,
    const DetailParams& p, const float* subjectMask, int maskW, int maskH) {

    if (w <= 0 || h <= 0 || pixels == nullptr || !p.any()) return;

    const size_t n = size_t(w) * h;
    // Working planar RGB (normalised, may exceed 1.0 in highlights).
    std::vector<float> R(n), G(n), B(n);
    parallelFor(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            const T* row = pixels + size_t(y) * strideInPixels * channels;
            const size_t base = size_t(y) * w;
            for (int x = 0; x < w; ++x) {
                const T* px = row + size_t(x) * channels;
                R[base + x] = float(px[0]);
                G[base + x] = float(px[1]);
                B[base + x] = float(px[2]);
            }
        }
    });

    // ── 1. Clarity (radius-5, midtone-gated) + Sharpness (radius-1 USM) +
    //      Texture (à trous wavelet mid-band boost) ──────────────────────────
    //
    // Texture was previously a radius-1 unsharp blended with sharpness; the
    // single fixed frequency made it weak (boosts one band only, and the same
    // band sharpness already drives → texture felt redundant). It is now a
    // proper 3-band à trous decomposition (Ansel/darktable contrast-equalizer
    // approach) where the slider drives band1+0.5·band2 — the mid-frequency
    // range that reads as "fabric / foliage / skin pores". Edge-aware weight
    // `w = exp(-(ΔL)²/σ²)` suppresses halos. Luma-only so chroma is not
    // disturbed.
    const bool doClarity = p.clarity != 0.f;
    const bool doSharp   = p.sharpness > 0.f;
    const bool doTexture = p.texture != 0.f;
    if (doClarity || doSharp || doTexture) {
        std::vector<float> bR1, bG1, bB1, bR5, bG5, bB5;
        // WYSIWYG: scale the clarity/sharpness USM radii with resolution so the
        // SAME spatial frequency is sharpened on the 2560px preview and on the
        // full-res export. These were fixed (1 and 5), so at export they bit a
        // ~2× finer band → over-sharpened micro-texture and amplified sensor
        // grain the preview never showed. Anchor on the preview's max long side
        // (maxPreviewLongSide = 2560). bR1/bR5 keep their names but now hold
        // radius-rSharp / radius-rClarity blurs. (Texture's à trous scales stay
        // fixed — dyadic dilation can't take a fractional factor; deferred.)
        const float kPreviewReferenceLongSide = 2560.f;
        const float resScale = (float)std::max(w, h) / kPreviewReferenceLongSide;
        const int rSharp   = (int)std::max(1.f, std::round(resScale * 1.f));
        const int rClarity = (int)std::max(1.f, std::round(resScale * 5.f));
        if (doSharp) {
            boxBlurPlane(R, bR1, w, h, rSharp);
            boxBlurPlane(G, bG1, w, h, rSharp);
            boxBlurPlane(B, bB1, w, h, rSharp);
        }
        if (doClarity) {
            boxBlurPlane(R, bR5, w, h, rClarity);
            boxBlurPlane(G, bG5, w, h, rClarity);
            boxBlurPlane(B, bB5, w, h, rClarity);
        }

        // ─ Build luma-band wavelet detail (only when texture is active) ─
        std::vector<float> L, Lp1, Lp2, Lp3;
        std::vector<float> band1, band2;     // band1 = Lp1-Lp2 (mid-fine)
                                             // band2 = Lp2-Lp3 (mid-coarse)
        // Local-variance edge mask: bandwise gain is multiplied by
        // exp(-var/σ²) so flat regions get full boost and edges get less
        // (prevents the haloing that USM-clarity produces at high-contrast
        // boundaries). Mask is in [0..1].
        std::vector<float> edgeW;
        if (doTexture) {
            L.resize(n);
            for (size_t i = 0; i < n; ++i)
                L[i] = R[i] * 0.299f + G[i] * 0.587f + B[i] * 0.114f;
            atrousLowpass(L,   Lp1, w, h, 1);
            atrousLowpass(Lp1, Lp2, w, h, 2);
            atrousLowpass(Lp2, Lp3, w, h, 4);
            band1.resize(n); band2.resize(n);
            for (size_t i = 0; i < n; ++i) {
                band1[i] = Lp1[i] - Lp2[i];
                band2[i] = Lp2[i] - Lp3[i];
            }
            // Edge weight from local variance of L (box r=2). High variance
            // → likely a strong edge → reduce gain to avoid halos.
            std::vector<float> meanL, L2(n), meanL2;
            for (size_t i = 0; i < n; ++i) L2[i] = L[i] * L[i];
            boxBlurPlane(L,  meanL,  w, h, 2);
            boxBlurPlane(L2, meanL2, w, h, 2);
            edgeW.resize(n);
            // σ² chosen so that a 0.05-amplitude edge halves the gain.
            const float invSig2 = 1.0f / (0.0025f);
            for (size_t i = 0; i < n; ++i) {
                float var = meanL2[i] - meanL[i] * meanL[i];
                if (var < 0.f) var = 0.f;
                edgeW[i] = std::exp(-var * invSig2);
            }
        }

        // Sharpness multiplier raised 0.6 → 1.5 so the slider produces a
        // visible effect at moderate positions.
        const float unsharpW = p.sharpness * 1.5f;
        // Texture gain at slider=±1 → ±1.5× the mid-band amplitude. Negative
        // texture smooths the mid-band (subtracts toward Lp3, "skin softener").
        const float texW = p.texture * 1.5f;
        // F1 — subject protection: feather Clarity/Texture DOWN on the subject so
        // environmental detail stays crisp while skin/people stay smooth (the
        // "brick wall sharp, skin not wrinkled" goal). 0 = off, 1 = full protect.
        // Distinct from Smart Sharpness below, which feathers the OTHER way (gain
        // ∝ subject) to enhance subject detail. Tunable.
        const float kSubjectProtect = 0.6f;
        parallelFor(h, [&](int yB, int yE) {
            for (int y = yB; y < yE; ++y) {
                const size_t base = size_t(y) * w;
                for (int x = 0; x < w; ++x) {
                    const size_t i = base + x;
                    float r = R[i], g = G[i], b = B[i];
                    // Subject-protect feather for Clarity/Texture (see kSubjectProtect).
                    float subjFeather = 1.f;
                    if (subjectMask && (doClarity || doTexture)) {
                        const float u = w > 1 ? float(x) / (w - 1) : 0.5f;
                        const float v = h > 1 ? float(y) / (h - 1) : 0.5f;
                        const float m = bilerpMask(subjectMask, maskW, maskH, u, v);
                        subjFeather = 1.f - m * kSubjectProtect;
                    }
                    if (doSharp) {
                        // Luma-mostly sharpness with soft-clip — same
                        // pattern as clarity below. Per-channel USM
                        // amplified R/B sensor noise and posterised
                        // smooth gradients; broadcasting a luma delta
                        // keeps RGB locked and uses a tanh-shaped cap
                        // at ±0.25 so heavy sharpness can't crush flat
                        // gradients into visible bands.
                        const float lum0    = r * 0.299f + g * 0.587f + b * 0.114f;
                        const float blurLum = bR1[i] * 0.299f + bG1[i] * 0.587f + bB1[i] * 0.114f;
                        const float dRaw    = unsharpW * (lum0 - blurLum);
                        const float Ls      = 0.25f;
                        const float dL      = dRaw / (1.f + std::fabs(dRaw) / Ls);
                        r += dL; g += dL; b += dL;
                    }
                    if (doTexture) {
                        // Mid-band emphasis: band1 full weight, band2 half.
                        const float mid = band1[i] + 0.5f * band2[i];
                        const float dL  = texW * edgeW[i] * mid * subjFeather;
                        r += dL; g += dL; b += dL;
                    }
                    if (doClarity) {
                        // Luma-only clarity. The previous per-channel form
                        // (`r += wgt * (r - bR5[i])` × 3) amplified tiny
                        // R/G/B misalignments — at max strength it produced
                        // the rainbow fringes visible on roof edges. Doing
                        // the unsharp on luma and broadcasting the same
                        // scalar back to R/G/B keeps R/G/B locked together,
                        // so chromatic edges stay clean.
                        //
                        // Strength also softened ×2.0 → ×1.2 and the per-
                        // pixel delta soft-clipped to ±0.20 so heavy clarity
                        // can't crush flat gradients into visible posterised
                        // bands (a 0.6-amplitude delta over 1-byte LSBs
                        // forces the rounded output into chunks of 6-8
                        // codes — visible as contours on smooth walls).
                        const float lum    = clampf(r * 0.299f + g * 0.587f + b * 0.114f, 0.f, 1.f);
                        const float blurLum = bR5[i] * 0.299f + bG5[i] * 0.587f + bB5[i] * 0.114f;
                        const float midZone = 4.f * lum * (1.f - lum);
                        const float wgt = p.clarity * midZone * 1.2f;
                        // Soft-clip the luma delta. tanh-like via x/(1+|x|/L)
                        // gives a smooth saturating cap at ~±L without
                        // hard clipping which would create its own bands.
                        const float dRaw = wgt * (lum - blurLum);
                        const float L = 0.20f;
                        const float dL = (dRaw / (1.f + std::fabs(dRaw) / L)) * subjFeather;
                        r += dL; g += dL; b += dL;

                        // img.ly-style midtone "pop": a small exposure lift on
                        // top of the local-contrast boost, gated by the SAME
                        // midZone mask (so shadows/highlights are untouched) and
                        // feathered by the subject mask. Applied AFTER the tanh
                        // soft-clip so it can't reintroduce haloing/posterisation.
                        // clarityLift ∈ [0..1] scales img.ly's 0.27 coupling.
                        // 0 = classic clarity. Mirrored in shader_sources.cpp
                        // (uClarityLift) for GL-preview↔CPU-export parity.
                        if (p.clarityLift > 0.f) {
                            const float lift = std::pow(2.f,
                                p.clarity * 0.27f * midZone * p.clarityLift * subjFeather);
                            r = clampf(r * lift, 0.f, 1.f);
                            g = clampf(g * lift, 0.f, 1.f);
                            b = clampf(b * lift, 0.f, 1.f);
                        }
                    }
                    R[i] = std::max(0.f, r);
                    G[i] = std::max(0.f, g);
                    B[i] = std::max(0.f, b);
                }
            }
        });
    }

    // ── 2. Smart Sharpness: multi-scale guided-filter detail sharpen ──────
    // Replaces the old edge-magnitude unsharp. We sharpen on LUMA only (colour
    // preserved), using TWO edge-preserving guided-filter scales:
    //   • fine band  = luma − guided(small radius)  → micro-texture/edges
    //   • medium band= guided(small) − guided(large)→ larger structure
    // Both bands are amplified and added back. Because the guided filter is
    // edge-preserving, the detail bands carry no ringing → halo-free, and flat
    // low-variance areas (sky/skin) yield ~zero detail → noise isn't boosted
    // (the "smart"/edge-aware behaviour). The luma delta is applied to RGB by
    // a ratio so chroma is untouched. Subject mask feathers the strength.
    if (p.smartSharpness > 0.f) {
        std::vector<float> Y(n);
        for (size_t i = 0; i < n; ++i)
            Y[i] = R[i] * 0.299f + G[i] * 0.587f + B[i] * 0.114f;

        const int longSide = std::max(w, h);
        const int rFine = std::max(1, longSide / 800);   // ~3px @2560
        const int rMed  = std::max(3, longSide / 250);   // ~10px @2560
        // eps in luma² units: small so only near-flat areas are treated as base.
        const float epsFine = 0.0016f;   // ~ (0.04)²
        const float epsMed  = 0.0036f;   // ~ (0.06)²

        std::vector<float> baseFine, baseMed;
        guidedFilterSelf(Y, baseFine, w, h, rFine, epsFine);
        guidedFilterSelf(baseFine, baseMed, w, h, rMed, epsMed);

        // Fine weight reduced (1.4→0.9) and structure nudged up (0.7→0.8): the
        // fine band carries the noise / minor tonal variation that over-amplified
        // into a brittle "plastic" look. Lean on the structure band (real edges).
        const float kFine = p.smartSharpness * 0.9f;   // micro-detail gain
        const float kMed  = p.smartSharpness * 0.8f;   // structure gain
        parallelFor(h, [&](int yB, int yE) {
            for (int y = yB; y < yE; ++y) {
                const float v = h > 1 ? float(y) / (h - 1) : 0.5f;
                const size_t base = size_t(y) * w;
                for (int x = 0; x < w; ++x) {
                    const size_t i = base + x;
                    const float fineRaw = Y[i] - baseFine[i];       // micro detail
                    const float med     = baseFine[i] - baseMed[i]; // structure
                    // (1) Coring: reject sub-threshold fine detail so the sharpen
                    //     only re-etches REAL micro-edges, not grain / minor tonal
                    //     variation (the "insignificant edges" that read as plastic).
                    const float thrFine = 0.012f;                    // ~3/255 floor
                    const float aFine   = std::fabs(fineRaw);
                    const float fine    = aFine <= thrFine ? 0.f
                                        : (fineRaw > 0.f ? fineRaw - thrFine : fineRaw + thrFine);
                    // (2) Adaptive strength: gain follows local edge strength (the
                    //     structure band) — low in smooth/flat regions so noise
                    //     isn't overloaded, full on strong edges.
                    float gain = 0.30f + 0.70f * clampf(std::fabs(med) / 0.05f, 0.f, 1.f);
                    if (subjectMask) {
                        const float u = w > 1 ? float(x) / (w - 1) : 0.5f;
                        const float m = bilerpMask(subjectMask, maskW, maskH, u, v);
                        gain *= 0.25f + 0.75f * m;   // full on subject, 25% off
                    }
                    const float y0 = Y[i];
                    // Shadow protection — in the darkest shadows the "detail"
                    // smart-sharpen would boost is mostly sensor noise, so fade
                    // the sharpen out as luma → 0 (the darker the shadow, the
                    // less it sharpens). smoothstep 0.02 … 0.12 in [0,1] luma:
                    // black shadows get ~none, midtones/highlights full strength.
                    float sg = clampf((y0 - 0.02f) / 0.10f, 0.f, 1.f);
                    sg = sg * sg * (3.f - 2.f * sg);
                    const float dY = (fine * kFine + med * kMed) * gain * sg;
                    if (dY == 0.f) continue;
                    const float y1 = y0 + dY;
                    // Apply luma delta to RGB preserving chroma: scale by ratio
                    // for non-dark pixels, additive for very dark ones.
                    if (y0 > 0.0157f) {
                        const float s = y1 / y0;
                        R[i] = std::max(0.f, R[i] * s);
                        G[i] = std::max(0.f, G[i] * s);
                        B[i] = std::max(0.f, B[i] * s);
                    } else {
                        R[i] = std::max(0.f, R[i] + dY);
                        G[i] = std::max(0.f, G[i] + dY);
                        B[i] = std::max(0.f, B[i] + dY);
                    }
                }
            }
        });
    }

    // ── 2b. Smooth Background: variance-masked OOF (bokeh) smoothing ──────
    // Goal: melt noise in blurry, out-of-focus areas into "buttery" bokeh
    // while leaving the in-focus subject 100% crisp. Three signals decide
    // per-pixel strength:
    //   (1) local detail energy — a low-detail pixel is probably OOF, so it
    //       should be smoothed hard; a high-detail pixel (an edge) should not.
    //   (2) the subject mask — even a flat patch ON the subject (skin, a plain
    //       shirt) must be protected, so we multiply by (1 - subjectProb).
    //   (3) the user strength slider.
    // Smoothing is done luma/chroma-split: chroma (Cb/Cr) gets an aggressive
    // large-radius guided blur (kills color blotches — the eye can't see
    // chroma detail anyway); luma (Y) gets a gentle guided blur (leaves a
    // whisper of grain so the background doesn't look plasticky). The guided
    // filter is edge-preserving so the subject boundary stays halo-free.
    if (p.smoothBackground > 0.f) {
        std::vector<float> Y(n), Cb(n), Cr(n);
        for (size_t i = 0; i < n; ++i) {
            const float r = R[i], g = G[i], b = B[i];
            const float y = r * 0.299f + g * 0.587f + b * 0.114f;
            Y[i]  = y;
            Cb[i] = b - y;   // BT.601-ish chroma differences (unscaled)
            Cr[i] = r - y;
        }

        // Local detail map: |Y - guided(Y)| smoothed into a slowly-varying
        // energy field, then normalised so a soft threshold separates flat
        // (OOF) from textured (in-focus) regions.
        const int longSide = std::max(w, h);
        const int rDetail = std::max(2, longSide / 400);
        std::vector<float> guidedY;
        guidedFilterSelf(Y, guidedY, w, h, rDetail, 0.0009f);
        std::vector<float> energy(n);
        for (size_t i = 0; i < n; ++i) energy[i] = std::fabs(Y[i] - guidedY[i]);
        std::vector<float> energyBlur;
        boxBlurPlane(energy, energyBlur, w, h, rDetail * 2);
        // Map energy → "flatness" in [0,1]: below loThr = fully flat (smooth
        // hard), above hiThr = textured (leave alone). Thresholds in luma units.
        const float loThr = 0.004f, hiThr = 0.020f;
        const float invSpan = 1.0f / (hiThr - loThr);

        // Chroma: large radius scales with strength. Luma: small, fixed-ish.
        const int rChroma = std::max(4, int(longSide / 200 * (0.5f + p.smoothBackground)));
        const int rLuma   = std::max(1, longSide / 700);
        std::vector<float> CbS, CrS, YS;
        guidedFilterSelf(Cb, CbS, w, h, rChroma, 0.0006f);
        guidedFilterSelf(Cr, CrS, w, h, rChroma, 0.0006f);
        guidedFilterSelf(Y,  YS,  w, h, rLuma,   0.0004f);

        const float lumaStrength   = p.smoothBackground * 0.45f; // gentle on Y
        const float chromaStrength = p.smoothBackground;         // full on chroma
        parallelFor(h, [&](int yB, int yE) {
            for (int y = yB; y < yE; ++y) {
                const float v = h > 1 ? float(y) / (h - 1) : 0.5f;
                const size_t base = size_t(y) * w;
                for (int x = 0; x < w; ++x) {
                    const size_t i = base + x;
                    // flatness: 1 where flat/OOF, 0 where textured.
                    const float flat = clampf(1.0f - (energyBlur[i] - loThr) * invSpan, 0.f, 1.f);
                    if (flat <= 0.f) continue;
                    // subject guard: 1 = background, 0 = subject.
                    float bg = 1.0f;
                    if (subjectMask) {
                        const float u = w > 1 ? float(x) / (w - 1) : 0.5f;
                        const float m = bilerpMask(subjectMask, maskW, maskH, u, v);
                        bg = 1.0f - m;
                    }
                    const float w0 = flat * bg;
                    if (w0 <= 0.f) continue;
                    const float wC = w0 * chromaStrength;
                    const float wL = w0 * lumaStrength;
                    const float ny  = Y[i]  + (YS[i]  - Y[i])  * wL;
                    const float ncb = Cb[i] + (CbS[i] - Cb[i]) * wC;
                    const float ncr = Cr[i] + (CrS[i] - Cr[i]) * wC;
                    // YCbCr → RGB (inverse of the unscaled diffs above).
                    R[i] = std::max(0.f, ny + ncr);
                    B[i] = std::max(0.f, ny + ncb);
                    G[i] = std::max(0.f, (ny - 0.299f * (ny + ncr) - 0.114f * (ny + ncb)) / 0.587f);
                }
            }
        });
    }

    // ── 3. Film grain + wash-out ──────────────────────────────────────────
    // MOVED to the GL uber-shader (blue-noise grain) + Stage C apply_macro for
    // export parity. Kept out of this CPU bake so grain previews live and isn't
    // applied twice. The filmGrain* params no longer trigger applyDetail (see
    // DetailParams::any in raw_v3_detail.h).

    // Recompose.
    parallelFor(h, [&](int yB, int yE) {
        for (int y = yB; y < yE; ++y) {
            T* row = pixels + size_t(y) * strideInPixels * channels;
            const size_t base = size_t(y) * w;
            for (int x = 0; x < w; ++x) {
                T* px = row + size_t(x) * channels;
                px[0] = static_cast<T>(R[base + x]);
                px[1] = static_cast<T>(G[base + x]);
                px[2] = static_cast<T>(B[base + x]);
            }
        }
    });
}

template void applyDetail<__fp16>(__fp16*, int, int, int, int, const DetailParams&, const float*, int, int);
template void applyDetail<float>(float*, int, int, int, int, const DetailParams&, const float*, int, int);

} // namespace raw_v3
