/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Pure-Kotlin Dual-ISO CR2 detector.
 *
 * Magic Lantern's [dual_iso module](https://www.magiclantern.fm/forum/index.php?topic=7139.0)
 * captures CR2 files where alternating row pairs use a different ISO,
 * giving ~3 stops more shadow latitude when blended in post. The
 * resulting CR2 looks like garbage if demosaiced straight — every other
 * row-pair has a wildly different brightness, so naive demosaic produces
 * horrid horizontal banding.
 *
 * Detection happens in two passes:
 *
 *   1. [detectFromFilename] — instant heuristic for the SD-card grid.
 *      ML's optional `dual_iso_file_prefix` config writes files as
 *      `DUAL0001.CR2`. Not all dual-ISO captures use it (it's labeled
 *      "unreliable" in the ML UI), so a NO from this method is NOT a
 *      guarantee — only a YES is.
 *
 *   2. [detectFromCr2Bayer] — authoritative bayer-level scan that
 *      mirrors cr2hdr's `hdr_check()`: compute the mean log2 brightness
 *      delta between vertically-adjacent pixel-pairs across the frame.
 *      Dual-ISO frames have avg_ev > 0.5 because alternating rows
 *      really are 2–5 stops apart. Single-ISO frames have avg_ev close
 *      to zero. Runs only when the user is about to edit (not on
 *      thumbnail scroll) because it needs the full bayer plane.
 *
 * Twin lookup ([findTwinDng]) returns the path of a previously-baked
 * `<basename>_DUAL.dng` next to the CR2 when present, so a second visit
 * to the same photo skips the bake.
 */
object DualIsoDetector {

    private const val TAG = "DualIsoDetector"

    /**
     * ML's dual-ISO file prefix. Off by default, must be enabled in
     * ML > Movie > Dual ISO menu. Anything matching this prefix is
     * dual-ISO with high confidence.
     */
    private const val ML_PREFIX = "DUAL"

    /** Suffix the in-app cr2hdr port writes for the blended DNG twin. */
    const val TWIN_DNG_SUFFIX = "_DUAL.dng"

    /**
     * Fast path used by the SD-card grid and the local-open file list.
     * Returns true only when the filename clearly identifies the file
     * as a dual-ISO capture (`DUAL\d+\.CR2` shape). False here means
     * "unknown" — call [detectFromCr2Bayer] at edit-time to confirm.
     */
    fun detectFromFilename(name: String): Boolean {
        val base = name.substringAfterLast('/').uppercase()
        if (!base.endsWith(".CR2") && !base.endsWith(".CR3")) return false
        return base.startsWith(ML_PREFIX) &&
            // Reject files that just happen to start with "DUAL" but
            // aren't ML captures — the prefix is always followed by
            // digits per ML's filename allocator.
            base.length > ML_PREFIX.length + 4 &&
            base[ML_PREFIX.length].isDigit()
    }

    /**
     * Look for an already-baked twin DNG next to [cr2File]. The
     * in-app cr2hdr port writes `IMG_1234_DUAL.dng` alongside
     * `IMG_1234.CR2`. Returns null when no twin exists yet.
     */
    fun findTwinDng(cr2File: File): File? {
        val parent = cr2File.parentFile ?: return null
        val basename = cr2File.nameWithoutExtension
        val candidate = File(parent, "$basename$TWIN_DNG_SUFFIX")
        return candidate.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * SAF variant of [findTwinDng] for files that live behind a content
     * URI (the Canon Sync working-folder download case). The caller
     * supplies the parent SAF tree URI and the basename; we resolve the
     * twin via [androidx.documentfile.provider.DocumentFile]. Returns
     * null when the parent isn't a directory we can list, or when no
     * matching file is found.
     */
    fun findTwinDng(
        context: Context,
        parentTreeUri: Uri,
        cr2FileName: String,
    ): Uri? {
        val parent = androidx.documentfile.provider.DocumentFile
            .fromTreeUri(context, parentTreeUri) ?: return null
        if (!parent.isDirectory) return null
        val twinName = cr2FileName.substringBeforeLast('.') + TWIN_DNG_SUFFIX
        return parent.findFile(twinName)?.uri
    }

    /**
     * Slow authoritative check — mirrors cr2hdr's `hdr_check()`. Given
     * a pre-decoded 16-bit bayer plane (sized [width]*[height]),
     * accumulates the mean log2 brightness delta between every
     * vertically-adjacent (x,y) / (x,y+2) pair. Dual-ISO frames pass
     * the avg_ev > 0.5 threshold.
     *
     * The caller is responsible for producing the bayer plane (e.g.
     * via [detectFromCr2File], which handles the LibRaw round-trip).
     *
     * Returns null when the native library failed to load (e.g. wrong
     * ABI) — caller should treat null as "unknown".
     */
    fun detectFromCr2Bayer(
        bayer: ShortArray,
        width: Int,
        height: Int,
        blackLevel: Int = 2048,
        whiteLevel: Int = 15000,
    ): Boolean? {
        val native = com.raz.razstudio.lib.dualiso.DualIsoNative
        if (!native.ensureLoaded()) {
            Log.w(TAG, "detectFromCr2Bayer: native lib not loaded")
            return null
        }
        return native.hdrCheck(bayer, width, height, blackLevel, whiteLevel)
    }

    /**
     * End-to-end authoritative detection from a CR2 file on disk:
     *   1. read the file bytes
     *   2. ask LibRaw (via NativeRawDecoder.readRawBayerForDualIso) for
     *      the bayer plane + black/white levels + active area
     *   3. call DualIsoNative.detectFull on that bayer
     *
     * Returns the full DetectionResult, or null on any failure (file
     * I/O, LibRaw, native lib unavailable). Heavy — allocates a ~50 MB
     * short[] for a 25 MP frame and discards it immediately. Always
     * call from a background dispatcher (IO).
     */
    fun detectFromCr2File(
        cr2File: File,
    ): com.raz.razstudio.lib.dualiso.DualIsoNative.DetectionResult? = runCatching {
        if (!cr2File.exists() || cr2File.length() < 1024) return@runCatching null
        val bytes = cr2File.readBytes()
        val meta = IntArray(8)
        val bayer = com.raz.razstudio.lib.raw.NativeRawDecoder
            .readRawBayerForDualIso(bytes, meta)
            ?: run {
                Log.w(TAG, "detectFromCr2File(${cr2File.name}): LibRaw returned null bayer")
                return@runCatching null
            }
        val (w, h, black, white) = listOf(meta[0], meta[1], meta[2], meta[3])
        Log.i(TAG, "detectFromCr2File(${cr2File.name}): bayer ${w}x${h} " +
            "black=$black white=$white — running detectFull")
        com.raz.razstudio.lib.dualiso.DualIsoNative.detectFull(
            bayer = bayer,
            width = w,
            height = h,
            black = black,
            white = white,
        )
    }.onFailure {
        Log.w(TAG, "detectFromCr2File(${cr2File.name}) threw", it)
    }.getOrNull()

    /**
     * Diagnostic detect+blend round-trip. When the detector confirms
     * the file is dual-ISO, runs the full blend kernel on the same
     * bayer buffer and reports timings. Doesn't persist the blended
     * bayer anywhere — this is currently a "does the kernel run + how
     * long does it take + do the intermediate numbers look sane"
     * smoke test, not a production code path.
     *
     * The blend's output will be used by future Stage A integration
     * (replacing the LibRaw raw_image plane between unpack and
     * demosaic). That integration is intentionally NOT done in this
     * commit — we want to verify the kernel's behaviour first.
     *
     * Returns the detection result. The blend status is logged to
     * logcat (search for "DualIsoBlend" or "DualIsoDetector").
     */
    fun detectAndBlendDiagnostic(
        cr2File: File,
    ): com.raz.razstudio.lib.dualiso.DualIsoNative.DetectionResult? = runCatching {
        if (!cr2File.exists() || cr2File.length() < 1024) return@runCatching null
        val bytes = cr2File.readBytes()
        val meta = IntArray(8)
        val bayer = com.raz.razstudio.lib.raw.NativeRawDecoder
            .readRawBayerForDualIso(bytes, meta) ?: return@runCatching null
        val w = meta[0]; val h = meta[1]; val black = meta[2]; val white = meta[3]
        val verdict = com.raz.razstudio.lib.dualiso.DualIsoNative.detectFull(
            bayer = bayer, width = w, height = h, black = black, white = white,
        ) ?: return@runCatching null
        if (verdict.isDualIso && verdict.fieldsConfirmed) {
            val t0 = System.currentTimeMillis()
            val ok = com.raz.razstudio.lib.dualiso.DualIsoNative.blendMean23(
                bayer = bayer, width = w, height = h, black = black, white = white,
            )
            val elapsed = System.currentTimeMillis() - t0
            Log.i(TAG, "blendMean23(${cr2File.name}) ${w}x${h} → ok=$ok in ${elapsed}ms")
        } else {
            Log.i(TAG, "blend skipped — detection not confirmed " +
                "(isDualIso=${verdict.isDualIso} fieldsConfirmed=${verdict.fieldsConfirmed})")
        }
        verdict
    }.onFailure {
        Log.w(TAG, "detectAndBlendDiagnostic(${cr2File.name}) threw", it)
    }.getOrNull()

    /**
     * Result type for the edit-time pre-flight. Lets the UI route to
     * the right handler without re-running detection.
     */
    sealed interface PreflightResult {
        /** File is not dual-ISO (filename ruled it out). */
        data object NotDualIso : PreflightResult

        /** File is dual-ISO and a baked DNG twin already exists. */
        data class TwinReady(val twinFile: File) : PreflightResult

        /** SAF variant of TwinReady — twin exists at this content URI. */
        data class TwinReadyUri(val twinUri: Uri) : PreflightResult

        /** Dual-ISO confirmed but no twin yet — caller should offer 'Process now'. */
        data object NeedsBake : PreflightResult

        /** Filename suggests dual-ISO but no twin and no bayer check available. */
        data object Suspected : PreflightResult
    }

    /**
     * One-shot pre-flight for an on-disk CR2. Returns the routing the
     * editor should take. Use this from the Edit button handler.
     */
    fun preflightLocal(cr2File: File): PreflightResult {
        if (!detectFromFilename(cr2File.name)) return PreflightResult.NotDualIso
        val twin = findTwinDng(cr2File)
        return if (twin != null) PreflightResult.TwinReady(twin)
        else PreflightResult.NeedsBake
    }

    /**
     * SAF variant. Use when the CR2 lives behind a tree URI (Canon Sync
     * Browse Edit / Download flows).
     */
    fun preflightSaf(
        context: Context,
        parentTreeUri: Uri,
        cr2FileName: String,
    ): PreflightResult {
        if (!detectFromFilename(cr2FileName)) return PreflightResult.NotDualIso
        val twin = findTwinDng(context, parentTreeUri, cr2FileName)
        return if (twin != null) PreflightResult.TwinReadyUri(twin)
        else PreflightResult.NeedsBake
    }
}
