/*
 * libdualiso — modernized port of cr2hdr.c
 * Copyright (C) 2013 Magic Lantern Team
 * Copyright (C) 2026 RAZStudio (Fakhrurraze)
 * Licensed under the GNU General Public License v2 or later.
 *
 * BlendContext replaces the static globals scattered across cr2hdr.c:
 *
 *     static int is_bright[4];        (L29)
 *     int interp_method = 0;          (L63)
 *     int chroma_smooth_method = 2;   (L64)
 *     int use_fullres = 1;            (L67)
 *     int use_alias_map = 1;          (L68)
 *     int use_stripe_fix = 1;         (L69)
 *     ... and ~30 more
 *
 * Threading them through a context makes the library re-entrant (the
 * desktop cr2hdr is single-shot per process; libdualiso must support
 * being called repeatedly from the same Android process) and removes
 * a major class of test-isolation bugs (a previous call's globals
 * silently affecting the next one).
 */

#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <string>

namespace dualiso::lib {

/**
 * Bayer-pattern offsets. The same names cr2hdr uses.
 */
enum class BayerPattern : uint8_t {
    Unknown = 0,
    RGGB,    // R at (0,0), G at (1,0)/(0,1), B at (1,1)
    GBRG,    // G at (0,0), B at (1,0), R at (0,1), G at (1,1)
};

/**
 * Interpolation strategy. cr2hdr exposes both as command-line flags.
 *  - Mean23 — fast nearest-pixel mean; what we port first.
 *  - AmazeEdge — uses the AMaZE demosaic at full-res to drive edge-aware
 *    interpolation. Higher quality, ~3× slower, much more code. Lands later.
 */
enum class Interpolation : uint8_t {
    Mean23 = 0,
    AmazeEdge = 1,
};

/**
 * Final chroma-smoothing pass kernel size. cr2hdr default is 2x2.
 */
enum class ChromaSmooth : uint8_t {
    Off = 0,
    K2x2 = 2,
    K3x3 = 3,
    K5x5 = 5,
};

/**
 * Active-area rectangle within the bayer buffer. cr2hdr's `raw_info`
 * carries this; many CR2s have a few rows of black-reference pixels
 * outside this rect that the blend must avoid.
 */
struct ActiveArea {
    int x1 = 0;
    int y1 = 0;
    int x2 = 0;   // exclusive
    int y2 = 0;   // exclusive
};

/**
 * Replacement for cr2hdr's `raw_info` global PLUS its pipeline-config
 * globals. One struct, no module state.
 *
 * Lifetime: caller owns the bayer buffer. libdualiso does NOT take
 * ownership; it operates in place on [bayer]. The pipeline allocates
 * an extra [work20] buffer (4× the size of bayer) during the blend;
 * that buffer is owned by the pipeline driver and freed before
 * `run()` returns. See blend.cpp for the lifecycle.
 */
struct BlendContext {
    // ── Bayer data (input) ────────────────────────────────────────
    // 14-bit values in 16-bit container (top 2 bits clear). Black and
    // white are in 14-bit units (Canon 6D defaults: 2048 / ~15000).
    uint16_t* bayer       = nullptr;
    int       width       = 0;
    int       height      = 0;
    int       black_level = 2048;       // 14-bit Canon default
    int       white_level = 15000;      // 14-bit Canon typical
    ActiveArea active{};

    // ── Working buffer (allocated mid-pipeline) ───────────────────
    // cr2hdr promotes bayer from 14-bit to a 20-bit working domain
    // (shifted left by 6) so interpolation has more headroom. The
    // promoted buffer is freshly allocated — it does NOT alias
    // [bayer]. black_level / white_level are scaled by 64 in the
    // 20-bit domain (cr2hdr.c L2034–2035: `raw_info.black_level *= 64;
    // raw_info.white_level *= 64`). Noise values scale by the same.
    uint32_t* work20             = nullptr;
    int       black_level_work   = 0;   // black_level * 64
    int       white_level_work   = 0;   // white_level * 64

    // ── Pattern + ISO pair ────────────────────────────────────────
    BayerPattern pattern = BayerPattern::Unknown;
    // is_bright[i] == 1 when row (y % 4 == i) is the bright (high-ISO)
    // pair, 0 when it's the dark (low-ISO) pair. Computed by
    // identify_fields() from a pre-pass on the bayer plane.
    std::array<uint8_t, 4> is_bright{0, 0, 0, 0};

    // ── Pipeline flags (mirror cr2hdr CLI options) ────────────────
    Interpolation interp        = Interpolation::Mean23;
    ChromaSmooth  chroma_smooth = ChromaSmooth::K2x2;
    bool use_fullres    = true;     // amaze-edge path only
    bool use_alias_map  = true;     // amaze-edge path only
    bool use_stripe_fix = true;
    bool fix_bad_pixels = true;
    bool fix_pink_dots  = false;

    // ── Exposure match output (filled by match_exposures) ─────────
    double corr_ev        = 0.0;    // EV difference between bright/dark
    int    white_darkened = 0;      // bright white scaled to dark domain

    // ── Diagnostics ───────────────────────────────────────────────
    std::string last_error;         // human-readable failure reason
    int         pixels_changed = 0; // populated by the blend step
};

/**
 * Helper — read a single bayer pixel by (x, y), masked to 14 bits.
 * Replaces cr2hdr's `raw_get_pixel16` (L464).
 */
inline int read_pixel(const BlendContext& c, int x, int y) noexcept {
    return c.bayer[x + y * c.width] & 0x3FFF;
}

/** Replaces cr2hdr's `raw_set_pixel16` (L471). */
inline void write_pixel(BlendContext& c, int x, int y, int v) noexcept {
    c.bayer[x + y * c.width] =
        static_cast<uint16_t>(v < 0 ? 0 : (v > 0xFFFF ? 0xFFFF : v));
}

} // namespace dualiso::lib
