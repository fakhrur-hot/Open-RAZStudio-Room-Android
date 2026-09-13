/*
 * libdualiso — EV LUT builder.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Derived from cr2hdr.c L2046–2079.
 */

#include "ev_luts.hpp"

#include <algorithm>
#include <cmath>

namespace dualiso::lib {

namespace {
inline int coerce(int v, int lo, int hi) noexcept {
    return std::max(lo, std::min(hi, v));
}
} // anon

std::unique_ptr<EvLuts> build_ev_luts(int black_level_20,
                                       int white_level_20) noexcept {
    auto luts = std::make_unique<EvLuts>();
    luts->raw2ev      = std::unique_ptr<int[]>(new (std::nothrow) int[kRaw2EvRange]);
    luts->ev2raw_base = std::unique_ptr<int[]>(new (std::nothrow) int[kEv2RawRange]);
    if (!luts->raw2ev || !luts->ev2raw_base) {
        return nullptr;
    }
    luts->ev2raw_offset = luts->ev2raw_base.get() + kEv2RawOffset;

    const int black = black_level_20;
    const int white = white_level_20;

    // ── raw2ev ────────────────────────────────────────────────────
    // cr2hdr.c L2053–2060. Maps every possible 20-bit raw value to an
    // EV-scaled integer (signed — negative EV for sub-black values).
    //
    //   signal = max(i/64 - black/64, -1023)
    //   raw2ev[i] = ± round(log2(1 + |signal|) * EV_RESOLUTION)
    //
    // The 1+ guard avoids log2(0); the 1023 floor caps the negative EV
    // range so sub-black noise doesn't blow up the bottom of the table.
    int* raw2ev = luts->raw2ev.get();
    for (int i = 0; i < kRaw2EvRange; ++i) {
        const double signal_raw = static_cast<double>(i) / 64.0 -
                                  static_cast<double>(black) / 64.0;
        const double signal = std::max(signal_raw, -1023.0);
        if (signal > 0.0) {
            raw2ev[i] = static_cast<int>(std::round(
                std::log2(1.0 + signal) * kEvResolution));
        } else {
            raw2ev[i] = -static_cast<int>(std::round(
                std::log2(1.0 - signal) * kEvResolution));
        }
    }

    // ── ev2raw ────────────────────────────────────────────────────
    // cr2hdr.c L2062–2075. Inverse of the above, indexed in two halves:
    //
    //   ev ∈ [-10*EV, 0):  ev2raw[ev] = black+64 - 64·2^(-ev/EV) (clamped 0..black)
    //   ev ∈ [0, 14*EV):   ev2raw[ev] = black-64 + 64·2^( ev/EV) (clamped black..2^20-1)
    //
    // For high EVs at or above raw2ev[white], cr2hdr ensures the result
    // is at least `white` so the half-res mix can't undershoot clipping.
    int* ev2raw = luts->ev2raw_offset;
    const int white_ev = raw2ev[coerce(white, 0, kRaw2EvRange - 1)];
    luts->white_ev = white_ev;

    for (int i = -kEv2RawOffset; i < 0; ++i) {
        const double v = static_cast<double>(black) + 64.0 -
                         std::round(64.0 * std::pow(2.0,
                            static_cast<double>(-i) / kEvResolution));
        ev2raw[i] = coerce(static_cast<int>(v), 0, black);
    }

    for (int i = 0; i < 14 * kEvResolution; ++i) {
        const double v = static_cast<double>(black) - 64.0 +
                         std::round(64.0 * std::pow(2.0,
                            static_cast<double>(i) / kEvResolution));
        int clamped = coerce(static_cast<int>(v), black, (1 << 20) - 1);
        if (i >= white_ev) {
            clamped = std::max(clamped, white);
        }
        ev2raw[i] = clamped;
    }

    // cr2hdr.c L2078: "keep bad pixels". The value at raw2ev[0] (the
    // EV corresponding to a literal zero raw input) maps back to 0
    // rather than to ev2raw's normal floor — preserves "discarded"
    // sentinel values through the conversion round-trip.
    if (raw2ev[0] >= -kEv2RawOffset && raw2ev[0] < 14 * kEvResolution) {
        ev2raw[raw2ev[0]] = 0;
    }

    return luts;
}

} // namespace dualiso::lib
