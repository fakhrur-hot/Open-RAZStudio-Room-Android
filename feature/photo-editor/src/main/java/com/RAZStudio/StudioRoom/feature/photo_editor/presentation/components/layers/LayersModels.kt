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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers

import android.graphics.Color as AndroidColor

/**
 * Vignette adjustment parameters.
 *
 * [intensity]      0..1 — how dark the vignette is at the edges
 * [feather]        0..1 — transition softness (0 = hard edge, 1 = very soft)
 * [roundness]      -1..+1 — shape: −1 = rectangular, 0 = default ellipse, +1 = circular
 * [midpoint]       0..1 — how far from center the vignette starts (0 = starts at center, 1 = starts at edge)
 * [centerX]        0..1 — vignette center X (0.5 = image center)
 * [centerY]        0..1 — vignette center Y (0.5 = image center)
 * [includeSubject] true = vignette applied everywhere including subject (default); false = skip subject pixels
 */
data class VignetteSelection(
    val intensity: Float = 0f,
    val feather: Float = 0.5f,
    val roundness: Float = 0f,
    val midpoint: Float = 0.5f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val includeSubject: Boolean = true,
) {
    val isEmpty: Boolean get() = intensity == 0f
}

/** Which region the linear gradient is applied to. */
enum class GradientApplyTo {
    All,        // default — affects every pixel
    Background, // only pixels outside the subject mask
    Subject,    // only pixels inside the subject mask
}

enum class GradientBlendMode {
    Solid,
    Fused,
}

/**
 * One side of the linear gradient tool.
 *
 * [intensity]       0..1 — how strong the darkening/brightening is
 * [length]          0..1 — how far the gradient reaches into the image (0.3 = 30% of image)
 * [feather]         0..1 — softness of the gradient falloff
 * [tintColor]       ARGB color to blend into this edge (default white)
 * [tintLuminosity]  0..1 — how strongly the tintColor is blended (0 = no tint)
 * [tintApplyTo]     which region the color tint affects (All / Background / Subject)
 * [blendMode]       how the tint color is blended into the original image pixels
 */
data class LinearGradientSide(
    val intensity: Float = 0f,
    val length: Float = 0.3f,
    val feather: Float = 0.5f,
    val tintColor: Int = AndroidColor.WHITE,
    val tintLuminosity: Float = 0f,
    val tintApplyTo: GradientApplyTo = GradientApplyTo.All,
    val blendMode: GradientBlendMode = GradientBlendMode.Solid,
) {
    val isEmpty: Boolean get() = intensity == 0f && tintLuminosity == 0f
}

/**
 * 4-side linear gradient darkening (like a graduated filter in Lightroom).
 *
 * [angle]   -180..+180 degrees, default 0° (= Top direction). Rotates all 4 sides together.
 *           Top=0°, Right=90°, Bottom=±180°, Left=-90°.
 * [applyTo] which region the gradient affects (All / Background / Subject).
 */
data class LinearGradientSelection(
    val angle: Float = 0f,
    val applyTo: GradientApplyTo = GradientApplyTo.All,
    val top: LinearGradientSide = LinearGradientSide(),
    val bottom: LinearGradientSide = LinearGradientSide(),
    val left: LinearGradientSide = LinearGradientSide(),
    val right: LinearGradientSide = LinearGradientSide(),
) {
    val isEmpty: Boolean get() = top.isEmpty && bottom.isEmpty && left.isEmpty && right.isEmpty
}

/**
 * Combined Layers tab selection (vignette + linear gradient).
 */
data class LayersSelection(
    val vignette: VignetteSelection = VignetteSelection(),
    val linearGradient: LinearGradientSelection = LinearGradientSelection(),
) {
    val isEmpty: Boolean get() = vignette.isEmpty && linearGradient.isEmpty
}
