/*
 * StudioRoom Magic-Lantern dual-ISO blend kernel
 *
 * Derived from cr2hdr.c
 *   Copyright (C) 2013 Magic Lantern Team
 *   Original: https://github.com/magiclantern/magic-lantern
 *   Forum:    https://www.magiclantern.fm/forum/index.php?topic=7139.0
 *
 * Android port:
 *   Copyright (C) 2026 RAZStudio (Fakhrurraze)
 *
 * This file is licensed under the GNU General Public License v2 or later,
 * matching the original cr2hdr source. See LICENSE-GPL2 in this module's
 * root for the full text.
 *
 * THIS PORT IS A WORK IN PROGRESS:
 *   - hdr_check (detector)  is FAITHFULLY PORTED from cr2hdr.c L1246.
 *   - blend_mean23 (kernel) is a STUB that returns the input unchanged.
 *     The full mean23 / amaze-edge pipeline will land in a follow-up
 *     with a validation harness against the desktop cr2hdr's output.
 *     Calling it today does not damage the bayer plane — it just
 *     doesn't fix the interlacing. The Stage 1 detector + UI surface
 *     above this layer continues to work; the user just sees the
 *     uncorrected dual-ISO frame until the blend lands.
 */

#include "dualiso_blend.h"
#include "libdualiso/blend.hpp"
#include "libdualiso/detect.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace dualiso {

namespace {
// Build a libdualiso BlendContext that views the externally-owned bayer
// buffer. The new library expects everything via the context; this thin
// adapter keeps the existing JNI signatures working while we migrate.
lib::BlendContext as_context(const uint16_t* bayer, int w, int h, int black, int white) {
    lib::BlendContext ctx;
    ctx.bayer = const_cast<uint16_t*>(bayer);
    ctx.width = w;
    ctx.height = h;
    ctx.black_level = black;
    ctx.white_level = white;
    return ctx;
}
} // anon

/**
 * Port of cr2hdr.c's hdr_check() (L1246–1284). Mean log2 brightness delta
 * between vertically-adjacent pixel pairs; dual-ISO frames have alternating
 * row pairs at different ISOs so the delta is large. Single-ISO frames
 * cluster near zero.
 *
 * Returns true when avg_ev > 0.5 — the exact threshold cr2hdr uses.
 *
 * The bayer buffer is expected as 16-bit values (right-shifted to 14-bit
 * range for typical Canon CR2s). black/white are the standard sensor
 * black and white levels in the same units. Buffer is read-only.
 */
bool hdr_check(const uint16_t* bayer, int width, int height, int black, int white) {
    // Delegate to the modernized libdualiso implementation. Same maths,
    // cleaner surface. The old inlined body has moved to libdualiso/detect.cpp.
    const lib::BlendContext ctx = as_context(bayer, width, height, black, white);
    return lib::hdr_check(ctx);
}

int detect_full(const uint16_t* bayer, int width, int height, int black, int white) {
    if (bayer == nullptr || width <= 4 || height <= 4) return -1;
    lib::BlendContext ctx = as_context(bayer, width, height, black, white);
    // Initialise active area to the full frame — we don't carry per-camera
    // active rectangles here. cr2hdr would normally read this from CR2
    // metadata; for the Stage-1 detection use case, full-frame is fine
    // because the histograms are stable against a few rows of edge bias.
    ctx.active = lib::ActiveArea{0, 0, width, height};

    int status = 0;
    if (lib::hdr_check(ctx))      status |= 0x01;
    if (lib::identify_pattern(ctx)) {
        if (ctx.pattern == lib::BayerPattern::RGGB) status |= 0x02;
    }
    if (lib::identify_fields(ctx)) status |= 0x04;
    // Pack is_bright[0..3] into bits 7..4 so the caller can recover
    // which mod-4 row groups were the bright (high-ISO) pair.
    for (int i = 0; i < 4; ++i) {
        if (ctx.is_bright[i]) status |= (1 << (4 + i));
    }
    return status;
}

/**
 * Run the full mean23 dual-ISO blend pipeline in place. Faithful
 * port of cr2hdr.c L2007+ orchestrator, restructured around
 * libdualiso::BlendContext so there's no global state.
 *
 * Returns true on a successful blend (bayer plane was rewritten).
 * Returns false on any failure — the bayer plane is restored to its
 * original contents by libdualiso::run_blend's RAII snapshot, so the
 * caller can safely fall back to single-ISO rendering.
 */
bool blend_mean23(uint16_t* bayer, int width, int height, int black, int white) {
    if (bayer == nullptr || width <= 4 || height <= 4) return false;
    lib::BlendContext ctx = as_context(bayer, width, height, black, white);
    ctx.active = lib::ActiveArea{0, 0, width, height};
    return lib::run_blend(ctx);
}

} // namespace dualiso
