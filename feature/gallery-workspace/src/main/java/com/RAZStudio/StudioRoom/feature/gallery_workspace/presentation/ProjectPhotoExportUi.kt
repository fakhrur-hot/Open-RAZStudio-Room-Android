/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.ui.widget.dialogs.LoadingDialog
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.listWatermarkPresets
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor

/** Formats offered for a project export — the everyday ones from the Export page. */
private val EXPORT_FORMATS = listOf(
    RawExportFormat.JPG, RawExportFormat.PNG, RawExportFormat.TIFF,
    RawExportFormat.WEBP, RawExportFormat.HEIC,
)

/**
 * "Export photos" options: format (JPG default) + watermark preset. Everything
 * else (quality, scale, sharpen, save folder) comes from Settings, exactly like
 * the folder batch and the Export page.
 */
@Composable
internal fun ProjectPhotoExportDialog(
    projectName: String,
    onDismiss: () -> Unit,
    onStart: (format: RawExportFormat, watermarkPresetName: String?) -> Unit,
) {
    val context = LocalContext.current
    var format by remember { mutableStateOf(RawExportFormat.JPG) }
    val presets = remember { runCatching { listWatermarkPresets(context) }.getOrDefault(emptyList()) }
    var watermark by remember { mutableStateOf<String?>(null) }
    var wmMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_export_photos)) },
        text = {
            Column {
                Text(projectName, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.gallery_export_photos_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.gallery_export_photos_format),
                    style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    EXPORT_FORMATS.forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                if (presets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.gallery_export_photos_watermark),
                        style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(onClick = { wmMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(watermark ?: stringResource(R.string.gallery_export_photos_no_watermark),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = wmMenu, onDismissRequest = { wmMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.gallery_export_photos_no_watermark)) },
                            onClick = { watermark = null; wmMenu = false },
                        )
                        presets.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { watermark = p.name; wmMenu = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(format, watermark) }) {
                Text(stringResource(R.string.gallery_export_photos_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Blocking progress for the project export (owner rule 2026-09-07): the app's
 * modal spinner with N/M, swallowing back and taps; only its Cancel prompt can
 * stop the batch. Shown on both gallery screens while the batch runs.
 */
@Composable
internal fun ProjectPhotoExportBlocking(
    state: RawBatchProcessor.BatchProgress,
    onCancel: () -> Unit,
) {
    val running = state as? RawBatchProcessor.BatchProgress.Running
    val done = ((running?.currentIndex ?: 1) - 1).coerceAtLeast(0)
    LoadingDialog(
        visible = running != null,
        done = done,
        left = ((running?.totalCount ?: 0) - done).coerceAtLeast(0),
        onCancelLoading = onCancel,
    )
}

/**
 * Outcome card for the project export. Renders nothing while Idle or Running
 * (the blocking dialog covers Running); Done/Cancelled show the result + OK.
 */
@Composable
internal fun ProjectPhotoExportCard(
    state: RawBatchProcessor.BatchProgress,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is RawBatchProcessor.BatchProgress.Idle ||
        state is RawBatchProcessor.BatchProgress.Running) return
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            when (state) {
                is RawBatchProcessor.BatchProgress.Running -> {
                    Text(
                        stringResource(R.string.gallery_export_photos_progress,
                            state.currentIndex, state.totalCount, state.currentFileName),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (state.totalCount > 0) (state.currentIndex - 1).toFloat() / state.totalCount else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.gallery_export_photos_cancel)) }
                    }
                }
                is RawBatchProcessor.BatchProgress.Done -> Outcome(
                    stringResource(R.string.gallery_export_photos_done, state.successCount, state.failCount),
                    onDismiss,
                )
                is RawBatchProcessor.BatchProgress.Cancelled -> Outcome(
                    stringResource(R.string.gallery_export_photos_cancelled, state.successCount),
                    onDismiss,
                )
                RawBatchProcessor.BatchProgress.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Outcome(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
    }
}
