/*
 * libdualiso — detection passes.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Three detection passes the desktop cr2hdr runs at the start of every
 * file. Restructured to take a BlendContext instead of reading globals.
 *
 *   hdr_check          : is this even a dual-ISO frame?
 *   identify_pattern   : RGGB or GBRG bayer?
 *   identify_fields    : which row pair (mod 4) is the bright ISO?
 */

#pragma once

#include "blend_context.hpp"

namespace dualiso::lib {

/**
 * Faithful port of cr2hdr.c L1246–1284 `hdr_check()`.
 *
 * Iterates every (x, y) and (x, y+2) pixel pair, accumulates the mean
 * log2 brightness delta. Dual-ISO captures alternate ISO every two
 * rows, so |log2(p2) - log2(p)| is large; single-ISO captures cluster
 * near zero. Threshold 0.5 EV is cr2hdr's published value.
 *
 * Time: O(w·h) with a 16384-entry LUT for log2.
 * Side effects: none. Doesn't read or write [ctx.bayer] except for
 *   reads. Doesn't touch any other ctx field.
 */
bool hdr_check(const BlendContext& ctx) noexcept;

/**
 * Faithful port of cr2hdr.c L1285–1355 `identify_rggb_or_gbrg()`.
 *
 * Builds four histograms (one per (y%2, x%2) bucket), computes their
 * CDFs, then picks whichever interpretation (RGGB or GBRG) gives the
 * smaller CDF difference between the two green channels. The greens
 * always match more closely than R/B because the scene is what it is.
 *
 * Writes the result into [ctx.pattern]. Returns true on success.
 */
bool identify_pattern(BlendContext& ctx) noexcept;

/**
 * Faithful port of cr2hdr.c L1357 onwards `identify_bright_and_dark_fields`.
 *
 * Decides which of the four mod-4 row groups (0/1/2/3) are the bright
 * ISO and which are the dark ISO. Writes [ctx.is_bright].
 *
 * Returns true on success, false if the input doesn't have a clear
 * bright/dark pattern (likely not actually dual-ISO).
 */
bool identify_fields(BlendContext& ctx) noexcept;

} // namespace dualiso::lib
