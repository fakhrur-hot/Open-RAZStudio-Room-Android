/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.SelfieMulticlassSegmenter

/**
 * Per-class soft probability masks produced by [SelfieMulticlassSegmenter].
 * Each mask is a [FloatArray] of size 320×320 (row-major), with values in
 * [0, 1] representing softmax probabilities — NOT binary 0/1 argmax indices.
 * Soft values at class boundaries carry genuine model uncertainty, which
 * [RawEditorContent.fillFromSegmentation] can exploit for sharper, more
 * natural edges via bilinear upsampling + guided-filter edge-snap.
 *
 * Classes:
 *   • Hair          (`SegmentationClass.Hair`,         channel index 1)
 *   • BodySkin      (`SegmentationClass.BodySkin`,     channel index 2)
 *   • FaceSkin      (`SegmentationClass.FaceSkin`,     channel index 3)
 *   • Clothes       (`SegmentationClass.Clothes`,      channel index 4)
 *   • Accessories   (`SegmentationClass.Accessories`,  channel index 5)
 *
 * Background (index 0) is intentionally omitted — the existing
 * `RawV3SegmentationMasks.backgroundMask` covers that case via
 * U2Net, which on portraits gives a more contiguous result.
 */
data class RawV3MulticlassMasks(
    val hair: FloatArray,
    val bodySkin: FloatArray,
    val faceSkin: FloatArray,
    val clothes: FloatArray,
    val accessories: FloatArray,
) {
    fun forClass(cls: SelfieMulticlassSegmenter.SegmentationClass): FloatArray? = when (cls) {
        SelfieMulticlassSegmenter.SegmentationClass.Hair          -> hair
        SelfieMulticlassSegmenter.SegmentationClass.BodySkin      -> bodySkin
        SelfieMulticlassSegmenter.SegmentationClass.FaceSkin      -> faceSkin
        SelfieMulticlassSegmenter.SegmentationClass.Clothes       -> clothes
        SelfieMulticlassSegmenter.SegmentationClass.Accessories   -> accessories
        SelfieMulticlassSegmenter.SegmentationClass.Background    -> null
    }

    companion object {
        const val SIZE = 320

        /**
         * Convert per-class soft probability planes from [SelfieMulticlassSegmenter]
         * into five 320×320 [FloatArray]s using bilinear upsampling.
         *
         * [perClass] is indexed as `perClass[classIndex][pixelIndex]` with values
         * in [0, 1].  Background (index 0) is dropped; Hair..Accessories (1..5)
         * are bilinearly resampled from [srcSize]×[srcSize] to [SIZE]×[SIZE].
         * Soft values are preserved — no binary thresholding — so downstream
         * edge-snap and guided-filter passes operate on continuous probabilities.
         */
        fun fromSoftProbs(
            perClass: Array<FloatArray>,
            srcSize: Int,
        ): RawV3MulticlassMasks {
            // perClass indices: 0=Background, 1=Hair, 2=BodySkin, 3=FaceSkin,
            //                   4=Clothes, 5=Accessories
            val classIndices = intArrayOf(1, 2, 3, 4, 5)
            val out = Array(5) { outIdx ->
                val src = perClass[classIndices[outIdx]]
                bilinearUpsample(src, srcSize, srcSize, SIZE, SIZE)
            }
            return RawV3MulticlassMasks(
                hair        = out[0],
                bodySkin    = out[1],
                faceSkin    = out[2],
                clothes     = out[3],
                accessories = out[4],
            )
        }

        // Bilinear upsample from [srcW×srcH] to [dstW×dstH].
        private fun bilinearUpsample(
            src: FloatArray,
            srcW: Int, srcH: Int,
            dstW: Int, dstH: Int,
        ): FloatArray {
            val dst = FloatArray(dstW * dstH)
            val scaleX = (srcW - 1).toFloat() / (dstW - 1).coerceAtLeast(1)
            val scaleY = (srcH - 1).toFloat() / (dstH - 1).coerceAtLeast(1)
            for (dy in 0 until dstH) {
                val fy = dy * scaleY
                val sy0 = fy.toInt().coerceIn(0, srcH - 2)
                val sy1 = sy0 + 1
                val wy  = fy - sy0
                val tRow = dy * dstW
                for (dx in 0 until dstW) {
                    val fx  = dx * scaleX
                    val sx0 = fx.toInt().coerceIn(0, srcW - 2)
                    val sx1 = sx0 + 1
                    val wx  = fx - sx0
                    val v00 = src[sy0 * srcW + sx0]
                    val v10 = src[sy0 * srcW + sx1]
                    val v01 = src[sy1 * srcW + sx0]
                    val v11 = src[sy1 * srcW + sx1]
                    dst[tRow + dx] =
                        v00 * (1f - wy) * (1f - wx) +
                        v10 * (1f - wy) * wx +
                        v01 * wy        * (1f - wx) +
                        v11 * wy        * wx
                }
            }
            return dst
        }
    }
}
