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

package com.RAZStudio.StudioRoom.feature.erase_background.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.RAZStudio.StudioRoom.core.data.image.utils.healAlpha
import com.RAZStudio.StudioRoom.core.domain.coroutines.AppScope
import com.RAZStudio.StudioRoom.core.utils.makeLog
import com.RAZStudio.StudioRoom.feature.erase_background.domain.AutoBackgroundRemover
import com.RAZStudio.StudioRoom.feature.erase_background.domain.AutoBackgroundRemoverBackendFactory
import com.RAZStudio.StudioRoom.feature.erase_background.domain.model.BgModelType
import com.t8rin.trickle.TrickleUtils
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

internal class AndroidAutoBackgroundRemover @Inject constructor(
    private val backendFactory: AutoBackgroundRemoverBackendFactory<Bitmap>,
    private val appScope: AppScope
) : AutoBackgroundRemover<Bitmap> {

    override suspend fun trimEmptyParts(
        image: Bitmap,
        emptyColor: Int?
    ): Bitmap = coroutineScope {
        val transparent = emptyColor ?: Color.Transparent.toArgb()

        runCatching {
            TrickleUtils.trimEmptyParts(
                bitmap = image,
                transparent = transparent
            )
        }.onFailure {
            "trimEmptyParts".makeLog("Failed to crop image ${it.message}")
        }.getOrNull() ?: image
    }

    override fun removeBackgroundFromImage(
        image: Bitmap,
        modelType: BgModelType,
        onSuccess: (Bitmap) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        appScope.launch {
            backendFactory.create(modelType)
                .performBackgroundRemove(image)
                .map { it.healAlpha(image) }
                .onSuccess(onSuccess)
                .onFailure {
                    onFailure(it.makeLog())
                }
        }
    }

    override fun cleanup() = Unit

}