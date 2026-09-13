/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — White Balance Scene Table.
 *
 * Maps CR2 EXIF ColorTemperature (Kelvin) to WB temperature bias + tint
 * bias offsets. These biases nudge the auto-WB starting point toward the
 * scene's lighting character when the camera reports a valid ColorTemperature
 * in the Canon MakerNote.
 *
 * Data provenance: Kelvin-to-bias mapping derived from Magic Lantern 6D
 * white balance intelligence. The scene groupings (tungsten, fluorescent,
 * daylight, shade) follow standard illuminant boundaries, with bias
 * magnitudes calibrated against ML_6D AWB solver output on Canon EOS bodies.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

/**
 * Seeds auto-WB starting point based on CR2's as-shot Kelvin.
 *
 * Maps color-temperature ranges to a WB temperature bias + tint bias pair.
 * Used by [applyMLDefaults()] to nudge the white-balance toward the scene's
 * lighting character when the camera reports a valid ColorTemperature in EXIF.
 *
 * Ranges follow the standard illuminant groupings:
 *   • 0 K         → no bias (unknown / missing data)
 *   • < 3500 K    → tungsten (warm bias)
 *   • 3500–4499 K → fluorescent (mild warm bias)
 *   • 4500–6000 K → daylight (neutral)
 *   • 6001–7500 K → shade (mild cool bias)
 *   • > 7500 K    → deep shade (cool bias)
 *
 * Return value: Pair(temperatureBias, tintBias) — additive offsets applied
 * to the WB multipliers in the smart-defaults UserMacro.
 */
object MLWbSceneTable {

    /**
     * Returns (temperatureBias, tintBias) for the given as-shot [kelvin].
     *
     * @param kelvin Color temperature from CR2 EXIF (Canon MakerNote).
     *               0 means the value was absent or unreadable.
     * @return Pair of additive bias floats for WB temperature and tint.
     */
    fun forKelvin(kelvin: Int): Pair<Float, Float> = when {
        kelvin == 0          -> 0f to 0f       // unknown / missing
        kelvin < 3500        -> 0.08f to 0f    // tungsten
        kelvin < 4500        -> 0.04f to 0f    // fluorescent
        kelvin in 4500..6000 -> 0f to 0f       // daylight (neutral)
        kelvin in 6001..7500 -> -0.02f to 0f   // shade
        else                 -> -0.04f to 0f   // deep shade (>7500)
    }
}
