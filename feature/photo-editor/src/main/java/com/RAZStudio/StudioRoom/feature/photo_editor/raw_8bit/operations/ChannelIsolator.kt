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

import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.domain.ImageChannels
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Splits a 3-channel BGR Mat (CV_16UC3 from LibRaw or CV_8UC3 from a
 * non-RAW source) into its luma + chroma constituents via YCrCb space.
 *
 * Why YCrCb instead of HSV / Lab:
 *  - CLAHE is a luma-only operation; YCrCb cleanly isolates Y.
 *  - YCrCb keeps chroma in two channels that share scale, so the
 *    fg-chroma boost in [AlphaBlender] applies uniformly.
 *  - cvtColor's CV_16U YCrCb path is well-defined; Lab on CV_16U
 *    requires float intermediates and is much slower.
 *
 * The returned [ImageChannels] owns *new* Mats — caller releases via
 * [ImageChannels.release].
 */
internal class ChannelIsolator {

    fun isolate(bgr: Mat): ImageChannels {
        val yCrCb = Mat()
        Imgproc.cvtColor(bgr, yCrCb, Imgproc.COLOR_BGR2YCrCb)

        val planes = ArrayList<Mat>(3)
        Core.split(yCrCb, planes)

        val luma = planes[0]
        // Merge Cr + Cb into a 2-channel chroma Mat so callers treat it
        // as a single unit. CV_*UC2 keeps memory contiguous for the
        // blender's per-channel multiply.
        val chroma = Mat()
        Core.merge(arrayListOf(planes[1], planes[2]), chroma)

        // Plane[1] and [2] are now copied into `chroma` — release the
        // originals. Plane[0] (luma) is handed off to ImageChannels.
        planes[1].release()
        planes[2].release()
        yCrCb.release()

        return ImageChannels(luma = luma, chroma = chroma)
    }
}
