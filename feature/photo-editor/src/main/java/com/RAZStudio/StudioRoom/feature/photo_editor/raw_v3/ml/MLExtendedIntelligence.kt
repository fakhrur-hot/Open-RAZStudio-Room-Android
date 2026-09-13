/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Extended Intelligence — Orchestrator (spec cr2-intelligence-integration,
 * Component 1).
 *
 * Thin dispatcher: no new algorithms. Calls the nine existing extended ML
 * modules (MLPictureStyle, MLChromaticAberration, MLDualIsoDetect,
 * MLFocusDepth, MLFlashCompensation, MLThermalNoise, MLExtendedHistogram,
 * MLDiffractionComp, MLBodyWbTrim) in the fixed order from
 * ml6d-extended-intelligence Requirement 15.2, applies sidecar precedence
 * (Requirement 1.3), maxOf/clamp semantics on reused slots (Requirement 1.6,
 * 1.5), and per-module exception isolation (Requirement 1.4).
 *
 * Note on slot representation: [ShaderParams] slots [147]/[148] (NR),
 * [149]/[178] (focus-depth sharpness/background), [348] (highlight
 * recovery) and [352]/[353] (CA) are all already mapped from [UserMacro]
 * fields by `RawV3ActionReplay.mapMacroToShaderParams()`, so this
 * orchestrator writes those reused slots via the macro (UI-scale [0..100]
 * or [0..1] per field, matching existing macro conventions) rather than
 * poking ShaderParams directly — that keeps them flowing correctly through
 * the normal action-stack recomposition. The four genuinely new slots
 * ([402]..[407]: dual-ISO recovery gain/blend, sceneDR/highlightHeadroom,
 * diffraction, body WB trim) have no macro representation, so they're
 * returned as a small diagnostics array for the caller to thread into
 * `RawV3ActionReplay.flatten(extDiagnostics = ...)`.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import android.content.Context
import com.RAZStudio.StudioRoom.core.utils.AppLog
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine

/** Clamps a Float into [min, max]. Local to the ML package — no shared extension existed. */
internal fun Float.clampTo(min: Float, max: Float): Float = coerceIn(min, max)

object MLExtendedIntelligence {

    private const val TAG = "MLExtended"

    /**
     * Dispatch to the nine extended ML modules and fold their results into
     * [currentMacro] plus a 6-float diagnostics array for the new
     * [ShaderParams][com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams]
     * slots [402]..[407].
     *
     * @param stageA Stage A decode result — supplies cameraMake/cameraModel/
     *   lensId/iso/colorTemperature/shutterSpeed/aperture/focalLength. Fields
     *   the current Stage A result does not carry (focus distance, flash
     *   fired, light source, Picture Style MakerNote tag, dual-ISO MakerNote
     *   tag) are passed as their "not present" defaults — the corresponding
     *   module then degrades gracefully (per its own null-safe contract)
     *   rather than acting on fabricated metadata.
     * @param stageAHistogramStats EXIF/histogram-derived scene stats, used
     *   only when [sidecar] carries no `ettr` block.
     * @param sidecar Parsed `.ml` firmware sidecar, or null (EXIF-only
     *   fallback, Requirement 8.4).
     */
    fun applyExtendedDefaults(
        context: Context?,
        stageA: RawV3Engine.StageAResult,
        stageAHistogramStats: StageAHistogramStats?,
        sidecar: MlShotSidecar?,
        currentMacro: UserMacro,
    ): ExtendedResult {
        var macro = currentMacro
        // [dualIsoRecoveryGain, dualIsoBlendFactor, sceneDR, highlightHeadroom, diffractionComp, bodyWbTrim]
        val diag = FloatArray(6)
        AppLog.i(TAG, "applyExtendedDefaults: begin sidecarPresent=${sidecar != null} lensId=${stageA.lensId} iso=${stageA.iso}")

        // 1. Picture Style detection — drives bias scaling for later steps.
        val style = safe("PictureStyle") { MLPictureStyle.detect(stageA.cameraMake, null) } ?: CanonPictureStyle.STANDARD
        val biasScale = safe("PictureStyle.biasScale") { MLPictureStyle.biasScale(style) } ?: 1f

        // 2. Chromatic Aberration — ALWAYS from the CR2's own EXIF lensId,
        //    same as a plain single-file RAWEditor import. Sidecar lens data
        //    is deliberately NOT consulted here: unlike dual-ISO/ETTR (which
        //    the CR2's EXIF cannot express at all), lens identity is already
        //    fully available from EXIF, and a sidecar's lens block can go
        //    stale/mismatched (wrong lens_id from a different shot, a synced
        //    sidecar reused across files, etc.) and silently apply the wrong
        //    lens's CA/fringe correction. lensfun's own distortion correction
        //    (Stage A, native) has never read the sidecar either — this keeps
        //    both lens-driven corrections on the same, single source of truth.
        safe("ChromaticAberration") { MLChromaticAberration.forLensId(stageA.lensId) }?.let { ca ->
            macro = macro.copy(
                aberStrength = maxOf(macro.aberStrength, ca.caStrength.clampTo(0f, 100f)),
                aberFringeReduce = maxOf(macro.aberFringeReduce, ca.fringeReduce.clampTo(0f, 100f)),
            )
        }

        // 3. Dual-ISO Detection — sidecar dualIso block wins over MakerNote tag
        //    (the current Stage A result carries no MakerNote tag field, so
        //    the EXIF-only path always reports "not dual-ISO" today).
        val dualIso = sidecar?.dualIso?.let {
            safe("DualIsoDetect.fromSidecar") { MLDualIsoDetect.fromSidecar(it.isoBase, it.isoAlternate) }
        } ?: safe("DualIsoDetect") { MLDualIsoDetect.detect(stageA.cameraMake, stageA.cameraModel, stageA.iso, null) }
        dualIso?.let {
            diag[0] = it.recoveryGain.clampTo(0f, 3f)
            diag[1] = it.blendFactor.clampTo(0f, 1f)
        }

        // 4. Focus Depth — no focus-distance fields on StageAResult today;
        //    pass 0/0 so the module's own "insufficient data" path (rather
        //    than a fabricated depth-of-field) decides the outcome.
        safe("FocusDepth") { MLFocusDepth.compute(0f, 0f, stageA.aperture, stageA.focalLength) }?.let {
            macro = macro.copy(
                smartSharpness = maxOf(macro.smartSharpness, (it.smartSharpness.clampTo(0f, 100f)) / 100f),
                smoothBackground = maxOf(macro.smoothBackground, (it.smoothBackground.clampTo(0f, 100f)) / 100f),
            )
        }

        // 5. Flash Compensation — skipped in MONOCHROME (WB/tint are zeroed
        //    at the end of the pipeline for that style anyway).
        if (style != CanonPictureStyle.MONOCHROME) {
            safe("FlashCompensation") { MLFlashCompensation.compute(false, 0, stageA.colorTemperature) }?.let {
                macro = macro.copy(
                    whiteBalance = macro.whiteBalance + (it.wbOffset * biasScale).toInt(),
                    tint = macro.tint + it.tintOffset * biasScale,
                    shadows = macro.shadows + it.shadowLift,
                )
            }
        }

        // 6. Thermal Noise — boosts the reused NR slots (never decreases them).
        safe("ThermalNoise") { MLThermalNoise.boost(stageA.shutterSpeed, stageA.iso, macro.luminanceNR, macro.colorNR) }?.let {
            macro = macro.copy(
                luminanceNR = maxOf(macro.luminanceNR, it.first.clampTo(0f, 1f)),
                colorNR = maxOf(macro.colorNR, it.second.clampTo(0f, 1f)),
            )
        }

        // 7. Extended Histogram — sidecar ettr block wins over Stage A stats,
        //    UNLESS the sidecar values are degenerate placeholders (the
        //    firmware's "no usable histogram" sentinel is sceneDR=4.0 +
        //    highlightHeadroom=0.0 + all channelClip=0.0 — per the RaZStudio
        //    Integration Contract, these are the fallback values written when
        //    the firmware couldn't compute real ETTR metadata). Accepting them
        //    blindly causes max highlight recovery on every photo.
        val sidecarEttrValid = sidecar?.ettr?.let { ettr ->
            // Reject if ALL of: sceneDR at floor (<=4), headroom at zero,
            // and no channel clip data — the degenerate "empty" state.
            !(ettr.sceneDR <= 4f && ettr.highlightHeadroom <= 0f &&
                ettr.channelClip.all { it <= 0f })
        } ?: false
        val histStats = if (sidecarEttrValid) {
            sidecar!!.ettr!!.let {
                StageAHistogramStats(
                    sceneDynamicRange = it.sceneDR,
                    highlightHeadroom = it.highlightHeadroom,
                    redClipPercent = it.channelClip.getOrElse(0) { 0f },
                    greenClipPercent = it.channelClip.getOrElse(1) { 0f },
                    blueClipPercent = it.channelClip.getOrElse(2) { 0f },
                )
            }
        } else {
            if (sidecar?.ettr != null) {
                AppLog.w(TAG, "applyExtendedDefaults: sidecar ettr block rejected as degenerate " +
                    "(sceneDR=${sidecar.ettr!!.sceneDR} headroom=${sidecar.ettr!!.highlightHeadroom})")
            }
            stageAHistogramStats
        }
        histStats?.let { stats ->
            diag[2] = stats.sceneDynamicRange.clampTo(4f, 14f)
            diag[3] = stats.highlightHeadroom.clampTo(0f, 3f)
            safe("ExtendedHistogram") { MLExtendedHistogram.driveFromStats(stats) }?.let { drive ->
                macro = macro.copy(
                    filmRolloff = maxOf(macro.filmRolloff, drive.filmRolloff),
                    gamutCompress = maxOf(macro.gamutCompress, drive.gamutCompress),
                    highlightRecovery = maxOf(macro.highlightRecovery, drive.highlightRecovery.clampTo(0f, 100f)),
                )
            }
        }

        // 8. Diffraction Compensation.
        safe("DiffractionComp") { MLDiffractionComp.compute(stageA.aperture, stageA.focalLength, cropFactorForModel(stageA.cameraModel)) }?.let {
            diag[4] = it.clampTo(0f, 30f)
        }

        // 9. Body WB Trim — skipped in MONOCHROME like Flash Compensation.
        if (style != CanonPictureStyle.MONOCHROME) {
            if (context != null) safe("BodyWbTrim.ensureLoaded") { MLBodyWbTrim.ensureLoaded(context) }
            safe("BodyWbTrim") { MLBodyWbTrim.forModel(stageA.cameraModel) }?.let {
                macro = macro.copy(
                    whiteBalance = macro.whiteBalance + (it.wbOffset * biasScale).toInt(),
                    tint = macro.tint + it.tintOffset * biasScale,
                )
                diag[5] = (it.wbOffset / 20f).clampTo(-1f, 1f)
            }
        }

        macro = applyBiasScale(macro, biasScale, style)
        AppLog.i(
            TAG,
            "applyExtendedDefaults: end style=$style biasScale=$biasScale " +
                "diag=[dualIsoGain=${diag[0]} dualIsoBlend=${diag[1]} sceneDR=${diag[2]} " +
                "highlightHeadroom=${diag[3]} diffractionComp=${diag[4]} bodyWbTrim=${diag[5]}]",
        )
        return ExtendedResult(adjustedMacro = macro, biasScale = biasScale, extDiagnostics = diag)
    }

    private inline fun <T> safe(module: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        AppLog.e(TAG, "applyExtendedDefaults: module '$module' threw, skipping", e)
        null
    }

    private fun applyBiasScale(macro: UserMacro, scale: Float, style: CanonPictureStyle): UserMacro =
        if (style == CanonPictureStyle.MONOCHROME) {
            macro.copy(saturation = 0f, tint = 0f, whiteBalance = 0)
        } else {
            macro.copy(
                contrast = macro.contrast * scale,
                saturation = macro.saturation * scale,
                whiteBalance = (macro.whiteBalance * scale).toInt(),
                tint = macro.tint * scale,
            )
        }
}
