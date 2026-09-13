/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

import android.content.Context
import android.content.SharedPreferences

/**
 * Internal working buffer precision for the RAW pipeline.
 *
 * [BIT_8]   — `Bitmap.Config.ARGB_8888` (8 bits per channel). Universal compatibility, ~90 MB
 *             for a 45 MP working bitmap. The current/default path.
 * [BIT_16]  — `Bitmap.Config.RGBA_F16` (16-bit float per channel). API 26+ only.
 *             ~360 MB for a 45 MP working bitmap. Eliminates banding from tone-curve and HSL
 *             operations, and lets the bitmap carry a real [android.graphics.ColorSpace] tag.
 */
enum class WorkspaceBitDepth(val displayName: String, val description: String) {
    BIT_8("8-bit",
        "Standard precision — universal compatibility, lowest memory"),
    BIT_16("16-bit float",
        "Float precision — wide-gamut accurate, no tone-curve banding (API 26+, 4× memory)"),
}

/**
 * Highlight reconstruction mode applied during native decode (v2 §7.2). Runs after demosaic,
 * before the color matrix, so clipped-channel reconstruction operates on linear sensor data.
 *
 * - [Off]         — clipped channels stay clipped; legacy behaviour.
 * - [Clip]        — uniformly clamp all three channels to the lowest clipped white point. Safe
 *                   default for 8-bit workspace where extra highlight headroom would be lost
 *                   in the output transfer anyway.
 * - [Reconstruct] — luminance-constrained reconstruction of the partially-clipped channel from
 *                   the unclipped ones. Default for 16-bit workspace.
 */
/**
 * Highlight recovery — maps to LibRaw `imgdata.params.highlight`:
 *   Off          → 1  (unclipped, leaves pink — debug only)
 *   Clip         → 0  (hard clip at sensor white; what v3 RCD has been using)
 *   Blend        → 2  (soft blend across the clip boundary — safest for RCD)
 *   Reconstruct3 → 3  (rebuild from neighbours, conservative)
 *   Reconstruct5 → 5  (default sweet spot — colour-aware reconstruction)
 *   Reconstruct7 → 7  (more aggressive, may shift hue on saturated highlights)
 *   Reconstruct9 → 9  (most aggressive — last resort for severely blown skies)
 *
 * Legacy `Reconstruct` entry kept for backward compat with old prefs; maps to
 * [Reconstruct5] at apply time so users on stored prefs land on a sensible value.
 */
enum class HighlightRecoveryMode(val displayName: String, val librawValue: Int) {
    Off("Off", 1),
    Clip("Hard clip", 0),
    Blend("Soft blend", 2),
    Reconstruct3("Rebuild soft", 3),
    Reconstruct5("Rebuild medium", 5),
    Reconstruct7("Rebuild strong", 7),
    Reconstruct9("Rebuild max", 9),
    /** Legacy alias — old prefs map here, treated as Reconstruct5 at apply. */
    Reconstruct("Rebuild medium", 5),
}

/**
 * LibRaw `output_color` working space. Selecting wider than sRGB lets the
 * downstream FP16 pipeline (CLAHE / LUT / curves) operate on the camera's
 * actual gamut instead of a pre-clipped sRGB triangle. Gamut compression
 * happens only at JPEG/WebP encode.
 *
 *   Raw          → 0 (camera primaries, needs CCM; debug only)
 *   sRGB         → 1 (legacy default)
 *   AdobeRgb     → 2 (~13% wider greens/cyans)
 *   WideGamut    → 3 (Adobe Wide Gamut RGB)
 *   ProPhoto     → 4 (encompasses all real scene colors — recommended default)
 *   Xyz          → 5
 *   Aces         → 6 (HDR-ready, very wide)
 *   DciP3        → 7 (modern phone OLEDs)
 *   Rec2020      → 8 (HDR delivery)
 */
enum class LibRawOutputColor(val displayName: String, val librawValue: Int) {
    Raw("Camera raw primaries", 0),
    SRgb("sRGB", 1),
    AdobeRgb("Adobe RGB", 2),
    WideGamut("Wide Gamut RGB", 3),
    ProPhoto("ProPhoto RGB", 4),
    Xyz("XYZ", 5),
    Aces("ACES", 6),
    DciP3("Display P3", 7),
    Rec2020("Rec.2020", 8),
}

/**
 * Color space a `.cube` LUT was authored against. Selecting the wrong one
 * shifts the LUT's intent — a Rec.709 film-look LUT fed ProPhoto input
 * desaturates; a ProPhoto LUT fed sRGB clips reds. Per-LUT picker because
 * `.cube` files rarely declare this in metadata.
 */
enum class LutInputSpace(val displayName: String) {
    Rec709("Rec.709 / sRGB"),
    ProPhoto("ProPhoto RGB"),
    Aces("ACES"),
    DciP3("Display P3"),
}

/**
 * Purple/violet colour-fringing correction. Combines LibRaw's lateral CA
 * correction at decode (`aber[]`) with a runtime purple-fringe desaturation
 * pass (mjambon/purple-fringe algorithm: detect magenta pixels adjacent to
 * high-contrast edges and pull their chroma toward zero).
 *
 *   Off    → no correction
 *   Light  → LibRaw aber[] only (cheap, fixes lateral CA)
 *   Strong → LibRaw aber[] + runtime purple-fringe pass (full correction)
 */
enum class ColorFringingMode(val displayName: String) {
    Off("Off"),
    Light("Light (lateral CA only)"),
    Strong("Strong (CA + purple-fringe)"),
}

/**
 * Output bit-depth / HDR pathway picker (v2 §7.6). Visible only when [WorkspaceBitDepth.BIT_16]
 * is selected — the HDR options require float precision through to encode.
 *
 * The HDR entries are gated as "Coming soon" in the v2 first cut (Phase 2 of v2 enables the
 * encoders). The data class still carries the selection so sidecar XMP round-trips it.
 */
enum class OutputBitDepthMode(val displayName: String, val isHdr: Boolean, val enabled: Boolean) {
    SdrPng16Tiff("SDR (PNG 16 / TIFF)", isHdr = false, enabled = true),
    HdrAvifHlg("HDR AVIF (HLG, BT.2020)", isHdr = true, enabled = true),
    HdrJpegXlPq("HDR JPEG XL (PQ, BT.2020)", isHdr = true, enabled = true),
}

/**
 * Per-session workspace configuration captured by the [WorkspaceSelectorSheet] dialog
 * and threaded through both [com.RAZStudio.StudioRoom.feature.photo_editor.raw.PreviewPipeline]
 * and [com.RAZStudio.StudioRoom.feature.photo_editor.raw.FullResPipeline].
 *
 * [Default] is `BIT_8 + SRGB + RAZ_AMAZE`, which is byte-for-byte identical to the pre-upgrade
 * pipeline output. Callsites that do not surface the dialog (e.g. the batch processor) MUST use
 * [Default] to preserve backward compatibility.
 *
 * v2 additions (see `.kiro/specs/raw-pipeline-v2-integration/requirements.md §1`):
 * [highlightRecovery] — pre-color-matrix highlight handling.
 * [nrEnabled], [nrLuma], [nrChroma] — demosaic-aware noise reduction.
 * [dcpProfileId]      — selected camera profile ("auto" / asset key / user file basename).
 * [outputBitDepthMode] — SDR vs HDR output pathway picker.
 * [sidecarEnabled]    — write/read XMP sidecar next to the source RAW.
 */
data class WorkspaceConfig(
    val bitDepth: WorkspaceBitDepth = WorkspaceBitDepth.BIT_16,
    val colorGamut: RawColorSpace = RawColorSpace.SRGB,
    val demosaicAlgorithm: DemosaicAlgorithm = DemosaicAlgorithm.RAZ_AMAZE_VNG,
    val highlightRecovery: HighlightRecoveryMode = HighlightRecoveryMode.Reconstruct5,
    val nrEnabled: Boolean = false,
    val nrLuma: Int = 0,
    val nrChroma: Int = 0,
    val dcpProfileId: String = DCP_AUTO,
    val outputBitDepthMode: OutputBitDepthMode = OutputBitDepthMode.HdrAvifHlg,
    val sidecarEnabled: Boolean = true,
    /**
     * Rayxie chromatic aberration correction at full-res decode. Defaults to on
     * (matches the algorithm's original ship state). Users with already-corrected
     * lenses or who prefer the raw uncorrected look can switch it off in the
     * workspace selector. The half-res preview never runs CA regardless — CA fringes
     * are invisible at half-resolution and the saved CPU keeps the canvas responsive.
     */
    val caCorrectionEnabled: Boolean = true,
    /**
     * Lensfun lens correction (distortion + vignetting + TCA at Stage A,
     * post-demosaic — both colour routes). [lensfunDbDir] empty = disabled.
     * [lensfunCameraId]/[lensfunLensId] carry the UI-resolved DB names so the
     * decode applies exactly what the workspace selector displayed. All three
     * are set together only when the camera AND lens were confidently matched
     * — a partial match imports with no correction (product rule).
     */
    val lensfunDbDir: String = "",
    val lensfunCameraId: String = "",
    val lensfunLensId: String = "",
    /**
     * Focal length (mm) for the Lensfun correction when the RAW's EXIF has
     * none (manual/adapted lenses). 0 = use EXIF focal.
     */
    val lensfunFocalOverrideMm: Float = 0f,
    /**
     * How the [lensfunLensId] above was arrived at, as the native matcher's
     * `LfaConfidence` ordinal (0 none · 1 low · 2 medium · 3 high). Persisted
     * rather than recomputed so the editor can tell an auto-applied HIGH match
     * apart from one the user confirmed, and so a re-open does not silently
     * re-guess. 3 when the user picked the lens by hand — an explicit choice is
     * the strongest signal there is.
     */
    val lensfunMatchConfidence: Int = 0,
    /**
     * Adapted-lens mode: the lens is on a dumb adapter, so its mount cannot
     * match the body's. Drops the mount criterion from matching while keeping
     * the sensor-format preference. Per-workspace because it is a property of
     * the physical setup, not a global preference.
     */
    val lensfunAdaptedMode: Boolean = false,
    /**
     * LibRaw WB source. Maps to RawV3WorkspaceOptions.WbSource on open.
     * Values: 0 = Camera, 1 = Auto, 2 = Daylight. Stored as int so the
     * cross-package import (raw/model ↔ raw_v3) doesn't pull a v3 type
     * into the v2 data class. The selector and toV3() owns the mapping.
     */
    val wbSourceOrdinal: Int = 0,
    /** LibRaw exp_correc / exp_shift in EV stops, clamped -2..+3 at apply. */
    val exposureShiftEv: Float = 0f,
    /** LibRaw fbdd_noiserd; 0 = off, 1 = light, 2 = full. */
    val fbddNoise: Int = 1,
    /**
     * Manual override deltas (raw DN, signed) added to LibRaw's per-camera
     * black/white-level defaults at Stage A. 0 = use LibRaw values unchanged.
     * Useful when LibRaw's camera table is a few counts off — black-delta
     * lifts/lowers the shadow floor, white-delta widens/narrows the saturation
     * cap. Persist across re-decodes (workspace-scoped, not per-edit).
     */
    val blackLevelDelta: Float = 0f,
    val whiteLevelDelta: Float = 0f,
    /**
     * Fraction of the (black..white) range at which a sensel is considered
     * clipped by the demosaic's highlight reconstruction. Default 0.97 matches
     * the previously-hardcoded value; lower values (0.85..0.95) ease clip
     * detection for files with slightly tinted highlights below the sensor
     * cap. Range [0.80..1.00].
     */
    val clipThreshold: Float = 0.97f,
    /**
     * AMaZE+VNG dual-decode contrast threshold.
     * Only used when [demosaicAlgorithm] == [DemosaicAlgorithm.RAZ_AMAZE_VNG].
     * Controls the sigmoid midpoint in [0..1] — pixels with local contrast
     * below this value blend toward VNG; above it blend toward AMaZE.
     * Ignored (and overwritten at decode time) when [dualAutoContrast] is true.
     */
    val dualContrastThreshold: Float = 0.2f,
    /**
     * When true, Stage A auto-detects the optimal [dualContrastThreshold] by
     * scanning the image for the flattest tile (matches RawTherapee's
     * "Auto contrast threshold" behaviour). The resolved value is echoed back
     * in [RawV3Engine.StageAResult.dualContrastThreshold].
     */
    val dualAutoContrast: Boolean = true,
    /** When true, RawV3Coordinator runs U2Net + Sobel after Stage A. */
    val subjectDetectionEnabled: Boolean = true,
    /**
     * Camera-style finish — adds a small, fixed tonal/colour boost
     * (saturation, contrast, gentle shadow lift, slight clarity +
     * sharpness) at the bottom of the action stack so the default
     * "open + save" output has the same punch as an in-camera JPG
     * instead of looking flat compared to the camera's own processor.
     *
     * The finish is implemented as a synthetic `RawAction` injected
     * at the start of the flatten() input list, so any user edits in
     * the editor's Light/Color tabs stack additively on top — turning
     * the finish off does not undo user work.
     *
     * Default on so the default-save look matches what users see when
     * comparing to their camera's JPG cousin file.
     */
    val cameraStyleFinishEnabled: Boolean = true,
    /**
     * LibRaw `output_color` working space the FP16 pipeline operates in.
     * Adobe RGB is the default — wide enough to hold most camera gamuts
     * without the ProPhoto green-imaginary-primary that caused green-tint
     * issues with sRGB-authored LUTs, but narrow enough that highlight
     * recovery doesn't push the haze model into pink-tint territory.
     */
    // Pipeline is locked to sRGB end-to-end (matches ImageToolbox). LibRaw
    // output_color is hardcoded to 1 in raw_decoder.cpp; the GLSL shader
    // workspaceSpace is pinned to 1. The selector UI is hidden — this
    // field stays in the config so old sidecars deserialise.
    val libRawOutputColor: LibRawOutputColor = LibRawOutputColor.SRgb,
    /**
     * Per-LUT working-space tag for `.cube` lookups. Stage C transforms the
     * pipeline pixel into this space before sampling the LUT, then back into
     * [libRawOutputColor]. ACES is the default per the user's tested
     * workspace — most professional .cube packs target scene-referred
     * ACEScg input.
     */
    // LUT input space locked to Rec.709 (== sRGB primaries). Pipeline runs
    // .cube LUTs directly on sRGB pixels, matching ImageToolbox's design
    // (Trickle.applyCubeLut on an sRGB ARGB_8888 Bitmap).
    val lutInputSpace: LutInputSpace = LutInputSpace.Rec709,
    /**
     * Sensor calibration — applies LibRaw's per-channel `cblack[0..3]` black
     * level subtraction at decode. Required for accurate shadow colour on
     * Canon CR3 and Sony A7R IV / A1 where black levels drift per channel
     * (without this, shadows pick up a slight green/magenta cast that no
     * amount of WB can remove).
     */
    val sensorCalibrationEnabled: Boolean = true,
    /**
     * Colour fringing correction. Off / Light (LibRaw aber[]) / Strong
     * (aber[] + runtime purple-fringe desat pass). Strong is the recommended
     * default for wide-aperture shots on consumer lenses.
     */
    val colorFringingMode: ColorFringingMode = ColorFringingMode.Strong,

    /**
     * Magic Lantern CR2 intelligence smart defaults. When true, [applyMLDefaults()]
     * uses CR2 EXIF metadata (lens ID, ISO, colour temperature) to apply lens-aware
     * finishing trims, ISO-aware NR defaults, and WB scene bias at file-open time.
     * When false, applyMLDefaults() returns early without applying any ML adjustments.
     *
     * Surfaced as "Smart Defaults (lens + noise)" checkbox in WorkspaceSelectorSheet.
     */
    val smartDefaultsEnabled: Boolean = true,
    /**
     * AI Color Enhance — the replacement for [smartDefaultsEnabled]. Fuses the
     * neural Zero-DCE low-light score with histogram metrics into a global
     * auto-enhance (saturation/vibrance/WB/CLAHE) at open, via
     * [UserMacro.createAiColorEnhance]. Default on; toggled per-photo from the
     * Color tab. Instant global tier — does NOT use the heavy segmentation
     * models (Stage-2 local blends deferred).
     */
    val aiColorEnhanceEnabled: Boolean = true,
    /**
     * Camera Color Profile mode (route A in the workspace selector). When true,
     * a camera-RAW open keeps the FULL 16-bit RAW decode (RAW detail) but matches
     * the in-camera color via an auto-matched tone curve derived from the embedded
     * JPEG (RawTherapee-style). When false (route B "RAZStudio RAW depth"), the
     * RAZStudio processing path runs (Auto Expose / AI Reconstruct / AI Enhance /
     * Guided Filter). The difference is colour only — both keep RAW detail.
     *
     * Default = true (route A): Camera Color Profile is the out-of-the-box
     * experience; the user can switch to RAZStudio RAW depth per open.
     */
    val useCameraColorProfile: Boolean = true,
    /**
     * Route A only. When true, apply an edge-aware chroma guided-filter pass
     * on the export bitmap after the camera colour-profile curve is applied.
     * Smooths colour-cast transitions in flat areas (sky, skin) without
     * blurring luminance detail at edges. Export-time pass only — the live
     * GL preview is unaffected.
     *
     * DEFAULT OFF: at radius 16 / eps 0.01 / scale 4 the chroma smoothing is
     * aggressive enough to soften fine COLORED detail and bleed colour across
     * edges, which reads as "lower resolution" on colourful textures. Left as
     * an opt-in for noisy/banded shots. (Luminance is never filtered, so true
     * luma sharpness is unaffected either way.)
     */
    val cameraProfileGuidedFilter: Boolean = false,
    /**
     * Subject-protection level for Auto Exposure (0..1).
     * Mirrors UserMacro.aeSubjectProtection — stored here so the workspace
     * setting persists across photo opens without requiring a manual AE tap.
     * 1.0 = full mean + P95 cap (default).
     */
    val aeSubjectProtection: Float = 0.95f,
    /**
     * Film simulation profile baked into Stage A at decode time.
     * DEFAULT = no transform applied (standard linear pipeline).
     * Changing this after opening requires re-decode.
     */
    val filmProfile: FilmProfile = FilmProfile.DEFAULT,
    /**
     * Grain intensity applied alongside [filmProfile].
     * Only has an effect when [filmProfile] != DEFAULT.
     */
    val filmGrainLevel: FilmGrainLevel = FilmGrainLevel.OFF,
    /**
     * Run the RAW-domain HDR highlight recovery U-Net between LibRaw::unpack()
     * and dcraw_process(). Commit-on-open — changing requires re-decoding.
     * No-op if models/raw_hdr_recovery.bin is absent from assets.
     */
    val hdrRecovery: Boolean = true,
    /** Run shadow/black recovery U-Net pre-demosaic. No-op if model absent. */
    val shadowRecovery: Boolean = true,
    /** Post-demosaic LMMSE denoise + thresholded USM sharpen on FP32 buffer. Commit-on-open. */
    val enhanceEnabled: Boolean = false,
    /**
     * Edge-aware guided-filter smoothing pass. Standalone option, independent of
     * [enhanceEnabled]: Stage A runs it whenever this is on — on its own, or
     * between LMMSE denoise and USM sharpening when AI Enhance is also on.
     * Default on for smoother tonal transitions; toggle off for a sharper, more
     * textured look.
     */
    val enhanceGuidedFilter: Boolean = true,
    /**
     * CLAHE highlight recovery boost [0..1]. Kept at 0 — the 0.35 default
     * caused per-tile banding on flat sky regions (CLAHE tile boundary artifacts
     * amplified on uniform areas). No separate UI control.
     */
    val claheHighlightsBoost: Float = 0f,
    /**
     * Default output format name (matches RawExportFormat.name).
     * Stored as a String to avoid a cross-package dependency from raw.model
     * into presentation.raw. Resolved to RawExportFormat in WorkspaceSelectorSheet
     * and RawExportScreen. Defaults to "JPG" — always 8-bit JPEG output.
     */
    val defaultExportFormat: String = "JPG",
    /**
     * LibRaw adjust_maximum_thr: prevents WB channel scaling from pushing
     * near-white pixels past the sensor clip ceiling before highlight
     * reconstruction can act. Range [0.0..1.0]. UI levels (editor selector +
     * batch): Off 0.0 / Low 0.75 (dcraw classic) / Standard 0.95 /
     * Strong 0.85 (default). Lower thr = more aggressive white-level pull-down;
     * Strong pairs with Stage A Safe Recovery desat (0.95) for magenta-free
     * opens. Standard 0.95 keeps fuller sensor headroom when the user wants it.
     */
    val highlightProtection: Float = 0.85f,
) {
    companion object {
        /** Default workspace: 16-bit sRGB + RAZ_AMAZE, highlight Reconstruct,
         *  FBDD light noise reduction, subject detection on. */
        val Default: WorkspaceConfig = WorkspaceConfig()

        /** Sentinel value for [dcpProfileId] meaning "pick best match from EXIF make/model". */
        const val DCP_AUTO = "auto"

        // ── SharedPreferences persistence ──────────────────────────────────────
        //
        // We store enum values by `.name` (never by ordinal) so reordering or
        // inserting enum entries in a future version cannot silently shift the
        // stored selection to a different option. Unknown names fall back to
        // [Default] field-by-field.

        const val PREFS_NAME = "raw_workspace_prefs"
        private const val KEY_BIT_DEPTH = "bit_depth"
        private const val KEY_COLOR_GAMUT = "color_gamut"
        private const val KEY_DEMOSAIC = "demosaic_algo"
        private const val KEY_HIGHLIGHT_RECOVERY = "highlight_recovery"
        private const val KEY_NR_ENABLED = "nr_enabled"
        private const val KEY_NR_LUMA = "nr_luma"
        private const val KEY_NR_CHROMA = "nr_chroma"
        private const val KEY_DCP_PROFILE = "dcp_profile_id"
        private const val KEY_OUTPUT_BIT_DEPTH = "output_bit_depth_mode"
        private const val KEY_SIDECAR = "sidecar_enabled"
        private const val KEY_CA_CORRECTION = "ca_correction_enabled"
        private const val KEY_WB_SOURCE_ORDINAL = "wb_source_ordinal"
        private const val KEY_EXPOSURE_SHIFT_EV = "exposure_shift_ev"
        private const val KEY_FBDD_NOISE = "fbdd_noise"
        private const val KEY_SUBJECT_DETECTION = "subject_detection_enabled"
        private const val KEY_CAMERA_STYLE_FINISH = "camera_style_finish_enabled"
        private const val KEY_LIBRAW_OUTPUT_COLOR = "libraw_output_color"
        private const val KEY_LUT_INPUT_SPACE = "lut_input_space"
        private const val KEY_SENSOR_CALIBRATION = "sensor_calibration_enabled"
        private const val KEY_COLOR_FRINGING = "color_fringing_mode"
        private const val KEY_DUAL_CONTRAST_THRESHOLD = "dual_contrast_threshold"
        private const val KEY_DUAL_AUTO_CONTRAST = "dual_auto_contrast"

        private const val KEY_USE_CAMERA_COLOR_PROFILE = "use_camera_color_profile"
        private const val KEY_AE_SUBJECT_PROTECTION = "ae_subject_protection"
        private const val KEY_FILM_PROFILE = "film_profile_id"
        private const val KEY_FILM_GRAIN_LEVEL = "film_grain_level_id"
        private const val KEY_HDR_RECOVERY    = "hdr_recovery"
        private const val KEY_SHADOW_RECOVERY = "shadow_recovery"
        private const val KEY_ENHANCE_ENABLED        = "enhance_enabled"
        private const val KEY_ENHANCE_GUIDED_FILTER  = "enhance_guided_filter"
        private const val KEY_CAMERA_PROFILE_GUIDED_FILTER = "camera_profile_guided_filter"
        private const val KEY_CLAHE_HIGHLIGHTS_BOOST = "clahe_highlights_boost"
        private const val KEY_DEFAULT_EXPORT_FORMAT  = "default_export_format"
        private const val KEY_HIGHLIGHT_PROTECTION   = "highlight_protection"
        private const val KEY_SMART_DEFAULTS         = "smart_defaults_enabled"

        /** Resolve the prefs file consistently across the project. */
        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun fromPrefs(prefs: SharedPreferences): WorkspaceConfig {
            val bitDepth = prefs.getString(KEY_BIT_DEPTH, null)?.let { name ->
                runCatching { WorkspaceBitDepth.valueOf(name) }.getOrNull()
            } ?: Default.bitDepth
            val colorGamut = prefs.getString(KEY_COLOR_GAMUT, null)?.let { name ->
                runCatching { RawColorSpace.valueOf(name) }.getOrNull()
            } ?: Default.colorGamut
            val demosaic = prefs.getString(KEY_DEMOSAIC, null)
                ?.let { DemosaicAlgorithm.fromName(it) }
                ?: Default.demosaicAlgorithm
            val highlightRecovery = prefs.getString(KEY_HIGHLIGHT_RECOVERY, null)?.let { name ->
                runCatching { HighlightRecoveryMode.valueOf(name) }.getOrNull()
            } ?: Default.highlightRecovery
            val nrEnabled = prefs.getBoolean(KEY_NR_ENABLED, Default.nrEnabled)
            val nrLuma = prefs.getInt(KEY_NR_LUMA, Default.nrLuma).coerceIn(0, 100)
            val nrChroma = prefs.getInt(KEY_NR_CHROMA, Default.nrChroma).coerceIn(0, 100)
            val dcpProfileId = prefs.getString(KEY_DCP_PROFILE, Default.dcpProfileId)
                ?: Default.dcpProfileId
            val outputMode = prefs.getString(KEY_OUTPUT_BIT_DEPTH, null)?.let { name ->
                runCatching { OutputBitDepthMode.valueOf(name) }.getOrNull()
            } ?: Default.outputBitDepthMode
            val sidecarEnabled = prefs.getBoolean(KEY_SIDECAR, Default.sidecarEnabled)
            val caEnabled = prefs.getBoolean(KEY_CA_CORRECTION, Default.caCorrectionEnabled)
            val wbOrd     = prefs.getInt(KEY_WB_SOURCE_ORDINAL, Default.wbSourceOrdinal)
                .coerceIn(0, 2)
            val expEv     = prefs.getFloat(KEY_EXPOSURE_SHIFT_EV, Default.exposureShiftEv)
                .coerceIn(-2f, 3f)
            val fbdd      = prefs.getInt(KEY_FBDD_NOISE, Default.fbddNoise).coerceIn(0, 2)
            val subjDet   = prefs.getBoolean(KEY_SUBJECT_DETECTION, Default.subjectDetectionEnabled)
            val finish    = prefs.getBoolean(KEY_CAMERA_STYLE_FINISH, Default.cameraStyleFinishEnabled)
            // Pipeline is locked to sRGB / Rec.709 (matches ImageToolbox).
            // Force these to the canonical values even if a persisted pref
            // from an older build carries AdobeRgb / ProPhoto / ACES / etc.
            // The selector UI is hidden so the user can't drift them back.
            // Restore the per-pref read once a real wide-gamut pipeline is
            // wired end-to-end.
            val outColor = LibRawOutputColor.SRgb
            val lutSpace = LutInputSpace.Rec709
            val sensorCal = prefs.getBoolean(KEY_SENSOR_CALIBRATION, Default.sensorCalibrationEnabled)
            val fringe = prefs.getString(KEY_COLOR_FRINGING, null)?.let { name ->
                runCatching { ColorFringingMode.valueOf(name) }.getOrNull()
            } ?: Default.colorFringingMode
            val dualThr    = prefs.getFloat(KEY_DUAL_CONTRAST_THRESHOLD, Default.dualContrastThreshold)
                .coerceIn(0f, 1f)
            val dualAuto   = prefs.getBoolean(KEY_DUAL_AUTO_CONTRAST, Default.dualAutoContrast)

            val useCamColorProfile = prefs.getBoolean(KEY_USE_CAMERA_COLOR_PROFILE, Default.useCameraColorProfile)
            val aeProtection   = prefs.getFloat(KEY_AE_SUBJECT_PROTECTION, Default.aeSubjectProtection)
                .coerceIn(0f, 1f)
            val filmProfile    = FilmProfile.fromId(prefs.getString(KEY_FILM_PROFILE, null))
            val filmGrainLevel = FilmGrainLevel.fromId(prefs.getString(KEY_FILM_GRAIN_LEVEL, null))
            val hdrRecovery    = prefs.getBoolean(KEY_HDR_RECOVERY,    Default.hdrRecovery)
            val shadowRecovery = prefs.getBoolean(KEY_SHADOW_RECOVERY, Default.shadowRecovery)
            val enhanceEnabled        = prefs.getBoolean(KEY_ENHANCE_ENABLED,        Default.enhanceEnabled)
            val enhanceGuidedFilter   = prefs.getBoolean(KEY_ENHANCE_GUIDED_FILTER,  Default.enhanceGuidedFilter)
            val camProfileGuidedFilter = prefs.getBoolean(KEY_CAMERA_PROFILE_GUIDED_FILTER, Default.cameraProfileGuidedFilter)
            val claheHighlightsBoost  = prefs.getFloat(KEY_CLAHE_HIGHLIGHTS_BOOST,  Default.claheHighlightsBoost)
            val defaultExportFormat   = prefs.getString(KEY_DEFAULT_EXPORT_FORMAT,  Default.defaultExportFormat)
                ?: Default.defaultExportFormat
            val highlightProtection   = prefs.getFloat(KEY_HIGHLIGHT_PROTECTION, Default.highlightProtection)
                .coerceIn(0f, 1f)
            val smartDefaults         = prefs.getBoolean(KEY_SMART_DEFAULTS, Default.smartDefaultsEnabled)
            return WorkspaceConfig(
                bitDepth = bitDepth,
                colorGamut = colorGamut,
                demosaicAlgorithm = demosaic,
                highlightRecovery = highlightRecovery,
                nrEnabled = nrEnabled,
                nrLuma = nrLuma,
                nrChroma = nrChroma,
                dcpProfileId = dcpProfileId,
                outputBitDepthMode = outputMode,
                sidecarEnabled = sidecarEnabled,
                caCorrectionEnabled = caEnabled,
                wbSourceOrdinal = wbOrd,
                exposureShiftEv = expEv,
                fbddNoise = fbdd,
                subjectDetectionEnabled = subjDet,
                cameraStyleFinishEnabled = finish,
                libRawOutputColor = outColor,
                lutInputSpace = lutSpace,
                sensorCalibrationEnabled = sensorCal,
                colorFringingMode = fringe,
                dualContrastThreshold = dualThr,
                dualAutoContrast = dualAuto,

                useCameraColorProfile = useCamColorProfile,
                cameraProfileGuidedFilter = camProfileGuidedFilter,
                aeSubjectProtection = aeProtection,
                filmProfile         = filmProfile,
                filmGrainLevel      = filmGrainLevel,
                hdrRecovery          = hdrRecovery,
                shadowRecovery       = shadowRecovery,
                enhanceEnabled       = enhanceEnabled,
                enhanceGuidedFilter  = enhanceGuidedFilter,
                claheHighlightsBoost = claheHighlightsBoost,
                defaultExportFormat  = defaultExportFormat,
                highlightProtection  = highlightProtection,
                smartDefaultsEnabled = smartDefaults,
            )
        }

        fun fromPrefs(context: Context): WorkspaceConfig = fromPrefs(prefs(context))
    }

    fun saveToPrefs(prefs: SharedPreferences) {
        prefs.edit()
            .putString(KEY_BIT_DEPTH, bitDepth.name)
            .putString(KEY_COLOR_GAMUT, colorGamut.name)
            .putString(KEY_DEMOSAIC, demosaicAlgorithm.name)
            .putString(KEY_HIGHLIGHT_RECOVERY, highlightRecovery.name)
            .putBoolean(KEY_NR_ENABLED, nrEnabled)
            .putInt(KEY_NR_LUMA, nrLuma)
            .putInt(KEY_NR_CHROMA, nrChroma)
            .putString(KEY_DCP_PROFILE, dcpProfileId)
            .putString(KEY_OUTPUT_BIT_DEPTH, outputBitDepthMode.name)
            .putBoolean(KEY_SIDECAR, sidecarEnabled)
            .putBoolean(KEY_CA_CORRECTION, caCorrectionEnabled)
            .putInt(KEY_WB_SOURCE_ORDINAL, wbSourceOrdinal)
            .putFloat(KEY_EXPOSURE_SHIFT_EV, exposureShiftEv)
            .putInt(KEY_FBDD_NOISE, fbddNoise)
            .putBoolean(KEY_SUBJECT_DETECTION, subjectDetectionEnabled)
            .putBoolean(KEY_CAMERA_STYLE_FINISH, cameraStyleFinishEnabled)
            .putString(KEY_LIBRAW_OUTPUT_COLOR, libRawOutputColor.name)
            .putString(KEY_LUT_INPUT_SPACE, lutInputSpace.name)
            .putBoolean(KEY_SENSOR_CALIBRATION, sensorCalibrationEnabled)
            .putString(KEY_COLOR_FRINGING, colorFringingMode.name)
            .putFloat(KEY_DUAL_CONTRAST_THRESHOLD, dualContrastThreshold)
            .putBoolean(KEY_DUAL_AUTO_CONTRAST, dualAutoContrast)

            .putBoolean(KEY_USE_CAMERA_COLOR_PROFILE, useCameraColorProfile)
            .putBoolean(KEY_CAMERA_PROFILE_GUIDED_FILTER, cameraProfileGuidedFilter)
            .putFloat(KEY_AE_SUBJECT_PROTECTION, aeSubjectProtection)
            .putString(KEY_FILM_PROFILE, filmProfile.id)
            .putString(KEY_FILM_GRAIN_LEVEL, filmGrainLevel.id)
            .putBoolean(KEY_HDR_RECOVERY,    hdrRecovery)
            .putBoolean(KEY_SHADOW_RECOVERY, shadowRecovery)
            .putBoolean(KEY_ENHANCE_ENABLED, enhanceEnabled)
            .putBoolean(KEY_ENHANCE_GUIDED_FILTER, enhanceGuidedFilter)
            .putFloat(KEY_CLAHE_HIGHLIGHTS_BOOST, claheHighlightsBoost)
            .putString(KEY_DEFAULT_EXPORT_FORMAT, defaultExportFormat)
            .putFloat(KEY_HIGHLIGHT_PROTECTION, highlightProtection)
            .putBoolean(KEY_SMART_DEFAULTS, smartDefaultsEnabled)
            .apply()
    }

    fun saveToPrefs(context: Context) {
        saveToPrefs(prefs(context))
    }
}
