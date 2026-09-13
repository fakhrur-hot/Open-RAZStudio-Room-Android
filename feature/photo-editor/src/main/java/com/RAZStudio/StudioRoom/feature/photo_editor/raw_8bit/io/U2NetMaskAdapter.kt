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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.io

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3SegmentationMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3SegmentationProcessor
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Bridge between the existing v3 U2-Net wrapper
 * ([RawV3SegmentationProcessor]) and the CV_32FC1 Mat shape the 8-bit
 * pipeline consumes.
 *
 * Two responsibilities:
 *
 *   1. Turn a CV_16UC3 BGR source Mat into a Bitmap small enough to be
 *      cheap to inference (downsample to 512 px long side; U2-Net only
 *      needs 320×320 internally but a slightly larger sampling target
 *      gives the segmentation a better view of mid-tones).
 *   2. Run U2-Net, upscale the 320×320 saliency mask to source dimensions,
 *      then apply a guided filter using the preview image as guide to snap
 *      soft boundaries to actual image edges.
 *
 * The processor itself owns the OrtSession (~168 MB native heap on
 * first call). Caller is expected to construct the adapter once per
 * pipeline run and [close] it when done.
 */
internal class U2NetMaskAdapter(context: Context) : AutoCloseable {

    private val processor = RawV3SegmentationProcessor(context)

    /**
     * Produce a CV_32FC1 mask at `(targetWidth, targetHeight)` from
     * the given 8-bit BGR preview Bitmap. Returns null on inference
     * failure (no model / OrtSession allocation failed).
     *
     * After upscaling the 320x320 U2Net output, a guided filter pass
     * using the preview image as guide snaps soft mask boundaries to
     * actual luminance edges — eliminating the blocky halo that simple
     * bilinear upscaling produces on fine detail like feathers or hair.
     */
    suspend fun computeMask(
        preview: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): Mat? {
        val masks = processor.compute(preview)
            ?: return null.also { Log.e(TAG, "computeMask: U2Net returned null") }
        val upscaled = upscaleMask(masks, targetWidth, targetHeight)
        return applyGuidedFilter(upscaled, preview, targetWidth, targetHeight)
    }

    override fun close() = processor.release()

    private fun upscaleMask(
        masks: RawV3SegmentationMasks,
        targetWidth: Int,
        targetHeight: Int,
    ): Mat {
        val size = RawV3SegmentationMasks.MASK_SIZE
        val mask320 = Mat(size, size, CvType.CV_32FC1)
        mask320.put(0, 0, masks.subjectMask)
        val upscaled = Mat()
        Imgproc.resize(
            mask320,
            upscaled,
            Size(targetWidth.toDouble(), targetHeight.toDouble()),
            0.0, 0.0,
            Imgproc.INTER_LINEAR,
        )
        Core.min(upscaled, Scalar(1.0), upscaled)
        Core.max(upscaled, Scalar(0.0), upscaled)
        mask320.release()
        return upscaled
    }

    /**
     * Guided filter (He et al. 2013) using the preview image as guide.
     * Snaps the soft upscaled mask to real image edges without ximgproc.
     *
     * Radius r=16, epsilon=0.01: large enough to smooth noise inside flat
     * regions, small enough to preserve 1-2px fine edges.
     */
    private fun applyGuidedFilter(
        mask: Mat,
        preview: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): Mat {
        val scaledBmp = Bitmap.createScaledBitmap(preview, targetWidth, targetHeight, true)
        val tmp = Mat()
        Utils.bitmapToMat(scaledBmp, tmp)          // RGBA
        scaledBmp.recycle()
        val guide8 = Mat()
        Imgproc.cvtColor(tmp, guide8, Imgproc.COLOR_RGBA2GRAY)
        tmp.release()
        val guide = Mat()
        guide8.convertTo(guide, CvType.CV_32FC1, 1.0 / 255.0)
        guide8.release()

        val guided = guidedFilterMono(guide, mask, r = 16, eps = 0.01)
        guide.release()

        Core.min(guided, Scalar(1.0), guided)
        Core.max(guided, Scalar(0.0), guided)
        mask.release()
        return guided
    }

    /**
     * Single-channel guided filter. Both [guide] and [src] must be
     * CV_32FC1 of the same size. Returns a new CV_32FC1 Mat.
     *
     * Box-filter implementation of He et al. 2013, Section 3:
     *   mean_I  = box(I)
     *   mean_p  = box(p)
     *   var_I   = box(I*I) - mean_I^2
     *   cov_Ip  = box(I*p) - mean_I*mean_p
     *   a       = cov_Ip / (var_I + eps)
     *   b       = mean_p - a*mean_I
     *   q       = box(a)*I + box(b)
     */
    private fun guidedFilterMono(guide: Mat, src: Mat, r: Int, eps: Double): Mat {
        val kSize = Size((2 * r + 1).toDouble(), (2 * r + 1).toDouble())

        fun box(m: Mat): Mat {
            val out = Mat()
            Imgproc.boxFilter(m, out, CvType.CV_32FC1, kSize,
                Point(-1.0, -1.0), true, Core.BORDER_REFLECT_101)
            return out
        }

        val meanI = box(guide)
        val meanP = box(src)

        val ii = Mat(); Core.multiply(guide, guide, ii)
        val ip = Mat(); Core.multiply(guide, src, ip)
        val corrI  = box(ii);  ii.release()
        val corrIp = box(ip);  ip.release()

        val meanI2 = Mat(); Core.multiply(meanI, meanI, meanI2)
        val varI   = Mat(); Core.subtract(corrI, meanI2, varI)
        corrI.release(); meanI2.release()

        val meanIp = Mat(); Core.multiply(meanI, meanP, meanIp)
        val covIp  = Mat(); Core.subtract(corrIp, meanIp, covIp)
        corrIp.release(); meanIp.release()

        val denom = Mat(); Core.add(varI, Scalar(eps), denom); varI.release()
        val a = Mat(); Core.divide(covIp, denom, a); covIp.release(); denom.release()

        val aMeanI = Mat(); Core.multiply(a, meanI, aMeanI); meanI.release()
        val b = Mat(); Core.subtract(meanP, aMeanI, b); meanP.release(); aMeanI.release()

        val meanA = box(a); a.release()
        val meanB = box(b); b.release()

        val q1 = Mat(); Core.multiply(meanA, guide, q1); meanA.release()
        val q  = Mat(); Core.add(q1, meanB, q); q1.release(); meanB.release()

        return q
    }

    /**
     * Helper: convert a CV_16UC3 BGR source to an 8-bit ARGB Bitmap
     * downscaled so the long side is `maxLongSide` (default 512). This
     * is the Bitmap that gets fed to [computeMask].
     */
    fun buildPreviewBitmap(bgr16u: Mat, maxLongSide: Int = 512): Bitmap {
        val srcW = bgr16u.cols()
        val srcH = bgr16u.rows()
        val scale = maxLongSide.toDouble() / maxOf(srcW, srcH).toDouble()
        val targetW = (srcW * scale).toInt().coerceAtLeast(1)
        val targetH = (srcH * scale).toInt().coerceAtLeast(1)

        val resized16 = Mat()
        Imgproc.resize(
            bgr16u, resized16,
            Size(targetW.toDouble(), targetH.toDouble()),
            0.0, 0.0,
            Imgproc.INTER_AREA,
        )
        val resized8 = Mat()
        resized16.convertTo(resized8, CvType.CV_8UC3, 1.0 / 256.0)
        val rgba = Mat()
        Imgproc.cvtColor(resized8, rgba, Imgproc.COLOR_BGR2RGBA)

        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)

        resized16.release()
        resized8.release()
        rgba.release()
        return bmp
    }

    private companion object {
        private const val TAG = "Raw8Bit.MaskAdapter"
    }
}
