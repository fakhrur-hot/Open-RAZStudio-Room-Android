/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.CenterFocusStrong
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import kotlin.math.roundToInt

private fun bloomUiToMacro(uiValue: Float): Float = (uiValue / 5f).coerceIn(0f, 1f)
private fun bloomMacroToUi(macroValue: Float): Float = (macroValue * 5f).coerceIn(0f, 5f)
private const val SUBJECT_PROTECT_GLOW = 0.10f
private const val SHOW_FILM_GRAIN_WASH_OUT = false

@Composable
internal fun RawEffectsTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
    onSegmentationNeeded: () -> Unit = {},
    subjectSegBusy: Boolean = false,
    subjectDetected: Boolean = true,
    imageLongSide: Int = CinematicBloomProcessor.REF_LONG_SIDE,
    isLensFlareMoveMode: Boolean = false,
    onLensFlareMoveModeChange: (Boolean) -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.VintageFxAssets.ensureLoaded(ctx)
    }
    val hasMistPng = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.VintageFxAssets.hasMist
    Column(modifier = modifier) {
        val bloomOn = CinematicBloomProcessor.activeTier(macro) != CinematicBloomProcessor.Tier.NONE

        Spacer(Modifier.height(8.dp))
        SectionHeader("Bloom")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        ) {
            val active = CinematicBloomProcessor.activeTier(macro)
            CinematicBloomProcessor.Tier.entries.forEach { tier ->
                FilterChip(
                    selected = active == tier,
                    onClick = {
                        onMacroChange(
                            CinematicBloomProcessor.apply(
                                macro,
                                tier,
                                strengthUi = if (tier == CinematicBloomProcessor.Tier.NONE) null
                                else bloomMacroToUi(macro.ortonStrength).takeIf { it > 0f },
                                imageLongSide = imageLongSide,
                            )
                        )
                    },
                    label = { Text(tier.label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        if (bloomOn) {
            RawSliderRow(
                label = stringResource(R.string.raw_bloom_strength),
                value = bloomMacroToUi(macro.ortonStrength),
                valueRange = 0f..5f,
                step = 1f,
                onValueChange = {
                    val s = bloomUiToMacro(it)
                    onMacroChange(
                        macro.copy(
                            ortonStrength = s,
                            subjectBloom = if (macro.bloomExcludeSubject) {
                                s.coerceAtMost(1f) * SUBJECT_PROTECT_GLOW
                            } else {
                                macro.subjectBloom
                            },
                        )
                    )
                },
                displayValue = "${bloomMacroToUi(macro.ortonStrength).toInt()}",
            )
            RawSliderRow(
                label = stringResource(R.string.raw_bloom_tightness),
                value = macro.mistTightness,
                valueRange = 0f..100f,
                step = 1f,
                onValueChange = { onMacroChange(macro.copy(mistTightness = it)) },
                displayValue = "${macro.mistTightness.toInt()}",
            )
            RawSliderRow(
                label = stringResource(R.string.raw_bloom_halation),
                value = macro.mistHalation,
                valueRange = 0f..100f,
                step = 1f,
                onValueChange = { onMacroChange(macro.copy(mistHalation = it)) },
                displayValue = "${macro.mistHalation.toInt()}",
            )
            RawSliderRow(
                label = stringResource(R.string.raw_bloom_warmth),
                value = macro.fxGlowWarmth,
                valueRange = -50f..50f,
                step = 1f,
                onValueChange = { onMacroChange(macro.copy(fxGlowWarmth = it)) },
                displayValue = "${macro.fxGlowWarmth.toInt()}",
            )
        }
        val protectSubject = macro.bloomExcludeSubject
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = protectSubject,
                enabled = !subjectSegBusy,
                onCheckedChange = { on ->
                    if (on) onSegmentationNeeded()
                    onMacroChange(
                        macro.copy(
                            bloomExcludeSubject = on,
                            subjectBloom = if (on) macro.ortonStrength.coerceAtMost(1f) * SUBJECT_PROTECT_GLOW else 0f,
                        )
                    )
                },
            )
            Text(
                text = "Protect subject",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (subjectSegBusy && protectSubject) {
            Text(
                    text = "Detecting subject…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else if (protectSubject && !subjectDetected) {
            Text(
                text = "No subject found.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Bokeh")
        RawSliderRow(
            label = "Background Blur",
            value = macro.bokehBlur.toFloat().coerceAtMost(50f),
            valueRange = 0f..50f,
            enabled = !subjectSegBusy,
            onValueChange = {
                val v = it.roundToInt().coerceIn(0, 50)
                if (v > 0) onSegmentationNeeded()
                onMacroChange(macro.copy(bokehBlur = v))
            },
            displayValue = "${macro.bokehBlur}",
        )
        if (subjectSegBusy) {
            Text(
                    text = "Detecting subject…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else if (!subjectDetected && macro.bokehBlur > 0) {
            Text(
                text = "No subject found.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Vintage")
        RawSliderRow(
            label = "Amount",
            value = macro.fxVintageStrength,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(fxVintageStrength = it)) },
            displayValue = "${macro.fxVintageStrength.toInt()}"
        )
        RawSliderRow(
            label = "Fade",
            value = macro.fxVintageFade,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(fxVintageFade = it)) },
            displayValue = "${macro.fxVintageFade.toInt()}"
        )
        if (hasMistPng) {
            SectionHeader("Mist")
            RawSliderRow(
                label = "Intensity",
                value = macro.fxVintageMistIntensity,
                valueRange = 0f..100f,
                step = 1f,
                onValueChange = { onMacroChange(macro.copy(fxVintageMistIntensity = it)) },
                displayValue = "${macro.fxVintageMistIntensity.toInt()}"
            )
            RawSliderRow(
                label = "Scale",
                value = macro.fxVintageMistScale,
                valueRange = 1f..100f,
                step = 1f,
                onValueChange = { onMacroChange(macro.copy(fxVintageMistScale = it)) },
                displayValue = "${macro.fxVintageMistScale.toInt()}"
            )
            RawSliderRow(
                label = "Warmth",
                value = macro.fxMistWarmth,
                valueRange = -50f..50f,
                step = 1f,
                onValueChange = { onMacroChange(macro.copy(fxMistWarmth = it)) },
                displayValue = "${macro.fxMistWarmth.toInt()}"
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Color Shift")
        Text(
            text = stringResource(R.string.raw_color_shift_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        RawSliderRow(
            label = stringResource(R.string.raw_color_shift_red),
            value = macro.colorShift.redX,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(colorShift = macro.colorShift.copy(redX = it))) },
            displayValue = "${macro.colorShift.redX.toInt()}",
        )
        RawSliderRow(
            label = stringResource(R.string.raw_color_shift_green),
            value = macro.colorShift.greenX,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(colorShift = macro.colorShift.copy(greenX = it))) },
            displayValue = "${macro.colorShift.greenX.toInt()}",
        )
        RawSliderRow(
            label = stringResource(R.string.raw_color_shift_blue),
            value = macro.colorShift.blueX,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(colorShift = macro.colorShift.copy(blueX = it))) },
            displayValue = "${macro.colorShift.blueX.toInt()}",
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Lens Flare")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isLensFlareMoveMode) {
                FilledIconButton(
                    onClick = { onLensFlareMoveModeChange(false) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                OutlinedIconButton(
                    onClick = { onLensFlareMoveModeChange(true) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column {
                Text(
                    text = stringResource(R.string.raw_flare_move),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isLensFlareMoveMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (isLensFlareMoveMode)
                        stringResource(R.string.raw_flare_move_hint_active)
                    else
                        stringResource(R.string.raw_flare_move_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RawSliderRow(
            label = "Brightness",
            value = macro.lensFlare.brightness,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(brightness = it))) },
            displayValue = "${(macro.lensFlare.brightness * 100).toInt()}"
        )
        RawSliderRow(
            label = "Position X",
            value = macro.lensFlare.x,
            valueRange = -1f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(x = it))) },
            displayValue = "%.2f".format(macro.lensFlare.x)
        )
        RawSliderRow(
            label = "Position Y",
            value = macro.lensFlare.y,
            valueRange = -1f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(y = it))) },
            displayValue = "%.2f".format(macro.lensFlare.y)
        )
        RawSliderRow(
            label = "Size",
            value = macro.lensFlare.size,
            valueRange = 0.1f..5f,
            step = 0.05f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(size = it))) },
            displayValue = "%.1f".format(macro.lensFlare.size)
        )
        RawSliderRow(
            label = "Spread",
            value = macro.lensFlare.spread,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(spread = it))) },
            displayValue = "${(macro.lensFlare.spread * 100).toInt()}"
        )
        RawSliderRow(
            label = "Warmth",
            value = macro.lensFlare.warmth,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(warmth = it))) },
            displayValue = "${(macro.lensFlare.warmth * 100).toInt()}"
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Film")
        RawSliderRow(
            label = "Push Pull",
            value = macro.pushPull,
            valueRange = -3f..3f,
            step = 0.1f,
            onValueChange = { onMacroChange(macro.copy(pushPull = it)) },
            displayValue = "%.1f".format(macro.pushPull),
        )
        RawSliderRow(
            label = "Rolloff",
            value = macro.filmRolloff,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(filmRolloff = it)) },
            displayValue = "${(macro.filmRolloff * 100).roundToInt()}",
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader(stringResource(R.string.raw_section_film_grain))
        RawSliderRow(
            label = stringResource(R.string.raw_film_grain_amount),
            value = macro.filmGrain,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(filmGrain = it)) },
            displayValue = "${(macro.filmGrain * 100).toInt()}",
        )
        RawSliderRow(
            label = stringResource(R.string.raw_film_grain_size),
            value = macro.filmGrainSize,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(filmGrainSize = it)) },
            displayValue = "${(macro.filmGrainSize * 100).toInt()}",
        )
        if (SHOW_FILM_GRAIN_WASH_OUT) {
            RawSliderRow(
                label = stringResource(R.string.raw_film_grain_wash_out),
                value = macro.filmGrainWashOut,
                valueRange = 0f..1f,
                step = 0.01f,
                onValueChange = { onMacroChange(macro.copy(filmGrainWashOut = it)) },
                displayValue = "${(macro.filmGrainWashOut * 100).toInt()}",
            )
        }

        Spacer(Modifier.height(8.dp))
        TabResetButton(RawTabId.Effects, macro, onMacroChange)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    )
}
