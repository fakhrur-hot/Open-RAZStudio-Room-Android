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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.RAZStudio.StudioRoom.core.database.model.PhotoGridRow
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Info
import com.RAZStudio.StudioRoom.core.resources.icons.Label
import com.RAZStudio.StudioRoom.core.resources.icons.History
import com.RAZStudio.StudioRoom.core.resources.icons.Block
import com.RAZStudio.StudioRoom.core.resources.icons.Share
import com.RAZStudio.StudioRoom.core.resources.icons.Visibility
import com.RAZStudio.StudioRoom.core.resources.icons.CameraAlt
import com.RAZStudio.StudioRoom.core.resources.icons.CheckCircle
import com.RAZStudio.StudioRoom.core.resources.icons.ContentCopy
import com.RAZStudio.StudioRoom.core.resources.icons.ContentPaste
import com.RAZStudio.StudioRoom.core.resources.icons.ContentPasteOff
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.Flag
import com.RAZStudio.StudioRoom.core.resources.icons.Folder
import com.RAZStudio.StudioRoom.core.resources.icons.FolderImage
import com.RAZStudio.StudioRoom.core.resources.icons.ImageReset
import com.RAZStudio.StudioRoom.core.resources.icons.Link
import com.RAZStudio.StudioRoom.core.resources.icons.SelectAll
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryProjectComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.EditClipboard
import com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.KitInfo
import com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.PasteCategories
import java.io.File

/**
 * Long-form context menu for one photo tile — a modal bottom sheet launched
 * from the per-tile ⋮ overflow (task 8a.1). Long-press stays with multi-select;
 * "Select" here is the NON-GESTURE route into it.
 *
 * Unreachable photos: only Remove and Relink stay enabled — every other entry
 * states why it is off instead of silently vanishing (Req 14.6a, 13.6).
 * Destructive entries are distinguished by ICON + WORDING, not colour alone
 * (Req 14.34/14.35).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoContextMenu(
    row: PhotoGridRow,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onFlag: () -> Unit,
    onReject: () -> Unit,
    onRate: (Int) -> Unit,
    onColorLabel: (Int) -> Unit,
    onCopySettings: () -> Unit,
    onPasteSettings: () -> Unit,
    onClearClipboard: () -> Unit,
    onResetEdits: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
    onRelink: () -> Unit,
    /** Opens the per-photo camera+lens profile picker (2026-09-07). */
    onLensProfile: () -> Unit = {},
    onView: () -> Unit = {},
    onShare: () -> Unit = {},
    onInfo: () -> Unit = {},
    onKeywords: () -> Unit = {},
    onVersions: () -> Unit = {},
    /** Current profile as "camera · lens", or null when none is set — shown as the entry's subtitle. */
    lensProfileLabel: String? = null,
) {
    val reachable = row.uriPermissionOk
    val hasEdits = row.hasEdits == 1
    val clipboardEntry = EditClipboard.entry
    val isClipboardSource = clipboardEntry?.photoId == row.id
    val unreachableReason = stringResource(R.string.gallery_menu_unreachable_reason)
    // Tags already on this photo, shown under the menu entry so the state is
    // visible without opening the sheet.
    val keywordSummary = com.RAZStudio.StudioRoom.core.database.model.Keywords
        .decode(row.keywords).takeIf { it.isNotEmpty() }?.joinToString(", ")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Req 14.2 — the non-gesture route into multi-select, first entry.
            MenuEntry(
                icon = { Icon(Icons.Outlined.SelectAll, null) },
                label = stringResource(R.string.gallery_action_select),
                enabled = true,
                onClick = { onSelect(); onDismiss() },
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            MenuEntry(
                icon = { Icon(Icons.Outlined.Flag, null) },
                label = stringResource(
                    if (row.flagState == GalleryProjectComponent.FLAG_FLAGGED)
                        R.string.gallery_action_unflag else R.string.gallery_action_flag
                ),
                enabled = reachable,
                disabledReason = unreachableReason,
                onClick = { onFlag(); onDismiss() },
            )
            MenuEntry(
                icon = { Icon(Icons.Rounded.Block, null) },
                label = stringResource(
                    if (row.flagState == GalleryProjectComponent.FLAG_REJECTED)
                        R.string.gallery_action_unreject else R.string.gallery_action_reject
                ),
                enabled = reachable,
                disabledReason = unreachableReason,
                onClick = { onReject(); onDismiss() },
            )

            // Rating + label as chip rows — flatter than nested menus.
            // minimumInteractiveComponentSize floors each chip's TOUCH target
            // at 48dp (Req 13.11) without inflating the 32dp visual; the
            // semantics give the ★-glyph chips a spoken label (Req 13.6).
            if (reachable) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (stars in 0..5) {
                        RatingChip(
                            stars = stars,
                            selected = row.rating == stars,
                            onClick = { onRate(stars); onDismiss() },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = row.colorLabel == 0,
                        onClick = { onColorLabel(0); onDismiss() },
                        label = { Text(stringResource(R.string.gallery_action_color_clear)) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                    for (label in 1..COLOR_LABELS.size) {
                        Spacer(Modifier.width(4.dp))
                        ColorLabelChip(
                            label = label,
                            selected = row.colorLabel == label,
                            onClick = { onColorLabel(label); onDismiss() },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ── Camera + lens profile (owner request 2026-09-07) ─────────────
            // Until now the profile could only be set INSIDE the editor and the
            // grid had no way to see or change it. Subtitle = what the sidecar
            // currently carries, so a photo with no profile says so plainly.
            MenuEntry(
                icon = { Icon(Icons.Outlined.CameraAlt, null) },
                label = stringResource(R.string.gallery_menu_lens_profile),
                subtitle = lensProfileLabel ?: stringResource(R.string.gallery_lens_none),
                enabled = reachable,
                disabledReason = if (!reachable) unreachableReason else null,
                onClick = { onLensProfile() },
            )

            // ── View / info / keywords / versions / share (2026-09-07) ──────
            MenuEntry(
                icon = { Icon(Icons.Rounded.Visibility, null) },
                label = stringResource(R.string.gallery_loupe_open),
                enabled = reachable,
                disabledReason = if (!reachable) unreachableReason else null,
                onClick = { onView() },
            )
            MenuEntry(
                icon = { Icon(Icons.Rounded.Share, null) },
                label = stringResource(R.string.share),
                enabled = reachable,
                disabledReason = if (!reachable) unreachableReason else null,
                onClick = { onShare() },
            )
            MenuEntry(
                icon = { Icon(Icons.Outlined.Info, null) },
                label = stringResource(R.string.gallery_info),
                enabled = true,
                onClick = { onInfo() },
            )
            MenuEntry(
                icon = { Icon(Icons.Rounded.Label, null) },
                label = stringResource(R.string.gallery_keywords),
                subtitle = keywordSummary,
                enabled = true,
                onClick = { onKeywords() },
            )
            MenuEntry(
                icon = { Icon(Icons.Rounded.History, null) },
                label = stringResource(R.string.gallery_versions),
                enabled = hasEdits,
                onClick = { onVersions() },
            )

            // ── Edit settings (Req 14.12–14.13) ─────────────────────────────
            MenuEntry(
                icon = { Icon(Icons.Rounded.ContentCopy, null) },
                label = stringResource(R.string.gallery_menu_copy_settings),
                enabled = reachable && hasEdits,
                disabledReason = when {
                    !reachable -> unreachableReason
                    else -> stringResource(R.string.gallery_menu_no_edits)
                },
                onClick = { onCopySettings(); onDismiss() },
            )
            // "Paste settings" is ABSENT (not disabled) until a copy exists,
            // and absent from the source photo's own menu (Req 14.13).
            if (clipboardEntry != null && !isClipboardSource) {
                MenuEntry(
                    icon = { Icon(Icons.Rounded.ContentPaste, null) },
                    label = stringResource(
                        R.string.gallery_menu_paste_settings_from, clipboardEntry.displayName,
                    ),
                    enabled = reachable,
                    disabledReason = unreachableReason,
                    onClick = { onPasteSettings() },
                )
            }
            if (clipboardEntry != null) {
                MenuEntry(
                    icon = { Icon(Icons.Rounded.ContentPasteOff, null) },
                    label = stringResource(R.string.gallery_menu_clear_clipboard),
                    enabled = true,
                    onClick = { onClearClipboard(); onDismiss() },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            MenuEntry(
                icon = { Icon(Icons.Rounded.Folder, null) },
                label = stringResource(R.string.gallery_batch_move),
                enabled = reachable,
                disabledReason = unreachableReason,
                onClick = { onMove() },
            )

            // ── Destructive block — icon + wording distinguish it, not colour
            //    alone (Req 14.34/14.35). Ellipses signal a confirmation follows.
            MenuEntry(
                icon = {
                    Icon(Icons.Rounded.ImageReset, null, tint = MaterialTheme.colorScheme.error)
                },
                label = stringResource(R.string.gallery_menu_reset_edits),
                labelColor = MaterialTheme.colorScheme.error,
                enabled = reachable && hasEdits,
                disabledReason = when {
                    !reachable -> unreachableReason
                    else -> stringResource(R.string.gallery_menu_no_edits)
                },
                onClick = { onResetEdits() },
            )
            MenuEntry(
                icon = {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                label = stringResource(R.string.gallery_menu_remove),
                labelColor = MaterialTheme.colorScheme.error,
                enabled = true,   // Remove stays available even when unreachable (Req 14.6)
                onClick = { onRemove() },
            )
            // Task 10 (Req 10.5/10.6) — the advertised way out for an
            // unreachable photo, so it stays VISIBLE (not hidden) when there is
            // nothing to relink, stating why instead.
            MenuEntry(
                icon = { Icon(Icons.Rounded.Link, null) },
                label = stringResource(R.string.gallery_menu_relink),
                enabled = !reachable,
                disabledReason = if (reachable) {
                    stringResource(R.string.gallery_menu_relink_already_linked)
                } else null,
                onClick = { onRelink(); onDismiss() },
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One sheet entry. A disabled entry stays VISIBLE with its reason as secondary
 * text and in the accessibility state description (Req 13.6, 14.36) — a
 * silently missing action reads as a bug, an explained one as a state.
 */
@Composable
private fun MenuEntry(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    disabledReason: String? = null,
    /** Always-visible second line (e.g. the current lens profile). */
    subtitle: String? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                if (!enabled) {
                    disabled()
                    disabledReason?.let { stateDescription = it }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).alpha(alpha)) { icon() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor.copy(alpha = labelColor.alpha * alpha),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!enabled && disabledReason != null) {
                Text(
                    text = disabledReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Paste category selection (Req 14.14–14.17). Tone/colour/curves/LUT/effects/
 * details/vignette default ON; lens correction defaults OFF with a cross-kit
 * warning; masks are NOT offered at all; crop is a visible-but-disabled entry
 * because our sidecar does not persist crop yet — stating that beats a silently
 * missing checkbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasteCategoriesSheet(
    entry: EditClipboard.Entry,
    targetCount: Int,
    /** Kit of the single target, when known — drives the specific warning. */
    targetKit: KitInfo?,
    onApply: (PasteCategories) -> Unit,
    onDismiss: () -> Unit,
) {
    var cats by remember { mutableStateOf(PasteCategories()) }

    val kitDiffers = targetKit == null ||
        targetKit.camera.isEmpty() || entry.cameraName.isEmpty() ||
        !targetKit.camera.equals(entry.cameraName, ignoreCase = true) ||
        !targetKit.lens.equals(entry.lensName, ignoreCase = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = pluralStringResource(R.plurals.gallery_paste_title, targetCount, targetCount),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = listOf(entry.displayName, entry.cameraName, entry.lensName)
                    .filter { it.isNotEmpty() }
                    .joinToString(stringResource(R.string.gallery_list_separator)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            CategoryRow(stringResource(R.string.gallery_cat_tone), cats.tone) {
                cats = cats.copy(tone = it)
            }
            CategoryRow(stringResource(R.string.gallery_cat_color), cats.color) {
                cats = cats.copy(color = it)
            }
            CategoryRow(stringResource(R.string.gallery_cat_curves), cats.curves) {
                cats = cats.copy(curves = it)
            }
            CategoryRow(stringResource(R.string.gallery_cat_lut), cats.lut) {
                cats = cats.copy(lut = it)
            }
            CategoryRow(stringResource(R.string.gallery_cat_effects), cats.effects) {
                cats = cats.copy(effects = it)
            }
            CategoryRow(stringResource(R.string.gallery_cat_details), cats.details) {
                cats = cats.copy(details = it)
            }
            CategoryRow(stringResource(R.string.gallery_cat_vignette), cats.vignetteGradient) {
                cats = cats.copy(vignetteGradient = it)
            }

            // Lens correction — default OFF, cross-kit warning (Req 14.15).
            CategoryRow(
                label = stringResource(R.string.gallery_cat_lens),
                checked = cats.lensCorrection,
                warning = if (kitDiffers) stringResource(R.string.gallery_paste_lens_warning)
                          else null,
            ) { cats = cats.copy(lensCorrection = it) }

            // Masks: never offered (Req 14.16). Crop: nothing to paste yet.
            Text(
                text = stringResource(R.string.gallery_paste_masks_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            CategoryRow(
                label = stringResource(R.string.gallery_cat_crop),
                checked = false,
                enabled = false,
                warning = stringResource(R.string.gallery_paste_crop_note),
            ) { }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onApply(cats) }) {
                    Text(stringResource(R.string.gallery_paste_apply))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CategoryRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    warning: String? = null,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChecked(!checked) }
            .semantics { if (!enabled) disabled() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChecked(it) }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (warning != null) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Move/copy destination picker (Req 14.24–14.26): cover thumbnail + photo
 * count per row; offers inline project creation when no destination exists.
 */
@Composable
internal fun TransferPickerDialog(
    title: String,
    targets: List<GalleryProjectComponent.TransferTarget>,
    onPick: (GalleryProjectComponent.TransferTarget) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(targets.isEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                targets.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(target) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (target.coverThumbPath != null) {
                            AsyncImage(
                                model = File(target.coverThumbPath),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Outlined.FolderImage, null) }
                        }
                        Spacer(Modifier.width(12.dp))
                        // Req 13.9 — the visual name may ellipsize, so the FULL
                        // name + count is set as the row's single description.
                        val rowDesc = pluralStringResource(
                            R.plurals.gallery_project_row_description,
                            target.photoCount, target.name, target.photoCount,
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .clearAndSetSemantics { contentDescription = rowDesc },
                        ) {
                            Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = pluralStringResource(
                                    R.plurals.gallery_project_photo_count,
                                    target.photoCount, target.photoCount,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (targets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.gallery_batch_no_targets),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.gallery_project_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(onClick = { creating = true }) {
                        Text(stringResource(R.string.gallery_project_create))
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = { onCreate(newName.trim()) },
                ) { Text(stringResource(R.string.gallery_project_create)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
