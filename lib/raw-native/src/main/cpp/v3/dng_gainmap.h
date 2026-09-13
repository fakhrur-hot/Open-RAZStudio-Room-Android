// DNG OpcodeList2 GainMap support (lens-shading correction).
//
// Android's Camera2 API hands out DNGs whose Bayer data is deliberately NOT
// shading-corrected: the correction ships as GainMap opcodes in OpcodeList2,
// one per CFA plane. LibRaw does not execute DNG opcodes unless it is built
// against the Adobe DNG SDK (we are not), so without this the maps are dropped
// and the picture keeps the sensor's raw falloff.
//
// Measured on an Infinix X6873 DNG (2026-09-07): gains reach 3.29–3.55× at the
// corners, and the per-plane MEANS differ — R 1.589, G 1.661/1.653, B 1.617.
// So ignoring them costs more than brightness: red is left relatively stronger
// than green away from centre, which reads as the pink/magenta cast the owner
// reported. Applying the maps fixes the tint and the vignetting together.
//
// Scope: GainMap (opcode id 9) only. Other opcodes are skipped — warp/bad-pixel
// correction is either handled elsewhere (lens profiles) or not applicable.

#ifndef RAZ_V3_DNG_GAINMAP_H
#define RAZ_V3_DNG_GAINMAP_H

#include <cstdint>
#include <string>

namespace raw_v3 {

/** What [applyDngGainMaps] did, for logging and diagnostics. */
struct DngGainMapResult {
    bool  applied     = false;   ///< true when at least one map was applied
    int   mapsFound   = 0;       ///< GainMap opcodes present in OpcodeList2
    int   mapsApplied = 0;       ///< of those, how many were applied
    float minGain     = 1.f;
    float maxGain     = 1.f;
    std::string note;            ///< why nothing was applied, when applicable
};

/**
 * Read OpcodeList2 from [dngPath] and apply its GainMaps to [rawImage]
 * in place.
 *
 * [rawImage] must be LibRaw's `imgdata.rawdata.raw_image` — the full
 * (raw_width × raw_height) Bayer plane BEFORE demosaic — and [black]/[white]
 * [leftMargin]/[topMargin] are `imgdata.sizes.left_margin`/`top_margin`: the
 * opcode rectangles are in ACTIVE-AREA coordinates while `raw_image` covers the
 * whole sensor plane. [black]/[white] are
 * the levels LibRaw reports, so the gain is applied to the black-subtracted
 * signal and re-offset (gaining the pedestal would lift the black point and
 * wash the shadows).
 *
 * Returns a result describing what happened; a file with no OpcodeList2, or a
 * non-DNG, is a silent no-op with `applied == false`.
 */
DngGainMapResult applyDngGainMaps(const std::string& dngPath,
                                  uint16_t* rawImage,
                                  int rawWidth,
                                  int rawHeight,
                                  int leftMargin,
                                  int topMargin,
                                  float black,
                                  float white);

}  // namespace raw_v3

#endif  // RAZ_V3_DNG_GAINMAP_H
