/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW v3 — DeepLabV3+ ResNet50 human-parsing processor.
 *
 *  Model: assets/models/deeplabv3p_human.onnx  (45 MB)
 *    Input  "input_1"  : [batch, 512, 512, 3] float32  NHWC  [0..1]
 *    Output "conv2d_9" : [batch, 512, 512, 20] float32  NHWC  logits
 *
 *  Trained on the LIP (Look Into Person) dataset — 20 human body-part
 *  classes. Argmax over the 20 logit channels gives per-pixel class labels.
 *  We flatten the dense class map into per-class binary FloatArrays at
 *  320×320 (matching MASK_SIZE) via nearest-neighbour resample so they
 *  drop straight into [RawV3DeepLabMasks] and the existing mask-tab pipeline.
 *
 *  LIP class index → semantic label:
 *    0  Background      1  Hat            2  Hair
 *    3  Glove           4  Sunglasses     5  Upper-clothes
 *    6  Dress           7  Coat           8  Socks
 *    9  Pants           10 Jumpsuits      11 Scarf
 *    12 Skirt           13 Face           14 Left-arm
 *    15 Right-arm       16 Left-leg       17 Right-leg
 *    18 Left-shoe       19 Right-shoe
 *
 *  ── Threading ──
 *  Runs on [RawV3SegmentationProcessor.dispatcher] (same MIN_PRIORITY daemon),
 *  called in series AFTER the selfie-multiclass pass in the coordinator.
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
import java.nio.FloatBuffer

internal class RawV3DeepLabProcessor(private val context: Context) {

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
                .also { Log.i(TAG, "DeepLabV3p OrtSession created") }
        }.getOrElse { e -> Log.e(TAG, "Session init failed: ${e.message}"); null }
    }
    private val session: OrtSession? by sessionLazy

    private val gate = OrtSessionGate(TAG)

    /**
     * Run DeepLabV3+ inference on [bitmap]. Returns [RawV3DeepLabMasks] with
     * per-class binary FloatArrays at 320×320, or null on failure / missing model.
     * Must be called from [RawV3SegmentationProcessor.dispatcher].
     */
    fun compute(bitmap: Bitmap): RawV3DeepLabMasks? {
        if (gate.isReleased || !hasModel) return null
        return gate.run {
            val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            try {
                val classMap = runDeepLab(scaled)       // [INPUT_SIZE * INPUT_SIZE] argmax class ids
                RawV3DeepLabMasks.fromClassMap(classMap, INPUT_SIZE)
            } finally {
                scaled.recycle()
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
                Log.d(TAG, "DeepLabV3p OrtSession released")
            }
        }
    }

    // ── DeepLabV3+ inference ─────────────────────────────────────────────────

    private fun runDeepLab(bitmap: Bitmap): IntArray {
        val s = session ?: error("OrtSession unavailable")

        val env   = OrtEnvironment.getEnvironment()
        val input = bitmapToNhwcTensor(bitmap, env, INPUT_SIZE)

        val output = s.run(mapOf(INPUT_NAME to input))
        input.close()

        // Use index 0 — named get() returns Optional<OnnxValue>, not OnnxValue directly.
        return extractArgmax(output[0].value, INPUT_SIZE, NUM_CLASSES)
    }

    // NHWC: [1, H, W, 3] float32, normalised to [0..1].
    private fun bitmapToNhwcTensor(bitmap: Bitmap, env: OrtEnvironment, size: Int): OnnxTensor {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val data = FloatArray(size * size * 3)
        var k = 0
        for (px in pixels) {
            data[k++] = ((px shr 16) and 0xFF) / 255f   // R
            data[k++] = ((px shr 8)  and 0xFF) / 255f   // G
            data[k++] = (px          and 0xFF) / 255f   // B
        }
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1L, size.toLong(), size.toLong(), 3L),
        )
    }

    // Output: [1, H, W, 20] NHWC logits → argmax over the 20-class dim.
    @Suppress("UNCHECKED_CAST")
    private fun extractArgmax(value: Any, size: Int, numClasses: Int): IntArray {
        val n = size * size
        val classMap = IntArray(n)
        when (value) {
            is Array<*> -> {
                // [1][H][W][C] nested arrays
                val batch = (value as Array<Array<Array<FloatArray>>>)[0]
                var i = 0
                for (y in 0 until size) {
                    val row = batch[y]
                    for (x in 0 until size) {
                        val logits = row[x]
                        var best = 0; var bestScore = logits[0]
                        for (c in 1 until numClasses) {
                            if (logits[c] > bestScore) { bestScore = logits[c]; best = c }
                        }
                        classMap[i++] = best
                    }
                }
            }
            is FloatArray -> {
                // Flat [1 * H * W * C]: stride = numClasses per pixel
                var i = 0
                var offset = 0
                while (i < n) {
                    var best = 0; var bestScore = value[offset]
                    for (c in 1 until numClasses) {
                        val s = value[offset + c]; if (s > bestScore) { bestScore = s; best = c }
                    }
                    classMap[i++] = best
                    offset += numClasses
                }
            }
        }
        return classMap
    }

    private fun buildSessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            runCatching { setIntraOpNumThreads(1) }
            runCatching { setInterOpNumThreads(1) }
            runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        }

    companion object {
        private const val TAG         = "RawV3.DeepLabV3p"
        private const val MODEL_ASSET = "models/deeplabv3p_human.onnx"
        private const val INPUT_NAME  = "input_1"
        private const val OUTPUT_NAME = "conv2d_9"
        private const val INPUT_SIZE  = 512
        private const val NUM_CLASSES = 20
    }
}
