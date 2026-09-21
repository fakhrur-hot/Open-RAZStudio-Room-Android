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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.MaskNode


/**
 * A saved edit action in the RAW editor action stack.
 *
 * Actions are stored newest-first (index 0 = most recent). The [Original] sentinel
 * is always the last entry and cannot be toggled or deleted.
 *
 * [isLocked] prevents deletion. [isVisible] excludes the card from the composite render
 * without deleting it — matching the PhotoEditor eye-toggle behaviour.
 */
data class RawAction(
    val id: String = java.util.UUID.randomUUID().toString(),
    val label: String,
    val tabIndex: Int,
    val macro: UserMacro,
    val isLocked: Boolean = false,
    val isVisible: Boolean = true,
    /**
     * Absolute path to this action's brush-mask PNG, or null if this action does
     * not own a mask. When non-null, the action represents a localized edit:
     * the renderer reads the PNG via `RawMaskStorage.loadFromPath`, then applies
     * `MacroProcessor` with only this action's `macro.mask*` fields, gated by
     * the PNG's alpha. Multiple mask actions stack sequentially in the action
     * list order.
     */
    val maskPath: String? = null,
    /**
     * Segmentation class this mask card targets, when the mask was produced
     * by an auto-segmentation button rather than hand-painted. Drives
     * preset portability: replaying the preset on a different photo
     * re-runs the named segmentation class instead of loading a
     * photo-specific PNG.
     *
     *   null      → hand-painted brush mask (uses [maskPath] verbatim).
     *   non-null  → auto-mask card; replay re-derives the mask by running
     *               that class's segmentation on the target photo.
     *
     * For sidecars saved on the SAME source photo, both fields can be
     * populated — [maskPath] is the cached bitmap and [maskClass] is the
     * recipe to regenerate it. Preset export drops [maskPath] and keeps
     * only [maskClass].
     */
    val maskClass: MaskClass? = null,
    /**
     * Set of auto-segmentation classes that compose this card's mask, in
     * order of selection. Populated when the user taps one or more split-
     * buttons before committing. Replay on a different photo re-runs each
     * class's segmentation in the saved order and OR-merges into a single
     * mask. Empty for hand-painted brush cards or non-mask cards.
     *
     * Kept alongside [maskClass] (which holds the FIRST class for
     * backward compat with older sidecars that only carried one).
     */
    val maskClasses: List<MaskClass> = emptyList(),
    /**
     * M12.2c.6 — Full operational graph for this mask instance. Storing
     * the nodes (brush paths, model planes, color recipes) allows the
     * "Undo" history to be restored when switching back to this layer,
     * ensuring session-persistent non-destructive editing per layer.
     * Not serialized to XMP (too large); kept in-memory for the session.
     */
    val maskNodes: List<MaskNode> = emptyList(),
    /**
     * True when this action was produced by the Light-tab Auto button.
     * The stored [macro] is a snapshot of the values Auto computed for
     * the original photo; presets containing this action recompute the
     * macro from the target photo's own histogram via
     * [RawV3.RawAutoExposure.analyse]. Manual edits land here as false.
     */
    val isAutoExposure: Boolean = false,
    /**
     * True when this action was produced by the Tone-tab "Auto Bright" toggle.
     * The stored [macro] carries only an [UserMacro.exposure] gain that pushes
     * the image's ~99.9th-percentile luma toward white (LibRaw auto-bright
     * equivalent), applied live in Stage B via the normal macro pipeline.
     * Default off so no gain is applied unless the user enables it.
     */
    val isAutoBright: Boolean = false,
    /**
     * True when this card was created automatically at workspace-open time from
     * the user's workspace-selector choices (e.g. [_smart_defaults] scene-adaptive
     * enhance, Auto-Expose-on-Open) — NOT by a user edit inside the editor. These
     * represent the chosen workspace setup: they still fold into the macro/preview
     * and export, but are HIDDEN from the Actions tab and are not user-removable
     * there (toggled via their own workspace/Color-tab controls instead). Manual
     * edits — including manually tapping AI Expose in the Light tab — leave this
     * false so they appear in the Actions list as normal.
     */
    val isWorkspaceDefault: Boolean = false,
) {
    companion object {
        const val ORIGINAL_ID = "raw_original"

        /** Sentinel card placed permanently at the bottom of the actions list. */
        val Original = RawAction(
            id       = ORIGINAL_ID,
            label    = "Original",
            tabIndex = -1,
            macro    = UserMacro(),
        )
    }
}
