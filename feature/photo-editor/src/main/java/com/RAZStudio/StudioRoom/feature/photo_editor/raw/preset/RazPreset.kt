/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.preset

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

/**
 * Portable preset = ordered list of [RawAction]s stripped of source-photo
 * specifics. Applying a preset to a different photo:
 *   • Re-runs each card's edits in order, using the saved [UserMacro] values.
 *   • For mask cards (Sky / Subject / Hair / ...), re-runs that segmentation
 *     class on the new photo instead of trying to transplant a bitmap.
 *
 * What is NOT carried by a preset (intentionally per design):
 *   • [WorkspaceConfig] — workspace selector / LibRaw output color /
 *     demosaic / highlight recovery is per-photo, not per-preset. The
 *     user's workspace defaults pick up on import.
 *   • Hand-painted brush masks — they can't transfer to a different image
 *     geometry. Brush cards are dropped during export with no fallback
 *     (the user can repaint or use a segmentation class).
 *   • Crop / rotation / heal coordinates — image-relative.
 *   • EXIF / file paths.
 *
 * The on-disk format is JSON (see [RazPresetSerializer]). Files live in
 * `<filesDir>/presets/<slug>.razpreset`.
 */
data class RazPreset(
    val name: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String = "",
    val createdEpochMs: Long = System.currentTimeMillis(),
    val cards: List<PresetCard> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val FILE_EXTENSION = "razpreset"
        const val PRESET_DIR = "presets"
    }
}

/**
 * One ordered card in a preset.
 *
 * @property label     The card label the editor will show ("Light", "Sky", ...).
 * @property tabIndex  Tab the card was created from. Used for routing the
 *                     macro values back to the right adjustment fields.
 * @property macro     Snapshot of the macro at commit time.
 * @property maskClass Auto-segmentation class for mask cards, or null for
 *                     global (full-image) cards. Replay re-segments the
 *                     target photo to derive the bitmap.
 * @property isAutoExposure True for cards that should recompute on the
 *                     target photo (Auto Exposure rerun).
 */
data class PresetCard(
    val label: String,
    val tabIndex: Int,
    val macro: UserMacro,
    /** First segmentation class for backward compat with older sidecars. */
    val maskClass: MaskClass? = null,
    /** Ordered set of segmentation classes that compose this card's mask. */
    val maskClasses: List<MaskClass> = emptyList(),
    val isAutoExposure: Boolean = false,
)

/**
 * Project the editor's live action stack into a portable preset, dropping
 * unsupported cards. Returns the preset plus the list of card labels that
 * had to be dropped so the caller can surface a warning toast.
 */
data class PresetExportResult(
    val preset: RazPreset,
    val droppedCardLabels: List<String>,
)

fun buildPresetFromActions(
    name: String,
    actions: List<RawAction>,
    appVersion: String = "",
): PresetExportResult {
    val cards = mutableListOf<PresetCard>()
    val dropped = mutableListOf<String>()
    // Editor stores newest-first; preset cards are ordered oldest-first
    // so replay walks from the bottom of the stack upward (matching the
    // editor's apply order).
    for (a in actions.reversed()) {
        if (a.id == RawAction.ORIGINAL_ID) continue
        // Hand-painted brush mask (maskPath set but no auto-class info)
        // can't transfer to a different photo — drop it with a warning.
        val hasClassInfo = a.maskClass != null || a.maskClasses.isNotEmpty()
        if (a.maskPath != null && !hasClassInfo) {
            dropped += a.label
            continue
        }
        cards += PresetCard(
            label = a.label,
            tabIndex = a.tabIndex,
            macro = a.macro,
            maskClass = a.maskClass,
            maskClasses = a.maskClasses,
            isAutoExposure = a.isAutoExposure,
        )
    }
    return PresetExportResult(
        preset = RazPreset(
            name = name,
            appVersion = appVersion,
            cards = cards,
        ),
        droppedCardLabels = dropped,
    )
}
