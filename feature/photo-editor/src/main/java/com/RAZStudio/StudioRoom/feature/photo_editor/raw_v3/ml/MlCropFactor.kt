/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Crop Factor Lookup Utility.
 *
 * Determines the sensor crop factor for a given Canon camera model
 * string. The crop factor is used by the diffraction compensation
 * module (MLDiffractionComp) to compute the diffraction limit for
 * the sensor size:
 *
 *   diffraction limit = f/11 / cropFactor
 *
 * Classification:
 *   Full-frame (crop 1.0): 6D, 5D series, 1DX, 1DC
 *   APS-C (crop 1.6):      7D, 70D, 80D, 90D, 77D, 750D, 760D,
 *                           800D, 200D, Rebel, Kiss
 *   APS-H (crop 1.3):      1D Mark III, 1D Mark IV
 *   Default:                1.0 (full-frame assumed for unknown models)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

/**
 * Returns the sensor crop factor for the given camera model string.
 *
 * Full-frame Canon bodies (6D, 5D, 1DX, 1DC) return 1.0.
 * APS-C Canon bodies (7D, 70D, 80D, Rebel, Kiss, etc.) return 1.6.
 * APS-H Canon bodies (1D Mark III/IV) return 1.3.
 * Unknown or non-Canon models default to 1.0.
 *
 * @param cameraModel The camera model string from EXIF (e.g. "Canon EOS 6D").
 * @return Crop factor: 1.0f, 1.3f, or 1.6f.
 */
fun cropFactorForModel(cameraModel: String): Float {
    val model = cameraModel.uppercase()
    return when {
        // APS-C Canon bodies
        model.contains("7D") || model.contains("70D") || model.contains("80D") ||
        model.contains("REBEL") || model.contains("KISS") || model.contains("750D") ||
        model.contains("760D") || model.contains("800D") || model.contains("200D") ||
        model.contains("77D") || model.contains("90D") -> 1.6f

        // APS-H Canon bodies
        model.contains("1D MARK III") || model.contains("1D MARK IV") ||
        model.contains("1D MK3") || model.contains("1D MK4") -> 1.3f

        // Full-frame Canon bodies (6D, 5D series, 1DX, 1DC) and default
        else -> 1.0f
    }
}
