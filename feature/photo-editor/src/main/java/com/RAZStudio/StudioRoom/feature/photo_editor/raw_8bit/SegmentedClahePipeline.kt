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

import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.domain.SegmentationMask
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.operations.AdaptiveClaheOp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.operations.AlphaBlender
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.operations.ChannelIsolator
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.operations.MaskPreprocessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.operations.ShadowLiftOp
import org.opencv.core.Mat
import org.opencv.core.Size

/**
 * Single-call tonemap that takes a 3-channel BGR source (the 16-bit
 * linear output from LibRaw, or any 8-bit BGR) plus a raw U2-Net mask
 * and returns an 8-bit BGR Mat suitable for the editor's working
 * buffer.
 *
 * Pipeline stages:
 *
 *   1. Decouple luma + chroma via YCrCb.
 *   2. Soften the mask with a small Gaussian (kills the network's
 *      coarse 320×320 edges).
 *   3. Run CLAHE twice with two configs — gentle on subject,
 *      aggressive on background. Each result is pre-multiplied by its
 *      (or its inverse) mask.
 *   4. Sum to a single luma plane.
 *   5. Boost chroma vibrancy inside the foreground region using the
 *      same mask, then merge + cvtColor back to BGR8.
 *
 * Memory: every intermediate `Mat` is released before return. The
 * caller owns only the returned Mat.
 */
internal class SegmentedClahePipeline(
    // blurRadius=0: skip the Gaussian softening on the mask. The
    // U2-Net mask is already softened by the bilinear upscale from
    // 320 → source resolution (~17 px transition width on a 5500-wide
    // image). Adding extra Gaussian on top of that produces a
    // 30+ px-wide blend zone — wide enough that bokeh circles
    // straddling the mask boundary get half foreground-CLAHE (gentle)
    // and half background-CLAHE (stronger), making their edges read
    // as soft. Removing the extra blur tightens the transition.
    private val maskPreprocessor: MaskPreprocessor = MaskPreprocessor(blurRadius = 0),
    private val channelIsolator: ChannelIsolator = ChannelIsolator(),
    // Shadow-lift on Y BEFORE CLAHE. Without this, the histogram inside
    // dark CLAHE tiles is so bottom-heavy that even a higher clipLimit
    // can't redistribute meaningfully. The lift moves dark mid-tones
    // upward by ~50% so CLAHE has actual histogram to operate on.
    // gamma=0.6 is a Lightroom-Shadows-slider-equivalent setting.
    private val shadowLift: ShadowLiftOp = ShadowLiftOp(gamma = 0.6),
    private val claheOperator: AdaptiveClaheOp = AdaptiveClaheOp(),
    // fgChromaBoost 1.35: 35% chroma magnitude boost on foreground
    // subjects. The previous 1.20 was a touch under camera JPG
    // saturation; 1.35 lands closer to Canon's processed JPG look on
    // foliage and dyed surfaces without pushing skin into orange.
    // The blender recentres chroma around neutral before multiplying
    // so this scales actual magnitude — not the raw encoded value.
    // Background uses bgChromaBoost (separate, currently 1.15) so
    // out-of-focus areas get colour life back too.
    private val alphaBlender: AlphaBlender = AlphaBlender(
        fgChromaBoost = 1.35,
        bgChromaBoost = 1.15,
    ),
    // Foreground CLAHE: gentle — preserves smooth skin/cloth gradients
    // without harsh local-contrast bumps. Small grid keeps the
    // contrast adjustments tightly localised on subjects.
    private val foregroundConfig: AdaptiveClaheOp.ClaheConfig =
        AdaptiveClaheOp.ClaheConfig(clipLimit = 1.8, gridSize = Size(8.0, 8.0)),
    // Background CLAHE: moderate-aggressive. clipLimit=4.5 is between
    // the overly-soft 3.5 (bokeh lost local definition) and the
    // overly-strong 6.0 (bokeh became HDR-looking). Grid 16×16 gives
    // a good balance — each tile is large enough to average over
    // genuine bokeh circles instead of flattening them.
    private val backgroundConfig: AdaptiveClaheOp.ClaheConfig =
        AdaptiveClaheOp.ClaheConfig(clipLimit = 4.5, gridSize = Size(16.0, 16.0)),
) {

    /**
     * @param srcBgr      3-channel BGR Mat (CV_8UC3 or CV_16UC3).
     * @param u2NetMask   single-channel U2-Net saliency mask (CV_8UC1
     *                    /CV_16UC1/CV_32FC1). Will be resized + soft-
     *                    edged inside [maskPreprocessor].
     * @return            a CV_8UC3 BGR Mat — caller's responsibility to
     *                    release.
     */
    fun execute(srcBgr: Mat, u2NetMask: Mat): Mat {
        val channels = channelIsolator.isolate(srcBgr)
        val mask = maskPreprocessor.preprocess(SegmentationMask(u2NetMask))

        // Lift shadows on Y BEFORE CLAHE so the CLAHE histograms inside
        // dark tiles aren't bottom-heavy. CLAHE then redistributes
        // genuine mid-tone information instead of stretching a dense
        // bottom cluster against a sparse top. The op also returns a
        // ratio map that the blender uses to re-saturate the dark
        // areas — without it those pixels would look colour-washed
        // because their chroma was tuned to the smaller original luma.
        val lift = shadowLift.apply(channels.luma)

        val yFore = claheOperator.applySegmentedClahe(
            luma = lift.luma,
            mask = mask.matrix,
            config = foregroundConfig,
            isForeground = true,
        )
        val yBack = claheOperator.applySegmentedClahe(
            luma = lift.luma,
            mask = mask.matrix,
            config = backgroundConfig,
            isForeground = false,
        )
        val finalLuma = alphaBlender.blend(yFore, yBack)
        val output = alphaBlender.reconstructAndBoost(
            finalLuma = finalLuma,
            originalChroma = channels.chroma,
            mask = mask.matrix,
            shadowChromaRatio = lift.ratio,
        )

        channels.release()
        mask.release()
        lift.release()
        yFore.release()
        yBack.release()
        finalLuma.release()
        return output
    }
}
