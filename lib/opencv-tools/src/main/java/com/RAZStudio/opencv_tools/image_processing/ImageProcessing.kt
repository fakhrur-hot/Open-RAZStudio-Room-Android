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

package com.RAZStudio.opencv_tools.image_processing

import android.graphics.Bitmap
import com.RAZStudio.opencv_tools.utils.OpenCV
import com.RAZStudio.opencv_tools.utils.toBitmap
import com.RAZStudio.opencv_tools.utils.toMat
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object ImageProcessing : OpenCV() {

    fun canny(
        bitmap: Bitmap,
        thresholdOne: Float,
        thresholdTwo: Float
    ): Bitmap {
        val matGrayScale = Mat()
        Imgproc.cvtColor(bitmap.toMat(), matGrayScale, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.medianBlur(matGrayScale, matGrayScale, 3)

        val matCanny = Mat()
        Imgproc.Canny(matGrayScale, matCanny, thresholdOne.toDouble(), thresholdTwo.toDouble())

        return matCanny.toBitmap()
    }

    fun highPass(
        bitmap: Bitmap,
        radius: Float,
        strength: Float
    ): Bitmap {
        val src = bitmap.toMat()
        val blurred = Mat()
        val kSize = (radius.toInt() * 2 + 1).toDouble().coerceAtLeast(1.0)
        Imgproc.GaussianBlur(src, blurred, org.opencv.core.Size(kSize, kSize), 0.0)

        val highPass = Mat()
        org.opencv.core.Core.subtract(src, blurred, highPass)

        // Result = (Original - Blurred) * strength + 127
        if (strength != 1f) {
            org.opencv.core.Core.multiply(highPass, org.opencv.core.Scalar(strength.toDouble(), strength.toDouble(), strength.toDouble(), 1.0), highPass)
        }
        org.opencv.core.Core.add(highPass, org.opencv.core.Scalar(127.0, 127.0, 127.0, 0.0), highPass)

        val result = highPass.toBitmap()
        src.release()
        blurred.release()
        highPass.release()
        return result
    }

}