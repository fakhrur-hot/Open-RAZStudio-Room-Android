/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 */

#include "tiff_mmap_io.h"

#include <android/log.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG "RawV3.Tiff"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

namespace {

// Readers (openStageATiff → close) hold shared; atomic rewriters hold exclusive.
// Prevents fopen("wb") truncate under a live MAP_SHARED → SIGBUS BUS_ADRERR.
static std::shared_mutex gStageAFileMutex;

// ── BigTIFF constants ────────────────────────────────────────────────────────
constexpr uint16_t BYTE_ORDER_LITTLE   = 0x4949; // "II"
constexpr uint16_t BIGTIFF_MAGIC       = 0x002B;
constexpr uint16_t BIGTIFF_OFFSET_SIZE = 8;

// ── TIFF tag IDs (we use the minimum set the format needs) ───────────────────
constexpr uint16_t TAG_IMAGE_WIDTH       = 256;
constexpr uint16_t TAG_IMAGE_LENGTH      = 257;
constexpr uint16_t TAG_BITS_PER_SAMPLE   = 258;
constexpr uint16_t TAG_COMPRESSION       = 259;
constexpr uint16_t TAG_PHOTOMETRIC       = 262;
constexpr uint16_t TAG_STRIP_OFFSETS     = 273;
constexpr uint16_t TAG_SAMPLES_PER_PIXEL = 277;
constexpr uint16_t TAG_ROWS_PER_STRIP    = 278;
constexpr uint16_t TAG_STRIP_BYTE_COUNTS = 279;
constexpr uint16_t TAG_PLANAR_CONFIG     = 284;
constexpr uint16_t TAG_SAMPLE_FORMAT     = 339;

// ── TIFF field types ─────────────────────────────────────────────────────────
constexpr uint16_t TYPE_SHORT  = 3;   // uint16
constexpr uint16_t TYPE_LONG   = 4;   // uint32
constexpr uint16_t TYPE_LONG8  = 16;  // uint64 (BigTIFF)

constexpr uint16_t PHOTOMETRIC_RGB         = 2;
constexpr uint16_t COMPRESSION_NONE        = 1;
constexpr uint16_t PLANAR_CHUNKY           = 1;
constexpr uint16_t SAMPLE_FORMAT_IEEE_FP   = 3;

constexpr uint32_t SAMPLES_PER_PIXEL = 4;     // RGBA
constexpr uint32_t BITS_PER_SAMPLE   = 16;    // each channel = IEEE 754 binary16

// One IFD entry is 20 bytes in BigTIFF (tag:2, type:2, count:8, value/offset:8).
constexpr size_t IFD_ENTRY_SIZE = 20;

// Helpers — write little-endian primitives at a stream cursor.
inline void put16(uint8_t* p, uint16_t v) {
    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF;
}
inline void put32(uint8_t* p, uint32_t v) {
    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF;
    p[2] = (v >> 16) & 0xFF; p[3] = (v >> 24) & 0xFF;
}
inline void put64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = (v >> (i * 8)) & 0xFF;
}

// Write a single IFD entry. For values ≤ 8 bytes the value fits inline
// (TIFF stores it in the value/offset slot); larger values get written
// elsewhere in the file and the slot holds the offset.
//
//  Tag layout (20 bytes):
//    [0..1]  tag id
//    [2..3]  field type
//    [4..11] count (number of values of that type)
//    [12..19] value (or file offset to value array)
void writeEntry(uint8_t* dst,
                uint16_t tag, uint16_t type, uint64_t count, uint64_t valueOrOffset) {
    put16(dst,     tag);
    put16(dst + 2, type);
    put64(dst + 4, count);
    put64(dst + 12, valueOrOffset);
}

}  // anonymous namespace

bool writeStageATiff(const std::string& outPath,
                     const uint16_t* pixelsRgbaF16,
                     uint32_t width,
                     uint32_t height) {
    if (!pixelsRgbaF16 || width == 0 || height == 0) {
        LOGE("writeStageATiff: invalid args");
        return false;
    }

    // ── Plan the layout ──────────────────────────────────────────────────────
    // Strip layout: each strip holds [rowsPerStrip] rows of [width * 8] bytes.
    const uint32_t rowsPerStrip = TIFF_ROWS_PER_STRIP;
    const uint32_t stripCount   = (height + rowsPerStrip - 1) / rowsPerStrip;
    const uint64_t bytesPerRow  = (uint64_t)width * SAMPLES_PER_PIXEL * 2; // 4 channels × 16 bits

    // 11 TIFF tags total. BitsPerSample and SampleFormat each have 4 values
    // (one per channel), but each value is only 2 bytes (SHORT), so 4 × 2 = 8
    // bytes still fits inline in the entry's value field.
    constexpr uint64_t TAG_COUNT = 11;

    // Header (16 bytes) + IFD (8-byte tag count + 11 × 20-byte entries + 8-byte
    // next-IFD offset = 8 + 220 + 8 = 236 bytes).
    constexpr uint64_t HEADER_BYTES = 16;
    constexpr uint64_t IFD_BYTES    = 8 + TAG_COUNT * IFD_ENTRY_SIZE + 8;
    constexpr uint64_t IFD_OFFSET   = HEADER_BYTES;                 // = 16
    constexpr uint64_t META_BYTES   = HEADER_BYTES + IFD_BYTES;     // = 252

    // Out-of-line arrays:
    //   • StripOffsets:     stripCount × 8 bytes (LONG8)
    //   • StripByteCounts:  stripCount × 8 bytes (LONG8)
    const uint64_t stripOffsetsArrayPos = META_BYTES;
    const uint64_t stripCountsArrayPos  = stripOffsetsArrayPos + stripCount * 8;
    const uint64_t pixelDataOffset      = stripCountsArrayPos    + stripCount * 8;

    // ── Open output file ─────────────────────────────────────────────────────
    FILE* fp = fopen(outPath.c_str(), "wb");
    if (!fp) {
        LOGE("writeStageATiff: fopen(%s) failed", outPath.c_str());
        return false;
    }

    // ── Header ───────────────────────────────────────────────────────────────
    uint8_t header[HEADER_BYTES];
    put16(header,      BYTE_ORDER_LITTLE);
    put16(header + 2,  BIGTIFF_MAGIC);
    put16(header + 4,  BIGTIFF_OFFSET_SIZE);
    put16(header + 6,  0);                 // constant 0
    put64(header + 8,  IFD_OFFSET);        // first IFD offset
    if (fwrite(header, 1, HEADER_BYTES, fp) != HEADER_BYTES) goto fail;

    // ── IFD ──────────────────────────────────────────────────────────────────
    {
        uint8_t ifd[IFD_BYTES];
        memset(ifd, 0, sizeof(ifd));
        put64(ifd, TAG_COUNT);
        uint8_t* e = ifd + 8;

        // ImageWidth (LONG)
        writeEntry(e, TAG_IMAGE_WIDTH, TYPE_LONG, 1, width);
        e += IFD_ENTRY_SIZE;

        // ImageLength (LONG)
        writeEntry(e, TAG_IMAGE_LENGTH, TYPE_LONG, 1, height);
        e += IFD_ENTRY_SIZE;

        // BitsPerSample (SHORT × 4 — fits inline: 8 bytes)
        {
            uint8_t inline8[8] = {};
            for (int i = 0; i < 4; ++i) put16(inline8 + i * 2, BITS_PER_SAMPLE);
            uint64_t packed; memcpy(&packed, inline8, 8);
            writeEntry(e, TAG_BITS_PER_SAMPLE, TYPE_SHORT, 4, packed);
        }
        e += IFD_ENTRY_SIZE;

        // Compression (SHORT)
        writeEntry(e, TAG_COMPRESSION, TYPE_SHORT, 1, COMPRESSION_NONE);
        e += IFD_ENTRY_SIZE;

        // PhotometricInterpretation (SHORT)
        writeEntry(e, TAG_PHOTOMETRIC, TYPE_SHORT, 1, PHOTOMETRIC_RGB);
        e += IFD_ENTRY_SIZE;

        // StripOffsets (LONG8 × stripCount). If stripCount == 1, the single
        // 8-byte value fits inline; otherwise points to the offsets array.
        if (stripCount == 1) {
            writeEntry(e, TAG_STRIP_OFFSETS, TYPE_LONG8, 1, pixelDataOffset);
        } else {
            writeEntry(e, TAG_STRIP_OFFSETS, TYPE_LONG8, stripCount, stripOffsetsArrayPos);
        }
        e += IFD_ENTRY_SIZE;

        // SamplesPerPixel (SHORT)
        writeEntry(e, TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, 1, SAMPLES_PER_PIXEL);
        e += IFD_ENTRY_SIZE;

        // RowsPerStrip (LONG)
        writeEntry(e, TAG_ROWS_PER_STRIP, TYPE_LONG, 1, rowsPerStrip);
        e += IFD_ENTRY_SIZE;

        // StripByteCounts (LONG8 × stripCount)
        if (stripCount == 1) {
            writeEntry(e, TAG_STRIP_BYTE_COUNTS, TYPE_LONG8, 1, (uint64_t)height * bytesPerRow);
        } else {
            writeEntry(e, TAG_STRIP_BYTE_COUNTS, TYPE_LONG8, stripCount, stripCountsArrayPos);
        }
        e += IFD_ENTRY_SIZE;

        // PlanarConfiguration (SHORT)
        writeEntry(e, TAG_PLANAR_CONFIG, TYPE_SHORT, 1, PLANAR_CHUNKY);
        e += IFD_ENTRY_SIZE;

        // SampleFormat (SHORT × 4 — IEEE float for every channel)
        {
            uint8_t inline8[8] = {};
            for (int i = 0; i < 4; ++i) put16(inline8 + i * 2, SAMPLE_FORMAT_IEEE_FP);
            uint64_t packed; memcpy(&packed, inline8, 8);
            writeEntry(e, TAG_SAMPLE_FORMAT, TYPE_SHORT, 4, packed);
        }
        e += IFD_ENTRY_SIZE;

        // Next-IFD offset = 0 (last IFD).
        put64(e, 0);

        if (fwrite(ifd, 1, IFD_BYTES, fp) != IFD_BYTES) goto fail;
    }

    // ── Out-of-line strip arrays (only if stripCount > 1) ───────────────────
    if (stripCount > 1) {
        uint8_t buf[8];
        for (uint32_t i = 0; i < stripCount; ++i) {
            uint64_t off = pixelDataOffset + (uint64_t)i * rowsPerStrip * bytesPerRow;
            put64(buf, off);
            if (fwrite(buf, 1, 8, fp) != 8) goto fail;
        }
        for (uint32_t i = 0; i < stripCount; ++i) {
            uint32_t rowsInStrip = rowsPerStrip;
            if (i == stripCount - 1) {
                uint32_t remaining = height - i * rowsPerStrip;
                if (remaining < rowsPerStrip) rowsInStrip = remaining;
            }
            uint64_t bytes = (uint64_t)rowsInStrip * bytesPerRow;
            put64(buf, bytes);
            if (fwrite(buf, 1, 8, fp) != 8) goto fail;
        }
    }

    // ── Pixel data — stream contiguously ────────────────────────────────────
    {
        uint64_t totalBytes = (uint64_t)height * bytesPerRow;
        const uint8_t* src = reinterpret_cast<const uint8_t*>(pixelsRgbaF16);
        uint64_t written = 0;
        while (written < totalBytes) {
            uint64_t chunk = totalBytes - written;
            if (chunk > 1 << 20) chunk = 1 << 20;     // 1 MB writes
            size_t n = fwrite(src + written, 1, chunk, fp);
            if (n != chunk) goto fail;
            written += n;
        }
    }

    fclose(fp);
    LOGI("writeStageATiff: wrote %ux%u (%u strips × %u rows, %llu bytes pixel data) → %s",
         width, height, stripCount, rowsPerStrip,
         (unsigned long long)((uint64_t)height * bytesPerRow), outPath.c_str());
    return true;

fail:
    LOGE("writeStageATiff: write failed at %s", outPath.c_str());
    fclose(fp);
    unlink(outPath.c_str());
    return false;
}

bool writeStageATiffAtomic(
        const std::string& outPath,
        const uint16_t* pixelsRgbaF16,
        uint32_t width,
        uint32_t height) {
    // Exclusive: no mmap reader may hold this path while we rename/truncate.
    std::unique_lock<std::shared_mutex> lock(gStageAFileMutex);
    const std::string tmpPath = outPath + ".rewrite.tmp";
    if (!writeStageATiff(tmpPath, pixelsRgbaF16, width, height)) {
        unlink(tmpPath.c_str());
        return false;
    }
    if (rename(tmpPath.c_str(), outPath.c_str()) == 0) {
        return true;
    }
    // rename can fail across filesystems; in-place under exclusive is safe
    // because every openStageATiff holds shared and is blocked here.
    LOGE("writeStageATiffAtomic: rename failed errno=%d — in-place fallback %s",
         errno, outPath.c_str());
    const bool ok = writeStageATiff(outPath, pixelsRgbaF16, width, height);
    unlink(tmpPath.c_str());
    return ok;
}

// ── Reader ──────────────────────────────────────────────────────────────────

struct StageATiffReader {
    int fd = -1;
    size_t fileSize = 0;
    void* mapped = MAP_FAILED;
    StageATiffHeader header;
    const uint16_t* pixelBase = nullptr;
    // Held for open→close so writers cannot truncate under this mmap.
    std::unique_ptr<std::shared_lock<std::shared_mutex>> sharedLock;
};

static StageATiffReader* openStageATiffUnlocked(const std::string& path) {
    StageATiffReader* r = new StageATiffReader();
    r->fd = ::open(path.c_str(), O_RDONLY);
    if (r->fd < 0) {
        LOGE("openStageATiff: open(%s) failed", path.c_str());
        delete r; return nullptr;
    }
    struct stat st {};
    if (fstat(r->fd, &st) != 0 || st.st_size < 16) {
        LOGE("openStageATiff: fstat failed or too small");
        ::close(r->fd); delete r; return nullptr;
    }
    r->fileSize = st.st_size;
    r->mapped = mmap(nullptr, r->fileSize, PROT_READ, MAP_SHARED, r->fd, 0);
    if (r->mapped == MAP_FAILED) {
        LOGE("openStageATiff: mmap failed");
        ::close(r->fd); delete r; return nullptr;
    }

    // Parse header. We know the exact layout: header is at offset 0,
    // first IFD at offset 16, dims at fixed positions within the IFD.
    const uint8_t* p = reinterpret_cast<const uint8_t*>(r->mapped);
    if (p[0] != 0x49 || p[1] != 0x49 || p[2] != 0x2B || p[3] != 0x00) {
        LOGE("openStageATiff: not a Stage A BigTIFF (magic mismatch)");
        munmap(r->mapped, r->fileSize); ::close(r->fd); delete r; return nullptr;
    }

    // IFD layout: 8-byte count + 11 entries, with entry order matching
    // writeStageATiff above.
    const uint8_t* ifd = p + 16 + 8;            // skip 8-byte tag count
    auto readU32At = [&](size_t entryIdx) -> uint32_t {
        const uint8_t* e = ifd + entryIdx * IFD_ENTRY_SIZE;
        // value/offset starts at byte 12 of the entry. For LONG (4 bytes)
        // the value is in the low 32 bits of that 8-byte slot.
        return uint32_t(e[12]) | (uint32_t(e[13]) << 8) |
               (uint32_t(e[14]) << 16) | (uint32_t(e[15]) << 24);
    };
    auto readU64At = [&](size_t entryIdx) -> uint64_t {
        const uint8_t* e = ifd + entryIdx * IFD_ENTRY_SIZE + 12;
        uint64_t v = 0;
        for (int i = 0; i < 8; ++i) v |= uint64_t(e[i]) << (i * 8);
        return v;
    };

    r->header.width        = readU32At(0);   // ImageWidth
    r->header.height       = readU32At(1);   // ImageLength
    r->header.rowsPerStrip = readU32At(7);   // RowsPerStrip
    r->header.stripCount   = (r->header.height + r->header.rowsPerStrip - 1) / r->header.rowsPerStrip;

    uint64_t firstStripOffset;
    if (r->header.stripCount == 1) {
        firstStripOffset = readU64At(5);     // inline value
    } else {
        uint64_t offsetArrayPos = readU64At(5);  // offset to LONG8 array
        const uint8_t* offsetArr = p + offsetArrayPos;
        uint64_t v = 0;
        for (int i = 0; i < 8; ++i) v |= uint64_t(offsetArr[i]) << (i * 8);
        firstStripOffset = v;
    }
    r->header.pixelDataOffset = firstStripOffset;
    r->header.pixelDataBytes  = (uint64_t)r->header.width * r->header.height * 8;
    r->pixelBase = reinterpret_cast<const uint16_t*>(p + firstStripOffset);
    return r;
}

StageATiffReader* openStageATiff(const std::string& path) {
    auto lock = std::make_unique<std::shared_lock<std::shared_mutex>>(gStageAFileMutex);
    StageATiffReader* r = openStageATiffUnlocked(path);
    if (!r) return nullptr;
    r->sharedLock = std::move(lock);
    return r;
}

void closeStageATiff(StageATiffReader* r) {
    if (!r) return;
    if (r->mapped != MAP_FAILED) munmap(r->mapped, r->fileSize);
    if (r->fd >= 0) ::close(r->fd);
    r->sharedLock.reset();  // release shared before delete
    delete r;
}

const StageATiffHeader& getStageATiffHeader(const StageATiffReader* r) {
    return r->header;
}

const uint16_t* getStageATiffStrip(const StageATiffReader* r, uint32_t stripIndex) {
    uint64_t bytesPerRow = (uint64_t)r->header.width * 4 * 2;
    uint64_t byteOffset  = (uint64_t)stripIndex * r->header.rowsPerStrip * bytesPerRow;
    return reinterpret_cast<const uint16_t*>(
        reinterpret_cast<const uint8_t*>(r->pixelBase) + byteOffset);
}

size_t getStageATiffFileSize(const StageATiffReader* r) {
    return r ? r->fileSize : 0;
}

bool stageATiffPayloadInBounds(const StageATiffReader* r) {
    if (!r || r->mapped == MAP_FAILED) return false;
    if (r->header.width == 0 || r->header.height == 0) return false;
    const uint64_t end = r->header.pixelDataOffset + r->header.pixelDataBytes;
    return end <= uint64_t(r->fileSize);
}

}  // namespace raw_v3
