/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.ui.widget.color_picker.ColorPickerSheet
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedChip
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun VignetteTabContent(
    onSelectionChanged: (VignetteSelection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Vignette state
    var vigIntensity by rememberSaveable { mutableStateOf(0f) }
    var vigFeather by rememberSaveable { mutableStateOf(0.5f) }
    var vigRoundness by rememberSaveable { mutableStateOf(0f) }
    var vigMidpoint by rememberSaveable { mutableStateOf(0.5f) }
    var vigCenterX by rememberSaveable { mutableStateOf(0.5f) }
    var vigCenterY by rememberSaveable { mutableStateOf(0.5f) }
    var vigIncludeSubject by rememberSaveable { mutableStateOf(true) }

    fun notify() {
        val vig = VignetteSelection(
            intensity = vigIntensity, feather = vigFeather, roundness = vigRoundness,
            midpoint = vigMidpoint, centerX = vigCenterX, centerY = vigCenterY,
            includeSubject = vigIncludeSubject,
        )
        onSelectionChanged(if (vig.isEmpty) null else vig)
    }

    VignetteContent(
        intensity = vigIntensity, feather = vigFeather,
        roundness = vigRoundness, midpoint = vigMidpoint,
        centerX = vigCenterX, centerY = vigCenterY,
        includeSubject = vigIncludeSubject,
        onIntensityChange = { vigIntensity = it; notify() },
        onFeatherChange = { vigFeather = it; notify() },
        onRoundnessChange = { vigRoundness = it; notify() },
        onMidpointChange = { vigMidpoint = it; notify() },
        onCenterXChange = { vigCenterX = it; notify() },
        onCenterYChange = { vigCenterY = it; notify() },
        onIncludeSubjectChange = { vigIncludeSubject = it; notify() },
    )
}

@Composable
fun LinearGradientTabContent(
    onSelectionChanged: (LinearGradientSelection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Linear gradient global state
    var lgAngle by rememberSaveable { mutableStateOf(0f) }

    // Top side
    var lgTopIntensity by rememberSaveable { mutableStateOf(0f) }
    var lgTopLength by rememberSaveable { mutableStateOf(0.3f) }
    var lgTopFeather by rememberSaveable { mutableStateOf(0.5f) }
    var lgTopTintColor by rememberSaveable { mutableStateOf(AndroidColor.WHITE) }
    var lgTopTintLuminosity by rememberSaveable { mutableStateOf(0f) }
    var lgTopTintApplyTo by rememberSaveable { mutableStateOf(GradientApplyTo.All) }
    var lgTopBlendMode by rememberSaveable { mutableStateOf(GradientBlendMode.Solid) }

    // Bottom side
    var lgBottomIntensity by rememberSaveable { mutableStateOf(0f) }
    var lgBottomLength by rememberSaveable { mutableStateOf(0.3f) }
    var lgBottomFeather by rememberSaveable { mutableStateOf(0.5f) }
    var lgBottomTintColor by rememberSaveable { mutableStateOf(AndroidColor.WHITE) }
    var lgBottomTintLuminosity by rememberSaveable { mutableStateOf(0f) }
    var lgBottomTintApplyTo by rememberSaveable { mutableStateOf(GradientApplyTo.All) }
    var lgBottomBlendMode by rememberSaveable { mutableStateOf(GradientBlendMode.Solid) }

    // Left side
    var lgLeftIntensity by rememberSaveable { mutableStateOf(0f) }
    var lgLeftLength by rememberSaveable { mutableStateOf(0.3f) }
    var lgLeftFeather by rememberSaveable { mutableStateOf(0.5f) }
    var lgLeftTintColor by rememberSaveable { mutableStateOf(AndroidColor.WHITE) }
    var lgLeftTintLuminosity by rememberSaveable { mutableStateOf(0f) }
    var lgLeftTintApplyTo by rememberSaveable { mutableStateOf(GradientApplyTo.All) }
    var lgLeftBlendMode by rememberSaveable { mutableStateOf(GradientBlendMode.Solid) }

    // Right side
    var lgRightIntensity by rememberSaveable { mutableStateOf(0f) }
    var lgRightLength by rememberSaveable { mutableStateOf(0.3f) }
    var lgRightFeather by rememberSaveable { mutableStateOf(0.5f) }
    var lgRightTintColor by rememberSaveable { mutableStateOf(AndroidColor.WHITE) }
    var lgRightTintLuminosity by rememberSaveable { mutableStateOf(0f) }
    var lgRightTintApplyTo by rememberSaveable { mutableStateOf(GradientApplyTo.All) }
    var lgRightBlendMode by rememberSaveable { mutableStateOf(GradientBlendMode.Solid) }

    fun notify() {
        val lg = LinearGradientSelection(
            angle   = lgAngle,
            top    = LinearGradientSide(lgTopIntensity, lgTopLength, lgTopFeather, lgTopTintColor, lgTopTintLuminosity, lgTopTintApplyTo, lgTopBlendMode),
            bottom = LinearGradientSide(lgBottomIntensity, lgBottomLength, lgBottomFeather, lgBottomTintColor, lgBottomTintLuminosity, lgBottomTintApplyTo, lgBottomBlendMode),
            left   = LinearGradientSide(lgLeftIntensity, lgLeftLength, lgLeftFeather, lgLeftTintColor, lgLeftTintLuminosity, lgLeftTintApplyTo, lgLeftBlendMode),
            right  = LinearGradientSide(lgRightIntensity, lgRightLength, lgRightFeather, lgRightTintColor, lgRightTintLuminosity, lgRightTintApplyTo, lgRightBlendMode),
        )
        onSelectionChanged(if (lg.isEmpty) null else lg)
    }

    LinearGradientContent(
        angle = lgAngle,
        // Top
        topIntensity = lgTopIntensity, topLength = lgTopLength, topFeather = lgTopFeather,
        topTintColor = lgTopTintColor, topTintLuminosity = lgTopTintLuminosity, topTintApplyTo = lgTopTintApplyTo,
        topBlendMode = lgTopBlendMode,
        // Bottom
        bottomIntensity = lgBottomIntensity, bottomLength = lgBottomLength, bottomFeather = lgBottomFeather,
        bottomTintColor = lgBottomTintColor, bottomTintLuminosity = lgBottomTintLuminosity, bottomTintApplyTo = lgBottomTintApplyTo,
        bottomBlendMode = lgBottomBlendMode,
        // Left
        leftIntensity = lgLeftIntensity, leftLength = lgLeftLength, leftFeather = lgLeftFeather,
        leftTintColor = lgLeftTintColor, leftTintLuminosity = lgLeftTintLuminosity, leftTintApplyTo = lgLeftTintApplyTo,
        leftBlendMode = lgLeftBlendMode,
        // Right
        rightIntensity = lgRightIntensity, rightLength = lgRightLength, rightFeather = lgRightFeather,
        rightTintColor = lgRightTintColor, rightTintLuminosity = lgRightTintLuminosity, rightTintApplyTo = lgRightTintApplyTo,
        rightBlendMode = lgRightBlendMode,
        // Callbacks
        onAngleChange = { lgAngle = it; notify() },
        onTopIntensityChange = { lgTopIntensity = it; notify() },
        onTopLengthChange = { lgTopLength = it; notify() },
        onTopFeatherChange = { lgTopFeather = it; notify() },
        onTopTintColorChange = { lgTopTintColor = it; notify() },
        onTopTintLuminosityChange = { lgTopTintLuminosity = it; notify() },
        onTopTintApplyToChange = { lgTopTintApplyTo = it; notify() },
        onTopBlendModeChange = { lgTopBlendMode = it; notify() },
        onBottomIntensityChange = { lgBottomIntensity = it; notify() },
        onBottomLengthChange = { lgBottomLength = it; notify() },
        onBottomFeatherChange = { lgBottomFeather = it; notify() },
        onBottomTintColorChange = { lgBottomTintColor = it; notify() },
        onBottomTintLuminosityChange = { lgBottomTintLuminosity = it; notify() },
        onBottomTintApplyToChange = { lgBottomTintApplyTo = it; notify() },
        onBottomBlendModeChange = { lgBottomBlendMode = it; notify() },
        onLeftIntensityChange = { lgLeftIntensity = it; notify() },
        onLeftLengthChange = { lgLeftLength = it; notify() },
        onLeftFeatherChange = { lgLeftFeather = it; notify() },
        onLeftTintColorChange = { lgLeftTintColor = it; notify() },
        onLeftTintLuminosityChange = { lgLeftTintLuminosity = it; notify() },
        onLeftTintApplyToChange = { lgLeftTintApplyTo = it; notify() },
        onLeftBlendModeChange = { lgLeftBlendMode = it; notify() },
        onRightIntensityChange = { lgRightIntensity = it; notify() },
        onRightLengthChange = { lgRightLength = it; notify() },
        onRightFeatherChange = { lgRightFeather = it; notify() },
        onRightTintColorChange = { lgRightTintColor = it; notify() },
        onRightTintLuminosityChange = { lgRightTintLuminosity = it; notify() },
        onRightTintApplyToChange = { lgRightTintApplyTo = it; notify() },
        onRightBlendModeChange = { lgRightBlendMode = it; notify() },
    )
}

// ── Vignette sub-tab ──────────────────────────────────────────────────────────

@Composable
private fun VignetteContent(
    intensity: Float, feather: Float, roundness: Float, midpoint: Float,
    centerX: Float, centerY: Float, includeSubject: Boolean,
    onIntensityChange: (Float) -> Unit, onFeatherChange: (Float) -> Unit,
    onRoundnessChange: (Float) -> Unit, onMidpointChange: (Float) -> Unit,
    onCenterXChange: (Float) -> Unit, onCenterYChange: (Float) -> Unit,
    onIncludeSubjectChange: (Boolean) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LayerSlider("Intensity", intensity, 0f..1f, "${(intensity * 100).roundToInt()}%", onIntensityChange)
                LayerSlider("Feather", feather, 0f..1f, "${(feather * 100).roundToInt()}%", onFeatherChange)
                LayerSlider("Roundness", roundness, -1f..1f, formatBipolar(roundness), onRoundnessChange)
                LayerSlider("Midpoint", midpoint, 0f..1f, "${(midpoint * 100).roundToInt()}%", onMidpointChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    text = "Center",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LayerSlider("Center X", centerX, 0f..1f, "${(centerX * 100).roundToInt()}%", onCenterXChange)
                LayerSlider("Center Y", centerY, 0f..1f, "${(centerY * 100).roundToInt()}%", onCenterYChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Include Subject",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Checkbox(
                        checked = includeSubject,
                        onCheckedChange = onIncludeSubjectChange,
                    )
                }
            }
        }
    }
}

// ── Linear Gradient sub-tab ───────────────────────────────────────────────────

@Composable
private fun LinearGradientContent(
    angle: Float,
    topIntensity: Float, topLength: Float, topFeather: Float,
    topTintColor: Int, topTintLuminosity: Float, topTintApplyTo: GradientApplyTo,
    topBlendMode: GradientBlendMode,
    bottomIntensity: Float, bottomLength: Float, bottomFeather: Float,
    bottomTintColor: Int, bottomTintLuminosity: Float, bottomTintApplyTo: GradientApplyTo,
    bottomBlendMode: GradientBlendMode,
    leftIntensity: Float, leftLength: Float, leftFeather: Float,
    leftTintColor: Int, leftTintLuminosity: Float, leftTintApplyTo: GradientApplyTo,
    leftBlendMode: GradientBlendMode,
    rightIntensity: Float, rightLength: Float, rightFeather: Float,
    rightTintColor: Int, rightTintLuminosity: Float, rightTintApplyTo: GradientApplyTo,
    rightBlendMode: GradientBlendMode,
    onAngleChange: (Float) -> Unit,
    onTopIntensityChange: (Float) -> Unit, onTopLengthChange: (Float) -> Unit,
    onTopFeatherChange: (Float) -> Unit,
    onTopTintColorChange: (Int) -> Unit, onTopTintLuminosityChange: (Float) -> Unit,
    onTopTintApplyToChange: (GradientApplyTo) -> Unit,
    onTopBlendModeChange: (GradientBlendMode) -> Unit,
    onBottomIntensityChange: (Float) -> Unit, onBottomLengthChange: (Float) -> Unit,
    onBottomFeatherChange: (Float) -> Unit,
    onBottomTintColorChange: (Int) -> Unit, onBottomTintLuminosityChange: (Float) -> Unit,
    onBottomTintApplyToChange: (GradientApplyTo) -> Unit,
    onBottomBlendModeChange: (GradientBlendMode) -> Unit,
    onLeftIntensityChange: (Float) -> Unit, onLeftLengthChange: (Float) -> Unit,
    onLeftFeatherChange: (Float) -> Unit,
    onLeftTintColorChange: (Int) -> Unit, onLeftTintLuminosityChange: (Float) -> Unit,
    onLeftTintApplyToChange: (GradientApplyTo) -> Unit,
    onLeftBlendModeChange: (GradientBlendMode) -> Unit,
    onRightIntensityChange: (Float) -> Unit, onRightLengthChange: (Float) -> Unit,
    onRightFeatherChange: (Float) -> Unit,
    onRightTintColorChange: (Int) -> Unit, onRightTintLuminosityChange: (Float) -> Unit,
    onRightTintApplyToChange: (GradientApplyTo) -> Unit,
    onRightBlendModeChange: (GradientBlendMode) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LayerSlider(
                    label = "Angle",
                    value = angle,
                    valueRange = -180f..180f,
                    displayValue = formatAngle(angle),
                    onValueChange = onAngleChange,
                )
            }
        }
        item {
            GradientSideSection(
                title = "Top",
                intensity = topIntensity, length = topLength, feather = topFeather,
                tintColor = topTintColor, tintLuminosity = topTintLuminosity, tintApplyTo = topTintApplyTo,
                blendMode = topBlendMode,
                onIntensityChange = onTopIntensityChange, onLengthChange = onTopLengthChange,
                onFeatherChange = onTopFeatherChange,
                onTintColorChange = onTopTintColorChange,
                onTintLuminosityChange = onTopTintLuminosityChange,
                onTintApplyToChange = onTopTintApplyToChange,
                onBlendModeChange = onTopBlendModeChange,
            )
        }
        item {
            GradientSideSection(
                title = "Bottom",
                intensity = bottomIntensity, length = bottomLength, feather = bottomFeather,
                tintColor = bottomTintColor, tintLuminosity = bottomTintLuminosity, tintApplyTo = bottomTintApplyTo,
                blendMode = bottomBlendMode,
                onIntensityChange = onBottomIntensityChange, onLengthChange = onBottomLengthChange,
                onFeatherChange = onBottomFeatherChange,
                onTintColorChange = onBottomTintColorChange,
                onTintLuminosityChange = onBottomTintLuminosityChange,
                onTintApplyToChange = onBottomTintApplyToChange,
                onBlendModeChange = onBottomBlendModeChange,
            )
        }
        item {
            GradientSideSection(
                title = "Left",
                intensity = leftIntensity, length = leftLength, feather = leftFeather,
                tintColor = leftTintColor, tintLuminosity = leftTintLuminosity, tintApplyTo = leftTintApplyTo,
                blendMode = leftBlendMode,
                onIntensityChange = onLeftIntensityChange, onLengthChange = onLeftLengthChange,
                onFeatherChange = onLeftFeatherChange,
                onTintColorChange = onLeftTintColorChange,
                onTintLuminosityChange = onLeftTintLuminosityChange,
                onTintApplyToChange = onLeftTintApplyToChange,
                onBlendModeChange = onLeftBlendModeChange,
            )
        }
        item {
            GradientSideSection(
                title = "Right",
                intensity = rightIntensity, length = rightLength, feather = rightFeather,
                tintColor = rightTintColor, tintLuminosity = rightTintLuminosity, tintApplyTo = rightTintApplyTo,
                blendMode = rightBlendMode,
                onIntensityChange = onRightIntensityChange, onLengthChange = onRightLengthChange,
                onFeatherChange = onRightFeatherChange,
                onTintColorChange = onRightTintColorChange,
                onTintLuminosityChange = onRightTintLuminosityChange,
                onTintApplyToChange = onRightTintApplyToChange,
                onBlendModeChange = onRightBlendModeChange,
            )
        }
    }
}

@Composable
private fun GradientSideSection(
    title: String,
    intensity: Float, length: Float, feather: Float,
    tintColor: Int, tintLuminosity: Float, tintApplyTo: GradientApplyTo,
    blendMode: GradientBlendMode,
    onIntensityChange: (Float) -> Unit, onLengthChange: (Float) -> Unit,
    onFeatherChange: (Float) -> Unit,
    onTintColorChange: (Int) -> Unit,
    onTintLuminosityChange: (Float) -> Unit,
    onTintApplyToChange: (GradientApplyTo) -> Unit,
    onBlendModeChange: (GradientBlendMode) -> Unit,
) {
    var showColorPicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 2.dp),
        )
        LayerSlider("Intensity", intensity, 0f..1f, "${(intensity * 100).roundToInt()}%", onIntensityChange)
        LayerSlider("Length", length, 0f..1f, "${(length * 100).roundToInt()}%", onLengthChange)
        LayerSlider("Feather", feather, 0f..1f, "${(feather * 100).roundToInt()}%", onFeatherChange)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // Color swatch row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Tint Color",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(tintColor))
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable { showColorPicker = true },
            )
        }

        LayerSlider(
            label = "Luminosity",
            value = tintLuminosity,
            valueRange = 0f..1f,
            displayValue = "${(tintLuminosity * 100).roundToInt()}%",
            onValueChange = onTintLuminosityChange,
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Text(
            text = "Blend Mode",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GradientBlendMode.entries.forEach { mode ->
                EnhancedChip(
                    selected = blendMode == mode,
                    onClick = { onBlendModeChange(mode) },
                    label = { Text(mode.name) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    selectedColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Text(
            text = "Tint Affects",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            GradientApplyTo.All to "All",
            GradientApplyTo.Background to "Background",
            GradientApplyTo.Subject to "Subject",
        ).forEach { (option, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTintApplyToChange(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = tintApplyTo == option,
                    onCheckedChange = { onTintApplyToChange(option) },
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    ColorPickerSheet(
        visible = showColorPicker,
        onDismiss = { showColorPicker = false },
        color = Color(tintColor),
        onColorSelected = { color -> onTintColorChange(color.toArgb()) },
        allowAlpha = false,
    )
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun LayerSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(displayValue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, modifier = Modifier.fillMaxWidth())
    }
}

private fun formatBipolar(v: Float): String {
    val pct = (v * 100).roundToInt()
    return when {
        pct > 0 -> "+$pct"
        pct < 0 -> "$pct"
        else -> "0"
    }
}

private fun formatAngle(angle: Float): String {
    val deg = angle.roundToInt()
    val direction = when {
        angle >= -22.5f && angle < 22.5f   -> "↑ Top"
        angle >= 22.5f  && angle < 67.5f   -> "↗ Top-Right"
        angle >= 67.5f  && angle < 112.5f  -> "→ Right"
        angle >= 112.5f && angle < 157.5f  -> "↘ Bottom-Right"
        angle >= 157.5f || angle < -157.5f -> "↓ Bottom"
        angle >= -157.5f && angle < -112.5f -> "↙ Bottom-Left"
        angle >= -112.5f && angle < -67.5f  -> "← Left"
        else                               -> "↖ Top-Left"
    }
    val sign = if (deg > 0) "+" else ""
    return "$direction ${sign}${deg}°"
}
