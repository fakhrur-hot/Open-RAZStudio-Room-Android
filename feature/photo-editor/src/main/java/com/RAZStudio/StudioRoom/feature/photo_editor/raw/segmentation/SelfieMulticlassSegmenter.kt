/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Runs the MediaPipe selfie-multiclass segmentation model
 * (`selfie_multiclass_256x256.tflite`, 16 MB float32) via raw LiteRT
 * Interpreter instead of `com.google.mediapipe.tasks.vision.ImageSegmenter`.
 *
 * Why bypass MediaPipe's wrapper: the .tflite file Google hosts has
 * a hard-coded batch dimension of 8 internally. MediaPipe's image-
 * segmenter graph asserts batch==1 and throws
 * `Batch size mismatch, expected 1 but got 8` during `createFromOptions`.
 * LiteRT doesn't care — we just feed `[8, 256, 256, 3]`, populate
 * slot 0 with the user's image, and read `output[0]` as our result.
 *
 * Class indices (`SegmentationClass`):
 *   0 = Background
 *   1 = Hair
 *   2 = BodySkin       (neck, arms, hands)
 *   3 = FaceSkin       (cheeks, forehead, chin)
 *   4 = Clothes        (t-shirts, hoodies, hats)
 *   5 = Accessories    (glasses, headphones, held objects)
 */
class SelfieMulticlassSegmenter private constructor(
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    private val batchSize: Int,
    private val inputH: Int,
    private val inputW: Int,
    private val numClasses: Int,
) : AutoCloseable {

    private val gate = OrtSessionGate(TAG)

    enum class SegmentationClass(val index: Int, val displayName: String) {
        Background(0, "Background"),
        Hair(1, "Hair"),
        BodySkin(2, "Body skin"),
        FaceSkin(3, "Face skin"),
        Clothes(4, "Clothes"),
        Accessories(5, "Accessories");

        companion object {
            fun fromIndex(i: Int): SegmentationClass? =
                entries.firstOrNull { it.index == i }
        }
    }

    /**
     * Soft per-class probability masks from one inference.
     *
     * [perClass] is indexed as `perClass[classIndex][pixelIndex]` where
     * pixel order is row-major and values are in [0, 1] (softmax-normalised
     * probabilities, not hard argmax indices).  The size of each plane is
     * [width] * [height].
     *
     * Keeping soft probabilities instead of hard argmax indices preserves
     * boundary ambiguity all the way into [RawEditorContent.fillFromSegmentation],
     * which can then apply bilinear upsampling, edge-snap, and smoothstep on
     * genuinely continuous values rather than binary 0/1 arrays.
     */
    data class SoftMasks(
        val perClass: Array<FloatArray>,   // [numClasses][width*height]
        val width: Int,
        val height: Int,
    )

    /**
     * Run the segmenter on [src].  Returns per-class softmax probabilities
     * in [0, 1] — NOT argmax class indices.  Boundary pixels have fractional
     * values that reflect model uncertainty, which downstream bilinear
     * upsampling and edge-snap can leverage for sharper, more natural edges.
     *
     * Priority fusion: BodySkin probability is suppressed wherever FaceSkin
     * probability is high, ensuring the two classes don't bleed into each other.
     */
    fun segment(src: Bitmap): SoftMasks? {
        if (gate.isReleased) return null
        return gate.run {
            val scaled = if (src.width == inputW && src.height == inputH) src
            else Bitmap.createScaledBitmap(src, inputW, inputH, /*filter=*/true)

            try {
                val pixels = IntArray(inputW * inputH)
                scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

                // Build one slot's worth of float bytes once, then replicate into
                // ALL batchSize slots. The model was designed for 8 consecutive video
                // frames; feeding 7 zero-padded slots biases BatchNorm statistics
                // heavily toward zero (mean ≈ real/8) and causes hit-or-miss class
                // predictions on still photos. Repeating the same image in every slot
                // makes BN see consistent statistics: mean and variance reflect the
                // actual photo, which is the best single-image approximation of how
                // the model was trained (a stable scene across all 8 frames).
                val slotBytes = ByteArray(inputH * inputW * 3 * 4)
                val slotBuf = ByteBuffer.wrap(slotBytes).order(ByteOrder.nativeOrder())
                for (px in pixels) {
                    slotBuf.putFloat(((px shr 16) and 0xFF) / 255f)
                    slotBuf.putFloat(((px shr 8)  and 0xFF) / 255f)
                    slotBuf.putFloat((px          and 0xFF) / 255f)
                }

                val inputBuf = ByteBuffer.allocateDirect(batchSize * inputH * inputW * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                for (b in 0 until batchSize) inputBuf.put(slotBytes)
                inputBuf.rewind()

                val outputBuf = ByteBuffer
                    .allocateDirect(batchSize * inputH * inputW * numClasses * 4)
                    .order(ByteOrder.nativeOrder())
                interpreter.run(inputBuf, outputBuf)
                outputBuf.rewind()

                // ── Slot 0 only: read raw logits ──────────────────────────────────
                val n = inputH * inputW
                val logits = Array(numClasses) { FloatArray(n) }
                for (i in 0 until n) {
                    for (c in 0 until numClasses) logits[c][i] = outputBuf.float
                }

                // ── Softmax per pixel: logits → probabilities in [0, 1] ───────────
                // Numerically stable (subtract per-pixel max before exp).
                val probs = Array(numClasses) { FloatArray(n) }
                for (i in 0 until n) {
                    var mx = logits[0][i]
                    for (c in 1 until numClasses) if (logits[c][i] > mx) mx = logits[c][i]
                    var sum = 0f
                    for (c in 0 until numClasses) {
                        val e = exp((logits[c][i] - mx).toDouble()).toFloat()
                        probs[c][i] = e; sum += e
                    }
                    if (sum > 1e-9f) for (c in 0 until numClasses) probs[c][i] /= sum
                }

                // ── Priority fusion: suppress BodySkin where FaceSkin is confident ─
                // A pixel with faceSkin=0.7 should have its bodySkin probability
                // scaled down proportionally so the two classes don't bleed into
                // one another at chin / neck boundaries.
                val faceIdx = SegmentationClass.FaceSkin.index
                val bodyIdx = SegmentationClass.BodySkin.index
                val faceProbs = probs[faceIdx]
                val bodyProbs = probs[bodyIdx]
                for (i in 0 until n) bodyProbs[i] *= (1f - faceProbs[i])

                SoftMasks(probs, inputW, inputH)
            } finally {
                if (scaled !== src) scaled.recycle()
            }
        }
    }

    override fun close() {
        gate.release {
            runCatching { interpreter.close() }
            runCatching { gpuDelegate?.close() }
        }
    }

    companion object {
        private const val ASSET = "selfie_multiclass_256x256.tflite"
        private const val TAG = "SelfieMulticlass"

        /**
         * Copy the asset to cache (LiteRT needs a real filesystem path
         * or a mapped buffer; cache file gives us mmap), then init the
         * interpreter. Returns null on any failure.
         */
        fun load(context: Context, preferGpu: Boolean = false): SelfieMulticlassSegmenter? {
            val cacheFile = java.io.File(context.cacheDir, ASSET)
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                runCatching {
                    context.assets.open(ASSET).use { input ->
                        cacheFile.outputStream().use { input.copyTo(it) }
                    }
                }.onFailure {
                    Log.w(TAG, "asset '$ASSET' missing — segmenter unavailable", it)
                    return null
                }
            }
            val modelBuffer = runCatching {
                FileInputStream(cacheFile).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                }
            }.getOrElse {
                Log.w(TAG, "mmap of '$ASSET' failed", it)
                return null
            }
            // Try GPU delegate first (2-5× faster on the 8-batch model).
            // Falls back to 4-thread CPU if the GPU delegate doesn't
            // support the ops in this model or the device has no GPU.
            var gpuDelegate: GpuDelegate? = null
            val interpreter = runCatching {
                val options = Interpreter.Options()
                val gpu = runCatching { GpuDelegate() }.getOrNull()
                if (gpu != null) {
                    options.addDelegate(gpu)
                    gpuDelegate = gpu
                    Log.i(TAG, "GPU delegate enabled")
                } else {
                    options.numThreads = 4
                    Log.i(TAG, "GPU delegate unavailable — using 4-thread CPU")
                }
                Interpreter(modelBuffer, options)
            }.getOrElse {
                // GPU init can throw on unsupported ops — retry on CPU only.
                gpuDelegate?.close(); gpuDelegate = null
                runCatching {
                    val cpuOpts = Interpreter.Options().apply { numThreads = 4 }
                    Interpreter(modelBuffer, cpuOpts)
                        .also { Log.i(TAG, "GPU failed, CPU fallback OK") }
                }.getOrElse {
                    Log.w(TAG, "Interpreter init failed", it)
                    return null
                }
            }
            // Inspect the input tensor: [batch, H, W, 3].
            val inShape = runCatching { interpreter.getInputTensor(0).shape() }
                .getOrNull() ?: run {
                Log.w(TAG, "input tensor shape unavailable")
                interpreter.close()
                return null
            }
            val outShape = runCatching { interpreter.getOutputTensor(0).shape() }
                .getOrNull() ?: run {
                Log.w(TAG, "output tensor shape unavailable")
                interpreter.close()
                return null
            }
            if (inShape.size != 4 || outShape.size != 4) {
                Log.w(TAG, "unexpected tensor rank in=$inShape out=$outShape")
                interpreter.close()
                return null
            }
            val batchSize = inShape[0]
            val h = inShape[1]
            val w = inShape[2]
            val numClasses = outShape[3]
            Log.i(TAG, "loaded $ASSET: in=[${inShape.joinToString(",")}] " +
                "out=[${outShape.joinToString(",")}] " +
                "batch=$batchSize ${w}×${h} classes=$numClasses")
            return SelfieMulticlassSegmenter(interpreter, gpuDelegate, batchSize, h, w, numClasses)
        }
    }
}
