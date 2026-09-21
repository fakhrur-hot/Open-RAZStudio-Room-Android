/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Zero-DCE low-light probe — Auto Expo helper.
 *
 *  Auto Expo's channel-clip path already detects when highlights are hot
 *  (one or more R/G/B channels >= 254). But it can't distinguish between
 *  "bright scene with normal contrast" and "low-light scene with isolated
 *  hot highlights" (a bare bulb in a dim room, a phone flash, sun through
 *  a window during golden hour). Zero-DCE's A-map gives that signal: the
 *  model's curve coefficients are larger for darker scenes — average them
 *  and you get a scalar in roughly [0..1] where high values mean
 *  "Zero-DCE would lift this image a lot if asked."
 *
 *  We use that scalar to push the AE's `claheHighlightsBoost` one step
 *  further negative when BOTH conditions hold:
 *    1. Auto Expo's channel-clip flag fires
 *    2. Zero-DCE confirms the scene is dark overall
 *
 *  That's the "bright-highlight-in-dim-scene" case where the user wants
 *  highlights pulled back harder than the base AE would normally do.
 *
 *  We DON'T apply Zero-DCE's image transform — that would conflict with
 *  the user's manual exposure edits and break sidecar round-trip. We just
 *  read the curve magnitude as an extra signal.
 *
 *  ── Threading ──
 *  Single MIN_PRIORITY daemon dispatcher. ~50-200 ms one-shot per file.
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

internal class RawV3ZeroDceLightProbe(private val context: Context) {

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
        Thread(r, "RawV3.ZeroDceProbe").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    private fun buildSessionOptions(): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)
        return opts
    }

    /**
     * Run Zero-DCE on a 256×256 resample of [bitmap] and return the mean
     * magnitude of the resulting A-map (curve coefficients). Higher value =
     * darker scene that would benefit from more lift. Typical ranges:
     *   • > 0.40 — very dark (night, dim interior)
     *   • 0.25 – 0.40 — low-light (sunset, indoor incandescent)
     *   • 0.15 – 0.25 — normal indoor / cloudy
     *   • < 0.15 — bright (sunny outdoor, well-lit)
     *
     * Returns null when the model is missing, releases is set, or
     * inference fails. Derived from [probeLiftMap] — one inference path.
     */
    suspend fun probeAverageLift(bitmap: Bitmap): Float? {
        val map = probeLiftMap(bitmap) ?: return null
        return map.average().toFloat().also {
            Log.i(TAG, "probe: average lift = %.3f".format(it))
        }
    }

    /**
     * Spatial variant of [probeAverageLift]: the per-pixel mean |A| across
     * the 24 curve-coefficient channels, as a 256×256 row-major float map.
     * High values mark pixels Zero-DCE would lift hard (deep shadow → low
     * SNR). Consumed by the adaptive devignette inside the native Stage A
     * Lensfun pass (StageAOptions.liftMap → lfa_correct_rgba_f16).
     */
    suspend fun probeLiftMap(bitmap: Bitmap): FloatArray? {
        if (gate.isReleased || !hasModel) return null
        return withContext(dispatcher) {
            gate.run {
                // 256×256 matches the model's training resolution; Zero-DCE's
                // A-map is low-frequency so the resample doesn't lose signal.
                val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
                try {
                    runProbeMap(scaled)
                } finally {
                    scaled.recycle()
                }
            }
        }
    }

    /**
     * Release the OrtSession. Safe while a probe is mid-infer — close is
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

    private fun runProbeMap(scaled: Bitmap): FloatArray {
        val s = session ?: error("OrtSession unavailable")
        val env = OrtEnvironment.getEnvironment()
        val input = bitmapToNchwFp32(scaled, env)
        val output = s.run(mapOf(s.inputNames.first() to input))
        input.close()

        // Output shape: [1, 24, 256, 256]. The model emits both positive
        // (lift) and negative (compress) curves; |a| per pixel gives total
        // "how much would Zero-DCE change this pixel" — the spatial lift map.
        @Suppress("UNCHECKED_CAST")
        val raw = output[0].value as Array<Array<Array<FloatArray>>>  // [1][24][H][W]
        val channels = raw[0].size
        val h = raw[0][0].size
        val w = raw[0][0][0].size
        val map = FloatArray(h * w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0.0
                for (c in 0 until channels) {
                    sum += kotlin.math.abs(raw[0][c][y][x])
                }
                map[y * w + x] = (sum / channels).toFloat()
            }
        }
        output.close()
        return map
    }

    private fun bitmapToNchwFp32(bitmap: Bitmap, env: OrtEnvironment): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        val buf = FloatBuffer.allocate(3 * plane)
        val arr = buf.array()
        // Zero-DCE was trained with [0,1] normalised RGB — no ImageNet stats.
        for (i in 0 until plane) {
            val p = pixels[i]
            arr[i]             = ((p shr 16) and 0xFF) / 255f
            arr[i + plane]     = ((p shr  8) and 0xFF) / 255f
            arr[i + 2 * plane] = ( p         and 0xFF) / 255f
        }
        return OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, h.toLong(), w.toLong()),
        )
    }

    companion object {
        private const val TAG = "RawV3.ZeroDceProbe"
        private const val MODEL_ASSET = "models/zero_dce.onnx"
        private const val INPUT_SIZE = 256
    }
}
