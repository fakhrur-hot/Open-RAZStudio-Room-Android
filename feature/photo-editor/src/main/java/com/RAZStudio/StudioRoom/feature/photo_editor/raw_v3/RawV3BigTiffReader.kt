/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Stage C BigTIFF reader (M9b / M11).
 *
 *  Pure-Kotlin decoder for the BigTIFF format Stage C writes
 *  ([stage_c_export.cpp:48-112]). The project's Coil [TiffDecoder] only
 *  matches classic TIFF (magic 0x002A); Stage C writes BigTIFF (0x002B)
 *  for futureproofing >4 GB outputs. Lives in the feature module so both
 *  the smoke export screen and the v3 batch coordinator can use it.
 *
 *  Layout (cross-checked against stage_c_export.cpp:48-112):
 *    • Header:      `II 2B 00 08 00 00 00 10 00 00 00 00 00 00 00 00`
 *    • IFD0 at 16:  count + 11 entries + 8-byte next-IFD = 236 bytes
 *    • Strip arrays follow IFD0
 *    • Pixel data = `width × height × 3 × uint16`, row-major, contiguous
 *      across strips (strips are 256 rows each).
 *
 *  Returns null on malformed input; logs to LogCat tag `RawV3.BigTiff`.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

object RawV3BigTiffReader {

    private const val TAG = "RawV3.BigTiff"

    fun decodeToArgb8888(file: File): Bitmap? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len < 16) error("file too short")
                val head = ByteArray(16)
                raf.readFully(head)
                // II + 0x002B
                if (head[0].toInt() != 0x49 || head[1].toInt() != 0x49 ||
                    (head[2].toInt() and 0xFF) != 0x2B || (head[3].toInt() and 0xFF) != 0x00) {
                    error("not Stage C BigTIFF (magic mismatch)")
                }
                val ifd0Off = u64le(head, 8)
                if (ifd0Off + 8 > len) error("IFD offset out of range")
                raf.seek(ifd0Off)
                val tagCountBuf = ByteArray(8)
                raf.readFully(tagCountBuf)
                val tagCount = u64le(tagCountBuf, 0).toInt()
                if (tagCount <= 0 || tagCount > 64) error("absurd tag count $tagCount")
                val ifdEntries = ByteArray(tagCount * 20)
                raf.readFully(ifdEntries)

                var width = 0
                var height = 0
                var samplesPerPixel = 0
                var bitsPerSample = 0
                var stripCount = 0
                var stripOffsetsArrayPos = 0L
                var stripOffsetInline = 0L
                for (i in 0 until tagCount) {
                    val e = i * 20
                    val tag = u16le(ifdEntries, e)
                    val type = u16le(ifdEntries, e + 2)
                    val count = u64le(ifdEntries, e + 4).toInt()
                    val valOrOff = u64le(ifdEntries, e + 12)
                    when (tag) {
                        256 -> width = valOrOff.toInt()
                        257 -> height = valOrOff.toInt()
                        258 -> bitsPerSample =
                            if (count == 1) valOrOff.toInt() and 0xFFFF
                            else u16le(ifdEntries, e + 12)
                        277 -> samplesPerPixel = valOrOff.toInt() and 0xFFFF
                        273 -> {
                            stripCount = count
                            if (count == 1) stripOffsetInline = valOrOff
                            else stripOffsetsArrayPos = valOrOff.toLong()
                        }
                    }
                }
                if (width <= 0 || height <= 0) error("invalid dims ${width}×${height}")
                if (samplesPerPixel != 3 || bitsPerSample != 16)
                    error("unexpected layout spp=$samplesPerPixel bps=$bitsPerSample")

                val firstStripOffset = if (stripCount == 1) {
                    stripOffsetInline
                } else {
                    if (stripOffsetsArrayPos + 8 > len)
                        error("strip array out of range")
                    raf.seek(stripOffsetsArrayPos)
                    val first = ByteArray(8); raf.readFully(first)
                    u64le(first, 0)
                }
                if (firstStripOffset < 0 ||
                    firstStripOffset + height.toLong() * width * 6 > len) {
                    error("pixel data out of range")
                }

                // Stream pixel data into the Bitmap chunk-by-chunk to avoid
                // holding a full-resolution IntArray alongside the Bitmap.
                // Peak Java-heap cost = Bitmap + one small chunk buffer.
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val rowBytes = width * 6
                val chunkRows = maxOf(1, (4 * 1024 * 1024) / rowBytes)
                val chunkBuf  = ByteArray(chunkRows * rowBytes)
                val chunkArgb = IntArray(chunkRows * width)
                raf.seek(firstStripOffset)
                var rowsLeft = height
                var dstY = 0
                while (rowsLeft > 0) {
                    val rows  = minOf(chunkRows, rowsLeft)
                    val bytes = rows * rowBytes
                    raf.readFully(chunkBuf, 0, bytes)
                    var src = 0
                    var off = 0
                    val end = rows * width
                    while (off < end) {
                        // Stage C wrote little-endian RGB16, gamma-encoded sRGB.
                        // Take the high byte → 8-bit value (equivalent to
                        // /65535 × 255 within rounding).
                        val r = chunkBuf[src + 1].toInt() and 0xFF
                        val g = chunkBuf[src + 3].toInt() and 0xFF
                        val b = chunkBuf[src + 5].toInt() and 0xFF
                        chunkArgb[off++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        src += 6
                    }
                    bmp.setPixels(chunkArgb, 0, width, 0, dstY, width, rows)
                    rowsLeft -= rows
                    dstY += rows
                }
                bmp
            }
        }.onFailure { e ->
            Log.e(TAG, "decodeToArgb8888 failed for ${file.absolutePath}", e)
        }.getOrNull()
    }

    /**
     * Read just the width×height from a BigTIFF without decoding pixels.
     * Returns null if the file is not a valid BigTIFF.
     */
    fun readDims(file: File): Pair<Int, Int>? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len < 16) return null
                val head = ByteArray(16); raf.readFully(head)
                if (head[0].toInt() != 0x49 || head[1].toInt() != 0x49 ||
                    (head[2].toInt() and 0xFF) != 0x2B) return null
                val ifd0Off = u64le(head, 8)
                if (ifd0Off + 8 > len) return null
                raf.seek(ifd0Off)
                val tagCountBuf = ByteArray(8); raf.readFully(tagCountBuf)
                val tagCount = u64le(tagCountBuf, 0).toInt()
                if (tagCount <= 0 || tagCount > 64) return null
                val entries = ByteArray(tagCount * 20); raf.readFully(entries)
                var w = 0; var h = 0
                for (i in 0 until tagCount) {
                    val e = i * 20
                    when (u16le(entries, e)) {
                        256 -> w = u64le(entries, e + 12).toInt()
                        257 -> h = u64le(entries, e + 12).toInt()
                    }
                }
                if (w > 0 && h > 0) Pair(w, h) else null
            }
        }.getOrNull()
    }

    /**
     * Decode a Stage A BigTIFF (RGBA float16, single-strip layout per
     * `tiff_mmap_io.cpp`) into an ARGB_8888 Bitmap, bilinearly
     * downsampled to fit within [maxLongSide]×[maxLongSide]. Used by
     * the segmentation processor to get a small, sRGB-encoded Bitmap
     * for U2Net inference (which wants 320×320) without paying the
     * full Stage A decode cost twice.
     *
     * Returns null on parse failure.
     */
    fun decodeStageAToArgb8888(file: File, maxLongSide: Int = 320): Bitmap? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len < 16) error("file too short")
                val head = ByteArray(16); raf.readFully(head)
                // II + 0x002B (BigTIFF magic)
                if (head[0].toInt() != 0x49 || head[1].toInt() != 0x49 ||
                    (head[2].toInt() and 0xFF) != 0x2B) {
                    error("not Stage A BigTIFF")
                }
                val ifd0Off = u64le(head, 8)
                if (ifd0Off + 8 > len) error("IFD offset out of range")
                raf.seek(ifd0Off)
                val tagCountBuf = ByteArray(8); raf.readFully(tagCountBuf)
                val tagCount = u64le(tagCountBuf, 0).toInt()
                if (tagCount <= 0 || tagCount > 64) error("absurd tag count $tagCount")
                val entries = ByteArray(tagCount * 20); raf.readFully(entries)

                var width = 0
                var height = 0
                var rowsPerStrip = 0
                var stripOffsetsField = 0L
                var stripOffsetsCount = 0L
                for (i in 0 until tagCount) {
                    val e = i * 20
                    val tag = u16le(entries, e)
                    val cnt = u64le(entries, e + 4)
                    val valOrOff = u64le(entries, e + 12)
                    when (tag) {
                        256 -> width = valOrOff.toInt()      // ImageWidth
                        257 -> height = valOrOff.toInt()     // ImageLength
                        273 -> {                             // StripOffsets
                            stripOffsetsField = valOrOff
                            stripOffsetsCount = cnt
                        }
                        278 -> rowsPerStrip = valOrOff.toInt() // RowsPerStrip
                    }
                }
                if (width <= 0 || height <= 0) error("invalid dims ${width}×${height}")
                if (stripOffsetsField <= 0) error("missing strip offset")
                if (rowsPerStrip <= 0) rowsPerStrip = height  // fall back to single-strip
                // Resolve strip offsets. For BigTIFF LONG8 (type 16), the
                // field carries 1 inline value or is a file offset to a
                // packed uint64 array of [stripOffsetsCount] entries. The
                // earlier decoder assumed single-strip and read straight
                // pixels from `stripOffsetsField` — for multi-strip Stage A
                // (15 strips × 256 rows on a 5496×3670 source) that's the
                // offset table itself, producing garbage rows at the top
                // and a wrap-around pattern further down.
                val stripOffsets = LongArray(stripOffsetsCount.toInt())
                if (stripOffsetsCount <= 1L) {
                    stripOffsets[0] = stripOffsetsField
                } else {
                    raf.seek(stripOffsetsField)
                    val tbl = ByteArray(stripOffsetsCount.toInt() * 8)
                    raf.readFully(tbl)
                    for (i in 0 until stripOffsetsCount.toInt()) {
                        stripOffsets[i] = u64le(tbl, i * 8)
                    }
                }

                // Compute downsample factor — Stage A is huge (e.g.
                // 5496×3670 RGBA-F16 ≈ 161 MB); we don't want to decode
                // it all just for a 320-px segmentation input.
                //
                // outW/outH preserve aspect; integer-step decimation is
                // used for memory + decode-cost reasons. Per-axis source
                // sampling uses a fractional stride so the LAST output
                // pixel maps to (width - 1) / (height - 1), not to
                // (outW - 1) * step. The old `sx += step` form dropped
                // up to `step - 1` pixels at the right + bottom edges
                // (≈15 pixels on a 5496×3670 source at maxLongSide=512),
                // which produced a visible left-shift of the U2Net mask
                // and a black strip on the tone-curve thumbnail.
                val srcLong = maxOf(width, height)
                val step = maxOf(1, srcLong / maxLongSide)
                val outW = width / step
                val outH = height / step
                if (outW <= 0 || outH <= 0) error("downsampled dims collapse")

                val xStride: Float = if (outW > 1) (width  - 1).toFloat() / (outW - 1) else 0f
                val yStride: Float = if (outH > 1) (height - 1).toFloat() / (outH - 1) else 0f

                val bytesPerSrcRow = width.toLong() * 4 * 2  // 4 channels × 2 bytes (fp16)
                val argb = IntArray(outW * outH)
                val rowBuf = ByteArray(bytesPerSrcRow.toInt())
                var outIdx = 0
                for (oy in 0 until outH) {
                    val srcY = (oy * yStride + 0.5f).toInt().coerceIn(0, height - 1)
                    // Strip-aware row fetch: pick the strip that owns
                    // srcY, then seek to that strip's base + the
                    // row-within-strip offset. Matches the writer in
                    // tiff_mmap_io.cpp.
                    val stripIdx = srcY / rowsPerStrip
                    val rowInStrip = srcY - stripIdx * rowsPerStrip
                    val rowFileOff = stripOffsets[stripIdx] + rowInStrip * bytesPerSrcRow
                    raf.seek(rowFileOff)
                    raf.readFully(rowBuf)
                    for (ox in 0 until outW) {
                        val srcX = (ox * xStride + 0.5f).toInt().coerceIn(0, width - 1)
                        val off = srcX * 8  // 8 bytes per RGBA-F16 pixel
                        val r = halfToFloat(u16le(rowBuf, off))
                        val g = halfToFloat(u16le(rowBuf, off + 2))
                        val b = halfToFloat(u16le(rowBuf, off + 4))
                        // Stage A holds sRGB-gamma-encoded pixels (LibRaw
                        // 1/2.4 + 12.92 transfer applied at decode); the
                        // segmentation Bitmap can consume them straight
                        // as 8-bit sRGB.
                        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
                        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
                        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
                        argb[outIdx++] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
                Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
            }
        }.onFailure { Log.e(TAG, "decodeStageAToArgb8888 failed", it) }.getOrNull()
    }

    /**
     * Decode Stage A as a single-channel linear-light luma plane, downsampled
     * by integer step so the long side fits `maxLongSide`. Used as the guide
     * image for guided-filter mask refinement.
     *
     * Stage A holds sRGB-gamma-encoded fp16 (writer applies 1/2.4 + 12.92 at
     * decode time, per tiff_mmap_io.cpp comments). We undo that here so the
     * guide is linear-light — edge-detection in linear space matches highlight
     * and shadow boundaries the way the human visual system / photo-edge
     * algorithms expect, instead of being skewed by display gamma.
     *
     * Returns Triple(luma, outW, outH) or null on failure.
     */
    fun decodeStageALumaLinear(file: File, maxLongSide: Int): Triple<FloatArray, Int, Int>? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len < 16) error("file too short")
                val head = ByteArray(16); raf.readFully(head)
                if (head[0].toInt() != 0x49 || head[1].toInt() != 0x49 ||
                    (head[2].toInt() and 0xFF) != 0x2B) {
                    error("not Stage A BigTIFF")
                }
                val ifd0Off = u64le(head, 8)
                if (ifd0Off + 8 > len) error("IFD offset out of range")
                raf.seek(ifd0Off)
                val tagCountBuf = ByteArray(8); raf.readFully(tagCountBuf)
                val tagCount = u64le(tagCountBuf, 0).toInt()
                if (tagCount <= 0 || tagCount > 64) error("absurd tag count $tagCount")
                val entries = ByteArray(tagCount * 20); raf.readFully(entries)

                var width = 0
                var height = 0
                var rowsPerStrip = 0
                var stripOffsetsField = 0L
                var stripOffsetsCount = 0L
                for (i in 0 until tagCount) {
                    val e = i * 20
                    val tag = u16le(entries, e)
                    val cnt = u64le(entries, e + 4)
                    val valOrOff = u64le(entries, e + 12)
                    when (tag) {
                        256 -> width = valOrOff.toInt()
                        257 -> height = valOrOff.toInt()
                        273 -> { stripOffsetsField = valOrOff; stripOffsetsCount = cnt }
                        278 -> rowsPerStrip = valOrOff.toInt()
                    }
                }
                if (width <= 0 || height <= 0) error("invalid dims")
                if (stripOffsetsField <= 0) error("missing strip offset")
                if (rowsPerStrip <= 0) rowsPerStrip = height

                val stripOffsets = LongArray(stripOffsetsCount.toInt())
                if (stripOffsetsCount <= 1L) {
                    stripOffsets[0] = stripOffsetsField
                } else {
                    raf.seek(stripOffsetsField)
                    val tbl = ByteArray(stripOffsetsCount.toInt() * 8)
                    raf.readFully(tbl)
                    for (i in 0 until stripOffsetsCount.toInt()) {
                        stripOffsets[i] = u64le(tbl, i * 8)
                    }
                }

                val srcLong = maxOf(width, height)
                val step = maxOf(1, srcLong / maxLongSide)
                val outW = width / step
                val outH = height / step
                if (outW <= 0 || outH <= 0) error("downsampled dims collapse")

                val xStride: Float = if (outW > 1) (width  - 1).toFloat() / (outW - 1) else 0f
                val yStride: Float = if (outH > 1) (height - 1).toFloat() / (outH - 1) else 0f

                val bytesPerSrcRow = width.toLong() * 4 * 2
                val luma = FloatArray(outW * outH)
                val rowBuf = ByteArray(bytesPerSrcRow.toInt())
                var outIdx = 0
                for (oy in 0 until outH) {
                    val srcY = (oy * yStride + 0.5f).toInt().coerceIn(0, height - 1)
                    val stripIdx = srcY / rowsPerStrip
                    val rowInStrip = srcY - stripIdx * rowsPerStrip
                    val rowFileOff = stripOffsets[stripIdx] + rowInStrip * bytesPerSrcRow
                    raf.seek(rowFileOff)
                    raf.readFully(rowBuf)
                    for (ox in 0 until outW) {
                        val srcX = (ox * xStride + 0.5f).toInt().coerceIn(0, width - 1)
                        val off = srcX * 8
                        val r = halfToFloat(u16le(rowBuf, off)).coerceIn(0f, 1f)
                        val g = halfToFloat(u16le(rowBuf, off + 2)).coerceIn(0f, 1f)
                        val b = halfToFloat(u16le(rowBuf, off + 4)).coerceIn(0f, 1f)
                        // Undo sRGB gamma (IEC 61966-2-1) per-channel, then
                        // Rec.709 luma — matches what the photo-edge / matting
                        // literature expects for a guide image.
                        val rl = if (r <= 0.04045f) r / 12.92f else Math.pow(((r + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
                        val gl = if (g <= 0.04045f) g / 12.92f else Math.pow(((g + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
                        val bl = if (b <= 0.04045f) b / 12.92f else Math.pow(((b + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
                        luma[outIdx++] = 0.2126f * rl + 0.7152f * gl + 0.0722f * bl
                    }
                }
                Triple(luma, outW, outH)
            }
        }.onFailure { Log.e(TAG, "decodeStageALumaLinear failed", it) }.getOrNull()
    }

    /**
     * IEEE 754 binary16 → float32. Standard reference implementation;
     * handles subnormals, infinities, and NaN. Used by the Stage A
     * fp16 → 8-bit sRGB downsample for segmentation input.
     */
    private fun halfToFloat(bits: Int): Float {
        val sign = (bits ushr 15) and 0x1
        val exp  = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        val signBit = sign shl 31

        return when (exp) {
            0 -> {
                if (mant == 0) {
                    Float.fromBits(signBit)  // ±0
                } else {
                    // Subnormal: value = (-1)^s * 2^-14 * (mant / 1024)
                    // Normalise by shifting until the implicit 1 surfaces.
                    var m = mant
                    var e = -14 + 127
                    while ((m and 0x400) == 0) {
                        m = m shl 1
                        e--
                    }
                    val m32 = (m and 0x3FF) shl 13
                    Float.fromBits(signBit or (e shl 23) or m32)
                }
            }
            31 -> {
                if (mant == 0) Float.fromBits(signBit or 0x7F800000)        // ±Inf
                else           Float.fromBits(signBit or 0x7F800000 or (mant shl 13))  // NaN
            }
            else -> {
                val e32 = (exp - 15 + 127) shl 23
                val m32 = mant shl 13
                Float.fromBits(signBit or e32 or m32)
            }
        }
    }

    private fun u16le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun u64le(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[off + i].toLong() and 0xFF) shl (i * 8))
        return v
    }
}
