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

package com.RAZStudio.StudioRoom.core.data.image

import android.graphics.Bitmap

internal object PostResizeSharpen {

    fun apply(bitmap: Bitmap, strength: Float): Bitmap {
        if (strength == 0f) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val px = IntArray(n)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        val fr = FloatArray(n) { i -> ((px[i] shr 16) and 0xFF) / 255f }
        val fg = FloatArray(n) { i -> ((px[i] shr 8) and 0xFF) / 255f }
        val fb = FloatArray(n) { i -> (px[i] and 0xFF) / 255f }

        val br = FloatArray(n)
        val bg = FloatArray(n)
        val bb = FloatArray(n)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val x0 = x
                val xm1 = (x - 1).coerceAtLeast(0)
                val xp1 = (x + 1).coerceAtMost(w - 1)
                val xm3 = (x - 3).coerceAtLeast(0)
                val xp3 = (x + 3).coerceAtMost(w - 1)
                val ym1w = (y - 1).coerceAtLeast(0) * w
                val yp1w = (y + 1).coerceAtMost(h - 1) * w
                val ym3w = (y - 3).coerceAtLeast(0) * w
                val yp3w = (y + 3).coerceAtMost(h - 1) * w
                val yw = y * w

                br[i] = (fr[ym1w + x0] + fr[yp1w + x0] + fr[yw + xm1] + fr[yw + xp1] +
                         fr[ym3w + x0] + fr[yp3w + x0] + fr[yw + xm3] + fr[yw + xp3]) * 0.125f
                bg[i] = (fg[ym1w + x0] + fg[yp1w + x0] + fg[yw + xm1] + fg[yw + xp1] +
                         fg[ym3w + x0] + fg[yp3w + x0] + fg[yw + xm3] + fg[yw + xp3]) * 0.125f
                bb[i] = (fb[ym1w + x0] + fb[yp1w + x0] + fb[yw + xm1] + fb[yw + xp1] +
                         fb[ym3w + x0] + fb[yp3w + x0] + fb[yw + xm3] + fb[yw + xp3]) * 0.125f
            }
        }

        val result = IntArray(n)
        val s = strength * 2f
        for (i in 0 until n) {
            val r = (fr[i] + s * (fr[i] - br[i])).coerceIn(0f, 1f)
            val g = (fg[i] + s * (fg[i] - bg[i])).coerceIn(0f, 1f)
            val b = (fb[i] + s * (fb[i] - bb[i])).coerceIn(0f, 1f)
            result[i] = (0xFF shl 24) or
                ((r * 255f + 0.5f).toInt() shl 16) or
                ((g * 255f + 0.5f).toInt() shl 8) or
                (b * 255f + 0.5f).toInt()
        }
        return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }
}
