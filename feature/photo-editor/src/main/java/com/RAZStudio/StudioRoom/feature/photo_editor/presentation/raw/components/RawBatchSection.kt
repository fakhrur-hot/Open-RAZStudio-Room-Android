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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons as AppIcons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowDropDown
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowDropUp
import com.RAZStudio.StudioRoom.core.resources.icons.BatchPrediction
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.FolderOpen
import com.RAZStudio.StudioRoom.core.resources.icons.PlayCircle
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPresetsStorage

/**
 * Batch processing section shown on the RAW Editor Idle (start) page.
 *
 * Layout (expanded):
 *   • Folder browser row
 *   • Preset dropdown (action-card presets)
 *   • Watermark preset dropdown
 *   • Progress bar (while running)
 *   • [Clear / Cancel]  [Run Batch ▶]
 *
 * All export settings (format, quality, scale mode, resize sharpening) come
 * from the user's Settings page — identical to single-image save.
 */
@Composable
internal fun RawBatchSection(
    processor: RawBatchProcessor,
    presets: List<RawPresetsStorage.Preset>,
    selectedFolderName: String?,
    onBrowseFolder: () -> Unit,
    onRunBatch: (
        presetIndex: Int?,
        autoExposure: Boolean,
        lensCorrection: Boolean,
        aiReconstruct: Boolean,
        aiEnhance: Boolean,
        guidedFilter: Boolean,
        useCameraColorProfile: Boolean,
        formatFilter: RawBatchProcessor.FormatFilter,
        watermarkPresetName: String?,
        exifPolicy: RawBatchProcessor.ExifPolicy,
        saveIcc: Boolean,
        highlightProtection: Float,
    ) -> Unit,
    onClearBatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val batchState by processor.state.collectAsState()
    val isRunning = batchState is RawBatchProcessor.BatchProgress.Running

    val settings = rememberRawBatchSettings()

    var expanded by remember { mutableStateOf(false) }
    var showDoneDialog by remember { mutableStateOf(false) }
    var doneState by remember { mutableStateOf<RawBatchProcessor.BatchProgress.Done?>(null) }

    val prevState = remember { mutableStateOf<RawBatchProcessor.BatchProgress>(RawBatchProcessor.BatchProgress.Idle) }
    if (batchState != prevState.value) {
        if (batchState is RawBatchProcessor.BatchProgress.Done &&
            prevState.value is RawBatchProcessor.BatchProgress.Running
        ) {
            doneState = batchState as RawBatchProcessor.BatchProgress.Done
            showDoneDialog = true
        }
        prevState.value = batchState
    }

    DisposableEffect(isRunning) {
        val token = if (isRunning) {
            val activity = com.RAZStudio.StudioRoom.core.ui.utils.helper.ContextUtils.run {
                context.findActivity()
            }
            com.RAZStudio.StudioRoom.core.ui.utils.helper.PerformanceModeManager
                .get(context).acquire(activity, reason = "raw-batch-run")
        } else null
        onDispose { token?.release() }
    }

    if (showDoneDialog) {
        val done = doneState
        AlertDialog(
            onDismissRequest = { showDoneDialog = false },
            title   = { Text(stringResource(R.string.raw_batch_done_title)) },
            text    = {
                if (done != null) {
                    Column {
                        Text(stringResource(R.string.raw_batch_done_body, done.successCount, done.failCount))
                        if (done.folderWasReset) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text  = stringResource(R.string.raw_batch_folder_reset),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDoneDialog = false }) {
                    Text(stringResource(R.string.raw_batch_done_ok))
                }
            },
        )
    }

    if (!expanded) {
        com.RAZStudio.StudioRoom.core.ui.widget.image.SourceNotPickedWidget(
            modifier = modifier.fillMaxWidth(),
            onClick  = { expanded = true },
            text     = stringResource(R.string.raw_batch_processing_button),
            icon     = AppIcons.Outlined.BatchPrediction,
        )
        return
    }

    Card(
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = false }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = AppIcons.Outlined.BatchPrediction,
                    contentDescription = null,
                    modifier           = Modifier.size(20.dp),
                    tint               = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text     = stringResource(R.string.raw_batch_title),
                    style    = MaterialTheme.typography.titleSmall,
                    color    = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector        = AppIcons.Rounded.ArrowDropUp,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {

                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))

                    // ── Folder browser ────────────────────────────────────────
                    SectionLabel(stringResource(R.string.raw_batch_folder_label))
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick  = onBrowseFolder,
                        enabled  = !isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector        = AppIcons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier           = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text     = selectedFolderName ?: stringResource(R.string.raw_batch_browse),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Process formats — which photo types to run ────────────
                    SectionLabel("Process formats")
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BatchChoiceButton("RAW + JPEG", settings.formatFilter == RawBatchProcessor.FormatFilter.RAW_AND_JPEG, !isRunning, Modifier.weight(1f)) {
                            settings.setFormatFilter(RawBatchProcessor.FormatFilter.RAW_AND_JPEG)
                        }
                        BatchChoiceButton("RAW only", settings.formatFilter == RawBatchProcessor.FormatFilter.RAW_ONLY, !isRunning, Modifier.weight(1f)) {
                            settings.setFormatFilter(RawBatchProcessor.FormatFilter.RAW_ONLY)
                        }
                        BatchChoiceButton("JPEG only", settings.formatFilter == RawBatchProcessor.FormatFilter.JPEG_ONLY, !isRunning, Modifier.weight(1f)) {
                            settings.setFormatFilter(RawBatchProcessor.FormatFilter.JPEG_ONLY)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Color pipeline is locked to Camera Color per owner request —
                    // the route selector is hidden and batch always uses the
                    // camera-matched profile (RAZStudio RAW depth is not offered).
                    LaunchedEffect(Unit) {
                        if (!settings.useCameraColorProfile) settings.setUseCameraColorProfile(true)
                    }

                    // ── Preset + per-file toggles. Route radio hidden here
                    //    (rendered above, under Source). ───────────────────────
                    RawBatchSettingsPanel(
                        settings = settings,
                        presets  = presets,
                        enabled  = !isRunning,
                        showColorProfile = false,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))

                    // ── Watermark preset ──────────────────────────────────────
                    var wmRefreshKey by remember { mutableStateOf(0) }
                    val wmPresets = remember(wmRefreshKey) { listWatermarkPresets(context) }
                    var selectedWmPreset by remember { mutableStateOf<WatermarkPreset?>(null) }
                    var wmDropdownExpanded by remember { mutableStateOf(false) }

                    SectionLabel("Watermark")
                    Spacer(Modifier.height(4.dp))
                    Box {
                        OutlinedButton(
                            onClick  = { wmRefreshKey++; wmDropdownExpanded = true },
                            enabled  = !isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text     = selectedWmPreset?.name ?: "None (no watermark)",
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
                            expanded         = wmDropdownExpanded,
                            onDismissRequest = { wmDropdownExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text    = { Text("None (no watermark)") },
                                onClick = { selectedWmPreset = null; wmDropdownExpanded = false },
                            )
                            wmPresets.forEach { preset ->
                                DropdownMenuItem(
                                    text    = { Text(preset.name) },
                                    onClick = {
                                        selectedWmPreset = preset
                                        wmDropdownExpanded = false
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                deleteWatermarkPreset(context, preset.name)
                                                if (selectedWmPreset?.name == preset.name) {
                                                    selectedWmPreset = null
                                                }
                                                wmRefreshKey++
                                                wmDropdownExpanded = false
                                            },
                                        ) {
                                            Icon(
                                                imageVector        = AppIcons.Rounded.Delete,
                                                contentDescription = "Delete preset",
                                                tint               = MaterialTheme.colorScheme.error,
                                                modifier           = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // ── Progress bar ──────────────────────────────────────────
                    AnimatedVisibility(visible = isRunning) {
                        val running = batchState as? RawBatchProcessor.BatchProgress.Running
                        if (running != null) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                LinearProgressIndicator(
                                    progress = { running.currentIndex.toFloat() / running.totalCount.coerceAtLeast(1) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text  = stringResource(
                                        R.string.raw_batch_progress,
                                        running.currentIndex,
                                        running.totalCount,
                                        running.currentFileName,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Bottom bar ────────────────────────────────────────────
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (isRunning) processor.cancel()
                                onClearBatch()
                            },
                            enabled = selectedFolderName != null || isRunning,
                            colors  = ButtonDefaults.outlinedButtonColors(
                                contentColor         = MaterialTheme.colorScheme.error,
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            ),
                        ) {
                            Icon(
                                imageVector        = AppIcons.Rounded.Close,
                                contentDescription = null,
                                modifier           = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isRunning) stringResource(R.string.raw_batch_cancel)
                                else stringResource(R.string.raw_batch_clear)
                            )
                        }

                        Button(
                            onClick  = {
                                if (isRunning) {
                                    processor.cancel()
                                } else {
                                    onRunBatch(
                                        settings.selectedPresetIndex,
                                        settings.autoExposure,
                                        settings.lensCorrection,
                                        settings.aiReconstruct,
                                        settings.aiEnhance,
                                        settings.guidedFilter,
                                        settings.useCameraColorProfile,
                                        settings.formatFilter,
                                        selectedWmPreset?.name,
                                        settings.exifPolicy,
                                        settings.saveIcc,
                                        settings.highlightProtection,
                                    )
                                }
                            },
                            enabled = selectedFolderName != null,
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color       = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.raw_batch_cancel))
                            } else {
                                Icon(
                                    imageVector        = AppIcons.Rounded.PlayCircle,
                                    contentDescription = null,
                                    modifier           = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.raw_batch_run))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** One option in a horizontal single-choice selector: a filled Button when
 *  selected, an OutlinedButton otherwise. Pass Modifier.weight(1f) from the Row. */
@Composable
private fun BatchChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
    if (selected) {
        Button(
            onClick = {},
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            modifier = modifier,
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            modifier = modifier,
            content = { content() },
        )
    }
}
