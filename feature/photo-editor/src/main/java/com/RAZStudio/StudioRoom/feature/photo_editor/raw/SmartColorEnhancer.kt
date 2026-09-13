/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Smart Color Enhancement — bitmap-level pre-pass applied before the main
 *  macro pixel loop when [UserMacro.smartColorEnhance] is true.
 *
 *  Steps (all in the same single bitmap scan through OpenCV):
 *    1. Auto white balance  — per-channel histogram stretch (min→0, max→255)
 *       to remove obvious color casts without touching hue relationships.
 *    2. CLAHE on Lab L      — adaptive luminance EQ (clipLimit=2, 8×8 tile)
 *       so details open in shadows and highlights without blowing them out.
 *    3. Adaptive chroma boost on Lab a/b — mid-range saturation amplified by
 *       1.3×, high-range (chroma > 80 from neutral 128) progressively
 *       compressed back toward 1×, so colors become richer but not garish.
 *
 *  Input/output: ARGB_8888 Bitmap.  FP16 callers must down/up-convert.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

internal object SmartColorEnhancer {

    private const val TAG = "SmartColorEnhancer"

    // Saturation boost factor for mid-range chromas.
    private const val SAT_SCALE = 1.3f
    // Chroma distance from center (128) above which boost is progressively reduced.
    private const val CHROMA_THRESHOLD = 80
    // Maximum achievable chroma distance in 8-bit Lab (±127).
    private const val MAX_CHROMA = 127f

    /**
     * Returns a new ARGB_8888 bitmap with smart color enhancement applied.
     * The original [bitmap] is not modified. Returns [bitmap] unchanged on
     * any OpenCV failure so the caller always gets a usable image.
     */
    fun enhance(bitmap: Bitmap): Bitmap = runCatching {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h

        // ── 1. Extract channels ──────────────────────────────────────────────
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val rBuf = ByteArray(n)
        val gBuf = ByteArray(n)
        val bBuf = ByteArray(n)
        for (i in 0 until n) {
            val px = pixels[i]
            rBuf[i] = ((px shr 16) and 0xFF).toByte()
            gBuf[i] = ((px shr 8)  and 0xFF).toByte()
            bBuf[i] = (px          and 0xFF).toByte()
        }

        // ── 2. Auto white balance (per-channel min/max stretch) ──────────────
        stretchChannel(rBuf)
        stretchChannel(gBuf)
        stretchChannel(bBuf)

        // ── 3. Pack into an interleaved RGB Mat ──────────────────────────────
        val rgbData = ByteArray(n * 3)
        for (i in 0 until n) {
            rgbData[i * 3]     = rBuf[i]
            rgbData[i * 3 + 1] = gBuf[i]
            rgbData[i * 3 + 2] = bBuf[i]
        }
        val rgbMat = Mat(h, w, CvType.CV_8UC3)
        rgbMat.put(0, 0, rgbData)

        // ── 4. RGB → Lab ─────────────────────────────────────────────────────
        val labMat = Mat()
        Imgproc.cvtColor(rgbMat, labMat, Imgproc.COLOR_RGB2Lab)
        rgbMat.release()

        // ── 5. Split L / a / b ───────────────────────────────────────────────
        val channels = ArrayList<Mat>(3)
        Core.split(labMat, channels)
        val lCh = channels[0]
        val aCh = channels[1]
        val bCh = channels[2]

        // ── 6. Sigmoidal L boost — GPU-parity with GLSL applySmartColorEnhancement
        //      OpenCV Lab L encoding: uint8 [0..255] = Lab L [0..100] * 2.55
        //      Formula: L_out = L_in + 15 * sin(PI * L_in/100) * hlProtect
        //      hlProtect (2026-08-28, mirror of the GLSL/apply_macro change):
        //      taper the lift above the upper midtones so Color Pop stops
        //      pumping highlights — full pop through L<=60, ~15% left by L~95.
        val nPx = lCh.rows() * lCh.cols()
        val lData = ByteArray(nPx)
        lCh.get(0, 0, lData)
        for (i in lData.indices) {
            val Lf = (lData[i].toInt() and 0xFF) * (100f / 255f)
            val ln = Lf / 100f
            val t = ((ln - 0.60f) / 0.35f).coerceIn(0f, 1f)
            val hlProtect = 1f - 0.85f * (t * t * (3f - 2f * t))
            val Lout = (Lf + 15f * sin(PI * Lf / 100.0).toFloat() * hlProtect)
                .coerceIn(0f, 100f)
            lData[i] = (Lout * (255f / 100f) + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        val lEnhanced = Mat(lCh.rows(), lCh.cols(), CvType.CV_8U)
        lEnhanced.put(0, 0, lData)
        lCh.release()

        // ── 7. Adaptive chroma boost on a and b ──────────────────────────────
        val aEnhanced = boostChroma(aCh)
        val bEnhanced = boostChroma(bCh)
        aCh.release()
        bCh.release()

        // ── 8. Merge and convert back to RGB ─────────────────────────────────
        Core.merge(listOf(lEnhanced, aEnhanced, bEnhanced), labMat)
        lEnhanced.release(); aEnhanced.release(); bEnhanced.release()

        val outRgb = Mat()
        Imgproc.cvtColor(labMat, outRgb, Imgproc.COLOR_Lab2RGB)
        labMat.release()

        // ── 9. Back to Bitmap ────────────────────────────────────────────────
        val outData = ByteArray(n * 3)
        outRgb.get(0, 0, outData)
        outRgb.release()

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(n)
        for (i in 0 until n) {
            val ri = outData[i * 3].toInt()     and 0xFF
            val gi = outData[i * 3 + 1].toInt() and 0xFF
            val bi = outData[i * 3 + 2].toInt() and 0xFF
            outPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        out
    }.getOrElse { e ->
        Log.w(TAG, "enhance failed — returning original bitmap", e)
        bitmap
    }

    // Per-channel min/max stretch.  Flat channels (min==max) are left alone.
    private fun stretchChannel(channel: ByteArray) {
        var min = 255; var max = 0
        for (b in channel) {
            val v = b.toInt() and 0xFF
            if (v < min) min = v
            if (v > max) max = v
        }
        if (min >= max) return
        val range = (max - min).toFloat()
        for (i in channel.indices) {
            val v = (channel[i].toInt() and 0xFF) - min
            channel[i] = (v / range * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
    }

    // Adaptive chroma boost for a single OpenCV 8-bit Lab channel.
    // Neutral center = 128; values near center boosted more, values far from
    // center (high saturation) boosted progressively less to avoid clipping.
    private fun boostChroma(channel: Mat): Mat {
        val n = channel.rows() * channel.cols()
        val data = ByteArray(n)
        channel.get(0, 0, data)

        val result = ByteArray(n)
        for (i in data.indices) {
            val centered = (data[i].toInt() and 0xFF) - 128  // -128..127
            val magnitude = abs(centered)
            val scale = if (magnitude > CHROMA_THRESHOLD) {
                val excess = magnitude - CHROMA_THRESHOLD
                (SAT_SCALE * (1f - excess / (MAX_CHROMA - CHROMA_THRESHOLD))).coerceAtLeast(1f)
            } else {
                SAT_SCALE
            }
            result[i] = (centered * scale + 128f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }

        val out = Mat(channel.rows(), channel.cols(), CvType.CV_8U)
        out.put(0, 0, result)
        return out
    }
}
