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

package com.RAZStudio.StudioRoom.feature.ai_enhance.domain.model

/**
 * Parameters for the two-stage LMMSE + Thresholded USM enhancement pipeline.
 *
 * Pipeline (pure math, zero VRAM, no model loading, deterministic time, SIMD-friendly):
 *
 *   Input → [Stage 1: LMMSE Denoise] → [Stage 2: Thresholded USM Sharpen] → Output
 *
 * Stage 1 — LMMSE (Linear Minimum Mean Square Error / Wiener filter):
 *   For each pixel, compute local mean (μ) and variance (σ²) over an N×N window.
 *   Output = μ + max(0, (σ² - σ_n²) / (σ² + ε)) · (pixel - μ)
 *   • Flat areas (σ² ≈ σ_n²): factor → 0, output = μ (noise erased)
 *   • Edges (σ² >> σ_n²): factor → 1, output = pixel (edge preserved)
 *   Self-masking — no binary edge mask needed.
 *
 * Stage 2 — Thresholded Unsharp Mask:
 *   H(x,y) = I_lmmse(x,y) - GaussianBlur(I_lmmse)(x,y)
 *   If |H| > threshold: output = I_lmmse + H × amount
 *   Else: output = I_lmmse (noise-gate kills residual micro-noise)
 *
 * Operating domain: post-demosaic 8-bit or 16-bit RGB (not raw Bayer).
 * Processing: per-channel or YCbCr-Y-only. Parallelizable via std::for_each + NEON.
 */
data class EnhanceParams(
    // ── Stage 1: LMMSE Denoise ──
    /** Local statistics window size (must be odd). Range: 3–7, step 2. */
    val windowSize: Int,
    /** Noise variance (σ_n²). Acts as "Denoise Strength" slider.
     *  Higher = more aggressive smoothing in flat areas. Range: 1.0–100.0 */
    val noiseVariance: Float,
    /** If true, auto-estimate noise variance from EXIF ISO metadata. */
    val autoNoiseFromExif: Boolean,

    // ── Stage 2: Thresholded USM Sharpening ──
    /** Gaussian blur kernel radius for HF extraction. Range: 0.5–3.0 */
    val usmRadius: Float,
    /** Scalar multiplier for the high-frequency boost. Range: 0.0–3.0 */
    val usmAmount: Float,
    /** Absolute noise-gate threshold. HF below this are rejected. Range: 0.0–30.0 */
    val usmThreshold: Float,

    // ── Pipeline Control ──
    /** Skip Stage 1 (for already-clean images that only need sharpening). */
    val skipDenoise: Boolean,
    /** Skip Stage 2 (denoise-only, no edge sharpening). */
    val skipSharpen: Boolean
) {

    companion object {
        // ── Stage 1: Window Size ──
        const val WINDOW_SIZE_MIN = 3
        const val WINDOW_SIZE_MAX = 7
        const val WINDOW_SIZE_STEP = 2  // odd only: 3, 5, 7
        const val WINDOW_SIZE_DEFAULT = 5

        // ── Stage 1: Noise Variance (σ_n²) ──
        const val NOISE_VARIANCE_MIN = 1.0f
        const val NOISE_VARIANCE_MAX = 100.0f
        const val NOISE_VARIANCE_STEP = 1.0f
        // Lowered 25→12: the previous default over-smoothed real luma texture
        // on clean shots (plastic look). Users can still raise it for noisy ones.
        const val NOISE_VARIANCE_DEFAULT = 12.0f

        // ── Stage 2: USM Radius ──
        const val USM_RADIUS_MIN = 0.5f
        const val USM_RADIUS_MAX = 3.0f
        const val USM_RADIUS_STEP = 0.1f
        const val USM_RADIUS_DEFAULT = 1.0f

        // ── Stage 2: USM Amount ──
        const val USM_AMOUNT_MIN = 0.0f
        const val USM_AMOUNT_MAX = 3.0f
        const val USM_AMOUNT_STEP = 0.1f
        // Lowered 1.5→0.9: heavy USM on denoised luma is the "plastic" signature.
        const val USM_AMOUNT_DEFAULT = 0.9f

        // ── Stage 2: USM Threshold ──
        const val USM_THRESHOLD_MIN = 0.0f
        const val USM_THRESHOLD_MAX = 30.0f
        const val USM_THRESHOLD_STEP = 0.5f
        const val USM_THRESHOLD_DEFAULT = 5.0f

        val Default = EnhanceParams(
            windowSize = WINDOW_SIZE_DEFAULT,
            noiseVariance = NOISE_VARIANCE_DEFAULT,
            autoNoiseFromExif = true,
            usmRadius = USM_RADIUS_DEFAULT,
            usmAmount = USM_AMOUNT_DEFAULT,
            usmThreshold = USM_THRESHOLD_DEFAULT,
            skipDenoise = false,
            skipSharpen = false
        )
    }
}
