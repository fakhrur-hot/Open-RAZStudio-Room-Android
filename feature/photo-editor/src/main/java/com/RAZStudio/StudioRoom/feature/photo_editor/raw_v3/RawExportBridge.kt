/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Shared bridge utilities used by both the single-photo editor
 * (RawEditorComponent) and the headless batch processor (RawBatchProcessor).
 * Keeping them here prevents the two callers from drifting apart when new
 * workspace fields or export formats are added.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageScaleMode
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeSharpen
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig

object RawExportBridge {

    // ── WorkspaceConfig → RawV3WorkspaceOptions ───────────────────────────────

    fun WorkspaceConfig.toV3Options(): RawV3WorkspaceOptions {
        val v3Qual = demosaicAlgorithm.userQual
        val wb = when (wbSourceOrdinal) {
            1    -> RawV3WorkspaceOptions.WbSource.Auto
            2    -> RawV3WorkspaceOptions.WbSource.Daylight
            else -> RawV3WorkspaceOptions.WbSource.Camera
        }
        return RawV3WorkspaceOptions(
            demosaicAlgorithm       = v3Qual,
            highlightMode           = highlightRecovery.librawValue,
            caCorrectionEnabled     = caCorrectionEnabled,
            lensfunDbDir            = lensfunDbDir,
            lensfunCameraId         = lensfunCameraId,
            lensfunLensId           = lensfunLensId,
            lensfunFocalOverrideMm  = lensfunFocalOverrideMm,
            wbSource                = wb,
            exposureShift           = exposureShiftEv,
            fbddNoise               = fbddNoise,
            nrEnabled               = nrEnabled,
            nrLuma                  = nrLuma,
            nrChroma                = nrChroma,
            subjectDetectionEnabled = subjectDetectionEnabled,
            blackLevelDelta         = blackLevelDelta,
            whiteLevelDelta         = whiteLevelDelta,
            clipThreshold           = clipThreshold,
            dualContrastThreshold   = dualContrastThreshold,
            dualAutoContrast        = dualAutoContrast,
            filmProfileIndex        = filmProfile.ordinal,
            filmGrainAmount         = filmGrainLevel.value,
            hdrRecovery             = hdrRecovery,
            shadowRecovery          = shadowRecovery,
            enhanceEnabled          = enhanceEnabled,
            enhanceGuidedFilter     = enhanceGuidedFilter,
            claheHighlightsBoost    = claheHighlightsBoost,
            adjustMaximumThr        = highlightProtection,
            useCameraColorProfile   = useCameraColorProfile,
        )
    }

    // ── RawExportFormat → RawV3Exporter.Format ────────────────────────────────

    fun mapExportFormat(format: RawExportFormat): RawV3Exporter.Format = when (format) {
        RawExportFormat.JPG                          -> RawV3Exporter.Format.Jpg
        RawExportFormat.PNG, RawExportFormat.PNG_16 -> RawV3Exporter.Format.Png16
        RawExportFormat.TIFF                         -> RawV3Exporter.Format.Tiff16
        RawExportFormat.WEBP                         -> RawV3Exporter.Format.WebP
        RawExportFormat.HEIC                         -> RawV3Exporter.Format.Heic
        RawExportFormat.AVIF                         -> RawV3Exporter.Format.Avif
        RawExportFormat.BMP,
        RawExportFormat.JPEG2000,
        RawExportFormat.JXL                          -> RawV3Exporter.Format.Jpg
    }

    // ── Settings ImageFormat → RawV3Exporter.Format ───────────────────────────

    fun mapImageFormat(fmt: ImageFormat?): RawV3Exporter.Format = when (fmt) {
        is ImageFormat.Jpg  -> RawV3Exporter.Format.Jpg
        is ImageFormat.Png  -> RawV3Exporter.Format.Png16
        is ImageFormat.Webp -> RawV3Exporter.Format.WebP
        is ImageFormat.Heic -> RawV3Exporter.Format.Heic
        is ImageFormat.Avif -> RawV3Exporter.Format.Avif
        is ImageFormat.Tiff -> RawV3Exporter.Format.Tiff16
        else                -> RawV3Exporter.Format.Jpg
    }

    // ── ImageScaleMode → RawV3Exporter.ScaleMode ─────────────────────────────

    fun mapScaleMode(mode: ImageScaleMode): RawV3Exporter.ScaleMode = when (mode) {
        is ImageScaleMode.Bilinear           -> RawV3Exporter.ScaleMode.Bilinear
        is ImageScaleMode.Spline64           -> RawV3Exporter.ScaleMode.Spline64
        is ImageScaleMode.Lanczos3           -> RawV3Exporter.ScaleMode.Lanczos3
        is ImageScaleMode.EwaLanczos4Sharpest -> RawV3Exporter.ScaleMode.Lanczos4SharpestEwa
        ImageScaleMode.RAZSharp              -> RawV3Exporter.ScaleMode.RAZSharp
        else                                 -> RawV3Exporter.ScaleMode.Lanczos3
    }

    // ── ResizeSharpen → RawV3Exporter.ResizeSharpen ──────────────────────────

    fun mapResizeSharpen(rs: ResizeSharpen): RawV3Exporter.ResizeSharpen =
        RawV3Exporter.ResizeSharpen.valueOf(rs.name)

    // ── XMP overlay blob from live ShaderParams ───────────────────────────────

    fun buildXmpOverlay(params: ShaderParams): FloatArray {
        val out = FloatArray(ShaderParams.XMP_BLOB_FLOAT_COUNT)
        out[0] = 1f               // enabled
        out[1] = params.exposure
        out[2] = params.contrast
        out[3] = params.highlights
        out[4] = params.shadows
        out[5] = params.whites
        out[6] = params.blacks
        for (i in 0 until 18) out[7 + i] = params.hsl[i]
        return out
    }
}
