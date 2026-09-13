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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.BuildConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata
import com.raz.razstudio.lib.raw.DecodeStatus
import com.raz.razstudio.lib.raw.NativeRawDecoder
import com.raz.razstudio.lib.raw.NativeRawDecoderV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LibRawJniBridge {

    private const val TAG = "LibRawBridge"

    val isAvailable: Boolean by lazy {
        val ok = runCatching {
            // Touch the object to trigger its init block (System.loadLibrary)
            NativeRawDecoder.javaClass
            true
        }.getOrElse { e ->
            Log.e(TAG, "libraw_decoder.so load FAILED: ${e.message}", e)
            false
        }
        Log.i(TAG, "isAvailable=$ok")
        ok
    }

    // ── Supported extensions ──────────────────────────────────────────────────

    val supportedExtensions: Set<String> = setOf(
        "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "rw2", "orf", "raf",
        "pef", "ptx", "dng", "3fr", "mef", "mrw", "erf", "kdc", "dcr",
        "raw", "rwl", "mos", "iiq", "x3f", "rwz",
    )

    // ── Public coroutine API ──────────────────────────────────────────────────

    /**
     * @param knownDims  Optional (previewW, previewH) from a prior [extractMetadata] call.
     *                   When provided, the redundant [NativeRawDecoder.readRawPreviewDimensions]
     *                   call is skipped (~340 ms saved per decode).
     */
    suspend fun decodeToLinear(
        filePath: String,
        halfSize: Boolean,
        userQual: Int = 3, // AHD default
        knownDims: Pair<Int, Int>? = null,
        /**
         * Per-call Rayxie chromatic-aberration override. Defaults to true for backward
         * compatibility (CA has always been applied on full-res in V1). The workspace
         * selector toggle plumbs this through from `WorkspaceConfig.caCorrectionEnabled`
         * via FullResPipeline.run. Ignored when [halfSize] is true — CA is always
         * skipped at half-res per spec §7.
         */
        caCorrectionEnabled: Boolean = true,
        /**
         * Color fringing correction mode threaded into LibRaw `aber[]` at
         * decode AND used to gate the runtime purple-fringe shader pass.
         *   0 = Off    — no aber[], no purple-fringe pass
         *   1 = Light  — aber[1.0, 1.0] (LibRaw lateral CA)
         *   2 = Strong — aber[1.0, 1.0] + runtime purple-fringe pass
         * Matches the ordinal of [ColorFringingMode]. Defaults to 2 (Strong)
         * so callers that don't explicitly set this still get the new default
         * behaviour.
         */
        colorFringingMode: Int = 2,
        /**
         * Optional native cancel-flag pointer. When non-zero, the watchdog
         * coroutine can call `NativeRawDecoderV2.cancel(ptr)` to make the
         * underlying decode return early at its next checkpoint. Defaults to
         * 0L (no cancellation) for callers that don't need it.
         */
        cancelFlagPtr: Long = 0L,
        /**
         * Demosaic-aware noise reduction — runs after demosaic, before the
         * pack-out conversion. Ignored when [halfSize] is true (decode_core
         * skips NR for half-res preview to keep the canvas responsive).
         */
        nrEnabled: Boolean = false,
        nrLuma: Int = 0,
        nrChroma: Int = 0,
    ): LinearDecodeResult? =
        withContext(Dispatchers.Default) {
            Log.d(TAG, "decodeToLinear: path=$filePath halfSize=$halfSize isAvailable=$isAvailable knownDims=$knownDims")
            if (!isAvailable) {
                Log.e(TAG, "decodeToLinear: library not available, aborting")
                return@withContext null
            }
            // Force GC before the big allocations. A full-res decode needs ~150 MB on
            // the JVM heap (the pixels ByteArray), and on devices with a 512 MB heap
            // cap that allocation OOMs if prior bitmaps haven't been collected yet.
            // System.gc() is a hint — pair it with runFinalization so any Bitmap
            // native references that are pending get released.
            run {
                val rt = Runtime.getRuntime()
                val beforeFree = rt.freeMemory()
                System.gc()
                System.runFinalization()
                System.gc()
                Log.w(
                    TAG,
                    "decodeToLinear: pre-decode GC free=${rt.freeMemory() / (1024 * 1024)}MB " +
                        "(was ${beforeFree / (1024 * 1024)}MB) max=${rt.maxMemory() / (1024 * 1024)}MB " +
                        "total=${rt.totalMemory() / (1024 * 1024)}MB",
                )
            }
            runCatching {
                val file = java.io.File(filePath)
                Log.d(TAG, "decodeToLinear: file exists=${file.exists()} size=${file.length()} ext=${file.extension}")
                if (!file.exists()) {
                    Log.e(TAG, "decodeToLinear: file does not exist at $filePath")
                    return@withContext null
                }

                val data = file.readBytes()
                Log.d(TAG, "decodeToLinear: read ${data.size} bytes from file")

                val outSize = IntArray(3)

                // Use caller-supplied dims when available to avoid a redundant open+unpack (~340ms).
                val previewW: Int
                val previewH: Int
                if (knownDims != null) {
                    previewW = knownDims.first
                    previewH = knownDims.second
                    outSize[0] = previewW
                    outSize[1] = previewH
                    Log.d(TAG, "decodeToLinear: using knownDims ${previewW}x${previewH} (skipping readRawPreviewDimensions)")
                } else {
                    Log.d(TAG, "decodeToLinear: calling readRawPreviewDimensions...")
                    val dimsOk = NativeRawDecoder.readRawPreviewDimensions(data, outSize)
                    Log.d(TAG, "decodeToLinear: readRawPreviewDimensions returned $dimsOk outSize=${outSize[0]}x${outSize[1]}")
                    if (!dimsOk) {
                        Log.e(TAG, "decodeToLinear: readRawPreviewDimensions failed")
                        return@withContext null
                    }
                    previewW = outSize[0]
                    previewH = outSize[1]
                }

                // readRawPreviewDimensions returns the *preview* dims (already 2× downsampled).
                // For halfSize=true we use those. For halfSize=false the native full-res decode
                // writes at the *full* native sensor size, which is 2× each axis = 4× pixels.
                val allocW = if (halfSize) previewW else previewW * 2
                val allocH = if (halfSize) previewH else previewH * 2
                Log.d(TAG, "decodeToLinear: allocating buffer for ${allocW}x${allocH} (preview ${previewW}x${previewH} halfSize=$halfSize)")

                val bufferBytes = allocW * allocH * 3 * 2
                // var (not val) because the V2 retry path may reallocate larger when a CR2
                // variant ignores LibRaw's half_size flag and returns full-res data instead.
                var buf = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.nativeOrder())

                // v2-integration — production gate: BuildConfig.USE_RAW_V2 routes the
                // preview decode through NativeRawDecoderV2's decode_core dispatcher.
                // Output layout (uint16 RGB DirectByteBuffer) matches V1 bit-for-bit so
                // downstream Stage A/B/C code is unchanged. Default off; enable with
                //     ./gradlew :app:assembleFossDebug -PuseRawV2=true
                // The V2 path:
                //   • Uses the same LibRaw config as V1 (output_color=1, output_bps=16,
                //     no_auto_bright, gamm=1.0, user_flip=-1, half_size).
                //   • Cancel-flag passthrough is no-op here (no cancellation in the legacy
                //     preview path; future wiring lands when pipelines move to V2 wholesale).
                //   • userQual < 0 (RAZAmaze/RCD): V2's decode_core owns this path now —
                //     it calls the same `rcd_demosaic_to_buf` (Kodak low-ISO CPSNR ≈ 39.94
                //     dB, +0.8 dB over AMaZE) as V1, exposed via raw_decoder_shared.h.
                //     Initial Canon EOS 6D test (logcat-v2.txt 2026-05-24) hung because V2
                //     was dispatching user_qual=-1 into LibRaw stock which has no RCD;
                //     fixed by porting the algorithm. Half-res preview still uses Linear
                //     bilinear (RCD requires the full Bayer image).
                val decodeOk = if (BuildConfig.USE_RAW_V2) {
                    Log.i(TAG, "decodeToLinear[V2]: calling decodeIntoBgr16Buffer halfSize=$halfSize userQual=$userQual ca=$caCorrectionEnabled nr=$nrEnabled($nrLuma/$nrChroma)")
                    val code = NativeRawDecoderV2.decodeIntoBgr16Buffer(
                        path = filePath,
                        halfSize = halfSize,
                        userQual = userQual,
                        gamut = 0,             // sRGB — matches legacy output_color=1
                        highlightMode = 0,     // Off (LibRaw clip-to-white) — matches legacy
                        nrEnabled = nrEnabled,
                        nrLuma = nrLuma,
                        nrChroma = nrChroma,
                        caCorrectionEnabled = caCorrectionEnabled,
                        dcpProfileId = "auto",
                        cancelFlagPtr = cancelFlagPtr,
                        outBuffer = buf,
                        outDims = outSize,
                    )
                    var status = DecodeStatus.fromCode(code)
                    Log.i(TAG, "decodeToLinear[V2]: status=$status outSize=${outSize[0]}x${outSize[1]}")

                    // Some CR2 variants ignore LibRaw's half_size flag and return full-res
                    // data anyway (verified on device 2026-05-24 with Canon EOS 6D IMG_2744;
                    // suspected IO.shrink logic in raw2image.cpp). V2 reports the actual
                    // decoded dims even on InsufficientBuffer so we can reallocate and retry
                    // exactly once — mirroring the legacy dimension-mismatch retry path
                    // below but at the V2 entry point.
                    if (status == DecodeStatus.InsufficientBuffer &&
                        outSize[0] > 0 && outSize[1] > 0) {
                        val needed = outSize[0].toLong() * outSize[1].toLong() * 6L
                        Log.w(TAG,
                            "decodeToLinear[V2]: buffer too small for ${outSize[0]}x${outSize[1]} " +
                                "(needed=$needed); reallocating + retrying")
                        val newBuf = ByteBuffer.allocateDirect(needed.toInt()).order(ByteOrder.nativeOrder())
                        val retryCode = NativeRawDecoderV2.decodeIntoBgr16Buffer(
                            path = filePath, halfSize = halfSize, userQual = userQual,
                            gamut = 0, highlightMode = 0,
                            nrEnabled = nrEnabled, nrLuma = nrLuma, nrChroma = nrChroma,
                            caCorrectionEnabled = caCorrectionEnabled,
                            dcpProfileId = "auto", cancelFlagPtr = cancelFlagPtr,
                            outBuffer = newBuf, outDims = outSize,
                        )
                        status = DecodeStatus.fromCode(retryCode)
                        Log.i(TAG, "decodeToLinear[V2]: retry status=$status outSize=${outSize[0]}x${outSize[1]}")
                        if (status == DecodeStatus.Ok) buf = newBuf
                    }
                    status == DecodeStatus.Ok
                } else if (halfSize) {
                    Log.d(TAG, "decodeToLinear: calling decodeRawPreviewLinearIntoBuffer...")
                    // Prefer the path-based variant — skips ~30 MB JVM
                    // alloc + ~30 MB JNI byte copy. Falls back to ByteArray
                    // variant if the JNI symbol isn't present (older .so).
                    NativeRawDecoder.decodeRawPreviewLinearIntoBufferFromPath(filePath, outSize, buf, colorFringingMode).also {
                        Log.d(TAG, "decodeToLinear: decodeRawPreviewLinearIntoBuffer returned $it outSize=${outSize[0]}x${outSize[1]}")
                    }
                } else if (userQual < 0) {
                    // userQual = -1 → RAZAmaze (RCD) path
                    Log.d(TAG, "decodeToLinear: calling decodeRawLinearIntoBufferRcd (RAZAmaze)...")
                    NativeRawDecoder.decodeRawLinearIntoBufferRcdFromPath(filePath, outSize, buf, colorFringingMode).also {
                        Log.d(TAG, "decodeToLinear: decodeRawLinearIntoBufferRcd returned $it outSize=${outSize[0]}x${outSize[1]}")
                    }
                } else {
                    Log.d(TAG, "decodeToLinear: calling decodeRawLinearIntoBuffer userQual=$userQual...")
                    NativeRawDecoder.decodeRawLinearIntoBufferFromPath(filePath, outSize, buf, userQual, colorFringingMode).also {
                        Log.d(TAG, "decodeToLinear: decodeRawLinearIntoBuffer returned $it outSize=${outSize[0]}x${outSize[1]}")
                    }
                }

                if (!decodeOk) {
                    Log.e(TAG, "decodeToLinear: native decode returned false")
                    return@withContext null
                }

                // outSize is updated by the native call to reflect actual decoded dimensions
                // (may differ from pre-calculated w/h due to sensor orientation swap)
                var actualW = outSize[0]
                var actualH = outSize[1]
                var workingBuf = buf
                Log.d(TAG, "decodeToLinear: actual decoded size ${actualW}x${actualH}")

                // Dimension mismatch retry: portrait-oriented Canon CR2 files (and a few
                // other rotated sensors) trip a LibRaw bug where `readRawPreviewDimensions`
                // returns landscape-orientation half-dims but `decodeRawPreviewLinearIntoBuffer`
                // writes at the rotated full-dim layout. Result: the buffer we allocated is
                // too small for the pixels the native side just wrote. Reallocate and re-decode.
                val requiredBytes = actualW.toLong() * actualH.toLong() * 6L
                if (requiredBytes > workingBuf.limit()) {
                    Log.w(
                        TAG,
                        "decodeToLinear: buffer too small (${workingBuf.limit()} < $requiredBytes); " +
                            "reallocating for ${actualW}x${actualH} and re-decoding",
                    )
                    workingBuf = ByteBuffer.allocateDirect(requiredBytes.toInt()).order(ByteOrder.nativeOrder())
                    // outSize is used as input *and* output by the native call. Pre-seed it
                    // with the dimensions we now know are correct so the native side allocates
                    // its working state at the right size.
                    outSize[0] = actualW
                    outSize[1] = actualH
                    val retryOk = if (BuildConfig.USE_RAW_V2) {
                        val code = NativeRawDecoderV2.decodeIntoBgr16Buffer(
                            path = filePath, halfSize = halfSize, userQual = userQual,
                            gamut = 0, highlightMode = 0,
                            nrEnabled = nrEnabled, nrLuma = nrLuma, nrChroma = nrChroma,
                            caCorrectionEnabled = caCorrectionEnabled,
                            dcpProfileId = "auto", cancelFlagPtr = cancelFlagPtr,
                            outBuffer = workingBuf, outDims = outSize,
                        )
                        DecodeStatus.fromCode(code) == DecodeStatus.Ok
                    } else if (halfSize) {
                        NativeRawDecoder.decodeRawPreviewLinearIntoBuffer(data, outSize, workingBuf)
                    } else if (userQual < 0) {
                        NativeRawDecoder.decodeRawLinearIntoBufferRcd(data, outSize, workingBuf)
                    } else {
                        NativeRawDecoder.decodeRawLinearIntoBuffer(data, outSize, workingBuf, userQual)
                    }
                    if (!retryOk) {
                        Log.e(TAG, "decodeToLinear: retry decode failed")
                        return@withContext null
                    }
                    actualW = outSize[0]
                    actualH = outSize[1]
                    Log.i(
                        TAG,
                        "decodeToLinear: retry succeeded ${actualW}x${actualH} bufLimit=${workingBuf.limit()}",
                    )
                }

                // LibRaw's bayer interpolation leaves the last row as a boundary artifact
                // (missing R photosites produce cyan). Trim it before conversion.
                val trimH = (actualH - 1).coerceAtLeast(1)

                // CPU-side Rayxie CA correction is DISABLED (2026-05-25).
                //
                // Even with -O3 + 4-thread parallel + H-only (V skipped via
                // RAYXIE_SKIP_VERTICAL), the algorithm's data-dependent edge-search
                // inside rayxie_rm_ca_row hits a pathological case on certain Canon
                // CR2 frames: high-contrast green/RB threshold-crossing density
                // means each row's worst-case work is ~700K ops × 3670 rows / 4
                // threads → still trips the 60s decode watchdog.
                //
                // Future plan: replace with a GPU fragment shader (barrel distortion
                // + spectrum offset) running on the existing GLES pipeline. CPU
                // version is gated off entirely until then. Lensfun is also dead
                // code at this point — Rayxie supplanted it; both are now off.
                //
                // The `caCorrectionEnabled` workspace toggle still drives whether
                // future GPU CA correction runs, so the UI state is preserved.
                if (!halfSize && caCorrectionEnabled) {
                    Log.i(TAG, "decodeToLinear: CA correction disabled (pending GPU shader implementation)")
                }

                Log.d(TAG, "decodeToLinear: converting uint16 BGR → float16 RGBA via native (trimH=$trimH)...")
                val pixelCount = actualW * trimH
                // Sanity-check the uint16 source buffer before conversion. After the
                // dimension-mismatch retry above this should always pass; the guard
                // remains as a defense against any future native-side regression.
                workingBuf.rewind()
                val midUint16Pos = (pixelCount / 2) * 3 * 2  // byte offset of mid-pixel R channel
                if (midUint16Pos < 0 || midUint16Pos + 2 > workingBuf.limit()) {
                    Log.e(
                        TAG,
                        "decodeToLinear: ABORT — dimension/buffer mismatch even after retry. " +
                            "actualW=$actualW actualH=$actualH trimH=$trimH pixelCount=$pixelCount " +
                            "midUint16Pos=$midUint16Pos workingBuf.limit=${workingBuf.limit()}",
                    )
                    return@withContext null
                }
                val u16Mid = workingBuf.getShort(midUint16Pos).toInt() and 0xFFFF
                Log.w(TAG, "decodeToLinear: uint16 buf mid-pixel R=$u16Mid (0=corrupt/zeros)")
                // Allocate float16 output as a DirectByteBuffer (off-heap) to avoid a ~161MB
                // JVM heap allocation that triggers OOM on full-res decodes.
                val f16Buf = ByteBuffer.allocateDirect(pixelCount * 8).order(ByteOrder.LITTLE_ENDIAN)
                Log.w(TAG, "decodeToLinear: f16Buf allocated capacity=${f16Buf.capacity()}")
                workingBuf.rewind()
                NativeRawDecoder.convertUint16BgrToFloat16Rgba(workingBuf, f16Buf, pixelCount)
                // Verify the float16 conversion actually wrote non-zero data.
                // Same guard as the uint16 read above — bail on mismatch instead of crash.
                f16Buf.rewind()
                val midF16Pos = (pixelCount / 2) * 8
                if (midF16Pos < 0 || midF16Pos + 2 > f16Buf.limit()) {
                    Log.e(
                        TAG,
                        "decodeToLinear: ABORT — f16 buffer too small. midF16Pos=$midF16Pos limit=${f16Buf.limit()}",
                    )
                    return@withContext null
                }
                val midF16 = f16Buf.getShort(midF16Pos).toInt() and 0xFFFF
                Log.w(TAG, "decodeToLinear: f16 mid-pixel=${midF16} (0=conversion wrote zeros)")
                // Copy off-heap → ByteArray for stage cache storage.
                //
                // The 134 MB ByteArray allocation is the single largest JVM heap
                // request in the pipeline, on a device with a 512 MB cap. When
                // the heap is fragmented (prior bitmaps, segmentation outputs)
                // this OOMs even though plenty of total memory remains. Wrap
                // the allocation in a GC-retry loop so we don't crash on a
                // transient fragmentation hiccup. After two attempts surface
                // null and let the watchdog kick in the embedded-JPEG fallback.
                f16Buf.rewind()
                val pixels: ByteArray = try {
                    ByteArray(pixelCount * 8).also { f16Buf.get(it) }
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "decodeToLinear: ByteArray($pixelCount * 8) OOM — GC + retry")
                    System.gc(); System.runFinalization(); System.gc()
                    f16Buf.rewind()
                    try {
                        ByteArray(pixelCount * 8).also { f16Buf.get(it) }
                    } catch (oom2: OutOfMemoryError) {
                        Log.e(TAG, "decodeToLinear: ByteArray OOM after GC retry — giving up",
                            oom2)
                        return@withContext null
                    }
                }
                Log.w(TAG, "decodeToLinear: pixels ByteArray size=${pixels.size}")

                Log.d(TAG, "decodeToLinear: calling readCamMul...")
                val camMulRaw = NativeRawDecoder.readCamMul(data).also {
                    Log.d(TAG, "decodeToLinear: readCamMul=${it?.toList()}")
                } ?: floatArrayOf(1f, 1f, 1f, 1f)
                // Normalize so G=1 — LibRaw returns raw ADU multipliers (e.g. R=2325, G=1024, B=1641).
                // All downstream consumers (WideGamutConverter highlight fix, UI temperature display)
                // expect ratios relative to green, not absolute ADU counts.
                val camMulG = camMulRaw.getOrElse(1) { 1f }.let { if (it == 0f) 1f else it }
                // G2 = camMulRaw[3]. Adobe DNGs leave this 0 (DNG spec uses
                // AsShotNeutral with 3 values, no per-CFA-position G2). When
                // it lands here as 0, the in-bounds `getOrElse` returns it as
                // is, and `0 / camMulG = 0` zeroes every G2 Bayer position,
                // producing a heavy pink/magenta cast over the whole image
                // (verified adb 2026-05-25 on Adobe-converted .dng). Fix:
                // treat 0 the same as missing — fall back to G1.
                val camMulG2Raw = camMulRaw.getOrElse(3) { camMulG }
                val camMulG2 = if (camMulG2Raw == 0f) camMulG else camMulG2Raw
                val camMul = floatArrayOf(
                    camMulRaw[0] / camMulG,
                    1f,
                    camMulRaw[2] / camMulG,
                    camMulG2 / camMulG,
                )
                Log.d(TAG, "decodeToLinear: camMul normalized=${camMul.toList()}")

                Log.d(TAG, "decodeToLinear: calling readLensMetadata...")
                val lensJson = runCatching {
                    NativeRawDecoder.readLensMetadata(data)?.let { org.json.JSONObject(it) }
                }.getOrNull()
                val camMake  = lensJson?.optString("camMake",  "").orEmpty()
                val camModel = lensJson?.optString("camModel", "").orEmpty()
                val lensModel = lensJson?.optString("lensModel", "").orEmpty()
                val focalLength = lensJson?.optDouble("focalLength", 0.0)?.toFloat() ?: 0f
                val aperture    = lensJson?.optDouble("aperture", 0.0)?.toFloat() ?: 0f
                val iso         = lensJson?.optInt("isoSpeed", 0) ?: 0
                val shutter     = lensJson?.optDouble("shutter", 0.0)?.toFloat() ?: 0f
                Log.d(TAG, "decodeToLinear: make='$camMake' model='$camModel' lens='$lensModel'")

                val ext = file.extension.lowercase()
                // V2 fills outSize[2] with LibRaw's imgdata.sizes.flip — note this is
                // LibRaw's INTERNAL flip encoding (0-7), NOT the EXIF Orientation tag
                // value. LibRaw maps EXIF → flip via the string "50132467" indexed by
                // (exif & 7) in tiff.cpp:628. The trick: `8 & 7 == 0`, so EXIF=8
                // (portrait, "left-bottom") indexes string[0] = '5' → LibRaw flip=5.
                //
                // Correct reverse mapping (verified 2026-05-24 on Canon EOS 6D
                // IMG_2743.CR2, a portrait shot that reported LibRaw flip=5):
                //   LibRaw 0 → EXIF 1 (landscape, top-left)
                //   LibRaw 1 → EXIF 2 (h-mirror landscape)
                //   LibRaw 2 → EXIF 4 (v-mirror landscape)
                //   LibRaw 3 → EXIF 3 (180° rotated)
                //   LibRaw 4 → EXIF 5 (portrait variant, transposed)
                //   LibRaw 5 → EXIF 8 (portrait CCW, "left-bottom") ← the one that bit us
                //   LibRaw 6 → EXIF 6 (portrait CW, "right-top")
                //   LibRaw 7 → EXIF 7 (portrait variant, transverse)
                //
                // applyExifOrientation expects EXIF values 1-8, so we translate here.
                // V1 paths leave outSize[2]=0 → maps to EXIF=1 (no rotation, matches the
                // legacy hardcoded value).
                val flipFromV2 = outSize.getOrElse(2) { 0 }
                val resolvedOrientation = when (flipFromV2) {
                    0 -> 1
                    1 -> 2
                    2 -> 4
                    3 -> 3
                    4 -> 5
                    5 -> 8   // portrait CCW (most common Canon portrait grip-up)
                    6 -> 6   // portrait CW
                    7 -> 7
                    else -> 1
                }
                android.util.Log.i(TAG, "decodeToLinear: LibRaw flip=$flipFromV2 → EXIF orientation=$resolvedOrientation")
                val metadata = RawMetadata(
                    sourceUri          = filePath,
                    fileSha256         = "",
                    fileExtension      = ext,
                    rawWidth           = allocW,
                    rawHeight          = allocH,
                    outputWidth        = actualW,
                    outputHeight       = trimH,
                    orientation        = resolvedOrientation,
                    rgbCam             = FloatArray(12).also { it[0] = 1f; it[5] = 1f; it[10] = 1f },
                    cameraWhiteBalance = if (camMul.all { it == 0f }) floatArrayOf(1f, 1f, 1f, 1f) else camMul,
                    blackLevel         = 0,
                    whiteLevel         = 65535,
                    colorSpace         = 0,
                    dngVersion         = 0,
                    cameraMake         = camMake,
                    cameraModel        = camModel,
                    cameraSerial       = "",
                    softwareVersion    = "",
                    lensInfo           = lensModel,
                    lensMake           = "",
                    lensId             = 0,
                    focalLength        = focalLength,
                    focalLength35mm    = 0f,
                    aperture           = aperture,
                    shutterSpeed       = shutter,
                    iso                = iso,
                    exposureBias       = 0f,
                    focusDistanceNear  = 0f,
                    focusDistanceFar   = 0f,
                    flashFired         = false,
                    lightSource        = 0,
                    gpsLatitude        = null,
                    gpsLongitude       = null,
                    gpsAltitude        = null,
                    gpsTimestamp       = "",
                    artist             = "",
                    copyright          = "",
                    imageDescription   = "",
                    userComment        = "",
                    dateTimeOriginal   = "",
                    dateTimeDigitized  = "",
                    thumbnailBytes     = null,
                )
                Log.i(TAG, "decodeToLinear: SUCCESS ${actualW}x${trimH} (trimmed from ${actualW}x${actualH}) ext=$ext")
                LinearDecodeResult(width = actualW, height = trimH, pixels = pixels, metadata = metadata)
            }.getOrElse { e ->
                Log.e(TAG, "decodeToLinear: EXCEPTION ${e::class.simpleName}: ${e.message}", e)
                null
            }
        }

    suspend fun extractMetadata(filePath: String): RawMetadata? =
        withContext(Dispatchers.Default) {
            Log.d(TAG, "extractMetadata: path=$filePath")
            if (!isAvailable) return@withContext null
            runCatching {
                val data = java.io.File(filePath).readBytes()
                val outSize = IntArray(3)
                val ok = NativeRawDecoder.readRawPreviewDimensions(data, outSize)
                Log.d(TAG, "extractMetadata: dims ok=$ok ${outSize[0]}x${outSize[1]}")
                if (!ok) return@withContext null
                val camMulRaw = NativeRawDecoder.readCamMul(data) ?: floatArrayOf(1f, 1f, 1f, 1f)
                val camMulG = camMulRaw.getOrElse(1) { 1f }.let { if (it == 0f) 1f else it }
                // DNG G2-zero guard — see decodeToLinear's matching block above.
                val camMulG2Raw = camMulRaw.getOrElse(3) { camMulG }
                val camMulG2val = if (camMulG2Raw == 0f) camMulG else camMulG2Raw
                val camMul = floatArrayOf(
                    camMulRaw[0] / camMulG, 1f, camMulRaw[2] / camMulG,
                    camMulG2val / camMulG,
                )
                val ext = java.io.File(filePath).extension.lowercase()
                RawMetadata(
                    sourceUri          = filePath,
                    fileSha256         = "",
                    fileExtension      = ext,
                    rawWidth           = outSize[0],
                    rawHeight          = outSize[1],
                    outputWidth        = outSize[0],
                    outputHeight       = outSize[1],
                    orientation        = 1,
                    rgbCam             = FloatArray(12).also { it[0] = 1f; it[5] = 1f; it[10] = 1f },
                    cameraWhiteBalance = if (camMul.all { it == 0f }) floatArrayOf(1f, 1f, 1f, 1f) else camMul,
                    blackLevel         = 0, whiteLevel = 65535, colorSpace = 0, dngVersion = 0,
                    cameraMake = "", cameraModel = "", cameraSerial = "", softwareVersion = "",
                    lensInfo = "", lensMake = "", lensId = 0, focalLength = 0f, focalLength35mm = 0f,
                    aperture = 0f, shutterSpeed = 0f, iso = 0, exposureBias = 0f,
                    focusDistanceNear = 0f, focusDistanceFar = 0f, flashFired = false, lightSource = 0,
                    gpsLatitude = null, gpsLongitude = null, gpsAltitude = null, gpsTimestamp = "",
                    artist = "", copyright = "", imageDescription = "", userComment = "",
                    dateTimeOriginal = "", dateTimeDigitized = "", thumbnailBytes = null,
                )
            }.getOrElse { e ->
                Log.e(TAG, "extractMetadata: EXCEPTION ${e.message}", e)
                null
            }
        }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    fun parseMetadataFromJson(json: org.json.JSONObject, sourceUri: String, sha256: String): RawMetadata {
        fun floatArr(key: String, len: Int): FloatArray {
            val arr = json.optJSONArray(key) ?: return FloatArray(len)
            return FloatArray(minOf(len, arr.length())) { arr.getDouble(it).toFloat() }
        }
        return RawMetadata(
            sourceUri          = sourceUri, fileSha256 = sha256,
            fileExtension      = json.optString("extension", ""),
            rawWidth           = json.optInt("rawWidth", json.optInt("width", 0)),
            rawHeight          = json.optInt("rawHeight", json.optInt("height", 0)),
            outputWidth        = json.optInt("width", 0),
            outputHeight       = json.optInt("height", 0),
            orientation        = json.optInt("orientation", 1),
            rgbCam             = floatArr("rgbCam", 12).takeIf { it.isNotEmpty() }
                                     ?: FloatArray(12).also { it[0] = 1f; it[5] = 1f; it[10] = 1f },
            cameraWhiteBalance = floatArr("cameraWb", 4).also { if (it.all { v -> v == 0f }) it.fill(1f) },
            blackLevel         = json.optInt("blackLevel", 0),
            whiteLevel         = json.optInt("whiteLevel", 65535),
            colorSpace         = json.optInt("colorSpace", 0),
            dngVersion         = json.optInt("dngVersion", 0),
            cameraMake         = json.optString("make", ""), cameraModel = json.optString("model", ""),
            cameraSerial       = json.optString("serial", ""), softwareVersion = json.optString("software", ""),
            lensInfo           = json.optString("lens", ""), lensMake = json.optString("lensMake", ""),
            lensId             = json.optInt("lensId", 0),
            focalLength        = json.optDouble("focalLength", 0.0).toFloat(),
            focalLength35mm    = json.optDouble("focalLength35mm", 0.0).toFloat(),
            aperture           = json.optDouble("aperture", 0.0).toFloat(),
            shutterSpeed       = json.optDouble("shutterSpeed", 0.0).toFloat(),
            iso                = json.optInt("iso", 0),
            exposureBias       = json.optDouble("exposureBias", 0.0).toFloat(),
            focusDistanceNear  = json.optDouble("focusNear", 0.0).toFloat(),
            focusDistanceFar   = json.optDouble("focusFar", 0.0).toFloat(),
            flashFired         = json.optBoolean("flashFired", false),
            lightSource        = json.optInt("lightSource", 0),
            gpsLatitude        = json.optJSONArray("gps")?.optDouble(0),
            gpsLongitude       = json.optJSONArray("gps")?.optDouble(1),
            gpsAltitude        = json.optJSONArray("gps")?.optDouble(2),
            gpsTimestamp       = json.optString("gpsTime", ""),
            artist             = json.optString("artist", ""), copyright = json.optString("copyright", ""),
            imageDescription   = json.optString("description", ""), userComment = json.optString("userComment", ""),
            dateTimeOriginal   = json.optString("dateTimeOriginal", ""),
            dateTimeDigitized  = json.optString("dateTimeDigitized", ""),
            thumbnailBytes     = json.optString("thumbnail", "").takeIf { it.isNotEmpty() }
                                     ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) },
        )
    }

    fun metadataToJson(m: RawMetadata): org.json.JSONObject = org.json.JSONObject().apply {
        put("sha256", m.fileSha256); put("extension", m.fileExtension)
        put("width", m.outputWidth); put("height", m.outputHeight)
        put("rawWidth", m.rawWidth); put("rawHeight", m.rawHeight)
        put("orientation", m.orientation)
        val cam = org.json.JSONArray(); m.rgbCam.forEach { cam.put(it.toDouble()) }; put("rgbCam", cam)
        val wb = org.json.JSONArray(); m.cameraWhiteBalance.forEach { wb.put(it.toDouble()) }; put("cameraWb", wb)
        put("blackLevel", m.blackLevel); put("whiteLevel", m.whiteLevel)
        put("colorSpace", m.colorSpace); put("dngVersion", m.dngVersion)
        put("make", m.cameraMake); put("model", m.cameraModel); put("serial", m.cameraSerial)
        put("software", m.softwareVersion); put("lens", m.lensInfo); put("lensMake", m.lensMake)
        put("lensId", m.lensId); put("focalLength", m.focalLength.toDouble())
        put("focalLength35mm", m.focalLength35mm.toDouble())
        put("aperture", m.aperture.toDouble()); put("shutterSpeed", m.shutterSpeed.toDouble())
        put("iso", m.iso); put("exposureBias", m.exposureBias.toDouble())
        put("focusNear", m.focusDistanceNear.toDouble()); put("focusFar", m.focusDistanceFar.toDouble())
        put("flashFired", m.flashFired); put("lightSource", m.lightSource)
        if (m.gpsLatitude != null) {
            put("gps", org.json.JSONArray().apply {
                put(m.gpsLatitude); put(m.gpsLongitude ?: 0.0); put(m.gpsAltitude ?: 0.0)
            })
        }
        put("gpsTime", m.gpsTimestamp); put("artist", m.artist); put("copyright", m.copyright)
        put("description", m.imageDescription); put("userComment", m.userComment)
        put("dateTimeOriginal", m.dateTimeOriginal); put("dateTimeDigitized", m.dateTimeDigitized)
    }

    // ── Result type ───────────────────────────────────────────────────────────

    data class LinearDecodeResult(
        val width: Int,
        val height: Int,
        val pixels: ByteArray,   // float16 RGBA, width×height×8 bytes
        val metadata: RawMetadata,
    )

    /**
     * Variant for the 8-bit-only paths (single editor in BIT_8 workspace and
     * batch processing). Carries ARGB_8888 pixels as an IntArray — same shape
     * the downstream MacroProcessor / Bitmap.setPixels expects.
     *
     * Memory shape comparison for a 24 MP (5500×3669) RAW:
     *   - float16 path: 121 MB native uint16 + 161 MB f16 (was Java heap, now
     *     mapped file via decodeToLinearMapped) + 80 MB IntArray after Stage C
     *   - **uint8 path (this)**: 121 MB native uint16 + **80 MB IntArray**
     *     produced directly by the converter — no float16 intermediate at all.
     *
     * Skipping the float16 step also drops ~2.5× the per-pixel work (the
     * uint16-to-f16 conversion + the cam2srgb matrix multiplication + gamma
     * encoding collapse into one combined per-pixel kernel here).
     */
    data class LinearDecode8Result(
        val width: Int,
        val height: Int,
        val pixels: IntArray,    // ARGB_8888 packed, width×height ints
        val metadata: RawMetadata,
    )

    /**
     * 8-bit-only decode: uint16 BGR → uint8 ARGB in a single per-pixel kernel,
     * skipping the float16 intermediate entirely. Used by the 8-bit workspace
     * save path and by batch processing (which is locked to BIT_8). 16-bit
     * workspace continues to use [decodeToLinear] / [decodeToLinearMapped]
     * because precision past 8 bits per channel must be preserved through
     * Stage C and the macro pipeline.
     *
     * LibRaw's `output_color=1` mode already returns linear-sRGB BGR with white
     * balance applied, so no camera→sRGB matrix is needed here. We just:
     *   1. Read uint16 BGR (3 channels, 6 bytes/pixel)
     *   2. Normalize to float [0, 1]
     *   3. Apply sRGB gamma encode (piecewise transfer function)
     *   4. Pack into ARGB_8888 int (alpha=255)
     *
     * Total per-pixel cost: 3 multiplies + 3 powf-equivalents + 1 pack. On
     * a 24 MP image that's ~80 ms on a mid-range Android CPU.
     */
    suspend fun decodeToLinear8bit(
        filePath: String,
        halfSize: Boolean,
        userQual: Int = 3,
        knownDims: Pair<Int, Int>? = null,
    ): LinearDecode8Result? =
        withContext(Dispatchers.Default) {
            Log.d(TAG, "decodeToLinear8bit: path=$filePath halfSize=$halfSize")
            if (!isAvailable) {
                Log.e(TAG, "decodeToLinear8bit: library not available, aborting")
                return@withContext null
            }
            run {
                val rt = Runtime.getRuntime()
                System.gc(); System.runFinalization(); System.gc()
                Log.w(
                    TAG,
                    "decodeToLinear8bit: pre-decode GC free=${rt.freeMemory() / (1024 * 1024)}MB " +
                        "max=${rt.maxMemory() / (1024 * 1024)}MB",
                )
            }
            runCatching {
                val file = java.io.File(filePath)
                if (!file.exists()) return@runCatching null
                val data = file.readBytes()

                val outSize = IntArray(3)
                val previewW: Int; val previewH: Int
                if (knownDims != null) {
                    previewW = knownDims.first; previewH = knownDims.second
                    outSize[0] = previewW; outSize[1] = previewH
                } else {
                    if (!NativeRawDecoder.readRawPreviewDimensions(data, outSize)) return@runCatching null
                    previewW = outSize[0]; previewH = outSize[1]
                }
                val allocW = if (halfSize) previewW else previewW * 2
                val allocH = if (halfSize) previewH else previewH * 2

                // uint16 BGR buffer is the only native heap allocation. ~121 MB
                // for a 24 MP RAW, well within budget.
                val bufferBytes = allocW * allocH * 3 * 2
                val buf = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.nativeOrder())

                val decodeOk = if (halfSize) {
                    NativeRawDecoder.decodeRawPreviewLinearIntoBuffer(data, outSize, buf)
                } else if (userQual < 0) {
                    NativeRawDecoder.decodeRawLinearIntoBufferRcd(data, outSize, buf)
                } else {
                    NativeRawDecoder.decodeRawLinearIntoBuffer(data, outSize, buf, userQual)
                }
                if (!decodeOk) { Log.e(TAG, "decodeToLinear8bit: native decode returned false"); return@runCatching null }

                var actualW = outSize[0]; var actualH = outSize[1]
                var workingBuf = buf
                val requiredBytes = actualW.toLong() * actualH.toLong() * 6L
                if (requiredBytes > workingBuf.limit()) {
                    Log.w(TAG, "decodeToLinear8bit: buffer too small; reallocating for ${actualW}x${actualH}")
                    workingBuf = ByteBuffer.allocateDirect(requiredBytes.toInt()).order(ByteOrder.nativeOrder())
                    outSize[0] = actualW; outSize[1] = actualH
                    val retryOk = if (halfSize) {
                        NativeRawDecoder.decodeRawPreviewLinearIntoBuffer(data, outSize, workingBuf)
                    } else if (userQual < 0) {
                        NativeRawDecoder.decodeRawLinearIntoBufferRcd(data, outSize, workingBuf)
                    } else {
                        NativeRawDecoder.decodeRawLinearIntoBuffer(data, outSize, workingBuf, userQual)
                    }
                    if (!retryOk) return@runCatching null
                    actualW = outSize[0]; actualH = outSize[1]
                }

                val trimH = (actualH - 1).coerceAtLeast(1)
                if (!halfSize) {
                    workingBuf.rewind()
                    NativeRawDecoder.correctFringing(workingBuf, actualW, trimH, 2000)
                }

                val pixelCount = actualW * trimH

                // Sanity check on uint16 buffer — probe 8 spread positions so a
                // zero-filled or near-zero buffer (silent native OOM during
                // demosaic, or partial corruption) is caught before we waste
                // time encoding it as a solid-color PNG.
                workingBuf.rewind()
                val bufLimit = workingBuf.limit()
                var nonZeroProbes = 0
                var totalVal = 0L
                var maxVal = 0
                val probeOffsets = IntArray(8) { idx ->
                    // Spread across the buffer at 1/16, 3/16, 5/16... fractions
                    // so we sample interior pixels, not just edges/start.
                    ((idx * 2 + 1) * bufLimit / 16) and 1.inv()  // round to short alignment
                }
                for (off in probeOffsets) {
                    if (off in 0 until bufLimit - 1) {
                        val v = workingBuf.getShort(off).toInt() and 0xFFFF
                        if (v != 0) nonZeroProbes++
                        totalVal += v
                        if (v > maxVal) maxVal = v
                    }
                }
                Log.i(
                    TAG,
                    "decodeToLinear8bit: probe nonZero=$nonZeroProbes/8 avg=${totalVal / 8} max=$maxVal " +
                        "bufLimit=$bufLimit pixelCount=$pixelCount",
                )
                // If 6+ of 8 probes are zero, the buffer is corrupt/empty.
                // We allow 2 probes to be zero because the edge of the sensor
                // (masked pixels) sometimes reads as 0.
                if (nonZeroProbes < 3) {
                    Log.w(
                        TAG,
                        "decodeToLinear8bit: uint16 buf mostly zero — native decode likely failed silently (silent OOM)",
                    )
                    return@runCatching null
                }
                // Additional check: if the max value across 8 probes is suspiciously
                // low (< 256), the buffer is technically non-zero but contains no
                // real image data — would compress to a solid-color PNG.
                if (maxVal < 256) {
                    Log.w(
                        TAG,
                        "decodeToLinear8bit: uint16 buf max=$maxVal across 8 probes — no real image data",
                    )
                    return@runCatching null
                }

                // ── uint16 BGR → uint8 ARGB conversion (combined per-pixel kernel) ──
                //
                // Read as ShortBuffer for fast bulk access (no boundary check per
                // get()). Each iteration reads 3 shorts (B, G, R) and writes one int
                // (ARGB packed).
                //
                // The sRGB gamma encode uses the standard IEC 61966-2-1 piecewise
                // transfer function:
                //   v_sRGB = 12.92 * v_linear                       (v ≤ 0.0031308)
                //   v_sRGB = 1.055 * v_linear^(1/2.4) - 0.055       (otherwise)
                //
                // Precomputed 1024-entry uint16-bin → uint8 LUT to avoid a powf per
                // channel per pixel. The 1024 bins are enough for visually
                // imperceptible quantization across the 0..1 range.
                val gammaLut = buildSrgbGammaLut1024()
                val src = workingBuf.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
                src.rewind()
                val out = IntArray(pixelCount)
                var i = 0
                while (i < pixelCount) {
                    // uint16 in BGR order from LibRaw
                    val b16 = (src.get().toInt() and 0xFFFF)
                    val g16 = (src.get().toInt() and 0xFFFF)
                    val r16 = (src.get().toInt() and 0xFFFF)
                    // Bin into [0, 1023] for the gamma LUT. Equivalent to clamping
                    // to [0, 65535] then >> 6 — preserving full uint16 range.
                    val rIdx = (r16 ushr 6).coerceAtMost(1023)
                    val gIdx = (g16 ushr 6).coerceAtMost(1023)
                    val bIdx = (b16 ushr 6).coerceAtMost(1023)
                    val r = gammaLut[rIdx]
                    val g = gammaLut[gIdx]
                    val b = gammaLut[bIdx]
                    out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    i++
                }
                // Diagnostic: sample 8 spread output pixels to confirm we produced
                // varied content (not all zeros / not all identical).
                val outProbes = IntArray(8) { idx -> out[(idx * 2 + 1) * pixelCount / 16] }
                val uniqueCount = outProbes.toSet().size
                Log.i(
                    TAG,
                    "decodeToLinear8bit: output probes=${outProbes.joinToString { String.format("0x%08X", it) }} " +
                        "unique=$uniqueCount/8",
                )
                if (uniqueCount < 3) {
                    Log.w(
                        TAG,
                        "decodeToLinear8bit: output has only $uniqueCount distinct probes — would be solid color. Rejecting.",
                    )
                    return@runCatching null
                }

                // Build metadata from lens JSON (rgbCam parsed if present, defaulted
                // to identity-style fallback otherwise — same as decodeToLinearMapped).
                val camMulRaw = NativeRawDecoder.readCamMul(data) ?: floatArrayOf(1f, 1f, 1f, 1f)
                val camMulG = camMulRaw.getOrElse(1) { 1f }.let { if (it == 0f) 1f else it }
                // DNG G2-zero guard — see decodeToLinear's matching block at the top.
                val camMulG2Raw = camMulRaw.getOrElse(3) { camMulG }
                val camMulG2val = if (camMulG2Raw == 0f) camMulG else camMulG2Raw
                val camMul = floatArrayOf(
                    camMulRaw[0] / camMulG, 1f, camMulRaw[2] / camMulG,
                    camMulG2val / camMulG,
                )
                val lensJson = runCatching {
                    NativeRawDecoder.readLensMetadata(data)?.let { org.json.JSONObject(it) }
                }.getOrNull()
                val camMake  = lensJson?.optString("camMake",  "").orEmpty()
                val camModel = lensJson?.optString("camModel", "").orEmpty()
                val ext = file.extension.uppercase()
                val orientation = lensJson?.optInt("orientation", 1) ?: 1
                val rgbCam = lensJson?.let { obj ->
                    val arr = obj.optJSONArray("rgbCam")
                    if (arr != null && arr.length() == 12)
                        FloatArray(12) { i2 -> arr.optDouble(i2).toFloat() }
                    else FloatArray(12)
                } ?: FloatArray(12)

                val metadata = RawMetadata(
                    sourceUri          = "",
                    fileSha256         = "",
                    fileExtension      = ext,
                    rawWidth           = actualW,
                    rawHeight          = trimH,
                    outputWidth        = actualW,
                    outputHeight       = trimH,
                    rgbCam             = rgbCam,
                    cameraWhiteBalance = camMul,
                    cameraMake         = camMake,
                    cameraModel        = camModel,
                    orientation        = orientation,
                )

                LinearDecode8Result(
                    width    = actualW,
                    height   = trimH,
                    pixels   = out,
                    metadata = metadata,
                )
            }.getOrElse { e ->
                Log.e(TAG, "decodeToLinear8bit: EXCEPTION ${e::class.simpleName}: ${e.message}", e)
                null
            }
        }

    // sRGB piecewise transfer function, baked into a 1024-entry LUT. Index is
    // linear value * 1023 (i.e. uint16 >> 6). Output is gamma-encoded [0, 255].
    private val srgbGammaLut1024Cache: IntArray by lazy { buildSrgbGammaLutImpl() }
    private fun buildSrgbGammaLut1024(): IntArray = srgbGammaLut1024Cache
    private fun buildSrgbGammaLutImpl(): IntArray {
        val lut = IntArray(1024)
        for (i in 0 until 1024) {
            val v = i / 1023.0
            val encoded = if (v <= 0.0031308) {
                12.92 * v
            } else {
                1.055 * Math.pow(v, 1.0 / 2.4) - 0.055
            }
            lut[i] = (encoded.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /**
     * Variant of [LinearDecodeResult] where the float16 pixels live in a
     * memory-mapped file rather than the Java heap. Used by the save path on
     * memory-constrained devices: the 161 MB pixel buffer would OOM the JVM
     * heap, but a `FileChannel.map(READ_WRITE)` lives in the kernel page
     * cache (effectively unlimited backing store on disk), and the OS demand-
     * pages pixels as the converter walks the buffer.
     *
     * The [file] handle is retained so the caller can delete it after Stage C
     * has consumed the buffer — leaving it would leak ~161 MB of disk per save.
     */
    data class MappedLinearDecodeResult(
        val width: Int,
        val height: Int,
        val pixels: ByteBuffer,  // float16 RGBA, mapped from `file`
        val file: java.io.File,
        val metadata: RawMetadata,
    )

    /**
     * Like [decodeToLinear] but writes the float16 output to a memory-mapped
     * file on disk instead of allocating a Java heap ByteArray. Use this on
     * devices where the ~161 MB heap allocation OOMs.
     *
     * The native uint16 BGR buffer is still allocated as a `DirectByteBuffer`
     * (native off-heap memory) — that allocation is much smaller because LibRaw
     * writes 6 bytes/pixel there, vs the 8 bytes/pixel float16 output. For a
     * 5500×3670 RAW: uint16 = 121 MB native + float16 = 161 MB *file* (zero
     * JVM heap impact for the float16 portion).
     *
     * The caller must dispose [MappedLinearDecodeResult.file] after Stage C
     * has finished reading the mapped buffer.
     */
    suspend fun decodeToLinearMapped(
        filePath: String,
        halfSize: Boolean,
        userQual: Int = 3,
        knownDims: Pair<Int, Int>? = null,
        outputFile: java.io.File,
    ): MappedLinearDecodeResult? =
        withContext(Dispatchers.Default) {
            Log.d(TAG, "decodeToLinearMapped: path=$filePath halfSize=$halfSize outputFile=${outputFile.absolutePath}")
            if (!isAvailable) {
                Log.e(TAG, "decodeToLinearMapped: library not available, aborting")
                return@withContext null
            }
            run {
                val rt = Runtime.getRuntime()
                val beforeFree = rt.freeMemory()
                System.gc(); System.runFinalization(); System.gc()
                Log.w(
                    TAG,
                    "decodeToLinearMapped: pre-decode GC free=${rt.freeMemory() / (1024 * 1024)}MB " +
                        "(was ${beforeFree / (1024 * 1024)}MB) max=${rt.maxMemory() / (1024 * 1024)}MB",
                )
            }
            runCatching {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "decodeToLinearMapped: file does not exist at $filePath")
                    return@runCatching null
                }
                val data = file.readBytes()
                Log.d(TAG, "decodeToLinearMapped: read ${data.size} bytes from file")

                val outSize = IntArray(3)
                val previewW: Int; val previewH: Int
                if (knownDims != null) {
                    previewW = knownDims.first; previewH = knownDims.second
                    outSize[0] = previewW; outSize[1] = previewH
                } else {
                    val dimsOk = NativeRawDecoder.readRawPreviewDimensions(data, outSize)
                    if (!dimsOk) { Log.e(TAG, "decodeToLinearMapped: readRawPreviewDimensions failed"); return@runCatching null }
                    previewW = outSize[0]; previewH = outSize[1]
                }
                val allocW = if (halfSize) previewW else previewW * 2
                val allocH = if (halfSize) previewH else previewH * 2

                val bufferBytes = allocW * allocH * 3 * 2
                val buf = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.nativeOrder())

                val decodeOk = if (halfSize) {
                    NativeRawDecoder.decodeRawPreviewLinearIntoBuffer(data, outSize, buf)
                } else if (userQual < 0) {
                    NativeRawDecoder.decodeRawLinearIntoBufferRcd(data, outSize, buf)
                } else {
                    NativeRawDecoder.decodeRawLinearIntoBuffer(data, outSize, buf, userQual)
                }
                if (!decodeOk) { Log.e(TAG, "decodeToLinearMapped: native decode returned false"); return@runCatching null }

                var actualW = outSize[0]; var actualH = outSize[1]
                var workingBuf = buf
                val requiredBytes = actualW.toLong() * actualH.toLong() * 6L
                if (requiredBytes > workingBuf.limit()) {
                    Log.w(TAG, "decodeToLinearMapped: buffer too small; reallocating for ${actualW}x${actualH}")
                    workingBuf = ByteBuffer.allocateDirect(requiredBytes.toInt()).order(ByteOrder.nativeOrder())
                    outSize[0] = actualW; outSize[1] = actualH
                    val retryOk = if (halfSize) {
                        NativeRawDecoder.decodeRawPreviewLinearIntoBuffer(data, outSize, workingBuf)
                    } else if (userQual < 0) {
                        NativeRawDecoder.decodeRawLinearIntoBufferRcd(data, outSize, workingBuf)
                    } else {
                        NativeRawDecoder.decodeRawLinearIntoBuffer(data, outSize, workingBuf, userQual)
                    }
                    if (!retryOk) { Log.e(TAG, "decodeToLinearMapped: retry decode failed"); return@runCatching null }
                    actualW = outSize[0]; actualH = outSize[1]
                }

                val trimH = (actualH - 1).coerceAtLeast(1)
                if (!halfSize) {
                    workingBuf.rewind()
                    NativeRawDecoder.correctFringing(workingBuf, actualW, trimH, 2000)
                }

                val pixelCount = actualW * trimH
                // Multi-probe uint16 sanity check. Same approach as decodeToLinear8bit:
                // sample 8 spread positions so a partially-corrupt buffer is caught
                // before we waste time on the float16 conversion that would produce
                // a solid-color output. Reject if fewer than 3 of 8 probes are
                // non-zero OR max value < 256 across all probes.
                workingBuf.rewind()
                val bufLimit = workingBuf.limit()
                var nonZeroProbes = 0
                var maxVal = 0
                val probeOffsets = IntArray(8) { idx ->
                    ((idx * 2 + 1) * bufLimit / 16) and 1.inv()
                }
                for (off in probeOffsets) {
                    if (off in 0 until bufLimit - 1) {
                        val v = workingBuf.getShort(off).toInt() and 0xFFFF
                        if (v != 0) nonZeroProbes++
                        if (v > maxVal) maxVal = v
                    }
                }
                Log.i(
                    TAG,
                    "decodeToLinearMapped: probe nonZero=$nonZeroProbes/8 max=$maxVal bufLimit=$bufLimit",
                )
                if (nonZeroProbes < 3 || maxVal < 256) {
                    Log.w(
                        TAG,
                        "decodeToLinearMapped: uint16 buf mostly zero (silent native OOM) — refusing to write mapped file",
                    )
                    return@runCatching null
                }

                // ── The key change: float16 output is a MEMORY-MAPPED FILE,
                // not a JVM heap ByteArray. The file becomes the backing store
                // for the f16 conversion; the JVM only sees a ByteBuffer view.
                val f16Bytes = pixelCount.toLong() * 8L
                java.io.RandomAccessFile(outputFile, "rw").use { raf ->
                    raf.setLength(f16Bytes)
                }
                val mapped = java.io.RandomAccessFile(outputFile, "rw").use { raf ->
                    raf.channel.map(
                        java.nio.channels.FileChannel.MapMode.READ_WRITE,
                        0, f16Bytes,
                    )
                }.order(ByteOrder.LITTLE_ENDIAN)

                workingBuf.rewind()
                NativeRawDecoder.convertUint16BgrToFloat16Rgba(workingBuf, mapped, pixelCount)

                // Sanity check
                mapped.rewind()
                val midF16Pos = (pixelCount / 2) * 8
                if (midF16Pos in 0 until mapped.limit() - 1) {
                    val midF16 = mapped.getShort(midF16Pos).toInt() and 0xFFFF
                    Log.w(TAG, "decodeToLinearMapped: f16 mid-pixel=$midF16 (mapped file=${outputFile.length()} bytes)")
                }
                mapped.rewind()

                // Build metadata (same as decodeToLinear's tail end)
                val camMulRaw = NativeRawDecoder.readCamMul(data) ?: floatArrayOf(1f, 1f, 1f, 1f)
                val camMulG = camMulRaw.getOrElse(1) { 1f }.let { if (it == 0f) 1f else it }
                // DNG G2-zero guard — see decodeToLinear's matching block at the top.
                val camMulG2Raw = camMulRaw.getOrElse(3) { camMulG }
                val camMulG2val = if (camMulG2Raw == 0f) camMulG else camMulG2Raw
                val camMul = floatArrayOf(
                    camMulRaw[0] / camMulG, 1f, camMulRaw[2] / camMulG,
                    camMulG2val / camMulG,
                )
                val lensJson = runCatching {
                    NativeRawDecoder.readLensMetadata(data)?.let { org.json.JSONObject(it) }
                }.getOrNull()
                val camMake  = lensJson?.optString("camMake",  "").orEmpty()
                val camModel = lensJson?.optString("camModel", "").orEmpty()
                val ext = file.extension.uppercase()
                val orientation = lensJson?.optInt("orientation", 1) ?: 1
                // rgbCam isn't exposed via a standalone JNI call — the existing
                // decodeToLinear pipeline reads it from the orientation/metadata
                // JSON payload returned by readLensMetadata. Fall back to identity
                // for the mapped-decode path; downstream wide-gamut conversion
                // uses metadata.rgbCam only as a hint, with safe defaults.
                val rgbCam = lensJson?.let { obj ->
                    val arr = obj.optJSONArray("rgbCam")
                    if (arr != null && arr.length() == 12) {
                        FloatArray(12) { i -> arr.optDouble(i).toFloat() }
                    } else FloatArray(12)
                } ?: FloatArray(12)

                val metadata = RawMetadata(
                    sourceUri          = "",
                    fileSha256         = "",
                    fileExtension      = ext,
                    rawWidth           = actualW,
                    rawHeight          = trimH,
                    outputWidth        = actualW,
                    outputHeight       = trimH,
                    rgbCam             = rgbCam,
                    cameraWhiteBalance = camMul,
                    cameraMake         = camMake,
                    cameraModel        = camModel,
                    orientation        = orientation,
                )

                MappedLinearDecodeResult(
                    width    = actualW,
                    height   = trimH,
                    pixels   = mapped,
                    file     = outputFile,
                    metadata = metadata,
                )
            }.getOrElse { e ->
                Log.e(TAG, "decodeToLinearMapped: EXCEPTION ${e::class.simpleName}: ${e.message}", e)
                null
            }
        }

    // ── Conversion ────────────────────────────────────────────────────────────

    // Native LibRaw outputs uint16 BGR (not RGB) — swap B and R when writing float16 RGBA.
    private fun uint16BgrToFloat16Rgba(buf: ByteBuffer, w: Int, h: Int): ByteArray {
        val n = w * h
        val out = ByteArray(n * 8)
        buf.rewind()
        val src = buf.asShortBuffer()
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val FLOAT16_ONE = 0x3C00.toShort()
        val scale = 1.0f / 65535.0f
        for (i in 0 until n) {
            val b = (src.get().toInt() and 0xFFFF) * scale  // native channel 0 = B
            val g = (src.get().toInt() and 0xFFFF) * scale  // native channel 1 = G
            val r = (src.get().toInt() and 0xFFFF) * scale  // native channel 2 = R
            outBuf.putShort(floatToFloat16(r))  // RGBA channel 0 = R
            outBuf.putShort(floatToFloat16(g))
            outBuf.putShort(floatToFloat16(b))
            outBuf.putShort(FLOAT16_ONE)
        }
        return out
    }

    private fun floatToFloat16(v: Float): Short {
        val bits = java.lang.Float.floatToIntBits(v)
        val sign = (bits ushr 16) and 0x8000
        val exp  = ((bits ushr 23) and 0xFF) - 127 + 15
        val mant = (bits ushr 13) and 0x3FF
        return when {
            exp <= 0  -> sign.toShort()
            exp >= 31 -> (sign or 0x7C00).toShort()
            else      -> (sign or (exp shl 10) or mant).toShort()
        }
    }
}
