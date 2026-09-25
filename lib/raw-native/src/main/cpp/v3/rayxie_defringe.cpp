// rayxie_defringe.cpp — see rayxie_defringe.h for why this is a median and not
// a guided filter, the measured comparison, the colour-domain contract, and why
// subtracting the DEVIATION rather than the baseline is load-bearing.

#include "rayxie_defringe.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RayxieDefringe", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { printf("[RayxieDefringe] " __VA_ARGS__); printf("\n"); } while (0)
#endif

namespace raw_v3 {
namespace {

constexpr int kMinRadius = 3;    // below this the window is narrower than a
                                 // typical fringe and the median returns the
                                 // fringe itself (measured: r=2 gives +0.0%)
constexpr int kMaxRadius = 8;    // past this the result is flat, so extra
                                 // radius buys nothing but time
constexpr int kMaxWindow = 2 * kMaxRadius + 1;

inline float h2f(uint16_t h) {
    const uint32_t sign = uint32_t(h & 0x8000u) << 16;
    const uint32_t e    = (h >> 10) & 0x1Fu;
    const uint32_t m    = h & 0x3FFu;
    uint32_t r32;
    if (e == 0)       r32 = sign | (m == 0 ? 0u : (((1u + 127u - 15u) << 23) | (m << 13)));
    else if (e == 31) r32 = sign | 0x7F800000u | (m << 13);
    else              r32 = sign | ((e + (127u - 15u)) << 23) | (m << 13);
    float f; std::memcpy(&f, &r32, 4); return f;
}
inline uint16_t f2h(float f) {
    uint32_t x; std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    int32_t  exp  = int32_t((x >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = x & 0x7FFFFFu;
    if (exp <= 0)  return uint16_t(sign);
    if (exp >= 31) return uint16_t(sign | 0x7C00u);
    return uint16_t(sign | (uint32_t(exp) << 10) | (mant >> 13));
}

// Median of up to kMaxWindow floats, in place. Insertion sort beats
// std::nth_element at this size (no partitioning overhead, no allocation) and
// this is the innermost loop of the whole pass.
inline float medianOf(float* v, int n) {
    for (int i = 1; i < n; ++i) {
        const float key = v[i];
        int j = i - 1;
        while (j >= 0 && v[j] > key) { v[j + 1] = v[j]; --j; }
        v[j + 1] = key;
    }
    return v[n / 2];
}

// Separable median: median along x, then along y. Not a true 2-D median, but
// O(r) instead of O(r^2) and it preserves straight edges just as well —
// verified at 0 px colour-edge disturbance, identical to the exact median.
//
// Optimized Vertical Pass: Column-major access into large planes (W > 5000)
// is the #1 cause of watchdog timeouts. We now process in column-blocks (64 px)
// and transpose into a contiguous scratch buffer to ensure the median sort
// hits L1/L2 cache instead of constantly missing to DRAM.
void separableMedian(const float* src, float* dst, float* mid,
                     int w, int h, int r, int y0, int y1) {
    float buf[kMaxWindow];
    // Horizontal, over the rows this worker will need vertically (y0-r .. y1+r).
    const int hy0 = std::max(0, y0 - r), hy1 = std::min(h - 1, y1 + r);
    for (int y = hy0; y <= hy1; ++y) {
        const float* s = src + size_t(y) * w;
        float* d = mid + size_t(y) * w;
        for (int x = 0; x < w; ++x) {
            const int lo = std::max(0, x - r), hi = std::min(w - 1, x + r);
            const int n = hi - lo + 1;
            for (int i = 0; i < n; ++i) buf[i] = s[lo + i];
            d[x] = medianOf(buf, n);
        }
    }

    // Vertical. Process in column-blocks to preserve cache locality.
    constexpr int kXBlock = 64;
    std::vector<float> tile(size_t(kXBlock) * (hy1 - hy0 + 1));

    for (int x0 = 0; x0 < w; x0 += kXBlock) {
        const int xw = std::min(kXBlock, w - x0);
        // Transpose strip from `mid` into `tile` contiguous storage.
        for (int y = hy0; y <= hy1; ++y) {
            const float* s = mid + size_t(y) * w + x0;
            float* t = tile.data() + size_t(y - hy0) * kXBlock;
            for (int x = 0; x < xw; ++x) t[x] = s[x];
        }

        // Run median sort on the contiguous tile.
        for (int y = y0; y <= y1; ++y) {
            float* d = dst + size_t(y) * w + x0;
            const int lo = (y - r) - hy0, hi = (y + r) - hy0;
            const int bh = hy1 - hy0 + 1;
            for (int x = 0; x < xw; ++x) {
                const int nlo = std::max(0, lo), nhi = std::min(bh - 1, hi);
                const int n = nhi - nlo + 1;
                for (int i = 0; i < n; ++i) {
                    buf[i] = tile[size_t(nlo + i) * kXBlock + x];
                }
                d[x] = medianOf(buf, n);
            }
        }
    }
}

}  // namespace

bool rayxie_defringe_f16(uint16_t* rgba, int W, int H,
                         const RayxieDefringeParams& p) {
    if (!rgba || W < 16 || H < 16 || p.strength <= 0.f) return false;

    const int longSide = std::max(W, H);
    const int radius = std::max(kMinRadius, std::min(kMaxRadius,
        (int)lrintf(float(p.radius) * float(longSide) / 2560.f)));

    // Bands keep the working set bounded by width, not megapixels. The halo is
    // the vertical median reach; the horizontal pass needs no halo because it
    // never crosses a row.
    const int halo = radius + 1;
    const int bandRows = std::max(64, std::min(256, H));

    const size_t planeMax = size_t(W) * size_t(bandRows + 2 * halo);
    std::vector<float> gPlane(planeMax), pPlane(planeMax),
                       mPlane(planeMax), midPlane(planeMax), edge(planeMax);

    const unsigned hw = std::max(1u, std::thread::hardware_concurrency());
    const int nThreads = (int)std::min<unsigned>(hw, 8u);
    std::atomic<long long> changed{0};

    for (int y0 = 0; y0 < H; y0 += bandRows) {
        const int rows = std::min(bandRows, H - y0);
        const int yTop = std::max(0, y0 - halo);
        const int yBot = std::min(H - 1, y0 + rows - 1 + halo);
        const int bh   = yBot - yTop + 1;

        // Guide/green and the edge gate, in the buffer's own sRGB domain.
        for (int y = 0; y < bh; ++y) {
            const uint16_t* src = rgba + (size_t(yTop + y) * size_t(W)) * 4;
            float* g = gPlane.data() + size_t(y) * W;
            for (int x = 0; x < W; ++x) g[x] = h2f(src[x * 4 + 1]);
        }
        for (int y = 0; y < bh; ++y) {
            const float* gc = gPlane.data() + size_t(y) * W;
            const float* gu = gPlane.data() + size_t(std::max(0, y - 1)) * W;
            const float* gd = gPlane.data() + size_t(std::min(bh - 1, y + 1)) * W;
            float* e = edge.data() + size_t(y) * W;
            for (int x = 0; x < W; ++x) {
                const int xl = std::max(0, x - 1), xr = std::min(W - 1, x + 1);
                const float gx = gc[xr] - gc[xl];
                const float gy = gd[x]  - gu[x];
                // 0.05 of full scale over two pixels is already a firm edge.
                const float t = std::min(1.0f, sqrtf(gx * gx + gy * gy) / 0.05f);
                // Mix toward 1 by (1 - edgeGate), so edgeGate = 0 means no gate.
                e[x] = p.edgeGate * t + (1.0f - p.edgeGate);
            }
        }

        for (int ch = 0; ch < 2; ++ch) {              // R-G, then B-G
            const int comp = (ch == 0) ? 0 : 2;
            for (int y = 0; y < bh; ++y) {
                const uint16_t* src = rgba + (size_t(yTop + y) * size_t(W)) * 4;
                float* pp = pPlane.data() + size_t(y) * W;
                const float* g = gPlane.data() + size_t(y) * W;
                for (int x = 0; x < W; ++x) pp[x] = h2f(src[x * 4 + comp]) - g[x];
            }

            // Median over the band, split by row range across threads. Each
            // worker redoes the horizontal pass for its own halo rows, which is
            // cheaper than synchronising a shared intermediate.
            {
                std::vector<std::thread> pool;
                const int chunk = std::max(1, (bh + nThreads - 1) / nThreads);
                for (int t = 0; t < nThreads; ++t) {
                    const int a = t * chunk, b = std::min(bh - 1, a + chunk - 1);
                    if (a > b) break;
                    pool.emplace_back([&, a, b] {
                        separableMedian(pPlane.data(), mPlane.data(),
                                        midPlane.data(), W, bh, radius, a, b);
                    });
                }
                for (auto& th : pool) th.join();
            }

            // Apply to the band's own rows only; the halo existed purely to give
            // the median correct context.
            long long local = 0;
            for (int y = 0; y < rows; ++y) {
                const int by = (y0 + y) - yTop;
                uint16_t* dst = rgba + (size_t(y0 + y) * size_t(W)) * 4;
                const float* pp = pPlane.data() + size_t(by) * W;
                const float* mm = mPlane.data() + size_t(by) * W;
                const float* ee = edge.data()   + size_t(by) * W;
                for (int x = 0; x < W; ++x) {
                    // THE deviation. Subtracting mm instead would drive the
                    // channel toward green and grey the image out.
                    // Fringe color gate (after the Stage A white balance already
                    // baked into this buffer). Median only where the pixel is
                    // the purple fringe; other edges stay put.
                    {
                        const float rC = h2f(dst[x * 4 + 0]);
                        const float gC = h2f(dst[x * 4 + 1]);
                        const float bC = h2f(dst[x * 4 + 2]);
                        const float luma = 0.299f * rC + 0.587f * gC + 0.114f * bC;
                        const bool fringe = luma >= 0.45f && bC > gC &&
                            (rC / (bC + 1e-4f)) <= 0.33f;
                        if (!fringe) continue;
                    }
                    float delta = p.strength * ee[x] * (pp[x] - mm[x]);
                    if (delta >  p.maxDelta) delta =  p.maxDelta;
                    if (delta < -p.maxDelta) delta = -p.maxDelta;
                    if (delta == 0.f) continue;
                    const float v0 = h2f(dst[x * 4 + comp]);
                    float v = v0 - delta;
                    if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
                    if (v != v0) { dst[x * 4 + comp] = f2h(v); ++local; }
                }
            }
            changed.fetch_add(local, std::memory_order_relaxed);
        }
    }

    const long long n = changed.load();
    LOGI("defringe: %dx%d r=%d(base %d) strength=%.2f edgeGate=%.2f "
         "threads=%d -> %lld channel writes",
         W, H, radius, p.radius, p.strength, p.edgeGate, nThreads, n);
    return n > 0;
}

}  // namespace raw_v3
