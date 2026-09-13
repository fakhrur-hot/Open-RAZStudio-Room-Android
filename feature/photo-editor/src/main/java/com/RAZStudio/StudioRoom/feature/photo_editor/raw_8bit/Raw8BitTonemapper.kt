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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.io.Raw16ULoader
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.io.U2NetMaskAdapter
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.operations.ZeroDcePreExposure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * End-to-end "RAW file → 8-bit ARGB Bitmap" tonemap orchestrator.
 *
 *   1. [Raw16ULoader] — LibRaw V2 → CV_16UC3 linear-sRGB BGR.
 *   2. sRGB-style gamma encode so the downstream stages see a
 *      perceptually-uniform surface (matches Zero-DCE's training
 *      distribution).
 *   3. [ZeroDcePreExposure] — Zero-DCE global exposure curve on the
 *      gamma-encoded data. Lifts shadows away from 0 and gently
 *      compresses highlights so segmented CLAHE doesn't amplify
 *      shadow noise.
 *   4. [U2NetMaskAdapter] — CV_32FC1 mask at source dimensions.
 *   5. [SegmentedClahePipeline] — segmented local CLAHE + chroma boost.
 *   6. Wrap the CV_8UC3 BGR result as ARGB_8888 Bitmap.
 *
 * Single-shot: instantiate per RAW file, call [tonemap], let
 * AutoCloseable release the OrtSession. Re-using across many files
 * is a future Phase 4 concern (where the editor lifecycle owns the
 * processor across edits).
 */
internal class Raw8BitTonemapper(context: Context) : AutoCloseable {

    private val maskAdapter = U2NetMaskAdapter(context)
    private val zeroDce = ZeroDcePreExposure(context)
    private val pipeline = SegmentedClahePipeline()

    suspend fun tonemap(
        rawFilePath: String,
        halfSize: Boolean = false,
        // -1 = RAZ_AMAZE / RCD — matches the 16-bit workspace default.
        // Higher quality than AHD (3) at similar cost on full-res RAWs.
        userQual: Int = -1,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val decoded = Raw16ULoader.decode(rawFilePath, halfSize = halfSize, userQual = userQual)
            ?: return@withContext null.also { Log.e(TAG, "tonemap: LibRaw decode returned null") }
        val tDecode = System.currentTimeMillis()
        Log.i(TAG, "tonemap: decoded ${decoded.width}x${decoded.height} in ${tDecode - t0}ms")

        var bgr16u: Mat? = decoded.bgr
        var bgr16uGamma: Mat? = null
        var bgr16uExposed: Mat? = null
        var mask: Mat? = null
        var preview: Bitmap? = null
        var output8u: Mat? = null
        var argb: Mat? = null
        var outBitmap: Bitmap? = null

        try {
            // Apply the proper IEC 61966-2-1 sRGB transfer function.
            // A pure pow(1/2.2) crushes near-black values flat (e.g.
            // a linear value of 0.001 → 0.067 in pow, but only 0.013
            // in true sRGB, *and* the true sRGB toe lifts deeper
            // shadows by 2-4×). That toe is exactly the shadow detail
            // we want to recover.
            bgr16uGamma = encodeSrgb(bgr16u!!)
            val tGamma = System.currentTimeMillis()
            Log.i(TAG, "tonemap: sRGB-encoded in ${tGamma - tDecode}ms")

            // Zero-DCE pre-exposure on the gamma-encoded buffer.
            // Lifts shadows and gently compresses highlights so the
            // downstream CLAHE pass doesn't amplify sensor noise in
            // deep shadows.
            bgr16uExposed = zeroDce.apply(bgr16uGamma)
            val tZeroDce = System.currentTimeMillis()
            Log.i(TAG, "tonemap: Zero-DCE pre-exposure in ${tZeroDce - tGamma}ms")

            // Build the small preview that feeds U2Net. Sourced from
            // the post-Zero-DCE buffer so the subject mask aligns with
            // the same exposure CLAHE will operate on.
            preview = maskAdapter.buildPreviewBitmap(bgr16uExposed, maxLongSide = 512)
            val tPreview = System.currentTimeMillis()
            Log.i(TAG, "tonemap: built preview ${preview.width}x${preview.height} in ${tPreview - tZeroDce}ms")

            mask = maskAdapter.computeMask(
                preview = preview,
                targetWidth = decoded.width,
                targetHeight = decoded.height,
            ) ?: return@withContext null.also {
                Log.e(TAG, "tonemap: U2Net mask computation failed")
            }
            val tMask = System.currentTimeMillis()
            Log.i(TAG, "tonemap: U2Net mask in ${tMask - tPreview}ms")

            output8u = pipeline.execute(srcBgr = bgr16uExposed, u2NetMask = mask)
            val tClahe = System.currentTimeMillis()
            Log.i(TAG, "tonemap: SegmentedClahePipeline in ${tClahe - tMask}ms")

            // Convert BGR → RGBA for Bitmap.
            argb = Mat()
            Imgproc.cvtColor(output8u, argb, Imgproc.COLOR_BGR2RGBA)
            outBitmap = Bitmap.createBitmap(
                decoded.width, decoded.height, Bitmap.Config.ARGB_8888,
            )
            Utils.matToBitmap(argb, outBitmap)
            val tBitmap = System.currentTimeMillis()
            Log.i(TAG, "tonemap: total ${tBitmap - t0}ms")
            outBitmap
        } finally {
            bgr16u?.release()
            bgr16uGamma?.release()
            bgr16uExposed?.release()
            mask?.release()
            output8u?.release()
            argb?.release()
            preview?.recycle()
            // outBitmap is the return value — do NOT recycle it here.
        }
    }

    override fun close() {
        maskAdapter.close()
        zeroDce.close()
    }

    /**
     * Apply the IEC 61966-2-1 sRGB OETF (linear → gamma encode) to a
     * 3-channel uint16 Mat. The transfer is piecewise:
     *
     *   out = 12.92 · x                  for x ≤ 0.0031308   (toe)
     *       = 1.055 · x^(1/2.4) − 0.055  otherwise            (curve)
     *
     * The toe is the part that matters for shadow recovery — a pure
     * `pow(x, 1/2.2)` would crush near-black pixels much harder.
     *
     * Implementation runs in float32 because OpenCV's element-wise
     * functions don't operate on integer types. Both branches are
     * computed over the full image then mask-blended — the mask is
     * cheap (one compare) and the extra `pow` on the toe pixels is
     * wasted work but is dominated by the convert/IO cost.
     */
    private fun encodeSrgb(src: Mat): Mat {
        val srcF = Mat()
        src.convertTo(srcF, CvType.CV_32FC3, 1.0 / 65535.0)

        // Branch A — toe: out = 12.92 · x
        val toe = Mat()
        Core.multiply(srcF, org.opencv.core.Scalar(12.92, 12.92, 12.92), toe)

        // Branch B — curve: out = 1.055 · x^(1/2.4) − 0.055
        val powed = Mat()
        Core.pow(srcF, 1.0 / 2.4, powed)
        val curve = Mat()
        Core.multiply(
            powed, org.opencv.core.Scalar(1.055, 1.055, 1.055), curve,
        )
        Core.subtract(
            curve, org.opencv.core.Scalar(0.055, 0.055, 0.055), curve,
        )
        powed.release()

        // Mask: 1.0 where x > 0.0031308, else 0.0 (per channel).
        // Use compare in float space — Core.compare with single-channel
        // operands isn't available, so we do it via threshold per
        // channel. Faster: split → threshold each plane → merge.
        val planes = ArrayList<Mat>(3)
        Core.split(srcF, planes)
        val maskPlanes = ArrayList<Mat>(3)
        for (p in planes) {
            val m = Mat()
            // threshold: out = 1.0 if p > 0.0031308 else 0.0
            Imgproc.threshold(
                p, m, 0.0031308, 1.0, Imgproc.THRESH_BINARY,
            )
            maskPlanes.add(m)
        }
        val mask = Mat()
        Core.merge(maskPlanes, mask)
        planes.forEach { it.release() }
        maskPlanes.forEach { it.release() }

        // out = mask · curve + (1 − mask) · toe
        val ones = Mat(mask.rows(), mask.cols(), mask.type(),
            org.opencv.core.Scalar(1.0, 1.0, 1.0))
        val invMask = Mat()
        Core.subtract(ones, mask, invMask)
        ones.release()
        val termCurve = Mat()
        Core.multiply(mask, curve, termCurve)
        val termToe = Mat()
        Core.multiply(invMask, toe, termToe)
        val outF = Mat()
        Core.add(termCurve, termToe, outF)

        val out16 = Mat()
        outF.convertTo(out16, CvType.CV_16UC3, 65535.0)

        srcF.release()
        toe.release()
        curve.release()
        mask.release()
        invMask.release()
        termCurve.release()
        termToe.release()
        outF.release()
        return out16
    }

    private companion object {
        private const val TAG = "Raw8Bit.Tonemapper"
    }
}
