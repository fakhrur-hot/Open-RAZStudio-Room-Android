/*
 * libdualiso — exposure-matching pass.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Pre-blend stage that finds the EV difference between the bright
 * (high-ISO) and dark (low-ISO) row groups, then applies the linear
 * transform that brings the bright plane down to the dark plane's
 * scale. The output (corr_ev) is the dynamic-range gain the blend
 * will deliver — typically 1–3 EV depending on the ISO offset the
 * user picked in the ML menu.
 *
 * Faithful port of cr2hdr.c L1541–1796.
 */

#pragma once

#include "blend_context.hpp"

namespace dualiso::lib {

/**
 * Find the exposure difference between the two ISO planes and apply
 * the matching correction to the bright-row pixels in [ctx.work20].
 *
 * Prerequisites:
 *   - ctx.work20 must be populated (promote_14_to_20 must have run)
 *   - ctx.is_bright must be populated (identify_fields must have run)
 *   - ctx.black_level_work / white_level_work must be set (20-bit)
 *   - ctx.white_darkened on input must hold the bright-plane white
 *     level in 20-bit units (i.e. white_bright * 64 from white_detect)
 *
 * Writes:
 *   - mutates ctx.work20 in place: bright-row pixels are linearly
 *     scaled so they sit on the same EV scale as dark-row pixels
 *   - ctx.corr_ev receives log2(1/a) where a is the fitted slope
 *   - ctx.white_darkened receives the bright-plane white level
 *     AFTER the linear correction is applied (in 20-bit units)
 *
 * Returns true on success, false if the file doesn't look like
 * interlaced ISO (the fitted ISO factor came out < 1.2 → almost
 * certainly not dual-ISO). Even on false, the buffer remains valid
 * (just unchanged) so caller can fall back to single-ISO rendering.
 *
 * Memory: allocates two w*h int planes (~200 MB on a 25 MP frame)
 * for the deinterlace + percentile passes. Frees them before
 * returning. Caller should pre-check available memory if running
 * on low-RAM devices.
 *
 * Time: ~500 ms on a 25 MP frame (the 3000-iteration EV sweep is
 * the dominant cost).
 */
bool match_exposures(BlendContext& ctx) noexcept;

} // namespace dualiso::lib
