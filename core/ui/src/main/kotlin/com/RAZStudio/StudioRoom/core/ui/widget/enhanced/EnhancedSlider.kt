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

package com.RAZStudio.StudioRoom.core.ui.widget.enhanced

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    colors: SliderColors? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    drawContainer: Boolean = true,
    isAnimated: Boolean = true
) {
    val realColors = colors ?: SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
        inactiveTickColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    )

    if (steps != 0) {
        var compositions by remember {
            mutableIntStateOf(0)
        }
        val haptics = LocalHapticFeedback.current
        val updatedValue by rememberUpdatedState(newValue = value)

        LaunchedEffect(updatedValue) {
            if (compositions > 0) haptics.press()
            compositions++
        }
    }

    val animatedValue = if (isAnimated) {
        animateFloatAsState(
            targetValue = value,
            animationSpec = tween(100)
        ).value
    } else {
        value
    }

    val defaultValue = remember(valueRange) {
        when {
            0f in valueRange -> 0f
            1f in valueRange -> 1f
            else -> (valueRange.start + valueRange.endInclusive) / 2f
        }
    }

    Box(
        modifier = modifier
            .pointerInput(valueRange) {
                detectTapGestures(
                    onDoubleTap = {
                        onValueChange(defaultValue)
                        onValueChangeFinished?.invoke()
                    }
                )
            }
    ) {
        Slider(
            value = animatedValue,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = realColors,
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    colors: SliderColors? = null,
    startInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    endInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    drawContainer: Boolean = true
) {
    val realColors = colors ?: SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
        inactiveTickColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    )

    if (steps != 0) {
        var compositions by remember {
            mutableIntStateOf(0)
        }
        val haptics = LocalHapticFeedback.current
        val updatedValue by rememberUpdatedState(newValue = value)

        LaunchedEffect(updatedValue) {
            if (compositions > 0) haptics.press()
            compositions++
        }
    }

    val defaultRange = remember(valueRange) {
        valueRange.start..valueRange.endInclusive
    }

    Box(
        modifier = modifier
            .pointerInput(valueRange) {
                detectTapGestures(
                    onDoubleTap = {
                        onValueChange(defaultRange)
                        onValueChangeFinished?.invoke()
                    }
                )
            }
    ) {
        RangeSlider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = realColors,
            startInteractionSource = startInteractionSource,
            endInteractionSource = endInteractionSource,
            modifier = Modifier.fillMaxWidth()
        )
    }
}