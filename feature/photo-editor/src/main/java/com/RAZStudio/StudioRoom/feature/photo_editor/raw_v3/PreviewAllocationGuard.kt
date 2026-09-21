package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.util.Log
import kotlin.math.roundToInt
import kotlin.math.sqrt

object PreviewAllocationGuard {
    const val DEFAULT_PREVIEW_PIXEL_BUDGET = 2_000_000L

    fun capDimensions(width: Int, height: Int, maxPixels: Long = DEFAULT_PREVIEW_PIXEL_BUDGET): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val total = width.toLong() * height.toLong()
        if (total <= maxPixels) return width to height

        val scale = sqrt(maxPixels.toDouble() / total.toDouble())
        val cappedW = (width * scale).roundToInt().coerceAtLeast(1)
        val cappedH = (height * scale).roundToInt().coerceAtLeast(1)

        val finalW = cappedW.coerceAtMost(width)
        val finalH = cappedH.coerceAtMost(height)
        return if (finalW.toLong() * finalH.toLong() <= maxPixels) {
            finalW to finalH
        } else {
            // Keep aspect ratio with a guaranteed safe cap.
            val maxByW = ((maxPixels / finalH.toLong()).coerceAtLeast(1L)).toInt()
            val safeW = finalW.coerceAtMost(maxByW)
            val safeH = ((maxPixels / safeW.toLong()).coerceAtLeast(1L)).toInt()
            safeW to safeH
        }
    }

    fun ensurePreviewBudget(width: Int, height: Int, maxPixels: Long = DEFAULT_PREVIEW_PIXEL_BUDGET): Int {
        val capped = capDimensions(width, height, maxPixels)
        if (capped.first * capped.second <= 0) {
            Log.w("PreviewAllocationGuard", "Invalid preview caps: ${capped.first}x${capped.second}")
            return 0
        }
        return capped.first * capped.second
    }
}
