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

package com.RAZStudio.StudioRoom.core.ui.widget.controls.selection

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeSharpen
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.ui.theme.outlineVariant
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedChip
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container

@Composable
fun ResizeSharpenSelector(
    value: ResizeSharpen,
    onValueChange: (ResizeSharpen) -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Unspecified,
    shape: Shape = ShapeDefaults.extraLarge,
    enabled: Boolean = true,
) {
    val alpha by animateColorAsState(
        targetValue = if (enabled) Color.Transparent else Color.Transparent
    )
    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.38f)
            .container(
                shape = shape,
                color = backgroundColor
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.resize_sharpen),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .container(
                    color = MaterialTheme.colorScheme.surface,
                    shape = ShapeDefaults.default
                )
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ResizeSharpen.entries.forEach { entry ->
                EnhancedChip(
                    onClick = { if (enabled) onValueChange(entry) },
                    selected = value == entry && enabled,
                    label = {
                        Text(text = stringResource(entry.label))
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    selectedColor = MaterialTheme.colorScheme.outlineVariant(
                        0.2f,
                        MaterialTheme.colorScheme.tertiary
                    ),
                    selectedContentColor = MaterialTheme.colorScheme.onTertiary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

val ResizeSharpen.label: Int
    get() = when (this) {
        ResizeSharpen.None -> R.string.resize_sharpen_none
        ResizeSharpen.Low -> R.string.resize_sharpen_low
        ResizeSharpen.Medium -> R.string.resize_sharpen_medium
        ResizeSharpen.High -> R.string.resize_sharpen_high
    }
