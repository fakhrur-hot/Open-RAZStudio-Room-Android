/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import kotlin.math.roundToInt

/**
 * Bloom slider scaling: UI 0..5 → internal macro 0.0..1.0, linear.
 * Max set to 5 (per owner request, 2026-08-29); UI 5 = macro 1.0 (the renderer's
 * bloom-mix cap), so the full effect range is preserved with a 0–5 dial.
 */
private fun bloomUiToMacro(uiValue: Float): Float = (uiValue / 5f).coerceIn(0f, 1f)

private fun bloomMacroToUi(macroValue: Float): Float = (macroValue * 5f).coerceIn(0f, 5f)

/**
 * Fraction of the background glow that the AI subject keeps when "Protect
 * subject" is on. 0.10 ≈ effectively clean (a whisper of glow to avoid a hard
 * mask edge). The renderer routes subject pixels to `subjectBloom` and
 * background pixels to `ortonStrength` (shader_sources.cpp bloom composite).
 */
private const val SUBJECT_PROTECT_GLOW = 0.10f

/** Hidden like SHOW_LUT_APPROX_SYNC — macro/sidecar field remains. */
private const val SHOW_FILM_GRAIN_WASH_OUT = false

@Composable
internal fun RawEffectsTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
    // Bokeh is subject-mask gated (the GL shader skips it entirely and the
    // export bake bails when no mask is present). Segmentation is lazy, so
    // engaging Bokeh must kick off the ONNX chain — otherwise the effect
    // silently no-ops. Idempotent, safe to call on every drag tick.
    onSegmentationNeeded: () -> Unit = {},
    // True while subject detection runs and no mask is ready yet — the Bokeh
    // slider is greyed out until it completes so the user can't drag a control
    // that would do nothing.
    subjectSegBusy: Boolean = false,
    // False once detection FINISHED and found no subject (empty matte). Bokeh
    // and "Protect subject" then have nothing to act on — say so instead of
    // letting the user think the controls are broken.
    subjectDetected: Boolean = true,
    /**
     * Stage A long side (max of previewWidth/Height). Drives Pro-Mist density
     * chip strength so bloom stays comparable across photo sizes.
     */
    imageLongSide: Int = CinematicBloomProcessor.REF_LONG_SIDE,
) {
    Column(modifier = modifier) {
        val bloomOn = CinematicBloomProcessor.activeTier(macro) != CinematicBloomProcessor.Tier.NONE

        // ── Bloom (filmic / Black Pro-Mist via Karis + Orton + fused Glow) ──
        Spacer(Modifier.height(8.dp))
        SectionHeader("Bloom")
        Text(
            text = "Diffusion filter",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
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
        // Strength / Tightness / Halation / Warmth only when a density chip is on.
        // Off chip alone turns bloom off — slider edits must not clear the tier.
        if (bloomOn) {
            RawSliderRow(
                label = stringResource(R.string.raw_bloom_strength),
                value = bloomMacroToUi(macro.ortonStrength),
                valueRange = 0f..5f, step = 1f,
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
                            // Keep selected density chip; never switch to Off here.
                        )
                    )
                },
                displayValue = "${bloomMacroToUi(macro.ortonStrength).toInt()}",
            )
            RawSliderRow(
                label = stringResource(R.string.raw_bloom_tightness),
                value = macro.mistTightness,
                valueRange = 0f..100f, step = 1f,
                onValueChange = { onMacroChange(macro.copy(mistTightness = it)) },
                displayValue = "${macro.mistTightness.toInt()}",
            )
            RawSliderRow(
                label = stringResource(R.string.raw_bloom_halation),
                value = macro.mistHalation,
                valueRange = 0f..100f, step = 1f,
                onValueChange = { onMacroChange(macro.copy(mistHalation = it)) },
                displayValue = "${macro.mistHalation.toInt()}",
            )
            // Glow fused into Bloom: strength comes from density chips; Warmth
            // remains a user slider (default 0 = neutral).
            RawSliderRow(
                label = stringResource(R.string.raw_bloom_warmth),
                value = macro.fxGlowWarmth,
                valueRange = -50f..50f, step = 1f,
                onValueChange = { onMacroChange(macro.copy(fxGlowWarmth = it)) },
                displayValue = "${macro.fxGlowWarmth.toInt()}",
            )
        }
        // "Protect subject" toggle removed — subject bloom exclusion is no longer
        // exposed in the UI. The bloomExcludeSubject macro field remains (defaults
        // to false) so existing sidecars/presets still deserialize; with no toggle
        // the effect always renders in the non-excluded (uniform bloom) mode.

        // ── Bokeh (moved here from the Local/Mask tab) ─────────────────────────
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
                text = "Detecting subject… Bokeh unlocks when ready.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else if (!subjectDetected && macro.bokehBlur > 0) {
            Text(
                text = "No subject detected in this photo — Bokeh has nothing to keep sharp, so it is off.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // Mist UI removed (2026-09) — Bloom covers the intention. Macro fields
        // fxMist / fxMistWarmth remain for sidecars (default 0).

        // ── Vintage — clean tonal look (desaturate + cast + fade + vignette) ─
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Vintage")
        RawSliderRow(label = "Strength", value = macro.fxVintageStrength,
            valueRange = 0f..100f, step = 1f,
            onValueChange = { onMacroChange(macro.copy(fxVintageStrength = it)) },
            displayValue = "${macro.fxVintageStrength.toInt()}")
        RawSliderRow(label = "Fade", value = macro.fxVintageFade,
            valueRange = 0f..100f, step = 1f,
            onValueChange = { onMacroChange(macro.copy(fxVintageFade = it)) },
            displayValue = "${macro.fxVintageFade.toInt()}")

        // ── Color Shift (OpenShot RGB split, horizontal per channel) ──────────
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Color Shift (RGB split)")
        Text(
            text = stringResource(R.string.raw_color_shift_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        RawSliderRow(
            label = stringResource(R.string.raw_color_shift_red),
            value = macro.colorShift.redX,
            valueRange = -100f..100f, step = 1f,
            onValueChange = { onMacroChange(macro.copy(colorShift = macro.colorShift.copy(redX = it))) },
            displayValue = "${macro.colorShift.redX.toInt()}",
        )
        RawSliderRow(
            label = stringResource(R.string.raw_color_shift_green),
            value = macro.colorShift.greenX,
            valueRange = -100f..100f, step = 1f,
            onValueChange = { onMacroChange(macro.copy(colorShift = macro.colorShift.copy(greenX = it))) },
            displayValue = "${macro.colorShift.greenX.toInt()}",
        )
        RawSliderRow(
            label = stringResource(R.string.raw_color_shift_blue),
            value = macro.colorShift.blueX,
            valueRange = -100f..100f, step = 1f,
            onValueChange = { onMacroChange(macro.copy(colorShift = macro.colorShift.copy(blueX = it))) },
            displayValue = "${macro.colorShift.blueX.toInt()}",
        )

        // ── Lens Flare (OpenShot procedural flare) ─────────────────────────────
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Lens Flare")
        RawSliderRow(label = "Brightness", value = macro.lensFlare.brightness,
            valueRange = 0f..1f, step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(brightness = it))) },
            displayValue = "${(macro.lensFlare.brightness * 100).toInt()}")
        RawSliderRow(label = "Position X", value = macro.lensFlare.x,
            valueRange = -1f..1f, step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(x = it))) },
            displayValue = "%.2f".format(macro.lensFlare.x))
        RawSliderRow(label = "Position Y", value = macro.lensFlare.y,
            valueRange = -1f..1f, step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(y = it))) },
            displayValue = "%.2f".format(macro.lensFlare.y))
        RawSliderRow(label = "Size", value = macro.lensFlare.size,
            valueRange = 0.1f..5f, step = 0.05f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(size = it))) },
            displayValue = "%.1f".format(macro.lensFlare.size))
        RawSliderRow(label = "Spread", value = macro.lensFlare.spread,
            valueRange = 0f..1f, step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(spread = it))) },
            displayValue = "${(macro.lensFlare.spread * 100).toInt()}")
        RawSliderRow(label = "Warmth", value = macro.lensFlare.warmth,
            valueRange = 0f..1f, step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(lensFlare = macro.lensFlare.copy(warmth = it))) },
            displayValue = "${(macro.lensFlare.warmth * 100).toInt()}")

        // ── Film ─────────────────────────────────────────────────────────────
        // Push/Pull is a post-LUT film-stop EV (after curves), NOT Tone Exposure
        // (early linear EV). Kept here; do not wire over Tone Exposure.
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader("Film")
        RawSliderRow(
            label = "Push / Pull",
            value = macro.pushPull,
            valueRange = -3f..3f,
            step = 0.1f,
            onValueChange = { onMacroChange(macro.copy(pushPull = it)) },
            displayValue = "%.1f".format(macro.pushPull),
        )
        RawSliderRow(
            label = "Film Rolloff",
            value = macro.filmRolloff,
            valueRange = 0f..1f,
            step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(filmRolloff = it)) },
            displayValue = "${Math.round(macro.filmRolloff * 100)}",
        )

        // ── Film Grain (moved here from the Detail tab) ────────────────────────
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SectionHeader(stringResource(R.string.raw_section_film_grain))
        RawSliderRow(
            label = stringResource(R.string.raw_film_grain_amount),
            value = macro.filmGrain,
            valueRange = 0f..1f, step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(filmGrain = it)) },
            displayValue = "${(macro.filmGrain * 100).toInt()}",
        )
        RawSliderRow(
            label = stringResource(R.string.raw_film_grain_size),
            value = macro.filmGrainSize,
            valueRange = 0f..1f, step = 0.01f,
            onValueChange = { onMacroChange(macro.copy(filmGrainSize = it)) },
            displayValue = "${(macro.filmGrainSize * 100).toInt()}",
        )
        if (SHOW_FILM_GRAIN_WASH_OUT) {
            RawSliderRow(
                label = stringResource(R.string.raw_film_grain_wash_out),
                value = macro.filmGrainWashOut,
                valueRange = 0f..1f, step = 0.01f,
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
