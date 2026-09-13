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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.io

import android.util.Log
import com.raz.razstudio.lib.raw.DecodeStatus
import com.raz.razstudio.lib.raw.NativeRawDecoderV2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Decodes a RAW file to a CV_16UC3 BGR Mat suitable as input to the
 * segmented-CLAHE pipeline. Skips every tonemap / gamma / colour-matrix
 * step downstream of LibRaw — what we want at this stage is the WB-
 * applied linear-sRGB BGR uint16 buffer that LibRaw natively emits in
 * `output_color=1` mode.
 *
 * Why this exists separately from the production [LibRawJniBridge]
 *  - LibRawJniBridge always converts the uint16 buffer into ARGB or
 *    float16 RGBA. Our pipeline doesn't want either — feeding it
 *    pre-tonemapped data defeats the wide-histogram premise of the
 *    8-bit workspace.
 *  - V1 / V2 dispatch is collapsed: we only use V2 here because
 *    `decodeIntoBgr16Buffer` is the lowest-overhead path that bypasses
 *    the gamma + matrix kernel V1 always runs.
 *
 * Memory profile for a 24 MP RAW:
 *   - DirectByteBuffer (off-heap):     ~121 MB
 *   - CV_16UC3 Mat (OpenCV-owned):     ~121 MB (copied from buffer)
 * The ByteBuffer is freed when the function returns; only the Mat
 * survives. Caller releases via [Mat.release].
 */
internal object Raw16ULoader {

    private const val TAG = "Raw8Bit.Loader"

    /** Decode result. Caller owns [bgr] and must call `bgr.release()`. */
    data class Result(
        val bgr: Mat,
        val width: Int,
        val height: Int,
    )

    private val openCvLoaded = AtomicBoolean(false)

    /**
     * Force the OpenCV native lib to load on this process before any
     * Mat constructor runs. The project's [com.RAZStudio.opencv_tools
     * .utils.OpenCV] base-class loads it lazily on first subclass
     * instantiation — but the raw_8bit pipeline doesn't go through any
     * of those helper classes, so we have to trigger the load
     * explicitly. `OpenCVLoader.initLocal()` is idempotent.
     */
    private fun ensureOpenCvLoaded() {
        if (openCvLoaded.compareAndSet(false, true)) {
            val ok = OpenCVLoader.initLocal()
            Log.i(TAG, "ensureOpenCvLoaded: OpenCVLoader.initLocal() = $ok")
        }
    }

    /**
     * @param userQual LibRaw demosaic-quality selector — match the
     *                 16-bit workspace default of -1 (RAZ_AMAZE / RCD)
     *                 so the 8-bit pipeline benefits from the same
     *                 ~1.5 dB CPSNR improvement over plain AHD (3).
     *                 Other values: 0=Linear, 1=VNG, 2=PPG, 3=AHD,
     *                 4=DCB, 5=AHD-Mod, 11=LMMSE, 12=AMaZE, -1=RCD.
     */
    suspend fun decode(
        filePath: String,
        halfSize: Boolean = false,
        userQual: Int = -1,
    ): Result? = withContext(Dispatchers.Default) {
        ensureOpenCvLoaded()
        Log.i(TAG, "decode: path=$filePath halfSize=$halfSize userQual=$userQual")

        // Probe dimensions first so we know the buffer size LibRaw will need.
        val outSize = IntArray(3)
        val dims = readPreviewDimensions(filePath)
            ?: return@withContext null.also {
                Log.e(TAG, "decode: dimension probe failed")
            }
        val (previewW, previewH) = dims
        val allocW = if (halfSize) previewW else previewW * 2
        val allocH = if (halfSize) previewH else previewH * 2
        outSize[0] = allocW; outSize[1] = allocH

        // uint16 BGR — 3 channels × 2 bytes per pixel.
        val bufferBytes = allocW.toLong() * allocH.toLong() * 3L * 2L
        var buf: ByteBuffer = ByteBuffer.allocateDirect(bufferBytes.toInt())
            .order(ByteOrder.nativeOrder())

        var status = decodeOnce(filePath, halfSize, userQual, buf, outSize)
        // Some CR2 variants ignore LibRaw's half_size flag and decode at full
        // resolution anyway. V2 reports the real dims even on
        // InsufficientBuffer, so we can grow the buffer and retry once.
        if (status == DecodeStatus.InsufficientBuffer &&
            outSize[0] > 0 && outSize[1] > 0
        ) {
            val needed = outSize[0].toLong() * outSize[1].toLong() * 6L
            Log.w(TAG, "decode: buffer too small for ${outSize[0]}x${outSize[1]} (needed=$needed); reallocating + retrying")
            buf = ByteBuffer.allocateDirect(needed.toInt()).order(ByteOrder.nativeOrder())
            status = decodeOnce(filePath, halfSize, userQual, buf, outSize)
        }
        if (status != DecodeStatus.Ok) {
            Log.e(TAG, "decode: status=$status outSize=${outSize[0]}x${outSize[1]}")
            return@withContext null
        }

        val actualW = outSize[0]
        val actualH = outSize[1]
        // LibRaw v2 occasionally emits an extra empty row at the bottom of
        // the buffer. Trim it the same way LibRawJniBridge does — the
        // downstream stage trusts (w, h-1) here.
        val trimH = (actualH - 1).coerceAtLeast(1)

        // Sanity probe — the same multi-position read that LibRawJniBridge
        // uses to catch silent native OOM. If 6+/8 probes are zero or the
        // max sample is under 256, the buffer is junk.
        buf.rewind()
        if (!sanityCheck(buf)) {
            Log.e(TAG, "decode: sanity check failed — buffer looks empty / corrupt")
            return@withContext null
        }

        // Copy the DirectByteBuffer's uint16 BGR samples into an
        // OpenCV-allocated CV_16UC3 Mat via Mat.put(short[]). We can't
        // use the Mat(rows, cols, type, ByteBuffer) constructor — that
        // overload requires an OpenCV 4.6+ build with the buffer-aware
        // JNI symbols, which the bundled opencv-tools doesn't expose.
        // The ShortArray hop costs one extra copy (~120 MB walk on a
        // 24 MP RAW) — acceptable in exchange for guaranteed binding
        // availability.
        buf.rewind()
        val shortCount = actualW * trimH * 3
        val shortPixels = ShortArray(shortCount)
        buf.asShortBuffer().get(shortPixels, 0, shortCount)
        val owned = Mat(trimH, actualW, CvType.CV_16UC3)
        owned.put(0, 0, shortPixels)

        Log.i(TAG, "decode: ok — ${actualW}x${trimH} CV_16UC3 BGR (status=$status)")
        Result(bgr = owned, width = actualW, height = trimH)
    }

    private fun decodeOnce(
        filePath: String,
        halfSize: Boolean,
        userQual: Int,
        buf: ByteBuffer,
        outDims: IntArray,
    ): DecodeStatus {
        val code = NativeRawDecoderV2.decodeIntoBgr16Buffer(
            path = filePath,
            halfSize = halfSize,
            userQual = userQual,
            gamut = 0,
            highlightMode = 0,
            nrEnabled = false,
            nrLuma = 0,
            nrChroma = 0,
            caCorrectionEnabled = false,
            dcpProfileId = "auto",
            cancelFlagPtr = 0L,
            outBuffer = buf,
            outDims = outDims,
        )
        return DecodeStatus.fromCode(code)
    }

    private fun readPreviewDimensions(filePath: String): Pair<Int, Int>? {
        val file = java.io.File(filePath)
        if (!file.exists()) return null
        val data = file.readBytes()
        val out = IntArray(3)
        val ok = com.raz.razstudio.lib.raw.NativeRawDecoder
            .readRawPreviewDimensions(data, out)
        return if (ok && out[0] > 0 && out[1] > 0) out[0] to out[1] else null
    }

    private fun sanityCheck(buf: ByteBuffer): Boolean {
        val limit = buf.limit()
        var nonZero = 0
        var maxV = 0
        val offsets = IntArray(8) { idx ->
            ((idx * 2 + 1) * limit / 16) and 1.inv()
        }
        for (o in offsets) {
            if (o in 0 until limit - 1) {
                val v = buf.getShort(o).toInt() and 0xFFFF
                if (v != 0) nonZero++
                if (v > maxV) maxV = v
            }
        }
        Log.d(TAG, "sanityCheck: nonZero=$nonZero/8 max=$maxV bufLimit=$limit")
        return nonZero >= 3 && maxV >= 256
    }
}
