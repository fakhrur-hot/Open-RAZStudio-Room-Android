/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.scene

import android.graphics.Bitmap

/**
 * Lightweight image statistics derived from a downscaled bitmap.
 * All values are on a 0–255 scale to match the heuristic thresholds
 * in [SceneDetector].
 *
 * @param avgLuma       Mean perceived brightness (BT.601 luma), 0–255.
 * @param avgChroma     Mean saturation (max-min of R,G,B per pixel), 0–255.
 * @param colorTempKelvin Rough colour-temperature estimate from the R/B ratio.
 *                        Values > 5500 K imply warm/golden light; < 4000 K
 *                        imply cool/blue light.
 * @param lumaRange     90th-percentile luma minus 10th-percentile luma, 0–255.
 *                      High values mean a wide dynamic range; low values mean
 *                      the image is flat or very uniformly exposed.
 */
data class ImageMetrics(
    val avgLuma:          Float,
    val avgChroma:        Float,
    val colorTempKelvin:  Int,
    val lumaRange:        Float,
    val wbRMin: Float = 0f,
    val wbRMax: Float = 1f,
    val wbGMin: Float = 0f,
    val wbGMax: Float = 1f,
    val wbBMin: Float = 0f,
    val wbBMax: Float = 1f,
) {
    companion object {
        /**
         * Compute [ImageMetrics] from [bitmap] by sampling a tiny 64×64
         * downscale.  Safe to call on any thread; allocates one small
         * intermediate bitmap (recycled before returning).
         */
        fun compute(bitmap: Bitmap): ImageMetrics {
            val side = 64
            val small = Bitmap.createScaledBitmap(bitmap, side, side, true)
            val n = side * side
            val pixels = IntArray(n)
            small.getPixels(pixels, 0, side, 0, 0, side, side)
            if (small !== bitmap) small.recycle()

            var sumLuma   = 0f
            var sumChroma = 0f
            var sumR      = 0f
            var sumB      = 0f
            val lumas     = FloatArray(n)

            var minR = 255f; var maxR = 0f
            var minG = 255f; var maxG = 0f
            var minB = 255f; var maxB = 0f

            for (i in pixels.indices) {
                val px = pixels[i]
                val r  = ((px shr 16) and 0xFF).toFloat()
                val g  = ((px shr 8)  and 0xFF).toFloat()
                val b  = (px          and 0xFF).toFloat()

                val luma   = 0.299f * r + 0.587f * g + 0.114f * b
                val maxRgb = maxOf(r, g, b)
                val minRgb = minOf(r, g, b)

                sumLuma   += luma
                sumChroma += maxRgb - minRgb
                sumR      += r
                sumB      += b
                lumas[i]   = luma

                if (r < minR) minR = r; if (r > maxR) maxR = r
                if (g < minG) minG = g; if (g > maxG) maxG = g
                if (b < minB) minB = b; if (b > maxB) maxB = b
            }

            lumas.sort()
            val p10 = lumas[(n * 0.10f).toInt()]
            val p90 = lumas[(n * 0.90f).toInt().coerceAtMost(n - 1)]

            // R/B ratio → rough Kelvin estimate.
            // Calibrated empirically: ratio 1.6 ≈ 6500 K, 1.0 ≈ 5500 K, 0.7 ≈ 3200 K.
            val rOverB   = if (sumB > 1f) sumR / sumB else 1.3f
            val kelvin   = (5500f / rOverB).toInt().coerceIn(2000, 10000)

            return ImageMetrics(
                avgLuma         = sumLuma   / n,
                avgChroma       = sumChroma / n,
                colorTempKelvin = kelvin,
                lumaRange       = p90 - p10,
                wbRMin = minR / 255f,
                wbRMax = maxR / 255f,
                wbGMin = minG / 255f,
                wbGMax = maxG / 255f,
                wbBMin = minB / 255f,
                wbBMax = maxB / 255f,
            )
        }
    }
}
