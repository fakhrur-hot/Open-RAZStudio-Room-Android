/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Extended Intelligence — Data Models.
 *
 * Defines all data classes, enums, and result types used by the extended
 * ML intelligence modules (chromatic aberration, dual-ISO, focus depth,
 * flash compensation, thermal noise, picture style, extended histogram,
 * diffraction compensation, and body WB trim).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

// ─────────────────────────────────────────────────────────────────────────────
// Chromatic Aberration
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Per-lens chromatic aberration correction entry from the extended lens_tune.tbl.
 *
 * @property lensId Canon lens_id from EXIF.
 * @property caStrength Correction strength [0..100] → ShaderParams slot [352].
 * @property fringeReduce Fringe suppression strength [0..100] → ShaderParams slot [353].
 */
data class LensCaEntry(
    val lensId: Int,
    val caStrength: Float,     // [0..100]
    val fringeReduce: Float,   // [0..100]
)

// ─────────────────────────────────────────────────────────────────────────────
// Dual-ISO Detection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Result of dual-ISO frame detection from Canon MakerNote.
 *
 * @property isDualIso True if dual-ISO interleaving was detected.
 * @property recoveryGain Log2 of ISO ratio, clamped [0..3] → slot [402].
 * @property blendFactor Interleave blend weight, clamped [0..1] → slot [403].
 * @property isoBase The base (lower) ISO value.
 * @property isoAlternate The alternate (higher) ISO value.
 */
data class DualIsoResult(
    val isDualIso: Boolean,
    val recoveryGain: Float,   // [0..3]
    val blendFactor: Float,    // [0..1]
    val isoBase: Int,
    val isoAlternate: Int,
)

// ─────────────────────────────────────────────────────────────────────────────
// Focus Depth Processing
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Depth-of-field-aware sharpening and smoothing result.
 *
 * @property smartSharpness Focus-aware sharpening [0..100] → slot [149].
 * @property smoothBackground Depth-aware background smoothing [0..100] → slot [178].
 */
data class FocusDepthResult(
    val smartSharpness: Float,      // [0..100]
    val smoothBackground: Float,    // [0..100]
)

// ─────────────────────────────────────────────────────────────────────────────
// Flash Compensation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Flash mixed-lighting WB/tint/shadow compensation result.
 *
 * @property wbOffset Additive white balance offset for UserMacro.
 * @property tintOffset Additive tint offset for UserMacro.
 * @property shadowLift Shadow push amount [0..0.3] applied when flash fires.
 */
data class FlashCompResult(
    val wbOffset: Float,
    val tintOffset: Float,
    val shadowLift: Float,      // [0..0.3]
)

// ─────────────────────────────────────────────────────────────────────────────
// Extended Histogram
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stage A histogram statistics computed from the 768-element R,G,B histogram.
 *
 * @property sceneDynamicRange Scene DR in stops, clamped [4..14] → slot [404].
 * @property highlightHeadroom Headroom in stops, clamped [0..3] → slot [405].
 * @property redClipPercent Fraction of red pixels at clip level [0..1].
 * @property greenClipPercent Fraction of green pixels at clip level [0..1].
 * @property blueClipPercent Fraction of blue pixels at clip level [0..1].
 */
data class StageAHistogramStats(
    val sceneDynamicRange: Float,     // [4..14]
    val highlightHeadroom: Float,     // [0..3]
    val redClipPercent: Float,        // [0..1]
    val greenClipPercent: Float,      // [0..1]
    val blueClipPercent: Float,       // [0..1]
)

/**
 * Histogram-driven adjustment result fed into UserMacro and ShaderParams.
 *
 * @property filmRolloff Highlight compression amount [0..1] → UserMacro.
 * @property gamutCompress Gamut compression amount [0..1] → UserMacro.
 * @property highlightRecovery DR-driven recovery [0..1] → slot [348].
 */
data class HistogramDriveResult(
    val filmRolloff: Float,           // [0..1]
    val gamutCompress: Float,         // [0..1]
    val highlightRecovery: Float,     // [0..1]
)

// ─────────────────────────────────────────────────────────────────────────────
// Body WB Trim
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Per-camera-model white balance calibration offset entry.
 *
 * @property cameraModel Camera model string, e.g. "Canon EOS 6D".
 * @property wbOffset Additive WB offset [-20..+20].
 * @property tintOffset Additive tint offset [-10..+10].
 */
data class BodyWbTrimEntry(
    val cameraModel: String,
    val wbOffset: Float,        // [-20..+20]
    val tintOffset: Float,      // [-10..+10]
)

// ─────────────────────────────────────────────────────────────────────────────
// Canon Picture Style
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Canon Picture Style values detected from MakerNote tag 0x000A.
 *
 * Used to compute a bias scale factor that modulates all ML adjustments:
 * - NEUTRAL / FAITHFUL → scale 0.5 (subtle adjustments)
 * - MONOCHROME → skip color trims entirely
 * - All others → scale 1.0 (full adjustments)
 */
enum class CanonPictureStyle {
    STANDARD, PORTRAIT, LANDSCAPE, NEUTRAL, FAITHFUL, MONOCHROME, AUTO, USER_DEF;
}

// ─────────────────────────────────────────────────────────────────────────────
// Extended Intelligence Orchestrator Result
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Combined result from the extended intelligence orchestrator.
 *
 * @property adjustedMacro The UserMacro with all extended module adjustments applied
 *   (flash WB compensation, body WB trim, bias scaling).
 * @property biasScale The Picture Style bias scale factor [0..1] applied to macro fields.
 * @property extDiagnostics 6-float array for the new ShaderParams diagnostic
 *   slots [402]..[407]: [dualIsoRecoveryGain, dualIsoBlendFactor, sceneDR,
 *   highlightHeadroom, diffractionComp, bodyWbTrim]. Feed to
 *   `RawV3ActionReplay.flatten(extDiagnostics = ...)`. The reused slots
 *   ([147]/[148]/[149]/[178]/[348]/[352]/[353]) are already folded into
 *   [adjustedMacro] instead, since those map 1:1 from existing UserMacro
 *   fields.
 */
data class ExtendedResult(
    val adjustedMacro: UserMacro,
    val biasScale: Float,
    val extDiagnostics: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtendedResult) return false
        return adjustedMacro == other.adjustedMacro &&
            biasScale == other.biasScale &&
            extDiagnostics.contentEquals(other.extDiagnostics)
    }

    override fun hashCode(): Int {
        var result = adjustedMacro.hashCode()
        result = 31 * result + biasScale.hashCode()
        result = 31 * result + extDiagnostics.contentHashCode()
        return result
    }
}
