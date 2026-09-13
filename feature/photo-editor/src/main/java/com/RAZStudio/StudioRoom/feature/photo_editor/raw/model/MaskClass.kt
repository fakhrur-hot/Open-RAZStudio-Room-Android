/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

/**
 * Identifies a segmentation class whose inclusion in the current mask
 * is tracked via the Mask tab's split-buttons (Select / Remove).
 *
 * The editor owns a `Set<MaskClass>` that records which classes are
 * currently in the mask. Tapping the LEFT side of a split-button adds
 * the class via OR-into-mask and adds the entry to the set; tapping
 * the RIGHT side subtracts the class from the mask and removes the
 * entry. The blue/red state of each button mirrors the set membership.
 */
enum class MaskClass {
    Subject,
    Background,
    Sky,
    Buildings,
    Vegetation,
    Terrain,
    Hair,
    BodySkin,
    FaceSkin,
    Clothes,
}
