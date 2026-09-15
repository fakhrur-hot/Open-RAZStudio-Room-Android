/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * RAW v3 — Depth-Anything-V2-Small relative depth (step 1 of depth-aware bokeh).
 *
 * Ship ONLY the Small weights (Apache-2.0). Base / Large / Giant are
 * CC-BY-NC-4.0 and must not be bundled.
 *
 * Model: assets/models/depth_anything_v2_vits.onnx
 *   (fabio-sim Depth-Anything-ONNX v2.0.0 export of Depth-Anything-V2-Small)
 *   Input  : [1, 3, 518, 518] float32 NCHW, ImageNet mean/std
 *            (518 % 14 == 0 — ViT patch constraint)
 *   Output : [1, 518, 518] (or [1,1,H,W]) relative depth → min-max to [0,1]
 *            then bilinear upsample to MASK_SIZE (320) for GL/CPU consumers
 *
 * Absent model → hasModel=false; compute returns null. Never crashes.
 *
 * Runs on its own MIN_PRIORITY dispatcher so it can sit beside BiRefNet-lite
 * (independent OrtSession, independent resize) — c-shop sidecar pattern.
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

internal class RawV3DepthProcessor(private val context: Context) {

    private val modelAvailable: Boolean by lazy {
        runCatching { context.assets.open(MODEL_ASSET).close(); true }.getOrDefault(false)
    }

    val hasModel: Boolean get() = modelAvailable

    private val sessionLazy: Lazy<OrtSession?> = lazy {
        runCatching {
            if (!modelAvailable) return@runCatching null
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            env.createSession(bytes, buildSessionOptions())
                .also { Log.i(TAG, "Depth-Anything-V2-Small OrtSession created") }
        }.getOrElse { e -> Log.e(TAG, "Session init failed: ${e.message}"); null }
    }
    private val session: OrtSession? by sessionLazy

    private val gate = OrtSessionGate(TAG)

    /**
     * Relative depth for [bitmap], normalised to [0,1] at MASK_SIZE.
     * Null when model missing / released / soft-fail.
     */
    suspend fun compute(bitmap: Bitmap): RawV3DepthMap? {
        if (gate.isReleased || !hasModel) return null
        return withContext(dispatcher) {
            gate.run {
                val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
                val raw = try {
                    runDepth(scaled)
                } finally {
                    scaled.recycle()
                }
                val size = RawV3SegmentationMasks.MASK_SIZE
                val down = bilinearDownsample(raw, INPUT_SIZE, INPUT_SIZE, size, size)
                RawV3DepthMap(depth = down, width = size, height = size, modelAsset = MODEL_ASSET)
            }
        }
    }

    fun release() {
        gate.release {
            if (sessionLazy.isInitialized()) {
                runCatching { session?.close() }
                    .onSuccess { Log.d(TAG, "Depth-Anything OrtSession released") }
            }
        }
    }

    private fun runDepth(bitmap518: Bitmap): FloatArray {
        val s = session ?: error("OrtSession unavailable")
        val env = OrtEnvironment.getEnvironment()
        val input = bitmapToNchwTensor(bitmap518, env, INPUT_SIZE)

        // Prefer known export names; fall back to first input/output.
        val inName = s.inputNames.firstOrNull {
            it == INPUT_NAME || it == "pixel_values" || it == "image"
        } ?: s.inputNames.first()
        val output = s.run(mapOf(inName to input))
        input.close()
        return extractAndNormalize(output[0].value, INPUT_SIZE)
    }

    private fun bitmapToNchwTensor(bitmap: Bitmap, env: OrtEnvironment, size: Int): OnnxTensor {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val data = FloatArray(3 * size * size)
        val gOff = size * size
        val bOff = 2 * size * size
        // Bitmap is already ARGB; channel order RGB after ImageNet norm
        // (Android getPixels is not BGR — scout "bgr→rgb" applies to OpenCV loads).
        for (i in pixels.indices) {
            val px = pixels[i]
            data[i] = (((px shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
            data[gOff + i] = (((px shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
            data[bOff + i] = ((px and 0xFF) / 255f - 0.406f) / 0.225f
        }
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1L, 3L, size.toLong(), size.toLong()),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractAndNormalize(value: Any, size: Int): FloatArray {
        val flat = when (value) {
            is Array<*> -> flattenDepthArray(value, size)
            is FloatArray -> value.copyOf()
            else -> FloatArray(size * size)
        }
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (v in flat) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val span = (max - min).coerceAtLeast(1e-6f)
        return FloatArray(flat.size) { i -> ((flat[i] - min) / span).coerceIn(0f, 1f) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenDepthArray(value: Array<*>, size: Int): FloatArray {
        // Common shapes: [1][H][W] or [1][1][H][W]
        return try {
            when (val a0 = value[0]) {
                is Array<*> -> when (val a1 = a0[0]) {
                    is FloatArray -> {
                        // [1][H][W]
                        val out = FloatArray(size * size)
                        for (y in 0 until size) {
                            val row = a0[y] as FloatArray
                            System.arraycopy(row, 0, out, y * size, size)
                        }
                        out
                    }
                    is Array<*> -> {
                        // [1][1][H][W]
                        val plane = a0[0] as Array<FloatArray>
                        FloatArray(size * size) { i -> plane[i / size][i % size] }
                    }
                    else -> FloatArray(size * size)
                }
                is FloatArray -> a0
                else -> FloatArray(size * size)
            }
        } catch (_: Throwable) {
            FloatArray(size * size)
        }
    }

    private fun bilinearDownsample(
        src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int,
    ): FloatArray {
        val out = FloatArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        for (ty in 0 until dstH) {
            val sy = (ty + 0.5f) * yRatio - 0.5f
            val sy0 = sy.toInt().coerceIn(0, srcH - 1)
            val sy1 = (sy0 + 1).coerceAtMost(srcH - 1)
            val fy = (sy - sy0).coerceIn(0f, 1f)
            val tRow = ty * dstW
            for (tx in 0 until dstW) {
                val sx = (tx + 0.5f) * xRatio - 0.5f
                val sx0 = sx.toInt().coerceIn(0, srcW - 1)
                val sx1 = (sx0 + 1).coerceAtMost(srcW - 1)
                val fx = (sx - sx0).coerceIn(0f, 1f)
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

    private fun buildSessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            runCatching { setIntraOpNumThreads(threads) }
            runCatching { setInterOpNumThreads(1) }
            runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
            runCatching { setMemoryPatternOptimization(false) }
            runCatching { setCPUArenaAllocator(false) }
        }

    companion object {
        private const val TAG = "RawV3.DepthAnything"
        const val MODEL_ASSET = "models/depth_anything_v2_vits.onnx"
        private const val INPUT_NAME = "image"
        // 518 % 14 == 0 (ViT-S patch). Do not change without matching export.
        private const val INPUT_SIZE = 518

        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "raw-v3-depth-dav2").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }
}
