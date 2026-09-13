/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Neural luma denoise (cszn/DnCNN, grayscale σ=15) for the RAW export bitmap.
 * σ=15 (not 25) keeps the result from going waxy/plastic at strength 0.7.
 *
 * Why this exists: the math LMMSE "AI Enhance" path flattens real luma texture
 * (plastic look) because its noise floor is a fixed threshold, not a learned
 * per-pixel noise estimate. DnCNN is a 17-layer residual CNN trained to tell
 * noise from structure, so it cleans flat areas while keeping detail.
 *
 * Design (matches the user's intent: "apply on the smooth/LMMSE path, keep
 * edges sharp, recombine chroma"):
 *   • LUMA only — DnCNN runs on Y; chroma is handled separately (blur).
 *   • FLAT-GATED — denoise is blended in by a flatness weight (1 at flats,
 *     0 at edges/lines), so thin lines and edges are NOT softened. This is the
 *     direct fix for "still too plastic on insignificant edges or lines".
 *   • CHROMA BLUR — a small low-pass on the colour-difference planes removes
 *     the chroma blotches the luma-only path leaves behind.
 *
 * Model contract (see scratchpad/convert_dncnn.py): input/output [1,256,256,1]
 * float32 in [0,1]; output is the CLEAN luma (model already subtracts noise).
 * Tiled with an overlap margin that is discarded to avoid seams. If the asset
 * is missing or TFLite fails, denoise() returns the input bitmap unchanged.
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DncnnLumaDenoiser {
    private const val TAG = "DncnnDenoise"
    private const val ASSET = "models/dncnn_gray_s15.tflite"
    private const val TILE = 256
    private const val MARGIN = 16          // context ring discarded when stitching
    private const val CORE = TILE - 2 * MARGIN   // 224 written per step
    // Guard transient float-plane memory (Y, Yd, 3 chroma planes ≈ 5·4·N bytes).
    private const val MAX_PIXELS = 16 * 1024 * 1024  // ~16 MP

    @Volatile private var interp: Interpreter? = null
    @Volatile private var triedLoad = false

    fun isModelPresent(ctx: Context): Boolean =
        runCatching { ctx.assets.openFd(ASSET).use { it.declaredLength > 0 } }.getOrDefault(false)

    private fun interpreter(ctx: Context): Interpreter? {
        interp?.let { return it }
        if (triedLoad) return interp
        synchronized(this) {
            if (triedLoad) return interp
            triedLoad = true
            interp = runCatching {
                val afd = ctx.assets.openFd(ASSET)
                FileInputStream(afd.fileDescriptor).channel.use { ch ->
                    val mbb = ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                    Interpreter(mbb, Interpreter.Options().apply { numThreads = 4 })
                }
            }.onFailure { Log.w(TAG, "model load failed → DnCNN disabled", it) }.getOrNull()
            if (interp != null) Log.i(TAG, "DnCNN model loaded ($ASSET)")
            return interp
        }
    }

    /**
     * Returns a NEW ARGB_8888 bitmap with flat-gated luma DnCNN + chroma blur
     * applied, or [src] unchanged if the model is unavailable / image too big.
     * [strength] 0..1 scales the luma denoise blend; [chromaRadius] is the
     * colour-difference blur radius in px (0 disables chroma cleanup).
     */
    fun denoise(ctx: Context, src: Bitmap, strength: Float = 0.7f, chromaRadius: Int = 2): Bitmap {
        val w = src.width; val h = src.height
        if (w < 32 || h < 32) return src
        if (w.toLong() * h > MAX_PIXELS) {
            Log.i(TAG, "skip DnCNN: ${w}x$h > ${MAX_PIXELS}px guard")
            return src
        }
        val itp = interpreter(ctx) ?: return src
        val t0 = System.currentTimeMillis()
        val n = w * h
        val px = IntArray(n); src.getPixels(px, 0, w, 0, 0, w, h)

        // Decompose → luma Y + colour-difference offsets (luma-preserving).
        val Y = FloatArray(n); val oR = FloatArray(n); val oG = FloatArray(n); val oB = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val r = ((c ushr 16) and 0xFF) / 255f
            val g = ((c ushr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            Y[i] = y; oR[i] = r - y; oG[i] = g - y; oB[i] = b - y
        }

        // ── Luma DnCNN over overlapping tiles (keep inner CORE, drop margin) ──
        val Yd = Y.copyOf()
        val inBuf = ByteBuffer.allocateDirect(TILE * TILE * 4).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(TILE * TILE * 4).order(ByteOrder.nativeOrder())
        val inF = inBuf.asFloatBuffer(); val outF = outBuf.asFloatBuffer()
        val maxIx = max(0, w - TILE); val maxIy = max(0, h - TILE)
        val ok = runCatching {
            var ty = 0
            while (ty < h) {
                val iy = min(max(ty - MARGIN, 0), maxIy)
                var tx = 0
                while (tx < w) {
                    val ix = min(max(tx - MARGIN, 0), maxIx)
                    // Fill the 256² input from Y with edge clamping.
                    inF.rewind()
                    for (dy in 0 until TILE) {
                        val sy = min(iy + dy, h - 1)
                        val row = sy * w
                        for (dx in 0 until TILE) {
                            inF.put(Y[row + min(ix + dx, w - 1)])
                        }
                    }
                    inBuf.rewind(); outBuf.rewind()
                    itp.run(inBuf, outBuf)
                    // Write only the CORE region for this step from the tile.
                    outF.rewind()
                    val yEnd = min(ty + CORE, h); val xEnd = min(tx + CORE, w)
                    var oy = ty
                    while (oy < yEnd) {
                        val tileRow = (oy - iy) * TILE
                        val outRow = oy * w
                        var ox = tx
                        while (ox < xEnd) {
                            Yd[outRow + ox] = outF.get(tileRow + (ox - ix))
                            ox++
                        }
                        oy++
                    }
                    tx += CORE
                }
                ty += CORE
            }
        }.onFailure { Log.w(TAG, "DnCNN inference failed → passthrough", it) }.isSuccess
        if (!ok) return src

        // ── Flatness gate: keep edges/lines crisp, denoise only flats ──
        // grad = central-difference magnitude on the ORIGINAL luma; edges → 0.
        val s = strength.coerceIn(0f, 1f)
        // Gradient ramp tightened (0.02/0.09 → 0.008/0.045): hair strands / thin
        // lines carry only a MODERATE gradient, so a high gHi mis-classified them
        // as "flat" and let DnCNN soften them ("blurred hair, missing lines").
        // Lower thresholds treat that moderate gradient as EDGE → preserved.
        val gLo = 0.008f; val gHi = 0.045f  // [0,1] luma gradient ramp
        for (y0 in 0 until h) {
            val row = y0 * w
            val up = (if (y0 > 0) y0 - 1 else 0) * w
            val dn = (if (y0 < h - 1) y0 + 1 else h - 1) * w
            for (x0 in 0 until w) {
                val xl = if (x0 > 0) x0 - 1 else 0
                val xr = if (x0 < w - 1) x0 + 1 else w - 1
                val grad = abs(Y[row + xr] - Y[row + xl]) + abs(Y[dn + x0] - Y[up + x0])
                // smoothstep(gLo,gHi,grad): 0 at flats … 1 at edges; gate = 1-edge
                val tg = ((grad - gLo) / (gHi - gLo)).coerceIn(0f, 1f)
                val edge = tg * tg * (3f - 2f * tg)
                val gate = (1f - edge) * s
                val i = row + x0
                Yd[i] = Y[i] + (Yd[i] - Y[i]) * gate
            }
        }

        // ── Chroma blur (separable box) on colour-difference planes ──
        if (chromaRadius > 0) {
            boxBlur(oR, w, h, chromaRadius); boxBlur(oG, w, h, chromaRadius); boxBlur(oB, w, h, chromaRadius)
        }

        // Recompose: denoised luma + (blurred) chroma offsets.
        for (i in 0 until n) {
            val y = Yd[i]
            val r = ((y + oR[i]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = ((y + oG[i]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = ((y + oB[i]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (px[i] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        Log.i(TAG, "DnCNN denoise ${w}x$h in ${System.currentTimeMillis() - t0} ms (strength=$s)")
        return out
    }

    /** In-place separable box blur on a planar float buffer. */
    private fun boxBlur(p: FloatArray, w: Int, h: Int, radius: Int) {
        val tmp = FloatArray(p.size)
        val win = 2 * radius + 1
        // horizontal
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) acc += p[row + min(max(x + k, 0), w - 1)]
                tmp[row + x] = acc / win
            }
        }
        // vertical
        for (x in 0 until w) {
            for (y in 0 until h) {
                var acc = 0f
                for (k in -radius..radius) acc += tmp[min(max(y + k, 0), h - 1) * w + x]
                p[y * w + x] = acc / win
            }
        }
    }
}
