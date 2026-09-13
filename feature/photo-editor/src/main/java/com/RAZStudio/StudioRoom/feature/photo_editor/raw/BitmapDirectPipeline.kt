/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.media.ExifInterface
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawStageCache.Stage
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Compatibility pipeline for non-RAW image sources (JPEG, PNG, WebP, BMP, TIFF).
 *
 * LibRaw's `dcraw_emu` cannot decode these consumer formats reliably, so we
 * bypass Stages A → B → C entirely. The decode goes through `BitmapFactory`,
 * EXIF orientation is applied, and the result is emitted directly as
 * [RawPipelineState.PreviewReady] / [RawPipelineState.FullResReady].
 *
 * The full editor — MacroProcessor, masks, export, etc. — works unchanged
 * because every downstream consumer operates on a screen-fit `previewBitmap`
 * and a full-res PNG path; both are produced here.
 *
 * Output color space: the source is assumed to be display-referred sRGB.
 * No demosaic, no white-balance correction, no wide-gamut LUT.
 */
class BitmapDirectPipeline(
    private val context: Context,
    private val cache: RawStageCache,
    private val previewReadySignal: MutableSharedFlow<RawPipelineState.PreviewReady>,
) {
    private val _previewState = MutableStateFlow<RawPipelineState>(RawPipelineState.Idle)
    val previewState = _previewState.asStateFlow()

    private val _fullResEvents = MutableSharedFlow<RawPipelineState>(replay = 1)
    val fullResEvents = _fullResEvents.asSharedFlow()

    @Volatile var previewReadyState: RawPipelineState.PreviewReady? = null
        private set

    /**
     * Decode [filePath] into a bitmap + emit PreviewReady + FullResReady.
     *
     * Both stages are produced from the same decoded bitmap — no separate
     * preview/full-res passes, since BitmapFactory already gives us the full
     * resolution and the downstream renderer scales to screen-fit on its own.
     */
    suspend fun run(
        uri: Uri,
        filePath: String,
        macro: UserMacro,
        @Suppress("UNUSED_PARAMETER") config: WorkspaceConfig,
    ) {
        val ctx = context.applicationContext
        try {
            Log.i(TAG, "run: filePath=$filePath")
            _previewState.value = RawPipelineState.PreviewLoading(1, 0f, "Decoding image")

            val sha = sha256OfFile(filePath)

            // Decode at full resolution. The result is ARGB_8888 (the only Config
            // BitmapFactory writes by default). For PNG-16/TIFF-16 sources Android
            // will downsample to 8-bit; that's the limit of the platform decoder
            // and matches what the user will see in any other Android app too.
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            }
            val decoded = BitmapFactory.decodeFile(filePath, opts)
                ?: error("BitmapFactory returned null for $filePath")
            Log.i(TAG, "decoded ${decoded.width}x${decoded.height} config=${decoded.config}")

            // Apply EXIF orientation so portrait JPEGs aren't shown sideways.
            val orientationTag = runCatching {
                ExifInterface(filePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val oriented = applyExifOrientation(decoded, orientationTag)

            // Persist as PNG so the rest of the pipeline (full-res cache, export
            // path) can read it back via the standard Stage-C-PNG mechanism.
            val workspacePng = bitmapToPngBytes(oriented)
            cache.writeStageFile(sha, Stage.C_PREVIEW, "workspace.png", workspacePng)
            // FullResPipeline reads workspace_full_base.png for full-res render.
            cache.writeStageFile(sha, Stage.C_FULLRES, "workspace_full_base.png", workspacePng)

            // Identity 3D LUT — the source is already in display sRGB so the
            // wide-gamut step is a no-op. MacroProcessor reads this cube file
            // for the LUT pass; identity means "pass through unchanged".
            val cubeText = buildIdentityCube()
            cache.writeStageFile(sha, Stage.C_PREVIEW, "wide_gamut.cube", cubeText)
            val cubeFilePath = cache.stageFile(sha, Stage.C_PREVIEW, "wide_gamut.cube").absolutePath

            // Synthesize metadata. Most fields are unknown for a non-RAW source;
            // we fill in geometry, file identity, and EXIF orientation, leaving
            // sensor-specific fields (rgbCam, white balance, black/white level)
            // at their EMPTY defaults. Downstream consumers tolerate this.
            val metadata = RawMetadata.EMPTY.copy(
                sourceUri      = uri.toString(),
                fileSha256     = sha,
                fileExtension  = uri.lastPathSegment
                    ?.substringAfterLast('.', "")
                    ?.uppercase()
                    .orEmpty(),
                rawWidth       = oriented.width,
                rawHeight      = oriented.height,
                outputWidth    = oriented.width,
                outputHeight   = oriented.height,
                aspectRatioNum = oriented.width,
                aspectRatioDen = oriented.height,
                orientation    = 1, // applied above, so downstream should treat as upright
            )

            val ready = RawPipelineState.PreviewReady(
                previewBitmap = oriented,
                wideCubeFile  = cubeFilePath,
                metadata      = metadata,
                userMacro     = macro,
            )
            previewReadyState = ready
            _previewState.value = ready
            previewReadySignal.emit(ready)

            // Full-res is the same PNG path — emit immediately so the canvas
            // doesn't sit in "processing" state.
            val fullResPath = cache.stageFile(sha, Stage.C_FULLRES, "workspace_full_base.png")
                .absolutePath
            _fullResEvents.emit(RawPipelineState.FullResReady(fullResPath, metadata))

            Log.i(TAG, "run: emitted PreviewReady + FullResReady for sha=$sha")
        } catch (e: Throwable) {
            Log.e(TAG, "PIPELINE FAILED: ${e::class.simpleName}: ${e.message}", e)
            _previewState.value = RawPipelineState.Error(
                e.message ?: "Bitmap pipeline failed", e,
            )
        }
    }

    fun cancel() {
        _previewState.value = RawPipelineState.Idle
        previewReadyState = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sha256OfFile(path: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180       -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL    -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE        -> { matrix.postRotate(90f); matrix.postScale(1f, -1f) }
            ExifInterface.ORIENTATION_ROTATE_90        -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE       -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270       -> matrix.postRotate(270f)
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    private fun bitmapToPngBytes(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 0, out)
        return out.toByteArray()
    }

    /** 33×33×33 identity 3D LUT — same shape as WideGamutConverter output. */
    private fun buildIdentityCube(size: Int = 33): String {
        val sb = StringBuilder()
        sb.append("TITLE \"Bitmap direct identity\"\n")
        sb.append("LUT_3D_SIZE $size\n")
        sb.append("DOMAIN_MIN 0.0 0.0 0.0\n")
        sb.append("DOMAIN_MAX 1.0 1.0 1.0\n\n")
        val step = 1f / (size - 1)
        // Cube ordering: B outermost, then G, then R (standard .cube format).
        for (b in 0 until size) {
            val bv = b * step
            for (g in 0 until size) {
                val gv = g * step
                for (r in 0 until size) {
                    val rv = r * step
                    sb.append("%.6f %.6f %.6f\n".format(rv, gv, bv))
                }
            }
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "BitmapDirectPipeline"

        /** Extensions handled by this pipeline (bypasses LibRaw). */
        private val NON_RAW_EXTENSIONS = setOf(
            "jpg", "jpeg", "jpe",
            "png",
            "webp",
            "bmp",
            "tif", "tiff",
        )

        /** True if [uri]'s extension is a standard consumer image format. */
        fun isNonRawSource(context: Context, uri: Uri): Boolean {
            val name = runCatching {
                if (uri.scheme == "content") {
                    context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } else uri.lastPathSegment
            }.getOrNull().orEmpty()
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in NON_RAW_EXTENSIONS
        }
    }
}
