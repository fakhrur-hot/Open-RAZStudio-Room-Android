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

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Extract 16-bit-per-channel RGB samples from any Bitmap, for handoff to
 * [Png16Writer] / [Tiff16Writer].
 *
 * Two source paths:
 *
 *   - **`ARGB_8888`**: each 8-bit channel is widened to 16-bit by multiplying
 *     by 257 (== `0x0101`), which maps `[0, 255]` to `[0, 65535]` with no
 *     quantization gaps. The output is a 16-bit *container* with 8-bit
 *     effective precision — useful for archival or for tools that expect a
 *     16-bit file.
 *
 *   - **`RGBA_F16`**: each linear-light half-float is gamma-encoded with sRGB
 *     transfer (the encoded output is sRGB-compatible 16-bit; if the source
 *     bitmap is tagged DISPLAY_P3 or another wide gamut the encoding still
 *     produces correct values *for that gamut* — the gamut survives in the
 *     ICC profile embedded by the caller, not by re-projection here). Each
 *     value is clamped to `[0,1]` then scaled to `[0, 65535]`.
 *
 * The returned `IntArray` has length `width * height * 3` with samples in
 * R, G, B order, each value in `[0, 65535]`.
 */
object Bitmap16Sampler {

    fun extractRgb16(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        return when (bitmap.config) {
            Bitmap.Config.RGBA_F16 -> extractFromF16(bitmap, w, h)
            else                    -> extractFromArgb8888(bitmap, w, h)
        }
    }

    private fun extractFromArgb8888(bitmap: Bitmap, w: Int, h: Int): IntArray {
        // Materialise as ARGB_8888 if the source happens to be anything else
        // (RGB_565, etc.) so getPixels works uniformly.
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                  else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        val argb = IntArray(w * h)
        src.getPixels(argb, 0, w, 0, 0, w, h)
        if (src !== bitmap && !src.isRecycled) {
            // Caller still owns `bitmap`; release the temporary copy.
            src.recycle()
        }
        val out = IntArray(w * h * 3)
        var oi = 0
        for (px in argb) {
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            // v16 = v8 * 257 maps [0,255] -> [0,65535] with no quantization gap.
            out[oi++] = r * 257
            out[oi++] = g * 257
            out[oi++] = b * 257
        }
        return out
    }

    private fun extractFromF16(bitmap: Bitmap, w: Int, h: Int): IntArray {
        val pixelCount = w * h
        val buf = ByteBuffer.allocateDirect(pixelCount * 8).order(ByteOrder.LITTLE_ENDIAN)
        bitmap.copyPixelsToBuffer(buf)
        buf.rewind()
        val out = IntArray(pixelCount * 3)
        var oi = 0
        for (i in 0 until pixelCount) {
            val rh = buf.short
            val gh = buf.short
            val bh = buf.short
            buf.short  // alpha, discarded
            val rf = halfBitsToFloat(rh).coerceIn(0f, 1f)
            val gf = halfBitsToFloat(gh).coerceIn(0f, 1f)
            val bf = halfBitsToFloat(bh).coerceIn(0f, 1f)
            // sRGB transfer for display-compatible 16-bit output. The Stage C
            // bitmap is tagged linear-extended-sRGB so the half-floats are
            // linear; we encode here using a single gamma-2.2 approximation
            // matching the existing 8-bit Stage C path. The full piecewise
            // sRGB curve lands in a follow-up turn.
            val rg = encodeSrgb(rf)
            val gg = encodeSrgb(gf)
            val bg = encodeSrgb(bf)
            out[oi++] = (rg * 65535f + 0.5f).toInt().coerceIn(0, 65535)
            out[oi++] = (gg * 65535f + 0.5f).toInt().coerceIn(0, 65535)
            out[oi++] = (bg * 65535f + 0.5f).toInt().coerceIn(0, 65535)
        }
        return out
    }

    /** Decode a half-float bit pattern into a Float. */
    private fun halfBitsToFloat(s: Short): Float {
        val bits = s.toInt() and 0xFFFF
        val sign = (bits ushr 15) and 0x1
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        val signI = sign shl 31
        return when {
            exp == 0 && mant == 0 -> java.lang.Float.intBitsToFloat(signI)
            exp == 0 -> {
                // Subnormal half
                var m = mant
                var e = -14
                while ((m and 0x400) == 0) { m = m shl 1; e-- }
                m = m and 0x3FF
                java.lang.Float.intBitsToFloat(signI or ((e + 127) shl 23) or (m shl 13))
            }
            exp == 0x1F -> java.lang.Float.intBitsToFloat(signI or 0x7F800000 or (mant shl 13))
            else -> java.lang.Float.intBitsToFloat(signI or ((exp + 112) shl 23) or (mant shl 13))
        }
    }

    /** Approximate sRGB encoding via γ2.2 (matches existing 8-bit Stage C). */
    private fun encodeSrgb(linear: Float): Float =
        linear.toDouble().pow(1.0 / 2.2).toFloat()
}
