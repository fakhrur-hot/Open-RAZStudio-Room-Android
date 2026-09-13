/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2024 RAZStudio (Fakhrurraze)
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

package com.RAZStudio.StudioRoom.feature.filters.data.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.RAZStudio.StudioRoom.core.data.image.utils.ColorUtils.toModel
import com.RAZStudio.StudioRoom.core.domain.model.ColorModel
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.domain.transformation.Transformation
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.ksp.annotations.FilterInject
import com.RAZStudio.StudioRoom.feature.filters.data.utils.pixelation.PixelationTool
import com.t8rin.trickle.Trickle

@FilterInject
internal class StrokePixelationFilter(
    override val value: Pair<Float, ColorModel> = 20f to Color.Black.toModel(),
) : Transformation<Bitmap>, Filter.StrokePixelation {
    override val cacheKey: String
        get() = value.hashCode().toString()

    override suspend fun transform(
        input: Bitmap,
        size: IntegerSize
    ): Bitmap = PixelationTool.pixelate(
        input = input,
        layers = { stroke(value.first) }
    ).let {
        Trickle.drawColorBehind(
            input = it,
            color = value.second.colorInt
        )
    }
}