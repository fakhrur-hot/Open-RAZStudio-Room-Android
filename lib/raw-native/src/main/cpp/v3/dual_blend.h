/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Dual-demosaic blend mask helpers — port of RawTherapee's
 *  `rt_algo.cc::buildBlendMask` and supporting helpers
 *  (calcBlendFactor, tileAverage, tileVariance, calcContrastThreshold).
 *
 *  Used by Stage A's AMaZE+VNG dual-decode path: AMaZE handles detail
 *  regions, VNG fills flat regions. Per-pixel blend factor in [0..1]
 *  decides which contributes (0 = pure AMaZE, 1 = pure VNG).
 *
 *  Critical scale adaptation: RT's helpers assume luminance in
 *  `[0..65535]` (16-bit). Our luminance is `[0..1]`. The sigmoid in
 *  `calcBlendFactor` is dimensionless so unchanged; the contrast
 *  computation in `calcContrastThreshold` / `buildBlendMask` uses
 *  `scale = 0.0625 / 327.68` against differences of luminance values —
 *  to keep the same perceptual threshold the constant is scaled by
 *  65535. Final value `kContrastScaleFp = 12.5f`.
 *
 *  Auto-contrast bounds (`minLuminance`, `maxLuminance`) are similarly
 *  rescaled: RT's 2000/20000 in 16-bit → 0.0305/0.305 in [0..1]. The
 *  variance threshold (0.5) is unitless and unchanged.
 * ─────────────────────────────────────────────────────────────────────────────
 */

#pragma once

namespace raw_v3 {

/**
 * Build a per-pixel blend mask in [0..1] from a luminance plane. The
 * mask values come from a sigmoid (`calcBlendFactor`) applied to the
 * local 4-point Sobel-ish contrast magnitude. 0 = high-contrast (use
 * primary decoder — AMaZE), 1 = low-contrast (use secondary — VNG).
 *
 * The final mask is Gaussian-blurred (sigma=2) so transitions are
 * smooth.
 *
 *  luminance   — W*H floats in [0..1]
 *  blend       — output W*H floats, written in-place
 *  W, H        — image dimensions
 *  contrastThreshold — in/out. Input value in [0..1] is used as-is when
 *                `autoContrast` is false; if it's 0 the function fills
 *                `blend` with 1.0 (pure secondary). When `autoContrast`
 *                is true, the function tile-scans the image and writes
 *                the picked threshold back.
 *  autoContrast — when true, the function searches the image for the
 *                flattest tile and derives the threshold from it.
 *                Matches RT's `auto contrast threshold` UI button.
 */
void buildBlendMask(const float* luminance,
                    float* blend,
                    int W, int H,
                    float* contrastThreshold,
                    bool autoContrast);

/**
 * Extended Debug Statistics for Multi-Factor Confidence & Weight Tuning.
 */
struct DualBlendDebugStats {
    float avgAmazeWeight;
    float avgVngWeight;
    float highlightAmazeWeight;
    float shadowAmazeWeight;
    float clippedRegionAmazeWeight;
    float avgEdgeConfidence;
    float avgTextureConfidence;
    float avgNoiseConfidence;
    float avgHighlightConfidence;
    int clippedPixels;
};

/**
 * Compute and retrieve extended debug statistics for the last built blend mask.
 */
const DualBlendDebugStats& getLastDualBlendDebugStats();

/**
 * Debug export helpers (e.g. for saving intermediate confidence planes to PNG/disk during tuning).
 */
void saveBlendMapPng(const char* filepath, const float* blend, int W, int H);
void saveEdgeConfidencePng(const char* filepath, int W, int H);
void saveTextureConfidencePng(const char* filepath, int W, int H);
void saveHighlightConfidencePng(const char* filepath, int W, int H);
void saveNoiseConfidencePng(const char* filepath, int W, int H);

}  // namespace raw_v3
