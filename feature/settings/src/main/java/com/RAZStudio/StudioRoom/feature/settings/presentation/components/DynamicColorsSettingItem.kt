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

package com.RAZStudio.StudioRoom.feature.settings.presentation.components

import androidx.compose.foundation.layout.padding
import com.RAZStudio.StudioRoom.core.resources.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.FormatColorFill
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceRowSwitch

@Composable
fun DynamicColorsSettingItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp),
    shape: Shape = ShapeDefaults.center
) {
    val settingsState = LocalSettingsState.current
    PreferenceRowSwitch(
        modifier = modifier,
        shape = shape,
        startIcon = Icons.Rounded.FormatColorFill,
        title = stringResource(R.string.dynamic_colors),
        subtitle = stringResource(R.string.dynamic_colors_sub),
        checked = settingsState.isDynamicColors,
        onClick = {
            onClick()
        }
    )
}