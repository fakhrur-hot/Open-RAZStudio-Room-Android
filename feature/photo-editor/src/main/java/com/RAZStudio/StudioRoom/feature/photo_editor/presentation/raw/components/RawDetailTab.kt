/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License, Version 2.0 (the "License");
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

@Composable
internal fun RawDetailTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Sharpness ────────────────────────────────────────────────────────────
        DetailSection(title = stringResource(R.string.raw_section_sharpness)) {
            RawSliderRow(
                label = stringResource(R.string.raw_sharpness),
                value = macro.sharpness,
                valueRange = 0f..100f,
                onValueChange = { onMacroChange(macro.withLinkedSharpness(it)) },
                displayValue = "${macro.sharpness.toInt()}",
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = stringResource(R.string.raw_texture),
                value = macro.texture,
                valueRange = -100f..100f,
                onValueChange = { onMacroChange(macro.copy(texture = it)) },
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = stringResource(R.string.raw_clarity),
                value = macro.clarity,
                valueRange = -100f..100f,
                onValueChange = { onMacroChange(macro.copy(clarity = it)) },
            )
        }

        // ── Noise Reduction ──────────────────────────────────────────────────────
        DetailSection(title = stringResource(R.string.raw_section_noise_reduction)) {
            RawSliderRow(
                label = stringResource(R.string.raw_luminance_nr),
                value = macro.luminanceNR,
                valueRange = 0f..1f,
                onValueChange = { onMacroChange(macro.copy(luminanceNR = it)) },
                displayValue = "${(macro.luminanceNR * 100).toInt()}",
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = stringResource(R.string.raw_color_nr),
                value = macro.colorNR,
                valueRange = 0f..1f,
                onValueChange = { onMacroChange(macro.copy(colorNR = it)) },
                displayValue = "${(macro.colorNR * 100).toInt()}",
            )
            Spacer(Modifier.height(4.dp))
            // Per-channel Blue NR — extra Cb-only smoothing on top of Color NR.
            // Bayer blue channel has the lowest WB gain → worst SNR → most
            // chroma noise; route extra cleanup here without flattening red /
            // yellow detail.
            RawSliderRow(
                label = "Blue noise",
                value = macro.blueNR,
                valueRange = 0f..1f,
                onValueChange = { onMacroChange(macro.copy(blueNR = it)) },
                displayValue = "${(macro.blueNR * 100).toInt()}",
            )
            Spacer(Modifier.height(4.dp))
            // Symmetric Red (Cr) NR knob — same idea as Blue NR but for the
            // red chroma axis. Picks up red shadow speckle that Color NR alone
            // would have to flatten reds across the frame to reach.
            RawSliderRow(
                label = "Red noise",
                value = macro.redNR,
                valueRange = 0f..1f,
                onValueChange = { onMacroChange(macro.copy(redNR = it)) },
                displayValue = "${(macro.redNR * 100).toInt()}",
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = stringResource(R.string.raw_smooth_background),
                value = macro.smoothBackground,
                valueRange = 0f..1f,
                onValueChange = { onMacroChange(macro.copy(smoothBackground = it)) },
                displayValue = "${(macro.smoothBackground * 100).toInt()}",
            )
        }

        // Film Grain section moved to the FX (Effects) tab — see RawEffectsTab.
        // ── Shadow Removal — hidden per request (2026-06-20) ──────────────────────
        // HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        // DetailSection(title = "Shadow Removal") {
        //     Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        //         Checkbox(checked = macro.removeShadows,
        //             onCheckedChange = { onMacroChange(macro.copy(removeShadows = it)) })
        //         Column(modifier = Modifier.weight(1f)) {
        //             Text("Remove Shadows", style = MaterialTheme.typography.bodyMedium)
        //             Text("General scene shadow removal — applied at export",
        //                 style = MaterialTheme.typography.bodySmall,
        //                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        //         }
        //     }
        //     Spacer(Modifier.height(4.dp))
        //     Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        //         Checkbox(checked = macro.removeFaceShadows,
        //             onCheckedChange = { onMacroChange(macro.copy(removeFaceShadows = it)) })
        //         Column(modifier = Modifier.weight(1f)) {
        //             Text("Remove Face Shadows", style = MaterialTheme.typography.bodyMedium)
        //             Text("Portrait-aware shadow removal — applied at export",
        //                 style = MaterialTheme.typography.bodySmall,
        //                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        //         }
        //     }
        // }

        TabResetButton(RawTabId.Detail, macro, onMacroChange)
    }
}

@Composable
private fun DetailCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        HorizontalDivider(
            modifier = Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        content()
    }
}
