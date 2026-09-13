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


import com.RAZStudio.StudioRoom.core.utils.AppLog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawStageCache.Stage
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.DemosaicAlgorithm
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import com.raz.razstudio.lib.raw.NativeRawDecoderV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-resolution background pipeline.
 *
 * ── Design contract ─────────────────────────────────────────────────────────
 *
 * This pipeline is INVISIBLE to the user. It:
 *   • Waits until [PreviewPipeline] signals [startSignal] (PreviewReady).
 *   • Replays Stages A → C at full native RAW resolution.
 *   • Writes a base PNG (no macro, no orientation) to cache so that
 *     [RawPipelineCoordinator.renderAndExport] can apply the current macro
 *     and EXIF orientation on demand when the user taps "Apply to Editor".
 *
 * The pipeline never:
 *   • Shows any UI, notification, or dialog.
 *   • Updates the preview canvas.
 *   • Blocks the UI thread.
 *
 * ── Dimension fix ───────────────────────────────────────────────────────────
 *
 * The preview metadata carries rawWidth/rawHeight set to preview (half-res)
 * dimensions. The full-res decode produces a buffer 4× larger (2× per axis).
 * This pipeline saves the actual decoded dimensions to "dims.bin" alongside
 * the Stage A buffer and uses those dimensions for Stage C — not the
 * stale metadata fields.
 *
 * ── Output format ───────────────────────────────────────────────────────────
 *
 * Delivers workspace_full_base.png (Stage C output, no macro applied) to
 * [RawPipelineCoordinator] via [_events]. The coordinator renders the final
 * export by loading this PNG and applying MacroProcessor + LUT + orientation.
 */
class FullResPipeline(
    private val context: Context,
    private val cache: RawStageCache,
    private val startSignal: MutableSharedFlow<RawPipelineState.PreviewReady>,
    /**
     * Coordinator-supplied callback to release canvas-side bitmaps before a
     * memory-tight full-res allocation. Without this, the 145 MB DirectByteBuffer
     * alloc in [LibRawJniBridge.decodeToLinear] OOMs while the coordinator is
     * still holding 200+ MB of canvas previews / idle full-res / mask bitmaps.
     * No-op default keeps existing tests / batch use cases working.
     */
    private val freeCanvasMemory: suspend () -> Unit = {},
) {
    /** Emits [RawPipelineState.FullResReady] when processing completes silently. */
    private val _events = MutableSharedFlow<RawPipelineState>(replay = 1)
    val events = _events.asSharedFlow()

    /**
     * Embedded-JPEG fallback channel — fires when the native full-res decode
     * doesn't finish within [FULL_RES_WATCHDOG_MS]. The coordinator subscribes
     * and overlays the camera-embedded preview on the canvas with a banner so
     * the user sees their image while the slow decode continues (or surfaces a
     * useful image even when the decode is hung on a vendor bug).
     *
     * The native decode is NOT cancelled — it keeps running in case it finishes
     * on its own; if it does, the coordinator clears the banner. This matches
     * the "preview only" pattern darktable uses for unsupported lossy RAFs and
     * lossless Sony ARWs.
     */
    private val _embeddedFallback = MutableSharedFlow<Bitmap?>(replay = 1)
    val embeddedFallback = _embeddedFallback.asSharedFlow()

    private companion object {
        /** 60 s default — covers any plausible legitimate decode on a high-end
         *  body, while still un-blocking the UI on the multi-minute Rayxie /
         *  vendor-bug hangs we've seen in the wild. */
        const val FULL_RES_WATCHDOG_MS = 60_000L

        /** Extended budget when the user opts into slow post-demosaic stages
         *  (NR + Rayxie CA together on 20 MP can legitimately take 50–80 s on
         *  the supported-floor device). Watchdog still fires eventually to
         *  catch the genuine hang case, but gives the legit work room. */
        const val FULL_RES_WATCHDOG_SLOW_MS = 150_000L
    }

    /**
     * Best-effort load of the embedded JPEG preview via LibRaw's `unpack_thumb`.
     * Returns null when the source has no embedded JPEG (uncommon) or when the
     * native call errors. Caller is expected to fall back gracefully.
     */
    private suspend fun loadEmbeddedJpeg(filePath: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val bytes = runCatching { NativeRawDecoderV2.extractEmbeddedJpeg(filePath) }
                .getOrNull() ?: return@withContext null
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

    suspend fun run(
        filePath: String,
        demosaicAlgorithmIn: DemosaicAlgorithm = DemosaicAlgorithm.DEFAULT,
        config: WorkspaceConfig = WorkspaceConfig.Default,
    ) {
        AppLog.i("FullResPipeline", "run: config=$config algo=$demosaicAlgorithmIn")
        val demosaicAlgorithm = demosaicAlgorithmIn
        try {
            // Block until preview declares itself ready
            val preview = startSignal.first()
            val sha      = preview.metadata.fileSha256
            val metadata = preview.metadata

            emitProgress(0.05f)

            // ── Early canvas upgrade ──────────────────────────────────────────
            //
            // Preview pipeline just signalled ready — its Stage-C workspace.png is
            // guaranteed to be on disk at this point (PreviewReady fires after it's
            // written). Emit a preliminary FullResReady now so the idle canvas shows
            // the preview-quality image immediately instead of staying black while
            // Stages A→B→C run (~30s on first open).
            // The coordinator updates fullResOutputPath again when the quality path arrives.
            val previewWorkspacePath = cache.stageFile(sha, Stage.C_PREVIEW, "workspace.png")
            if (previewWorkspacePath.exists()) {
                _events.emit(RawPipelineState.FullResReady(previewWorkspacePath.absolutePath, metadata))
            }

            // ── Fast path: Stage C PNG already valid for this algorithm ──────────
            //
            // The on-disk Stage C PNG ("workspace_full_base.png") is the only thing
            // the export path actually reads. If it exists for this (sha, algorithm)
            // pair, we can skip Stage A entirely — saving the 161 MB float16 buffer
            // read and the wide-gamut conversion.
            val algTag = demosaicAlgorithm.name.lowercase()
            val algFile = "alg.txt"
            val stageCFile = cache.stageFile(sha, Stage.C_FULLRES, "workspace_full_base.png")
            // Minimum plausible PNG size: a genuine full-res image should be >> 500 KB.
            // A 87 KB PNG at 5496×3669 is all-black (corrupt from prior OOM). Invalidate it.
            val stageCValid = stageCFile.exists() && stageCFile.length() > 500_000L
            val cachedAlg = cache.readStageText(sha, Stage.A_FULLRES, algFile)
            val algMatches = cachedAlg == algTag

            AppLog.i(
                "FullResPipeline",
                "decision: algMatches=$algMatches cachedAlg=$cachedAlg algTag=$algTag stageCValid=$stageCValid stageCFile=${stageCFile.absolutePath} exists=${stageCFile.exists()} len=${if (stageCFile.exists()) stageCFile.length() else -1}",
            )
            if (algMatches && stageCValid) {
                AppLog.i("FullResPipeline", "Stage C cache HIT — skipping Stage A entirely")
                emitProgress(1f)
                _events.emit(RawPipelineState.FullResReady(stageCFile.absolutePath, metadata))
                return
            }

            // ── Slow path: need to regenerate Stage C ────────────────────────────
            // Only now do we wait for heap headroom — we're about to allocate ~161 MB.
            AppLog.i("FullResPipeline", "slow path entered; waiting for heap headroom")
            waitForHeapHeadroom(requiredMb = 200, maxWaitMs = 5000L)
            AppLog.i("FullResPipeline", "heap headroom OK; checking A_FULLRES cache")

            // The cache key incorporates the demosaic algorithm name so that switching
            // algorithms forces a fresh decode (different algorithms produce different pixels).
            // dims.bin stores the actual decoded W,H so later stages use the correct
            // full-res dimensions (not the half-res metadata fields).
            //
            // linearBuf is either:
            //   - a fresh decode result (ByteArray), allocated by LibRaw, ~161 MB on the heap; or
            //   - a memory-mapped view of the cached linear.rawbuf file (ByteBuffer),
            //     which avoids the 161 MB heap allocation entirely — the OS demand-pages
            //     pixels into RAM as the converter walks the buffer.
            // We carry both as nullable vars and pass whichever one is set to the converter.
            var linearArr: ByteArray? = null
            var linearBuf: java.nio.ByteBuffer? = null
            val fullW: Int
            val fullH: Int

            if (algMatches &&
                cache.isStageComplete(sha, Stage.A_FULLRES, "linear.rawbuf") &&
                cache.readStageText(sha, Stage.A_FULLRES, "dims.bin") != null) {

                val cachedFile = cache.stageFile(sha, Stage.A_FULLRES, "linear.rawbuf")
                val mapped = mapStageFile(cachedFile)
                if (mapped == null) {
                    error("A_FULLRES linear.rawbuf could not be memory-mapped")
                }
                // Reject a corrupt (zero-filled) cached buffer — can happen if OOM
                // occurred during a previous run before the write-guard was in place.
                val midCached = mapped.capacity() / 2
                val cachedIsZero = mapped.get(midCached) == 0.toByte() &&
                    mapped.get(midCached + 1) == 0.toByte() &&
                    mapped.get(midCached + 4) == 0.toByte() &&
                    mapped.get(midCached + 5) == 0.toByte()
                if (cachedIsZero) {
                    AppLog.w("FullResPipeline", "Stage A: cached buffer is zero — corrupt from prior OOM, falling through to re-decode")
                    val decoded = decodeFullResWithFallback(
                        filePath = filePath,
                        userQual = demosaicAlgorithm.userQual,
                        caCorrectionEnabled = config.caCorrectionEnabled,
                        colorFringingMode = config.colorFringingMode.ordinal,
                        nrEnabled = config.nrEnabled,
                        nrLuma = config.nrLuma,
                        nrChroma = config.nrChroma,
                    ) ?: error("Full-res re-decode failed at both full and half resolution")
                    cache.writeStageFile(sha, Stage.A_FULLRES, "linear.rawbuf", decoded.pixels)
                    cache.writeStageFile(sha, Stage.A_FULLRES, "dims.bin", "${decoded.width},${decoded.height}")
                    val originalSensorW = metadata.outputWidth * 2
                    val originalSensorH = metadata.outputHeight * 2
                    cache.writeStageFile(
                        sha, Stage.A_FULLRES, "original_dims.bin",
                        "$originalSensorW,$originalSensorH",
                    )
                    cache.writeStageFile(sha, Stage.A_FULLRES, algFile, algTag)
                    cache.evictIfNeeded()
                    linearArr = decoded.pixels; fullW = decoded.width; fullH = decoded.height
                } else {
                    linearBuf = mapped
                    val (w, h) = parseDims(
                        cache.readStageText(sha, Stage.A_FULLRES, "dims.bin"),
                        metadata.outputWidth * 2, metadata.outputHeight * 2,
                    )
                    fullW = w; fullH = h
                }
            } else {
                // Do NOT pass knownDims for full-res: metadata.outputHeight is the trimmed preview
                // height (actualH - 1), so doubling it undersizes the buffer by 2 rows and causes
                // a native write overrun that corrupts pixel data (blue cast). Let decodeToLinear
                // call readRawPreviewDimensions itself to get the true untrimmed sensor dims.
                //
                // OOM fallback: full-res decode allocates ~161 MB for the float16 buffer on a
                // ~24 MP RAW. On low-memory devices the native float16 conversion silently
                // returns a zero-filled ByteArray. If we detect that (or hit OOM outright),
                // retry once at halfSize=true — the result is still much sharper than the
                // preview, and it's far better than failing silently.
                AppLog.i("FullResPipeline", "slow path: calling decodeFullResWithFallback userQual=${demosaicAlgorithm.userQual} ca=${config.caCorrectionEnabled}")
                // Watchdog: if the native decode hasn't returned in
                // FULL_RES_WATCHDOG_MS:
                //   1. Surface the embedded JPEG preview so the user sees
                //      something instead of a blank canvas.
                //   2. Flip the LibRaw cancel flag so the native side returns
                //      at its next checkpoint instead of burning CPU for
                //      minutes on a hung vendor-specific decode (Rayxie on
                //      11 MP, Sony A7V Compressed HQ, etc.).
                // If the decode finishes first, the watchdog is cancelled and
                // the cancel flag is never flipped.
                val cancelFlag = com.raz.razstudio.lib.raw.CancelFlag()
                // Pick a watchdog budget based on what the user opted into.
                // When NR is on AND CA is on (the slow combo at full-res on
                // 20MP), use a generous budget so the legitimate ~50–80 s of
                // post-demosaic work isn't mistaken for a hang. The watchdog
                // still fires eventually to catch real hangs.
                val slowPath = config.nrEnabled && config.caCorrectionEnabled
                val watchdogMs = if (slowPath) FULL_RES_WATCHDOG_SLOW_MS
                                 else FULL_RES_WATCHDOG_MS
                val decoded = try {
                    coroutineScope {
                        val watchdog = launch {
                            delay(watchdogMs)
                            val embedded = loadEmbeddedJpeg(filePath)
                            if (embedded != null) {
                                AppLog.w(
                                    "FullResPipeline",
                                    "watchdog fired @${watchdogMs}ms (slowPath=$slowPath) — " +
                                        "surfacing embedded JPEG ${embedded.width}x${embedded.height} " +
                                        "+ cancelling native decode",
                                )
                                _embeddedFallback.emit(embedded)
                            } else {
                                AppLog.w(
                                    "FullResPipeline",
                                    "watchdog fired @${watchdogMs}ms (slowPath=$slowPath) — " +
                                        "no embedded JPEG available; still cancelling native decode",
                                )
                            }
                            // Flip the native cancel flag. decode_core polls it
                            // between stages, returns RDV2_ERR_CANCELLED which
                            // decodeFullResWithFallback surfaces as null.
                            cancelFlag.cancel()
                        }
                        try {
                            decodeFullResWithFallback(
                                filePath = filePath,
                                userQual = demosaicAlgorithm.userQual,
                                caCorrectionEnabled = config.caCorrectionEnabled,
                                colorFringingMode = config.colorFringingMode.ordinal,
                                cancelFlagPtr = cancelFlag.ptr(),
                                nrEnabled = config.nrEnabled,
                                nrLuma = config.nrLuma,
                                nrChroma = config.nrChroma,
                            ) ?: error("Full-res LibRaw decode failed at both full and half resolution")
                        } finally {
                            watchdog.cancel()
                            // Clear the fallback banner — either the decode just
                            // succeeded (so the proper preview will take over) or
                            // it failed and the coordinator will surface its own
                            // error message. Either way the embedded JPEG is no
                            // longer the right thing to be looking at.
                            _embeddedFallback.emit(null)
                        }
                    }
                } finally {
                    cancelFlag.close()
                }
                AppLog.i("FullResPipeline", "decode returned ${decoded.width}x${decoded.height} pixels=${decoded.pixels.size}; writing linear.rawbuf")
                cache.writeStageFile(sha, Stage.A_FULLRES, "linear.rawbuf", decoded.pixels)
                AppLog.i("FullResPipeline", "linear.rawbuf written")
                cache.writeStageFile(sha, Stage.A_FULLRES, "dims.bin", "${decoded.width},${decoded.height}")
                // Also write `original_dims.bin` with the TRUE sensor dimensions —
                // distinct from `dims.bin` which holds the *actual decoded buffer*
                // dimensions (may be half-size after an OOM fallback). The export
                // page reads `original_dims.bin` for its "Original: WxH" label so
                // the user sees their RAW's true resolution regardless of which
                // fallback path the decoder took.
                //
                // V2's decode_core uses user_flip=0 so the buffer is always landscape-
                // coordinates (sensor-native), with the EXIF orientation surfaced
                // separately on `metadata.orientation`. `decoded.width × decoded.height`
                // is therefore already the canonical landscape sensor resolution we
                // want to report on the RAW Export page — matches Canon's published
                // numbers (EOS 6D = 5472×3648) regardless of how the body was held.
                //
                // Display rotation is applied later at canvas/export time using the
                // orientation flag; we never mutate the dims here.
                //
                // Un-trim height: LibRawJniBridge.decodeToLinear strips the last row
                // ("bayer boundary artifact — missing R photosites produce cyan") which
                // makes `decoded.height` one short of the camera's native vertical. For
                // reporting purposes we want the camera's true number (Canon EOS 6D
                // mRAW = 4104×2736, not 4104×2735), so add 1 back. Buffer math
                // downstream still uses the trimmed value via `dims.bin`.
                val originalSensorW = decoded.width
                val originalSensorH = decoded.height + 1
                AppLog.i(
                    "FullResPipeline",
                    "original_dims: landscape ${originalSensorW}x${originalSensorH} " +
                        "(decoded=${decoded.width}x${decoded.height}, +1 untrim row, orientation=${metadata.orientation})",
                )
                cache.writeStageFile(
                    sha, Stage.A_FULLRES, "original_dims.bin",
                    "$originalSensorW,$originalSensorH",
                )
                cache.writeStageFile(sha, Stage.A_FULLRES, algFile, algTag)
                cache.evictIfNeeded()
                fullW = decoded.width; fullH = decoded.height
                // ── Early eviction: replace heap ByteArray with mmap'd view ──────
                //
                // Up to this point `decoded.pixels` held ~161 MB on the JVM heap.
                // Stage C below will allocate another ~134 MB (workspace_full_base
                // bitmap), and on BIT_16 workspace the FP16 pass at line ~435 adds
                // a third ~134 MB allocation. Three large blocks alive at the same
                // time blows past the 512 MB heap ceiling on Helio G99 / 4 GB
                // phones, manifesting as the OOM cascade we saw in earlier traces.
                //
                // Instead of carrying the bytes in `linearArr`, memory-map the file
                // we just wrote and feed Stage C through `linearBuf` (the mmap'd
                // ByteBuffer branch already exists for the cache-hit path). The
                // 161 MB lives in mmap-backed kernel pages, not JVM heap; the OS
                // pages them in on demand and out under memory pressure.
                val newlyWritten = cache.stageFile(sha, Stage.A_FULLRES, "linear.rawbuf")
                linearBuf = java.io.RandomAccessFile(newlyWritten, "r").use { raf ->
                    raf.channel.map(
                        java.nio.channels.FileChannel.MapMode.READ_ONLY,
                        0, newlyWritten.length(),
                    )
                }.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                // `decoded` is a local val in this `else` block; it falls out of
                // scope as soon as we exit the block, which is right before Stage
                // C allocates. We intentionally do NOT do `linearArr = decoded.pixels`
                // — keeping `linearArr` null forces Stage C to use the mmap'd path.
                AppLog.i(
                    "FullResPipeline",
                    "linearArr evicted; using mmap'd linearBuf=${linearBuf?.capacity()} bytes",
                )
            }
            emitProgress(0.50f)

            // ── Stage C full-res ────────────────────────────────────────────────
            //
            // Run **only one** Stage C pass — picked based on the user-selected
            // workspace. Running both (BIT_8 first, then BIT_16) caused two
            // problems we fixed 2026-05-25:
            //
            //   1. The 8-bit/sRGB pass landed first (~22s including PNG compress)
            //      and the canvas switched the user's BIT_16/P3 photo into 8-bit/sRGB
            //      for the ~20s gap until the FP16 pass completed. Any slider
            //      adjustment during that window rendered in the wrong colorspace.
            //
            //   2. Stage C ran twice for every BIT_16 session — ~22 seconds of
            //      wasted compute per file.
            //
            // New strategy:
            //   • BIT_16 workspace → run **FP16/P3 pass only**, persist .fp16 + .gamut,
            //     and write a tiny stub `workspace_full_base.png` so the legacy
            //     "is Stage C ready?" existence check still passes. The idle-full-res
            //     and Save paths both read the FP16 cache directly; no one reads
            //     pixel data from the stub PNG when bitDepth=BIT_16.
            //   • BIT_8 workspace → unchanged: BIT_8/sRGB pass writes the PNG.
            val isFp16Workspace =
                config.bitDepth == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth.BIT_16

            // For BIT_8: use Default (BIT_8/sRGB). For BIT_16: use the user's full
            // workspace config so the single pass produces the FP16/gamut output
            // the canvas + save path expect.
            val stageCConfig = if (isFp16Workspace) config else WorkspaceConfig.Default
            val stageC = when {
                linearArr != null -> WideGamutConverter.convert(
                    pixelsFloat16 = linearArr, width = fullW, height = fullH,
                    metadata = metadata, config = stageCConfig,
                )
                linearBuf != null -> WideGamutConverter.convert(
                    pixelsFloat16 = linearBuf, width = fullW, height = fullH,
                    metadata = metadata, config = stageCConfig,
                )
                else -> error("linearBuf/linearArr missing for Stage C")
            }
            // Release references — Stage C is done with the input. The ByteArray
            // branch frees ~161 MB of heap; the mapped branch releases the file
            // mapping (the OS pages out the demand-loaded pixels on GC).
            linearArr = null
            linearBuf = null
            cache.writeStageFile(sha, Stage.C_FULLRES, "wide_gamut.cube", stageC.cubeFileText)
            emitProgress(0.85f)

            if (isFp16Workspace) {
                // Persist the FP16 bitmap as raw pixel bytes + sidecar gamut.
                // Readers: IdleFullRes (canvas upgrade) + renderAndExport Save path.
                val fp16Bmp = stageC.workspaceBitmap
                runCatching {
                    val rowBytes = fullW * 8
                    val pixelBytes = rowBytes * fullH
                    val buf = java.nio.ByteBuffer
                        .allocateDirect(pixelBytes)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    fp16Bmp.copyPixelsToBuffer(buf)
                    buf.rewind()
                    cache.writeStageFile(sha, Stage.C_FULLRES, "workspace_full_base.fp16") { out ->
                        val arr = ByteArray(64 * 1024)
                        while (buf.hasRemaining()) {
                            val n = minOf(arr.size, buf.remaining())
                            buf.get(arr, 0, n)
                            out.write(arr, 0, n)
                        }
                    }
                    cache.writeStageFile(
                        sha, Stage.C_FULLRES, "workspace_full_base.gamut",
                        config.colorGamut.name,
                    )
                    AppLog.i(
                        "FullResPipeline",
                        "FP16 Stage C persisted: ${fullW}x$fullH gamut=${config.colorGamut.name} bytes=$pixelBytes",
                    )
                }.onFailure { e ->
                    AppLog.w("FullResPipeline", "FP16 Stage C persist failed (non-fatal): ${e.message}", e)
                }

                // Stub PNG: 1×1 transparent, just so the legacy `stageCFile.exists()`
                // existence check + `fullResOutputPath` plumbing keep working. Any
                // BIT_16 reader explicitly bypasses this in favor of the .fp16
                // cache (see RawPipelineCoordinator.loadFp16CacheBitmap +
                // renderAndExport's BIT_16 fast path).
                runCatching {
                    val stub = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    cache.writeStageFile(sha, Stage.C_FULLRES, "workspace_full_base.png") { out ->
                        stub.compress(android.graphics.Bitmap.CompressFormat.PNG, 0, out)
                    }
                    stub.recycle()
                }
                fp16Bmp.recycle()
            } else {
                // BIT_8 path: persist the workspace bitmap as a PNG. Stream the
                // compress straight to a buffered FileOutputStream so we never
                // materialize the ~12 MB encoded blob in memory.
                var compressOk = false
                cache.writeStageFile(sha, Stage.C_FULLRES, "workspace_full_base.png") { out ->
                    compressOk = stageC.workspaceBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 0, out)
                }
                AppLog.i(
                    "FullResPipeline",
                    "Stage C compress streamed: ok=$compressOk bmpRecycled=${stageC.workspaceBitmap.isRecycled}",
                )
                stageC.workspaceBitmap.recycle()
            }

            // Once Stage C is on disk, the 161 MB linear.rawbuf is dead weight FOR the
            // 8-bit export path — that path reads only the PNG. For BIT_16 however
            // the idle full-res render reads the FP16 cache we just wrote (if it
            // succeeded), which references the same pixels we just persisted —
            // no need to keep the camera-linear buffer.
            runCatching {
                cache.stageFile(sha, Stage.A_FULLRES, "linear.rawbuf").delete()
            }

            emitProgress(1f)
            _events.emit(RawPipelineState.FullResReady(stageCFile.absolutePath, metadata))

        } catch (e: Throwable) {
            AppLog.e("FullResPipeline", "Full-res pipeline failed: ${e.message}", e)
            if (BuildConfig_DEBUG) {
                _events.emit(RawPipelineState.Error("Full-res failed: ${e.message}", e))
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Decode at full resolution; if the native float16 buffer comes back zero-filled
     * (silent OOM in NativeRawDecoder.convertUint16BgrToFloat16Rgba) or if the JVM
     * itself OOMs allocating the result ByteArray, retry once with halfSize=true.
     * Returns null only if both attempts fail.
     */
    private suspend fun decodeFullResWithFallback(
        filePath: String,
        userQual: Int,
        caCorrectionEnabled: Boolean = true,
        colorFringingMode: Int = 2,
        cancelFlagPtr: Long = 0L,
        nrEnabled: Boolean = false,
        nrLuma: Int = 0,
        nrChroma: Int = 0,
    ): LibRawJniBridge.LinearDecodeResult? {
        fun isZeroBuffer(pixels: ByteArray): Boolean {
            val midIdx = pixels.size / 2
            if (midIdx + 5 >= pixels.size) return false
            return pixels[midIdx] == 0.toByte() &&
                pixels[midIdx + 1] == 0.toByte() &&
                pixels[midIdx + 4] == 0.toByte() &&
                pixels[midIdx + 5] == 0.toByte()
        }

        // Three-step ladder, in increasing-degradation order:
        //
        //   1. Full-res with the user-selected demosaic (RAZAmaze=RCD, AMaZE, AHD, etc.)
        //   2. Full-res with AHD (LibRaw's stock high-quality demosaic; different code
        //      path from RCD, so files that produce zero-buffers from RCD often work
        //      here — verified 2026-05-24 IMG_3894.CR2: RCD's `filters=0xb4b4b4b4`
        //      decode produces all-zero output, AHD on the same buffer is fine).
        //   3. Half-res via LibRaw `half_size=1` (2×2 Bayer box average — guaranteed
        //      to work but quarter the pixel area).
        //
        // The middle step gives users 4× the pixel count compared to the half-res fallback
        // when the RCD step fails, at the cost of demosaic algorithm quality (AHD ≈ 38.4 dB
        // vs RCD's 39.94 dB CPSNR on Kodak — visually similar in normal photos).
        suspend fun attempt(half: Boolean, demosaic: Int, label: String): LibRawJniBridge.LinearDecodeResult? = try {
            AppLog.i("FullResPipeline", "attempt[$label]: half=$half userQual=$demosaic")
            val r = LibRawJniBridge.decodeToLinear(
                filePath = filePath, halfSize = half, userQual = demosaic,
                caCorrectionEnabled = caCorrectionEnabled,
                colorFringingMode = colorFringingMode,
                cancelFlagPtr = cancelFlagPtr,
                nrEnabled = nrEnabled, nrLuma = nrLuma, nrChroma = nrChroma,
            )
            AppLog.i("FullResPipeline", "attempt[$label]: decodeToLinear returned ${if (r == null) "null" else "${r.width}x${r.height} pixels=${r.pixels.size}"}")
            if (r == null) return@attempt null
            val zero = isZeroBuffer(r.pixels)
            AppLog.i("FullResPipeline", "attempt[$label]: isZeroBuffer=$zero")
            if (zero) {
                AppLog.w(
                    "FullResPipeline",
                    "attempt[$label] returned zero buffer — falling through",
                )
                null
            } else r
        } catch (e: OutOfMemoryError) {
            AppLog.w("FullResPipeline", "attempt[$label] hit OOM: ${e.message}")
            null
        } catch (e: Throwable) {
            AppLog.e("FullResPipeline", "attempt[$label] threw: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }

        // Paired GC + finalize + sleep between attempts. The 145MB DirectByteBuffer
        // allocation in decodeToLinear is non-movable (DirectByteBuffer$MemoryRef
        // wraps a Bitmap-style native array). It needs a contiguous slot that prior
        // ARGB_8888 / RGBA_F16 working bitmaps may be fragmenting. A single `gc()`
        // is a hint; pairing with runFinalization + a small sleep gives the GC time
        // to actually reclaim and compact. Verified adb 2026-05-25: without this,
        // step 2 OOMs at the same 145 MB alloc that step 1 OOM'd on.
        suspend fun aggressiveFree(label: String) {
            val rt = Runtime.getRuntime()
            val before = rt.freeMemory()
            // Ask the coordinator to release canvas-side bitmaps (idle full-res
            // bitmap, neutral8, zoom source, etc.). Without this the GC below
            // can't reclaim much — those bitmaps are still strongly referenced.
            freeCanvasMemory()
            System.gc()
            System.runFinalization()
            System.gc()
            kotlinx.coroutines.delay(200L)  // let pending finalizers complete
            AppLog.i(
                "FullResPipeline",
                "aggressiveFree[$label]: free=${rt.freeMemory() / (1024 * 1024)}MB " +
                    "(was ${before / (1024 * 1024)}MB)",
            )
        }

        // Step 1: user-selected demosaic at full-res.
        attempt(half = false, demosaic = userQual, label = "full-res user")
            ?.let { return it }

        // Step 2: AHD at full-res. Only worth trying if the user picked RCD/RAZAmaze
        // (userQual<0) — for AMaZE/AHD/etc. picks, step 1 already tried that algorithm
        // and the file's broken at any full-res demosaic, so jump straight to half.
        if (userQual < 0) {
            AppLog.w("FullResPipeline", "step 1 (RCD full-res) failed; trying step 2 (AHD full-res)")
            aggressiveFree("pre-step2")
            attempt(half = false, demosaic = 3 /* AHD */, label = "full-res AHD")
                ?.let { return it }
        }

        // Step 3: half-res via LibRaw box-average. Pixel area cut to 1/4 but the path
        // is the most reliable: bypasses demosaic entirely (2×2 Bayer averaged), no
        // RCD/AHD interpolation, no per-channel math that can produce zeros.
        AppLog.w("FullResPipeline", "all full-res attempts failed; falling back to halfSize=true")
        aggressiveFree("pre-step3")
        return attempt(half = true, demosaic = userQual, label = "half-res")
    }

    /**
     * Memory-map [file] read-only and return a [java.nio.ByteBuffer] view. Avoids
     * the 161 MB heap allocation that `file.readBytes()` would force; the OS
     * demand-pages pixels as the converter walks the buffer. Returns null on I/O
     * error so the caller can fall back to a fresh decode.
     */
    private fun mapStageFile(file: java.io.File): java.nio.ByteBuffer? = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { ch ->
                ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, ch.size())
            }
        }
    }.getOrNull()

    /**
     * Poll heap headroom until [requiredMb] is free or [maxWaitMs] elapses. Issues
     * a single hint GC up front; subsequent waits are cooperative (the system runs
     * its own concurrent GC and we just check the result). On hot paths this returns
     * within ~50 ms; on cold OOM-prone paths it caps at [maxWaitMs] (~2 s) — same
     * upper bound as the old fixed delay, but free when memory isn't tight.
     */
    private suspend fun waitForHeapHeadroom(requiredMb: Int, maxWaitMs: Long) {
        fun freeMb(): Long {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            return (rt.maxMemory() - used) / (1024 * 1024)
        }
        if (freeMb() >= requiredMb) return

        // Aggressive reclaim up front: ask the coordinator to recycle canvas
        // bitmaps + release the GPU processor, then a paired gc+finalization.
        // Without this, the GC has nothing to free — the 300 MB of Dalvik heap
        // is pinned by neutralBitmap / neutralBitmap8Bit / GPU surfaces that
        // the coordinator owns. Observed adb 2026-05-25: timeout fired with
        // 15 MB free because heap-pressured pinned bitmaps weren't recycled.
        freeCanvasMemory()
        System.gc()
        System.runFinalization()
        System.gc()
        kotlinx.coroutines.delay(150L)  // let finalizers complete
        if (freeMb() >= requiredMb) {
            AppLog.i(
                "FullResPipeline",
                "waitForHeapHeadroom: ${freeMb()} MB free after aggressive reclaim",
            )
            return
        }

        // Cooperative poll: the system runs its own concurrent GC and we just
        // re-check. Re-issue a hint GC each 500 ms in case freeCanvasMemory()
        // released more references that the first cycle didn't catch.
        val deadline = System.currentTimeMillis() + maxWaitMs
        var nextGcAt = System.currentTimeMillis() + 500L
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100L)
            if (freeMb() >= requiredMb) return
            if (System.currentTimeMillis() >= nextGcAt) {
                System.gc()
                nextGcAt += 500L
            }
        }
        AppLog.w(
            "FullResPipeline",
            "waitForHeapHeadroom: timed out after ${maxWaitMs}ms, only ${freeMb()} MB free " +
                "(needed $requiredMb MB) — proceeding anyway",
        )
    }

    private fun parseDims(text: String?, fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
        if (text != null) {
            val parts = text.trim().split(",")
            if (parts.size == 2) {
                val w = parts[0].trim().toIntOrNull()
                val h = parts[1].trim().toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) return w to h
            }
        }
        return fallbackW to fallbackH
    }

    private suspend fun emitProgress(p: Float) =
        _events.emit(RawPipelineState.FullResProcessing(p))

    private val BuildConfig_DEBUG: Boolean = runCatching {
        Class.forName("com.RAZStudio.StudioRoom.BuildConfig")
            .getDeclaredField("DEBUG").getBoolean(null)
    }.getOrDefault(false)
}
