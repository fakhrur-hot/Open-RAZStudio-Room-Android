/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Thermal Noise Boost for Long Exposures.
 *
 * Adds noise-reduction boost on top of the ISO-derived NR curve for long
 * exposures that generate thermal dark-current noise. Thermal noise is
 * independent of ISO — it grows with exposure duration (sensor heating),
 * so even a low-ISO long exposure accumulates noise not captured by the
 * ISO-based NR curve alone.
 *
 * Boost is proportional to log2(shutterSpeed) in seconds:
 *   • Luma boost  = min(20, 6 × log2(shutter))
 *   • Chroma boost = min(15, 4 × log2(shutter))
 *
 * Final combined values (ISO NR + thermal boost) are clamped to [0..100].
 * Shutter ≤ 1s produces no thermal boost — the ISO curve is sufficient.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import kotlin.math.ln
import kotlin.math.min

/**
 * Thermal noise reduction boost for long-exposure CR2 frames.
 *
 * Adds NR on top of [MLNoiseCurve] values when shutter speed exceeds 1 second.
 * The boost is additive and proportional to log2 of the exposure duration,
 * capped at +20 luma / +15 chroma, with final values clamped to [0..100].
 *
 * Slots affected: [147] luminanceNR, [148] colorNR.
 */
object MLThermalNoise {

    /** ln(2) constant for log2 conversion. */
    private const val LN2 = 0.6931471805599453f

    /**
     * Compute thermal-boosted NR values for the given exposure parameters.
     *
     * @param shutterSpeed Exposure time in seconds from EXIF. Must be > 0.
     * @param iso ISO sensitivity (unused in current formula, reserved for
     *   future ISO×time interaction models).
     * @param baseNrLuma Existing ISO-curve luminance NR value [0..100].
     * @param baseNrChroma Existing ISO-curve color NR value [0..100].
     * @return Pair of (boostedLumaNR, boostedChromaNR) clamped to [0..100].
     *   Returns base values unchanged when shutterSpeed ≤ 1s.
     */
    fun boost(
        shutterSpeed: Float,
        iso: Int,
        baseNrLuma: Float,
        baseNrChroma: Float,
    ): Pair<Float, Float> {
        // No thermal boost for short exposures
        if (shutterSpeed <= 1f) return baseNrLuma to baseNrChroma

        // Thermal noise grows with exposure duration (dark current)
        // Boost proportional to log2 of shutter speed in seconds
        val logShutter = ln(shutterSpeed) / LN2

        val lumaBoost = min(20f, (6f * logShutter).coerceAtLeast(0f))
        val chromaBoost = min(15f, (4f * logShutter).coerceAtLeast(0f))

        val resultLuma = (baseNrLuma + lumaBoost).coerceIn(0f, 100f)
        val resultChroma = (baseNrChroma + chromaBoost).coerceIn(0f, 100f)

        return resultLuma to resultChroma
    }
}
