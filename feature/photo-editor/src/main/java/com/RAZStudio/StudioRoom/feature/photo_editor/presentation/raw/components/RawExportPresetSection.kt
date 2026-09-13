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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons as AppIcons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowDropDown
import com.RAZStudio.StudioRoom.core.resources.icons.Save
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedIconButton
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPresetsStorage

/**
 * Simplified Saved-Presets selector for the RAW Export page.
 *
 * Unlike the Actions tab (which lists every adjustment card), this is just a
 * dropdown of saved presets plus a Save button. When the current edit doesn't
 * match a saved preset it shows "Current (unsaved)" as the selection — which
 * can be saved into a new preset exactly like the Actions tab does.
 *
 * @param selectedIndex index into [presets] of the active preset, or null for
 *        "Current (unsaved)".
 */
@Composable
internal fun RawExportPresetSection(
    presets: List<RawPresetsStorage.Preset>,
    selectedIndex: Int?,
    canSave: Boolean,
    onSelectPreset: (Int) -> Unit,
    onSavePreset: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded        by remember { mutableStateOf(false) }
    var showSaveDialog  by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var saveError       by remember { mutableStateOf(false) }

    val selectedLabel = selectedIndex
        ?.let { presets.getOrNull(it)?.name }
        ?: stringResource(R.string.raw_preset_current_unsaved)

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false; presetNameInput = ""; saveError = false },
            title   = { Text(stringResource(R.string.raw_save_preset)) },
            text    = {
                Column {
                    OutlinedTextField(
                        value         = presetNameInput,
                        onValueChange = { presetNameInput = it; saveError = false },
                        label         = { Text(stringResource(R.string.raw_preset_name_hint)) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        isError       = saveError,
                    )
                    if (saveError) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = stringResource(R.string.raw_presets_full),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = onSavePreset(presetNameInput.trim().ifEmpty { "Preset" })
                    if (saved) {
                        showSaveDialog = false; presetNameInput = ""; saveError = false
                    } else saveError = true
                }) { Text(stringResource(R.string.raw_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false; presetNameInput = ""; saveError = false
                }) { Text(stringResource(R.string.raw_cancel)) }
            },
        )
    }

    Column(modifier = modifier) {
        Text(
            text  = stringResource(R.string.raw_presets_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick  = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text     = selectedLabel,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector        = AppIcons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        modifier           = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded         = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    // "Current (unsaved)" — shown so the active state is explicit.
                    DropdownMenuItem(
                        text    = { Text(stringResource(R.string.raw_preset_current_unsaved)) },
                        onClick = { expanded = false },
                    )
                    presets.forEachIndexed { index, preset ->
                        DropdownMenuItem(
                            text    = { Text(preset.name) },
                            onClick = { onSelectPreset(index); expanded = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // Save current edit as a new preset — diskette, same as Actions tab.
            EnhancedIconButton(
                onClick = { presetNameInput = ""; saveError = false; showSaveDialog = true },
                enabled = canSave,
            ) {
                Icon(
                    imageVector        = AppIcons.Rounded.Save,
                    contentDescription = stringResource(R.string.raw_save_preset),
                    modifier           = Modifier.size(22.dp),
                    tint = if (canSave) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }
        }
    }
}
