/*
 * raw_decoder_shared.h
 *
 * Functions implemented in raw_decoder.cpp that raw_decoder_v2.cpp needs to call. Kept
 * deliberately small — anything not on this list stays private to its own .cpp. Adding
 * a symbol here is a deliberate cross-file dependency; prefer copying the few-line helper
 * inline if it's truly trivial.
 */

#pragma once

#include <atomic>
#include <cstdint>

// Rayxie chromatic-aberration correction. Operates in-place on a uint16 BGR interleaved
// buffer. Same implementation as the JNI export `correctFringing`; v2 calls it directly so
// it can be wedged into the decode pipeline right after demosaic, before any color-matrix
// or float conversion.
//
// W, H — image dimensions in pixels (post-LibRaw orientation).
// threshold — green-gradient magnitude in uint16 units. 2000 (~3% of range) is typical.
// cancelFlag — optional pointer to an atomic int. Polled every ~64 rows; if non-zero,
//   the function returns early. Pass nullptr to disable cancellation.
void rayxie_correct_fringing(uint16_t* bgr, int W, int H, int threshold,
                             const std::atomic<int32_t>* cancelFlag = nullptr);

// RAZAmaze / RCD demosaic — ported from Darktable's rcd_demosaic.c. Kodak low-ISO
// CPSNR ≈ 39.94 dB, +0.8 dB over AMaZE. Operates on the raw Bayer image and writes
// demosaiced uint16 **BGR** interleaved into [dst] (matches LibRaw's
// dcraw_make_mem_image layout, so v2's output-mode switch handles it identically).
//
// rawImg      — LibRaw raw_image (uint16 Bayer, rawW × rawH, *including margins*).
// rawW, rawH  — full sensor dimensions including margins.
// cropL,cropT — active image crop offsets.
// outW, outH  — active image dimensions (≤ rawW, rawH).
// filters     — LibRaw Bayer filter bitmask (imgdata.idata.filters).
// blackLevel  — sensor black point (imgdata.color.black).
// whiteLevel  — sensor saturation point (imgdata.color.maximum).
// camMul[4]   — as-shot WB multipliers, normalised so G == 1.0.
// dst         — output uint16 BGR interleaved, outW × outH × 3 channels.
bool rcd_demosaic_to_buf(const uint16_t* rawImg,
                         int rawW, int rawH,
                         int cropL, int cropT,
                         int outW, int outH,
                         unsigned filters,
                         float blackLevel, float whiteLevel,
                         const float camMul[4],
                         uint16_t* dst);
