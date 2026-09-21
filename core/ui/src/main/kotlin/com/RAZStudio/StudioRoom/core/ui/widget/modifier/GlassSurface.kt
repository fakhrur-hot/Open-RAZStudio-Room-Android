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

package com.RAZStudio.StudioRoom.core.ui.widget.modifier

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.ui.theme.PhotoLabColors

/**
 * Dark-glass chrome. Does not blur child text/sliders. True backdrop blur is
 * avoided over the GL canvas (it would sample the SurfaceView poorly).
 */
fun Modifier.glassSurface(
    shape: Shape? = null,
    tint: Color = Color.Unspecified,
): Modifier = composed {
    val night = LocalSettingsState.current.isNightMode
    val scheme = MaterialTheme.colorScheme
    val resultShape = shape ?: ShapeDefaults.default
    val fill = when {
        tint != Color.Unspecified -> tint
        night -> scheme.surface.copy(alpha = 0.82f)
        else -> Color.White.copy(alpha = 0.55f)
    }
    val stroke = if (night) PhotoLabColors.hairline else Color.White.copy(alpha = 0.35f)
    Modifier
        .clip(resultShape)
        .background(fill, resultShape)
        .border(0.6.dp, stroke, resultShape)
}
