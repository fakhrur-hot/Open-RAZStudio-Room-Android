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

@file:Suppress("unused")

package com.RAZStudio.opencv_tools.spot_heal

import android.graphics.Bitmap
import com.RAZStudio.opencv_tools.spot_heal.model.HealType
import com.RAZStudio.opencv_tools.utils.OpenCV
import com.RAZStudio.opencv_tools.utils.toBitmap
import com.RAZStudio.opencv_tools.utils.toMat
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

object SpotHealer : OpenCV() {

    fun heal(
        image: Bitmap,
        mask: Bitmap,
        radius: Float,
        type: HealType
    ): Bitmap {
        // OpenCV's `Photo.inpaint` requires the source as 3-channel
        // (CV_8UC3 RGB) and the mask as 1-channel (CV_8UC1 binary).
        val src = image.toMat()
        if (src.channels() == 4) {
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)
        }

        val inpaintMask = Mat()
        Imgproc.resize(
            mask.toMat(),
            inpaintMask,
            Size(image.width.toDouble(), image.height.toDouble()),
            0.0, 0.0,
            Imgproc.INTER_NEAREST,
        )
        when (inpaintMask.channels()) {
            4 -> Imgproc.cvtColor(inpaintMask, inpaintMask, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(inpaintMask, inpaintMask, Imgproc.COLOR_RGB2GRAY)
            else -> { /* already 1-channel */ }
        }
        // Feather the mask boundary to create soft transition instead of harsh edge.
        // This prevents TELEA from creating artifacts in the middle.
        // The gradual transition naturally blends inpainted pixels with originals.
        val featherRadius = (radius * 0.4f).coerceAtLeast(2f).toInt()
        if (featherRadius > 0) {
            val ksize = (featherRadius * 2 + 1).toDouble()
            Imgproc.GaussianBlur(
                inpaintMask, inpaintMask,
                org.opencv.core.Size(ksize, ksize),
                featherRadius.toDouble() * 0.5,
            )
        }
        Imgproc.threshold(
            inpaintMask, inpaintMask,
            127.0, 255.0,
            Imgproc.THRESH_BINARY,
        )

        val dst = Mat()
        Photo.inpaint(
            src,
            inpaintMask,
            dst,
            radius.toDouble(),
            type.ordinal,
        )

        // Post-process: Apply bilateral filter to smooth the harsh middle
        // while preserving sharp edges between healed and original areas.
        // This reduces the harsh/blocky artifacts TELEA creates in the center.
        val smoothed = Mat()
        val bilateralD = (radius * 0.5f).coerceIn(5f, 15f).toInt()
        val bilateralSigma = 50.0
        Imgproc.bilateralFilter(
            dst, smoothed,
            bilateralD,           // Diameter of pixel neighborhood
            bilateralSigma,       // Sigma for color space
            bilateralSigma,       // Sigma for coordinate space
        )
        dst.release()

        return smoothed.toBitmap()
    }


}