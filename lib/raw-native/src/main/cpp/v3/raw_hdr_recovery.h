/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * RAW-domain HDR highlight recovery — injected between LibRaw::unpack()
 * and dcraw_process().
 *
 * Architecture: pixel-shuffle lightweight U-Net operating on the Bayer
 * mosaic in the [R, Gr, Gb, B] × (H/2 × W/2) domain.
 *
 *   Input  : 4-ch float32 (H/2 × W/2 × 4), normalised [0, 1] against
 *             per-channel white level.
 *   Output : 4-ch float32, same shape — recovered highlights, clamped
 *             [0, 1] and denormalised back to uint16 before write-back.
 *
 * Model file: assets/models/raw_hdr_recovery.bin  (custom flat binary,
 *             see export_raw_hdr_to_bin.py for the format).
 *
 * When the model file is absent or weight loading fails the function is a
 * no-op so the decode path is unchanged.
 */

#pragma once

#include <cstdint>
#include <string>

namespace raw_v3 {

/**
 * Run the RAW-domain HDR recovery pass.
 *
 *  raw_image   — the LibRaw raw_image buffer (uint16, 1-channel Bayer/CFA)
 *  raw_width   — buffer width  (raw_width, i.e. including active margins)
 *  raw_height  — buffer height
 *  filters     — LibRaw filters field (0 for X-Trans — skipped)
 *  white_level — maximum sensor value (after black subtraction) used to
 *                normalise the network input
 *  black_level — per-pixel black level (scalar approximation)
 *  model_data  — byte pointer to the .bin weight blob loaded from assets
 *  model_size  — length of model_data in bytes
 *
 * The function writes recovered uint16 values back in-place to raw_image.
 * It is safe to call when model_data is null (returns immediately).
 */
void applyRawHdrRecovery(
    uint16_t*     raw_image,
    int           raw_width,
    int           raw_height,
    unsigned int  filters,
    float         white_level,
    float         black_level,
    const uint8_t* model_data,
    size_t         model_size);

}  // namespace raw_v3
