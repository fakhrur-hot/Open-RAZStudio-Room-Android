/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RCD demosaic (RAZAmaze) — v3 port.
 *
 *  Direct port of the v2 `rcd_demosaic_to_buf` kernel from raw_decoder.cpp
 *  into the v3 namespace. Kept byte-compatible with v2 — same algorithm,
 *  same output channel order (BGR uint16), same parallelisation strategy.
 *  See raw_decoder.cpp:1267-1549 for the original; comments preserved.
 *
 *  Dispatched from `runStageA` when StageAOptions.demosaicAlgorithm == -1.
 *  LibRaw never sees the negative user_qual — Stage A intercepts the value,
 *  reads the post-unpack Bayer mosaic, runs RCD, and skips dcraw_process().
 * ─────────────────────────────────────────────────────────────────────────────
 */

#pragma once

#include <cstdint>

namespace raw_v3 {

/**
 * Run the RCD demosaic on a Bayer-pattern raw mosaic.
 *
 *  rawImg       — pointer to LibRaw's `imgdata.rawdata.raw_image` (uint16
 *                 mosaic in sensor coordinates).
 *  rawW, rawH   — full mosaic dimensions including masked/optical-black
 *                 borders.
 *  cropL, cropT — top-left of the active image region within `rawImg`.
 *  outW, outH   — active dimensions (= LibRaw `iwidth / iheight`).
 *  filters      — LibRaw's Bayer pattern code (`imgdata.idata.filters`).
 *  blackLevel   — sensor black point (`imgdata.color.black`).
 *  whiteLevel   — sensor saturation (`imgdata.color.maximum`).
 *  camMul[4]    — as-shot WB multipliers, caller must pre-normalise so
 *                 green == 1.0 (mirroring v2 `decodeCore`).
 *  dst          — output buffer, must hold `outW*outH*3` uint16 in **BGR**
 *                 order (matches v2 / LibRaw `dcraw_make_mem_image`).
 *
 *  Returns false only on contract violations the v2 kernel never hit; the
 *  algorithm itself is total.
 */
/**
 *  clipThreshold — fraction of [black, white] range at which a sensel is
 *                  considered clipped. Default 0.97. Lower values (e.g. 0.90)
 *                  pull highlight reconstruction back, leaving slightly
 *                  brighter and more colour-saturated near-clip detail.
 *                  Range [0.80 .. 1.00]; out-of-range values are clamped.
 */
bool rcd_demosaic_to_buf(const uint16_t* rawImg,
                         int rawW, int rawH,
                         int cropL, int cropT,
                         int outW, int outH,
                         unsigned filters,
                         float blackLevel, float whiteLevel,
                         const float camMul[4],
                         uint16_t* dst,
                         float clipThreshold = 0.97f);

}  // namespace raw_v3
