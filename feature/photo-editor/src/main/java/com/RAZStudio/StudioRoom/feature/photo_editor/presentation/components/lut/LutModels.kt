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
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

/**
 * A single LUT preset entry.
 *
 * [originalKey] is non-null only for copies placed in the Favorite category.
 * It holds the source "CategoryName/entryName" key so that selection highlighting
 * and path resolution stay in sync with the original entry.
 */
data class LutEntry(
    val name: String,
    val assetPath: String?,
    val filePath: String?,
    val originalKey: String? = null,
) {
    val isUserCustom: Boolean get() = filePath != null
}

/**
 * A folder of [LutEntry] items grouped under a [categoryName].
 */
data class LutCategory(
    val categoryName: String,
    val entries: List<LutEntry>,
)

internal const val FAVORITE_CATEGORY = "Favorite"
internal const val USER_CUSTOM_CATEGORY = "User's Lut"
internal const val CORRECTION_CATEGORY = "Correction"
internal const val ASSETS_LUT_ROOT = "luts"
internal const val MAX_FAVORITES = 10

/**
 * Runtime pick defaults for stock packs (no re-bake).
 * Black & White packs default to 100% intensity; the shader/CPU mix keeps
 * chroma locked (see [isBlackAndWhiteLutPath] / ShaderParams.lutBwForce).
 */
data class LutPickDefaults(
    val intensity: Float = 1f,
    /** Modest filmRolloff to apply only when the macro's current value is 0. */
    val autoFilmRolloff: Float = 0f,
    val categoryHint: String? = null,
    val isFilmStyle: Boolean = false,
    val isBlackAndWhite: Boolean = false,
)

/** True when [pathOrUri] resolves under the stock Black & White pack folder or matches a stock B&W LUT. */
fun isBlackAndWhiteLutPath(pathOrUri: String): Boolean {
    if (pathOrUri.isBlank()) return false
    val p = pathOrUri.replace('\\', '/')
    if (p.contains("luts/Black & White/", ignoreCase = true) ||
        p.contains("/Black & White/", ignoreCase = true) ||
        p.contains("Black & White", ignoreCase = true) ||
        p.contains("Black%20&%20White", ignoreCase = true) ||
        p.contains("Black%20%26%20White", ignoreCase = true) ||
        p.contains("Black+%26+White", ignoreCase = true)
    ) {
        return true
    }
    val fn = p.substringAfterLast('/').lowercase()
    return isStockBwFilename(fn)
}

/** Check known stock B&W filename prefixes and stems so flattened cache paths still lock chroma. */
private fun isStockBwFilename(fn: String): Boolean {
    return fn.startsWith("ilford") ||
        fn.startsWith("kodak_t-max") ||
        fn.startsWith("kodak_tmax") ||
        fn.startsWith("kodak_tri-x") ||
        fn.startsWith("kodak_bw_400") ||
        fn.startsWith("kodak panatomic") ||
        fn.startsWith("kodak plus-x") ||
        fn.startsWith("kodak recording") ||
        fn.startsWith("kodak technical pan") ||
        fn.startsWith("kodak verichrome") ||
        fn.startsWith("fuji_fp-3000b") ||
        fn.startsWith("fuji_neopan") ||
        fn.startsWith("fuji_xtrans_iii_acros") ||
        fn.startsWith("fuji_xtrans_iii_mono") ||
        fn.startsWith("fujifilm acros") ||
        fn.startsWith("polaroid_66") ||
        fn.startsWith("polaroid_672") ||
        fn.startsWith("polaroid_px-100uv") ||
        fn.startsWith("agfa_apx") ||
        fn.startsWith("agfa apx") ||
        fn.startsWith("agfa scala") ||
        fn.startsWith("rollei_") ||
        fn.startsWith("rollei retro") ||
        fn.startsWith("01 cyanotype") ||
        fn.startsWith("02 cyanotype") ||
        fn.startsWith("04 wet plate") ||
        fn.startsWith("06 platinum") ||
        fn.startsWith("15 solarization") ||
        fn.startsWith("21 lith") ||
        fn.startsWith("22 bw infrared") ||
        fn.startsWith("bw1") || fn.startsWith("bw2") || fn.startsWith("bw3") ||
        fn.startsWith("bw4") || fn.startsWith("bw5") || fn.startsWith("bw6") ||
        fn.startsWith("bw7") || fn.startsWith("bw8") || fn.startsWith("bw9") ||
        fn.startsWith("b&w") || fn.startsWith("ad1920") ||
        fn.startsWith("litho") || fn.startsWith("plate") || fn.startsWith("x400") ||
        fn.startsWith("selenium tone") || fn.startsWith("sepia tone") || fn.startsWith("sepiahigh") ||
        fn.startsWith("copper tone") || fn.startsWith("gold tone") ||
        fn.startsWith("dramatic monochrome") || fn.startsWith("fan ho") ||
        fn.startsWith("faux infrared") || fn.startsWith("foma fomapan") ||
        fn.startsWith("henri cartier-bresson") || fn.startsWith("kentmere") ||
        fn.startsWith("michael kenna") || fn.startsWith("peter lindbergh") ||
        fn.startsWith("ricoh high-contrast") || fn.startsWith("sebastiao salgado") ||
        fn.startsWith("split warm-cool") || fn.startsWith("tri-x 400") ||
        fn.startsWith("vivian maier") || fn.startsWith("zone focus")
}

fun lutPickDefaultsFor(categoryName: String?, pathOrUri: String = ""): LutPickDefaults {
    val cat = (categoryName ?: "").ifBlank {
        // Infer from cache / asset path segments when favorites omit the folder.
        val p = pathOrUri.replace('\\', '/')
        when {
            isBlackAndWhiteLutPath(p) || p.contains("Black & White", ignoreCase = true) ->
                "Black & White"
            p.contains("Contrast & Correction", ignoreCase = true) -> "Contrast & Correction"
            p.contains("Color Slide", ignoreCase = true) -> "Color Slide"
            p.contains("RAZ Looks", ignoreCase = true) -> "RAZ Looks"
            p.contains("Cinematic", ignoreCase = true) -> "Cinematic"
            p.contains("Film Emulation", ignoreCase = true) -> "Film Emulation"
            p.contains("Color Boost", ignoreCase = true) -> "Color Boost"
            p.contains("Warm Tones", ignoreCase = true) -> "Warm Tones"
            p.contains("Cool Tones", ignoreCase = true) -> "Cool Tones"
            p.contains("Soft & Pastel", ignoreCase = true) -> "Soft & Pastel"
            p.contains("Instant", ignoreCase = true) -> "Instant"
            else -> ""
        }
    }
    val lower = cat.lowercase()
    return when {
        lower.contains("black") && lower.contains("white") -> LutPickDefaults(
            intensity = 1f,
            autoFilmRolloff = 0f,
            categoryHint = null,
            isFilmStyle = false,
            isBlackAndWhite = true,
        )
        // Technical/utility looks: apply at full strength, no film rolloff.
        lower.contains("contrast") && lower.contains("correction") -> LutPickDefaults(
            intensity = 1f,
            autoFilmRolloff = 0f,
            categoryHint = null,
            isFilmStyle = false,
        )
        // Creative film-style looks share the same softened defaults.
        lower.contains("color slide") || lower.contains("raz looks") ||
            lower.contains("cinematic") || lower.contains("film emulation") ||
            lower.contains("color boost") || lower.contains("warm tones") ||
            lower.contains("cool tones") || lower.contains("soft & pastel") ||
            lower.contains("instant") -> LutPickDefaults(
            intensity = 0.85f,
            autoFilmRolloff = 0.12f,
            categoryHint = null,
            isFilmStyle = true,
        )
        else -> LutPickDefaults()
    }
}

/** Per-hue HSL adjustment (Hue Shift / Saturation / Luminance). */
data class HslAdjustment(
    val hueShift: Float = 0f,   // -1..+1 → -180..+180°
    val saturation: Float = 0f, // -1..+1
    val luminance: Float = 0f,  // -1..+1
) {
    val isEmpty: Boolean get() = hueShift == 0f && saturation == 0f && luminance == 0f
}

/**
 * Global per-preset photo adjustments — no AI/segmentation layer involved.
 * For RAW files these will map directly to libraw parameters.
 *
 * [exposure]   −2..+2 EV
 * [contrast]   −1..+1
 * [saturation] −1..+1
 * [vibrance]   −1..+1 (saturation boost weighted by pixel desaturation)
 * [hslRed]..[hslMagenta] — 8-hue HSL panel, each collapsed by default in the UI
 */
data class GlobalPhotoAdjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val hslRed: HslAdjustment = HslAdjustment(),
    val hslOrange: HslAdjustment = HslAdjustment(),
    val hslYellow: HslAdjustment = HslAdjustment(),
    val hslGreen: HslAdjustment = HslAdjustment(),
    val hslCyan: HslAdjustment = HslAdjustment(),
    val hslBlue: HslAdjustment = HslAdjustment(),
    val hslPurple: HslAdjustment = HslAdjustment(),
    val hslMagenta: HslAdjustment = HslAdjustment(),
) {
    val isEmpty: Boolean get() = exposure == 0f && contrast == 0f && saturation == 0f && vibrance == 0f &&
        hslRed.isEmpty && hslOrange.isEmpty && hslYellow.isEmpty && hslGreen.isEmpty &&
        hslCyan.isEmpty && hslBlue.isEmpty && hslPurple.isEmpty && hslMagenta.isEmpty
}

/**
 * Holds the complete LUT + AI adjustment selection state.
 *
 * [strength]             overall LUT blend strength, 0..1
 * [globalAdjustments]    global photo adjustments (exposure/contrast/sat/vibrance/HSL) — no AI
 * [aiLutEnabled]         LUT Control — enables subject/background/tone adjustments
 * [subjectPop]           subject local contrast boost, -1..1
 * [backgroundBrightness] background brightness offset, -1..1
 * [shadowBoost]          shadow boost via sigmoid (−1..1, centered at 10 % luma)
 * [highlightBoost]       highlight boost via sigmoid (−1..1, centered at 85 % luma)
 * [edgeBlackClip]        0..1 — Clarity/Texture local contrast boost on sharp-edge pixels
 * [subjectTemperature]   subject color temperature shift, −1..+1 (cool-warm)
 * [backgroundTemperature] background color temperature shift, −1..+1
 * [subjectTint]          subject green-magenta tint, −1..+1 (negative=green, positive=magenta)
 * [backgroundTint]       background green-magenta tint, −1..+1
 * [highlightTemperature] highlight color temperature shift, −1..+1 (weighted by highlight sigmoid)
 * [highlightTint]        highlight green-magenta tint, −1..+1 (weighted by highlight sigmoid)
 * [zeroDce]             enable Zero-DCE automatic exposure fix, applied before LUT
 * [zeroDceStrength]     blend strength for Zero-DCE result, 0..1
 */
data class LutSelection(
    val path: String,
    val strength: Float = 1f,
    val globalAdjustments: GlobalPhotoAdjustments = GlobalPhotoAdjustments(),
    val aiLutEnabled: Boolean = false,
    val subjectPop: Float = 0f,
    val backgroundBrightness: Float = 0f,
    val shadowBoost: Float = 0f,
    val highlightBoost: Float = 0f,
    val edgeBlackClip: Float = 0f,
    val subjectTemperature: Float = 0f,
    val backgroundTemperature: Float = 0f,
    val subjectTint: Float = 0f,
    val backgroundTint: Float = 0f,
    val highlightTemperature: Float = 0f,
    val highlightTint: Float = 0f,
    val zeroDce: Boolean = false,
    val zeroDceStrength: Float = 1f,
)
