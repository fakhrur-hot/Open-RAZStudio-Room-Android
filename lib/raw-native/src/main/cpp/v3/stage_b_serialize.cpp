/*
 * StudioRoom — RAW Pipeline v3 — Stage B serializer (M7).
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

#include "stage_b_serialize.h"

#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG "RawV3.StageBSer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

bool writeStageBSerialized(AHardwareBuffer* ahb, const std::string& outPath) {
    if (!ahb) {
        LOGE("writeStageBSerialized: null AHB");
        return false;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
        LOGE("writeStageBSerialized: AHB format must be RGBA_F16 (got 0x%x)", desc.format);
        return false;
    }

    void* src = nullptr;
    int lockRet = AHardwareBuffer_lock(
        ahb, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &src);
    if (lockRet != 0 || !src) {
        LOGE("writeStageBSerialized: AHardwareBuffer_lock failed (%d)", lockRet);
        return false;
    }

    const std::string tmpPath = outPath + ".tmp";
    FILE* fp = std::fopen(tmpPath.c_str(), "wb");
    if (!fp) {
        LOGE("writeStageBSerialized: fopen(%s) failed", tmpPath.c_str());
        AHardwareBuffer_unlock(ahb, nullptr);
        return false;
    }

    StageBSerializedHeader header{
        STAGE_B_MAGIC,
        STAGE_B_VERSION,
        desc.width,
        desc.height,
        desc.stride,
        0u,                 // gamut tag — sRGB for M7; M10 will refine
    };
    if (std::fwrite(&header, sizeof(header), 1, fp) != 1) {
        LOGE("writeStageBSerialized: header write failed");
        std::fclose(fp); std::remove(tmpPath.c_str());
        AHardwareBuffer_unlock(ahb, nullptr);
        return false;
    }

    // Pixel bytes: height × stride × 4 channels × 2 B per sample.
    const size_t bytesPerRow = size_t(desc.stride) * 4 * 2;
    const uint8_t* row = reinterpret_cast<const uint8_t*>(src);
    for (uint32_t y = 0; y < desc.height; ++y) {
        if (std::fwrite(row, 1, bytesPerRow, fp) != bytesPerRow) {
            LOGE("writeStageBSerialized: row %u write failed", y);
            std::fclose(fp); std::remove(tmpPath.c_str());
            AHardwareBuffer_unlock(ahb, nullptr);
            return false;
        }
        row += bytesPerRow;
    }
    std::fflush(fp);
    std::fclose(fp);
    AHardwareBuffer_unlock(ahb, nullptr);

    // Atomic rename.
    if (std::rename(tmpPath.c_str(), outPath.c_str()) != 0) {
        LOGE("writeStageBSerialized: rename(%s, %s) failed", tmpPath.c_str(), outPath.c_str());
        std::remove(tmpPath.c_str());
        return false;
    }
    const uint64_t total = sizeof(header) + uint64_t(desc.height) * bytesPerRow;
    LOGI("writeStageBSerialized: ok %ux%u stride=%u → %s (%llu bytes)",
         desc.width, desc.height, desc.stride, outPath.c_str(),
         (unsigned long long) total);
    return true;
}

struct StageBSerializedReader {
    int   fd = -1;
    size_t fileSize = 0;
    void*  mapped = MAP_FAILED;
    StageBSerializedHeader header{};
    const uint16_t* pixels = nullptr;
};

StageBSerializedReader* openStageBSerialized(const std::string& path) {
    int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        LOGE("openStageBSerialized: open(%s) failed", path.c_str());
        return nullptr;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < int64_t(sizeof(StageBSerializedHeader))) {
        LOGE("openStageBSerialized: fstat failed or too small");
        ::close(fd);
        return nullptr;
    }

    void* m = mmap(nullptr, st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (m == MAP_FAILED) {
        LOGE("openStageBSerialized: mmap failed");
        ::close(fd);
        return nullptr;
    }

    auto* r = new StageBSerializedReader();
    r->fd       = fd;
    r->fileSize = st.st_size;
    r->mapped   = m;
    std::memcpy(&r->header, m, sizeof(r->header));
    if (r->header.magic != STAGE_B_MAGIC || r->header.version != STAGE_B_VERSION) {
        LOGE("openStageBSerialized: bad magic/version (0x%x v=%u)",
             r->header.magic, r->header.version);
        munmap(m, st.st_size); ::close(fd); delete r;
        return nullptr;
    }
    r->pixels = reinterpret_cast<const uint16_t*>(
        reinterpret_cast<const uint8_t*>(m) + sizeof(StageBSerializedHeader));
    LOGI("openStageBSerialized: %ux%u stride=%u (%llu bytes mapped)",
         r->header.width, r->header.height, r->header.strideInPixels,
         (unsigned long long) r->fileSize);
    return r;
}

void closeStageBSerialized(StageBSerializedReader* r) {
    if (!r) return;
    if (r->mapped != MAP_FAILED) munmap(r->mapped, r->fileSize);
    if (r->fd >= 0) ::close(r->fd);
    delete r;
}

const StageBSerializedHeader& getStageBSerializedHeader(const StageBSerializedReader* r) {
    return r->header;
}

const uint16_t* getStageBSerializedPixels(const StageBSerializedReader* r) {
    return r->pixels;
}

// ── IEEE 754 binary16 → binary32 ───────────────────────────────────────────
inline float halfToFloat(uint16_t h) {
    uint32_t sign = (uint32_t(h) & 0x8000) << 16;
    uint32_t exp  = (h >> 10) & 0x1F;
    uint32_t mant = h & 0x3FF;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) bits = sign;
        else {
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
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}

bool getStageBSerializedDims(const std::string& path, uint32_t& outW, uint32_t& outH) {
    outW = outH = 0;
    int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    StageBSerializedHeader h{};
    bool ok = false;
    if (read(fd, &h, sizeof(h)) == sizeof(h) &&
        h.magic == STAGE_B_MAGIC && h.version == STAGE_B_VERSION) {
        outW = h.width;
        outH = h.height;
        ok = true;
    }
    ::close(fd);
    return ok;
}

bool renderStageBSerializedToBitmap(
        const std::string& path,
        uint8_t* outPixels, uint32_t outStride) {
    StageBSerializedReader* r = openStageBSerialized(path);
    if (!r) return false;
    const auto& h = getStageBSerializedHeader(r);
    const uint16_t* src = getStageBSerializedPixels(r);
    for (uint32_t y = 0; y < h.height; ++y) {
        uint8_t* dst = outPixels + size_t(y) * outStride;
        const uint16_t* row = src + size_t(y) * h.strideInPixels * 4;
        for (uint32_t x = 0; x < h.width; ++x) {
            float rf = halfToFloat(row[x * 4 + 0]);
            float gf = halfToFloat(row[x * 4 + 1]);
            float bf = halfToFloat(row[x * 4 + 2]);
            if (rf < 0.f) rf = 0.f; else if (rf > 1.f) rf = 1.f;
            if (gf < 0.f) gf = 0.f; else if (gf > 1.f) gf = 1.f;
            if (bf < 0.f) bf = 0.f; else if (bf > 1.f) bf = 1.f;
            dst[x * 4 + 0] = uint8_t(rf * 255.f + 0.5f);
            dst[x * 4 + 1] = uint8_t(gf * 255.f + 0.5f);
            dst[x * 4 + 2] = uint8_t(bf * 255.f + 0.5f);
            dst[x * 4 + 3] = 0xFF;
        }
    }
    closeStageBSerialized(r);
    return true;
}

}  // namespace raw_v3
