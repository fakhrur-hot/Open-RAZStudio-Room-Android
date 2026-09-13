/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * HaldCLUT bridge — the universal LUT importer.
 *
 * A HaldCLUT is a full 3D LUT laid out as a square image. You take an *identity*
 * Hald (every pixel = its own position-mapped RGB), run it through ANY colour
 * app (Capture One, Lightroom, Photoshop, DaVinci, G'MIC…), and the processed
 * image now encodes that app's entire colour transform. We reconstruct a native
 * .cube from it — so a closed-source style (e.g. a Capture One .costyle that
 * can't be parsed directly) becomes a LUT we can apply.
 *
 * Geometry: a HaldCLUT of "level" n has
 *   • image side  = n³   (e.g. level 8 → 512×512)
 *   • cube edge   = n²   (level 8 → 64³)
 *   • pixel count = n⁶   = (n²)³ = side²
 * Raster order is b·S² + g·S + r (red fastest) — identical to the Adobe .cube
 * ordering — so the image's raster pixels ARE the cube entries in order. We then
 * trilinearly resample to [outSize]³ (33 by default) for a compact, pipeline-
 * standard cube.
 *
 * Colour space: input is treated as sRGB and the produced cube is sRGB→sRGB,
 * matching the app's sRGB-locked pipeline. (A wide-gamut/ProPhoto variant would
 * be a separate pipeline change.)
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import android.graphics.Bitmap
import kotlin.math.cbrt
import kotlin.math.roundToInt

object HaldClutConverter {

    /**
     * Image extensions we treat as a HaldCLUT. PNG/JPEG decode via BitmapFactory;
     * TIFF (incl. 16-bit) decodes via OpenCV in [LocalLutRepository.decodeHaldImage]
     * (BitmapFactory can't read TIFF). 16-bit is tone-mapped to 8-bit before the
     * float cube is built — visually lossless for a LUT.
     */
    val SUPPORTED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "tif", "tiff")

    /** The HaldCLUT level for a square image edge, or null if [side] isn't n³ (n≥2). */
    private fun haldLevel(side: Int): Int? {
        if (side < 8) return null
        val n = cbrt(side.toDouble()).roundToInt()
        return if (n in 2..64 && n * n * n == side) n else null
    }

    /** True if [bmp] is plausibly a HaldCLUT (square, side == level³). */
    fun isHaldClut(bmp: Bitmap): Boolean = bmp.width == bmp.height && haldLevel(bmp.width) != null

    /**
     * Reconstruct a [outSize]³ Adobe .cube string from a (transformed) HaldCLUT
     * [bmp], or null if [bmp] isn't a valid square HaldCLUT.
     */
    fun haldBitmapToCubeString(bmp: Bitmap, outSize: Int = 33): String? {
        val w = bmp.width
        if (w != bmp.height) return null
        val level = haldLevel(w) ?: return null
        val s = level * level                  // source cube edge
        val count = w * w                      // == s³
        val px = IntArray(count)
        bmp.getPixels(px, 0, w, 0, 0, w, w)

        // Raster pixel i == cube entry i (b·s² + g·s + r). Decode to a float cube.
        val src = FloatArray(count * 3)
        for (i in 0 until count) {
            val c = px[i]
            src[i * 3]     = ((c ushr 16) and 0xFF) / 255f
            src[i * 3 + 1] = ((c ushr 8)  and 0xFF) / 255f
            src[i * 3 + 2] = ( c          and 0xFF) / 255f
        }

        // Trilinearly resample s³ → outSize³ and emit the .cube.
        val sb = StringBuilder(outSize * outSize * outSize * 22 + 64)
        sb.append("LUT_3D_SIZE ").append(outSize).append('\n')
        val fmax = (s - 1).toFloat()
        val denom = (outSize - 1).toFloat()
        for (bi in 0 until outSize) {
            val fb = bi / denom * fmax
            for (gi in 0 until outSize) {
                val fg = gi / denom * fmax
                for (ri in 0 until outSize) {
                    val fr = ri / denom * fmax
                    appendSample(sb, src, s, fr, fg, fb)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Generate a level-[level] identity HaldCLUT bitmap. Default level 8 →
     * 512×512 (a 64³ LUT) — the common exchange size. Save it, run it through
     * another app's style, then re-import the result via [haldBitmapToCubeString].
     */
    fun generateIdentityHald(level: Int = 8): Bitmap {
        val s = level * level
        val side = level * level * level
        val px = IntArray(side * side)
        val d = (s - 1).toFloat()
        var i = 0
        for (b in 0 until s) for (g in 0 until s) for (r in 0 until s) {
            val rr = (r / d * 255f).roundToInt()
            val gg = (g / d * 255f).roundToInt()
            val bb = (b / d * 255f).roundToInt()
            px[i++] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
    }

    // Trilinear sample of the s³ float cube at (fr,fg,fb) ∈ [0, s-1] → append "r g b\n".
    private fun appendSample(sb: StringBuilder, cube: FloatArray, s: Int, fr: Float, fg: Float, fb: Float) {
        val r0 = fr.toInt().coerceIn(0, s - 1); val r1 = (r0 + 1).coerceAtMost(s - 1); val dr = fr - r0
        val g0 = fg.toInt().coerceIn(0, s - 1); val g1 = (g0 + 1).coerceAtMost(s - 1); val dg = fg - g0
        val b0 = fb.toInt().coerceIn(0, s - 1); val b1 = (b0 + 1).coerceAtMost(s - 1); val db = fb - b0
        for (ch in 0 until 3) {
            fun at(r: Int, g: Int, b: Int) = cube[((b * s + g) * s + r) * 3 + ch]
            val c00 = at(r0, g0, b0) * (1 - dr) + at(r1, g0, b0) * dr
            val c10 = at(r0, g1, b0) * (1 - dr) + at(r1, g1, b0) * dr
            val c01 = at(r0, g0, b1) * (1 - dr) + at(r1, g0, b1) * dr
            val c11 = at(r0, g1, b1) * (1 - dr) + at(r1, g1, b1) * dr
            val c0 = c00 * (1 - dg) + c10 * dg
            val c1 = c01 * (1 - dg) + c11 * dg
            val v = (c0 * (1 - db) + c1 * db).coerceIn(0f, 1f)
            sb.append("%.6f".format(v))
            sb.append(if (ch < 2) ' ' else '\n')
        }
    }
}
