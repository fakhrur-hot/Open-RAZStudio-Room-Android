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

class ZeroDceProcessor(private val context: Context) {

    val hasModel: Boolean = runCatching {
        context.assets.open("models/zero_dce.onnx").close(); true
    }.getOrDefault(false)

    private val sessionLazy: Lazy<OrtSession?> = lazy {
        runCatching {
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open("models/zero_dce.onnx").use { it.readBytes() }
            env.createSession(bytes, buildAiSessionOptions())
        }.getOrNull()
    }
    private val session: OrtSession? by sessionLazy
    private val gate = OrtSessionGate("ZeroDceProcessor")

    fun close() {
        gate.release {
            if (sessionLazy.isInitialized()) runCatching { session?.close() }
        }
    }

    /**
     * Runs Zero-DCE on [bitmap] and returns the exposure-corrected result.
     * [strength] blends between original (0) and fully enhanced (1).
     * Image is downscaled to ≤256px before inference and result is upscaled back.
     * Alpha channel is preserved from the original pixels.
     */
    suspend fun enhance(bitmap: Bitmap, strength: Float = 1f): Bitmap =
        withContext(Dispatchers.IO) {
            if (strength <= 0f || !hasModel || gate.isReleased) return@withContext bitmap
            gate.run {
            val sess = session ?: return@run bitmap

            val w = bitmap.width; val h = bitmap.height
            val maxDim = 256
            val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
            val sw = (w * scale).toInt().coerceAtLeast(1)
            val sh = (h * scale).toInt().coerceAtLeast(1)

            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, sw, sh, true) else bitmap
            val pixels = IntArray(sw * sh)
            scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            if (scale < 1f) scaled.recycle()

            val n = sw * sh
            // NCHW [1, 3, H, W] float32 [0..1] — alpha excluded from model input
            val data = FloatArray(3 * n)
            for (i in 0 until n) {
                val px = pixels[i]
                data[i]         = ((px shr 16) and 0xFF) / 255f
                data[n + i]     = ((px shr 8)  and 0xFF) / 255f
                data[2 * n + i] = ( px         and 0xFF) / 255f
            }

            val env = OrtEnvironment.getEnvironment()
            val inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(data),
                longArrayOf(1L, 3L, sh.toLong(), sw.toLong())
            )
            val outputs = runCatching {
                sess.run(mapOf(sess.inputNames.first() to inputTensor))
            }.getOrNull()
            if (outputs == null) {
                inputTensor.close()
                return@run bitmap
            }
            inputTensor.close()

            // Extract value and close outputs immediately to free native memory
            val rawOut = runCatching { outputs[0].value }.getOrElse {
                outputs.close()
                return@run bitmap
            }
            outputs.close()

            // Flatten to float[] — auto-detect channel count from array[B][C][H][W]
            val outFlat: FloatArray = when (rawOut) {
                is FloatArray -> rawOut
                is Array<*>  -> flattenNchw(rawOut, sh, sw)
                else         -> return@run bitmap
            }

            // Zero-DCE can output:
            //   - 3 channels  → direct enhanced image
            //   - 24 channels → 8 iterations × 3 channel curve params (apply iteratively)
            val enhRgb: FloatArray = when {
                outFlat.size >= 24 * n -> applyCurveParams(data, outFlat, n)
                outFlat.size >=  3 * n -> FloatArray(3 * n) { i -> outFlat[i].coerceIn(0f, 1f) }
                else                   -> return@run bitmap
            }

            // Rebuild bitmap at scaled size, preserving original alpha channel
            val enhPixels = IntArray(n)
            for (i in 0 until n) {
                val r = (enhRgb[i]         * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (enhRgb[n + i]     * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (enhRgb[2 * n + i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                enhPixels[i] = (pixels[i] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
            val enhSmall = Bitmap.createBitmap(enhPixels, sw, sh, Bitmap.Config.ARGB_8888)
            val enhanced = if (scale < 1f) {
                Bitmap.createScaledBitmap(enhSmall, w, h, true).also { enhSmall.recycle() }
            } else enhSmall

            if (strength >= 1f) enhanced
            else blendBitmaps(bitmap, enhanced, strength)
            } ?: bitmap
        }

    // Zero-DCE curve formula: img = img + A·img·(1−img)  applied 8 times
    private fun applyCurveParams(img: FloatArray, params: FloatArray, n: Int): FloatArray {
        val out = img.copyOf()
        for (iter in 0 until 8) {
            for (c in 0..2) {
                val aOff   = (iter * 3 + c) * n
                val imgOff = c * n
                for (i in 0 until n) {
                    val a = params[aOff + i]
                    val x = out[imgOff + i]
                    out[imgOff + i] = (x + a * x * (1f - x)).coerceIn(0f, 1f)
                }
            }
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

    private fun blendBitmaps(orig: Bitmap, enh: Bitmap, strength: Float): Bitmap {
        val w = orig.width; val h = orig.height
        val op = IntArray(w * h); val ep = IntArray(w * h)
        orig.getPixels(op, 0, w, 0, 0, w, h)
        enh.getPixels(ep, 0, w, 0, 0, w, h)
        val result = IntArray(w * h)
        val inv = 1f - strength
        for (i in op.indices) {
            val r = (((op[i] shr 16) and 0xFF) * inv + ((ep[i] shr 16) and 0xFF) * strength + 0.5f).toInt().coerceIn(0, 255)
            val g = (((op[i] shr 8)  and 0xFF) * inv + ((ep[i] shr 8)  and 0xFF) * strength + 0.5f).toInt().coerceIn(0, 255)
            val b = (( op[i]         and 0xFF) * inv + ( ep[i]         and 0xFF) * strength + 0.5f).toInt().coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }
}

@Composable
fun rememberZeroDceProcessor(): ZeroDceProcessor {
    val context = LocalContext.current
    val processor = remember(context) { ZeroDceProcessor(context) }
    DisposableEffect(processor) { onDispose { processor.close() } }
    return processor
}
