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

import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.domain.SegmentationMask
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Conditions the raw U2-Net mask so the downstream blend doesn't expose
 * the network's coarse 320×320 output as visible stair-step edges. Two
 * jobs:
 *
 *  1. **Type-normalise** the mask to CV_32FC1 in [0, 1]. The mask
 *     can arrive as CV_8UC1 (0-255), CV_16UC1 (0-65535), or already
 *     CV_32FC1 from the inference path. We rescale on convert.
 *  2. **Soft-edge** the mask with a small Gaussian blur. Radius defaults
 *     to 3 px which on the editor preview size (~1500 px wide) is
 *     subtle but enough to kill banding on hair / fur edges.
 *
 * Returns a *new* [SegmentationMask] — the input is not mutated.
 */
internal class MaskPreprocessor(private val blurRadius: Int = 3) {

    fun preprocess(rawMask: SegmentationMask): SegmentationMask {
        val processed = Mat()
        val src = rawMask.matrix
        when (src.type()) {
            CvType.CV_32FC1 -> src.copyTo(processed)
            CvType.CV_8UC1  -> src.convertTo(processed, CvType.CV_32FC1, 1.0 / 255.0)
            CvType.CV_16UC1 -> src.convertTo(processed, CvType.CV_32FC1, 1.0 / 65535.0)
            else            -> src.convertTo(processed, CvType.CV_32FC1)
        }
        if (blurRadius > 0) {
            val k = (blurRadius * 2 + 1).toDouble()
            Imgproc.GaussianBlur(processed, processed, Size(k, k), 0.0)
        }
        return SegmentationMask(processed)
    }
}
