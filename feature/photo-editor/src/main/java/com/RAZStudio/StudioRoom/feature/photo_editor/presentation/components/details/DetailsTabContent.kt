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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container
import kotlin.math.roundToInt

@Composable
fun DetailsTabContent(
    onSelectionChanged: (DetailsSelection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var smartSharpness by rememberSaveable { mutableStateOf(0f) }
    var luminanceNR by rememberSaveable { mutableStateOf(0f) }
    var colorNR by rememberSaveable { mutableStateOf(0f) }
    var filmGrain by rememberSaveable { mutableStateOf(0f) }
    var filmGrainSize by rememberSaveable { mutableStateOf(0.5f) }
    var filmGrainUniformity by rememberSaveable { mutableStateOf(0f) }
    var filmGrainWashOut by rememberSaveable { mutableStateOf(0f) }

    fun notify() {
        val sel = DetailsSelection(
            smartSharpness = smartSharpness,
            luminanceNR = luminanceNR,
            colorNR = colorNR,
            filmGrain = filmGrain,
            filmGrainSize = filmGrainSize,
            filmGrainUniformity = filmGrainUniformity,
            filmGrainWashOut = filmGrainWashOut,
        )
        onSelectionChanged(if (sel.isEmpty) null else sel)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Sharpness ────────────────────────────────────────────────────────
        item(key = "sharpness_section") {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionHeader(title = "Sharpness")
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                DetailSlider(
                    label = "Smart Sharpness",
                    value = smartSharpness,
                    valueRange = 0f..1f,
                    displayValue = formatPositive(smartSharpness),
                    onValueChange = { smartSharpness = it; notify() },
                )
            }
        }

        // ── Noise Reduction ──────────────────────────────────────────────────
        item(key = "nr_section") {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionHeader(title = "Noise Reduction")
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                DetailSlider(
                    label = "Luminance",
                    value = luminanceNR,
                    valueRange = 0f..1f,
                    displayValue = formatPositive(luminanceNR),
                    onValueChange = { luminanceNR = it; notify() },
                )
                DetailSlider(
                    label = "Color",
                    value = colorNR,
                    valueRange = 0f..1f,
                    displayValue = formatPositive(colorNR),
                    onValueChange = { colorNR = it; notify() },
                )
            }
        }

        // ── Film Grain ───────────────────────────────────────────────────────
        item(key = "grain_section") {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionHeader(title = "Film Grain")
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                DetailSlider(
                    label = "Amount",
                    value = filmGrain,
                    valueRange = 0f..1f,
                    displayValue = formatPositive(filmGrain),
                    onValueChange = { filmGrain = it; notify() },
                )
                DetailSlider(
                    label = "Size",
                    value = filmGrainSize,
                    valueRange = 0f..1f,
                    displayValue = formatPositive(filmGrainSize),
                    onValueChange = { filmGrainSize = it; notify() },
                )
                DetailSlider(
                    label = "Uniformity",
                    value = filmGrainUniformity,
                    valueRange = 0f..1f,
                    displayValue = formatPositive(filmGrainUniformity),
                    onValueChange = { filmGrainUniformity = it; notify() },
                )
                DetailSlider(
                    label = "Wash Out",
                    value = filmGrainWashOut,
                    valueRange = 0f..1f,
                    displayValue = formatPositive(filmGrainWashOut),
                    onValueChange = { filmGrainWashOut = it; notify() },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun DetailSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatPositive(v: Float): String {
    return "${(v * 100).roundToInt()}"
}
