/*
 * libdualiso — top-level blend driver.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Orchestrates the full mean23 dual-ISO pipeline:
 *   1. detect: hdr_check + identify_pattern + identify_fields
 *   2. promote 14→20-bit working buffer
 *   3. white_detect (per-ISO clipping)
 *   4. match_exposures (find EV difference, linearise bright plane)
 *   5. build raw2ev / ev2raw LUTs
 *   6. interpolate_mean23 → dark[] + bright[] planes
 *   7. build_mix_curve from corr_ev + lowiso_dr
 *   8. half_res_mix → halfres[] 20-bit plane
 *   9. chroma_smooth (optional, on by default)
 *  10. write back to 14-bit bayer (shift right by 6, store as uint16_t)
 *
 * Memory peak: ~ w*h * (4+4+4+4) bytes = ~400 MB transient on a 25 MP
 * frame (work20 + dark + bright + halfres). All released before return.
 */

#pragma once

#include "blend_context.hpp"

namespace dualiso::lib {

/**
 * Run the full mean23 dual-ISO blend on ctx.bayer in place.
 *
 * Prerequisites:
 *   - ctx.bayer + ctx.width + ctx.height set
 *   - ctx.black_level, ctx.white_level in 14-bit units
 *   - ctx.active populated (caller can pass full-frame if unsure —
 *     the detection passes are robust to a generous active rect).
 *
 * Returns true on success — ctx.bayer now contains the blended,
 * deinterlaced plane. ctx.corr_ev holds the recovered EV gain.
 *
 * Returns false on:
 *   - Detection fails (file isn't actually dual-ISO)
 *   - Bayer/interlace pattern not in cr2hdr's supported set
 *   - Memory allocation failure for any intermediate plane
 *   - match_exposures bails (factor < 1.2)
 *
 * On `false`, the original bayer plane is restored from the work20
 * snapshot before promotion — caller can safely fall back to single-
 * ISO rendering with no visible damage.
 *
 * Time: ~2–4 s on a 25 MP CR2 on arm64-v8a. Dominated by the
 * match_exposures EV sweep + chroma_smooth.
 */
bool run_blend(BlendContext& ctx) noexcept;

} // namespace dualiso::lib
