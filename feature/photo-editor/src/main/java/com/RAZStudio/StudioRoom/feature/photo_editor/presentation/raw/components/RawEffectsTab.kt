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
    masksReady: Boolean = false,
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
                enabled = masksReady,
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
        if (!masksReady) {
            Text(
                text = "Detecting subject…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isLensFlareMoveMode) {
                FilledIconButton(
                    onClick = { onLensFlareMoveModeChange(false) },
                    enabled = masksReady,
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
                    enabled = masksReady,
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
        Spacer(Modifier.height(8.dp))
        RawSliderRow(
            label = "Brightness",
            value = macro.lensFlare.brightness,
            enabled = masksReady,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(brightness = it))) },
            displayValue = "${(macro.lensFlare.brightness * 100).toInt()}"
        )
        RawSliderRow(
            label = "Size",
            value = macro.lensFlare.size,
            enabled = masksReady,
            valueRange = 0.1f..5f,
            step = 0.05f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(size = it))) },
            displayValue = "%.1f".format(macro.lensFlare.size)
        )
        RawSliderRow(
            label = "Spread",
            value = macro.lensFlare.spread,
            enabled = masksReady,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(spread = it))) },
            displayValue = "${(macro.lensFlare.spread * 100).toInt()}"
        )
        RawSliderRow(
            label = "Warmth",
            value = macro.lensFlare.warmth,
            enabled = masksReady,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(warmth = it))) },
            displayValue = "${(macro.lensFlare.warmth * 100).toInt()}"
        )
        RawSliderRow(
            label = "Distance",
            value = macro.lensFlare.distance,
            enabled = masksReady,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(distance = it))) },
            displayValue = if (macro.lensFlare.distance < 0.05f) "Far" else if (macro.lensFlare.distance > 0.95f) "Near" else "${(macro.lensFlare.distance * 100).toInt()}",
        )
        RawSliderRow(
            label = "Lens Hood",
            value = macro.lensFlare.hood * 100f,
            enabled = masksReady,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(hood = it / 100f))) },
            displayValue = "${(macro.lensFlare.hood * 100f).toInt()}",
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = macro.lensFlare.roundness < 0.98f,
                enabled = masksReady,
                onCheckedChange = { on ->
                    onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(
                        roundness = if (on) 0.45f else 1f,
                    )))
                },
            )
            Text("Starburst", style = MaterialTheme.typography.bodyMedium)
        }
        RawSliderRow(
            label = "Starburst",
            value = macro.lensFlare.starburst * 100f,
            enabled = masksReady,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(starburst = it / 100f))) },
            displayValue = "${(macro.lensFlare.starburst * 100f).toInt()}",
        )
        RawSliderRow(
            label = "Blades",
            value = macro.lensFlare.blades,
            enabled = masksReady,
            valueRange = 0f..1f,
            step = 1f / 12f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(blades = it))) },
            displayValue = if (macro.lensFlare.blades <= 0.04f) "0" else (4 + (macro.lensFlare.blades * 12f).toInt()).coerceIn(4, 16).toString(),
        )
        RawSliderRow(
            label = "Length",
            value = if (macro.lensFlare.roundness < 0.98f) macro.lensFlare.roundness * 100f else 45f,
            enabled = masksReady && macro.lensFlare.roundness < 0.98f,
            valueRange = 5f..97f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(roundness = it / 100f))) },
            displayValue = "${(if (macro.lensFlare.roundness < 0.98f) macro.lensFlare.roundness * 100f else 45f).toInt()}",
        )
        RawSliderRow(
            label = "Rotation",
            value = macro.lensFlare.rotation * 100f,
            enabled = masksReady,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(rotation = it / 100f))) },
            displayValue = "${(macro.lensFlare.rotation * 100f).toInt()}",
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Light Source")
        if (!masksReady) {
            Text(
                text = "Detecting subject…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
        }
        RawSliderRow(
            label = "Scene Distance",
            value = macro.sceneShadow.distance,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(sceneShadow = macro.sceneShadow.copy(distance = it))) },
            displayValue = if (macro.sceneShadow.distance < 5f) "Far" else if (macro.sceneShadow.distance > 95f) "Near" else "${macro.sceneShadow.distance.toInt()}",
        )
        RawSliderRow(
            label = "Shadow Strength",
            value = macro.sceneShadow.strength,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(sceneShadow = macro.sceneShadow.copy(strength = it))) },
            displayValue = "${macro.sceneShadow.strength.toInt()}",
        )
        RawSliderRow(
            label = "Shadow Softness",
            value = macro.sceneShadow.softness,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(sceneShadow = macro.sceneShadow.copy(softness = it))) },
            displayValue = "${macro.sceneShadow.softness.toInt()}",
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
        RawSliderRow(
            label = "Structure",
            value = macro.grainEmulsion.getOrElse(0) { 0f },
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { v ->
                val g = macro.grainEmulsion.copyOf(13)
                g[0] = v
                onMacroChange(macro.copy(grainEmulsion = g))
            },
            displayValue = "${(macro.grainEmulsion.getOrElse(0) { 0f } * 100).toInt()}",
        )
        RawSliderRow(
            label = "Chroma Grain",
            value = macro.grainEmulsion.getOrElse(1) { 0f },
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { v ->
                val g = macro.grainEmulsion.copyOf(13)
                g[1] = v
                onMacroChange(macro.copy(grainEmulsion = g))
            },
            displayValue = "${(macro.grainEmulsion.getOrElse(1) { 0f } * 100).toInt()}",
        )
        RawSliderRow(
            label = "Shadow Grain",
            value = macro.grainEmulsion.getOrElse(3) { 0f },
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { v ->
                val g = macro.grainEmulsion.copyOf(13)
                g[3] = v
                onMacroChange(macro.copy(grainEmulsion = g))
            },
            displayValue = "${(macro.grainEmulsion.getOrElse(3) { 0f } * 100).toInt()}",
        )
        RawSliderRow(
            label = "Highlight Suppression",
            value = macro.grainEmulsion.getOrElse(2) { 0f },
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { v ->
                val g = macro.grainEmulsion.copyOf(13)
                g[2] = v
                onMacroChange(macro.copy(grainEmulsion = g))
            },
            displayValue = "${(macro.grainEmulsion.getOrElse(2) { 0f } * 100).toInt()}",
        )
        RawSliderRow(
            label = "Edge Bias",
            value = macro.grainEmulsion.getOrElse(4) { 0f },
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { v ->
                val g = macro.grainEmulsion.copyOf(13)
                g[4] = v
                onMacroChange(macro.copy(grainEmulsion = g))
            },
            displayValue = "${(macro.grainEmulsion.getOrElse(4) { 0f } * 100).toInt()}",
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
