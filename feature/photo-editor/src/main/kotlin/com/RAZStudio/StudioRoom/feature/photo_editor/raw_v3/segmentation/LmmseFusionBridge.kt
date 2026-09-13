/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.io.U2NetMaskAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

/**
 * LMMSE (Local Mean and Median-based Stochastic Enhancement) integration
 * with Offline Fusion (U2Net + SAM).
 *
 * Purpose: Enhance tone mapping with sharp foreground/background separation.
 *
 * Pipeline:
 * 1. Input image + parameters
 * 2. Offline Fusion detects sharp subject mask
 * 3. Convert to FloatArray for native code
 * 4. Native LMMSE applies tone mapping with fusion masks
 * 5. Output enhanced image
 *
 * Result: Tone mapping with crisp boundaries, no tonal bleeding.
 */
internal class LmmseFusionBridge(
    private val context: Context,
) : AutoCloseable {

    private val fusionSegmenter = OfflineFusionSegmenter(context)
    private val adapter = U2NetMaskAdapter(context)

    /**
     * Enhance image using LMMSE with offline fusion masks.
     *
     * @param inputBitmap Image to enhance
     * @param shadowBoost Boost shadows (range: -1.0 to 1.0)
     * @param highlightBoost Boost highlights (range: -1.0 to 1.0)
     * @param saturation Saturation adjustment (range: -1.0 to 1.0)
     * @return Enhanced bitmap
     */
    suspend fun enhanceWithFusion(
        inputBitmap: Bitmap,
        shadowBoost: Float,
        highlightBoost: Float,
        saturation: Float,
    ): Bitmap = withContext(Dispatchers.Default) {
        try {
            val startTime = System.currentTimeMillis()

            // Step 1: Build preview for fusion segmentation
            val mat = Mat()
            org.opencv.android.Utils.bitmapToMat(inputBitmap, mat)
            val preview = adapter.buildPreviewBitmap(
                mat,
                maxLongSide = 512
            )
            mat.release()

            // Step 2: Detect fusion mask (U2Net→SAM offline)
            val fusionMat = fusionSegmenter.detectBlemishMask(
                preview = preview,
                targetWidth = inputBitmap.width,
                targetHeight = inputBitmap.height
            ) ?: return@withContext inputBitmap.also {
                Log.w(TAG, "Fusion mask failed, returning unenhanced")
            }

            preview.recycle()

            // Step 3: Convert fusion Mat to FloatArray for native code
            val maskW = fusionMat.cols()
            val maskH = fusionMat.rows()
            val subjectMask = FloatArray(maskW * maskH)
            fusionMat.get(0, 0, subjectMask)
            fusionMat.release()

            // Step 4: Call native LMMSE with fusion mask. The native code
            // mutates pixels in-place, so it must run on a mutable ARGB_8888
            // copy — the source bitmap may be immutable / HARDWARE-backed.
            val workBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
                ?: return@withContext inputBitmap.also {
                    Log.w(TAG, "Could not create mutable copy, returning unenhanced")
                }
            val enhanced = enhanceLMMSENative(
                bitmap = workBitmap,
                subjectMask = subjectMask,
                maskWidth = maskW,
                maskHeight = maskH,
                shadowBoost = shadowBoost,
                highlightBoost = highlightBoost,
                saturation = saturation
            )

            val time = System.currentTimeMillis() - startTime
            Log.i(TAG, "LMMSE enhancement with fusion complete in ${time}ms")

            enhanced
        } catch (e: Exception) {
            Log.e(TAG, "Fusion enhancement failed: ${e.message}", e)
            inputBitmap  // Fallback
        }
    }

    override fun close() {
        adapter.close()
        fusionSegmenter.close()
    }

    companion object {
        private const val TAG = "LmmseFusion"

        /**
         * Load native JNI library containing LMMSE enhancement.
         * Call once at app startup.
         */
        init {
            try {
                System.loadLibrary("lmmse_enhance")
                Log.i(TAG, "LMMSE native library loaded")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load LMMSE library: ${e.message}")
            }
        }

        /**
         * Enhance using a PRE-COMPUTED subject mask, skipping the (expensive)
         * U2Net→SAM segmentation. Static so callers without a fusion instance
         * (e.g. [MacroProcessor]) can grade a bitmap they already hold a mask
         * for, without re-segmenting the frame or needing a Context.
         *
         * The native pass mutates pixels in-place and requires a mutable
         * ARGB_8888 bitmap, so this operates on a copy and returns it. On any
         * failure the original [inputBitmap] is returned unchanged.
         *
         * @param subjectMask [0,1] probabilities, row-major [maskWidth]×[maskHeight].
         *   The mask is bilinear-sampled to the bitmap resolution, so it may be any
         *   size. A length that doesn't match maskWidth*maskHeight makes the native
         *   side grade uniformly rather than crash.
         */
        @JvmStatic
        fun enhanceWithMask(
            inputBitmap: Bitmap,
            subjectMask: FloatArray,
            maskWidth: Int,
            maskHeight: Int,
            shadowBoost: Float,
            highlightBoost: Float,
            saturation: Float,
        ): Bitmap {
            if (shadowBoost == 0f && highlightBoost == 0f && saturation == 0f) return inputBitmap
            return try {
                val work = inputBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return inputBitmap
                enhanceLMMSENative(work, subjectMask, maskWidth, maskHeight, shadowBoost, highlightBoost, saturation)
            } catch (e: Throwable) {
                Log.e(TAG, "enhanceWithMask failed: ${e.message}", e)
                inputBitmap
            }
        }

        /**
         * Native JNI function for fusion tone-mapping. Mutates [bitmap] in-place
         * (must be mutable ARGB_8888) and returns it. [subjectMask] is a
         * row-major maskWidth×maskHeight probability grid, bilinear-sampled to
         * the image; shadow/highlight/saturation are each [-1,1].
         *
         * @JvmStatic so the JNI symbol stays on the enclosing class
         * (LmmseFusionBridge_enhanceLMMSENative), matching lmmse_fusion_jni.cpp.
         */
        @JvmStatic
        private external fun enhanceLMMSENative(
            bitmap: Bitmap,
            subjectMask: FloatArray,
            maskWidth: Int,
            maskHeight: Int,
            shadowBoost: Float,
            highlightBoost: Float,
            saturation: Float,
        ): Bitmap
    }
}
