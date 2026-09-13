/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.preset.RazPresetEntry

/**
 * Dialog to name and confirm a preset export.
 *
 * @param initialName  Suggested default (e.g. source filename without extension).
 * @param droppedCount Number of cards that will be dropped during export
 *                     (typically hand-painted brush masks that can't transfer
 *                     to a different photo). Shown to the user as a warning.
 */
@Composable
fun PresetExportDialog(
    initialName: String,
    droppedCount: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName.ifBlank { "Untitled" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset") },
        text = {
            Column {
                Text(
                    text = "Saves the current card stack as a portable preset. " +
                        "Mask cards re-segment on the target photo.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (droppedCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "$droppedCount hand-painted brush card" +
                            (if (droppedCount == 1) "" else "s") +
                            " will be skipped — those can't transfer between photos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim().ifBlank { "Untitled" }) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Preset picker — lists presets in the app-private store, newest first.
 * Apply runs the selected preset's cards on the current editor; Delete
 * removes a preset from disk (with a confirmation).
 */
@Composable
fun PresetPickerDialog(
    entries: List<RazPresetEntry>,
    onApply: (RazPresetEntry) -> Unit,
    onDelete: (RazPresetEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply preset") },
        text = {
            if (entries.isEmpty()) {
                Text(
                    text = "No presets saved yet. Use \"Save preset\" first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(entries) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onApply(entry) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${entry.sizeBytes / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDelete(entry) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                                .copy(alpha = 0.3f),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
