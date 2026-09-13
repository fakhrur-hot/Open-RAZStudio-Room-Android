/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.graphics.Bitmap
import android.graphics.Matrix
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.LibRawJniBridge.LinearDecodeResult
import kotlin.math.pow

/**
 * Converts a [LinearDecodeResult] (float16 RGBA, camera-native colour space, [0,1] normalised)
 * into an 8-bit sRGB [Bitmap] suitable for display and editing.
 *
 * Pipeline per pixel:
 *   1. Decode float16 → float32
 *   2. Apply the 3×4 rgbCam matrix (camera native → sRGB linear)
 *   3. Apply sRGB gamma (IEC 61966-2-1)
 *   4. Pack into ARGB_8888
 */
object RawBitmapConverter {

    // 1024-entry sRGB gamma LUT: index = (linear * 1023).toInt(), value = 8-bit output
    private val SRGB_LUT: IntArray = IntArray(1024) { i ->
        val lin = i.toFloat() / 1023f
        val srgb = if (lin <= 0.0031308f) lin * 12.92f
                   else 1.055f * lin.pow(1f / 2.4f) - 0.055f
        (srgb.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }

    /**
     * Converts to sRGB bitmap AND applies EXIF orientation (1-8) so the result is
     * always upright — no separate rotation step needed by the caller.
     */
    fun linearToBitmap(result: LinearDecodeResult): Bitmap {
        val w = result.width
        val h = result.height
        val px = result.pixels          // float16 RGBA, 8 bytes/pixel, row-major
        val meta = result.metadata

        // Build 3×3 colour matrix from the 3×4 rgbCam (skip 4th column).
        // Falls back to identity if matrix is zero/unpopulated.
        val m = meta.rgbCam
        val hasMatrix = m.size >= 11 && m.any { it != 0f }
        val m00 = if (hasMatrix) m[0]  else 1f; val m01 = if (hasMatrix) m[1]  else 0f; val m02 = if (hasMatrix) m[2]  else 0f
        val m10 = if (hasMatrix) m[4]  else 0f; val m11 = if (hasMatrix) m[5]  else 1f; val m12 = if (hasMatrix) m[6]  else 0f
        val m20 = if (hasMatrix) m[8]  else 0f; val m21 = if (hasMatrix) m[9]  else 0f; val m22 = if (hasMatrix) m[10] else 1f

        val argb = IntArray(w * h)
        var byteOff = 0

        for (i in 0 until w * h) {
            val rBits = ((px[byteOff + 1].toInt() and 0xFF) shl 8) or (px[byteOff].toInt() and 0xFF)
            val gBits = ((px[byteOff + 3].toInt() and 0xFF) shl 8) or (px[byteOff + 2].toInt() and 0xFF)
            val bBits = ((px[byteOff + 5].toInt() and 0xFF) shl 8) or (px[byteOff + 4].toInt() and 0xFF)
            byteOff += 8

            val r = halfToFloat(rBits)
            val g = halfToFloat(gBits)
            val b = halfToFloat(bBits)

            val rs = (m00 * r + m01 * g + m02 * b).coerceIn(0f, 1f)
            val gs = (m10 * r + m11 * g + m12 * b).coerceIn(0f, 1f)
            val bs = (m20 * r + m21 * g + m22 * b).coerceIn(0f, 1f)

            val r8 = SRGB_LUT[(rs * 1023f + 0.5f).toInt().coerceIn(0, 1023)]
            val g8 = SRGB_LUT[(gs * 1023f + 0.5f).toInt().coerceIn(0, 1023)]
            val b8 = SRGB_LUT[(bs * 1023f + 0.5f).toInt().coerceIn(0, 1023)]

            argb[i] = (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
        }

        val raw = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        return applyExifOrientation(raw, result.metadata.orientation)
    }

    // EXIF orientation 1-8 → physically rotate/flip the bitmap so it is always upright.
    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.postScale(-1f, 1f)
            3 -> matrix.postRotate(180f)
            4 -> matrix.postScale(1f, -1f)
            5 -> { matrix.postRotate(90f);  matrix.postScale(1f, -1f) }
            6 -> matrix.postRotate(90f)
            7 -> { matrix.postRotate(90f);  matrix.postScale(-1f, 1f) }
            8 -> matrix.postRotate(270f)
            else -> return src  // orientation == 1 or unknown: no change
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    // IEEE 754 float16 → float32 conversion
    private fun halfToFloat(bits: Int): Float {
        val e = (bits and 0x7C00) shr 10
        val m = bits and 0x03FF
        val s = (bits and 0x8000) shl 16
        return when {
            e == 0 && m == 0 -> java.lang.Float.intBitsToFloat(s)
            e == 0 -> {
                var mantissa = m
                var exp = -14
                while (mantissa and 0x0400 == 0) { mantissa = mantissa shl 1; exp-- }
                java.lang.Float.intBitsToFloat(s or ((exp + 127) shl 23) or ((mantissa and 0x03FF) shl 13))
            }
            e == 31 -> java.lang.Float.intBitsToFloat(s or 0x7F800000 or (m shl 13))
            else -> java.lang.Float.intBitsToFloat(s or ((e + 112) shl 23) or (m shl 13))
        }
    }
}
