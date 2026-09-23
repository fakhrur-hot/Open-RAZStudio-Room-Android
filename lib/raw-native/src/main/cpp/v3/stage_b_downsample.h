/*
 * StudioRoom — RAW Pipeline v3 — Stage B downsampler.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Read the mmap'd Stage A BigTIFF, Lanczos-3 downsample to a target size
 * ≤ 8 MP, and write the RGBA_F16 result directly into an AHardwareBuffer
 * (zero JVM heap allocation). See Plan.md §4.1 / §5 / §8.
 */

#pragma once

#include <android/hardware_buffer.h>
#include <cstdint>
#include <string>

#include "raw_v3_detail.h"

namespace raw_v3 {

struct StageBResult {
    bool     success = false;
    bool     cancelled = false;
    uint32_t outWidth = 0;
    uint32_t outHeight = 0;
    std::string error;
};

/**
 * Downsample the Stage A TIFF at [stageATifPath] into [ahb], targeting
 * [targetW] × [targetH]. The actual output dimensions are computed inside
 * (preserving the source aspect ratio, never larger than [targetW]/[targetH]
 * and never larger than the source). The AHardwareBuffer must have been
 * allocated by the caller with:
 *   format = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT
 *   usage  = CPU_WRITE_RARELY | GPU_SAMPLED_IMAGE
 *   width  ≥ targetW, height ≥ targetH
 *
 * Synchronous; expects to be called on a background dispatcher.
 *
 * When [claheEnabled] is true, a tile-based CLAHE pass (sigmoid-weighted by
 * [claheShadowsBoost] / [claheHighlightsBoost], both [0..1]) runs in place on
 * the downsampled FP16 result before the AHB is unlocked. See raw_v3_clahe.h.
 * Defaults keep the call site unchanged for callers that don't pass params.
 */
StageBResult runStageBDownsample(
    const std::string& stageATifPath,
    AHardwareBuffer* ahb,
    uint32_t targetW,
    uint32_t targetH,
    bool  claheEnabled = false,
    float claheShadowsBoost = 0.0f,
    float claheHighlightsBoost = 0.0f,
    float luminanceNR = 0.0f,
    float colorNR = 0.0f,
    float blueNR = 0.0f,
    float redNR = 0.0f,
    const DetailParams& detail = DetailParams{},
    const float* subjectMask = nullptr,   // [maskW×maskH] in [0,1], or null
    int subjectMaskSize = 0,              // mask width
    int subjectMaskH = 0,                 // mask height; 0 = square (== width)
    float jpegRefineStrength = 0.0f,
    float jpegRefineClean = 0.5f,
    float jpegRefineDetail = 0.5f,
    // Cancellation token. When non-null, the bake checks *cancelToken
    // at the start of each row (pass 1 and pass 2) and before each
    // post-pass (CLAHE/NR/Detail). Returns cancelled=true immediately
    // when set. Safe to flip from another thread.
    const volatile int8_t* cancelToken = nullptr);

/**
 * Option A fast path: copy the PRISTINE (downsample-only) FP16 from [srcAhb]
 * into [dstAhb], then apply ONLY the spatial pre-pass (CLAHE/NR/Detail) in
 * place. Used when only spatial sliders change — skips the disk decode +
 * Lanczos downsample, since [srcAhb] was baked once by [runStageBDownsample]
 * (with spatial params off). Both AHBs must be R16G16B16A16_FLOAT and the same
 * size; the smaller of the two dims is used.
 */
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
    int subjectMaskH = 0,
    float jpegRefineStrength = 0.0f,
    float jpegRefineClean = 0.5f,
    float jpegRefineDetail = 0.5f);

}  // namespace raw_v3
