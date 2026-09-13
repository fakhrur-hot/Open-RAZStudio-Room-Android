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
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.feature.ai_enhance.domain.AiEnhanceRepository
import com.RAZStudio.StudioRoom.feature.ai_enhance.domain.EnhanceProgressListener
import com.RAZStudio.StudioRoom.feature.ai_enhance.domain.model.EnhanceParams
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Repository backed by native C++ LMMSE + USM pipeline.
 *
 * No model downloads. No ONNX sessions. No VRAM.
 * The native call runs in-place on a mutable bitmap copy,
 * parallelized across CPU cores in C++.
 */
internal class AndroidAiEnhanceRepository @Inject constructor(
    dispatchersHolder: DispatchersHolder
) : AiEnhanceRepository<Bitmap>, DispatchersHolder by dispatchersHolder {

    override suspend fun enhance(
        image: Bitmap,
        params: EnhanceParams,
        isoValue: Int?,
        listener: EnhanceProgressListener
    ): Bitmap? = withContext(defaultDispatcher) {
        // Compute effective noise variance
        val effectiveNoiseVariance = if (params.autoNoiseFromExif && isoValue != null) {
            estimateNoiseFromIso(isoValue)
        } else {
            params.noiseVariance
        }

        // Create a mutable ARGB_8888 copy for in-place processing
        val workBitmap = if (image.isMutable && image.config == Bitmap.Config.ARGB_8888) {
            image.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            image.copy(Bitmap.Config.ARGB_8888, true)
        }

        if (workBitmap == null) {
            listener.onError("Failed to create working bitmap copy")
            return@withContext null
        }

        try {
            if (!params.skipDenoise) {
                listener.onStageStarted("LMMSE Denoise (σ²=${String.format("%.1f", effectiveNoiseVariance)})")
            }

            val success = LmmseNative.nativeEnhance(
                bitmap = workBitmap,
                windowSize = params.windowSize,
                noiseVariance = effectiveNoiseVariance,
                usmRadius = params.usmRadius,
                usmAmount = params.usmAmount,
                usmThreshold = params.usmThreshold,
                skipDenoise = params.skipDenoise,
                skipSharpen = params.skipSharpen
            )

            if (!success) {
                workBitmap.recycle()
                listener.onError("Native LMMSE pipeline failed")
                return@withContext null
            }

            listener.onComplete()
            workBitmap
        } catch (e: Throwable) {
            workBitmap.recycle()
            listener.onError(e.message ?: "Unknown native error")
            null
        }
    }

    /**
     * Estimate noise variance (σ_n²) from ISO metadata.
     *
     * Empirical mapping: sensor noise ∝ sqrt(ISO) approximately.
     * These values are calibrated for typical APS-C/full-frame sensors:
     *   ISO 100  → σ_n² ≈ 3  (barely noticeable)
     *   ISO 400  → σ_n² ≈ 8
     *   ISO 1600 → σ_n² ≈ 20
     *   ISO 6400 → σ_n² ≈ 45
     *   ISO 25600 → σ_n² ≈ 90
     *
     * Clamped to the valid parameter range [1, 100].
     */
    private fun estimateNoiseFromIso(iso: Int): Float {
        // σ_n² ≈ 0.56 × sqrt(ISO) — tuned for typical camera sensors
        val estimated = 0.56f * kotlin.math.sqrt(iso.toFloat())
        return estimated.coerceIn(
            EnhanceParams.NOISE_VARIANCE_MIN,
            EnhanceParams.NOISE_VARIANCE_MAX
        )
    }
}
