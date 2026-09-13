/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.ui.widget.color_picker.ColorPickerSheet
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedChip
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawGradientBlendMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.SegmentTarget
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks
import kotlin.math.roundToInt

@Composable
internal fun RawGradientTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    segmentationMasks: RawSegmentationMasks?,
    // True while subject detection runs — greys the Subject/Background apply-to
    // targets until the mask is ready (All stays available).
    subjectSegBusy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var sideTab by remember { mutableIntStateOf(0) }
    val sides = listOf(
        stringResource(R.string.raw_gradient_top),
        stringResource(R.string.raw_gradient_bottom),
        stringResource(R.string.raw_gradient_left),
        stringResource(R.string.raw_gradient_right),
    )

    Column(modifier = modifier) {
        // Global angle
        RawSliderRow(
            label        = stringResource(R.string.raw_gradient_angle),
            value        = macro.gradientAngle,
            valueRange   = -180f..180f,
            onValueChange = { onMacroChange(macro.copy(gradientAngle = it)) },
            displayValue = formatAngle(macro.gradientAngle),
        )

        // Side selector
        ScrollableTabRow(
            selectedTabIndex = sideTab,
            modifier  = Modifier.fillMaxWidth().padding(top = 12.dp),
            edgePadding = 0.dp,
        ) {
            sides.forEachIndexed { index, title ->
                Tab(
                    selected = sideTab == index,
                    onClick  = { sideTab = index },
                    text     = { Text(title, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        when (sideTab) {
            0 -> GradientSideSection(
                title         = sides[0],
                // first color
                intensity     = macro.gradientTopIntensity,
                length        = macro.gradientTopLength,
                feather       = macro.gradientTopFeather,
                tintColor     = macro.gradientTopTintColor,
                tintLuminosity = macro.gradientTopTintLuminosity,
                blendMode     = macro.gradientTopBlendMode,
                applyTo       = macro.gradientTopApplyTo,
                // second color
                enable2          = macro.gradientTopEnable2,
                intensity2       = macro.gradientTopIntensity2,
                length2          = macro.gradientTopLength2,
                feather2         = macro.gradientTopFeather2,
                tintColor2       = macro.gradientTopTintColor2,
                tintLuminosity2  = macro.gradientTopTintLuminosity2,
                segmentationMasks = segmentationMasks,
                subjectSegBusy    = subjectSegBusy,
                onIntensity   = { onMacroChange(macro.copy(gradientTopIntensity    = it)) },
                onLength      = { onMacroChange(macro.copy(gradientTopLength       = it)) },
                onFeather     = { onMacroChange(macro.copy(gradientTopFeather      = it)) },
                onTintColor   = { onMacroChange(macro.copy(gradientTopTintColor    = it)) },
                onTintLuminosity = { onMacroChange(macro.copy(gradientTopTintLuminosity = it)) },
                onBlendMode   = { onMacroChange(macro.copy(gradientTopBlendMode    = it)) },
                onApplyTo     = { onMacroChange(macro.copy(gradientTopApplyTo      = it)) },
                onEnable2         = { onMacroChange(macro.copy(gradientTopEnable2 = it)) },
                onIntensity2      = { onMacroChange(macro.copy(gradientTopIntensity2 = it)) },
                onLength2         = { onMacroChange(macro.copy(gradientTopLength2 = it)) },
                onFeather2        = { onMacroChange(macro.copy(gradientTopFeather2 = it)) },
                onTintColor2      = { onMacroChange(macro.copy(gradientTopTintColor2 = it)) },
                onTintLuminosity2 = { onMacroChange(macro.copy(gradientTopTintLuminosity2 = it)) },
            )
            1 -> GradientSideSection(
                title         = sides[1],
                intensity     = macro.gradientBottomIntensity,
                length        = macro.gradientBottomLength,
                feather       = macro.gradientBottomFeather,
                tintColor     = macro.gradientBottomTintColor,
                tintLuminosity = macro.gradientBottomTintLuminosity,
                blendMode     = macro.gradientBottomBlendMode,
                applyTo       = macro.gradientBottomApplyTo,
                enable2          = macro.gradientBottomEnable2,
                intensity2       = macro.gradientBottomIntensity2,
                length2          = macro.gradientBottomLength2,
                feather2         = macro.gradientBottomFeather2,
                tintColor2       = macro.gradientBottomTintColor2,
                tintLuminosity2  = macro.gradientBottomTintLuminosity2,
                segmentationMasks = segmentationMasks,
                subjectSegBusy    = subjectSegBusy,
                onIntensity   = { onMacroChange(macro.copy(gradientBottomIntensity    = it)) },
                onLength      = { onMacroChange(macro.copy(gradientBottomLength       = it)) },
                onFeather     = { onMacroChange(macro.copy(gradientBottomFeather      = it)) },
                onTintColor   = { onMacroChange(macro.copy(gradientBottomTintColor    = it)) },
                onTintLuminosity = { onMacroChange(macro.copy(gradientBottomTintLuminosity = it)) },
                onBlendMode   = { onMacroChange(macro.copy(gradientBottomBlendMode    = it)) },
                onApplyTo     = { onMacroChange(macro.copy(gradientBottomApplyTo      = it)) },
                onEnable2         = { onMacroChange(macro.copy(gradientBottomEnable2 = it)) },
                onIntensity2      = { onMacroChange(macro.copy(gradientBottomIntensity2 = it)) },
                onLength2         = { onMacroChange(macro.copy(gradientBottomLength2 = it)) },
                onFeather2        = { onMacroChange(macro.copy(gradientBottomFeather2 = it)) },
                onTintColor2      = { onMacroChange(macro.copy(gradientBottomTintColor2 = it)) },
                onTintLuminosity2 = { onMacroChange(macro.copy(gradientBottomTintLuminosity2 = it)) },
            )
            2 -> GradientSideSection(
                title         = sides[2],
                intensity     = macro.gradientLeftIntensity,
                length        = macro.gradientLeftLength,
                feather       = macro.gradientLeftFeather,
                tintColor     = macro.gradientLeftTintColor,
                tintLuminosity = macro.gradientLeftTintLuminosity,
                blendMode     = macro.gradientLeftBlendMode,
                applyTo       = macro.gradientLeftApplyTo,
                enable2          = macro.gradientLeftEnable2,
                intensity2       = macro.gradientLeftIntensity2,
                length2          = macro.gradientLeftLength2,
                feather2         = macro.gradientLeftFeather2,
                tintColor2       = macro.gradientLeftTintColor2,
                tintLuminosity2  = macro.gradientLeftTintLuminosity2,
                segmentationMasks = segmentationMasks,
                subjectSegBusy    = subjectSegBusy,
                onIntensity   = { onMacroChange(macro.copy(gradientLeftIntensity    = it)) },
                onLength      = { onMacroChange(macro.copy(gradientLeftLength       = it)) },
                onFeather     = { onMacroChange(macro.copy(gradientLeftFeather      = it)) },
                onTintColor   = { onMacroChange(macro.copy(gradientLeftTintColor    = it)) },
                onTintLuminosity = { onMacroChange(macro.copy(gradientLeftTintLuminosity = it)) },
                onBlendMode   = { onMacroChange(macro.copy(gradientLeftBlendMode    = it)) },
                onApplyTo     = { onMacroChange(macro.copy(gradientLeftApplyTo      = it)) },
                onEnable2         = { onMacroChange(macro.copy(gradientLeftEnable2 = it)) },
                onIntensity2      = { onMacroChange(macro.copy(gradientLeftIntensity2 = it)) },
                onLength2         = { onMacroChange(macro.copy(gradientLeftLength2 = it)) },
                onFeather2        = { onMacroChange(macro.copy(gradientLeftFeather2 = it)) },
                onTintColor2      = { onMacroChange(macro.copy(gradientLeftTintColor2 = it)) },
                onTintLuminosity2 = { onMacroChange(macro.copy(gradientLeftTintLuminosity2 = it)) },
            )
            3 -> GradientSideSection(
                title         = sides[3],
                intensity     = macro.gradientRightIntensity,
                length        = macro.gradientRightLength,
                feather       = macro.gradientRightFeather,
                tintColor     = macro.gradientRightTintColor,
                tintLuminosity = macro.gradientRightTintLuminosity,
                blendMode     = macro.gradientRightBlendMode,
                applyTo       = macro.gradientRightApplyTo,
                enable2          = macro.gradientRightEnable2,
                intensity2       = macro.gradientRightIntensity2,
                length2          = macro.gradientRightLength2,
                feather2         = macro.gradientRightFeather2,
                tintColor2       = macro.gradientRightTintColor2,
                tintLuminosity2  = macro.gradientRightTintLuminosity2,
                segmentationMasks = segmentationMasks,
                subjectSegBusy    = subjectSegBusy,
                onIntensity   = { onMacroChange(macro.copy(gradientRightIntensity    = it)) },
                onLength      = { onMacroChange(macro.copy(gradientRightLength       = it)) },
                onFeather     = { onMacroChange(macro.copy(gradientRightFeather      = it)) },
                onTintColor   = { onMacroChange(macro.copy(gradientRightTintColor    = it)) },
                onTintLuminosity = { onMacroChange(macro.copy(gradientRightTintLuminosity = it)) },
                onBlendMode   = { onMacroChange(macro.copy(gradientRightBlendMode    = it)) },
                onApplyTo     = { onMacroChange(macro.copy(gradientRightApplyTo      = it)) },
                onEnable2         = { onMacroChange(macro.copy(gradientRightEnable2 = it)) },
                onIntensity2      = { onMacroChange(macro.copy(gradientRightIntensity2 = it)) },
                onLength2         = { onMacroChange(macro.copy(gradientRightLength2 = it)) },
                onFeather2        = { onMacroChange(macro.copy(gradientRightFeather2 = it)) },
                onTintColor2      = { onMacroChange(macro.copy(gradientRightTintColor2 = it)) },
                onTintLuminosity2 = { onMacroChange(macro.copy(gradientRightTintLuminosity2 = it)) },
            )
        }
        TabResetButton(RawTabId.Gradient, macro, onMacroChange)
    }
}

@Composable
private fun GradientSideSection(
    title: String,
    // first color (always shown)
    intensity: Float, length: Float, feather: Float,
    tintColor: Int, tintLuminosity: Float, blendMode: RawGradientBlendMode,
    applyTo: SegmentTarget,
    // second color (gated by `enable2` checkbox)
    enable2: Boolean,
    intensity2: Float, length2: Float, feather2: Float,
    tintColor2: Int, tintLuminosity2: Float,
    segmentationMasks: RawSegmentationMasks?,
    subjectSegBusy: Boolean = false,
    onIntensity: (Float) -> Unit, onLength: (Float) -> Unit, onFeather: (Float) -> Unit,
    onTintColor: (Int) -> Unit, onTintLuminosity: (Float) -> Unit,
    onBlendMode: (RawGradientBlendMode) -> Unit,
    onApplyTo: (SegmentTarget) -> Unit,
    onEnable2: (Boolean) -> Unit,
    onIntensity2: (Float) -> Unit, onLength2: (Float) -> Unit, onFeather2: (Float) -> Unit,
    onTintColor2: (Int) -> Unit, onTintLuminosity2: (Float) -> Unit,
) {
    var showColorPicker by rememberSaveable { mutableStateOf(false) }
    var showColorPicker2 by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
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

        // ── First color: tint swatch + luminosity hoisted to the TOP of the
        //    side section (per UX spec). Intensity/Length/Feather follow.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.raw_gradient_tint_color),
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
        RawSliderRow(
            label = stringResource(R.string.raw_gradient_luminosity),
            value = tintLuminosity, valueRange = 0f..1f,
            onValueChange = onTintLuminosity,
            displayValue = "${(tintLuminosity * 100).roundToInt()}%",
        )
        RawSliderRow(
            label = stringResource(R.string.raw_gradient_intensity),
            value = intensity, valueRange = 0f..1f,
            onValueChange = onIntensity,
            displayValue = "${(intensity * 100).roundToInt()}%",
        )
        RawSliderRow(
            label = stringResource(R.string.raw_gradient_length),
            value = length, valueRange = 0f..1f,
            onValueChange = onLength,
            displayValue = "${(length * 100).roundToInt()}%",
        )
        RawSliderRow(
            // Note: when the second color is enabled, this slider also controls
            // the transition width between color 1 and color 2 (see pipeline).
            label = stringResource(R.string.raw_gradient_feather),
            value = feather, valueRange = 0f..1f,
            onValueChange = onFeather,
            displayValue = "${(feather * 100).roundToInt()}%",
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Text(
            text = stringResource(R.string.raw_gradient_blend_mode),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RawGradientBlendMode.entries.forEach { mode ->
                EnhancedChip(
                    selected = blendMode == mode,
                    onClick  = { onBlendMode(mode) },
                    label    = { Text(mode.name, style = MaterialTheme.typography.labelSmall) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    selectedColor        = MaterialTheme.colorScheme.primaryContainer,
                    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (segmentationMasks != null) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.raw_gradient_apply_to),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    SegmentTarget.All        to stringResource(R.string.raw_segment_all),
                    SegmentTarget.Subject    to stringResource(R.string.raw_segment_subject),
                    SegmentTarget.Background to stringResource(R.string.raw_segment_background),
                ).forEach { (target, label) ->
                    // Subject/Background need the mask → grey while detecting; All is free.
                    val chipEnabled = target == SegmentTarget.All || !subjectSegBusy
                    EnhancedChip(
                        selected = applyTo == target,
                        onClick  = if (chipEnabled) { { onApplyTo(target) } } else null,
                        modifier = Modifier.alpha(if (chipEnabled) 1f else 0.38f),
                        label    = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        selectedColor         = MaterialTheme.colorScheme.primaryContainer,
                        selectedContentColor  = MaterialTheme.colorScheme.onPrimaryContainer,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (subjectSegBusy) {
                Text(
                    text = "Detecting subject… Subject/Background unlock when ready.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    ColorPickerSheet(
        visible  = showColorPicker,
        onDismiss = { showColorPicker = false },
        color    = Color(tintColor),
        onColorSelected = { color -> onTintColor(color.toArgb()) },
        allowAlpha = false,
    )
    ColorPickerSheet(
        visible  = showColorPicker2,
        onDismiss = { showColorPicker2 = false },
        color    = Color(tintColor2),
        onColorSelected = { color -> onTintColor2(color.toArgb()) },
        allowAlpha = false,
    )
}

private fun formatAngle(angle: Float): String {
    val deg = angle.roundToInt()
    val direction = when {
        angle >= -22.5f  && angle < 22.5f   -> "↑ Top"
        angle >= 22.5f   && angle < 67.5f   -> "↗ Top-Right"
        angle >= 67.5f   && angle < 112.5f  -> "→ Right"
        angle >= 112.5f  && angle < 157.5f  -> "↘ Bottom-Right"
        angle >= 157.5f  || angle < -157.5f -> "↓ Bottom"
        angle >= -157.5f && angle < -112.5f -> "↙ Bottom-Left"
        angle >= -112.5f && angle < -67.5f  -> "← Left"
        else                                -> "↖ Top-Left"
    }
    val sign = if (deg > 0) "+" else ""
    return "$direction ${sign}${deg}°"
}
