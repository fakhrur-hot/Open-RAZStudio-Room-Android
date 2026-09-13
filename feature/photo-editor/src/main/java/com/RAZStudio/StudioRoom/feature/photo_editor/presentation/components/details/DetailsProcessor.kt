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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.details

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Pure-Kotlin CPU processor for Details-tab adjustments.
 *
 * All operations run on [Dispatchers.Default]. The pipeline:
 * 1. Sharpness / Smart Sharpness  — unsharp mask via separable box-blur
 * 2. Luminance NR                 — luma-channel blend toward box-blurred luma
 * 3. Color NR                     — YCbCr chroma box-blur
 * 4. Film Grain                   — spatially-correlated procedural grain
 *
 * No ONNX models required.
 */
class DetailsProcessor {

    /**
     * Applies all active Details adjustments to [bitmap] and returns the result.
     * Returns [bitmap] unchanged when [selection] is empty.
     * Safe to call from a coroutine — internally switches to [Dispatchers.Default].
     */
    suspend fun apply(
        bitmap: Bitmap,
        selection: DetailsSelection,
    ): Bitmap = withContext(Dispatchers.Default) {
        if (selection.isEmpty) return@withContext bitmap

        val w = bitmap.width; val h = bitmap.height; val n = w * h
        val px = IntArray(n)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        var result = px

        // ── Step 1: Smart Sharpness ───────────────────────────────────────────
        if (selection.hasSharpness) {
            result = applySmartSharpness(result, w, h, selection.smartSharpness)
        }

        // ── Step 2: Luminance NR ──────────────────────────────────────────────
        if (selection.luminanceNR > 0f) {
            result = applyLuminanceNR(result, w, h, selection.luminanceNR)
        }

        // ── Step 3: Color NR ──────────────────────────────────────────────────
        if (selection.colorNR > 0f) {
            result = applyColorNR(result, w, h, selection.colorNR)
        }

        // ── Step 4: Wash Out ──────────────────────────────────────────────────
        if (selection.filmGrainWashOut > 0f) {
            result = applyWashOut(result, selection.filmGrainWashOut)
        }

        // ── Step 5: Film Grain ────────────────────────────────────────────────
        if (selection.filmGrain > 0f) {
            result = applyFilmGrain(result, w, h, selection.filmGrain, selection.filmGrainSize, selection.filmGrainUniformity)
        }

        Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }

    // ── Smart Sharpness ───────────────────────────────────────────────────────

    /**
     * Edge-only unsharp mask. Detail is computed via 8-tap stencil; gain is gated by
     * the L1 edge magnitude so flat areas are untouched.
     *
     * [smartSharpness] 0..+1 — scales to [0..+2] internally
     */
    private fun applySmartSharpness(
        px: IntArray,
        w: Int, h: Int,
        smartSharpness: Float,
    ): IntArray {
        val fr = FloatArray(px.size) { i -> ((px[i] shr 16) and 0xFF) / 255f }
        val fg = FloatArray(px.size) { i -> ((px[i] shr 8) and 0xFF) / 255f }
        val fb = FloatArray(px.size) { i -> (px[i] and 0xFF) / 255f }

        val br = FloatArray(px.size); val bg = FloatArray(px.size); val bb = FloatArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                fun cx(d: Int) = (x + d).coerceIn(0, w - 1)
                fun ry(d: Int) = (y + d).coerceIn(0, h - 1) * w
                br[i] = (fr[ry(-1)+cx(0)] + fr[ry(1)+cx(0)] + fr[ry(0)+cx(-1)] + fr[ry(0)+cx(1)] +
                         fr[ry(-3)+cx(0)] + fr[ry(3)+cx(0)] + fr[ry(0)+cx(-3)] + fr[ry(0)+cx(3)]) * 0.125f
                bg[i] = (fg[ry(-1)+cx(0)] + fg[ry(1)+cx(0)] + fg[ry(0)+cx(-1)] + fg[ry(0)+cx(1)] +
                         fg[ry(-3)+cx(0)] + fg[ry(3)+cx(0)] + fg[ry(0)+cx(-3)] + fg[ry(0)+cx(3)]) * 0.125f
                bb[i] = (fb[ry(-1)+cx(0)] + fb[ry(1)+cx(0)] + fb[ry(0)+cx(-1)] + fb[ry(0)+cx(1)] +
                         fb[ry(-3)+cx(0)] + fb[ry(3)+cx(0)] + fb[ry(0)+cx(-3)] + fb[ry(0)+cx(3)]) * 0.125f
            }
        }

        val result = IntArray(px.size)
        val sSharp = smartSharpness * 2f
        for (i in px.size - 1 downTo 0) {
            val dr = fr[i] - br[i]; val dg = fg[i] - bg[i]; val db = fb[i] - bb[i]
            val edgeMag = ((abs(dr) + abs(dg) + abs(db)) * 30f).coerceAtMost(1f)
            val r = fr[i] + sSharp * edgeMag * dr
            val g = fg[i] + sSharp * edgeMag * dg
            val b = fb[i] + sSharp * edgeMag * db
            result[i] = (0xFF shl 24) or
                ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (b * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return result
    }

    // ── Post-resize sharpening (libresize-style) ─────────────────────────────

    /**
     * Global unsharp mask applied after resize, matching libresize's sharpen parameter.
     * Uses the same 8-tap stencil as [applySmartSharpness] but without edge gating —
     * the gain is applied uniformly across all pixels.
     *
     * [strength] 0.30 = Low, 0.60 = Medium, 1.00 = High  (maps from [ResizeSharpen.strength])
     */
    fun applyPostResizeSharpen(bitmap: Bitmap, strength: Float): Bitmap {
        if (strength == 0f) return bitmap
        val w = bitmap.width; val h = bitmap.height; val n = w * h
        val px = IntArray(n)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        val fr = FloatArray(n) { i -> ((px[i] shr 16) and 0xFF) / 255f }
        val fg = FloatArray(n) { i -> ((px[i] shr 8) and 0xFF) / 255f }
        val fb = FloatArray(n) { i -> (px[i] and 0xFF) / 255f }

        val br = FloatArray(n); val bg = FloatArray(n); val bb = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                fun cx(d: Int) = (x + d).coerceIn(0, w - 1)
                fun ry(d: Int) = (y + d).coerceIn(0, h - 1) * w
                br[i] = (fr[ry(-1)+cx(0)] + fr[ry(1)+cx(0)] + fr[ry(0)+cx(-1)] + fr[ry(0)+cx(1)] +
                         fr[ry(-3)+cx(0)] + fr[ry(3)+cx(0)] + fr[ry(0)+cx(-3)] + fr[ry(0)+cx(3)]) * 0.125f
                bg[i] = (fg[ry(-1)+cx(0)] + fg[ry(1)+cx(0)] + fg[ry(0)+cx(-1)] + fg[ry(0)+cx(1)] +
                         fg[ry(-3)+cx(0)] + fg[ry(3)+cx(0)] + fg[ry(0)+cx(-3)] + fg[ry(0)+cx(3)]) * 0.125f
                bb[i] = (fb[ry(-1)+cx(0)] + fb[ry(1)+cx(0)] + fb[ry(0)+cx(-1)] + fb[ry(0)+cx(1)] +
                         fb[ry(-3)+cx(0)] + fb[ry(3)+cx(0)] + fb[ry(0)+cx(-3)] + fb[ry(0)+cx(3)]) * 0.125f
            }
        }

        val result = IntArray(n)
        val s = strength * 2f  // scale to 0..2 like libresize's effective range
        for (i in 0 until n) {
            val r = (fr[i] + s * (fr[i] - br[i])).coerceIn(0f, 1f)
            val g = (fg[i] + s * (fg[i] - bg[i])).coerceIn(0f, 1f)
            val b = (fb[i] + s * (fb[i] - bb[i])).coerceIn(0f, 1f)
            result[i] = (0xFF shl 24) or
                ((r * 255f + 0.5f).toInt() shl 16) or
                ((g * 255f + 0.5f).toInt() shl 8) or
                (b * 255f + 0.5f).toInt()
        }
        return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }

    // ── Luminance NR ──────────────────────────────────────────────────────────

    /**
     * Blends per-pixel luma toward box-blurred luma, leaving chroma untouched.
     * Equivalent to a luma-only low-pass filter: removes high-frequency luma noise
     * while preserving color information.
     *
     * Formula: lumaNR = mix(lumaPixel, lumaBlurred, strength × 0.6)
     *          output  = pixel + (lumaNR − lumaPixel)  [shifts all channels equally]
     */
    private fun applyLuminanceNR(px: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val luma = FloatArray(px.size) { i ->
            val p = px[i]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        val blurLuma = separableBoxBlurFloat(luma, w, h, radius = 3)

        val result = IntArray(px.size)
        val t = strength * 0.6f
        for (i in px.size - 1 downTo 0) {
            val p = px[i]
            val lNR = luma[i] + (blurLuma[i] - luma[i]) * t
            val shift = ((lNR - luma[i]) * 255f + 0.5f).toInt()
            val r = (((p shr 16) and 0xFF) + shift).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + shift).coerceIn(0, 255)
            val b = ((p and 0xFF) + shift).coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return result
    }

    // ── Color NR ─────────────────────────────────────────────────────────────

    /**
     * Converts image to YCbCr, box-blurs the Cb and Cr channels (radius scales with strength),
     * then converts back. Removes chroma noise without touching luminance detail.
     */
    private fun applyColorNR(px: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val n = px.size
        val Y = FloatArray(n); val Cb = FloatArray(n); val Cr = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            Y[i]  =  0.299f * r + 0.587f * g + 0.114f * b
            Cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b + 0.5f
            Cr[i] =  0.5f * r - 0.418688f * g - 0.081312f * b + 0.5f
        }
        // Blur radius: 1 at minimum strength, up to 8 at full strength
        val radius = (1 + (strength * 7f + 0.5f).toInt()).coerceIn(1, 8)
        val blurCb = separableBoxBlurFloat(Cb, w, h, radius)
        val blurCr = separableBoxBlurFloat(Cr, w, h, radius)

        val result = IntArray(n)
        for (i in 0 until n) {
            val y = Y[i]
            val cb = Cb[i] + (blurCb[i] - Cb[i]) * strength
            val cr = Cr[i] + (blurCr[i] - Cr[i]) * strength
            val r = (y + 1.402f * (cr - 0.5f)).coerceIn(0f, 1f)
            val g = (y - 0.344136f * (cb - 0.5f) - 0.714136f * (cr - 0.5f)).coerceIn(0f, 1f)
            val b = (y + 1.772f * (cb - 0.5f)).coerceIn(0f, 1f)
            result[i] = (0xFF shl 24) or
                ((r * 255f + 0.5f).toInt() shl 16) or
                ((g * 255f + 0.5f).toInt() shl 8) or
                (b * 255f + 0.5f).toInt()
        }
        return result
    }

    // ── Wash Out ─────────────────────────────────────────────────────────────

    /**
     * Milky fade / washed-out film look.
     *
     * Lifts the black point and compresses the tonal range to simulate the base fog
     * of old film stocks:
     *   output = blackLift + pixel * (1 − blackLift − whiteCrush)
     *
     * At strength=1: blacks → ~15%, whites → ~95%, contrast reduced to 80% of original.
     */
    private fun applyWashOut(px: IntArray, strength: Float): IntArray {
        val blackLift = strength * 0.15f
        val scale = 1f - blackLift - strength * 0.05f
        val result = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val r = (blackLift * 255f + ((p shr 16) and 0xFF) * scale + 0.5f).toInt().coerceIn(0, 255)
            val g = (blackLift * 255f + ((p shr 8)  and 0xFF) * scale + 0.5f).toInt().coerceIn(0, 255)
            val b = (blackLift * 255f + ( p          and 0xFF) * scale + 0.5f).toInt().coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return result
    }

    // ── Film Grain ────────────────────────────────────────────────────────────

    /**
     * Adds spatially-correlated procedural grain.
     *
     * Grain is generated by blending:
     * - Structured grain: 2×2 block hash seeded from pixel coordinates (coarse spatial structure)
     * - Uniform noise: per-pixel random, seeded from position + global seed
     *
     * The [uniformity] parameter blends between structured (0) and uniform (1).
     * [size] controls the spatial frequency — larger size = softer, lower-frequency grain.
     */
    private fun applyFilmGrain(
        px: IntArray,
        w: Int, h: Int,
        strength: Float,
        size: Float,
        uniformity: Float,
    ): IntArray {
        val seed = 12345L  // deterministic per adjustment
        val result = px.copyOf()
        // Block size for structured grain: 1 at size=0 (fine), up to 4 at size=1 (coarse)
        val blockSize = (1 + (size * 3f + 0.5f).toInt()).coerceIn(1, 4)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]

                // Structured grain: deterministic hash of block coordinates
                val bx = x / blockSize; val by = y / blockSize
                val structuredGrain = hash(bx, by, seed)  // −1..+1

                // Uniform noise: per-pixel hash
                val uniformGrain = hash2(x, y, seed)  // −1..+1

                val grain = (1f - uniformity) * structuredGrain + uniformity * uniformGrain
                val amount = (grain * strength * 45f + 0.5f).toInt().coerceIn(-64, 64)

                val r = (((p shr 16) and 0xFF) + amount).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + amount).coerceIn(0, 255)
                val b = ((p and 0xFF) + amount).coerceIn(0, 255)
                result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return result
    }

    /** Fast integer hash in [−1, +1] from 2D block coordinate + seed. */
    private fun hash(x: Int, y: Int, seed: Long): Float {
        var h = (x * 1664525L + y * 1013904223L + seed) and 0xFFFFFFFFL
        h = (h xor (h shr 16)) * 0x45D9F3BL
        h = (h xor (h shr 16)) * 0x45D9F3BL
        h = h xor (h shr 16)
        return (h and 0xFFL).toFloat() / 127.5f - 1f
    }

    /** Per-pixel hash for uniform noise component. */
    private fun hash2(x: Int, y: Int, seed: Long): Float {
        var h = (x * 374761393L + y * 668265263L + seed) and 0xFFFFFFFFL
        h = (h xor (h shr 13)) * 1274126177L
        h = h xor (h shr 16)
        return (h and 0xFFL).toFloat() / 127.5f - 1f
    }

    // ── Separable box blur (float channels) ──────────────────────────────────

    /**
     * O(W×H) sliding-window separable box blur on a single float channel.
     * Uses horizontal pass → vertical pass with O(W+H) extra memory.
     */
    private fun separableBoxBlurFloat(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val diameter = 2 * radius + 1
        val temp = FloatArray(src.size)
        // Horizontal pass
        for (y in 0 until h) {
            var sum = 0f
            val yOff = y * w
            for (dx in -radius..radius) sum += src[yOff + dx.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                temp[yOff + x] = sum / diameter
                val leave = x - radius; if (leave >= 0) sum -= src[yOff + leave]
                val enter = x + radius + 1; if (enter < w) sum += src[yOff + enter]
            }
        }
        // Vertical pass
        val result = FloatArray(src.size)
        for (x in 0 until w) {
            var sum = 0f
            for (dy in -radius..radius) sum += temp[dy.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                result[y * w + x] = sum / diameter
                val leave = y - radius; if (leave >= 0) sum -= temp[leave * w + x]
                val enter = y + radius + 1; if (enter < h) sum += temp[enter * w + x]
            }
        }
        return result
    }
}
