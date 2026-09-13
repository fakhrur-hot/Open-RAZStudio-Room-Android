/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * RAW-domain shadow/black recovery — pre-demosaic Bayer U-Net inference.
 * Applied AFTER the HDR highlight pass in stage_a.cpp.
 *
 * Pixels BELOW SHADOW_FRAC of white level are treated as shadow candidates.
 * Luma scale is clamped to [1.0, 4.0] — only brightens, never darkens.
 * Luminance-only: preserves R:Gr:Gb:B ratios (no colour shift).
 *
 * Model format identical to raw_hdr_recovery.bin (RAZ1).
 */

#include "raw_shadow_recovery.h"

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <atomic>
#include <thread>
#include <utility>
#include <unordered_map>
#include <vector>

namespace {
// See raw_hdr_recovery.cpp — OpenMP is not linked on Android, so std::thread is
// how this codebase parallelises. Conv outputs are disjoint → race-free.
template <typename Fn>
inline void parallelChunks(int total, Fn&& body) {
    if (total <= 0) return;
    unsigned hw = std::thread::hardware_concurrency();
    int workers = int(hw == 0 ? 1 : hw);
    workers = std::min(workers, std::max(1, total / 16));
    if (workers <= 1) { body(0, total); return; }
    std::vector<std::thread> pool;
    pool.reserve(workers - 1);
    const int chunk = (total + workers - 1) / workers;
    for (int wk = 0; wk < workers; ++wk) {
        const int begin = wk * chunk;
        const int end = std::min(begin + chunk, total);
        if (begin >= end) break;
        if (wk == workers - 1) body(begin, end);
        else pool.emplace_back([&body, begin, end] { body(begin, end); });
    }
    for (auto& t : pool) t.join();
}
}  // namespace

#define LOG_TAG "RawV3.ShadowRec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {
namespace {

// ── Tensor helpers (same as HDR recovery) ────────────────────────────────────

struct Tensor {
    std::vector<int>   shape;
    std::vector<float> data;

    int dims() const { return (int)shape.size(); }
    int size() const {
        int s = 1;
        for (int d : shape) s *= d;
        return s;
    }
    float at3(int c, int y, int x) const {
        return data[(c * shape[1] + y) * shape[2] + x];
    }
};

using TensorMap = std::unordered_map<std::string, Tensor>;

// ── Model loader ──────────────────────────────────────────────────────────────

static bool loadModel(const uint8_t* data, size_t size, TensorMap& out) {
    if (!data || size < 12) return false;
    if (std::memcmp(data, "RAZ1", 4) != 0) {
        LOGE("loadModel: bad magic — expected RAZ1");
        return false;
    }
    uint32_t version, num_tensors;
    std::memcpy(&version,     data + 4, 4);
    std::memcpy(&num_tensors, data + 8, 4);
    LOGD("loadModel: version=%u  tensors=%u  size=%zu bytes", version, num_tensors, size);

    size_t pos = 12;
    for (uint32_t t = 0; t < num_tensors; ++t) {
        if (pos + 4 > size) return false;
        uint32_t name_len;
        std::memcpy(&name_len, data + pos, 4); pos += 4;
        if (pos + name_len > size) return false;
        std::string name(reinterpret_cast<const char*>(data + pos), name_len);
        pos += name_len;

        if (pos + 4 > size) return false;
        uint32_t rank;
        std::memcpy(&rank, data + pos, 4); pos += 4;

        Tensor tensor;
        tensor.shape.resize(rank);
        int total = 1;
        for (uint32_t i = 0; i < rank; ++i) {
            if (pos + 4 > size) return false;
            uint32_t d;
            std::memcpy(&d, data + pos, 4); pos += 4;
            tensor.shape[i] = (int)d;
            total *= (int)d;
        }
        if (pos + (size_t)total * 4 > size) return false;
        tensor.data.resize(total);
        std::memcpy(tensor.data.data(), data + pos, (size_t)total * 4);
        pos += (size_t)total * 4;
        out[name] = std::move(tensor);
    }
    LOGI("loadModel: loaded %u tensors OK", num_tensors);
    return true;
}

// ── Convolution primitives ────────────────────────────────────────────────────

static void relu(std::vector<float>& t) {
    for (float& v : t) v = v < 0.f ? 0.f : v;
}

static void batchnormAffine(std::vector<float>& feat,
                             int C, int H, int W,
                             const Tensor& scale,
                             const Tensor& bias) {
    for (int c = 0; c < C; ++c) {
        float s = scale.data[c];
        float b = bias.data[c];
        for (int i = 0; i < H * W; ++i)
            feat[c * H * W + i] = s * feat[c * H * W + i] + b;
    }
}

static std::vector<float> depthwiseConv(
        const std::vector<float>& in, int C, int H, int W,
        const Tensor& weight, const Tensor& bias, int k) {
    const int pad = k / 2;
    std::vector<float> out(C * H * W, 0.f);
    // Scalar — parallelism is at the tile level (runTiledShadowLumaScale).
    for (int c = 0; c < C; ++c) {
        float b = bias.data[c];
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                float acc = b;
                for (int ky = 0; ky < k; ++ky)
                    for (int kx = 0; kx < k; ++kx) {
                        int sy = y + ky - pad, sx = x + kx - pad;
                        if (sy < 0 || sy >= H || sx < 0 || sx >= W) continue;
                        acc += weight.data[c * k * k + ky * k + kx]
                             * in[(c * H + sy) * W + sx];
                    }
                out[(c * H + y) * W + x] = acc;
            }
        }
    }
    return out;
}

static std::vector<float> pointwiseConv(
        const std::vector<float>& in, int C_in, int H, int W,
        const Tensor& weight, const Tensor& bias) {
    const int C_out = weight.shape[0];
    const int HW = H * W;
    std::vector<float> out(C_out * HW);
    // Scalar — tile-level parallelism wraps this.
    for (int co = 0; co < C_out; ++co) {
        float b = bias.data[co];
        const float* wrow = &weight.data[co * C_in];
        for (int i = 0; i < HW; ++i) {
            float acc = b;
            for (int ci = 0; ci < C_in; ++ci)
                acc += wrow[ci] * in[ci * HW + i];
            out[co * HW + i] = acc;
        }
    }
    return out;
}

static std::vector<float> maxpool2(const std::vector<float>& in, int C, int H, int W) {
    const int oH = H / 2, oW = W / 2;
    std::vector<float> out(C * oH * oW);
    for (int c = 0; c < C; ++c)
        for (int y = 0; y < oH; ++y)
            for (int x = 0; x < oW; ++x) {
                float m = -1e30f;
                for (int dy = 0; dy < 2; ++dy)
                    for (int dx = 0; dx < 2; ++dx) {
                        float v = in[(c * H + y*2+dy) * W + x*2+dx];
                        if (v > m) m = v;
                    }
                out[(c * oH + y) * oW + x] = m;
            }
    return out;
}

static std::vector<float> upsample2(const std::vector<float>& in, int C, int H, int W) {
    const int oH = H * 2, oW = W * 2;
    std::vector<float> out(C * oH * oW);
    for (int c = 0; c < C; ++c)
        for (int y = 0; y < oH; ++y)
            for (int x = 0; x < oW; ++x) {
                float fy = (y + 0.5f) / 2.f - 0.5f;
                float fx = (x + 0.5f) / 2.f - 0.5f;
                int y0 = (int)std::floor(fy), x0 = (int)std::floor(fx);
                float ty = fy - y0, tx = fx - x0;
                auto clampGet = [&](int cy, int cx) -> float {
                    cy = std::max(0, std::min(H-1, cy));
                    cx = std::max(0, std::min(W-1, cx));
                    return in[(c * H + cy) * W + cx];
                };
                out[(c * oH + y) * oW + x] =
                    (1-ty)*(1-tx)*clampGet(y0,   x0)   +
                    (1-ty)*   tx *clampGet(y0,   x0+1) +
                       ty *(1-tx)*clampGet(y0+1, x0)   +
                       ty *   tx *clampGet(y0+1, x0+1);
            }
    return out;
}

static std::vector<float> catC(const std::vector<float>& a, int Ca,
                                const std::vector<float>& b, int Cb,
                                int H, int W) {
    std::vector<float> out((Ca + Cb) * H * W);
    std::memcpy(out.data(),            a.data(), Ca * H * W * sizeof(float));
    std::memcpy(out.data() + Ca*H*W,   b.data(), Cb * H * W * sizeof(float));
    return out;
}

static std::vector<float> dwsepBlock(
        const std::vector<float>& in, int C_in, int H, int W,
        const TensorMap& m, const std::string& prefix, int C_out) {
    auto dw = depthwiseConv(in, C_in, H, W,
                            m.at(prefix + ".dw.weight"),
                            m.at(prefix + ".dw.bias"), 3);
    auto pw = pointwiseConv(dw, C_in, H, W,
                            m.at(prefix + ".pw.weight"),
                            m.at(prefix + ".pw.bias"));
    batchnormAffine(pw, C_out, H, W,
                    m.at(prefix + ".bn.scale"),
                    m.at(prefix + ".bn.bias"));
    relu(pw);
    return pw;
}

// ── Pixel unshuffle ───────────────────────────────────────────────────────────

static std::vector<float> packBayer(const uint16_t* raw_image,
                                     int RW, int RH,
                                     float black, float white,
                                     int stride) {
    const int oH = RH / 2, oW = RW / 2;
    std::vector<float> out(4 * oH * oW);
    float range = white - black;
    if (range < 1.f) range = 1.f;
    for (int y = 0; y < oH; ++y)
        for (int x = 0; x < oW; ++x) {
            auto get = [&](int ry, int rx) -> float {
                float v = (float)raw_image[ry * stride + rx] - black;
                return std::max(0.f, v) / range;
            };
            out[(0 * oH + y) * oW + x] = get(2*y,   2*x);    // R
            out[(1 * oH + y) * oW + x] = get(2*y,   2*x+1);  // Gr
            out[(2 * oH + y) * oW + x] = get(2*y+1, 2*x);    // Gb
            out[(3 * oH + y) * oW + x] = get(2*y+1, 2*x+1);  // B
        }
    return out;
}

// ── Shadow density gate ───────────────────────────────────────────────────────
// Fire on tiles where enough pixels are in the shadow zone (below SHADOW_FRAC).
// Skip near-pure-black tiles (mean < BLACK_FLOOR) — just sensor noise.

static constexpr float SHADOW_DENSITY_LO = 0.20f;  // ≥20% of pixels must be below SHADOW_FRAC
static constexpr float SHADOW_DENSITY_HI = 0.98f;  // skip tiles that are entirely black

static bool tileHasMeaningfulShadow(const std::vector<float>& tile_in,
                                     int C, int H, int W,
                                     float shadow_frac) {
    const int total = H * W;
    int shadow_count = 0;
    float sum = 0.f;
    for (int c = 0; c < C; ++c)
        for (int i = 0; i < total; ++i) {
            float v = tile_in[c * total + i];
            sum += v;
            if (v < shadow_frac) ++shadow_count;
        }
    float density = float(shadow_count) / float(C * total);
    float mean    = sum / float(C * total);
    bool in_range = density > SHADOW_DENSITY_LO && density < SHADOW_DENSITY_HI;
    bool not_dead = mean > 0.01f;   // skip pure black / dead pixels
    return in_range && not_dead;
}

// ── Cosine blend weight ───────────────────────────────────────────────────────

static inline float cosineWeight(int pos, int size, int overlap) {
    if (pos < overlap) {
        float t = float(pos) / float(overlap);
        return 0.5f * (1.f - std::cos(t * 3.14159265f));
    }
    if (pos >= size - overlap) {
        float t = float(size - 1 - pos) / float(overlap);
        return 0.5f * (1.f - std::cos(t * 3.14159265f));
    }
    return 1.f;
}

// ── Luma weights (BT.601) ─────────────────────────────────────────────────────

static constexpr float LUMA_R  = 0.299f;
static constexpr float LUMA_GR = 0.2935f;
static constexpr float LUMA_GB = 0.2935f;
static constexpr float LUMA_B  = 0.114f;

static inline float tilePixelLuma(const std::vector<float>& t, int H, int W, int y, int x) {
    auto v = [&](int ch) { return t[(ch * H + y) * W + x]; };
    return LUMA_R * v(0) + LUMA_GR * v(1) + LUMA_GB * v(2) + LUMA_B * v(3);
}

// ── Forward pass ─────────────────────────────────────────────────────────────

static std::vector<float> runUNet(const std::vector<float>& in,
                                   int H, int W,
                                   const TensorMap& m) {
    // Encoder
    auto e0 = dwsepBlock(in,              4,  H,   W,   m, "enc0", 16);
    auto e1 = dwsepBlock(maxpool2(e0, 16, H, W), 16, H/2, W/2, m, "enc1", 32);
    auto e2 = dwsepBlock(maxpool2(e1, 32, H/2, W/2), 32, H/4, W/4, m, "enc2", 64);
    // Bottleneck
    auto b  = dwsepBlock(e2,             64, H/4, W/4, m, "bot",  64);
    // Decoder
    auto u2 = upsample2(b, 64, H/4, W/4);
    auto d2 = dwsepBlock(catC(u2, 64, e1, 32, H/2, W/2), 96, H/2, W/2, m, "dec2", 32);
    auto u1 = upsample2(d2, 32, H/2, W/2);
    auto d1 = dwsepBlock(catC(u1, 32, e0, 16, H, W), 48, H, W, m, "dec1", 16);
    // Head: conv 1×1 + sigmoid
    auto head = pointwiseConv(d1, 16, H, W, m.at("head.weight"), m.at("head.bias"));
    for (float& v : head) v = 1.f / (1.f + std::exp(-v));
    return head;
}

// ── Tiled luma-scale accumulation ────────────────────────────────────────────

static std::vector<float> runTiledShadowLumaScale(
        const std::vector<float>& packed,  // [4, oH, oW]
        int oH, int oW,
        const TensorMap& model,
        float shadow_frac,
        int& tiles_processed,
        int& tiles_skipped) {

    constexpr int TILE  = 32;   // matches training patch size (64×64 Bayer → 32×32 unshuffled)
    constexpr int STEP  = 32;   // no overlap — Gaussian blur on luma_scale smooths seams
    constexpr int BLEND = 8;    // cosine taper width

    std::vector<float> accum_luma(oH * oW, 0.f);
    std::vector<float> weight_sum(oH * oW, 0.f);

    // Tile coords = the parallel unit. TILE==STEP==32 → non-overlapping tiles
    // write disjoint [ty,ty+32)×[tx,tx+32) accumulator regions, so running them
    // across cores is race-free. Each tile's convs stay scalar (no nested
    // threads). Replaces per-conv threading, which spawned ~100k threads over
    // 1204 tiles and ran slower than scalar.
    std::vector<std::pair<int,int>> coords;
    for (int ty = 0; ty + TILE <= oH; ty += STEP)
        for (int tx = 0; tx + TILE <= oW; tx += STEP)
            coords.emplace_back(ty, tx);

    std::atomic<int> processed{0}, skipped{0};
    parallelChunks((int)coords.size(), [&](int cB, int cE) {
        int localRun = 0, localSkip = 0;
        for (int idx = cB; idx < cE; ++idx) {
            const int ty = coords[idx].first, tx = coords[idx].second;
            std::vector<float> tile_in(4 * TILE * TILE);
            for (int c = 0; c < 4; ++c)
                for (int y = 0; y < TILE; ++y)
                    for (int x = 0; x < TILE; ++x)
                        tile_in[(c * TILE + y) * TILE + x] =
                            packed[(c * oH + ty + y) * oW + tx + x];

            if (!tileHasMeaningfulShadow(tile_in, 4, TILE, TILE, shadow_frac)) {
                ++localSkip;
                continue;
            }

            auto tile_out = runUNet(tile_in, TILE, TILE, model);
            ++localRun;

            for (int y = 0; y < TILE; ++y) {
                float wy = cosineWeight(y, TILE, BLEND);
                for (int x = 0; x < TILE; ++x) {
                    float wx    = cosineWeight(x, TILE, BLEND);
                    float w_cos = wy * wx;

                    float Y_orig = tilePixelLuma(tile_in,  TILE, TILE, y, x);
                    float Y_rec  = tilePixelLuma(tile_out, TILE, TILE, y, x);

                    float alpha = std::max(0.f, (shadow_frac - Y_orig) / (shadow_frac + 1e-6f));

                    if (alpha > 1e-6f) {
                        static constexpr float EPS = 1e-4f;
                        float luma_scale = std::max(1.f, std::min(4.f,
                            (Y_rec + EPS) / (Y_orig + EPS)));
                        float w = alpha * w_cos;
                        accum_luma[(ty + y) * oW + (tx + x)] += w * luma_scale;
                        weight_sum[(ty + y) * oW + (tx + x)] += w;
                    }
                }
            }
        }
        processed.fetch_add(localRun,  std::memory_order_relaxed);
        skipped.fetch_add(localSkip, std::memory_order_relaxed);
    });
    tiles_processed = processed.load();
    tiles_skipped   = skipped.load();

    // Normalise accumulator → final luma scale map (1.0 = identity)
    std::vector<float> luma_scale(oH * oW, 1.f);
    for (int i = 0; i < oH * oW; ++i) {
        if (weight_sum[i] > 1e-6f)
            luma_scale[i] = accum_luma[i] / weight_sum[i];
    }

    // Gaussian blur the luma-scale map to eliminate tile-boundary seams.
    // Radius=8 (sigma≈4) is enough to bridge one full tile-step (16px).
    // We blur only scale > 1 regions; identity (1.0) pixels act as anchors.
    constexpr int   BLUR_R = 16;
    constexpr float SIGMA  = 8.f;
    // Build 1D kernel
    float kernel[2 * BLUR_R + 1];
    float ksum = 0.f;
    for (int k = -BLUR_R; k <= BLUR_R; ++k) {
        kernel[k + BLUR_R] = std::exp(-0.5f * k * k / (SIGMA * SIGMA));
        ksum += kernel[k + BLUR_R];
    }
    for (float& v : kernel) v /= ksum;

    std::vector<float> tmp(oH * oW);
    // Horizontal pass
    for (int y = 0; y < oH; ++y) {
        for (int x = 0; x < oW; ++x) {
            float acc = 0.f;
            for (int k = -BLUR_R; k <= BLUR_R; ++k) {
                int sx = std::max(0, std::min(oW - 1, x + k));
                acc += kernel[k + BLUR_R] * luma_scale[y * oW + sx];
            }
            tmp[y * oW + x] = acc;
        }
    }
    // Vertical pass
    for (int y = 0; y < oH; ++y) {
        for (int x = 0; x < oW; ++x) {
            float acc = 0.f;
            for (int k = -BLUR_R; k <= BLUR_R; ++k) {
                int sy = std::max(0, std::min(oH - 1, y + k));
                acc += kernel[k + BLUR_R] * tmp[sy * oW + x];
            }
            luma_scale[y * oW + x] = acc;
        }
    }

    return luma_scale;
}

}  // namespace (anonymous)

// ── Public entry point ────────────────────────────────────────────────────────

void applyRawShadowRecovery(
        uint16_t*      raw_image,
        int            raw_width,
        int            raw_height,
        unsigned int   filters,
        float          white_level,
        float          black_level,
        const uint8_t* model_data,
        size_t         model_size) {

    if (!model_data || model_size == 0) {
        LOGI("applyRawShadowRecovery: no model data, skipping");
        return;
    }
    // Only RGGB Bayer supported (filters != 0 and not X-Trans)
    if (filters == 0 || filters == 9) {
        LOGI("applyRawShadowRecovery: non-RGGB mosaic (filters=0x%x), skipping", filters);
        return;
    }
    if (raw_width < 4 || raw_height < 4) return;

    const int RW = raw_width  & ~1;
    const int RH = raw_height & ~1;

    TensorMap model;
    if (!loadModel(model_data, model_size, model)) {
        LOGE("applyRawShadowRecovery: model load failed");
        return;
    }

    // Shadow trigger threshold: pixels below 40% of white level are shadow candidates.
    // This matches the training synthetic darkening range (gain 0.15–0.50 of GT).
    // Match HDR threshold exactly so shadow and highlight zones overlap at 0.50.
    // Alpha in both models tapers to zero at the boundary — smooth crossfade.
    static constexpr float SHADOW_FRAC = 0.35f;  // meets HDR at 0.35 for smooth crossfade

    LOGI("applyRawShadowRecovery: %dx%d → %dx%d  white=%.0f black=%.0f  shadow_frac=%.2f",
         RW, RH, RW/2, RH/2, white_level, black_level, SHADOW_FRAC);

    // Pack Bayer → 4-ch float [0,1]
    auto packed = packBayer(raw_image, RW, RH, black_level, white_level, raw_width);
    const int oH = RH / 2, oW = RW / 2;

    int tiles_processed = 0, tiles_skipped = 0;
    auto luma_scale = runTiledShadowLumaScale(
        packed, oH, oW, model, SHADOW_FRAC,
        tiles_processed, tiles_skipped);

    LOGI("applyRawShadowRecovery: %d tiles processed, %d skipped (density gate)",
         tiles_processed, tiles_skipped);

    if (tiles_processed == 0) {
        LOGI("applyRawShadowRecovery: no shadow tiles — image may be well-exposed, skipping write-back");
        return;
    }

    // Write back: apply luma scale to all four RGGB channels equally.
    // Preserves R:Gr:Gb:B ratio — only brightness changes, not colour.
    float range = white_level - black_level;
    if (range < 1.f) range = 1.f;

    // Use raw_width as buffer stride — LibRaw allocates rows of raw_width,
    // not RW (raw_width & ~1). Wrong stride causes out-of-bounds writes at edges.
    const int stride = raw_width;
    int pixels_brightened = 0;
    for (int y = 0; y < oH; ++y) {
        for (int x = 0; x < oW; ++x) {
            float s = luma_scale[y * oW + x];
            if (s <= 1.0001f) continue;  // identity — skip write

            int ry0 = 2*y, ry1 = 2*y+1;
            int rx0 = 2*x, rx1 = 2*x+1;
            // Guard against buffer overrun at image edges
            if (ry1 >= raw_height || rx1 >= raw_width) continue;
            ++pixels_brightened;

            auto scaleChannel = [&](int ry, int rx) {
                float orig_norm = ((float)raw_image[ry * stride + rx] - black_level) / range;
                float scaled    = std::max(0.f, std::min(1.f, orig_norm * s));
                float dn        = black_level + scaled * range;
                raw_image[ry * stride + rx] =
                    (uint16_t)std::max(0.f, std::min(65535.f, dn));
            };
            scaleChannel(ry0, rx0);  // R
            scaleChannel(ry0, rx1);  // Gr
            scaleChannel(ry1, rx0);  // Gb
            scaleChannel(ry1, rx1);  // B
        }
    }

    LOGI("applyRawShadowRecovery: done — %d quads brightened (%.1f%% of image)",
         pixels_brightened,
         100.f * float(pixels_brightened) / float(oH * oW));
}

}  // namespace raw_v3
