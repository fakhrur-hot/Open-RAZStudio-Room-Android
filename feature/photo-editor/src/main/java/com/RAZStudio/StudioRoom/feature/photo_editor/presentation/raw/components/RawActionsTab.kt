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

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowDropDown
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowDropUp
import com.RAZStudio.StudioRoom.core.resources.icons.ContentCopy
import com.RAZStudio.StudioRoom.core.resources.icons.ContentPaste
import com.RAZStudio.StudioRoom.core.resources.icons.ContentPasteGo
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.FileExport
import com.RAZStudio.StudioRoom.core.resources.icons.FileImport
import com.RAZStudio.StudioRoom.core.resources.icons.ImageReset
import com.RAZStudio.StudioRoom.core.resources.icons.Lock
import com.RAZStudio.StudioRoom.core.resources.icons.LockOpen
import com.RAZStudio.StudioRoom.core.resources.icons.Save
import com.RAZStudio.StudioRoom.core.resources.icons.Visibility
import com.RAZStudio.StudioRoom.core.resources.icons.VisibilityOff
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedIconButton
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPresetsStorage
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.core.resources.Icons as AppIcons

/** Which stack-replacing paste is awaiting user confirmation. */
private enum class PendingReplace { Paste, Previous }

/**
 * Displays the action stack: newest at top, oldest at bottom, Original sentinel last.
 * The underlying [actions] list is stored oldest-first (pipeline order); we reverse
 * for display so the most recently applied adjustment always appears at the top.
 * Header: diskette Save button | label | Import | Export
 * "Saved Actions" collapsible section above the current action cards.
 */
@Composable
internal fun RawActionsTab(
    actions: List<RawAction>,
    presets: List<RawPresetsStorage.Preset>,
    onLoad: (RawAction) -> Unit,
    onEyeToggle: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onDelete: (id: String, keepStorage: Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    /** Copy Settings — snapshot the current stack to the in-memory clipboard. */
    onCopySettings: () -> Unit = {},
    /** Paste Settings — replace this photo's stack with the clipboard. */
    onPasteSettings: () -> Unit = {},
    canPasteSettings: Boolean = false,
    /** Apply from previous — replace with the LAST OTHER photo's stack. */
    onApplyPrevious: () -> Unit = {},
    canApplyPrevious: Boolean = false,
    onSavePreset: (String) -> Boolean,
    onLoadPreset: (Int) -> Unit,
    onDeletePreset: (Int) -> Unit,
    onExportDebugMap: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Pipeline stores actions oldest-first; reverse so newest appears at the top of the UI.
    // Shown = user-created edits only. Excluded:
    //   • the Original sentinel;
    //   • workspace-default cards (isWorkspaceDefault) — auto-created at open from the
    //     workspace-selector choices (_smart_defaults, Auto-Expose-on-Open, film-profile
    //     curves). Those are the user's workspace setup, not editor edits, and must not be
    //     removable here; they're toggled via their own workspace/Color-tab controls.
    // Manually tapping AI Expose in the Light tab leaves isWorkspaceDefault=false, so that
    // card still appears (it carries isAutoExposure only for preset per-photo recompute).
    val userActions = actions.filter {
        it.id != RawAction.ORIGINAL_ID && !it.isWorkspaceDefault
    }.reversed()
    val hasOriginal = actions.any { it.id == RawAction.ORIGINAL_ID }

    var showSaveDialog   by remember { mutableStateOf(false) }
    var presetNameInput  by remember { mutableStateOf("") }
    var saveError        by remember { mutableStateOf(false) }
    var savedActionsOpen by remember { mutableStateOf(true) }
    // Index of the preset pending deletion (drives the confirm dialog), or null.
    var presetToDelete   by remember { mutableStateOf<Int?>(null) }
    // Revert-to-original confirm dialog (the bottom Original card).
    var showRevertDialog by remember { mutableStateOf(false) }
    // Toasts explain what each icon-only header button does when tapped.
    val ctx = LocalContext.current
    fun explain(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    // Paste / Apply-from-previous REPLACE the whole stack; when the photo
    // already carries edits, confirm before overwriting them.
    var pendingReplace   by remember { mutableStateOf<PendingReplace?>(null) }

    pendingReplace?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingReplace = null },
            title = { Text(stringResource(R.string.raw_paste_settings)) },
            text  = { Text(stringResource(R.string.raw_paste_replace_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    when (pending) {
                        PendingReplace.Paste    -> onPasteSettings()
                        PendingReplace.Previous -> onApplyPrevious()
                    }
                    pendingReplace = null
                }) { Text(stringResource(R.string.raw_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReplace = null }) {
                    Text(stringResource(R.string.raw_cancel))
                }
            },
        )
    }

    // ── Delete preset confirmation ─────────────────────────────────────────────
    presetToDelete?.let { idx ->
        val name = presets.getOrNull(idx)?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title   = { Text(stringResource(R.string.raw_delete_preset_title)) },
            text    = { Text(stringResource(R.string.raw_delete_preset_message, name)) },
            confirmButton = {
                TextButton(onClick = { onDeletePreset(idx); presetToDelete = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text(stringResource(R.string.raw_cancel))
                }
            },
        )
    }

    // ── Save preset dialog ────────────────────────────────────────────────────
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
                    } else {
                        saveError = true
                    }
                }) { Text(stringResource(R.string.raw_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false; presetNameInput = ""; saveError = false
                }) { Text(stringResource(R.string.raw_cancel)) }
            },
        )
    }

    // ── Revert-to-original confirm (the bottom Original card) ───────────────────
    if (showRevertDialog) {
        AlertDialog(
            onDismissRequest = { showRevertDialog = false },
            title   = { Text("Revert to original?") },
            text    = { Text("This removes all your edits and restores the photo to how it was opened. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    // Remove every user edit → back to the Original sentinel.
                    userActions.forEach { onDelete(it.id, false) }
                    explain("Reverted to original")
                    showRevertDialog = false
                }) { Text("Revert") }
            },
            dismissButton = {
                TextButton(onClick = { showRevertDialog = false }) {
                    Text(stringResource(R.string.raw_cancel))
                }
            },
        )
    }

    Column(modifier = modifier) {

        // ── Header row: Actions  [Import] [Export] [Save] ──────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = stringResource(R.string.raw_tab_actions),
                style    = MaterialTheme.typography.labelMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )

            // ── Copy / Paste / Apply-from-previous (Lightroom trio) ─────────
            EnhancedIconButton(
                onClick = { explain("Copy: copies all this photo's edits to the clipboard"); onCopySettings() },
                enabled = userActions.isNotEmpty(),
            ) {
                Icon(
                    imageVector        = AppIcons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.raw_copy_settings),
                    modifier           = Modifier.size(22.dp),
                    tint = if (userActions.isEmpty())
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            EnhancedIconButton(
                onClick = {
                    explain("Paste: applies the copied edits to this photo")
                    // Pasting REPLACES the stack — confirm when edits exist.
                    if (userActions.isEmpty()) onPasteSettings()
                    else pendingReplace = PendingReplace.Paste
                },
                enabled = canPasteSettings,
            ) {
                Icon(
                    imageVector        = AppIcons.Rounded.ContentPaste,
                    contentDescription = stringResource(R.string.raw_paste_settings),
                    modifier           = Modifier.size(22.dp),
                    tint = if (!canPasteSettings)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            EnhancedIconButton(
                onClick = {
                    explain("Apply previous: reuses the edits from the last photo you edited")
                    if (userActions.isEmpty()) onApplyPrevious()
                    else pendingReplace = PendingReplace.Previous
                },
                enabled = canApplyPrevious,
            ) {
                Icon(
                    imageVector        = AppIcons.Rounded.ContentPasteGo,
                    contentDescription = stringResource(R.string.raw_apply_previous),
                    modifier           = Modifier.size(22.dp),
                    tint = if (!canApplyPrevious)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            EnhancedIconButton(onClick = { explain("Import: load edits from a saved file"); onImport() }) {
                Icon(
                    imageVector        = AppIcons.Rounded.FileImport,
                    contentDescription = stringResource(R.string.raw_import_actions),
                    modifier           = Modifier.size(22.dp),
                )
            }

            EnhancedIconButton(
                onClick = { explain("Export: save these edits to a file you can share/reuse"); onExport() },
                enabled = userActions.isNotEmpty(),
            ) {
                Icon(
                    imageVector = AppIcons.Rounded.FileExport,
                    contentDescription = stringResource(R.string.raw_export_actions),
                    modifier    = Modifier.size(22.dp),
                    tint = if (userActions.isEmpty())
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Save as preset — diskette icon, highlighted in primary color
            EnhancedIconButton(
                onClick  = {
                    explain("Save preset: store these edits as a reusable preset")
                    presetNameInput = ""; saveError = false; showSaveDialog = true
                },
                enabled  = userActions.isNotEmpty(),
            ) {
                Icon(
                    imageVector        = AppIcons.Rounded.Save,
                    contentDescription = stringResource(R.string.raw_save_preset),
                    modifier           = Modifier.size(22.dp),
                    tint = if (userActions.isEmpty())
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.primary,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

        // ── Saved Actions section (collapsible dropdown) ──────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { savedActionsOpen = !savedActionsOpen }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = stringResource(R.string.raw_presets_title),
                style    = MaterialTheme.typography.labelMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (presets.isNotEmpty()) {
                Text(
                    text  = "${presets.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                imageVector = if (savedActionsOpen) AppIcons.Rounded.ArrowDropUp
                              else AppIcons.Rounded.ArrowDropDown,
                contentDescription = null,
                modifier    = Modifier.size(18.dp),
                tint        = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = savedActionsOpen,
            enter   = expandVertically(),
            exit    = shrinkVertically(),
        ) {
            if (presets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = stringResource(R.string.raw_actions_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    presets.forEachIndexed { index, preset ->
                        Card(
                            onClick = { onLoadPreset(index) },
                            colors  = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text     = preset.name,
                                    style    = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Red delete button — confirm before deleting.
                                EnhancedIconButton(onClick = { presetToDelete = index }) {
                                    Icon(
                                        imageVector        = AppIcons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        modifier           = Modifier.size(18.dp),
                                        tint               = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))

        // ── Current action cards ──────────────────────────────────────────────
        if (userActions.isEmpty() && !hasOriginal) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = stringResource(R.string.raw_actions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                userActions.forEach { action ->
                    ActionCard(
                        action       = action,
                        onLoad       = { onLoad(action) },
                        onEyeToggle  = { onEyeToggle(action.id) },
                        onToggleLock = { onToggleLock(action.id) },
                        onDelete     = { onDelete(action.id, false) },
                    )
                }
                if (hasOriginal) OriginalCard(
                    enabled = userActions.isNotEmpty(),
                    onClick = { showRevertDialog = true },
                )
            }
        }

        // ─── Debug Exports ──────────────────────────────────────────────────
        if (com.RAZStudio.StudioRoom.feature.photo_editor.BuildConfig.DEBUG) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Text(
                text     = "Native Debug Maps (Export PNG to /sdcard/Documents/SR_Debug/)",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                listOf(
                    "Blend" to 0, "Edge" to 1, "Texture" to 2, "Noise" to 3, "Highlights" to 4
                ).forEach { (name, type) ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { onExportDebugMap(type) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    action: RawAction,
    onLoad: () -> Unit,
    onEyeToggle: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onLoad,
        colors  = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (action.isVisible) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EnhancedIconButton(onClick = onEyeToggle) {
                Icon(
                    imageVector = if (action.isVisible) AppIcons.Rounded.Visibility
                                  else AppIcons.Rounded.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }

            Text(
                text     = action.label,
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            EnhancedIconButton(onClick = onToggleLock) {
                Icon(
                    imageVector = if (action.isLocked) AppIcons.Rounded.Lock
                                  else AppIcons.Rounded.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (action.isLocked)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            EnhancedIconButton(onClick = onDelete, enabled = !action.isLocked) {
                Icon(
                    imageVector = AppIcons.Rounded.Delete,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(20.dp),
                    tint = if (action.isLocked)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun OriginalCard(enabled: Boolean = true, onClick: () -> Unit = {}) {
    // The Original card is the "revert everything" reset. It used to render
    // greyed and inert; it's now a real, tappable button (owner request).
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val a = if (enabled) 1f else 0.5f
            Icon(
                imageVector        = AppIcons.Rounded.ImageReset,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = a),
                modifier           = Modifier.size(18.dp),
            )
            Text(
                text     = stringResource(R.string.original),
                style    = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.7f),
            )
        }
    }
}
