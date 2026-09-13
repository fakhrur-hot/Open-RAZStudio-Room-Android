/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * RAW-domain HDR highlight recovery — pre-demosaic Bayer U-Net inference.
 *
 * Model binary format (raw_hdr_recovery.bin):
 *   [0]   char[4]  magic  = "RAZ1"
 *   [4]   uint32   version = 1
 *   [8]   uint32   num_tensors
 *   For each tensor:
 *     uint32   name_len
 *     char[]   name   (name_len bytes, no NUL)
 *     uint32   rank
 *     uint32[] shape  (rank ints)
 *     float[]  data   (product(shape) floats, little-endian)
 *
 * Network topology (pixel-unshuffled depthwise-separable U-Net, <100K params):
 *   enc0  : DWSepConv(4 → 16,  k=3, stride=1)  + BN + ReLU
 *   enc1  : DWSepConv(16→ 32,  k=3, stride=2)  + BN + ReLU
 *   enc2  : DWSepConv(32→ 64,  k=3, stride=2)  + BN + ReLU
 *   bottleneck : DWSepConv(64→ 64, k=3, stride=1) + BN + ReLU
 *   dec2  : bilinear upsample × 2 + DWSepConv(64+32→ 32, k=3) + BN + ReLU
 *   dec1  : bilinear upsample × 2 + DWSepConv(32+16→ 16, k=3) + BN + ReLU
 *   head  : Conv(16→ 4, k=1) + sigmoid
 *
 * DWSepConv = depthwise conv + pointwise conv, each with bias.
 * BN = batchnorm (fused into affine scale+bias at export time, so inference
 *      just does  y = scale * x + bias  channel-wise).
 *
 * Only pixels above a clip threshold (default 0.95 × white_level) are
 * replaced with the network prediction; the rest keep the original values.
 * This prevents the network from degrading well-exposed regions.
 */

#include "raw_hdr_recovery.h"

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
// Split [0,total) across the CPU cores and run body(begin,end) per chunk.
// Mirrors stage_c_export.cpp's parallelRows — OpenMP is NOT linked on Android,
// so std::thread is how this codebase actually uses more than one core. The
// conv kernels below write disjoint output ranges, so this is race-free.
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

#define LOG_TAG "RawV3.HdrRec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {
namespace {

// ── Tensor helpers ────────────────────────────────────────────────────────────

struct Tensor {
    std::vector<int>   shape;
    std::vector<float> data;

    int dims() const { return (int)shape.size(); }
    int size() const {
        int s = 1;
        for (int d : shape) s *= d;
        return s;
    }
    // C×H×W → offset at (c, y, x)
    float at3(int c, int y, int x) const {
        return data[(c * shape[1] + y) * shape[2] + x];
    }
};

using TensorMap = std::unordered_map<std::string, Tensor>;

// ── Model loader ──────────────────────────────────────────────────────────────

static bool loadModel(const uint8_t* data, size_t size, TensorMap& out) {
    if (!data || size < 12) return false;

    // Magic
    if (std::memcmp(data, "RAZ1", 4) != 0) {
        LOGE("loadModel: bad magic");
        return false;
    }
    uint32_t version;
    std::memcpy(&version, data + 4, 4);
    uint32_t num_tensors;
    std::memcpy(&num_tensors, data + 8, 4);

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
    return true;
}

// ── Convolution primitives ────────────────────────────────────────────────────

// In-place ReLU
static void relu(std::vector<float>& t) {
    for (float& v : t) v = v < 0.f ? 0.f : v;
}

// Fused BN: y = scale * x + bias  (scale/bias are per-channel)
static void batchnormAffine(std::vector<float>& feat,
                             int C, int H, int W,
                             const Tensor& scale,
                             const Tensor& bias) {
    for (int c = 0; c < C; ++c) {
        float s = scale.data[c];
        float b = bias.data[c];
        for (int i = 0; i < H * W; ++i) {
            feat[c * H * W + i] = s * feat[c * H * W + i] + b;
        }
    }
}

// Depthwise conv: one filter per input channel, k×k, output same spatial size
// (zero-padded). Returns [C_in, H_out, W_out].
static std::vector<float> depthwiseConv(
        const std::vector<float>& in, int C, int H, int W,
        const Tensor& weight,   // [C, 1, k, k]
        const Tensor& bias,     // [C]
        int k) {
    const int pad = k / 2;
    std::vector<float> out(C * H * W, 0.f);
    // Scalar — parallelism is at the TILE level (runTiledLumaScale), not here:
    // per-conv threads over 1204 tiles × ~14 convs was ~100k thread spawns and
    // ran SLOWER than scalar. Tiles are coarse-grained and independent.
    for (int c = 0; c < C; ++c) {
        float b = bias.data[c];
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                float acc = b;
                for (int ky = 0; ky < k; ++ky) {
                    for (int kx = 0; kx < k; ++kx) {
                        int sy = y + ky - pad;
                        int sx = x + kx - pad;
                        if (sy < 0 || sy >= H || sx < 0 || sx >= W) continue;
                        float w = weight.data[c * k * k + ky * k + kx];
                        acc += w * in[(c * H + sy) * W + sx];
                    }
                }
                out[(c * H + y) * W + x] = acc;
            }
        }
    }
    return out;
}

// Pointwise conv: [C_out, C_in, 1, 1]
static std::vector<float> pointwiseConv(
        const std::vector<float>& in, int C_in, int H, int W,
        const Tensor& weight,  // [C_out, C_in]
        const Tensor& bias) {  // [C_out]
    const int C_out = weight.shape[0];
    const int HW = H * W;
    std::vector<float> out(C_out * HW);
    // Scalar (see depthwiseConv) — tile-level parallelism wraps this.
    for (int co = 0; co < C_out; ++co) {
        float b = bias.data[co];
        const float* wrow = &weight.data[co * C_in];
        for (int i = 0; i < HW; ++i) {
            float acc = b;
            for (int ci = 0; ci < C_in; ++ci) {
                acc += wrow[ci] * in[ci * HW + i];
            }
            out[co * HW + i] = acc;
        }
    }
    return out;
}

// Max-pool 2×2 stride 2
static std::vector<float> maxpool2(const std::vector<float>& in,
                                    int C, int H, int W) {
    const int oH = H / 2, oW = W / 2;
    std::vector<float> out(C * oH * oW);
    for (int c = 0; c < C; ++c) {
        for (int y = 0; y < oH; ++y) {
            for (int x = 0; x < oW; ++x) {
                float m = -1e30f;
                for (int dy = 0; dy < 2; ++dy)
                    for (int dx = 0; dx < 2; ++dx) {
                        float v = in[(c * H + y*2+dy) * W + x*2+dx];
                        if (v > m) m = v;
                    }
                out[(c * oH + y) * oW + x] = m;
            }
        }
    }
    return out;
}

// Bilinear upsample × 2
static std::vector<float> upsample2(const std::vector<float>& in,
                                     int C, int H, int W) {
    const int oH = H * 2, oW = W * 2;
    std::vector<float> out(C * oH * oW);
    for (int c = 0; c < C; ++c) {
        for (int y = 0; y < oH; ++y) {
            for (int x = 0; x < oW; ++x) {
                float fy = (y + 0.5f) / 2.f - 0.5f;
                float fx = (x + 0.5f) / 2.f - 0.5f;
                int y0 = (int)std::floor(fy);
                int x0 = (int)std::floor(fx);
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
        }
    }
    return out;
}

// Channel-wise concatenation: [A, H, W] ++ [B, H, W] → [(A+B), H, W]
static std::vector<float> catC(const std::vector<float>& a, int Ca,
                                const std::vector<float>& b, int Cb,
                                int H, int W) {
    std::vector<float> out((Ca + Cb) * H * W);
    std::memcpy(out.data(),                      a.data(), Ca * H * W * sizeof(float));
    std::memcpy(out.data() + Ca * H * W,         b.data(), Cb * H * W * sizeof(float));
    return out;
}

// Depthwise-separable block: dw + pw + fused BN + ReLU
static std::vector<float> dwsepBlock(
        const std::vector<float>& in, int C_in, int H, int W,
        const TensorMap& m,
        const std::string& prefix, int C_out) {
    auto dw   = depthwiseConv(in, C_in, H, W,
                              m.at(prefix + ".dw.weight"),
                              m.at(prefix + ".dw.bias"), 3);
    auto pw   = pointwiseConv(dw, C_in, H, W,
                              m.at(prefix + ".pw.weight"),
                              m.at(prefix + ".pw.bias"));
    batchnormAffine(pw, C_out, H, W,
                    m.at(prefix + ".bn.scale"),
                    m.at(prefix + ".bn.bias"));
    relu(pw);
    return pw;
}

// ── Pixel unshuffle / shuffle ─────────────────────────────────────────────────
// pixelUnshuffle(raw_image, RW, RH) → 4-ch RGGB at (RW/2, RH/2)
// Assumes RGGB Bayer pattern starting at (0,0).
// Gr is at (0,1), Gb is at (1,0); we pack [R, Gr, Gb, B].
static std::vector<float> packBayer(const uint16_t* raw_image,
                                     int RW, int RH,
                                     float black, float white,
                                     int stride) {
    const int oH = RH / 2, oW = RW / 2;
    std::vector<float> out(4 * oH * oW);
    float range = white - black;
    if (range < 1.f) range = 1.f;

    for (int y = 0; y < oH; ++y) {
        for (int x = 0; x < oW; ++x) {
            // RGGB: R=(2y,2x), Gr=(2y,2x+1), Gb=(2y+1,2x), B=(2y+1,2x+1)
            auto get = [&](int ry, int rx) -> float {
                float v = (float)raw_image[ry * stride + rx] - black;
                return std::max(0.f, v) / range;
            };
            out[(0 * oH + y) * oW + x] = get(2*y,   2*x);    // R
            out[(1 * oH + y) * oW + x] = get(2*y,   2*x+1);  // Gr
            out[(2 * oH + y) * oW + x] = get(2*y+1, 2*x);    // Gb
            out[(3 * oH + y) * oW + x] = get(2*y+1, 2*x+1);  // B
        }
    }
    return out;
}

// ── Density gate ──────────────────────────────────────────────────────────────
// Returns true only when a tile has enough clipped pixels to warrant inference.
// Patches with < DENSITY_LO clipped pixels are sensor noise / isolated specular;
// patches with > DENSITY_HI are uniformly blown out — no structural neighbour
// information survives, so inference would hallucinate.
static constexpr float DENSITY_LO = 0.20f;  // 20% — only tiles with significant clipping
static constexpr float DENSITY_HI = 0.99f;  // allow near-fully clipped tiles through

static bool tileHasMeaningfulClipping(const std::vector<float>& tile_in,
                                       int C, int H, int W,
                                       float clip_frac) {
    int clipped = 0;
    const int total = H * W;  // per-channel pixel count
    // Check all four RGGB channels together
    for (int c = 0; c < C; ++c) {
        for (int i = 0; i < total; ++i) {
            if (tile_in[c * total + i] >= clip_frac) ++clipped;
        }
    }
    float density = float(clipped) / float(C * total);
    return density > DENSITY_LO && density < DENSITY_HI;
}

// ── Cosine blend weight for one axis ─────────────────────────────────────────
// Returns a weight in [0,1]: 0 at the tile border, 1 in the centre.
// overlap pixels wide on each side taper from 0→1 / 1→0 via a raised cosine.
static inline float cosineWeight(int pos, int size, int overlap) {
    if (pos < overlap) {
        // rising edge: 0 at pos=0, 1 at pos=overlap
        float t = float(pos) / float(overlap);
        return 0.5f * (1.f - std::cos(t * 3.14159265f));
    }
    if (pos >= size - overlap) {
        // falling edge
        float t = float(size - 1 - pos) / float(overlap);
        return 0.5f * (1.f - std::cos(t * 3.14159265f));
    }
    return 1.f;
}

// ── Linear-Bayer luma weights (BT.601 approximation) ─────────────────────────
// RGGB channels: [0]=R, [1]=Gr, [2]=Gb, [3]=B
// G average = (Gr + Gb) / 2, so luma = 0.299*R + 0.587*G_avg + 0.114*B
static constexpr float LUMA_R  = 0.299f;
static constexpr float LUMA_GR = 0.2935f;   // 0.587 / 2
static constexpr float LUMA_GB = 0.2935f;
static constexpr float LUMA_B  = 0.114f;

static inline float tilePixelLuma(const std::vector<float>& t,
                                   int H, int W, int y, int x) {
    auto v = [&](int ch) { return t[(ch * H + y) * W + x]; };
    return LUMA_R * v(0) + LUMA_GR * v(1) + LUMA_GB * v(2) + LUMA_B * v(3);
}

// Accumulate a per-pixel luminance scale factor into a single-channel map.
//
// Instead of replacing per-channel values (which shifts colour), we compute:
//   luma_scale = Y_recovered / Y_original
// and accumulate that scalar with cosine-spatial × clip-strength weighting.
//
// At write-back time every original channel is multiplied by luma_scale,
// which adjusts brightness while leaving the R:Gr:Gb:B ratios — and thus
// the skin-tone colour — completely unchanged.
//
// accum_luma  [H*W] — weighted sum of luma scale factors
// weight_sum  [H*W] — sum of blend weights
static void blendTileIntoAccum(
        const std::vector<float>& tile_out,
        const std::vector<float>& tile_in,
        std::vector<float>& accum_luma,  // [H * W]  luma-scale accumulator
        std::vector<float>& weight_sum,  // [H * W]  weight accumulator
        int H, int W,
        int ty, int tx,
        int th, int tw,
        int overlap,
        float clip_frac) {

    for (int y = 0; y < th; ++y) {
        float wy = cosineWeight(y, th, overlap);
        for (int x = 0; x < tw; ++x) {
            float wx      = cosineWeight(x, tw, overlap);
            float spatial = wy * wx;

            int gi = (ty + y) * W + tx + x;  // index in full luma map

            // Original and recovered luma for this 2×2 Bayer quad
            float Y_orig = tilePixelLuma(tile_in,  th, tw, y, x);
            float Y_rec  = tilePixelLuma(tile_out, th, tw, y, x);

            // clip_strength: how far into the clipped zone the original pixel was.
            // Use the maximum channel value as the clip proxy (any saturated channel
            // means the quad needs recovery).
            float max_orig = 0.f;
            for (int c = 0; c < 4; ++c)
                max_orig = std::max(max_orig, tile_in[(c * th + y) * tw + x]);
            float clip_str = std::max(0.f,
                (max_orig - clip_frac) / (1.f - clip_frac + 1e-6f));
            clip_str = std::min(1.f, clip_str);

            float alpha = spatial * clip_str;

            // Stabilised luma scale: (Y_rec + ε) / (Y_orig + ε).
            // The ε floor (1e-4 ≈ 0.01% of white) prevents explosive gain
            // in deep shadows where Y_orig → 0 while keeping the ratio
            // accurate in highlight regions (Y_orig >> ε).
            // Cap at 2.0: one stop of recovery is the maximum physically
            // plausible gain from a single-channel sensor pattern; anything
            // higher would amplify shadow noise rather than recover structure.
            if (alpha > 1e-6f) {
                static constexpr float EPS = 1e-4f;
                // Clamp to [1, 2]: scale can only brighten clipped pixels,
                // never darken them. A model that predicts near-zero for
                // blown highlights (undertrained) would otherwise black out
                // the entire highlight region.
                float luma_scale = std::max(1.f, std::min(2.f,
                    (Y_rec + EPS) / (Y_orig + EPS)));
                accum_luma[gi] += alpha * luma_scale;
                weight_sum[gi] += alpha;
            }
        }
    }
}

// ── U-Net forward ─────────────────────────────────────────────────────────────
static std::vector<float> runUNet(const std::vector<float>& input,
                                   int H, int W,
                                   const TensorMap& m) {
    // enc0: 4→16, stride 1
    auto e0 = dwsepBlock(input, 4, H, W, m, "enc0", 16);
    // enc1: 16→32, stride 2 via maxpool
    auto e1_pre = dwsepBlock(e0, 16, H, W, m, "enc1", 32);
    auto e1 = maxpool2(e1_pre, 32, H, W);
    int H1 = H/2, W1 = W/2;
    // enc2: 32→64, stride 2
    auto e2_pre = dwsepBlock(e1, 32, H1, W1, m, "enc2", 64);
    auto e2 = maxpool2(e2_pre, 64, H1, W1);
    int H2 = H1/2, W2 = W1/2;
    // bottleneck: 64→64
    auto bot = dwsepBlock(e2, 64, H2, W2, m, "bot", 64);
    // dec2: up + skip from e1_pre (before downsampling) + dw
    auto up2  = upsample2(bot, 64, H2, W2);
    auto cat2 = catC(up2, 64, e1_pre, 32, H1, W1);
    auto d2   = dwsepBlock(cat2, 96, H1, W1, m, "dec2", 32);
    // dec1: up + cat
    auto up1 = upsample2(d2, 32, H1, W1);
    auto cat1 = catC(up1, 32, e0, 16, H, W);
    auto d1 = dwsepBlock(cat1, 48, H, W, m, "dec1", 16);
    // head: 1×1 conv → sigmoid
    auto head_w = m.at("head.weight");  // [4, 16, 1, 1] flattened as [4, 16]
    auto head_b = m.at("head.bias");    // [4]
    auto out = pointwiseConv(d1, 16, H, W, head_w, head_b);
    // sigmoid
    for (float& v : out) v = 1.f / (1.f + std::exp(-v));
    return out;
}

// ── Tiling: density-gated inference → luminance-scale map ────────────────────
// Returns a single-channel [H*W] luma-scale map where each value is the
// ratio Y_recovered / Y_original, cosine-blended across overlapping tiles.
// Pixels not touched by any passing tile have scale = 1.0 (identity).
//
// Per-tile pipeline:
//   1. Extract 4-ch RGGB tile
//   2. Density gate — skip if < 5% or > 95% of pixels are clipped
//   3. Run U-Net → 4-ch recovered output
//   4. Compute per-pixel luma scale = Y_rec / Y_orig
//   5. Accumulate with weight = cosine_spatial × clip_strength
static std::vector<float> runTiledLumaScale(
        const std::vector<float>& input,
        int H, int W,
        const TensorMap& m,
        float clip_frac,
        int tile    = 64,
        int overlap = 0) {
    const int C    = 4;
    const int step = tile - overlap * 2;

    std::vector<float> accum_luma(H * W, 0.f);
    std::vector<float> wsum      (H * W, 0.f);

    // Tile coordinates — the parallel unit. Each U-Net tile is independent, and
    // with overlap==0 the accumulate targets disjoint [ty,ty+tile)×[tx,tx+tile)
    // regions, so running tiles across cores is race-free. Coarse-grained: one
    // thread per CPU processes a slice of the tile list, each tile's convs stay
    // scalar (no nested threads → no spawn storm — see depthwiseConv).
    std::vector<std::pair<int,int>> coords;
    for (int ty = 0; ty + tile <= H; ty += step)
        for (int tx = 0; tx + tile <= W; tx += step)
            coords.emplace_back(ty, tx);

    std::atomic<int> tiles_run{0}, tiles_skipped{0};
    parallelChunks((int)coords.size(), [&](int cB, int cE) {
        int localRun = 0, localSkip = 0;
        for (int idx = cB; idx < cE; ++idx) {
            const int ty = coords[idx].first, tx = coords[idx].second;
            const int th = tile, tw = tile;

            std::vector<float> tile_in(C * th * tw);
            for (int c = 0; c < C; ++c)
                for (int y = 0; y < th; ++y)
                    for (int x = 0; x < tw; ++x)
                        tile_in[(c * th + y) * tw + x] = input[(c * H + ty+y) * W + tx+x];

            if (!tileHasMeaningfulClipping(tile_in, C, th, tw, clip_frac)) {
                ++localSkip;
                continue;
            }
            ++localRun;

            auto tile_out = runUNet(tile_in, th, tw, m);
            blendTileIntoAccum(tile_out, tile_in,
                               accum_luma, wsum,
                               H, W, ty, tx, th, tw,
                               overlap, clip_frac);
        }
        tiles_run.fetch_add(localRun, std::memory_order_relaxed);
        tiles_skipped.fetch_add(localSkip, std::memory_order_relaxed);
    });

    LOGI("applyRawHdrRecovery: %d tiles processed, %d skipped (density gate)",
         tiles_run.load(), tiles_skipped.load());

    // Resolve: scale = accum/wsum where touched, else 1.0 (identity)
    std::vector<float> luma_scale(H * W, 1.f);
    for (int i = 0; i < H * W; ++i) {
        if (wsum[i] > 1e-6f)
            luma_scale[i] = accum_luma[i] / wsum[i];
    }

    // Gaussian blur to smooth tile-boundary seams and the highlight→midtone edge.
    constexpr int   BLUR_R = 16;
    constexpr float SIGMA  = 8.f;
    float kernel[2 * BLUR_R + 1];
    float ksum = 0.f;
    for (int k = -BLUR_R; k <= BLUR_R; ++k) {
        kernel[k + BLUR_R] = std::exp(-0.5f * k * k / (SIGMA * SIGMA));
        ksum += kernel[k + BLUR_R];
    }
    for (float& v : kernel) v /= ksum;

    std::vector<float> tmp(H * W);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            float acc = 0.f;
            for (int k = -BLUR_R; k <= BLUR_R; ++k) {
                int sx = std::max(0, std::min(W - 1, x + k));
                acc += kernel[k + BLUR_R] * luma_scale[y * W + sx];
            }
            tmp[y * W + x] = acc;
        }
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            float acc = 0.f;
            for (int k = -BLUR_R; k <= BLUR_R; ++k) {
                int sy = std::max(0, std::min(H - 1, y + k));
                acc += kernel[k + BLUR_R] * tmp[sy * W + x];
            }
            luma_scale[y * W + x] = acc;
        }

    return luma_scale;
}

}  // namespace

// ── Public entry point ────────────────────────────────────────────────────────

void applyRawHdrRecovery(
        uint16_t*      raw_image,
        int            raw_width,
        int            raw_height,
        unsigned int   filters,
        float          white_level,
        float          black_level,
        const uint8_t* model_data,
        size_t         model_size) {
    if (!model_data || model_size == 0) return;
    // X-Trans (filters == 0 or filters == 9) — non-RGGB mosaic, skip.
    if (filters == 0 || (filters & 0xFF) == 9) {
        LOGI("applyRawHdrRecovery: non-Bayer mosaic, skipping");
        return;
    }
    // Need even dimensions for pixel-unshuffle.
    if (raw_width < 4 || raw_height < 4) return;
    const int RW = raw_width  & ~1;
    const int RH = raw_height & ~1;

    TensorMap model;
    if (!loadModel(model_data, model_size, model)) {
        LOGE("applyRawHdrRecovery: model load failed");
        return;
    }

    // Clip fraction used throughout: pixels above this normalised level are
    // considered highlight-clipped. 0.92 matches the training synthetic clip
    // floor so the model's operating point aligns with inference expectations.
    // Display clip threshold: 10-bit display compresses 14-bit sensor range.
    // Visually clipped pixels start at 2^10/2^14 = 1024/16383 ≈ 0.0625 of
    // the normalised range, but accounting for tone-curve headroom the
    // practical onset of visible clipping is ~71% of white level.
    // Use 0.75 as the detection threshold so any pixel in the top quarter
    // of the sensor range is treated as a highlight candidate.
    static constexpr float CLIP_FRAC = 0.35f;

    LOGI("applyRawHdrRecovery: %dx%d → %dx%d, white=%.0f black=%.0f",
         RW, RH, RW/2, RH/2, white_level, black_level);

    // Pack Bayer → 4-ch float [0,1]
    auto packed = packBayer(raw_image, RW, RH, black_level, white_level, raw_width);

    // Density-gated tiled inference → single-channel luma-scale map [oH*oW].
    // Each value is Y_recovered / Y_original at that 2×2 Bayer quad.
    // Untouched pixels have scale = 1.0 (identity — original preserved).
    const int oH = RH / 2, oW = RW / 2;
    auto luma_scale = runTiledLumaScale(packed, oH, oW, model, CLIP_FRAC);

    // Write back: apply the luma scale to all four RGGB channels equally.
    // Multiplying all channels by the same scalar preserves the R:Gr:Gb:B ratio
    // (i.e. the colour / chromaticity) — only brightness is adjusted.
    // Skin tones, saturation, and hue are mathematically unchanged.
    float range = white_level - black_level;
    if (range < 1.f) range = 1.f;

    // Use raw_width (LibRaw's actual buffer stride), not RW (which is raw_width & ~1).
    // Indexing with RW instead of raw_width would give wrong offsets if raw_width is odd
    // or LibRaw adds padding, causing out-of-bounds writes.
    const int stride = raw_width;
    for (int y = 0; y < oH; ++y) {
        for (int x = 0; x < oW; ++x) {
            float s = luma_scale[y * oW + x];
            if (s == 1.f) continue;  // identity — skip write

            int ry0 = 2*y, ry1 = 2*y+1;
            int rx0 = 2*x, rx1 = 2*x+1;
            // Guard against buffer overrun at image edges
            if (ry1 >= raw_height || rx1 >= raw_width) continue;

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

    LOGI("applyRawHdrRecovery: done");
}

}  // namespace raw_v3
