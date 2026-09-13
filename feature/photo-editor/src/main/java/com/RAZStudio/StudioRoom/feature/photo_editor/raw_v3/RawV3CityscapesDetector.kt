/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW v3 — Cityscapes 4-class segmenter (SegFormer-B1, Hugging Face).
 *
 *  Runs a remapped-to-4-classes SegFormer-B1 Cityscapes ONNX model:
 *    assets/models/segformer_cityscapes_remap_fp16.onnx
 *
 *  Source model: smp-hub/segformer-b1-1024x1024-city-160k. The user runs an
 *  offline export script that collapses the 19 Cityscapes classes into 4
 *  groups before serialising to ONNX:
 *
 *    0 = Building+Wall   (Cityscapes classes 2, 3)
 *    1 = Vegetation      (Cityscapes class  8)
 *    2 = Terrain         (Cityscapes class  9)
 *    3 = Sky             (Cityscapes class 10)
 *
 *  Each class becomes a 320×320 alpha plane (row-major, [0..1]) that the
 *  Mask tab consumes directly — same grid as RawV3SegmentationMasks so the
 *  existing fill code path needs no special-case.
 *
 *  ── Model contract ──
 *    input  "pixel_values" : 1×3×1024×1024 RGB FP32 (normalised)
 *    output "segmentation" : 1×4×H×W float logits
 *
 *  H and W are model-defined (typically 256×256 — SegFormer's stride-4
 *  output). We argmax across the channel dim then upsample to 320×320 for
 *  the mask plane.
 *
 *  ── Threading ──
 *  Single MIN_PRIORITY daemon dispatcher. Inference is heavy (~1-3 s on a
 *  modern phone with 50 MB FP16); never blocks the preview pipeline.
 *
 *  ── Lifecycle ──
 *  OrtSession created lazily on first compute call. Subsequent calls reuse.
 *  Call [release] when the coordinator is closed to free native memory.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.OrtSessionGate
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Bundle of 4 alpha planes — one per Cityscapes group class. Each plane is
 * 320 × 320 row-major [0..1], matching the grid of RawV3SegmentationMasks
 * so the existing Mask-tab fill path can consume any of them unchanged.
 */
data class RawV3CityscapesMasks(
    val buildingWall: FloatArray,
    val vegetation:   FloatArray,
    val terrain:      FloatArray,
    val sky:          FloatArray,
) {
    companion object {
        const val SIZE = RawV3SegmentationMasks.MASK_SIZE
    }
}

internal class RawV3CityscapesDetector(private val context: Context) {

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
                .also { Log.i(TAG, "OrtSession created (model=$MODEL_ASSET, ${bytes.size / 1024} KB)") }
        }.getOrElse { e -> Log.e(TAG, "Session init failed: ${e.message}"); null }
    }
    private val session: OrtSession? by sessionLazy
    private val gate = OrtSessionGate(TAG)

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RawV3.CityscapesSeg").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    private fun buildSessionOptions(): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        // No NNAPI (some MediaTek SoCs hang), no QNN — CPU only, reliable.
        // But multi-thread it: SegFormer-B1 at 1024² was ~13 s single-threaded;
        // it parallelizes across intra-op threads. Leave 2 cores for UI/GL.
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(4, 8)
        runCatching { opts.setIntraOpNumThreads(threads) }
        runCatching { opts.setInterOpNumThreads(1) }
        runCatching { opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        return opts
    }

    /**
     * Run 4-class semantic segmentation on [bitmap]. Returns null when the
     * model is missing, the session failed to init, or [release] was called.
     */
    suspend fun compute(bitmap: Bitmap): RawV3CityscapesMasks? {
        if (gate.isReleased || !hasModel) return null
        return withContext(dispatcher) {
            gate.run {
                val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
                try {
                    runSegFormer(scaled)
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

    // ── Inference ────────────────────────────────────────────────────────────

    private fun runSegFormer(scaledInput: Bitmap): RawV3CityscapesMasks {
        val s = session ?: error("OrtSession unavailable")
        val env = OrtEnvironment.getEnvironment()
        val input = bitmapToNchwFp32(scaledInput, env)
        val output = s.run(mapOf(s.inputNames.first() to input))
        input.close()

        // Output shape: [1, 4, outH, outW]. SegFormer-B1 at 1024 input
        // typically emits a 256×256 logit grid (stride 4). We argmax per
        // pixel to the most-likely group, then expand each group's
        // confidence into its own 320×320 mask via bilinear upsampling.
        @Suppress("UNCHECKED_CAST")
        val raw = output[0].value as Array<Array<Array<FloatArray>>>  // [1][C][H][W]
        val channels = raw[0].size
        require(channels == NUM_CLASSES) {
            "Expected $NUM_CLASSES output channels, got $channels — wrong model?"
        }
        val h = raw[0][0].size
        val w = raw[0][0][0].size
        Log.i(TAG, "segformer output ${channels}×${h}×${w}")

        // Compute per-class probability via softmax over the channel axis.
        // The argmax of softmax == argmax of logits, but we want SOFT masks
        // for the Mask tab (so subjects with low certainty paint less).
        // Softmax is cheap: shared denominator per pixel.
        val classProbs = Array(channels) { FloatArray(h * w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Find max logit for numerical-stable softmax.
                var mx = Float.NEGATIVE_INFINITY
                for (c in 0 until channels) {
                    val v = raw[0][c][y][x]
                    if (v > mx) mx = v
                }
                var sum = 0f
                val tmp = FloatArray(channels)
                for (c in 0 until channels) {
                    val e = kotlin.math.exp((raw[0][c][y][x] - mx).toDouble()).toFloat()
                    tmp[c] = e
                    sum += e
                }
                val inv = if (sum > 0f) 1f / sum else 0f
                for (c in 0 until channels) {
                    classProbs[c][y * w + x] = tmp[c] * inv
                }
            }
        }
        output.close()

        // Upsample each per-class plane to MASK_SIZE × MASK_SIZE via
        // bilinear. MASK_SIZE matches RawV3SegmentationMasks so callers
        // can consume any plane without further resize.
        val out = Array(channels) { c ->
            bilinearUpsample(classProbs[c], w, h, RawV3CityscapesMasks.SIZE, RawV3CityscapesMasks.SIZE)
        }
        return RawV3CityscapesMasks(
            buildingWall = out[0],
            vegetation   = out[1],
            terrain      = out[2],
            sky          = out[3],
        )
    }

    /**
     * Bitmap → NCHW FP32 tensor with ImageNet normalisation. The SegFormer
     * export script (RemapSegFormer wrapper) doesn't include the mean/std
     * subtraction since the HF feature_extractor handles it; we replicate
     * the standard ImageNet stats here so the model sees the same input
     * distribution it was trained on.
     */
    private fun bitmapToNchwFp32(bitmap: Bitmap, env: OrtEnvironment): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        require(w == INPUT_SIZE && h == INPUT_SIZE) {
            "bitmap must be ${INPUT_SIZE}×${INPUT_SIZE}, got ${w}×${h}"
        }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        // Layout: [R-plane, G-plane, B-plane]. Allocate one contiguous buffer.
        val buf = FloatBuffer.allocate(3 * plane)
        val arr = buf.array()
        // ImageNet normalisation. Hugging Face SegFormer uses these stats.
        val rMean = 0.485f; val rStd = 0.229f
        val gMean = 0.456f; val gStd = 0.224f
        val bMean = 0.406f; val bStd = 0.225f
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr  8) and 0xFF) / 255f
            val b = ( p         and 0xFF) / 255f
            arr[i]              = (r - rMean) / rStd
            arr[i + plane]      = (g - gMean) / gStd
            arr[i + 2 * plane]  = (b - bMean) / bStd
        }
        return OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
    }

    /**
     * Bilinear upsample a single-channel [0..1] plane from [srcW]×[srcH]
     * to [dstW]×[dstH]. Standard sampler — no half-pixel offset since the
     * destination is going to be sampled as a mask alpha (UV coordinates),
     * not as a texture with a specific sample model.
     */
    private fun bilinearUpsample(
        src: FloatArray, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int,
    ): FloatArray {
        val out = FloatArray(dstW * dstH)
        val sx = (srcW - 1f) / (dstW - 1).coerceAtLeast(1).toFloat()
        val sy = (srcH - 1f) / (dstH - 1).coerceAtLeast(1).toFloat()
        for (y in 0 until dstH) {
            val fy = y * sy
            val y0 = fy.toInt()
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val ty = fy - y0
            for (x in 0 until dstW) {
                val fx = x * sx
                val x0 = fx.toInt()
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val tx = fx - x0
                val v00 = src[y0 * srcW + x0]
                val v10 = src[y0 * srcW + x1]
                val v01 = src[y1 * srcW + x0]
                val v11 = src[y1 * srcW + x1]
                val v0 = v00 + (v10 - v00) * tx
                val v1 = v01 + (v11 - v01) * tx
                out[y * dstW + x] = v0 + (v1 - v0) * ty
            }
        }
        return out
    }

    companion object {
        private const val TAG = "RawV3.CityscapesSeg"
        private const val MODEL_ASSET = "models/segformer_cityscapes_remap_fp16.onnx"
        private const val INPUT_SIZE = 1024
        private const val NUM_CLASSES = 4
    }
}
