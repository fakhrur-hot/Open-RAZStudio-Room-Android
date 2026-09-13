/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation

/**
 * Segmentation masks produced by U2Net and cached per RAW file.
 *
 * Both masks are stored at [MASK_SIZE]×[MASK_SIZE] (U2Net's native inference resolution).
 * Before applying to a full-resolution bitmap, bilinearly upscale to the target dimensions.
 *
 * [backgroundMask] is derived on-demand as (1 − subjectMask); it is not stored separately.
 */
data class RawSegmentationMasks(
    /**
     * Subject/foreground saliency — value per pixel in [0, 1].
     * 0 = pure background, 1 = pure subject/foreground.
     * Flat array, row-major, [MASK_SIZE] × [MASK_SIZE] elements.
     */
    val subjectMask: FloatArray,

    /**
     * Sobel edge magnitude — value per pixel in [0, 1].
     * 0 = flat region, 1 = sharp edge.
     * Flat array, row-major, [MASK_SIZE] × [MASK_SIZE] elements.
     */
    val edgeMask: FloatArray,

    /**
     * Optional guided-filter-refined matte. When present, callers (e.g. AE)
     * should prefer this over [subjectMask] for edge-sensitive operations —
     * it is computed at [REFINED_LONG_SIDE] long-side resolution against a
     * linear-light luma guide from Stage A, so edges snap to actual photo
     * boundaries instead of the 320² U2Net grid.
     *
     * Flat array, row-major. Width/height carried alongside in
     * [refinedWidth]/[refinedHeight] since the aspect matches the source.
     */
    val refinedMask: FloatArray? = null,
    val refinedWidth: Int = 0,
    val refinedHeight: Int = 0,
) {
    /**
     * Inverse of [subjectMask]. Allocates a new array on each access;
     * cache the result if calling repeatedly in a hot loop.
     */
    val backgroundMask: FloatArray
        get() = FloatArray(subjectMask.size) { i -> (1f - subjectMask[i]).coerceIn(0f, 1f) }

    /**
     * Normalized (x, y) centroid of the subject mask, in [0, 1] coordinates
     * relative to the mask bounds (which match the original image's aspect).
     * Computed as the alpha-weighted centroid: sum(x · alpha) / sum(alpha)
     * across both axes. Returns null when the subject mask has no signal
     * (total alpha below a tiny threshold) so callers can fall back to a
     * default like the geometric centre.
     *
     * Used by [com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawVignetteTab]
     * to auto-snap the vignette centre to the subject when the user first
     * dials Amount away from 0.
     */
    val subjectCenterNormalized: Pair<Float, Float>?
        get() {
            var sumX = 0.0
            var sumY = 0.0
            var sumW = 0.0
            var i = 0
            for (y in 0 until MASK_SIZE) {
                for (x in 0 until MASK_SIZE) {
                    val a = subjectMask[i].toDouble()
                    if (a > 0.0) {
                        sumX += x * a
                        sumY += y * a
                        sumW += a
                    }
                    i++
                }
            }
            if (sumW < 1.0) return null  // mask effectively empty
            val cx = (sumX / sumW / (MASK_SIZE - 1)).toFloat().coerceIn(0f, 1f)
            val cy = (sumY / sumW / (MASK_SIZE - 1)).toFloat().coerceIn(0f, 1f)
            return cx to cy
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawSegmentationMasks) return false
        return subjectMask.contentEquals(other.subjectMask) &&
               edgeMask.contentEquals(other.edgeMask)
    }

    override fun hashCode(): Int {
        var result = subjectMask.contentHashCode()
        result = 31 * result + edgeMask.contentHashCode()
        return result
    }

    companion object {
        /** Side length of stored masks — matches U2Net's input/output resolution. */
        const val MASK_SIZE = 320

        /**
         * Long-side of the guided-filter-refined mask. U2Net still runs at
         * [MASK_SIZE], but the refined matte is computed at this resolution
         * so edges land on real photo boundaries rather than the 320² grid.
         */
        const val REFINED_LONG_SIDE = 1024
    }
}
