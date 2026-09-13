/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ImageSearch
import com.RAZStudio.StudioRoom.core.resources.icons.IosShare
import com.RAZStudio.StudioRoom.core.resources.icons.KeyboardArrowDown
import com.RAZStudio.StudioRoom.core.resources.icons.Lock
import com.RAZStudio.StudioRoom.core.resources.icons.LockOpen
import com.RAZStudio.StudioRoom.core.resources.icons.Save
import java.io.File

// Building blocks shared between the 8-bit and (forthcoming) 16-bit RAW export screens.
// Visual identical to the originals in RawExportScreen.kt — moved here verbatim so a
// second screen can consume them without duplication.

@Composable
internal fun RawExportBottomBar(
    isSaving:      Boolean,
    hasPreview:    Boolean,
    onSave:        () -> Unit,
    onImagePicker: () -> Unit,
    /** Non-null once a save has completed this session → "Share" button appears beside Save. */
    onShare:       (() -> Unit)? = null,
) {
    BottomAppBar(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            BottomBarItem(
                label   = androidx.compose.ui.res.stringResource(R.string.raw_export_save),
                icon    = Icons.Rounded.Save,
                enabled = hasPreview && !isSaving,
                loading = isSaving,
                onClick = onSave,
            )
            if (onShare != null) {
                BottomBarItem(
                    label   = androidx.compose.ui.res.stringResource(R.string.share),
                    icon    = Icons.Rounded.IosShare,
                    enabled = !isSaving,
                    loading = false,
                    onClick = onShare,
                )
            }
            BottomBarItem(
                label   = androidx.compose.ui.res.stringResource(R.string.raw_export_image_picker),
                icon    = Icons.Rounded.ImageSearch,
                enabled = !isSaving,
                loading = false,
                onClick = onImagePicker,
            )
        }
    }
}

@Composable
private fun BottomBarItem(
    label:   String,
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled)
        MaterialTheme.colorScheme.onSurface
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Column(
        modifier            = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier    = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color       = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                modifier           = Modifier.size(24.dp),
                tint               = contentColor,
            )
        }
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

@Composable
internal fun FormatChip(
    format:  RawExportFormat,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                         else MaterialTheme.colorScheme.surfaceVariant
    val labelColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                     else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = format.label,
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color      = labelColor,
        )
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
internal fun CollapsibleSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null && !expanded) {
            trailing()
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f },
        )
    }
}

@Composable
internal fun ExportToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ExifPolicyRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick  = onClick,
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text      = value,
            style     = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier  = Modifier
                .weight(1f, fill = false)
                .padding(start = 16.dp),
        )
    }
}

/** W × H text fields with an aspect-lock toggle between them. */
@Composable
internal fun DimensionInputRow(
    dimW: String,
    dimH: String,
    origW: Int,
    origH: Int,
    isAspectLocked: Boolean,
    onDimWChange: (String) -> Unit,
    onDimHChange: (String) -> Unit,
    onToggleLock: () -> Unit,
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value         = dimW,
            onValueChange = { v ->
                val w = v.toIntOrNull()
                if (w != null && isAspectLocked && origW > 0 && origH > 0) {
                    onDimHChange(((w.toFloat() * origH / origW).toInt()).toString())
                }
                onDimWChange(v)
            },
            label          = { Text(androidx.compose.ui.res.stringResource(R.string.raw_export_width)) },
            singleLine     = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier       = Modifier.weight(1f),
        )
        IconButton(
            onClick  = onToggleLock,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector        = if (isAspectLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                contentDescription = if (isAspectLocked) "Locked" else "Unlocked",
                tint               = if (isAspectLocked) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value         = dimH,
            onValueChange = { v ->
                val h = v.toIntOrNull()
                if (h != null && isAspectLocked && origW > 0 && origH > 0) {
                    onDimWChange(((h.toFloat() * origW / origH).toInt()).toString())
                }
                onDimHChange(v)
            },
            label          = { Text(androidx.compose.ui.res.stringResource(R.string.raw_export_height)) },
            singleLine     = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier       = Modifier.weight(1f),
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000     -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000         -> "%.1f KB".format(bytes / 1_000.0)
    else                   -> "$bytes B"
}

internal fun exifOrientationLabel(orientation: Int): String = when (orientation) {
    1    -> "Normal (0°)"
    3    -> "Rotated 180°"
    6    -> "Rotated 90° CW"
    8    -> "Rotated 90° CCW"
    else -> "Normal"
}

internal fun shareFile(context: Context, file: File, mimeType: String) {
    val authority = context.getString(R.string.file_provider)
    val uri = FileProvider.getUriForFile(context, authority, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
