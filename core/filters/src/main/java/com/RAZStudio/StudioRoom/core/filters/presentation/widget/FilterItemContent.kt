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

package com.RAZStudio.StudioRoom.core.filters.presentation.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.domain.utils.Quad
import com.RAZStudio.StudioRoom.core.domain.utils.cast
import com.RAZStudio.StudioRoom.core.filters.domain.model.FilterValueWrapper
import com.RAZStudio.StudioRoom.core.filters.domain.model.enums.PolarCoordinatesType
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.ArcParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.AsciiParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.BilaterialBlurParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.BloomParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.ChannelMixParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.ClaheParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.CropOrPerspectiveParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.EnhancedZoomBlurParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.GlitchParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.KaleidoscopeParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.LinearGaussianParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.LinearTiltShiftParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.PinchParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.RadialTiltShiftParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.RubberStampParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.SideFadeParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.SmearParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.SparkleParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.ToneCurvesParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.VoronoiCrystallizeParams
import com.RAZStudio.StudioRoom.core.filters.domain.model.params.WaterParams
import com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter
import com.RAZStudio.StudioRoom.core.filters.presentation.utils.translatedName
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.ArcParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.AsciiParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.BilaterialBlurParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.BloomParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.BooleanItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.ChannelMixParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.ClaheParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.CropOrPerspectiveParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.EnhancedZoomBlurParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.FilterValueWrapperItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.FloatArrayItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.FloatItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.GlitchParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.IntegerSizeParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.KaleidoscopeParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.LinearGaussianParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.LinearTiltShiftParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.PairItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.PinchParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.QuadItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.RadialTiltShiftParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.RubberStampParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.SideFadeRelativeItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.SmearParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.SparkleParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.ToneCurvesParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.TripleItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.VoronoiCrystallizeParamsItem
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.filterItem.WaterParamsItem
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButtonGroup

@Composable
internal fun <T : Any> FilterItemContent(
    filter: UiFilter<T>,
    onFilterChange: (value: Any) -> Unit,
    modifier: Modifier = Modifier,
    previewOnly: Boolean = false,
) {
    Column(
        modifier = modifier
    ) {
        when (val value = filter.value) {
            is FilterValueWrapper<*> -> {
                FilterValueWrapperItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is FloatArray -> {
                FloatArrayItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is Float -> {
                FloatItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is Boolean -> {
                BooleanItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is PolarCoordinatesType -> {
                EnhancedButtonGroup(
                    inactiveButtonColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    items = PolarCoordinatesType.entries.map { it.translatedName },
                    selectedIndex = PolarCoordinatesType.entries.indexOf(value),
                    onIndexChange = {
                        onFilterChange(PolarCoordinatesType.entries[it])
                    }
                )
            }

            is Pair<*, *> -> {
                PairItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is Triple<*, *, *> -> {
                TripleItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is Quad<*, *, *, *> -> {
                QuadItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is RadialTiltShiftParams -> {
                RadialTiltShiftParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is LinearTiltShiftParams -> {
                LinearTiltShiftParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is GlitchParams -> {
                GlitchParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is SideFadeParams.Relative -> {
                SideFadeRelativeItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is WaterParams -> {
                WaterParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is EnhancedZoomBlurParams -> {
                EnhancedZoomBlurParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is ClaheParams -> {
                ClaheParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is LinearGaussianParams -> {
                LinearGaussianParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is ToneCurvesParams -> {
                ToneCurvesParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is BilaterialBlurParams -> {
                BilaterialBlurParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is KaleidoscopeParams -> {
                KaleidoscopeParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is ChannelMixParams -> {
                ChannelMixParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is VoronoiCrystallizeParams -> {
                VoronoiCrystallizeParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is PinchParams -> {
                PinchParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is RubberStampParams -> {
                RubberStampParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is SmearParams -> {
                SmearParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is ArcParams -> {
                ArcParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is SparkleParams -> {
                SparkleParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is AsciiParams -> {
                AsciiParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is CropOrPerspectiveParams -> {
                CropOrPerspectiveParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is IntegerSize -> {
                IntegerSizeParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }

            is BloomParams -> {
                BloomParamsItem(
                    value = value,
                    filter = filter.cast(),
                    onFilterChange = onFilterChange,
                    previewOnly = previewOnly
                )
            }
        }
    }
}