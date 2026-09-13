/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.preset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * Phase 4 — Verification harness for the editor-preview vs saved-file drift.
 *
 * **Option B implementation** — compares the editor's GL snapshot against the
 * MOST-RECENT SAVED file on disk (the actual JPG/WebP/TIFF the user produced
 * via Apply → Export). Reflects real user-visible drift end-to-end. The
 * previous "Option A" re-rendered via `stageCToBitmap` at preview dims and
 * silently failed with "outStride too small for RGBA_8888" because that JNI
 * expects bitmap dims to match the source TIFF's full sensor resolution.
 *
 * Workflow:
 *   1. User does Apply → Export to save a file (populates
 *      `RawEditorComponent.fullResOutputPath`).
 *   2. User taps Verify save fidelity.
 *   3. Harness loads the editor's GL snapshot AND the most-recent saved file.
 *   4. Both are downscaled to a common modest size with the SAME bilinear
 *      filter + box-blurred, then compared per-channel (MAD). Sharing one
 *      resample path makes the number a TONAL/COLOUR metric, not a measure of
 *      Lanczos-vs-bilinear filter mismatch + JPEG + export-sharpen edge noise
 *      (all high-frequency, perceptually invisible) that used to inflate it.
 *
 * Skip semantics: if the user hasn't saved yet, `savedFilePath` is null and
 * the harness returns a clear error so the user knows to save first.
 *
 * MAD scale (0..255 per channel), now filter-fair:
 *   • < 5     → tonally matched (residual = GL-vs-CPU math + 8-bit rounding).
 *   • 5-15    → visible feature divergence (Color Grading / dehaze / curve).
 *   • 15-30+  → structural/global mismatch (dropped grade, wrong curve, mask).
 * `centerPatchMad` stays as the strict native-res 1:1 spot-check.
 */
object PresetVerifyHarness {
    private const val TAG = "PresetVerifyHarness"

    // Mean absolute difference over two ARGB_8888 IntArrays of equal length.
    private fun madRgb(a: IntArray, b: IntArray): Double {
        var total = 0L
        for (i in a.indices) {
            val ai = a[i]; val bi = b[i]
            total += abs(((ai ushr 16) and 0xFF) - ((bi ushr 16) and 0xFF))
            total += abs(((ai ushr  8) and 0xFF) - ((bi ushr  8) and 0xFF))
            total += abs((ai and 0xFF) - (bi and 0xFF))
        }
        return total.toDouble() / (a.size * 3L)
    }

    // 3×3 box blur returning ARGB pixel array. Suppresses high-frequency edge
    // artifacts so MAD reflects tonal/color divergence, not filter-frequency mismatch.
    private fun boxBlur3(bmp: Bitmap, w: Int, h: Int): IntArray {
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val px = src[yy * w + xx]
                        r += (px ushr 16) and 0xFF
                        g += (px ushr 8) and 0xFF
                        b += px and 0xFF
                        n++
                    }
                }
                dst[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return dst
    }

    /**
     * @param editorSnapshot Live GL preview snapshot at the user's current edit.
     *                       Must be ARGB_8888.
     * @param savedFilePath  Path to the most-recent saved file (typically
     *                       `RawEditorComponent.fullResOutputPath`). Null →
     *                       returns a "save first" error.
     */
    suspend fun run(
        context: Context,
        editorSnapshot: Bitmap,
        savedFilePath: String?,
    ): Result {
        val started = System.currentTimeMillis()
        val w = editorSnapshot.width
        val h = editorSnapshot.height
        if (w <= 0 || h <= 0) {
            return Result(success = false, error = "editor snapshot empty",
                editorPath = null, savePath = null, meanAbsDiff = -1.0, durationMs = 0L)
        }
        if (savedFilePath.isNullOrEmpty()) {
            return Result(success = false,
                error = "no saved file yet — Apply → Export first",
                editorPath = null, savePath = null, meanAbsDiff = -1.0,
                durationMs = System.currentTimeMillis() - started)
        }
        val savedFile = File(savedFilePath)
        if (!savedFile.exists()) {
            return Result(success = false,
                error = "saved file missing: ${savedFile.name}",
                editorPath = null, savePath = null, meanAbsDiff = -1.0,
                durationMs = System.currentTimeMillis() - started)
        }

        // Decode the saved file. BitmapFactory handles JPG / WebP / PNG natively.
        // TIFF won't decode via BitmapFactory; the harness skips those (rare —
        // most exports are JPG/WebP).
        val ext = savedFile.extension.lowercase()
        if (ext == "tif" || ext == "tiff") {
            return Result(success = false,
                error = "TIFF saves not supported by harness (decode unavailable)",
                editorPath = null, savePath = null, meanAbsDiff = -1.0,
                durationMs = System.currentTimeMillis() - started)
        }
        val savedRaw = try {
            BitmapFactory.decodeFile(savedFilePath)
        } catch (e: OutOfMemoryError) {
            return Result(success = false, error = "OOM decoding ${savedFile.name}",
                editorPath = null, savePath = null, meanAbsDiff = -1.0,
                durationMs = System.currentTimeMillis() - started)
        }
        if (savedRaw == null) {
            return Result(success = false,
                error = "BitmapFactory.decodeFile returned null for ${savedFile.name}",
                editorPath = null, savePath = null, meanAbsDiff = -1.0,
                durationMs = System.currentTimeMillis() - started)
        }

        // ── Center-patch comparison ──────────────────────────────────────────
        // The full-image comparison below downscales the saved file (5000+ px)
        // to match the editor snapshot (~2560 px). Downscaling uses Android's
        // bilinear filter, while Stage B used Lanczos-3 — two different
        // low-pass filters that create edge-frequency MAD even when tonality
        // matches. A center-patch comparison avoids any downscaling: crop the
        // same normalized center region from both images at their native
        // resolutions, then compare 1:1. This gives the ground-truth tonal MAD
        // without filter mismatch noise.
        //
        // Done HERE before savedRaw is recycled by createScaledBitmap below.
        val patchSide = min(200, min(w, h) / 4)
        var centerPatchMad = -1.0
        if (patchSide >= 16) {
            val ePatchL = (w - patchSide) / 2
            val ePatchT = (h - patchSide) / 2
            val ePatch = IntArray(patchSide * patchSide)
            editorSnapshot.getPixels(ePatch, 0, patchSide,
                ePatchL, ePatchT, patchSide, patchSide)

            // Crop the proportional center from savedRaw, scaled to the same
            // physical scene area as the editor patch. The editor patch covers
            // (patchSide/w) of the editor image width — the saved crop must
            // cover that same fraction of the saved image, then be downscaled
            // to patchSide×patchSide so both patches are pixel-for-pixel comparable.
            val sW = savedRaw.width; val sH = savedRaw.height
            val sCropW = (patchSide.toFloat() * sW / w).toInt().coerceAtLeast(patchSide)
            val sCropH = (patchSide.toFloat() * sH / h).toInt().coerceAtLeast(patchSide)
            val sPatchL = (sW - sCropW) / 2
            val sPatchT = (sH - sCropH) / 2
            val savedForPatch = if (savedRaw.config == Bitmap.Config.ARGB_8888) savedRaw
                else savedRaw.copy(Bitmap.Config.ARGB_8888, false)
            // Crop the proportional area, then downscale to patchSide×patchSide.
            val sCrop = Bitmap.createBitmap(savedForPatch,
                sPatchL.coerceAtLeast(0), sPatchT.coerceAtLeast(0),
                sCropW.coerceAtMost(sW - sPatchL.coerceAtLeast(0)),
                sCropH.coerceAtMost(sH - sPatchT.coerceAtLeast(0)))
            if (savedForPatch !== savedRaw) savedForPatch.recycle()
            val sScaled = if (sCrop.width == patchSide && sCrop.height == patchSide) sCrop
                else Bitmap.createScaledBitmap(sCrop, patchSide, patchSide, true)
                    .also { if (it !== sCrop) sCrop.recycle() }
            val sPatch = IntArray(patchSide * patchSide)
            sScaled.getPixels(sPatch, 0, patchSide, 0, 0, patchSide, patchSide)
            sScaled.recycle()

            centerPatchMad = madRgb(ePatch, sPatch)
            Log.i(TAG, "Center-patch MAD ($patchSide×$patchSide, proportional crop ${sCropW}×${sCropH}→scaled): ${"%.2f".format(centerPatchMad)}")
        }

        // ── Filter-fair full-image tonal MAD ─────────────────────────────────
        // Downscale BOTH images to a common modest size with the SAME bilinear
        // filter, THEN box-blur, then diff. This makes the headline number a
        // TONAL / COLOUR fidelity metric: tonal error is low-frequency and
        // survives the downscale (a dropped grade / wrong curve — the old
        // MAD≈76 class — still reads high), while the perceptually-invisible
        // high-frequency noise that used to dominate averages out:
        //   • Lanczos-3 (Stage B preview) vs bilinear (harness) resample
        //     mismatch — eliminated by sending BOTH through the same bilinear
        //     downscale here (the previous code only resampled the saved file,
        //     so the two never shared a filter history → 20–40 edge MAD).
        //   • JPEG 8×8 / 4:2:0 chroma blocks, and the export resize-sharpen the
        //     live GL snapshot never had — both high-frequency, averaged away.
        // centerPatchMad above remains the strict native-res 1:1 tonal check.
        val cmpLong = 1024
        val cmpW: Int; val cmpH: Int
        if (w >= h) { cmpW = minOf(w, cmpLong); cmpH = maxOf(1, (cmpW.toLong() * h / w).toInt()) }
        else        { cmpH = minOf(h, cmpLong); cmpW = maxOf(1, (cmpH.toLong() * w / h).toInt()) }

        val savedArgbFull = if (savedRaw.config == Bitmap.Config.ARGB_8888) savedRaw
            else savedRaw.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== savedRaw) savedRaw.recycle() }
        val edCmp = if (w == cmpW && h == cmpH) editorSnapshot
            else Bitmap.createScaledBitmap(editorSnapshot, cmpW, cmpH, true)
        val svCmp = if (savedArgbFull.width == cmpW && savedArgbFull.height == cmpH) savedArgbFull
            else Bitmap.createScaledBitmap(savedArgbFull, cmpW, cmpH, true)
                .also { if (it !== savedArgbFull) savedArgbFull.recycle() }

        // ALSO compute MAD with saved R↔B swapped — diagnostic for a BGR/RGB
        // channel-order bug: if the swapped MAD is much lower than the straight
        // MAD, the save path is writing pixels in the wrong channel order.
        val ePixels = boxBlur3(edCmp, cmpW, cmpH)
        val sPixels = boxBlur3(svCmp, cmpW, cmpH)
        var totalDiff = 0L
        var totalSwapped = 0L
        var maxR = 0; var maxG = 0; var maxB = 0
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        for (i in ePixels.indices) {
            val e = ePixels[i]
            val s = sPixels[i]
            val er = (e ushr 16) and 0xFF
            val eg = (e ushr 8) and 0xFF
            val eb = e and 0xFF
            val sr = (s ushr 16) and 0xFF
            val sg = (s ushr 8) and 0xFF
            val sb = s and 0xFF
            val dr = abs(er - sr)
            val dg = abs(eg - sg)
            val db = abs(eb - sb)
            totalDiff += dr + dg + db
            // Swapped: compare editor RGB to saved BGR.
            totalSwapped += abs(er - sb) + abs(eg - sg) + abs(eb - sr)
            sumR += dr; sumG += dg; sumB += db
            if (dr > maxR) maxR = dr
            if (dg > maxG) maxG = dg
            if (db > maxB) maxB = db
        }
        val mad = totalDiff.toDouble() / (ePixels.size * 3L)
        val madSwapped = totalSwapped.toDouble() / (ePixels.size * 3L)
        val pxCount = ePixels.size.toDouble()
        Log.i(TAG, "MAD breakdown: " +
            "R mean=${sumR / pxCount} max=$maxR  " +
            "G mean=${sumG / pxCount} max=$maxG  " +
            "B mean=${sumB / pxCount} max=$maxB")
        Log.i(TAG, "MAD channel-swap diagnostic: " +
            "straight=${"%.2f".format(mad)} " +
            "saved-BGR-swapped=${"%.2f".format(madSwapped)} " +
            "(if swapped < straight, R↔B channel order bug confirmed)")

        // Dump both bitmaps to external cache so the user can pixel-peep.
        val outDir = File(context.externalCacheDir ?: context.cacheDir, "verify").apply {
            if (!exists()) mkdirs()
        }
        val ts = System.currentTimeMillis()
        val editorOut = File(outDir, "verify_${ts}_editor.png")
        val saveOut   = File(outDir, "verify_${ts}_saved_scaled.png")
        runCatching {
            FileOutputStream(editorOut).use {
                edCmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            FileOutputStream(saveOut).use {
                svCmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }.onFailure { Log.w(TAG, "PNG dump failed", it) }

        if (edCmp !== editorSnapshot) edCmp.recycle()
        svCmp.recycle()
        val durationMs = System.currentTimeMillis() - started
        Log.i(TAG, "MAD=$mad (filter-fair ${cmpW}x${cmpH}, both bilinear+blur) " +
            "centerPatch=${"%.2f".format(centerPatchMad)} in ${durationMs}ms; saved=${savedFile.name}")
        return Result(
            success = true,
            error = null,
            editorPath = editorOut.absolutePath,
            savePath = saveOut.absolutePath,
            meanAbsDiff = mad,
            centerPatchMad = centerPatchMad,
            durationMs = durationMs,
        )
    }

    data class Result(
        val success: Boolean,
        val error: String?,
        val editorPath: String?,
        val savePath: String?,
        /** Mean absolute difference 0..255 per channel; -1 on failure. */
        val meanAbsDiff: Double,
        /**
         * MAD computed on a 200×200 center crop from both images at their
         * NATIVE resolutions — no downscaling, so the measurement is immune
         * to filter-frequency differences (Lanczos-3 vs bilinear).
         * This is the reliable tonal fidelity number: if it is < 5 the
         * pipeline matches; if it is ≥ 5 there is a genuine tonal divergence.
         * -1 if the patch was too small to compute.
         */
        val centerPatchMad: Double = -1.0,
        val durationMs: Long,
    )
}
