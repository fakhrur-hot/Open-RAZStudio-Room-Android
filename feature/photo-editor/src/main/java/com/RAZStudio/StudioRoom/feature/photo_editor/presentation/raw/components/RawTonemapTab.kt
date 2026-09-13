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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

@Composable
internal fun RawTonemapTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Exposure / Highlights / Shadows use independent tonemap*
        // macro fields so an XMP preset can drive them without
        // overwriting the Light tab's AUTO EXPO values.
        RawSliderRow(
            label = stringResource(R.string.raw_exposure),
            value = macro.tonemapExposure,
            valueRange = -3f..3f,
            step = 0.1f,
            onValueChange = { onMacroChange(macro.copy(tonemapExposure = it)) },
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_contrast),
            value = macro.contrast,
            valueRange = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(contrast = it)) },
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_highlights),
            value = macro.tonemapHighlights,
            valueRange = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(tonemapHighlights = it)) },
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_shadows),
            value = macro.tonemapShadows,
            valueRange = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(tonemapShadows = it)) },
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_whites),
            value = macro.whites,
            valueRange = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(whites = it)) },
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_blacks),
            value = macro.blacks,
            valueRange = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(blacks = it)) },
        )
        Spacer(Modifier.height(4.dp))
        // Film Response Recovery / Fill Light — moved here from LUT ADV
        // (2026-09). Same macro fields; Recovery renamed for the Tone panel.
        RawSliderRow(
            label = stringResource(R.string.raw_highlights_recovery),
            value = macro.filmResponse.recovery,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = {
                onMacroChange(macro.copy(filmResponse = macro.filmResponse.copy(recovery = it)))
            },
            displayValue = "${macro.filmResponse.recovery.toInt()}",
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = stringResource(R.string.raw_fill_light),
            value = macro.filmResponse.fillLight,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = {
                onMacroChange(macro.copy(filmResponse = macro.filmResponse.copy(fillLight = it)))
            },
            displayValue = "${macro.filmResponse.fillLight.toInt()}",
        )
        // Resets the whole Tone panel (Light + Tonemap sliders) — see resetTab.
        TabResetButton(RawTabId.Tonemap, macro, onMacroChange, label = "Tone")
    }
}
