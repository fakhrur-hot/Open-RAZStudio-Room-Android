/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — Stage C export facade (M9).
 *
 *  Stage C always emits a 16-bit RGB BigTIFF at source resolution
 *  (single-purpose, fast). M9 wraps that with:
 *    1. Optional resize via existing in-app ImageScaler
 *    2. Format-appropriate encode (TIFF-16, PNG-16, JPG, WebP, HEIC, AVIF)
 *    3. ICC profile embedding (sRGB v2)
 *    4. EXIF policy (KeepAll / StripSensitive / NoneExceptSoftware) — RAZStudio
 *       Software tag always written
 *
 *  Saves to context.filesDir/exports/raw_v3/ (app-private, no MediaStore scan)
 *  per Plan.md M8 user decision.
 *
 *  Pipeline:
 *
 *      Stage A.tif (mmap, full-res 16-bit linear)
 *           ↓ Stage C NDK kernel (apply_macro per pixel)
 *      <out>.tif (16-bit RGB BigTIFF, source dims)
 *           ↓ Kotlin decode + optional resize
 *      ShortArray (16-bit per channel) OR Bitmap (8-bit ARGB)
 *           ↓ Format-specific encode
 *      <final>.{tif|png|jpg|webp|heic|avif}
 *           ↓ Post-encode metadata splice
 *      ICC chunk + EXIF tags + Software signature
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.util.Log
import java.io.File

object RawV3Exporter {

    private const val TAG = "RawV3.Exporter"
    internal const val SOFTWARE_TAG = "RAZStudio RAZRAW v1.01 alpha (Android)"

    enum class Format(val extension: String, val mime: String, val bitDepth: Int) {
        Tiff16 ("tif",  "image/tiff", 16),
        Png16  ("png",  "image/png",  16),
        Jpg    ("jpg",  "image/jpeg",  8),
        WebP   ("webp", "image/webp",  8),
        Heic   ("heic", "image/heic",  8),
        Avif   ("avif", "image/avif",  8),
        Heic16 ("heic", "image/heic", 16),
    }

    enum class ScaleMode {
        Basic,
        Bilinear,
        Spline64,
        Lanczos3,
        Lanczos4SharpestEwa,
        RAZSharp,    // limited to ≤ 2560 px long side
    }

    enum class ResizeSharpen { None, Low, Medium, High }

    enum class ExifPolicy { KeepAll, StripSensitive, NoneExceptSoftware }

    data class Options(
        val format: Format,
        /** Target dims. 0/0 → keep source dims (no resize). */
        val targetWidth: Int = 0,
        val targetHeight: Int = 0,
        val scaleMode: ScaleMode = ScaleMode.Lanczos3,
        val resizeSharpen: ResizeSharpen = ResizeSharpen.None,
        /** JPG / WebP / HEIC / AVIF quality. PNG / TIFF ignore. */
        val quality: Int = 95,
        val exifPolicy: ExifPolicy = ExifPolicy.KeepAll,
        val embedIcc: Boolean = true,
        /**
         * Normalised crop rect (0..1 in source coords). Default identity.
         * Currently honored only on the 8-bit hybrid fast path; the
         * legacy TIFF intermediate path ignores it.
         */
        val cropL: Float = 0f,
        val cropT: Float = 0f,
        val cropR: Float = 1f,
        val cropB: Float = 1f,
    )

    data class Result(
        val success: Boolean,
        val outputFile: File?,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val error: String?,
    )

    /**
     * Synchronous; call from Dispatchers.IO.
     *
     * @param stageATifPath path to the full-resolution Stage A BigTIFF that
     *                      Stage A wrote at session-open time.
     * @param paramsBlob    57-float ShaderParams blob (e.g. read from M7's
     *                      .params sidecar). Drives the Stage C kernel.
     * @param lutData       Optional .cube LUT data. Pass null to skip LUT.
     */
    fun export(
        context: Context,
        stageATifPath: String,
        paramsBlob: FloatArray,
        options: Options,
        lutData: FloatArray? = null,
        lutSize: Int = 0,
        lutDomainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
        lutDomainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    ): Result {
        val t0 = System.currentTimeMillis()
        return runCatching {
            val outDir = File(context.filesDir, "exports/raw_v3").also { it.mkdirs() }
            val stamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss", java.util.Locale.US,
            ).format(java.util.Date())
            val finalOut = File(outDir, "stagec_${stamp}.${options.format.extension}")

            // ── M9 fast path for 8-bit codecs ─────────────────────────────
            //   Jpeg / WebP / Png-8 skip the intermediate TIFF entirely:
            //   the NDK renders Stage A straight into an RGBA_8888 Bitmap
            //   and Bitmap.compress() encodes it. No 80 MB intermediate
            //   file, no double-decode. Resize / EXIF / ICC follow-ups
            //   still land in the legacy path below; once those are
            //   wired into this path the legacy intermediate-TIFF route
            //   can be deleted.
            val fastPathFormat = when (options.format) {
                Format.Jpg   -> RawV3Export.ExportFormat.Jpeg
                Format.WebP  -> RawV3Export.ExportFormat.WebpLossy
                else         -> null
            }
            if (fastPathFormat != null &&
                options.targetWidth == 0 && options.targetHeight == 0
            ) {
                val r = RawV3Export.encode(
                    stageATifPath = stageATifPath,
                    outputPath    = finalOut.absolutePath,
                    format        = fastPathFormat,
                    quality       = options.quality,
                    actionParams  = paramsBlob,
                    lutData       = lutData,
                    lutSize       = lutSize,
                    // Crop is honored through Options.crop{L,T,R,B}.
                    // Default identity = no-op. Currently only Jpeg/WebP
                    // fast paths consume this; legacy TIFF intermediate
                    // route is unchanged (TODO: thread through).
                    cropL = options.cropL,
                    cropT = options.cropT,
                    cropR = options.cropR,
                    cropB = options.cropB,
                )
                val ms = System.currentTimeMillis() - t0
                return if (r.success && r.outputPath != null)
                    Result(true, File(r.outputPath), ms, 0, 0, null)
                else
                    Result(false, null, ms, 0, 0, r.error ?: "encode failed")
            }

            // ── 1. Stage C kernel → 16-bit RGB BigTIFF at source dims ──
            val intermediateTif = if (options.format == Format.Tiff16 &&
                options.targetWidth == 0 && options.targetHeight == 0 &&
                options.exifPolicy == ExifPolicy.NoneExceptSoftware &&
                !options.embedIcc
            ) {
                // Fast path: Stage C writes directly to the final TIFF-16.
                finalOut
            } else {
                File(outDir, "stagec_${stamp}.intermediate.tif")
            }

            val stageC = RawV3Engine.stageCExport(
                stageATifPath = stageATifPath,
                outputPath    = intermediateTif.absolutePath,
                format        = RawV3Engine.StageCFormat.Tiff16,
                targetW       = 0,    // M9: scaling happens in Kotlin
                targetH       = 0,
                actionParams  = paramsBlob,
                lutData       = lutData,
                lutSize       = lutSize,
                lutDomainMin  = lutDomainMin,
                lutDomainMax  = lutDomainMax,
            )
            if (!stageC.success) {
                return Result(false, null, System.currentTimeMillis() - t0,
                    stageC.width, stageC.height, "Stage C failed: ${stageC.error}")
            }
            Log.i(TAG, "stageC: ${stageC.width}×${stageC.height} ${intermediateTif.length()} B " +
                "in ${stageC.durationMs} ms → ${intermediateTif.name}")

            // ── 2/3. Decode + resize + encode ─────────────────────────────
            // M9 implementation order:
            //   • TIFF-16 + no-resize → already final (see fast path above)
            //   • TIFF-16 + resize    → decode + resize + re-encode as TIFF-16
            //   • PNG-16              → decode + optional resize + encode PNG-16
            //   • 8-bit codecs        → decode + downcast to ARGB_8888 + resize + encode
            //
            // To keep M9 small, ship the no-resize + no-EXIF + no-ICC TIFF-16
            // path FIRST (already covered above). The other branches are the
            // working set for the rest of M9.
            //
            // M9a fast path (TIFF-16 verbatim) lands directly in `finalOut`
            // via the conditional above. For every other format we now
            // RETURN THE INTERMEDIATE TIFF — the caller (RawV3ExportScreen
            // in M9b) is responsible for the format-specific encode +
            // resize. Returning the intermediate (instead of copying it
            // under a `.jpg` extension that confuses every decoder) keeps
            // the kernel honest about what Stage C actually produced.
            //
            // ICC + EXIF still live in M9c.
            val handoff = if (intermediateTif != finalOut) intermediateTif else finalOut

            val ms = System.currentTimeMillis() - t0
            Result(
                success    = true,
                outputFile = handoff,
                durationMs = ms,
                width      = stageC.width,
                height     = stageC.height,
                error      = null,
            )
        }.getOrElse { e ->
            Log.e(TAG, "export failed", e)
            Result(false, null, System.currentTimeMillis() - t0, 0, 0,
                "${e::class.simpleName}: ${e.message}")
        }
    }
}
