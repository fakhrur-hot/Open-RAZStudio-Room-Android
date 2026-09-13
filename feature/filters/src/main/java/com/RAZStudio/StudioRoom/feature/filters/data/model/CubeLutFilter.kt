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
import androidx.core.net.toUri
import com.RAZStudio.StudioRoom.core.domain.model.FileModel
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.domain.transformation.Transformation
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.ksp.annotations.FilterInject
import com.RAZStudio.StudioRoom.core.utils.appContext
import com.RAZStudio.opencv_tools.gpu.GpuLutProcessor
import com.t8rin.trickle.Trickle
import com.t8rin.trickle.TrickleUtils
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@FilterInject
internal class CubeLutFilter(
    override val value: Pair<Float, FileModel> = 1f to FileModel(""),
) : Transformation<Bitmap>, Filter.CubeLut {

    override val cacheKey: String
        get() = value.hashCode().toString()

    override suspend fun transform(
        input: Bitmap,
        size: IntegerSize,
    ): Bitmap {
        if (value.second.uri.isEmpty()) return input

        val intensity = value.first
        val uri = value.second.uri

        // Try GPU path first — falls back to Trickle (CPU) on any failure.
        val gpuResult = runCatching {
            val lut = parsedLutCache.getOrPut(uri) { parseCubeLut(uri) }
                ?: return@runCatching null

            withContext(GpuLutProcessor.gpuDispatcher) {
                val gpu = gpuProcessorHolder.getOrCreate()
                gpu?.applyLut(
                    image = input,
                    lutTable = lut.table,
                    lutSize = lut.size,
                    intensity = intensity,
                    // mask = null → uniform LUT, no subject pop
                    mask = null,
                    contrastBoost = 0f,
                )
            }
        }.getOrNull()

        if (gpuResult != null) return gpuResult

        // CPU fallback via Trickle.
        // TrickleUtils.getAbsolutePath uses ContentResolver, which rejects file:// URIs on
        // Android 7+. For file:// URIs extract the path directly; use TrickleUtils only for
        // content:// URIs where it knows how to resolve provider paths.
        val parsedUri = uri.toUri()
        val lutPath = if (parsedUri.scheme == "file") {
            parsedUri.path ?: return input
        } else {
            TrickleUtils.getAbsolutePath(uri = parsedUri, context = appContext)
        }
        return Trickle.applyCubeLut(
            input = input,
            cubeLutPath = lutPath,
            intensity = intensity,
        )
    }

    // ── Companion: shared GPU context and LUT cache ───────────────────────────

    companion object {
        // LUT parse results are expensive — cache by URI string.
        private val parsedLutCache = ConcurrentHashMap<String, LutData?>()

        // GPU processor singleton created lazily on the GPU thread.
        private val gpuProcessorHolder = GpuProcessorHolder()

        /** Clear cached LUT data (e.g. when the user replaces a custom .cube file). */
        fun clearLutCache() = parsedLutCache.clear()

        private data class LutData(val table: FloatArray, val size: Int)

        /**
         * Parse a .cube file at the given URI string into a flat float array.
         * Returns null if the URI cannot be opened or the file is malformed.
         */
        private fun parseCubeLut(uriString: String): LutData? = runCatching {
            val uri = uriString.toUri()
            val stream = if (uri.scheme == "file") {
                java.io.File(checkNotNull(uri.path)).inputStream()
            } else {
                appContext.contentResolver.openInputStream(uri) ?: return null
            }

            var size = 33
            val values = mutableListOf<Float>()

            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    when {
                        trimmed.isBlank() || trimmed.startsWith("#") -> Unit
                        trimmed.startsWith("LUT_3D_SIZE") ->
                            size = trimmed.substringAfterLast(' ').trim().toIntOrNull() ?: size
                        trimmed.startsWith("TITLE") || trimmed.startsWith("DOMAIN") -> Unit
                        else -> {
                            val parts = trimmed.split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                values.add(parts[0].toFloatOrNull() ?: 0f)
                                values.add(parts[1].toFloatOrNull() ?: 0f)
                                values.add(parts[2].toFloatOrNull() ?: 0f)
                            }
                        }
                    }
                }
            }

            val expected = size * size * size * 3
            if (values.size < expected) return null

            LutData(table = values.toFloatArray(), size = size)
        }.getOrNull()

        /** Holds the GpuLutProcessor, created once on the GPU thread. */
        private class GpuProcessorHolder {
            @Volatile private var instance: GpuLutProcessor? = null
            @Volatile private var initAttempted = false

            fun getOrCreate(): GpuLutProcessor? {
                if (initAttempted) return instance
                // Must be called on gpuDispatcher thread
                instance = GpuLutProcessor.createOrNull()
                initAttempted = true
                return instance
            }
        }
    }
}
