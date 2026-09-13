/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — pre-decode EXIF/TIFF probe.
 *
 *  Hand-parses the first 256 KB of a RAW/DNG to decide whether the file
 *  is a Bayer mosaic the RCD kernel should run on, or a pre-processed
 *  DNG (LinearRaw / Adobe Enhanced) that must skip RCD and go through
 *  plain LibRaw dcraw_process.
 *
 *  Mirrors v2 [WorkspaceSelectorSheet.kt:920-952]. Originally lived in
 *  the smoke activity; promoted here in M11 so the batch coordinator
 *  can re-use the same detection per file without duplicating ~100 LOC.
 *
 *  We hand-parse the TIFF IFD0 (no androidx.exifinterface dependency)
 *  because v3 must work from both file paths (RawV3SmokeActivity) and
 *  freshly-copied scratch files (RawV3Coordinator).
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RawV3SourceProbe {

    private const val TAG = "RawV3.SourceProbe"

    /**
     *  • [alreadyDemosaiced] — TIFF `PhotometricInterpretation` is 34892
     *    (LinearRaw). Source is no longer a Bayer mosaic, so running the
     *    RCD kernel on it would scramble channels.
     *  • [adobeEnhanced] — EXIF Software tag mentions Adobe Enhance, or
     *    [alreadyDemosaiced] is true and Software mentions Lightroom /
     *    Camera Raw / Adobe.
     *  • [photometric] / [software] — raw EXIF values, surfaced for logs.
     */
    data class Result(
        val alreadyDemosaiced: Boolean,
        val adobeEnhanced: Boolean,
        val photometric: Int,
        val software: String,
    ) {
        /** When true, Stage A should force `user_qual = 3` (AHD via LibRaw
         *  dcraw_process) and skip the RCD kernel. */
        val skipRcd: Boolean get() = alreadyDemosaiced || adobeEnhanced
    }

    /**
     * Read the TIFF/DNG IFD0 tags v2 uses to decide source kind. Falls
     * back to "Bayer mosaic" (skipRcd = false) on any parse error — failing
     * closed would block every native CR2/ARW/NEF.
     *
     * Tags consulted (TIFF 6.0 + DNG 1.0):
     *   • 0x0106  PhotometricInterpretation  (SHORT)  34892 = LinearRaw
     *   • 0x0131  Software                   (ASCII)
     */
    fun probe(file: File): Result {
        return runCatching {
            file.inputStream().use { ins ->
                // Read enough of the header to walk IFD0. Most RAW headers
                // fit comfortably in the first ~64 KB; 256 KB is generous.
                val buf = ByteArray(256 * 1024)
                val read = readNBytesSafe(ins, buf)
                if (read < 16) error("file too short for TIFF header")
                val byteOrder = when {
                    buf[0] == 'I'.code.toByte() && buf[1] == 'I'.code.toByte() ->
                        ByteOrder.LITTLE_ENDIAN
                    buf[0] == 'M'.code.toByte() && buf[1] == 'M'.code.toByte() ->
                        ByteOrder.BIG_ENDIAN
                    else -> error("not a TIFF file (no II/MM header)")
                }
                val bb = ByteBuffer.wrap(buf, 0, read).order(byteOrder)
                val magic = bb.getShort(2).toInt() and 0xFFFF
                if (magic != 42 && magic != 43) error("not a TIFF (magic=$magic)")
                val ifd0Off = bb.getInt(4)
                if (ifd0Off < 8 || ifd0Off + 2 > read) error("IFD0 offset out of range")
                val entryCount = bb.getShort(ifd0Off).toInt() and 0xFFFF
                var photometric = -1
                var software = ""
                for (i in 0 until entryCount) {
                    val entryOff = ifd0Off + 2 + i * 12
                    if (entryOff + 12 > read) break
                    val tag = bb.getShort(entryOff).toInt() and 0xFFFF
                    val type = bb.getShort(entryOff + 2).toInt() and 0xFFFF
                    val count = bb.getInt(entryOff + 4)
                    val valOrOff = bb.getInt(entryOff + 8)
                    when (tag) {
                        0x0106 -> photometric = if (type == 3) {
                            valOrOff and 0xFFFF
                        } else valOrOff
                        0x0131 -> if (type == 2 && count > 0) {
                            val strOff = if (count <= 4) entryOff + 8 else valOrOff
                            if (strOff in 0 until read) {
                                val end = (strOff + count - 1).coerceAtMost(read)
                                software = String(
                                    buf, strOff, (end - strOff).coerceAtLeast(0),
                                    Charsets.US_ASCII,
                                ).trim(' ', '\t')
                            }
                        }
                    }
                }
                val alreadyDemosaiced = photometric == 34892
                val adobeEnhanced = software.contains("Enhance", ignoreCase = true) ||
                    (alreadyDemosaiced && (
                        software.contains("Lightroom", ignoreCase = true) ||
                        software.contains("Camera Raw", ignoreCase = true) ||
                        software.contains("Adobe", ignoreCase = true)
                    ))
                Result(alreadyDemosaiced, adobeEnhanced, photometric, software)
            }
        }.getOrElse { e ->
            Log.w(TAG, "probe failed for ${file.absolutePath} — assuming Bayer mosaic", e)
            Result(false, false, -1, "")
        }
    }

    /** Read up to `dst.size` bytes; returns the actual count. */
    private fun readNBytesSafe(ins: java.io.InputStream, dst: ByteArray): Int {
        var total = 0
        while (total < dst.size) {
            val n = ins.read(dst, total, dst.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
