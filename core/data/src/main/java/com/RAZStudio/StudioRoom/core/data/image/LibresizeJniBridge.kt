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

import android.graphics.Bitmap
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeSharpen

/**
 * Thin wrapper around libresize.so — the same native library bundled in XnResize.
 *
 * Session model (mirrors XnResize NativeBridge usage in bi0.smali):
 *   1. invokeStart(path, 32)  — load source image into native session
 *   2. invokeResize2(...)     — scale and return result Bitmap
 *   3. invokeDestroy()        — free native resources
 *
 * invokeResize2 parameter order (from bi0.smali / range v0..v8):
 *   0 dstWidth, 1 dstHeight, 2 canvasColor, 3 highMethod (bool),
 *   4 sharpen (0-100), 5 borderSize, 6 borderColor, 7 borderMode, 8 fitMode
 */
internal object LibresizeJniBridge {

    init {
        System.loadLibrary("resize")
    }

    @JvmStatic private external fun invokeStart(path: String, flags: Int): Bitmap?
    @JvmStatic private external fun invokeResize2(
        dstWidth: Int, dstHeight: Int,
        canvasColor: Int, highMethod: Int, sharpen: Int,
        borderSize: Int, borderColor: Int, borderMode: Int,
        fitMode: Int
    ): Bitmap?
    @JvmStatic private external fun invokeDestroy()

    /**
     * Scale the image at [srcPath] to [dstWidth]×[dstHeight] using libresize
     * high-quality algorithm. Caller must ensure output is smaller than input.
     */
    @Synchronized
    fun scale(
        srcPath: String,
        dstWidth: Int,
        dstHeight: Int,
        sharpen: ResizeSharpen = ResizeSharpen.None
    ): Bitmap? = try {
        invokeStart(srcPath, 32) ?: return null
        invokeResize2(
            dstWidth    = dstWidth,
            dstHeight   = dstHeight,
            canvasColor = 0,
            highMethod  = 1,
            sharpen     = sharpen.toNativeSharpen(),
            borderSize  = 0,
            borderColor = 0,
            borderMode  = 0,
            fitMode     = 0
        )
    } finally {
        invokeDestroy()
    }

    private fun ResizeSharpen.toNativeSharpen(): Int = when (this) {
        ResizeSharpen.None   -> 0
        ResizeSharpen.Low    -> 30
        ResizeSharpen.Medium -> 60
        ResizeSharpen.High   -> 100
    }
}
