/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2024 RAZStudio (Fakhrurraze)
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.VolunteerActivism
import com.RAZStudio.StudioRoom.core.settings.presentation.model.isFirstLaunch
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.ui.theme.blend
import com.RAZStudio.StudioRoom.core.ui.widget.icon_shape.LocalIconShapeContainerColor
import com.RAZStudio.StudioRoom.core.ui.widget.icon_shape.LocalIconShapeContentColor
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.pulsate
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceItem
import com.RAZStudio.StudioRoom.feature.settings.presentation.components.additional.DonateSheet

@Composable
fun DonateSettingItem(
    shape: Shape = ShapeDefaults.bottom
) {
    val settingsState = LocalSettingsState.current
    var showDonateSheet by rememberSaveable { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalIconShapeContentColor provides MaterialTheme.colorScheme.onTertiaryContainer,
        LocalIconShapeContainerColor provides MaterialTheme.colorScheme.tertiaryContainer.blend(
            color = MaterialTheme.colorScheme.tertiary,
            fraction = if (settingsState.isNightMode) 0.2f else 0.1f
        )
    ) {
        PreferenceItem(
            modifier = Modifier
                .pulsate(
                    range = 0.98f..1.02f,
                    enabled = settingsState.isFirstLaunch()
                )
                .padding(horizontal = 8.dp),
            shape = shape,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            title = stringResource(R.string.donation),
            subtitle = stringResource(R.string.donation_sub),
            startIcon = Icons.Outlined.VolunteerActivism,
            onClick = {
                showDonateSheet = true
            },
            overrideIconShapeContentColor = true
        )
    }
    DonateSheet(
        visible = showDonateSheet,
        onDismiss = { showDonateSheet = false }
    )
}