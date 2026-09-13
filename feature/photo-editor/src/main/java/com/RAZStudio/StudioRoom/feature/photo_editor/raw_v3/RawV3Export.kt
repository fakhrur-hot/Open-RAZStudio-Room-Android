/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — Stage C dispatcher (M9).
 *
 *  Picks an encoder based on [ExportFormat]:
 *
 *    Tiff16       → existing NDK runStageC (BigTIFF, RGB16, gamma-encoded sRGB)
 *    Jpeg         → NDK runStageCToRGBA8 → Bitmap.compress(JPEG, quality)
 *    WebpLossy    → NDK runStageCToRGBA8 → Bitmap.compress(WEBP_LOSSY, quality)
 *    WebpLossless → NDK runStageCToRGBA8 → Bitmap.compress(WEBP_LOSSLESS, 100)
 *    Png8         → NDK runStageCToRGBA8 → Bitmap.compress(PNG, 100)
 *
 *  Shelved formats (need extra infrastructure):
 *    • HEIC  — Bitmap.compress has no HEIC enum on any API. Needs
 *              androidx.heifwriter:heifwriter dependency + a multi-step
 *              encoder dance. Park until we depend on that library.
 *    • PNG-16 — Needs libpng in the NDK. Stage C TIFF-16 already covers
 *              the lossless-16-bit use case.
 *    • AVIF  — Needs libavif in the NDK (~3 MB APK overhead).
 *
 *  Memory budget for the 8-bit path:
 *    • RGBA_8888 Bitmap at full res (5500 × 3670 × 4 ≈ 80 MB) lives on the
 *      Android ASHMEM heap for the duration of the encode call. The
 *      caller closes/recycles it once `encode()` returns.
 *    • TIFF-16 path is unchanged: streams rows to disk, no full-res buffer.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object RawV3Export {

    private const val TAG = "RawV3.Export"

    enum class ExportFormat(val extension: String) {
        Tiff16("tif"),
        Jpeg("jpg"),
        WebpLossy("webp"),
        WebpLossless("webp"),
        Png8("png"),
    }

    data class Result(
        val success: Boolean,
        val outputPath: String?,
        val durationMs: Long,
        val error: String?,
    )

    /**
     * Synchronous; call from `Dispatchers.IO`.
     *
     * @param stageATifPath Stage A BigTIFF written by Stage A.
     * @param outputPath    Absolute path; the file extension is overwritten
     *                      to match [format].
     * @param format        Target format.
     * @param quality       JPEG / WebP quality in [0..100]; ignored for
     *                      lossless formats.
     * @param actionParams  Flat ShaderParams blob from
     *                      `ShaderParams.toFloatArray()`.
     * @param lutData       Optional 3D LUT (size³ × 3 floats).
     */
    fun encode(
        stageATifPath: String,
        outputPath: String,
        format: ExportFormat,
        quality: Int = 95,
        actionParams: FloatArray,
        lutData: FloatArray? = null,
        lutSize: Int = 0,
        /**
         * Optional normalised crop rect (0..1 in source coords). Default
         * (0,0,1,1) = no crop. Applied as a post-render Bitmap.createBitmap
         * for the 8-bit hybrid path. TIFF16 path doesn't honor this yet
         * (would need a native ROI in runStageC) — see plan.md C.2 notes.
         */
        cropL: Float = 0f,
        cropT: Float = 0f,
        cropR: Float = 1f,
        cropB: Float = 1f,
    ): Result {
        val started = System.currentTimeMillis()
        val finalPath = File(outputPath).let { src ->
            File(src.parentFile, "${src.nameWithoutExtension}.${format.extension}")
        }.absolutePath

        if (format == ExportFormat.Tiff16) {
            val r = RawV3Engine.stageCExport(
                stageATifPath = stageATifPath,
                outputPath    = finalPath,
                format        = RawV3Engine.StageCFormat.Tiff16,
                targetW       = 0, targetH = 0,
                actionParams  = actionParams,
                lutData       = lutData,
                lutSize       = lutSize,
            )
            return Result(r.success, finalPath.takeIf { r.success }, r.durationMs, r.error)
        }

        // 8-bit hybrid path. Read Stage A dims first so we can size the Bitmap.
        val (w, h) = readStageADims(stageATifPath)
            ?: return Result(false, null, System.currentTimeMillis() - started,
                "Stage A dims unreadable")

        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM allocating ${w}×${h} ARGB_8888", e)
            return Result(false, null, System.currentTimeMillis() - started, "OOM: ${w}×$h")
        }

        val nativeResult = RawV3Engine.stageCToBitmap(
            stageATifPath = stageATifPath,
            bitmap        = bmp,
            actionParams  = actionParams,
            lutData       = lutData,
            lutSize       = lutSize,
        )
        if (!nativeResult.success) {
            bmp.recycle()
            return Result(false, null, System.currentTimeMillis() - started,
                nativeResult.error ?: "NDK render failed")
        }

        // Post-render crop (C.2 minimal path). Wasteful — Stage C still
        // rendered the full frame — but correct: the saved file matches
        // what the user previewed. A native ROI in runStageC is the next
        // optimisation (planned C.2.1).
        val cropped: Bitmap = if (cropL > 0.001f || cropT > 0.001f || cropR < 0.999f || cropB < 0.999f) {
            val x = (cropL * w).toInt().coerceIn(0, w - 1)
            val y = (cropT * h).toInt().coerceIn(0, h - 1)
            val cw = ((cropR - cropL) * w).toInt().coerceAtLeast(1).coerceAtMost(w - x)
            val ch = ((cropB - cropT) * h).toInt().coerceAtLeast(1).coerceAtMost(h - y)
            val sub = try {
                Bitmap.createBitmap(bmp, x, y, cw, ch)
            } catch (e: Throwable) {
                Log.w(TAG, "crop $x,$y ${cw}x$ch on ${w}x$h failed; saving uncropped", e)
                null
            }
            if (sub != null && sub !== bmp) {
                bmp.recycle()
                sub
            } else bmp
        } else bmp

        val ok = try {
            when (format) {
                ExportFormat.Jpeg ->
                    writeBitmapCompress(cropped, finalPath, Bitmap.CompressFormat.JPEG, quality)
                ExportFormat.WebpLossy ->
                    writeBitmapCompress(cropped, finalPath, webpLossyFormat(), quality)
                ExportFormat.WebpLossless ->
                    writeBitmapCompress(cropped, finalPath, webpLosslessFormat(), 100)
                ExportFormat.Png8 ->
                    writeBitmapCompress(cropped, finalPath, Bitmap.CompressFormat.PNG, 100)
                ExportFormat.Tiff16 -> false  // unreachable
            }
        } catch (e: Throwable) {
            Log.e(TAG, "encode($format) failed", e)
            false
        } finally {
            cropped.recycle()
        }
        val ms = System.currentTimeMillis() - started
        return if (ok)
            Result(true, finalPath, ms, null)
        else
            Result(false, null, ms, "encoder failed for ${format.name}")
    }

    private fun writeBitmapCompress(
        bmp: Bitmap,
        outPath: String,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): Boolean {
        FileOutputStream(outPath).use { fos ->
            return bmp.compress(format, quality, fos)
        }
    }

    /**
     * Inject an ICC profile into a JPEG file as an APP2 "ICC_PROFILE" chunk.
     * Splits a profile larger than 65500 bytes across multiple APP2 markers
     * per the ICC JPEG spec. No-op when [iccBytes] is null/empty.
     *
     * Inserts the APP2 chunk(s) immediately after the SOI marker and any
     * existing APP0/JFIF chunk — before the SOS image data. Rewrites the
     * file in place.
     *
     * Spec reference: ICC.1:2010 Annex B (ICC Profile embedded in JPEG).
     */
    @Suppress("MagicNumber")
    fun injectIccProfileIntoJpeg(jpegPath: String, iccBytes: ByteArray?): Boolean {
        if (iccBytes == null || iccBytes.isEmpty()) return true
        return try {
            val orig = java.io.File(jpegPath).readBytes()
            if (orig.size < 4 ||
                orig[0] != 0xFF.toByte() || orig[1] != 0xD8.toByte()) {
                Log.w(TAG, "injectIccProfileIntoJpeg: not a JPEG, skipping")
                return false
            }
            // Find insertion point — after SOI, plus any APP0 (JFIF) that follows.
            var insertAt = 2 // after FFD8
            if (orig.size > 4 &&
                orig[2] == 0xFF.toByte() && orig[3] == 0xE0.toByte()) {
                // Skip APP0: length is big-endian uint16 at offset 4..5.
                val app0Len = ((orig[4].toInt() and 0xFF) shl 8) or
                    (orig[5].toInt() and 0xFF)
                insertAt = 2 + 2 + app0Len  // 2 SOI + 2 marker + length-inclusive payload
            }

            // Build the APP2 chunk(s).
            val CHUNK_MAX = 65500  // ICC max per APP2 (16-bit length minus header)
            val total = iccBytes.size
            val chunks = (total + CHUNK_MAX - 1) / CHUNK_MAX
            val sig = "ICC_PROFILE ".toByteArray(Charsets.ISO_8859_1)
            val app2 = java.io.ByteArrayOutputStream()
            for (i in 0 until chunks) {
                val start = i * CHUNK_MAX
                val end = minOf(start + CHUNK_MAX, total)
                val payloadLen = end - start
                val segLen = 2 + sig.size + 2 + payloadLen
                // Marker FFE2
                app2.write(0xFF); app2.write(0xE2)
                // Length (big-endian uint16, includes the length bytes themselves)
                app2.write((segLen shr 8) and 0xFF)
                app2.write(segLen and 0xFF)
                // Signature
                app2.write(sig)
                // Chunk index (1-based), total chunks
                app2.write(i + 1); app2.write(chunks)
                // ICC payload
                app2.write(iccBytes, start, payloadLen)
            }

            // Write: [0..insertAt) + APP2 + [insertAt..end)
            val out = java.io.File(jpegPath)
            FileOutputStream(out).use { fos ->
                fos.write(orig, 0, insertAt)
                fos.write(app2.toByteArray())
                fos.write(orig, insertAt, orig.size - insertAt)
            }
            Log.i(TAG, "injectIccProfileIntoJpeg: tagged $jpegPath with " +
                "${iccBytes.size} bytes ICC in $chunks APP2 chunk(s)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "injectIccProfileIntoJpeg failed", e)
            false
        }
    }

    /**
     * Pick the right WebP enum value across SDK levels. WEBP_LOSSY +
     * WEBP_LOSSLESS were added in API 30; older versions only expose
     * the deprecated `WEBP` (which is lossless when quality=100, lossy
     * otherwise). We support API 28+ overall, so honour both paths.
     */
    @Suppress("DEPRECATION")
    private fun webpLossyFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Bitmap.CompressFormat.WEBP_LOSSY
        else
            Bitmap.CompressFormat.WEBP

    @Suppress("DEPRECATION")
    private fun webpLosslessFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Bitmap.CompressFormat.WEBP_LOSSLESS
        else
            Bitmap.CompressFormat.WEBP

    /**
     * Stage A dims via the same on-disk parse used by RawV3PreviewComposable.
     * Returns null on parse failure.
     */
    private fun readStageADims(stageATifPath: String): Pair<Int, Int>? {
        val tif = File(stageATifPath)
        if (!tif.exists() || tif.length() < 64) return null
        return runCatching {
            java.io.RandomAccessFile(tif, "r").use { raf ->
                // BigTIFF IFD0 starts at byte 16; ImageWidth (tag 256) entry
                // sits at offset 24 (8-byte tag count + 12 bytes prelude),
                // ImageLength at offset 24 + 20.
                val w = readU32(raf, 24 + 12)
                val h = readU32(raf, 24 + 20 + 12)
                if (w <= 0 || h <= 0) null else w to h
            }
        }.getOrNull()
    }

    private fun readU32(raf: java.io.RandomAccessFile, off: Long): Int {
        raf.seek(off)
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        val b2 = raf.readUnsignedByte()
        val b3 = raf.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
