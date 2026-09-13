/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW v3 — BiRefNet-lite subject + edge segmentation.
 *
 *  Replaces the previous U2Net-based processor. BiRefNet-lite (Swin-V1-Tiny
 *  backbone, rembg export, 213 MB) produces substantially cleaner subject
 *  boundaries — especially on hair, fine fabric edges, and complex
 *  background/foreground transitions — compared to U2Net's salient-object
 *  output, which was trained for saliency ranking rather than dichotomous
 *  foreground/background separation.
 *
 *  Model: assets/models/birefnet_lite.onnx
 *    Input  "input_image"  : [1, 3, 1024, 1024] float32  NCHW
 *                            ImageNet-normalised  (mean 0.485/0.456/0.406,
 *                                                 std  0.229/0.224/0.225)
 *    Output "output_image" : [1, 1, 1024, 1024] float32  sigmoid [0..1]
 *
 *  The output alpha map is downsampled to 320×320 (RawV3SegmentationMasks
 *  .MASK_SIZE) via bilinear interpolation, then sharpened and morphologically
 *  closed — the same post-processing as the previous U2Net path. Sobel edge
 *  detection runs on the same 320×320 scaled bitmap and is unchanged.
 *
 *  ── Threading ──
 *  Identical to the previous U2Net processor: single MIN_PRIORITY daemon
 *  thread shared across all coordinator instances.
 *
 *  ── Session lifecycle ──
 *  Lazy initialisation on first [compute] call (~2-5 s for the 213 MB model).
 *  Call [release] when the owning coordinator closes.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.OrtSessionGate
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.sqrt

internal class RawV3SegmentationProcessor(private val context: Context) {

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
                .also { Log.i(TAG, "BiRefNet-lite OrtSession created") }
        }.getOrElse { e -> Log.e(TAG, "Session init failed: ${e.message}"); null }
    }
    private val session: OrtSession? by sessionLazy

    private val gate = OrtSessionGate(TAG)

    /**
     * Compute subject + edge masks for [bitmap] at 320×320. Returns null
     * when the model is missing or [release] has already been called.
     */
    suspend fun compute(bitmap: Bitmap): RawV3SegmentationMasks? {
        if (gate.isReleased || !hasModel) return null
        return withContext(dispatcher) {
            gate.run {
                val size = RawV3SegmentationMasks.MASK_SIZE   // 320

                // BiRefNet inference at the model's fixed 1024² input, then
                // downsample. This pass peaks ~6 GB — the coordinator only calls
                // it when [hasMemoryForBiRefNet]; otherwise a DeepLab person mask
                // is used as the subject fallback.
                val scaledIn  = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
                val alphaIn   = try {
                    runBiRefNet(scaledIn)
                } finally {
                    scaledIn.recycle()
                }

                // Bilinear downsample INPUT_SIZE→320 for the mask arrays.
                val subject = bilinearDownsample(alphaIn, INPUT_SIZE, INPUT_SIZE, size, size)

                // Soft sigmoid (k=4): rembg-style soft alpha for Bloom/Bokeh
                // feathering. k=8 previously crushed the matte toward binary and
                // threw away hair/fabric edge quality before guided-filter refine.
                val boosted = FloatArray(size * size) { i ->
                    val shifted = (subject[i] - 0.5f) * 4f
                    (1f / (1f + kotlin.math.exp(-shifted.toDouble()))).toFloat()
                }

                // Morph close: fill ≤2px gaps without bloating hair/fabric edges.
                val closed = morphologicalClose(boosted, size, size, radius = 2)

                // Keep a soft 1024² alpha (sigmoid only, no morph) as the native-
                // res matte. The coordinator remaps it onto Stage A luma aspect
                // via guided filter when needed; this avoids the old
                // 1024→320→1024 quality round-trip when the refine path can use
                // the model-native soft edge directly as a refine seed.
                val soft1024 = FloatArray(INPUT_SIZE * INPUT_SIZE) { i ->
                    val shifted = (alphaIn[i] - 0.5f) * 4f
                    (1f / (1f + kotlin.math.exp(-shifted.toDouble()))).toFloat()
                }

                // Sobel edge: computed on a 320-sized bitmap for consistency
                // with the rest of the pipeline. We scale the source bitmap
                // (not alpha1024) so the edge map captures real photo edges,
                // not the model's soft boundary.
                val scaled320 = Bitmap.createScaledBitmap(bitmap, size, size, true)
                val edge = try {
                    computeSobel(scaled320, size)
                } finally {
                    scaled320.recycle()
                }

                RawV3SegmentationMasks(
                    subjectMask = closed,
                    edgeMask = edge,
                    // Seed refined* with the soft 1024² model output. Aspect is
                    // square (BiRefNet input stretch); the coordinator's guided-
                    // filter pass replaces this with an image-aspect refine when
                    // Stage A luma is available.
                    refinedMask = soft1024,
                    refinedWidth = INPUT_SIZE,
                    refinedHeight = INPUT_SIZE,
                )
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
                Log.d(TAG, "BiRefNet-lite OrtSession released")
            }
        }
    }

    // ── BiRefNet-lite inference ──────────────────────────────────────────────

    private fun runBiRefNet(bitmap1024: Bitmap): FloatArray {
        val s = session ?: error("OrtSession unavailable")

        val env   = OrtEnvironment.getEnvironment()
        val input = bitmapToNchwTensor(bitmap1024, env, INPUT_SIZE)

        val output = s.run(mapOf(INPUT_NAME to input))
        input.close()

        // Output shape: [1, 1, 1024, 1024] — already sigmoid-activated.
        // Use index 0 (not named get) — named access returns Optional<OnnxValue>.
        return extractAlpha(output[0].value, INPUT_SIZE)
    }

    private fun bitmapToNchwTensor(bitmap: Bitmap, env: OrtEnvironment, size: Int): OnnxTensor {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        // NCHW float32, ImageNet normalisation.
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
            longArrayOf(1L, 3L, size.toLong(), size.toLong()),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractAlpha(value: Any, size: Int): FloatArray = when (value) {
        // [1][1][H][W] nested arrays
        is Array<*> -> {
            val plane = (value as Array<Array<Array<FloatArray>>>)[0][0]
            FloatArray(size * size) { i -> plane[i / size][i % size].coerceIn(0f, 1f) }
        }
        is FloatArray -> FloatArray(value.size) { i -> value[i].coerceIn(0f, 1f) }
        else -> FloatArray(size * size)
    }

    // ── Bilinear downsample ──────────────────────────────────────────────────

    private fun bilinearDownsample(
        src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int,
    ): FloatArray {
        val out = FloatArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        for (ty in 0 until dstH) {
            val sy  = (ty + 0.5f) * yRatio - 0.5f
            val sy0 = sy.toInt().coerceIn(0, srcH - 1)
            val sy1 = (sy0 + 1).coerceAtMost(srcH - 1)
            val fy  = (sy - sy0).coerceIn(0f, 1f)
            val tRow = ty * dstW
            for (tx in 0 until dstW) {
                val sx  = (tx + 0.5f) * xRatio - 0.5f
                val sx0 = sx.toInt().coerceIn(0, srcW - 1)
                val sx1 = (sx0 + 1).coerceAtMost(srcW - 1)
                val fx  = (sx - sx0).coerceIn(0f, 1f)
                val v00 = src[sy0 * srcW + sx0]
                val v10 = src[sy0 * srcW + sx1]
                val v01 = src[sy1 * srcW + sx0]
                val v11 = src[sy1 * srcW + sx1]
                out[tRow + tx] = v00 * (1 - fx) * (1 - fy) +
                                  v10 * fx * (1 - fy) +
                                  v01 * (1 - fx) * fy +
                                  v11 * fx * fy
            }
        }
        return out
    }

    // ── Morphological close (dilate-max → erode-min) ────────────────────────

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

    // ── Sobel edge mask ─────────────────────────────────────────────────────

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

    // ── Session options ─────────────────────────────────────────────────────

    private fun buildSessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            // Multi-thread the CPU inference. BiRefNet-lite is conv-heavy and
            // parallelizes near-linearly across intra-op threads; the previous
            // single-thread config was the dominant cost (~42 s at 1024²). Leave
            // 2 cores for the UI/GL thread. Thread scratch buffers are small and
            // unrelated to the activation arena guarded below, so this does NOT
            // change the memory posture. interOp stays 1 (single dataflow branch).
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(4, 8)
            runCatching { setIntraOpNumThreads(threads) }
            runCatching { setInterOpNumThreads(1) }
            runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
            // Memory: BiRefNet's 1024² pass allocates ~4.5 GB of activations that
            // ONNX's CPU arena would otherwise CACHE (not return to the OS) after
            // close(), leaving the app a lmkd kill target long after the mask is
            // done. Disable the arena + memory-pattern pre-allocation so that
            // memory is freed on close(). Slightly slower per run; worth it here.
            runCatching { setMemoryPatternOptimization(false) }
            runCatching { setCPUArenaAllocator(false) }
        }

    companion object {
        private const val TAG          = "RawV3.BiRefNet"
        private const val MODEL_ASSET  = "models/birefnet_lite.onnx"
        private const val INPUT_NAME   = "input_image"
        private const val OUTPUT_NAME  = "output_image"
        // Model input is a FIXED [1,3,1024,1024] tensor — ORT rejects any other
        // size (verified: "Got: 512 Expected: 1024"), so this cannot be lowered
        // to save memory. A 1024² pass peaks ~6 GB; RawV3Coordinator gates it by
        // available RAM and falls back to a DeepLab person mask when too low.
        private const val INPUT_SIZE   = 1024

        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "raw-v3-seg-birefnet").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }
}
