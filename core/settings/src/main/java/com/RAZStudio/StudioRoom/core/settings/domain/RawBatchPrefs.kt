/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.core.settings.domain

import android.content.Context
import android.content.SharedPreferences
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageScaleMode
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeSharpen

/**
 * Persistence for the RAW batch processor's panel selections.
 *
 * Lives in `core/settings` so both RAWEditor's in-place batch panel
 * AND Canon Sync's Download & Process panel can share the same
 * SharedPreferences without forcing the canon-sync module to depend
 * on `feature/photo-editor` for this one class.
 *
 * Format-enum coupling
 * --------------------
 * Storage is via the enum's `name` string ([selectedFormatName]), not
 * the enum itself, because `RawExportFormat` lives in `feature/photo-editor`
 * and pulling it into `core/settings` would invert the dependency.
 * Callers convert on the way in/out with `RawExportFormat.valueOf(...)` /
 * `enum.name`. The default value is "JPG" — universally available, the
 * safest first-run pick.
 */
class RawBatchPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("raw_batch_prefs", Context.MODE_PRIVATE)

    /**
     * Persisted format as the enum's `name` (e.g. `"JPG"`). Feature
     * modules round-trip via `RawExportFormat.valueOf(prefs.selectedFormatName)`
     * / `prefs.selectedFormatName = format.name`.
     */
    var selectedFormatName: String
        get() = prefs.getString(KEY_FORMAT, "JPG") ?: "JPG"
        set(v) = prefs.edit().putString(KEY_FORMAT, v).apply()

    var fullDimension: Boolean
        get() = prefs.getBoolean(KEY_FULL_DIM, true)
        set(v) = prefs.edit().putBoolean(KEY_FULL_DIM, v).apply()

    var longSideInput: String
        get() = prefs.getString(KEY_LONG_SIDE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LONG_SIDE, v).apply()

    var outputInSource: Boolean
        get() = prefs.getBoolean(KEY_OUTPUT_IN_SOURCE, true)
        set(v) = prefs.edit().putBoolean(KEY_OUTPUT_IN_SOURCE, v).apply()

    var outputSubdir: Boolean
        get() = prefs.getBoolean(KEY_OUTPUT_SUBDIR, false)
        set(v) = prefs.edit().putBoolean(KEY_OUTPUT_SUBDIR, v).apply()

    // First-run default is Lanczos3 (a neutral high-quality scaler) NOT
    // RAZSharp — RAZSharp caps dimensions + is a specialised look, so
    // it shouldn't be sticky as a default. Once the user picks any
    // mode it persists here and is restored on the next run.
    var scaleModeValue: Int
        get() = prefs.getInt(KEY_SCALE_MODE, ImageScaleMode.Lanczos3().value)
        set(v) = prefs.edit().putInt(KEY_SCALE_MODE, v).apply()

    fun resolveScaleMode(): ImageScaleMode =
        ImageScaleMode.entries.find { it.value == scaleModeValue } ?: ImageScaleMode.Lanczos3()

    /**
     * RAZSharp's post-resize sharpening level. Surfaced as a sub-row
     * in the batch scale-mode selector when RAZSharp is selected,
     * matching the Settings page Default Values group.
     */
    var resizeSharpen: ResizeSharpen
        get() = ResizeSharpen.entries.getOrNull(
            prefs.getInt(KEY_RESIZE_SHARPEN, ResizeSharpen.Medium.ordinal)
        ) ?: ResizeSharpen.Medium
        set(v) = prefs.edit().putInt(KEY_RESIZE_SHARPEN, v.ordinal).apply()

    // ── Batch panel toggles (sticky last-used) ──────────────────────────────
    // Fresh-install defaults: AI Level Reconstruct and Guided Filter are ON.
    // Auto Expose, AI Enhance and Embed ICC start OFF. Each setter persists so
    // the next batch session restores the user's last choices.
    var batchAutoExposure: Boolean
        // Default OFF to match the single-photo editor's default (Route A
        // "Camera Color Profile" ships with Auto Expose on Open = OFF). With
        // the old `true` default, a defaults-untouched batch ran the
        // RawAutoExposure solve on every file while a defaults-untouched
        // editor open+save did not — batch output was consistently brighter
        // ("overexposed") than the single-edit save of the same RAW.
        get() = prefs.getBoolean(KEY_AUTO_EXPOSURE, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_EXPOSURE, v).apply()

    /** AI Level Reconstruct value used under Route B (RAZStudio RAW depth).
     *  Default ON — matches the editor's Route B default. */
    var batchAiReconstruct: Boolean
        get() = prefs.getBoolean(KEY_AI_RECONSTRUCT, true)
        set(v) = prefs.edit().putBoolean(KEY_AI_RECONSTRUCT, v).apply()

    /** AI Level Reconstruct value used under Route A (Camera Color Profile).
     *  Separate from [batchAiReconstruct] because the two routes default
     *  differently — mirrors the editor workspace selector's two independent
     *  toggles (Route A default OFF, Route B default ON). */
    var batchAiReconstructRouteA: Boolean
        get() = prefs.getBoolean(KEY_AI_RECONSTRUCT_ROUTE_A, false)
        set(v) = prefs.edit().putBoolean(KEY_AI_RECONSTRUCT_ROUTE_A, v).apply()

    /** Lensfun lens correction, per-file auto-detected from each photo's own
     *  EXIF (no manual override in batch). Default ON — matches the single
     *  editor's workspace-selector default. Files whose camera/lens can't be
     *  confidently matched import without correction. */
    var batchLensCorrection: Boolean
        get() = prefs.getBoolean(KEY_LENS_CORRECTION, true)
        set(v) = prefs.edit().putBoolean(KEY_LENS_CORRECTION, v).apply()

    var batchAiEnhance: Boolean
        get() = prefs.getBoolean(KEY_AI_ENHANCE, false)
        set(v) = prefs.edit().putBoolean(KEY_AI_ENHANCE, v).apply()

    var batchGuidedFilter: Boolean
        get() = prefs.getBoolean(KEY_GUIDED_FILTER, true)
        set(v) = prefs.edit().putBoolean(KEY_GUIDED_FILTER, v).apply()

    /** Route A "Camera Color Profile" (match camera colour, full RAW detail).
     *  When true the RAZStudio route-B toggles above are bypassed. Default ON
     *  (route A) — matches the RAW editor workspace selector default. */
    var batchUseCameraColorProfile: Boolean
        get() = prefs.getBoolean(KEY_USE_CAMERA_COLOR_PROFILE, true)
        set(v) = prefs.edit().putBoolean(KEY_USE_CAMERA_COLOR_PROFILE, v).apply()

    /** Which source formats a folder batch runs on, as a FormatFilter ordinal
     *  (0 = RAW_AND_JPEG, 1 = RAW_ONLY, 2 = JPEG_ONLY). Default 0 (both). */
    var batchFormatFilterOrdinal: Int
        get() = prefs.getInt(KEY_FORMAT_FILTER, 0)
        set(v) = prefs.edit().putInt(KEY_FORMAT_FILTER, v).apply()

    var batchSaveIcc: Boolean
        get() = prefs.getBoolean(KEY_SAVE_ICC, false)
        set(v) = prefs.edit().putBoolean(KEY_SAVE_ICC, v).apply()

    /** LibRaw adjust_maximum_thr for batch. Off 0 / Low .75 / Standard .95 /
     *  Strong .85 (default) — mirrors the workspace selector's levels.
     *  Strong pairs with Safe Recovery desat; Standard keeps fuller headroom. */
    var batchHighlightProtection: Float
        get() = prefs.getFloat(KEY_HIGHLIGHT_PROTECTION, 0.85f)
        set(v) = prefs.edit().putFloat(KEY_HIGHLIGHT_PROTECTION, v).apply()

    /** EXIF policy stored as the enum ordinal (0 = KeepAll). The enum itself
     *  lives in feature/photo-editor so callers round-trip via ordinal. */
    var batchExifPolicyOrdinal: Int
        get() = prefs.getInt(KEY_EXIF_POLICY, 0)
        set(v) = prefs.edit().putInt(KEY_EXIF_POLICY, v).apply()

    /** Selected preset's stable fileName ("" = No preset). Persisting the
     *  fileName (not the list index) survives preset add/remove/reorder. */
    var batchPresetFileName: String
        get() = prefs.getString(KEY_PRESET_FILE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PRESET_FILE, v).apply()

    // ── Single-photo RAW Export screen sticky options ───────────────────────
    // Saved on each successful export so the next export pre-fills the user's
    // last choices rather than resetting to factory defaults.

    /** Whether to embed an ICC profile in the exported file. Default false. */
    var exportSaveIcc: Boolean
        get() = prefs.getBoolean(KEY_EXPORT_SAVE_ICC, false)
        set(v) = prefs.edit().putBoolean(KEY_EXPORT_SAVE_ICC, v).apply()

    /** EXIF policy ordinal (0 = KeepAll). Mirrors batch's batchExifPolicyOrdinal. */
    var exportExifPolicyOrdinal: Int
        get() = prefs.getInt(KEY_EXPORT_EXIF_POLICY, 0)
        set(v) = prefs.edit().putInt(KEY_EXPORT_EXIF_POLICY, v).apply()

    /** Whether to export at the original full resolution. Default true. */
    var exportUseFullResolution: Boolean
        get() = prefs.getBoolean(KEY_EXPORT_FULL_RES, true)
        set(v) = prefs.edit().putBoolean(KEY_EXPORT_FULL_RES, v).apply()

    companion object {
        private const val KEY_FORMAT          = "format"
        private const val KEY_FULL_DIM        = "full_dim"
        private const val KEY_LONG_SIDE       = "long_side"
        private const val KEY_OUTPUT_IN_SOURCE = "output_in_source"
        private const val KEY_OUTPUT_SUBDIR   = "output_subdir"
        private const val KEY_SCALE_MODE      = "scale_mode"
        private const val KEY_RESIZE_SHARPEN  = "resize_sharpen"
        private const val KEY_AUTO_EXPOSURE   = "batch_auto_exposure"
        private const val KEY_AI_RECONSTRUCT  = "batch_ai_reconstruct"
        private const val KEY_AI_RECONSTRUCT_ROUTE_A = "batch_ai_reconstruct_route_a"
        private const val KEY_LENS_CORRECTION = "batch_lens_correction"
        private const val KEY_AI_ENHANCE      = "batch_ai_enhance"
        private const val KEY_GUIDED_FILTER   = "batch_guided_filter"
        private const val KEY_USE_CAMERA_COLOR_PROFILE = "batch_use_camera_color_profile"
        private const val KEY_FORMAT_FILTER = "batch_format_filter"
        private const val KEY_SAVE_ICC        = "batch_save_icc"
        private const val KEY_HIGHLIGHT_PROTECTION = "batch_highlight_protection"
        private const val KEY_EXIF_POLICY     = "batch_exif_policy"
        private const val KEY_PRESET_FILE     = "batch_preset_file"
        // Single-photo export screen sticky keys
        private const val KEY_EXPORT_SAVE_ICC     = "export_save_icc"
        private const val KEY_EXPORT_EXIF_POLICY  = "export_exif_policy"
        private const val KEY_EXPORT_FULL_RES     = "export_full_res"
    }
}
