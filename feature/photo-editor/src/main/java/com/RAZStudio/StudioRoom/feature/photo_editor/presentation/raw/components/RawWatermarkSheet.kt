/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.RAZStudio.StudioRoom.core.resources.Icons as AppIcons
import com.RAZStudio.StudioRoom.core.resources.R as CoreR
import com.RAZStudio.StudioRoom.core.resources.icons.AddPhotoAlt
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.FormatAlignCenter
import com.RAZStudio.StudioRoom.core.resources.icons.FormatAlignLeft
import com.RAZStudio.StudioRoom.core.resources.icons.FormatAlignRight
import com.RAZStudio.StudioRoom.core.resources.icons.FormatBold
import com.RAZStudio.StudioRoom.core.resources.icons.FormatItalic

import com.RAZStudio.StudioRoom.core.ui.widget.color_picker.ColorPickerSheet
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

// ── Position enum ─────────────────────────────────────────────────────────────

enum class WatermarkPosition(val label: String) {
    TOP_LEFT("↖ TL"),
    TOP_CENTER("↑ TC"),
    TOP_RIGHT("↗ TR"),
    CENTER("✛ C"),
    BOTTOM_LEFT("↙ BL"),
    BOTTOM_CENTER("↓ BC"),
    BOTTOM_RIGHT("↘ BR"),
}

// ── Text size presets ──────────────────────────────────────────────────────────

enum class WatermarkTextSize(val label: String, val fraction: Float) {
    Small("S",  0.020f),
    Medium("M", 0.020f * 1.15f),
    Large("L",  0.020f * 1.15f * 1.15f),
    ExtraLarge("XL", 0.020f * 1.15f * 1.15f * 1.15f),
}

enum class WatermarkTextAlign { Left, Center, Right }

// ── Watermark font model ───────────────────────────────────────────────────────

/** Identifies a font for the burn pipeline — resolved to android.graphics.Typeface at draw time. */
sealed class WatermarkFont(val displayName: String) {
    // App bundled fonts (res/font)
    data object Default    : WatermarkFont("Default")
    data object Montserrat : WatermarkFont("Montserrat")
    data object Caveat     : WatermarkFont("Caveat")
    data object Comfortaa  : WatermarkFont("Comfortaa")
    data object Nunito     : WatermarkFont("Nunito")
    data object DejaVu     : WatermarkFont("DejaVu")
    data object BadScript  : WatermarkFont("Bad Script")
    data object Nothing    : WatermarkFont("Nothing")
    data object Jura       : WatermarkFont("Jura")
    data object Tektur     : WatermarkFont("Tektur")
    data object Podkova    : WatermarkFont("Podkova")
    data object YsabeauSC  : WatermarkFont("Ysabeau SC")
    data object Handjet    : WatermarkFont("Handjet")
    data object RuslanDisplay   : WatermarkFont("Ruslan Display")
    data object Catterdale      : WatermarkFont("Catterdale")
    data object FRM32           : WatermarkFont("FRM32")
    data object TokeelyBrookings: WatermarkFont("Tokeely Brookings")
    data object WOPRTweaked     : WatermarkFont("WOPR Tweaked")
    data object AlegreyaSans    : WatermarkFont("Alegreya Sans")
    data object MinecraftGnu    : WatermarkFont("Minecraft GNU")
    data object GraniteFixed    : WatermarkFont("Granite Fixed")
    data object NokiaPixel      : WatermarkFont("Nokia Pixel")
    data object Ztivalia        : WatermarkFont("Ztivalia")
    data object Axotrel         : WatermarkFont("Axotrel")
    data object LcdOctagon      : WatermarkFont("LCD Octagon")
    data object LcdMoving       : WatermarkFont("LCD Moving")
    data object Unisource       : WatermarkFont("Unisource")
    data object InterExtraLight       : WatermarkFont("Inter ExtraLight")
    data object TeXGyreHerosCondensed : WatermarkFont("TeXGyre Heros Condensed")
    data object WorkSansExtraLight    : WatermarkFont("Work Sans ExtraLight")
    data object WorkSansLight         : WatermarkFont("Work Sans Light")
    data object KhulaLight            : WatermarkFont("Khula Light")
    data object Minimalistic          : WatermarkFont("Minimalistic")
    // System font — file path on device
    class SystemFile(val path: String) : WatermarkFont(
        File(path).nameWithoutExtension
            .replace(Regex("[-_]"), " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    ) {
        override fun equals(other: Any?) = other is SystemFile && path == other.path
        override fun hashCode() = path.hashCode()
    }

    fun key(): String = when (this) {
        is SystemFile -> "file:$path"
        else -> displayName
    }

    companion object {
        val bundled: List<WatermarkFont> by lazy {
            listOf(Montserrat, Caveat, Comfortaa, Nunito, DejaVu, BadScript,
                Nothing, Jura, Tektur, Podkova, YsabeauSC, Handjet, RuslanDisplay,
                Catterdale, FRM32, TokeelyBrookings, WOPRTweaked, AlegreyaSans,
                MinecraftGnu, GraniteFixed, NokiaPixel, Ztivalia, Axotrel,
                LcdOctagon, LcdMoving, Unisource,
                InterExtraLight, TeXGyreHerosCondensed, WorkSansExtraLight, WorkSansLight,
                KhulaLight, Minimalistic,
            ).sortedBy { it.displayName }
        }
        fun fromKey(key: String?, default: WatermarkFont = WorkSansLight): WatermarkFont = when {
            key == null -> default
            key == "Default" -> Default
            key.startsWith("file:") -> SystemFile(key.removePrefix("file:"))
            else -> bundled.find { it.displayName == key } ?: default
        }
        /** Readable display name for the system font file (strips path, extension, weight suffix). */
        private fun systemFontDisplayName(path: String): String =
            File(path).nameWithoutExtension
                .replace(Regex("[-_](Regular|Bold|Italic|Light|Medium|Thin|Black|SemiBold|VF).*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[-_]"), " ")
                .trim()
        /** Scan /system/fonts and /product/fonts for human-readable .ttf/.otf files. */
        fun loadSystemFonts(): List<SystemFile> {
            val dirs = listOf("/system/fonts", "/product/fonts")
            val skip = Regex("Noto(Sans|Serif)((?!Regular|-Regular|-VF).)*\\.ttf$|Emoji|Flags|Color|Clock|Symbol", RegexOption.IGNORE_CASE)
            return dirs.flatMap { dir ->
                File(dir).listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".ttf") || it.name.endsWith(".otf")) && !it.name.matches(skip) }
                    ?.map { SystemFile(it.absolutePath) }
                    ?: emptyList()
            }.distinctBy { systemFontDisplayName(it.path) }
             .sortedBy { systemFontDisplayName(it.path) }
        }
    }
}

/** Resolve a [WatermarkFont] to an optional Compose [FontFamily] for UI previews. */
fun WatermarkFont.toComposeFontFamily(ctx: Context): FontFamily? {
    if (this is WatermarkFont.SystemFile) {
        return runCatching { FontFamily(Font(File(path))) }.getOrNull()
    }
    val resId: Int? = when (this) {
        WatermarkFont.Default       -> null
        WatermarkFont.Montserrat    -> CoreR.font.montserrat_regular
        WatermarkFont.Caveat        -> CoreR.font.caveat_regular
        WatermarkFont.Comfortaa     -> CoreR.font.comfortaa_regular
        WatermarkFont.Nunito        -> CoreR.font.nunito_variable
        WatermarkFont.DejaVu        -> CoreR.font.dejavu_regular
        WatermarkFont.BadScript     -> CoreR.font.bad_script_regular
        WatermarkFont.Nothing       -> CoreR.font.nothing_font_regular
        WatermarkFont.Jura          -> CoreR.font.jura_variable
        WatermarkFont.Tektur        -> CoreR.font.tektur_variable
        WatermarkFont.Podkova       -> CoreR.font.podkova_variable
        WatermarkFont.YsabeauSC     -> CoreR.font.ysabeau_sc_variable
        WatermarkFont.Handjet       -> CoreR.font.handjet_varibale
        WatermarkFont.RuslanDisplay -> CoreR.font.ruslan_display_regular
        WatermarkFont.Catterdale    -> CoreR.font.cattedrale_regular
        WatermarkFont.FRM32         -> CoreR.font.frm32_regular
        WatermarkFont.TokeelyBrookings -> CoreR.font.tokeely_brookings_regular
        WatermarkFont.WOPRTweaked   -> CoreR.font.wopr_tweaked_regular
        WatermarkFont.AlegreyaSans  -> CoreR.font.alegreya_sans_regular
        WatermarkFont.MinecraftGnu  -> CoreR.font.minecraft_gnu_regular
        WatermarkFont.GraniteFixed  -> CoreR.font.granite_fixed_regular
        WatermarkFont.NokiaPixel    -> CoreR.font.nokia_pixel_regular
        WatermarkFont.Ztivalia      -> CoreR.font.ztivalia_regular
        WatermarkFont.Axotrel       -> CoreR.font.axotrel_regular
        WatermarkFont.LcdOctagon    -> CoreR.font.lcd_octagon_regular
        WatermarkFont.LcdMoving     -> CoreR.font.lcd_moving_regular
        WatermarkFont.Unisource     -> CoreR.font.unisource_regular
        WatermarkFont.InterExtraLight          -> CoreR.font.inter_extralight
        WatermarkFont.TeXGyreHerosCondensed    -> CoreR.font.texgyreheros_condensed_regular
        WatermarkFont.WorkSansExtraLight       -> CoreR.font.work_sans_extralight
        WatermarkFont.WorkSansLight            -> CoreR.font.work_sans_light
        WatermarkFont.KhulaLight               -> CoreR.font.khula_light
        WatermarkFont.Minimalistic             -> CoreR.font.minimalistic_regular
        is WatermarkFont.SystemFile -> null
    }
    return resId?.let { runCatching { FontFamily(Font(it)) }.getOrNull() }
}

/** Resolve a [WatermarkFont] to an android.graphics.Typeface for canvas drawing. */
fun WatermarkFont.toTypeface(ctx: Context, bold: Boolean = false, italic: Boolean = false): Typeface {
    val style = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    if (this is WatermarkFont.SystemFile) {
        return runCatching { Typeface.createFromFile(path) }
            .getOrDefault(Typeface.defaultFromStyle(style))
            .let { Typeface.create(it, style) }
    }
    val resId: Int? = when (this) {
        WatermarkFont.Default       -> null
        WatermarkFont.Montserrat    -> CoreR.font.montserrat_regular
        WatermarkFont.Caveat        -> CoreR.font.caveat_regular
        WatermarkFont.Comfortaa     -> CoreR.font.comfortaa_regular
        WatermarkFont.Nunito        -> CoreR.font.nunito_variable
        WatermarkFont.DejaVu        -> CoreR.font.dejavu_regular
        WatermarkFont.BadScript     -> CoreR.font.bad_script_regular
        WatermarkFont.Nothing       -> CoreR.font.nothing_font_regular
        WatermarkFont.Jura          -> CoreR.font.jura_variable
        WatermarkFont.Tektur        -> CoreR.font.tektur_variable
        WatermarkFont.Podkova       -> CoreR.font.podkova_variable
        WatermarkFont.YsabeauSC     -> CoreR.font.ysabeau_sc_variable
        WatermarkFont.Handjet       -> CoreR.font.handjet_varibale
        WatermarkFont.RuslanDisplay -> CoreR.font.ruslan_display_regular
        WatermarkFont.Catterdale    -> CoreR.font.cattedrale_regular
        WatermarkFont.FRM32         -> CoreR.font.frm32_regular
        WatermarkFont.TokeelyBrookings -> CoreR.font.tokeely_brookings_regular
        WatermarkFont.WOPRTweaked   -> CoreR.font.wopr_tweaked_regular
        WatermarkFont.AlegreyaSans  -> CoreR.font.alegreya_sans_regular
        WatermarkFont.MinecraftGnu  -> CoreR.font.minecraft_gnu_regular
        WatermarkFont.GraniteFixed  -> CoreR.font.granite_fixed_regular
        WatermarkFont.NokiaPixel    -> CoreR.font.nokia_pixel_regular
        WatermarkFont.Ztivalia      -> CoreR.font.ztivalia_regular
        WatermarkFont.Axotrel       -> CoreR.font.axotrel_regular
        WatermarkFont.LcdOctagon    -> CoreR.font.lcd_octagon_regular
        WatermarkFont.LcdMoving     -> CoreR.font.lcd_moving_regular
        WatermarkFont.Unisource     -> CoreR.font.unisource_regular
        WatermarkFont.InterExtraLight          -> CoreR.font.inter_extralight
        WatermarkFont.TeXGyreHerosCondensed    -> CoreR.font.texgyreheros_condensed_regular
        WatermarkFont.WorkSansExtraLight       -> CoreR.font.work_sans_extralight
        WatermarkFont.WorkSansLight            -> CoreR.font.work_sans_light
        WatermarkFont.KhulaLight               -> CoreR.font.khula_light
        WatermarkFont.Minimalistic             -> CoreR.font.minimalistic_regular
        is WatermarkFont.SystemFile -> null
    }
    return if (resId != null) {
        runCatching { ResourcesCompat.getFont(ctx, resId) }
            .getOrNull()
            ?.let { Typeface.create(it, style) }
            ?: Typeface.defaultFromStyle(style)
    } else {
        Typeface.defaultFromStyle(style)
    }
}

// ── Config — no bitmap mutation until Save ────────────────────────────────────

sealed class WatermarkConfig {
    data class TextConfig(
        val text: String,         // legacy / header line
        val header: String = "",
        val description: String = "",
        val textAlign: WatermarkTextAlign = WatermarkTextAlign.Left,
        val position: WatermarkPosition,
        val color: Color,
        val sizePercent: Float,   // 0.02..0.20 fraction of image short side
        val opacity: Float,       // 0.1..1.0
        val bold: Boolean,
        val italic: Boolean = false,
        val font: WatermarkFont = WatermarkFont.WorkSansLight,
        val textShadow: Boolean = true,
        val letterSpacing: Float = 0f,   // em
        val lineSpacing: Float = 1.25f,  // multiplier on header text size
        val bgEnabled: Boolean = false,
        val bgColor: Int = android.graphics.Color.BLACK,
        val bgOpacity: Float = 0.6f,
    ) : WatermarkConfig()

    data class ImageConfig(
        val logoBitmap: Bitmap,
        val logoFileName: String,
        val position: WatermarkPosition,
        val sizePercent: Float,   // 0.05..0.50 logo width fraction of short side
        val opacity: Float,       // 0.1..1.0
    ) : WatermarkConfig()
}

// Footer gradient banner applied along the bottom edge of the image
enum class FooterGradientStyle { Plain, Dots }
enum class FooterGradientDirection { DownToUp, FeatherLeft, FeatherRight }

data class FooterGradientConfig(
    val color: Int = android.graphics.Color.BLACK,  // ARGB
    val intensity: Float = 0.7f,   // 0..1 opacity of the gradient peak
    val length: Float = 0.25f,     // DownToUp: fraction of image height; FeatherLeft/Right: band thickness fraction
    val style: FooterGradientStyle = FooterGradientStyle.Plain,
    val direction: FooterGradientDirection = FooterGradientDirection.DownToUp,
    // FeatherLeft/FeatherRight params
    val sideLength: Float = 0.40f,  // 0.10..1.0 — how far the solid portion extends
    val feather: Float = 0.30f,     // 0..1 — feather length toward the far edge
    val featherRight: Boolean = true, // derived from direction; kept for render compat
)

// EXIF strip config — white bar at the bottom with camera/shot info
/**
 * EXIF watermark visual themes, inspired by exif-frame (github.com/jeonghyeon-net/exif-frame).
 * Each theme controls the layout, colours, and typography of the EXIF overlay.
 */
enum class ExifTheme(val label: String) {
    /** White text with drop-shadow overlaid directly on the image — current minimal look. */
    Overlay("Overlay"),
    /** White strip footer: logo left, exposure right — like the "Strap" theme. */
    Strap("Strap"),
    /** Three-zone dark footer: exposure | camera | date — like Lightroom export. */
    Lightroom("Lightroom"),
    /** Orange text in corners on black letterbox bars — Film aesthetic. */
    Film("Film"),
    /** Exposure values spread evenly across a dark bottom bar — Monitor/HUD style. */
    Monitor("Monitor"),
    /** Single centered white line at bottom — minimal "Shot on" style. */
    ShotOn("Shot On"),
}

data class ExifWatermarkConfig(
    val make: String,
    val model: String,
    val lensModel: String,
    val iso: Int,
    val shutterSpeed: Float,   // seconds
    val aperture: Float,       // f-number
    val focalMm: Float,
    val dateTimeOriginal: String,
    val textSize: WatermarkTextSize = WatermarkTextSize.Small,
    val font: WatermarkFont = WatermarkFont.WorkSansLight,
    val theme: ExifTheme = ExifTheme.Overlay,
    val textShadow: Boolean = true,
    val textColor: Int = android.graphics.Color.WHITE,
    val showLogo: Boolean = true,
    val showBrand: Boolean = true,
    val showModel: Boolean = true,
    val showLens: Boolean = true,
    val showExposure: Boolean = true,
    val showDate: Boolean = false,
    val textAlign: WatermarkTextAlign = WatermarkTextAlign.Left,
    val bgEnabled: Boolean = false,
    val bgColor: Int = android.graphics.Color.BLACK,
    val bgOpacity: Float = 0.6f,
    val italic: Boolean = false,
    val letterSpacing: Float = 0f,   // em
    val lineSpacing: Float = 1.3f,   // multiplier on main text size
)

// Combined config when both text and image are enabled simultaneously
data class CombinedWatermarkConfig(
    val text: WatermarkConfig.TextConfig?,
    val image: WatermarkConfig.ImageConfig?,
    val footer: FooterGradientConfig? = null,
    val exif: ExifWatermarkConfig? = null,
)

// ── Persistence ───────────────────────────────────────────────────────────────

private const val PREFS_NAME = "watermark_prefs"
private const val KEY_TEXT_ENABLED   = "text_enabled"
private const val KEY_TEXT            = "text"
private const val KEY_TEXT_POS        = "text_pos"
private const val KEY_TEXT_COLOR      = "text_color"
private const val KEY_TEXT_SIZE       = "text_size"
private const val KEY_TEXT_OPACITY    = "text_opacity"
private const val KEY_TEXT_BOLD       = "text_bold"
private const val KEY_TEXT_SIZE_ENUM  = "text_size_enum"
private const val KEY_TEXT_FONT       = "text_font"
private const val KEY_TEXT_SHADOW     = "text_shadow"
private const val KEY_TEXT_ITALIC     = "text_italic"
private const val KEY_TEXT_LETTER_SPACING = "text_letter_spacing"
private const val KEY_TEXT_LINE_SPACING = "text_line_spacing"
private const val KEY_TEXT_BG_ENABLED = "text_bg_enabled"
private const val KEY_TEXT_BG_COLOR   = "text_bg_color"
private const val KEY_TEXT_BG_OPACITY = "text_bg_opacity"
private const val KEY_EXIF_SHADOW     = "exif_shadow"
private const val KEY_EXIF_ITALIC     = "exif_italic"
private const val KEY_EXIF_LETTER_SPACING = "exif_letter_spacing"
private const val KEY_EXIF_LINE_SPACING = "exif_line_spacing"
private const val KEY_IMG_ENABLED     = "img_enabled"
private const val KEY_IMG_POS         = "img_pos"
private const val KEY_IMG_SIZE        = "img_size"
private const val KEY_IMG_OPACITY     = "img_opacity"
private const val KEY_IMG_FILENAME    = "img_filename"
private const val LOGO_FILE_NAME      = "watermark_logo.png"
private const val KEY_EXIF_ENABLED      = "exif_enabled"
private const val KEY_EXIF_THEME        = "exif_theme"
private const val KEY_EXIF_FONT         = "exif_font"
private const val KEY_EXIF_SIZE_ENUM    = "exif_size_enum"
private const val KEY_EXIF_MAKE_OVERRIDE  = "exif_make_override"
private const val KEY_EXIF_MODEL_OVERRIDE = "exif_model_override"
private const val KEY_EXIF_LENS_OVERRIDE  = "exif_lens_override"
private const val KEY_EXIF_TEXT_COLOR     = "exif_text_color"
private const val KEY_EXIF_SHOW_LOGO      = "exif_show_logo"
private const val KEY_EXIF_SHOW_BRAND     = "exif_show_brand"
private const val KEY_EXIF_SHOW_MODEL     = "exif_show_model"
private const val KEY_EXIF_SHOW_LENS      = "exif_show_lens"
private const val KEY_EXIF_SHOW_EXPOSURE  = "exif_show_exposure"
private const val KEY_EXIF_SHOW_DATE      = "exif_show_date"
private const val KEY_EXIF_ALIGN          = "exif_align"
private const val KEY_EXIF_BG_ENABLED     = "exif_bg_enabled"
private const val KEY_EXIF_BG_COLOR       = "exif_bg_color"
private const val KEY_EXIF_BG_OPACITY     = "exif_bg_opacity"
private const val KEY_FOOTER_ENABLED    = "footer_enabled"
private const val KEY_FOOTER_COLOR      = "footer_color"
private const val KEY_FOOTER_INTENSITY  = "footer_intensity"
private const val KEY_FOOTER_LENGTH     = "footer_length"
private const val KEY_FOOTER_STYLE      = "footer_style"
private const val KEY_FOOTER_DIRECTION  = "footer_direction"
private const val KEY_FOOTER_SIDE_LEN   = "footer_side_length"
private const val KEY_FOOTER_FEATHER    = "footer_feather"
private const val KEY_FOOTER_FEATHER_R  = "footer_feather_right"

private fun watermarkPrefs(ctx: Context): SharedPreferences =
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

// ── Named watermark presets ───────────────────────────────────────────────────

private const val PREFS_PRESETS = "watermark_presets"
private const val KEY_PRESET_NAMES = "preset_names"

data class WatermarkPreset(val name: String, val prefsName: String)

fun listWatermarkPresets(ctx: Context): List<WatermarkPreset> {
    val prefs = ctx.getSharedPreferences(PREFS_PRESETS, Context.MODE_PRIVATE)
    val names = prefs.getString(KEY_PRESET_NAMES, null) ?: return emptyList()
    return names.split("\n").filter { it.isNotBlank() }.map { WatermarkPreset(it, "wm_preset_$it") }
}

fun saveWatermarkPreset(ctx: Context, name: String, cfg: CombinedWatermarkConfig) {
    val presets = ctx.getSharedPreferences(PREFS_PRESETS, Context.MODE_PRIVATE)
    val existing = presets.getString(KEY_PRESET_NAMES, null)?.split("\n")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
    if (!existing.contains(name)) existing.add(name)
    presets.edit().putString(KEY_PRESET_NAMES, existing.joinToString("\n")).apply()
    // Reuse saveCombinedWatermarkConfig but into a preset-named prefs file
    val presetPrefs = ctx.getSharedPreferences("wm_preset_$name", Context.MODE_PRIVATE)
    val t = cfg.text
    presetPrefs.edit().apply {
        putBoolean(KEY_TEXT_ENABLED, t != null)
        if (t != null) {
            putString(KEY_TEXT, t.text); putString(KEY_TEXT_POS, t.position.name)
            putInt(KEY_TEXT_COLOR, t.color.toArgb()); putFloat(KEY_TEXT_SIZE, t.sizePercent)
            putFloat(KEY_TEXT_OPACITY, t.opacity); putBoolean(KEY_TEXT_BOLD, t.bold)
            putString(KEY_TEXT_SIZE_ENUM, WatermarkTextSize.entries.minByOrNull { kotlin.math.abs(it.fraction - t.sizePercent) }?.name ?: WatermarkTextSize.Small.name)
            putString(KEY_TEXT_FONT, t.font.key()); putBoolean(KEY_TEXT_SHADOW, t.textShadow)
            putBoolean(KEY_TEXT_ITALIC, t.italic)
            putFloat(KEY_TEXT_LETTER_SPACING, t.letterSpacing)
            putFloat(KEY_TEXT_LINE_SPACING, t.lineSpacing)
            putBoolean(KEY_TEXT_BG_ENABLED, t.bgEnabled)
            putInt(KEY_TEXT_BG_COLOR, t.bgColor)
            putFloat(KEY_TEXT_BG_OPACITY, t.bgOpacity)
        }
        val img = cfg.image
        putBoolean(KEY_IMG_ENABLED, img != null)
        if (img != null) {
            putString(KEY_IMG_POS, img.position.name); putFloat(KEY_IMG_SIZE, img.sizePercent)
            putFloat(KEY_IMG_OPACITY, img.opacity); putString(KEY_IMG_FILENAME, img.logoFileName)
            saveLogo(ctx, img.logoBitmap)
        }
        val footer = cfg.footer
        putBoolean(KEY_FOOTER_ENABLED, footer != null)
        if (footer != null) {
            putInt(KEY_FOOTER_COLOR, footer.color); putFloat(KEY_FOOTER_INTENSITY, footer.intensity)
            putFloat(KEY_FOOTER_LENGTH, footer.length); putString(KEY_FOOTER_STYLE, footer.style.name)
            putString(KEY_FOOTER_DIRECTION, footer.direction.name)
            putFloat(KEY_FOOTER_SIDE_LEN, footer.sideLength); putFloat(KEY_FOOTER_FEATHER, footer.feather)
            putBoolean(KEY_FOOTER_FEATHER_R, footer.featherRight)
        }
        putBoolean(KEY_EXIF_ENABLED, cfg.exif != null)
        if (cfg.exif != null) {
            putString(KEY_EXIF_THEME, cfg.exif.theme.name); putString(KEY_EXIF_FONT, cfg.exif.font.key())
            putString(KEY_EXIF_SIZE_ENUM, cfg.exif.textSize.name)
            putBoolean(KEY_EXIF_SHADOW, cfg.exif.textShadow)
            putInt(KEY_EXIF_TEXT_COLOR, cfg.exif.textColor)
            putBoolean(KEY_EXIF_SHOW_LOGO, cfg.exif.showLogo)
            putBoolean(KEY_EXIF_SHOW_BRAND, cfg.exif.showBrand)
            putBoolean(KEY_EXIF_SHOW_MODEL, cfg.exif.showModel)
            putBoolean(KEY_EXIF_SHOW_LENS, cfg.exif.showLens)
            putBoolean(KEY_EXIF_SHOW_EXPOSURE, cfg.exif.showExposure)
            putBoolean(KEY_EXIF_SHOW_DATE, cfg.exif.showDate)
            putBoolean(KEY_EXIF_ITALIC, cfg.exif.italic)
            putFloat(KEY_EXIF_LETTER_SPACING, cfg.exif.letterSpacing)
            putFloat(KEY_EXIF_LINE_SPACING, cfg.exif.lineSpacing)
            // Override strings: "" = use per-file EXIF value; non-blank = user override
            putString(KEY_EXIF_MAKE_OVERRIDE, cfg.exif.make)
            putString(KEY_EXIF_MODEL_OVERRIDE, cfg.exif.model)
            putString(KEY_EXIF_LENS_OVERRIDE, cfg.exif.lensModel)
        }
    }.apply()
}

fun deleteWatermarkPreset(ctx: Context, name: String) {
    val presets = ctx.getSharedPreferences(PREFS_PRESETS, Context.MODE_PRIVATE)
    val existing = presets.getString(KEY_PRESET_NAMES, null)?.split("\n")?.filter { it.isNotBlank() && it != name }?.toMutableList() ?: mutableListOf()
    presets.edit().putString(KEY_PRESET_NAMES, existing.joinToString("\n")).apply()
    ctx.getSharedPreferences("wm_preset_$name", Context.MODE_PRIVATE).edit().clear().apply()
}

fun loadWatermarkPreset(ctx: Context, name: String): CombinedWatermarkConfig? {
    val prefs = ctx.getSharedPreferences("wm_preset_$name", Context.MODE_PRIVATE)
    val textEnabled = prefs.getBoolean(KEY_TEXT_ENABLED, false)
    val imgEnabled  = prefs.getBoolean(KEY_IMG_ENABLED, false)
    val footerEnabled = prefs.getBoolean(KEY_FOOTER_ENABLED, false)
    val exifEnabled = prefs.getBoolean(KEY_EXIF_ENABLED, false)
    if (!textEnabled && !imgEnabled && !footerEnabled && !exifEnabled) return null
    val textCfg = if (textEnabled) {
        val sizeEnum = runCatching { WatermarkTextSize.valueOf(prefs.getString(KEY_TEXT_SIZE_ENUM, WatermarkTextSize.Small.name) ?: "") }.getOrDefault(WatermarkTextSize.Small)
        WatermarkConfig.TextConfig(
            text = prefs.getString(KEY_TEXT, "") ?: "",
            position = runCatching { WatermarkPosition.valueOf(prefs.getString(KEY_TEXT_POS, WatermarkPosition.BOTTOM_RIGHT.name) ?: "") }.getOrDefault(WatermarkPosition.BOTTOM_RIGHT),
            color = Color(prefs.getInt(KEY_TEXT_COLOR, android.graphics.Color.WHITE)),
            sizePercent = sizeEnum.fraction,
            opacity = prefs.getFloat(KEY_TEXT_OPACITY, 0.85f),
            bold = prefs.getBoolean(KEY_TEXT_BOLD, false),
            font = WatermarkFont.fromKey(prefs.getString(KEY_TEXT_FONT, null)),
            textShadow = prefs.getBoolean(KEY_TEXT_SHADOW, true),
            italic = prefs.getBoolean(KEY_TEXT_ITALIC, false),
            letterSpacing = prefs.getFloat(KEY_TEXT_LETTER_SPACING, 0f),
            lineSpacing = prefs.getFloat(KEY_TEXT_LINE_SPACING, 1.25f),
            bgEnabled = prefs.getBoolean(KEY_TEXT_BG_ENABLED, false),
            bgColor = prefs.getInt(KEY_TEXT_BG_COLOR, android.graphics.Color.BLACK),
            bgOpacity = prefs.getFloat(KEY_TEXT_BG_OPACITY, 0.6f),
        )
    } else null
    val imgCfg = if (imgEnabled) loadLogo(ctx)?.let { bmp ->
        WatermarkConfig.ImageConfig(bmp, prefs.getString(KEY_IMG_FILENAME, LOGO_FILE_NAME) ?: LOGO_FILE_NAME,
            runCatching { WatermarkPosition.valueOf(prefs.getString(KEY_IMG_POS, WatermarkPosition.BOTTOM_RIGHT.name) ?: "") }.getOrDefault(WatermarkPosition.BOTTOM_RIGHT),
            prefs.getFloat(KEY_IMG_SIZE, 0.20f), prefs.getFloat(KEY_IMG_OPACITY, 0.85f))
    } else null
    val footerCfg = if (footerEnabled) FooterGradientConfig(
        prefs.getInt(KEY_FOOTER_COLOR, android.graphics.Color.BLACK),
        prefs.getFloat(KEY_FOOTER_INTENSITY, 0.7f), prefs.getFloat(KEY_FOOTER_LENGTH, 0.25f),
        runCatching { FooterGradientStyle.valueOf(prefs.getString(KEY_FOOTER_STYLE, FooterGradientStyle.Plain.name) ?: "") }.getOrDefault(FooterGradientStyle.Plain),
        runCatching { FooterGradientDirection.valueOf(prefs.getString(KEY_FOOTER_DIRECTION, FooterGradientDirection.DownToUp.name) ?: "") }.getOrDefault(FooterGradientDirection.DownToUp),
        prefs.getFloat(KEY_FOOTER_SIDE_LEN, 0.40f), prefs.getFloat(KEY_FOOTER_FEATHER, 0.30f),
        prefs.getBoolean(KEY_FOOTER_FEATHER_R, true),
    ) else null
    // For EXIF config: make/model/lensModel hold the user-typed overrides.
    // Empty string = "as per EXIF" — the coordinator fills these from per-file metadata at burn time.
    val exifCfg = if (exifEnabled) ExifWatermarkConfig(
        make             = prefs.getString(KEY_EXIF_MAKE_OVERRIDE, "") ?: "",
        model            = prefs.getString(KEY_EXIF_MODEL_OVERRIDE, "") ?: "",
        lensModel        = prefs.getString(KEY_EXIF_LENS_OVERRIDE, "") ?: "",
        iso              = 0,   // always filled from per-file EXIF at burn time
        shutterSpeed     = 0f,
        aperture         = 0f,
        focalMm          = 0f,
        dateTimeOriginal = "",
        textSize         = runCatching { WatermarkTextSize.valueOf(prefs.getString(KEY_EXIF_SIZE_ENUM, WatermarkTextSize.Small.name) ?: "") }.getOrDefault(WatermarkTextSize.Small),
        font             = WatermarkFont.fromKey(prefs.getString(KEY_EXIF_FONT, null)),
        theme            = runCatching { ExifTheme.valueOf(prefs.getString(KEY_EXIF_THEME, ExifTheme.Overlay.name) ?: "") }.getOrDefault(ExifTheme.Overlay),
        textShadow       = prefs.getBoolean(KEY_EXIF_SHADOW, true),
        textColor        = prefs.getInt(KEY_EXIF_TEXT_COLOR, android.graphics.Color.WHITE),
        showLogo         = prefs.getBoolean(KEY_EXIF_SHOW_LOGO, true),
        showBrand        = prefs.getBoolean(KEY_EXIF_SHOW_BRAND, true),
        showModel        = prefs.getBoolean(KEY_EXIF_SHOW_MODEL, true),
        showLens         = prefs.getBoolean(KEY_EXIF_SHOW_LENS, true),
        showExposure     = prefs.getBoolean(KEY_EXIF_SHOW_EXPOSURE, true),
        showDate         = prefs.getBoolean(KEY_EXIF_SHOW_DATE, false),
        textAlign = runCatching { WatermarkTextAlign.valueOf(prefs.getString(KEY_EXIF_ALIGN, WatermarkTextAlign.Left.name) ?: "") }.getOrDefault(WatermarkTextAlign.Left),
        bgEnabled = prefs.getBoolean(KEY_EXIF_BG_ENABLED, false),
        bgColor   = prefs.getInt(KEY_EXIF_BG_COLOR, android.graphics.Color.BLACK),
        bgOpacity = prefs.getFloat(KEY_EXIF_BG_OPACITY, 0.6f),
        italic    = prefs.getBoolean(KEY_EXIF_ITALIC, false),
        letterSpacing = prefs.getFloat(KEY_EXIF_LETTER_SPACING, 0f),
        lineSpacing   = prefs.getFloat(KEY_EXIF_LINE_SPACING, 1.3f),
    ) else null
    return CombinedWatermarkConfig(textCfg, imgCfg, footerCfg, exifCfg)
}

private fun saveLogo(ctx: Context, bmp: Bitmap) {
    val f = File(ctx.filesDir, LOGO_FILE_NAME)
    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private fun loadLogo(ctx: Context): Bitmap? {
    val f = File(ctx.filesDir, LOGO_FILE_NAME)
    return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
}

fun saveCombinedWatermarkConfig(ctx: Context, cfg: CombinedWatermarkConfig?) {
    val prefs = watermarkPrefs(ctx).edit()
    if (cfg == null) {
        prefs.putBoolean(KEY_TEXT_ENABLED, false)
            .putBoolean(KEY_IMG_ENABLED, false)
            .putBoolean(KEY_FOOTER_ENABLED, false)
            .putBoolean(KEY_EXIF_ENABLED, false)
            .apply()
        return
    }
    val t = cfg.text
    prefs.putBoolean(KEY_TEXT_ENABLED, t != null)
    if (t != null) {
        prefs.putString(KEY_TEXT, t.text)
        prefs.putString(KEY_TEXT_POS, t.position.name)
        prefs.putInt(KEY_TEXT_COLOR, t.color.toArgb())
        prefs.putFloat(KEY_TEXT_SIZE, t.sizePercent)
        prefs.putFloat(KEY_TEXT_OPACITY, t.opacity)
        prefs.putBoolean(KEY_TEXT_BOLD, t.bold)
        prefs.putString(KEY_TEXT_SIZE_ENUM, t.sizePercent.let { f ->
            WatermarkTextSize.entries.minByOrNull { kotlin.math.abs(it.fraction - f) }?.name
                ?: WatermarkTextSize.Small.name
        })
        prefs.putString(KEY_TEXT_FONT, t.font.key())
        prefs.putBoolean(KEY_TEXT_SHADOW, t.textShadow)
        prefs.putBoolean(KEY_TEXT_ITALIC, t.italic)
        prefs.putFloat(KEY_TEXT_LETTER_SPACING, t.letterSpacing)
        prefs.putFloat(KEY_TEXT_LINE_SPACING, t.lineSpacing)
        prefs.putBoolean(KEY_TEXT_BG_ENABLED, t.bgEnabled)
        prefs.putInt(KEY_TEXT_BG_COLOR, t.bgColor)
        prefs.putFloat(KEY_TEXT_BG_OPACITY, t.bgOpacity)
    }
    val img = cfg.image
    prefs.putBoolean(KEY_IMG_ENABLED, img != null)
    if (img != null) {
        prefs.putString(KEY_IMG_POS, img.position.name)
        prefs.putFloat(KEY_IMG_SIZE, img.sizePercent)
        prefs.putFloat(KEY_IMG_OPACITY, img.opacity)
        prefs.putString(KEY_IMG_FILENAME, img.logoFileName)
        saveLogo(ctx, img.logoBitmap)
    }
    prefs.putBoolean(KEY_EXIF_ENABLED, cfg.exif != null)
    if (cfg.exif != null) {
        prefs.putString(KEY_EXIF_THEME, cfg.exif.theme.name)
        prefs.putString(KEY_EXIF_FONT, cfg.exif.font.key())
        prefs.putString(KEY_EXIF_SIZE_ENUM, cfg.exif.textSize.name)
        prefs.putString(KEY_EXIF_MAKE_OVERRIDE, cfg.exif.make)
        prefs.putString(KEY_EXIF_MODEL_OVERRIDE, cfg.exif.model)
        prefs.putString(KEY_EXIF_LENS_OVERRIDE, cfg.exif.lensModel)
        prefs.putBoolean(KEY_EXIF_SHADOW, cfg.exif.textShadow)
        prefs.putInt(KEY_EXIF_TEXT_COLOR, cfg.exif.textColor)
        prefs.putBoolean(KEY_EXIF_SHOW_LOGO, cfg.exif.showLogo)
        prefs.putBoolean(KEY_EXIF_SHOW_BRAND, cfg.exif.showBrand)
        prefs.putBoolean(KEY_EXIF_SHOW_MODEL, cfg.exif.showModel)
        prefs.putBoolean(KEY_EXIF_SHOW_LENS, cfg.exif.showLens)
        prefs.putBoolean(KEY_EXIF_SHOW_EXPOSURE, cfg.exif.showExposure)
        prefs.putBoolean(KEY_EXIF_SHOW_DATE, cfg.exif.showDate)
        prefs.putString(KEY_EXIF_ALIGN, cfg.exif.textAlign.name)
        prefs.putBoolean(KEY_EXIF_BG_ENABLED, cfg.exif.bgEnabled)
        prefs.putInt(KEY_EXIF_BG_COLOR, cfg.exif.bgColor)
        prefs.putFloat(KEY_EXIF_BG_OPACITY, cfg.exif.bgOpacity)
        prefs.putBoolean(KEY_EXIF_ITALIC, cfg.exif.italic)
        prefs.putFloat(KEY_EXIF_LETTER_SPACING, cfg.exif.letterSpacing)
        prefs.putFloat(KEY_EXIF_LINE_SPACING, cfg.exif.lineSpacing)
    }
    val footer = cfg.footer
    prefs.putBoolean(KEY_FOOTER_ENABLED, footer != null)
    if (footer != null) {
        prefs.putInt(KEY_FOOTER_COLOR, footer.color)
        prefs.putFloat(KEY_FOOTER_INTENSITY, footer.intensity)
        prefs.putFloat(KEY_FOOTER_LENGTH, footer.length)
        prefs.putString(KEY_FOOTER_STYLE, footer.style.name)
        prefs.putString(KEY_FOOTER_DIRECTION, footer.direction.name)
        prefs.putFloat(KEY_FOOTER_SIDE_LEN, footer.sideLength)
        prefs.putFloat(KEY_FOOTER_FEATHER, footer.feather)
        prefs.putBoolean(KEY_FOOTER_FEATHER_R, footer.featherRight)
    }
    prefs.apply()
}

fun loadCombinedWatermarkConfig(ctx: Context): CombinedWatermarkConfig? {
    val prefs = watermarkPrefs(ctx)
    val textEnabled    = prefs.getBoolean(KEY_TEXT_ENABLED,   false)
    val imgEnabled     = prefs.getBoolean(KEY_IMG_ENABLED,    false)
    val exifEnabled    = prefs.getBoolean(KEY_EXIF_ENABLED,   false)
    val footerPreCheck = prefs.getBoolean(KEY_FOOTER_ENABLED, false)
    if (!textEnabled && !imgEnabled && !footerPreCheck && !exifEnabled) return null
    val textCfg = if (textEnabled) {
        val text     = prefs.getString(KEY_TEXT, "© StudioRoom") ?: "© StudioRoom"
        val posName  = prefs.getString(KEY_TEXT_POS, WatermarkPosition.BOTTOM_RIGHT.name)
        val pos      = runCatching { WatermarkPosition.valueOf(posName ?: "") }.getOrDefault(WatermarkPosition.BOTTOM_RIGHT)
        val colorInt = prefs.getInt(KEY_TEXT_COLOR, android.graphics.Color.WHITE)
        val color    = Color(colorInt)
        val sizeEnumName = prefs.getString(KEY_TEXT_SIZE_ENUM, WatermarkTextSize.Small.name)
        val sizeEnum = runCatching { WatermarkTextSize.valueOf(sizeEnumName ?: "") }.getOrDefault(WatermarkTextSize.Small)
        val size     = sizeEnum.fraction
        val opacity  = prefs.getFloat(KEY_TEXT_OPACITY, 0.85f).coerceIn(0.1f, 1.0f)
        val bold     = prefs.getBoolean(KEY_TEXT_BOLD, false)
        val font     = WatermarkFont.fromKey(prefs.getString(KEY_TEXT_FONT, null))
        val shadow   = prefs.getBoolean(KEY_TEXT_SHADOW, true)
        val italic   = prefs.getBoolean(KEY_TEXT_ITALIC, false)
        val letterSp = prefs.getFloat(KEY_TEXT_LETTER_SPACING, 0f)
        val lineSp   = prefs.getFloat(KEY_TEXT_LINE_SPACING, 1.25f)
        val bgEn     = prefs.getBoolean(KEY_TEXT_BG_ENABLED, false)
        val bgCol    = prefs.getInt(KEY_TEXT_BG_COLOR, android.graphics.Color.BLACK)
        val bgOp     = prefs.getFloat(KEY_TEXT_BG_OPACITY, 0.6f)
        WatermarkConfig.TextConfig(
            text = text, position = pos, color = color, sizePercent = size, opacity = opacity,
            bold = bold, italic = italic, font = font, textShadow = shadow,
            letterSpacing = letterSp, lineSpacing = lineSp,
            bgEnabled = bgEn, bgColor = bgCol, bgOpacity = bgOp,
        )
    } else null
    val imgCfg = if (imgEnabled) {
        val logoBmp = loadLogo(ctx)
        if (logoBmp != null) {
            val posName  = prefs.getString(KEY_IMG_POS, WatermarkPosition.BOTTOM_RIGHT.name)
            val pos      = runCatching { WatermarkPosition.valueOf(posName ?: "") }.getOrDefault(WatermarkPosition.BOTTOM_RIGHT)
            val size     = prefs.getFloat(KEY_IMG_SIZE, 0.20f).coerceIn(0.05f, 0.50f)
            val opacity  = prefs.getFloat(KEY_IMG_OPACITY, 0.85f).coerceIn(0.1f, 1.0f)
            val fileName = prefs.getString(KEY_IMG_FILENAME, LOGO_FILE_NAME) ?: LOGO_FILE_NAME
            WatermarkConfig.ImageConfig(logoBmp, fileName, pos, size, opacity)
        } else null
    } else null
    val footerEnabled = prefs.getBoolean(KEY_FOOTER_ENABLED, false)
    val footerCfg = if (footerEnabled) {
        val color        = prefs.getInt(KEY_FOOTER_COLOR, android.graphics.Color.BLACK)
        val intensity    = prefs.getFloat(KEY_FOOTER_INTENSITY, 0.7f).coerceIn(0.1f, 1.0f)
        val length       = prefs.getFloat(KEY_FOOTER_LENGTH, 0.25f).coerceIn(0.05f, 0.60f)
        val styleName    = prefs.getString(KEY_FOOTER_STYLE, FooterGradientStyle.Plain.name)
        val style        = runCatching { FooterGradientStyle.valueOf(styleName ?: "") }.getOrDefault(FooterGradientStyle.Plain)
        val dirName      = prefs.getString(KEY_FOOTER_DIRECTION, FooterGradientDirection.DownToUp.name)
        val direction    = runCatching { FooterGradientDirection.valueOf(dirName ?: "") }.getOrDefault(FooterGradientDirection.DownToUp)
        val sideLength   = prefs.getFloat(KEY_FOOTER_SIDE_LEN, 0.40f).coerceIn(0.10f, 1.0f)
        val feather      = prefs.getFloat(KEY_FOOTER_FEATHER, 0.30f).coerceIn(0f, 1.0f)
        val featherRight = prefs.getBoolean(KEY_FOOTER_FEATHER_R, true)
        FooterGradientConfig(color, intensity, length, style, direction, sideLength, feather, featherRight)
    } else null
    // EXIF data is runtime-only (not persisted) — load restores the toggle but not the data.
    return if (textCfg == null && imgCfg == null && footerCfg == null && !exifEnabled) null
    else CombinedWatermarkConfig(textCfg, imgCfg, footerCfg, exif = null)
    // Note: exif = null here; callers must call attachExifConfig(ctx, metadata, cfg) to re-attach.
}

/**
 * Attaches a live [ExifWatermarkConfig] to [cfg] using saved prefs + the supplied [metadata].
 * Call this at export time after [loadCombinedWatermarkConfig].
 * Returns the same object (or a copy with exif populated) ready for burn.
 *
 * When [cfg.exif] is already non-null (e.g. loaded from a named preset), the preset's own
 * override strings (make/model/lensModel) and display settings are preserved. Only the
 * numeric runtime values (iso/shutter/aperture/focal/date) and the "as per EXIF" resolution
 * (empty override → live metadata value) are applied from [metadata].
 * When [cfg.exif] is null (global-prefs-based config), the full settings are read from prefs.
 */
fun attachExifConfig(ctx: Context, metadata: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata, cfg: CombinedWatermarkConfig): CombinedWatermarkConfig {
    // If the config already has an embedded exif block (named preset path), only refresh
    // runtime numeric fields and resolve any blank overrides — never read global prefs.
    val existing = cfg.exif
    if (existing != null) {
        val resolvedMake = existing.make.ifBlank { metadata.cameraMake }
        val exif = existing.copy(
            make             = resolvedMake,
            // Auto-resolved model → map the cryptic EXIF code to its marketing
            // name (e.g. "Infinix X6873" → "GT 30 Pro"). A user override is kept
            // verbatim (only the ifBlank branch maps).
            model            = existing.model.ifBlank {
                mapModelToMarketing(ctx, resolvedMake, metadata.cameraModel)
            },
            lensModel        = existing.lensModel.ifBlank { metadata.lensInfo },
            iso              = metadata.iso,
            shutterSpeed     = metadata.shutterSpeed,
            aperture         = metadata.aperture,
            focalMm          = metadata.focalLength,
            dateTimeOriginal = metadata.dateTimeOriginal,
            textAlign        = existing.textAlign,
            bgEnabled        = existing.bgEnabled,
            bgColor          = existing.bgColor,
            bgOpacity        = existing.bgOpacity,
        )
        return cfg.copy(exif = exif)
    }
    // Global-prefs path: read all settings from prefs.
    val prefs = watermarkPrefs(ctx)
    if (!prefs.getBoolean(KEY_EXIF_ENABLED, false)) return cfg
    val theme    = prefs.getString(KEY_EXIF_THEME, null)?.let { runCatching { ExifTheme.valueOf(it) }.getOrNull() } ?: ExifTheme.Overlay
    val exifShadow = prefs.getBoolean(KEY_EXIF_SHADOW, true)
    val font     = WatermarkFont.fromKey(prefs.getString(KEY_EXIF_FONT, null))
    val sizeEnum = prefs.getString(KEY_EXIF_SIZE_ENUM, null)?.let { runCatching { WatermarkTextSize.valueOf(it) }.getOrNull() } ?: WatermarkTextSize.Small
    val makeOvr  = prefs.getString(KEY_EXIF_MAKE_OVERRIDE,  null)
    val modelOvr = prefs.getString(KEY_EXIF_MODEL_OVERRIDE, null)
    val lensOvr  = prefs.getString(KEY_EXIF_LENS_OVERRIDE,  null)
    val prefsResolvedMake = makeOvr?.ifBlank { metadata.cameraMake } ?: metadata.cameraMake
    val exif = ExifWatermarkConfig(
        make            = prefsResolvedMake,
        // Map the auto EXIF model code to its marketing name; a non-blank
        // user override (modelOvr) is used verbatim.
        model           = modelOvr?.takeIf { it.isNotBlank() }
            ?: mapModelToMarketing(ctx, prefsResolvedMake, metadata.cameraModel),
        lensModel       = lensOvr?.ifBlank { metadata.lensInfo }   ?: metadata.lensInfo,
        iso             = metadata.iso,
        shutterSpeed    = metadata.shutterSpeed,
        aperture        = metadata.aperture,
        focalMm         = metadata.focalLength,
        dateTimeOriginal = metadata.dateTimeOriginal,
        textSize        = sizeEnum,
        font            = font,
        theme           = theme,
        textShadow      = exifShadow,
        textColor       = prefs.getInt(KEY_EXIF_TEXT_COLOR, android.graphics.Color.WHITE),
        showLogo        = prefs.getBoolean(KEY_EXIF_SHOW_LOGO, true),
        showBrand       = prefs.getBoolean(KEY_EXIF_SHOW_BRAND, true),
        showModel       = prefs.getBoolean(KEY_EXIF_SHOW_MODEL, true),
        showLens        = prefs.getBoolean(KEY_EXIF_SHOW_LENS, true),
        showExposure    = prefs.getBoolean(KEY_EXIF_SHOW_EXPOSURE, true),
        showDate        = prefs.getBoolean(KEY_EXIF_SHOW_DATE, false),
        textAlign = runCatching { WatermarkTextAlign.valueOf(prefs.getString(KEY_EXIF_ALIGN, WatermarkTextAlign.Left.name) ?: "") }.getOrDefault(WatermarkTextAlign.Left),
        bgEnabled = prefs.getBoolean(KEY_EXIF_BG_ENABLED, false),
        bgColor   = prefs.getInt(KEY_EXIF_BG_COLOR, android.graphics.Color.BLACK),
        bgOpacity = prefs.getFloat(KEY_EXIF_BG_OPACITY, 0.6f),
        italic    = prefs.getBoolean(KEY_EXIF_ITALIC, false),
        letterSpacing = prefs.getFloat(KEY_EXIF_LETTER_SPACING, 0f),
        lineSpacing   = prefs.getFloat(KEY_EXIF_LINE_SPACING, 1.3f),
    )
    return cfg.copy(exif = exif)
}

/**
 * Merges per-file EXIF data from a domain [Metadata] into a preset-loaded [CombinedWatermarkConfig].
 * Unlike [attachExifConfig] (which reads global prefs), this uses the overrides already stored in
 * [cfg.exif.make/model/lensModel] (empty = use per-file value) and fills all numeric fields
 * (iso/shutter/aperture/focal/date) from the domain metadata.
 * Returns cfg unchanged if [cfg.exif] is null.
 */
/** Map an EXIF device model code to its marketing name (see [DeviceNameDatabase]),
 *  falling back to the raw code when there's no mapping. */
internal fun mapModelToMarketing(ctx: Context, make: String, model: String): String =
    if (model.isBlank()) model
    else DeviceNameDatabase.marketingName(ctx, make, model) ?: model

fun mergeExifFromDomainMetadata(
    ctx: Context,
    cfg: CombinedWatermarkConfig,
    domainMeta: com.RAZStudio.StudioRoom.core.domain.image.Metadata,
): CombinedWatermarkConfig {
    val exif = cfg.exif ?: return cfg
    fun parseRational(s: String?): Float {
        if (s.isNullOrBlank()) return 0f
        return runCatching {
            val parts = s.split("/")
            if (parts.size == 2) parts[0].trim().toFloat() / parts[1].trim().toFloat()
            else s.trim().toFloat()
        }.getOrDefault(0f)
    }
    val make  = exif.make.ifBlank  { domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.Make) ?: "" }
    // Auto-resolved model → marketing name (user override kept verbatim by ifBlank).
    val model = exif.model.ifBlank {
        val raw = domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.Model) ?: ""
        mapModelToMarketing(ctx, make, raw)
    }
    val lens  = exif.lensModel.ifBlank { domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.LensModel) ?: "" }
    // ISOSpeed (0x8833) is rarely written by cameras; most write PhotographicSensitivity (0x8827).
    val iso   = (domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.PhotographicSensitivity)?.trim()?.toIntOrNull()
        ?: domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.IsoSpeed)?.trim()?.toIntOrNull()
        ?: 0)
    val shutter = parseRational(domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.ExposureTime))
    val aperture = parseRational(domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.FNumber))
    val focal = parseRational(domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.FocalLength))
    val dt = domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.DatetimeOriginal) ?: ""
    // Keep whatever was already resolved into the config when the domain read is
    // blank/zero. The domain metadata here is an ExifInterface read of the source;
    // for many RAWs (e.g. CR2) it can't parse the date/exposure, but the values
    // were already filled from LibRaw's Stage A via attachExifConfig — don't wipe
    // them with an empty read. (For JPEG the domain read has the values → used.)
    return cfg.copy(exif = exif.copy(
        make = make, model = model, lensModel = lens,
        iso              = if (iso > 0) iso else exif.iso,
        shutterSpeed     = if (shutter > 0f) shutter else exif.shutterSpeed,
        aperture         = if (aperture > 0f) aperture else exif.aperture,
        focalMm          = if (focal > 0f) focal else exif.focalMm,
        dateTimeOriginal = dt.ifBlank { exif.dateTimeOriginal },
    ))
}

/**
 * Overload of [attachExifConfig] for callers that have a domain [com.RAZStudio.StudioRoom.core.domain.image.Metadata]
 * (e.g. the v3 coordinator after calling fileController().readMetadata()).
 * Extracts make/model/lens/exposure values from EXIF tags and delegates to the primary overload.
 */
fun attachExifConfig(
    ctx: Context,
    domainMeta: com.RAZStudio.StudioRoom.core.domain.image.Metadata,
    cfg: CombinedWatermarkConfig,
): CombinedWatermarkConfig {
    fun parseRational(s: String?): Float {
        if (s.isNullOrBlank()) return 0f
        return runCatching {
            val parts = s.split("/")
            if (parts.size == 2) parts[0].trim().toFloat() / parts[1].trim().toFloat()
            else s.trim().toFloat()
        }.getOrDefault(0f)
    }
    val base = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata.EMPTY
    val rawMeta = base.copy(
        cameraMake       = domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.Make) ?: "",
        cameraModel      = domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.Model) ?: "",
        lensInfo         = domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.LensModel) ?: "",
        iso              = (domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.PhotographicSensitivity)?.trim()?.toIntOrNull()
            ?: domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.IsoSpeed)?.trim()?.toIntOrNull()
            ?: 0),
        shutterSpeed     = parseRational(domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.ExposureTime)),
        aperture         = parseRational(domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.FNumber)),
        focalLength      = parseRational(domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.FocalLength)),
        dateTimeOriginal = domainMeta.getAttribute(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.DatetimeOriginal) ?: "",
    )
    return attachExifConfig(ctx, rawMeta, cfg)
}

// ── Burn helpers — called at Save time only ───────────────────────────────────

fun burnWatermarkOnto(source: Bitmap, config: WatermarkConfig, ctx: Context? = null): Bitmap {
    return when (config) {
        is WatermarkConfig.TextConfig  -> burnText(source, config, ctx = ctx)
        is WatermarkConfig.ImageConfig -> burnImage(source, config)
    }
}

/**
 * Correct a mis-reported lens name for the EXIF watermark.
 *
 * 1. A Canon EF 28mm f/2.8 physically opens no wider than f/2.8, so when EXIF reports
 *    that lens at an aperture below f/2.8 the body is really metering an adapted
 *    (chipped / "dandelion") manual lens that borrows the EF 28mm f/2.8 identity — on
 *    this rig the "Sigma High-Speed Wide 28mm f/1.8 II". Gated on the impossible
 *    aperture, so genuine EF 28mm f/2.8 shots are left untouched.
 *
 * 2. The Tamron SP AF 20-40mm f/2.7-3.5 Aspherical (IF) reports itself over the Canon
 *    EF protocol as the bare focal range "21-39mm" (EXIF LensModel; LensSpecification
 *    = [21,39,0,0]) — the electronic contacts transmit rounded integer focal limits,
 *    not the marketing name, and the lens sends no LensMake at all. Substitute the
 *    real name so the burned strip reads the lens the photographer actually mounted.
 *
 *    This MIRRORS `remapSpecialLens()` in lensfun_android.cpp, which maps the same
 *    "21-39mm" string onto the bundled correction profile. Keep the two in sync: the
 *    native side drives optical correction, this one drives the printed name. The
 *    display string deliberately drops the "(IF)" suffix the lensfun DB entry carries
 *    — the DB name is a matching key, this is human-facing text.
 */
internal fun correctLensModel(lens: String, aperture: Float): String {
    val norm = lens.replace(" ", "").lowercase()
    val looksEf28f28 = norm.contains("ef28mm") && norm.contains("2.8")
    if (looksEf28f28 && aperture > 0f && aperture < 2.8f)
        return "Sigma 28mm f/1.8 High-Speed Wide Aspherical II"
    // normKey parity with the native remap: strip spaces AND dashes → "2139mm".
    // `bare` also normalises the "(IF)" spelling (the lensfun DB / autocomplete
    // name) onto the same clean display string, so the burned strip reads
    // identically whether the name came from EXIF, the DB, or an autocomplete
    // pick. Dashes must be stripped for BOTH checks — the DB name contains
    // "20-40mm", so matching it without the strip silently fails.
    val bare = norm.replace("-", "")
    if (bare == "2139mm" || bare.startsWith("tamronspaf2040mm"))
        return "Tamron SP AF 20-40mm f/2.7-3.5 Aspherical"
    return lens
}

fun burnCombinedWatermarkOnto(source: Bitmap, cfg: CombinedWatermarkConfig, ctx: Context? = null): Bitmap {
    var out = source
    cfg.footer?.let { out = burnFooterGradient(if (out === source) source.copy(Bitmap.Config.ARGB_8888, true) else out, it, inPlace = true) }
    cfg.exif?.let   { out = burnExifStrip(if (out === source) source.copy(Bitmap.Config.ARGB_8888, true) else out, it, inPlace = true, ctx = ctx) }
    cfg.text?.let   { out = burnText(if (out === source) source.copy(Bitmap.Config.ARGB_8888, true) else out, it, inPlace = true, ctx = ctx) }
    cfg.image?.let  { out = burnImage(if (out === source) source.copy(Bitmap.Config.ARGB_8888, true) else out, it, inPlace = true) }
    return out
}

/**
 * Renders a stylised brand "logo" bitmap for the given camera make string.
 * The result is a white-on-transparent ARGB_8888 bitmap sized to [logoHeight] px tall.
 * Each brand gets a distinct typeface style that approximates its real wordmark aesthetic.
 */
internal fun makeBrandLogoBitmap(make: String, logoHeight: Float, maxWidth: Float = logoHeight * 3.674f, ctx: Context? = null): Bitmap {
    val normalized = make.trim().lowercase()
    // Canon reference: 720×196 → aspect 3.674. All logos scale to Canon's rendered width.
    // Portrait logos capped at 2× Canon's rendered height so they don't tower.
    val canonW = maxWidth                      // Canon rendered width = logoHeight * 3.674
    val maxRenderedH = logoHeight * 2f         // portrait cap = 2× Canon height

    val assetFile: String? = when {
        "canon"     in normalized -> "brand_logos/Canon.png"
        "sony"      in normalized -> "brand_logos/Sony.png"
        "nikon"     in normalized -> "brand_logos/Nikon.png"
        "fuji"      in normalized -> "brand_logos/Fujifilm.png"
        "samsung"   in normalized -> "brand_logos/Samsung.png"
        "google"    in normalized -> "brand_logos/Google.png"
        "honor"     in normalized -> "brand_logos/Honor.png"
        "huawei"    in normalized -> "brand_logos/Huawei.png"
        "infinix"   in normalized -> "brand_logos/Infinix.png"
        "leica"     in normalized -> "brand_logos/Leica.png"
        "lenovo"    in normalized -> "brand_logos/Lenovo.png"
        "lg"        == normalized || normalized.startsWith("lg ") -> "brand_logos/LG.png"
        "motorola"  in normalized -> "brand_logos/Motorola.png"
        "olympus"   in normalized ||
        "om system" in normalized ||
        "om-system" in normalized -> "brand_logos/Olympus.png"
        "oppo"      in normalized -> "brand_logos/Oppo.png"
        "panasonic" in normalized -> "brand_logos/Panasonic.png"
        "realme"    in normalized -> "brand_logos/Realme.png"
        "redmi"     in normalized -> "brand_logos/Redmi.png"
        "ricoh"     in normalized -> "brand_logos/Ricoh.png"
        "vivo"      in normalized -> "brand_logos/Vivo.png"
        "xiaomi"    in normalized -> "brand_logos/Xiaomi.png"
        "zte"       in normalized -> "brand_logos/ZTE.png"
        else -> null
    }
    if (assetFile != null && ctx != null) {
        runCatching {
            ctx.assets.open(assetFile).use { stream ->
                val src = BitmapFactory.decodeStream(stream) ?: return@runCatching null
                // Scale to Canon's rendered width, preserve aspect ratio, cap height for portrait logos.
                val scaleByW = canonW / src.width.toFloat()
                val naturalH = src.height * scaleByW
                val dstH = naturalH.coerceAtMost(maxRenderedH).toInt().coerceAtLeast(1)
                val dstW = canonW.toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(src, dstW, dstH, true)
                if (scaled !== src) src.recycle()
                scaled
            }
        }.getOrNull()?.let { return it }
    }
    // Fallback: render brand name as styled text (all brands without a PNG asset).
    data class BrandStyle(val label: String, val bold: Boolean, val italic: Boolean, val tracking: Float = 0f)
    val style = when {
        "canon"      in normalized -> BrandStyle("CANON",      bold = false, italic = true,  tracking = 0.05f)
        "sony"       in normalized -> BrandStyle("SONY",       bold = true,  italic = false, tracking = 0.12f)
        "nikon"      in normalized -> BrandStyle("NIKON",      bold = true,  italic = false, tracking = 0.04f)
        "fuji"       in normalized -> BrandStyle("FUJIFILM",   bold = false, italic = false, tracking = 0.06f)
        "olympus"    in normalized ||
        "om system"  in normalized ||
        "om-system"  in normalized -> BrandStyle("OM SYSTEM",  bold = true,  italic = false, tracking = 0.08f)
        "panasonic"  in normalized -> BrandStyle("LUMIX",      bold = false, italic = false, tracking = 0.10f)
        "leica"      in normalized -> BrandStyle("LEICA",      bold = false, italic = false, tracking = 0.20f)
        "ricoh"      in normalized -> BrandStyle("RICOH",      bold = false, italic = false, tracking = 0.05f)
        "gopro"      in normalized -> BrandStyle("GoPro",      bold = true,  italic = false, tracking = 0.0f)
        "dji"        in normalized -> BrandStyle("DJI",        bold = true,  italic = false, tracking = 0.15f)
        "hasselblad" in normalized -> BrandStyle("HASSELBLAD", bold = false, italic = false, tracking = 0.08f)
        "pentax"     in normalized -> BrandStyle("PENTAX",     bold = true,  italic = false, tracking = 0.03f)
        "sigma"      in normalized -> BrandStyle("SIGMA",      bold = false, italic = true,  tracking = 0.04f)
        "phase one"  in normalized ||
        "phaseone"   in normalized -> BrandStyle("PHASE ONE",  bold = false, italic = false, tracking = 0.10f)
        "polaroid"   in normalized -> BrandStyle("polaroid",   bold = false, italic = false, tracking = 0.0f)
        "kodak"      in normalized -> BrandStyle("KODAK",      bold = true,  italic = false, tracking = 0.05f)
        "insta360"   in normalized -> BrandStyle("Insta360",   bold = true,  italic = false, tracking = 0.0f)
        "samsung"    in normalized -> BrandStyle("SAMSUNG",    bold = false, italic = false, tracking = 0.06f)
        "casio"      in normalized -> BrandStyle("CASIO",      bold = false, italic = false, tracking = 0.04f)
        "minolta"    in normalized -> BrandStyle("MINOLTA",    bold = false, italic = true,  tracking = 0.04f)
        else                       -> BrandStyle(make.uppercase().take(16), bold = false, italic = false)
    }
    val typefaceStyle = when {
        style.bold && style.italic -> Typeface.BOLD_ITALIC
        style.bold                 -> Typeface.BOLD
        style.italic               -> Typeface.ITALIC
        else                       -> Typeface.NORMAL
    }
    val tf = Typeface.create(Typeface.SERIF, typefaceStyle)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = logoHeight
        typeface = tf
        letterSpacing = style.tracking
        setShadowLayer(logoHeight * 0.12f, logoHeight * 0.05f, logoHeight * 0.05f,
            android.graphics.Color.argb(180, 0, 0, 0))
    }
    val textW = paint.measureText(style.label)
    val metrics = paint.fontMetrics
    val bmpW = (textW + 2).toInt().coerceAtLeast(1)
    val bmpH = ((-metrics.ascent + metrics.descent) + 2).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawText(style.label, 1f, -metrics.ascent + 1f, paint)
    return bmp
}

fun burnExifStrip(source: Bitmap, cfg: ExifWatermarkConfig, inPlace: Boolean = false, ctx: Context? = null): Bitmap {
    return when (cfg.theme) {
        ExifTheme.Strap      -> burnExifThemeStrap(source, cfg, inPlace, ctx)
        ExifTheme.Lightroom  -> burnExifThemeLightroom(source, cfg, inPlace, ctx)
        ExifTheme.Film       -> burnExifThemeFilm(source, cfg, inPlace, ctx)
        ExifTheme.Monitor    -> burnExifThemeMonitor(source, cfg, inPlace, ctx)
        ExifTheme.ShotOn     -> burnExifThemeShotOn(source, cfg, inPlace, ctx)
        ExifTheme.Overlay    -> burnExifThemeOverlay(source, cfg, inPlace, ctx)
    }
}

/** Overlay theme: brand logo + EXIF text drawn directly on image, bottom-left, white + shadow. */
private fun burnExifThemeOverlay(source: Bitmap, cfg: ExifWatermarkConfig, inPlace: Boolean, ctx: Context?): Bitmap {
    val out = if (inPlace) source else source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val w = out.width; val h = out.height
    val shortSide = minOf(w, h).toFloat()
    val szMain  = (shortSide * cfg.textSize.fraction).coerceAtLeast(10f)
    val szSmall = szMain * 0.80f
    val marginX = szMain * 0.8f
    val lineGap = szMain * cfg.lineSpacing.coerceIn(0.5f, 3f)
    val logoHeight = szMain * 1.44f
    val typeface = if (ctx != null) cfg.font.toTypeface(ctx, false, cfg.italic)
                   else Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    fun wp(sz: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cfg.textColor; textSize = sz; this.typeface = typeface
        letterSpacing = cfg.letterSpacing
        if (cfg.textShadow) setShadowLayer(sz * 0.15f, sz * 0.06f, sz * 0.06f, android.graphics.Color.argb(180, 0, 0, 0))
    }
    val shutterStr  = formatShutter(cfg.shutterSpeed)
    val exposureLine = if (cfg.showExposure) listOfNotNull(
        "ISO${cfg.iso}".takeIf { cfg.iso > 0 },
        "${"%.0f".format(cfg.focalMm)}mm".takeIf { cfg.focalMm > 0f },
        "F${"%.1f".format(cfg.aperture)}".takeIf { cfg.aperture > 0f },
        shutterStr.takeIf { it.isNotEmpty() },
    ).joinToString("  ") else ""
    val lensStr = if (cfg.showLens) LensDatabase.stripRedundantBrand(correctLensModel(cfg.lensModel, cfg.aperture), cfg.make).trim() else ""
    val modelStr = if (cfg.showModel) cfg.model.trim() else ""
    val modelLensLine: String? = when {
        modelStr.isNotBlank() && lensStr.isNotBlank() -> "$modelStr  |  $lensStr"
        modelStr.isNotBlank() -> modelStr
        lensStr.isNotBlank()  -> lensStr
        else -> null
    }

    // Measure max text width for background panel
    val pMain  = wp(szMain);  val pSmall = wp(szSmall)
    val textLines = listOfNotNull(modelLensLine, exposureLine.ifEmpty { null },
        cfg.dateTimeOriginal.takeIf { cfg.showDate && it.isNotBlank() })
    val maxTextW = textLines.maxOfOrNull { line ->
        (if (line == modelLensLine) pMain else pSmall).measureText(line)
    } ?: 0f

    // Resolve logo bitmap now so we know its width for right-align
    val logoBmp: Bitmap? = if (cfg.showLogo && cfg.make.isNotBlank())
        runCatching { makeBrandLogoBitmap(cfg.make, logoHeight, logoHeight * 3.674f, ctx) }.getOrNull()
    else null
    val logoW = logoBmp?.width?.toFloat() ?: 0f

    // Count text rows for block height (used for bg panel). Use the ACTUAL logo
    // bitmap height (some brand PNGs — e.g. Canon — sit higher / are taller than
    // the nominal logoHeight) plus a generous top pad so the panel always covers
    // the logo's top edge, never clipping it.
    val textRowCount = textLines.size
    val logoBlockH = if (logoBmp != null) (logoBmp.height.toFloat()) + szMain * 1.0f else 0f
    val blockH = textRowCount * lineGap + logoBlockH

    // Compute anchor X based on alignment
    val anchorX: Float = when (cfg.textAlign) {
        WatermarkTextAlign.Left   -> marginX
        WatermarkTextAlign.Center -> w / 2f
        WatermarkTextAlign.Right  -> w - marginX
    }

    // Resolve paint textAlign
    val paintAlign = when (cfg.textAlign) {
        WatermarkTextAlign.Left   -> Paint.Align.LEFT
        WatermarkTextAlign.Center -> Paint.Align.CENTER
        WatermarkTextAlign.Right  -> Paint.Align.RIGHT
    }

    // Background panel — hugs the text/logo content (NOT full image width) with
    // SHARP corners (no radius), and a gradient that feathers only the trailing
    // edge so it reads like the Footer gradient: solid behind the text, softly
    // fading out just past it. Left-align solid at the image's left edge fading
    // right; Right-align mirrors; Center feathers both sides.
    val contentW = maxOf(maxTextW, logoW)
    val bgPadH = szMain * 0.6f
    val bgPadV = szMain * 0.4f
    val bgBottom = h.toFloat()
    val bgTop    = bgBottom - blockH - bgPadV * 2f
    // Solid portion hugs the content; a long feather tail extends PAST it so the
    // fade has room to run smoothly instead of fading over the text.
    val featherLen = szMain * 6f
    val contentEndL = marginX + contentW + bgPadH          // right edge of solid (Left align)
    val contentEndR = w - marginX - contentW - bgPadH      // left edge of solid (Right align)
    val panelHalf   = contentW / 2f + bgPadH               // half solid width (Center)
    val bgLeft: Float; val bgRight: Float
    when (cfg.textAlign) {
        WatermarkTextAlign.Left -> {
            bgLeft = 0f
            bgRight = (contentEndL + featherLen).coerceAtMost(w.toFloat())
        }
        WatermarkTextAlign.Right -> {
            bgLeft = (contentEndR - featherLen).coerceAtLeast(0f)
            bgRight = w.toFloat()
        }
        WatermarkTextAlign.Center -> {
            bgLeft = (w / 2f - panelHalf - featherLen).coerceAtLeast(0f)
            bgRight = (w / 2f + panelHalf + featherLen).coerceAtMost(w.toFloat())
        }
    }

    if (cfg.bgEnabled) {
        val baseA = (cfg.bgOpacity.coerceIn(0f, 1f) * 255).toInt()
        val r = android.graphics.Color.red(cfg.bgColor)
        val g = android.graphics.Color.green(cfg.bgColor)
        val b = android.graphics.Color.blue(cfg.bgColor)
        // Alpha stop at fraction f of full opacity.
        fun ca(f: Float) = android.graphics.Color.argb((baseA * f).toInt().coerceIn(0, 255), r, g, b)
        val span = (bgRight - bgLeft).coerceAtLeast(1f)
        // Feather fraction of the whole span. Capped at 0.45 so the two-sided
        // Center gradient's stop positions never collide (LinearGradient requires
        // strictly ascending stops) — still a long, gradual fade.
        val ff = (featherLen / span).coerceIn(0.15f, 0.45f)
        // Eased multi-stop ramp (1 → 0.72 → 0.40 → 0.16 → 0.05 → 0) for a smooth,
        // gradual falloff with no hard kink where solid meets the fade.
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = when (cfg.textAlign) {
                WatermarkTextAlign.Left -> android.graphics.LinearGradient(
                    bgLeft, 0f, bgRight, 0f,
                    intArrayOf(ca(1f), ca(1f), ca(0.72f), ca(0.40f), ca(0.16f), ca(0.05f), ca(0f)),
                    floatArrayOf(0f, 1f - ff, 1f - ff * 0.72f, 1f - ff * 0.45f, 1f - ff * 0.22f, 1f - ff * 0.08f, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
                WatermarkTextAlign.Right -> android.graphics.LinearGradient(
                    bgLeft, 0f, bgRight, 0f,
                    intArrayOf(ca(0f), ca(0.05f), ca(0.16f), ca(0.40f), ca(0.72f), ca(1f), ca(1f)),
                    floatArrayOf(0f, ff * 0.08f, ff * 0.22f, ff * 0.45f, ff * 0.72f, ff, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
                WatermarkTextAlign.Center -> android.graphics.LinearGradient(
                    bgLeft, 0f, bgRight, 0f,
                    intArrayOf(ca(0f), ca(0.16f), ca(0.72f), ca(1f), ca(1f), ca(0.72f), ca(0.16f), ca(0f)),
                    floatArrayOf(0f, ff * 0.35f, ff * 0.72f, ff, 1f - ff, 1f - ff * 0.72f, 1f - ff * 0.35f, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
            }
        }
        canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, bgPaint)
    }

    // Draw text bottom-up from baseline
    val baseY = h - marginX
    var rowY  = baseY
    // Apply textAlign to paints
    fun wpa(sz: Float) = wp(sz).apply { textAlign = paintAlign }
    if (cfg.showDate && cfg.dateTimeOriginal.isNotBlank()) {
        canvas.drawText(cfg.dateTimeOriginal, anchorX, rowY, wpa(szSmall)); rowY -= lineGap
    }
    if (exposureLine.isNotEmpty()) {
        canvas.drawText(exposureLine, anchorX, rowY, wpa(szSmall)); rowY -= lineGap
    }
    if (modelLensLine != null) {
        canvas.drawText(modelLensLine, anchorX, rowY, wpa(szMain)); rowY -= lineGap
    }
    // Logo
    if (logoBmp != null) {
        val lp = Paint(Paint.ANTI_ALIAS_FLAG)
        val logoTopAnchor = rowY + lineGap - szMain * 1.62f
        val logoX = when (cfg.textAlign) {
            WatermarkTextAlign.Left   -> anchorX
            WatermarkTextAlign.Center -> anchorX - logoBmp.width / 2f
            WatermarkTextAlign.Right  -> anchorX - logoBmp.width
        }
        canvas.drawBitmap(logoBmp, logoX, logoTopAnchor - logoBmp.height, lp)
        logoBmp.recycle()
    }
    if (inPlace && source !== out) source.recycle()
    return out
}

/**
 * Strap theme: dark footer bar below the image.
 * Left column: brand logo + exposure. Right column: camera model.
 */
private fun burnExifThemeStrap(source: Bitmap, cfg: ExifWatermarkConfig, inPlace: Boolean, ctx: Context?): Bitmap {
    val w = source.width; val h = source.height
    val shortSide = minOf(w, h).toFloat()
    val textSz = (shortSide * cfg.textSize.fraction).coerceAtLeast(10f)
    val barH = (textSz * 4.5f).toInt()
    val out = Bitmap.createBitmap(w, h + barH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(source, 0f, 0f, null)
    canvas.drawRect(0f, h.toFloat(), w.toFloat(), (h + barH).toFloat(), Paint().apply { color = android.graphics.Color.BLACK })
    val typeface = if (ctx != null) cfg.font.toTypeface(ctx, false, cfg.italic) else Typeface.DEFAULT
    fun tp(sz: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cfg.textColor; textSize = sz; this.typeface = typeface; letterSpacing = cfg.letterSpacing
    }
    val marginX = textSz * 0.8f; val logoH = shortSide * 0.12f
    val lineGap = textSz * cfg.lineSpacing.coerceIn(0.5f, 3f)
    val shutterStr = formatShutter(cfg.shutterSpeed)
    val exposure = if (cfg.showExposure) listOfNotNull(
        "ISO${cfg.iso}".takeIf { cfg.iso > 0 },
        "${"%.0f".format(cfg.focalMm)}mm".takeIf { cfg.focalMm > 0f },
        "F${"%.1f".format(cfg.aperture)}".takeIf { cfg.aperture > 0f },
        shutterStr.takeIf { it.isNotEmpty() },
    ).joinToString("  ") else ""
    val lensStr = if (cfg.showLens) LensDatabase.stripRedundantBrand(correctLensModel(cfg.lensModel, cfg.aperture), cfg.make).trim() else ""
    // Left column: logo (top), exposure (below), lens if present (below)
    val totalLeft = logoH + lineGap + (if (lensStr.isNotBlank()) lineGap else 0f)
    val topY = h + (barH - totalLeft) / 2f
    if (cfg.showLogo && cfg.make.isNotBlank()) {
        runCatching { makeBrandLogoBitmap(cfg.make, logoH, logoH * 3.674f, ctx) }.getOrNull()?.let { bmp ->
            canvas.drawBitmap(bmp, marginX, topY, Paint(Paint.ANTI_ALIAS_FLAG)); bmp.recycle()
        }
    }
    var leftY = topY + logoH + lineGap * 0.425f
    if (exposure.isNotEmpty()) canvas.drawText(exposure, marginX, leftY, tp(textSz * 0.8f))
    if (lensStr.isNotBlank()) {
        leftY += lineGap * 0.85f
        canvas.drawText(lensStr, marginX, leftY, tp(textSz * 0.7f))
    }
    // Right column: camera make + model (centered vertically)
    val brandPart = if (cfg.showBrand) cfg.make.ifBlank { null } else null
    val modelPart = if (cfg.showModel) cfg.model.ifBlank { null } else null
    val modelStr = listOfNotNull(brandPart, modelPart).joinToString(" ")
    val rp = tp(textSz); val rw = rp.measureText(modelStr)
    val ry = h + barH / 2f + textSz * 0.35f
    canvas.drawText(modelStr, w - marginX - rw, ry, rp)
    // Vertical divider
    val divX = w / 2f
    canvas.drawLine(divX, h + marginX, divX, h + barH - marginX, Paint().apply { color = android.graphics.Color.argb(120, 255, 255, 255); strokeWidth = 1f })
    if (inPlace) source.recycle()
    return out
}

/**
 * Lightroom theme: dark footer bar, three zones: exposure | camera | date.
 */
private fun burnExifThemeLightroom(source: Bitmap, cfg: ExifWatermarkConfig, inPlace: Boolean, ctx: Context?): Bitmap {
    val w = source.width; val h = source.height
    val shortSide = minOf(w, h).toFloat()
    val textSz = (shortSide * cfg.textSize.fraction).coerceAtLeast(10f)
    val barH = (textSz * 3.5f).toInt()
    val out = Bitmap.createBitmap(w, h + barH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(source, 0f, 0f, null)
    canvas.drawRect(0f, h.toFloat(), w.toFloat(), (h + barH).toFloat(), Paint().apply { color = android.graphics.Color.parseColor("#1f1f1f") })
    val typeface = if (ctx != null) cfg.font.toTypeface(ctx, false, cfg.italic) else Typeface.DEFAULT
    fun tp(sz: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cfg.textColor; textSize = sz; this.typeface = typeface; letterSpacing = cfg.letterSpacing
    }
    val mX = textSz * 0.8f
    val lineGap = textSz * cfg.lineSpacing.coerceIn(0.5f, 3f)
    val lensStr = if (cfg.showLens) LensDatabase.stripRedundantBrand(correctLensModel(cfg.lensModel, cfg.aperture), cfg.make).trim() else ""
    val hasLens = lensStr.isNotBlank()
    // Increase bar height if lens info is present (need two lines on left)
    val actualBarH = if (hasLens) (textSz * 4.5f).toInt() else barH
    val out2 = if (hasLens) {
        val b = Bitmap.createBitmap(w, h + actualBarH, Bitmap.Config.ARGB_8888)
        Canvas(b).also { c ->
            c.drawBitmap(source, 0f, 0f, null)
            c.drawRect(0f, h.toFloat(), w.toFloat(), (h + actualBarH).toFloat(), Paint().apply { color = android.graphics.Color.parseColor("#1f1f1f") })
        }
        b
    } else out
    val canvas2 = if (hasLens) Canvas(out2) else canvas
    val shutterStr = formatShutter(cfg.shutterSpeed)
    val exposure = if (cfg.showExposure) listOfNotNull(
        "ISO${cfg.iso}".takeIf { cfg.iso > 0 },
        "F${"%.1f".format(cfg.aperture)}".takeIf { cfg.aperture > 0f },
        shutterStr.takeIf { it.isNotEmpty() },
        "${"%.0f".format(cfg.focalMm)}mm".takeIf { cfg.focalMm > 0f },
    ).joinToString("  ") else ""
    val barTop = if (hasLens) (h + actualBarH) else (h + barH)
    val mid = barTop - (if (hasLens) actualBarH else barH) / 2f
    val cy = mid + textSz * 0.35f
    // Left zone: exposure (+ lens below)
    if (exposure.isNotEmpty()) canvas2.drawText(exposure, mX, if (hasLens) cy - lineGap * 0.5f else cy, tp(textSz))
    if (hasLens) canvas2.drawText(lensStr, mX, cy + lineGap * 0.5f, tp(textSz * 0.75f))
    // Center zone: camera make/model
    val brandPart2 = if (cfg.showBrand) cfg.make.ifBlank { null } else null
    val modelPart2 = if (cfg.showModel) cfg.model.ifBlank { null } else null
    val model = listOfNotNull(brandPart2, modelPart2).joinToString(" ")
    val cp = tp(textSz); val cw = cp.measureText(model)
    if (model.isNotEmpty()) canvas2.drawText(model, (w - cw) / 2f, cy, cp)
    // Right zone: date (only when showDate is enabled)
    if (cfg.showDate && cfg.dateTimeOriginal.isNotBlank()) {
        val dp = tp(textSz * 0.8f); val dw = dp.measureText(cfg.dateTimeOriginal)
        canvas2.drawText(cfg.dateTimeOriginal, w - dw - mX, cy, dp)
    }
    if (inPlace) source.recycle()
    return if (hasLens) out2 else out
}

/**
 * Film theme: black letterbox bars top + bottom; orange exposure text in lower-right,
 * camera/lens in lower-left.
 */
private fun burnExifThemeFilm(source: Bitmap, cfg: ExifWatermarkConfig, inPlace: Boolean, ctx: Context?): Bitmap {
    val w = source.width; val h = source.height
    val shortSide = minOf(w, h).toFloat()
    val textSz = (shortSide * cfg.textSize.fraction).coerceAtLeast(10f)
    val barH = (textSz * 3.0f).toInt()
    val out = Bitmap.createBitmap(w, h + barH * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawRect(0f, 0f, w.toFloat(), barH.toFloat(), Paint().apply { color = android.graphics.Color.BLACK })
    canvas.drawBitmap(source, 0f, barH.toFloat(), null)
    canvas.drawRect(0f, (h + barH).toFloat(), w.toFloat(), (h + barH * 2).toFloat(), Paint().apply { color = android.graphics.Color.BLACK })
    val typeface = if (ctx != null) cfg.font.toTypeface(ctx, false, cfg.italic) else Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val orange = android.graphics.Color.parseColor("#FFA500")
    fun op(sz: Float, col: Int = orange) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col; textSize = sz; this.typeface = typeface; letterSpacing = cfg.letterSpacing
    }
    val mX = textSz * 0.8f
    val cy = h + barH + barH / 2f + textSz * 0.35f
    val shutterStr = formatShutter(cfg.shutterSpeed)
    val exposure = listOfNotNull(
        "ISO${cfg.iso}".takeIf { cfg.iso > 0 },
        "F${"%.1f".format(cfg.aperture)}".takeIf { cfg.aperture > 0f },
        shutterStr.takeIf { it.isNotEmpty() },
        "${"%.0f".format(cfg.focalMm)}mm".takeIf { cfg.focalMm > 0f },
    ).joinToString("  ")
    val ep = op(textSz); val ew = ep.measureText(exposure)
    canvas.drawText(exposure, w - ew - mX, cy, ep)
    val camStr = "${cfg.make} ${cfg.model}".trim().ifBlank { cfg.lensModel }
    canvas.drawText(camStr, mX, cy, op(textSz, android.graphics.Color.WHITE))
    if (inPlace) source.recycle()
    return out
}

/**
 * Monitor theme: exposure values spread evenly across a dark bottom bar (HUD aesthetic).
 */
private fun burnExifThemeMonitor(source: Bitmap, cfg: ExifWatermarkConfig, inPlace: Boolean, ctx: Context?): Bitmap {
    val w = source.width; val h = source.height
    val shortSide = minOf(w, h).toFloat()
    val textSz = (shortSide * cfg.textSize.fraction).coerceAtLeast(10f)
    val barH = (textSz * 2.8f).toInt()
    val out = Bitmap.createBitmap(w, h + barH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(source, 0f, 0f, null)
    canvas.drawRect(0f, h.toFloat(), w.toFloat(), (h + barH).toFloat(), Paint().apply { color = android.graphics.Color.BLACK })
    val typeface = if (ctx != null) cfg.font.toTypeface(ctx, false, cfg.italic) else Typeface.DEFAULT
    fun tp(sz: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = sz; this.typeface = typeface; textAlign = Paint.Align.CENTER; letterSpacing = cfg.letterSpacing
    }
    val shutterStr = formatShutter(cfg.shutterSpeed)
    val items = listOfNotNull(
        "F${"%.1f".format(cfg.aperture)}".takeIf { cfg.aperture > 0f },
        shutterStr.takeIf { it.isNotEmpty() },
        "ISO ${cfg.iso}".takeIf { cfg.iso > 0 },
        "${"%.0f".format(cfg.focalMm)} mm".takeIf { cfg.focalMm > 0f },
    )
    if (items.isNotEmpty()) {
        val step = w.toFloat() / items.size
        val cy = h + barH / 2f + textSz * 0.35f
        items.forEachIndexed { i, label -> canvas.drawText(label, step * i + step / 2f, cy, tp(textSz)) }
    }
    if (inPlace) source.recycle()
    return out
}

/**
 * Shot On theme: single centred line at bottom — "Shot on {make} {model}".
 */
private fun burnExifThemeShotOn(source: Bitmap, cfg: ExifWatermarkConfig, inPlace: Boolean, ctx: Context?): Bitmap {
    val out = if (inPlace) source else source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val w = out.width; val h = out.height
    val shortSide = minOf(w, h).toFloat()
    val textSz = (shortSide * cfg.textSize.fraction).coerceAtLeast(10f)
    val marginY = textSz * 1.5f
    val typeface = if (ctx != null) cfg.font.toTypeface(ctx, false, cfg.italic) else Typeface.DEFAULT
    val paintAlign = when (cfg.textAlign) {
        WatermarkTextAlign.Left   -> Paint.Align.LEFT
        WatermarkTextAlign.Center -> Paint.Align.CENTER
        WatermarkTextAlign.Right  -> Paint.Align.RIGHT
    }
    val anchorX: Float = when (cfg.textAlign) {
        WatermarkTextAlign.Left   -> textSz * 0.8f
        WatermarkTextAlign.Center -> w / 2f
        WatermarkTextAlign.Right  -> w - textSz * 0.8f
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cfg.textColor; textSize = textSz; this.typeface = typeface; textAlign = paintAlign
        letterSpacing = cfg.letterSpacing
        if (cfg.textShadow) setShadowLayer(textSz * 0.15f, textSz * 0.06f, textSz * 0.06f, android.graphics.Color.argb(180, 0, 0, 0))
    }
    val text = "Shot on ${cfg.make} ${cfg.model}".trim()
    if (cfg.bgEnabled) {
        val textW = paint.measureText(text)
        val bgPadH = textSz * 0.5f
        val bgPadV = textSz * 0.4f
        val cornerRad = textSz * 0.5f
        val bgLeft = when (cfg.textAlign) {
            WatermarkTextAlign.Left   -> anchorX - bgPadH
            WatermarkTextAlign.Center -> anchorX - textW / 2f - bgPadH
            WatermarkTextAlign.Right  -> anchorX - textW - bgPadH
        }
        val bgRight = bgLeft + textW + bgPadH * 2f
        val bgBottom = h - marginY + textSz * 0.3f + bgPadV
        val bgTop    = bgBottom - textSz - bgPadV * 2f
        val alpha = (cfg.bgOpacity.coerceIn(0f, 1f) * 255).toInt()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(alpha,
                android.graphics.Color.red(cfg.bgColor),
                android.graphics.Color.green(cfg.bgColor),
                android.graphics.Color.blue(cfg.bgColor))
        }
        canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, cornerRad, cornerRad, bgPaint)
    }
    canvas.drawText(text, anchorX, h - marginY, paint)
    if (inPlace && source !== out) source.recycle()
    return out
}

internal fun formatShutter(shutterSec: Float): String {
    if (shutterSec <= 0f) return ""
    return if (shutterSec >= 1f) {
        "${"%.1f".format(shutterSec)}s"
    } else {
        val denom = (1f / shutterSec).roundToInt()
        "1/${denom}s"
    }
}

fun burnFooterGradient(source: Bitmap, cfg: FooterGradientConfig, inPlace: Boolean = false): Bitmap {
    val out = if (inPlace) source else source.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width; val h = out.height
    val baseR = android.graphics.Color.red(cfg.color)
    val baseG = android.graphics.Color.green(cfg.color)
    val baseB = android.graphics.Color.blue(cfg.color)
    val peakAlpha = (cfg.intensity * 255).toInt().coerceIn(0, 255)
    val canvas = Canvas(out)
    val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    if (cfg.direction == FooterGradientDirection.DownToUp) {
        val bandH = (h * cfg.length).toInt().coerceAtLeast(4)
        val top = h - bandH

        // Down-to-up: transparent at band top → opaque at bottom
        gradPaint.shader = LinearGradient(
            0f, top.toFloat(), 0f, h.toFloat(),
            android.graphics.Color.argb(0, baseR, baseG, baseB),
            android.graphics.Color.argb(peakAlpha, baseR, baseG, baseB),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, top.toFloat(), w.toFloat(), h.toFloat(), gradPaint)

        if (cfg.style == FooterGradientStyle.Dots) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val gridStep = (bandH / 12f).coerceAtLeast(4f)
            val cols = ceil(w / gridStep).toInt() + 1
            val rows = ceil(bandH / gridStep).toInt() + 1
            for (row in 0..rows) {
                val cy = top + row * gridStep
                val t = ((cy - top) / bandH).coerceIn(0f, 1f)
                val maxRadius = gridStep * 0.44f * t
                if (maxRadius < 0.5f) continue
                val xOffset = if (row % 2 == 0) 0f else gridStep * 0.5f
                for (col in 0..cols) {
                    val cx = col * gridStep + xOffset
                    dotPaint.color = android.graphics.Color.argb((cfg.intensity * t * 255).toInt().coerceIn(0, 255), baseR, baseG, baseB)
                    canvas.drawCircle(cx, cy, maxRadius, dotPaint)
                }
            }
        }
    } else {
        // Side-to-side: gradient runs horizontally.
        // FeatherRight → solid on RIGHT side, fades to transparent on LEFT
        // FeatherLeft  → solid on LEFT side, fades to transparent on RIGHT
        val bandH = (h * cfg.length).toInt().coerceAtLeast(4)
        val bandTop = h - bandH
        val gradW = (w * cfg.sideLength).coerceAtLeast(4f)
        // feather zone = soft ramp from 0→peakAlpha at the transparent end
        val featherW = (gradW * cfg.feather).coerceAtLeast(1f)

        if (cfg.direction == FooterGradientDirection.FeatherRight) {
            // solid RIGHT → fade left: opaque at x=w, transparent by x=(w-gradW)
            val solidX = w.toFloat()
            val fadeX  = w - gradW
            val featherX = fadeX + featherW   // ramp starts at fadeX, reaches peak at featherX
            gradPaint.shader = LinearGradient(
                fadeX, 0f, solidX, 0f,
                android.graphics.Color.argb(0, baseR, baseG, baseB),
                android.graphics.Color.argb(peakAlpha, baseR, baseG, baseB),
                Shader.TileMode.CLAMP,
            )
        } else {
            // solid LEFT → fade right: opaque at x=0, transparent by x=gradW
            gradPaint.shader = LinearGradient(
                0f, 0f, gradW, 0f,
                android.graphics.Color.argb(peakAlpha, baseR, baseG, baseB),
                android.graphics.Color.argb(0, baseR, baseG, baseB),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, bandTop.toFloat(), w.toFloat(), h.toFloat(), gradPaint)

        if (cfg.style == FooterGradientStyle.Dots) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val gridStep = (bandH / 12f).coerceAtLeast(4f)
            val cols = ceil(gradW / gridStep).toInt() + 2
            val rows = ceil(bandH / gridStep).toInt() + 1
            for (row in 0..rows) {
                val cy = bandTop + row * gridStep
                val xOffset = if (row % 2 == 0) 0f else gridStep * 0.5f
                for (col in 0..cols) {
                    val localX = col * gridStep + xOffset
                    // t = how far from the transparent edge toward the solid edge
                    val t = if (cfg.direction == FooterGradientDirection.FeatherRight) (localX / gradW).coerceIn(0f, 1f)
                            else (1f - localX / gradW).coerceIn(0f, 1f)
                    val maxRadius = gridStep * 0.44f * t
                    if (maxRadius < 0.5f) continue
                    val cx = if (cfg.direction == FooterGradientDirection.FeatherRight) w - gradW + localX else localX
                    dotPaint.color = android.graphics.Color.argb((cfg.intensity * t * 255).toInt().coerceIn(0, 255), baseR, baseG, baseB)
                    canvas.drawCircle(cx, cy, maxRadius, dotPaint)
                }
            }
        }
    }
    return out
}

private fun burnText(source: Bitmap, cfg: WatermarkConfig.TextConfig, inPlace: Boolean = false, ctx: Context? = null): Bitmap {
    val out = if (inPlace) source else source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val tf = if (ctx != null) {
        cfg.font.toTypeface(ctx, cfg.bold, cfg.italic)
    } else {
        val style = when {
            cfg.bold && cfg.italic -> Typeface.BOLD_ITALIC
            cfg.bold -> Typeface.BOLD
            cfg.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        Typeface.defaultFromStyle(style)
    }
    val shortSide = minOf(source.width, source.height).toFloat()
    val szHeader = (shortSide * cfg.sizePercent).coerceAtLeast(12f)
    val szDesc   = szHeader * 0.74f
    val margin   = shortSide * 0.03f
    val baseColor = cfg.color.copy(alpha = cfg.opacity).toArgb()
    val paintAlign = when (cfg.textAlign) {
        WatermarkTextAlign.Left   -> Paint.Align.LEFT
        WatermarkTextAlign.Center -> Paint.Align.CENTER
        WatermarkTextAlign.Right  -> Paint.Align.RIGHT
    }
    fun mkPaint(sz: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = baseColor; textSize = sz; typeface = tf; setTextAlign(paintAlign)
        letterSpacing = cfg.letterSpacing
        if (cfg.textShadow) setShadowLayer(sz * 0.15f, sz * 0.05f, sz * 0.05f, android.graphics.Color.argb(160, 0, 0, 0))
    }
    val ph = mkPaint(szHeader)
    val pd = mkPaint(szDesc)
    val headerText = cfg.header.ifBlank { cfg.text }
    val descText   = cfg.description
    val lineGap    = szHeader * cfg.lineSpacing.coerceIn(0.5f, 3f)
    val blockH     = szHeader + (if (descText.isNotBlank()) lineGap else 0f)
    val hw = ph.measureText(headerText)
    val (_, by) = stampXY(cfg.position, source.width, source.height, hw, blockH, margin, ph.fontMetrics.ascent)
    val anchorX = when (cfg.textAlign) {
        WatermarkTextAlign.Left   -> margin
        WatermarkTextAlign.Center -> source.width / 2f
        WatermarkTextAlign.Right  -> source.width - margin
    }

    // Background panel behind the text block
    if (cfg.bgEnabled) {
        val alpha = (cfg.bgOpacity.coerceIn(0f, 1f) * 255).toInt()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(alpha,
                android.graphics.Color.red(cfg.bgColor),
                android.graphics.Color.green(cfg.bgColor),
                android.graphics.Color.blue(cfg.bgColor))
        }
        val padH = szHeader * 0.5f
        val padV = szHeader * 0.4f
        val corner = szHeader * 0.4f
        val textLeft = when (cfg.textAlign) {
            WatermarkTextAlign.Left   -> anchorX - padH
            WatermarkTextAlign.Center -> anchorX - hw / 2f - padH
            WatermarkTextAlign.Right  -> anchorX - hw - padH
        }
        val textRight = textLeft + hw + padH * 2f
        val textTop = by + ph.fontMetrics.ascent - padV
        val textBottom = by + (if (descText.isNotBlank()) lineGap else 0f) + pd.fontMetrics.descent + padV
        canvas.drawRoundRect(textLeft, textTop, textRight, textBottom, corner, corner, bgPaint)
    }

    if (headerText.isNotBlank()) canvas.drawText(headerText, anchorX, by, ph)
    if (descText.isNotBlank())   canvas.drawText(descText,   anchorX, by + lineGap, pd)
    return out
}

private fun burnImage(source: Bitmap, cfg: WatermarkConfig.ImageConfig, inPlace: Boolean = false): Bitmap {
    val out = if (inPlace) source else source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val shortSide = minOf(source.width, source.height).toFloat()
    val logoW = (shortSide * cfg.sizePercent).toInt().coerceAtLeast(10)
    val logoH = (logoW.toFloat() / cfg.logoBitmap.width * cfg.logoBitmap.height).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(cfg.logoBitmap, logoW, logoH, true)
    val margin = (shortSide * 0.03f).toInt()
    val (ix, iy) = stampXYImage(cfg.position, source.width, source.height, logoW, logoH, margin)
    val paint = Paint().apply { alpha = (cfg.opacity * 255).toInt().coerceIn(0, 255) }
    canvas.drawBitmap(scaled, ix.toFloat(), iy.toFloat(), paint)
    if (scaled !== cfg.logoBitmap) scaled.recycle()
    return out
}

fun stampXYPublic(pos: WatermarkPosition, w: Int, h: Int, textW: Float, textH: Float, margin: Float, ascent: Float): Pair<Float, Float> =
    stampXY(pos, w, h, textW, textH, margin, ascent)

fun stampXYImagePublic(pos: WatermarkPosition, w: Int, h: Int, lw: Int, lh: Int, margin: Int): Pair<Int, Int> =
    stampXYImage(pos, w, h, lw, lh, margin)

private fun stampXY(pos: WatermarkPosition, w: Int, h: Int, textW: Float, textH: Float, margin: Float, ascent: Float): Pair<Float, Float> = when (pos) {
    WatermarkPosition.TOP_LEFT      -> margin to (margin - ascent)
    WatermarkPosition.TOP_CENTER    -> ((w - textW) / 2f) to (margin - ascent)
    WatermarkPosition.TOP_RIGHT     -> (w - textW - margin) to (margin - ascent)
    WatermarkPosition.CENTER        -> ((w - textW) / 2f) to ((h - textH) / 2f - ascent)
    WatermarkPosition.BOTTOM_LEFT   -> margin to (h - margin - (textH + ascent))
    WatermarkPosition.BOTTOM_CENTER -> ((w - textW) / 2f) to (h - margin - (textH + ascent))
    WatermarkPosition.BOTTOM_RIGHT  -> (w - textW - margin) to (h - margin - (textH + ascent))
}

private fun stampXYImage(pos: WatermarkPosition, w: Int, h: Int, lw: Int, lh: Int, margin: Int): Pair<Int, Int> = when (pos) {
    WatermarkPosition.TOP_LEFT      -> margin to margin
    WatermarkPosition.TOP_CENTER    -> ((w - lw) / 2) to margin
    WatermarkPosition.TOP_RIGHT     -> (w - lw - margin) to margin
    WatermarkPosition.CENTER        -> ((w - lw) / 2) to ((h - lh) / 2)
    WatermarkPosition.BOTTOM_LEFT   -> margin to (h - lh - margin)
    WatermarkPosition.BOTTOM_CENTER -> ((w - lw) / 2) to (h - lh - margin)
    WatermarkPosition.BOTTOM_RIGHT  -> (w - lw - margin) to (h - lh - margin)
}

// ── Preset colors ─────────────────────────────────────────────────────────────

private val PRESET_COLORS = listOf(
    Color.White, Color.Black, Color(0xFFCCCCCC), Color(0xFF888888),
    Color(0xFFFFD700), Color(0xFFFF6B6B), Color(0xFF6BCFFF), Color(0xFF90EE90),
)

// ── Sheet composable ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RawWatermarkSheet(
    source: Bitmap,
    initialConfig: CombinedWatermarkConfig? = null,
    metadata: RawMetadata? = null,
    onDismiss: () -> Unit,
    onWatermarkConfigured: (CombinedWatermarkConfig?) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // Tab: 0 = Text, 1 = Image, 2 = Footer, 3 = EXIF
    var selectedTab by remember { mutableIntStateOf(0) }

    // ── Text state ────────────────────────────────────────────────────────────
    var textEnabled   by remember { mutableStateOf(initialConfig?.text != null) }
    var watermarkText by remember { mutableStateOf(initialConfig?.text?.text ?: "") }
    var textHeader    by remember { mutableStateOf(initialConfig?.text?.header ?: "") }
    var textDesc      by remember { mutableStateOf(initialConfig?.text?.description ?: "") }
    var textAlign     by remember { mutableStateOf(initialConfig?.text?.textAlign ?: WatermarkTextAlign.Left) }
    var textPosition  by remember { mutableStateOf(initialConfig?.text?.position ?: WatermarkPosition.BOTTOM_RIGHT) }
    var textColor     by remember { mutableStateOf(initialConfig?.text?.color ?: Color.White) }
    var textSizeEnum  by remember {
        val saved = watermarkPrefs(context).getString(KEY_TEXT_SIZE_ENUM, WatermarkTextSize.Small.name)
        mutableStateOf(runCatching { WatermarkTextSize.valueOf(saved ?: "") }.getOrDefault(WatermarkTextSize.Small))
    }
    var selectedFont  by remember {
        mutableStateOf(WatermarkFont.fromKey(watermarkPrefs(context).getString(KEY_TEXT_FONT, null)))
    }
    var showFontPicker by remember { mutableStateOf(false) }
    var textOpacity   by remember { mutableFloatStateOf((initialConfig?.text?.opacity ?: 0.85f).coerceIn(0.1f, 1.0f)) }
    var textBold      by remember { mutableStateOf(initialConfig?.text?.bold ?: false) }
    var textItalic    by remember { mutableStateOf(initialConfig?.text?.italic ?: false) }
    var textShadow    by remember { mutableStateOf(initialConfig?.text?.textShadow ?: true) }
    var textLetterSpacing by remember { mutableFloatStateOf(initialConfig?.text?.letterSpacing ?: 0f) }
    var textLineSpacing by remember { mutableFloatStateOf(initialConfig?.text?.lineSpacing ?: 1.25f) }
    var textBgEnabled by remember { mutableStateOf(initialConfig?.text?.bgEnabled ?: false) }
    var textBgColor   by remember { mutableIntStateOf(initialConfig?.text?.bgColor ?: android.graphics.Color.BLACK) }
    var textBgOpacity by remember { mutableFloatStateOf(initialConfig?.text?.bgOpacity ?: 0.6f) }
    var showTextBgColorPicker by remember { mutableStateOf(false) }

    // ── EXIF strip state ──────────────────────────────────────────────────────
    val prefs = watermarkPrefs(context)
    // Enabled follows the APPLIED config (like Text/Image/Footer), NOT the global
    // pref — so opening the Watermark sheet never auto-turns-on EXIF; only the
    // user ticking "Enable EXIF watermark" enables it. (Theme/font/size below
    // still read prefs to remember styling preferences — just not the on/off.)
    var exifEnabled   by remember { mutableStateOf(initialConfig?.exif != null) }
    var exifTheme     by remember {
        mutableStateOf(
            prefs.getString(KEY_EXIF_THEME, null)
                ?.let { runCatching { ExifTheme.valueOf(it) }.getOrNull() }
                ?: ExifTheme.Overlay
        )
    }
    var exifFont      by remember {
        mutableStateOf(WatermarkFont.fromKey(prefs.getString(KEY_EXIF_FONT, null)))
    }
    var exifTextSize  by remember {
        mutableStateOf(
            prefs.getString(KEY_EXIF_SIZE_ENUM, null)
                ?.let { runCatching { WatermarkTextSize.valueOf(it) }.getOrNull() }
                ?: WatermarkTextSize.Small
        )
    }
    var showExifFontPicker by remember { mutableStateOf(false) }

    // Editable EXIF overrides — pre-filled from metadata, user can change in the EXIF tab
    var exifShadow       by remember { mutableStateOf(initialConfig?.exif?.textShadow ?: true) }
    var exifTextColor    by remember { mutableIntStateOf(initialConfig?.exif?.textColor ?: android.graphics.Color.WHITE) }
    var exifShowLogo     by remember { mutableStateOf(initialConfig?.exif?.showLogo ?: true) }
    var exifShowBrand    by remember { mutableStateOf(initialConfig?.exif?.showBrand ?: true) }
    var exifShowModel    by remember { mutableStateOf(initialConfig?.exif?.showModel ?: true) }
    var exifShowLens     by remember { mutableStateOf(initialConfig?.exif?.showLens ?: true) }
    var exifShowExposure by remember { mutableStateOf(initialConfig?.exif?.showExposure ?: true) }
    var exifShowDate     by remember { mutableStateOf(initialConfig?.exif?.showDate ?: false) }
    // "" = "As per EXIF" (dynamic per-file); non-blank = fixed user override.
    // Seed from initialConfig so loading a preset restores its saved choice.
    // Do NOT seed from live metadata — that would bake the current file's
    // make/model/lens into the preset and break batch (all files would get
    // this file's strings instead of their own EXIF).
    var exifMakeOverride  by remember { mutableStateOf(initialConfig?.exif?.make      ?: "") }
    var exifModelOverride by remember { mutableStateOf(initialConfig?.exif?.model     ?: "") }
    var exifLensOverride  by remember { mutableStateOf(initialConfig?.exif?.lensModel ?: "") }
    var exifTextAlign  by remember { mutableStateOf(initialConfig?.exif?.textAlign ?: WatermarkTextAlign.Left) }
    var exifBgEnabled  by remember {
        mutableStateOf(
            initialConfig?.exif?.bgEnabled
                ?: prefs.getBoolean(KEY_EXIF_BG_ENABLED, false)
        )
    }
    var exifBgColor    by remember {
        mutableIntStateOf(
            initialConfig?.exif?.bgColor
                ?: prefs.getInt(KEY_EXIF_BG_COLOR, android.graphics.Color.BLACK)
        )
    }
    var exifBgOpacity  by remember {
        mutableFloatStateOf(
            initialConfig?.exif?.bgOpacity
                ?: prefs.getFloat(KEY_EXIF_BG_OPACITY, 0.6f)
        )
    }
    var exifItalic     by remember { mutableStateOf(initialConfig?.exif?.italic ?: false) }
    var exifLetterSpacing by remember { mutableFloatStateOf(initialConfig?.exif?.letterSpacing ?: 0f) }
    var exifLineSpacing by remember { mutableFloatStateOf(initialConfig?.exif?.lineSpacing ?: 1.3f) }
    var showExifBgColorPicker by remember { mutableStateOf(false) }

    // ── Footer gradient state ─────────────────────────────────────────────────
    var footerEnabled     by remember { mutableStateOf(initialConfig?.footer != null) }
    var footerColor       by remember { mutableIntStateOf(initialConfig?.footer?.color ?: android.graphics.Color.BLACK) }
    var footerIntensity   by remember { mutableFloatStateOf((initialConfig?.footer?.intensity ?: 0.7f).coerceIn(0.1f, 1.0f)) }
    var footerLength      by remember { mutableFloatStateOf((initialConfig?.footer?.length ?: 0.25f).coerceIn(0.05f, 0.60f)) }
    var footerStyle       by remember { mutableStateOf(initialConfig?.footer?.style ?: FooterGradientStyle.Plain) }
    var footerDirection   by remember { mutableStateOf(initialConfig?.footer?.direction ?: FooterGradientDirection.DownToUp) }
    var footerSideLength  by remember { mutableFloatStateOf(initialConfig?.footer?.sideLength ?: 0.40f) }
    var footerFeather     by remember { mutableFloatStateOf(initialConfig?.footer?.feather ?: 0.30f) }
    var footerFeatherRight by remember { mutableStateOf(initialConfig?.footer?.featherRight ?: true) }
    var showFooterColorPicker by remember { mutableStateOf(false) }
    var showTextColorPicker   by remember { mutableStateOf(false) }
    var showExifColorPicker   by remember { mutableStateOf(false) }

    // ── Image state ───────────────────────────────────────────────────────────
    var imageEnabled   by remember { mutableStateOf(initialConfig?.image != null) }
    var logoBitmap     by remember { mutableStateOf<Bitmap?>(initialConfig?.image?.logoBitmap) }
    var logoFileName   by remember { mutableStateOf(initialConfig?.image?.logoFileName ?: "") }
    var imagePosition  by remember { mutableStateOf(initialConfig?.image?.position ?: WatermarkPosition.BOTTOM_RIGHT) }
    var imageSize      by remember { mutableFloatStateOf((initialConfig?.image?.sizePercent ?: 0.20f).coerceIn(0.05f, 0.50f)) }
    var imageOpacity   by remember { mutableFloatStateOf((initialConfig?.image?.opacity ?: 0.85f).coerceIn(0.1f, 1.0f)) }

    // PNG-only image picker
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val name = uri.lastPathSegment?.substringAfterLast("/") ?: "logo.png"
                val bmp = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        logoBitmap = bmp
                        logoFileName = name
                        imageEnabled = true
                    }
                }
            }
        }
    }

    // ── Live preview config ───────────────────────────────────────────────────
    val hasTextContent = textEnabled && (textHeader.isNotBlank() || textDesc.isNotBlank() || watermarkText.isNotBlank())
    val previewTextCfg: WatermarkConfig.TextConfig? = if (hasTextContent)
        WatermarkConfig.TextConfig(
            text = watermarkText, header = textHeader, description = textDesc, textAlign = textAlign,
            position = textPosition, color = textColor, sizePercent = textSizeEnum.fraction,
            opacity = textOpacity, bold = textBold, italic = textItalic, font = selectedFont,
            textShadow = textShadow, letterSpacing = textLetterSpacing, lineSpacing = textLineSpacing,
            bgEnabled = textBgEnabled, bgColor = textBgColor, bgOpacity = textBgOpacity,
        )
    else null
    val previewImgCfg: WatermarkConfig.ImageConfig? = if (imageEnabled && logoBitmap != null)
        WatermarkConfig.ImageConfig(logoBitmap!!, logoFileName, imagePosition, imageSize, imageOpacity)
    else null
    val previewFooterCfg: FooterGradientConfig? = if (footerEnabled)
        FooterGradientConfig(footerColor, footerIntensity, footerLength, footerStyle, footerDirection, footerSideLength, footerFeather, footerFeatherRight)
    else null
    val wmCtx = androidx.compose.ui.platform.LocalContext.current
    val previewExifCfg: ExifWatermarkConfig? = if (exifEnabled && metadata != null)
        ExifWatermarkConfig(
            make = exifMakeOverride.ifBlank { metadata.cameraMake },
            // Auto model → marketing name (e.g. "Infinix X6873" → "GT 30 Pro").
            // A user override is kept verbatim (only the ifBlank branch maps).
            model = exifModelOverride.ifBlank {
                mapModelToMarketing(wmCtx, exifMakeOverride.ifBlank { metadata.cameraMake }, metadata.cameraModel)
            },
            lensModel = exifLensOverride.ifBlank { metadata.lensInfo },
            iso = metadata.iso,
            shutterSpeed = metadata.shutterSpeed,
            aperture = metadata.aperture,
            focalMm = metadata.focalLength,
            dateTimeOriginal = metadata.dateTimeOriginal,
            textSize = exifTextSize,
            font = exifFont,
            theme = exifTheme,
            textShadow = exifShadow,
            textColor = exifTextColor,
            showLogo = exifShowLogo,
            showBrand = exifShowBrand,
            showModel = exifShowModel,
            showLens = exifShowLens,
            showExposure = exifShowExposure,
            showDate = exifShowDate,
            textAlign = exifTextAlign,
            bgEnabled = exifBgEnabled,
            bgColor   = exifBgColor,
            bgOpacity = exifBgOpacity,
            italic = exifItalic,
            letterSpacing = exifLetterSpacing,
            lineSpacing = exifLineSpacing,
        )
    else null
    val hasAnything = hasTextContent || previewImgCfg != null || previewFooterCfg != null || previewExifCfg != null

    // ── WYSIWYG preview (single source of truth) ──────────────────────────────
    // Burn the CURRENT combined config onto a copy of [source] with the SAME
    // burnCombinedWatermarkOnto the Save path uses, so the preview's anchoring +
    // sizing match the exported file EXACTLY. This replaces the old hand-mirrored
    // Canvas overlay below (disabled via an early return), which re-derived anchors
    // against the display canvas and drifted — the EXIF "Overlay" theme rendered
    // its text out of the photo frame in the preview while the save was correct.
    val previewCombined = CombinedWatermarkConfig(
        previewTextCfg, previewImgCfg, previewFooterCfg, previewExifCfg,
    )
    val shownPreview by androidx.compose.runtime.produceState(source, source, previewCombined) {
        value = if (!hasAnything) source
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                burnCombinedWatermarkOnto(
                    source.copy(android.graphics.Bitmap.Config.ARGB_8888, true),
                    previewCombined, context,
                )
            }.getOrDefault(source)
        }
    }

    // ── Preset save / delete dialog state ────────────────────────────────────
    var showSavePresetDialog   by remember { mutableStateOf(false) }
    var presetNameInput        by remember { mutableStateOf("") }
    var showDeletePresetDialog by remember { mutableStateOf(false) }
    var wmPresetListKey        by remember { mutableStateOf(0) }

    if (showSavePresetDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text(stringResource(CoreR.string.watermark_save_preset)) },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text(stringResource(CoreR.string.watermark_preset_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = presetNameInput.trim()
                        if (name.isNotBlank() && hasAnything) {
                            val cfg = CombinedWatermarkConfig(previewTextCfg, previewImgCfg, previewFooterCfg, previewExifCfg)
                            saveWatermarkPreset(context, name, cfg)
                        }
                        showSavePresetDialog = false
                    },
                    enabled = presetNameInput.isNotBlank() && hasAnything,
                ) { Text(stringResource(CoreR.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) { Text(stringResource(CoreR.string.cancel)) }
            },
        )
    }

    if (showDeletePresetDialog) {
        val presets = remember(wmPresetListKey) { listWatermarkPresets(context) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeletePresetDialog = false },
            title = { Text(stringResource(CoreR.string.watermark_delete_preset)) },
            text = {
                if (presets.isEmpty()) {
                    Text(stringResource(CoreR.string.watermark_no_presets))
                } else {
                    LazyColumn {
                        items(presets) { preset: WatermarkPreset ->
                            ListItem(
                                headlineContent = { Text(preset.name) },
                                trailingContent = {
                                    IconButton(onClick = {
                                        deleteWatermarkPreset(context, preset.name)
                                        wmPresetListKey++
                                    }) {
                                        Icon(
                                            imageVector        = AppIcons.Rounded.Delete,
                                            contentDescription = "Delete",
                                            tint               = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeletePresetDialog = false }) { Text(stringResource(CoreR.string.watermark_done)) }
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Rounded.Close, contentDescription = "Back")
                    }
                },
                title = { Text(stringResource(CoreR.string.watermark_title), fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (initialConfig != null) {
                        TextButton(onClick = {
                            onWatermarkConfigured(null)
                        }) { Text(stringResource(CoreR.string.watermark_remove), color = MaterialTheme.colorScheme.error) }
                    }
                    IconButton(
                        onClick = { wmPresetListKey++; showDeletePresetDialog = true },
                    ) {
                        Icon(AppIcons.Rounded.Delete, contentDescription = "Delete preset")
                    }
                    TextButton(
                        onClick = { presetNameInput = ""; showSavePresetDialog = true },
                        enabled = hasAnything,
                    ) { Text(stringResource(CoreR.string.watermark_preset)) }
                    Button(
                        onClick = {
                            val cfg = if (hasAnything) CombinedWatermarkConfig(previewTextCfg, previewImgCfg, previewFooterCfg, previewExifCfg) else null
                            onWatermarkConfigured(cfg)
                        },
                        enabled = hasAnything,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(CoreR.string.watermark_apply)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(bottom = 32.dp),
        ) {

            // ── Preview ───────────────────────────────────────────────────────
            // Photo-gallery canvas (matches RawExportScreen): a bounded viewport whose
            // Image is sized to the photo's EXACT aspect ratio and fitted WHOLE, so only
            // the rendered photo area shows — no overflow. The EXIF/watermark is burned
            // into shownPreview, so it previews inside the frame exactly as it saves.
            val ratio = source.width.toFloat() / source.height
            val maxCanvasHeightDp = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxCanvasHeightDp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
            val fitW: androidx.compose.ui.unit.Dp
            val fitH: androidx.compose.ui.unit.Dp
            if (maxWidth / ratio <= maxHeight) { fitW = maxWidth; fitH = maxWidth / ratio }
            else { fitH = maxHeight; fitW = maxHeight * ratio }
            Image(
                bitmap = shownPreview.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .width(fitW)
                    .height(fitH)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.FillBounds,
            )
            }

            // ── Tabs ──────────────────────────────────────────────────────────
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(CoreR.string.watermark_tab_text)) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(CoreR.string.watermark_tab_image)) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(stringResource(CoreR.string.watermark_tab_footer)) })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(stringResource(CoreR.string.watermark_tab_exif)) })
            }

            Spacer(Modifier.height(16.dp))

            when (selectedTab) {
                0 -> TextWatermarkControls(
                    enabled = textEnabled, onEnabledChange = { textEnabled = it },
                    header = textHeader, onHeaderChange = { textHeader = it },
                    description = textDesc, onDescriptionChange = { textDesc = it },
                    textAlign = textAlign, onTextAlignChange = { textAlign = it },
                    position = textPosition, onPositionChange = { textPosition = it },
                    color = textColor, onColorChange = { textColor = it },
                    onOpenColorPicker = { showTextColorPicker = true },
                    textSizeEnum = textSizeEnum, onTextSizeChange = { textSizeEnum = it },
                    opacity = textOpacity, onOpacityChange = { textOpacity = it },
                    bold = textBold, onBoldChange = { textBold = it },
                    italic = textItalic, onItalicChange = { textItalic = it },
                    textShadow = textShadow, onTextShadowChange = { textShadow = it },
                    letterSpacing = textLetterSpacing, onLetterSpacingChange = { textLetterSpacing = it },
                    lineSpacing = textLineSpacing, onLineSpacingChange = { textLineSpacing = it },
                    bgEnabled = textBgEnabled, onBgEnabledChange = { textBgEnabled = it },
                    bgColor = textBgColor, onOpenBgColorPicker = { showTextBgColorPicker = true },
                    bgOpacity = textBgOpacity, onBgOpacityChange = { textBgOpacity = it },
                    selectedFont = selectedFont, onFontPickerOpen = { showFontPicker = true },
                )
                1 -> ImageWatermarkControls(
                    enabled = imageEnabled, onEnabledChange = { imageEnabled = it },
                    logoBitmap = logoBitmap,
                    logoFileName = logoFileName,
                    onPickImage = { imagePicker.launch("image/png") },
                    onRemoveImage = { logoBitmap = null; logoFileName = ""; imageEnabled = false },
                    position = imagePosition, onPositionChange = { imagePosition = it },
                    size = imageSize, onSizeChange = { imageSize = it },
                    opacity = imageOpacity, onOpacityChange = { imageOpacity = it },
                )
                2 -> FooterGradientControls(
                    enabled = footerEnabled, onEnabledChange = { footerEnabled = it },
                    color = footerColor, onColorChange = { footerColor = it },
                    onOpenColorPicker = { showFooterColorPicker = true },
                    intensity = footerIntensity, onIntensityChange = { footerIntensity = it },
                    length = footerLength, onLengthChange = { footerLength = it },
                    style = footerStyle, onStyleChange = { footerStyle = it },
                    direction = footerDirection, onDirectionChange = { footerDirection = it },
                    sideLength = footerSideLength, onSideLengthChange = { footerSideLength = it },
                    feather = footerFeather, onFeatherChange = { footerFeather = it },
                    featherRight = footerFeatherRight, onFeatherRightChange = { footerFeatherRight = it },
                )
                3 -> ExifWatermarkControls(
                    enabled = exifEnabled, onEnabledChange = { exifEnabled = it },
                    hasMetadata = metadata != null,
                    make = exifMakeOverride, onMakeChange = { exifMakeOverride = it },
                    model = exifModelOverride, onModelChange = { exifModelOverride = it },
                    lens = exifLensOverride, onLensChange = { exifLensOverride = it },
                    metaMake = metadata?.cameraMake ?: "",
                    // Show the marketing name as the "As per EXIF" value so the field
                    // matches the burned watermark (mapped from the raw model code).
                    metaModel = metadata?.let { mapModelToMarketing(wmCtx, it.cameraMake, it.cameraModel) } ?: "",
                    metaLens = metadata?.lensInfo ?: "",
                    metaIso = metadata?.iso ?: 0,
                    metaShutter = metadata?.shutterSpeed ?: 0f,
                    metaAperture = metadata?.aperture ?: 0f,
                    metaFocalMm = metadata?.focalLength ?: 0f,
                    metaDate = metadata?.dateTimeOriginal ?: "",
                    textSize = exifTextSize, onTextSizeChange = { exifTextSize = it },
                    textShadow = exifShadow, onTextShadowChange = { exifShadow = it },
                    textColor = exifTextColor, onOpenColorPicker = { showExifColorPicker = true },
                    showLogo = exifShowLogo, onShowLogoChange = { exifShowLogo = it },
                    showModel = exifShowModel, onShowModelChange = { exifShowModel = it },
                    showLens = exifShowLens, onShowLensChange = { exifShowLens = it },
                    showExposure = exifShowExposure, onShowExposureChange = { exifShowExposure = it },
                    showDate = exifShowDate, onShowDateChange = { exifShowDate = it },
                    selectedFont = exifFont, onFontPickerOpen = { showExifFontPicker = true },
                    theme = exifTheme, onThemeChange = { exifTheme = it },
                    textAlign = exifTextAlign, onTextAlignChange = { exifTextAlign = it },
                    bgEnabled = exifBgEnabled, onBgEnabledChange = { exifBgEnabled = it },
                    bgColor = exifBgColor, onOpenBgColorPicker = { showExifBgColorPicker = true },
                    bgOpacity = exifBgOpacity, onBgOpacityChange = { exifBgOpacity = it },
                    italic = exifItalic, onItalicChange = { exifItalic = it },
                    letterSpacing = exifLetterSpacing, onLetterSpacingChange = { exifLetterSpacing = it },
                    lineSpacing = exifLineSpacing, onLineSpacingChange = { exifLineSpacing = it },
                )
            }
        }
    }

    // Color picker for footer — rendered at sheet level (not inside the scrollable Column)
    // so EnhancedModalBottomSheet can overlay correctly without nesting issues.
    if (showFooterColorPicker) {
        ColorPickerSheet(
            visible = true,
            onDismiss = { showFooterColorPicker = false },
            color = Color(footerColor),
            onColorSelected = { footerColor = it.toArgb(); showFooterColorPicker = false },
            allowAlpha = false,
        )
    }
    if (showTextColorPicker) {
        ColorPickerSheet(
            visible = true,
            onDismiss = { showTextColorPicker = false },
            color = textColor,
            onColorSelected = { textColor = it; showTextColorPicker = false },
            allowAlpha = false,
        )
    }
    if (showTextBgColorPicker) {
        ColorPickerSheet(
            visible = true,
            onDismiss = { showTextBgColorPicker = false },
            color = Color(textBgColor),
            onColorSelected = { textBgColor = it.toArgb(); showTextBgColorPicker = false },
            allowAlpha = false,
        )
    }
    if (showExifColorPicker) {
        ColorPickerSheet(
            visible = true,
            onDismiss = { showExifColorPicker = false },
            color = Color(exifTextColor),
            onColorSelected = { exifTextColor = it.toArgb(); showExifColorPicker = false },
            allowAlpha = false,
        )
    }
    if (showExifBgColorPicker) {
        ColorPickerSheet(
            visible = true,
            onDismiss = { showExifBgColorPicker = false },
            color = Color(exifBgColor),
            onColorSelected = { exifBgColor = it.toArgb(); showExifBgColorPicker = false },
            allowAlpha = false,
        )
    }
    if (showFontPicker) {
        WatermarkFontPickerSheet(
            selected = selectedFont,
            onSelect = { selectedFont = it; showFontPicker = false },
            onDismiss = { showFontPicker = false },
        )
    }
    if (showExifFontPicker) {
        WatermarkFontPickerSheet(
            selected = exifFont,
            onSelect = { exifFont = it; showExifFontPicker = false },
            onDismiss = { showExifFontPicker = false },
        )
    }
}

// ── Text controls ─────────────────────────────────────────────────────────────

@Composable
private fun TextWatermarkControls(
    enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    header: String, onHeaderChange: (String) -> Unit,
    description: String, onDescriptionChange: (String) -> Unit,
    textAlign: WatermarkTextAlign, onTextAlignChange: (WatermarkTextAlign) -> Unit,
    position: WatermarkPosition, onPositionChange: (WatermarkPosition) -> Unit,
    color: Color, onColorChange: (Color) -> Unit,
    onOpenColorPicker: () -> Unit,
    textSizeEnum: WatermarkTextSize, onTextSizeChange: (WatermarkTextSize) -> Unit,
    opacity: Float, onOpacityChange: (Float) -> Unit,
    bold: Boolean, onBoldChange: (Boolean) -> Unit,
    italic: Boolean, onItalicChange: (Boolean) -> Unit,
    textShadow: Boolean, onTextShadowChange: (Boolean) -> Unit,
    letterSpacing: Float, onLetterSpacingChange: (Float) -> Unit,
    lineSpacing: Float, onLineSpacingChange: (Float) -> Unit,
    bgEnabled: Boolean, onBgEnabledChange: (Boolean) -> Unit,
    bgColor: Int, onOpenBgColorPicker: () -> Unit,
    bgOpacity: Float, onBgOpacityChange: (Float) -> Unit,
    selectedFont: WatermarkFont, onFontPickerOpen: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = enabled, onCheckedChange = onEnabledChange)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(CoreR.string.watermark_enable_text), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = header, onValueChange = onHeaderChange,
            label = { Text(stringResource(CoreR.string.watermark_header)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
        OutlinedTextField(
            value = description, onValueChange = onDescriptionChange,
            label = { Text(stringResource(CoreR.string.watermark_description)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
        WmLabel(stringResource(CoreR.string.watermark_alignment))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AlignmentChip(
                selected = textAlign == WatermarkTextAlign.Left,
                onClick = { onTextAlignChange(WatermarkTextAlign.Left) },
                icon = AppIcons.Rounded.FormatAlignLeft,
                contentDescription = stringResource(CoreR.string.watermark_align_left),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            AlignmentChip(
                selected = textAlign == WatermarkTextAlign.Center,
                onClick = { onTextAlignChange(WatermarkTextAlign.Center) },
                icon = AppIcons.Rounded.FormatAlignCenter,
                contentDescription = stringResource(CoreR.string.watermark_align_center),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            AlignmentChip(
                selected = textAlign == WatermarkTextAlign.Right,
                onClick = { onTextAlignChange(WatermarkTextAlign.Right) },
                icon = AppIcons.Rounded.FormatAlignRight,
                contentDescription = stringResource(CoreR.string.watermark_align_right),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
        WmLabel(stringResource(CoreR.string.watermark_position))
        PositionGrid(selected = position, onSelect = onPositionChange, enabled = enabled)
        WmLabel(stringResource(CoreR.string.watermark_color))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorPalette(selected = color, onSelect = onColorChange, enabled = enabled)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, onClick = onOpenColorPicker),
            )
        }
        WmLabel(stringResource(CoreR.string.watermark_font))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = selectedFont.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onFontPickerOpen) { Text(stringResource(CoreR.string.watermark_font_change)) }
        }
        WmLabel(stringResource(CoreR.string.watermark_size))
        TextSizeChips(selected = textSizeEnum, onSelect = onTextSizeChange, enabled = enabled)
        WmLabel(stringResource(CoreR.string.watermark_opacity, opacity * 100))
        Slider(value = opacity, onValueChange = onOpacityChange, valueRange = 0.1f..1.0f, modifier = Modifier.fillMaxWidth(), enabled = enabled)

        WmLabel("${stringResource(CoreR.string.watermark_bold)} / ${stringResource(CoreR.string.watermark_italic)}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = bold,
                onClick = { onBoldChange(!bold) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(AppIcons.Rounded.FormatBold, contentDescription = stringResource(CoreR.string.watermark_bold))
                        Text(stringResource(CoreR.string.watermark_bold))
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            FilterChip(
                selected = italic,
                onClick = { onItalicChange(!italic) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(AppIcons.Rounded.FormatItalic, contentDescription = stringResource(CoreR.string.watermark_italic))
                        Text(stringResource(CoreR.string.watermark_italic))
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = textShadow, onCheckedChange = onTextShadowChange, enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(CoreR.string.watermark_text_shadow), style = MaterialTheme.typography.bodyMedium)
        }

        WmLabel(stringResource(CoreR.string.watermark_letter_spacing))
        Slider(value = letterSpacing, onValueChange = onLetterSpacingChange, valueRange = -0.05f..0.20f, modifier = Modifier.fillMaxWidth(), enabled = enabled)

        WmLabel(stringResource(CoreR.string.watermark_line_spacing))
        Slider(value = lineSpacing, onValueChange = onLineSpacingChange, valueRange = 0.8f..2.0f, modifier = Modifier.fillMaxWidth(), enabled = enabled)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = bgEnabled, onCheckedChange = onBgEnabledChange, enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(CoreR.string.watermark_background_panel), style = MaterialTheme.typography.bodyMedium)
        }
        if (bgEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(bgColor))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenBgColorPicker),
                )
                Text(
                    text = "#%06X".format(bgColor and 0xFFFFFF),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WmLabel(stringResource(CoreR.string.watermark_opacity, bgOpacity * 100))
            Slider(value = bgOpacity, onValueChange = onBgOpacityChange, valueRange = 0.1f..1.0f, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AlignmentChip(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Icon(icon, contentDescription = contentDescription) },
        modifier = modifier,
        enabled = enabled,
    )
}

// ── EXIF override field ───────────────────────────────────────────────────────

/**
 * Text field for Logo / Model / Lens override.
 *
 * Empty string = "As per EXIF" — the watermark engine reads each photo's own
 * EXIF at burn time, so batch exports each file's actual make/model/lens.
 *
 * When blank: shows an "As per EXIF" chip (tappable — no action needed, just
 * visual confirmation) and a "Set custom" button to start typing a fixed override.
 * When non-blank: shows the typed override and a "As per EXIF" button to clear back.
 */
@Composable
private fun ExifOverrideField(
    value: String,
    onValueChange: (String) -> Unit,
    metaValue: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    // Lens field only: when the user types a custom lens (EXIF couldn't detect
    // one), show a lens-name autocomplete dropdown from LensDatabase at the 3rd char.
    lensAutocomplete: Boolean = false,
) {
    // Mode is tracked EXPLICITLY, not derived from value.isBlank(): otherwise
    // backspacing the text field to empty would flip it back to the chip view
    // mid-edit, destroying the field so the user can't keep typing (they'd have
    // to tap "Set custom" again). Custom mode is entered by tapping "Set custom"
    // (or when a non-blank override already exists) and is left ONLY via the
    // "As per EXIF" button — an empty custom field is allowed and just bakes as
    // the live EXIF value until the user types.
    var customMode by remember { mutableStateOf(value.isNotBlank()) }
    // If the parent pushes a non-blank override in (e.g. loaded from prefs),
    // reflect it as custom mode.
    androidx.compose.runtime.LaunchedEffect(value) { if (value.isNotBlank()) customMode = true }
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 40.dp, bottom = 4.dp),
    ) {
        if (!customMode) {
            // Dynamic mode — show chip + hint
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.SuggestionChip(
                    onClick = {},
                    label  = { Text(stringResource(CoreR.string.watermark_as_per_exif), style = MaterialTheme.typography.labelSmall) },
                )
                Text(
                    text  = metaValue.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    customMode = true
                    // Seed with the live EXIF value when there is one so the user
                    // edits from it; otherwise start empty (placeholder shows as a
                    // hint) rather than injecting the placeholder text as a value.
                    if (metaValue.isNotBlank()) onValueChange(metaValue)
                }) {
                    Text(stringResource(CoreR.string.watermark_set_custom), style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            // Fixed override mode — show text field + revert button
            val ctx = LocalContext.current
            var justPicked by remember { mutableStateOf(false) }
            var brand by remember { mutableStateOf<String?>(null) }
            var brandMenu by remember { mutableStateOf(false) }
            val brands by androidx.compose.runtime.produceState(
                initialValue = emptyList<String>(), lensAutocomplete,
            ) {
                this.value = if (!lensAutocomplete) emptyList()
                else withContext(kotlinx.coroutines.Dispatchers.IO) { LensDatabase.brands(ctx) }
            }
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (lensAutocomplete) {
                    Box {
                        TextButton(onClick = { brandMenu = true }) {
                            Text(
                                brand ?: "All",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        DropdownMenu(expanded = brandMenu, onDismissRequest = { brandMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("All") },
                                onClick = { brand = null; brandMenu = false },
                            )
                            brands.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name, maxLines = 1) },
                                    onClick = { brand = name; brandMenu = false },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value         = value,
                    onValueChange = { justPicked = false; onValueChange(it) },
                    modifier      = Modifier.weight(1f),
                    singleLine    = true,
                    textStyle     = MaterialTheme.typography.bodySmall,
                    placeholder   = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
                    trailingIcon  = {
                        TextButton(onClick = { customMode = false; onValueChange("") }) {
                            Text(stringResource(CoreR.string.watermark_as_per_exif), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
            }
            // Lens autocomplete — appears once 3+ chars are typed; tap to fill.
            // Searched OFF the main thread: the corpus now merges the Lensfun
            // database (see LensDatabase.all), whose first load touches disk.
            if (lensAutocomplete && !justPicked) {
                val query = value
                val brandFilter = brand
                val matches by androidx.compose.runtime.produceState(
                    initialValue = emptyList<String>(), query, brandFilter,
                ) {
                    this.value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        LensDatabase.search(ctx, query, brandFilter)
                    }
                }
                if (matches.isNotEmpty()) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        matches.forEach { suggestion ->
                            Text(
                                text  = suggestion,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { justPicked = true; onValueChange(suggestion) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── EXIF controls ─────────────────────────────────────────────────────────────

@Composable
private fun ExifWatermarkControls(
    enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    hasMetadata: Boolean,
    make: String, onMakeChange: (String) -> Unit,
    model: String, onModelChange: (String) -> Unit,
    lens: String, onLensChange: (String) -> Unit,
    metaMake: String, metaModel: String, metaLens: String,
    metaIso: Int, metaShutter: Float, metaAperture: Float, metaFocalMm: Float, metaDate: String,
    textSize: WatermarkTextSize, onTextSizeChange: (WatermarkTextSize) -> Unit,
    textShadow: Boolean, onTextShadowChange: (Boolean) -> Unit,
    textColor: Int, onOpenColorPicker: () -> Unit,
    showLogo: Boolean, onShowLogoChange: (Boolean) -> Unit,
    showModel: Boolean, onShowModelChange: (Boolean) -> Unit,
    showLens: Boolean, onShowLensChange: (Boolean) -> Unit,
    showExposure: Boolean, onShowExposureChange: (Boolean) -> Unit,
    showDate: Boolean, onShowDateChange: (Boolean) -> Unit,
    selectedFont: WatermarkFont, onFontPickerOpen: () -> Unit,
    theme: ExifTheme, onThemeChange: (ExifTheme) -> Unit,
    textAlign: WatermarkTextAlign, onTextAlignChange: (WatermarkTextAlign) -> Unit,
    bgEnabled: Boolean, onBgEnabledChange: (Boolean) -> Unit,
    bgColor: Int, onOpenBgColorPicker: () -> Unit,
    bgOpacity: Float, onBgOpacityChange: (Float) -> Unit,
    italic: Boolean, onItalicChange: (Boolean) -> Unit,
    letterSpacing: Float, onLetterSpacingChange: (Float) -> Unit,
    lineSpacing: Float, onLineSpacingChange: (Float) -> Unit,
) {
    val shutStr = formatShutter(metaShutter)
    val isoStr  = if (metaIso > 0) "ISO$metaIso" else "—"
    val focalStr = if (metaFocalMm > 0f) "${"%.0f".format(metaFocalMm)}mm" else "—"
    val apertureStr = if (metaAperture > 0f) "F${"%.1f".format(metaAperture)}" else "—"
    val shutterStr = shutStr.ifEmpty { "—" }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Enable toggle
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = enabled, onCheckedChange = onEnabledChange, enabled = hasMetadata)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(CoreR.string.watermark_enable_exif), style = MaterialTheme.typography.bodyMedium,
                    color = if (hasMetadata) androidx.compose.ui.graphics.Color.Unspecified
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                if (!hasMetadata) Text(stringResource(CoreR.string.watermark_no_metadata), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider()

        // ── Field table: checkbox | label | value ───────────────────────────
        WmLabel(stringResource(CoreR.string.watermark_fields))

        @Composable
        fun FieldRow(
            checked: Boolean, onCheckedChange: (Boolean) -> Unit,
            label: String, value: String,
            editContent: (@Composable () -> Unit)? = null,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(72.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        color = if (checked) androidx.compose.ui.graphics.Color.Unspecified
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                if (checked && editContent != null) {
                    editContent()
                }
            }
        }

        // Logo row with brand override text field
        FieldRow(checked = showLogo, onCheckedChange = onShowLogoChange,
            label = stringResource(CoreR.string.watermark_logo), value = make.ifBlank { metaMake }) {
            ExifOverrideField(
                value       = make,
                onValueChange = onMakeChange,
                metaValue   = metaMake,
                placeholder = metaMake.ifBlank { "e.g. Canon" },
            )
        }

        // Model row with inline text field
        FieldRow(checked = showModel, onCheckedChange = onShowModelChange,
            label = stringResource(CoreR.string.watermark_model), value = model.ifBlank { metaModel }) {
            ExifOverrideField(
                value       = model,
                onValueChange = onModelChange,
                metaValue   = metaModel,
                placeholder = metaModel.ifBlank { "e.g. EOS 6D" },
            )
        }

        // Lens row with inline text field
        FieldRow(checked = showLens, onCheckedChange = onShowLensChange,
            label = stringResource(CoreR.string.watermark_lens), value = lens.ifBlank { metaLens }) {
            ExifOverrideField(
                value       = lens,
                onValueChange = onLensChange,
                metaValue   = metaLens,
                placeholder = metaLens.ifBlank { "e.g. Super-Takumar 50mm f/1.4" },
                lensAutocomplete = true,
            )
        }

        // Exposure row (read-only sub-fields)
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showExposure, onCheckedChange = onShowExposureChange, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(CoreR.string.watermark_exposure), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(72.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(CoreR.string.watermark_exposure_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = if (showExposure) androidx.compose.ui.graphics.Color.Unspecified
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
            if (showExposure) {
                Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(CoreR.string.raw_export_iso),     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(isoStr,    style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(CoreR.string.raw_export_focal_length),   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(focalStr,  style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(CoreR.string.raw_export_aperture),    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(apertureStr,   style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(CoreR.string.raw_export_shutter_speed),  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(shutterStr, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Date row
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showDate, onCheckedChange = onShowDateChange, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(CoreR.string.watermark_date), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(72.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metaDate.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = if (showDate) androidx.compose.ui.graphics.Color.Unspecified
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }

        HorizontalDivider()

        WmLabel(stringResource(CoreR.string.watermark_text_color))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(textColor))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenColorPicker),
            )
            Text(
                text = "#%06X".format(textColor and 0xFFFFFF),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = textShadow, onCheckedChange = onTextShadowChange)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(CoreR.string.watermark_text_shadow), style = MaterialTheme.typography.bodyMedium)
        }

        // Font
        WmLabel(stringResource(CoreR.string.watermark_font))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(selectedFont.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onFontPickerOpen) { Text(stringResource(CoreR.string.watermark_font_change)) }
        }

        // Size — controls all EXIF elements: logo height, brand/model/lens text, exposure text
        WmLabel(stringResource(CoreR.string.watermark_size))
        TextSizeChips(selected = textSize, onSelect = onTextSizeChange, enabled = true)

        // Typography: italic, letter spacing, line spacing
        WmLabel(stringResource(CoreR.string.watermark_italic))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = italic,
                onClick = { onItalicChange(!italic) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(AppIcons.Rounded.FormatItalic, contentDescription = stringResource(CoreR.string.watermark_italic))
                        Text(stringResource(CoreR.string.watermark_italic))
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        WmLabel(stringResource(CoreR.string.watermark_letter_spacing))
        Slider(value = letterSpacing, onValueChange = onLetterSpacingChange, valueRange = -0.05f..0.20f, modifier = Modifier.fillMaxWidth())

        WmLabel(stringResource(CoreR.string.watermark_line_spacing))
        Slider(value = lineSpacing, onValueChange = onLineSpacingChange, valueRange = 0.8f..2.0f, modifier = Modifier.fillMaxWidth())

        HorizontalDivider()
        WmLabel(stringResource(CoreR.string.watermark_alignment))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AlignmentChip(
                selected = textAlign == WatermarkTextAlign.Left,
                onClick = { onTextAlignChange(WatermarkTextAlign.Left) },
                icon = AppIcons.Rounded.FormatAlignLeft,
                contentDescription = stringResource(CoreR.string.watermark_align_left),
                modifier = Modifier.weight(1f),
            )
            AlignmentChip(
                selected = textAlign == WatermarkTextAlign.Center,
                onClick = { onTextAlignChange(WatermarkTextAlign.Center) },
                icon = AppIcons.Rounded.FormatAlignCenter,
                contentDescription = stringResource(CoreR.string.watermark_align_center),
                modifier = Modifier.weight(1f),
            )
            AlignmentChip(
                selected = textAlign == WatermarkTextAlign.Right,
                onClick = { onTextAlignChange(WatermarkTextAlign.Right) },
                icon = AppIcons.Rounded.FormatAlignRight,
                contentDescription = stringResource(CoreR.string.watermark_align_right),
                modifier = Modifier.weight(1f),
            )
        }

        // Background border — only for Overlay/ShotOn where text overlays the image
        val showBgOption = theme == ExifTheme.Overlay || theme == ExifTheme.ShotOn
        if (showBgOption) {
            HorizontalDivider()
            WmLabel(stringResource(CoreR.string.watermark_background_panel))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bgEnabled, onCheckedChange = onBgEnabledChange)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(CoreR.string.watermark_background_panel), style = MaterialTheme.typography.bodyMedium)
            }
            if (bgEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(bgColor))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenBgColorPicker),
                    )
                    Text("#%06X".format(bgColor and 0xFFFFFF),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WmLabel(stringResource(CoreR.string.watermark_opacity, bgOpacity * 100))
                Slider(value = bgOpacity, onValueChange = onBgOpacityChange,
                    valueRange = 0.1f..1.0f, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ── Image controls ────────────────────────────────────────────────────────────

@Composable
private fun ImageWatermarkControls(
    enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    logoBitmap: Bitmap?,
    logoFileName: String,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    position: WatermarkPosition, onPositionChange: (WatermarkPosition) -> Unit,
    size: Float, onSizeChange: (Float) -> Unit,
    opacity: Float, onOpacityChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = enabled, onCheckedChange = onEnabledChange)
            Spacer(Modifier.width(8.dp))
            Text("Enable Image Watermark", style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (logoBitmap != null) {
                Box {
                    Image(
                        bitmap = logoBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier.size(20.dp).align(Alignment.TopEnd),
                    ) {
                        Icon(AppIcons.Rounded.Close, contentDescription = "Remove image", modifier = Modifier.size(16.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = logoFileName.take(30),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onPickImage) { Text(stringResource(CoreR.string.watermark_change)) }
                }
            } else {
                Box(
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable(onClick = onPickImage),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Rounded.AddPhotoAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onPickImage) { Text(stringResource(CoreR.string.watermark_pick_png)) }
            }
        }
        WmLabel(stringResource(CoreR.string.watermark_position))
        PositionGrid(selected = position, onSelect = onPositionChange, enabled = enabled)
        WmLabel(stringResource(CoreR.string.watermark_size_value, size * 100))
        Slider(value = size, onValueChange = onSizeChange, valueRange = 0.05f..0.50f, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        WmLabel(stringResource(CoreR.string.watermark_opacity, opacity * 100))
        Slider(value = opacity, onValueChange = onOpacityChange, valueRange = 0.1f..1.0f, modifier = Modifier.fillMaxWidth(), enabled = enabled)
    }
}

// ── Shared sub-components ─────────────────────────────────────────────────────

@Composable
private fun TextSizeChips(
    selected: WatermarkTextSize,
    onSelect: (WatermarkTextSize) -> Unit,
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WatermarkTextSize.entries.forEach { sz ->
            val isSel = sz == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSel) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(if (enabled) Modifier.clickable { onSelect(sz) } else Modifier)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = sz.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else if (isSel) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WmLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PositionGrid(selected: WatermarkPosition, onSelect: (WatermarkPosition) -> Unit, enabled: Boolean = true) {
    // 3×3 grid: null cells are inert spacers (center-left and center-right)
    val grid: List<List<WatermarkPosition?>> = listOf(
        listOf(WatermarkPosition.TOP_LEFT,    WatermarkPosition.TOP_CENTER,    WatermarkPosition.TOP_RIGHT),
        listOf(null,                           WatermarkPosition.CENTER,        null),
        listOf(WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.BOTTOM_CENTER, WatermarkPosition.BOTTOM_RIGHT),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        grid.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { pos ->
                    if (pos == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val isSel = pos == selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSel) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .then(if (enabled) Modifier.clickable { onSelect(pos) } else Modifier)
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = pos.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        else if (isSel) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorPalette(selected: Color, onSelect: (Color) -> Unit, enabled: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESET_COLORS.forEach { c ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .then(if (enabled) Modifier.clickable { onSelect(c) } else Modifier),
            )
        }
    }
}

// ── Footer gradient controls ──────────────────────────────────────────────────

@Composable
private fun FooterGradientControls(
    enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    color: Int, onColorChange: (Int) -> Unit,
    onOpenColorPicker: () -> Unit,
    intensity: Float, onIntensityChange: (Float) -> Unit,
    length: Float, onLengthChange: (Float) -> Unit,
    style: FooterGradientStyle, onStyleChange: (FooterGradientStyle) -> Unit,
    direction: FooterGradientDirection, onDirectionChange: (FooterGradientDirection) -> Unit,
    sideLength: Float, onSideLengthChange: (Float) -> Unit,
    feather: Float, onFeatherChange: (Float) -> Unit,
    featherRight: Boolean, onFeatherRightChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Enable toggle
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = enabled, onCheckedChange = onEnabledChange)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(CoreR.string.watermark_enable_footer), style = MaterialTheme.typography.bodyMedium)
        }

        // Direction radio buttons
        WmLabel(stringResource(CoreR.string.watermark_direction))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(if (enabled) Modifier.clickable { onDirectionChange(FooterGradientDirection.DownToUp) } else Modifier)) {
                RadioButton(
                    selected = direction == FooterGradientDirection.DownToUp,
                    onClick = { onDirectionChange(FooterGradientDirection.DownToUp) },
                    enabled = enabled,
                )
                Text(stringResource(CoreR.string.watermark_direction_down_to_up), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(if (enabled) Modifier.clickable { onDirectionChange(FooterGradientDirection.FeatherLeft) } else Modifier)) {
                RadioButton(
                    selected = direction == FooterGradientDirection.FeatherLeft,
                    onClick = { onDirectionChange(FooterGradientDirection.FeatherLeft) },
                    enabled = enabled,
                )
                Text(stringResource(CoreR.string.watermark_direction_feather_left), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(if (enabled) Modifier.clickable { onDirectionChange(FooterGradientDirection.FeatherRight) } else Modifier)) {
                RadioButton(
                    selected = direction == FooterGradientDirection.FeatherRight,
                    onClick = { onDirectionChange(FooterGradientDirection.FeatherRight) },
                    enabled = enabled,
                )
                Text(stringResource(CoreR.string.watermark_direction_feather_right), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Color swatch — tap opens color picker (hoisted to parent to avoid nested sheet issues)
        WmLabel(stringResource(CoreR.string.watermark_color))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenColorPicker),
            )
            Text(
                text = "#%06X".format(color and 0xFFFFFF),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Style selector (Plain / Dots)
        WmLabel(stringResource(CoreR.string.watermark_style))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FooterGradientStyle.entries.forEach { s ->
                val isSel = s == style
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (enabled) Modifier.clickable { onStyleChange(s) } else Modifier)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                else if (isSel) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Length (shared by both directions — height fraction for Down→Up, band thickness for Side→Side)
        WmLabel(stringResource(CoreR.string.watermark_length, length * 100))
        Slider(value = length, onValueChange = onLengthChange, valueRange = 0.05f..0.60f,
            modifier = Modifier.fillMaxWidth(), enabled = enabled)

        WmLabel(stringResource(CoreR.string.watermark_intensity, intensity * 100))
        Slider(value = intensity, onValueChange = onIntensityChange, valueRange = 0.1f..1.0f,
            modifier = Modifier.fillMaxWidth(), enabled = enabled)

        // Feather-specific controls
        if (direction == FooterGradientDirection.FeatherLeft || direction == FooterGradientDirection.FeatherRight) {
            WmLabel(stringResource(CoreR.string.watermark_side_length, sideLength * 100))
            Slider(value = sideLength, onValueChange = onSideLengthChange, valueRange = 0.10f..1.0f,
                modifier = Modifier.fillMaxWidth(), enabled = enabled)

            WmLabel(stringResource(CoreR.string.watermark_feather_length, feather * 100))
            Slider(value = feather, onValueChange = onFeatherChange, valueRange = 0f..1.0f,
                modifier = Modifier.fillMaxWidth(), enabled = enabled)
        }
    }
}

// ── Font picker sheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatermarkFontPickerSheet(
    selected: WatermarkFont,
    onSelect: (WatermarkFont) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var systemFonts by remember { mutableStateOf<List<WatermarkFont.SystemFile>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            systemFonts = WatermarkFont.loadSystemFonts()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(CoreR.string.watermark_font),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.heightIn(max = 520.dp),
        ) {
            item { FontSectionHeader(stringResource(CoreR.string.watermark_bundled_fonts)) }
            items(WatermarkFont.bundled, key = { it.displayName }) { font ->
                FontPickerRow(font = font, selected = selected, onSelect = onSelect)
            }
            if (systemFonts.isNotEmpty()) {
                item { FontSectionHeader(stringResource(CoreR.string.watermark_system_fonts)) }
                items(systemFonts, key = { it.path }) { font ->
                    FontPickerRow(font = font, selected = selected, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun FontSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun FontPickerRow(
    font: WatermarkFont,
    selected: WatermarkFont,
    onSelect: (WatermarkFont) -> Unit,
) {
    val isSel = font == selected
    val context = LocalContext.current
    val fontFamily = remember(font) { font.toComposeFontFamily(context) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(font) }
            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = font.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(CoreR.string.watermark_font_sample),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = fontFamily,
                color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.74f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isSel) {
            Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
