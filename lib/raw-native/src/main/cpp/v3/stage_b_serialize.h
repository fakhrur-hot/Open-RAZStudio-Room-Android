/*
 * StudioRoom — RAW Pipeline v3 — Stage B serializer (M7).
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Snapshots the current AHardwareBuffer (RGBA_F16, GPU-resident) to disk at
 * Apply-time so the editor canvas can be torn down (and its 64 MB VRAM
 * texture freed) before the user navigates to the RAW Export screen.
 *
 * File format (no third-party deps):
 *
 *   Offset  Size  Field
 *   ──────  ────  ──────────────────────────────────────────────────────
 *   0       4     'B' 'F' '1' '6'  (magic)
 *   4       4     uint32 version  (= 1)
 *   8       4     uint32 width
 *   12      4     uint32 height
 *   16      4     uint32 row-stride in pixels (matches AHB)
 *   20      4     uint32 gamut tag (0=sRGB, 1=DisplayP3, reserved otherwise)
 *   24      ...   pixel data: height × stride × 4 × 2 bytes, row-major
 *
 *  Symmetric reader in `readStageBSerialized`. Both end up zero-copy on the
 *  reader side via mmap.
 */

#pragma once

#include <android/hardware_buffer.h>
#include <cstdint>
#include <string>

namespace raw_v3 {

struct StageBSerializedHeader {
    uint32_t magic;        // 'B','F','1','6' → 0x36314642 (LE)
    uint32_t version;
    uint32_t width;
    uint32_t height;
    uint32_t strideInPixels;
    uint32_t gamutTag;
};

constexpr uint32_t STAGE_B_MAGIC   = 0x36314642u;
constexpr uint32_t STAGE_B_VERSION = 1u;

/**
 * Lock [ahb] for CPU read, copy its pixel bytes into [outPath] alongside a
 * header. Atomic write via temp-file + rename.
 */
bool writeStageBSerialized(AHardwareBuffer* ahb, const std::string& outPath);

struct StageBSerializedReader;

/** mmap the file, validate header. Returns nullptr on failure. */
StageBSerializedReader* openStageBSerialized(const std::string& path);

void closeStageBSerialized(StageBSerializedReader* r);

const StageBSerializedHeader& getStageBSerializedHeader(const StageBSerializedReader* r);

/** Pointer to the first RGBA_F16 pixel (row-major, stride matches header). */
const uint16_t* getStageBSerializedPixels(const StageBSerializedReader* r);

/** Convenience: read width/height from a file without keeping it mmap'd. */
bool getStageBSerializedDims(const std::string& path, uint32_t& outW, uint32_t& outH);

/**
 * Render a serialized Stage B snapshot into a locked ARGB_8888 Bitmap.
 * [outPixels] is the locked buffer, [outStride] is the byte stride per row.
 * Returns false on read/conversion failure.
 */
bool renderStageBSerializedToBitmap(
    const std::string& path,
    uint8_t* outPixels, uint32_t outStride);

}  // namespace raw_v3
