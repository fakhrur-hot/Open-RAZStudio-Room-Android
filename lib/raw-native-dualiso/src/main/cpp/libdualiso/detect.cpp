/*
 * libdualiso — detection passes (implementation).
 * Licensed under the GNU General Public License v2 or later.
 *
 * Derived from cr2hdr.c (Magic Lantern Team, 2013):
 *   hdr_check                 — L1246
 *   identify_rggb_or_gbrg     — L1285
 *   identify_bright_and_dark_fields — L1357
 *
 * Behaviour is line-for-line faithful to the originals; only the
 * surface is modernized (BlendContext parameters, no module globals,
 * thread-local LUTs, std::vector instead of malloc/free).
 */

#include "detect.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <vector>

namespace dualiso::lib {

namespace {

/**
 * Build / return a thread-local log2 LUT keyed on the given black level.
 * Rebuilds only when [black] changes — cheap amortized cost for the
 * common case of identical-camera batch processing.
 */
const double* raw2ev_lut(int black) {
    static thread_local std::array<double, 16384> lut{};
    static thread_local int cached_black = -1;
    if (cached_black != black) {
        for (int i = 0; i < 16384; ++i) {
            const int v = std::max(1, i - black);
            lut[i] = std::log2(static_cast<double>(v));
        }
        cached_black = black;
    }
    return lut.data();
}

} // anon

bool hdr_check(const BlendContext& ctx) noexcept {
    if (ctx.bayer == nullptr) return false;
    if (ctx.width <= 4 || ctx.height <= 4) return false;
    if (ctx.white_level <= ctx.black_level + 1) return false;

    // cr2hdr ignores the last half-stop (white-level isn't precisely known
    // at this point in the pipeline). Mirror exactly so the threshold
    // stays calibrated.
    const double scaled_white =
        (ctx.white_level - ctx.black_level) * 0.707 + ctx.black_level;

    const double* raw2ev = raw2ev_lut(ctx.black_level);

    double avg_ev = 0.0;
    long   num    = 0;
    const int w = ctx.width;
    const int h = ctx.height;
    const int black_noise_floor = ctx.black_level + 32;

    for (int y = 2; y < h - 2; ++y) {
        const uint16_t* row    = ctx.bayer + y * w;
        const uint16_t* row_p2 = ctx.bayer + (y + 2) * w;
        for (int x = 2; x < w - 2; ++x) {
            const int p  = row[x]    & 0x3FFF;
            const int p2 = row_p2[x] & 0x3FFF;
            // Need at least one pixel above noise AND both below clipping.
            if ((p > black_noise_floor || p2 > black_noise_floor) &&
                p < scaled_white && p2 < scaled_white) {
                avg_ev += std::fabs(raw2ev[p2] - raw2ev[p]);
                ++num;
            }
        }
    }
    if (num <= 0) return false;
    avg_ev /= static_cast<double>(num);
    return avg_ev > 0.5;
}

bool identify_pattern(BlendContext& ctx) noexcept {
    if (ctx.bayer == nullptr || ctx.width <= 4 || ctx.height <= 4) {
        return false;
    }

    const int w = ctx.width;
    const int h = ctx.height;

    // Four histograms — one per (y%2, x%2) bucket. cr2hdr allocates these
    // as `int[4][16384]`. We use std::vector<int> for automatic cleanup
    // and a flat layout (avoids the four malloc/free pairs of the original).
    std::vector<int> hist_storage(4 * 16384, 0);
    int* hist[4] = {
        hist_storage.data() + 0 * 16384,
        hist_storage.data() + 1 * 16384,
        hist_storage.data() + 2 * 16384,
        hist_storage.data() + 3 * 16384,
    };

    // Round y0 down to a multiple of 4, mirroring `(y1 + 3) & ~3`.
    const int y0 = (ctx.active.y1 + 3) & ~3;
    const int y_end = (h / 4) * 4;
    for (int y = y0; y < y_end; ++y) {
        const uint16_t* row = ctx.bayer + y * w;
        const int yb = (y % 2) * 2;
        for (int x = 0; x < w; ++x) {
            const int v = row[x] & 0x3FFF;
            ++hist[yb + (x % 2)][v];
        }
    }

    // CDFs in place.
    for (int k = 0; k < 4; ++k) {
        int acc = 0;
        for (int i = 0; i < 16384; ++i) {
            acc += hist[k][i];
            hist[k][i] = acc;
        }
    }

    // Compare the two interpretations:
    //   RGGB → greens are at (y%2 != x%2), buckets 1 and 2
    //   GBRG → greens are at (y%2 == x%2), buckets 0 and 3
    // The greens always agree more than R/B does, so whichever pair has
    // smaller CDF differences is the correct interpretation.
    long diffs_rggb = 0;
    long diffs_gbrg = 0;
    for (int i = 0; i < 16384; ++i) {
        diffs_rggb += std::abs(hist[1][i] - hist[2][i]);
        diffs_gbrg += std::abs(hist[0][i] - hist[3][i]);
    }

    ctx.pattern = (diffs_rggb < diffs_gbrg)
        ? BayerPattern::RGGB
        : BayerPattern::GBRG;
    return true;
}

bool identify_fields(BlendContext& ctx) noexcept {
    // Faithful port of cr2hdr.c L1357–1522 `identify_bright_and_dark_fields`.
    //
    // Builds four histograms — one per mod-4 row group, restricted to
    // the GREEN pixels (where x%2 != y%2). Then walks the histograms
    // simultaneously, accumulating counts, until two of them reach a
    // "clipping" reference. The non-clipping pair is the dark ISO; the
    // clipping pair is the bright ISO.
    //
    // White level is unknown at this stage of the pipeline — cr2hdr
    // uses a rough guess of 10000. We mirror that exactly.
    if (ctx.bayer == nullptr || ctx.width <= 4 || ctx.height <= 4) {
        ctx.last_error = "identify_fields: invalid bayer dimensions";
        return false;
    }

    const int w     = ctx.width;
    const int h     = ctx.height;
    const int black = ctx.black_level;
    const int white = 10000;          // cr2hdr L1378 — fixed guess at this stage

    // Four histograms, one per (y % 4). cr2hdr lays them out as four
    // separate malloc'd 16384-int arrays; we use a contiguous
    // std::vector for automatic cleanup. Layout matches cr2hdr's
    // [bucket][value] indexing.
    std::vector<int> hist_storage(4 * 16384, 0);
    int* hist[4] = {
        hist_storage.data() + 0 * 16384,
        hist_storage.data() + 1 * 16384,
        hist_storage.data() + 2 * 16384,
        hist_storage.data() + 3 * 16384,
    };

    // y0 rounded up to a multiple of 4 — keeps the four buckets sampled
    // from balanced row groups (otherwise bucket 0 might see one extra
    // row at the top edge).
    const int y0    = (ctx.active.y1 + 3) & ~3;
    const int y_end = (h / 4) * 4;
    for (int y = y0; y < y_end; ++y) {
        const uint16_t* row = ctx.bayer + y * w;
        const int yb = y % 4;
        const int y_parity = y % 2;
        for (int x = 0; x < w; ++x) {
            // Restrict to green pixels (cr2hdr L1400):
            //   x%2 != y%2 → green in both RGGB and GBRG bayers.
            if ((x % 2) != y_parity) {
                ++hist[yb][row[x] & 0x3FFF];
            }
        }
    }

    // Total samples per bucket should be equal (we sampled the same
    // number of rows). Use bucket 0's total as the reference.
    int hist_total = 0;
    for (int i = 0; i < 16384; ++i) {
        hist_total += hist[0][i];
    }
    if (hist_total <= 0) {
        ctx.last_error = "identify_fields: empty green histogram";
        return false;
    }

    // The high-water-mark walk (cr2hdr L1421–1462).
    //
    //   acc[i] : running cumulative count for bucket i
    //   raw[i] : the bayer value where acc[i] caught up to ref
    //   off[i] : black-offset estimate captured at the 5% percentile
    //   ref    : target count we're chasing
    //   ref_max: 99.8th percentile (cr2hdr keeps a hair of robustness
    //            against specular highlights)
    //   ref_off: 5th percentile (used to capture per-bucket black)
    //
    // We advance bucket-wise until at least two buckets hit the white
    // ceiling — those are the bright (clipping) ISO; the others are
    // the dark ISO that hasn't reached clipping yet.
    int acc[4] = {0, 0, 0, 0};
    int raw[4] = {0, 0, 0, 0};
    int off[4] = {0, 0, 0, 0};
    const int ref_max = static_cast<int>(hist_total * 0.998);
    const int ref_off = static_cast<int>(hist_total * 0.05);

    for (int ref = 0; ref < ref_max; ++ref) {
        for (int i = 0; i < 4; ++i) {
            while (acc[i] < ref && raw[i] < 16384) {
                acc[i] += hist[i][raw[i]];
                ++raw[i];
            }
        }
        if (ref < ref_off) {
            // While we're still in the bottom 5%, snapshot the current
            // raw values as the black offset estimate. cr2hdr stops
            // refining the offset once any bucket climbs above
            // black + (white-black)/4 — past that point we're no
            // longer measuring black, we're measuring real signal.
            const int mx = std::max(std::max(raw[0], raw[1]),
                                    std::max(raw[2], raw[3]));
            if (mx < black + (white - black) / 4) {
                off[0] = raw[0];
                off[1] = raw[1];
                off[2] = raw[2];
                off[3] = raw[3];
            }
        }
        // Stop when at least two of the four buckets have hit white.
        const int hit_white =
            (raw[0] >= white ? 1 : 0) +
            (raw[1] >= white ? 1 : 0) +
            (raw[2] >= white ? 1 : 0) +
            (raw[3] >= white ? 1 : 0);
        if (hit_white >= 2) break;
    }

    // Subtract the black offsets we observed (cr2hdr L1481–1484).
    for (int i = 0; i < 4; ++i) raw[i] -= off[i];

    // Crude median of four values via 2-pass swap (cr2hdr L1487–1502).
    // Returns the mean of the two middle values.
    int sorted[4] = {raw[0], raw[1], raw[2], raw[3]};
    for (int i = 0; i < 4; ++i) {
        for (int j = i + 1; j < 4; ++j) {
            if (sorted[i] > sorted[j]) std::swap(sorted[i], sorted[j]);
        }
    }
    const double median_bright = (sorted[1] + sorted[2]) / 2.0;

    // Each bucket above the median is BRIGHT (high-ISO) — it had more
    // signal so reached clipping faster.
    for (int i = 0; i < 4; ++i) {
        ctx.is_bright[i] = (raw[i] > median_bright) ? 1 : 0;
    }

    // Validate: exactly two of the four mod-4 row groups should be
    // bright, exactly two dark. Otherwise the pattern doesn't match
    // ML's dual-iso interlace and we should bail.
    const int bright_count = ctx.is_bright[0] + ctx.is_bright[1] +
                             ctx.is_bright[2] + ctx.is_bright[3];
    if (bright_count != 2) {
        ctx.last_error = "identify_fields: bright/dark detection error "
                         "(expected 2 bright groups, got " +
                         std::to_string(bright_count) + ")";
        // Reset to a safe default so callers don't act on garbage.
        ctx.is_bright = {1, 1, 0, 0};
        return false;
    }

    // ML's interlace alternates row-PAIRS. So bucket (y%4 == 0) and
    // bucket (y%4 == 2) MUST be in opposite groups (one bright, one
    // dark); same for buckets 1 and 3. cr2hdr enforces this and bails
    // when violated as "interlacing method not supported".
    if (ctx.is_bright[0] == ctx.is_bright[2] ||
        ctx.is_bright[1] == ctx.is_bright[3]) {
        ctx.last_error = "identify_fields: interlacing method not supported "
                         "(bright/dark are not in alternating pairs)";
        ctx.is_bright = {1, 1, 0, 0};
        return false;
    }

    return true;
}

} // namespace dualiso::lib
