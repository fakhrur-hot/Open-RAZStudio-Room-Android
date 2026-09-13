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

package com.RAZStudio.StudioRoom.core.ui.widget.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import com.RAZStudio.StudioRoom.core.resources.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageInfo
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Calculate
import com.RAZStudio.StudioRoom.core.resources.icons.Lock
import com.RAZStudio.StudioRoom.core.resources.icons.LockOpen
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ImageUtils
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ImageUtils.restrict
import com.RAZStudio.StudioRoom.core.ui.widget.dialogs.CalculatorDialog
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedIconButton
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.animateContentSizeNoClip
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container
import com.RAZStudio.StudioRoom.core.ui.widget.text.AutoSizeText
import com.RAZStudio.StudioRoom.core.ui.widget.text.RoundedTextField

@Composable
fun ResizeImageField(
    imageInfo: ImageInfo,
    originalSize: IntegerSize?,
    onWidthChange: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showWarning: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = ShapeDefaults.extraLarge
) {
    var aspectRatioLocked by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .container(shape = shape)
            .padding(8.dp)
            .animateContentSizeNoClip()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ResizeImageFieldImpl(
                value = imageInfo.width.takeIf { it > 0 }
                    .let { it ?: "" }
                    .toString(),
                onValueChange = { value ->
                    val maxValue = if (imageInfo.height > 0) {
                        (ImageUtils.Dimens.MAX_IMAGE_SIZE / imageInfo.height).coerceAtMost(32768)
                    } else 32768
                    val newWidth = value.restrict(maxValue).toIntOrNull() ?: 0
                    onWidthChange(newWidth)
                    if (aspectRatioLocked && newWidth > 0) {
                        val refW = (originalSize?.width ?: imageInfo.width).takeIf { it > 0 } ?: return@ResizeImageFieldImpl
                        val refH = (originalSize?.height ?: imageInfo.height).takeIf { it > 0 } ?: return@ResizeImageFieldImpl
                        val ratio = refH.toFloat() / refW.toFloat()
                        val newHeight = (newWidth * ratio).toInt().coerceAtLeast(1)
                        val maxH = (ImageUtils.Dimens.MAX_IMAGE_SIZE / newWidth.coerceAtLeast(1)).coerceAtMost(32768)
                        onHeightChange(newHeight.coerceAtMost(maxH))
                    }
                },
                shape = ShapeDefaults.smallStart,
                label = {
                    AutoSizeText(
                        stringResource(
                            R.string.width,
                            originalSize?.width?.toString() ?: ""
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = enabled
            )

            Box(contentAlignment = Alignment.Center) {
                EnhancedIconButton(
                    onClick = { aspectRatioLocked = !aspectRatioLocked },
                    modifier = Modifier.size(36.dp),
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = if (aspectRatioLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = if (aspectRatioLocked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            ResizeImageFieldImpl(
                value = imageInfo.height.takeIf { it > 0 }
                    .let { it ?: "" }
                    .toString(),
                onValueChange = { value ->
                    val maxValue = if (imageInfo.width > 0) {
                        (ImageUtils.Dimens.MAX_IMAGE_SIZE / imageInfo.width).coerceAtMost(32768)
                    } else 32768
                    val newHeight = value.restrict(maxValue).toIntOrNull() ?: 0
                    onHeightChange(newHeight)
                    if (aspectRatioLocked && newHeight > 0) {
                        val refW = (originalSize?.width ?: imageInfo.width).takeIf { it > 0 } ?: return@ResizeImageFieldImpl
                        val refH = (originalSize?.height ?: imageInfo.height).takeIf { it > 0 } ?: return@ResizeImageFieldImpl
                        val ratio = refW.toFloat() / refH.toFloat()
                        val newWidth = (newHeight * ratio).toInt().coerceAtLeast(1)
                        val maxW = (ImageUtils.Dimens.MAX_IMAGE_SIZE / newHeight.coerceAtLeast(1)).coerceAtMost(32768)
                        onWidthChange(newWidth.coerceAtMost(maxW))
                    }
                },
                shape = ShapeDefaults.smallEnd,
                label = {
                    AutoSizeText(
                        stringResource(
                            R.string.height,
                            originalSize?.height?.toString() ?: ""
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = enabled
            )
        }
        IcoSizeWarning(
            visible = imageInfo.run {
                imageFormat == ImageFormat.Ico && (width > 256 || height > 256)
            }
        )
        OOMWarning(visible = showWarning)
    }
}

@Composable
internal fun ResizeImageFieldImpl(
    modifier: Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    shape: Shape,
    enabled: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    var showCalculator by rememberSaveable {
        mutableStateOf(false)
    }

    RoundedTextField(
        value = value,
        onValueChange = onValueChange,
        shape = shape,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number
        ),
        label = label,
        endIcon = {
            AnimatedVisibility(
                visible = isFocused,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                EnhancedIconButton(
                    onClick = {
                        showCalculator = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Calculate,
                        contentDescription = null
                    )
                }
            }
        },
        modifier = modifier,
        interactionSource = interactionSource,
        enabled = enabled
    )

    CalculatorDialog(
        visible = showCalculator,
        onDismiss = { showCalculator = false },
        initialValue = value.toBigDecimalOrNull(),
        onValueChange = { onValueChange(it.toInt().toString()) }
    )
}