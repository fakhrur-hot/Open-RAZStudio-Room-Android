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

package com.RAZStudio.StudioRoom.core.ui.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.RAZStudio.dynamic.theme.ColorTuple
import com.RAZStudio.dynamic.theme.DynamicTheme
import com.RAZStudio.dynamic.theme.rememberDynamicThemeState
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.rememberAppColorTuple
import com.RAZStudio.StudioRoom.core.ui.utils.animation.FancyTransitionEasing
import com.RAZStudio.StudioRoom.core.ui.utils.helper.DeviceInfo
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.AutoCornersShape

@SuppressLint("NewApi")
@Composable
fun StudioRoomTheme(
    content: @Composable () -> Unit
) {
    val settingsState = LocalSettingsState.current
    val context = LocalContext.current

    DynamicTheme(
        typography = rememberTypography(settingsState.font),
        state = rememberDynamicThemeState(rememberAppColorTuple()),
        colorBlindType = settingsState.colorBlindType,
        defaultColorTuple = settingsState.appColorTuple,
        dynamicColor = settingsState.isDynamicColors,
        dynamicColorsOverride = { isNightMode ->
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.BAKLAVA && (DeviceInfo.isInfinix() || DeviceInfo.isPixel())) {
                val colors = if (isNightMode) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }

                ColorTuple(
                    primary = colors.primary,
                    secondary = colors.secondary,
                    tertiary = colors.tertiary,
                    surface = colors.surface
                )
            } else null
        },
        amoledMode = settingsState.isAmoledMode,
        isDarkTheme = settingsState.isNightMode,
        contrastLevel = settingsState.themeContrastLevel,
        style = settingsState.themeStyle,
        isInvertColors = settingsState.isInvertThemeColors,
        colorAnimationSpec = tween(
            durationMillis = 400,
            easing = FancyTransitionEasing
        ),
        content = {
            MaterialExpressiveTheme(
                motionScheme = CustomMotionScheme,
                colorScheme = modifiedColorScheme(),
                shapes = modifiedShapes(),
                content = content
            )
        }
    )
}

@Composable
fun StudioRoomThemeSurface(
    content: @Composable BoxScope.() -> Unit
) {
    StudioRoomTheme {
        val night = LocalSettingsState.current.isNightMode
        val amoled = LocalSettingsState.current.isAmoledMode
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (night) Modifier.background(PhotoLabColors.auroraBrush(amoled))
                            else Modifier.background(MaterialTheme.colorScheme.background)
                        ),
                    content = content
                )
            }
        )
    }
}

@Composable
internal fun modifiedShapes(): Shapes {
    val shapes = MaterialTheme.shapes
    val shapesType = LocalSettingsState.current.shapesType

    return remember(shapes, shapesType) {
        derivedStateOf {
            shapes.copy(
                extraSmall = AutoCornersShape(
                    topStart = shapes.extraSmall.topStart,
                    topEnd = shapes.extraSmall.topEnd,
                    bottomEnd = shapes.extraSmall.bottomEnd,
                    bottomStart = shapes.extraSmall.bottomStart,
                    shapesType = shapesType
                ),
                small = AutoCornersShape(
                    topStart = shapes.small.topStart,
                    topEnd = shapes.small.topEnd,
                    bottomEnd = shapes.small.bottomEnd,
                    bottomStart = shapes.small.bottomStart,
                    shapesType = shapesType
                ),
                medium = AutoCornersShape(
                    topStart = shapes.medium.topStart,
                    topEnd = shapes.medium.topEnd,
                    bottomEnd = shapes.medium.bottomEnd,
                    bottomStart = shapes.medium.bottomStart,
                    shapesType = shapesType
                ),
                large = AutoCornersShape(
                    topStart = shapes.large.topStart,
                    topEnd = shapes.large.topEnd,
                    bottomEnd = shapes.large.bottomEnd,
                    bottomStart = shapes.large.bottomStart,
                    shapesType = shapesType
                ),
                extraLarge = AutoCornersShape(
                    topStart = shapes.extraLarge.topStart,
                    topEnd = shapes.extraLarge.topEnd,
                    bottomEnd = shapes.extraLarge.bottomEnd,
                    bottomStart = shapes.extraLarge.bottomStart,
                    shapesType = shapesType
                ),
                largeIncreased = AutoCornersShape(
                    topStart = shapes.largeIncreased.topStart,
                    topEnd = shapes.largeIncreased.topEnd,
                    bottomEnd = shapes.largeIncreased.bottomEnd,
                    bottomStart = shapes.largeIncreased.bottomStart,
                    shapesType = shapesType
                ),
                extraLargeIncreased = AutoCornersShape(
                    topStart = shapes.extraLargeIncreased.topStart,
                    topEnd = shapes.extraLargeIncreased.topEnd,
                    bottomEnd = shapes.extraLargeIncreased.bottomEnd,
                    bottomStart = shapes.extraLargeIncreased.bottomStart,
                    shapesType = shapesType
                ),
                extraExtraLarge = AutoCornersShape(
                    topStart = shapes.extraExtraLarge.topStart,
                    topEnd = shapes.extraExtraLarge.topEnd,
                    bottomEnd = shapes.extraExtraLarge.bottomEnd,
                    bottomStart = shapes.extraExtraLarge.bottomStart,
                    shapesType = shapesType
                )
            )
        }
    }.value
}

@Composable
internal fun modifiedColorScheme(): ColorScheme {
    val scheme = MaterialTheme.colorScheme
    val night = LocalSettingsState.current.isNightMode
    val amoled = LocalSettingsState.current.isAmoledMode

    return remember(scheme, night, amoled) {
        derivedStateOf {
            scheme.photoEditSafe(night, amoled).copy(
                errorContainer = scheme.errorContainer.blend(
                    color = scheme.primary,
                    fraction = 0.15f
                )
            )
        }
    }.value
}

const val DisabledAlpha = 0.38f