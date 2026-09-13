#pragma once
#include <cstdint>

namespace raw_v3 {

/**
 * Blend RAW FP16 highlights into the 8-bit AHB surface.
 * Only brightens clipped highlights (luma > 0.85).
 *
 * @param stageAFp16  FP16 RGBA Stage A buffer (W×H×4 uint16_t).
 * @param ahbArgb8    In-place AHB surface (W×H uint32_t, ARGB8).
 * @param w, h        Frame dimensions.
 * @param recovery    Strength [0..1] (UI value / 100).
 */
void recoverHighlights(
    const uint16_t* stageAFp16,
    uint32_t*       ahbArgb8,
    int w, int h,
    float recovery);

} // namespace raw_v3
