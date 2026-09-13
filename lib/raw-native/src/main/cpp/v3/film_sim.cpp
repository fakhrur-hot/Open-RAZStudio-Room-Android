/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Film simulation engine implementation.
 * Tasks 4 (LibRaw native injection) + 5 (pixel transform) + 6 (hybrid router).
 */

#include "film_sim.h"
#include "tiff_mmap_io.h"

#include <android/log.h>
#include <cinttypes>
#include <cstring>
#include <cstdio>
#include <memory>
#include <vector>

// LibRaw is included here (not in the header) so film_sim.h doesn't drag it in.
#include "../libraw/libraw.h"

#define LOG_TAG "RawV3.FilmSim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

namespace {

// ── FP16 ↔ FP32 helpers ──────────────────────────────────────────────────────

inline float halfToFloat(uint16_t h) {
    uint32_t sign = (h >> 15) & 1u;
    uint32_t exp  = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x3FFu;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) { bits = sign << 31; }
        else {
            // Subnormal → normalised
            exp = 1;
            while (!(mant & 0x400)) { mant <<= 1; --exp; }
            mant &= ~0x400u;
            bits = (sign << 31) | ((exp + 112) << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        bits = (sign << 31) | 0x7F800000u | (mant << 13);  // inf/nan
    } else {
        bits = (sign << 31) | ((exp + 112) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}

inline uint16_t floatToHalf(float f) {
    uint32_t bits;
    std::memcpy(&bits, &f, 4);
    uint32_t sign = (bits >> 16) & 0x8000u;
    int32_t  exp  = int32_t((bits >> 23) & 0xFFu) - 127 + 15;
    uint32_t mant = bits & 0x7FFFFFu;
    if (exp >= 0x1F) return uint16_t(sign | 0x7C00u);
    if (exp <= 0)    return uint16_t(sign);
    uint32_t m = (mant + 0x1000u) >> 13;
    if (m & 0x400u) { m = 0; ++exp; if (exp >= 0x1F) return uint16_t(sign | 0x7C00u); }
    return uint16_t(sign | (uint32_t(exp) << 10) | (m & 0x3FFu));
}

// Returns true if profileIndex belongs to the native LibRaw execution path.
inline bool isNativePath(int profile) {
    return profile == PROFILE_VELVIA   ||
           profile == PROFILE_PROVIA   ||
           profile == PROFILE_CLASSIC_CHROME ||
           profile == PROFILE_ASTIA    ||
           profile == PROFILE_ETERNA;
}

// ── Post-decode pixel pass ────────────────────────────────────────────────────
// Reads the TIFF from [inputTifPath], applies apply_profile_transforms() per
// pixel (including grain), writes to [outputTifPath] using writeStageATiff().
bool applyPostDecodePass(
    const std::string& inputTifPath,
    const std::string& outputTifPath,
    int profileIndex,
    float grainAmount)
{
    StageATiffReader* reader = openStageATiff(inputTifPath);
    if (!reader) {
        LOGE("applyPostDecodePass: cannot open %s", inputTifPath.c_str());
        return false;
    }

    const StageATiffHeader& hdr = getStageATiffHeader(reader);
    const uint32_t W = hdr.width;
    const uint32_t H = hdr.height;
    const uint64_t nPixels = uint64_t(W) * H;

    // Working buffer: one full-image copy in FP16 (RGBA, 4 uint16_t per pixel)
    std::vector<uint16_t> buf(nPixels * 4);

    // Copy pixel data from mmap'd strips into the working buffer
    for (uint32_t strip = 0; strip < hdr.stripCount; ++strip) {
        const uint16_t* src = getStageATiffStrip(reader, strip);
        if (!src) { closeStageATiff(reader); return false; }
        uint32_t rowStart = strip * TIFF_ROWS_PER_STRIP;
        uint32_t rowEnd   = std::min(rowStart + TIFF_ROWS_PER_STRIP, H);
        for (uint32_t row = rowStart; row < rowEnd; ++row) {
            const uint32_t srcOffset = (row - rowStart) * W * 4;
            const uint32_t dstOffset = row * W * 4;
            std::memcpy(buf.data() + dstOffset, src + srcOffset,
                        W * 4 * sizeof(uint16_t));
        }
    }
    closeStageATiff(reader);

    // Apply per-pixel transform (Task 5)
    for (uint32_t row = 0; row < H; ++row) {
        for (uint32_t col = 0; col < W; ++col) {
            uint32_t idx = (row * W + col) * 4;
            float rgb[3] = {
                halfToFloat(buf[idx + 0]),
                halfToFloat(buf[idx + 1]),
                halfToFloat(buf[idx + 2]),
            };
            const uint32_t seed = row * W + col;
            apply_profile_transforms(rgb, profileIndex, grainAmount, seed);
            buf[idx + 0] = floatToHalf(rgb[0]);
            buf[idx + 1] = floatToHalf(rgb[1]);
            buf[idx + 2] = floatToHalf(rgb[2]);
            // buf[idx + 3] = alpha, preserved unchanged
        }
    }

    LOGI("applyPostDecodePass: profile=%d grain=%.4f pixels=%u×%u=%" PRIu64 " — transform complete",
         profileIndex, grainAmount, W, H, nPixels);

    // Write result (caller deletes outputTifPath on failure)
    bool ok = writeStageATiff(outputTifPath, buf.data(), W, H);
    if (!ok) LOGE("applyPostDecodePass: writeStageATiff failed → %s", outputTifPath.c_str());
    else     LOGI("applyPostDecodePass: wrote %s OK", outputTifPath.c_str());
    return ok;
}

// For native-path profiles with grain: apply grain (+ Classic Chrome blue
// correction) on top of the already-matrix'd TIFF.
bool applyGrainPass(
    const std::string& inputTifPath,
    const std::string& outputTifPath,
    int profileIndex,
    float grainAmount)
{
    StageATiffReader* reader = openStageATiff(inputTifPath);
    if (!reader) return false;

    const StageATiffHeader& hdr = getStageATiffHeader(reader);
    const uint32_t W = hdr.width;
    const uint32_t H = hdr.height;
    const uint64_t nPixels = uint64_t(W) * H;

    std::vector<uint16_t> buf(nPixels * 4);

    for (uint32_t strip = 0; strip < hdr.stripCount; ++strip) {
        const uint16_t* src = getStageATiffStrip(reader, strip);
        if (!src) { closeStageATiff(reader); return false; }
        uint32_t rowStart = strip * TIFF_ROWS_PER_STRIP;
        uint32_t rowEnd   = std::min(rowStart + TIFF_ROWS_PER_STRIP, H);
        for (uint32_t row = rowStart; row < rowEnd; ++row) {
            std::memcpy(buf.data() + row * W * 4,
                        src + (row - rowStart) * W * 4,
                        W * 4 * sizeof(uint16_t));
        }
    }
    closeStageATiff(reader);

    for (uint32_t row = 0; row < H; ++row) {
        for (uint32_t col = 0; col < W; ++col) {
            uint32_t idx = (row * W + col) * 4;
            float nr = halfToFloat(buf[idx + 0]);
            float ng = halfToFloat(buf[idx + 1]);
            float nb = halfToFloat(buf[idx + 2]);

            // Classic Chrome: LibRaw used uniform 1.15; correct blue residual
            // pow(b, 1.12/1.15) = pow(b, 0.97391…)
            if (profileIndex == PROFILE_CLASSIC_CHROME) {
                nb = std::pow(std::clamp(nb, 0.f, 1.f), 0.9739f);
            }

            // Grain (Task 5.9)
            if (grainAmount > 0.f) {
                float luma = (0.2126f * nr) + (0.7152f * ng) + (0.0722f * nb);
                float mask = 4.f * luma * (1.f - luma);
                const uint32_t seed = row * W + col;
                float n = static_cast<float>(seed * 1664525u + 1013904223u) / 4294967296.f;
                float grain = (n - 0.5f) * 2.f * grainAmount * mask;
                nr = std::clamp(nr + grain, 0.f, 1.f);
                ng = std::clamp(ng + grain, 0.f, 1.f);
                nb = std::clamp(nb + grain, 0.f, 1.f);
            }

            buf[idx + 0] = floatToHalf(nr);
            buf[idx + 1] = floatToHalf(ng);
            buf[idx + 2] = floatToHalf(nb);
        }
    }

    return writeStageATiff(outputTifPath, buf.data(), W, H);
}

// Simple file copy (std::FILE-based) — used when the native path already
// produced the correct TIFF and no post-decode pass is needed.
bool copyFile(const std::string& src, const std::string& dst) {
    FILE* in  = std::fopen(src.c_str(), "rb");
    FILE* out = std::fopen(dst.c_str(), "wb");
    if (!in || !out) {
        if (in)  std::fclose(in);
        if (out) { std::fclose(out); std::remove(dst.c_str()); }
        return false;
    }
    char block[65536];
    size_t n;
    while ((n = std::fread(block, 1, sizeof(block), in)) > 0) {
        if (std::fwrite(block, 1, n, out) != n) {
            std::fclose(in); std::fclose(out); std::remove(dst.c_str());
            return false;
        }
    }
    std::fclose(in);
    std::fclose(out);
    return true;
}

}  // anonymous namespace

// ── Task 4: LibRaw native matrix injection ────────────────────────────────────
bool setup_native_libraw_profile(LibRaw& processor, int profile) {
    // Raw linear output — skip LibRaw's own sRGB/AdobeRGB conversion
    processor.imgdata.params.output_color = 0;

    float m[3][4] = {};  // 3×4; column 3 unused (always 0)

    switch (profile) {
        case PROFILE_VELVIA:           // Task 4.2
            m[0][0] =  1.15f; m[0][1] = -0.10f; m[0][2] = -0.05f;
            m[1][0] = -0.05f; m[1][1] =  1.20f; m[1][2] = -0.15f;
            m[2][0] = -0.02f; m[2][1] = -0.08f; m[2][2] =  1.10f;
            processor.imgdata.params.gamm[0] = 1.0f / 1.05f;
            processor.imgdata.params.gamm[1] = 4.5f;
            break;

        case PROFILE_PROVIA:           // Task 4.3
            m[0][0] =  1.02f; m[0][1] = -0.01f; m[0][2] = -0.01f;
            m[1][0] = -0.01f; m[1][1] =  1.02f; m[1][2] = -0.01f;
            m[2][0] = -0.01f; m[2][1] = -0.01f; m[2][2] =  1.04f;
            processor.imgdata.params.gamm[0] = 1.0f;
            processor.imgdata.params.gamm[1] = 1.0f;
            break;

        case PROFILE_CLASSIC_CHROME:   // Task 4.4
            m[0][0] =  1.05f; m[0][1] = -0.05f; m[0][2] =  0.00f;
            m[1][0] = -0.02f; m[1][1] =  0.94f;  m[1][2] =  0.08f;
            m[2][0] = -0.01f; m[2][1] = -0.06f;  m[2][2] =  1.07f;
            // Uniform 1.15; blue residual 1.12/1.15 corrected in grain pass
            processor.imgdata.params.gamm[0] = 1.0f / 1.15f;
            processor.imgdata.params.gamm[1] = 4.5f;
            break;

        case PROFILE_ASTIA:            // Task 4.5
            m[0][0] =  1.06f; m[0][1] = -0.04f; m[0][2] = -0.02f;
            m[1][0] = -0.04f; m[1][1] =  1.12f;  m[1][2] = -0.08f;
            m[2][0] = -0.01f; m[2][1] = -0.09f;  m[2][2] =  1.10f;
            processor.imgdata.params.gamm[0] = 1.0f / 1.02f;
            processor.imgdata.params.gamm[1] = 4.5f;
            break;

        case PROFILE_ETERNA:           // Task 4.6
            m[0][0] = 0.88f; m[0][1] = 0.06f; m[0][2] = 0.06f;
            m[1][0] = 0.05f; m[1][1] = 0.88f; m[1][2] = 0.07f;
            m[2][0] = 0.05f; m[2][1] = 0.05f; m[2][2] = 0.90f;
            processor.imgdata.params.gamm[0] = 1.0f / 0.92f;
            processor.imgdata.params.gamm[1] = 4.5f;
            break;

        case PROFILE_DEFAULT:
        case PROFILE_CLASSIC_NEG:  // asymmetric gamma — post-decode CPU path
        case PROFILE_ACROS:        // panchromatic collapse — post-decode CPU path
        default:
            return false;
    }

    // Task 4.7: copy matrix into LibRaw's internal rgb_cam
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 4; ++j)
            processor.imgdata.color.rgb_cam[i][j] = m[i][j];

    return true;
}

// ── Hybrid router ────────────────────────────────────────────────────────────
bool applyFilmSimCpu(
    const std::string& inputTifPath,
    const std::string& outputTifPath,
    int profileIndex,
    float grainAmount)
{
    // DEFAULT + no grain = identity; copy pristine TIFF to profile-keyed path.
    if (profileIndex == PROFILE_DEFAULT && grainAmount == 0.f) {
        return copyFile(inputTifPath, outputTifPath);
    }

    // All profiles run through the full pixel loop (sRGB↔linear round-trip +
    // matrix + tone curve + grain). The LibRaw native injection path was removed
    // because Stage A always writes sRGB-encoded pixels; matrix injection inside
    // dcraw_process would conflict with Stage A's own gamm[]/output_color setup.
    bool ok = applyPostDecodePass(inputTifPath, outputTifPath, profileIndex, grainAmount);
    if (!ok) std::remove(outputTifPath.c_str());
    return ok;
}

}  // namespace raw_v3
