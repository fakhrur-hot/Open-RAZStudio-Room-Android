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



package com.RAZStudio.StudioRoom.core.data.image

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import com.RAZStudio.StudioRoom.core.data.image.utils.ImageCompressorBackend
import com.RAZStudio.StudioRoom.core.data.utils.toSoftware
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.domain.image.ImageCompressor
import com.RAZStudio.StudioRoom.core.domain.image.ImageGetter
import com.RAZStudio.StudioRoom.core.domain.image.ImageScaler
import com.RAZStudio.StudioRoom.core.domain.image.ImageTransformer
import com.RAZStudio.StudioRoom.core.domain.image.ShareProvider
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageInfo
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageScaleMode
import com.RAZStudio.StudioRoom.core.domain.image.model.Quality
import com.RAZStudio.StudioRoom.core.domain.image.model.alphaContainedEntries
import com.RAZStudio.StudioRoom.core.domain.model.sizeTo
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsProvider
import com.RAZStudio.StudioRoom.core.settings.domain.model.FilenameBehavior
import com.RAZStudio.StudioRoom.core.utils.fileSize
import com.t8rin.trickle.Trickle
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject

internal class AndroidImageCompressor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageTransformer: ImageTransformer<Bitmap>,
    private val imageScaler: ImageScaler<Bitmap>,
    private val imageGetter: ImageGetter<Bitmap>,
    private val shareProvider: Lazy<ShareProvider>,
    settingsProvider: SettingsProvider,
    dispatchersHolder: DispatchersHolder
) : DispatchersHolder by dispatchersHolder, ImageCompressor<Bitmap> {

    private val _settingsState = settingsProvider.settingsState
    private val settingsState get() = _settingsState.value

    override suspend fun compress(
        image: Bitmap,
        imageFormat: ImageFormat,
        quality: Quality
    ): ByteArray = withContext(encodingDispatcher) {
        val transformedImage = image.toSoftware().let { software ->
            val enableForAlpha = settingsState.enableBackgroundColorForAlphaFormats
            val isNonAlpha = imageFormat !in ImageFormat.alphaContainedEntries

            if (isNonAlpha || quality.isNonAlpha() || enableForAlpha) {
                withContext(defaultDispatcher) {
                    if (isNonAlpha && settingsState.backgroundForNoAlphaImageFormats.colorInt == Color.Black.toArgb()) {
                        software
                    } else {
                        Trickle.drawColorBehind(
                            color = settingsState.backgroundForNoAlphaImageFormats.colorInt,
                            input = software
                        )
                    }
                }
            } else software
        }

        ImageCompressorBackend.Factory()
            .create(
                imageFormat = imageFormat,
                context = context,
                imageScaler = imageScaler
            )
            .compress(
                image = transformedImage,
                quality = quality.coerceIn(imageFormat)
            )
    }


    override suspend fun compressAndTransform(
        image: Bitmap,
        imageInfo: ImageInfo,
        onImageReadyToCompressInterceptor: suspend (Bitmap) -> Bitmap,
        applyImageTransformations: Boolean
    ): ByteArray = withContext(encodingDispatcher) {
        val currentImage: Bitmap
        if (applyImageTransformations) {
            val size = imageInfo.originalUri?.let {
                imageGetter.getImage(
                    data = it,
                    originalSize = true
                )?.run { width sizeTo height }
            }
            val isRazSharp = imageInfo.imageScaleMode is ImageScaleMode.RAZSharp
            currentImage = imageScaler
                .scaleImage(
                    image = imageTransformer.rotate(
                        image = image.apply { setHasAlpha(true) },
                        degrees = imageInfo.rotationDegrees
                    ),
                    width = imageInfo.width,
                    height = imageInfo.height,
                    resizeType = imageInfo.resizeType.withOriginalSizeIfCrop(size),
                    imageScaleMode = imageInfo.imageScaleMode,
                    resizeSharpen = imageInfo.resizeSharpen
                )
                .let {
                    imageTransformer.flip(
                        image = it,
                        isFlipped = imageInfo.isFlipped
                    )
                }
                .let {
                    // RAZSharp applies sharpening natively inside libresize; skip Kotlin post-sharpen
                    if (isRazSharp) it
                    else PostResizeSharpen.apply(it, imageInfo.resizeSharpen.strength)
                }
                .let {
                    onImageReadyToCompressInterceptor(it)
                }
        } else currentImage = onImageReadyToCompressInterceptor(image)

        val extension = imageInfo.originalUri?.let { imageGetter.getExtension(it) }

        val imageFormat =
            if (settingsState.filenameBehavior is FilenameBehavior.Overwrite && extension != null) {
                val target = ImageFormat[extension]

                if (imageInfo.imageFormat.extension == target.extension) {
                    imageInfo.imageFormat
                } else {
                    target
                }
            } else imageInfo.imageFormat

        yield()
        compress(
            image = currentImage,
            imageFormat = imageFormat,
            quality = imageInfo.quality
        )
    }

    override suspend fun calculateImageSize(
        image: Bitmap,
        imageInfo: ImageInfo
    ): Long = withContext(encodingDispatcher) {
        val newInfo = imageInfo.let {
            if (it.width == 0 || it.height == 0) {
                it.copy(
                    width = image.width,
                    height = image.height
                )
            } else it
        }
        compressAndTransform(
            image = image,
            imageInfo = newInfo
        ).let {
            shareProvider.get().cacheByteArray(
                byteArray = it,
                filename = "temp.${newInfo.imageFormat.extension}"
            )?.toUri()
                ?.fileSize() ?: it.size.toLong()
        }
    }

}