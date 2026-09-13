/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Shadow removal via classical illumination normalisation.
 * Algorithm: estimate the illumination field by large-radius Gaussian blur of
 * the luminance channel, then lift shadow pixels toward the mean illumination.
 * No ONNX models required — runs entirely on CPU in linear float space.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ShadowRemovalEngine(private val context: Context) {

    // Always available — no external models needed.
    val isAvailable: Boolean get() = true

    suspend fun removeShadows(src: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            // Convert to float RGB [0..1]
            val r = FloatArray(w * h)
            val g = FloatArray(w * h)
            val b = FloatArray(w * h)
            for (i in pixels.indices) {
                val px = pixels[i]
                r[i] = ((px shr 16) and 0xFF) / 255f
                g[i] = ((px shr 8)  and 0xFF) / 255f
                b[i] = ( px         and 0xFF) / 255f
            }

            // Luminance
            val lum = FloatArray(w * h) { i -> 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i] }

            // Large Gaussian blur of luminance → illumination estimate.
            // Radius = ~8% of the shorter dimension, min 30px.
            val radius = max(30, min(w, h) / 12)
            val illum = gaussianBlur(lum, w, h, radius)

            // Global mean illumination — target brightness for shadow pixels.
            val meanIllum = illum.average().toFloat().let { if (it < 0.01f) 0.5f else it }

            // Shadow mask: pixels whose illumination is significantly below mean.
            // Threshold: 70% of mean. Soft ramp between 50%–80% of mean.
            val loThr = meanIllum * 0.50f
            val hiThr = meanIllum * 0.80f

            val outR = FloatArray(w * h)
            val outG = FloatArray(w * h)
            val outB = FloatArray(w * h)

            for (i in 0 until w * h) {
                val il = illum[i]
                // Shadow strength: 1.0 = fully in shadow, 0.0 = lit
                val shadow = when {
                    il <= loThr -> 1f
                    il >= hiThr -> 0f
                    else -> 1f - (il - loThr) / (hiThr - loThr)
                }

                if (shadow > 0f) {
                    // Lift factor: scale pixel up so its illumination matches meanIllum.
                    // Clamp lift to avoid over-brightening very dark pixels.
                    val liftFactor = if (il > 0.01f) min(meanIllum / il, 2.5f) else 1f
                    val blended = 1f + (liftFactor - 1f) * shadow
                    outR[i] = (r[i] * blended).coerceIn(0f, 1f)
                    outG[i] = (g[i] * blended).coerceIn(0f, 1f)
                    outB[i] = (b[i] * blended).coerceIn(0f, 1f)
                } else {
                    outR[i] = r[i]; outG[i] = g[i]; outB[i] = b[i]
                }
            }

            val outPixels = IntArray(w * h) { i ->
                val ri = (outR[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (outG[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (outB[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(outPixels, 0, w, 0, 0, w, h)
            Log.i(TAG, "removeShadows: done (${w}x${h} radius=$radius meanIllum=${"%.3f".format(meanIllum)})")
            result
        }.getOrElse { e ->
            Log.e(TAG, "removeShadows failed: ${e.message}", e)
            null
        }
    }

    fun release() {}

    companion object {
        private const val TAG = "ShadowRemoval"

        fun gaussianBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
            val sigma = radius / 3f
            val kSize = radius * 2 + 1
            val kernel = FloatArray(kSize) { i ->
                val x = (i - radius).toFloat()
                exp(-x * x / (2f * sigma * sigma))
            }
            val kSum = kernel.sum()
            for (i in kernel.indices) kernel[i] /= kSum

            // Horizontal pass
            val tmp = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var acc = 0f
                    for (k in 0 until kSize) {
                        val sx = (x + k - radius).coerceIn(0, w - 1)
                        acc += src[y * w + sx] * kernel[k]
                    }
                    tmp[y * w + x] = acc
                }
            }
            // Vertical pass
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var acc = 0f
                    for (k in 0 until kSize) {
                        val sy = (y + k - radius).coerceIn(0, h - 1)
                        acc += tmp[sy * w + x] * kernel[k]
                    }
                    out[y * w + x] = acc
                }
            }
            return out
        }
    }
}
