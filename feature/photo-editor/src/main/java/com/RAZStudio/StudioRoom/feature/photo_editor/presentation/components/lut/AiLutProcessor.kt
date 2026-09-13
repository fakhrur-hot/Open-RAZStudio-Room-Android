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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.OrtSessionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Applies AI-aware LUT adjustments using U2Net subject segmentation.
 *
 * Capabilities:
 * - U2Net subject mask inference (320×320, session cached per instance)
 * - Sobel sharp-edge mask (25% scale, fast, computed per photo)
 * - Per-region HDR tone mapping with mid-anchor preservation
 * - Subject/Background brightness & shadow/highlight sigmoid boosts
 * - Subject/Background color temperature and tint adjustments
 * - Global photo adjustments: exposure, contrast, saturation, vibrance, per-hue HSL
 *
 * OrtSession is created once and reused across all calls (168 MB U2Net model).
 * Call [close] when done to release native resources.
 */
class AiLutProcessor(private val context: Context) {

    // ── Model availability ────────────────────────────────────────────────────

    private val modelPath: String? by lazy {
        listOf("models/u2net.onnx").firstOrNull { path ->
            runCatching { context.assets.open(path).close(); true }.getOrDefault(false)
        }
    }

    val hasModel: Boolean get() = modelPath != null

    // ── Session cache — created once, reused across all calls ─────────────────
    // U2Net is 168 MB: session creation from bytes takes 1–5 s.
    // Lazy initialization ensures the cost is paid only once, not on every inference.

    private val u2netSessionLazy: Lazy<OrtSession?> = lazy {
        runCatching {
            val path = modelPath ?: return@runCatching null
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(path).use { it.readBytes() }
            env.createSession(bytes, buildAiSessionOptions())
        }.getOrNull()
    }
    private val u2netSession: OrtSession? by u2netSessionLazy

    private val gate = OrtSessionGate("AiLutProcessor")

    /**
     * Release native OrtSession resources. Safe to call while inference is in
     * progress — close is deferred until OrtSession.run returns ([OrtSessionGate]).
     * Call via [rememberAiLutProcessor]'s DisposableEffect.
     */
    fun close() {
        gate.release {
            if (u2netSessionLazy.isInitialized()) {
                runCatching { u2netSession?.close() }
            }
        }
    }

    // ── Public inference API ──────────────────────────────────────────────────

    /**
     * Runs U2Net inference and returns a flat float mask [0,1] per pixel.
     * Slow (~0.5–2 s on first call including session init; reuses cached session after).
     * Returns null immediately if [close] has already been called.
     */
    suspend fun computeMask(bitmap: Bitmap): FloatArray? {
        if (gate.isReleased) return null
        return runCatching { inferAlphaMask(bitmap) }.getOrNull()
    }

    /**
     * Computes a Sobel-based sharp-edge mask at 25% scale, bilinearly upscaled to [bitmap] dimensions.
     * Fast (~5–30 ms) — compute once and cache per photo.
     */
    fun computeEdgeMask(bitmap: Bitmap): FloatArray {
        val w = bitmap.width; val h = bitmap.height
        val sw = maxOf(2, (w * 0.25f).toInt()); val sh = maxOf(2, (h * 0.25f).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()

        val gray = FloatArray(sw * sh) { i ->
            val px = pixels[i]
            (0.299f * ((px shr 16) and 0xFF) + 0.587f * ((px shr 8) and 0xFF) + 0.114f * (px and 0xFF)) / 255f
        }
        val edge = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val x0 = (x - 1).coerceAtLeast(0); val x1 = (x + 1).coerceAtMost(sw - 1)
                val y0 = (y - 1).coerceAtLeast(0); val y1 = (y + 1).coerceAtMost(sh - 1)
                val gx = -gray[y0*sw+x0] + gray[y0*sw+x1] - 2f*gray[y*sw+x0] + 2f*gray[y*sw+x1] - gray[y1*sw+x0] + gray[y1*sw+x1]
                val gy = -gray[y0*sw+x0] - 2f*gray[y0*sw+x] - gray[y0*sw+x1] + gray[y1*sw+x0] + 2f*gray[y1*sw+x] + gray[y1*sw+x1]
                edge[y*sw+x] = sqrt((gx*gx + gy*gy).toDouble()).toFloat().coerceIn(0f, 1f)
            }
        }
        val maxE = edge.maxOrNull() ?: 1f
        if (maxE > 0f) for (i in edge.indices) edge[i] = (edge[i] / maxE).coerceIn(0f, 1f)
        return bilinearUpscaleFlat(edge, sw, sh, w, h)
    }

    // ── Adjustment application ────────────────────────────────────────────────

    /**
     * Applies shadow/highlight tone mapping and masked subject/background adjustments.
     * Fast (~20–100 ms) — safe to call on every slider change.
     */
    fun applyAdjustments(
        bitmap: Bitmap,
        mask: FloatArray?,
        subjectPop: Float,
        backgroundBrightness: Float,
        shadowBoost: Float = 0f,
        highlightBoost: Float = 0f,
        subjectTemperature: Float = 0f,
        backgroundTemperature: Float = 0f,
        subjectTint: Float = 0f,
        backgroundTint: Float = 0f,
        highlightTemperature: Float = 0f,
        highlightTint: Float = 0f,
    ): Bitmap {
        val noMasked = subjectPop == 0f && backgroundBrightness == 0f &&
            subjectTemperature == 0f && backgroundTemperature == 0f &&
            subjectTint == 0f && backgroundTint == 0f
        val noTone = shadowBoost == 0f && highlightBoost == 0f &&
            highlightTemperature == 0f && highlightTint == 0f
        if (noMasked && noTone) return bitmap

        val toned = if (noTone) bitmap
        else applyShadowHighlightBoost(
            bitmap,
            sliderToBoostFactor(shadowBoost),
            sliderToBoostFactor(highlightBoost),
            highlightTemperature,
            highlightTint,
        )

        if (noMasked) return toned
        return if (mask != null) applyMaskedAdjustments(
            toned, mask, subjectPop, backgroundBrightness,
            subjectTemperature, backgroundTemperature,
            subjectTint, backgroundTint,
        )
        else applyUniformAdjustment(toned, subjectPop)
    }

    /**
     * Applies Lightroom-style Texture + Clarity local contrast boost globally across the image.
     *
     * Two frequency bands are separated using two box blurs at different spatial radii:
     *   textureDetail = luma − blurSmall  (fine grain / micro-texture)
     *   clarityDetail = blurSmall − blurMedium  (mid-frequency local contrast)
     *
     * Texture is applied at full strength; clarity at 30% so the result reads as
     * micro-texture enhancement without the broad mid-tone halo of classic clarity.
     * [strength] is expected in [0, 0.15] — slider cap keeps the effect subtle.
     * Applied uniformly across the whole image (no edge mask).
     */
    fun applyEdgeBlackClip(
        bitmap: Bitmap,
        strength: Float,
    ): Bitmap {
        if (strength <= 0f) return bitmap
        val w = bitmap.width; val h = bitmap.height; val n = w * h
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = FloatArray(n) { i ->
            val px = pixels[i]
            (0.299f * ((px shr 16) and 0xFF) + 0.587f * ((px shr 8) and 0xFF) + 0.114f * (px and 0xFF)) / 255f
        }

        // Fine radius for micro-texture; medium radius for the clarity band
        val avg = (w + h) / 2
        val textureRadius = (avg * 0.005f).toInt().coerceIn(1, 4)
        val clarityRadius  = (avg * 0.020f).toInt().coerceIn(2, 20)

        val blurSmall  = separableBoxBlurFloat(luma, w, h, textureRadius)
        val blurMedium = separableBoxBlurFloat(luma, w, h, clarityRadius)

        val result = IntArray(n)
        for (i in 0 until n) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val lum = luma[i]

            val textureDetail = lum - blurSmall[i]           // fine micro-texture
            val clarityDetail = blurSmall[i] - blurMedium[i] // mid-frequency band only

            // Texture at 100%, clarity at 30% to avoid harsh mid-tone halos
            val detail = textureDetail + 0.3f * clarityDetail

            val boost = (strength * 4f * detail * 255f + 0.5f).toInt()
            result[i] = (0xFF shl 24) or
                ((r + boost).coerceIn(0, 255) shl 16) or
                ((g + boost).coerceIn(0, 255) shl 8) or
                (b + boost).coerceIn(0, 255)
        }
        return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Separable box blur on a float array. O(w×h) via horizontal + vertical passes. */
    private fun separableBoxBlurFloat(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val temp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(w - 1)
                var s = 0f
                for (xi in x0..x1) s += src[base + xi]
                temp[base + x] = s / (x1 - x0 + 1)
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                val y0 = (y - radius).coerceAtLeast(0)
                val y1 = (y + radius).coerceAtMost(h - 1)
                var s = 0f
                for (yi in y0..y1) s += temp[yi * w + x]
                out[y * w + x] = s / (y1 - y0 + 1)
            }
        }
        return out
    }

    /**
     * Applies global photo adjustments (exposure, contrast, saturation, vibrance, per-hue HSL)
     * to [bitmap]. Returns [bitmap] unchanged if [adj] is empty.
     *
     * Must be called from a coroutine; internally dispatches to [Dispatchers.Default].
     */
    suspend fun applyGlobalAdjustments(bitmap: Bitmap, adj: GlobalPhotoAdjustments): Bitmap =
        withContext(Dispatchers.Default) {
            if (adj.isEmpty) return@withContext bitmap

            val w = bitmap.width; val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val result = IntArray(pixels.size)

            val exposureMul = 2f.pow(adj.exposure)

            for (i in pixels.indices) {
                val px = pixels[i]

                // Unpack to float [0,1]
                var r = ((px shr 16) and 0xFF) / 255f
                var g = ((px shr 8) and 0xFF) / 255f
                var b = (px and 0xFF) / 255f

                // Exposure
                if (adj.exposure != 0f) {
                    r = (r * exposureMul).coerceIn(0f, 1f)
                    g = (g * exposureMul).coerceIn(0f, 1f)
                    b = (b * exposureMul).coerceIn(0f, 1f)
                }

                // Contrast: out = 0.5 + (in - 0.5) * (1 + contrast)
                if (adj.contrast != 0f) {
                    val factor = 1f + adj.contrast
                    r = (0.5f + (r - 0.5f) * factor).coerceIn(0f, 1f)
                    g = (0.5f + (g - 0.5f) * factor).coerceIn(0f, 1f)
                    b = (0.5f + (b - 0.5f) * factor).coerceIn(0f, 1f)
                }

                // Convert to HSL
                val hsl = rgbToHsl(r, g, b)
                var h = hsl[0]   // 0..360
                var s = hsl[1]   // 0..1
                var l = hsl[2]   // 0..1

                // Global saturation
                if (adj.saturation != 0f) {
                    s = (s * (1f + adj.saturation)).coerceIn(0f, 1f)
                }

                // Vibrance (saturation boost weighted by desaturation)
                if (adj.vibrance != 0f) {
                    s = if (adj.vibrance > 0f) {
                        (s + adj.vibrance * (1f - s)).coerceIn(0f, 1f)
                    } else {
                        (s * (1f + adj.vibrance)).coerceIn(0f, 1f)
                    }
                }

                // Per-hue HSL adjustments. Gate by saturation so near-
                // achromatic pixels (white/gray/black) — whose hue is numerically
                // unstable and defaults to 0° (red) — are not tinted or darkened
                // by color-specific sliders.
                val hueAdj = getHueAdjustment(h, adj)
                if (!hueAdj.isEmpty) {
                    val satMask = smoothstep01(0.05f, 0.20f, s)
                    h = (h + hueAdj.hueShift * 180f * satMask + 360f) % 360f
                    s = (s * (1f + hueAdj.saturation * satMask)).coerceIn(0f, 1f)
                    l = (l + hueAdj.luminance * 0.5f * satMask).coerceIn(0f, 1f)
                }

                // Convert back to RGB
                val rgb = hslToRgb(h, s, l)
                val ri = (rgb[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (rgb[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (rgb[2] * 255f + 0.5f).toInt().coerceIn(0, 255)

                result[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }

            Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
        }

    /**
     * Returns the [HslAdjustment] from [adj] that corresponds to the given hue (0..360°).
     * Hard hue bins:
     *   Red     345–360° and 0–15°
     *   Orange  15–45°
     *   Yellow  45–75°
     *   Green   75–165°
     *   Cyan    165–195°
     *   Blue    195–255°
     *   Purple  255–315°
     *   Magenta 315–345°
     */
    private fun getHueAdjustment(hue: Float, adj: GlobalPhotoAdjustments): HslAdjustment {
        val h = ((hue % 360f) + 360f) % 360f
        return when {
            h >= 345f || h < 15f  -> adj.hslRed
            h < 45f               -> adj.hslOrange
            h < 75f               -> adj.hslYellow
            h < 165f              -> adj.hslGreen
            h < 195f              -> adj.hslCyan
            h < 255f              -> adj.hslBlue
            h < 315f              -> adj.hslPurple
            else                  -> adj.hslMagenta  // 315–345°
        }
    }

    /**
     * Computes 256-bin luminance histograms for subject, background, and edge segments.
     * Uses mask weights to attribute pixels to each region (threshold 0.3).
     * Also logs the histograms via ADB for debugging.
     */
    fun computeHistograms(
        bitmap: Bitmap,
        subjectMask: FloatArray?,
        edgeMask: FloatArray?,
    ): Triple<IntArray, IntArray, IntArray> {
        val w = bitmap.width; val h = bitmap.height; val n = w * h
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val subjW = subjectMask ?: FloatArray(n)
        val edgeW = edgeMask ?: FloatArray(n)
        val subjectHist = IntArray(256)
        val bgHist = IntArray(256)
        val edgeHist = IntArray(256)
        for (i in 0 until n) {
            val px = pixels[i]
            val luma = ((0.299f * ((px shr 16) and 0xFF) + 0.587f * ((px shr 8) and 0xFF) + 0.114f * (px and 0xFF)) / 255f * 255f + 0.5f).toInt().coerceIn(0, 255)
            val sw = subjW[i]; val ew = edgeW[i]; val bw = (1f - sw - ew).coerceAtLeast(0f)
            if (sw > 0.3f) subjectHist[luma]++
            else if (ew > 0.3f) edgeHist[luma]++
            else if (bw > 0.3f) bgHist[luma]++
        }
        logHistogram("Subject", subjectHist)
        logHistogram("Background", bgHist)
        logHistogram("Edge", edgeHist)
        return Triple(subjectHist, bgHist, edgeHist)
    }

    private fun logHistogram(segment: String, hist: IntArray) {
        val total = hist.sum().coerceAtLeast(1)
        val bars = buildString {
            var i = 0
            while (i < 256) {
                val binSum = (i until minOf(i + 8, 256)).sumOf { hist[it] }
                val pct = binSum * 100 / total
                append(when { pct >= 75 -> "█"; pct >= 50 -> "▇"; pct >= 25 -> "▅"; pct >= 10 -> "▃"; pct >= 1 -> "▁"; else -> " " })
                i += 8
            }
        }
        Log.d("LutHistogram", "[$segment] $bars  (total=$total)")
        val peak = hist.indexOfMax()
        Log.d("LutHistogram", "[$segment] peak=L$peak (${hist[peak]} px), dark=${hist.slice(0..63).sum()}, mid=${hist.slice(64..191).sum()}, bright=${hist.slice(192..255).sum()}")
    }

    private fun IntArray.indexOfMax(): Int { var maxIdx = 0; for (i in 1 until size) if (this[i] > this[maxIdx]) maxIdx = i; return maxIdx }

    // ── Shadow / Highlight sigmoid boost ─────────────────────────────────────

    private fun sliderToBoostFactor(slider: Float): Double =
        if (slider >= 0f) 1.0 + slider.toDouble() else 1.0 + slider.toDouble() * 0.5

    private fun applyShadowHighlightBoost(
        bitmap: Bitmap,
        shadowBoostFactor: Double,
        highlightBoostFactor: Double,
        highlightTemperature: Float = 0f,
        highlightTint: Float = 0f,
    ): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        // Warm = +R −B, cool = −R +B. Positive tint = magenta (+R −G +B).
        val maxTempShift = 40f
        val maxTintShift = 25f

        for (i in pixels.indices) {
            val px = pixels[i]
            var r = (px shr 16) and 0xFF; var g = (px shr 8) and 0xFF; var b = px and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            val shadowWeight    = 1.0 / (1.0 + exp(12.0 * (lum - 0.10)))
            val highlightWeight = 1.0 / (1.0 + exp(-12.0 * (lum - 0.85)))
            val factor = 1.0 + (shadowBoostFactor - 1.0) * shadowWeight + (highlightBoostFactor - 1.0) * highlightWeight
            r = (r * factor).toInt().coerceIn(0, 255)
            g = (g * factor).toInt().coerceIn(0, 255)
            b = (b * factor).toInt().coerceIn(0, 255)
            if (highlightTemperature != 0f) {
                val shift = (highlightTemperature * maxTempShift * highlightWeight + 0.5).toInt()
                r = (r + shift).coerceIn(0, 255)
                b = (b - shift).coerceIn(0, 255)
            }
            if (highlightTint != 0f) {
                val shift = (highlightTint * maxTintShift * highlightWeight + 0.5).toInt()
                r = (r + shift).coerceIn(0, 255)
                g = (g - shift).coerceIn(0, 255)
                b = (b + shift).coerceIn(0, 255)
            }
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ── U2Net ONNX inference ─────────────────────────────────────────────────

    private suspend fun inferAlphaMask(bitmap: Bitmap): FloatArray =
        withContext(Dispatchers.IO) {
            gate.run {
                val session = u2netSession ?: error("U2Net session unavailable")
                val env = OrtEnvironment.getEnvironment()

                val inputSize = 320
                val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
                try {
                    val inputTensor = bitmapToNchwTensor(scaled, env, inputSize)
                    val output = session.run(mapOf(session.inputNames.first() to inputTensor))
                    inputTensor.close()

                    val primary = extractSaliencyMap(output[0].value, inputSize)
                    val sideMap = runCatching {
                        val n = output.size()
                        if (n > 1) extractSaliencyMap(output[n - 1].value, inputSize) else null
                    }.getOrNull()
                    val fused = if (sideMap != null) fuseOutputs(primary, sideMap, inputSize) else primary

                    val flat = FloatArray(inputSize * inputSize) { i -> fused[i / inputSize][i % inputSize] }
                    val scaledPixels = IntArray(inputSize * inputSize)
                    scaled.getPixels(scaledPixels, 0, inputSize, 0, 0, inputSize, inputSize)
                    val refined = postProcessMask(scaledPixels, flat, inputSize, inputSize)
                    val refined2D = Array(inputSize) { y -> FloatArray(inputSize) { x -> refined[y * inputSize + x] } }
                    bilinearUpscale(refined2D, inputSize, inputSize, bitmap.width, bitmap.height)
                } finally {
                    scaled.recycle()
                }
            } ?: error("AiLutProcessor closed")
        }

    private fun bitmapToNchwTensor(bitmap: Bitmap, env: OrtEnvironment, size: Int): OnnxTensor {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val data = FloatArray(3 * size * size)
        val gOff = size * size; val bOff = 2 * size * size
        for (i in pixels.indices) {
            val px = pixels[i]
            data[i]        = (((px shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
            data[gOff + i] = (((px shr 8)  and 0xFF) / 255f - 0.456f) / 0.224f
            data[bOff + i] = ((px and 0xFF)          / 255f - 0.406f) / 0.225f
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1L, 3L, size.toLong(), size.toLong()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractSaliencyMap(value: Any, size: Int): Array<FloatArray> = when (value) {
        is Array<*> -> (value as Array<Array<Array<FloatArray>>>)[0][0]
        is FloatArray -> Array(size) { row -> FloatArray(size) { col -> value[row * size + col] } }
        else -> Array(size) { FloatArray(size) }
    }

    private fun bilinearUpscale(mask: Array<FloatArray>, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val result = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val sx = x * (srcW - 1).toFloat() / (dstW - 1)
                val sy = y * (srcH - 1).toFloat() / (dstH - 1)
                val x0 = sx.toInt().coerceIn(0, srcW - 2); val y0 = sy.toInt().coerceIn(0, srcH - 2)
                val fx = sx - x0; val fy = sy - y0
                result[y * dstW + x] =
                    mask[y0][x0] * (1-fx)*(1-fy) + mask[y0][x0+1] * fx*(1-fy) +
                    mask[y0+1][x0] * (1-fx)*fy   + mask[y0+1][x0+1] * fx*fy
            }
        }
        return result
    }

    // ── Pixel adjustments (masked) ────────────────────────────────────────────

    private fun applyMaskedAdjustments(
        bitmap: Bitmap,
        alpha: FloatArray,
        subjectPop: Float,
        bgBrightness: Float,
        subjectTemperature: Float = 0f,
        backgroundTemperature: Float = 0f,
        subjectTint: Float = 0f,
        backgroundTint: Float = 0f,
    ): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(pixels.size)
        // Temperature: warm = +R −B, cool = −R +B. Max shift ≈ 30 levels at |temp|=1.
        val MAX_TEMP_SHIFT = 30
        // Tint: green-magenta axis. Positive=magenta (+R,−G,+B), negative=green (−R,+G,−B).
        val MAX_TINT_SHIFT = 20
        for (i in pixels.indices) {
            val a = alpha[i].coerceIn(0f, 1f); val bg = 1f - a
            val px = pixels[i]
            var r = (px shr 16) and 0xFF; var g = (px shr 8) and 0xFF; var b = px and 0xFF
            if (subjectPop != 0f) {
                val factor = 1f + subjectPop * 0.5f * a
                r = (r * factor).toInt().coerceIn(0, 255)
                g = (g * factor).toInt().coerceIn(0, 255)
                b = (b * factor).toInt().coerceIn(0, 255)
            }
            if (bgBrightness != 0f) {
                val factor = 1f + bgBrightness * 0.5f * bg
                r = (r * factor).toInt().coerceIn(0, 255)
                g = (g * factor).toInt().coerceIn(0, 255)
                b = (b * factor).toInt().coerceIn(0, 255)
            }
            // Subject temperature shift (weighted by subject mask)
            if (subjectTemperature != 0f && a > 0f) {
                val shift = (subjectTemperature * MAX_TEMP_SHIFT * a + 0.5f).toInt()
                r = (r + shift).coerceIn(0, 255)
                b = (b - shift).coerceIn(0, 255)
            }
            // Background temperature shift (weighted by background mask)
            if (backgroundTemperature != 0f && bg > 0f) {
                val shift = (backgroundTemperature * MAX_TEMP_SHIFT * bg + 0.5f).toInt()
                r = (r + shift).coerceIn(0, 255)
                b = (b - shift).coerceIn(0, 255)
            }
            // Subject tint shift (weighted by subject mask)
            // Positive tint = magenta (+R, −G, +B); negative tint = green (−R, +G, −B)
            if (subjectTint != 0f && a > 0f) {
                val shift = (subjectTint * MAX_TINT_SHIFT * a + 0.5f).toInt()
                r = (r + shift).coerceIn(0, 255)
                g = (g - shift).coerceIn(0, 255)
                b = (b + shift).coerceIn(0, 255)
            }
            // Background tint shift (weighted by background mask)
            if (backgroundTint != 0f && bg > 0f) {
                val shift = (backgroundTint * MAX_TINT_SHIFT * bg + 0.5f).toInt()
                r = (r + shift).coerceIn(0, 255)
                g = (g - shift).coerceIn(0, 255)
                b = (b + shift).coerceIn(0, 255)
            }
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun applyUniformAdjustment(bitmap: Bitmap, subjectPop: Float): Bitmap {
        if (subjectPop == 0f) return bitmap
        val factor = 1f + subjectPop * 0.5f
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (((px shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val g = (((px shr 8)  and 0xFF) * factor).toInt().coerceIn(0, 255)
            val b = ((px and 0xFF) * factor).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ── U2Net post-processing ─────────────────────────────────────────────────

    private fun fuseOutputs(primary: Array<FloatArray>, side: Array<FloatArray>, size: Int): Array<FloatArray> =
        Array(size) { y -> FloatArray(size) { x -> maxOf(primary[y][x], side[y][x] * 0.55f) } }

    private fun postProcessMask(pixels: IntArray, mask: FloatArray, w: Int, h: Int): FloatArray =
        morphologicalClose(mask, w, h, radius = 6)

    private fun morphologicalClose(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray =
        erodeMin(dilateMax(mask, w, h, radius), w, h, radius)

    private fun dilateMax(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask
        val temp = FloatArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var max = 0f
            for (nx in (x-radius).coerceAtLeast(0)..(x+radius).coerceAtMost(width-1)) { val v = mask[y*width+nx]; if (v > max) max = v }
            temp[y*width+x] = max
        }
        val result = FloatArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var max = 0f
            for (ny in (y-radius).coerceAtLeast(0)..(y+radius).coerceAtMost(height-1)) { val v = temp[ny*width+x]; if (v > max) max = v }
            result[y*width+x] = max
        }
        return result
    }

    private fun erodeMin(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask
        val temp = FloatArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var min = 1f
            for (nx in (x-radius).coerceAtLeast(0)..(x+radius).coerceAtMost(width-1)) { val v = mask[y*width+nx]; if (v < min) min = v }
            temp[y*width+x] = min
        }
        val result = FloatArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var min = 1f
            for (ny in (y-radius).coerceAtLeast(0)..(y+radius).coerceAtMost(height-1)) { val v = temp[ny*width+x]; if (v < min) min = v }
            result[y*width+x] = min
        }
        return result
    }

    /** Bilinear upscale of a flat float mask from srcW×srcH to dstW×dstH. */
    private fun bilinearUpscaleFlat(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val result = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val sx = x * (srcW - 1).toFloat() / (dstW - 1)
                val sy = y * (srcH - 1).toFloat() / (dstH - 1)
                val x0 = sx.toInt().coerceIn(0, srcW - 2); val y0 = sy.toInt().coerceIn(0, srcH - 2)
                val fx = sx - x0; val fy = sy - y0
                result[y*dstW+x] =
                    src[y0*srcW+x0]     * (1-fx)*(1-fy) + src[y0*srcW+x0+1]     * fx*(1-fy) +
                    src[(y0+1)*srcW+x0] * (1-fx)*fy     + src[(y0+1)*srcW+x0+1] * fx*fy
            }
        }
        return result
    }

    // ── RGB ↔ HSL helpers ────────────────────────────────────────────────────

    /**
     * Converts linear RGB [0,1] to HSL.
     * Returns [hue (0..360), saturation (0..1), lightness (0..1)].
     */
    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val cMax = max(r, max(g, b))
        val cMin = min(r, min(g, b))
        val delta = cMax - cMin
        val l = (cMax + cMin) / 2f

        val s = if (delta == 0f) 0f
                else delta / (1f - abs(2f * l - 1f))

        val h = when {
            delta == 0f -> 0f
            cMax == r   -> 60f * (((g - b) / delta) % 6f)
            cMax == g   -> 60f * (((b - r) / delta) + 2f)
            else        -> 60f * (((r - g) / delta) + 4f)
        }

        return floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    /**
     * Converts HSL [hue (0..360), saturation (0..1), lightness (0..1)] to RGB [0,1].
     * Returns [r, g, b] each in [0,1].
     */
    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) return floatArrayOf(l, l, l)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f  -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else     -> Triple(c, 0f, x)
        }
        return floatArrayOf(
            (r1 + m).coerceIn(0f, 1f),
            (g1 + m).coerceIn(0f, 1f),
            (b1 + m).coerceIn(0f, 1f),
        )
    }

    /** Smoothstep from 0 at [lo] to 1 at [hi]. Used to fade per-hue HSL off greys. */
    private fun smoothstep01(lo: Float, hi: Float, x: Float): Float {
        if (hi <= lo) return if (x >= hi) 1f else 0f
        val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

@Composable
fun rememberAiLutProcessor(): AiLutProcessor {
    val context = LocalContext.current
    val processor = remember(context) { AiLutProcessor(context) }
    DisposableEffect(processor) {
        onDispose { processor.close() }
    }
    return processor
}
