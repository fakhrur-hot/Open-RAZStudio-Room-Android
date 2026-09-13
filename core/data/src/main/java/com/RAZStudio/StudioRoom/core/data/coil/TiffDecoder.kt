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

import android.graphics.Bitmap
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import coil3.size.Size
import coil3.size.pxOrElse
import okio.BufferedSource
import okio.ByteString.Companion.toByteString
import org.beyka.tiffbitmapfactory.TiffBitmapFactory
import oupson.apng.utils.Utils.flexibleResize

internal class TiffDecoder private constructor(
    private val source: ImageSource,
    private val options: Options
) : Decoder {

    @Suppress("DEPRECATION")
    override suspend fun decode(): DecodeResult? {
        val config = options.bitmapConfig.takeIf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it != Bitmap.Config.HARDWARE
            } else true
        } ?: Bitmap.Config.ARGB_8888

        val decoded = runCatching {
            TiffBitmapFactory.decodeFile(
                source.file().toFile()
            )
        }.getOrNull() ?: return null

        val image = decoded
            .createScaledBitmap(options.size)
            .copy(config, false)
            .asImage()

        return DecodeResult(
            image = image,
            isSampled = options.size != Size.ORIGINAL
        )
    }

    private fun Bitmap.createScaledBitmap(
        size: Size
    ): Bitmap {
        if (size == Size.ORIGINAL) return this

        return flexibleResize(
            maxOf(
                size.width.pxOrElse { 1 },
                size.height.pxOrElse { 1 }
            )
        )
    }

    class Factory : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? {
            return if (isTiff(result.source.source())) {
                TiffDecoder(
                    source = result.source,
                    options = options
                )
            } else null
        }

        private fun isTiff(source: BufferedSource): Boolean {
            val magic1 = byteArrayOf(0x49, 0x49, 0x2a, 0x00)
            val magic2 = byteArrayOf(0x4d, 0x4d, 0x00, 0x2a)
            // Canon CR2 = TIFF little-endian header + "CR" signature at offset 8.
            // Bytes 4-7 are the IFD offset which varies per file/model, so we
            // intentionally do NOT match those — check the header (0..3) and
            // the "CR" signature (8..9) independently.
            val cr2Header    = byteArrayOf(0x43, 0x52) // "CR" at offset 8

            val littleEndianTiff = source.rangeEquals(0, magic1.toByteString())
            if (littleEndianTiff && source.rangeEquals(8, cr2Header.toByteString())) {
                // It's a CR2 — let LibRaw handle it, not the coil TIFF decoder.
                return false
            }
            if (littleEndianTiff) return true
            return source.rangeEquals(0, magic2.toByteString())
        }
    }
}