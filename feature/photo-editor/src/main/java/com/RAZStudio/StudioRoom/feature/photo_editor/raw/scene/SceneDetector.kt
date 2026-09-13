/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.scene

/**
 * Scene-detection result carrying both a human-readable preset label and
 * recommended UserMacro slider deltas.  All numeric fields are in the same
 * units used by the UserMacro sliders (saturation/vibrance in [-100..+100],
 * whiteBalance in Kelvin delta, tint in [-200..+200], CLAHE boosts in
 * [-1..+1]).  Defaults to no adjustment (flat raw look preserved).
 */
data class SceneDetectionResult(
    val presetName:                   String?  = null,
    val recommendedSaturation:        Float    = 0f,
    val recommendedVibrance:          Float    = 0f,
    val recommendedWhiteBalanceDelta: Int      = 0,
    val recommendedTintDelta:         Float    = 0f,
    val recommendedClaheShadowsBoost: Float    = 0f,
    val recommendedClaheHighlightsBoost: Float = 0f,
)

/**
 * Rule-based scene detector.  Takes [ImageMetrics] from a downscaled
 * preview and returns a [SceneDetectionResult] with suggested slider
 * values.  No ML model required — the thresholds are calibrated from
 * typical RAW histogram distributions.
 *
 * Phase 1 heuristics (swap these rules for a TFLite SceneClassifier in Phase 2):
 *
 *   • Night             — avgLuma < 70 AND high luma spread
 *   • Golden Hour       — warm colour temp AND medium-high brightness
 *   • Indoor / Tungsten — cool-ish temp AND low-medium brightness
 *   • Flat / Desaturated— low avgChroma AND adequate brightness
 *   • High Contrast     — very wide dynamic range
 *   • Neutral           — everything else (modest defaults)
 */
object SceneDetector {

    fun detect(metrics: ImageMetrics): SceneDetectionResult = when {

        // Night scene: very dark, often high spread (highlights vs shadows).
        metrics.avgLuma < 70f && metrics.lumaRange > 50f ->
            SceneDetectionResult(
                presetName                   = "Night Scene",
                recommendedSaturation        = 15f,
                recommendedVibrance          = 25f,
                recommendedWhiteBalanceDelta = -200,
                recommendedTintDelta         = 5f,
                recommendedClaheShadowsBoost = 0.25f,
                recommendedClaheHighlightsBoost = 0.10f,
            )

        // Very dark and flat — heavily underexposed raw.
        metrics.avgLuma < 70f ->
            SceneDetectionResult(
                presetName                   = "Underexposed",
                recommendedSaturation        = 10f,
                recommendedVibrance          = 30f,
                recommendedClaheShadowsBoost = 0.35f,
            )

        // Golden hour / warm daylight — warm tones, good exposure.
        metrics.colorTempKelvin > 5800 && metrics.avgLuma > 100f ->
            SceneDetectionResult(
                presetName                   = "Golden Hour",
                recommendedSaturation        = 20f,
                recommendedVibrance          = 30f,
                recommendedWhiteBalanceDelta = 0,
                recommendedClaheShadowsBoost = 0.10f,
                recommendedClaheHighlightsBoost = 0.05f,
            )

        // Indoor tungsten / mixed artificial light — cool-ish, medium bright.
        metrics.colorTempKelvin < 4500 && metrics.avgLuma in 60f..140f ->
            SceneDetectionResult(
                presetName                   = "Indoor Tungsten",
                recommendedSaturation        = 10f,
                recommendedVibrance          = 20f,
                recommendedWhiteBalanceDelta = -300,
                recommendedTintDelta         = 5f,
                recommendedClaheShadowsBoost = 0.20f,
                recommendedClaheHighlightsBoost = 0.10f,
            )

        // Flat / desaturated base — dull colours, adequate brightness.
        metrics.avgChroma < 25f && metrics.avgLuma > 80f ->
            SceneDetectionResult(
                presetName                      = "Flat/Desaturated",
                recommendedSaturation           = 15f,
                recommendedVibrance             = 35f,
                recommendedClaheShadowsBoost    = 0.05f,
                recommendedClaheHighlightsBoost = 0.05f,
            )

        // High contrast / wide dynamic range outdoor.
        metrics.lumaRange > 160f ->
            SceneDetectionResult(
                presetName                      = "High Contrast",
                recommendedSaturation           = 8f,
                recommendedVibrance             = 15f,
                recommendedClaheShadowsBoost    = 0.15f,
                recommendedClaheHighlightsBoost = -0.10f,
            )

        // Neutral fallback — well-exposed, no obvious scene signature.
        else ->
            SceneDetectionResult(
                presetName            = "Neutral",
                recommendedSaturation = 5f,
                recommendedVibrance   = 10f,
                recommendedClaheShadowsBoost = 0.05f,
            )
    }
}
