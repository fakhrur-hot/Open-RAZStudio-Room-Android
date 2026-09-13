/*
 * libdualiso — half-res mix and chroma_smooth passes.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Final blend stages in the mean23 path:
 *
 *   build_mix_curve  — precompute the smooth crossover from bright to
 *                      dark exposure used by half-res mixing.
 *   half_res_mix     — combine dark[] and bright[] planes through the
 *                      mix curve, producing the final 20-bit output.
 *   chroma_smooth_*  — optional 2x2/3x3/5x5 chroma cleanup pass that
 *                      reduces colour fringing in the mixed result.
 *
 * Faithful port of cr2hdr.c L2772–2865 + chroma_smooth.c.
 */

#pragma once

#include "blend_context.hpp"
#include "ev_luts.hpp"

#include <cstdint>
#include <vector>

namespace dualiso::lib {

/**
 * Build the half-res mix curve. cr2hdr.c L2793–2803.
 *
 * The curve maps every 20-bit raw value to a blend coefficient k ∈ [0,1].
 * k=0 → full bright (low-noise shadows). k=1 → full dark (un-clipped
 * highlights). Crossover is a raised-cosine ramp over `overlap` EV
 * just below the dark plane's clip point.
 *
 *   ev_at_i = log2(max(i/64 - black/64, 1)) + corr_ev
 *   c = -cos(clamp(ev_at_i - (max_ev - overlap), 0, overlap) * π / overlap)
 *   k = (c + 1) / 2
 *
 * `overlap` is `lowiso_dr - corr_ev` (cr2hdr.c L2773), adjusted down by
 * min(3, overlap-3) for better colour fidelity at the cost of slightly
 * more aliasing. We compute it from the context noise + corr_ev fields.
 *
 * Returns a heap-allocated kRaw2EvRange-sized double[] (the same shape
 * as raw2ev). Caller owns it via the returned unique_ptr.
 */
std::unique_ptr<double[]> build_mix_curve(const BlendContext& ctx,
                                           double overlap_ev) noexcept;

/**
 * Apply chroma_smooth to a 20-bit plane. Direction is `in → out`;
 * caller pre-copies `in` into `out` so untouched pixels (in dark areas
 * the kernel skips) keep their original value.
 *
 * `kernel` selects 2/3/5 — matching ChromaSmooth::K2x2/K3x3/K5x5.
 */
void chroma_smooth(const uint32_t* in,
                   uint32_t* out,
                   int width, int height,
                   const EvLuts& luts,
                   int kernel /* 2, 3, or 5 */) noexcept;

/**
 * Half-res mix loop (cr2hdr.c L2827–2848).
 *
 * For each pixel: read bright[]+dark[] EV values, look up mix coefficient
 * by bright value's mix_curve index, output ev2raw[lerp(bev,dev,k)].
 *
 * `out` may alias either input — cr2hdr writes to `halfres` which is a
 * separate buffer in the original. Caller must provide w*h-sized out.
 */
void half_res_mix(const uint32_t* dark_plane,
                  const uint32_t* bright_plane,
                  uint32_t* out,
                  int width, int height,
                  const EvLuts& luts,
                  const double* mix_curve) noexcept;

} // namespace dualiso::lib
