/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ETTR (Expose To The Right) bias model.
 *
 * Maps a scene light level (0–255) derived from the RAW histogram's
 * 90th-percentile green channel value to an exposure EV bias:
 *
 *   • Bright scenes (>200): pull back −0.5 EV to protect highlights.
 *   • Dark scenes  (<30):   push  +0.3 EV to shift the histogram right
 *                           (ETTR) without clipping.
 *   • Mid-range:            no bias (0 EV).
 *
 * Data provenance: light level is computed identically to Magic Lantern's
 * `compute_light_level()` — the 90th percentile of the green (G1+G2)
 * channel histogram from the Stage A linear decode.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

/**
 * Biases auto-exposure based on scene light level from RAW histogram.
 *
 * Light level is the 90th percentile of the green channel (0–255).
 */
object MLEttrBias {

    /**
     * Returns the ETTR-informed exposure bias in EV for the given [lightLevel].
     *
     * @param lightLevel 0–255 value derived from RAW histogram 90th percentile
     *                   (green channel).
     * @return EV bias: −0.5 for bright scenes, +0.3 for dark scenes, 0 otherwise.
     */
    fun forLightLevel(lightLevel: Int): Float = when {
        lightLevel > 200 -> -0.5f   // protect highlights
        lightLevel < 30  -> +0.3f   // push ETTR right
        else             -> 0f
    }
}
