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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin TIFF 6.0 Baseline encoder for 16-bit-per-sample RGB images.
 *
 * Emits little-endian TIFF (`II*\0` magic) with a single IFD describing one strip of
 * uncompressed RGB pixel data. Each sample is 16-bit unsigned. Optional embedded ICC
 * profile via tag 34675 (`ICC Profile`).
 *
 * This is intentionally a minimal Baseline implementation — no LZW/Deflate compression,
 * no tiling, no multi-strip. It is sufficient for archival 16-bit export from the RAW
 * pipeline and is readable by every TIFF-aware tool (Photoshop, Lightroom, GIMP, libtiff).
 *
 * Reference: TIFF Revision 6.0, Adobe, June 3 1992.
 *   https://download.osgeo.org/libtiff/doc/TIFF6.pdf
 */
object Tiff16Writer {

    // ── Field types ────────────────────────────────────────────────────────────
    private const val TYPE_BYTE      = 1
    private const val TYPE_ASCII     = 2
    private const val TYPE_SHORT     = 3
    private const val TYPE_LONG      = 4
    private const val TYPE_RATIONAL  = 5
    private const val TYPE_UNDEFINED = 7

    // ── Tag IDs ────────────────────────────────────────────────────────────────
    private const val TAG_IMAGE_WIDTH              = 256
    private const val TAG_IMAGE_LENGTH             = 257
    private const val TAG_BITS_PER_SAMPLE          = 258
    private const val TAG_COMPRESSION              = 259
    private const val TAG_PHOTOMETRIC              = 262
    private const val TAG_STRIP_OFFSETS            = 273
    private const val TAG_SAMPLES_PER_PIXEL        = 277
    private const val TAG_ROWS_PER_STRIP           = 278
    private const val TAG_STRIP_BYTE_COUNTS        = 279
    private const val TAG_X_RESOLUTION             = 282
    private const val TAG_Y_RESOLUTION             = 283
    private const val TAG_PLANAR_CONFIGURATION     = 284
    private const val TAG_RESOLUTION_UNIT          = 296
    private const val TAG_SAMPLE_FORMAT            = 339
    private const val TAG_ICC_PROFILE              = 34675

    // ── Constants ──────────────────────────────────────────────────────────────
    private const val COMPRESSION_NONE             = 1
    private const val PHOTOMETRIC_RGB              = 2
    private const val PLANAR_CHUNKY                = 1
    private const val RESOLUTION_INCH              = 2
    private const val SAMPLE_FORMAT_UINT           = 1   // unsigned 16-bit integer

    /**
     * Encode a 16-bit-per-channel RGB image as a TIFF 6.0 Baseline file.
     *
     * @param pixels      Pixel data in row-major order, 3 unsigned 16-bit samples per pixel
     *                    (R, G, B). Length must equal `width * height * 3`. Stored as
     *                    `IntArray` of values in the range [0, 65535].
     * @param width       Image width in pixels.
     * @param height      Image height in pixels.
     * @param iccProfile  Optional embedded ICC profile bytes (raw, uncompressed). When non-null
     *                    a 34675 (`ICC Profile`) IFD entry is added.
     */
    fun encodeRgb16(
        pixels: IntArray,
        width: Int,
        height: Int,
        iccProfile: ByteArray? = null,
    ): ByteArray {
        require(pixels.size == width * height * 3) {
            "pixels.size=${pixels.size} but expected ${width * height * 3} for ${width}x$height RGB"
        }

        // ── Layout plan ─────────────────────────────────────────────────────
        // 0:        TIFF header (8 bytes)
        // 8:        IFD0 (2-byte count + 12 × entries + 4-byte next-IFD pointer)
        // after:    External arrays: BitsPerSample (6 bytes), XRes (8), YRes (8), optional ICC
        // then:     Strip pixel data
        //
        // Per spec each external offset must be even, so we pad strategically.
        val numEntries = if (iccProfile != null) 15 else 14
        val ifdSize = 2 + numEntries * 12 + 4
        val headerSize = 8

        val bpsBytes = 6                    // 3 SHORT values = 6 bytes
        val xResBytes = 8                   // 1 RATIONAL = 8 bytes
        val yResBytes = 8

        val iccBytes = iccProfile?.size ?: 0
        val iccPadded = (iccBytes + 1) and 1.inv()  // pad to even length

        // Strip data
        val stripBytes = width * height * 3 * 2
        val rowsPerStrip = height

        // Place external arrays after the IFD, in this order:
        //   bps, xRes, yRes, icc (if any), stripData
        // All must start on even offsets.
        val bpsOffset    = headerSize + ifdSize
        val xResOffset   = (bpsOffset + bpsBytes + 1) and 1.inv()
        val yResOffset   = (xResOffset + xResBytes + 1) and 1.inv()
        val iccOffset    = if (iccProfile != null) (yResOffset + yResBytes + 1) and 1.inv() else 0
        val stripOffset  = if (iccProfile != null) (iccOffset + iccPadded + 1) and 1.inv()
                           else                    (yResOffset + yResBytes + 1) and 1.inv()
        val totalSize    = stripOffset + stripBytes

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // ── Header ──────────────────────────────────────────────────────────
        buf.put('I'.code.toByte())          // II = little-endian
        buf.put('I'.code.toByte())
        buf.putShort(42)                    // magic
        buf.putInt(headerSize)              // offset to IFD0

        // ── IFD ─────────────────────────────────────────────────────────────
        // Build IFD entries in ascending-tag order (TIFF requires this).
        buf.position(headerSize)
        buf.putShort(numEntries.toShort())

        putShortEntry  (buf, TAG_IMAGE_WIDTH,           width)
        putShortEntry  (buf, TAG_IMAGE_LENGTH,          height)
        // BitsPerSample: 3 SHORT values, stored externally because > 4 bytes total
        putExternalEntry(buf, TAG_BITS_PER_SAMPLE, TYPE_SHORT, count = 3, offset = bpsOffset)
        putShortEntry  (buf, TAG_COMPRESSION,           COMPRESSION_NONE)
        putShortEntry  (buf, TAG_PHOTOMETRIC,           PHOTOMETRIC_RGB)
        putLongEntry   (buf, TAG_STRIP_OFFSETS,         stripOffset)
        putShortEntry  (buf, TAG_SAMPLES_PER_PIXEL,     3)
        putLongEntry   (buf, TAG_ROWS_PER_STRIP,        rowsPerStrip)
        putLongEntry   (buf, TAG_STRIP_BYTE_COUNTS,     stripBytes)
        putExternalEntry(buf, TAG_X_RESOLUTION, TYPE_RATIONAL, count = 1, offset = xResOffset)
        putExternalEntry(buf, TAG_Y_RESOLUTION, TYPE_RATIONAL, count = 1, offset = yResOffset)
        putShortEntry  (buf, TAG_PLANAR_CONFIGURATION,  PLANAR_CHUNKY)
        putShortEntry  (buf, TAG_RESOLUTION_UNIT,       RESOLUTION_INCH)
        putShortEntry  (buf, TAG_SAMPLE_FORMAT,         SAMPLE_FORMAT_UINT)
        if (iccProfile != null) {
            putExternalEntry(buf, TAG_ICC_PROFILE, TYPE_UNDEFINED, count = iccBytes, offset = iccOffset)
        }

        // Next IFD pointer (0 = none)
        buf.putInt(0)

        // ── External arrays ─────────────────────────────────────────────────
        buf.position(bpsOffset)
        buf.putShort(16); buf.putShort(16); buf.putShort(16)

        buf.position(xResOffset)
        buf.putInt(72); buf.putInt(1)       // 72/1 = 72 dpi
        buf.position(yResOffset)
        buf.putInt(72); buf.putInt(1)

        if (iccProfile != null) {
            buf.position(iccOffset)
            buf.put(iccProfile)
        }

        // ── Strip pixel data (little-endian 16-bit samples) ─────────────────
        buf.position(stripOffset)
        for (v in pixels) {
            buf.putShort((v and 0xFFFF).toShort())
        }

        return buf.array()
    }

    // ── IFD entry helpers ──────────────────────────────────────────────────────

    /**
     * Write an IFD entry whose value fits within the 4-byte value field as a single SHORT.
     * The remaining 2 bytes of the value field are zeroed (TIFF spec — unused part should be 0).
     */
    private fun putShortEntry(buf: ByteBuffer, tag: Int, value: Int) {
        buf.putShort(tag.toShort())
        buf.putShort(TYPE_SHORT.toShort())
        buf.putInt(1)                       // count = 1
        buf.putShort((value and 0xFFFF).toShort())
        buf.putShort(0)                     // pad to 4 bytes
    }

    /** Write an IFD entry whose value fits in 4 bytes as a single LONG. */
    private fun putLongEntry(buf: ByteBuffer, tag: Int, value: Int) {
        buf.putShort(tag.toShort())
        buf.putShort(TYPE_LONG.toShort())
        buf.putInt(1)                       // count = 1
        buf.putInt(value)
    }

    /** Write an IFD entry whose payload lives at [offset] in the file. */
    private fun putExternalEntry(buf: ByteBuffer, tag: Int, type: Int, count: Int, offset: Int) {
        buf.putShort(tag.toShort())
        buf.putShort(type.toShort())
        buf.putInt(count)
        buf.putInt(offset)
    }
}
