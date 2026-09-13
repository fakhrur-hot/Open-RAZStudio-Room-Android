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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.details

/**
 * Holds all Details-tab adjustment parameters.
 *
 * [smartSharpness]     edge-sensitive sharpness, 0..1 (only boosts high-gradient areas)
 * [luminanceNR]        luminance noise reduction, 0..1 (blends luma toward blurred luma)
 * [colorNR]            color (chroma) noise reduction, 0..1 (box-blurs Cb/Cr channels)
 * [filmGrain]          film grain strength, 0..1
 * [filmGrainSize]      grain particle size, 0..1 (higher = larger, softer grain)
 * [filmGrainUniformity] grain uniformity, 0..1 (0 = structured Perlin-like, 1 = pure random)
 * [filmGrainWashOut]   washed-out film look, 0..1 (lifts blacks + crushes contrast → milky fade)
 */
data class DetailsSelection(
    val smartSharpness: Float = 0f,
    val luminanceNR: Float = 0f,
    val colorNR: Float = 0f,
    val filmGrain: Float = 0f,
    val filmGrainSize: Float = 0.5f,
    val filmGrainUniformity: Float = 0f,
    val filmGrainWashOut: Float = 0f,
) {
    val hasSharpness: Boolean get() = smartSharpness != 0f
    val hasNR: Boolean get() = luminanceNR > 0f || colorNR > 0f
    val hasGrain: Boolean get() = filmGrain > 0f || filmGrainWashOut > 0f
    val isEmpty: Boolean get() = !hasSharpness && !hasNR && !hasGrain
}
