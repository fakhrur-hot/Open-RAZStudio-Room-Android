/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Flash Mixed-Lighting Compensation.
 *
 * Detects flash + ambient light mismatch from EXIF metadata and computes
 * corrective WB/tint offsets and a shadow lift to counteract color casts:
 *
 *   • Flash + Tungsten (LightSource=3 or <3500K): WB=-8, tint=+3
 *   • Flash + Fluorescent (LightSource=2 or 3500–4500K): WB=-2, tint=-5
 *   • Flash + Daylight/Fine/Cloudy (LightSource∈{1,9,10} or 5000–6500K): zero
 *   • Flash + Shade (>6500K): WB=+3, tint=-1
 *   • No flash: zero offsets, zero shadow lift
 *
 * Shadow lift of 0.15 is always applied when flash fires, regardless of
 * ambient lighting type or Picture Style.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

/**
 * Computes flash mixed-lighting WB/tint compensation and shadow lift.
 *
 * The returned offsets are additive to the UserMacro WB and tint fields.
 * The orchestrator applies Picture Style bias scaling to WB/tint but not
 * to shadowLift.
 */
object MLFlashCompensation {

    // EXIF LightSource tag values
    private const val LIGHT_SOURCE_DAYLIGHT = 1
    private const val LIGHT_SOURCE_FLUORESCENT = 2
    private const val LIGHT_SOURCE_TUNGSTEN = 3
    private const val LIGHT_SOURCE_FINE_WEATHER = 9
    private const val LIGHT_SOURCE_CLOUDY = 10

    // Shadow lift constant applied whenever flash fires
    private const val FLASH_SHADOW_LIFT = 0.15f

    /**
     * Computes flash compensation offsets for mixed-lighting correction.
     *
     * @param flashFired Whether the flash was fired for this shot (from EXIF).
     * @param lightSource EXIF LightSource tag value (0 = unknown, 1 = daylight, etc.).
     * @param colorTemperature As-shot color temperature in Kelvin (from EXIF/Stage A).
     * @return [FlashCompResult] with WB offset, tint offset, and shadow lift.
     *         Zero offsets when no flash detected.
     */
    fun compute(
        flashFired: Boolean,
        lightSource: Int,
        colorTemperature: Int,
    ): FlashCompResult {
        // No flash → no compensation at all
        if (!flashFired) {
            return FlashCompResult(
                wbOffset = 0f,
                tintOffset = 0f,
                shadowLift = 0f,
            )
        }

        // Flash fired — always apply shadow lift
        val shadowLift = FLASH_SHADOW_LIFT

        // Determine ambient lighting type from LightSource tag first,
        // then fall back to color temperature ranges
        val (wbOffset, tintOffset) = classifyAmbient(lightSource, colorTemperature)

        return FlashCompResult(
            wbOffset = wbOffset,
            tintOffset = tintOffset,
            shadowLift = shadowLift,
        )
    }

    /**
     * Classifies ambient light type and returns (wbOffset, tintOffset) pair.
     *
     * Priority: EXIF LightSource tag takes precedence over color temperature.
     * Falls through to color temperature ranges when LightSource is unknown (0)
     * or not one of the recognized values.
     */
    private fun classifyAmbient(lightSource: Int, colorTemperature: Int): Pair<Float, Float> {
        // Check EXIF LightSource tag first (takes precedence)
        when (lightSource) {
            LIGHT_SOURCE_TUNGSTEN -> return -8f to 3f
            LIGHT_SOURCE_FLUORESCENT -> return -2f to -5f
            LIGHT_SOURCE_DAYLIGHT,
            LIGHT_SOURCE_FINE_WEATHER,
            LIGHT_SOURCE_CLOUDY -> return 0f to 0f
        }

        // Fall back to color temperature classification
        return when {
            colorTemperature < 3500 -> -8f to 3f              // Tungsten
            colorTemperature in 3500..4500 -> -2f to -5f      // Fluorescent
            colorTemperature in 5000..6500 -> 0f to 0f        // Daylight
            colorTemperature > 6500 -> 3f to -1f              // Shade
            else -> 0f to 0f                                   // Unknown / gap (4501–4999K)
        }
    }
}
