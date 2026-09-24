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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

@Composable
internal fun RawColorTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    /**
     * As-shot Kelvin baked into Stage A's WB multipliers. Sourced from
     * the EXIF probe at file-open time. Used as the centre point of the
     * temperature slider when the user unchecks "Default" — drag right
     * → warmer than as-shot, left → cooler. The macro field
     * `whiteBalance` still stores a Kelvin delta from as-shot (0 = no
     * shift), so the shader uniform is unchanged.
     *
     * Pass 0 when EXIF doesn't carry a ColorTemperature tag (typical
     * for consumer DSLR RAWs). The tab falls back to a 5500 K daylight
     * centre in that case.
     */
    asShotKelvin: Int = 0,
    /**
     * True for JPEG/PNG/HEIC sources. A developed file has no as-shot white
     * balance to anchor to — every Kelvin number shown for one would be
     * invented — so the Temperature control becomes a RELATIVE −100…+100
     * slider (owner decision 2026-09-07). RAW keeps absolute Kelvin.
     */
    isNonRawSource: Boolean = false,
    /**
     * Scene-adaptive auto-enhance ("_smart_defaults" card) state. True when a
     * visible _smart_defaults card is present in the action stack. Toggling this
     * shows/hides that card — it carries the per-image SceneDetector
     * recommendations (saturation/vibrance/WB/tint/CLAHE), distinct from the
     * fixed Color Pop effect above.
     */
    sceneAutoEnhanceEnabled: Boolean = false,
    onSceneAutoEnhanceChange: (Boolean) -> Unit = {},
    /** Downscaled graded preview used to render the Color-tab scopes. */
    previewBitmap: android.graphics.Bitmap? = null,
    modifier: Modifier = Modifier,
) {
    // Default = whiteBalance delta == 0 (no shift from as-shot Kelvin).
    var isWbDefault by remember(macro.whiteBalance) { mutableStateOf(macro.whiteBalance == 0) }
    // Relative scale for non-RAW: ±100 on the slider == the ±2500 K the shader
    // uniform saturates at, so the stored macro field and the ABI are unchanged.
    val relativeWb = (macro.whiteBalance / 25f).coerceIn(-100f, 100f)
    // Resolve the slider's centre. 0 from the probe means "EXIF didn't
    // carry a temperature tag" — fall back to a 5500 K daylight anchor.
    val effectiveAsShot = if (asShotKelvin > 0) asShotKelvin else 5500
    val effectiveKelvin = (effectiveAsShot + macro.whiteBalance).coerceIn(2000, 12000)

    Column(modifier = modifier) {
        RawSliderRow(
            label = stringResource(R.string.raw_smart_color_enhance),
            value = macro.smartColorEnhance * 100f,
            valueRange = 0f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(smartColorEnhance = it / 100f)) },
            displayValue = "${(macro.smartColorEnhance * 100f).toInt()}",
        )
        // AI Color Enhance toggle — shows/hides the per-image AI-fusion
        // (Zero-DCE + histogram) auto-enhance card (_ai_color_enhance).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.raw_scene_auto_enhance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Checkbox(
                checked = sceneAutoEnhanceEnabled,
                onCheckedChange = onSceneAutoEnhanceChange,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

        // Temperature row: Default checkbox + collapsible slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.raw_white_balance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.raw_wb_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Checkbox(
                    checked = isWbDefault,
                    onCheckedChange = { checked ->
                        isWbDefault = checked
                        if (checked) onMacroChange(macro.copy(whiteBalance = 0))
                    },
                )
            }
        }
        AnimatedVisibility(visible = !isWbDefault) {
            Column {
                if (isNonRawSource) {
                    // Relative: cooler ← 0 → warmer. No Kelvin, because there is
                    // no as-shot temperature on a developed file to be relative to.
                    RawSliderRow(
                        label = stringResource(R.string.raw_temperature),
                        value = relativeWb,
                        valueRange = -100f..100f,
                        onValueChange = { rel ->
                            onMacroChange(macro.copy(whiteBalance = (rel * 25f).toInt()))
                        },
                        displayValue = (if (relativeWb > 0) "+" else "") + relativeWb.toInt(),
                    )
                } else {
                    RawSliderRow(
                        label = stringResource(R.string.raw_temperature),
                        value = effectiveKelvin.toFloat(),
                        valueRange = 2000f..12000f,
                        onValueChange = { absKelvin ->
                            val delta = (absKelvin.toInt() - effectiveAsShot).coerceIn(-3500, 6500)
                            onMacroChange(macro.copy(whiteBalance = delta))
                        },
                        displayValue = "$effectiveKelvin K",
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_tint),
            value = macro.tint,
            valueRange = -200f..200f,
            onValueChange = { onMacroChange(macro.copy(tint = it)) },
            // Green ← 0 → magenta. The sign is always shown so the neutral point
            // reads as a real zero rather than "some number near the middle".
            displayValue = (if (macro.tint > 0) "+" else "") + macro.tint.toInt(),
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_saturation),
            value = macro.saturation,
            valueRange = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(saturation = it)) },
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_vibrance),
            value = macro.vibrance,
            valueRange = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(vibrance = it)) },
        )
        // Color Density — mid-band saturation boost via 3D LUT
        Spacer(Modifier.height(8.dp))
        RawSliderRow(
            label = "Color Density",
            value = macro.colorDensity,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onMacroChange(macro.copy(colorDensity = it)) },
            displayValue = "${macro.colorDensity.toInt()}",
        )

        // HSL section
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Text(
            text = stringResource(R.string.raw_hsl_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
        )

        HslRangePanel(macro = macro, onMacroChange = onMacroChange)

        // Scopes (vectorscope + luma waveform) — guided-grading aid, collapsed
        // by default. Placed just above the wheels so you can watch chroma/luma
        // while you grade.
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        ColorScopesSection(previewBitmap = previewBitmap)

        // Color Grading wheels (Global / Shadows / Midtones / Highlights) —
        // DaVinci/OpenShot-style Lift/Gamma/Gain trackballs.
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Text(
            text = "Color Grading",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
        )
        ColorGradingSection(macro = macro, onMacroChange = onMacroChange)
        Spacer(Modifier.height(8.dp))
        RawSliderRow(
            label = "Separation",
            value = macro.filmResponse.separation,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = {
                onMacroChange(macro.copy(filmResponse = macro.filmResponse.copy(separation = it)))
            },
            displayValue = (if (macro.filmResponse.separation > 0f) "+" else "") +
                macro.filmResponse.separation.toInt(),
        )

        TabResetButton(RawTabId.Color, macro, onMacroChange)
    }
}

@Composable
private fun HslRangePanel(macro: UserMacro, onMacroChange: (UserMacro) -> Unit) {
    // SIX bands, deliberately — not the twelve the engine can carry.
    //
    // hueWeight() (apply_macro.cpp / gles_renderer.cpp) feathers each anchor
    // with a Gaussian of FIXED 30-degree half-width. At six anchors that is
    // 60 degrees apart, so a pixel blends about two neighbours on a gentle
    // ramp -- the width the feather was tuned for. Exposing all twelve puts
    // the anchors 30 degrees apart WITHOUT widening the feather, so adjacent
    // sliders that are set far apart sit inside each other's falloff; per-pixel
    // hue noise (rgbToHsl's hue is unstable as saturation drops, and satMask is
    // already fully open by s=0.20) then swings the blend weights pixel to
    // pixel and the result SPECKLES. Owner reported exactly that, 2026-09-07:
    // "adjusting them each cause unsmooth speckle different".
    //
    // The other six anchors stay live in the model, the sidecar and both
    // renderers, so an imported Adobe preset's Purple/Magenta still applies --
    // they simply have no slider. If they are ever exposed again, WIDEN
    // hueWeight's half-width to match the new spacing first.
    val ranges = listOf(
        stringResource(R.string.raw_hsl_red),    // 0 deg
        stringResource(R.string.raw_hsl_orange), // 30 deg
        stringResource(R.string.raw_hsl_yellow), // 60 deg
        stringResource(R.string.raw_hsl_green),  // 120 deg
        stringResource(R.string.raw_hsl_aqua),   // 180 deg
        stringResource(R.string.raw_hsl_blue),   // 240 deg
    )
    var selectedRange by remember { mutableIntStateOf(0) }

    ScrollableTabRow(
        selectedTabIndex = selectedRange,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 0.dp,
    ) {
        ranges.forEachIndexed { i, name ->
            Tab(
                selected = selectedRange == i,
                onClick  = { selectedRange = i },
                text     = { Text(name, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    when (selectedRange) {
        0 -> HslSliders(
            hue = macro.hslRedHue, sat = macro.hslRedSat, lum = macro.hslRedLum,
            onHue = { onMacroChange(macro.copy(hslRedHue = it)) },
            onSat = { onMacroChange(macro.copy(hslRedSat = it)) },
            onLum = { onMacroChange(macro.copy(hslRedLum = it)) },
        )
        1 -> HslSliders(
            hue = macro.hslOrangeHue, sat = macro.hslOrangeSat, lum = macro.hslOrangeLum,
            onHue = { onMacroChange(macro.copy(hslOrangeHue = it)) },
            onSat = { onMacroChange(macro.copy(hslOrangeSat = it)) },
            onLum = { onMacroChange(macro.copy(hslOrangeLum = it)) },
        )
        2 -> HslSliders(
            hue = macro.hslYellowHue, sat = macro.hslYellowSat, lum = macro.hslYellowLum,
            onHue = { onMacroChange(macro.copy(hslYellowHue = it)) },
            onSat = { onMacroChange(macro.copy(hslYellowSat = it)) },
            onLum = { onMacroChange(macro.copy(hslYellowLum = it)) },
        )
        3 -> HslSliders(
            hue = macro.hslGreenHue, sat = macro.hslGreenSat, lum = macro.hslGreenLum,
            onHue = { onMacroChange(macro.copy(hslGreenHue = it)) },
            onSat = { onMacroChange(macro.copy(hslGreenSat = it)) },
            onLum = { onMacroChange(macro.copy(hslGreenLum = it)) },
        )
        4 -> HslSliders(
            hue = macro.hslAquaHue, sat = macro.hslAquaSat, lum = macro.hslAquaLum,
            onHue = { onMacroChange(macro.copy(hslAquaHue = it)) },
            onSat = { onMacroChange(macro.copy(hslAquaSat = it)) },
            onLum = { onMacroChange(macro.copy(hslAquaLum = it)) },
        )
        5 -> HslSliders(
            hue = macro.hslBlueHue, sat = macro.hslBlueSat, lum = macro.hslBlueLum,
            onHue = { onMacroChange(macro.copy(hslBlueHue = it)) },
            onSat = { onMacroChange(macro.copy(hslBlueSat = it)) },
            onLum = { onMacroChange(macro.copy(hslBlueLum = it)) },
        )
    }
}

@Composable
private fun HslSliders(
    hue: Float, sat: Float, lum: Float,
    onHue: (Float) -> Unit, onSat: (Float) -> Unit, onLum: (Float) -> Unit,
) {
    RawSliderRow(
        label = stringResource(R.string.raw_hsl_hue),
        value = hue,
        valueRange = -180f..180f,
        onValueChange = onHue,
        displayValue  = "${hue.toInt()}°",
    )
    Spacer(Modifier.height(4.dp))
    RawSliderRow(
        label = stringResource(R.string.raw_saturation),
        value = sat,
        valueRange = -100f..100f,
        onValueChange = onSat,
    )
    Spacer(Modifier.height(4.dp))
    RawSliderRow(
        label = stringResource(R.string.raw_hsl_lum),
        value = lum,
        valueRange = -100f..100f,
        onValueChange = onLum,
    )
}
