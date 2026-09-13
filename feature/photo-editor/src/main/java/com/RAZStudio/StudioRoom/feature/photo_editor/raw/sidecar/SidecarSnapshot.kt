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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig

/**
 * v2-integration §6.1 — what one sidecar file represents: a workspace + macro pair, an
 * edit timestamp, and the bounded history stack of older revisions. Read from disk at
 * `openRawFile`; written debounced 500 ms after the last macro change.
 *
 * @property workspace          The workspace settings (bit depth, gamut, demosaic, etc.)
 *                              at the time of the latest revision.
 * @property macro              The full `UserMacro` at the time of the latest revision.
 * @property revisions          History stack — head is the latest snapshot, tail the
 *                              oldest. Capped at [HISTORY_LIMIT]. Empty when this is the
 *                              first ever revision (no history yet).
 * @property schemaVersion      Forward-compatible serialization version. Bumped only when
 *                              the XMP layout changes; older sidecars are upgraded in
 *                              [SidecarXmpSerializer] without losing data.
 * @property appVersion         App version that wrote this snapshot. Informational; not
 *                              used for any compatibility decision.
 * @property lastEditedEpochMs  Wall clock at write time.
 */
data class SidecarSnapshot(
    val workspace: WorkspaceConfig,
    val macro: UserMacro,
    val revisions: List<SidecarRevision> = emptyList(),
    /**
     * M10 — persisted committed action stack. Empty list when the
     * snapshot predates the v2 schema (the loader migrates by leaving
     * this list empty and falling back to `macro` for the live state).
     * Order: oldest action first; the editor reverses on load so the
     * topmost (most-recently-committed) card sits at the head.
     */
    val actionStack: List<SidecarActionEntry> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String = "",
    val lastEditedEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Bump when XMP layout changes incompatibly.
         *  v1 — workspace + macro + revisions (M5.5 / M7)
         *  v2 — adds [actionStack] (M10)
         *  v3 — adds [SidecarActionEntry.maskClass] (portable presets)
         *  v4 — macro JSON completed: 120 UserMacro fields that were silently
         *       DROPPED on serialize (CLAHE boosts, colour grading, ambiance,
         *       crop, subject-weighted AE, extended HSL, gradients-2, mask
         *       adjust, bloom/orton, tonemap, …) are now round-tripped. Reads
         *       of v3-and-earlier files default the new keys, same as any
         *       missing key — additive, not breaking. */
        const val CURRENT_SCHEMA_VERSION: Int = 4

        /** v2-integration §7.4 — max revisions kept in the history stack. */
        const val HISTORY_LIMIT: Int = 50
    }
}

/**
 * M10 — minimal RawAction projection that survives a save/load cycle.
 * Kept in the sidecar package (not in raw/model) so SidecarSnapshot
 * stays self-contained and the cross-package import only flows one way
 * (the editor maps RawAction <-> SidecarActionEntry at the call site).
 *
 * @property id        Stable UUID matching the in-memory RawAction. The
 *                     editor uses this to locate the on-disk mask PNG.
 * @property label     User-visible label (e.g. "Light", "Color #2").
 * @property tabIndex  Original tab the action was created from.
 * @property macro     Snapshot of the macro at commit time.
 * @property isVisible Eye-toggle state.
 * @property isLocked  Whether the user pinned this card against deletion.
 * @property maskPath  Absolute path to the painted mask PNG (filesDir),
 *                     or null when the action has no brush mask.
 */
data class SidecarActionEntry(
    val id: String,
    val label: String,
    val tabIndex: Int,
    val macro: UserMacro,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val maskPath: String? = null,
    /**
     * Auto-segmentation class for portable presets. When non-null, replay
     * re-runs the named class's segmentation on the target photo and the
     * `maskPath` (if any) is ignored. Stored as the enum name string
     * ("Sky", "Subject", ...) so the JSON is human-readable and the
     * field can drop into older schemas as a null without breaking.
     */
    val maskClass: String? = null,
    /** Ordered list of auto-segmentation class names that compose this mask
     *  card (CSV in the JSON, e.g. "Sky,Vegetation"). Empty for hand-painted
     *  brush cards. */
    val maskClasses: List<String> = emptyList(),
    /** True for AUTO EXPO cards; preset apply recomputes the macro. */
    val isAutoExposure: Boolean = false,
)

/**
 * One revision in the history stack. Carries the full macro snapshot rather than a diff —
 * decode time at restore matters more than disk size, and the file cap of ~256 KB
 * (enforced in [SidecarStore]) is the real backstop against unbounded growth.
 *
 * @property timestampEpochMs Wall clock when this revision was committed.
 * @property macro            The full [UserMacro] at this revision.
 */
data class SidecarRevision(
    val timestampEpochMs: Long,
    val macro: UserMacro,
)

/**
 * Rebuild the committed [RawAction] this entry was serialised from. Same mapping
 * as `RawEditorComponent.restoreActionStackFromSidecar` (a mask PNG missing on
 * disk drops the path so the card renders unmasked; unknown class names are
 * skipped). Used by the Gallery Workspace "Export photos" batch so a sidecar
 * bakes exactly the stack the editor would restore.
 */
fun SidecarActionEntry.toRawAction(): com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction {
    fun cls(name: String) = runCatching {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.valueOf(name)
    }.getOrNull()
    return com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction(
        id        = id,
        label     = label,
        tabIndex  = tabIndex,
        macro     = macro,
        isVisible = isVisible,
        isLocked  = isLocked,
        maskPath  = maskPath?.takeIf { java.io.File(it).exists() },
        maskClass = maskClass?.let(::cls),
        maskClasses = maskClasses.mapNotNull(::cls),
        isAutoExposure = isAutoExposure,
        isWorkspaceDefault = label == "_ai_color_enhance" ||
            label == "_smart_defaults" || label.endsWith(" Curves"),
    )
}
