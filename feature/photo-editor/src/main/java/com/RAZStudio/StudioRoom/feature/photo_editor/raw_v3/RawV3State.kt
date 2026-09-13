/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * ───────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — skeleton (milestone M1)
 *
 *  Tracks: .kiro/specs/raw-pipeline-v3-rebuild/Plan.md
 *
 *  Nothing in this package is wired into the UI yet. Old `raw/` package
 *  remains the production engine until BuildConfig.USE_RAW_V3 flips at M12.
 * ───────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.graphics.Bitmap

/**
 * Lifecycle states of the v3 pipeline. Mirrors the high-level flow in
 * Plan.md §2 (Pipeline at a glance).
 */
sealed interface RawV3State {
    /** No file open. Initial state. */
    data object Idle : RawV3State

    /** Workspace Options dialog visible. User picks demosaic / NR / CA / Lensfun / bit depth. */
    data class DialogShown(val suggestedConfig: RawV3WorkspaceOptions) : RawV3State

    /** Stage A native decode in flight (LibRaw → 16-bit TIFF). */
    data class StageADecoding(
        val progress: Float,
        /** Embedded JPEG thumbnail shown as instant placeholder while Stage A runs. Null if unavailable. */
        val thumbnailBitmap: Bitmap? = null,
    ) : RawV3State

    /** Stage A complete; Stage B downsample + GLES render up. Editor canvas is live. */
    data class StageBReady(
        val sha: String,
        val stageATifPath: String,
        val previewWidth: Int,
        val previewHeight: Int,
    ) : RawV3State

    /** User tapped Apply. Stage B serializing to disk; navigating to Export. */
    data object Applying : RawV3State

    /** Stage C export running on the NDK kernel. */
    data class Exporting(val progress: Float) : RawV3State

    /** Pipeline complete; file written. */
    data class Done(val outputPath: String) : RawV3State

    /** Recoverable failure. */
    data class Failed(val message: String, val cause: Throwable? = null) : RawV3State
}
