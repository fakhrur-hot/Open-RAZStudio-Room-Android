/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Native port of RawV3Clahe.kt — tile-based CLAHE with sigmoid
 * shadows/highlights weighting. Operates in-place on a full-image pixel
 * buffer. Templated on the storage type so both Stage B (__fp16 AHB) and
 * Stage C (float export buffer) share one implementation.
 *
 * The math mirrors the Kotlin reference 1:1: BT.601 luma on the encoded
 * (gamma) values, 256 bins, clipLimit 2.0, a single sigmoid
 * (steepness 4, centred at 0.5) driving the per-pixel blend weight, and the
 * chroma-preserve branch (ratio scaling for luma > 4/255, additive offset
 * for deep shadows). No upper clamp — highlight headroom above 1.0 is kept.
 * Tile size is derived from image dimensions / tileCount so Stage B and
 * Stage C produce the same spatial frequency of local contrast regardless
 * of resolution, keeping saved files perceptually consistent with GL preview.
 */

#ifndef RAW_V3_CLAHE_H
#define RAW_V3_CLAHE_H

namespace raw_v3 {

/**
 * Apply CLAHE + sigmoid blend in place over [pixels].
 *
 * @tparam T               Pixel storage type: __fp16 (Stage B) or float (Stage C).
 * @param pixels           Interleaved pixel buffer, [strideInPixels * h] elements
 *                         of [channels] each. RGB read from channels 0,1,2; any
 *                         further channels (e.g. alpha) are left untouched.
 * @param w                Image width in pixels.
 * @param h                Image height in pixels.
 * @param strideInPixels   Row stride in pixels (>= w). Channel count is applied on top.
 * @param channels         Elements per pixel (4 for RGBA Stage B, 3 for RGB Stage C).
 * @param shadowsBoost     [-1..1]; + = CLAHE lift, - = darken shadows.
 * @param highlightsBoost  [-1..1]; + = CLAHE recover, - = mute highlights.
 * @param tileCount        Number of tiles along the longer image axis (default 8).
 *                         Fixed tile count ensures Stage B (512 px) and Stage C
 *                         (full-res) produce the same spatial frequency of local
 *                         contrast enhancement, keeping the saved file visually
 *                         consistent with the GL preview.
 */
template <typename T>
void applyClahe(
    T* pixels,
    int w,
    int h,
    int strideInPixels,
    int channels,
    float shadowsBoost,
    float highlightsBoost,
    int tileCount = 8);

} // namespace raw_v3

#endif // RAW_V3_CLAHE_H
