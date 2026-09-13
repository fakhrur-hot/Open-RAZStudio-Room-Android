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

package com.RAZStudio.StudioRoom.core.data.coil

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse
import com.hashsequence.coilresvg.ResvgDecoder
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8

internal class UpscaleSvgDecoder(
    private val source: ImageSource,
    private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult = ResvgDecoder(
        source = source,
        options = options.copy(
            size = options.size.coerceAtLeast(2048)
        )
    ).decode()

    private fun Size.coerceAtLeast(size: Int): Size = Size(
        width = width.pxOrElse { 0 }.coerceAtLeast(size),
        height = height.pxOrElse { 0 }.coerceAtLeast(size)
    )


    class Factory : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? {
            if (!isSvg(result.source.source(), result.mimeType)) return null

            return UpscaleSvgDecoder(
                source = result.source,
                options = options
            )
        }

        private fun isSvg(source: BufferedSource, mimeType: String?): Boolean {
            return mimeType == MIME_TYPE_SVG ||
                    mimeType == MIME_TYPE_XML ||
                    (source.rangeEquals(0, LEFT_ANGLE_BRACKET) &&
                            source.indexOf(SVG_TAG, 0, SVG_DETECT_BUFFER_SIZE) != -1L)
        }

        private companion object {
            private const val MIME_TYPE_SVG = "image/svg+xml"
            private const val MIME_TYPE_XML = "text/xml"
            private const val SVG_DETECT_BUFFER_SIZE = 1024L
            private val SVG_TAG = "<svg".encodeUtf8()
            private val LEFT_ANGLE_BRACKET = "<".encodeUtf8()
        }
    }

}