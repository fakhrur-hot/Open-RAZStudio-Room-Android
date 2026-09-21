/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

/**
 * User-selected Stage A decode parameters. Matches FR-1.1 of Plan.md.
 *
 * These are baked into the Stage A TIFF cache; they cannot be changed
 * mid-session without re-decoding. The action-card adjustments applied
 * during Stage B (and later replayed in Stage C) are independent of this.
 *
 *  • [demosaicAlgorithm] — LibRaw user_qual value. -1 = RCD/RAZAmaze,
 *                          0 = Linear, 1 = VNG, 2 = PPG, 3 = AHD, 4 = DCB,
 *                          11 = DHT, 12 = AAHD (a.k.a. AMaZE).
 *  • [highlightMode]    — LibRaw highlight. 0 = clip, 1 = unclip, 2 = blend,
 *                          3..9 = rebuild (3 = default rebuild).
 *  • [wbSource]         — picks WB source for LibRaw. See [WbSource].
 *  • [exposureShift]    — LibRaw exp_correc/exp_shift. EV stops, -2..+3.
 *                          0f → disabled. Applied pre-demosaic, distinct
 *                          from the shader-side exposure slider.
 *  • [fbddNoise]        — LibRaw fbdd_noiserd. 0 = off, 1 = light, 2 = full.
 *  • [nrEnabled] / [nrLuma] / [nrChroma] — wavelet/median pre-demosaic NR
 *                          (separate path from FBDD).
 *  • [caCorrectionEnabled] — chromatic aberration correction via Rayxie CA.
 *  • [lensfunCameraId] / [lensfunLensId] — empty strings = Lensfun disabled.
 */
data class RawV3WorkspaceOptions(
    val demosaicAlgorithm: Int = -3,
    val highlightMode: Int = 2,
    val wbSource: WbSource = WbSource.Camera,
    val exposureShift: Float = 0f,
    val fbddNoise: Int = 0,
    val nrEnabled: Boolean = false,
    val nrLuma: Int = 0,
    val nrChroma: Int = 0,
    val caCorrectionEnabled: Boolean = true,
    val lensfunCameraId: String = "",
    val lensfunLensId: String = "",
    /**
     * Directory of Lensfun XML database files (materialised from
     * assets/lensfun_db by [LensfunDatabase.ensureMaterialized]). Empty →
     * lens correction disabled. When set, Stage A auto-matches the camera +
     * lens from the RAW's EXIF and applies devignetting + distortion + TCA
     * right after demosaic. If either side can't be confidently matched the
     * decode proceeds without correction — never guessed. Works for BOTH
     * Route A (Camera Color Profile) and Route B: the correction happens on
     * the demosaiced pixels before either route's colour handling.
     */
    val lensfunDbDir: String = "",
    /** Focal length (mm) override for the Lensfun correction; 0 = EXIF focal. */
    val lensfunFocalOverrideMm: Float = 0f,
    /**
     * Zero-DCE adaptive devignetting: shadow threshold τ for the SNR mask
     * (M = 1 − clamp(aMean/τ, 0, 1)) applied natively inside the Lensfun
     * vignette pass. Higher = corners protected already at moderate shadow;
     * lower = only the deepest shadows attenuate the optical gain.
     * ~0.3–1.2, default 0.7. Only used when Lensfun correction runs.
     */
    val liftTau: Float = 0.7f,
    /**
     * Manual offset applied to LibRaw's per-camera black-level default, in
     * raw DN. Positive lifts the black point (crushes shadows), negative
     * lowers it (lifts shadows / removes black tint).
     */
    val blackLevelDelta: Float = 0f,
    /**
     * Manual offset applied to LibRaw's per-camera white-level (sensor
     * saturation) default, in raw DN. Negative clips earlier (drops dynamic
     * range), positive raises the cap (more headroom before highlight
     * reconstruction kicks in — useful when LibRaw under-reports saturation).
     */
    val whiteLevelDelta: Float = 0f,
    /**
     * Fraction of the [black..white] range at which a sensel is considered
     * clipped by the demosaic's highlight-reconstruction pass [0.80..1.00].
     * Default 0.97 matches the previous hardcoded behaviour.
     */
    val clipThreshold: Float = 0.97f,
    /**
     * When true, run U2Net subject segmentation right after Stage A
     * completes (before Stage B downsamples) — producing subject /
     * background / edge masks consumed by the Vignette / Gradient / Mask
     * tabs and the Smooth-Background pass.
     */
    val subjectDetectionEnabled: Boolean = false,

    // ── AMaZE+VNG dual-decode options ────────────────────────────────────────
    // Only consulted when [demosaicAlgorithm] == -3 (RAZ_AMAZE_VNG).
    //
    //  [dualContrastThreshold] — sigmoid midpoint in [0..1] that controls
    //    how much a pixel must differ from its neighbours to prefer AMaZE
    //    over VNG. 0.2 is RawTherapee's default and a good starting point.
    //    Ignored (and overwritten with the auto-resolved value) when
    //    [dualAutoContrast] is true.
    //
    //  [dualAutoContrast] — when true, Stage A scans the image for the
    //    flattest tile and derives a scene-adaptive threshold. The resolved
    //    value is echoed back in the Stage A JSON as "dualContrastThreshold"
    //    so the UI can display what was picked.
    val dualContrastThreshold: Float = 0.2f,
    val dualAutoContrast: Boolean = true,
    /**
     * Film simulation profile index. Maps to the C++ ProfileIndex enum:
     * 0=DEFAULT (pass-through), 1=CLASSIC_NEG, 2=VELVIA, 3=PROVIA,
     * 4=ACROS, 5=CLASSIC_CHROME, 6=ASTIA, 7=ETERNA.
     * 0 means no film sim; the standard pipeline is used unchanged.
     */
    val filmProfileIndex: Int = 0,
    /**
     * Grain amplitude passed to the C++ kernel [0.0, 0.07].
     * 0.0 = grain pass skipped entirely.
     */
    val filmGrainAmount: Float = 0f,
    /**
     * When true, load raw_hdr_recovery.bin from assets and run the
     * lightweight Bayer U-Net between LibRaw::unpack() and dcraw_process()
     * to recover highlight detail before demosaicing. "Commit on Open" —
     * changing this flag requires re-decoding.
     */
    val hdrRecovery: Boolean = true,
    /**
     * When true, load raw_shadow_recovery.bin from assets and run shadow/black
     * lift U-Net after the HDR highlight pass, pre-demosaic.
     */
    val shadowRecovery: Boolean = true,

    // ── Post-demosaic LMMSE + USM enhancement (ai-enhance) ──────────────────
    // Runs on the RGBA FP16 buffer after demosaic+EXIF-flip, before BigTIFF write.
    // Params are pre-scaled to [0,1] domain by stageADecode() before passing to JNI:
    //   noiseVariance = enhanceNoiseVariance8bit / (255f * 255f)
    //   usmThreshold  = enhanceUsmThreshold8bit / 255f
    val enhanceEnabled: Boolean = false,
    val enhanceWindowSize: Int = 5,
    /** σ_n² on 0–255 pixel² scale; will be divided by 255² before JNI call.
     *  10→6→4: minor edges/thin lines carry only moderate local variance, so a
     *  higher σ_n still flattens them into a painted/plastic look. 4 (σ_n=2)
     *  keeps the floor below all but the faintest real structure. */
    val enhanceNoiseVariance8bit: Float = 4f,
    val enhanceUsmRadius: Float = 1.0f,
    /** 1.5→0.9→0.7: USM re-etches the minor edges the denoise just softened,
     *  which is what still reads as plastic on thin lines. 0.7 is a light touch. */
    val enhanceUsmAmount: Float = 0.7f,
    /** Noise-gate on 0–255 scale; will be divided by 255 before JNI call.
     *  6→12: raises the floor so low-amplitude mid-frequencies (insignificant
     *  edges/lines) are left alone by the sharpener instead of being crisped. */
    val enhanceUsmThreshold8bit: Float = 12f,
    val enhanceSkipDenoise: Boolean = false,
    val enhanceSkipSharpen: Boolean = false,
    /** Local σ cutoff for halo suppression in [0,1] domain (0.08–0.15 recommended). */
    val enhanceUsmEdgeThreshold: Float = 0.08f,
    /**
     * Run a small guided-filter pass between LMMSE denoise and mid-frequency USM.
     * Smooths tonal transitions in a edge-aware way; turn off for a slightly
     * sharper / more textured look at the cost of possible blotchy flat areas.
     */
    val enhanceGuidedFilter: Boolean = true,
    /**
     * CLAHE highlight recovery boost [0..1]. 0 = disabled. Set to ~0.35 when
     * AI Level Reconstruct is on; tied to that toggle, not a separate UI control.
     */
    val claheHighlightsBoost: Float = 0f,
    /**
     * LibRaw adjust_maximum_thr: prevents WB channel multipliers from
     * blowing near-white highlights past the sensor clip ceiling.
     * 0.85 = Strong (default; pairs with Safe Recovery desat 0.95).
     * 0.95 = Standard (fuller sensor headroom). 0.0 = off. Range [0.0..1.0].
     * Bridged from WorkspaceConfig.highlightProtection.
     */
    val adjustMaximumThr: Float = 0.85f,
    /**
     * Route A (Camera Color Profile): when true, [RawV3Coordinator.openRawFile]
     * derives the per-channel histogram-matched camera curve from the embedded
     * JPEG and bakes it into the FP16 A.tif right after Stage A decode (before
     * Stage B), so the preview AND export inherit the in-camera colour directly
     * instead of re-applying a LUT per stage. RAW only — non-RAW sources are
     * already camera-rendered and skip the bake.
     */
    val useCameraColorProfile: Boolean = false,
) {
    enum class WbSource(val wireValue: Int) {
        Camera(0),    // use_camera_wb = 1
        Auto(1),      // use_auto_wb   = 1
        Daylight(2),  // user_mul = D65 preset (1.0, 1.0, 1.0, 1.0 — sensor-neutral)
    }

    companion object {
        val Default = RawV3WorkspaceOptions()
    }
}
