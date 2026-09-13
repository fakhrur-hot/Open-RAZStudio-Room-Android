/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions

import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur.BlurSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.details.DetailsSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LayersSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LinearGradientSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.VignetteSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LutSelection
import java.util.UUID

/**
 * Represents a single adjustment step that has been applied to the filter stack.
 *
 * Each card carries a unique [id], a human-readable [displayName], and a [isVisible]
 * flag that controls whether the adjustment is included when rendering the preview or
 * applying the full-resolution output.
 *
 * [Original] is a special sentinel card that is always pinned to the bottom of the stack
 * and cannot be deleted or toggled.
 */
sealed class ActionCard {
    abstract val id: String
    abstract val displayName: String
    abstract val isVisible: Boolean

    /**
     * Sentinel card representing the unmodified source image.
     * Always at the bottom of the stack; undeletable and non-togglable.
     */
    data object Original : ActionCard() {
        override val id: String = "original"
        override val displayName: String = "Original"
        override val isVisible: Boolean = true
    }

    /**
     * An applied LUT (Look-Up Table) adjustment.
     *
     * [id]          unique identifier for this card (defaults to a new UUID)
     * [displayName] label shown in the Actions stack UI
     * [isVisible]   whether this adjustment participates in the render pipeline
     * [selection]   full LUT + AI adjustment state at the time the card was created
     */
    data class LutCard(
        override val id: String = UUID.randomUUID().toString(),
        override val displayName: String,
        override val isVisible: Boolean = true,
        val selection: LutSelection,
    ) : ActionCard()

    /**
     * An applied Blur adjustment.
     *
     * [selection] blur filter + AI parameters captured at creation time
     */
    data class BlurCard(
        override val id: String = UUID.randomUUID().toString(),
        override val displayName: String,
        override val isVisible: Boolean = true,
        val selection: BlurSelection,
    ) : ActionCard()

    /**
     * An applied Details adjustment (sharpness, noise reduction, film grain).
     *
     * [selection] details parameters captured at creation time
     */
    data class DetailsCard(
        override val id: String = UUID.randomUUID().toString(),
        override val displayName: String,
        override val isVisible: Boolean = true,
        val selection: DetailsSelection,
    ) : ActionCard()

    /**
     * An applied Layers adjustment (vignette + linear gradient).
     *
     * [selection] layers parameters captured at creation time
     */
    data class LayersCard(
        override val id: String = UUID.randomUUID().toString(),
        override val displayName: String,
        override val isVisible: Boolean = true,
        val selection: LayersSelection,
    ) : ActionCard()

    /**
     * An applied Vignette adjustment.
     *
     * [selection] vignette parameters captured at creation time
     */
    data class VignetteCard(
        override val id: String = UUID.randomUUID().toString(),
        override val displayName: String,
        override val isVisible: Boolean = true,
        val selection: VignetteSelection,
    ) : ActionCard()

    /**
     * An applied Linear Gradient adjustment.
     *
     * [selection] linear gradient parameters captured at creation time
     */
    data class LinearGradientCard(
        override val id: String = UUID.randomUUID().toString(),
        override val displayName: String,
        override val isVisible: Boolean = true,
        val selection: LinearGradientSelection,
    ) : ActionCard()

    /**
     * An applied Tone Curves adjustment.
     *
     * [controlPoints] a list of exactly 4 inner lists — [luminance, R, G, B] — where each
     *                 inner list holds evenly-spaced Y values in 0..1 along the curve.
     *                 An empty inner list signals "use the identity/diagonal curve" for that
     *                 channel.
     */
    data class ToneCurvesCard(
        override val id: String = UUID.randomUUID().toString(),
        override val displayName: String,
        override val isVisible: Boolean = true,
        val controlPoints: List<List<Float>>,
    ) : ActionCard()
}

/**
 * A named, persistable collection of [ActionCard]s (the [ActionCard.Original] sentinel is
 * never included).
 *
 * [name]    user-given name for the preset
 * [cards]   ordered list of adjustments — top of the stack first
 * [savedAt] Unix epoch millis at the moment the set was persisted
 */
data class ActionsSet(
    val name: String,
    val cards: List<ActionCard>,
    val savedAt: Long = System.currentTimeMillis(),
)
