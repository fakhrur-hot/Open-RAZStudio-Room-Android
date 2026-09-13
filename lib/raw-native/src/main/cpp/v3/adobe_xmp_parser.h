/*
 * StudioRoom — RAW Pipeline v3 — Adobe XMP sidecar parser (M5.5).
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Reads Adobe Camera Raw / Lightroom `.xmp` sidecar files and extracts the
 * `crs:*` adjustment tags into a flat parameter struct. See Plan.md §10.6
 * for the locked scope (sidecar files only, no embedded XMP, no curves, no
 * camera profiles, no local adjustments).
 *
 * Supported tags:
 *   crs:Exposure2012           (stops, ±5)        → xmpExposure
 *   crs:Contrast2012           (−100..+100)        → xmpContrast
 *   crs:Highlights2012         (−100..+100)        → xmpHighlights
 *   crs:Shadows2012            (−100..+100)        → xmpShadows
 *   crs:Whites2012             (−100..+100)        → xmpWhites
 *   crs:Blacks2012             (−100..+100)        → xmpBlacks
 *   crs:Saturation             (−100..+100)        → xmpSaturation (UNUSED in M5.5)
 *   crs:Vibrance               (−100..+100)        → xmpVibrance   (UNUSED in M5.5)
 *   crs:Temperature            (Kelvin, abs)        → xmpTemperature (UNUSED)
 *   crs:Tint                   (−150..+150)        → xmpTint        (UNUSED)
 *   crs:HueAdjustmentRed/Orange/Yellow/Green/Aqua/Blue          → xmpHsl[0..15..2..]
 *   crs:SaturationAdjustmentRed/...                              → xmpHsl[1..16..2..]
 *   crs:LuminanceAdjustmentRed/...                               → xmpHsl[2..17..2..]
 *
 * All values are normalized to the [-1, +1] shader-uniform range:
 *   Exposure2012: divided by 5.0 (Adobe's ±5 EV maps to our ±5 slider)
 *   All 100-scale tags: divided by 100.0
 *   Tint: divided by 150.0
 *   Temperature: ignored in M5.5 (Adobe stores absolute K, our WB is delta;
 *                M6 will wire a proper K-to-slider mapping based on the
 *                source RAW's AsShot WB)
 *
 * Parser accepts both attribute-form and nested-element-form sidecars (the
 * Adobe XMP spec allows either; Lightroom emits attribute-form by default).
 */

#pragma once

#include <cstdint>
#include <string>

namespace raw_v3 {

// Mirrors the `xmp*` block of ShaderParams (Plan.md §7). 23 floats:
//   [0]   xmpEnabled (1 if any non-default field was set, else 0)
//   [1]   xmpExposure
//   [2]   xmpContrast
//   [3]   xmpHighlights
//   [4]   xmpShadows
//   [5]   xmpWhites
//   [6]   xmpBlacks
//   [7..24] xmpHsl[18]  — R/O/Y/G/A/B × (h, s, l)
struct XmpParams {
    bool  found = false;
    float exposure = 0.f;
    float contrast = 0.f;
    float highlights = 0.f;
    float shadows = 0.f;
    float whites = 0.f;
    float blacks = 0.f;
    float hsl[18] = {0};
    static constexpr int FLOAT_COUNT = 25;     // 1 enabled + 6 tone + 18 hsl
    /** Pack into a fixed FloatArray for jfloatArray return. */
    void writeTo(float* dst) const;
};

/** Parse a .xmp sidecar file from [path]. Returns XmpParams with found=false on failure. */
XmpParams parseAdobeXmpFile(const std::string& path);

/** Parse a .xmp document from an in-memory string. */
XmpParams parseAdobeXmpString(const std::string& xml);

}  // namespace raw_v3
