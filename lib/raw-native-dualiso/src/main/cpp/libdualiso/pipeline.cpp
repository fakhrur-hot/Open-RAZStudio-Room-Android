/*
 * libdualiso — pipeline setup passes (implementation).
 * Licensed under the GNU General Public License v2 or later.
 *
 * Derived from cr2hdr.c (Magic Lantern Team, 2013):
 *   white_detect        — L919–967
 *   promote_14_to_20    — L2108–2119 (inlined within hdr_interpolate)
 */

#include "pipeline.hpp"
#include "wirth.hpp"

#include <algorithm>
#include <cstdlib>
#include <vector>

namespace dualiso::lib {

namespace {
// COERCE(v, lo, hi) — cr2hdr's macro, expressed as a function for clarity.
inline int coerce(int v, int lo, int hi) noexcept {
    return std::max(lo, std::min(hi, v));
}
} // anon

int white_detect(const BlendContext& ctx, int& out_white_bright) noexcept {
    // cr2hdr ports use a per-ISO-bin pixel collection then take the
    // k-th largest sample to ignore hot pixels and bright specular
    // highlights. The kth-largest is implemented as kth-smallest of
    // the negated values — same trick cr2hdr uses, mirrored exactly.
    //
    // discard_pixels[0] = 10  (dark plane: top 10 brightest discarded)
    // discard_pixels[1] = 50  (bright plane: top 50 — saturated earlier)
    // safety_margins[0] = 100 (subtract 100 from the dark white)
    // safety_margins[1] = 1500(subtract 1500 from the bright white; loses
    //                          ~0.15 EV of non-aliased detail but avoids
    //                          pink-highlight blowouts from underestimation)
    constexpr int discard_pixels[2] = {10, 50};
    constexpr int safety_margins[2] = {100, 1500};

    // Bin 0 = dark ISO, bin 1 = bright ISO. Sized to width*height/2/9
    // because we step in 3s on both axes and partition between two
    // bins; cr2hdr uses the same max_pix value.
    const int max_pix =
        static_cast<int>(static_cast<long long>(ctx.width) * ctx.height / 2 / 9);
    if (max_pix < 16) {
        // Frame too small to detect — fall back to the 14-bit defaults.
        out_white_bright = std::min(ctx.white_level, 16383);
        return std::min(ctx.white_level, 16383);
    }

    std::vector<int> pixels0; pixels0.reserve(max_pix);
    std::vector<int> pixels1; pixels1.reserve(max_pix);
    int counts[2] = {0, 0};

    // Sample every 3rd pixel in both axes within the active area.
    // Restricted to active_area so the masked black-reference strip
    // along the top/left of the sensor doesn't bias the stats.
    for (int y = ctx.active.y1; y < ctx.active.y2; y += 3) {
        if (y < 0 || y >= ctx.height) continue;
        const uint16_t* row = ctx.bayer + y * ctx.width;
        const int bin = ctx.is_bright[y % 4] ? 1 : 0;
        for (int x = ctx.active.x1; x < ctx.active.x2; x += 3) {
            if (x < 0 || x >= ctx.width) continue;
            const int pix = row[x] & 0x3FFF;
            // cr2hdr's "stop collecting once we have enough" pattern:
            // counts is clamped BEFORE the write so the same slot just
            // gets overwritten repeatedly past max_pix-1. Preserved.
            int& cnt = counts[bin];
            if (cnt >= max_pix) cnt = max_pix - 1;
            std::vector<int>& vec = (bin == 0) ? pixels0 : pixels1;
            if (static_cast<int>(vec.size()) <= cnt) {
                vec.resize(cnt + 1);
            }
            vec[cnt] = -pix;  // negate so kth-smallest gives us kth-largest
            ++cnt;
        }
    }

    // If a bin received zero samples (e.g. is_bright[] all-zero on a
    // file that isn't actually dual-iso), fall back to the input
    // white_level for that side so the caller doesn't see garbage.
    int whites[2];
    if (counts[0] > discard_pixels[0]) {
        whites[0] = -kth_smallest_int(pixels0.data(), counts[0],
                                      discard_pixels[0])
                    - safety_margins[0];
    } else {
        whites[0] = ctx.white_level;
    }
    if (counts[1] > discard_pixels[1]) {
        whites[1] = -kth_smallest_int(pixels1.data(), counts[1],
                                      discard_pixels[1])
                    - safety_margins[1];
    } else {
        whites[1] = ctx.white_level;
    }

    // cr2hdr clamps to assumed-14-bit ranges so an out-of-range white
    // doesn't crash the downstream blend kernel.
    const int white_dark   = coerce(whites[0], 10000, 16383);
    const int white_bright = coerce(whites[1],  5000, 16383);

    out_white_bright = white_bright;
    return white_dark;
}

bool promote_14_to_20(BlendContext& ctx) noexcept {
    if (ctx.bayer == nullptr) return false;
    if (ctx.width <= 0 || ctx.height <= 0) return false;
    // Already promoted? Fail rather than leak.
    if (ctx.work20 != nullptr) return false;

    const size_t count = static_cast<size_t>(ctx.width) * ctx.height;
    auto* work = static_cast<uint32_t*>(std::malloc(count * sizeof(uint32_t)));
    if (work == nullptr) {
        ctx.last_error = "promote_14_to_20: malloc failed";
        return false;
    }

    // raw_get_pixel_14to20(x, y) = (raw[i] & 0x3FFF) << 6
    // cr2hdr.c L513: `(raw_get_pixel16(x,y) << 6) & 0xFFFFF`
    const uint16_t* src = ctx.bayer;
    for (size_t i = 0; i < count; ++i) {
        work[i] = (static_cast<uint32_t>(src[i]) & 0x3FFF) << 6;
    }

    ctx.work20 = work;
    // cr2hdr.c L2034–2035 promotes black/white by *64 to land in the
    // 20-bit working domain (14 + 6 = 20).
    ctx.black_level_work = ctx.black_level * 64;
    ctx.white_level_work = ctx.white_level * 64;
    return true;
}

void free_work20(BlendContext& ctx) noexcept {
    if (ctx.work20 != nullptr) {
        std::free(ctx.work20);
        ctx.work20 = nullptr;
    }
    ctx.black_level_work = 0;
    ctx.white_level_work = 0;
}

} // namespace dualiso::lib
