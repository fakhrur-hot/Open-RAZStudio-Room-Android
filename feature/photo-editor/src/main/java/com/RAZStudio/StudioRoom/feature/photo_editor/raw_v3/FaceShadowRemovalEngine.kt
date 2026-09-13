/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Portrait face shadow removal via classical illumination normalisation with
 * skin-tone weighting. Shadow pixels in skin-hue regions are lifted gently;
 * background shadow pixels are lifted more aggressively.
 * No ONNX models required — runs entirely on CPU.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FaceShadowRemovalEngine(private val context: Context) {

    val isAvailable: Boolean get() = true

    suspend fun removeFaceShadows(src: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            val r = FloatArray(w * h)
            val g = FloatArray(w * h)
            val b = FloatArray(w * h)
            for (i in pixels.indices) {
                val px = pixels[i]
                r[i] = ((px shr 16) and 0xFF) / 255f
                g[i] = ((px shr 8)  and 0xFF) / 255f
                b[i] = ( px         and 0xFF) / 255f
            }

            val lum = FloatArray(w * h) { i -> 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i] }

            // Illumination estimate via large Gaussian blur.
            val radius = max(30, min(w, h) / 12)
            val illum = ShadowRemovalEngine.gaussianBlur(lum, w, h, radius)
            val meanIllum = illum.average().toFloat().let { if (it < 0.01f) 0.5f else it }

            val loThr = meanIllum * 0.55f
            val hiThr = meanIllum * 0.85f

            val outPixels = IntArray(w * h)
            for (i in 0 until w * h) {
                val il = illum[i]
                val shadow = when {
                    il <= loThr -> 1f
                    il >= hiThr -> 0f
                    else -> 1f - (il - loThr) / (hiThr - loThr)
                }

                if (shadow > 0f) {
                    // Skin-tone detection: warm hue + moderate saturation.
                    // In RGB: R > G > B, R substantially > B.
                    val isSkin = r[i] > 0.35f && r[i] > g[i] && g[i] > b[i] &&
                                 (r[i] - b[i]) > 0.1f && lum[i] > 0.15f

                    // Gentler lift for skin (avoid washing out face), stronger for background.
                    val maxLift = if (isSkin) 1.8f else 2.5f
                    val liftFactor = if (il > 0.01f) min(meanIllum / il, maxLift) else 1f
                    val blended = 1f + (liftFactor - 1f) * shadow

                    val ri = ((r[i] * blended).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val gi = ((g[i] * blended).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val bi = ((b[i] * blended).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    outPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                } else {
                    outPixels[i] = pixels[i]
                }
            }

            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(outPixels, 0, w, 0, 0, w, h)
            Log.i(TAG, "removeFaceShadows: done (${w}x${h} radius=$radius meanIllum=${"%.3f".format(meanIllum)})")
            result
        }.getOrElse { e ->
            Log.e(TAG, "removeFaceShadows failed: ${e.message}", e)
            null
        }
    }

    fun release() {}

    companion object {
        private const val TAG = "FaceShadowRemoval"
    }
}
