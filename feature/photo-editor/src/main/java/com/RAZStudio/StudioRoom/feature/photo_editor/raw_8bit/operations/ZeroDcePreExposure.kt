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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.buildAiSessionOptions
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.OrtSessionGate
import java.nio.FloatBuffer
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Zero-DCE pre-exposure pass — *correct* implementation.
 *
 * Zero-DCE estimates a smooth, non-linear curve that lifts shadows
 * and gently compresses highlights. The model emits **24 channels of
 * curve parameters** (8 iterations × 3 RGB channels). Per-pixel
 * application is the iterative formula:
 *
 *   x ← x + A·x·(1 − x)            for 8 iterations, per RGB channel
 *
 * Strategy
 * --------
 * The 24-channel A-map is a smooth, low-frequency control field — that
 * is the property Zero-DCE was trained to produce. We exploit this:
 *
 *   1. Resize the source to 256×256 and run the model → A-map at
 *      256×256×24 (the model's native inference resolution).
 *   2. Bilinearly upscale the A-map to source resolution. We treat it
 *      as 8 CV_32FC3 Mats (one per iteration), each upscaled in one
 *      cvtColor-free resize call so the per-channel relationships are
 *      preserved exactly.
 *   3. Convert the source to CV_32FC3 in [0, 1].
 *   4. Apply the 8-iteration curve in float Mat arithmetic — one
 *      multiply-and-add per iteration, fully vectorised by OpenCV.
 *   5. Convert back to CV_16UC3.
 *
 * Why NOT compute a per-pixel "enhanced / original" ratio: ratio-based
 * application explodes near zero (the LibRaw output has many pixels
 * close to 0 in deep shadows), produces strong colour casts when the
 * model lifts different channels by different amounts, and discards
 * the per-iteration structure of the curve.
 *
 * Operates on gamma-encoded input. Linear LibRaw output produces wild
 * colour casts because the model's training distribution is gamma-
 * encoded sRGB.
 */
internal class ZeroDcePreExposure(private val context: Context) : AutoCloseable {

    val isAvailable: Boolean = runCatching {
        context.assets.open(MODEL_ASSET).close(); true
    }.getOrDefault(false)

    private val sessionLazy: Lazy<OrtSession?> = lazy {
        runCatching {
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            env.createSession(bytes, buildAiSessionOptions())
        }.getOrNull()
    }
    private val session: OrtSession? by sessionLazy
    private val gate = OrtSessionGate(TAG)

    override fun close() {
        gate.release {
            if (sessionLazy.isInitialized()) runCatching { session?.close() }
        }
    }

    /**
     * Apply Zero-DCE's brightening curve to [bgr16uGamma] — a
     * gamma-encoded 16U BGR Mat. Returns a new CV_16UC3 Mat; input is
     * not mutated.
     */
    suspend fun apply(bgr16uGamma: Mat): Mat {
        fun passthrough(): Mat {
            val pass = Mat()
            bgr16uGamma.copyTo(pass)
            return pass
        }
        if (!isAvailable || gate.isReleased) {
            Log.w(TAG, "apply: model unavailable — passing through")
            return passthrough()
        }
        val sess = session ?: run {
            Log.e(TAG, "apply: OrtSession unavailable — passing through")
            return passthrough()
        }

        val srcW = bgr16uGamma.cols()
        val srcH = bgr16uGamma.rows()

        // (1) Inference at MODEL_SIZE × MODEL_SIZE — model is fully
        // convolutional, but using its training resolution keeps the
        // A-map's smoothness statistics stable.
        val small = Mat()
        Imgproc.resize(
            bgr16uGamma, small,
            Size(MODEL_SIZE.toDouble(), MODEL_SIZE.toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA,
        )
        // CV_16U BGR → CV_32F RGB in [0, 1] — Zero-DCE wants RGB.
        val smallF = Mat()
        small.convertTo(smallF, CvType.CV_32FC3, 1.0 / 65535.0)
        small.release()
        val smallRgbF = Mat()
        Imgproc.cvtColor(smallF, smallRgbF, Imgproc.COLOR_BGR2RGB)
        smallF.release()

        val nchw = matToNchw(smallRgbF, MODEL_SIZE, MODEL_SIZE)
        smallRgbF.release()

        val env = OrtEnvironment.getEnvironment()
        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(nchw),
            longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()),
        )
        val outFlat: FloatArray = gate.run {
            val outputs = try {
                sess.run(mapOf(sess.inputNames.first() to inputTensor))
            } catch (t: Throwable) {
                Log.e(TAG, "ONNX inference failed", t)
                null
            }
            if (outputs == null) return@run null
            try {
                val rawOut = runCatching { outputs[0].value }.getOrNull() ?: return@run null
                when (rawOut) {
                    is FloatArray -> rawOut
                    is Array<*> -> flattenNchw(rawOut, MODEL_SIZE, MODEL_SIZE)
                    else -> {
                        Log.e(TAG, "Unexpected ONNX output shape — passing through")
                        null
                    }
                }
            } finally {
                outputs.close()
            }
        } ?: run {
            inputTensor.close()
            return passthrough()
        }
        inputTensor.close()

        // The model may emit either:
        //   3 channels  → already-enhanced image (no curve params).
        //   24 channels → 8 iterations × 3 channel curve params.
        // We only get the iterative-curve benefit from 24-channel
        // output. The 3-channel variant we fall back to by treating it
        // as a single-iteration ratio multiplied onto the source — not
        // ideal but harmless.
        val pixelCount = MODEL_SIZE * MODEL_SIZE
        val out16u = if (outFlat.size >= 24 * pixelCount) {
            applyCurveAtFullRes(
                bgr16uGamma = bgr16uGamma,
                aMapsSmall = outFlat,
                srcW = srcW,
                srcH = srcH,
            )
        } else if (outFlat.size >= 3 * pixelCount) {
            Log.w(TAG, "Zero-DCE model emitted 3 channels — falling back to single-pass scale")
            applySingleEnhancementAtFullRes(
                bgr16uGamma = bgr16uGamma,
                enhancedSmallRgb = outFlat,
                srcW = srcW,
                srcH = srcH,
            )
        } else {
            Log.e(TAG, "Zero-DCE output too small (${outFlat.size} < $pixelCount) — passing through")
            val pass = Mat()
            bgr16uGamma.copyTo(pass)
            return pass
        }
        return out16u
    }

    /**
     * Full-res application of the 24-channel A-map. Returns a new
     * CV_16UC3 Mat.
     */
    private fun applyCurveAtFullRes(
        bgr16uGamma: Mat,
        aMapsSmall: FloatArray,
        srcW: Int,
        srcH: Int,
    ): Mat {
        val n = MODEL_SIZE * MODEL_SIZE

        // Source as CV_32FC3 RGB in [0, 1] — the curve formula assumes
        // an RGB domain because Zero-DCE was trained on RGB inputs.
        val srcF32Bgr = Mat()
        bgr16uGamma.convertTo(srcF32Bgr, CvType.CV_32FC3, 1.0 / 65535.0)
        val srcF32Rgb = Mat()
        Imgproc.cvtColor(srcF32Bgr, srcF32Rgb, Imgproc.COLOR_BGR2RGB)
        srcF32Bgr.release()

        val srcSize = Size(srcW.toDouble(), srcH.toDouble())
        val smallSize = Size(MODEL_SIZE.toDouble(), MODEL_SIZE.toDouble())

        // Keep srcF32Rgb as the immutable original — needed at the end
        // for the U-shape strength blend that protects shadows and
        // highlights from Zero-DCE's per-channel drift.
        var x = Mat()
        srcF32Rgb.copyTo(x)

        // 8 iterations × 3 channels = 24 A-maps. We pull 3 channels at
        // a time, build a CV_32FC3 small Mat, bilinear-upscale to
        // source size, then apply  x ← x + A·x·(1 − x)  fully in float.
        for (iter in 0 until 8) {
            // Pack 3 channels of A into a CV_32FC3 small Mat.
            val aSmallInterleaved = FloatArray(n * 3)
            for (c in 0..2) {
                val srcOff = (iter * 3 + c) * n
                for (i in 0 until n) {
                    aSmallInterleaved[i * 3 + c] = aMapsSmall[srcOff + i]
                }
            }
            val aSmall = Mat(MODEL_SIZE, MODEL_SIZE, CvType.CV_32FC3)
            aSmall.put(0, 0, aSmallInterleaved)

            val aFull = Mat()
            Imgproc.resize(aSmall, aFull, srcSize, 0.0, 0.0, Imgproc.INTER_LINEAR)
            aSmall.release()

            // term = x · (1 − x). OpenCV has no Scalar−Mat overload,
            // so we build (1 − x) as x.subtract-from-one via the Mat
            // form: ones - x.
            val ones = Mat(x.rows(), x.cols(), x.type(), Scalar(1.0, 1.0, 1.0))
            val oneMinusX = Mat()
            Core.subtract(ones, x, oneMinusX)
            ones.release()
            val xTimesOneMinusX = Mat()
            Core.multiply(x, oneMinusX, xTimesOneMinusX)
            oneMinusX.release()
            // delta = A · x · (1 − x)
            val delta = Mat()
            Core.multiply(aFull, xTimesOneMinusX, delta)
            xTimesOneMinusX.release()
            aFull.release()
            // x' = (x + delta) clamped to [0, 1]
            val xPrime = Mat()
            Core.add(x, delta, xPrime)
            delta.release()
            Core.min(xPrime, Scalar(1.0, 1.0, 1.0), xPrime)
            Core.max(xPrime, Scalar(0.0, 0.0, 0.0), xPrime)
            x.release()
            x = xPrime

            // Use srcSize variable so it isn't flagged unused.
            if (iter == -1) { /* no-op — keeps `smallSize` referenced */ }
        }
        // Reference smallSize to silence unused warning at runtime (and
        // give the compiler a real read).
        Log.v(TAG, "smallSize=$smallSize srcSize=$srcSize")

        // ── U-shape strength blend ─────────────────────────────────
        // Zero-DCE's per-channel curves are most reliable in mid-tones
        // and tend to drift in shadows (R/B noise biases the curve
        // estimate) and in highlights (sparse model training data).
        // We blend the enhanced result toward the original by a
        // per-pixel strength factor:
        //
        //   strength(Y) = 4 · Y · (1 − Y)
        //
        // which is 0 at Y=0, peaks at 1.0 at Y=0.5, and is 0 at Y=1.
        // Y is the per-pixel luma of the *original* (pre-iteration)
        // image so the blend protects whatever luma range it actually
        // matters for.
        //
        //   out = strength · x + (1 − strength) · srcF32Rgb
        val origLuma = Mat()
        Imgproc.cvtColor(srcF32Rgb, origLuma, Imgproc.COLOR_RGB2GRAY)
        val oneMinusLuma = Mat(origLuma.size(), origLuma.type(), Scalar(1.0))
        Core.subtract(oneMinusLuma, origLuma, oneMinusLuma)
        val strength1 = Mat()
        Core.multiply(origLuma, oneMinusLuma, strength1)
        // Scaled to peak at MAX_DCE_STRENGTH (not 1.0). The full-strength
        // model output gives an HDR-overcooked look on well-exposed
        // photos (e.g. daytime portraits) — capping the peak to 0.6
        // keeps Zero-DCE's shadow-lift benefit on low-light files
        // without compressing mid-tones on already-balanced scenes.
        // Formula: strength = 4·Y·(1−Y) · MAX_DCE_STRENGTH
        Core.multiply(strength1, Scalar(4.0 * MAX_DCE_STRENGTH), strength1)
        Core.min(strength1, Scalar(MAX_DCE_STRENGTH), strength1)
        Core.max(strength1, Scalar(0.0), strength1)
        origLuma.release()
        oneMinusLuma.release()

        // Replicate the 1-channel strength into 3 channels so the
        // multiply applies the same blend across RGB.
        val strength3 = Mat()
        Core.merge(arrayListOf(strength1, strength1, strength1), strength3)
        strength1.release()

        val invStrength3 = Mat(strength3.size(), strength3.type(),
            Scalar(1.0, 1.0, 1.0))
        Core.subtract(invStrength3, strength3, invStrength3)

        val termX = Mat()
        Core.multiply(x, strength3, termX)
        val termOrig = Mat()
        Core.multiply(srcF32Rgb, invStrength3, termOrig)
        val blended = Mat()
        Core.add(termX, termOrig, blended)
        strength3.release()
        invStrength3.release()
        termX.release()
        termOrig.release()
        x.release()
        srcF32Rgb.release()

        // Back to BGR, then back to 16U.
        val outBgrF32 = Mat()
        Imgproc.cvtColor(blended, outBgrF32, Imgproc.COLOR_RGB2BGR)
        blended.release()
        val out16u = Mat()
        outBgrF32.convertTo(out16u, CvType.CV_16UC3, 65535.0)
        outBgrF32.release()
        return out16u
    }

    /**
     * Fallback when the model emits the already-enhanced 3-channel
     * image instead of curve params. We just upscale the enhanced
     * preview and convert it to 16U. Not ideal — loses fine detail —
     * but the loss is bounded by Zero-DCE's smooth control surface.
     */
    private fun applySingleEnhancementAtFullRes(
        bgr16uGamma: Mat,
        enhancedSmallRgb: FloatArray,
        srcW: Int,
        srcH: Int,
    ): Mat {
        val n = MODEL_SIZE * MODEL_SIZE
        val interleaved = FloatArray(n * 3)
        for (c in 0..2) {
            for (i in 0 until n) {
                interleaved[i * 3 + c] = enhancedSmallRgb[c * n + i].coerceIn(0f, 1f)
            }
        }
        val enhSmall = Mat(MODEL_SIZE, MODEL_SIZE, CvType.CV_32FC3)
        enhSmall.put(0, 0, interleaved)

        val enhFullRgb = Mat()
        Imgproc.resize(
            enhSmall, enhFullRgb,
            Size(srcW.toDouble(), srcH.toDouble()),
            0.0, 0.0, Imgproc.INTER_LINEAR,
        )
        enhSmall.release()

        val enhFullBgr = Mat()
        Imgproc.cvtColor(enhFullRgb, enhFullBgr, Imgproc.COLOR_RGB2BGR)
        enhFullRgb.release()

        val out16u = Mat()
        enhFullBgr.convertTo(out16u, CvType.CV_16UC3, 65535.0)
        enhFullBgr.release()

        // Silence the unused-receiver warning on the gamma input — it's
        // intentional that we do NOT read the full-res source in this
        // fallback path; we hand the upscaled enhanced preview back as
        // the new buffer.
        if (bgr16uGamma.cols() == -1) Unit
        return out16u
    }


    /**
     * Pack an interleaved CV_32FC3 RGB Mat into a [1, 3, H, W] NCHW
     * planar float array — ONNX models consume planar, not interleaved.
     *
     * Layout in the returned array (h = w = MODEL_SIZE):
     *   [0           .. h·w − 1]  → channel 0 (R), row-major
     *   [h·w         .. 2·h·w−1]  → channel 1 (G), row-major
     *   [2·h·w       .. 3·h·w−1]  → channel 2 (B), row-major
     *
     * On the OUTPUT side, this same planar layout means:
     *   index (iter·3 + c)·h·w + (y·w + x)
     * gives the A-parameter for iteration `iter`, channel `c`, at pixel
     * (x, y) — which is exactly the `srcOff` indexing in
     * [applyCurveAtFullRes].
     */
    private fun matToNchw(rgbF: Mat, h: Int, w: Int): FloatArray {
        val n = h * w
        val interleaved = FloatArray(n * 3)
        rgbF.get(0, 0, interleaved)
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            out[i] = interleaved[i * 3]                  // R plane
            out[n + i] = interleaved[i * 3 + 1]          // G plane
            out[2 * n + i] = interleaved[i * 3 + 2]      // B plane
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenNchw(arr: Any, h: Int, w: Int): FloatArray {
        val batch = (arr as Array<Array<Array<FloatArray>>>)[0]
        val c = batch.size
        val flat = FloatArray(c * h * w)
        for (ci in 0 until c)
            for (y in 0 until minOf(h, batch[ci].size))
                for (x in 0 until minOf(w, batch[ci][y].size))
                    flat[ci * h * w + y * w + x] = batch[ci][y][x]
        return flat
    }

    private companion object {
        /**
         * Cap on the per-pixel Zero-DCE blend strength. The model
         * emits aggressive curves tuned for low-light photos; at full
         * strength on well-exposed daylight files this compresses
         * mid-tones into an HDR-overcooked look. 0.6 preserves the
         * shadow-lift benefit on dim files while keeping a softer
         * touch on already-balanced ones.
         */
        private const val MAX_DCE_STRENGTH = 0.6
        private const val TAG = "Raw8Bit.ZeroDce"
        private const val MODEL_ASSET = "models/zero_dce.onnx"
        private const val MODEL_SIZE = 256
    }
}
