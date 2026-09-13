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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur

import com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter

/**
 * Depth-of-field compositing algorithm used when AI Detection is enabled.
 *
 * [Standard]   feathered box-blur mask composite — fast, smooth transition
 * [Guided]     guided-filter mask — edge-preserving, near-zero halo, O(N) fast
 * [Bilateral]  bilateral-filter mask — range-aware feathering, very clean edges
 * [ScatterCoC] variable-radius CoC scatter — lens-like bokeh balls that scale with depth
 */
enum class DofMode {
    Standard,
    Guided,
    Bilateral,
    ScatterCoC,
}

/**
 * Holds the current blur selection state including AI and DoF parameters.
 *
 * [filter]      the selected blur filter with its current parameter values
 * [aiEnabled]   whether AI Detection is active (U2Net subject mask)
 * [edgeBlur]    edge feather radius, 0..1 (0 = tight, 1 = wide)
 * [bokehEdges]  N-gon aperture sides for bokeh shaping. 0 = perfect circle.
 * [dofMode]     compositing algorithm used when [aiEnabled] is true
 */
data class BlurSelection(
    val filter: UiFilter<*>,
    val aiEnabled: Boolean = false,
    val edgeBlur: Float = 0.5f,
    val bokehEdges: Int = 0,
    val dofMode: DofMode = DofMode.Standard,
)
