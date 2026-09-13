/*
 * libdualiso — exposure-matching pass (implementation).
 * Licensed under the GNU General Public License v2 or later.
 *
 * Derived from cr2hdr.c (Magic Lantern Team, 2013) L1541–1796.
 * Behaviour is line-for-line faithful to the original; only the
 * surface is modernized (BlendContext + std::vector lifetime + no
 * module globals).
 *
 * Pipeline:
 *   1. Deinterlace into dark[] / bright[] helper planes (3×3 stride
 *      sample, 16-bit-equivalent units).
 *   2. Wirth median + percentiles on each plane.
 *   3. Pick highlights in (98th, 99.9th) percentile band as fit points.
 *   4. RANSAC-flavoured EV sweep: 3000 iterations from 0 to 6 EV,
 *      scoring each by how many highlight points satisfy the fit
 *      within ±50 raw units.
 *   5. Apply the best (a, b) linear transform to every bright-row
 *      pixel in work20.
 *
 * Memory profile:
 *   - dark[]  : w*h * sizeof(int)
 *   - bright[]: w*h * sizeof(int)
 *   - tmp[]   : (w+2)*(h+2)/9 * sizeof(int)
 *   - hi_*    : nmax/50 * 2 * sizeof(int)
 *   Total: ~200 MB on a 25 MP frame. Frees all before returning.
 */

#include "match_exposures.hpp"
#include "wirth.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace dualiso::lib {

namespace {
inline int coerce(int v, int lo, int hi) noexcept {
    return std::max(lo, std::min(hi, v));
}

/** cr2hdr's raw_get_pixel_20to16: read 32-bit pixel, return upper
 *  16 bits. Equivalent to dividing by 16. */
inline int read_20_to_16(const BlendContext& c, int x, int y) noexcept {
    return static_cast<int>((c.work20[x + y * c.width] >> 4) & 0xFFFF);
}

/** cr2hdr's raw_get_pixel32 read. */
inline uint32_t read_32(const BlendContext& c, int x, int y) noexcept {
    return c.work20[x + y * c.width];
}

/** cr2hdr's raw_set_pixel20: write 32-bit value, clamped to 20-bit range. */
inline void write_20(BlendContext& c, int x, int y, int v) noexcept {
    const int clamped = coerce(v, 0, 0xFFFFF);
    c.work20[x + y * c.width] = static_cast<uint32_t>(clamped);
}
} // anon

bool match_exposures(BlendContext& ctx) noexcept {
    if (ctx.work20 == nullptr) {
        ctx.last_error = "match_exposures: work20 not allocated "
                         "(call promote_14_to_20 first)";
        return false;
    }
    if (ctx.width <= 0 || ctx.height <= 0) return false;

    // ── Level domain conversion ───────────────────────────────────
    // cr2hdr.c L1544–1549. The blend operates on 16-bit-equivalent
    // values for the helper planes, even though the underlying buffer
    // is 20-bit. We use the same trick: divide work-domain levels by
    // 16 to get 16-bit-equivalent thresholds for the deinterlace.
    const int black20 = ctx.black_level_work;
    const int white20 = std::min(ctx.white_level_work, ctx.white_darkened);
    const int black   = black20 / 16;
    const int white   = white20 / 16;
    const int clip0   = white - black;
    const int clip    = static_cast<int>(clip0 * 0.95);  // 5% nonlinear-response margin

    const int w  = ctx.width;
    const int h  = ctx.height;
    const int y0 = ctx.active.y1 + 2;

    // ── Deinterlace into dark[] / bright[] ────────────────────────
    // L1556–1577. Sample every 3rd pixel in both axes. For each
    // bright row, the native pixel goes into bright[] and the
    // dark-side interpolant (mean of y-2, y+2) goes into dark[].
    // For each dark row, vice versa.
    //
    // Vectors are zero-initialised — cr2hdr explicitly memset's both
    // planes before the loop. Untouched entries stay zero, which is
    // important downstream (the "if (p == 0) continue;" guard at
    // L1762 relies on this).
    std::vector<int> dark(static_cast<size_t>(w) * h, 0);
    std::vector<int> bright(static_cast<size_t>(w) * h, 0);

    for (int y = y0; y < h - 2; y += 3) {
        const bool bright_row = (ctx.is_bright[y % 4] != 0);
        int* native = bright_row ? bright.data() : dark.data();
        int* interp = bright_row ? dark.data()   : bright.data();
        for (int x = 0; x < w; x += 3) {
            const int pa = read_20_to_16(ctx, x, y - 2) - black;
            const int pb = read_20_to_16(ctx, x, y + 2) - black;
            const int pn = read_20_to_16(ctx, x, y)     - black;
            int pi = (pa + pb + 1) / 2;
            // Bright neighbour above or below clipping? Discard
            // (use clip0 sentinel — handled downstream as ">= clip").
            if (pa >= clip || pb >= clip) pi = clip0;
            int pn_out = pn;
            if (pi >= clip) pn_out = clip0;
            interp[x + y * w] = pi;
            native[x + y * w] = pn_out;
        }
    }

    // ── Bright median + percentile range ─────────────────────────
    // L1590–1619. nmax sized for the 3×3 downsample.
    const int nmax = (w + 2) * (h + 2) / 9;
    std::vector<int> tmp;
    tmp.reserve(static_cast<size_t>(nmax));

    // Collect non-clipped bright samples for median.
    for (int y = y0; y < h - 2; y += 3) {
        for (int x = 0; x < w; x += 3) {
            const int b = bright[x + y * w];
            if (b >= clip) continue;
            tmp.push_back(b);
        }
    }
    if (tmp.empty()) {
        ctx.last_error = "match_exposures: no usable bright samples";
        return false;
    }
    int n = static_cast<int>(tmp.size());
    const int bmed = median_int_wirth(tmp.data(), n);

    // Percentile range for highlight selection. cr2hdr re-runs
    // kth_smallest on the SAME (now partitioned) array — each
    // kth_smallest call further partitions but doesn't invalidate
    // earlier results for the purpose of picking other percentiles.
    // We preserve that exact reuse pattern.
    const int b_lo = kth_smallest_int(tmp.data(), n,
        static_cast<int>(static_cast<long long>(n) * 98 / 100));
    const int b_hi = kth_smallest_int(tmp.data(), n,
        static_cast<int>(static_cast<long long>(n) * 999 / 1000));

    // ── Dark median ──────────────────────────────────────────────
    // L1622–1633. tmp is reused — clear and rebuild from non-clipped
    // dark samples paired with non-clipped bright samples (same
    // filter as the bright pass: skip wherever bright >= clip).
    tmp.clear();
    for (int y = y0; y < h - 2; y += 3) {
        for (int x = 0; x < w; x += 3) {
            const int d = dark[x + y * w];
            const int b = bright[x + y * w];
            if (b >= clip) continue;
            tmp.push_back(d);
        }
    }
    if (tmp.empty()) {
        ctx.last_error = "match_exposures: no usable dark samples";
        return false;
    }
    n = static_cast<int>(tmp.size());
    const int dmed = median_int_wirth(tmp.data(), n);

    // ── Highlight point selection ────────────────────────────────
    // L1646–1666. Cap at nmax/50 highlight points (~2% of the
    // downsampled samples). Break out of the loop as soon as we hit
    // the cap — mirrors cr2hdr exactly so the deterministic order
    // and result match.
    const int hi_nmax = nmax / 50;
    std::vector<int> hi_dark; hi_dark.reserve(static_cast<size_t>(hi_nmax));
    std::vector<int> hi_bright; hi_bright.reserve(static_cast<size_t>(hi_nmax));

    for (int y = y0; y < h - 2 && static_cast<int>(hi_dark.size()) < hi_nmax;
         y += 3) {
        for (int x = 0; x < w && static_cast<int>(hi_dark.size()) < hi_nmax;
             x += 3) {
            const int d = dark[x + y * w];
            const int b = bright[x + y * w];
            if (b >= b_hi) continue;
            if (b <= b_lo) continue;
            hi_dark.push_back(d);
            hi_bright.push_back(b);
        }
    }

    // ── RANSAC-flavoured EV sweep ────────────────────────────────
    // L1673–1694. For each candidate EV in [0, 6) at 0.002 increments,
    // build the linear fit through (bmed, dmed) with that slope and
    // count how many highlight points fall within ±50 raw units.
    // Pick the EV with the highest score.
    double a = 0.0;
    double b = 0.0;
    int best_score = 0;
    const int hi_n = static_cast<int>(hi_dark.size());

    for (double ev = 0.0; ev < 6.0; ev += 0.002) {
        const double test_a = std::pow(2.0, -ev);
        const double test_b = dmed - bmed * test_a;
        int score = 0;
        for (int i = 0; i < hi_n; ++i) {
            const int    d = hi_dark[i];
            const int    bv = hi_bright[i];
            const double e = static_cast<double>(d) - (bv * test_a + test_b);
            if (std::fabs(e) < 50.0) ++score;
        }
        if (score > best_score) {
            best_score = score;
            a = test_a;
            b = test_b;
        }
    }

    // ── Apply the correction in place ────────────────────────────
    // L1755–1780. Bright-row pixels are linearly mapped down toward
    // the dark-row scale. The non-zero guard at L1762 preserves
    // "discarded" pixels (which we mark as 0 in the helper planes —
    // but here we're walking the original work20 buffer, where 0
    // would only occur for genuinely-zero raw values, edge cases).
    // We mirror cr2hdr's check exactly.
    const double b20 = b * 16.0;
    for (int y = 0; y < h; ++y) {
        const bool bright_row = (ctx.is_bright[y % 4] != 0);
        for (int x = 0; x < w; ++x) {
            const uint32_t pu = read_32(ctx, x, y);
            if (pu == 0) continue;
            double p = static_cast<double>(pu);
            if (bright_row) {
                // Bright exposure: darken and apply the black offset.
                // cr2hdr comment: "fixme: why not half?"
                p = (p - black20) * a + black20 + b20 * a;
            } else {
                p = p - b20 + b20 * a;
            }
            write_20(ctx, x, y, static_cast<int>(p));
        }
    }

    // ── Update white_darkened ────────────────────────────────────
    // L1781. The bright-plane white level AFTER the linear transform.
    ctx.white_darkened = static_cast<int>(
        (white20 - black20 + b20) * a + black20);

    // ── Validate the fit ─────────────────────────────────────────
    // L1783–1789. If the inferred ISO factor is < 1.2× or not finite,
    // the file probably isn't interlaced — bail rather than apply a
    // bogus correction. (Note: the correction was ALREADY applied to
    // work20 by the loop above. The caller should treat a `false`
    // return as "discard work20, fall back to single-ISO render".)
    const double factor = 1.0 / a;
    if (a == 0.0 || !std::isfinite(factor) || factor < 1.2) {
        ctx.corr_ev = 0.0;
        ctx.last_error = "match_exposures: doesn't look like interlaced ISO "
                         "(factor=" + std::to_string(factor) + ")";
        return false;
    }

    ctx.corr_ev = std::log2(factor);
    return true;
}

} // namespace dualiso::lib
