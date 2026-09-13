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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.operations

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Combines the pre-multiplied foreground / background luma planes back
 * into a single luma channel, then boosts chroma vibrancy inside the
 * foreground region only and merges everything back into a final 8-bit
 * BGR Mat.
 *
 * Luma blend
 * ----------
 * The caller already multiplied each plane by its (or its inverse)
 * mask in [AdaptiveClaheOp]:
 *   Y_fore = M · Y_clahe_fg
 *   Y_back = (1 − M) · Y_clahe_bg
 * So the final luma is just `Y_fore + Y_back`.
 *
 * Chroma boost
 * ------------
 * Skin tones flatten under aggressive CLAHE because chroma stays at the
 * original (now relatively muted) level. We lerp the chroma channel
 * toward a saturation-boosted copy using the same float mask:
 *   C_final = M · (C · k) + (1 − M) · C
 * Doing this with `addWeighted` on float mats avoids the bit-pattern
 * banding `bitwise_and` produces on alpha edges.
 */
internal class AlphaBlender(
    private val fgChromaBoost: Double = 1.15,
    private val bgChromaBoost: Double = 1.0,
) {

    /** Sum the pre-multiplied luma planes. Returns a Mat in the same depth. */
    fun blend(yFore: Mat, yBack: Mat): Mat {
        val out = Mat()
        Core.add(yFore, yBack, out)
        return out
    }

    /**
     * Re-merge the blended luma + (foreground-boosted) chroma, then
     * convert YCrCb → BGR and downconvert to CV_8UC3 (the 8-bit working
     * buffer the editor expects).
     *
     * @param finalLuma         single-channel luma in source depth.
     * @param originalChroma    2-channel CrCb in source depth.
     * @param mask              CV_32FC1 in [0,1] — soft U2-Net mask.
     * @param shadowChromaRatio Optional CV_32FC1 per-pixel chroma-boost
     *                          factor, clamped to ≥1. When provided,
     *                          we multiply `Cr−neutral` and `Cb−neutral`
     *                          by this ratio before any fg-vibrancy
     *                          boost, then re-centre. This compensates
     *                          for the desaturation that a luma-only
     *                          shadow lift produces — without it, dark
     *                          pixels look colour-washed because their
     *                          chroma was tuned to the smaller original
     *                          luma. Pass `null` to skip.
     */
    fun reconstructAndBoost(
        finalLuma: Mat,
        originalChroma: Mat,
        mask: Mat,
        shadowChromaRatio: Mat? = null,
    ): Mat {
        // 0. Optional shadow chroma compensation: scale (chroma−neutral)
        //    by the per-pixel ratio map ShadowLiftOp produced. The
        //    neutral point is 128 for 8U YCrCb and 32768 for 16U YCrCb
        //    (mid-range of the unsigned domain).
        val chromaForBoost: Mat = if (shadowChromaRatio != null) {
            val neutral = if (originalChroma.depth() == CvType.CV_16U) 32768.0 else 128.0
            val chromaF32 = Mat()
            originalChroma.convertTo(chromaF32, CvType.CV_32FC2)
            // Centre to 0.
            Core.subtract(
                chromaF32, Scalar(neutral, neutral), chromaF32,
            )
            // Build a 2-channel ratio Mat so per-pixel multiply hits
            // both Cr and Cb with the same boost factor.
            val ratio2 = Mat()
            Core.merge(arrayListOf(shadowChromaRatio, shadowChromaRatio), ratio2)
            Core.multiply(chromaF32, ratio2, chromaF32)
            ratio2.release()
            // Re-centre.
            Core.add(
                chromaF32, Scalar(neutral, neutral), chromaF32,
            )
            // Clamp to representable range for the source depth so the
            // later convertTo doesn't wrap.
            val clampMax = if (originalChroma.depth() == CvType.CV_16U) 65535.0 else 255.0
            Core.max(chromaF32, Scalar(0.0, 0.0), chromaF32)
            Core.min(chromaF32, Scalar(clampMax, clampMax), chromaF32)
            val chromaBack = Mat()
            chromaF32.convertTo(chromaBack, originalChroma.type())
            chromaF32.release()
            chromaBack
        } else {
            originalChroma
        }

        // 1. Boost a copy of chroma uniformly (recentre-scale-recentre
        //    so the multiply scales chroma *magnitude* and not the raw
        //    unsigned encoded value — multiplying the encoded value
        //    directly produces a catastrophic cast on highlights, where
        //    a tiny neutral offset of e.g. +232 from a slight WB drift
        //    becomes a +4192 offset after a 1.12× raw multiply).
        //
        // Small global WB correction is also applied here, post-centre:
        // LibRaw's output_color=1 mode leaves a faint cool/blue cast on
        // Canon EOS 6D RAWs (Cb is biased ~0.8% above neutral). We
        // subtract a small constant from the centred Cb value so neutral
        // surfaces (wood, paper, walls) render as the warm grey/brown
        // they actually are. Cr is left unchanged because the bias is
        // blue-yellow, not red-green.
        val neutral = if (originalChroma.depth() == CvType.CV_16U) 32768.0 else 128.0
        val wbCbOffset = if (originalChroma.depth() == CvType.CV_16U) -650.0 else -2.5
        val chromaF = Mat()
        chromaForBoost.convertTo(chromaF, CvType.CV_32FC2)
        val centred = Mat()
        Core.subtract(chromaF, Scalar(neutral, neutral), centred)
        // Apply the Cb warm-shift on the centred chroma so the
        // subsequent fg-vibrancy multiply doesn't amplify it (the shift
        // gets divided back to its small magnitude when we re-add the
        // neutral).
        Core.add(centred, Scalar(0.0, wbCbOffset), centred)
        // Foreground branch: scale centred chroma by fgChromaBoost.
        val boostedF = Mat()
        Core.multiply(
            centred,
            Scalar(fgChromaBoost, fgChromaBoost),
            boostedF,
        )
        Core.add(boostedF, Scalar(neutral, neutral), boostedF)

        // Background branch: scale centred chroma by bgChromaBoost.
        // bgChromaBoost=1.0 reproduces the previous "background gets
        // raw chroma" behaviour; >1.0 lifts saturation on bokeh /
        // out-of-focus regions where the camera JPG has rich colour
        // but our segmented pipeline previously left untouched.
        val bgBoostedF = Mat()
        Core.multiply(
            centred,
            Scalar(bgChromaBoost, bgChromaBoost),
            bgBoostedF,
        )
        Core.add(bgBoostedF, Scalar(neutral, neutral), bgBoostedF)
        centred.release()

        // Build a 2-channel float mask matching chromaF's layout so the
        // per-channel lerp uses the same alpha per pixel.
        val mask2 = Mat()
        Core.merge(arrayListOf(mask, mask), mask2)
        val invMask2 = Mat(mask2.size(), mask2.type(), Scalar(1.0, 1.0))
        Core.subtract(invMask2, mask2, invMask2)

        // C_final = M · boostedF + (1 − M) · bgBoostedF
        val termFg = Mat()
        Core.multiply(boostedF, mask2, termFg)
        val termBg = Mat()
        Core.multiply(bgBoostedF, invMask2, termBg)
        val chromaFinalF = Mat()
        Core.add(termFg, termBg, chromaFinalF)
        val chromaFinal = Mat()
        chromaFinalF.convertTo(chromaFinal, originalChroma.type())

        // 2. Normalise luma to the chroma depth then merge. Conversion
        //    factor handles 16U→8U / 16U→16U / 8U→8U cleanly.
        val lumaForMerge = if (finalLuma.depth() == originalChroma.depth()) {
            val copy = Mat()
            finalLuma.copyTo(copy)
            copy
        } else {
            val copy = Mat()
            val targetDepth = originalChroma.depth()
            val scale = when {
                finalLuma.depth() == CvType.CV_16U && targetDepth == CvType.CV_8U -> 1.0 / 256.0
                finalLuma.depth() == CvType.CV_8U && targetDepth == CvType.CV_16U -> 256.0
                else -> 1.0
            }
            val targetType = CvType.makeType(targetDepth, 1)
            finalLuma.convertTo(copy, targetType, scale)
            copy
        }

        // 3. Re-split chromaFinal into Cr + Cb planes, merge {Y, Cr, Cb}.
        val chromaPlanes = ArrayList<Mat>(2)
        Core.split(chromaFinal, chromaPlanes)
        val yCrCbMerged = Mat()
        Core.merge(arrayListOf(lumaForMerge, chromaPlanes[0], chromaPlanes[1]), yCrCbMerged)

        // 4. Convert to BGR. If the merge type is 16U we step down to
        //    8U with the standard /256 scaling so the editor's
        //    Bitmap-backed working buffer gets the 8-bit output.
        val bgr16u = Mat()
        Imgproc.cvtColor(yCrCbMerged, bgr16u, Imgproc.COLOR_YCrCb2BGR)
        val out = Mat()
        if (bgr16u.depth() == CvType.CV_8U) {
            bgr16u.copyTo(out)
        } else {
            bgr16u.convertTo(out, CvType.CV_8UC3, 1.0 / 256.0)
        }

        // Cleanup
        chromaF.release()
        boostedF.release()
        bgBoostedF.release()
        mask2.release()
        invMask2.release()
        termFg.release()
        termBg.release()
        chromaFinalF.release()
        chromaFinal.release()
        lumaForMerge.release()
        chromaPlanes.forEach { it.release() }
        yCrCbMerged.release()
        // chromaForBoost may be a fresh Mat from the shadow-chroma
        // compensation path, or it may alias originalChroma. Only
        // release if we allocated it.
        if (chromaForBoost !== originalChroma) chromaForBoost.release()
        bgr16u.release()

        return out
    }
}
