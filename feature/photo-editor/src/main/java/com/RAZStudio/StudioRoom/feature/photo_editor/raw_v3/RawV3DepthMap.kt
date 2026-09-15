/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Relative depth map from Depth-Anything-V2-Small (Apache-2.0 weights).
 * Values are normalised to [0, 1] (far → near or near → far — consumers
 * treat this as relative disparity for CoC; subject mask overrides CoC=0).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

data class RawV3DepthMap(
    /** Relative depth in [0, 1]. Row-major, [width] x [height]. */
    val depth: FloatArray,
    val width: Int,
    val height: Int,
    /** Model asset that produced this map (for diagnostics / attribution). */
    val modelAsset: String = RawV3DepthProcessor.MODEL_ASSET,
) {
    val isEmpty: Boolean get() = depth.isEmpty() || width <= 0 || height <= 0

    companion object {
        val EMPTY = RawV3DepthMap(FloatArray(0), 0, 0)
    }
}
