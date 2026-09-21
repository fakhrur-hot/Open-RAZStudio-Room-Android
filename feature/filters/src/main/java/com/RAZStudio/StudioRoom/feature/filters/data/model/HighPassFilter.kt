/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.filters.data.model

import android.graphics.Bitmap
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.domain.transformation.Transformation
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.ksp.annotations.FilterInject
import com.RAZStudio.opencv_tools.image_processing.ImageProcessing

@FilterInject
internal class HighPassFilter(
    override val value: Pair<Float, Float> = 10f to 1f,
) : Transformation<Bitmap>, Filter.HighPass {

    override val cacheKey: String
        get() = value.hashCode().toString()

    override suspend fun transform(
        input: Bitmap,
        size: IntegerSize
    ): Bitmap = ImageProcessing.highPass(
        bitmap = input,
        radius = value.first,
        strength = value.second
    )

}
