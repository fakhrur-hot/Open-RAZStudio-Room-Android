/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * RAZGAN deep-inpaint engine — the quality upgrade that lets Heal/Erase
 * SYNTHESISE plausible texture (people/objects removed cleanly) instead of the
 * smeared diffusion classic OpenCV TELEA/NS produce on anything larger than a
 * small blemish.
 *
 * ── Attribution (required, MIT) ───────────────────────────────────────────
 * "RAZGAN" is MI-GAN, converted to ONNX for on-device use. MI-GAN is
 *   Copyright (c) 2024 Picsart AI Research (PAIR), MIT License
 *   https://github.com/Picsart-AI-Research/MI-GAN
 * The MIT licence text + this copyright MUST remain in the app's open-source
 * attributions. We did not train this model; we converted the released weights.
 *
 * ── How it runs ───────────────────────────────────────────────────────────
 * The 512² MI-GAN generator runs on-device via ONNX Runtime Mobile (already a
 * project dependency). Pre/post-processing is done here (the converted graph is
 * just the generator core). Following MI-GAN's own pipeline: crop a padded
 * square around the mask so the model sees context, resize to 512, infer,
 * resize back, and composite ONLY the masked pixels (feathered) so untouched
 * areas stay pixel-identical.
 *
 * Model contract (assets/models/razgan.onnx — NCHW, float32):
 *   input  x : [1, 4, S, S] = concat([keepMask-0.5, image*keepMask])
 *              image in [-1,1]; keepMask in {0,1} (1 = KEEP, 0 = hole)
 *   output y : [1, 3, S, S] in [-1,1]
 * NB: MI-GAN's mask is INVERTED vs our heal mask — our HealMaskBuilder paints
 * white(255) = HOLE, so keepMask = 0 there.
 *
 * If the asset is missing or inference throws, [heal] returns null and the
 * caller falls back to TELEA — so Heal always works, just better with RAZGAN.
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.heal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.OrtSessionGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object InpaintEngine {
    private const val TAG = "RazGanInpaint"
    private const val ASSET = "models/razgan.onnx"
    /** Context padding around the mask bbox, as a fraction of the bbox size. */
    private const val CONTEXT_PAD = 0.6f
    /** Hole dilation (px, region scale) before compositing — ensures the fill
     *  fully covers the object so no thin rim of it survives the boundary. */
    private const val DILATE = 2
    private const val MAX_PIXELS = 40 * 1024 * 1024

    @Volatile private var session: OrtSession? = null
    @Volatile private var triedLoad = false
    @Volatile private var inputName = "x"
    @Volatile private var modelSize = 512   // S, learned from the model input shape
    private val gate = OrtSessionGate(TAG)

    fun isModelPresent(ctx: Context): Boolean =
        runCatching { ctx.assets.openFd(ASSET).use { it.declaredLength > 0 } }
            .getOrElse { runCatching { ctx.assets.open(ASSET).use { it.read() >= 0 } }.getOrDefault(false) }

    private fun session(ctx: Context): OrtSession? {
        session?.let { return it }
        if (triedLoad) return session
        synchronized(this) {
            if (triedLoad) return session
            triedLoad = true
            session = runCatching {
                val bytes = ctx.assets.open(ASSET).use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
                val s = env.createSession(bytes, opts)
                inputName = s.inputNames.firstOrNull() ?: "x"
                val shp = (s.inputInfo[inputName]?.info as? TensorInfo)?.shape
                if (shp != null && shp.size == 4 && shp[2] > 0) modelSize = shp[2].toInt()
                s
            }.onFailure { Log.w(TAG, "RAZGAN model unavailable → TELEA fallback", it) }.getOrNull()
            if (session != null) Log.i(TAG, "RAZGAN (MI-GAN) loaded ($ASSET, S=$modelSize)")
            return session
        }
    }

    /** Optional teardown — safe while [heal] is mid-infer. */
    fun release() {
        gate.release {
            runCatching { session?.close() }
            session = null
        }
    }

    /**
     * Deep-inpaint [src] over the white pixels of [maskArgb] (strict-binary
     * 0/255 ARGB, white = hole, from [HealMaskBuilder]). Returns a NEW bitmap
     * with the masked region synthesised, or null when the model is absent /
     * fails — caller then falls back to TELEA.
     */
    fun heal(ctx: Context, src: Bitmap, maskArgb: Bitmap): Bitmap? {
        val w = src.width; val h = src.height
        if (w < 8 || h < 8 || w.toLong() * h > MAX_PIXELS) return null
        if (maskArgb.width != w || maskArgb.height != h) return null
        if (gate.isReleased) return null
        val sess = session(ctx) ?: return null
        val s = modelSize
        if (s < 8) return null
        val env = OrtEnvironment.getEnvironment()
        val t0 = System.currentTimeMillis()

        return gate.run {
        runCatching {
            // 1) Mask bbox (white = hole). Bail if empty.
            val mPx = IntArray(w * h); maskArgb.getPixels(mPx, 0, w, 0, 0, w, h)
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if ((mPx[row + x] and 0xFF) > 127) {
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < minX) return@runCatching null

            // 2) Padded square region around the bbox (context for the model).
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val pad = (max(bw, bh) * CONTEXT_PAD).roundToInt()
            val side = min(max(bw, bh) + 2 * pad, min(w, h))
            val cxC = (minX + maxX) / 2; val cyC = (minY + maxY) / 2
            val rl = (cxC - side / 2).coerceIn(0, w - side)
            val rt = (cyC - side / 2).coerceIn(0, h - side)
            val region = Rect(rl, rt, rl + side, rt + side)

            // 3) Crop region; resize image + mask to S×S.
            val regionBmp = Bitmap.createBitmap(src, region.left, region.top, side, side)
            val regionMask = Bitmap.createBitmap(maskArgb, region.left, region.top, side, side)
            val imgS = Bitmap.createScaledBitmap(regionBmp, s, s, true)
            val mskS = Bitmap.createScaledBitmap(regionMask, s, s, true)
            regionBmp.recycle(); regionMask.recycle()
            val ss = s * s
            val sPx = IntArray(ss); imgS.getPixels(sPx, 0, s, 0, 0, s, s)
            val kPx = IntArray(ss); mskS.getPixels(kPx, 0, s, 0, 0, s, s)
            imgS.recycle(); mskS.recycle()

            // 4) Build NCHW 4-ch input: [keepMask-0.5, image*keepMask], image in
            //    [-1,1]. keepMask = 0 at holes (our white mask), 1 elsewhere.
            val inBuf = ByteBuffer.allocateDirect(4 * ss * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val c0 = 0; val c1 = ss; val c2 = 2 * ss; val c3 = 3 * ss
            val plane = FloatArray(4 * ss)
            for (i in 0 until ss) {
                val c = sPx[i]
                val keep = if ((kPx[i] and 0xFF) > 127) 0f else 1f   // white = hole → keep 0
                plane[c0 + i] = keep - 0.5f
                plane[c1 + i] = (((c ushr 16) and 0xFF) / 255f * 2f - 1f) * keep
                plane[c2 + i] = (((c ushr 8) and 0xFF) / 255f * 2f - 1f) * keep
                plane[c3 + i] = ((c and 0xFF) / 255f * 2f - 1f) * keep
            }
            inBuf.put(plane); inBuf.rewind()

            // 5) Run.
            val outPx = IntArray(ss)
            OnnxTensor.createTensor(env, inBuf, longArrayOf(1, 4, s.toLong(), s.toLong())).use { input ->
                sess.run(mapOf(inputName to input)).use { res ->
                    val out = (res.get(0) as OnnxTensor).floatBuffer
                    val o = FloatArray(3 * ss); out.get(o)
                    for (i in 0 until ss) {
                        val r = ((o[i] * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
                        val g = ((o[ss + i] * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
                        val b = ((o[2 * ss + i] * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
                        outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }

            // 6) Resize result back to region; composite ONLY masked pixels (feathered).
            val outS = Bitmap.createBitmap(outPx, s, s, Bitmap.Config.ARGB_8888)
            val outRegion = Bitmap.createScaledBitmap(outS, side, side, true)
            outS.recycle()
            val result = src.copy(Bitmap.Config.ARGB_8888, true)
            val rPx = IntArray(side * side); outRegion.getPixels(rPx, 0, side, 0, 0, side, side)
            outRegion.recycle()
            val resPx = IntArray(side * side)
            result.getPixels(resPx, 0, side, region.left, region.top, side, side)

            // ── Grain match (F2) ───────────────────────────────────────────────
            // RAZGAN fills holes with CLEAN texture, leaving a noise-free "dead
            // spot" in grainy / high-ISO photos. Estimate the local sensor-noise
            // sigma from the SURROUNDING (non-hole) pixels via a high-pass residual
            // (pixel − its 3×3 mean); the MEDIAN residual magnitude is a robust
            // noise estimate (edges/texture are a sparse minority the median
            // ignores). Then synthesise matching per-channel grain on the fill so
            // the edit is invisible at 100%. (DnCNN's "Input − denoised = noise"
            // idea, with a fast box high-pass standing in for the denoiser.)
            val hR = IntArray(256); val hG = IntArray(256); val hB = IntArray(256); var nSamp = 0
            run {
                var ry = 1
                while (ry < side - 1) {
                    var rx = 1
                    while (rx < side - 1) {
                        val gi = (region.top + ry) * w + (region.left + rx)
                        // Sample only FLAT surrounding pixels: skip holes and a 1px
                        // halo around them (their 3×3 would straddle the fill seam).
                        if ((mPx[gi] and 0xFF) > 127 ||
                            (mPx[gi - 1] and 0xFF) > 127 || (mPx[gi + 1] and 0xFF) > 127 ||
                            (mPx[gi - w] and 0xFF) > 127 || (mPx[gi + w] and 0xFF) > 127) {
                            rx++; continue
                        }
                        val ri = ry * side + rx
                        var sr = 0; var sg = 0; var sb = 0
                        var dy = -1
                        while (dy <= 1) {
                            val rb = ri + dy * side
                            var dx = -1
                            while (dx <= 1) {
                                val p = resPx[rb + dx]
                                sr += (p ushr 16) and 0xFF; sg += (p ushr 8) and 0xFF; sb += p and 0xFF
                                dx++
                            }
                            dy++
                        }
                        val c = resPx[ri]
                        hR[abs(((c ushr 16) and 0xFF) - sr / 9)]++
                        hG[abs(((c ushr 8) and 0xFF) - sg / 9)]++
                        hB[abs((c and 0xFF) - sb / 9)]++
                        nSamp++
                        rx++
                    }
                    ry++
                }
            }
            fun medianMag(h: IntArray): Int {
                if (nSamp == 0) return 0
                val half = nSamp / 2; var acc = 0
                for (v in 0..255) { acc += h[v]; if (acc >= half) return v }
                return 0
            }
            // median|residual| → σ: MAD constant (1.4826) × high-pass variance
            // correction (1/√0.888 ≈ 1.061) ≈ 1.572. Clamp to a sane 8-bit range.
            val sigR = (medianMag(hR) * 1.572f).coerceIn(0f, 18f)
            val sigG = (medianMag(hG) * 1.572f).coerceIn(0f, 18f)
            val sigB = (medianMag(hB) * 1.572f).coerceIn(0f, 18f)
            val grainOn = (sigR + sigG + sigB) > 1.5f   // skip clean images entirely
            val rng = java.util.Random()

            // ── Region-local hole mask + bbox (in region coords) ───────────────
            // Dilated by DILATE px so the fill fully covers the object — the old
            // path re-blended the ORIGINAL (object-carrying) pixels back in over a
            // 3px feather, which left a faint halo rim of the thing being removed.
            val holeReg = ByteArray(side * side)
            var hMinX = side; var hMinY = side; var hMaxX = -1; var hMaxY = -1
            for (ry in 0 until side) {
                val gy = (region.top + ry) * w + region.left
                val rrow = ry * side
                for (rx in 0 until side) {
                    if ((mPx[gy + rx] and 0xFF) > 127) {
                        holeReg[rrow + rx] = 1
                        if (rx < hMinX) hMinX = rx; if (rx > hMaxX) hMaxX = rx
                        if (ry < hMinY) hMinY = ry; if (ry > hMaxY) hMaxY = ry
                    }
                }
            }
            // Cheap square dilation of the hole by DILATE px (covers thin object edges).
            if (hMaxX >= hMinX && DILATE > 0) {
                val dil = holeReg.copyOf()
                for (ry in 0 until side) for (rx in 0 until side) {
                    if (holeReg[ry * side + rx].toInt() == 0) continue
                    val y0 = (ry - DILATE).coerceAtLeast(0); val y1 = (ry + DILATE).coerceAtMost(side - 1)
                    val x0 = (rx - DILATE).coerceAtLeast(0); val x1 = (rx + DILATE).coerceAtMost(side - 1)
                    var yy = y0
                    while (yy <= y1) { var xx = x0; while (xx <= x1) { dil[yy * side + xx] = 1; xx++ }; yy++ }
                }
                System.arraycopy(dil, 0, holeReg, 0, dil.size)
                hMinX = (hMinX - DILATE).coerceAtLeast(0); hMinY = (hMinY - DILATE).coerceAtLeast(0)
                hMaxX = (hMaxX + DILATE).coerceAtMost(side - 1); hMaxY = (hMaxY + DILATE).coerceAtMost(side - 1)
            }

            // ── Primary composite: Poisson seamless clone ──────────────────────
            // Clones the model fill into the hole and solves the boundary gradient
            // against the SURROUNDINGS, so the fill inherits neighbouring
            // brightness/colour with no DC seam — the "clean, natural" look of a
            // content-aware fill. Falls back to a hard fill on any failure
            // (mask touching the region border, tiny region, OpenCV missing).
            val ssz = side * side
            val blend: IntArray = runCatching {
                // seamlessClone needs a 1px border around the mask.
                if (hMinX <= 0 || hMinY <= 0 || hMaxX >= side - 1 || hMaxY >= side - 1) {
                    return@runCatching null
                }
                // src = fill inside hole, original outside (continuous field); dst = original.
                val srcArr = IntArray(ssz) { if (holeReg[it].toInt() != 0) rPx[it] else resPx[it] }
                val mArr = IntArray(ssz) { if (holeReg[it].toInt() != 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
                val srcBmp = Bitmap.createBitmap(srcArr, side, side, Bitmap.Config.ARGB_8888)
                val dstBmp = Bitmap.createBitmap(resPx, side, side, Bitmap.Config.ARGB_8888)
                val mBmp = Bitmap.createBitmap(mArr, side, side, Bitmap.Config.ARGB_8888)
                val srcMat = org.opencv.core.Mat(); org.opencv.android.Utils.bitmapToMat(srcBmp, srcMat)
                val dstMat = org.opencv.core.Mat(); org.opencv.android.Utils.bitmapToMat(dstBmp, dstMat)
                val mMat = org.opencv.core.Mat(); org.opencv.android.Utils.bitmapToMat(mBmp, mMat)
                org.opencv.imgproc.Imgproc.cvtColor(srcMat, srcMat, org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB)
                org.opencv.imgproc.Imgproc.cvtColor(dstMat, dstMat, org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB)
                org.opencv.imgproc.Imgproc.cvtColor(mMat, mMat, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
                val outMat = org.opencv.core.Mat()
                val center = org.opencv.core.Point(
                    ((hMinX + hMaxX) / 2).toDouble(), ((hMinY + hMaxY) / 2).toDouble(),
                )
                org.opencv.photo.Photo.seamlessClone(
                    srcMat, dstMat, mMat, center, outMat, org.opencv.photo.Photo.NORMAL_CLONE,
                )
                org.opencv.imgproc.Imgproc.cvtColor(outMat, outMat, org.opencv.imgproc.Imgproc.COLOR_RGB2RGBA)
                val outBmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                org.opencv.android.Utils.matToBitmap(outMat, outBmp)
                val arr = IntArray(ssz); outBmp.getPixels(arr, 0, side, 0, 0, side, side)
                srcBmp.recycle(); dstBmp.recycle(); mBmp.recycle(); outBmp.recycle()
                srcMat.release(); dstMat.release(); mMat.release(); outMat.release()
                arr
            }.onFailure { Log.w(TAG, "seamlessClone failed → hard-fill fallback", it) }.getOrNull()
                ?: resPx.copyOf().also { fb ->
                    // Fallback: hole = model fill, FULLY (no original re-blend → no
                    // object halo). Slightly harder edge than seamless; grain hides it.
                    for (i in 0 until ssz) if (holeReg[i].toInt() != 0) fb[i] = rPx[i]
                }

            // ── Grain match on the filled hole (keeps the edit invisible at 100%) ─
            if (grainOn) {
                for (i in 0 until ssz) {
                    if (holeReg[i].toInt() == 0) continue
                    val c = blend[i]
                    val nr = (((c ushr 16) and 0xFF) + rng.nextGaussian() * sigR).roundToInt().coerceIn(0, 255)
                    val ng = (((c ushr 8) and 0xFF) + rng.nextGaussian() * sigG).roundToInt().coerceIn(0, 255)
                    val nb = ((c and 0xFF) + rng.nextGaussian() * sigB).roundToInt().coerceIn(0, 255)
                    blend[i] = (0xFF.shl(24)) or (nr shl 16) or (ng shl 8) or nb
                }
            }
            result.setPixels(blend, 0, side, region.left, region.top, side, side)
            Log.i(TAG, "RAZGAN inpaint ${side}x$side @S=$s in ${System.currentTimeMillis() - t0} ms " +
                "grain=${if (grainOn) "σ(%.1f,%.1f,%.1f) n=$nSamp".format(sigR, sigG, sigB) else "off (clean)"}")
            result
        }.onFailure { Log.w(TAG, "RAZGAN inpaint failed → TELEA fallback", it) }.getOrNull()
        }
    }
}
