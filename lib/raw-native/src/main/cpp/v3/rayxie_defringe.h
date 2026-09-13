// rayxie_defringe.h
//
// Second-stage chromatic-aberration defringe for the Stage-A FP16 buffer:
// subtract the local DEVIATION of each chroma difference from its median.
//
// ── Relationship to the existing Rayxie kernel ───────────────────────────────
// This does NOT replace it. The shipped RayXie29 port
// (`rayxie_correct_fringing`, raw_decoder.cpp) is an edge-span CLIP: it finds a
// green-gradient edge and clamps R-G / B-G inside the span to the values at the
// span's two endpoints. There is no smoothing kernel in it to upgrade. This is
// an independent residual pass that runs after the clip and catches what a span
// clip structurally cannot: edges without clean endpoints, and low-amplitude
// bleed spread across more than one span.
//
// ── Why a MEDIAN and not a guided filter ─────────────────────────────────────
// A guided filter guided by green was tried first and measured. It does not
// work for lateral CA, for a structural reason: the fringe is a residual
// excursion spatially ALIGNED with the green edge, so it correlates with the
// guide. The guided filter therefore reproduces the fringe in its own baseline
// and the correction has nothing left to subtract.
//
// Measured on a synthetic 3 px purple/green lateral fringe (chroma residual
// sqrt((R-G)^2+(B-G)^2), mean over a ±5 px band, radius 4, strength 0.8):
//
//     guide=green, eps=1e-4   -7.2 %      eps is the only thing that helps, and
//     guide=green, eps=4e-3  -12.2 %      it helps by DISABLING the guide:
//     guide=green, eps=1e-2  -16.6 %      a = cov/(var+eps) -> 0 as eps grows
//     guide=green, eps=0.2   -43.5 %      past var(I) (~0.073 across the step)
//     guide=green, eps=5     -46.5 %      i.e. it degenerates to a box mean
//     plain box mean, r=8    -53.1 %      ...which already beats it outright
//     MEDIAN, r=3..8         -54.2 %      and is flat across the whole range
//
// And on a genuine red|green colour edge — the halo test — the box-mean and
// guided baselines disturb pixels up to 8 px away from the boundary, while the
// median disturbs 0. That bleed IS the halo artifact. A median is the right
// primitive here because it has both properties this problem needs at once:
// it rejects a thin outlier (the fringe) AND preserves a step (real colour).
//
// The median's insensitivity to radius above the fringe width is a bonus: there
// is no radius to tune per lens, which is why no per-lens parameter table
// ships with this.
//
// ── Colour domain ────────────────────────────────────────────────────────────
// Operates directly in the Stage-A buffer's own domain: sRGB-encoded FP16 in
// [0,1]. It deliberately does NOT linearise. Every Stage B/C consumer and the
// Lensfun pass read this data as sRGB-encoded, so converting here would either
// corrupt them or force the same conversion to be mirrored in a dozen places.
// Chroma-difference defringing does not need linear light: R-G is a difference
// of like-encoded values and the correction is a small local subtraction, not a
// photometric operation.
//
// ── Preview = export parity (hard rule #1) ───────────────────────────────────
// Runs inside Stage A, so the result is baked into A.tif and BOTH the GL
// preview and the CPU export read the same corrected pixels. There is
// deliberately no GL shader version: a GPU-only CA pass would need an
// apply_macro.cpp mirror to stay in parity AND several full-resolution FP16
// render targets. Doing the work once at Stage A avoids the parity problem and
// the memory problem together.
//
// ── Memory ───────────────────────────────────────────────────────────────────
// Banded with a halo; peak working set is a function of width and band height
// only, never of megapixels.
#pragma once

#include <cstdint>

namespace raw_v3 {

struct RayxieDefringeParams {
    /** Correction amount, 0..1. 0 disables the pass. */
    float strength = 0.7f;
    /**
     * Median radius in pixels at 2560 px long side, scaled with the image and
     * then clamped to [kMinRadius, kMaxRadius]. The median only needs a window
     * WIDER than the fringe; past that the result is flat (see the table in the
     * file header), so this is a floor-and-ceiling, not a tuning knob.
     */
    int radius = 3;
    /**
     * How strongly to confine the correction to green-gradient edges. 1 = edges
     * only (where CA lives); 0 = everywhere, which also acts as a chroma
     * denoiser but can flatten genuinely fine colour detail.
     */
    float edgeGate = 0.85f;
    /**
     * Hard ceiling on any single channel's change, in [0,1] signal units. Real
     * CA is a small deviation; the backstop keeps a pathological neighbourhood
     * from repainting a pixel.
     */
    float maxDelta = 0.06f;
};

/**
 * Remove residual colour fringing in place on an sRGB-encoded RGBA FP16 buffer.
 *
 * For each pixel, with P = R-G (then B-G):
 *     m = separableMedian(P, radius)   — the fringe-free local baseline
 *     d = P - m                        — the fringe itself
 *     R' = R - clamp(strength * edgeWeight * d, ±maxDelta)
 *
 * The DEVIATION `d` is subtracted, never the baseline. Subtracting the baseline
 * instead — `R' = R - s*m` — collapses to R' = (1-s)R + s*G in any flat coloured
 * region, i.e. it drags R toward G and greys the image out at full strength.
 * That is the single most important line in this file, and the reason test T2
 * exists.
 *
 * Alpha is untouched. Multi-threaded. Returns true if any pixel changed.
 */
bool rayxie_defringe_f16(uint16_t* rgbaF16, int width, int height,
                         const RayxieDefringeParams& params);

}  // namespace raw_v3
