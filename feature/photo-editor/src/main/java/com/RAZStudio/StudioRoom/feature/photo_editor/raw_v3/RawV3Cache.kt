/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import java.io.File

/**
 * Per-session scratch cache for v3. One directory per source RAW SHA-256.
 * Layout matches Plan.md §10.5 (the custom mmap-able TIFF format):
 *
 *     <cacheDir>/raw_v3/<sha>/
 *         A.tif              ← Stage A — full-res 16-bit linear (BigTIFF, RGBA_F16, 256-row strips)
 *         A.meta.json        ← Stage A metadata (camera, lens, EXIF subset)
 *         B_preview.f16      ← Stage B serialized snapshot at Apply-time
 *         B_preview.meta     ← Stage B dims + gamut
 *         sidecar.xmp        ← user actions (mirrors the existing sidecar)
 *         segmentation.bin   ← U2Net masks (mirrors the existing segmentation cache)
 *
 * On a new session for the same SHA the directory is purged and recreated
 * (FR-1.2 — "Must persist on the storage scratch directory until a new image
 * session is initialized, at which point the old cache file is immediately
 * purged").
 */
class RawV3Cache(private val context: Context) {

    private val root: File
        get() = File(context.cacheDir, "raw_v3").also { it.mkdirs() }

    /** Per-SHA session directory. Created on demand. */
    fun sessionDir(sha: String): File =
        File(root, sha).also { it.mkdirs() }

    fun stageATif(sha: String): File   = File(sessionDir(sha), "A.tif")
    fun stageAMeta(sha: String): File  = File(sessionDir(sha), "A.meta.json")
    fun stageBPreview(sha: String): File = File(sessionDir(sha), "B_preview.f16")
    fun stageBMeta(sha: String): File    = File(sessionDir(sha), "B_preview.meta")
    fun sidecar(sha: String): File     = File(sessionDir(sha), "sidecar.xmp")
    fun segmentation(sha: String): File = File(sessionDir(sha), "segmentation.bin")

    /** Wipe the session for [sha] — called when a new file with the same SHA opens. */
    fun purge(sha: String) {
        sessionDir(sha).deleteRecursively()
    }

    /** Wipe every v3 session. Called from a "Clear cache" button. */
    fun purgeAll() {
        root.deleteRecursively()
    }

    // ── M10 — session-survival action state ─────────────────────────────────
    //
    // The per-SHA directory above is in `cacheDir`, which Stage A purges on
    // every Pick RAW AND which Android may evict under storage pressure.
    // M10's "reload restores the action stack" requirement means the slider
    // state has to survive both — so we persist it to a sibling root in
    // `filesDir` instead. One file per SHA, holding the 57-float
    // little-endian `ShaderParams` blob (same layout the Apply path's
    // `.params` sidecar uses).

    private val stateRoot: File
        get() = File(context.filesDir, "raw_v3_state").also { it.mkdirs() }

    /** Sticky path for the persisted [ShaderParams] of source [sha]. */
    fun actionState(sha: String): File = File(stateRoot, "$sha.bin")
}
