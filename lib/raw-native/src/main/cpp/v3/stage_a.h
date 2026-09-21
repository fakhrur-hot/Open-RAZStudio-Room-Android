/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Stage A — RAW → 16-bit linear FP16 BigTIFF cache.
 *  See Plan.md §2 / §3.2 / §5.
 * ─────────────────────────────────────────────────────────────────────────────
 */

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace raw_v3 {

struct StageAOptions {
    int  demosaicAlgorithm  = -1;   // LibRaw user_qual; -1 = RCD/RAZAmaze
    int  highlightMode      = 2;    // LibRaw highlight; 0=clip 1=unclip 2=blend 3..9=rebuild
    /**
     * Post-demosaic highlight desaturation ("Safe Recovery"), 0..1 strength.
     * Independent of `highlightMode` above — fixes a DIFFERENT problem than
     * the CA/lens correction does: LibRaw's highlight=2 "blend" mode can
     * introduce magenta/pink color casts in blown-out smooth highlights
     * (clouds, specular hotspots), because R/G/B clip at slightly different
     * raw levels and the dcraw blend algorithm mixes that mismatch into a
     * false color. This is not an edge artifact (lens TCA doesn't touch it).
     * Fix: reconstruct luminance from all 3 channels (BT.601 weights) and
     * fade chroma toward that neutral luminance as pixels approach/exceed
     * clip, so brightness/detail survives but the false color doesn't.
     * 0 = off (no change vs. today). Applied in runStageA right after
     * demosaic + lensfun correction, before CLAHE.
     */
    float highlightDesaturateStrength = 0.f;
    // 0=camera 1=auto 2=daylight-neutral
    //
    // !! CURRENTLY INERT ON THE DUAL (AMaZE+LMMSE) PATH — verified 2026-08-18.
    // The switch near the top of runStageA does set LibRaw's use_camera_wb /
    // use_auto_wb / user_mul from this, but the dual path then derives its own
    // multipliers: `camMulDual` is built from `colorRef2.cam_mul` (or
    // asShotNeutral) UNCONDITIONALLY and never consults wbSource. Measured:
    // wbSource 0/1/2 on the same CR2 all logged camMul=[2.000,1.000,1.682,1.000]
    // and produced BYTE-IDENTICAL output.
    //
    // So this field only bites on paths that go through plain dcraw_process.
    // Either thread it into camMulDual or leave it alone — but do NOT use it as
    // a diagnostic lever believing it changes the render (it does not).
    int  wbSource           = 0;
    float exposureShift     = 0.f;  // EV stops, -2..+3; 0 → disabled
    int  fbddNoise          = 0;    // LibRaw fbdd_noiserd; 0=off 1=light 2=full
    bool nrEnabled          = false;
    int  nrLuma             = 0;    // 0..100
    int  nrChroma           = 0;    // 0..100
    bool caCorrectionEnabled = true;
    /**
     * Extra median-deviation defringe pass after the RayXie29 span clip, for
     * the residual fringing a span clip structurally cannot reach (edges
     * without clean endpoints, low-amplitude bleed spanning more than one
     * span). See rayxie_defringe.h — including why it is a median and not a
     * guided filter. Independent of caCorrectionEnabled: either pass can run
     * alone. 0 = off; otherwise the strength, 0..1.
     */
    float caGuidedStrength = 0.f;
    std::string lensfunCameraId;    // optional camera override (empty = auto from EXIF)
    std::string lensfunLensId;      // optional lens override (empty = auto from EXIF)
    /**
     * Directory of Lensfun XML database files (materialised from assets).
     * Empty → Lensfun correction disabled. When set, Stage A matches the
     * camera + lens from the RAW's own metadata (LibRaw idata/lens/other)
     * and applies devignetting + distortion + TCA right after demosaic —
     * the same pipeline stage where RawTherapee runs its lensfun transform.
     * If either the camera or the lens can't be confidently matched, the
     * import proceeds normally with no correction (product rule).
     */
    std::string lensfunDbDir;
    /**
     * Focal length override in mm for the Lensfun correction. Manual/adapted
     * lenses report focal_len = 0 in EXIF, which makes the correction math
     * impossible (NormScale divides by focal); the workspace selector asks
     * the user for the focal in that case and passes it here. 0 = use EXIF.
     */
    float lensfunFocalOverrideMm = 0.f;
    /**
     * Zero-DCE guided adaptive devignetting. Row-major square lift map
     * (per-pixel mean |A| of the Zero-DCE curve channels, 256×256 from the
     * RAW's embedded thumbnail), or EMPTY = classic static Lensfun pass.
     * Inside the devignette loop the radial gain is attenuated in deep
     * shadow:  G_final = 1 + (G_lens − 1) · (1 − clamp(aMean/τ, 0, 1)).
     * liftTau is the shadow threshold τ (default 0.7).
     */
    std::vector<float> liftMap;
    int   liftSide = 0;
    float liftTau  = 0.7f;
    /**
     * Manual override deltas applied to LibRaw's per-camera black/white-level
     * defaults. Both are in raw DN, signed: positive `blackLevelDelta` lifts
     * the black point (crushes shadows), negative lowers it (lifts shadows /
     * removes a black tint). Positive `whiteLevelDelta` raises the saturation
     * point (more headroom before clip detection fires), negative lowers it
     * (clips earlier). Default 0 = use LibRaw values unchanged.
     */
    float blackLevelDelta = 0.f;
    float whiteLevelDelta = 0.f;
    /**
     * Fraction of the (black..white) range at which a sensel is considered
     * clipped by the demosaic's highlight reconstruction pass. Default 0.97
     * matches the previously-hardcoded value. Range [0.80..1.00].
     */
    float clipThreshold = 0.97f;
    /**
     * LibRaw adjust_maximum_thr: if the actual in-image maximum is greater
     * than (thr × sensor_maximum), LibRaw uses the actual maximum as the
     * white-point reference instead of the camera-reported sensor cap.
     * This prevents WB channel multipliers from over-scaling near-white
     * pixels past the clipping boundary. Default 0.95 = fuller sensor
     * headroom (eases premature histogram wall); magenta cast is handled
     * by highlightDesaturateStrength (Safe Recovery). 0.0 = disabled.
     * Range [0.0..1.0].
     */
    float adjustMaximumThr = 0.95f;

    // ── AMaZE+VNG dual-decode (demosaicAlgorithm == -3) ─────────────────────
    // These two fields are only consulted when demosaicAlgorithm == -3
    // (RAZ_AMAZE_VNG sentinel). They control the blend-mask contrast threshold
    // that decides per-pixel which decoder contributes.
    //
    // POLARITY (this comment previously said the OPPOSITE and that inversion
    // cost a mis-attributed root cause): the sigmoid emits ~1 for HIGH local
    // contrast, and stage_a's lerp is `t*amaze + (1-t)*vng` — so
    // **blend=1 -> AMaZE (detail), blend=0 -> VNG/LMMSE (flat regions)**.
    // Authoritative references: dual_blend.cpp calcBlendFactor and the Step 5
    // lerp in stage_a.cpp. Trust the code, not this header.
    //
    //  dualContrastThreshold — contrast value at which the sigmoid blend is
    //   50%/50%; expressed in [0..1] (0.2 is RT's default). Ignored when
    //   dualAutoContrast is true — in that case the value is OVERWRITTEN
    //   by the auto-picked threshold and can be read back after runStageA
    //   returns (via the returned StageAMetadata.dualContrastThreshold field).
    //
    //  dualAutoContrast — when true, `buildBlendMask` tile-scans the
    //   luminance plane to find the flattest region and derives a scene-
    //   adaptive threshold. Matches RT's "Auto contrast threshold" button.
    float dualContrastThreshold = 0.2f;
    bool  dualAutoContrast      = true;

    // RAW-domain HDR highlight recovery.
    // When non-empty, the lightweight U-Net is run between unpack() and
    // dcraw_process().  Pass the raw bytes of raw_hdr_recovery.bin loaded
    // from assets. Empty vector = feature disabled (no-op).
    std::vector<uint8_t> hdrModelData;

    // RAW-domain shadow/black recovery.
    // Applied after the HDR highlight pass (also pre-demosaic).
    // Pass the raw bytes of raw_shadow_recovery.bin from assets.
    // Empty vector = feature disabled (no-op).
    std::vector<uint8_t> shadowModelData;

    // Post-demosaic CLAHE highlight recovery.
    // When > 0, applyClahe runs on the sRGB FP16 buffer (after EXIF flip,
    // before the BigTIFF write) with shadowsBoost=0 and
    // highlightsBoost=claheHighlightsBoost. Restores local contrast in
    // specular-blown zones that the HDR U-Net already partially recovered.
    // Range [0..1]; 0 = disabled (no-op).
    float claheHighlightsBoost = 0.f;

    // ── Post-demosaic LMMSE + USM enhancement (ai-enhance) ──────────────────
    // Runs on the RGBA FP16 buffer after AMaZE+LMMSE+EXIF-flip, before the
    // BigTIFF write. Disabled when enhanceEnabled == false.
    // Params are pre-scaled to [0,1] domain by the Kotlin repository layer:
    //   noiseVariance  = 8-bit value / 255²
    //   usmThreshold   = 8-bit value / 255
    bool  enhanceEnabled         = false;
    int   enhanceWindowSize      = 5;
    float enhanceNoiseVar        = 0.f;    // σ_n² in [0,1]² domain
    float enhanceUsmRadius       = 1.0f;
    float enhanceUsmAmount       = 1.5f;
    float enhanceUsmThreshold    = 0.f;    // soft-ramp onset in [0,1] domain
    float enhanceUsmEdgeThreshold = 0.08f; // halo suppression σ cutoff in [0,1] domain
    bool  enhanceSkipDenoise     = false;
    bool  enhanceSkipSharpen     = false;
    bool  enhanceGuidedFilter    = true;   // edge-aware smoothing before USM

    // When true, the source DNG is a LinearRaw (PhotometricInterpretation=34892)
    // or Adobe Enhanced NR DNG — WB has already been baked into the pixel data.
    // Suppresses LibRaw scale_colors WB in the !rawMosaic fallback path so we
    // don't double-apply WB → pink/red cast.
    bool  isLinearRaw            = false;
};

struct StageAMetadata {
    uint32_t width  = 0;
    uint32_t height = 0;
    int      orientation = 1;       // EXIF orientation 1..8
    std::string cameraMake;
    std::string cameraModel;
    std::string lensMake;
    std::string lensModel;
    int      iso = 0;
    float    shutterSpeed = 0.f;
    float    aperture = 0.f;
    float    focalLength = 0.f;
    int      lensId = 0;            // Canon lens_id from MakerNotes (LibRaw lens.makernotes.LensID)
    int      colorTemperature = 0;  // As-shot Kelvin from Canon MakerNote (LibRaw color.WBCT_Coeffs[0][0])
    std::string dateTimeOriginal;
    bool     success = false;
    std::string errorMessage;
    // Set by runStageA when demosaicAlgorithm == -3 (RAZ_AMAZE_VNG).
    // Carries back the auto-resolved blend-mask contrast threshold so the
    // UI can show it when the user has "Auto contrast" enabled.
    float    dualContrastThreshold = -1.f;  // -1 = not applicable
};

/**
 * Run the full Stage A pipeline:
 *
 *   1. LibRaw open + unpack [rawFilePath]
 *   2. Apply user demosaic / NR / CA settings via LibRaw params
 *   3. process() → 16-bit BGR
 *   4. (Optional) Lensfun corrections
 *   5. Convert to RGBA FP16 (alpha = 1.0)
 *   6. Write to [outTifPath] in the Stage A BigTIFF format
 *
 * The output file is overwritten if it exists. Caller is expected to have
 * created the parent directory.
 */
StageAMetadata runStageA(
    const std::string& rawFilePath,
    const std::string& outTifPath,
    const StageAOptions& options);

/**
 * Bake a LUMA-scale map into an existing Stage A FP16 BigTIFF, in place.
 *
 * For each TIFF pixel, RGB is multiplied by the (bilinearly resampled) scale at
 * that location, so a luma-only correction — e.g. a neural denoise computed on
 * a display-space proxy as `denoisedLuma / originalLuma` — broadcasts to RGB
 * while preserving chroma ratios (hue/saturation). The [scale] map is
 * [scaleW × scaleH] row-major and may be lower-res than the TIFF (it is
 * resampled to the TIFF's native dimensions). Per-pixel scale is clamped to
 * [0.5, 2.0] for safety. Alpha is untouched. Returns true on success.
 *
 * Used by the "denoised baseline baked at import" path so the preview AND the
 * save inherit one clean 16-bit foundation without a per-render neural pass.
 */
bool applyLumaScaleToStageA(
    const std::string& tifPath,
    const float* scale,
    int scaleW,
    int scaleH);

/*
 * Bake a per-channel 256×3 tone-curve LUT into the FP16 Stage A TIFF, in place.
 * [lut768] is 256 entries × 3 channels interleaved (R,G,B per input index),
 * 0..255 → mapped output. Each pixel's RGB (FP16, sRGB-gamma, [0,1]) is sampled
 * through its channel's curve with bilinear interpolation (identical math to
 * the Stage C toneCurveLut tap), converted back to FP16, and rewritten. Alpha
 * is untouched. Returns true on success.
 *
 * Used by the "Camera Color Profile baked at import" path (route A): the
 * histogram-matched camera curve is baked once into A.tif so the Stage B
 * preview AND Stage C export inherit the in-camera colour directly, instead of
 * each stage re-applying the LUT.
 */
bool applyToneCurveToStageA(
    const std::string& tifPath,
    const uint8_t* lut768);

/*
 * Apply Lensfun geometric correction (devignette + distortion + TCA) IN PLACE to
 * an existing Stage-A FP16 BigTIFF. This is the non-RAW (JPEG/PNG/TIFF) path's
 * equivalent of the Lensfun block inside runStageA: the synthetic Stage A writes
 * the decoded bitmap as an A.tif with no correction, then this re-runs the exact
 * same lfa_match_strict + lfa_correct_rgba_f16 on the FP16 buffer so JPEGs get
 * the same lens corrections RAW gets. Camera/lens are matched from the passed
 * strings (the UI-selected DB names for adapted lenses, or EXIF names); focalMm
 * is the manual override (adapted lenses report no focal). Returns true only when
 * a confident match was found AND a correction was actually applied. No-op /
 * false when dbDir is empty, no match, or no usable calibration — the A.tif is
 * left untouched in those cases (product rule: never guess a correction).
 */
/**
 * Optional chromatic-aberration correction hook, invoked by [runStageA] on the
 * demosaiced sRGB-encoded FP16 RGBA buffer (post-demosaic, BEFORE the Lensfun
 * geometric pass) when `StageAOptions::caCorrectionEnabled` is set.
 *
 * WHY A HOOK: the implementation (`rayxie_correct_fringing`) lives in
 * raw_decoder.cpp, which is NOT part of the desktop razbatch target — calling
 * it directly from stage_a.cpp would break the desktop link. The Android JNI
 * layer installs it at runtime instead; on desktop the hook stays null and CA
 * is simply skipped.
 *
 * The hook must operate IN PLACE and must not reallocate the buffer.
 * `threshold` is the green-gradient magnitude in uint16 units (2000 ≈ 3%).
 */
using CaHook = void (*)(uint16_t* rgbaF16, int width, int height, int threshold);
void setCaHook(CaHook hook);

bool applyLensfunToStageA(
    const std::string& tifPath,
    const std::string& camMaker,
    const std::string& camModel,
    const std::string& lensMaker,
    const std::string& lensModel,
    float focalMm,
    float aperture,
    const std::string& lensfunDbDir);

}  // namespace raw_v3
