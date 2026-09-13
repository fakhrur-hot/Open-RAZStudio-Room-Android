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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FileHandle
import com.raz.razstudio.lib.raw.NativeRawDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Extracts embedded JPEG previews from CR2 file bytes — via SAF input streams
 * for a mounted volume, or via libaums [FileHandle.Usb] for a raw USB Mass
 * Storage device — on a background dispatcher.
 */
class ThumbnailExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersHolder,
) {
    /**
     * Read [handle]'s bytes, extract the embedded JPEG thumbnail using
     * NativeRawDecoder.extractEmbeddedThumbnail(), and decode to a Bitmap.
     * Returns null on failure (corrupt file, no preview, I/O error).
     *
     * The input stream is opened and closed within this call — no handles leak.
     * Runs on dispatchers.ioDispatcher.
     */
    suspend fun extractThumbnail(handle: FileHandle): Bitmap? =
        withContext(dispatchers.ioDispatcher) {
            runCatching {
                val bytes = when (handle) {
                    is FileHandle.Saf -> context.contentResolver.openInputStream(handle.documentFile.uri)
                        ?.use { it.readBytes() }
                    is FileHandle.Usb -> handle.openInputStream().use { it.readBytes() }
                } ?: return@withContext null

                val jpegBytes = NativeRawDecoder.extractEmbeddedThumbnail(bytes)
                    ?: return@withContext null

                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            }.getOrNull()
        }

    /**
     * Batch extraction for a list of CR2 files. Emits results progressively
     * as (filename, Bitmap?) pairs so the UI can update the grid incrementally.
     */
    fun extractThumbnailsFlow(
        cr2Files: List<FileHandle>,
    ): Flow<Pair<String, Bitmap?>> = flow {
        for (file in cr2Files) {
            val name = file.name
            val thumbnail = extractThumbnail(file)
            emit(name to thumbnail)
        }
    }.flowOn(dispatchers.ioDispatcher)
}
