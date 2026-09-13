/*
 * libdualiso — mean23 row-pair interpolation.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Faithful port of cr2hdr.c L2598–2692. This is the "simple" path
 * cr2hdr offers with --mean23 — interpolates missing row pairs by
 * averaging the two/three nearest same-colour neighbours in EV space.
 * The alternative AMaZE-edge path is more accurate but ~3× the code
 * and ~3× the runtime; we ship mean23 first.
 *
 * Output: two w*h uint32_t planes
 *   - dark[]   — values on the dark (low-ISO) scale at every pixel
 *   - bright[] — values on the bright (high-ISO) scale at every pixel
 *
 * Native pixels (on their own row) are copied straight from work20.
 * Interpolated pixels (on the missing row) are filled by averaging
 * vertical neighbours from the SAME ISO group.
 */

#pragma once

#include "blend_context.hpp"
#include "ev_luts.hpp"

#include <cstdint>

namespace dualiso::lib {

/**
 * Mean23 row-pair interpolation. Fills [dark_out] and [bright_out]
 * (both sized w*h) with the dark/bright planes ready for half_res_mix.
 *
 * Prerequisites:
 *   - ctx.work20 + ctx.is_bright + ctx.black_level_work / white_level_work
 *   - ctx.white_darkened (in 20-bit units, from match_exposures)
 *   - luts must be built for the same black/white levels
 *
 * Caller owns the two output buffers — supply them zero-initialised
 * (the interpolation only writes specific pixels per row, the border
 * fill handles the rest).
 *
 * Returns true on success. Currently always succeeds — the inner
 * loop has no failure modes that aren't already screened by the
 * prerequisites.
 */
bool interpolate_mean23(const BlendContext& ctx,
                         const EvLuts& luts,
                         uint32_t* dark_out,
                         uint32_t* bright_out) noexcept;

} // namespace dualiso::lib
