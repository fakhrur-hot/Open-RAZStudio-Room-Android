// Shared separable Gaussian blur used by Stage B (preview kernel pre-pass)
// and Stage C (save kernel pre-pass) so both feed `applyMacroPixel` an
// identical reference layer when ambiance + Orton are active. The blur
// math mirrors the shader's `uBlurTex` pass; σ = radius/2 keeps the
// falloff shape consistent across preview and save resolutions.
//
// Header-only — both translation units inline these so we don't add a
// new linkage unit. `parallelRows` is a tiny helper that fans the
// horizontal/vertical passes across cores when the row count justifies
// the thread-spawn cost.

#pragma once

#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

namespace raw_v3 {

template <typename Fn>
inline void parallelRowsBlur(int count, Fn&& body) {
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

inline void gaussianBlurSeparableShared(const float* src, float* dst,
                                        int w, int h, int radius) {
    if (w <= 0 || h <= 0 || radius <= 0) {
        std::memcpy(dst, src, size_t(w) * h * 3 * sizeof(float));
        return;
    }
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

    std::vector<float> tmp(size_t(w) * h * 3);
    parallelRowsBlur(h, [&](int yB, int yE) {
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
    parallelRowsBlur(h, [&](int yB, int yE) {
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

} // namespace raw_v3
