/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.database.model.Keywords
import com.RAZStudio.StudioRoom.core.database.model.PhotoGridRow
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryProjectComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryWorkspaceComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarRevision

/**
 * Photo info: everything the grid row already carries (schema v3), so opening it
 * costs no file I/O. Capture time, dimensions, size, camera, lens, exposure and
 * keywords — the panel Lightroom shows in the loupe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoInfoSheet(row: PhotoGridRow, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(row.displayName, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            InfoLine(stringResource(R.string.gallery_sort_captured), formatDate(row.capturedAt))
            InfoLine(stringResource(R.string.gallery_sort_added), formatDate(row.addedAt))
            if (row.widthPx > 0 && row.heightPx > 0) {
                InfoLine("Dimensions", "${row.widthPx} × ${row.heightPx}")
            }
            if (row.sizeBytes > 0) InfoLine("File size", formatBytes(row.sizeBytes))
            InfoLine(stringResource(R.string.gallery_filter_camera), row.cameraModel)
            InfoLine(stringResource(R.string.gallery_filter_lens), row.lensModel)
            if (row.focalLengthMm > 0f) InfoLine("Focal length", "${row.focalLengthMm.toInt()} mm")
            if (row.apertureF > 0f) InfoLine("Aperture", "f/" + trimNumber(row.apertureF))
            if (row.shutterSpeed > 0f) InfoLine("Shutter", shutterLabel(row.shutterSpeed))
            if (row.iso > 0) InfoLine("ISO", row.iso.toString())
            val kw = keywordsOfRow(row)
            if (kw.isNotEmpty()) {
                InfoLine(stringResource(R.string.gallery_keywords), kw.joinToString(", "))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Keyword editor for one photo or a whole selection. Existing tags are chips you
 * can remove; the field adds one (comma-separated adds several). For a multi-photo
 * edit the chips are the tags the FIRST selected photo carries — adding applies to
 * all of them, and removal only affects photos that actually had the tag
 * (see [GalleryProjectComponent.editKeywords], which merges per photo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeywordsSheet(
    component: GalleryProjectComponent,
    photoIds: List<Long>,
    onDismiss: () -> Unit,
) {
    val cloud by component.keywordCloud.collectAsState()
    val tags = remember { mutableListOf<String>().toMutableStateList() }
    val removed = remember { mutableListOf<String>().toMutableStateList() }
    var draft by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(photoIds.firstOrNull()) {
        val first = photoIds.firstOrNull() ?: return@LaunchedEffect
        tags.clear()
        tags.addAll(component.keywordsOf(first))
        loaded = true
    }

    fun commitDraft() {
        draft.split(',').mapNotNull { Keywords.normalise(it) }.forEach { t ->
            if (t !in tags) tags.add(t)
            removed.remove(t)
        }
        draft = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.gallery_keywords), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.gallery_selected_count, photoIds.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (loaded && tags.isEmpty()) {
                Text(
                    stringResource(R.string.gallery_keywords_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                tags.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { tags.remove(tag); removed.add(tag) },
                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = { Icon(Icons.Rounded.Close, null) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.gallery_keywords_add_hint)) },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { commitDraft() }, enabled = draft.isNotBlank()) {
                        Text(stringResource(R.string.save))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Existing library tags, so the same word is reused instead of a
            // near-duplicate being typed ("sunsets" vs "sunset").
            if (cloud.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    cloud.take(12).forEach { (tag, count) ->
                        AssistChip(
                            onClick = { if (tag !in tags) { tags.add(tag); removed.remove(tag) } },
                            label = { Text("$tag ($count)", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val add = draft.split(',').mapNotNull { Keywords.normalise(it) } + tags
                    component.editKeywords(photoIds, add = add, remove = removed.toList())
                    onDismiss()
                }) { Text(stringResource(R.string.save)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Version history for one photo, read from the sidecar's revision list. Each save
 * pushes the state it replaced, so the newest entry is the edit before the current
 * one. Restoring pushes the current state first, which makes restore itself undoable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VersionsSheet(
    component: GalleryProjectComponent,
    row: PhotoGridRow,
    onDismiss: () -> Unit,
) {
    var revisions by remember(row.id) { mutableStateOf<List<SidecarRevision>?>(null) }
    LaunchedEffect(row.id) {
        revisions = runCatching { component.revisionsOf(row.id) }.getOrDefault(emptyList())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.gallery_versions), style = MaterialTheme.typography.titleMedium)
            Text(row.displayName, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))

            val list = revisions
            when {
                list == null -> Text("…", style = MaterialTheme.typography.bodySmall)
                list.isEmpty() -> Text(
                    stringResource(R.string.gallery_versions_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> list.forEachIndexed { index, rev ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                formatDate(rev.timestampEpochMs),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                if (index == 0) stringResource(R.string.gallery_version_current)
                                else "#${list.size - index}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            component.restoreRevision(row.id, index)
                            onDismiss()
                        }) { Text(stringResource(R.string.gallery_version_restore)) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Storage breakdown on the project list: one stacked bar for what THIS APP holds
 * (thumbnails, copied originals, decode caches) plus the device's free space, and
 * a one-tap cache purge. Linked sources are reported separately because deleting
 * the app would not reclaim them — they are the user's own files.
 */
@Composable
internal fun StorageCard(
    stats: GalleryWorkspaceComponent.StorageStats,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = stats.appBytes.coerceAtLeast(1L)
    val segments = listOf(
        Triple(
            stringResource(R.string.gallery_storage_thumbnails),
            stats.thumbnailBytes,
            MaterialTheme.colorScheme.primary,
        ),
        Triple(
            stringResource(R.string.gallery_storage_originals),
            stats.copiedOriginalBytes,
            MaterialTheme.colorScheme.tertiary,
        ),
        Triple(
            stringResource(R.string.gallery_storage_cache),
            stats.cacheBytes,
            MaterialTheme.colorScheme.secondary,
        ),
    )
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.gallery_storage_app_total, formatBytes(stats.appBytes)),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (stats.cacheBytes > 0) {
                    TextButton(onClick = onClearCache) {
                        Text(
                            stringResource(R.string.gallery_storage_clear_cache),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                segments.forEach { (_, bytes, color) ->
                    if (bytes > 0) {
                        Box(
                            Modifier
                                .weight(bytes.toFloat() / total)
                                .fillMaxWidth()
                                .height(10.dp)
                                .background(color),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                segments.forEach { (label, bytes, color) ->
                    Row(
                        Modifier.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$label ${formatBytes(bytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (stats.deviceTotalBytes > 0) {
                Text(
                    stringResource(
                        R.string.gallery_storage_free,
                        formatBytes(stats.deviceFreeBytes),
                        formatBytes(stats.deviceTotalBytes),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stats.linkedSourceBytes > 0) {
                Text(
                    stringResource(R.string.gallery_storage_linked, formatBytes(stats.linkedSourceBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

