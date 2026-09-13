/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.io.U2NetMaskAdapter
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.segmentation.OfflineFusionSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

/**
 * Local Adjustments integration with Offline Fusion (U2Net + SAM).
 *
 * Purpose: Provide sharp segmentation masks for regional adjustments:
 * - Vignette boundaries
 * - Clarity/texture enhancement
 * - Shadow/highlight adjustments
 * - Temperature/tint per region
 *
 * Result: All regional filters have crisp boundaries, no bleeding.
 */
internal class LocalAdjustmentFusionProcessor(
    private val context: Context,
) : AutoCloseable {

    private val fusionSegmenter = OfflineFusionSegmenter(context)
    private val adapter = U2NetMaskAdapter(context)

    // Cache masks for performance (reuse across multiple adjustments)
    private var cachedFusionMask: Mat? = null
    private var cachedBitmapKey: String = ""

    /**
     * Detect regional mask for local adjustments using offline fusion.
     *
     * Masks are cached - subsequent calls with same bitmap hash reuse the mask.
     *
     * @param preview Preview bitmap (8-bit)
     * @param targetWidth Target width (source image width)
     * @param targetHeight Target height (source image height)
     * @param useCache If true, reuse cached mask; if false, recompute
     * @return Mat mask [0,1] or null on failure (falls back to generic)
     */
    suspend fun detectRegionalMask(
        preview: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        useCache: Boolean = true,
    ): Mat? = withContext(Dispatchers.Default) {
        try {
            val startTime = System.currentTimeMillis()

            // Check cache
            val bitmapKey = "${preview.hashCode()}_${targetWidth}x${targetHeight}"
            if (useCache && cachedFusionMask != null && bitmapKey == cachedBitmapKey) {
                Log.i(TAG, "Using cached fusion mask")
                return@withContext cachedFusionMask?.clone()
            }

            // Compute fresh mask
            val fusionMask = fusionSegmenter.detectBlemishMask(
                preview = preview,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            ) ?: return@withContext null.also {
                Log.w(TAG, "Fusion mask computation failed, falling back to generic")
            }

            // Cache for next adjustment
            cachedFusionMask?.release()
            cachedFusionMask = fusionMask.clone()
            cachedBitmapKey = bitmapKey

            val time = System.currentTimeMillis() - startTime
            Log.i(TAG, "Regional mask computed in ${time}ms (cached for reuse)")

            fusionMask
        } catch (e: Exception) {
            Log.e(TAG, "detectRegionalMask failed: ${e.message}", e)
            null
        }
    }

    /**
     * Convert fusion Mat mask to RawSegmentationMasks format for compatibility.
     *
     * Extracts subject mask from fusion output.
     */
    suspend fun convertToSegmentationMasks(
        fusionMat: Mat,
        width: Int,
        height: Int,
    ): RawSegmentationMasks? = withContext(Dispatchers.Default) {
        try {
            // Convert Mat [0,1] to FloatArray
            val float32 = FloatArray(fusionMat.cols() * fusionMat.rows())
            fusionMat.get(0, 0, float32)

            // Create RawSegmentationMasks wrapper
            // (Simplified - just uses subject mask from fusion)
            RawSegmentationMasks(
                subjectMask = float32,
                edgeMask = FloatArray(float32.size)  // Empty edge mask (can be computed if needed)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed: ${e.message}", e)
            null
        }
    }

    /**
     * Clear cached mask to force recomputation on next request.
     * Call this when switching to a new image.
     */
    fun clearCache() {
        cachedFusionMask?.release()
        cachedFusionMask = null
        cachedBitmapKey = ""
        Log.d(TAG, "Mask cache cleared")
    }

    override fun close() {
        clearCache()
        adapter.close()
        fusionSegmenter.close()
    }

    private companion object {
        private const val TAG = "LocalAdjustmentFusion"
    }
}
