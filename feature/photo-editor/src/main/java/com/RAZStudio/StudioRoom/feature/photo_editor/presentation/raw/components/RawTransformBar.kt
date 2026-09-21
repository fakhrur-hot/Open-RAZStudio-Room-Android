/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.AutoAwesome
import com.RAZStudio.StudioRoom.core.resources.icons.CropSmall
import com.RAZStudio.StudioRoom.core.resources.icons.FilterFrames
import com.RAZStudio.StudioRoom.core.resources.icons.Healing
import com.RAZStudio.StudioRoom.core.resources.icons.RestartAlt
import com.RAZStudio.StudioRoom.core.resources.icons.Tune
import com.RAZStudio.StudioRoom.core.resources.icons.Watermark
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedIconButton
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container

/**
 * Online AI Editing (✨) button visibility. HIDDEN 2026-09-06 per owner
 * request, together with the Atmosphere sheet it opened (removed from
 * RawExportScreen). Kept as a flag — rather than deleting the button and its
 * [RawExportTransformBar.onOnlineAiEdit] wiring — so it can be restored by
 * flipping this to true.
 */
private const val SHOW_ONLINE_AI_EDIT_BUTTON = false

@Composable
internal fun RawExportTransformBar(
    hasAiDenoise: Boolean,
    hasWatermark: Boolean,
    hasCrop: Boolean,
    hasHeal: Boolean,
    hasBorder: Boolean,
    hasTransformChanges: Boolean,
    onCrop: () -> Unit,
    onHeal: () -> Unit,
    onBorder: () -> Unit,
    onWatermark: () -> Unit,
    onAiDenoise: () -> Unit,
    onOnlineAiEdit: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.container(shape = ShapeDefaults.extraLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EnhancedIconButton(
            containerColor = if (hasCrop) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (hasCrop) MaterialTheme.colorScheme.onTertiaryContainer
                           else MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onCrop,
        ) {
            Icon(
                imageVector = Icons.Rounded.CropSmall,
                contentDescription = "Crop",
                modifier = Modifier.height(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        EnhancedIconButton(
            containerColor = if (hasAiDenoise) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (hasAiDenoise) MaterialTheme.colorScheme.onTertiaryContainer
                           else MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onAiDenoise,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = "AI Denoise",
                modifier = Modifier.height(20.dp),
            )
        }
        EnhancedIconButton(
            containerColor = if (hasHeal) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (hasHeal) MaterialTheme.colorScheme.onTertiaryContainer
                           else MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onHeal,
        ) {
            Icon(
                imageVector = Icons.Rounded.Healing,
                contentDescription = "Heal",
                modifier = Modifier.height(20.dp),
            )
        }
        // Border / frame — expands the canvas outward, never crops the photo.
        EnhancedIconButton(
            containerColor = if (hasBorder) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (hasBorder) MaterialTheme.colorScheme.onTertiaryContainer
                           else MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onBorder,
        ) {
            Icon(
                imageVector = Icons.Outlined.FilterFrames,
                contentDescription = "Border",
                modifier = Modifier.height(20.dp),
            )
        }
        // Half-width padding spacer before watermark
        Spacer(Modifier.width(4.dp))
        EnhancedIconButton(
            containerColor = if (hasWatermark)
                MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (hasWatermark)
                MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onWatermark,
        ) {
            Icon(
                imageVector = Icons.Outlined.Watermark,
                contentDescription = "Watermark",
                modifier = Modifier.height(20.dp),
            )
        }
        // Online AI Editing — plain action button, no active-state highlight
        // or cloud badge (per explicit product decision: skip the
        // Cloud_Indicator_Badge, unlike Crop/Heal/Watermark's toggle style).
        // Hidden — see SHOW_ONLINE_AI_EDIT_BUTTON.
        if (SHOW_ONLINE_AI_EDIT_BUTTON) {
            Spacer(Modifier.width(4.dp))
            EnhancedIconButton(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onOnlineAiEdit,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = stringResource(R.string.raw_online_ai_editing_button_desc),
                    modifier = Modifier.height(20.dp),
                )
            }
        }
        // Reset button — red rotating arrow, only shown when there are changes
        if (hasTransformChanges) {
            Spacer(Modifier.width(4.dp))
            EnhancedIconButton(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onReset,
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = "Reset",
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.height(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun RawTransformBarDetailsButton(onClick: () -> Unit) {
    EnhancedIconButton(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        onClick = onClick,
    ) {
        Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = "Details",
            modifier = Modifier.height(20.dp),
        )
    }
}
