/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Smart Color Enhancement — bitmap-level pre-pass applied before the main
 *  macro pixel loop when [UserMacro.smartColorEnhance] is true.
 *
 *  Color only. Lab L is copied through unchanged.
 *    1. Gray-world cast removal — scale each channel by target/average.
 *       The frame mean of R, G, and B meets. Channels are not stretched
 *       to 0..255.
 *    2. Chroma magnitude vibrance. Weak colors move more, skin hue least.
 *       a' = a * Cout/C, b' = b * Cout/C. Hue direction stays.
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
import kotlin.math.atan2
import kotlin.math.sqrt

internal object SmartColorEnhancer {

    private const val TAG = "SmartColorEnhancer"

    private const val SAT_SCALE = 1.35f

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

        // ── 2. Gray-world cast removal. Ratios only, no full-range stretch.
        grayWorld(rBuf, gBuf, bBuf)

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

        // L is not modified.
        val aEnhanced = Mat()
        val bEnhanced = Mat()
        boostChromaMagnitude(aCh, bCh, aEnhanced, bEnhanced)
        aCh.release()
        bCh.release()

        // ── 8. Merge and convert back to RGB ─────────────────────────────────
        Core.merge(listOf(lCh, aEnhanced, bEnhanced), labMat)
        lCh.release(); aEnhanced.release(); bEnhanced.release()

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

    private fun grayWorld(rBuf: ByteArray, gBuf: ByteArray, bBuf: ByteArray) {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        for (i in rBuf.indices) {
            sumR += rBuf[i].toInt() and 0xFF
            sumG += gBuf[i].toInt() and 0xFF
            sumB += bBuf[i].toInt() and 0xFF
        }
        val n = rBuf.size.coerceAtLeast(1).toDouble()
        val avgR = (sumR / n).coerceAtLeast(1.0)
        val avgG = (sumG / n).coerceAtLeast(1.0)
        val avgB = (sumB / n).coerceAtLeast(1.0)
        val target = (avgR + avgG + avgB) / 3.0
        val gR = target / avgR
        val gG = target / avgG
        val gB = target / avgB
        for (i in rBuf.indices) {
            rBuf[i] = ((rBuf[i].toInt() and 0xFF) * gR).toInt().coerceIn(0, 255).toByte()
            gBuf[i] = ((gBuf[i].toInt() and 0xFF) * gG).toInt().coerceIn(0, 255).toByte()
            bBuf[i] = ((bBuf[i].toInt() and 0xFF) * gB).toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun boostChromaMagnitude(aCh: Mat, bCh: Mat, aOut: Mat, bOut: Mat) {
        val n = aCh.rows() * aCh.cols()
        val aData = ByteArray(n)
        val bData = ByteArray(n)
        aCh.get(0, 0, aData)
        bCh.get(0, 0, bData)
        val aRes = ByteArray(n)
        val bRes = ByteArray(n)
        for (i in aData.indices) {
            val a = (aData[i].toInt() and 0xFF) - 128f
            val b = (bData[i].toInt() and 0xFF) - 128f
            val c = sqrt(a * a + b * b)
            val scale = if (c < 0.5f) {
                1f
            } else {
                val hue = atan2(b, a)
                val skin = smooth(0.15f, 0.45f, hue) * (1f - smooth(0.95f, 1.25f, hue))
                val weak = (1f - (c / 80f)).coerceIn(0f, 1f)
                1f + (SAT_SCALE - 1f) * weak * (1f - 0.75f * skin)
            }
            aRes[i] = (a * scale + 128f).toInt().coerceIn(0, 255).toByte()
            bRes[i] = (b * scale + 128f).toInt().coerceIn(0, 255).toByte()
        }
        aOut.create(aCh.rows(), aCh.cols(), CvType.CV_8U)
        bOut.create(bCh.rows(), bCh.cols(), CvType.CV_8U)
        aOut.put(0, 0, aRes)
        bOut.put(0, 0, bRes)
    }
}
