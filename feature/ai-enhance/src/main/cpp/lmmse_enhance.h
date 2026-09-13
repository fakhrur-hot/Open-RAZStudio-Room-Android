/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * LMMSE Denoise + optional Guided Filter + Mid-frequency USM Sharpen —
 * pure mathematical pipeline.
 *
 * Zero VRAM, zero model loading. Runs in deterministic time on CPU.
 * Parallelized via std::thread, NEON auto-vectorizable inner loops.
 *
 * Two overloads share one Params block; the repository layer scales
 * noiseVariance and usmThreshold to the correct domain before calling:
 *
 *   enhance(uint8_t* ...)  — 8-bit ARGB_8888 path (JPEG/PNG editor).
 *                             Params on 0–255 scale.
 *
 *   enhanceFp16(uint16_t* ...) — FP16 RGBA path (RAW Stage A, post-demosaic).
 *                             Params on 0.0–1.0 scale (divide 8-bit values
 *                             by 255 before passing).
 */

#pragma once

#include <cstdint>

namespace lmmse_enhance {

struct Params {
    // Stage 1: LMMSE
    int   windowSize;       // 3, 5, or 7 (odd only)
    float noiseVariance;    // σ_n² — scale matches the domain:
                            //   8-bit path : 1.0–100.0  (pixel² units, 0–255 range)
                            //   FP16 path  : divide by 255² ≈ 0.000015–0.00154

    // Stage 2: Thresholded USM
    float usmRadius;        // Gaussian kernel radius (0.5–3.0)
    float usmAmount;        // HF boost multiplier (0.0–3.0)
    float usmThreshold;     // Soft-ramp onset — same scale as domain:
                            //   8-bit path : 0.0–30.0
                            //   FP32 path  : divide by 255
    float usmEdgeThreshold; // Local σ cutoff for halo suppression — same scale:
                            //   8-bit path : 30.0–40.0  (σ in pixel units)
                            //   FP32 path  : 0.12–0.15  (σ in [0,1] domain)

    // Guided filter refinement (edge-aware smoothing) between denoise and USM.
    // Independent toggle so A/B tests with/without the filter are easy.
    bool useGuidedFilter;

    // Pipeline control
    bool skipDenoise;
    bool skipSharpen;
};

/**
 * 8-bit path — in-place on ARGB_8888 bitmap (JPEG/PNG editor).
 * Params.noiseVariance and usmThreshold on 0–255 pixel scale.
 */
void enhance(uint8_t* pixels, int width, int height, int stride, const Params& params);

/**
 * FP32 path — in-place on packed RGBA float buffer (RAW Stage A post-demosaic).
 *
 * Call this on the float staging buffer BEFORE packing to FP16, so no
 * scalar half-float conversion is needed inside the spatial loops.
 *
 * Buffer layout: width*height*4 floats, row-major, interleaved RGBA.
 * Values are sRGB-gamma-encoded in [0, 1] (matching the BigTIFF content).
 *
 * Params.noiseVariance and usmThreshold must be pre-scaled to [0,1] domain:
 *   noiseVariance  = 8-bit equivalent / (255 * 255)   [squared units]
 *   usmThreshold   = 8-bit equivalent / 255
 */
void enhanceFp32RGBA(float* rgba, int width, int height, const Params& params);

/**
 * Fusion tone-mapping — in-place on ARGB_8888 bitmap, modulated by a
 * sharp subject mask (U2Net→SAM fusion output) so shadow/highlight/
 * saturation adjustments apply crisply to the subject and only faintly
 * to the background, with no tonal bleeding across the boundary.
 *
 *   subjectMask : subject probability in [0,1], row-major maskW×maskH grid.
 *                 It is bilinear-sampled to the image resolution, so the mask
 *                 may be any size (e.g. a 320² U2Net grid). Pass nullptr (or
 *                 maskW/maskH ≤ 0) to apply the effect uniformly.
 *   shadowBoost   : lift (+) / crush (−) shadows,    range [-1, 1].
 *   highlightBoost: lift (+) / pull  (−) highlights, range [-1, 1].
 *   saturation    : chroma gain (+) / desat (−),     range [-1, 1].
 *
 * Background still receives a small fraction of each adjustment so the
 * result reads as a graded photo, not a hard cutout.
 */
void enhanceFusion(uint8_t* pixels, int width, int height, int stride,
                   const float* subjectMask, int maskW, int maskH,
                   float shadowBoost, float highlightBoost, float saturation);

} // namespace lmmse_enhance
