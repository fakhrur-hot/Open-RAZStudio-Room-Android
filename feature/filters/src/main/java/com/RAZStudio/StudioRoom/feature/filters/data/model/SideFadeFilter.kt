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
import androidx.core.graphics.applyCanvas
import com.RAZStudio.StudioRoom.core.data.utils.safeConfig
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.domain.transformation.Transformation
import com.RAZStudio.StudioRoom.core.filters.data.getPaint
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.filters.domain.model.enums.FadeSide
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.SideFadeParams
import com.RAZStudio.StudioRoom.core.ksp.annotations.FilterInject
import kotlin.math.roundToInt

@FilterInject
internal class SideFadeFilter(
    override val value: SideFadeParams = SideFadeParams.Relative(FadeSide.Start, 0.5f),
) : Transformation<Bitmap>, Filter.SideFade {

    override val cacheKey: String
        get() = value.hashCode().toString()

    override suspend fun transform(
        input: Bitmap,
        size: IntegerSize
    ): Bitmap {
        val bitmap = input.copy(input.safeConfig, true).apply { setHasAlpha(true) }
        val fadeSize: Int = when (value) {
            is SideFadeParams.Absolute -> value.size
            is SideFadeParams.Relative -> {
                when (value.side) {
                    FadeSide.Start, FadeSide.End -> {
                        bitmap.width * value.scale
                    }

                    FadeSide.Bottom, FadeSide.Top -> {
                        bitmap.height * value.scale
                    }
                }.roundToInt()
            }
        }
        val strength = when (value) {
            is SideFadeParams.Absolute -> value.strength
            is SideFadeParams.Relative -> 1f
        }

        return bitmap.applyCanvas {
            drawPaint(
                value.side.getPaint(
                    bmp = input,
                    length = fadeSize,
                    strength = strength
                )
            )
        }
    }

}