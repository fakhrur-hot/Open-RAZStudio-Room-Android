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
import com.RAZStudio.StudioRoom.core.resources.icons.Fullscreen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceRowSwitch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UseFullscreenSettingsSettingItem(
    onClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    shape: Shape = ShapeDefaults.center,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp)
) {
    val settingsState = LocalSettingsState.current
    val scope = rememberCoroutineScope()

    PreferenceRowSwitch(
        modifier = modifier,
        shape = shape,
        title = stringResource(R.string.fullscreen_settings),
        subtitle = stringResource(R.string.fullscreen_settings_sub),
        checked = settingsState.useFullscreenSettings,
        onClick = {
            if (it) {
                onNavigateToSettings()
                scope.launch {
                    delay(1000)
                    onClick()
                }
            } else onClick()
        },
        startIcon = Icons.Rounded.Fullscreen
    )
}