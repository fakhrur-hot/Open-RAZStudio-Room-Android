/*
 * libdualiso — pipeline setup passes.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Functions that prepare the 20-bit working buffer cr2hdr's blend
 * kernel operates on, plus the white-level detection that has to
 * happen after identify_fields and before match_exposures.
 *
 * Execution order (per cr2hdr.c):
 *   1. hdr_check                  → libdualiso/detect.cpp
 *   2. identify_pattern           → libdualiso/detect.cpp
 *   3. identify_fields            → libdualiso/detect.cpp
 *   4. white_detect               → here
 *   5. promote_14_to_20           → here  (creates ctx.work20)
 *   6. match_exposures            → pending
 *   7. interpolate (mean23 path)  → pending
 *   8. chroma_smooth              → pending
 *   9. free_work20                → here  (call from caller after blend)
 */

#pragma once

#include "blend_context.hpp"

namespace dualiso::lib {

/**
 * Faithful port of cr2hdr.c L919–967 `white_detect`.
 *
 * Determines the actual white level for the dark and bright ISO planes
 * separately. cr2hdr captures both because the two planes may clip at
 * different points (the bright/high-ISO plane saturates earlier).
 *
 * Prerequisites: ctx.is_bright must be populated (via identify_fields).
 *                ctx.active must be set.
 *
 * Writes:
 *   - returns the dark plane's white level
 *   - [out_white_bright] receives the bright plane's white level
 *
 * The values returned mirror cr2hdr's clamps: [10000, 16383] for dark,
 * [5000, 16383] for bright. Caller should fold these into the context
 * before downstream passes (cr2hdr promotes them to 20-bit by *=64
 * before the match-exposures step).
 *
 * Time: O(w*h / 9) — samples every 3rd pixel in both axes.
 */
int white_detect(const BlendContext& ctx, int& out_white_bright) noexcept;

/**
 * Promote the bayer plane from 14-bit to a 20-bit working domain.
 * Allocates a fresh uint32_t buffer of size width*height and fills
 * it with (bayer[i] & 0x3FFF) << 6. Updates ctx.work20 to point at
 * the new buffer; updates ctx.black_level_work / white_level_work
 * to the *64 scaled values (cr2hdr.c L2034–2035).
 *
 * Caller owns the new buffer's lifetime — call [free_work20] when
 * done. Returns true on success, false on malloc failure.
 *
 * Prerequisite: ctx.bayer + ctx.width + ctx.height + ctx.black_level
 *               + ctx.white_level must be set. Levels in 14-bit units.
 */
bool promote_14_to_20(BlendContext& ctx) noexcept;

/**
 * Release the working buffer allocated by [promote_14_to_20] and
 * reset the pointer/level fields to their pre-promotion state.
 * Idempotent — safe to call on a context that was never promoted.
 */
void free_work20(BlendContext& ctx) noexcept;

} // namespace dualiso::lib
