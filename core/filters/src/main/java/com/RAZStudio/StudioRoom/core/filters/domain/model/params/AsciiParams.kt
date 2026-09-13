/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2025 RAZStudio (Fakhrurraze)
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

package com.RAZStudio.StudioRoom.core.filters.domain.model.params

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.RAZStudio.StudioRoom.core.domain.model.ColorModel
import com.RAZStudio.StudioRoom.core.domain.model.toColorModel
import com.RAZStudio.StudioRoom.core.settings.domain.model.FontType

data class AsciiParams(
    val gradient: String,
    val fontSize: Float,
    val backgroundColor: ColorModel,
    val isGrayscale: Boolean,
    val font: FontType?,
) {
    companion object {
        val Default = AsciiParams(
            gradient = " .:-=+*#%@",
            fontSize = 10f,
            backgroundColor = Color.Black.toArgb().toColorModel(),
            isGrayscale = false,
            font = null,
        )
    }
}
