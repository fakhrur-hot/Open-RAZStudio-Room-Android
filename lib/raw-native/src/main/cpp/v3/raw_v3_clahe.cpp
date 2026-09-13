/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Native port of RawV3Clahe.kt. See header for the contract. The structure
 * below intentionally mirrors the Kotlin file step-for-step so the two stay
 * verifiable against each other.
 */

#include "raw_v3_clahe.h"

#include <cmath>
#include <algorithm>
#include <thread>
#include <vector>

static_assert(sizeof(__fp16) == 2, "__fp16 must be IEEE binary16 (2 bytes) to match R16G16B16A16_FLOAT");

namespace raw_v3 {

namespace {

// Split [0, count) into contiguous chunks across worker threads and run [body]
// on each chunk in parallel. [body] receives [begin, end). Falls back to a
// direct call (no thread spawn) for tiny ranges. Each chunk touches disjoint
// output, so callers must ensure no write overlap between chunks.
template <typename Fn>
void parallelFor(int count, Fn&& body) {
    if (count <= 0) return;
    unsigned hw = std::thread::hardware_concurrency();
    int workers = int(hw == 0 ? 1 : hw);
    // Cap workers so each gets a meaningful slice; sequential under ~64 units.
    workers = std::min(workers, std::max(1, count / 64));
    if (workers <= 1) { body(0, count); return; }

    std::vector<std::thread> pool;
    pool.reserve(workers - 1);
    const int chunk = (count + workers - 1) / workers;
    for (int w = 0; w < workers; ++w) {
        const int begin = w * chunk;
        const int end   = std::min(begin + chunk, count);
        if (begin >= end) break;
        if (w == workers - 1) {
            body(begin, end);                 // last slice on the calling thread
        } else {
            pool.emplace_back([&body, begin, end] { body(begin, end); });
        }
    }
    for (auto& t : pool) t.join();
}

// Tile grid constants.
// kDefaultTileCount drives tile size from image dimensions rather than fixing
// a pixel size. This ensures Stage B (512 px) and Stage C (full-res, e.g.
// 4000+ px) use the same number of spatial regions, producing perceptually
// consistent local contrast — which is what the user sees in the GL preview.
// A fixed 64-pixel tile on a 4000 px image gives 62 tiles; on a 512 px
// preview it gives 8 tiles — completely different spatial frequency and the
// primary cause of the MAD=17+ preview/save divergence.
constexpr int   kDefaultTileCount = 8;
constexpr int   kBins       = 256;
constexpr float kClipLimit  = 2.0f;
constexpr float kSigSteep   = 4.0f;   // sigmoid steepness
constexpr float kSigCenter  = 0.5f;   // sigmoid centre (normalised luma)
constexpr float kDarkThresh = 4.0f / 255.0f; // luma <= this uses additive chroma branch

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// BT.601 luma on the encoded (gamma) value, normalised [0..1]. Matches
// Kotlin's `(r*299 + g*587 + b*114)/1000` applied to 0..255, scaled to 0..1.
inline float lumaOf(float r, float g, float b) {
    return r * 0.299f + g * 0.587f + b * 0.114f;
}

// Map normalised luma → 256-bin index, same quantisation as Kotlin's int luma.
inline int binOf(float luma) {
    return clampi(static_cast<int>(std::lround(luma * 255.0f)), 0, kBins - 1);
}

} // namespace

template <typename T>
void applyClahe(
    T* pixels,
    int w,
    int h,
    int strideInPixels,
    int channels,
    float shadowsBoost,
    float highlightsBoost,
    int tileCount) {

    if (w <= 0 || h <= 0 || pixels == nullptr) return;

    // Derive tile size from the longer dimension so the tile *count* is fixed.
    // Both Stage B (512 px) and Stage C (full-res) then use the same number of
    // spatial regions, giving perceptually consistent local contrast.
    const int tc = (tileCount > 0) ? tileCount : kDefaultTileCount;
    const int longerSide = std::max(w, h);
    const int kTileSize = std::max(1, (longerSide + tc - 1) / tc);

    const int tilesX = (w + kTileSize - 1) / kTileSize;
    const int tilesY = (h + kTileSize - 1) / kTileSize;

    const int tilePixels = kTileSize * kTileSize;
    const int clipMax = std::max(1, static_cast<int>(std::lround(kClipLimit * tilePixels / static_cast<float>(kBins))));

    // ── 1. Per-pixel luma (normalised), one pass, kept for reuse ──────────
    std::vector<float> luma(static_cast<size_t>(w) * h);
    parallelFor(h, [&](int yBegin, int yEnd) {
        for (int yy = yBegin; yy < yEnd; ++yy) {
            const T* row = pixels + static_cast<size_t>(yy) * strideInPixels * channels;
            float* lrow = luma.data() + static_cast<size_t>(yy) * w;
            for (int xx = 0; xx < w; ++xx) {
                const T* px = row + static_cast<size_t>(xx) * channels;
                lrow[xx] = lumaOf(static_cast<float>(px[0]),
                                  static_cast<float>(px[1]),
                                  static_cast<float>(px[2]));
            }
        }
    });

    // Per-tile remap LUTs, packed: lut[(ty*tilesX + tx)*kBins + bin].
    // Values are normalised output luma in [0..1].
    std::vector<float> lut(static_cast<size_t>(tilesX) * tilesY * kBins, 0.0f);

    // ── 3 + 4. Histogram → clip → redistribute → CDF → remap LUT per tile ─
    // Parallel by tile-row: each ty writes a disjoint band of LUT slots and
    // uses a thread-local histogram, so no synchronisation is needed.
    parallelFor(tilesY, [&](int tyBegin, int tyEnd) {
        std::vector<int> hist(kBins);
        for (int ty = tyBegin; ty < tyEnd; ++ty) {
            const int y0 = ty * kTileSize;
            const int y1 = std::min(y0 + kTileSize, h);
            for (int tx = 0; tx < tilesX; ++tx) {
                const int x0 = tx * kTileSize;
                const int x1 = std::min(x0 + kTileSize, w);

                std::fill(hist.begin(), hist.end(), 0);
                int n = 0;
                for (int yy = y0; yy < y1; ++yy) {
                    const float* lrow = luma.data() + static_cast<size_t>(yy) * w;
                    for (int xx = x0; xx < x1; ++xx) {
                        hist[binOf(lrow[xx])]++;
                        ++n;
                    }
                }
                if (n == 0) continue;

                // Clip + accumulate excess.
                int excess = 0;
                for (int i = 0; i < kBins; ++i) {
                    if (hist[i] > clipMax) {
                        excess += hist[i] - clipMax;
                        hist[i] = clipMax;
                    }
                }
                const int redist = excess / kBins;
                const int redistRem = excess - redist * kBins;
                for (int i = 0; i < kBins; ++i) hist[i] += redist;
                for (int i = 0; i < redistRem; ++i) hist[i]++;

                // CDF → remap LUT, normalised to [0..1].
                const size_t base = (static_cast<size_t>(ty) * tilesX + tx) * kBins;
                int cdf = 0;
                const float scale = 1.0f / static_cast<float>(n);
                for (int i = 0; i < kBins; ++i) {
                    cdf += hist[i];
                    lut[base + i] = clampf(cdf * scale, 0.0f, 1.0f);
                }
            }
        }
    });

    // ── 5 + 6. Bilinear sample between tile LUTs + sigmoid blend ──────────
    // Parallel by row: reads shared luma + lut (read-only here), writes
    // disjoint pixel rows.
    const float halfTile = kTileSize / 2.0f;

    parallelFor(h, [&](int yBegin, int yEnd) {
    for (int yy = yBegin; yy < yEnd; ++yy) {
        float tyf = (yy - halfTile) / kTileSize;
        tyf = clampf(tyf, 0.0f, static_cast<float>(tilesY - 1));
        const int ty0 = static_cast<int>(tyf);
        const int ty1 = std::min(ty0 + 1, tilesY - 1);
        const float fy = tyf - ty0;

        T* prow = pixels + static_cast<size_t>(yy) * strideInPixels * channels;
        const float* lrow = luma.data() + static_cast<size_t>(yy) * w;

        for (int xx = 0; xx < w; ++xx) {
            float txf = (xx - halfTile) / kTileSize;
            txf = clampf(txf, 0.0f, static_cast<float>(tilesX - 1));
            const int tx0 = static_cast<int>(txf);
            const int tx1 = std::min(tx0 + 1, tilesX - 1);
            const float fx = txf - tx0;

            const float lNorm = lrow[xx];
            const int bin = binOf(lNorm);

            const float l00 = lut[(static_cast<size_t>(ty0) * tilesX + tx0) * kBins + bin];
            const float l10 = lut[(static_cast<size_t>(ty0) * tilesX + tx1) * kBins + bin];
            const float l01 = lut[(static_cast<size_t>(ty1) * tilesX + tx0) * kBins + bin];
            const float l11 = lut[(static_cast<size_t>(ty1) * tilesX + tx1) * kBins + bin];

            const float top = l00 * (1.0f - fx) + l10 * fx;
            const float bot = l01 * (1.0f - fx) + l11 * fx;
            float equalized = top * (1.0f - fy) + bot * fy; // normalised [0..1]

            // Green-curve transfer: smooth the CLAHE output so near-255 bins
            // don't spike hard. log1p remap lifts midtones early and rolls off
            // smoothly at 1.0 — eliminates bright halo at tree/sky boundaries.
            // k=12: strong enough to fully suppress the halo fringe.
            {
                constexpr float k = 12.0f;
                constexpr float inv_log1pk = 1.0f / 2.564949f; // 1/log1p(12)
                const float smooth = std::log1p(equalized * k) * inv_log1pk;
                const float blendW = clampf(highlightsBoost, 0.0f, 1.0f);
                equalized = equalized * (1.0f - blendW) + smooth * blendW;
            }

            // Sigmoid tonal selector: ~0 in deep shadows, ~1 in deep highlights.
            const float sig = 1.0f / (1.0f + std::exp(-kSigSteep * (lNorm - kSigCenter)));
            float shadowSel    = 1.0f - sig;   // weights shadow region
            float highlightSel = sig;          // weights highlight region
            // Highlight-burn guard: above L=0.85 fade shadowSel smoothly to 0
            // at L=1.0 so that a strong shadow boost cannot lift near-white
            // pixels into clipping. Below 0.85 the curve is unchanged.
            if (lNorm > 0.85f) {
                const float t = std::min(1.0f, (lNorm - 0.85f) / 0.15f);
                const float fade = 1.0f - t * t * (3.0f - 2.0f * t); // smoothstep
                shadowSel *= fade;
            }
            // Sub-midtone attenuation for the highlight selector.
            // Without this, a strong positive Highlights boost still
            // touches shadows with ~12-27% weight (sigmoid tail).
            // The user wants the boost to fall off ABOVE the slider's
            // hard floor — i.e. below 0.5 contributes proportionally
            // less. Apply a smoothstep ramp on [0.30..0.50]:
            //   L < 0.30  → highlightSel = 0    (no contribution)
            //   L = 0.40  → highlightSel × ~0.5
            //   L ≥ 0.50  → unchanged
            // Negative Highlights already has a hard L > 0.5 gate
            // below, so this only affects positive boosts.
            if (lNorm < 0.5f) {
                const float t = std::max(0.0f, (lNorm - 0.30f) / 0.20f);
                const float ramp = t * t * (3.0f - 2.0f * t); // smoothstep
                highlightSel *= ramp;
            }

            // Boosts are now signed [-1..+1]:
            //   • POSITIVE → blend toward the CLAHE-equalized luma in that
            //     tonal region (open shadows / recover highlights).
            //   • NEGATIVE → pull luma the other way: darken shadows / mute
            //     highlights, scaled by |boost|.
            const float shPos = shadowsBoost    > 0.f ? shadowsBoost    : 0.f;
            const float shNeg = shadowsBoost    < 0.f ? -shadowsBoost   : 0.f;
            const float hiPos = highlightsBoost > 0.f ? highlightsBoost : 0.f;
            const float hiNeg = highlightsBoost < 0.f ? -highlightsBoost: 0.f;

            // Positive part → equalize blend weight.
            const float wPos = highlightSel * hiPos + shadowSel * shPos;
            float newL = lNorm * (1.0f - wPos) + equalized * wPos;

            // Negative part → darken. Shadows pull toward black (×(1-k)),
            // highlights pull toward the midpoint so they "mute" rather than
            // clip. kDarken caps the max attenuation at full -1.0 strength.
            constexpr float kDarken = 0.85f;
            const float darkenShadow    = shadowSel    * shNeg * kDarken;
            // Only darken pixels that are actually shadows (below the sigmoid
            // centre). Without this gate, shadowSel ≈ 0.3 even at lNorm = 0.7
            // and a negative shadow boost would dim the bright side too —
            // making the whole image dim instead of crushing only the
            // shadow zone.
            if (darkenShadow > 0.f && lNorm < kSigCenter) {
                newL *= (1.0f - darkenShadow);
            }
            const float muteHighlight   = highlightSel * hiNeg * kDarken;
            // Only mute pixels that are actually highlights (above the sigmoid
            // centre). The earlier "pull toward 0.5" form had the wrong sign
            // for sub-centre pixels — sigmoidSel is non-zero everywhere, so
            // midtones below 0.5 got pushed UP toward 0.5 by a negative
            // boost, brightening the bulk of the image even though pure
            // highlights muted as intended. Gate on lNorm > centre to
            // restrict the mute to true highlight territory.
            if (muteHighlight > 0.f && lNorm > kSigCenter) {
                newL = newL + (kSigCenter - newL) * muteHighlight;
            }

            T* px = prow + static_cast<size_t>(xx) * channels;
            const float r = static_cast<float>(px[0]);
            const float g = static_cast<float>(px[1]);
            const float b = static_cast<float>(px[2]);

            if (lNorm > kDarkThresh) {
                // Ratio scaling preserves chroma. No upper clamp — keep
                // highlight headroom above 1.0 for the downstream shader.
                const float s = newL / lNorm;
                px[0] = static_cast<T>(std::max(0.0f, r * s));
                px[1] = static_cast<T>(std::max(0.0f, g * s));
                px[2] = static_cast<T>(std::max(0.0f, b * s));
            } else {
                // Deep shadows: additive offset to avoid divide-by-near-zero.
                const float d = newL - lNorm;
                px[0] = static_cast<T>(std::max(0.0f, r + d));
                px[1] = static_cast<T>(std::max(0.0f, g + d));
                px[2] = static_cast<T>(std::max(0.0f, b + d));
            }
        }
    }
    });
}

// Explicit instantiations for the two storage types the pipeline uses.
template void applyClahe<__fp16>(__fp16*, int, int, int, int, float, float, int);
template void applyClahe<float>(float*, int, int, int, int, float, float, int);

} // namespace raw_v3
