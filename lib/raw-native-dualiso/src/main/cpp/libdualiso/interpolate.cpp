/*
 * libdualiso — mean23 interpolation (implementation).
 * Licensed under the GNU General Public License v2 or later.
 *
 * Derived from cr2hdr.c L1798–1825 (mean2/mean3 helpers) and
 * L2598–2692 (interpolation + border fill).
 *
 * Concept:
 *   - For each y in [2, h-2): one of the row pairs is "native" (already
 *     contains real exposures for this y) and the other is "interp"
 *     (we need to fill it).
 *   - On an RG row (y%2==0): interp the R from vertical neighbours
 *     (x, y±2), interp the G from a 3-pixel pattern across the
 *     diagonal that lands on same-colour same-ISO samples.
 *   - On a GB row (y%2==1): symmetric — interp G + B.
 *   - Edge rows are filled by repeating the nearest valid row.
 *
 * The trick `s = (is_bright[y%4] == is_bright[(y+1)%4]) ? -1 : 1`
 * decides whether the same-ISO partner for the green interpolation
 * is one row up or one row down — depends on which mod-4 group y is in
 * relative to the row above/below it.
 */

#include "interpolate.hpp"

#include <algorithm>
#include <cstdlib>

namespace dualiso::lib {

namespace {

// cr2hdr mean2/mean3 helpers (L1798–1825). All inputs are EV values.
// `white` is the bright/dark plane's white level converted to EV.
// We don't surface the err output — the mean23 path passes 0 (NULL)
// at every call site.

inline int mean2_ev(int a, int b, int white_ev) noexcept {
    if (a >= white_ev || b >= white_ev) return white_ev;
    return (a + b) / 2;
}

inline int mean3_ev(int a, int b, int c, int white_ev) noexcept {
    const int m = (a + b + c) / 3;
    if (a >= white_ev || b >= white_ev || c >= white_ev) {
        return std::max(m, white_ev);
    }
    return m;
}

inline uint32_t read32(const uint32_t* buf, int x, int y, int w) noexcept {
    return buf[x + y * w];
}

} // anon

bool interpolate_mean23(const BlendContext& ctx,
                         const EvLuts& luts,
                         uint32_t* dark_out,
                         uint32_t* bright_out) noexcept {
    if (ctx.work20 == nullptr || dark_out == nullptr || bright_out == nullptr) {
        return false;
    }
    const int w = ctx.width;
    const int h = ctx.height;
    const uint32_t* work = ctx.work20;
    const int* raw2ev = luts.raw2ev.get();

    // White EV for the two planes — cr2hdr looks up these on demand
    // per row (white differs for bright vs dark — the bright plane
    // was darkened by match_exposures to white_darkened).
    //   bright's "effective white" after correction = white_darkened
    //   dark's white                                = white_level_work
    // Both are 20-bit values; we need their EV-space equivalents.
    auto bound = [&](int v) noexcept {
        return raw2ev[std::clamp(v, 0, kRaw2EvRange - 1)];
    };
    const int white_ev_for_dark   = bound(ctx.white_level_work);
    const int white_ev_for_bright = bound(ctx.white_darkened);

    // ── Main interior loop (cr2hdr.c L2601–2648) ──────────────────
    for (int y = 2; y < h - 2; ++y) {
        const bool bright_row = (ctx.is_bright[y % 4] != 0);
        uint32_t* native = bright_row ? bright_out : dark_out;
        uint32_t* interp = bright_row ? dark_out   : bright_out;
        const bool is_rg = ((y % 2) == 0);
        // cr2hdr: white = !BRIGHT_ROW ? white_darkened : white_level
        // (so when row is bright, native is bright[] → interp is dark[]
        //  → we're filling dark, so the white we cap to is white_level
        //  ...wait, let me re-read).
        //
        // Re-reading L2606: `int white = !BRIGHT_ROW ? white_darkened : raw_info.white_level`.
        // So when BRIGHT_ROW is FALSE (we're on a dark row), the white
        // we use for the interpolation cap is white_darkened. When
        // BRIGHT_ROW is TRUE (we're on a bright row), use white_level.
        //
        // What's being interpolated? When y is a bright row, native
        // points at bright[] (filled from this row's actual pixels)
        // and interp points at dark[] (filled by interpolating from
        // the dark rows above/below). The interpolated value is on
        // the DARK scale, so its clip is `white_level` = the dark
        // plane's clip = ctx.white_level_work. ✓
        //
        // When y is a dark row, native is dark[] (this row) and
        // interp is bright[]. The interpolated bright value is on
        // the bright (already-darkened) scale, so its clip is
        // white_darkened. ✓
        const int white_ev = bright_row ? white_ev_for_dark : white_ev_for_bright;

        // Same-ISO-partner sign for the green interpolation.
        // cr2hdr.c L2614: s = -1 if y and y+1 share the same is_bright
        // bucket (which means y-1 is the partner — they're on opposite
        // rows but same ISO?). Actually re-reading: is_bright[y%4]
        // tells which ISO bucket row y belongs to. If y%4 and (y+1)%4
        // BOTH say "bright" or both say "dark", then the green
        // partner is at y-1 direction... but is_bright pairs are
        // (0,1) and (2,3) — meaning consecutive mod-4 buckets share
        // an ISO. So is_bright[y%4] == is_bright[(y+1)%4] is the
        // "y and y+1 are paired" case. s = -1 means look at y-1 for
        // the green partner; s = +1 means look at y+1.
        const int s = (ctx.is_bright[y % 4] == ctx.is_bright[(y + 1) % 4])
                          ? -1 : 1;

        for (int x = 2; x < w - 3; x += 2) {
            if (is_rg) {
                // Red at (x, y), Green at (x+1, y) of an RG row.
                const int ra = static_cast<int>(read32(work, x,     y - 2, w) & 0xFFFFF);
                const int rb = static_cast<int>(read32(work, x,     y + 2, w) & 0xFFFFF);
                const int ri = mean2_ev(raw2ev[ra], raw2ev[rb], white_ev);

                const int ga = static_cast<int>(read32(work, x + 2, y + s,     w) & 0xFFFFF);
                const int gb = static_cast<int>(read32(work, x,     y + s,     w) & 0xFFFFF);
                const int gc = static_cast<int>(read32(work, x + 1, y - 2 * s, w) & 0xFFFFF);
                const int gi = mean3_ev(raw2ev[ga], raw2ev[gb], raw2ev[gc], white_ev);

                interp[x     + y * w] = static_cast<uint32_t>(luts.ev2raw_clamped(ri));
                interp[x + 1 + y * w] = static_cast<uint32_t>(luts.ev2raw_clamped(gi));
            } else {
                // Green at (x, y), Blue at (x+1, y) of a GB row.
                const int ba = static_cast<int>(read32(work, x + 1, y - 2, w) & 0xFFFFF);
                const int bb = static_cast<int>(read32(work, x + 1, y + 2, w) & 0xFFFFF);
                const int bi = mean2_ev(raw2ev[ba], raw2ev[bb], white_ev);

                const int ga = static_cast<int>(read32(work, x + 1, y + s,     w) & 0xFFFFF);
                const int gb = static_cast<int>(read32(work, x - 1, y + s,     w) & 0xFFFFF);
                const int gc = static_cast<int>(read32(work, x,     y - 2 * s, w) & 0xFFFFF);
                const int gi = mean3_ev(raw2ev[ga], raw2ev[gb], raw2ev[gc], white_ev);

                interp[x     + y * w] = static_cast<uint32_t>(luts.ev2raw_clamped(gi));
                interp[x + 1 + y * w] = static_cast<uint32_t>(luts.ev2raw_clamped(bi));
            }
            // Native pixels: copy straight through, no interpolation.
            native[x     + y * w] = read32(work, x,     y, w);
            native[x + 1 + y * w] = read32(work, x + 1, y, w);
        }
    }

    // ── Border interpolation (L2651–2691) ────────────────────────
    // Top 3 rows: native[y] = work[y], interp[y] = work[y+2]
    for (int y = 0; y < 3 && y < h; ++y) {
        const bool bright_row = (ctx.is_bright[y % 4] != 0);
        uint32_t* native = bright_row ? bright_out : dark_out;
        uint32_t* interp = bright_row ? dark_out   : bright_out;
        const int y_src_interp = std::min(y + 2, h - 1);
        for (int x = 0; x < w; ++x) {
            interp[x + y * w] = read32(work, x, y_src_interp, w);
            native[x + y * w] = read32(work, x, y, w);
        }
    }

    // Bottom 4 rows: native[y] = work[y], interp[y] = work[y-2]
    for (int y = std::max(0, h - 4); y < h; ++y) {
        const bool bright_row = (ctx.is_bright[y % 4] != 0);
        uint32_t* native = bright_row ? bright_out : dark_out;
        uint32_t* interp = bright_row ? dark_out   : bright_out;
        const int y_src_interp = std::max(y - 2, 0);
        for (int x = 0; x < w; ++x) {
            interp[x + y * w] = read32(work, x, y_src_interp, w);
            native[x + y * w] = read32(work, x, y, w);
        }
    }

    // Left/right 2-pixel strips for the middle rows.
    for (int y = 2; y < h; ++y) {
        const bool bright_row = (ctx.is_bright[y % 4] != 0);
        uint32_t* native = bright_row ? bright_out : dark_out;
        uint32_t* interp = bright_row ? dark_out   : bright_out;
        const int y_src_interp = std::max(y - 2, 0);
        // Left edge.
        for (int x = 0; x < std::min(2, w); ++x) {
            interp[x + y * w] = read32(work, x, y_src_interp, w);
            native[x + y * w] = read32(work, x, y,           w);
        }
        // Right edge (cr2hdr.c L2687–2691 — uses x-2 source).
        for (int x = std::max(w - 3, 0); x < w; ++x) {
            const int x_src = std::max(x - 2, 0);
            interp[x + y * w] = read32(work, x_src, y_src_interp, w);
            native[x + y * w] = read32(work, x_src, y,           w);
        }
    }

    return true;
}

} // namespace dualiso::lib
