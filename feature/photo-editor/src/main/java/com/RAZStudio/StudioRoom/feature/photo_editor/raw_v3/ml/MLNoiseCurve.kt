/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — ISO-Aware Noise Reduction Curve.
 *
 * Maps ISO sensitivity to sensible luminance and color noise-reduction
 * starting values based on Canon sensor characterization. The step curve
 * is derived from Magic Lantern sensor data applicable across DIGIC 4/5/6
 * sensors (Canon EOS bodies running ML firmware).
 *
 * These values serve as *overridable defaults*: they seed the NR sliders
 * when no user-specified NR adjustment exists. The user can always dial
 * them down or disable via the Smart Defaults workspace toggle.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

/**
 * ISO → (luminanceNR, colorNR) step curve for Canon sensors.
 *
 * Returns a [Pair] of (luminance noise reduction strength, color noise
 * reduction strength) as floats in the range [0..100]. Higher ISO values
 * produce more aggressive NR defaults to counteract sensor noise.
 */
object MLNoiseCurve {

    /**
     * Look up noise-reduction defaults for the given ISO value.
     *
     * @param iso The ISO sensitivity from CR2 EXIF metadata.
     * @return Pair of (luminanceNR, colorNR) strength values.
     */
    fun forIso(iso: Int): Pair<Float, Float> = when {
        iso <= 100  -> 0f to 0f
        iso <= 200  -> 0f to 0f
        iso <= 400  -> 5f to 3f
        iso <= 800  -> 12f to 8f
        iso <= 1600 -> 22f to 15f
        iso <= 3200 -> 35f to 25f
        iso <= 6400 -> 50f to 38f
        else        -> 65f to 50f   // ISO 12800+
    }
}
