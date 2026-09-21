package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

import android.net.Uri

/** A portable recipe format for LUT-based looks shared by deep links and presets. */
data class FilmRecipe(
    val version: Int = 1,
    val name: String = "Untitled",
    val stock: String = "default",
    val intensity: Float = 1f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val grain: Float = 0f,
    val bloom: Float = 0f,
    val haze: Float = 0f,
    val fade: Float = 0f,
    val clarity: Float = 0f,
    val vignette: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val lightLeak: Float = 0f,
    val lightLeakStyle: String = "off",
    val distortion: Float = 0f,
) {
    /** Resolve bundled stocks without pretending a missing exact stock is identical. */
    fun bundledLutAsset(): String? = when (stock.lowercase()) {
        "golden_hour_200", "golden-gold-200", "golden_gold_200" ->
            "luts/Warm Tones/Golden Hour.cube"
        else -> null
    }

    /** Apply the recipe to a new macro. Unsupported recipe fields stay documented here. */
    fun toMacro(lutUri: String = bundledLutAsset().orEmpty()): UserMacro = UserMacro(
        lutCubeUri = lutUri,
        lutIntensity = intensity.coerceIn(0f, 1f),
        lutEdited = lutUri.isNotEmpty(),
        exposure = exposure,
        contrast = contrast,
        highlights = highlights,
        shadows = shadows,
        filmGrain = (grain / 100f).coerceIn(0f, 1f),
        ortonStrength = (bloom / 100f).coerceIn(0f, 1f),
        fxMist = haze.coerceIn(0f, 100f),
        dehaze = clarity.coerceIn(-25f, 25f),
        vignetteAmount = vignette.coerceIn(-100f, 100f),
        whiteBalance = temperature.roundToInt(),
        tint = tint,
        vibrance = vibrance.coerceIn(-100f, 100f),
        saturation = saturation.coerceIn(-100f, 100f),
    )

    companion object {
        /** Parse neofilm://recipe?... without relying on Android intent state. */
        fun parse(url: String): FilmRecipe? {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "neofilm" || uri.host?.lowercase() != "recipe") return null
            fun text(key: String, fallback: String) = uri.getQueryParameter(key) ?: fallback
            fun number(key: String, fallback: Float) =
                text(key, fallback.toString()).toFloatOrNull() ?: fallback
            return FilmRecipe(
                version = text("ver", "1").toIntOrNull() ?: 1,
                name = text("name", "Untitled"),
                stock = text("stock", "default"),
                intensity = (number("int", 100f) / 100f).coerceIn(0f, 1f),
                exposure = number("exp", 0f),
                contrast = number("cntr", 0f),
                highlights = number("hlgt", 0f),
                shadows = number("shdw", 0f),
                grain = number("g", 0f),
                bloom = number("blm", 0f),
                haze = number("h", 0f),
                fade = number("f", 0f),
                clarity = number("d", 0f),
                vignette = number("vign", 0f),
                temperature = number("temp", 0f),
                tint = number("tint", 0f),
                vibrance = number("vib", 0f),
                saturation = number("sat", 0f),
                lightLeak = if (text("lls", "off").equals("off", true)) 0f else number("llk", 0f),
                lightLeakStyle = text("lls", "off"),
                distortion = number("dst", 0f),
            )
        }
    }
}

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
