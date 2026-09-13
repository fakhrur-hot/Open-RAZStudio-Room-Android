/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Minimal BigTIFF writer + mmap-able reader for the Stage A scratch cache.
 *
 *  Format is fixed (see Plan.md §10.5):
 *    • BigTIFF magic `MM\0+` 0x002B (big-endian)
 *    • 8-byte offsets (BigTIFF), 8-byte counts.
 *    • One IFD only, located immediately after the 16-byte header.
 *    • Pixel data: RGBA_F16 (4 channels × half-float = 8 bytes/pixel),
 *      chunky planar, row-major within each 256-row strip.
 *    • No compression. ICC chunk omitted (Stage C handles tagging).
 *
 *  We control both the writer and the reader, so we hard-code field offsets
 *  and skip any generic TIFF parsing. The reader is just `mmap` + pointer
 *  arithmetic.
 * ─────────────────────────────────────────────────────────────────────────────
 */

#pragma once

#include <cstdint>
#include <string>

namespace raw_v3 {

constexpr uint32_t TIFF_ROWS_PER_STRIP = 256;

struct StageATiffHeader {
    uint32_t width;
    uint32_t height;
    uint32_t rowsPerStrip;      // always TIFF_ROWS_PER_STRIP
    uint32_t stripCount;        // ceil(height / rowsPerStrip)
    uint64_t pixelDataOffset;   // file offset of first strip
    uint64_t pixelDataBytes;    // total pixel bytes (width * height * 8)
};

/**
 * Write [pixelsRgbaF16] (width × height × 4 half-floats, little-endian) to
 * [outPath] in the Stage A BigTIFF format.
 *
 * Returns true on success. Overwrites if the file exists via fopen("wb")
 * truncate — safe only when no other thread holds an mmap of [outPath].
 * Prefer [writeStageATiffAtomic] when replacing an existing A.tif that may
 * still be mapped by a reader (preview / export / Stage B|C).
 */
bool writeStageATiff(
    const std::string& outPath,
    const uint16_t* pixelsRgbaF16,   // each value is a IEEE 754 binary16 bit pattern
    uint32_t width,
    uint32_t height);

/**
 * Exclusive rewrite of an existing Stage A path: write a sibling temp file,
 * then rename over [outPath]. Holds the Stage A file lock so concurrent
 * mmap readers (shared) cannot observe a truncated inode → SIGBUS.
 * Falls back to in-place writeStageATiff under the same exclusive lock if
 * rename fails.
 */
bool writeStageATiffAtomic(
    const std::string& outPath,
    const uint16_t* pixelsRgbaF16,
    uint32_t width,
    uint32_t height);

/**
 * mmap-backed reader. Open the file once, read the header, then sample strips
 * by index. The mapped pointer stays valid until [closeStageATiff].
 * Takes a shared Stage A file lock for the open→close lifetime so writers
 * using [writeStageATiffAtomic] cannot truncate under this mmap.
 *
 * Returns nullptr on failure.
 */
struct StageATiffReader;

StageATiffReader* openStageATiff(const std::string& path);
void              closeStageATiff(StageATiffReader* r);

const StageATiffHeader& getStageATiffHeader(const StageATiffReader* r);

/** Pointer to the first byte of the [stripIndex]-th 256-row strip. */
const uint16_t* getStageATiffStrip(const StageATiffReader* r, uint32_t stripIndex);

/** Byte length of the mmap'd Stage A file (0 if null). */
size_t getStageATiffFileSize(const StageATiffReader* r);

/**
 * True when the full pixel payload declared by the IFD fits inside the
 * mmap. Truncated / mid-write A.tif files return false — callers must not
 * walk strips (SIGBUS on past-EOF pages).
 */
bool stageATiffPayloadInBounds(const StageATiffReader* r);

}  // namespace raw_v3
