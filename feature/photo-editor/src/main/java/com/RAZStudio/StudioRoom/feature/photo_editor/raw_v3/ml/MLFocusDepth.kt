/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Focus-Distance Depth-Aware Processing.
 *
 * Maps focus distance + aperture + focal length to depth-of-field-aware
 * sharpening and background smoothing defaults. Uses the hyperfocal
 * distance formula with Canon full-frame circle of confusion (0.029mm)
 * to determine whether the shot has shallow or deep depth of field.
 *
 * Shallow DoF (wide aperture, close focus) → increase smoothBackground
 * Deep DoF (small aperture, far focus)    → increase smartSharpness
 * Missing/invalid data                    → return (0, 0) — no change
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

/**
 * Focus-distance depth-aware sharpening/smoothing computation.
 *
 * Uses EXIF focus distance, aperture, and focal length to estimate
 * depth of field and produce appropriate smart defaults for background
 * smoothing (shallow DoF) or detail sharpening (deep DoF).
 */
object MLFocusDepth {

    /**
     * Circle of confusion for Canon full-frame sensor (mm).
     */
    private const val COC = 0.029f

    /**
     * Compute depth-aware sharpening and smoothing values.
     *
     * @param focusDistanceNear Near focus distance in meters from EXIF (0 = unknown).
     * @param focusDistanceFar Far focus distance in meters from EXIF.
     * @param aperture F-number from EXIF (e.g. 2.8, 5.6, 11.0).
     * @param focalLength Focal length in mm from EXIF.
     * @return [FocusDepthResult] with smartSharpness [0..100] and smoothBackground [0..100].
     */
    fun compute(
        focusDistanceNear: Float,
        focusDistanceFar: Float,
        aperture: Float,
        focalLength: Float,
    ): FocusDepthResult {
        // No focus data or invalid parameters — skip
        if (focusDistanceNear <= 0f || aperture <= 0f || focalLength <= 0f) {
            return FocusDepthResult(0f, 0f)
        }

        // Convert focus distance from meters to mm
        val focusDist = focusDistanceNear * 1000f

        // Hyperfocal distance (mm):
        // H = (f² / (N × CoC)) + f
        val hyperfocal = (focalLength * focalLength) / (aperture * COC) + focalLength

        // Depth of field ratio:
        // 0 = infinite DoF (at or beyond hyperfocal → everything sharp)
        // 1 = razor-thin DoF (very close focus, well inside hyperfocal)
        val dofRatio = if (focusDist >= hyperfocal) {
            0f  // at or beyond hyperfocal → everything sharp
        } else {
            1f - (focusDist / hyperfocal)
        }.coerceIn(0f, 1f)

        // Shallow DoF → smooth background (proportional to dofRatio)
        // Deep DoF → smart sharpness (proportional to 1 - dofRatio)
        val smoothBackground = (dofRatio * 50f).coerceIn(0f, 100f)
        val smartSharpness = ((1f - dofRatio) * 25f).coerceIn(0f, 100f)

        return FocusDepthResult(smartSharpness, smoothBackground)
    }
}
