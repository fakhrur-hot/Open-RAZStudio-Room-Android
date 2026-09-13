/*
 * StudioRoom Magic-Lantern dual-ISO blend kernel — public header.
 * Licensed under the GNU General Public License v2 or later (see
 * LICENSE-GPL2 in this module's root).
 */

#pragma once

#include <cstdint>

namespace dualiso {

/**
 * Authoritative dual-ISO detector. Returns true when the bayer plane
 * exhibits the alternating-row-pair brightness pattern that ML's
 * dual_iso module produces. Faithful port of cr2hdr.c's hdr_check.
 *
 * @param bayer  16-bit bayer plane (14-bit values, top bits clear)
 * @param width  in pixels
 * @param height in pixels
 * @param black  sensor black level (e.g. 2048 for 14-bit Canon)
 * @param white  sensor white level (e.g. 15000 for 14-bit Canon)
 */
bool hdr_check(const uint16_t* bayer, int width, int height, int black, int white);

/**
 * Run the full detection pass — hdr_check + identify_pattern +
 * identify_fields — and return a packed status code:
 *   bit 0:  hdr_check passed                (avg_ev > 0.5)
 *   bit 1:  bayer pattern is RGGB           (clear=GBRG)
 *   bit 2:  identify_fields succeeded       (clear=ambiguous / wrong pattern)
 *   bits 4..7 (4 bits): is_bright[3..0] packed (4 = bucket 3, ... 7 = bucket 0)
 *
 * Returns -1 on hard input error (null bayer, bad dims).
 *
 * This is a read-only pass over the bayer plane (does not mutate).
 */
int detect_full(const uint16_t* bayer, int width, int height, int black, int white);

/**
 * Run the mean23 blend pipeline in place on the bayer buffer. The
 * resulting plane no longer has the alternating-ISO interlacing — it's
 * a single-exposure plane with ~3 stops of extra shadow latitude
 * recovered from the dark rows.
 *
 * Returns true on success, false on hard failure (e.g. malloc). The
 * buffer is left untouched on failure.
 *
 * STUB CURRENTLY — see implementation comment.
 */
bool blend_mean23(uint16_t* bayer, int width, int height, int black, int white);

} // namespace dualiso
