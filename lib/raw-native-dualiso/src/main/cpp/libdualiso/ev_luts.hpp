/*
 * libdualiso — raw2ev / ev2raw lookup tables.
 * Licensed under the GNU General Public License v2 or later.
 *
 * cr2hdr.c uses two large static LUTs to convert between raw pixel
 * values and EV-units throughout the blend kernel:
 *
 *   raw2ev[1<<20]            — 4 MB int array, value → EV
 *   ev2raw_0[24*EV_RESOLUTION] — 6 MB int array, EV → value (offset so
 *                               negative EV indexes are addressable)
 *
 * In the original, both are `static` globals inside hdr_interpolate.
 * We move them to a heap-allocated owned-by-context struct so the
 * library is reentrant and we don't blow .bss in the .so. Total
 * ~10 MB heap per blend, freed when the context goes out of scope.
 *
 * Constants:
 *   EV_RESOLUTION = 65536   (cr2hdr.c L27)
 *   EV2RAW_OFFSET = 10 * EV_RESOLUTION  (negative-EV indexing offset)
 *   EV2RAW_RANGE  = 24 * EV_RESOLUTION  (full positive+negative span)
 */

#pragma once

#include "blend_context.hpp"

#include <memory>

namespace dualiso::lib {

constexpr int kEvResolution = 65536;
constexpr int kEv2RawOffset = 10 * kEvResolution;   // negative-EV slack
constexpr int kEv2RawRange  = 24 * kEvResolution;   // total table size
constexpr int kRaw2EvRange  = 1 << 20;              // 1 048 576 entries

/**
 * Lookup tables for raw ↔ EV conversion. Lifetime is tied to a single
 * blend pass — built by [build_ev_luts], consumed by the interpolation
 * + half-res mix + chroma_smooth + write-back stages.
 *
 * The two arrays are stored as one combined allocation for cache
 * locality. raw2ev_index() / ev2raw_index() do the bounds clamping
 * cr2hdr's EV2RAW macro does.
 */
struct EvLuts {
    // raw2ev: raw20-bit value → EV-scaled int (sign carries negative).
    // ev2raw: EV-scaled int (with offset) → raw20-bit value.
    std::unique_ptr<int[]> raw2ev;
    std::unique_ptr<int[]> ev2raw_base;   // owns the storage
    int* ev2raw_offset = nullptr;          // = ev2raw_base.get() + kEv2RawOffset

    // White-level in EV terms — used by the half-res mix to bound the
    // overlap calculation. Populated by [build_ev_luts] from
    // ctx.white_level_work.
    int white_ev = 0;

    /** Returns ev2raw[clamped(ev)], matching cr2hdr's EV2RAW macro. */
    inline int ev2raw_clamped(int ev) const noexcept {
        if (ev < -kEv2RawOffset) ev = -kEv2RawOffset;
        else if (ev >= 14 * kEvResolution) ev = 14 * kEvResolution - 1;
        return ev2raw_offset[ev];
    }
};

/**
 * Build the raw2ev / ev2raw LUTs for the given black/white levels (in
 * 20-bit working units). Faithful port of cr2hdr.c L2046–2079.
 *
 * Allocates ~10 MB on the heap. Returns nullptr on allocation failure.
 *
 * The black level is the 20-bit working black (black_level * 64).
 * white_level_20 is used to set the upper-clip behaviour: any EV index
 * at or above raw2ev[white] returns at least `white` in ev2raw, so
 * the blend can't undershoot the clip point during a mix.
 */
std::unique_ptr<EvLuts> build_ev_luts(int black_level_20,
                                       int white_level_20) noexcept;

} // namespace dualiso::lib
