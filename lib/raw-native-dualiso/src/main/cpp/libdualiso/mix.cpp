/*
 * libdualiso — half-res mix + chroma_smooth (implementation).
 * Licensed under the GNU General Public License v2 or later.
 *
 * Derived from:
 *   cr2hdr.c          L2793–2848  — build_mix_curve + half_res_mix
 *   chroma_smooth.c   full file   — chroma_smooth (3 kernels)
 */

#include "mix.hpp"
#include "optmed.hpp"

#include <algorithm>
#include <cmath>

namespace dualiso::lib {

namespace {
inline int coerce(int v, int lo, int hi) noexcept {
    return std::max(lo, std::min(hi, v));
}
inline double coerce_d(double v, double lo, double hi) noexcept {
    return std::max(lo, std::min(hi, v));
}
} // anon

std::unique_ptr<double[]> build_mix_curve(const BlendContext& ctx,
                                           double overlap_ev) noexcept {
    auto curve = std::unique_ptr<double[]>(new (std::nothrow) double[kRaw2EvRange]);
    if (!curve) return nullptr;

    const int black = ctx.black_level_work;
    const int white = ctx.white_level_work;
    if (white <= black) return curve;   // degenerate, return zeros

    // cr2hdr.c L2794: max_ev = log2(white/64 - black/64)
    const double max_ev = std::log2(
        static_cast<double>(white) / 64.0 - static_cast<double>(black) / 64.0);
    if (overlap_ev <= 0.0) {
        // Caller should have already detected this — but be safe and
        // emit all-zero so the half-res mix just outputs bright[].
        std::fill_n(curve.get(), kRaw2EvRange, 0.0);
        return curve;
    }
    const double inv_overlap_pi = M_PI / overlap_ev;
    const double black_d_64 = static_cast<double>(black) / 64.0;
    const double crossover_start = max_ev - overlap_ev;

    for (int i = 0; i < kRaw2EvRange; ++i) {
        // L2799: ev = log2(max(i/64 - black/64, 1)) + corr_ev
        const double ev_at_i = std::log2(std::max(
            static_cast<double>(i) / 64.0 - black_d_64, 1.0)) + ctx.corr_ev;
        const double t = coerce_d(ev_at_i - crossover_start, 0.0, overlap_ev);
        const double c = -std::cos(t * inv_overlap_pi);
        curve[i] = (c + 1.0) * 0.5;
    }
    return curve;
}

void half_res_mix(const uint32_t* dark_plane,
                  const uint32_t* bright_plane,
                  uint32_t* out,
                  int width, int height,
                  const EvLuts& luts,
                  const double* mix_curve) noexcept {
    if (!dark_plane || !bright_plane || !out || !mix_curve) return;
    const int* raw2ev = luts.raw2ev.get();
    const long n = static_cast<long>(width) * height;

    for (long i = 0; i < n; ++i) {
        const uint32_t b = bright_plane[i];
        const uint32_t d = dark_plane[i];
        const int bev = raw2ev[b & 0xFFFFF];
        const int dev = raw2ev[d & 0xFFFFF];

        // cr2hdr.c L2842: blend coefficient indexed by bright pixel.
        const double k = coerce_d(mix_curve[b & 0xFFFFF], 0.0, 1.0);

        // Linear mix in EV space, then convert back to raw via ev2raw.
        const int mixed = static_cast<int>(
            static_cast<double>(bev) * (1.0 - k) +
            static_cast<double>(dev) * k);
        out[i] = static_cast<uint32_t>(luts.ev2raw_clamped(mixed));
    }
}

// ── chroma_smooth ─────────────────────────────────────────────────
//
// One implementation handles all three kernels. The shape parameters
// (filter size, neighbourhood radius, median function) are switched
// at runtime — same approach the original took with the macro switch
// at chroma_smooth.c top, just resolved to a function-pointer-free
// inline switch since C++ optimises away the conditional in the hot
// loop body just as effectively.

namespace {

inline int median_for_kernel(int* p, int kernel) noexcept {
    switch (kernel) {
        case 2: return opt_med5(p);
        case 3: return opt_med9(p);
        case 5: return opt_med25(p);
        default: return p[0];
    }
}

// The 2x2 kernel skips corner samples (|i|+|j|==4) — cr2hdr's
// CHROMA_SMOOTH_2X2 #ifdef path. Returns true for "skip this offset".
inline bool skip_offset_for_kernel(int i, int j, int kernel) noexcept {
    if (kernel == 2) return (std::abs(i) + std::abs(j)) == 4;
    return false;
}

} // anon

void chroma_smooth(const uint32_t* in,
                   uint32_t* out,
                   int width, int height,
                   const EvLuts& luts,
                   int kernel) noexcept {
    if (!in || !out || kernel == 0) return;
    if (kernel != 2 && kernel != 3 && kernel != 5) return;

    const int max_ij = (kernel == 5) ? 4 : 2;
    const int* raw2ev = luts.raw2ev.get();
    const int w = width;
    const int h = height;

    // Storage for the per-pixel median collection. opt_med25 is the
    // largest filter so allocate up to 25 slots — actual fill depth
    // depends on the kernel and the 2x2 corner-skip rule.
    int med_r[25];
    int med_b[25];

    for (int y = 4; y < h - 5; y += 2) {
        for (int x = 4; x < w - 4; x += 2) {
            // Centre pixel's green EV — average of the two G slots in
            // the 2×2 RGGB cell. cr2hdr.c chroma_smooth.c L28–30.
            const int g1c = static_cast<int>(in[x + 1 + y * w]      & 0xFFFFF);
            const int g2c = static_cast<int>(in[x     + (y + 1) * w] & 0xFFFFF);
            const int ge_centre = (raw2ev[g1c] + raw2ev[g2c]) / 2;

            // cr2hdr drops the kernel in low-light zones — produces
            // ugly artefacts when there isn't real chroma to smooth.
            if (ge_centre < 2 * kEvResolution) continue;

            int k = 0;
            for (int j = -max_ij; j <= max_ij; j += 2) {
                for (int i = -max_ij; i <= max_ij; i += 2) {
                    if (skip_offset_for_kernel(i, j, kernel)) continue;
                    const int xx = x + i;
                    const int yy = y + j;
                    const int r  = static_cast<int>(in[xx     +   yy     * w] & 0xFFFFF);
                    const int g1 = static_cast<int>(in[xx + 1 +   yy     * w] & 0xFFFFF);
                    const int g2 = static_cast<int>(in[xx     +  (yy + 1) * w] & 0xFFFFF);
                    const int b  = static_cast<int>(in[xx + 1 +  (yy + 1) * w] & 0xFFFFF);
                    const int ge = (raw2ev[g1] + raw2ev[g2]) / 2;
                    med_r[k] = raw2ev[r] - ge;
                    med_b[k] = raw2ev[b] - ge;
                    ++k;
                }
            }
            const int dr = median_for_kernel(med_r, kernel);
            const int db = median_for_kernel(med_b, kernel);

            // L62–63 sanity check: if the median says the red/blue
            // channel would round-trip below 1 EV, leave the pixel
            // alone — likely a noisy low-signal area.
            if (ge_centre + dr <= kEvResolution) continue;
            if (ge_centre + db <= kEvResolution) continue;

            out[x     +     y * w] = static_cast<uint32_t>(
                luts.ev2raw_clamped(ge_centre + dr));
            out[x + 1 + (y + 1) * w] = static_cast<uint32_t>(
                luts.ev2raw_clamped(ge_centre + db));
        }
    }
}

} // namespace dualiso::lib
