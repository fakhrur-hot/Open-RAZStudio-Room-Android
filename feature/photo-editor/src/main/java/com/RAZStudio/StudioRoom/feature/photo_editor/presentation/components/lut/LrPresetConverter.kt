/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import android.util.Log
import kotlin.math.*

/**
 * Converts Lightroom presets to 33-point cube LUTs. Accepts every container
 * Adobe ships a preset in: `.xmp` (XML), `.lrtemplate` (Lua) and `.dng`
 * (a "preset DNG" — a tiny JPEG proxy whose TIFF tag 700 holds the same XMP
 * packet; see [extractXmpPacket]). All three carry byte-identical `crs:`
 * settings, and this converter is verified to produce bit-identical LUTs from
 * them (Kodak preset, 2026-09-07).
 *
 * Supported settings (v3):
 *   • Basic tone: Exposure, Contrast, Highlights, Shadows, Whites, Blacks
 *   • Tone curves: Point curve (ToneCurvePV2012) + per-channel RGB curves
 *   • Parametric curve: ParametricShadows/Darks/Lights/Highlights + splits
 *   • Presence: Saturation, Vibrance
 *   • HSL: Hue/Saturation/Luminance per color (Red..Magenta)
 *   • Split Toning: Highlight/Shadow hue/saturation + balance
 *   • Camera Calibration: Shadow tint + RGB primary hue/saturation
 *   • Color Grading (PV11 / Lightroom 2020+): shadow/midtone/highlight/global
 *     hue+sat+lum, blending and balance
 *   • Black & White: ConvertToGrayscale + the 8-channel GrayMixer
 *   • Legacy process versions (PV2003/PV2010): Exposure, Brightness, Contrast,
 *     Recovery, FillLight and the old `ToneCurve` point curve
 *   • Named point curves (Linear / Medium Contrast / Strong Contrast) for
 *     presets that name a curve instead of listing its points
 *
 * Not supported (these are spatial / non-color operators that cannot be baked
 * into a pure 3D color LUT): Texture, Clarity, Dehaze, Sharpening, Noise
 * Reduction, Vignette, Grain, Lens Corrections.
 *
 * Also NOT baked, deliberately: White Balance (Temperature/Tint). Those act on
 * the raw sensor data before demosaic; re-deriving them from an already-
 * developed sRGB pixel is guesswork, and a LUT that guessed would fight the
 * editor's own WB control. A warning is logged when a preset carries one.
 */
object LrPresetConverter {

    private const val TAG = "LrPresetConverter"

    data class LrSettings(val raw: Map<String, String>) {
        fun num(key: String, default: Double = 0.0): Double =
            raw[key]?.let { normalizeNumber(it).toDoubleOrNull() } ?: default

        fun str(key: String, default: String = ""): String =
            raw[key]?.trim('"') ?: default

        /** True if the key exists with a non-empty value. */
        fun has(key: String): Boolean = !raw[key].isNullOrBlank()

        companion object {
            fun normalizeNumber(v: String): String {
                val t = v.trim('"').trim()
                return if (t.startsWith("+")) t.substring(1) else t
            }
        }
    }

    enum class SourceFormat { LRTEMPLATE, XMP, UNKNOWN }

    /**
     * Pull the XMP packet out of a binary Adobe container — a Lightroom
     * "preset DNG" keeps the preset in TIFF tag 700 exactly as a sidecar .xmp
     * would. Scans the bytes for the packet delimiters rather than walking the
     * IFD: it is one pass, needs no TIFF parser, and works for any container
     * that embeds XMP (DNG, JPEG, TIFF, PSD).
     *
     * Returns null when there is no packet, which the caller reports as an
     * unreadable preset rather than silently importing nothing.
     */
    fun extractXmpPacket(bytes: ByteArray): String? {
        val head = "<x:xmpmeta".toByteArray(Charsets.US_ASCII)
        val tail = "</x:xmpmeta>".toByteArray(Charsets.US_ASCII)
        fun indexOf(needle: ByteArray, from: Int): Int {
            outer@ for (i in from..bytes.size - needle.size) {
                for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
        val start = indexOf(head, 0)
        if (start < 0) return null
        val end = indexOf(tail, start)
        if (end < 0) return null
        val text = String(bytes, start, end + tail.size - start, Charsets.UTF_8)
        Log.i(TAG, "extractXmpPacket: found ${text.length} chars at offset $start")
        return text
    }

    /**
     * Robust format detection. Checks multiple signatures because user preset
     * files may be missing the usual XML prologue or may use different key names.
     */
    private fun detectFormat(text: String): SourceFormat {
        val t = text.trimStart()
        return when {
            // XMP signatures
            t.startsWith("<?xml") -> SourceFormat.XMP
            t.contains("<x:xmpmeta") -> SourceFormat.XMP
            t.contains("xmlns:crs=") -> SourceFormat.XMP
            t.contains("crs:Exposure2012") -> SourceFormat.XMP
            t.contains("crs:Contrast2012") -> SourceFormat.XMP
            t.contains("crs:ToneCurvePV2012") -> SourceFormat.XMP
            // LRTEMPLATE signatures
            t.contains("s = {") -> SourceFormat.LRTEMPLATE
            t.contains("settings = {") -> SourceFormat.LRTEMPLATE
            t.contains("Exposure2012") && t.contains("Contrast2012") -> SourceFormat.LRTEMPLATE
            t.contains("ToneCurvePV2012") && t.contains("settings") -> SourceFormat.LRTEMPLATE
            else -> SourceFormat.UNKNOWN
        }
    }

    // ─── Lua parser ───────────────────────────────────────────────────

    private object LrTemplateParser {
        private fun extractSettingsBlock(text: String): String {
            val idx = text.indexOf("settings")
            if (idx < 0) return ""
            val braceStart = text.indexOf('{', idx)
            if (braceStart < 0) return ""
            var depth = 0
            var i = braceStart
            while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return text.substring(braceStart, i + 1) }
                }
                i++
            }
            return ""
        }

        private fun stripNestedTables(block: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < block.length) {
                val c = block[i]
                if (c == '{') {
                    var depth = 1; i++
                    while (i < block.length && depth > 0) {
                        when (block[i]) { '{' -> depth++; '}' -> depth-- }
                        i++
                    }
                    sb.append(" ")
                    continue
                }
                sb.append(c); i++
            }
            return sb.toString()
        }

        private val kvRegex = Regex(
            """([A-Za-z_][A-Za-z0-9_]*)\s*=\s*("(?:[^"\\]|\\.)*"|[+-]?\d+\.?\d*|true|false)"""
        )

        fun parse(text: String): LrSettings {
            val settingsBlock = extractSettingsBlock(text)
            if (settingsBlock.isEmpty()) return LrSettings(emptyMap())
            val flatOnly = stripNestedTables(settingsBlock.substring(1, settingsBlock.length - 1))
            val map = LinkedHashMap<String, String>()
            for (m in kvRegex.findAll(flatOnly)) {
                map.putIfAbsent(m.groupValues[1], m.groupValues[2])
            }
            return LrSettings(map)
        }

        fun parseToneCurve(text: String, key: String = "ToneCurvePV2012"): List<Pair<Double, Double>> {
            val idx = text.indexOf("$key =")
            if (idx < 0) return emptyList()
            val braceStart = text.indexOf('{', idx)
            val braceEnd = text.indexOf('}', braceStart)
            if (braceStart < 0 || braceEnd < 0) return emptyList()
            val nums = text.substring(braceStart + 1, braceEnd)
                .split(',')
                .mapNotNull { it.trim().toDoubleOrNull() }
            val pts = mutableListOf<Pair<Double, Double>>()
            var i = 0
            while (i + 1 < nums.size) { pts.add(nums[i] to nums[i + 1]); i += 2 }
            return pts
        }
    }

    // ─── XMP parser ───────────────────────────────────────────────────

    private object XmpParser {
        /** Matches both `crs:Key="value"` and `Key="value"` when crs is default. */
        private val attrRegex = Regex("""(?:crs:)?([A-Za-z0-9_]+)="([^"]*)""")

        fun parse(text: String): LrSettings {
            val map = LinkedHashMap<String, String>()
            for (m in attrRegex.findAll(text)) {
                map.putIfAbsent(m.groupValues[1], m.groupValues[2])
            }
            return LrSettings(map)
        }

        fun parseToneCurve(text: String, key: String = "ToneCurvePV2012"): List<Pair<Double, Double>> {
            val blockRegex = Regex(
                "<$key>\\s*<rdf:Seq>(.*?)</rdf:Seq>\\s*</$key>",
                RegexOption.DOT_MATCHES_ALL
            )
            val block = blockRegex.find(text)?.groupValues?.get(1) ?: return emptyList()
            val liRegex = Regex("<rdf:li>(.*?)</rdf:li>")
            return liRegex.findAll(block).mapNotNull { m ->
                val parts = m.groupValues[1].split(',').map { it.trim().toDoubleOrNull() }
                if (parts.size == 2 && parts[0] != null && parts[1] != null) parts[0]!! to parts[1]!! else null
            }.toList()
        }
    }

    // ─── Color helpers ────────────────────────────────────────────────

    private fun clamp01(x: Double) = x.coerceIn(0.0, 1.0)

    /** True when the preset is a black-and-white conversion. */
    private fun convertGrayNotice(s: LrSettings): Boolean =
        s.str("ConvertToGrayscale").equals("true", true) || s.str("ConvertToGrayscale") == "1"


    private fun srgbToLinear(c: Double): Double {
        val cc = clamp01(c)
        return if (cc <= 0.04045) cc / 12.92 else ((cc + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(c: Double): Double {
        val cc = clamp01(c)
        return if (cc <= 0.0031308) cc * 12.92 else 1.055 * cc.pow(1.0 / 2.4) - 0.055
    }

    private data class Hsl(val h: Double, val s: Double, val l: Double)

    private fun rgbToHsl(r: Double, g: Double, b: Double): Hsl {
        val maxc = maxOf(r, g, b)
        val minc = minOf(r, g, b)
        val l = (maxc + minc) / 2.0
        val d = maxc - minc
        if (d == 0.0) return Hsl(0.0, 0.0, l)
        val s = d / (1 - abs(2 * l - 1) + 1e-8)
        var h = when (maxc) {
            r -> ((g - b) / (d + 1e-8)).mod(6.0)
            g -> (b - r) / (d + 1e-8) + 2
            else -> (r - g) / (d + 1e-8) + 4
        }
        h = (h * 60.0).mod(360.0)
        return Hsl(h, s.coerceIn(0.0, 1.0), l)
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): Triple<Double, Double, Double> {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = h / 60.0
        val x = c * (1 - abs(hp.mod(2.0) - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2.0
        return Triple(clamp01(r1 + m), clamp01(g1 + m), clamp01(b1 + m))
    }

    /** Convert RGB to a hue bin index matching Lightroom's 8 HSL sliders. */
    private fun hueBin(hueDeg: Double): Int {
        val h = hueDeg.mod(360.0)
        return when {
            h < 15.0 || h >= 345.0 -> 0   // Red
            h < 45.0 -> 1                  // Orange
            h < 70.0 -> 2                  // Yellow
            h < 160.0 -> 3                 // Green
            h < 200.0 -> 4                 // Aqua
            h < 260.0 -> 5                 // Blue
            h < 310.0 -> 6                 // Purple
            else -> 7                      // Magenta
        }
    }

    private val HSL_NAMES = arrayOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")

    /** Hue centre of each Adobe HSL band, in degrees. */
    private val HSL_CENTERS = doubleArrayOf(0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 285.0, 330.0)

    /**
     * Weight of each of the 8 HSL bands for one hue, blended between the two
     * nearest band centres.
     *
     * Adobe's HSL bands overlap — a colour between orange and yellow takes part
     * of both sliders. Assigning a pixel to ONE band (which [hueBin] does, and
     * which this used until 2026-09-07) puts a hard seam wherever a hue crosses
     * a boundary; with a big shift such as the Kodak preset's Purple −63 that
     * seam is plainly visible as banding across a sky or a face.
     */
    private fun hslWeights(hueDeg: Double): DoubleArray {
        val h = hueDeg.mod(360.0)
        val w = DoubleArray(8)
        var lo = 7
        var hi = 0
        for (i in HSL_CENTERS.indices) {
            val c = HSL_CENTERS[i]
            if (c <= h) { lo = i; hi = (i + 1) % 8 }
        }
        if (h < HSL_CENTERS[0]) { lo = 7; hi = 0 }
        val cLo = HSL_CENTERS[lo]
        val cHi = HSL_CENTERS[hi] + if (hi <= lo) 360.0 else 0.0
        val hh = if (h < cLo) h + 360.0 else h
        val span = (cHi - cLo).takeIf { it > 1e-9 } ?: 1.0
        val t = ((hh - cLo) / span).coerceIn(0.0, 1.0)
        // Cosine blend: smooth, and the two weights always sum to 1.
        val smooth = t * t * (3 - 2 * t)
        w[lo] = 1.0 - smooth
        w[hi] = smooth
        return w
    }

    // ─── Pipeline ─────────────────────────────────────────────────────

    private class Pipeline(
        private val settings: LrSettings,
        private val toneCurve: List<Pair<Double, Double>>,
        private val toneCurveR: List<Pair<Double, Double>>,
        private val toneCurveG: List<Pair<Double, Double>>,
        private val toneCurveB: List<Pair<Double, Double>>,
    ) {
        /**
         * PV2012+ key, falling back to the PV2003/PV2010 name. A preset saved
         * before 2012 carries ONLY the legacy keys, so reading just the *2012
         * ones turned every such preset into an identity LUT.
         */
        private fun tone(modern: String, legacy: String? = null): Double = when {
            settings.has(modern) -> settings.num(modern)
            legacy != null -> settings.num(legacy)
            else -> 0.0
        }

        private val exposure = tone("Exposure2012", "Exposure")
        private val contrast = tone("Contrast2012", "Contrast") / 100.0
        private val highlights = tone("Highlights2012") / 100.0
        private val shadows = tone("Shadows2012") / 100.0
        private val whites = tone("Whites2012") / 100.0
        private val blacks = tone("Blacks2012") / 100.0

        // ── Legacy-only controls (PV2003/PV2010) ─────────────────────────────
        // Adobe replaced these in 2012; there is no exact modern equivalent, so
        // they are folded into the nearest 2012 control. Brightness is centred
        // on 50 and Recovery/FillLight run 0..100 in one direction only.
        private val legacyBrightness =
            if (settings.has("Brightness") && !settings.has("Exposure2012"))
                (settings.num("Brightness", 50.0) - 50.0) / 100.0 else 0.0
        private val legacyRecovery =
            if (settings.has("Recovery")) settings.num("Recovery") / 100.0 else 0.0
        private val legacyFillLight =
            if (settings.has("FillLight")) settings.num("FillLight") / 100.0 else 0.0

        // ── Black & White (Adobe's B&W Mix panel) ────────────────────────────
        private val convertToGray = settings.str("ConvertToGrayscale").equals("true", true) ||
            settings.str("ConvertToGrayscale") == "1"
        private val grayMix = DoubleArray(8) { i -> settings.num("GrayMixer${HSL_NAMES[i]}") / 100.0 }
        private val saturation = settings.num("Saturation") / 100.0
        private val vibrance = settings.num("Vibrance") / 100.0

        private val hslHue = DoubleArray(8) { i -> settings.num("HueAdjustment${HSL_NAMES[i]}") / 100.0 }
        private val hslSat = DoubleArray(8) { i -> settings.num("SaturationAdjustment${HSL_NAMES[i]}") / 100.0 }
        private val hslLum = DoubleArray(8) { i -> settings.num("LuminanceAdjustment${HSL_NAMES[i]}") / 100.0 }
        private val hasHsl = hslHue.any { it != 0.0 } || hslSat.any { it != 0.0 } || hslLum.any { it != 0.0 }

        private val splitHiHue = settings.num("SplitToningHighlightHue")
        private val splitHiSat = settings.num("SplitToningHighlightSaturation") / 100.0
        private val splitShHue = settings.num("SplitToningShadowHue")
        private val splitShSat = settings.num("SplitToningShadowSaturation") / 100.0
        private val splitBalance = settings.num("SplitToningBalance") / 100.0

        // Lightroom 2020+ (PV11) Color Grading. Adobe MIRRORS the shadow and
        // highlight hue/sat into the legacy SplitToning* keys — only the
        // luminance sliders, the midtone wheel and the global wheel are new — so
        // reading both and preferring the ColorGrade* key when present is the
        // mapping that reproduces either vintage of preset without double-
        // applying the shadow/highlight tint.
        private fun grade(newKey: String, legacyKey: String? = null): Double = when {
            settings.has(newKey) -> settings.num(newKey)
            legacyKey != null -> settings.num(legacyKey)
            else -> 0.0
        }

        private val gradeShHue = grade("ColorGradeShadowHue", "SplitToningShadowHue")
        private val gradeShSat = grade("ColorGradeShadowSat", "SplitToningShadowSaturation") / 100.0
        private val gradeShLum = settings.num("ColorGradeShadowLum") / 100.0
        private val gradeMidHue = settings.num("ColorGradeMidtoneHue")
        private val gradeMidSat = settings.num("ColorGradeMidtoneSat") / 100.0
        private val gradeMidLum = settings.num("ColorGradeMidtoneLum") / 100.0
        private val gradeHiHue = grade("ColorGradeHighlightHue", "SplitToningHighlightHue")
        private val gradeHiSat = grade("ColorGradeHighlightSat", "SplitToningHighlightSaturation") / 100.0
        private val gradeHiLum = settings.num("ColorGradeHighlightLum") / 100.0
        private val gradeGlobalHue = settings.num("ColorGradeGlobalHue")
        private val gradeGlobalSat = settings.num("ColorGradeGlobalSat") / 100.0
        private val gradeGlobalLum = settings.num("ColorGradeGlobalLum") / 100.0
        /** 0..100, Adobe default 100 = full strength. */
        private val gradeBlending =
            (if (settings.has("ColorGradeBlending")) settings.num("ColorGradeBlending") else 100.0) / 100.0

        private val hasSplitToning = gradeShSat != 0.0 || gradeHiSat != 0.0 ||
            gradeMidSat != 0.0 || gradeGlobalSat != 0.0 ||
            gradeShLum != 0.0 || gradeMidLum != 0.0 || gradeHiLum != 0.0 || gradeGlobalLum != 0.0

        // Adobe writes the Calibration panel as ShadowTint / <Colour>Hue /
        // <Colour>Saturation — NOT "CameraCalibrationRedPrimaryHue", which is
        // what this read until 2026-09-07 and which exists in no preset file, so
        // the whole panel silently did nothing. The long names are kept as a
        // fallback in case a third-party exporter emits them.
        private fun cal(vararg keys: String): Double =
            keys.firstOrNull { settings.has(it) }?.let { settings.num(it) / 100.0 } ?: 0.0

        private val calShadowTint = cal("ShadowTint", "CameraCalibrationShadowTint")
        private val calRedHue = cal("RedHue", "CameraCalibrationRedPrimaryHue")
        private val calRedSat = cal("RedSaturation", "CameraCalibrationRedPrimarySat")
        private val calGreenHue = cal("GreenHue", "CameraCalibrationGreenPrimaryHue")
        private val calGreenSat = cal("GreenSaturation", "CameraCalibrationGreenPrimarySat")
        private val calBlueHue = cal("BlueHue", "CameraCalibrationBluePrimaryHue")
        private val calBlueSat = cal("BlueSaturation", "CameraCalibrationBluePrimarySat")
        private val hasCalibration = calShadowTint != 0.0 || calRedHue != 0.0 || calRedSat != 0.0 ||
                calGreenHue != 0.0 || calGreenSat != 0.0 || calBlueHue != 0.0 || calBlueSat != 0.0

        private val paramShadows = settings.num("ParametricShadows") / 100.0
        private val paramDarks = settings.num("ParametricDarks") / 100.0
        private val paramLights = settings.num("ParametricLights") / 100.0
        private val paramHighlights = settings.num("ParametricHighlights") / 100.0
        private val paramShadowSplit = settings.num("ParametricShadowSplit") / 100.0
        private val paramMidtoneSplit = settings.num("ParametricMidtoneSplit") / 100.0
        private val paramHighlightSplit = settings.num("ParametricHighlightSplit") / 100.0
        private val hasParametric = paramShadows != 0.0 || paramDarks != 0.0 ||
                paramLights != 0.0 || paramHighlights != 0.0

        private fun applyBasicTone(rIn: Double, gIn: Double, bIn: Double): Triple<Double, Double, Double> {
            var r = linearToSrgb(srgbToLinear(rIn) * 2.0.pow(exposure))
            var g = linearToSrgb(srgbToLinear(gIn) * 2.0.pow(exposure))
            var b = linearToSrgb(srgbToLinear(bIn) * 2.0.pow(exposure))

            fun contrastFn(c: Double) = clamp01((c - 0.5) * (1 + contrast) + 0.5)
            r = contrastFn(r); g = contrastFn(g); b = contrastFn(b)

            fun toneRegion(c: Double): Double {
                val wHi = ((c - 0.5) * 2).coerceIn(0.0, 1.0)
                val wSh = ((0.5 - c) * 2).coerceIn(0.0, 1.0)
                val wWh = ((c - 0.75) * 4).coerceIn(0.0, 1.0)
                val wBl = ((0.25 - c) * 4).coerceIn(0.0, 1.0)
                var cc = c
                cc += highlights * 0.25 * wHi
                cc += shadows * 0.25 * wSh
                cc += whites * 0.2 * wWh
                cc += blacks * 0.2 * wBl
                // Legacy equivalents: Recovery pulls highlights down, FillLight
                // lifts shadows, Brightness is a midtone lift.
                cc -= legacyRecovery * 0.25 * wHi
                cc += legacyFillLight * 0.25 * wSh
                cc += legacyBrightness * 0.5 * (1.0 - abs(c - 0.5) * 2.0).coerceIn(0.0, 1.0)
                return clamp01(cc)
            }
            return Triple(toneRegion(r), toneRegion(g), toneRegion(b))
        }

        private fun interpolateCurve(pts: List<Pair<Double, Double>>, c: Double): Double {
            if (pts.isEmpty()) return c
            val xs = pts.map { it.first / 255.0 }
            val ys = pts.map { it.second / 255.0 }
            if (c <= xs.first()) return ys.first()
            if (c >= xs.last()) return ys.last()
            for (i in 0 until xs.size - 1) {
                if (c in xs[i]..xs[i + 1]) {
                    val t = (c - xs[i]) / (xs[i + 1] - xs[i] + 1e-12)
                    return ys[i] + t * (ys[i + 1] - ys[i])
                }
            }
            return c
        }

        /** Lightroom-style parametric curve applied to a scalar luminance-like value. */
        private fun applyParametricCurve(c: Double): Double {
            if (!hasParametric) return c
            // Convert input to symmetric -1..1 tone-space around mid-gray.
            val x = (c - 0.5) * 2.0
            // Region weights based on the four split points.
            val shadowEnd = paramShadowSplit * 2.0 - 1.0      // -1 .. 1
            val darkEnd = paramMidtoneSplit * 2.0 - 1.0
            val lightStart = paramMidtoneSplit * 2.0 - 1.0
            val highlightStart = paramHighlightSplit * 2.0 - 1.0

            val wSh = smoothStepDown(x, -1.0, shadowEnd)
            val wDk = smoothBell(x, shadowEnd, darkEnd)
            val wLt = smoothBell(x, lightStart, highlightStart)
            val wHi = smoothStepUp(x, highlightStart, 1.0)

            val delta = paramShadows * wSh + paramDarks * wDk + paramLights * wLt + paramHighlights * wHi
            return clamp01(c + delta * 0.25)
        }

        private fun smoothStepUp(x: Double, edge0: Double, edge1: Double): Double {
            if (x <= edge0) return 0.0
            if (x >= edge1) return 1.0
            val t = (x - edge0) / (edge1 - edge0)
            return t * t * (3 - 2 * t)
        }

        private fun smoothStepDown(x: Double, edge0: Double, edge1: Double): Double =
            1.0 - smoothStepUp(x, edge0, edge1)

        private fun smoothBell(x: Double, edge0: Double, edge1: Double): Double {
            val center = (edge0 + edge1) / 2.0
            val half = (edge1 - edge0) / 2.0
            if (half <= 0.0) return 0.0
            val t = ((x - center) / half).coerceIn(-1.0, 1.0)
            return (cos(t * PI / 2)).coerceAtLeast(0.0)
        }

        private fun applyToneCurve(c: Double): Double {
            val p = applyParametricCurve(c)
            return if (toneCurve.isNotEmpty()) interpolateCurve(toneCurve, p) else p
        }

        private fun applyVibranceSaturation(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
            val (h, s, l) = rgbToHsl(r, g, b)
            // Saturation slider: linear multiplier.
            // Vibrance: preserves already-saturated colors (uses sqrt(s) weighting).
            val satMult = 1.0 + saturation + vibrance * (1.0 - sqrt(s))
            val s2 = clamp01(s * satMult)
            return hslToRgb(h, s2, l)
        }

        private fun applyHsl(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
            if (!hasHsl) return Triple(r, g, b)
            val (h, s, l) = rgbToHsl(r, g, b)
            // Blend the bands (see hslWeights) — a hard band assignment seams.
            // Weight by saturation too: Adobe's HSL barely touches near-greys,
            // where "hue" is numerically unstable and a shift shows up as noise.
            val w = hslWeights(h)
            val sw = (s * 4.0).coerceIn(0.0, 1.0)
            var dHue = 0.0; var dSat = 0.0; var dLum = 0.0
            for (i in 0 until 8) {
                if (w[i] == 0.0) continue
                dHue += hslHue[i] * w[i]
                dSat += hslSat[i] * w[i]
                dLum += hslLum[i] * w[i]
            }
            val newH = (h + dHue * 30.0 * sw).mod(360.0)
            val newS = clamp01(s * (1.0 + dSat * sw))
            // Lightness moves TOWARD white/black rather than scaling, so a
            // +100 on a bright colour cannot clip to a flat patch.
            val lum = dLum * sw
            val newL = clamp01(if (lum >= 0.0) l + (1.0 - l) * lum * 0.5 else l * (1.0 + lum * 0.5))
            return hslToRgb(newH, newS, newL)
        }

        /**
         * Adobe's B&W Mix: each hue band brightens or darkens the grey it maps
         * to. Without this a monochrome preset produced a COLOUR LUT — the whole
         * point of the preset silently lost (fixed 2026-09-07).
         *
         * The per-band weights are the same smooth blend the HSL panel uses, and
         * the mix is scaled by saturation so a neutral pixel keeps its luminance
         * (a grey wall must not shift because "Red" was pulled down).
         */
        private fun applyGrayMixer(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
            val (h, s, _) = rgbToHsl(r, g, b)
            var y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if (grayMix.any { it != 0.0 }) {
                val w = hslWeights(h)
                var mix = 0.0
                for (i in 0 until 8) if (w[i] != 0.0) mix += grayMix[i] * w[i]
                y = clamp01(y + mix * s * 0.5)
            }
            return Triple(y, y, y)
        }

        private fun applySplitToning(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
            if (!hasSplitToning) return Triple(r, g, b)
            val luma = 0.299 * r + 0.587 * g + 0.114 * b

            // Balance shifts the shadow/highlight crossover. Default 0 → crossover at 0.5.
            val crossover = 0.5 + splitBalance * 0.25
            val wHi = smoothStepUp(luma, crossover - 0.15, crossover + 0.15)
            val wSh = 1.0 - smoothStepUp(luma, crossover - 0.35, crossover - 0.05)
            // Midtones peak at the crossover and fall away toward both ends, so
            // the three wheels sum to roughly one everywhere instead of stacking.
            val wMid = (1.0 - abs(luma - crossover) * 2.5).coerceIn(0.0, 1.0)

            fun tint(hue: Double, sat: Double, weight: Double): Triple<Double, Double, Double> {
                if (sat <= 0.0 || weight <= 0.0) return Triple(0.0, 0.0, 0.0)
                val (tr, tg, tb) = hslToRgb(hue, 1.0, 0.5)
                val k = sat * weight * gradeBlending
                return Triple(tr * k, tg * k, tb * k)
            }

            val (hiR, hiG, hiB) = tint(gradeHiHue, gradeHiSat, wHi)
            val (mdR, mdG, mdB) = tint(gradeMidHue, gradeMidSat, wMid)
            val (shR, shG, shB) = tint(gradeShHue, gradeShSat, wSh)
            // The global wheel applies everywhere at full weight.
            val (glR, glG, glB) = tint(gradeGlobalHue, gradeGlobalSat, 1.0)

            // Per-range luminance: lift or drop the pixel where that range lives.
            val lumDelta = (gradeShLum * wSh + gradeMidLum * wMid + gradeHiLum * wHi +
                gradeGlobalLum) * 0.25 * gradeBlending

            return Triple(
                clamp01(r + hiR + mdR + shR + glR + lumDelta),
                clamp01(g + hiG + mdG + shG + glG + lumDelta),
                clamp01(b + hiB + mdB + shB + glB + lumDelta)
            )
        }

        private fun applyCalibration(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
            if (!hasCalibration) return Triple(r, g, b)

            // Shadow tint: shift shadow RGB toward magenta or green.
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            val shadowWeight = 1.0 - luma  // darker pixels affected more
            val tintR = 1.0 + calShadowTint * 0.2 * shadowWeight
            val tintG = 1.0 - calShadowTint * 0.3 * shadowWeight
            val tintB = 1.0 + calShadowTint * 0.15 * shadowWeight

            // Primary hue/saturation shifts are approximated by rotating the
            // corresponding channel region in HSL space.
            val (h, s, l) = rgbToHsl(r * tintR, g * tintG, b * tintB)
            val bin = hueBin(h)
            val (hueShift, satShift) = when (bin) {
                0 -> calRedHue to calRedSat      // Red
                3 -> calGreenHue to calGreenSat  // Green
                5 -> calBlueHue to calBlueSat    // Blue
                else -> 0.0 to 0.0
            }
            val newH = (h + hueShift * 30.0).mod(360.0)
            val newS = clamp01(s * (1.0 + satShift))
            return hslToRgb(newH, newS, l)
        }

        fun process(rIn: Double, gIn: Double, bIn: Double): Triple<Double, Double, Double> {
            // 1. Basic tone (exposure, contrast, highlights, shadows, whites, blacks)
            val basic = applyBasicTone(rIn, gIn, bIn)

            // 2. Master + per-channel tone curves
            var r = applyToneCurve(basic.first)
            var g = applyToneCurve(basic.second)
            var b = applyToneCurve(basic.third)
            if (toneCurveR.isNotEmpty()) r = interpolateCurve(toneCurveR, r)
            if (toneCurveG.isNotEmpty()) g = interpolateCurve(toneCurveG, g)
            if (toneCurveB.isNotEmpty()) b = interpolateCurve(toneCurveB, b)

            // 3. Saturation / Vibrance
            val vib = applyVibranceSaturation(r, g, b)

            // 4. HSL (skipped for B&W — the mix below replaces it)
            val hsl = if (convertToGray) vib else applyHsl(vib.first, vib.second, vib.third)

            // 4b. B&W mix. Runs BEFORE toning so a split-toned monochrome preset
            //     (sepia, selenium, duotone) tints the grey, as Adobe does.
            val mono = if (convertToGray) applyGrayMixer(hsl.first, hsl.second, hsl.third) else hsl

            // 5. Split Toning / Color Grading
            val split = applySplitToning(mono.first, mono.second, mono.third)

            // 6. Camera Calibration
            val cal = applyCalibration(split.first, split.second, split.third)

            return Triple(clamp01(cal.first), clamp01(cal.second), clamp01(cal.third))
        }
    }

    /**
     * Adobe's built-in point curves, for presets that NAME a curve instead of
     * listing its points (ToneCurveName2012 = "Medium Contrast"). Values are
     * ACR's classic control points in 0..255 input/output space.
     */
    private fun namedCurve(name: String): List<Pair<Double, Double>> = when (name.trim().lowercase()) {
        "medium contrast" -> listOf(0.0 to 0.0, 32.0 to 22.0, 64.0 to 56.0,
            128.0 to 128.0, 192.0 to 196.0, 255.0 to 255.0)
        "strong contrast" -> listOf(0.0 to 0.0, 32.0 to 16.0, 64.0 to 50.0,
            128.0 to 128.0, 192.0 to 202.0, 255.0 to 255.0)
        // "Linear" and "Custom" need no synthetic curve: linear is identity, and
        // a custom curve always ships its own points.
        else -> emptyList()
    }

    /**
     * Converts .lrtemplate or .xmp text to a 33-point cube LUT string.
     * Returns null on parse failure or unsupported format.
     */
    fun convertToCubeString(presetText: String, cubeSize: Int = 33): String? {
        return try {
            Log.i(TAG, "convertToCubeString: input ${presetText.length} bytes")
            val format = detectFormat(presetText)
            Log.i(TAG, "convertToCubeString: detected format=$format")
            if (format == SourceFormat.UNKNOWN) {
                Log.w(TAG, "Could not detect preset format (first 200 chars: ${presetText.take(200)})")
                return null
            }

            val settings = when (format) {
                SourceFormat.XMP -> {
                    Log.i(TAG, "convertToCubeString: parsing XMP...")
                    XmpParser.parse(presetText)
                }
                SourceFormat.LRTEMPLATE -> {
                    Log.i(TAG, "convertToCubeString: parsing LRTEMPLATE...")
                    LrTemplateParser.parse(presetText)
                }
                SourceFormat.UNKNOWN -> return null
            }

            // Point curve: PV2012 first, then the PV2003/PV2010 `ToneCurve`, then
            // the named built-in. A preset that names "Medium Contrast" without
            // listing points used to lose its curve entirely.
            var curve = when (format) {
                SourceFormat.XMP -> XmpParser.parseToneCurve(presetText, "crs:ToneCurvePV2012")
                SourceFormat.LRTEMPLATE -> LrTemplateParser.parseToneCurve(presetText, "ToneCurvePV2012")
                SourceFormat.UNKNOWN -> emptyList()
            }
            if (curve.isEmpty()) {
                curve = when (format) {
                    SourceFormat.XMP -> XmpParser.parseToneCurve(presetText, "crs:ToneCurve")
                    SourceFormat.LRTEMPLATE -> LrTemplateParser.parseToneCurve(presetText, "ToneCurve")
                    SourceFormat.UNKNOWN -> emptyList()
                }
                if (curve.isNotEmpty()) Log.i(TAG, "using legacy PV2003 ToneCurve (${curve.size} points)")
            }
            if (curve.isEmpty()) {
                val name = settings.str("ToneCurveName2012").ifEmpty { settings.str("ToneCurveName") }
                curve = namedCurve(name)
                if (curve.isNotEmpty()) Log.i(TAG, "using built-in curve '$name'")
            }
            val curveR = when (format) {
                SourceFormat.XMP -> XmpParser.parseToneCurve(presetText, "crs:ToneCurvePV2012Red")
                SourceFormat.LRTEMPLATE -> LrTemplateParser.parseToneCurve(presetText, "ToneCurvePV2012Red")
                SourceFormat.UNKNOWN -> emptyList()
            }
            val curveG = when (format) {
                SourceFormat.XMP -> XmpParser.parseToneCurve(presetText, "crs:ToneCurvePV2012Green")
                SourceFormat.LRTEMPLATE -> LrTemplateParser.parseToneCurve(presetText, "ToneCurvePV2012Green")
                SourceFormat.UNKNOWN -> emptyList()
            }
            val curveB = when (format) {
                SourceFormat.XMP -> XmpParser.parseToneCurve(presetText, "crs:ToneCurvePV2012Blue")
                SourceFormat.LRTEMPLATE -> LrTemplateParser.parseToneCurve(presetText, "ToneCurvePV2012Blue")
                SourceFormat.UNKNOWN -> emptyList()
            }

            // Tell the user what a 3D LUT structurally cannot carry, instead of
            // dropping it silently (WB is raw-domain; the rest is spatial).
            val wb = settings.str("WhiteBalance")
            if ((wb.isNotEmpty() && wb != "As Shot") || settings.num("IncrementalTemperature") != 0.0) {
                Log.w(TAG, "preset sets White Balance ($wb) — NOT baked into the LUT (raw-domain)")
            }
            val spatial = listOf("Clarity2012", "Clarity", "Texture", "Dehaze",
                "GrainAmount", "PostCropVignetteAmount", "Sharpness", "LuminanceSmoothing",
                "ColorNoiseReduction", "DefringePurpleAmount", "DefringeGreenAmount")
                .filter { settings.num(it) != 0.0 }
            if (spatial.isNotEmpty()) Log.w(TAG, "preset sets spatial controls not bakeable to a LUT: $spatial")
            // A preset can also POINT AT a profile (a creative "Look" or a camera
            // profile) whose table lives in a separate .dcp/.xmp we do not have.
            // Nothing in the preset text can reproduce it, so say so.
            val lookName = settings.str("Look").ifEmpty { settings.str("Name") }
            if (presetText.contains("crs:Look") && lookName.isNotEmpty()) {
                Log.w(TAG, "preset references profile Look '$lookName' — its table is NOT in this file")
            }
            if (convertGrayNotice(settings)) Log.i(TAG, "monochrome preset — B&W mix applied")

            Log.i(TAG, "Detected format: $format, converting to $cubeSize-point cube")

            val pipeline = Pipeline(settings, curve, curveR, curveG, curveB)
            val sb = StringBuilder()
            sb.append("TITLE \"Converted Lightroom Preset\"\n")
            sb.append("LUT_3D_SIZE $cubeSize\n")
            sb.append("DOMAIN_MIN 0.0 0.0 0.0\n")
            sb.append("DOMAIN_MAX 1.0 1.0 1.0\n")

            val step = 1.0 / (cubeSize - 1)
            for (bi in 0 until cubeSize) {
                val bVal = bi * step
                for (gi in 0 until cubeSize) {
                    val gVal = gi * step
                    for (ri in 0 until cubeSize) {
                        val rVal = ri * step
                        val (r, g, b) = pipeline.process(rVal, gVal, bVal)
                        sb.append("%.6f %.6f %.6f\n".format(r, g, b))
                    }
                }
            }

            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed: ${e.message}", e)
            null
        }
    }
}
