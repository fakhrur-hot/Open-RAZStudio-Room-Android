/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Per-tab "Reset" button. Each tab owns a contiguous group of UserMacro
 * fields; this widget zeroes ONLY those fields, leaving the rest of the
 * macro intact. Cheap UX: one tap restores the tab's defaults without
 * clicking each slider individually or wiping the whole edit.
 *
 * The reset map below is intentionally explicit rather than reflection-
 * based — fields move tabs over time, and a typo on a field name is a
 * compile error here, not a silent miss.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.HslExtended
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.JpegRefineMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

enum class RawTabId { Light, Tonemap, Color, Detail, Effects, Curves, LutAdj, Vignette, Gradient, Mask }

/**
 * Zero out every UserMacro field owned by [tab]; pass through the rest.
 * "Owned" means the slider lives in that tab's panel — if a field is
 * shared across tabs (none currently) it must stay non-zero unless ALL
 * its tabs are reset.
 */
fun UserMacro.resetTab(tab: RawTabId): UserMacro = when (tab) {
    // Field ownership reflects which tab actually exposes the slider.
    // Note: `whites` appears in BOTH Light and Tonemap panels (legacy UI),
    // so it's owned by both — resetting either tab zeroes it. Same for
    // `blacks` (visible in Tonemap; Light has whites only).
    RawTabId.Light -> copy(
        exposure   = 0f,
        highlights = 0f,
        shadows    = 0f,
        whites     = 0f,
    )
    // The Tone tab panel stacks RawLightTab + RawTonemapTab and shows ONE reset
    // pill at the very bottom ("Reset Tone"), so this branch owns both panels'
    // fields (Light's exposure/highlights/shadows/whites + Smart Bright too).
    RawTabId.Tonemap -> copy(
        exposure          = 0f,
        highlights        = 0f,
        shadows           = 0f,
        smartBright       = 0f,
        tonemapExposure   = 0f,
        contrast          = 0f,
        tonemapHighlights = 0f,
        tonemapShadows    = 0f,
        ambiance          = 0f,
        ortonStrength     = 0f,
        whites            = 0f,
        blacks            = 0f,
        dehaze            = 0f,
        // Per-segment values written by Auto Expo are also tonal — reset
        // here so a "reset Tonemap" actually returns the rendered look to
        // baseline.
        highlightsSubject    = 0f,
        highlightsBackground = 0f,
        ambianceSubject      = 0f,
        ambianceBackground   = 0f,
        whitesSubject        = 0f,
        whitesBackground     = 0f,
        blacksSubject        = 0f,
        blacksBackground     = 0f,
        shadowsSubject       = 0f,
        shadowsBackground    = 0f,
        // Highlights Recovery + Fill Light (Film Response) live on Tone now.
        filmResponse         = filmResponse.copy(recovery = 0f, fillLight = 0f),
    )
    RawTabId.Color -> copy(
        saturation   = 0f,
        vibrance     = 0f,
        colorDensity = 0f,
        smartColorEnhance = 0f,   // Color Pop level chips
        whiteBalance = 0,
        tint         = 0f,
        // 4-way colour grading wheels (Global/Shadows/Midtones/Highlights).
        cgGlobal     = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorWheel(),
        cgShadows    = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorWheel(),
        cgMidtones   = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorWheel(),
        cgHighlights = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorWheel(),
        // 12-anchor HSL (6 original + 6 expansion).
        hslRedHue = 0f,    hslRedSat = 0f,    hslRedLum = 0f,
        hslOrangeHue = 0f, hslOrangeSat = 0f, hslOrangeLum = 0f,
        hslYellowHue = 0f, hslYellowSat = 0f, hslYellowLum = 0f,
        hslGreenHue = 0f,  hslGreenSat = 0f,  hslGreenLum = 0f,
        hslAquaHue = 0f,   hslAquaSat = 0f,   hslAquaLum = 0f,
        hslBlueHue = 0f,   hslBlueSat = 0f,   hslBlueLum = 0f,
        hslExt = HslExtended(),
    )
    RawTabId.Detail -> copy(
        ambiance         = 0f,   // moved here from LUT Adjustments
        sharpness        = 0f,
        smartSharpness   = 0f,
        texture          = 0f,
        clarity          = 0f,
        clarityLift      = 0f,
        luminanceNR      = 0f,
        colorNR          = 0f,
        blueNR           = 0f,
        redNR            = 0f,
        smoothBackground = 0f,
        jpegRefine = JpegRefineMacro(touched = true),
    )
    RawTabId.Effects -> copy(
        filmRolloff      = 0f,   // moved here from the Tone tab
        filmGrain        = 0f,   // Film Grain — moved here from the Detail tab
        filmGrainSize    = 0.5f,
        filmGrainWashOut = 0f,
        ortonStrength    = 0f,   // Bloom — moved here from LUT Adjustments
        bloomExcludeSubject = false,
        subjectBloom     = 0f,
        bloomRadius      = 0f,
        mistTightness    = 55f,
        mistHalation     = 0f,
        cinematicMistTier = 0,
        bokehBlur        = 0,    // Bokeh — moved here from the Local/Mask tab
        fxBlurStyle      = 0,
        fxGaussBlur      = 0f,
        fxDirBlurAmt     = 0f,
        fxDirBlurAngle   = 0f,
        fxRadBlurAmt     = 0f,
        fxRadBlurCx      = 0.5f,
        fxRadBlurCy      = 0.5f,
        fxZoomBlurAmt    = 0f,
        fxZoomBlurCx     = 0.5f,
        fxZoomBlurCy     = 0.5f,
        fxMist           = 0f,   // Mist UI hidden; keep field clear on reset
        fxMistWarmth     = 0f,
        fxDust           = 0f,
        fxDustSize       = 0f,
        fxVintageStrength = 0f,
        fxVintageFade    = 0f,
        fxVintageVig     = 0f,
        fxVintageMistIntensity = 0f,
        fxVintageMistScale     = 1f,
        fxVintageTextureIntensity = 0f,
        fxVintageTextureScale  = 1f,
        fxGlowStrength   = 0f,   // fused into Bloom density chips
        fxGlowSpread     = 0f,
        fxGlowWarmth     = 0f,
        pushPull         = 0f,
        lensFlare        = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LensFlare(),
        colorShift       = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorShift(),
    )
    RawTabId.Curves -> copy(
        toneCurvePoints   = UserMacro().toneCurvePoints,   // DEFAULT_CURVE_POINTS (all 4 channels)
        toneCurveLumaMode = false,
        filmCurve         = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmCurve(),
    )
    // LUT Adjustments tab (zone WB, skintone, dehaze, LUT trims).
    // CLAHE / Film Response Recovery+Fill / B&W UI removed — don't wipe
    // filmResponse here (Tone owns Recovery/Fill; monochrome stays for sidecars).
    RawTabId.LutAdj -> copy(
        claheShadowsBoost    = 0f,
        claheHighlightsBoost = 0f,
        dehaze               = 0f,
        highlightTemperature = 0f,
        highlightTint        = 0f,
        shadowTemperature    = 0f,
        shadowTint           = 0f,
        skintoneWarm         = 0f,
        skintoneSmooth       = 0f,
        skintoneLuma         = 0f,
        lutHighlightVibrancy = 0f,
        lutColorDensity      = 0f,
        lutSkintoneBalance   = 0f,
    )
    // Non-zero defaults (feather 0.5, intensity 1, enum targets) are copied
    // from a fresh UserMacro() so the reset lands exactly on the constructor
    // defaults and the card splitter emits no card.
    RawTabId.Vignette -> UserMacro().let { d ->
        copy(
            vignetteAmount            = d.vignetteAmount,
            vignetteFeather           = d.vignetteFeather,
            vignetteIntensity         = d.vignetteIntensity,
            vignetteEffect            = d.vignetteEffect,
            vignetteCenterX           = d.vignetteCenterX,
            vignetteCenterY           = d.vignetteCenterY,
            vignetteCenterAutoSnapped = d.vignetteCenterAutoSnapped,
            vignetteSegmentation      = d.vignetteSegmentation,
        )
    }
    // Every gradient field (4 sides × 2 layers + angle + per-side ApplyTo).
    RawTabId.Gradient -> UserMacro().let { d ->
        copy(
            gradientAngle = d.gradientAngle,
            gradientTopIntensity = d.gradientTopIntensity,
            gradientTopLength = d.gradientTopLength,
            gradientTopFeather = d.gradientTopFeather,
            gradientTopTintColor = d.gradientTopTintColor,
            gradientTopTintLuminosity = d.gradientTopTintLuminosity,
            gradientTopBlendMode = d.gradientTopBlendMode,
            gradientTopEnable2 = d.gradientTopEnable2,
            gradientTopIntensity2 = d.gradientTopIntensity2,
            gradientTopLength2 = d.gradientTopLength2,
            gradientTopFeather2 = d.gradientTopFeather2,
            gradientTopTintColor2 = d.gradientTopTintColor2,
            gradientTopTintLuminosity2 = d.gradientTopTintLuminosity2,
            gradientBottomIntensity = d.gradientBottomIntensity,
            gradientBottomLength = d.gradientBottomLength,
            gradientBottomFeather = d.gradientBottomFeather,
            gradientBottomTintColor = d.gradientBottomTintColor,
            gradientBottomTintLuminosity = d.gradientBottomTintLuminosity,
            gradientBottomBlendMode = d.gradientBottomBlendMode,
            gradientBottomEnable2 = d.gradientBottomEnable2,
            gradientBottomIntensity2 = d.gradientBottomIntensity2,
            gradientBottomLength2 = d.gradientBottomLength2,
            gradientBottomFeather2 = d.gradientBottomFeather2,
            gradientBottomTintColor2 = d.gradientBottomTintColor2,
            gradientBottomTintLuminosity2 = d.gradientBottomTintLuminosity2,
            gradientLeftIntensity = d.gradientLeftIntensity,
            gradientLeftLength = d.gradientLeftLength,
            gradientLeftFeather = d.gradientLeftFeather,
            gradientLeftTintColor = d.gradientLeftTintColor,
            gradientLeftTintLuminosity = d.gradientLeftTintLuminosity,
            gradientLeftBlendMode = d.gradientLeftBlendMode,
            gradientLeftEnable2 = d.gradientLeftEnable2,
            gradientLeftIntensity2 = d.gradientLeftIntensity2,
            gradientLeftLength2 = d.gradientLeftLength2,
            gradientLeftFeather2 = d.gradientLeftFeather2,
            gradientLeftTintColor2 = d.gradientLeftTintColor2,
            gradientLeftTintLuminosity2 = d.gradientLeftTintLuminosity2,
            gradientRightIntensity = d.gradientRightIntensity,
            gradientRightLength = d.gradientRightLength,
            gradientRightFeather = d.gradientRightFeather,
            gradientRightTintColor = d.gradientRightTintColor,
            gradientRightTintLuminosity = d.gradientRightTintLuminosity,
            gradientRightBlendMode = d.gradientRightBlendMode,
            gradientRightEnable2 = d.gradientRightEnable2,
            gradientRightIntensity2 = d.gradientRightIntensity2,
            gradientRightLength2 = d.gradientRightLength2,
            gradientRightFeather2 = d.gradientRightFeather2,
            gradientRightTintColor2 = d.gradientRightTintColor2,
            gradientRightTintLuminosity2 = d.gradientRightTintLuminosity2,
            gradientTopApplyTo = d.gradientTopApplyTo,
            gradientBottomApplyTo = d.gradientBottomApplyTo,
            gradientLeftApplyTo = d.gradientLeftApplyTo,
            gradientRightApplyTo = d.gradientRightApplyTo,
        )
    }
    // Mask tab: the per-mask ADJUSTMENTS only. The luminance-range fields
    // (maskLum*) define the selection itself and are deliberately kept.
    RawTabId.Mask -> copy(
        maskBrightness  = 0f,
        maskContrast    = 0f,
        maskTemperature = 0,
        maskTint        = 0f,
        maskSaturation  = 0f,
        maskClarity     = 0f,
        maskSharpness   = 0f,
        maskTone        = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskToneRegions(),
    )
}

/**
 * Small "Reset" pill that calls [onReset] with the tab-cleared macro.
 * Placed at the bottom of each tab panel.
 */
@Composable
internal fun TabResetButton(
    tab: RawTabId,
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
    /** Display name override (e.g. "Tone" for the combined Light+Tonemap panel). */
    label: String? = null,
) {
    ResetPill(
        text = "Reset ${label ?: tab.name.lowercase().replaceFirstChar { it.uppercase() }}",
        onClick = { onMacroChange(macro.resetTab(tab)) },
        modifier = modifier,
    )
}

/** The bare right-aligned reset pill — for tabs whose reset isn't a plain
 *  `resetTab` (e.g. clearing a LUT slot goes through the slot shim). */
@Composable
internal fun ResetPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}
