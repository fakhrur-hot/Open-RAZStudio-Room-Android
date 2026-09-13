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

/**
 * Tone-shaping op for the luma channel. Does two related jobs:
 *
 *  1. **Shadow / dark-mid-tone lift** via a power curve `pow(x, gamma)`
 *     with `gamma < 1`. Restores detail crushed against black.
 *  2. **Highlight shoulder** that compresses values approaching white
 *     so brightest pixels keep ~10–15% headroom instead of clipping to
 *     pure 1.0. This matches the "shoulder" film curves use and the
 *     reference camera JPG exhibits, where overexposed surfaces keep
 *     subtle structure instead of becoming solid sheets of white.
 *
 * Mapping (input → output, in [0..1], gamma=0.6, shoulderStart=0.85):
 *   0.05 → 0.18   (3.6× shadow lift)
 *   0.20 → 0.38   (1.9×)
 *   0.50 → 0.66   (1.3×)
 *   0.80 → 0.87   (highlights nearly identity)
 *   0.95 → 0.93   (shoulder pulls overexposed area back into headroom)
 *   1.00 → 0.96
 *
 * Chroma boost ratio
 * ------------------
 * Lifting luma without lifting chroma desaturates shadows (the
 * chroma/luma ratio drops). To compensate, the caller multiplies
 * `(Cr−neutral)` and `(Cb−neutral)` by [Result.ratio]. But chroma
 * boost on near-white pixels just amplifies any neutral-WB error
 * into a visible cast — so the ratio is additionally **scaled down**
 * toward 1.0 as the *output* luma approaches 1.0.
 */
internal class ShadowLiftOp(
    // gamma=0.75 (was 0.6): softer mid-tone lift. The previous 0.6 was
    // tuned alongside Zero-DCE at full strength, but with Zero-DCE
    // now capped at 0.6 of its model output, an additional aggressive
    // pow lift here compounded into an HDR-overcooked look. 0.75 is
    // still meaningfully lifting shadows (Y=0.2 → 0.30, +50% lift)
    // without crushing the mid-tone separation.
    private val gamma: Double = 0.75,
    private val shoulderStart: Double = 0.85,
    private val shoulderTarget: Double = 0.96,
) {

    data class Result(val luma: Mat, val ratio: Mat) {
        fun release() {
            luma.release()
            ratio.release()
        }
    }

    fun apply(luma: Mat, maxBoost: Double = 2.5): Result {
        val maxValue = when (luma.depth()) {
            CvType.CV_8U -> 255.0
            CvType.CV_16U -> 65535.0
            CvType.CV_32F -> 1.0
            else -> 65535.0
        }

        // Normalise to [0,1].
        val srcF = Mat()
        luma.convertTo(srcF, CvType.CV_32FC1, 1.0 / maxValue)

        // Shadow lift: pow curve.
        val powed = Mat()
        Core.pow(srcF, gamma, powed)

        // Highlight shoulder: pixels above `shoulderStart` get
        // remapped linearly from [shoulderStart, 1.0] to
        // [shoulderStart, shoulderTarget]. Below shoulderStart we keep
        // the pow-lifted value as-is.
        //
        // shouldered = shoulderStart + (powed − shoulderStart) ·
        //              ((shoulderTarget − shoulderStart) /
        //               (1.0           − shoulderStart))
        // applied only where powed > shoulderStart.
        val shoulderScale = (shoulderTarget - shoulderStart) /
            (1.0 - shoulderStart)
        val toned = applyShoulder(powed, shoulderStart, shoulderScale)
        powed.release()

        // ratio = (toned + ε) / (srcF + ε) — luma-boost factor.
        val eps = 1.0 / 256.0
        val numer = Mat()
        Core.add(toned, Scalar(eps), numer)
        val denom = Mat()
        Core.add(srcF, Scalar(eps), denom)
        val ratio = Mat()
        Core.divide(numer, denom, ratio)
        numer.release()
        denom.release()

        // Clamp to [1.0, maxBoost] — never desaturate, never blow up
        // near-black noise into colour.
        Core.max(ratio, Scalar(1.0), ratio)
        Core.min(ratio, Scalar(maxBoost), ratio)

        // **Highlight chroma suppression.** Multiply the ratio by a
        // factor `(1 − toned)` that smoothly falls from 1 in shadows
        // to 0 in highlights. This pulls the per-pixel chroma boost
        // back toward 1.0 on bright pixels so any neutral-WB drift in
        // the LibRaw output isn't amplified into a magenta / colour
        // cast on whites.
        //
        //   ratio_final = 1 + (ratio − 1) · (1 − toned)
        //
        // At toned = 0 (pure shadow):  ratio_final = ratio    (full boost)
        // At toned = 1 (pure white):   ratio_final = 1.0      (no boost)
        val boostDelta = Mat()
        Core.subtract(ratio, Scalar(1.0), boostDelta)
        val oneMinusToned = Mat(toned.size(), toned.type(), Scalar(1.0))
        Core.subtract(oneMinusToned, toned, oneMinusToned)
        Core.multiply(boostDelta, oneMinusToned, boostDelta)
        Core.add(boostDelta, Scalar(1.0), ratio)
        boostDelta.release()
        oneMinusToned.release()

        // Convert lifted+shouldered luma back to input depth.
        val outLuma = Mat()
        toned.convertTo(outLuma, luma.type(), maxValue)
        srcF.release()
        toned.release()
        return Result(luma = outLuma, ratio = ratio)
    }

    /**
     * Build `toned` from `powed` so:
     *   toned = powed                                     if powed ≤ shoulderStart
     *         = shoulderStart + (powed − shoulderStart) · scale  otherwise
     *
     * Equivalent expression that avoids per-pixel branching:
     *   toned = min(powed, shoulderStart) +
     *           max(0, powed − shoulderStart) · scale
     */
    private fun applyShoulder(powed: Mat, shoulderStart: Double, scale: Double): Mat {
        val below = Mat()
        Core.min(powed, Scalar(shoulderStart), below)
        val above = Mat()
        Core.subtract(powed, Scalar(shoulderStart), above)
        Core.max(above, Scalar(0.0), above)
        Core.multiply(above, Scalar(scale), above)
        val toned = Mat()
        Core.add(below, above, toned)
        below.release()
        above.release()
        return toned
    }
}
