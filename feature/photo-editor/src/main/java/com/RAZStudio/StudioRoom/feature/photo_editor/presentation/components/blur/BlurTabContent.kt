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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.FilterItem
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.hapticsClickable
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container
import kotlin.math.roundToInt

private val DofMode.label: String
    get() = when (this) {
        DofMode.Standard   -> "Standard"
        DofMode.Guided     -> "Guided"
        DofMode.Bilateral  -> "Bilateral"
        DofMode.ScatterCoC -> "Scatter CoC"
    }

private val DofMode.description: String
    get() = when (this) {
        DofMode.Standard   -> "Smooth feathered edge"
        DofMode.Guided     -> "Edge-preserving, no halo"
        DofMode.Bilateral  -> "Range-aware feathering"
        DofMode.ScatterCoC -> "Lens-like variable bokeh"
    }

@Composable
fun BlurTabContent(
    filters: List<UiFilter<*>>,
    onBlurSelectionChanged: (BlurSelection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ── Selection state ──────────────────────────────────────────────────────
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var currentFilter by remember { mutableStateOf<UiFilter<*>?>(null) }
    var aiEnabled by remember { mutableStateOf(false) }
    var edgeBlur by remember { mutableFloatStateOf(0.5f) }
    // Bokeh shape: slider position 0-7 maps to sides [0, 16, 14, 12, 10, 8, 6, 4]
    var bokehSliderPos by remember { mutableIntStateOf(0) }
    var dofMode by remember { mutableStateOf(DofMode.Standard) }

    val bokehEdgesMap = intArrayOf(0, 16, 14, 12, 10, 8, 6, 4)

    fun notify() {
        val f = currentFilter ?: return onBlurSelectionChanged(null)
        val bokehEdges = bokehEdgesMap[bokehSliderPos]
        onBlurSelectionChanged(BlurSelection(f, aiEnabled, edgeBlur, bokehEdges, dofMode))
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items = filters, key = { _, f -> f::class.simpleName ?: f.hashCode() }) { index, filter ->
            val isSelected = selectedIndex == index

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp),
            ) {
                // Blur type name row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hapticsClickable {
                            if (isSelected) {
                                // Deselect
                                selectedIndex = -1
                                currentFilter = null
                                onBlurSelectionChanged(null)
                            } else {
                                selectedIndex = index
                                currentFilter = filter.newInstance()
                                aiEnabled = false
                                edgeBlur = 0.5f
                                bokehSliderPos = 0
                                dofMode = DofMode.Standard
                                notify()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(filter.title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Expanded controls
                AnimatedVisibility(
                    visible = isSelected,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    val cf = currentFilter
                    if (cf != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Spacer(Modifier.height(4.dp))

                            // Filter parameter sliders via FilterItem
                            @Suppress("UNCHECKED_CAST")
                            FilterItem(
                                filter = cf as UiFilter<Any>,
                                showDragHandle = false,
                                onRemove = {
                                    selectedIndex = -1
                                    currentFilter = null
                                    onBlurSelectionChanged(null)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                onFilterChange = { value ->
                                    @Suppress("UNCHECKED_CAST")
                                    currentFilter = (cf as UiFilter<Any>).copy(value)
                                    notify()
                                },
                                onCreateTemplate = null,
                                backgroundColor = Color.Transparent,
                                canHide = false,
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )

                            // Bokeh Shape slider — controls N-gon aperture sides
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Bokeh Shape",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    val sidesLabel = when (bokehEdgesMap[bokehSliderPos]) {
                                        0 -> "Circle"
                                        else -> "${bokehEdgesMap[bokehSliderPos]}-edge"
                                    }
                                    Text(
                                        text = sidesLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Slider(
                                    value = bokehSliderPos.toFloat(),
                                    onValueChange = { v ->
                                        bokehSliderPos = v.roundToInt()
                                        notify()
                                    },
                                    valueRange = 0f..7f,
                                    steps = 6,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .size(height = 32.dp, width = 0.dp),
                                )
                                Text(
                                    text = "Aperture shape of bokeh highlights",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )

                            // AI Detection toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AI Detection",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "Subject excluded · Edge protection",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Checkbox(
                                    checked = aiEnabled,
                                    onCheckedChange = { v ->
                                        aiEnabled = v
                                        notify()
                                    },
                                )
                            }

                            // Edge Feather + DoF Mode — only shown when AI is on
                            AnimatedVisibility(visible = aiEnabled) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                ) {
                                    // Edge Feather slider
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Edge Feather",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            text = "${(edgeBlur * 100).roundToInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Slider(
                                        value = edgeBlur,
                                        onValueChange = { v ->
                                            edgeBlur = v
                                            notify()
                                        },
                                        valueRange = 0f..1f,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .size(height = 32.dp, width = 0.dp),
                                    )
                                    Text(
                                        text = "Wider = softer subject edge",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    // DoF Algorithm selector
                                    Text(
                                        text = "DoF Quality",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        DofMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = dofMode == mode,
                                                onClick = { dofMode = mode; notify() },
                                                label = {
                                                    Text(
                                                        text = mode.label,
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                    Text(
                                        text = dofMode.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
