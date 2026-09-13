/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Diffraction-Aware Compensatory Sharpening.
 *
 * Applies compensatory sharpening when the lens is stopped past the
 * diffraction limit for the sensor size. Beyond the diffraction limit,
 * the Airy disk diameter exceeds the pixel pitch, causing softening that
 * cannot be recovered by the lens — only post-process sharpening helps.
 *
 * Diffraction limit formula:
 *   limit = f/11 / cropFactor  (≈ f/11 FF, ≈ f/6.9 APS-C)
 *
 * Compensation ramps linearly past the limit:
 *   compensation = (aperture - limit) × 6, capped at 30
 *
 * Below the limit → 0 (no compensation needed).
 * Invalid inputs (aperture ≤ 0, cropFactor ≤ 0) → 0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import kotlin.math.min

/**
 * Diffraction-aware compensatory sharpening for stopped-down apertures.
 *
 * Computes a sharpening compensation value [0..30] written to ShaderParams
 * slot [406] (mlDiffractionComp). The compensation increases linearly once
 * the aperture exceeds the diffraction limit for the given sensor crop factor.
 *
 * Slot affected: [406] mlDiffractionComp.
 */
object MLDiffractionComp {

    /**
     * Compute diffraction compensation sharpening for the given lens parameters.
     *
     * @param aperture f-number from EXIF. Must be > 0 for valid result.
     * @param focalLength Focal length in mm from EXIF (reserved for future
     *   pixel-pitch-aware models; not used in current linear formula).
     * @param cropFactor Sensor crop factor: 1.0 for full-frame, 1.6 for
     *   Canon APS-C. Must be > 0 for valid result.
     * @return Diffraction compensation value clamped to [0..30].
     *   Returns 0 when aperture is at or below the diffraction limit,
     *   or when inputs are invalid (aperture ≤ 0, cropFactor ≤ 0).
     */
    fun compute(
        aperture: Float,
        focalLength: Float,
        cropFactor: Float,
    ): Float {
        // Guard against invalid inputs
        if (aperture <= 0f || cropFactor <= 0f) return 0f

        // Diffraction limit scales inversely with crop factor:
        // Full-frame (1.0) → f/11, APS-C (1.6) → f/6.875
        val diffractionLimit = 11.0f / cropFactor

        // Below or at limit — no compensation needed
        if (aperture <= diffractionLimit) return 0f

        // Linear ramp: 6 units per f-stop beyond the limit, capped at 30
        val compensation = (aperture - diffractionLimit) * 6f
        return min(30f, compensation)
    }
}
