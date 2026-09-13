/*
 * libdualiso — top-level blend driver (implementation).
 * Licensed under the GNU General Public License v2 or later.
 *
 * Orchestrates the mean23 dual-ISO pipeline end to end. The order
 * mirrors cr2hdr's hdr_interpolate (L2007 onwards): detection, then
 * 14→20 promotion, then exposure matching, then interpolation +
 * half-res mix + chroma_smooth + write-back.
 */

#include "blend.hpp"
#include "detect.hpp"
#include "ev_luts.hpp"
#include "interpolate.hpp"
#include "match_exposures.hpp"
#include "mix.hpp"
#include "pipeline.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

#include <android/log.h>
#define BLEND_TAG "DualIsoBlend"
#define BLEND_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  BLEND_TAG, __VA_ARGS__)
#define BLEND_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  BLEND_TAG, __VA_ARGS__)

namespace dualiso::lib {

namespace {

/**
 * Snapshot the input bayer to a side buffer so we can restore on
 * failure. Sized w*h*uint16_t — ~50 MB on a 25 MP frame. Released
 * either when commit() is called (no restore needed) or at scope
 * exit (restore happens automatically).
 */
class BayerSnapshot {
public:
    BayerSnapshot(uint16_t* dst, int w, int h)
        : dst_(dst), n_(static_cast<size_t>(w) * h), data_(n_) {
        std::memcpy(data_.data(), dst, n_ * sizeof(uint16_t));
    }
    ~BayerSnapshot() {
        if (!committed_) {
            std::memcpy(dst_, data_.data(), n_ * sizeof(uint16_t));
        }
    }
    void commit() noexcept { committed_ = true; }
private:
    uint16_t* dst_;
    size_t n_;
    std::vector<uint16_t> data_;
    bool committed_ = false;
};

/**
 * Estimate `lowiso_dr` (the dark plane's effective dynamic range in
 * EV). cr2hdr.c L2198: `log2(white - black) - dark_noise_ev`.
 *
 * cr2hdr computes dark_noise_ev from a `compute_black_noise` pass over
 * the masked black-reference strip on the left/right sensor edges. We
 * don't have that pass yet, so use a conservative default: the 6D's
 * read noise at ISO 100 is ~1.5 DN in 14-bit, so log2(1.5) ≈ 0.58 EV.
 * Higher-ISO bodies have ~2–3 DN. Using 1.5 errs on the optimistic
 * side and gives slightly more midtone overlap — preferable for the
 * blend than the alternative.
 *
 * TODO: port compute_black_noise (cr2hdr.c ~L1862) for accuracy.
 */
constexpr double kDarkNoiseEvDefault = 0.58;

double estimate_lowiso_dr(const BlendContext& ctx) noexcept {
    const int black = ctx.black_level_work;
    const int white = ctx.white_level_work;
    if (white <= black + 1) return 1.0;
    return std::log2(static_cast<double>(white - black)) - kDarkNoiseEvDefault;
}

/**
 * Compute the half-res mix overlap (cr2hdr.c L2772–2784).
 *   overlap = lowiso_dr - corr_ev
 *   overlap -= min(3, overlap - 3)
 *
 * Returns the overlap value, or 0 when out of range — caller should
 * bail in that case (the file's ISO offset is too small for a useful
 * blend).
 */
double compute_overlap(const BlendContext& ctx) noexcept {
    const double lowiso = estimate_lowiso_dr(ctx);
    double overlap = lowiso - ctx.corr_ev;
    overlap -= std::min(3.0, overlap - 3.0);
    if (overlap < 0.5) return 0.0;  // cr2hdr L2781 — overlap error
    return overlap;
}

/**
 * Write the half-res result back to the 14-bit bayer buffer (the
 * caller's input plane). The half-res plane is 20-bit; we shift right
 * by 4 to land in 16-bit and then by 2 more so the values fit in the
 * 14-bit-in-uint16_t representation Stage A expects. (Total >>6
 * recovers the original bit depth.)
 *
 * Black level / white level on the BlendContext are NOT modified —
 * caller's view of the buffer remains "14-bit values stored in
 * uint16_t with the top 2 bits clear", same as on input.
 */
void write_back_14bit(const uint32_t* halfres, BlendContext& ctx) noexcept {
    const int w = ctx.width;
    const int h = ctx.height;
    uint16_t* dst = ctx.bayer;
    const size_t n = static_cast<size_t>(w) * h;
    for (size_t i = 0; i < n; ++i) {
        const uint32_t v20 = halfres[i] & 0xFFFFF;
        // 20 → 14: shift right by 6.
        dst[i] = static_cast<uint16_t>((v20 >> 6) & 0x3FFF);
    }
}

} // anon

bool run_blend(BlendContext& ctx) noexcept {
    if (ctx.bayer == nullptr || ctx.width <= 4 || ctx.height <= 4) {
        ctx.last_error = "run_blend: invalid input";
        return false;
    }

    // Ensure active rect is set (full-frame default).
    if (ctx.active.x2 == 0 && ctx.active.y2 == 0) {
        ctx.active = ActiveArea{0, 0, ctx.width, ctx.height};
    }

    // Snapshot input — auto-restores on any early return / RAII path.
    BayerSnapshot snap(ctx.bayer, ctx.width, ctx.height);

    // 1. Detection passes.
    if (!hdr_check(ctx)) {
        ctx.last_error = "run_blend: hdr_check failed (not dual-ISO)";
        return false;
    }
    if (!identify_pattern(ctx)) {
        ctx.last_error = "run_blend: identify_pattern failed";
        return false;
    }
    if (!identify_fields(ctx)) {
        // identify_fields sets last_error itself.
        return false;
    }
    BLEND_LOGI("detection: pattern=%s is_bright=%d,%d,%d,%d",
        ctx.pattern == BayerPattern::RGGB ? "RGGB" : "GBRG",
        ctx.is_bright[0], ctx.is_bright[1], ctx.is_bright[2], ctx.is_bright[3]);

    // 2. White-detect — produces per-plane clipping levels (14-bit units).
    int white_bright_14 = 0;
    const int white_dark_14 = white_detect(ctx, white_bright_14);
    BLEND_LOGI("white levels (14-bit): dark=%d bright=%d", white_dark_14, white_bright_14);

    // 3. Promote to 20-bit working buffer.
    if (!promote_14_to_20(ctx)) {
        return false;
    }
    // cr2hdr.c L2042: white_dark and white_bright are also *64 to land
    // in the 20-bit domain.
    ctx.white_level_work = white_dark_14 * 64;
    ctx.white_darkened   = white_bright_14 * 64;

    // RAII: free work20 on any return.
    struct WorkFree {
        BlendContext& c;
        ~WorkFree() { free_work20(c); }
    } work_guard{ctx};

    // 4. Match exposures — linearises the bright plane to the dark scale.
    if (!match_exposures(ctx)) {
        BLEND_LOGW("match_exposures bailed: %s", ctx.last_error.c_str());
        return false;
    }
    BLEND_LOGI("corr_ev=%.3f white_darkened=%d", ctx.corr_ev, ctx.white_darkened);

    // 5. Build EV LUTs against the dark plane's working levels.
    auto luts = build_ev_luts(ctx.black_level_work, ctx.white_level_work);
    if (!luts) {
        ctx.last_error = "run_blend: LUT alloc failed";
        return false;
    }

    // 6. Allocate dark[] / bright[] planes for the interpolation output.
    const size_t n = static_cast<size_t>(ctx.width) * ctx.height;
    std::vector<uint32_t> dark_plane(n, 0);
    std::vector<uint32_t> bright_plane(n, 0);
    if (!interpolate_mean23(ctx, *luts, dark_plane.data(), bright_plane.data())) {
        ctx.last_error = "run_blend: interpolate_mean23 failed";
        return false;
    }

    // 7. Mix curve from the corrected corr_ev and estimated overlap.
    const double overlap_ev = compute_overlap(ctx);
    if (overlap_ev <= 0.0) {
        ctx.last_error = "run_blend: overlap too small (ISO difference unusable)";
        return false;
    }
    BLEND_LOGI("overlap_ev=%.3f", overlap_ev);

    auto mix_curve = build_mix_curve(ctx, overlap_ev);
    if (!mix_curve) {
        ctx.last_error = "run_blend: mix_curve alloc failed";
        return false;
    }

    // 8. Half-res mix — combine dark + bright through the curve.
    std::vector<uint32_t> halfres(n, 0);
    half_res_mix(dark_plane.data(), bright_plane.data(),
                 halfres.data(), ctx.width, ctx.height, *luts, mix_curve.get());

    // 9. Chroma smooth (default kernel = 2x2, matching cr2hdr default).
    if (ctx.chroma_smooth != ChromaSmooth::Off) {
        std::vector<uint32_t> smoothed = halfres;  // pre-copy: untouched
        // pixels keep their value when chroma_smooth skips dark regions.
        const int kernel = (ctx.chroma_smooth == ChromaSmooth::K5x5) ? 5 :
                           (ctx.chroma_smooth == ChromaSmooth::K3x3) ? 3 : 2;
        chroma_smooth(halfres.data(), smoothed.data(),
                      ctx.width, ctx.height, *luts, kernel);
        halfres = std::move(smoothed);
    }

    // 10. Write back to 14-bit bayer (the caller's input plane).
    write_back_14bit(halfres.data(), ctx);

    // Success — don't restore the snapshot.
    snap.commit();
    BLEND_LOGI("blend complete");
    return true;
}

} // namespace dualiso::lib
