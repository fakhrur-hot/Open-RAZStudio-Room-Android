/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * RAW-domain shadow/black recovery — injected between LibRaw::unpack()
 * and dcraw_process(), after the HDR highlight pass.
 *
 * Same pixel-unshuffled U-Net architecture as raw_hdr_recovery.
 * Triggered on pixels BELOW a threshold (shadow zone) instead of above.
 * Luma scale clamped to [0.5, 1.0] — can only brighten shadows, never clip.
 *
 * Model file: assets/models/raw_shadow_recovery.bin  (RAZ1 format)
 */

#pragma once

#include <cstdint>
#include <cstddef>

namespace raw_v3 {

void applyRawShadowRecovery(
    uint16_t*      raw_image,
    int            raw_width,
    int            raw_height,
    unsigned int   filters,
    float          white_level,
    float          black_level,
    const uint8_t* model_data,
    size_t         model_size);

}  // namespace raw_v3
