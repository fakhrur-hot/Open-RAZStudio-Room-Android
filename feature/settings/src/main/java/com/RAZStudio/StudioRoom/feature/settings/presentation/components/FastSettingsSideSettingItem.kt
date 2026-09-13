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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.RAZStudio.StudioRoom.core.resources.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.RadioButtonChecked
import com.RAZStudio.StudioRoom.core.resources.icons.RadioButtonUnchecked
import com.RAZStudio.StudioRoom.core.resources.icons.SettingsTimelapse
import com.RAZStudio.StudioRoom.core.settings.domain.model.FastSettingsSide
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.ui.theme.takeColorFromScheme
import com.RAZStudio.StudioRoom.core.ui.utils.provider.LocalContainerColor
import com.RAZStudio.StudioRoom.core.ui.utils.provider.ProvideContainerDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceItemDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceRow
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceRowSwitch

@Composable
fun FastSettingsSideSettingItem(
    onValueChange: (FastSettingsSide) -> Unit,
    shape: Shape = ShapeDefaults.center,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp),
) {
    val settingsState = LocalSettingsState.current
    PreferenceRowSwitch(
        shape = shape,
        modifier = modifier,
        onClick = {
            if (it) {
                onValueChange(FastSettingsSide.CenterEnd)
            } else {
                onValueChange(FastSettingsSide.None)
            }
        },
        title = stringResource(R.string.fast_settings_side),
        subtitle = stringResource(R.string.fast_settings_side_sub),
        checked = settingsState.fastSettingsSide != FastSettingsSide.None,
        startIcon = Icons.Outlined.SettingsTimelapse,
        additionalContent = {
            AnimatedVisibility(
                visible = settingsState.fastSettingsSide != FastSettingsSide.None,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ProvideContainerDefaults(
                    shape = null,
                    color = LocalContainerColor.current
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .height(IntrinsicSize.Max)
                    ) {
                        val entries = remember {
                            listOf(FastSettingsSide.CenterStart, FastSettingsSide.CenterEnd)
                        }

                        entries.forEachIndexed { index, side ->
                            val selected = settingsState.fastSettingsSide == side
                            PreferenceRow(
                                title = when (side) {
                                    FastSettingsSide.CenterEnd -> stringResource(R.string.end)
                                    FastSettingsSide.CenterStart -> stringResource(R.string.start)
                                    FastSettingsSide.None -> ""
                                },
                                onClick = {
                                    onValueChange(side)
                                },
                                shape = ShapeDefaults.byIndex(
                                    index = index,
                                    size = entries.size,
                                    vertical = false
                                ),
                                titleFontStyle = PreferenceItemDefaults.TitleFontStyleCenteredSmall,
                                startIcon = if (selected) {
                                    Icons.Rounded.RadioButtonChecked
                                } else {
                                    Icons.Rounded.RadioButtonUnchecked
                                },
                                drawStartIconContainer = false,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                color = takeColorFromScheme {
                                    if (selected) tertiaryContainer.copy(0.5f)
                                    else surfaceContainer
                                },
                                contentColor = takeColorFromScheme {
                                    if (selected) onTertiaryContainer.copy(0.8f)
                                    else onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    )
}