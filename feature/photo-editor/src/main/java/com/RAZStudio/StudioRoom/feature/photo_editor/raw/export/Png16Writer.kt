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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.export

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Pure-Kotlin 16-bit-per-channel PNG encoder.
 *
 * Emits a PNG with:
 *   - IHDR: bit depth = 16, color type = 2 (truecolor RGB) or 6 (truecolor + alpha)
 *   - IDAT: zlib-compressed scanlines, filter byte 0 (None) per row
 *   - Optional iCCP chunk (callers compose it via [IccProfileWriter])
 *
 * PNG samples are stored big-endian regardless of host byte order (per PNG spec).
 *
 * @see  http://www.libpng.org/pub/png/spec/1.2/PNG-Chunks.html
 */
object Png16Writer {

    /**
     * Encode a 16-bit-per-channel RGB image to PNG bytes.
     *
     * @param pixels      Pixel data in row-major order, 3 unsigned shorts per pixel (R, G, B).
     *                    Length must equal `width * height * 3`. Stored as `IntArray` of unsigned
     *                    16-bit values in the range [0, 65535] for convenience.
     * @param width       Image width in pixels.
     * @param height      Image height in pixels.
     */
    fun encodeRgb16(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(pixels.size == width * height * 3) {
            "pixels.size=${pixels.size} but expected ${width * height * 3} for ${width}x$height RGB"
        }
        return encode(
            width = width,
            height = height,
            bitDepth = 16,
            colorType = 2,        // truecolor RGB (no alpha)
            samplesPerPixel = 3,
            scanlineBytes = buildRgb16Scanlines(pixels, width, height),
        )
    }

    /**
     * Encode a 16-bit-per-channel RGBA image to PNG bytes.
     *
     * @param pixels      Pixel data in row-major order, 4 unsigned shorts per pixel (R, G, B, A).
     *                    Length must equal `width * height * 4`. Stored as `IntArray` of unsigned
     *                    16-bit values in the range [0, 65535].
     */
    fun encodeRgba16(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(pixels.size == width * height * 4) {
            "pixels.size=${pixels.size} but expected ${width * height * 4} for ${width}x$height RGBA"
        }
        return encode(
            width = width,
            height = height,
            bitDepth = 16,
            colorType = 6,        // truecolor + alpha
            samplesPerPixel = 4,
            scanlineBytes = buildRgba16Scanlines(pixels, width, height),
        )
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun encode(
        width: Int,
        height: Int,
        bitDepth: Int,
        colorType: Int,
        samplesPerPixel: Int,
        scanlineBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // PNG signature
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR
        val ihdr = ByteArrayOutputStream().apply {
            writeInt32Be(width)
            writeInt32Be(height)
            write(bitDepth)
            write(colorType)
            write(0)              // compression method = deflate
            write(0)              // filter method = adaptive (filter byte per scanline)
            write(0)              // interlace = none
        }
        writeChunk(out, "IHDR", ihdr.toByteArray())
        // IDAT (one chunk is fine for our sizes; we could split but it's not required)
        val compressed = deflate(scanlineBytes)
        writeChunk(out, "IDAT", compressed)
        // IEND
        writeChunk(out, "IEND", ByteArray(0))
        @Suppress("UNUSED_VARIABLE")
        val unused = samplesPerPixel  // kept for clarity; not directly used after scanline build
        return out.toByteArray()
    }

    /**
     * Fan-out helper: process the height range [0, height) across 4 worker
     * threads. Each worker is given its (y0, y1) row band and writes to a
     * non-overlapping byte slice of the shared output ByteArray. No
     * synchronization needed — disjoint write ranges.
     */
    private inline fun parallelRows(height: Int, crossinline body: (y0: Int, y1: Int) -> Unit) {
        if (height < 4) { body(0, height); return }
        val n = 4
        val band = (height + n - 1) / n
        val threads = (0 until n).mapNotNull { t ->
            val y0 = t * band
            val y1 = minOf(y0 + band, height)
            if (y0 >= y1) null
            else Thread { body(y0, y1) }.apply { start() }
        }
        threads.forEach { it.join() }
    }

    private fun buildRgb16Scanlines(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bytesPerPixel = 6                // 16-bit × 3 samples
        val rowBytes = width * bytesPerPixel
        val rowStride = 1 + rowBytes         // filter byte + rowBytes
        val out = ByteArray(height * rowStride)
        parallelRows(height) { y0, y1 ->
            var inIdx = y0 * width * 3
            var outIdx = y0 * rowStride
            for (y in y0 until y1) {
                out[outIdx++] = 0            // filter type: None
                for (x in 0 until width) {
                    val r = pixels[inIdx++]
                    val g = pixels[inIdx++]
                    val b = pixels[inIdx++]
                    out[outIdx++] = ((r ushr 8) and 0xFF).toByte()
                    out[outIdx++] = (r and 0xFF).toByte()
                    out[outIdx++] = ((g ushr 8) and 0xFF).toByte()
                    out[outIdx++] = (g and 0xFF).toByte()
                    out[outIdx++] = ((b ushr 8) and 0xFF).toByte()
                    out[outIdx++] = (b and 0xFF).toByte()
                }
            }
        }
        return out
    }

    private fun buildRgba16Scanlines(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bytesPerPixel = 8                // 16-bit × 4 samples
        val rowBytes = width * bytesPerPixel
        val rowStride = 1 + rowBytes
        val out = ByteArray(height * rowStride)
        parallelRows(height) { y0, y1 ->
            var inIdx = y0 * width * 4
            var outIdx = y0 * rowStride
            for (y in y0 until y1) {
                out[outIdx++] = 0
                for (x in 0 until width) {
                    val r = pixels[inIdx++]
                    val g = pixels[inIdx++]
                    val b = pixels[inIdx++]
                    val a = pixels[inIdx++]
                    out[outIdx++] = ((r ushr 8) and 0xFF).toByte()
                    out[outIdx++] = (r and 0xFF).toByte()
                    out[outIdx++] = ((g ushr 8) and 0xFF).toByte()
                    out[outIdx++] = (g and 0xFF).toByte()
                    out[outIdx++] = ((b ushr 8) and 0xFF).toByte()
                    out[outIdx++] = (b and 0xFF).toByte()
                    out[outIdx++] = ((a ushr 8) and 0xFF).toByte()
                    out[outIdx++] = (a and 0xFF).toByte()
                }
            }
        }
        return out
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        out.writeInt32Be(data.size)
        out.write(typeBytes)
        out.write(data)
        out.writeInt32Be(crc)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream(data.size / 2)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt32Be(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }
}
