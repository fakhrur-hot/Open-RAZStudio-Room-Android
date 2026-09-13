/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

/**
 * OkLab color space — Björn Ottosson, 2020 (https://bottosson.github.io/posts/oklab/).
 *
 * Used by the FP16 macro pipeline for hue / saturation / vibrance operations that
 * must preserve extended-range (>1.0) linear-light values. Unlike sRGB HSL/HSV,
 * OkLab's `L` axis is luminance-like and the `(a, b)` chroma axes have no
 * upper bound — operating on `(a, b)` and converting back is HDR-safe.
 *
 * Domain: linear-sRGB / LINEAR_EXTENDED_SRGB. Negative chroma values are
 * tolerated end-to-end; the round-trip is invertible for any real-valued RGB.
 *
 * Cost per pixel (rgb→Lab→rgb): ~12 mul + 3 cbrt + 3 cube. NEON-friendly,
 * fits inside MacroProcessor's existing per-pixel loop without measurable
 * overhead vs. the old sRGB HSL math (which itself had min/max + branches).
 */
internal object OkLabMath {

    // ── Forward: linear sRGB → LMS → cube root → Lab ─────────────────────────

    /**
     * Convert linear-sRGB `(r, g, b)` to OkLab `(L, a, b)`. Writes into `out`
     * (length 3) to avoid allocating a Triple per pixel.
     *
     * The cube root uses `Math.cbrt` which handles negative inputs correctly
     * (returns the real cube root) — important because linear values from
     * extreme white-balance corrections can be slightly negative.
     */
    fun linearSrgbToOklab(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val l_ = Math.cbrt(l.toDouble()).toFloat()
        val m_ = Math.cbrt(m.toDouble()).toFloat()
        val s_ = Math.cbrt(s.toDouble()).toFloat()

        out[0] = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_   // L
        out[1] = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_   // a
        out[2] = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_   // b
    }

    // ── Inverse: Lab → cube → LMS → linear sRGB ──────────────────────────────

    /** Convert OkLab `(L, a, b)` back to linear-sRGB into `out` (length 3). */
    fun oklabToLinearSrgb(L: Float, a: Float, b: Float, out: FloatArray) {
        val l_ = L + 0.3963377774f * a + 0.2158037573f * b
        val m_ = L - 0.1055613458f * a - 0.0638541728f * b
        val s_ = L - 0.0894841775f * a - 1.2914855480f * b

        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_

        out[0] =  4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        out[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        out[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    }

    // ── Convenience: chroma and hue extraction (no allocations) ──────────────

    /** Polar chroma `sqrt(a² + b²)` of an OkLab pixel. */
    fun chroma(a: Float, b: Float): Float = kotlin.math.sqrt(a * a + b * b)

    /** Polar hue in radians, range `(-π, π]`. */
    fun hueRadians(a: Float, b: Float): Float = kotlin.math.atan2(b, a)
}
