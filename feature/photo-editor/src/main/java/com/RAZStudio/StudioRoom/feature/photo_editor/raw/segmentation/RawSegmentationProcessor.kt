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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Low-priority U2Net segmentation processor for the RAW editor pipeline.
 *
 * Produces [RawSegmentationMasks] at [RawSegmentationMasks.MASK_SIZE]×[MASK_SIZE] (320×320):
 *   • Subject mask  — U2Net saliency map, morphological-closed, values in [0, 1]
 *   • Edge mask     — Sobel magnitude on the same 320×320 bitmap, normalised to [0, 1]
 *
 * ── Threading ───────────────────────────────────────────────────────────────────
 * All inference runs on [dispatcher]: a single daemon thread at [Thread.MIN_PRIORITY].
 * This ensures the segmentation job never starves the foreground preview pipeline or UI.
 *
 * ── Session lifecycle ───────────────────────────────────────────────────────────
 * The OrtSession is created lazily on first [compute] call (168 MB model; ~1–5 s init).
 * It is reused for every subsequent call on the same file and across files.
 * Call [release] when the owning coordinator is closed to free native memory.
 *
 * ── ONNX options ────────────────────────────────────────────────────────────────
 * intraOpThreads = 1, interOpThreads = 1 — intentionally minimal to stay out of the way.
 * No NNAPI (hangs on MediaTek), no QNN (reserved for interactive workloads).
 */
internal class RawSegmentationProcessor(private val context: Context) {

    // ── Session ──────────────────────────────────────────────────────────────────

    private val modelAvailable: Boolean by lazy {
        runCatching { context.assets.open(MODEL_ASSET).close(); true }.getOrDefault(false)
    }

    val hasModel: Boolean get() = modelAvailable

    private val sessionLazy: Lazy<OrtSession?> = lazy {
        runCatching {
            if (!modelAvailable) return@runCatching null
            val env   = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            env.createSession(bytes, buildSessionOptions())
                .also { Log.i(TAG, "OrtSession created") }
        }.getOrElse { e -> Log.e(TAG, "Session init failed: ${e.message}"); null }
    }
    private val session: OrtSession? by sessionLazy

    private val gate = OrtSessionGate(TAG)

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Compute subject and edge masks for [bitmap] at 320×320.
     * Returns null if the model is unavailable or [release] has already been called.
     * Must be called from a coroutine; switches to [dispatcher] internally.
     */
    suspend fun compute(bitmap: Bitmap): RawSegmentationMasks? {
        if (gate.isReleased || !hasModel) return null
        return withContext(dispatcher) {
            gate.run {
                val size    = RawSegmentationMasks.MASK_SIZE
                val scaled  = Bitmap.createScaledBitmap(bitmap, size, size, true)
                try {
                    val subject = runU2Net(scaled, size)
                    val edge    = computeSobel(scaled, size)
                    RawSegmentationMasks(subjectMask = subject, edgeMask = edge)
                } finally {
                    scaled.recycle()
                }
            }
        }
    }

    /**
     * Release the OrtSession. Safe while [compute] is mid-infer — close is
     * deferred until OrtSession.run returns (see [OrtSessionGate]).
     */
    fun release() {
        gate.release {
            if (sessionLazy.isInitialized()) {
                runCatching { session?.close() }
                Log.d(TAG, "OrtSession released")
            }
        }
    }

    // ── U2Net inference ───────────────────────────────────────────────────────────

    private fun runU2Net(scaled320: Bitmap, size: Int): FloatArray {
        val s = session ?: error("OrtSession unavailable")

        val env   = OrtEnvironment.getEnvironment()
        val input = bitmapToNchwTensor(scaled320, env, size)

        val output = s.run(mapOf(s.inputNames.first() to input))
        input.close()

        val primary = extractSaliency(output[0].value, size)
        val side    = runCatching {
            val n = output.size()
            if (n > 1) extractSaliency(output[n - 1].value, size) else null
        }.getOrNull()

        // Fuse: primary is the main d0 output; side (d6) adds boundary detail at 55%.
        val fused = if (side != null)
            FloatArray(size * size) { i -> maxOf(primary[i], side[i] * 0.55f) }
        else primary

        return morphologicalClose(fused, size, size, radius = 6)
    }

    private fun bitmapToNchwTensor(bitmap: Bitmap, env: OrtEnvironment, size: Int): OnnxTensor {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        // NCHW float32, ImageNet normalisation per channel
        val data = FloatArray(3 * size * size)
        val gOff = size * size
        val bOff = 2 * size * size
        for (i in pixels.indices) {
            val px = pixels[i]
            data[i]        = (((px shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
            data[gOff + i] = (((px shr 8)  and 0xFF) / 255f - 0.456f) / 0.224f
            data[bOff + i] = ((px and 0xFF)          / 255f - 0.406f) / 0.225f
        }
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1L, 3L, size.toLong(), size.toLong())
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractSaliency(value: Any, size: Int): FloatArray = when (value) {
        is Array<*> -> {
            val arr = (value as Array<Array<Array<FloatArray>>>)[0][0]
            FloatArray(size * size) { i -> arr[i / size][i % size] }
        }
        is FloatArray -> value.copyOf()
        else          -> FloatArray(size * size)
    }

    // ── Morphological close (dilate-max → erode-min) ─────────────────────────────

    private fun morphologicalClose(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray =
        erodeMin(dilateMax(mask, w, h, radius), w, h, radius)

    private fun dilateMax(mask: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var mx = 0f
            for (nx in (x - r).coerceAtLeast(0)..(x + r).coerceAtMost(w - 1)) {
                val v = mask[y * w + nx]; if (v > mx) mx = v
            }
            tmp[y * w + x] = mx
        }
        val out = FloatArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var mx = 0f
            for (ny in (y - r).coerceAtLeast(0)..(y + r).coerceAtMost(h - 1)) {
                val v = tmp[ny * w + x]; if (v > mx) mx = v
            }
            out[y * w + x] = mx
        }
        return out
    }

    private fun erodeMin(mask: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var mn = 1f
            for (nx in (x - r).coerceAtLeast(0)..(x + r).coerceAtMost(w - 1)) {
                val v = mask[y * w + nx]; if (v < mn) mn = v
            }
            tmp[y * w + x] = mn
        }
        val out = FloatArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var mn = 1f
            for (ny in (y - r).coerceAtLeast(0)..(y + r).coerceAtMost(h - 1)) {
                val v = tmp[ny * w + x]; if (v < mn) mn = v
            }
            out[y * w + x] = mn
        }
        return out
    }

    // ── Sobel edge mask ───────────────────────────────────────────────────────────

    private fun computeSobel(bitmap: Bitmap, size: Int): FloatArray {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        val gray = FloatArray(size * size) { i ->
            val px = pixels[i]
            (0.299f * ((px shr 16) and 0xFF) +
             0.587f * ((px shr 8)  and 0xFF) +
             0.114f * (px and 0xFF)) / 255f
        }

        val edge   = FloatArray(size * size)
        var maxMag = 0f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val x0 = (x - 1).coerceAtLeast(0);    val x1 = (x + 1).coerceAtMost(size - 1)
                val y0 = (y - 1).coerceAtLeast(0);    val y1 = (y + 1).coerceAtMost(size - 1)
                val gx = -gray[y0*size+x0] + gray[y0*size+x1] -
                          2f*gray[y*size+x0] + 2f*gray[y*size+x1] -
                          gray[y1*size+x0] + gray[y1*size+x1]
                val gy = -gray[y0*size+x0] - 2f*gray[y0*size+x] - gray[y0*size+x1] +
                          gray[y1*size+x0] + 2f*gray[y1*size+x] + gray[y1*size+x1]
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                edge[y * size + x] = mag
                if (mag > maxMag) maxMag = mag
            }
        }
        if (maxMag > 0f) {
            val inv = 1f / maxMag
            for (i in edge.indices) edge[i] = (edge[i] * inv).coerceIn(0f, 1f)
        }
        return edge
    }

    // ── Session options ───────────────────────────────────────────────────────────

    private fun buildSessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            // Single thread: intentionally minimal — this job runs at minimum OS priority.
            // Adding more threads would compete with preview rendering and the UI thread pool.
            runCatching { setIntraOpNumThreads(1) }
            runCatching { setInterOpNumThreads(1) }
            runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
            // No NNAPI (hangs on MediaTek devices).
            // No QNN — reserved for interactive/foreground workloads on Qualcomm devices.
        }

    companion object {
        private const val TAG         = "RawSegProcessor"
        private const val MODEL_ASSET = "models/u2net.onnx"

        /**
         * Single daemon thread at [Thread.MIN_PRIORITY].
         * Shared across all [RawSegmentationProcessor] instances (one per coordinator).
         * The OS scheduler will always prefer any normal-priority thread over this one,
         * making segmentation truly transparent to all interactive work.
         */
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "raw-seg-u2net").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }
}
