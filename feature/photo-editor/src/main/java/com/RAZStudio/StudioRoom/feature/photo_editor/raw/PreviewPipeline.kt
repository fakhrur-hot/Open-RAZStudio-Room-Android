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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawStageCache.Stage
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preview pipeline: Stages A → C at device-scaled resolution.
 *
 * Emits [RawPipelineState] for the UI throughout processing. Each stage checks the
 * [RawStageCache] first; if the artefact exists the stage is skipped.
 *
 * Completion emits [RawPipelineState.PreviewReady] and returns [previewReadyState]
 * so [RawPipelineCoordinator] can latch it and signal the full-res pipeline.
 *
 * Lifecycle: one [PreviewPipeline] instance per open file. Call [cancel] when the
 * workspace is closed or a new file is opened.
 */
class PreviewPipeline(
    private val context: Context,
    private val cache: RawStageCache,
    private val detector: DeviceCapabilityDetector,
) {
    private val _state = MutableStateFlow<RawPipelineState>(RawPipelineState.Idle)
    val state = _state.asStateFlow()

    @Volatile var previewReadyState: RawPipelineState.PreviewReady? = null
        private set

    suspend fun run(
        uri: Uri,
        macro: UserMacro,
        config: WorkspaceConfig = WorkspaceConfig.Default,
    ) {
        val ctx = context.applicationContext
        try {
            Log.i("PreviewPipeline", "run: uri=$uri config=$config")
            emit(RawPipelineState.PreviewLoading(1, 0f, "Reading RAW"))

            Log.d("PreviewPipeline", "run: computing sha256...")
            val sha = cache.sha256OfUri(ctx, uri)
            Log.d("PreviewPipeline", "run: sha256=$sha")

            Log.d("PreviewPipeline", "run: resolving path for uri=$uri")
            val filePath = resolveToAbsolutePath(ctx, uri)
            Log.d("PreviewPipeline", "run: resolved filePath=$filePath")

            val metadata = if (cache.isStageComplete(sha, Stage.A_PREVIEW, "linear.rawbuf")) {
                Log.d("PreviewPipeline", "Stage A: cache HIT for sha=$sha")
                val json = cache.readMetaJson(sha) ?: error("meta.json missing for $sha")
                LibRawJniBridge.parseMetadataFromJson(json, uri.toString(), sha)
            } else {
                Log.d("PreviewPipeline", "Stage A: cache MISS, decoding from file")
                val absPath = filePath ?: error("Cannot resolve path for $uri")
                emit(RawPipelineState.PreviewLoading(1, 0.2f, "Decoding RAW"))

                Log.d("PreviewPipeline", "Stage A: extractMetadata from $absPath")
                val quickMeta = LibRawJniBridge.extractMetadata(absPath)
                Log.d("PreviewPipeline", "Stage A: quickMeta=${quickMeta?.outputWidth}x${quickMeta?.outputHeight}")
                val (previewW, previewH) = detector.previewSize(
                    quickMeta?.outputWidth ?: 4000,
                    quickMeta?.outputHeight ?: 3000,
                )
                // Pass dims from extractMetadata so decodeToLinear skips readRawPreviewDimensions (~340ms saved).
                val knownDims = if (quickMeta != null) quickMeta.outputWidth to quickMeta.outputHeight else null
                Log.d("PreviewPipeline", "Stage A: previewSize=${previewW}x${previewH}, calling decodeToLinear halfSize=true knownDims=$knownDims")
                val decoded = LibRawJniBridge.decodeToLinear(absPath, halfSize = true, knownDims = knownDims)
                    ?: error("LibRaw decode failed: $absPath")
                Log.d("PreviewPipeline", "Stage A: decode SUCCESS ${decoded.width}x${decoded.height} pixels=${decoded.pixels.size}")

                cache.writeStageFile(sha, Stage.A_PREVIEW, "linear.rawbuf", decoded.pixels)
                val metaJson = LibRawJniBridge.metadataToJson(
                    decoded.metadata.copy(sourceUri = uri.toString(), fileSha256 = sha)
                )
                cache.writeMetaJson(sha, metaJson)
                cache.evictIfNeeded()
                decoded.metadata.copy(sourceUri = uri.toString(), fileSha256 = sha)
            }

            emit(RawPipelineState.PreviewLoading(1, 1f, "RAW decoded"))

            // ── Stage C: ProPhoto wide-gamut conversion ────────────────────────
            emit(RawPipelineState.PreviewLoading(2, 0f, "Wide-gamut conversion"))
            Log.d("PreviewPipeline", "Stage C: starting wide-gamut conversion")

            val linearBuf = cache.readStageFile(sha, Stage.A_PREVIEW, "linear.rawbuf")
                ?: error("linear.rawbuf missing")

            // Cache layout note: when the workspace is BIT_16, the cached workspace bitmap is
            // an `RGBA_F16` bitmap that cannot be persisted via Android's PNG encoder. We skip
            // the cache hit path for that case and rebuild Stage C from the cached linear buffer
            // — still fast (the JNI decode in Stage A is what dominates cold-open cost).
            val canUseCachedWorkspace = config.bitDepth == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth.BIT_8
            val cacheHasCube = cache.isStageComplete(sha, Stage.C_PREVIEW, "wide_gamut.cube")
            val cacheHasPng = cache.isStageComplete(sha, Stage.C_PREVIEW, "workspace.png")
            Log.i(
                "PreviewPipeline",
                "Stage C decision: bitDepth=${config.bitDepth.name} canUseCache=$canUseCachedWorkspace cube=$cacheHasCube png=$cacheHasPng",
            )
            val (cubeFile, workspaceBmp) = if (
                canUseCachedWorkspace && cacheHasCube && cacheHasPng
            ) {
                val cubeText = cache.readStageText(sha, Stage.C_PREVIEW, "wide_gamut.cube")
                    ?: error("cube missing")
                val pngBytes = cache.readStageFile(sha, Stage.C_PREVIEW, "workspace.png")
                    ?: error("workspace.png missing")
                val bmp = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                cubeText to bmp
            } else {
                emit(RawPipelineState.PreviewLoading(2, 0.3f, "Converting colour space"))
                val stageC = WideGamutConverter.convert(
                    pixelsFloat16 = linearBuf,
                    width  = metadata.outputWidth,
                    height = metadata.outputHeight,
                    metadata = metadata,
                    config = config,
                )
                cache.writeStageFile(sha, Stage.C_PREVIEW, "wide_gamut.cube", stageC.cubeFileText)
                val orientedBmp = applyExifOrientation(stageC.workspaceBitmap, metadata.orientation)
                if (canUseCachedWorkspace) {
                    val pngBytes = bitmapToPngBytes(orientedBmp)
                    cache.writeStageFile(sha, Stage.C_PREVIEW, "workspace.png", pngBytes)
                }
                stageC.cubeFileText to orientedBmp
            }

            val cubeFilePath = cache.stageFile(sha, Stage.C_PREVIEW, "wide_gamut.cube").absolutePath
            Log.d("PreviewPipeline", "Stage C: complete, emitting PreviewReady")
            emit(RawPipelineState.PreviewLoading(2, 1f, "Color space ready"))

            // ── Stage D: signal preview ready ─────────────────────────────────
            val ready = RawPipelineState.PreviewReady(
                previewBitmap = workspaceBmp,
                wideCubeFile  = cubeFilePath,
                metadata      = metadata,
                userMacro     = macro,
            )
            previewReadyState = ready
            emit(ready)

        } catch (e: Throwable) {
            Log.e("PreviewPipeline", "PIPELINE FAILED: ${e::class.simpleName}: ${e.message}", e)
            emit(RawPipelineState.Error(e.message ?: "Preview pipeline failed", e))
        }
    }

    private fun emit(state: RawPipelineState) { _state.value = state }

    fun cancel() {
        _state.value = RawPipelineState.Idle
        previewReadyState = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveToAbsolutePath(context: Context, uri: Uri): String? {
        Log.d("PreviewPipeline", "resolveToAbsolutePath: scheme=${uri.scheme} lastPathSegment=${uri.lastPathSegment}")
        return when (uri.scheme) {
            "file" -> uri.path.also { Log.d("PreviewPipeline", "resolveToAbsolutePath: file path=$it") }
            else   -> {
                val ext = uri.lastPathSegment
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() }
                    ?: run {
                        val mime = context.contentResolver.getType(uri) ?: ""
                        Log.d("PreviewPipeline", "resolveToAbsolutePath: MIME=$mime")
                        android.webkit.MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mime) ?: ""
                    }
                Log.d("PreviewPipeline", "resolveToAbsolutePath: detected ext='$ext'")
                val suffix = if (ext.isNotEmpty()) ".$ext" else ""
                val tmp = java.io.File(context.cacheDir, "raw_tmp_${uri.hashCode()}$suffix")
                Log.d("PreviewPipeline", "resolveToAbsolutePath: tmp file=${tmp.absolutePath}")
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    Log.d("PreviewPipeline", "resolveToAbsolutePath: copied to tmp, size=${tmp.length()}")
                    tmp.absolutePath
                }.getOrElse { e ->
                    Log.e("PreviewPipeline", "resolveToAbsolutePath: copy FAILED: ${e.message}", e)
                    null
                }
            }
        }
    }

    private fun bitmapToPngBytes(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Quality 0 = fastest PNG compression (still lossless — PNG ignores quality above 0).
        bmp.compress(Bitmap.CompressFormat.PNG, 0, out)
        return out.toByteArray()
    }

    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.postScale(-1f, 1f)
            3 -> matrix.postRotate(180f)
            4 -> matrix.postScale(1f, -1f)
            5 -> { matrix.postRotate(90f); matrix.postScale(1f, -1f) }
            6 -> matrix.postRotate(90f)
            7 -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.postRotate(270f)
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }
}
