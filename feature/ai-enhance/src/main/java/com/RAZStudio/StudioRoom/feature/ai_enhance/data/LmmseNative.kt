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

package com.RAZStudio.StudioRoom.feature.ai_enhance.data

import android.graphics.Bitmap

/**
 * JNI bridge to the native LMMSE + USM C++ implementation.
 * Operates in-place on a mutable ARGB_8888 Bitmap.
 */
internal object LmmseNative {

    init {
        System.loadLibrary("lmmse_enhance")
    }

    /**
     * Run the LMMSE denoise + thresholded USM sharpen pipeline in-place.
     *
     * @param bitmap Mutable ARGB_8888 bitmap (modified in-place)
     * @param windowSize LMMSE window (3, 5, or 7)
     * @param noiseVariance σ_n² denoise strength (1.0–100.0)
     * @param usmRadius Gaussian kernel radius (0.5–3.0)
     * @param usmAmount HF boost multiplier (0.0–3.0)
     * @param usmThreshold Noise-gate on 0–255 scale (0.0–30.0)
     * @param skipDenoise Skip Stage 1
     * @param skipSharpen Skip Stage 2
     * @return true on success
     */
    @JvmStatic
    external fun nativeEnhance(
        bitmap: Bitmap,
        windowSize: Int,
        noiseVariance: Float,
        usmRadius: Float,
        usmAmount: Float,
        usmThreshold: Float,
        skipDenoise: Boolean,
        skipSharpen: Boolean
    ): Boolean
}
