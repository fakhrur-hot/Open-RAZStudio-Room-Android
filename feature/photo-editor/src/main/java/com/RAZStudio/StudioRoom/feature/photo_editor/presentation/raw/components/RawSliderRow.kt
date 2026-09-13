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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun RawSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    displayValue: String? = null,
    /**
     * Step granularity for the slider, in the same units as [value]. Default
     * 0f means no snapping (continuous slider). When >0, values are snapped to
     * the nearest multiple of `step` and the display formats with enough
     * decimal places to show that resolution (0.1 → 1 decimal, 0.01 → 2, …).
     */
    step: Float = 0f,
    /**
     * Per-slider enable override. Combined (AND) with the panel-wide
     * [LocalPanelControlsEnabled] so a single row can be locked/greyed even
     * while the rest of the panel is interactive.
     */
    enabled: Boolean = true,
    /** Fired once when the drag gesture ends (Material `onValueChangeFinished`). */
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val displayText = displayValue ?: run {
        if (step > 0f && step < 1f) {
            // Fractional step: show as many decimals as the step needs.
            val decimals = when {
                step >= 0.1f -> 1
                step >= 0.01f -> 2
                else -> 3
            }
            val formatted = "%.${decimals}f".format(value)
            if (value > 0f) "+$formatted" else formatted
        } else {
            val rounded = value.roundToInt()
            if (rounded > 0) "+$rounded" else "$rounded"
        }
    }

    val controlsEnabled = LocalPanelControlsEnabled.current

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(min = 96.dp, max = 132.dp),
            maxLines = 1,
        )
        // When a step is set, snap the slider's reported value to the nearest
        // multiple. Material3's Slider supports `steps` (number of discrete
        // notches between min/max) — compute that from the requested step
        // size so the user gets haptic detents at each increment.
        val sliderSteps = if (step > 0f) {
            val span = valueRange.endInclusive - valueRange.start
            val n = (span / step).roundToLong().toInt() - 1
            n.coerceAtLeast(0)
        } else 0
        Slider(
            value = value,
            onValueChange = { v ->
                val snapped = if (step > 0f) {
                    val s = ((v - valueRange.start) / step).roundToLong() * step +
                        valueRange.start
                    s.coerceIn(valueRange.start, valueRange.endInclusive)
                } else v
                onValueChange(snapped)
            },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = sliderSteps,
            enabled = controlsEnabled && enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
    }
}
