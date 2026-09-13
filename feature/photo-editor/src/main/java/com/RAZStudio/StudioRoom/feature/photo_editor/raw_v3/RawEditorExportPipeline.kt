/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Editor single-photo export pipeline — canonical Stage C option builder.
 *
 *  Both the interactive RAW editor and the headless batch processors
 *  (folder-tree batch + Canon Sync download-and-process) must produce the
 *  same Stage C params. This object is the single place where the action
 *  stack is folded into ShaderParams, the LUT/tone-curve/mask/bokeh
 *  options are derived, and the v3 coordinator's ExportOptions are built.
 *
 *  Rule: if a setting affects what Stage C renders, it lives here. The
 *  interactive editor and batch processors differ only by whether they
 *  also drive a live GL canvas preview.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.core.settings.domain.model.SettingsState
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.CombinedWatermarkConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import java.io.File

object RawEditorExportPipeline {

    /**
     * Build the canonical [RawV3Coordinator.ExportOptions] used by both the
     * single-photo editor and batch processing.
     *
     * @param actions Committed action stack (including [RawAction.Original]).
     * @param workspace Effective workspace config for this export.
     * @param settings App settings carrying default format/quality/scale/sharpen.
     * @param format Optional export-format override. When null, the user's
     *               [SettingsState.defaultImageFormat] is used.
     * @param targetLongSide 0 = keep source resolution; otherwise fit the long
     *                       side to this value before encode.
     * @param qualityPct Output quality; -1 = use [SettingsState.defaultQuality].
     * @param exifPolicy Which EXIF tags to retain in the output file.
     * @param saveIcc Whether to embed an sRGB ICC profile.
     * @param cropL/T/R/B Normalised crop rect (0..1). Default identity.
     * @param cropRotationDeg Straighten angle in degrees, applied before crop.
     * @param watermarkConfig Optional watermark to burn after resize.
     * @param outputUri Optional one-time SAF save-location URI.
     * @param autoExposure Re-analyse this file's histogram and fold the result
     *                     into the Light-tab params. Used by batch presets.
     * @param aeBaked True when the action stack already contains an AE action,
     *                so export-time re-analysis is skipped.
     * @param aeSubjectProtection Subject-protection level forwarded to AE.
     * @param nrLevel Batch NR strength layered on top when [autoExposure] is on.
     * @param resolvedLutFile Resolved .cube file for the effective LUT, or null.
     * @param resolvedLutIntensity Intensity for [resolvedLutFile].
     * @param directOutputFile Optional ephemeral output file; bypasses gallery.
     * @param forceSubjectDetection Force U2Net subject detection on when the
     *                             preset needs masks (LUT/bokeh) or auto-expo.
     * @param autoBrightFactor Per-image LibRaw auto-bright factor for Smart Bright.
     * @param shaderParams Pre-computed ShaderParams (e.g. the live preview state).
     *                     When null, the builder recomputes it from [actions].
     */
    fun buildExportOptions(
        actions: List<RawAction>,
        workspace: WorkspaceConfig,
        settings: SettingsState,
        format: RawExportFormat? = null,
        targetLongSide: Int = 0,
        qualityPct: Int = -1,
        exifPolicy: RawBatchProcessor.ExifPolicy = RawBatchProcessor.ExifPolicy.KeepAll,
        saveIcc: Boolean = true,
        cropL: Float = 0f,
        cropT: Float = 0f,
        cropR: Float = 1f,
        cropB: Float = 1f,
        cropRotationDeg: Float = 0f,
        cropRotate90: Int = 0,
        cropFlipH: Boolean = false,
        cropFlipV: Boolean = false,
        watermarkConfig: CombinedWatermarkConfig? = null,
        borderThickness: Float = 0f,
        borderColorArgb: Int = 0,
        outputUri: String? = null,
        autoExposure: Boolean = false,
        aeBaked: Boolean = false,
        aeSubjectProtection: Float = 0.95f,
        nrLevel: RawV3Coordinator.NrLevel = RawV3Coordinator.NrLevel.None,
        resolvedLutFile: File? = null,
        resolvedLutIntensity: Float = 1f,
        directOutputFile: File? = null,
        forceSubjectDetection: Boolean = false,
        autoBrightFactor: Float = 1f,
        shaderParams: ShaderParams? = null,
        // Route A: the editor's exact camera-match curve (preview's), passed so
        // the save uses it verbatim instead of recomputing (preview == save).
        cameraMatchLutOverride: ByteArray? = null,
    ): RawV3Coordinator.ExportOptions {
        // 1) Workspace. Batch presets may force subject detection when they
        //    need masks for LUT/bokeh/auto-expo; otherwise respect the dialog.
        val baseWorkspaceV3 = RawExportBridge.run { workspace.toV3Options() }
        val workspaceV3 = if (forceSubjectDetection) {
            baseWorkspaceV3.copy(subjectDetectionEnabled = true)
        } else baseWorkspaceV3

        // 2) Fold the action stack into ShaderParams. This is the same flatten()
        //    path the live GL preview uses, so the saved file matches the canvas.
        val baseMacro = if (workspace.cameraStyleFinishEnabled) {
            UserMacro.CAMERA_STYLE_FINISH
        } else UserMacro()
        val effectiveShaderParams = shaderParams ?: RawV3ActionReplay.flatten(
            actions = actions,
            baseMacro = baseMacro,
            autoBrightFactor = autoBrightFactor,
            purpleFringeMode = workspace.colorFringingMode.ordinal,
            // Pipeline is locked to sRGB end-to-end; see RawEditorComponent.
            workspaceSpace = 1,
            lutAuthoredSpace = 0,
        )
        val xmpBlob = RawExportBridge.buildXmpOverlay(effectiveShaderParams)
        val fullParamsBlob = effectiveShaderParams.toFloatArray()

        // 3) LUT + tone-curve metadata that Stage C needs in addition to the
        //    params blob.
        val lutFile = resolvedLutFile?.takeIf { it.exists() }
        val lutIntensity = resolvedLutIntensity.coerceIn(0f, 1f)
        val composedMacro = RawV3ActionReplay.composeMacro(actions, baseMacro)
        val toneCurveLut = RawV3ToneCurve.buildLut(composedMacro)

        // 4) Bokeh (OpenCV post-pass). Prefer a painted mask layer when a
        //    visible Mask-tab card carries bokeh; otherwise fall back to the
        //    global fold paired with the U2Net subject mask.
        val bokehSource = findBokehSource(actions)
        val bokeh = BokehParams(
            blurLevel = bokehSource.macro.bokehBlur,
            bokehLevel = bokehSource.macro.bokehBalls,
            spreadEnabled = bokehSource.macro.bokehSpread > 0f,
            spreadIntensity = bokehSource.macro.bokehSpread,
            isolateSubject = bokehSource.maskPath == null,
        )

        // 5) Format/scale/sharpen/quality from Settings.
        val v3Format = format?.let { RawExportBridge.mapExportFormat(it) }
            ?: RawExportBridge.mapImageFormat(settings.defaultImageFormat)
        val v3Scale = RawExportBridge.mapScaleMode(settings.defaultImageScaleMode)
        val v3Sharpen = RawExportBridge.mapResizeSharpen(settings.defaultResizeSharpen)

        // 6) EXIF policy ordinal understood by the coordinator.
        val exifOrdinal = when (exifPolicy) {
            RawBatchProcessor.ExifPolicy.KeepAll -> 0
            RawBatchProcessor.ExifPolicy.StripSensitive -> 1
            RawBatchProcessor.ExifPolicy.NoneExceptSoftware -> 2
        }

        // 7) Up to 4 brush-mask layer PNGs so per-region adjustments match the
        //    preview. Previously missing from batch; now unified.
        val maskLayerPaths = RawV3ActionReplay.maskLayers(actions)
            .mapNotNull { it.maskPath }

        return RawV3Coordinator.ExportOptions(
            workspace = workspaceV3,
            format = v3Format,
            targetLongSide = targetLongSide,
            scaleMode = v3Scale,
            resizeSharpen = v3Sharpen,
            qualityPct = if (qualityPct >= 0) qualityPct else settings.defaultQuality.qualityValue,
            embedIcc = saveIcc,
            xmpPresetBlob = xmpBlob,
            oneTimeSaveLocationUri = outputUri,
            cropL = cropL,
            cropT = cropT,
            cropR = cropR,
            cropB = cropB,
            cropRotationDeg = cropRotationDeg,
            cropRotate90 = cropRotate90,
            cropFlipH = cropFlipH,
            cropFlipV = cropFlipV,
            fullParamsBlob = fullParamsBlob,
            exifPolicyOrdinal = exifOrdinal,
            lutCubeFile = lutFile,
            lutIntensity = lutIntensity,
            maskLayerPaths = maskLayerPaths,
            autoExposure = autoExposure,
            aeBaked = aeBaked,
            aeSubjectProtection = aeSubjectProtection,
            nrLevel = nrLevel,
            bokeh = bokeh,
            paintedBokehMaskPath = bokehSource.maskPath,
            toneCurveLut = toneCurveLut,
            useCameraColorProfile = workspace.useCameraColorProfile,
            cameraProfileGuidedFilter = workspace.cameraProfileGuidedFilter,
            cameraMatchLutOverride = cameraMatchLutOverride,
            watermarkConfig = watermarkConfig,
            borderThickness = borderThickness,
            borderColorArgb = borderColorArgb,
            directOutputFile = directOutputFile,
            // Smart Bright: only the headless path (shaderParams == null) needs the
            // coordinator to apply it per-file. When the editor passes its live
            // params, Smart Bright is ALREADY baked into the blob (flatten ran with
            // the editor's per-image autoBrightFactor) — leave 0 to avoid double-apply.
            smartBrightAmount = if (shaderParams == null) composedMacro.smartBright else 0f,
        )
    }

    /**
     * Locate the source macro that drives bokeh, and any painted mask path.
     * Matches RawEditorComponent's export logic:
     *   - If a visible masked action has non-zero bokeh fields, use that
     *     action's macro and mask path.
     *   - Otherwise fold all visible unmasked bokeh actions.
     */
    private data class BokehSource(val macro: UserMacro, val maskPath: String?)

    private fun findBokehSource(actions: List<RawAction>): BokehSource {
        val all = actions.toList()
        val paintedSource = all.firstOrNull {
            it.id != RawAction.ORIGINAL_ID && it.isVisible &&
                it.maskPath != null && hasBokeh(it.macro)
        }
        return if (paintedSource != null) {
            BokehSource(paintedSource.macro, paintedSource.maskPath)
        } else {
            val folded = all
                .filter { it.id != RawAction.ORIGINAL_ID && it.isVisible && it.maskPath == null && hasBokeh(it.macro) }
                .reversed()
                .fold(UserMacro()) { acc, a -> acc.mergeWith(a.macro) }
            BokehSource(folded, null)
        }
    }

    private fun hasBokeh(m: UserMacro): Boolean =
        m.bokehBlur > 0 || m.bokehBalls > 0 || m.bokehSpread > 0f
}
