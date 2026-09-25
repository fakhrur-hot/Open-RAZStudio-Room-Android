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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw


import com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat
import com.RAZStudio.StudioRoom.core.utils.AppLog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import com.RAZStudio.StudioRoom.core.ui.widget.dialogs.LoadingDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.BugReport
import com.RAZStudio.StudioRoom.core.resources.icons.ImageSearch
import com.RAZStudio.StudioRoom.core.resources.icons.KeyboardArrowDown
import com.RAZStudio.StudioRoom.core.resources.icons.IosShare
import com.RAZStudio.StudioRoom.core.resources.icons.Lock
import com.RAZStudio.StudioRoom.core.resources.icons.LockOpen
import com.RAZStudio.StudioRoom.core.resources.icons.Save
import com.RAZStudio.StudioRoom.core.resources.icons.Settings
import com.RAZStudio.StudioRoom.core.resources.icons.Tune
import com.RAZStudio.StudioRoom.core.ui.utils.provider.LocalOpenAppSettings
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawStageCache
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.toTypeface
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.WatermarkTextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Format descriptor ────────────────────────────────────────────────────────

enum class RawExportFormat(
    val label: String,
    val mimeType: String,
    val extension: String,
) {
    PNG      ("PNG",        "image/png",  "png"),
    PNG_16   ("PNG 16-bit", "image/png",  "png"),
    TIFF     ("TIFF 16-bit","image/tiff", "tiff"),
    JPG      ("JPG",        "image/jpeg", "jpg"),
    WEBP     ("WEBP",       "image/webp", "webp"),
    HEIC     ("HEIC",       "image/heic", "heic"),
    BMP      ("BMP",        "image/bmp",  "bmp"),
    JPEG2000 ("JPEG 2000",  "image/jp2",  "jp2"),
    AVIF     ("AVIF (HDR)", "image/avif", "avif"),
    JXL      ("JPEG XL (HDR)", "image/jxl", "jxl"),
}

/**
 * Map the app-wide default [ImageFormat] (main Settings page — single source of
 * truth) to the RAW editor's export format. 16-bit outputs (PNG16, 16-bit TIFF)
 * are intentionally NOT produced (per product decision to drop 16-bit): TIFF/Tif
 * fall back to 8-bit PNG. Null / unmapped formats default to JPG.
 */
internal fun ImageFormat?.toRawExportFormat(): RawExportFormat = when (this) {
    is ImageFormat.Png      -> RawExportFormat.PNG
    is ImageFormat.Webp     -> RawExportFormat.WEBP
    is ImageFormat.Heic, is ImageFormat.Heif -> RawExportFormat.HEIC
    ImageFormat.Bmp         -> RawExportFormat.BMP
    is ImageFormat.Avif     -> RawExportFormat.AVIF
    is ImageFormat.Jxl      -> RawExportFormat.JXL
    is ImageFormat.Jpeg2000 -> RawExportFormat.JPEG2000
    ImageFormat.Tiff, ImageFormat.Tif -> RawExportFormat.PNG   // 16-bit TIFF dropped → 8-bit PNG
    else                    -> RawExportFormat.JPG   // Jpg/Jpeg/MozJpeg/Jpegli/Qoi/Ico/Gif/null
}

// ── Public entry point ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawExportScreen(
    component: RawEditorComponent,
    onGoBack: () -> Unit,
    onGoToEditor: () -> Unit,
    onPickNewImage: () -> Unit,
    onOpenDetailsEditor: () -> Unit,
) {
    val context = LocalContext.current
    val exportSavedMsg = stringResource(R.string.raw_export_saved)
    val exportSaveFailedMsg = stringResource(R.string.raw_export_save_failed)
    val scope   = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }

    // Live pipeline state — previewBitmap already has current macro applied, screen-fit
    val uiState by component.uiState.collectAsState()
    val pipelineReady = uiState as? RawPipelineState.PreviewReady
    // v3 emits only SENTINELS in PreviewReady (1×1 bitmap + RawMetadata.EMPTY)
    // because the live preview is a SurfaceView and the real metadata lives on
    // the component. Earlier code used `pipelineReady?.previewBitmap ?: neutral`
    // and `pipelineReady?.metadata ?: rawMetadata`, but the sentinels are
    // NON-null so they always won — leaving the Export page with a 1×1 image
    // and blank EXIF. Prefer the component's real sources directly.
    val neutralBmp by component.neutralBitmapFlow.collectAsState()
    // Serialized Stage B preview bitmap (loaded from B_preview.f16). Used as a
    // neutral fallback so the Export page doesn't re-run Stage B downsample.
    val stageBBmp by component.stageBSnapshotBitmap.collectAsState()
    // Prefer the graded GL snapshot captured on navigation (matches the editor
    // + saved file). Fall back to the Stage B snapshot, then the neutral Stage A
    // thumbnail, then the pipeline sentinel.
    val gradedPreview by component.gradedPreview.collectAsState()
    val healProtectMask by component.masking.maskBitmap.collectAsState()
    val healSegmentMasks by component.masking.segmentationMasksV3.collectAsState()
    val sentinelPreview = pipelineReady?.previewBitmap
    // Crop sheet (RAW Export transform bar) — see RawCropSheet. Result
    // is held locally for the preview AND threaded into Save as a
    // normalised crop rect so the saved file is also cropped.
    var showCropSheet by remember { mutableStateOf(false) }
    var cosmeticCroppedPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showHealSheet by remember { mutableStateOf(false) }
    var cosmeticHealedPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Border / frame — expands the canvas outward (never crops the photo). Like
    // Heal/Cloud it's held as a cosmetic override bitmap and passed to Save as
    // overrideBitmap; it's applied OUTERMOST, over whatever the user currently
    // sees (crop/heal/cloud already baked into previewBitmap). Thickness is a
    // fraction of the long side; colour is ARGB. Remembered so re-opening the
    // sheet restores the last settings.
    var showBorderSheet by remember { mutableStateOf(false) }
    var cosmeticBorderedPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var borderThickness by remember { mutableStateOf(0.075f) }  // 50% of MAX_BORDER_FRACTION (owner default)
    var borderColorArgb by remember { mutableStateOf(android.graphics.Color.WHITE) }
    // Online AI Editing (AI Beautify) — same full-replacement-bitmap shape as
    // Heal, composited ahead of it since a cloud edit result should always be
    // the freshest truth once applied (Requirement 9.1). No Action_Card is
    // created; Reset clears this exactly like Crop/Heal/Watermark.
    var showOnlineAiEditSheet by remember { mutableStateOf(false) }
    var cosmeticCloudEditPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var cloudEditJobId by remember { mutableStateOf<String?>(null) }
    var showOnlineAiConsentDialog by remember { mutableStateOf(false) }
    // Full-resolution bitmap prepared by running Stage C to a temp file.
    // Loaded once when the user first opens the Heal sheet so heal edits
    // happen at full sensor resolution. Null until prepared (or on failure,
    // falls back to preview-res).
    var fullResBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isPreparingFullRes by remember { mutableStateOf(false) }
    // Checkpoint — a copy of basePreview captured the first time it is
    // non-null, never mutated by Crop / Heal / Watermark. Used by the
    // Reset button to wipe all transform-bar changes.
    var checkpointPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Watermark config — session state, never baked into the preview bitmap.
    // Burned onto the final export bitmap only at Save time, AFTER crop+rotate,
    // so position anchors stay correct regardless of canvas transformations.
    val appContext = LocalContext.current
    var watermarkConfig by remember { mutableStateOf<com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.CombinedWatermarkConfig?>(null) }
    var showWatermarkSheet by remember { mutableStateOf(false) }
    var showAiDenoiseSheet by remember { mutableStateOf(false) }
    var aiDenoiseSession by remember {
        mutableStateOf(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseEditSession())
    }
    var aiAppliedPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var denoiseLongSide by remember { mutableIntStateOf(0) }
    val aiDenoiseModel by remember(context) {
        mutableStateOf(
            runCatching {
                com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseModelAssetLoader.load(context.applicationContext)
            }.getOrNull(),
        )
    }
    val aiDenoiseRunner = remember(context, aiDenoiseModel) {
        aiDenoiseModel?.let {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseRunner().apply {
                load(context.applicationContext, it.manifestAsset, it.modelAsset)
            }
        }
    }
    val aiDenoiseAdapterParams = remember(context, aiDenoiseModel) {
        aiDenoiseModel?.let {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseAdapterParams(
                gamma = it.manifest.adapterGamma,
                alpha = it.manifest.adapterStrength,
                maxVal = it.manifest.adapterMaxValue,
            )
        } ?: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseAdapterParams(
            gamma = 2.2f,
            alpha = 0.10f,
            maxVal = 65535f,
        )
    }
    // RawExportScreen owns preview scheduling and Stage A buffer lifetime. The
    // scheduler remains idle until an approved model and Stage A proxy are available.
    val aiDenoisePreviewScheduler = remember(scope) {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoisePreviewScheduler<android.graphics.Bitmap>(scope)
    }
    val aiDenoisePreviewState by aiDenoisePreviewScheduler.state.collectAsState()
    val aiDenoiseLivePreview = remember(aiDenoisePreviewState) {
        (aiDenoisePreviewState as? com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoisePreviewState.Ready<android.graphics.Bitmap>)?.value
    }
    val basePreview: Bitmap? = gradedPreview
        ?: stageBBmp
        ?: neutralBmp
        ?: sentinelPreview?.takeIf { it.width > 1 || it.height > 1 }
    val persistentCanvasBitmap: Bitmap? = remember(
        basePreview,
        fullResBitmap,
        cosmeticCroppedPreview,
        cosmeticHealedPreview,
        cosmeticCloudEditPreview,
        watermarkConfig,
        aiAppliedPreview,
        context,
    ) {
        var source = aiAppliedPreview
            ?: cosmeticCloudEditPreview
            ?: cosmeticHealedPreview
            ?: cosmeticCroppedPreview
            ?: fullResBitmap
            ?: basePreview
        val activeWatermark = watermarkConfig
        if (source != null && activeWatermark != null) {
            source = runCatching {
                val copy = source.copy(Bitmap.Config.ARGB_8888, true)
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                    .burnCombinedWatermarkOnto(copy, activeWatermark, context)
            }.getOrElse { source }
        }
        source
    }
    val previewBitmap: Bitmap? = aiDenoiseLivePreview
        ?: persistentCanvasBitmap
    // Trigger a denoise preview whenever the draft state changes and a proxy buffer exists.
    LaunchedEffect(aiDenoiseSession.draft, previewBitmap, aiDenoiseRunner, aiDenoiseModel) {
        val state = aiDenoiseSession.draft
        val bitmap = previewBitmap ?: return@LaunchedEffect
        val runner = aiDenoiseRunner ?: return@LaunchedEffect
        AppLog.i("AiDenoise", "preview request: enabled=${state.enabled} strength=${state.strength} input=${bitmap.width}x${bitmap.height}")
        if (!state.enabled) {
            AppLog.i("AiDenoise", "preview request disabled; cancelling queued denoise preview")
            aiDenoisePreviewScheduler.cancel()
            return@LaunchedEffect
        }
        val stageAProxy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val proxyRgb = IntArray(stageAProxy.width * stageAProxy.height * 3)
        val pixels = IntArray(stageAProxy.width * stageAProxy.height)
        stageAProxy.getPixels(pixels, 0, stageAProxy.width, 0, 0, stageAProxy.width, stageAProxy.height)
        var i = 0
        for (pixel in pixels) {
            val a = android.graphics.Color.alpha(pixel)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            proxyRgb[i++] = r
            proxyRgb[i++] = g
            proxyRgb[i++] = b
            if (a == 0) {
                proxyRgb[i - 3] = 0
                proxyRgb[i - 2] = 0
                proxyRgb[i - 1] = 0
            }
        }
        aiDenoisePreviewScheduler.submit(state) {
            val tile = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseTileStitcher.processRgb16(
                input = proxyRgb,
                width = stageAProxy.width,
                height = stageAProxy.height,
            ) { tileInput ->
                val normalized = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseTensorContract.uint16ToNormalized(tileInput)
                val output = runner.runRgbTile(normalized)
                com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseTensorContract.normalizedToUint16(output)
            }
            android.graphics.Bitmap.createBitmap(stageAProxy.width, stageAProxy.height, Bitmap.Config.ARGB_8888).also { out ->
                val outPixels = IntArray(tile.size / 3)
                var pos = 0
                for (idx in tile.indices step 3) {
                    val r = tile[idx].coerceIn(0, 65535)
                    val g = tile[idx + 1].coerceIn(0, 65535)
                    val b = tile[idx + 2].coerceIn(0, 65535)
                    outPixels[pos++] = android.graphics.Color.rgb(r shr 8, g shr 8, b shr 8)
                }
                out.setPixels(outPixels, 0, stageAProxy.width, 0, 0, stageAProxy.width, stageAProxy.height)
            }
        }
    }
    // Online AI Editing consent — per-install flag (Requirement 7.4), not
    // Hilt-injected, following the same manual-remember pattern already used
    // for RawBatchPrefs (see RawBatchSettingsPanel).
    val onlineAiConsentPrefs = remember {
        com.RAZStudio.StudioRoom.core.settings.domain.OnlineAiConsentPrefs(appContext.applicationContext)
    }
    // Normalised crop rect derived from the cropped bitmap's dims against
    // the basePreview's dims at confirm time. Identity by default.
    var cropRectL by remember { mutableStateOf(0f) }
    var cropRectT by remember { mutableStateOf(0f) }
    var cropRectR by remember { mutableStateOf(1f) }
    var cropRectB by remember { mutableStateOf(1f) }
    var cropRotationDeg by remember { mutableStateOf(0f) }
    // Discrete orientation from the crop sheet (img.ly TRANSFORM parity).
    var cropOrient90 by remember { mutableStateOf(0) }       // 0..3 quarter-turns CW
    var cropFlipHState by remember { mutableStateOf(false) }
    var cropFlipVState by remember { mutableStateOf(false) }
    // Underlying preview from the pipeline. The transform-bar Crop sheet
    // may override this with a cosmetic crop (cosmeticCroppedPreview)
    // that's preferred for downstream display.
    // Capture checkpoint the first time basePreview arrives — never updated.
    if (basePreview != null && checkpointPreview == null) {
        checkpointPreview = basePreview
    }
    // Cloud edit beats heal beats crop beats base — each later sheet's Apply
    // runs on whatever the user currently sees, so the most-recently-applied
    // edit is always the freshest truth.
    val componentMeta = component.rawMetadata
    val pipelineMeta = pipelineReady?.metadata?.takeIf {
        it.cameraMake.isNotBlank() || it.rawWidth > 0
    }
    val stageAMeta: RawMetadata? = componentMeta ?: pipelineMeta
    // ExifInterface fallback: Stage A (LibRaw) only fills RAW sources, so JPEG/
    // HEIC/PNG have blank camera/lens/exposure/date. This reads the file's own
    // EXIF and fills any field Stage A left empty (Stage A wins when present).
    val exifFallback by androidx.compose.runtime.produceState<RawMetadata?>(null, stageAMeta) {
        value = runCatching { component.readSourceExifMetadata() }.getOrNull()
    }
    val metadata: RawMetadata? = remember(stageAMeta, exifFallback) {
        val a = stageAMeta
        val b = exifFallback
        when {
            a == null -> b
            b == null -> a
            else -> a.copy(
                cameraMake       = a.cameraMake.ifBlank { b.cameraMake },
                cameraModel      = a.cameraModel.ifBlank { b.cameraModel },
                lensInfo         = a.lensInfo.ifBlank { b.lensInfo },
                iso              = if (a.iso > 0) a.iso else b.iso,
                shutterSpeed     = if (a.shutterSpeed > 0f) a.shutterSpeed else b.shutterSpeed,
                aperture         = if (a.aperture > 0f) a.aperture else b.aperture,
                focalLength      = if (a.focalLength > 0f) a.focalLength else b.focalLength,
                dateTimeOriginal = a.dateTimeOriginal.ifBlank { b.dateTimeOriginal },
            )
        }
    }

    // Attach EXIF config once metadata is available (EXIF needs live metadata for ISO/shutter/etc.)
    LaunchedEffect(metadata) {
        if (metadata != null) {
            val current = watermarkConfig
            if (current != null) {
                watermarkConfig = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                    .attachExifConfig(appContext, metadata, current)
            }
        }
    }

    // Original RAW file size (from ContentResolver, not the rendered bitmap)
    var rawFileSizeBytes by remember { mutableStateOf<Long?>(null) }
    // Resolved display name and real path for the source file
    var sourceDisplayName by remember { mutableStateOf<String?>(null) }
    var sourceRealPath by remember { mutableStateOf<String?>(null) }

    // Full-res pipeline ready signal — used to re-read actual sensor dims from dims.bin
    val fullResReady by component.fullResReady.collectAsState()

    // Post-orientation Stage C dimensions: what the exported PNG will actually be.
    // Prefer dims.bin (full-res pass), fall back to metadata × 2. Either source stores
    // the *unrotated* sensor W,H; for EXIF orientations 5–8 (90° / 270° rotations) the
    // exported bitmap has width and height swapped, so we mirror that swap here. Without
    // this, a portrait-shot landscape-sensor RAW shows e.g. 5496×3668 in the export page
    // while the actual saved PNG is 3668×5496 — the bug the user reported.
    var origW by remember { mutableStateOf(0) }
    var origH by remember { mutableStateOf(0) }
    LaunchedEffect(fullResReady, metadata) {
        // Pipeline contract (V2, post-2026-05-24):
        //  • Native `decode_core` uses `user_flip=0` so the buffer + reported dims are
        //    always landscape-canonical (sensor-native, matches Canon's 5472×3648 spec).
        //  • EXIF orientation comes through `metadata.orientation` separately (1=landscape,
        //    6=portrait CW, 8=portrait CCW, etc.). Rotation is applied at render/export
        //    time, never baked into the dims.
        //
        // RAW Export "Original resolution" label shows the **as-displayed** dims —
        // i.e. the same numbers the user sees on the camera's playback screen
        // (5472×3648 for landscape, 3648×5472 for portrait). That's the landscape
        // dims swapped when orientation tag indicates 90° rotation.
        val (sensorW, sensorH) = when {
            // The v3 component does not expose the legacy full-res cache API. The preview metadata's
            // outputWidth/Height is the half-size decode result. Multiply by 2 to
            // estimate full sensor. The "looksAlreadyFull" guard handles the rare
            // CR2 variants where LibRaw ignores half_size=1 (verified 2026-05-24
            // Canon EOS 6D IMG_2744.CR2).
            metadata != null -> {
                val pw = metadata.outputWidth.takeIf { it > 0 } ?: metadata.rawWidth
                val ph = metadata.outputHeight.takeIf { it > 0 } ?: metadata.rawHeight
                val looksAlreadyFull = pw > 3500 || ph > 3500
                if (looksAlreadyFull) pw to ph else (pw * 2) to (ph * 2)
            }
            else -> 0 to 0
        }
        // EXIF orientations 5/6/7/8 = 90°-rotated (portrait); swap W↔H for display.
        // Orientations 1/2/3/4 = landscape (incl. mirrored/180°); keep W×H as-is.
        val orientation = metadata?.orientation ?: 1
        val swap = orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8
        if (swap) {
            origW = sensorH
            origH = sensorW
        } else {
            origW = sensorW
            origH = sensorH
        }
    }

    // Dimension text fields — seeded from origW/origH, user-editable
    var dimW by remember(origW) { mutableStateOf(if (origW > 0) origW.toString() else "") }
    var dimH by remember(origH) { mutableStateOf(if (origH > 0) origH.toString() else "") }
    var isAspectLocked by remember { mutableStateOf(true) }

    LaunchedEffect(metadata?.sourceUri) {
        withContext(Dispatchers.IO) {
            val uriStr = metadata?.sourceUri ?: return@withContext
            val uri = Uri.parse(uriStr)
            // Resolve display name and real path
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        val data = if (dataIdx >= 0) runCatching { cursor.getString(dataIdx) }.getOrNull() else null
                        withContext(Dispatchers.Main) {
                            sourceDisplayName = name
                            sourceRealPath = data ?: name
                        }
                    }
                }
            }
            // File size
            rawFileSizeBytes = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.statSize.takeIf { it > 0 }
                }
            }.getOrNull()
        }
    }

    val workspaceConfig by component.workspaceConfigFlow.collectAsState()
    // Output format locked to JPEG — format picker UI is hidden.
    @Suppress("UNUSED_VARIABLE")
    val appSettings by component.settingsFlow.collectAsState()
    val selectedFormat = RawExportFormat.JPG
    var showClearCacheDialog by remember { mutableStateOf(false) }
    // Simplified Saved-Presets selector shown above Dimensions. Re-reads the
    // index after a save; selection defaults to the preset matching the current
    // edit, else null → "Current (unsaved)".
    var exportPresets by remember { mutableStateOf(component.loadPresetIndex()) }
    var selectedPresetIdx by remember { mutableStateOf(component.matchingPresetIndex()) }
    // Warning dialog for the Details transform-bar button. Tapping Details
    // shows this; OK navigates to the new Details editor (commit 3 adds the
    // actual bake — entering Details destroys the existing editor cache so
    // the warning copy is honest about it).
    var showDetailsWarning by remember { mutableStateOf(false) }
    // `isSaving` is now sourced from the component so it survives configuration
    // changes (rotation).
    val isSaving by component.isSaving.collectAsState()
    // Consume save result → snackbar. Survives rotation because the component
    // remembers the result until consumeLastSaveResult() acknowledges it.
    val lastSaveResult by component.lastSaveResult.collectAsState()
    val lastSavedUri by component.lastSavedUri.collectAsState()
    // Section UI state
    val _exportPrefs = remember { com.RAZStudio.StudioRoom.core.settings.domain.RawBatchPrefs(context) }
    var useFullResolution by remember { mutableStateOf(_exportPrefs.exportUseFullResolution) }

    // Save-time toggles for the File Info section
    var saveIcc  by remember { mutableStateOf(_exportPrefs.exportSaveIcc) }
    // EXIF policy mirrors the Batch UI: KeepAll / StripSensitive /
    // NoneExceptSoftware. RAZStudio Software tag is always written.
    var exifPolicy by remember {
        mutableStateOf(
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.entries
                .getOrElse(_exportPrefs.exportExifPolicyOrdinal) {
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.KeepAll
                },
        )
    }

    LaunchedEffect(lastSaveResult) {
        val r = lastSaveResult ?: return@LaunchedEffect
        if (r) {
            // Persist the user's current choices so the next export opens
            // with the same settings rather than factory defaults.
            _exportPrefs.exportSaveIcc            = saveIcc
            _exportPrefs.exportExifPolicyOrdinal  = exifPolicy.ordinal
            _exportPrefs.exportUseFullResolution  = useFullResolution
        }
        snackbarState.showSnackbar(if (r) exportSavedMsg else exportSaveFailedMsg)
        component.consumeLastSaveResult()
    }

    val hasPreview = metadata != null && previewBitmap != null

    // Back from Export → main page directly (no confirmation needed, editor state is preserved)
    BackHandler { onGoBack() }

    // Blocking save UI (owner rule 2026-09-07: every save/export shows the app's
    // modal spinner; back/taps only reach a "cancel?" prompt until it completes).
    // Same LoadingDialog the conventional editors use, so behaviour is uniform.
    LoadingDialog(
        visible = isSaving,
        onCancelLoading = { component.cancelSaveToGallery() },
        canCancel = true,
        isForSaving = true,
    )

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title   = { Text(stringResource(R.string.raw_export_clear_cache_title)) },
            text    = { Text(stringResource(R.string.raw_export_clear_cache_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    scope.launch(Dispatchers.IO) {
                        metadata?.fileSha256?.let { sha ->
                            val cache = RawStageCache(context)
                            RawStageCache.Stage.entries.forEach { stage ->
                                cache.stageDir(sha, stage).deleteRecursively()
                            }
                            cache.metaFile(sha).delete()
                        }
                        // Also clear saved Actions for this session (and persist the empty list)
                        // so the next picked file starts with a fresh action stack.
                        withContext(Dispatchers.Main) {
                            component.clearAllActions()
                            onPickNewImage()
                        }
                    }
                }) {
                    Text(stringResource(R.string.raw_export_clear_cache_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.raw_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text  = stringResource(R.string.raw_export_screen),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (rawFileSizeBytes != null) {
                            Text(
                                text  = formatFileSize(rawFileSizeBytes!!),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    val openAppSettings = LocalOpenAppSettings.current
                    IconButton(onClick = openAppSettings) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                    // Phase 4 — drift verification harness. Debug-build only.
                    // Lives on the Export page (not the editor) because the
                    // harness compares the editor GL snapshot against the
                    // most-recent saved file — both are guaranteed to exist
                    // by the time the user reaches this screen.
                    if (com.RAZStudio.StudioRoom.feature.photo_editor
                        .BuildConfig.DEBUG) {
                        IconButton(onClick = {
                            // Look up the debug "lastsave" file the coordinator
                            // dumps before SAF publish. Avoids the user-facing
                            // display-path mess (e.g. "Device storage/...")
                            // that BitmapFactory can't decode.
                            val verifyDir = java.io.File(
                                context.cacheDir, "verify_lastsave",
                            )
                            val savedFile = verifyDir.listFiles()
                                ?.firstOrNull { it.isFile && it.length() > 0 }
                            AppLog.i("PresetVerifyHarness",
                                "verify button tapped; hasPreview=$hasPreview " +
                                "gradedPreview=${gradedPreview != null} " +
                                "basePreview=${basePreview != null} " +
                                "lastsave=${savedFile?.absolutePath} " +
                                "size=${savedFile?.length() ?: 0}")
                            scope.launch {
                                // Compare the GRADED GL snapshot ONLY — never
                                // basePreview, which silently degrades to the
                                // UNGRADED Stage B/neutral image. Comparing that
                                // against the graded saved file produced a bogus
                                // colour MAD (~60) that looked like a WB/tint/
                                // saturation defect but was just the missing grade.
                                val snap = gradedPreview
                                if (snap == null) {
                                    snackbarState.showSnackbar(
                                        message = "Verify: graded preview unavailable — re-open this photo from the editor (Apply → Export) so the live canvas is captured first.",
                                        duration = androidx.compose.material3.SnackbarDuration.Long,
                                    )
                                    return@launch
                                }
                                if (savedFile == null) {
                                    snackbarState.showSnackbar(
                                        "Verify: save a file first (Apply → Export)"
                                    )
                                    return@launch
                                }
                                snackbarState.showSnackbar(
                                    "Verify: comparing editor vs saved file…"
                                )
                                val result = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.Default,
                                ) {
                                    com.RAZStudio.StudioRoom.feature
                                        .photo_editor.raw.preset
                                        .PresetVerifyHarness.run(
                                            context = context,
                                            editorSnapshot = snap,
                                            savedFilePath  = savedFile.absolutePath,
                                        )
                                }
                                val msg = if (result.success) {
                                    val patch = if (result.centerPatchMad >= 0)
                                        " patch=${"%.1f".format(result.centerPatchMad)}" else ""
                                    "MAD ${"%.1f".format(result.meanAbsDiff)}$patch /255 in ${result.durationMs}ms"
                                } else {
                                    "Verify failed: ${result.error}"
                                }
                                snackbarState.showSnackbar(
                                    message = msg,
                                    duration = androidx.compose.material3
                                        .SnackbarDuration.Long,
                                )
                            }
                        }) {
                            Icon(
                                Icons.Rounded.BugReport,
                                contentDescription = "Verify save fidelity",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            RawExportBottomBar(
                isSaving        = isSaving,
                hasPreview      = hasPreview,
                onShare         = if (lastSavedUri != null) ({ component.shareLastSaved(context) }) else null,
                onSave          = {
                    // Rotation-survivable save: kicks off the component-scoped
                    // coroutine, which lives as long as the Decompose component
                    // itself. The screen observes isSaving + lastSaveResult,
                    // so a rotation just rebuilds the UI on top of the same
                    // in-flight save instead of killing it.
                    val denoiseEnabled = aiDenoiseSession.committed.enabled && aiDenoiseRunner != null
                    val tw = if (useFullResolution) 0 else (dimW.toIntOrNull() ?: 0)
                    val th = if (useFullResolution) 0 else (dimH.toIntOrNull() ?: 0)
                    // Bake the on-screen healed preview when the user
                    // opted in. The healed bitmap incorporates all
                    // upstream edits (camera-style finish, LUT, tone
                    // curves, crop, rotate, heals) at preview
                    // resolution — output dimensions become the
                    // bitmap's dimensions. When the toggle is off (or
                    // there's no heal), we fall through to the full-
                    // res Stage A→C re-run as before.
                    // Cloud edit beats heal — see the previewBitmap composition
                    // chain above for the same ordering rationale.
                    // Composition order photo → watermark → border: when a border
                    // is present the watermark must sit on the PHOTO and the border
                    // frame around it — never the reverse. So we pre-compose the
                    // finished bitmap here (burn the watermark onto the un-bordered
                    // photo, then frame the border) and hand it to the coordinator
                    // with NO separate watermark (otherwise it would burn over the
                    // border). Without a border, the coordinator burns the watermark
                    // at full export resolution as before.
                    // Border is no longer pre-composed on the ~1280 px preview: it is
                    // an export option the coordinator frames onto the FULL-RES
                    // result after the watermark (fixes bordered saves coming out at
                    // preview size). The heal/cloud override still rides the direct
                    // path — the component applies watermark → border to it too.
                    val override: Bitmap? = aiAppliedPreview
                        ?: cosmeticCloudEditPreview
                        ?: cosmeticHealedPreview
                    val exportBorder = if (cosmeticBorderedPreview != null) borderThickness else 0f
                    component.triggerSaveToGallery(
                        context = context,
                        format = selectedFormat,
                        targetWidth = tw,
                        targetHeight = th,
                        exifPolicy = exifPolicy,
                        saveIcc = saveIcc,
                        cropL = cropRectL,
                        cropT = cropRectT,
                        cropR = cropRectR,
                        cropB = cropRectB,
                        cropRotationDeg = cropRotationDeg,
                        cropRotate90 = cropOrient90,
                        cropFlipH = cropFlipHState,
                        cropFlipV = cropFlipVState,
                        overrideBitmap = override,
                        watermarkConfig = watermarkConfig,
                        cloudEditJobId = cloudEditJobId,
                        borderThickness = exportBorder,
                        borderColorArgb = borderColorArgb,
                        aiDenoiseSession = aiDenoiseSession,
                        aiDenoiseRunner = aiDenoiseRunner,
                    )
                },
                onImagePicker   = { showClearCacheDialog = true },
            )
        },
    ) { paddingValues ->
        // Empty state — no file opened yet
        if (metadata == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Rounded.ImageSearch,
                        contentDescription = null,
                        modifier           = Modifier.size(72.dp),
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text  = stringResource(R.string.raw_export_no_file),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onPickNewImage) {
                        Icon(Icons.Rounded.ImageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.raw_export_pick_photo))
                    }
                }
            }
        } else {

        LazyColumn(
            modifier       = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
        ) {

            // ── Stage-A preview image ──────────────────────────────────────────
            item {
                // Preview-only color space soft-proof. Default to workspace gamut
                // (matches the canvas the user just left); toggling to sRGB
                // re-renders the same pixels via WideGamutConverter so the user
                // sees what the file would look like in an sRGB context. Does
                // NOT affect the save — the save still uses macro.outputColorSpace
                // which is set by applyRazamazeDefaults from the workspace.
                val workspaceGamut = remember(previewBitmap) {
                    val tag = previewBitmap?.colorSpace?.name ?: ""
                    when {
                        tag.contains("Display P3", ignoreCase = true) ||
                            tag.contains("DCI-P3", ignoreCase = true) ->
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.DISPLAY_P3
                        tag.contains("ProPhoto", ignoreCase = true) ->
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.PROPHOTO_RGB
                        else ->
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.SRGB
                    }
                }
                var viewAsSrgb by rememberSaveable(workspaceGamut) {
                    mutableStateOf(false)  // default = workspace gamut
                }
                // The sRGB-rendition is cached so toggling doesn't re-convert
                // on every recomposition.
                val srgbBitmap = remember(previewBitmap) {
                    previewBitmap?.let { src ->
                        if (workspaceGamut == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.SRGB) {
                            src  // no conversion needed
                        } else {
                            runCatching {
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.WideGamutConverter
                                    .convertBitmapToColorSpace(
                                        src,
                                        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.SRGB,
                                    )
                            }.getOrNull() ?: src
                        }
                    }
                }
                // Composition order is photo → watermark → border. The watermark
                // affects ONLY the photo pixels; the border is the outermost frame,
                // added AROUND the watermarked photo so it is never covered by the
                // watermark. Both preview and export follow this exact order.
                val photoBase: Bitmap? =
                    if (viewAsSrgb) srgbBitmap else previewBitmap
                val hasBorder = cosmeticBorderedPreview != null

                // Photo-gallery canvas: a bounded viewport whose Image is sized to the
                // final composited image's EXACT aspect ratio and fitted WHOLE (no
                // overflow, no letterbox bars). The watermark is burned at the photo's
                // own resolution so its position/size match the saved file exactly.
                val maxCanvasHeightDp = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxCanvasHeightDp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (photoBase != null) {
                        val base = photoBase
                        // WYSIWYG single source of truth: burn the watermark onto the
                        // PHOTO, then frame the border around it — the same ordering
                        // the export uses. Runs off-main; until it completes we show
                        // the current bordered/plain bitmap.
                        val initialShown = cosmeticBorderedPreview ?: base
                        val shownBitmap by produceState(
                            initialShown, base, watermarkConfig, hasBorder,
                            borderThickness, borderColorArgb,
                        ) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                runCatching {
                                    var b: Bitmap = base
                                    val cfg = watermarkConfig
                                    if (cfg != null) {
                                        b = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                                            .burnCombinedWatermarkOnto(
                                                b.copy(Bitmap.Config.ARGB_8888, true), cfg, context,
                                            )
                                    }
                                    if (hasBorder) {
                                        b = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                                            .applyBorderToBitmap(b, borderThickness, borderColorArgb)
                                    }
                                    b
                                }.getOrDefault(initialShown)
                            }
                        }
                        val ratio = shownBitmap.width.toFloat() / shownBitmap.height.toFloat()
                        // Largest rect with the composited AR that fits (maxWidth × maxHeight).
                        val fitW: androidx.compose.ui.unit.Dp
                        val fitH: androidx.compose.ui.unit.Dp
                        if (maxWidth / ratio <= maxHeight) { fitW = maxWidth; fitH = maxWidth / ratio }
                        else { fitH = maxHeight; fitW = maxHeight * ratio }
                        Image(
                            bitmap       = shownBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier     = Modifier
                                .width(fitW)
                                .height(fitH)
                                .clip(RoundedCornerShape(0.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                // Soft-proof toggle row. Hidden when workspace is already sRGB
                // (toggle would be a no-op).
                if (workspaceGamut != com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.SRGB) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text  = "View as",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ViewAsChip(
                            label    = workspaceGamut.displayName,
                            selected = !viewAsSrgb,
                            onClick  = { viewAsSrgb = false },
                        )
                        ViewAsChip(
                            label    = "sRGB",
                            selected = viewAsSrgb,
                            onClick  = { viewAsSrgb = true },
                        )
                    }
                }
            }

            // ── Razsizr-style transform bar ──────────────────────────────────
            //
            // Replaces the old single "Go to Editor" OutlinedButton with a
            // single-row bar that hosts BOTH actions:
            //
            //   [ Go to Editor (text) ............... [ ⚙ Details ] ]
            //
            // Rotate-L / Flip / Rotate-R from Razsizr's bar are deliberately
            // dropped — the v3 RAW pipeline does orientation at encode time,
            // not as a live post-process, so on-bar rotation needs Stage C
            // surgery that's not in scope here.
            //
            // Visibility rule unchanged from the old button: the bar hides
            // when the editor session is gone (uiState == Idle) — same
            // Save-clears-cache semantics. The Details button additionally
            // hides once the bake commits (handled in commit 3 via a
            // component flag; for now it's always visible alongside Go to
            // Editor).
            if (uiState !is RawPipelineState.Idle && previewBitmap != null) {
                item {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                    com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
                        .components.RawExportTransformBar(
                        hasAiDenoise = aiDenoiseSession.committed.enabled,
                        hasWatermark = watermarkConfig != null,
                        hasCrop = cosmeticCroppedPreview != null,
                        hasHeal = cosmeticHealedPreview != null,
                        hasBorder = cosmeticBorderedPreview != null,
                        hasTransformChanges = cosmeticCroppedPreview != null
                            || cosmeticHealedPreview != null
                            || cosmeticBorderedPreview != null
                            || watermarkConfig != null
                            || cosmeticCloudEditPreview != null,
                        onCrop = { showCropSheet = true },
                        onBorder = { showBorderSheet = true },
                        onHeal = {
                            if (fullResBitmap != null) {
                                showHealSheet = true
                            } else if (!isPreparingFullRes) {
                                isPreparingFullRes = true
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val bmp = component.prepareFullResBitmap()
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        fullResBitmap = bmp
                                        isPreparingFullRes = false
                                        showHealSheet = true
                                    }
                                }
                            }
                        },
                        onWatermark = { showWatermarkSheet = true },
                        onAiDenoise = {
                            aiDenoiseSession = aiDenoiseSession.copy(draft = aiDenoiseSession.committed)
                            if (fullResBitmap == null && !isPreparingFullRes) {
                                isPreparingFullRes = true
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val bmp = component.prepareFullResBitmap()
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        fullResBitmap = bmp
                                        isPreparingFullRes = false
                                        showAiDenoiseSheet = true
                                    }
                                }
                            } else {
                                showAiDenoiseSheet = true
                            }
                        },
                        // Focus & Fade is now 100% on-device — no cloud upload,
                        // so no consent gate. Open the local sheet directly.
                        onOnlineAiEdit = { showOnlineAiEditSheet = true },
                        onReset = {
                            cosmeticCroppedPreview = null
                            cosmeticHealedPreview = null
                            cosmeticBorderedPreview?.let { runCatching { it.recycle() } }
                            cosmeticBorderedPreview = null
                            cosmeticCloudEditPreview = null
                            cloudEditJobId = null
                            fullResBitmap = null
                            aiAppliedPreview = null
                            denoiseLongSide = 0
                            watermarkConfig = null
                            aiDenoiseSession = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseEditSession()
                            aiDenoisePreviewScheduler.cancel()
                            cropRectL = 0f
                            cropRectT = 0f
                            cropRectR = 1f
                            cropRectB = 1f
                            cropRotationDeg = 0f
                            cropOrient90 = 0
                            cropFlipHState = false
                            cropFlipVState = false
                        },
                    )
                    } // Box
                }

                // Full-res preparation progress — shown while Stage C runs for heal
                if (isPreparingFullRes) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                            Text(
                                text  = "Preparing full resolution for heal…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            // Image Format section hidden — locked to JPEG (selectedFormat).

            // ── Saved Presets (simplified selector) ──────────────────────────
            item {
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                    .RawExportPresetSection(
                        presets       = exportPresets,
                        selectedIndex = selectedPresetIdx,
                        canSave       = component.actions.any {
                            it.id != com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.ORIGINAL_ID &&
                                !it.isAutoExposure
                        },
                        onSelectPreset = { idx ->
                            component.loadPreset(idx)?.let { component.replaceActions(it) }
                            selectedPresetIdx = idx
                        },
                        onSavePreset = { name ->
                            val ok = component.savePreset(name)
                            if (ok) {
                                exportPresets = component.loadPresetIndex()
                                selectedPresetIdx = exportPresets.lastIndex.takeIf { it >= 0 }
                            }
                            ok
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            // ── Dimensions (editable W×H with aspect-ratio lock) ─────────────
            item {
                SectionHeader(stringResource(R.string.raw_export_dimensions))
                // Original file path + raw sensor resolution
                val srcLabel = sourceRealPath ?: sourceDisplayName
                if (srcLabel != null) {
                    Text(
                        text     = srcLabel,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                if (origW > 0 && origH > 0) {
                    Text(
                        text     = stringResource(R.string.raw_export_original_resolution, origW, origH),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                    )
                }
                // Size chips (owner request 2026-09-07): the "Use full resolution"
                // checkbox is gone — "Original" IS the full-resolution choice and
                // is selected by default. Each chip scales the LONG edge to the
                // named target while preserving aspect (a resize, never a crop).
                // Manual W/H fields below appear for anything the chips don't cover.
                if (origW > 0 && origH > 0) {
                    val longSide = maxOf(origW, origH)
                    fun scaledW(target: Int) = (origW.toFloat() * target / longSide).toInt().coerceAtLeast(1)
                    fun applyLongSidePreset(target: Int) {
                        useFullResolution = false
                        val scale = target.toFloat() / longSide
                        dimW = (origW * scale).toInt().coerceAtLeast(1).toString()
                        dimH = (origH * scale).toInt().coerceAtLeast(1).toString()
                    }
                    @Composable
                    fun SizeChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
                        androidx.compose.material3.FilterChip(
                            selected = selected,
                            onClick = onClick,
                            enabled = enabled,
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SizeChip(
                            stringResource(R.string.raw_export_preset_original),
                            useFullResolution,
                        ) {
                            useFullResolution = true
                            dimW = origW.toString()
                            dimH = origH.toString()
                        }
                        // 1350 px = long-edge cap of the common feed platforms
                        // (Instagram 4:5 portrait cap; FB/X recompress larger).
                        listOf(
                            1350 to R.string.raw_export_preset_social,
                            1600 to R.string.raw_export_preset_hd_lite,
                            1920 to R.string.raw_export_preset_hd,
                            3840 to R.string.raw_export_preset_4k,
                        ).forEach { (target, labelRes) ->
                            SizeChip(
                                label = stringResource(labelRes),
                                selected = !useFullResolution &&
                                    dimW.toIntOrNull() == scaledW(target) &&
                                    (target == 1350 || longSide > target),
                                enabled = target == 1350 ||
                                    denoiseLongSide == 0 ||
                                    target <= denoiseLongSide,
                            ) { applyLongSidePreset(target) }
                        }
                    }
                }
                if (!useFullResolution) Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value         = dimW,
                        enabled       = denoiseLongSide == 0,
                        onValueChange = { v ->
                            dimW = v
                            val w = v.toIntOrNull() ?: return@OutlinedTextField
                            if (isAspectLocked && origW > 0 && origH > 0) {
                                dimH = ((w.toFloat() * origH / origW).toInt()).toString()
                            }
                        },
                        label          = { Text(stringResource(R.string.raw_export_width)) },
                        singleLine     = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier       = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick  = { isAspectLocked = !isAspectLocked },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector        = if (isAspectLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            contentDescription = if (isAspectLocked) "Locked" else "Unlocked",
                            tint               = if (isAspectLocked) MaterialTheme.colorScheme.primary
                                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value         = dimH,
                        enabled       = denoiseLongSide == 0,
                        onValueChange = { v ->
                            dimH = v
                            val h = v.toIntOrNull() ?: return@OutlinedTextField
                            if (isAspectLocked && origW > 0 && origH > 0) {
                                dimW = ((h.toFloat() * origW / origH).toInt()).toString()
                            }
                        },
                        label          = { Text(stringResource(R.string.raw_export_height)) },
                        singleLine     = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier       = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // ── File Info (full EXIF) ─────────────────────────────────────────
            item {
                SectionHeader(stringResource(R.string.raw_export_file_info))
                // EXIF policy — same three options as the Batch processing page.
                // RAZStudio Software tag is always written regardless of choice.
                ExifPolicyRadioRow(
                    label    = "Keep all EXIF information",
                    selected = exifPolicy == com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.KeepAll,
                    onClick  = { exifPolicy = com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.KeepAll },
                )
                ExifPolicyRadioRow(
                    label    = "Strip out sensitive information",
                    selected = exifPolicy == com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.StripSensitive,
                    onClick  = { exifPolicy = com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.StripSensitive },
                )
                ExifPolicyRadioRow(
                    label    = "Do not keep any EXIF information",
                    selected = exifPolicy == com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.NoneExceptSoftware,
                    onClick  = { exifPolicy = com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.NoneExceptSoftware },
                )
                Spacer(Modifier.height(8.dp))
                SectionHeader("Color Profile")
                ExifPolicyRadioRow(
                    label    = "Embed ICC color profile + tags",
                    selected = saveIcc,
                    onClick  = { saveIcc = true },
                )
                ExifPolicyRadioRow(
                    label    = "Do not embed",
                    selected = !saveIcc,
                    onClick  = { saveIcc = false },
                )
                metadata.let { meta ->
                    // Camera & Lens
                    val cameraStr = buildString {
                        if (meta.cameraMake.isNotBlank()) append(meta.cameraMake)
                        if (meta.cameraModel.isNotBlank()) { if (isNotEmpty()) append(" "); append(meta.cameraModel) }
                    }.ifBlank { "—" }
                    InfoRow(label = stringResource(R.string.raw_export_camera), value = cameraStr)
                    InfoRow(
                        label = stringResource(R.string.raw_export_lens),
                        value = meta.lensInfo.ifBlank { meta.lensMake.ifBlank { "—" } },
                    )
                    // Shutter speed
                    if (meta.shutterSpeed > 0f) {
                        InfoRow(
                            label = stringResource(R.string.raw_export_shutter_speed),
                            value = if (meta.shutterSpeed >= 1f) "%.0fs".format(meta.shutterSpeed)
                                    else "1/%.0f s".format(1f / meta.shutterSpeed),
                        )
                    }
                    // Aperture
                    InfoRow(
                        label = stringResource(R.string.raw_export_aperture),
                        value = if (meta.aperture > 0f) "f/%.1f".format(meta.aperture) else "Manual",
                    )
                    // ISO
                    if (meta.iso > 0) {
                        InfoRow(label = stringResource(R.string.raw_export_iso), value = "ISO ${meta.iso}")
                    }
                    // Exposure bias
                    if (meta.exposureBias != 0f) {
                        InfoRow(
                            label = stringResource(R.string.raw_export_exposure),
                            value = "%+.1f EV".format(meta.exposureBias),
                        )
                    }
                    // Focal length
                    if (meta.focalLength > 0f) {
                        val fl = "%.0f mm".format(meta.focalLength)
                        val fl35 = if (meta.focalLength35mm > 0f) " (%.0f mm equiv)".format(meta.focalLength35mm) else ""
                        InfoRow(label = stringResource(R.string.raw_export_focal_length), value = fl + fl35)
                    }
                    // Flash
                    InfoRow(
                        label = "Flash",
                        value = if (meta.flashFired) "Fired" else "Did not fire",
                    )
                    // Orientation
                    InfoRow(
                        label = stringResource(R.string.raw_export_orientation),
                        value = exifOrientationLabel(meta.orientation),
                    )
                    // Date
                    if (meta.dateTimeOriginal.isNotBlank()) {
                        InfoRow(label = stringResource(R.string.raw_export_date), value = meta.dateTimeOriginal)
                    }
                    // GPS
                    if (meta.gpsLatitude != null && meta.gpsLongitude != null) {
                        InfoRow(
                            label = stringResource(R.string.raw_export_gps),
                            value = "%.5f, %.5f".format(meta.gpsLatitude, meta.gpsLongitude),
                        )
                    }
                    // Software / Artist / Copyright
                    if (meta.softwareVersion.isNotBlank()) {
                        InfoRow(label = "Software", value = meta.softwareVersion)
                    }
                    if (meta.artist.isNotBlank()) {
                        InfoRow(label = "Artist", value = meta.artist)
                    }
                    if (meta.copyright.isNotBlank()) {
                        InfoRow(label = "Copyright", value = meta.copyright)
                    }
                    if (meta.imageDescription.isNotBlank()) {
                        InfoRow(label = "Description", value = meta.imageDescription)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        } // end else (metadata != null)
    }

    // ── Full-screen page overlays (drawn after Scaffold so they appear on top) ─
    AnimatedVisibility(
        visible = showCropSheet && previewBitmap != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize()) {
            com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
                .components.RawCropSheet(
                source = cosmeticCroppedPreview ?: previewBitmap!!,
                onDismiss = { showCropSheet = false },
                onCropConfirmed = { cropped, l, t, r, b, deg, rot90, fH, fV ->
                    val prevL = cropRectL; val prevT = cropRectT
                    val prevW = (cropRectR - cropRectL).coerceAtLeast(0.001f)
                    val prevH = (cropRectB - cropRectT).coerceAtLeast(0.001f)
                    cropRectL = (prevL + l * prevW).coerceIn(0f, 1f)
                    cropRectT = (prevT + t * prevH).coerceIn(0f, 1f)
                    cropRectR = (prevL + r * prevW).coerceIn(0f, 1f)
                    cropRectB = (prevT + b * prevH).coerceIn(0f, 1f)
                    cropRotationDeg = (cropRotationDeg + deg).coerceIn(-45f, 45f)
                    // Accumulate discrete orientation across re-crop sessions.
                    cropOrient90 = (cropOrient90 + rot90) % 4
                    cropFlipHState = cropFlipHState != fH
                    cropFlipVState = cropFlipVState != fV
                    cosmeticCroppedPreview = cropped
                    val staleHeal = cosmeticHealedPreview
                    cosmeticHealedPreview = null
                    if (staleHeal != null && staleHeal !== cropped) runCatching { staleHeal.recycle() }
                    // Border wraps the old photo — it's stale after a re-crop.
                    val staleBorder = cosmeticBorderedPreview
                    cosmeticBorderedPreview = null
                    if (staleBorder != null && staleBorder !== cropped) runCatching { staleBorder.recycle() }
                    showCropSheet = false
                },
            )
        }
    }
    AnimatedVisibility(
        visible = showHealSheet && previewBitmap != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize()) {
            com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
                .components.RawHealSheet(
                source = fullResBitmap ?: previewBitmap!!,
                protectBitmap = healProtectMask,
                segmentMasks = healSegmentMasks,
                onDismiss = { showHealSheet = false },
                onHealConfirmed = { healed ->
                    val old = cosmeticHealedPreview
                    if (old != null && old !== healed && old !== previewBitmap) runCatching { old.recycle() }
                    cosmeticHealedPreview = healed
                    val staleBorder = cosmeticBorderedPreview
                    cosmeticBorderedPreview = null
                    if (staleBorder != null && staleBorder !== healed) runCatching { staleBorder.recycle() }
                    showHealSheet = false
                },
            )
        }
    }
    AnimatedVisibility(
        visible = showBorderSheet && previewBitmap != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize()) {
            com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
                .components.RawBorderSheet(
                // Border wraps whatever is currently shown (crop/heal/cloud
                // already baked into previewBitmap). Never the source of the
                // crop/heal sheets, so those keep operating on the un-framed photo.
                source = previewBitmap!!,
                initialThickness = borderThickness,
                initialColorArgb = borderColorArgb,
                onDismiss = { showBorderSheet = false },
                onBorderConfirmed = { bordered, thickness, colorArgb ->
                    val old = cosmeticBorderedPreview
                    if (old != null && old !== bordered && old !== previewBitmap) runCatching { old.recycle() }
                    cosmeticBorderedPreview = bordered
                    borderThickness = thickness
                    borderColorArgb = colorArgb
                    showBorderSheet = false
                },
            )
        }
    }
    AnimatedVisibility(
        visible = showWatermarkSheet && previewBitmap != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize()) {
            com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
                .components.RawWatermarkSheet(
                source = previewBitmap!!,
                initialConfig = watermarkConfig,
                metadata = metadata,
                onDismiss = { showWatermarkSheet = false },
                onWatermarkConfigured = { cfg ->
                    watermarkConfig = cfg
                    com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.saveCombinedWatermarkConfig(appContext, cfg)
                    showWatermarkSheet = false
                },
            )
        }
    }
    if (showAiDenoiseSheet) {
        val denoiseSource = fullResBitmap ?: previewBitmap
        if (denoiseSource != null) {
            val scaleMode = appSettings.defaultImageScaleMode
            com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawAiDenoiseSheet(
                source = denoiseSource,
                scaleModeLabel = "${scaleMode} · ${scaleMode.scaleColorSpace}",
                onScale = { bitmap, width, height -> component.scaleBitmap(bitmap, width, height) },
                onDone = { processed ->
                    aiAppliedPreview = processed
                    fullResBitmap = processed
                    denoiseLongSide = maxOf(processed.width, processed.height)
                    useFullResolution = false
                    dimW = processed.width.toString()
                    dimH = processed.height.toString()
                    aiDenoiseSession = aiDenoiseSession.copy(
                        committed = aiDenoiseSession.committed.copy(enabled = true),
                    )
                },
                onClose = { showAiDenoiseSheet = false },
            )
        }
    }
    // Atmosphere sheet REMOVED from the Export page 2026-09-06 (owner request),
    // together with its ✨ launcher in the transform bar (see
    // SHOW_ONLINE_AI_EDIT_BUTTON in RawTransformBar.kt); RawAtmosphereSheet and
    // AtmosphereProcessor were deleted 2026-09-07 — recover them from git if the
    // feature is ever wanted back. showOnlineAiEditSheet / cosmeticCloudEditPreview
    // stay wired but are now permanently false / null; the preview chain treats
    // that as "no edit".
    if (showOnlineAiConsentDialog) {
        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
            .components.RawOnlineAiConsentDialog(
            onContinue = {
                onlineAiConsentPrefs.hasSeenOnlineAiConsent = true
                showOnlineAiConsentDialog = false
                showOnlineAiEditSheet = true
            },
            onCancel = { showOnlineAiConsentDialog = false },
        )
    }
    if (showDetailsWarning) {
        AlertDialog(
            onDismissRequest = { showDetailsWarning = false },
            title = { Text(stringResource(R.string.raw_details_enter_title)) },
            text = { Text(stringResource(R.string.raw_details_enter_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDetailsWarning = false
                    onOpenDetailsEditor()
                }) {
                    Text(stringResource(R.string.raw_details_enter_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetailsWarning = false }) {
                    Text(stringResource(R.string.raw_details_enter_cancel))
                }
            },
        )
    }
}

// Small pill chip used for the preview's "View as" colorspace toggle (DCI-P3 vs sRGB).
// Visually similar to FormatChip but lighter — single line, no border emphasis.
@Composable
private fun ViewAsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
    val labelColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                     else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color      = labelColor,
        )
    }
}

// Shared building blocks (RawExportBottomBar, FormatChip, SectionHeader,
// CollapsibleSectionHeader, ExportToggleRow, InfoRow, DimensionInputRow, and the
// formatFileSize / exifOrientationLabel / shareFile helpers) live in
// RawExportComposables.kt — same package, so they're referenced unqualified.
