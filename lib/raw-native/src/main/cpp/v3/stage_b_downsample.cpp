/*
 * StudioRoom — RAW Pipeline v3 — Stage B downsampler.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Two-pass Lanczos-3 (separable) downsample on RGBA_F16 pixels. Pass 1 is
 * horizontal into a transient FloatArray; pass 2 is vertical writing
 * directly into the AHardwareBuffer's mapped memory.
 *
 * Why separable Lanczos rather than the GLSL filter? Lanczos at the C++
 * stage gives crisp 8 MP base pixels regardless of the eventual on-screen
 * scale. Doing it on the GPU at sample time would re-filter on every
 * pinch-zoom event and re-blur stationary frames. Stage B is the *base*
 * texture; we want it once, clean, and stable.
 */

#include "stage_b_downsample.h"
#include "tiff_mmap_io.h"
#include "raw_v3_clahe.h"
#include "jpeg_dual_recon.h"
#include "raw_v3_nr.h"
#include "raw_v3_detail.h"


#include <android/log.h>
#include <android/hardware_buffer.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "RawV3.StageB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

namespace {

// Parallelize [0, count) across hardware threads. Body receives (begin, end).
// Falls back to a direct call for tiny ranges. Matches the raw_v3_clahe.cpp
// implementation so both files share the same load-balancing strategy.
template <typename Fn>
static void parallelForB(int count, Fn&& body) {
    if (count <= 0) return;
    unsigned hw = std::thread::hardware_concurrency();
    int workers = int(hw == 0 ? 1 : hw);
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
            body(begin, end);
        } else {
            pool.emplace_back([&body, begin, end] { body(begin, end); });
        }
    }
    for (auto& t : pool) t.join();
}

// ── IEEE 754 binary16 ↔ binary32 ────────────────────────────────────────────
inline float halfToFloat(uint16_t h) {
    uint32_t sign = (uint32_t(h) & 0x8000) << 16;
    uint32_t exp  = (h >> 10) & 0x1F;
    uint32_t mant = h & 0x3FF;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) {
            bits = sign;
        } else {
            // subnormal — normalize
            int e = -1;
            do { ++e; mant <<= 1; } while ((mant & 0x400) == 0);
            mant &= 0x3FF;
            bits = sign | (uint32_t(127 - 15 - e) << 23) | (mant << 13);
        }
    } else if (exp == 0x1F) {
        bits = sign | 0x7F800000 | (mant << 13);
    } else {
        bits = sign | (uint32_t(exp - 15 + 127) << 23) | (mant << 13);
    }
    float f; std::memcpy(&f, &bits, 4); return f;
}

inline uint16_t floatToHalf(float f) {
    uint32_t bits; std::memcpy(&bits, &f, 4);
    uint32_t sign = (bits >> 16) & 0x8000;
    int32_t  exp  = int32_t((bits >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = bits & 0x007FFFFF;
    if (((bits >> 23) & 0xFF) == 0xFF) {
        return uint16_t(sign | 0x7C00 | (mant ? 0x200 : 0));
    }
    if (exp >= 0x1F) return uint16_t(sign | 0x7C00);
    if (exp <= 0) {
        if (exp < -10) return uint16_t(sign);
        mant |= 0x00800000;
        uint32_t shift = uint32_t(14 - exp);
        uint32_t m = (mant >> shift) + ((mant >> (shift - 1)) & 1);
        return uint16_t(sign | m);
    }
    uint32_t m = (mant + 0x00001000) >> 13;
    if (m & 0x00000400) { m = 0; ++exp; if (exp >= 0x1F) return uint16_t(sign | 0x7C00); }
    return uint16_t(sign | (uint32_t(exp) << 10) | (m & 0x3FF));
}

// ── Lanczos-3 sinc kernel ──────────────────────────────────────────────────
inline float sinc(float x) {
    if (x == 0.f) return 1.f;
    float px = float(M_PI) * x;
    return std::sin(px) / px;
}
inline float lanczos3(float x) {
    constexpr float a = 3.f;
    if (x <= -a || x >= a) return 0.f;
    return sinc(x) * sinc(x / a);
}

/**
 * Build a per-output-pixel kernel table. For each output pixel [o] we store
 * the input-side window [start, start+count) and the `count` filter weights
 * (normalized to sum to 1).
 *
 * count is bounded by ceil(2 * a / scale) + 2 — at most ~13 taps when going
 * 5500 → 1224 (scale ≈ 0.222).
 */
struct KernelTable {
    std::vector<int>   start;   // first input index for output pixel o
    std::vector<int>   count;   // number of taps for output pixel o
    std::vector<float> weights; // packed weights, indexed [weightOffset[o]..]
    std::vector<int>   weightOffset;
};

KernelTable buildKernel(int inSize, int outSize) {
    KernelTable k;
    k.start.resize(outSize);
    k.count.resize(outSize);
    k.weightOffset.resize(outSize);
    k.weights.reserve(size_t(outSize) * 16);

    const float scale = float(outSize) / float(inSize);  // < 1 for downsample
    const float invScale = 1.f / scale;
    constexpr float a = 3.f;
    const float support = a * (scale < 1.f ? invScale : 1.f);

    for (int o = 0; o < outSize; ++o) {
        // Center of output pixel o in input coordinates.
        float center = (float(o) + 0.5f) * invScale - 0.5f;
        int lo = int(std::floor(center - support + 0.5f));
        int hi = int(std::floor(center + support + 0.5f));
        if (lo < 0) lo = 0;
        if (hi > inSize - 1) hi = inSize - 1;
        k.start[o]        = lo;
        k.count[o]        = hi - lo + 1;
        k.weightOffset[o] = int(k.weights.size());
        float sum = 0.f;
        for (int i = lo; i <= hi; ++i) {
            float arg = (float(i) - center) * scale;
            float w = lanczos3(arg);
            k.weights.push_back(w);
            sum += w;
        }
        // Normalize (sum may drift from 1 near edges).
        if (sum != 0.f) {
            float inv = 1.f / sum;
            for (int i = 0; i < k.count[o]; ++i) {
                k.weights[k.weightOffset[o] + i] *= inv;
            }
        }
    }
    return k;
}

// Spatial pre-pass (CLAHE → NR → Detail) applied IN-PLACE on a locked FP16 RGBA
// buffer. Factored out so the full downsample AND the fast pre-spatial-cache
// re-apply path (runStageBApplySpatialToAhb) share byte-identical math.
static void applyStageBSpatialInPlace(
    uint16_t* dstBase, int outW, int outH, int dstStridePx,
    bool claheEnabled, float claheShadowsBoost, float claheHighlightsBoost,
    float jpegRefineStrength, float jpegRefineClean, float jpegRefineDetail,
    float luminanceNR, float colorNR, float blueNR, float redNR,
    const DetailParams& detail,
    const float* subjectMask, int subjectMaskSize, int subjectMaskH) {
    // 0 = legacy square mask. The editor now hands the guided-filter refined
    // (image-aspect) mask so preview spatial passes match the GL grade's mask.
    const int maskH = subjectMaskH > 0 ? subjectMaskH : subjectMaskSize;
    if (claheEnabled) {
        applyClahe<__fp16>(reinterpret_cast<__fp16*>(dstBase),
                           outW, outH, dstStridePx, 4,
                           claheShadowsBoost, claheHighlightsBoost);
    }
    if (jpegRefineStrength > 0.001f) {
        applyJpegDualRecon<__fp16>(reinterpret_cast<__fp16*>(dstBase),
                                   outW, outH, dstStridePx, 4,
                                   jpegRefineStrength, jpegRefineClean, jpegRefineDetail);
    }
    if (luminanceNR > 0.f || colorNR > 0.f || blueNR > 0.f || redNR > 0.f) {
        applyNoiseReduction<__fp16>(reinterpret_cast<__fp16*>(dstBase),
                                    outW, outH, dstStridePx, 4,
                                    luminanceNR, colorNR, blueNR, redNR,
                                    subjectMask, subjectMaskSize, maskH);
    }
    if (detail.any()) {
        applyDetail<__fp16>(reinterpret_cast<__fp16*>(dstBase),
                            outW, outH, dstStridePx, 4,
                            detail, subjectMask, subjectMaskSize, maskH);
    }
}

}  // anonymous namespace

StageBResult runStageBDownsample(
    const std::string& stageATifPath,
    AHardwareBuffer* ahb,
    uint32_t targetW,
    uint32_t targetH,
    bool  claheEnabled,
    float claheShadowsBoost,
    float claheHighlightsBoost,
    float luminanceNR,
    float colorNR,
    float blueNR,
    float redNR,
    const DetailParams& detail,
    const float* subjectMask,
    int subjectMaskSize,
    int subjectMaskH,
    float jpegRefineStrength,
    float jpegRefineClean,
    float jpegRefineDetail,
    const volatile int8_t* cancelToken) {

// Checked before AHB lock (no unlock needed)
#define CHECK_CANCEL_EARLY() \
    if (cancelToken && *cancelToken) { \
        closeStageATiff(reader); \
        StageBResult cr; cr.cancelled = true; cr.error = "cancelled"; return cr; \
    }
// Checked after AHB lock (must unlock before returning)
#define CHECK_CANCEL() \
    if (cancelToken && *cancelToken) { \
        AHardwareBuffer_unlock(ahb, nullptr); \
        closeStageATiff(reader); \
        StageBResult cr; cr.cancelled = true; cr.error = "cancelled"; return cr; \
    }

    StageBResult r;
    auto t0 = std::chrono::steady_clock::now();

    StageATiffReader* reader = openStageATiff(stageATifPath);
    if (!reader) { r.error = "openStageATiff failed"; return r; }

    const auto& h = getStageATiffHeader(reader);
    const uint32_t inW = h.width;
    const uint32_t inH = h.height;
    if (inW == 0 || inH == 0) {
        r.error = "zero source dimensions";
        closeStageATiff(reader);
        return r;
    }

    // Compute output size preserving aspect ratio, capped at target and source.
    double scaleW = double(targetW) / double(inW);
    double scaleH = double(targetH) / double(inH);
    double scale = std::min({scaleW, scaleH, 1.0});
    uint32_t outW = std::max<uint32_t>(1, uint32_t(std::round(double(inW) * scale)));
    uint32_t outH = std::max<uint32_t>(1, uint32_t(std::round(double(inH) * scale)));

    LOGI("runStageBDownsample: src=%ux%u target=%ux%u → out=%ux%u (scale=%.3f)",
         inW, inH, targetW, targetH, outW, outH, scale);

    // Check for cancellation before the expensive lock + alloc.
    CHECK_CANCEL_EARLY();

    // ── Lock the AHardwareBuffer for CPU write ───────────────────────────────
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
        r.error = "AHB format must be R16G16B16A16_FLOAT";
        closeStageATiff(reader);
        return r;
    }
    if (desc.width < outW || desc.height < outH) {
        r.error = "AHB too small for target dims";
        closeStageATiff(reader);
        return r;
    }

    void* dstPtr = nullptr;
    // CLAHE and NR read back the FP16 they wrote, so request READ too when
    // either is enabled. The downsample-only path keeps WRITE-only.
    const bool needsReadback = claheEnabled || luminanceNR > 0.f || colorNR > 0.f || blueNR > 0.f || redNR > 0.f || detail.any();
    const uint64_t lockUsage = needsReadback
        ? (AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY)
        : AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
    int lockRet = AHardwareBuffer_lock(
        ahb,
        lockUsage,
        -1 /* fence */, nullptr /* rect */,
        &dstPtr);
    if (lockRet != 0 || !dstPtr) {
        r.error = "AHardwareBuffer_lock failed";
        closeStageATiff(reader);
        return r;
    }

    // AHB rows may be padded — `desc.stride` is in *pixels*, not bytes.
    const uint32_t dstStridePx = desc.stride;
    uint16_t* dstBase = reinterpret_cast<uint16_t*>(dstPtr);

    // ── Build kernel tables ─────────────────────────────────────────────────
    KernelTable kx = buildKernel(int(inW), int(outW));
    KernelTable ky = buildKernel(int(inH), int(outH));

    // ── Pass 1: horizontal → intermediate FloatArray (outW × inH × 4) ───────
    // Read source row-by-row from the strip-based TIFF.
    // 24 MP source × 4 ch × 4 B = ~384 MB — too large. Stream in row bands.
    //
    // Approach: do everything column-by-column for the vertical pass too.
    // Simpler: allocate intermediate as outW × inH × 4 float (only outW gets
    // smaller, inH stays). At outW=1224 inH=3669 that's 1224 × 3669 × 16 = ~72 MB
    // — acceptable transient native heap. At larger targets we'd need banding.
    //
    // For M3 we ship the straightforward path; banded version is a follow-up
    // if the user's target ever exceeds ~16 MP (current cap is 8 MP).
    std::vector<float> mid(size_t(outW) * inH * 4);

    const uint32_t bytesPerRowSrc = inW * 4 * 2;
    for (uint32_t srcY = 0; srcY < inH; ++srcY) {
        if ((srcY & 0x1F) == 0) { CHECK_CANCEL(); }  // check every 32 rows
        // Find the strip + row offset for srcY.
        uint32_t stripIdx = srcY / h.rowsPerStrip;
        uint32_t rowInStrip = srcY - stripIdx * h.rowsPerStrip;
        const uint16_t* stripStart = getStageATiffStrip(reader, stripIdx);
        const uint16_t* srcRow = stripStart + size_t(rowInStrip) * inW * 4;

        for (uint32_t o = 0; o < outW; ++o) {
            int start = kx.start[o];
            int count = kx.count[o];
            const float* w = &kx.weights[kx.weightOffset[o]];
            float r0 = 0.f, g0 = 0.f, b0 = 0.f, a0 = 0.f;
            for (int t = 0; t < count; ++t) {
                const uint16_t* p = srcRow + (start + t) * 4;
                float wt = w[t];
                r0 += wt * halfToFloat(p[0]);
                g0 += wt * halfToFloat(p[1]);
                b0 += wt * halfToFloat(p[2]);
                a0 += wt * halfToFloat(p[3]);
            }
            float* m = &mid[size_t(srcY) * outW * 4 + size_t(o) * 4];
            m[0] = r0; m[1] = g0; m[2] = b0; m[3] = a0;
        }
    }

    auto tH = std::chrono::steady_clock::now();
    LOGI("runStageBDownsample: pass 1 (H) %lld ms",
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tH - t0).count());

    // ── Pass 2: vertical → AHardwareBuffer (RGBA_F16) ───────────────────────
    for (uint32_t dstY = 0; dstY < outH; ++dstY) {
        if ((dstY & 0x1F) == 0) { CHECK_CANCEL(); }  // check every 32 rows
        int start = ky.start[dstY];
        int count = ky.count[dstY];
        const float* w = &ky.weights[ky.weightOffset[dstY]];
        uint16_t* dstRow = dstBase + size_t(dstY) * dstStridePx * 4;

        for (uint32_t x = 0; x < outW; ++x) {
            float r0 = 0.f, g0 = 0.f, b0 = 0.f, a0 = 0.f;
            for (int t = 0; t < count; ++t) {
                const float* m = &mid[size_t(start + t) * outW * 4 + size_t(x) * 4];
                float wt = w[t];
                r0 += wt * m[0];
                g0 += wt * m[1];
                b0 += wt * m[2];
                a0 += wt * m[3];
            }
            // Clamp alpha to [0,1] but keep RGB free (linear extended range).
            if (a0 < 0.f) a0 = 0.f; else if (a0 > 1.f) a0 = 1.f;
            uint16_t* p = dstRow + size_t(x) * 4;
            p[0] = floatToHalf(r0);
            p[1] = floatToHalf(g0);
            p[2] = floatToHalf(b0);
            p[3] = floatToHalf(a0);
        }
    }

    auto tV = std::chrono::steady_clock::now();
    LOGI("runStageBDownsample: pass 2 (V) %lld ms; total %lld ms",
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tV - tH).count(),
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tV - t0).count());

    CHECK_CANCEL();

    CHECK_CANCEL();

    // ── Spatial pre-pass: CLAHE → NR → Detail, in-place on the AHB ───────
    {
        auto tS0 = std::chrono::steady_clock::now();
        applyStageBSpatialInPlace(dstBase, int(outW), int(outH), int(dstStridePx),
                                  claheEnabled, claheShadowsBoost, claheHighlightsBoost,
                                  jpegRefineStrength, jpegRefineClean, jpegRefineDetail,
                                  luminanceNR, colorNR, blueNR, redNR,
                                  detail, subjectMask, subjectMaskSize, subjectMaskH);
        LOGI("runStageBDownsample: spatial pre-pass %lld ms (clahe=%d nr=%.2f detail=%d)",
             (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
                 std::chrono::steady_clock::now() - tS0).count(),
             claheEnabled ? 1 : 0, luminanceNR, detail.any() ? 1 : 0);
    }

    CHECK_CANCEL();

    AHardwareBuffer_unlock(ahb, nullptr);
    closeStageATiff(reader);

    r.success   = true;
    r.outWidth  = outW;
    r.outHeight = outH;
    return r;
}

// Option A fast path: copy the PRISTINE downsampled FP16 from [srcAhb] into
// [dstAhb], then apply ONLY the spatial pre-pass (CLAHE/NR/Detail) in-place.
// Skips the ~1.4 s disk decode + Lanczos downsample entirely when only spatial
// sliders changed — passes 1+2 already produced srcAhb once per photo/res.
StageBResult runStageBApplySpatialToAhb(
    AHardwareBuffer* srcAhb,
    AHardwareBuffer* dstAhb,
    bool  claheEnabled,
    float claheShadowsBoost,
    float claheHighlightsBoost,
    float luminanceNR,
    float colorNR,
    float blueNR,
    float redNR,
    const DetailParams& detail,
    const float* subjectMask,
    int subjectMaskSize,
    int subjectMaskH,
    float jpegRefineStrength,
    float jpegRefineClean,
    float jpegRefineDetail) {
    StageBResult r;
    if (!srcAhb || !dstAhb) { r.error = "null AHB"; return r; }

    AHardwareBuffer_Desc sd{}, dd{};
    AHardwareBuffer_describe(srcAhb, &sd);
    AHardwareBuffer_describe(dstAhb, &dd);
    if (sd.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT ||
        dd.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
        r.error = "AHB format must be R16G16B16A16_FLOAT"; return r;
    }
    const uint32_t outW = std::min(sd.width,  dd.width);
    const uint32_t outH = std::min(sd.height, dd.height);
    if (outW == 0 || outH == 0) { r.error = "zero dims"; return r; }

    void* srcPtr = nullptr;
    if (AHardwareBuffer_lock(srcAhb, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
                             -1, nullptr, &srcPtr) != 0 || !srcPtr) {
        r.error = "lock srcAhb failed"; return r;
    }
    void* dstPtr = nullptr;
    if (AHardwareBuffer_lock(dstAhb,
            AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
            -1, nullptr, &dstPtr) != 0 || !dstPtr) {
        AHardwareBuffer_unlock(srcAhb, nullptr);
        r.error = "lock dstAhb failed"; return r;
    }

    const auto* srcBase = reinterpret_cast<const uint16_t*>(srcPtr);
    auto* dstBase = reinterpret_cast<uint16_t*>(dstPtr);
    const uint32_t rowU16 = outW * 4;
    for (uint32_t y = 0; y < outH; ++y) {
        const uint16_t* sr = srcBase + size_t(y) * sd.stride * 4;
        uint16_t* dr = dstBase + size_t(y) * dd.stride * 4;
        for (uint32_t i = 0; i < rowU16; ++i) dr[i] = sr[i];
    }
    AHardwareBuffer_unlock(srcAhb, nullptr);

    applyStageBSpatialInPlace(dstBase, int(outW), int(outH), int(dd.stride),
                              claheEnabled, claheShadowsBoost, claheHighlightsBoost,
                              jpegRefineStrength, jpegRefineClean, jpegRefineDetail,
                              luminanceNR, colorNR, blueNR, redNR,
                              detail, subjectMask, subjectMaskSize, subjectMaskH);

    // DIAG (cyan-preview investigation): mean RGB of the filled buffer. If this
    // is already skewed cyan (R low, G/B high) the fill/spatial pass is at fault;
    // if it is balanced but the GL preview still shows cyan, the bug is in the
    // GL shader/sampling of this AHB. Sampled sparsely — negligible cost.
    {
        double sr = 0, sg = 0, sb = 0; long n = 0;
        float gMin = 1e9f, gMax = -1e9f;
        for (uint32_t y = 0; y < outH; y += 8) {
            const __fp16* row = reinterpret_cast<const __fp16*>(dstBase + size_t(y) * dd.stride * 4);
            for (uint32_t x = 0; x < outW; x += 8) {
                float R = (float)row[x * 4 + 0], G = (float)row[x * 4 + 1], B = (float)row[x * 4 + 2];
                sr += R; sg += G; sb += B; ++n;
                if (G < gMin) gMin = G; if (G > gMax) gMax = G;
            }
        }
        if (n > 0)
            LOGI("AHB-DIAG mean RGB=(%.4f,%.4f,%.4f) Grange=[%.4f,%.4f] n=%ld %ux%u",
                 sr / n, sg / n, sb / n, gMin, gMax, n, outW, outH);
    }

    AHardwareBuffer_unlock(dstAhb, nullptr);
    r.success = true; r.outWidth = outW; r.outHeight = outH;
    return r;
}

}  // namespace raw_v3
