/*
 * JPEG Dual Reconstruction Lite — edge-aware fusion of a clean branch
 * (box + range-gated smooth) and a detail branch (micro-contrast USM).
 * Not demosaic. Strength 0 is a no-op.
 */

#include "jpeg_dual_recon.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace raw_v3 {
namespace {

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

inline float lumaOf(float r, float g, float b) {
    return r * 0.299f + g * 0.587f + b * 0.114f;
}

void boxBlur(const std::vector<float>& src, std::vector<float>& dst, int w, int h, int r) {
    dst.assign(src.size(), 0.f);
    if (r < 1) { dst = src; return; }
    std::vector<float> tmp(src.size());
    const int diam = r * 2 + 1;
    for (int y = 0; y < h; ++y) {
        float acc = 0.f;
        for (int x = -r; x <= r; ++x) {
            const int xx = std::clamp(x, 0, w - 1);
            acc += src[size_t(y) * w + xx];
        }
        for (int x = 0; x < w; ++x) {
            tmp[size_t(y) * w + x] = acc / float(diam);
            const int add = std::clamp(x + r + 1, 0, w - 1);
            const int sub = std::clamp(x - r, 0, w - 1);
            acc += src[size_t(y) * w + add] - src[size_t(y) * w + sub];
        }
    }
    for (int x = 0; x < w; ++x) {
        float acc = 0.f;
        for (int y = -r; y <= r; ++y) {
            const int yy = std::clamp(y, 0, h - 1);
            acc += tmp[size_t(yy) * w + x];
        }
        for (int y = 0; y < h; ++y) {
            dst[size_t(y) * w + x] = acc / float(diam);
            const int add = std::clamp(y + r + 1, 0, h - 1);
            const int sub = std::clamp(y - r, 0, h - 1);
            acc += tmp[size_t(add) * w + x] - tmp[size_t(sub) * w + x];
        }
    }
}

}  // namespace

template <typename T>
void applyJpegDualRecon(
    T* pixels,
    int w,
    int h,
    int strideInPixels,
    int channels,
    float strength,
    float cleanBias,
    float detailBias) {
    if (!pixels || w < 3 || h < 3 || channels < 3) return;
    strength = clampf(strength, 0.f, 1.f);
    if (strength < 0.001f) return;
    cleanBias  = clampf(cleanBias, 0.f, 1.f);
    detailBias = clampf(detailBias, 0.f, 1.f);

    const int n = w * h;
    std::vector<float> L(n), clean(n), detail(n), edge(n);
    for (int y = 0; y < h; ++y) {
        const T* row = pixels + size_t(y) * strideInPixels * channels;
        for (int x = 0; x < w; ++x) {
            const T* px = row + size_t(x) * channels;
            L[size_t(y) * w + x] = lumaOf(float(px[0]), float(px[1]), float(px[2]));
        }
    }

    const float resScale = float(std::max(w, h)) / 2560.f;
    const int rClean = std::max(1, int(std::lround(resScale * 3.f)));
    const int rUsm   = std::max(1, int(std::lround(resScale * 1.f)));

    std::vector<float> blurC, blurU;
    boxBlur(L, blurC, w, h, rClean);
    boxBlur(L, blurU, w, h, rUsm);

    // Clean: range-gated mix of original vs blur (bilateral-lite).
    const float rangeSigma = 0.08f;
    for (int i = 0; i < n; ++i) {
        const float d = L[i] - blurC[i];
        const float wRange = std::exp(-(d * d) / (2.f * rangeSigma * rangeSigma));
        clean[i] = L[i] * (1.f - wRange) + blurC[i] * wRange;
    }

    // Detail: light USM + local micro-contrast vs the clean blur.
    for (int i = 0; i < n; ++i) {
        const float usm = L[i] + 0.55f * (L[i] - blurU[i]);
        const float micro = L[i] + 0.35f * (L[i] - blurC[i]);
        detail[i] = 0.55f * usm + 0.45f * micro;
    }

    // Sobel structure mask (not DCT-aligned — lens-corrected JPEGs are warped).
    for (int y = 0; y < h; ++y) {
        const int yu = std::max(0, y - 1);
        const int yd = std::min(h - 1, y + 1);
        for (int x = 0; x < w; ++x) {
            const int xl = std::max(0, x - 1);
            const int xr = std::min(w - 1, x + 1);
            const float gx =
                -L[size_t(yu) * w + xl] + L[size_t(yu) * w + xr]
                - 2.f * L[size_t(y) * w + xl] + 2.f * L[size_t(y) * w + xr]
                - L[size_t(yd) * w + xl] + L[size_t(yd) * w + xr];
            const float gy =
                -L[size_t(yu) * w + xl] - 2.f * L[size_t(yu) * w + x] - L[size_t(yu) * w + xr]
                + L[size_t(yd) * w + xl] + 2.f * L[size_t(yd) * w + x] + L[size_t(yd) * w + xr];
            edge[size_t(y) * w + x] = clampf(std::sqrt(gx * gx + gy * gy) * 2.2f, 0.f, 1.f);
        }
    }

    for (int y = 0; y < h; ++y) {
        T* row = pixels + size_t(y) * strideInPixels * channels;
        for (int x = 0; x < w; ++x) {
            const int i = y * w + x;
            const float t = clampf(
                edge[i] * (0.35f + 0.65f * detailBias) +
                (1.f - edge[i]) * (1.f - cleanBias) * 0.35f,
                0.f, 1.f);
            const float fused = clean[i] * (1.f - t) + detail[i] * t;
            const float outL = L[i] * (1.f - strength) + fused * strength;
            const float scale = (L[i] > 1e-5f) ? (outL / L[i]) : 1.f;
            T* px = row + size_t(x) * channels;
            px[0] = T(float(px[0]) * scale);
            px[1] = T(float(px[1]) * scale);
            px[2] = T(float(px[2]) * scale);
        }
    }
}

template void applyJpegDualRecon<__fp16>(
    __fp16*, int, int, int, int, float, float, float);
template void applyJpegDualRecon<float>(
    float*, int, int, int, int, float, float, float);

}  // namespace raw_v3
