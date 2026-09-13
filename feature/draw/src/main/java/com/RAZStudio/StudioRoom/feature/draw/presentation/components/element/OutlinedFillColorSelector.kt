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

package com.RAZStudio.StudioRoom.feature.draw.presentation.components.element

import androidx.compose.foundation.layout.fillMaxWidth
import com.RAZStudio.StudioRoom.core.resources.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.FormatColorFill
import com.RAZStudio.StudioRoom.core.ui.widget.controls.selection.ColorRowSelector
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container

@Composable
fun OutlinedFillColorSelector(
    value: Color?,
    onValueChange: (Color?) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = ShapeDefaults.default,
    containerColor: Color = Color.Unspecified
) {
    ColorRowSelector(
        value = value,
        onValueChange = onValueChange,
        onNullClick = { onValueChange(null) },
        title = stringResource(R.string.fill_color),
        icon = Icons.Rounded.FormatColorFill,
        allowAlpha = true,
        modifier = modifier
            .fillMaxWidth()
            .container(
                color = containerColor,
                shape = shape
            )
    )
}