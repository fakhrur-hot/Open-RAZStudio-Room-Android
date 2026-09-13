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
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Runs OpenCV CLAHE with different clip/grid profiles for foreground and
 * background, then weights the result against the segmentation mask so
 * a later add() reconstructs the full image with no spillover between
 * regions.
 *
 *  - Foreground (subject, e.g. skin/fabric): low clipLimit, small grid.
 *    Preserves smooth skin gradients without aggressive local contrast.
 *  - Background: higher clipLimit, larger grid. Reveals atmospheric
 *    haze, mid-tone detail in foliage / sky.
 *
 * The CLAHE kernel itself runs on the luma plane unmasked — masking
 * before CLAHE corrupts the local histograms with zeros at the boundary.
 * We CLAHE the full plane, then multiply by the (inverted-for-background)
 * mask before returning.
 */
internal class AdaptiveClaheOp {

    data class ClaheConfig(
        val clipLimit: Double,
        val gridSize: Size,
    )

    fun applySegmentedClahe(
        luma: Mat,
        mask: Mat,
        config: ClaheConfig,
        isForeground: Boolean,
    ): Mat {
        val enhanced = Mat()
        val clahe = Imgproc.createCLAHE(config.clipLimit, config.gridSize)
        clahe.apply(luma, enhanced)

        // Weight the enhanced luma against the (foreground or inverse)
        // mask so the final blend = M·Y_fg + (1−M)·Y_bg adds cleanly.
        // multiply() with a float mask + dtype back to luma.type() keeps
        // the result in the luma's native depth (CV_16UC1 here for 16U
        // sources).
        val weighting = if (isForeground) {
            mask
        } else {
            val inverse = Mat(mask.size(), mask.type(), org.opencv.core.Scalar(1.0))
            Core.subtract(inverse, mask, inverse)
            inverse
        }
        val weighted = Mat()
        // Convert luma to float for the multiply, then back to its
        // original depth — multiplying a CV_16U by a CV_32F mask
        // directly with dtype=CV_16U truncates and clips badly.
        val lumaF = Mat()
        enhanced.convertTo(lumaF, CvType.CV_32FC1)
        Core.multiply(lumaF, weighting, weighted, 1.0, CvType.CV_32FC1)
        weighted.convertTo(weighted, luma.type())

        clahe.collectGarbage()
        enhanced.release()
        lumaF.release()
        if (!isForeground) weighting.release()
        return weighted
    }
}
