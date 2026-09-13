/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class LayersProcessor {

    /**
     * @param subjectMask flat float array [0,1] per pixel (U2Net output). May be null when mask
     *                    is not yet computed — in that case subject-aware options are ignored.
     */
    suspend fun apply(
        bitmap: Bitmap,
        selection: LayersSelection,
        subjectMask: FloatArray? = null,
    ): Bitmap = withContext(Dispatchers.Default) {
        if (selection.isEmpty) return@withContext bitmap
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val px = IntArray(n)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        var result = px

        if (!selection.vignette.isEmpty) {
            result = applyVignette(result, w, h, selection.vignette, subjectMask)
        }

        if (!selection.linearGradient.isEmpty) {
            result = applyLinearGradient(result, w, h, selection.linearGradient, subjectMask)
        }

        Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }

    // ── Vignette ──────────────────────────────────────────────────────────────

    private fun applyVignette(
        px: IntArray,
        w: Int,
        h: Int,
        v: VignetteSelection,
        subjectMask: FloatArray?,
    ): IntArray {
        val result = IntArray(px.size)
        val cx = v.centerX * w
        val cy = v.centerY * h
        val aspect = if (h > 0) w.toFloat() / h else 1f
        val power = if (v.roundness >= 0f) 2f else 2f + (1f - v.roundness) * 18f
        val midStart = v.midpoint
        val featherWidth = v.feather.coerceAtLeast(0.01f)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]

                val dx = (x - cx) / (w * 0.5f)
                val dy = (y - cy) / (h * 0.5f) * aspect
                val dist = (abs(dx).pow(power) + abs(dy).pow(power)).pow(1f / power)
                val t = ((dist - midStart) / featherWidth).coerceIn(0f, 1f)
                val mask = t * t * (3f - 2f * t)  // smoothstep

                // Subject exclusion: when includeSubject=false, don't darken subject pixels
                val subjectWeight = if (!v.includeSubject && subjectMask != null)
                    (1f - subjectMask[i].coerceIn(0f, 1f))
                else 1f

                val darkening = mask * v.intensity * subjectWeight

                val r = (((p shr 16) and 0xFF) * (1f - darkening) + 0.5f).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) * (1f - darkening) + 0.5f).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) * (1f - darkening) + 0.5f).toInt().coerceIn(0, 255)
                result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return result
    }

    // ── Linear Gradient ───────────────────────────────────────────────────────

    private fun applyLinearGradient(
        px: IntArray,
        w: Int,
        h: Int,
        lg: LinearGradientSelection,
        subjectMask: FloatArray?,
    ): IntArray {
        val result = px.copyOf()
        val angleRad = Math.toRadians(lg.angle.toDouble())
        val cosA = cos(angleRad).toFloat()
        val sinA = sin(angleRad).toFloat()

        if (!lg.top.isEmpty) applyGradientSide(result, w, h, lg.top, Side.Top, cosA, sinA, lg.applyTo, subjectMask)
        if (!lg.bottom.isEmpty) applyGradientSide(result, w, h, lg.bottom, Side.Bottom, cosA, sinA, lg.applyTo, subjectMask)
        if (!lg.left.isEmpty) applyGradientSide(result, w, h, lg.left, Side.Left, cosA, sinA, lg.applyTo, subjectMask)
        if (!lg.right.isEmpty) applyGradientSide(result, w, h, lg.right, Side.Right, cosA, sinA, lg.applyTo, subjectMask)
        return result
    }

    private enum class Side { Top, Bottom, Left, Right }

    private fun applyGradientSide(
        px: IntArray,
        w: Int,
        h: Int,
        side: LinearGradientSide,
        which: Side,
        cosA: Float,
        sinA: Float,
        applyTo: GradientApplyTo,
        subjectMask: FloatArray?,
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Normalize pixel to [-1, 1] then rotate by -angle (inverse rotation of the
                // coordinate frame so the gradient "source direction" rotates by +angle).
                val pxN = x.toFloat() / w * 2f - 1f
                val pyN = y.toFloat() / h * 2f - 1f
                // Rotate the pixel position by -angle so "Top" at angle=0° is truly at the top,
                // and at angle=90° the Top gradient comes from the right side.
                val rx = pxN * cosA + pyN * sinA
                val ry = -pxN * sinA + pyN * cosA
                // Convert rotated [-1,1] coords back to [0,1] for distance computation.
                val rx01 = ((rx + 1f) / 2f).coerceIn(0f, 1f)
                val ry01 = ((ry + 1f) / 2f).coerceIn(0f, 1f)

                val edgeT = when (which) {
                    Side.Top    -> ry01
                    Side.Bottom -> 1f - ry01
                    Side.Left   -> rx01
                    Side.Right  -> 1f - rx01
                }
                val normalizedT = edgeT / side.length.coerceAtLeast(0.01f)
                val rawMask = 1f - normalizedT / side.feather.coerceAtLeast(0.01f)
                val gradMask = rawMask.coerceIn(0f, 1f)
                if (gradMask <= 0f) continue

                // Region masking (for darkening)
                val i = y * w + x
                val regionWeight = when (applyTo) {
                    GradientApplyTo.All        -> 1f
                    GradientApplyTo.Background -> if (subjectMask != null) (1f - subjectMask[i].coerceIn(0f, 1f)) else 1f
                    GradientApplyTo.Subject    -> if (subjectMask != null) subjectMask[i].coerceIn(0f, 1f) else 0f
                }
                if (regionWeight <= 0f && side.tintLuminosity == 0f) continue

                val effectiveMask = gradMask * regionWeight
                val darkening = effectiveMask * side.intensity

                val p = px[i]
                var r = (p shr 16) and 0xFF
                var g = (p shr 8) and 0xFF
                var b = p and 0xFF

                r = (r * (1f - darkening) + 0.5f).toInt().coerceIn(0, 255)
                g = (g * (1f - darkening) + 0.5f).toInt().coerceIn(0, 255)
                b = (b * (1f - darkening) + 0.5f).toInt().coerceIn(0, 255)

                // Color tint: blend pixel toward tintColor at tintLuminosity strength,
                // with its own per-side region mask independent from the darkening region.
                if (side.tintLuminosity > 0f) {
                    val tintRegionWeight = when (side.tintApplyTo) {
                        GradientApplyTo.All        -> 1f
                        GradientApplyTo.Background -> if (subjectMask != null) (1f - subjectMask[i].coerceIn(0f, 1f)) else 1f
                        GradientApplyTo.Subject    -> if (subjectMask != null) subjectMask[i].coerceIn(0f, 1f) else 0f
                    }
                    val tintStrength = gradMask * tintRegionWeight * side.tintLuminosity
                    if (tintStrength > 0f) {
                        val tr = (side.tintColor shr 16) and 0xFF
                        val tg = (side.tintColor shr 8) and 0xFF
                        val tb = side.tintColor and 0xFF

                        if (side.blendMode == GradientBlendMode.Fused) {
                            val pixelHsl = FloatArray(3)
                            val tintHsl = FloatArray(3)
                            rgbToHsl(r, g, b, pixelHsl)
                            rgbToHsl(tr, tg, tb, tintHsl)

                            val fusedRgb = IntArray(3)
                            // Keep original lightness, take tint hue and saturation
                            hslToRgb(tintHsl[0], tintHsl[1], pixelHsl[2], fusedRgb)

                            r = (r + (fusedRgb[0] - r) * tintStrength + 0.5f).toInt().coerceIn(0, 255)
                            g = (g + (fusedRgb[1] - g) * tintStrength + 0.5f).toInt().coerceIn(0, 255)
                            b = (b + (fusedRgb[2] - b) * tintStrength + 0.5f).toInt().coerceIn(0, 255)
                        } else {
                            r = (r + (tr - r) * tintStrength + 0.5f).toInt().coerceIn(0, 255)
                            g = (g + (tg - g) * tintStrength + 0.5f).toInt().coerceIn(0, 255)
                            b = (b + (tb - b) * tintStrength + 0.5f).toInt().coerceIn(0, 255)
                        }
                    }
                }

                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int, hsl: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val h: Float
        val s: Float
        val l = (max + min) / 2f

        if (max == min) {
            h = 0f
            s = 0f
        } else {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                rf -> (gf - bf) / d + (if (gf < bf) 6f else 0f)
                gf -> (bf - rf) / d + 2f
                else -> (rf - gf) / d + 4f
            } / 6f
        }
        hsl[0] = h * 360f
        hsl[1] = s
        hsl[2] = l
    }

    private fun hslToRgb(h: Float, s: Float, l: Float, rgb: IntArray) {
        val hc = h / 360f
        val r: Float
        val g: Float
        val b: Float
        if (s == 0f) {
            r = l
            g = l
            b = l
        } else {
            fun hue2rgb(p: Float, q: Float, t: Float): Float {
                var tc = t
                if (tc < 0f) tc += 1f
                if (tc > 1f) tc -= 1f
                if (tc < 1f/6f) return p + (q - p) * 6f * tc
                if (tc < 1f/2f) return q
                if (tc < 2f/3f) return p + (q - p) * (2f/3f - tc) * 6f
                return p
            }
            val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
            val p = 2f * l - q
            r = hue2rgb(p, q, hc + 1f/3f)
            g = hue2rgb(p, q, hc)
            b = hue2rgb(p, q, hc - 1f/3f)
        }
        rgb[0] = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
        rgb[1] = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
        rgb[2] = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
