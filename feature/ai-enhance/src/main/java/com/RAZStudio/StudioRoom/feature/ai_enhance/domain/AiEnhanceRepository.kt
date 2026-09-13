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

package com.RAZStudio.StudioRoom.feature.ai_enhance.domain

import com.RAZStudio.StudioRoom.feature.ai_enhance.domain.model.EnhanceParams

/**
 * Repository for the LMMSE + Thresholded USM enhancement pipeline.
 *
 * No model downloads, no ONNX sessions, no VRAM — pure C++ math.
 * Runs in deterministic time, parallelized across CPU cores via NEON/SIMD.
 */
interface AiEnhanceRepository<Image> {

    /**
     * Run the two-stage enhancement pipeline on an RGB bitmap.
     *
     * Stage 1 (if !skipDenoise): LMMSE statistical denoiser
     *   - Self-masking via local variance ratio
     *   - Zero model loading, runs in milliseconds
     *
     * Stage 2 (if !skipSharpen): Thresholded Unsharp Mask
     *   - Separable Gaussian blur → HF extraction → threshold gate → amount boost
     *   - Only sharpens structural edges, ignores residual micro-noise
     *
     * @param image Input bitmap (8-bit ARGB_8888 or similar)
     * @param params Pipeline parameters (window size, noise variance, USM settings)
     * @param isoValue ISO value from EXIF (used when autoNoiseFromExif=true), or null
     * @param listener Progress callbacks
     * @return Enhanced bitmap, or null on error
     */
    suspend fun enhance(
        image: Image,
        params: EnhanceParams,
        isoValue: Int?,
        listener: EnhanceProgressListener
    ): Image?
}
