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

package com.RAZStudio.StudioRoom.feature.image_stacking.domain

import com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat
import com.RAZStudio.StudioRoom.core.domain.image.model.Quality

interface ImageStacker<I> {

    suspend fun stackImages(
        stackImages: List<StackImage>,
        stackingParams: StackingParams,
        onFailure: (Throwable) -> Unit,
        onProgress: (Int) -> Unit
    ): I?

    suspend fun stackImagesPreview(
        stackImages: List<StackImage>,
        stackingParams: StackingParams,
        imageFormat: ImageFormat,
        quality: Quality,
        onGetByteCount: (Int) -> Unit
    ): I?

}