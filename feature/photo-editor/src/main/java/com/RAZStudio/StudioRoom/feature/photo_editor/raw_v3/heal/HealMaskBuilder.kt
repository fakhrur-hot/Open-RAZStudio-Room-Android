/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.heal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3SegmentationMasks
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Builds the touch-to-heal mask used by [com.RAZStudio.opencv_tools.spot_heal.SpotHealer].
 *
 * The pattern (from the workflow plan):
 *
 *   [User Touch] → [Generate circular touch mask]
 *                ↓
 *                ∩ (PorterDuff.SRC_IN equivalent)
 *                ↓
 *   [Same-class region from U2Net subject mask, sampled at the tap]
 *                ↓
 *   [Strict-binary 0/255 mask handed to Photo.inpaint]
 *
 * This keeps inpainting from sampling across semantic boundaries — a
 * tap on a face won't pull background pixels onto the skin, and a tap
 * on the sky won't reach into a tree silhouette.
 *
 * Behaviour notes (matches user-confirmed decisions):
 *   • Strict binary mask. No feathering — Photo.inpaint TELEA/NS
 *     require 0/255 input.
 *   • Tap exactly on subject/background edge: > 0.5 → subject
 *     (the workflow default).
 *   • If [masks] is null (U2Net not ready yet, or unavailable), the
 *     mask is just the touch circle — caller's existing legacy path.
 */
object HealMaskBuilder {

    /**
     * @param srcW source bitmap width
     * @param srcH source bitmap height
     * @param tapXSrc tap X in source-bitmap coords
     * @param tapYSrc tap Y in source-bitmap coords
     * @param radiusSrcPx touch radius in source-bitmap pixels
     * @param masks U2Net segmentation masks (may be null)
     * @param subjectThreshold confidence cutoff for subject/background
     *        decision (subjectMask sample > threshold → subject region)
     * @param dilatePx morphological dilate so the class boundary
     *        doesn't bite into the touch disc by a few pixels
     * @param fusionMask Optional: Pre-computed fusion mask (U2Net→SAM) at full resolution.
     *        If provided, bypasses U2Net processing and uses this directly.
     *        Should be CV_32FC1 [0,1] normalized.
     */
    fun build(
        srcW: Int,
        srcH: Int,
        tapXSrc: Float,
        tapYSrc: Float,
        radiusSrcPx: Float,
        masks: RawV3SegmentationMasks?,
        subjectThreshold: Float = 0.5f,
        dilatePx: Int = 6,
        fusionMask: Mat? = null,
    ): Bitmap {
        // Ensure libopencv_java4.so is loaded before allocating any Mat.
        // Heal is the first OpenCV consumer that doesn't go through the
        // OpenCV base class (which loads in its init block), so without
        // this call the JNI binding throws UnsatisfiedLinkError on
        // n_Mat(). initLocal() is idempotent and cheap on subsequent
        // calls.
        OpenCVLoader.initLocal()

        // ── 1. Touch circle on a black bitmap ──────────────────────
        val touchBmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        Canvas(touchBmp).apply {
            drawColor(Color.BLACK)
            drawCircle(
                tapXSrc, tapYSrc,
                radiusSrcPx.coerceAtLeast(2f),
                Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                    isAntiAlias = false
                },
            )
        }

        // ── Fusion path: use pre-computed U2Net→SAM fusion mask ──
        if (fusionMask != null) {
            Log.d(TAG, "build: using fusion mask (U2Net→SAM offline)")
            return buildWithFusionMask(srcW, srcH, tapXSrc, tapYSrc, radiusSrcPx,
                                       touchBmp, fusionMask, dilatePx)
        }

        // No segmentation available → return the touch circle as-is.
        // Caller's SpotHealer will threshold to binary itself.
        if (masks == null) {
            Log.d(TAG, "build: no masks, returning touch-only")
            return touchBmp
        }

        // ── 2. Sample U2Net to decide which class the user tapped ──
        val sample = sampleSubject(masks, srcW, srcH, tapXSrc, tapYSrc)
        val tappedIsSubject = sample > subjectThreshold
        Log.d(TAG, "build: tap=($tapXSrc,$tapYSrc) sample=$sample → ${if (tappedIsSubject) "SUBJECT" else "BG"}")

        // ── 3. Build a 320×320 binary class mask matching the tap ──
        val mask320 = ByteArray(RawV3SegmentationMasks.MASK_SIZE * RawV3SegmentationMasks.MASK_SIZE)
        val sm = masks.subjectMask
        for (i in sm.indices) {
            val isSubject = sm[i] > subjectThreshold
            mask320[i] = if (isSubject == tappedIsSubject) 255.toByte() else 0
        }
        val classMat320 = Mat(
            RawV3SegmentationMasks.MASK_SIZE,
            RawV3SegmentationMasks.MASK_SIZE,
            CvType.CV_8UC1,
        )
        classMat320.put(0, 0, mask320)

        // ── 4. Resize to source dims + re-threshold ────────────────
        val classMatFull = Mat()
        Imgproc.resize(
            classMat320, classMatFull,
            Size(srcW.toDouble(), srcH.toDouble()),
            0.0, 0.0,
            Imgproc.INTER_LINEAR,
        )
        Imgproc.threshold(classMatFull, classMatFull, 127.0, 255.0, Imgproc.THRESH_BINARY)

        // ── 5. Slight dilate so the boundary doesn't pinch the disc
        if (dilatePx > 0) {
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size((dilatePx * 2 + 1).toDouble(), (dilatePx * 2 + 1).toDouble()),
            )
            Imgproc.dilate(classMatFull, classMatFull, kernel)
            kernel.release()
        }

        // ── 6. AND the touch circle with the class mask ────────────
        val touchMat = Mat()
        Utils.bitmapToMat(touchBmp, touchMat)
        when (touchMat.channels()) {
            4 -> Imgproc.cvtColor(touchMat, touchMat, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(touchMat, touchMat, Imgproc.COLOR_RGB2GRAY)
            else -> { /* 1ch already */ }
        }
        Imgproc.threshold(touchMat, touchMat, 127.0, 255.0, Imgproc.THRESH_BINARY)
        val finalMat = Mat()
        Core.bitwise_and(touchMat, classMatFull, finalMat)

        // ── 7. Safety fallback: if AND wiped the mask (tap landed in
        //      a hairline boundary where the class mask flipped sides
        //      under resize), fall back to the touch-only mask so the
        //      heal still does something visible.
        val nz = Core.countNonZero(finalMat)
        if (nz == 0) {
            Log.w(TAG, "build: AND wiped mask — falling back to touch-only")
            classMat320.release(); classMatFull.release()
            touchMat.release(); finalMat.release()
            return touchBmp
        }

        // ── 8. Mat → ARGB_8888 Bitmap ──────────────────────────────
        val out = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        Imgproc.cvtColor(finalMat, rgba, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(rgba, out)

        // ── Cleanup ────────────────────────────────────────────────
        classMat320.release()
        classMatFull.release()
        touchMat.release()
        finalMat.release()
        rgba.release()
        touchBmp.recycle()

        return out
    }

    /**
     * Sample the U2Net subjectMask at a point in source-bitmap coords.
     * Honours the inner-rect remapping the masks carry so we don't
     * read outside the valid region for non-square images.
     *
     * @return confidence in [0, 1]; 0 if the tap falls outside the
     *         inner rect (treat as background).
     */
    fun sampleSubject(
        masks: RawV3SegmentationMasks,
        srcW: Int,
        srcH: Int,
        xSrc: Float,
        ySrc: Float,
    ): Float {
        if (srcW <= 0 || srcH <= 0) return 0f
        val u = (xSrc / srcW).coerceIn(0f, 1f)
        val v = (ySrc / srcH).coerceIn(0f, 1f)
        val l = masks.innerRectLeft
        val r = masks.innerRectRight
        val t = masks.innerRectTop
        val b = masks.innerRectBottom
        if (u < l || u > r || v < t || v > b) return 0f
        val rectW = (r - l).coerceAtLeast(1e-6f)
        val rectH = (b - t).coerceAtLeast(1e-6f)
        val uIn = (u - l) / rectW
        val vIn = (v - t) / rectH
        val size = RawV3SegmentationMasks.MASK_SIZE
        val mx = (uIn * (size - 1)).toInt().coerceIn(0, size - 1)
        val my = (vIn * (size - 1)).toInt().coerceIn(0, size - 1)
        return masks.subjectMask[my * size + mx].coerceIn(0f, 1f)
    }

    /**
     * Build mask using pre-computed fusion segmentation (U2Net→SAM).
     *
     * Fusion provides:
     * - Sharp edges from SAM
     * - Fine structure from U2Net
     * - No need for semantic class boundary detection
     *
     * Simply: AND the touch circle with the fusion mask
     */
    private fun buildWithFusionMask(
        srcW: Int,
        srcH: Int,
        tapXSrc: Float,
        tapYSrc: Float,
        radiusSrcPx: Float,
        touchBmp: Bitmap,
        fusionMask: Mat,
        dilatePx: Int,
    ): Bitmap {
        // ── 1. Convert touch bitmap to Mat ──────────────────────
        val touchMat = Mat()
        Utils.bitmapToMat(touchBmp, touchMat)
        when (touchMat.channels()) {
            4 -> Imgproc.cvtColor(touchMat, touchMat, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(touchMat, touchMat, Imgproc.COLOR_RGB2GRAY)
            else -> { /* 1ch already */ }
        }
        Imgproc.threshold(touchMat, touchMat, 127.0, 255.0, Imgproc.THRESH_BINARY)

        // ── 2. Threshold fusion mask to binary ─────────────────
        val binaryFusion = Mat()
        Imgproc.threshold(fusionMask, binaryFusion, 0.5, 255.0, Imgproc.THRESH_BINARY)

        // ── 3. Optional dilate so boundary doesn't pinch disc ──
        if (dilatePx > 0) {
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size((dilatePx * 2 + 1).toDouble(), (dilatePx * 2 + 1).toDouble()),
            )
            Imgproc.dilate(binaryFusion, binaryFusion, kernel)
            kernel.release()
        }

        // ── 3b. Make the fusion mask match the touch circle (size + type) ──
        // The segmenter can return its mask at a different resolution and/or as a
        // float / multi-channel Mat, while the touch circle is srcW×srcH CV_8UC1.
        // bitwise_and needs identical size AND type or it throws arithm.cpp:214
        // "Sizes of input arguments do not match" — which was failing EVERY heal
        // on the fusion path. Normalise binaryFusion before the AND.
        when (binaryFusion.channels()) {
            4 -> Imgproc.cvtColor(binaryFusion, binaryFusion, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(binaryFusion, binaryFusion, Imgproc.COLOR_RGB2GRAY)
        }
        if (binaryFusion.type() != org.opencv.core.CvType.CV_8UC1) {
            binaryFusion.convertTo(binaryFusion, org.opencv.core.CvType.CV_8UC1)
        }
        if (binaryFusion.rows() != touchMat.rows() || binaryFusion.cols() != touchMat.cols()) {
            Imgproc.resize(binaryFusion, binaryFusion, touchMat.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        }

        // ── 4. AND: touch circle ∩ fusion segmentation ────────
        val finalMat = Mat()
        Core.bitwise_and(touchMat, binaryFusion, finalMat)

        // ── 5. Safety fallback: if AND wiped the mask ─────────
        val nz = Core.countNonZero(finalMat)
        if (nz == 0) {
            Log.w(TAG, "buildWithFusionMask: AND wiped mask, fallback to touch-only")
            touchMat.release()
            binaryFusion.release()
            finalMat.release()
            return touchBmp
        }

        // ── 6. Mat → ARGB_8888 Bitmap ───────────────────────
        val out = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        Imgproc.cvtColor(finalMat, rgba, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(rgba, out)

        // ── Cleanup ────────────────────────────────────────────
        touchMat.release()
        binaryFusion.release()
        finalMat.release()
        rgba.release()
        touchBmp.recycle()

        return out
    }

    private const val TAG = "HealMaskBuilder"
}
