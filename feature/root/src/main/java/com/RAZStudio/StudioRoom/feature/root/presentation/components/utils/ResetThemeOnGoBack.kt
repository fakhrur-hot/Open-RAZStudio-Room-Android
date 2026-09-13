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

package com.RAZStudio.StudioRoom.feature.root.presentation.components.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.RAZStudio.dynamic.theme.LocalDynamicThemeState
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.rememberAppColorTuple
import com.RAZStudio.StudioRoom.feature.root.presentation.screenLogic.RootComponent

@Composable
internal fun ResetThemeOnGoBack(
    component: RootComponent
) {
    val appColorTuple = rememberAppColorTuple()
    val themeState = LocalDynamicThemeState.current

    DisposableEffect(component, themeState, appColorTuple) {
        val observer = BackEventObserver {
            themeState.updateColorTuple(appColorTuple)
        }

        component.addBackEventsObserver(observer)

        onDispose {
            component.removeBackEventsObserver(observer)
        }
    }
}