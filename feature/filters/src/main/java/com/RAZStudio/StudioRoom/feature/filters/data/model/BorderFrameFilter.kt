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

package com.RAZStudio.StudioRoom.feature.filters.data.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import com.RAZStudio.StudioRoom.core.data.image.utils.ColorUtils.toModel
import com.RAZStudio.StudioRoom.core.data.image.utils.drawBitmap
import com.RAZStudio.StudioRoom.core.domain.model.ColorModel
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.domain.model.Position
import com.RAZStudio.StudioRoom.core.domain.transformation.Transformation
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.ksp.annotations.FilterInject
import kotlin.math.roundToInt

@FilterInject
internal class BorderFrameFilter(
    override val value: Triple<Float, Float, ColorModel> = Triple(20f, 40f, Color.White.toModel())
) : Transformation<Bitmap>, Filter.BorderFrame {
    override val cacheKey: String
        get() = value.hashCode().toString()

    override suspend fun transform(
        input: Bitmap,
        size: IntegerSize
    ): Bitmap {
        val horizontal = value.first.roundToInt()
        val vertical = value.second.roundToInt()

        return createBitmap(
            width = input.width + horizontal * 2,
            height = input.height + vertical * 2
        ).applyCanvas {
            drawColor(value.third.colorInt)

            drawBitmap(
                bitmap = input,
                position = Position.Center
            )
        }
    }
}