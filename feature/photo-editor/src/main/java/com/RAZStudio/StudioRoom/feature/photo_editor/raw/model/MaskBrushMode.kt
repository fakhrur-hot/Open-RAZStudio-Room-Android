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

/**
 * Mask-tab interaction modes.
 *
 *   None       — view-only (mask overlay shown; canvas taps do nothing)
 *   Draw       — brush-paint additive (canvas drag adds to mask)
 *   Erase      — brush-paint subtractive (canvas drag removes from mask)
 *   ColorSelect — Color-range mask (like Lightroom's "Select Color"): each
 *                 canvas tap samples that pixel's colour; all pixels within a
 *                 colour distance (set by the Refine slider) of any sampled
 *                 colour become the mask. Multiple taps extend the range.
 *   LumaSelect  — Luminance-range mask (like Lightroom's "Select Luminance"):
 *                 a target tone + spread + feather select a luminance band,
 *                 generated per-pixel in the shader/export (no brush texture,
 *                 halo-free). A canvas tap samples that pixel's luma as target.
 */
enum class MaskBrushMode { None, Draw, Erase, ColorSelect, LumaSelect }
