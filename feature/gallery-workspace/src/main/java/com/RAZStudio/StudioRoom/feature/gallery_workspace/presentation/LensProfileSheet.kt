/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.database.model.PhotoGridRow
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryProjectComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.LensfunDatabase
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-photo camera + lens profile picker, opened from the grid's three-dots menu
 * (owner request 2026-09-07). Mirrors the editor's Lens Correction section — same
 * profile database, same autocomplete catalogues, same adapted-lens semantics —
 * but writes ONLY the decode workspace of the sidecar, so the photo's edits are
 * preserved (see SidecarOpsRepository.updateWorkspace). Prefills from the
 * profile already stored on the photo, else from the top suggestion for the
 * photo's EXIF camera/lens.
 *
 * UI text never names the underlying library (product rule).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LensProfileSheet(
    row: PhotoGridRow,
    component: GalleryProjectComponent,
    onDismiss: () -> Unit,
    /**
     * >0 when the sheet is applying to the current SELECTION rather than to
     * [row] alone; [row] is then just the prefill source (the first selected
     * photo) and the "apply to matching kit" checkbox is hidden, because the
     * selection is already an explicit list.
     */
    selectionCount: Int = 0,
) {
    val forSelection = selectionCount > 0
    val context = LocalContext.current
    var dbDir by remember { mutableStateOf<String?>(null) }
    var dbFailed by remember { mutableStateOf(false) }
    var cameras by remember { mutableStateOf<List<RawV3Engine.LensfunCamera>>(emptyList()) }
    var lenses by remember { mutableStateOf<List<RawV3Engine.LensfunLens>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<RawV3Engine.LensfunCandidate>>(emptyList()) }
    var suggestedCamera by remember { mutableStateOf("") }
    var camera by remember { mutableStateOf("") }
    var lens by remember { mutableStateOf("") }
    // Adapted-lens UI removed — always non-adapted (mount matching on).
    val adapted = false
    var focalText by remember { mutableStateOf("") }
    var applyMatching by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(row.id) {
        withContext(Dispatchers.IO) {
            val dir = LensfunDatabase.ensureMaterialized(context)
            if (dir == null) { dbFailed = true; return@withContext }
            dbDir = dir
            cameras = RawV3Engine.lensfunCameras(dir)
            lenses = RawV3Engine.lensfunLenses(dir)
            // 1) what the sidecar already carries; 2) suggestions from EXIF kit.
            val ws = component.workspaceOf(row.id)
            val kit = component.kitOf(row.id)
            if (ws != null && (ws.lensfunCameraId.isNotBlank() || ws.lensfunLensId.isNotBlank())) {
                camera = ws.lensfunCameraId; lens = ws.lensfunLensId
                focalText = if (ws.lensfunFocalOverrideMm > 0f) trimFloat(ws.lensfunFocalOverrideMm) else ""
            } else if (kit != null) {
                // Body: the DB's own model name that best matches the EXIF model.
                val camNorm = norm(kit.camera)
                suggestedCamera = cameras.firstOrNull { camNorm.contains(norm(it.model)) && it.model.isNotBlank() }?.model
                    ?: cameras.firstOrNull { norm(it.alias).isNotEmpty() && camNorm.contains(norm(it.alias)) }?.model
                    ?: ""
                camera = suggestedCamera
            }
            if (kit != null && kit.lens.isNotBlank()) {
                suggestions = runCatching {
                    RawV3Engine.lensfunRankLenses(dir, "", camera.ifBlank { kit.camera }, "", kit.lens, adapted, 3)
                }.getOrDefault(emptyList())
                if (lens.isBlank()) suggestions.firstOrNull()?.let { lens = it.model }
            }
            loaded = true
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.gallery_lens_sheet_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (forSelection) stringResource(R.string.gallery_lens_profile_selected, selectionCount)
                else row.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            when {
                dbFailed -> Text(stringResource(R.string.gallery_lens_db_missing), color = MaterialTheme.colorScheme.error)
                !loaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.gallery_lens_suggested), style = MaterialTheme.typography.labelSmall)
                }
                else -> {
                    if (suggestions.isNotEmpty()) {
                        Text(stringResource(R.string.gallery_lens_suggested), style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            suggestions.take(3).forEach { c ->
                                AssistChip(
                                    onClick = { lens = c.model },
                                    label = {
                                        Text(
                                            "${c.model} — ${LensfunDatabase.sensorFormatLabel(c.cropFactor)}",
                                            style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                        )
                                    },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                    }

                    AutocompleteField(
                        label = stringResource(R.string.gallery_lens_camera),
                        value = camera,
                        options = cameras.map { it.model },
                        onValueChange = { camera = it },
                    )
                    Spacer(Modifier.height(6.dp))
                    AutocompleteField(
                        label = stringResource(R.string.gallery_lens_lens),
                        value = lens,
                        options = lenses.map { it.model },
                        onValueChange = { lens = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    // Adapted lens checkbox hidden — always false.
                    OutlinedTextField(
                        value = focalText,
                        onValueChange = { v -> if (v.isEmpty() || v.toFloatOrNull() != null) focalText = v },
                        label = { Text(stringResource(R.string.gallery_lens_focal)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!forSelection) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = applyMatching, onCheckedChange = { applyMatching = it })
                            Text(stringResource(R.string.gallery_lens_apply_matching), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        stringResource(R.string.gallery_lens_edits_kept),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                        TextButton(onClick = {
                            dbDir?.let {
                                if (forSelection) {
                                    component.applyLensProfileToSelection(it, "", "", false, 0f)
                                } else {
                                    component.applyLensProfile(row.id, it, "", "", false, 0f, applyMatching)
                                }
                            }
                            onDismiss()
                        }) { Text(stringResource(R.string.gallery_lens_clear)) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = camera.isNotBlank() && lens.isNotBlank(),
                            onClick = {
                                dbDir?.let {
                                    val focal = focalText.toFloatOrNull() ?: 0f
                                    if (forSelection) {
                                        component.applyLensProfileToSelection(
                                            it, camera, lens, adapted, focal,
                                        )
                                    } else {
                                        component.applyLensProfile(
                                            row.id, it, camera, lens, adapted, focal, applyMatching,
                                        )
                                    }
                                }
                                onDismiss()
                            },
                        ) { Text(stringResource(R.string.gallery_lens_apply)) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** Text field + up to 8 "contains" matches as tappable chips (mirrors the editor's picker). */
@Composable
private fun AutocompleteField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
) {
    var query by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it; onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val q = norm(query)
    if (q.length >= 2 && options.none { it == query }) {
        val hits = options.asSequence().filter { norm(it).contains(q) }.distinct().take(8).toList()
        if (hits.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                hits.forEach { h ->
                    TextButton(onClick = { query = h; onValueChange(h) }) {
                        Text(h, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
private fun trimFloat(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}" else "%.1f".format(v)
