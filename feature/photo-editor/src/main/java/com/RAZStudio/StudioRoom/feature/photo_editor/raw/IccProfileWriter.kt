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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.roundToInt

/**
 * Embeds ICC color profile information into PNG byte streams.
 *
 * For [RawColorSpace.SRGB]: inserts a 1-byte `sRGB` PNG chunk (Perceptual intent).
 * For [RawColorSpace.PROPHOTO_RGB]: inserts an `iCCP` chunk with a minimal ICC v2
 *   ROMM RGB / ProPhoto RGB profile — ProPhoto primaries (D50), γ1.8 transfer function.
 *
 * The chunk is inserted immediately after the IHDR chunk (PNG offset 33).
 * EXIF must be embedded BEFORE calling [embedColorSpace] since ExifInterface
 * may rewrite the PNG and discard manually inserted chunks.
 */
internal object IccProfileWriter {

    fun embedColorSpace(pngBytes: ByteArray, colorSpace: RawColorSpace): ByteArray {
        val chunk = when (colorSpace) {
            RawColorSpace.SRGB         -> buildSrgbChunk()
            // Step 2 of raw-pipeline-upgrade will replace this with a real Display P3
            // iCCP chunk. Until then, fall back to the sRGB chunk so the PNG export
            // path stays valid for the new enum value.
            RawColorSpace.DISPLAY_P3   -> buildSrgbChunk()
            RawColorSpace.PROPHOTO_RGB -> buildIccpChunk("ROMM RGB", buildProPhotoProfile())
        }
        return insertAfterIhdr(pngBytes, chunk)
    }

    // ── PNG chunk builders ─────────────────────────────────────────────────────

    private fun buildSrgbChunk(): ByteArray =
        buildPngChunk("sRGB", byteArrayOf(0x00))  // 0x00 = Perceptual rendering intent

    private fun buildIccpChunk(name: String, iccData: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        val data = nameBytes + byteArrayOf(0x00, 0x00) + zlibCompress(iccData)
        //                     ^null terminator  ^compression method (0=deflate)
        return buildPngChunk("iCCP", data)
    }

    private fun buildPngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcVal = crc.value.toInt()
        val out = ByteArrayOutputStream(4 + 4 + data.size + 4)
        out.writeInt32Be(data.size)
        out.write(typeBytes)
        out.write(data)
        out.writeInt32Be(crcVal)
        return out.toByteArray()
    }

    private fun insertAfterIhdr(pngBytes: ByteArray, chunk: ByteArray): ByteArray {
        // PNG signature (8) + IHDR (4+4+13+4 = 25) = 33 bytes
        val insertAt = 33
        if (pngBytes.size < insertAt) return pngBytes
        return pngBytes.copyOfRange(0, insertAt) + chunk + pngBytes.copyOfRange(insertAt, pngBytes.size)
    }

    private fun zlibCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt32Be(v: Int) {
        write(v ushr 24 and 0xFF)
        write(v ushr 16 and 0xFF)
        write(v ushr  8 and 0xFF)
        write(v         and 0xFF)
    }

    // ── Minimal ICC v2 profile generator ──────────────────────────────────────
    //
    // Profile layout (312 bytes):
    //   Header:    128 bytes  (offset 0)
    //   Tag table: 88 bytes   (offset 128): count=7 + 7×12-byte entries
    //   rXYZ:      20 bytes   (offset 216)
    //   gXYZ:      20 bytes   (offset 236)
    //   bXYZ:      20 bytes   (offset 256)
    //   rTRC/gTRC/bTRC (shared): 14 bytes (offset 276) + 2 pad → next at 292
    //   wtpt:      20 bytes   (offset 292)
    //   Total:     312 bytes

    private fun buildProfile(
        rX: Double, rY: Double, rZ: Double,
        gX: Double, gY: Double, gZ: Double,
        bX: Double, bY: Double, bZ: Double,
        gammau8_8: Int,
    ): ByteArray {
        val buf = ByteArrayOutputStream(312)
        fun i32(v: Int) { buf.writeInt32Be(v) }
        fun f1616(v: Double) { i32((v * 65536.0).roundToInt()) }

        // ── Header (128 bytes) ────────────────────────────────────────────────
        i32(312)              // profile size
        i32(0x6C636D73)       // CMM type 'lcms'
        i32(0x02100000)       // version 2.1.0
        i32(0x6D6E7472)       // class 'mntr' (display)
        i32(0x52474220)       // color space 'RGB '
        i32(0x58595A20)       // PCS 'XYZ '
        // date/time: 6×uint16 — use zeros (1970-01-01 00:00:00)
        repeat(6) { buf.write(0); buf.write(0) }
        i32(0x61637370)       // file signature 'acsp'
        i32(0)                // platform: unspecified
        i32(0)                // profile flags
        i32(0); i32(0)        // device manufacturer, model
        i32(0); i32(0)        // device attributes (8 bytes = 2×int32)
        i32(0)                // rendering intent: Perceptual
        // Illuminant XYZ (D50 whitepoint): X=0.9642, Y=1.0000, Z=0.8249
        f1616(0.9642); f1616(1.0000); f1616(0.8249)
        i32(0)                // creator
        repeat(16) { buf.write(0) }  // profile ID (MD5, zeroed)
        repeat(28) { buf.write(0) }  // reserved

        // ── Tag table (4+7×12 = 88 bytes, starting at offset 128) ─────────────
        i32(7)  // tag count
        fun tagEntry(sig: Int, offset: Int, size: Int) { i32(sig); i32(offset); i32(size) }
        tagEntry(0x7258595A, 216, 20)  // rXYZ
        tagEntry(0x6758595A, 236, 20)  // gXYZ
        tagEntry(0x6258595A, 256, 20)  // bXYZ
        tagEntry(0x72545243, 276, 14)  // rTRC
        tagEntry(0x67545243, 276, 14)  // gTRC (shared)
        tagEntry(0x62545243, 276, 14)  // bTRC (shared)
        tagEntry(0x77747074, 292, 20)  // wtpt

        // ── XYZ type helper (20 bytes: sig + reserved + 3×s15.16) ─────────────
        fun xyzTag(x: Double, y: Double, z: Double) {
            i32(0x58595A20); i32(0)  // 'XYZ ' + reserved
            f1616(x); f1616(y); f1616(z)
        }

        // ── Tag data ──────────────────────────────────────────────────────────
        xyzTag(rX, rY, rZ)   // rXYZ at offset 216
        xyzTag(gX, gY, gZ)   // gXYZ at offset 236
        xyzTag(bX, bY, bZ)   // bXYZ at offset 256

        // TRC shared at offset 276 (curv type, 1 entry, gamma as u8.8)
        i32(0x63757276); i32(0)        // 'curv' + reserved
        i32(1)                          // count = 1 (single gamma value)
        buf.write(gammau8_8 ushr 8 and 0xFF)
        buf.write(gammau8_8 and 0xFF)
        buf.write(0); buf.write(0)      // 2-byte padding to reach 4-byte alignment (offset 292)

        xyzTag(0.9642, 1.0000, 0.8249) // wtpt at offset 292 (D50)

        return buf.toByteArray()
    }

    private fun buildProPhotoProfile(): ByteArray = buildProfile(
        // D50 primaries from ROMM RGB / ISO 22028-2 spec (columns of ProPhoto→XYZ D50 matrix)
        rX = 0.7977604, rY = 0.2880402, rZ = 0.0000000,
        gX = 0.1351917, gY = 0.7118741, gZ = 0.0000000,
        bX = 0.0313534, bY = 0.0000857, bZ = 0.8252100,
        gammau8_8 = 0x01CD,  // γ1.8 in u8.8 fixed point (461 / 256 ≈ 1.801)
    )
}
