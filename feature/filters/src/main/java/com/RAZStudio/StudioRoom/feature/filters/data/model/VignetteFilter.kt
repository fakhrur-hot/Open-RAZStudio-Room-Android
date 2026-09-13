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

import android.graphics.PointF
import androidx.compose.ui.graphics.Color
import com.RAZStudio.StudioRoom.core.data.image.utils.ColorUtils.blue
import com.RAZStudio.StudioRoom.core.data.image.utils.ColorUtils.green
import com.RAZStudio.StudioRoom.core.data.image.utils.ColorUtils.red
import com.RAZStudio.StudioRoom.core.data.image.utils.ColorUtils.toModel
import com.RAZStudio.StudioRoom.core.domain.model.ColorModel
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.ksp.annotations.FilterInject
import com.RAZStudio.StudioRoom.feature.filters.data.transformation.GPUFilterTransformation
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVignetteFilter

@FilterInject
internal class VignetteFilter(
    override val value: Triple<Float, Float, ColorModel> = Triple(
        first = 0.3f,
        second = 0.75f,
        third = Color.Black.toModel()
    )
) : GPUFilterTransformation(), Filter.Vignette {

    override val cacheKey: String
        get() = value.hashCode().toString()

    override fun createFilter(): GPUImageFilter = GPUImageVignetteFilter(
        PointF(0.5f, 0.5f),
        floatArrayOf(value.third.red, value.third.green, value.third.blue),
        value.first,
        value.second
    )

}