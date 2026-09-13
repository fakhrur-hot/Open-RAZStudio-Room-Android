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

package com.RAZStudio.StudioRoom.feature.filters.data.model

import android.graphics.Bitmap
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.domain.transformation.Transformation
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.CropOrPerspectiveParams
import com.RAZStudio.StudioRoom.core.ksp.annotations.FilterInject
import com.RAZStudio.opencv_tools.auto_straight.AutoStraighten
import com.RAZStudio.opencv_tools.auto_straight.model.Corners
import com.RAZStudio.opencv_tools.auto_straight.model.PointD
import com.RAZStudio.opencv_tools.auto_straight.model.StraightenMode

@FilterInject
internal class CropOrPerspectiveFilter(
    override val value: CropOrPerspectiveParams = CropOrPerspectiveParams.Default
) : Transformation<Bitmap>, Filter.CropOrPerspective {

    override val cacheKey: String
        get() = value.hashCode().toString()

    override suspend fun transform(
        input: Bitmap,
        size: IntegerSize
    ): Bitmap = AutoStraighten.process(
        input = input,
        mode = StraightenMode.Manual(
            corners = Corners(
                topLeft = PointD(
                    x = value.topLeft.first.toDouble(),
                    y = value.topLeft.second.toDouble()
                ),
                topRight = PointD(
                    x = value.topRight.first.toDouble(),
                    y = value.topRight.second.toDouble()
                ),
                bottomLeft = PointD(
                    x = value.bottomLeft.first.toDouble(),
                    y = value.bottomLeft.second.toDouble()
                ),
                bottomRight = PointD(
                    x = value.bottomRight.first.toDouble(),
                    y = value.bottomRight.second.toDouble()
                ),
                isAbsolute = value.isAbsolute
            )
        )
    )

}