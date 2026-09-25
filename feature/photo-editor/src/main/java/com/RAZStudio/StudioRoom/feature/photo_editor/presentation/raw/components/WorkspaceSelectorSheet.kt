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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.t8rin.exif.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.RAZStudio.StudioRoom.core.utils.AppLog
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.DemosaicAlgorithm
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.HighlightRecoveryMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.OutputBitDepthMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmGrainLevel
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmProfile
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspacePresetsStore
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

/**
 * Bottom-sheet dialog that lets the user choose the working-buffer configuration
 * (bit depth, color gamut, demosaic algorithm) before the RAW pipeline starts.
 *
 * Layout: a scrollable Column of three sections + a sticky button row at the bottom
 * so Cancel / Proceed remain reachable even on small screens with long content.
 *
 * @param initial   Pre-filled selection — usually the persisted choice from
 *                  `SharedPreferences("raw_workspace_prefs")`.
 * @param onProceed Called with the user's final selection. Caller persists + opens.
 * @param onCancel  Called when the user dismisses the sheet without confirming.
 */
/**
 * Live lens-correction params reported to the caller so the background
 * preview canvas can mirror the geometry Stage A will bake.
 */
data class LensPreviewParams(
    val camMaker: String,
    val camModel: String,
    val lensMaker: String,
    val lensModel: String,
    val focalMm: Float,
    val aperture: Float,
    val dbDir: String,
)

/**
 * Start Page / workspace-selector background preview.
 *
 * Root cause of the old low-res look: [ExifInterface.getThumbnailBitmap] returns
 * the tiny IFD0/EXIF thumbnail (often 160–640 px). Cameras also embed a much
 * larger preview JPEG (~1620×1080 typical) that LibRaw's `unpack_thumb` reads
 * in ~10–30 ms — same path Stage A uses for its instant placeholder.
 *
 * Pipeline: LibRaw embedded preview (via fd, no full-file copy) → EXIF thumb
 * fallback → subsampled BitmapFactory decode for non-RAW. Longest side is
 * capped at [maxSide] (default 1600). Live lens correction
 * ([RawV3Engine.applyLensfunToBitmap]) runs on this bitmap when the sheet
 * reports a complete match.
 */
@Composable
fun rememberEmbeddedThumbnail(sourceUri: Uri?, maxSide: Int = 1600): Bitmap? {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, sourceUri, maxSide) {
        val uri = sourceUri
        if (uri == null) { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            loadWorkspacePreviewBitmap(context, uri, maxSide)
        }
    }
    return thumb
}

/** Longest-side cap used by the Start Page preview (and lens live-preview). */
const val WORKSPACE_PREVIEW_MAX_SIDE = 1600

/**
 * Decode a ≤[maxSide] preview for [uri]. Prefer LibRaw embedded JPEG (large),
 * then EXIF IFD thumb, then a subsampled full decode for ordinary images.
 */
internal fun loadWorkspacePreviewBitmap(
    context: Context,
    uri: Uri,
    maxSide: Int = WORKSPACE_PREVIEW_MAX_SIDE,
): Bitmap? = runCatching {
    // 1) LibRaw unpack_thumb — camera preview JPEG, typically ~1–2k on the long side.
    val librawBmp = decodeLibRawEmbeddedPreview(context, uri)
    if (librawBmp != null) {
        val scaled = scaleBitmapToMaxSide(librawBmp, maxSide)
        AppLog.i(
            "WorkspacePreview",
            "LibRaw embedded preview ${librawBmp.width}×${librawBmp.height} → ${scaled.width}×${scaled.height} (max=$maxSide)",
        )
        return@runCatching scaled
    }

    // 2) EXIF IFD thumbnail (small, but better than blank).
    val exifThumb = context.contentResolver.openInputStream(uri)?.use { ins ->
        val exif = ExifInterface(ins)
        val raw = exif.getThumbnailBitmap() ?: return@use null
        val orient = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )
        rotateBitmapForExif(raw, orient)
    }
    if (exifThumb != null) {
        val scaled = scaleBitmapToMaxSide(exifThumb, maxSide)
        AppLog.i(
            "WorkspacePreview",
            "EXIF IFD thumb ${exifThumb.width}×${exifThumb.height} → ${scaled.width}×${scaled.height}",
        )
        return@runCatching scaled
    }

    // 3) Non-RAW / no embedded preview — subsampled decode of the file itself.
    val decoded = decodeUriSubsampled(context, uri, maxSide)
    if (decoded != null) {
        AppLog.i("WorkspacePreview", "subsampled decode ${decoded.width}×${decoded.height}")
    } else {
        AppLog.w("WorkspacePreview", "no preview for $uri")
    }
    decoded
}.getOrNull()

/**
 * Open [uri] via ParcelFileDescriptor and ask LibRaw for the embedded preview
 * JPEG. Tries `/proc/self/fd/N` first (no full-file copy); if that fails
 * (some OEMs block LibRaw on proc fds), copies to a cache file once.
 */
private fun decodeLibRawEmbeddedPreview(context: Context, uri: Uri): Bitmap? {
    // file:// — open directly.
    if (uri.scheme == "file") {
        val path = uri.path ?: return null
        val jpegBytes = RawV3Engine.extractEmbeddedThumbnail(path) ?: return null
        return decodeJpegBytesOriented(jpegBytes)
    }

    // content:// — try fd path without copying the whole RAW.
    runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val jpegBytes = RawV3Engine.extractEmbeddedThumbnail(fdPath)
            if (jpegBytes != null) return decodeJpegBytesOriented(jpegBytes)
        }
    }

    // Fallback: copy once into cache so LibRaw can open_file() a real path.
    // ~1–2 s for a 25 MB ARW — still far cheaper than Stage A, and yields the
    // large camera preview (~1600px) instead of the tiny EXIF IFD thumb.
    return runCatching {
        val cache = java.io.File(context.cacheDir, "ws_preview_${uri.hashCode().toUInt()}.raw")
        if (!cache.exists() || cache.length() == 0L) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
        }
        val jpegBytes = RawV3Engine.extractEmbeddedThumbnail(cache.absolutePath)
            ?: return@runCatching null
        decodeJpegBytesOriented(jpegBytes)
    }.getOrNull()
}

private fun decodeJpegBytesOriented(jpegBytes: ByteArray): Bitmap? {
    val raw = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        ?: return null
    // Orientation is usually already baked into camera preview JPEGs; still
    // honour an EXIF Orientation tag when present.
    val orientation = runCatching {
        val tmp = java.io.File.createTempFile("ws_prev_", ".jpg")
        try {
            tmp.writeBytes(jpegBytes)
            ExifInterface(tmp.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        } finally {
            tmp.delete()
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return rotateBitmapForExif(raw, orientation)
}

private fun decodeUriSubsampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
    } ?: return null
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    val longSide = maxOf(w, h)
    while (longSide / sample > maxSide) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    val orient = context.contentResolver.openInputStream(uri)?.use { ins ->
        ExifInterface(ins).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    return scaleBitmapToMaxSide(rotateBitmapForExif(decoded, orient), maxSide)
}

private fun scaleBitmapToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
    val longSide = maxOf(src.width, src.height)
    if (longSide <= maxSide || maxSide <= 0) return src
    val scale = maxSide.toFloat() / longSide.toFloat()
    val nw = (src.width * scale).toInt().coerceAtLeast(1)
    val nh = (src.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, nw, nh, true).also {
        if (it !== src) src.recycle()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceSelectorSheet(
    initial: WorkspaceConfig,
    onProceed: (WorkspaceConfig) -> Unit,
    onCancel: () -> Unit,
    sourceUri: Uri? = null,
    /**
     * Gallery Workspace first-open: lens-correction controls + Cancel/Proceed
     * only. Highlight Recovery is forced to Reconstruct3 and hidden.
     */
    reducedMode: Boolean = false,
    /**
     * When [reducedMode] is true and this is non-null, Proceed goes through
     * this callback (with whether "apply to matching" was checked) instead of
     * [onProceed].
     */
    onProceedReduced: ((WorkspaceConfig, applyToMatching: Boolean) -> Unit)? = null,
    onLensPreviewParamsChanged: (LensPreviewParams?) -> Unit = {},
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val TAG = "WorkspaceSelectorSheet"

    // These three are locked — never read from prefs or user selection.
    var bitDepth by remember { mutableStateOf(WorkspaceBitDepth.BIT_16) }
    var colorGamut by remember { mutableStateOf(RawColorSpace.SRGB) }
    var demosaic by remember { mutableStateOf(DemosaicAlgorithm.RAZ_AMAZE_VNG) }
    // v2-integration §1 additions — five new rows below the demosaic section.
    // Requirement 15.8 — every project photo (reducedMode) forces the
    // conservative reconstruction level. Full sheet exposes Highlight Recovery
    // chips so Clip/Off/etc. change LibRaw params.highlight at Stage A.
    var highlightRecovery by remember(reducedMode) {
        mutableStateOf(
            if (reducedMode) HighlightRecoveryMode.Reconstruct3 else initial.highlightRecovery
        )
    }
    var applyToMatchingPhotos by remember { mutableStateOf(true) }
    var nrEnabled by remember { mutableStateOf(initial.nrEnabled) }
    var nrLuma by remember { mutableStateOf(initial.nrLuma) }
    var nrChroma by remember { mutableStateOf(initial.nrChroma) }
    var dcpProfileId by remember { mutableStateOf(initial.dcpProfileId) }
    var outputBitDepthMode by remember { mutableStateOf(initial.outputBitDepthMode) }
    var sidecarEnabled by remember { mutableStateOf(initial.sidecarEnabled) }
    var caCorrectionEnabled by remember { mutableStateOf(initial.caCorrectionEnabled) }
    // LibRaw output working space — wider than sRGB is usually a free
    // quality win for the FP16 pipeline. Persisted in WorkspaceConfig and
    // mapped to imgdata.params.output_color at Stage A.
    var libRawOutputColor by remember {
        mutableStateOf(initial.libRawOutputColor)
    }
    // Per-photo .cube LUT working space hint.
    var lutInputSpace by remember { mutableStateOf(initial.lutInputSpace) }
    // Sensor calibration — applies LibRaw cblack[] per-channel.
    var sensorCalibrationEnabled by remember {
        mutableStateOf(initial.sensorCalibrationEnabled)
    }
    // 3-mode colour fringing selector (replaces the older 2-state CA toggle).
    var colorFringingMode by remember {
        mutableStateOf(initial.colorFringingMode)
    }
    // Raw sensor calibration overrides — black / white-level deltas (in DN)
    // and the clip-detection threshold. Defaults match LibRaw's per-camera
    // tables; surface them so a sensor with slightly off calibration can be
    // tuned per-workspace (persisted into WorkspaceConfig).
    var blackLevelDelta by remember { mutableStateOf(initial.blackLevelDelta) }
    var whiteLevelDelta by remember { mutableStateOf(initial.whiteLevelDelta) }
    var clipThreshold         by remember { mutableStateOf(initial.clipThreshold) }
    var highlightProtection   by remember { mutableStateOf(initial.highlightProtection) }
    // AMaZE+VNG dual-decode controls — only active when demosaic == RAZ_AMAZE_VNG.
    var dualContrastThreshold by remember { mutableStateOf(initial.dualContrastThreshold) }
    var dualAutoContrast      by remember { mutableStateOf(initial.dualAutoContrast) }
    // Color route locked to Camera Color Profile (route A). Profile picker UI is
    // hidden; only AI Level Reconstruct remains as a checkbox.
    val useCameraColorProfile = true
    var aeSubjectProtection   by remember { mutableStateOf(initial.aeSubjectProtection) }
    // Film Profile Simulation hidden — always Standard Linear.
    var filmProfile    by remember { mutableStateOf(FilmProfile.DEFAULT) }
    AppLog.d(TAG, "sheet opened — initial filmProfile=${initial.filmProfile.id}")
    RawV3Engine.resetAdjustmentDebugLog()
    // Stage A LibRaw knobs lifted from the v3 smoke screen so the
    // production selector exposes the same surface area.
    var wbSourceOrdinal by remember { mutableStateOf(initial.wbSourceOrdinal) }
    var exposureShiftEv by remember { mutableStateOf(initial.exposureShiftEv) }
    // Color Noise Cleanup is hidden from the UI and forced to Full (2) for
    // every file — strongest pre-demosaic chroma denoise always on.
    var fbddNoise by remember { mutableStateOf(2) }
    // Subject Detection + Camera-style finish are MANDATORY in the
    // workspace pipeline now — the UI toggles are removed but the state
    // is pinned to `true` so the rest of the sheet logic (and the
    // committed WorkspaceConfig) keeps the legacy field shape. Changing
    // the data class default isn't enough; the user's persisted prefs
    // may still carry false, so we override here every time the sheet
    // opens.
    val subjectDetectionEnabled = true
    val cameraStyleFinishEnabled = true
    // Tracks whether we've applied the auto-default for CA based on whether the
    // source is already-demosaiced (upstream tool already did CA correction).
    // Applied once when `loadedInfo` first becomes non-null — never re-applied,
    // so toggling CA after that point reflects the user's choice and not the
    // probe result.
    var caAutoDefaultApplied by remember(sourceUri) { mutableStateOf(false) }
    // Route-B AI Enhance / Smart Defaults forced off (route B UI hidden).
    val aiEnhance = false
    val smartDefaultsEnabled = false
    // AI Level Reconstruct (pre-demosaic HDR+shadow U-Nets). Checkbox only; OFF by default.
    var aiReconstructA by remember { mutableStateOf(false) }

    // ── Probed loaded-image info (filename, format, model, dims, rotation) ────
    // Runs on IO when sourceUri changes. Uses t8rin ExifInterface (a port of
    // AndroidX ExifInterface) on a fresh InputStream — it reads just the EXIF
    // IFD, so the full RAW is not copied to cache. Typical cost <50ms on the
    // supported-floor device.
    var loadedInfo by remember(sourceUri) { mutableStateOf<LoadedImageInfo?>(null) }
    LaunchedEffect(sourceUri) {
        if (sourceUri == null) {
            loadedInfo = null
            return@LaunchedEffect
        }
        loadedInfo = withContext(Dispatchers.IO) {
            probeLoadedImageInfo(context, sourceUri)
        }
    }

    // ── Lensfun lens correction (both routes, default ON) ────────────────────
    // Resolves the EXIF camera + lens against the bundled Lensfun database
    // with the same strict matcher Stage A uses. The matched names are passed
    // as overrides so what's displayed here is exactly what gets corrected.
    // If either side fails to resolve, the import runs WITHOUT correction.
    var lensCorrectionOn by remember(sourceUri) { mutableStateOf(true) }
    var lensfunDbDir by remember(sourceUri) { mutableStateOf("") }
    // null = still probing; Triple("",0f,"") = probed, no match.
    var lensfunMatch by remember(sourceUri) {
        mutableStateOf<Triple<String, Float, String>?>(null)
    }
    // Manual overrides (mix-and-match): every field can replace the auto
    // match. All picks are exact Lensfun DB strings, so Stage A resolves them
    // via the existing exact-match override path.
    var lfManualCamModel by remember(sourceUri) { mutableStateOf("") }
    var lfManualCamCrop by remember(sourceUri) { mutableStateOf(0f) }
    var lfManualBrand by remember(sourceUri) { mutableStateOf("") }
    var lfManualLensModel by remember(sourceUri) { mutableStateOf("") }
    var lfFocalText by remember(sourceUri) { mutableStateOf("") }
    // Zero-DCE adaptive devignetting threshold τ (see WorkspaceConfig.liftTau).
    var lfLiftTau by remember(sourceUri) { mutableStateOf(0.7f) }
    var lfConfidence by remember(sourceUri) {
        mutableStateOf(com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3Engine.LensMatchConfidence.None)
    }
    var lfCandidates by remember(sourceUri) {
        mutableStateOf<List<com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3Engine.LensfunCandidate>>(emptyList())
    }
    // Adapted-lens mode UI removed — always non-adapted (mount matching on).
    val lfAdaptedMode = false
    LaunchedEffect(loadedInfo, lfAdaptedMode) {
        val info = loadedInfo ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            val dir = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                .LensfunDatabase.ensureMaterialized(context)
                ?: return@withContext null
            val engine = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
            val detailed = engine.lensfunMatchDetailed(
                dbDir = dir,
                camMaker = info.cameraMake,
                camModel = info.cameraModel,
                lensMaker = "",
                lensModel = info.lensModel,
            )
            val alts = if (detailed != null && !detailed.autoApplicable) {
                engine.lensfunRankLenses(
                    dbDir = dir,
                    camMaker = info.cameraMake,
                    camModel = info.cameraModel,
                    lensMaker = "",
                    lensModel = info.lensModel,
                    adaptedMode = lfAdaptedMode,
                    maxOut = 3,
                )
            } else emptyList()
            Triple(dir, detailed, alts)
        } ?: return@LaunchedEffect
        lensfunDbDir = result.first
        val d = result.second
        lensfunMatch = if (d == null) Triple("", 0f, "")
                       else Triple(d.cameraModel, d.cropFactor, d.lensModel)
        lfConfidence = d?.confidence
            ?: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                .RawV3Engine.LensMatchConfidence.None
        lfCandidates = result.third
    }

    // Live lens-correction preview feed for the caller.
    LaunchedEffect(
        lensCorrectionOn, lensfunMatch, lfManualCamModel, lfManualLensModel,
        lfFocalText, lensfunDbDir, loadedInfo, lfConfidence,
    ) {
        val match = lensfunMatch
        val cam = lfManualCamModel.ifBlank { match?.first.orEmpty() }
        val crop = if (lfManualCamModel.isNotBlank()) lfManualCamCrop
                   else match?.second ?: 0f
        val lens = lfManualLensModel.ifBlank { match?.third.orEmpty() }
        val exifFocal = loadedInfo?.focalMm ?: 0f
        val focalOv = lfFocalText.toFloatOrNull() ?: 0f
        val rangeFb = parseLensFocalRange(lens)?.let { (minF, maxF) ->
            minF + 0.25f * (maxF - minF)
        } ?: 0f
        val focal = when {
            focalOv > 0f -> focalOv
            exifFocal > 0f -> exifFocal
            else -> rangeFb
        }
        val aperture = (loadedInfo?.apertureFNumber ?: 0.0).toFloat()
        val lensExact = lfManualLensModel.isNotBlank() ||
            lfConfidence == RawV3Engine.LensMatchConfidence.High
        val ready = lensCorrectionOn && lensfunDbDir.isNotBlank() &&
            cam.isNotBlank() && crop > 0f && lens.isNotBlank() &&
            lensExact && focal > 0f
        onLensPreviewParamsChanged(
            if (ready) LensPreviewParams(
                camMaker  = loadedInfo?.cameraMake.orEmpty(),
                camModel  = cam,
                lensMaker = "",
                lensModel = lens,
                focalMm   = focal,
                aperture  = aperture,
                dbDir     = lensfunDbDir,
            ) else null
        )
    }

    val supports16Bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    val has3GbRam = remember(context) { deviceHasAtLeast3GbRam(context) }

    // Detect source-file precision. JPEG/WebP/BMP are 8-bit per channel only —
    // selecting a 16-bit workspace gains nothing. PNG, TIFF, and RAW formats
    // can carry >8-bit precision; allow 16-bit there.
    val sourceFormat = remember(sourceUri) {
        sourceUri?.let { detectSourceFormat(context, it) } ?: SourceFormat.Unknown
    }
    val source8BitOnly = sourceFormat in setOf(
        SourceFormat.Jpeg, SourceFormat.Webp, SourceFormat.Bmp,
    )

    // Compact sheet: title lives on the Cancel/Proceed row (no tall top header)
    // so more of the background preview canvas stays visible.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val scrollMaxDp = (screenHeightDp * 0.32f - 72f).coerceAtLeast(160f).dp

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            var showVngDebug by remember { mutableStateOf(false) }
            if (showVngDebug) {
                VngDebugSheet(onDismiss = { showVngDebug = false })
            }

            // ── Scrollable content ────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = scrollMaxDp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                // Loaded image info — surfaced before the user picks workspace
                // so they can sanity-check that EXIF dims/orientation match
                // what RAW Export will report.
                // NOTE: the loaded-photo preview is deliberately NOT drawn here.
                // It renders on the main-screen canvas BEHIND this sheet.
                LoadedImageInfoCard(info = loadedInfo)
                Spacer(Modifier.height(12.dp))

                // Bit depth section is hidden — v3 always runs FP16
                // working space (see the bitDepth coerce above). The
                // section + its enum entries remain in the codebase
                // for legacy preset compatibility but the user can't
                // change them.

                // Color gamut — full-width stack of options.
                // PROPHOTO_RGB is intentionally hidden from the UI: its primaries
                // extend far past any phone display gamut, and its D50 white
                // point adapted onto a D65 phone screen produces a visible
                // blueish cast that's correct color-management behavior but
                // misleading as a preview. The enum entry stays so persisted
                // prefs from earlier builds still deserialize, but if a user
                // had ProPhoto selected we coerce it to DISPLAY_P3 below.
                // Color gamut UI removed — locked to sRGB. Wide gamuts produced
                // desaturation drift between the live preview, idle full-res
                // canvas, and saved file due to Android rejecting custom
                // linear-transfer ColorSpace tags on RGBA_F16 bitmaps. Restore
                // when the FP16 ColorSpace plumbing is verified across OEMs.
                // Demosaic section is RAW-only — non-RAW sources (JPEG/PNG/WebP/
                // BMP/TIFF) skip LibRaw via BitmapDirectPipeline, so the algorithm
                // choice has no effect. Hide the entire section in that case.
                val isRawSource = sourceFormat == SourceFormat.Raw ||
                    sourceFormat == SourceFormat.Unknown
                if (isRawSource) {
                    Spacer(Modifier.height(20.dp))

                    val sourceAlreadyDemosaiced = loadedInfo?.alreadyDemosaiced == true
                    val sourceAdobeEnhanced = loadedInfo?.adobeEnhanced == true

                    // Workspace presets section hidden.

                    // ── Highlight Recovery (LibRaw params.highlight) ──────────
                    // Plain-language chips; librawValue wiring unchanged.
                    if (!reducedMode) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Highlight Recovery",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                            ) {
                                listOf(
                                    HighlightRecoveryMode.Off,
                                    HighlightRecoveryMode.Clip,
                                    HighlightRecoveryMode.Blend,
                                    HighlightRecoveryMode.Reconstruct3,
                                    HighlightRecoveryMode.Reconstruct5,
                                    HighlightRecoveryMode.Reconstruct7,
                                    HighlightRecoveryMode.Reconstruct9,
                                ).forEach { mode ->
                                    val selected =
                                        highlightRecovery.librawValue == mode.librawValue
                                    androidx.compose.material3.FilterChip(
                                        selected = selected,
                                        onClick = { highlightRecovery = mode },
                                        label = {
                                            Text(
                                                mode.displayName,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Noise Reduction hidden — controlled via the dedicated NR tab.

                    // Camera Color is hidden from the UI — always Automatic
                    // (dcpProfileId stays at its DCP_AUTO default).

                    // Output bit depth section hidden — locked to pipeline default.

                    // Edit History (sidecar XMP) is hidden from the UI —
                    // sidecarEnabled keeps its initial default.

                    // ── Chromatic Aberration toggle ──────────────────────────
                    // Toggle is always interactive. CA correction is offered at
                    // every ISO and on every source type — the user may want to
                    // re-correct an already-demosaiced source (e.g. when upstream
                    // CA was weak) or skip a Bayer source (e.g. when running CA
                    // through a different tool downstream).
                    //
                    // Default-on rule (applied once when probe completes):
                    //   • Source IS already-demosaiced (upstream already ran CA):
                    //       default OFF — don't double-correct, but keep the
                    //       toggle interactive so the user can opt back in.
                    //   • Source is NOT already-demosaiced (raw Bayer / unprocessed):
                    //       default ON — upstream tool did not handle CA, so we
                    //       should.
                    // Color Fringing hidden — fixed to Strong (lateral CA + purple-fringe desat).
                    // caCorrectionEnabled kept coherent for downstream consumers.
                    colorFringingMode = com.RAZStudio.StudioRoom.feature.photo_editor
                        .raw.model.ColorFringingMode.Strong
                    caCorrectionEnabled = true

                    // ── Pipeline Working Space + LUT Input Space ───────────
                    //
                    // BOTH selectors hidden. The pipeline is locked to sRGB
                    // end-to-end, matching ImageToolbox's design (no

                    // AMaZE+LMMSE Blend section hidden — internal pipeline detail.
                    // colorspace conversions, ARGB_8888 with implicit sRGB
                    // tag throughout).
                    //
                    // Reasoning:
                    //   • LibRaw output_color is hardcoded to 1 (sRGB) in
                    //     raw_decoder.cpp — these UI options were
                    //     silently ignored.
                    //   • GLSL shader workspaceSpace + lutAuthoredSpace
                    //     drove matrix transforms that produced visible
                    //     channel-corruption when the user picked anything
                    //     other than sRGB / Rec.709.
                    //   • Editor preview, Export-page preview, and saved
                    //     file all derive from the same sRGB-tagged
                    //     Bitmaps now — zero gamut-mismatch surface.
                    //
                    // The fields are still persisted in WorkspaceConfig
                    // (sRGB / Rec.709 defaults) so older sidecars deserialise
                    // cleanly. Re-expose only when a real wide-gamut pipeline
                    // is wired end-to-end (output_color JNI parameter,
                    // shader matrix paths verified, ColorSpace.Named tags
                    // threaded through encoders).
                    libRawOutputColor = com.RAZStudio.StudioRoom.feature
                        .photo_editor.raw.model.LibRawOutputColor.SRgb
                    lutInputSpace = com.RAZStudio.StudioRoom.feature
                        .photo_editor.raw.model.LutInputSpace.Rec709

                    // Sensor Calibration hidden — always on (per-channel cblack[] subtraction).
                    sensorCalibrationEnabled = true

                    // Manual offsets (black/white-level deltas + clip threshold)
                    // removed from the UI. The values default to LibRaw's
                    // per-camera tables which are correct for ~99% of bodies;
                    // surfacing these sliders cluttered the sheet for a
                    // diagnostic-only feature. State variables are still
                    // declared above and threaded into WorkspaceConfig so any
                    // saved sidecars/presets with non-default values still load.

                    // White Balance, Exposure, and Color Noise Cleanup are
                    // hidden from the UI. White balance keeps its initial
                    // source (As Shot); exposure shift stays 0; Color Noise
                    // Cleanup is forced to Full (fbddNoise = 2, set at init).

                    // Subject Detection + Camera-style finish are now
                    // mandatory; the visible toggles were removed. The
                    // pinned `true` values above flow into the committed
                    // WorkspaceConfig so downstream pipeline behaviour is
                    // unchanged.

                    if (!reducedMode) {
                        // ── Highlight Protection ─────────────────────────────────
                        // Controls LibRaw's adjust_maximum_thr: tells the decoder
                        // to use the actual in-image maximum as the white-point
                        // reference when WB scaling might otherwise push near-white
                        // channels past the sensor ceiling. Prevents highlights
                        // from blowing out before reconstruction can act.
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Highlight Protection",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                listOf(
                                    "Off"      to 0.00f,
                                    "Low"      to 0.75f,
                                    "Standard" to 0.85f,
                                    "Strong"   to 0.90f,
                                ).forEach { (label, thr) ->
                                    val selected = kotlin.math.abs(highlightProtection - thr) < 0.03f
                                    androidx.compose.material3.FilterChip(
                                        selected = selected,
                                        onClick  = { highlightProtection = thr },
                                        label    = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                    )
                                }
                            }
                        }
                    }
                } // end isRawSource

                // Color profile selection hidden — always Camera Color Profile.
                // Only AI Level Reconstruct checkbox remains (camera RAW).
                val isCameraRawSource = sourceFormat == SourceFormat.Raw ||
                    sourceFormat == SourceFormat.Unknown
                if (!reducedMode && isCameraRawSource) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SectionLabel("Color")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { aiReconstructA = !aiReconstructA }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = aiReconstructA,
                            onCheckedChange = { aiReconstructA = it },
                        )
                        Text(
                            text = "AI Level Reconstruct",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                // ── Lens Correction — applies to BOTH routes ───────────────────
                // Section stays ON so autodetection can run. Correction is
                // applied only when detection is complete enough (sensor format
                // + exact lens brand/type). Adapted-lens UI removed.
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                SectionLabel("Lens Correction")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { lensCorrectionOn = !lensCorrectionOn }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = lensCorrectionOn,
                        onCheckedChange = { lensCorrectionOn = it },
                    )
                    Text(
                        text = "Lens profile correction",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (lensCorrectionOn) {
                    val match = lensfunMatch
                    val lfCameras by androidx.compose.runtime.produceState(
                        initialValue = emptyList<com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine.LensfunCamera>(),
                        lensfunDbDir,
                    ) {
                        value = if (lensfunDbDir.isBlank()) emptyList()
                        else withContext(Dispatchers.IO) {
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .RawV3Engine.lensfunCameras(lensfunDbDir)
                        }
                    }
                    val lfLenses by androidx.compose.runtime.produceState(
                        initialValue = emptyList<com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine.LensfunLens>(),
                        lensfunDbDir,
                    ) {
                        value = if (lensfunDbDir.isBlank()) emptyList()
                        else withContext(Dispatchers.IO) {
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .RawV3Engine.lensfunLenses(lensfunDbDir)
                        }
                    }
                    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

                    val effCamModel = lfManualCamModel.ifBlank { match?.first.orEmpty() }
                    val effCamCrop = if (lfManualCamModel.isNotBlank()) lfManualCamCrop
                                     else match?.second ?: 0f
                    val effLensModel = lfManualLensModel.ifBlank { match?.third.orEmpty() }
                    val exifFocalMm = loadedInfo?.focalMm ?: 0f
                    val lensFocalRange = remember(effLensModel) { parseLensFocalRange(effLensModel) }
                    val isZoomLens = lensFocalRange != null &&
                        lensFocalRange.second - lensFocalRange.first > 0.5f
                    // Default zoom / focal to 25% of the lens focal range when
                    // EXIF focal is missing (and seed the slider for zooms).
                    LaunchedEffect(effLensModel, exifFocalMm, lensFocalRange) {
                        val range = lensFocalRange ?: return@LaunchedEffect
                        if (exifFocalMm > 0f) return@LaunchedEffect
                        if (lfFocalText.isNotBlank()) return@LaunchedEffect
                        val (minF, maxF) = range
                        val at25 = minF + 0.25f * (maxF - minF)
                        lfFocalText = if (at25 == at25.toInt().toFloat())
                            at25.toInt().toString()
                        else "%.1f".format(at25)
                    }

                    val cameraBodyAutoDetected = !match?.first.isNullOrBlank()
                    var camQuery by remember(sourceUri) { mutableStateOf("") }
                    var camEdited by remember(sourceUri) { mutableStateOf(false) }
                    LaunchedEffect(match) {
                        if (!camEdited && lfManualCamModel.isBlank())
                            camQuery = match?.first.orEmpty()
                    }
                    if (!cameraBodyAutoDetected) {
                        androidx.compose.material3.OutlinedTextField(
                            value = camQuery,
                            onValueChange = {
                                camQuery = it
                                camEdited = true
                                if (it != lfManualCamModel) {
                                    lfManualCamModel = ""; lfManualCamCrop = 0f
                                }
                            },
                            label = { Text("Camera body") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        if (camEdited && camQuery.length >= 3 &&
                            camQuery != lfManualCamModel && camQuery != match?.first
                        ) {
                            val q = norm(camQuery)
                            lfCameras.asSequence()
                                .filter {
                                    norm(it.model).contains(q) ||
                                        (it.alias.isNotBlank() &&
                                            norm(it.alias).contains(q)) ||
                                        norm(it.maker + it.model).contains(q)
                                }
                                .take(8)
                                .forEach { cam ->
                                    Text(
                                        text = if (cam.alias.isNotBlank())
                                            "${cam.model}  ·  ${cam.alias}"
                                        else cam.model,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                lfManualCamModel = cam.model
                                                lfManualCamCrop = cam.cropFactor
                                                camQuery = cam.model
                                            }
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant
                                                    .copy(alpha = 0.5f),
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                        }
                    }
                    if (effCamCrop > 0f) {
                        Text(
                            text = "Sensor format · " +
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                    .LensfunDatabase.sensorFormatLabel(effCamCrop),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                        )
                    }

                    val brands = remember(lfLenses) {
                        lfLenses.groupBy {
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .LensfunDatabase.canonicalBrand(it.maker)
                        }.toSortedMap()
                    }
                    var brandExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        OutlinedButton(
                            onClick = { brandExpanded = true },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = if (lfManualBrand.isBlank())
                                    "Lens brand"
                                else "Brand · $lfManualBrand",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                            )
                            Text("▾", style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = brandExpanded,
                            onDismissRequest = { brandExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.85f).heightIn(max = 300.dp),
                        ) {
                            brands.forEach { (brand, list) ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("$brand  (${list.size})") },
                                    onClick = {
                                        lfManualBrand = brand
                                        lfManualLensModel = ""
                                        brandExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (lfManualBrand.isNotBlank()) {
                        var lensQuery by remember(sourceUri, lfManualBrand) { mutableStateOf("") }
                        androidx.compose.material3.OutlinedTextField(
                            value = lensQuery,
                            onValueChange = {
                                lensQuery = it
                                if (it != lfManualLensModel) lfManualLensModel = ""
                            },
                            label = { Text("Lens type") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        if (lensQuery.length >= 3 && lensQuery != lfManualLensModel) {
                            val q = norm(lensQuery)
                            val brandLenses = brands[lfManualBrand].orEmpty()
                            brandLenses.asSequence()
                                .filter { norm(it.model).contains(q) }
                                .take(8)
                                .forEach { lens ->
                                    Text(
                                        text = lens.model,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                lfManualLensModel = lens.model
                                                lensQuery = lens.model
                                            }
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                        }
                    }
                    val conf = lfConfidence
                    val confLabel = when {
                        lfManualLensModel.isNotBlank() -> null
                        effLensModel.isBlank() -> null
                        conf == RawV3Engine.LensMatchConfidence.High -> "Confident"
                        conf == RawV3Engine.LensMatchConfidence.Low -> "Uncertain"
                        else -> null
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = "Lens type · " + effLensModel.ifBlank { "—" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (confLabel != null) {
                            val badgeColor = when (conf) {
                                RawV3Engine.LensMatchConfidence.High ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            }
                            Text(
                                text = confLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .background(
                                        badgeColor.copy(alpha = 0.14f),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (lfManualLensModel.isBlank() &&
                        conf != RawV3Engine.LensMatchConfidence.High &&
                        lfCandidates.size > 1
                    ) {
                        lfCandidates.forEach { cand ->
                            Text(
                                text = cand.model,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { lfManualLensModel = cand.model }
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant
                                            .copy(alpha = 0.5f),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }

                    // Zoom slider when the matched/picked lens is a zoom.
                    if (isZoomLens && lensFocalRange != null) {
                        val (minF, maxF) = lensFocalRange
                        val sliderFocal = (lfFocalText.toFloatOrNull()
                            ?: if (exifFocalMm > 0f) exifFocalMm
                            else minF + 0.25f * (maxF - minF))
                            .coerceIn(minF, maxF)
                        Text(
                            text = "Zoom · ${"%.0f".format(sliderFocal)} mm",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Slider(
                            value = sliderFocal,
                            onValueChange = { v ->
                                lfFocalText = if (v == v.toInt().toFloat())
                                    v.toInt().toString()
                                else "%.1f".format(v)
                            },
                            valueRange = minF..maxF,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (exifFocalMm <= 0f && effLensModel.isNotBlank() && !isZoomLens) {
                        androidx.compose.material3.OutlinedTextField(
                            value = lfFocalText,
                            onValueChange = { new ->
                                if (new.length <= 6 && new.all { it.isDigit() || it == '.' })
                                    lfFocalText = new
                            },
                            label = { Text("Focal length (mm)") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    // Zero-DCE adaptive devignetting: how early (in shadow
                    // depth) the corner optical gain starts being attenuated.
                    Text(
                        text = "Shadow protection",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Slider(
                        value = lfLiftTau,
                        onValueChange = { lfLiftTau = it },
                        valueRange = 0.3f..1.2f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Output Format section hidden — locked to JPEG (selectedExportFormat).

                if (reducedMode && onProceedReduced != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { applyToMatchingPhotos = !applyToMatchingPhotos }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = applyToMatchingPhotos,
                            onCheckedChange = { applyToMatchingPhotos = it },
                        )
                        Text(
                            text = "Apply to matching photos in this project",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // ── Sticky title + Cancel / Proceed (same row) ───────────────────
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Workspace information",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .then(
                            if (com.RAZStudio.StudioRoom.feature.photo_editor.BuildConfig.DEBUG) {
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { showVngDebug = true },
                                )
                            } else Modifier
                        ),
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        AppLog.d(TAG, "onProceed — filmProfile=${filmProfile.id} (ordinal=${filmProfile.ordinal}) profileIndex=${filmProfile.ordinal}")
                        AppLog.d(TAG, "onProceed — wb=$wbSourceOrdinal expShift=$exposureShiftEv nrEnabled=$nrEnabled highlight=$highlightRecovery caCorr=$caCorrectionEnabled dualAutoContrast=$dualAutoContrast")
                        // Always Camera Color Profile (route A). Non-RAW sources
                        // stay route A as well (pixels as-is).
                        val routeA = true
                        // Lens correction applies ONLY when enabled AND detection
                        // is complete enough: (a) camera sensor size/format known
                        // (crop > 0) AND (b) exact lens brand+type matched
                        // (High confidence auto OR manual lens pick) AND a focal
                        // length is known. Incomplete → import without correction
                        // even if the section toggle stays on for detection.
                        val lfMatch = lensfunMatch
                        val lfCam = lfManualCamModel.ifBlank { lfMatch?.first.orEmpty() }
                        val lfCrop = if (lfManualCamModel.isNotBlank()) lfManualCamCrop
                                     else lfMatch?.second ?: 0f
                        val lfLens = lfManualLensModel.ifBlank { lfMatch?.third.orEmpty() }
                        val lfExifFocal = loadedInfo?.focalMm ?: 0f
                        val lfFocalOv = lfFocalText.toFloatOrNull() ?: 0f
                        // If still no focal but we can parse a zoom/prime range,
                        // fall back to 25% of that range so correction can run.
                        val rangeFallback = parseLensFocalRange(lfLens)?.let { (minF, maxF) ->
                            minF + 0.25f * (maxF - minF)
                        } ?: 0f
                        val effectiveFocalOv = when {
                            lfFocalOv > 0f -> lfFocalOv
                            lfExifFocal <= 0f && rangeFallback > 0f -> rangeFallback
                            else -> 0f
                        }
                        val lensExact = lfManualLensModel.isNotBlank() ||
                            lfConfidence == RawV3Engine.LensMatchConfidence.High
                        val lfReady = lensCorrectionOn &&
                            lensfunDbDir.isNotBlank() &&
                            lfCam.isNotBlank() &&
                            lfCrop > 0f &&
                            lfLens.isNotBlank() &&
                            lensExact &&
                            (lfExifFocal > 0f || effectiveFocalOv > 0f)
                        val builtConfig = WorkspaceConfig(
                                lensfunDbDir    = if (lfReady) lensfunDbDir else "",
                                lensfunCameraId = if (lfReady) lfCam else "",
                                lensfunLensId   = if (lfReady) lfLens else "",
                                lensfunFocalOverrideMm = if (lfReady) effectiveFocalOv else 0f,
                                liftTau = lfLiftTau,
                                lensfunMatchConfidence = when {
                                    !lfReady -> 0
                                    lfManualLensModel.isNotBlank() -> 3
                                    else -> lfConfidence.ordinal
                                },
                                lensfunAdaptedMode = false,
                                bitDepth = bitDepth,
                                colorGamut = colorGamut,
                                demosaicAlgorithm = demosaic,
                                highlightRecovery = highlightRecovery,
                                nrEnabled = nrEnabled,
                                nrLuma = nrLuma,
                                nrChroma = nrChroma,
                                dcpProfileId = dcpProfileId,
                                outputBitDepthMode = outputBitDepthMode,
                                sidecarEnabled = sidecarEnabled,
                                caCorrectionEnabled = caCorrectionEnabled,
                                wbSourceOrdinal = wbSourceOrdinal,
                                exposureShiftEv = exposureShiftEv,
                                fbddNoise = fbddNoise,
                                subjectDetectionEnabled = subjectDetectionEnabled,
                                cameraStyleFinishEnabled = cameraStyleFinishEnabled,
                                blackLevelDelta = blackLevelDelta,
                                whiteLevelDelta = whiteLevelDelta,
                                clipThreshold = clipThreshold,
                                libRawOutputColor = libRawOutputColor,
                                lutInputSpace = lutInputSpace,
                                sensorCalibrationEnabled = sensorCalibrationEnabled,
                                colorFringingMode = colorFringingMode,
                                dualContrastThreshold = dualContrastThreshold,
                                dualAutoContrast = dualAutoContrast,
                                useCameraColorProfile = routeA,
                                cameraProfileGuidedFilter = false,
                                aeSubjectProtection = aeSubjectProtection,
                                filmProfile         = filmProfile,
                                filmGrainLevel      = FilmGrainLevel.OFF,
                                hdrRecovery          = aiReconstructA,
                                shadowRecovery       = aiReconstructA,
                                enhanceEnabled       = false,
                                enhanceGuidedFilter  = false,
                                claheHighlightsBoost = 0f,
                                defaultExportFormat  = RawExportFormat.JPG.name,
                                highlightProtection  = highlightProtection,
                                smartDefaultsEnabled = false,
                            )
                        if (reducedMode && onProceedReduced != null) {
                            onProceedReduced(builtConfig, applyToMatchingPhotos)
                        } else {
                            onProceed(builtConfig)
                        }
                    },
                ) {
                    Text("Proceed")
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * Full-width selectable row for bit-depth and color-gamut sections. Uses a Box +
 * background tint rather than `FilterChip` so the title + description always fit.
 */
@Composable
private fun OptionRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
) {
    val containerColor =
        if (!enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        else if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val titleColor =
        if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    val descrColor =
        if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .then(
                if (enabled) Modifier
                    .clickableNoRipple(onClick = onSelect)
                else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = descrColor,
            )
        }
    }
}

/**
 * Compact algorithm chip used in the horizontal LazyRow. Slightly wider than the
 * default `FilterChip` so the description line is readable.
 */
@Composable
private fun AlgorithmChip(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    description: String,
    onSelect: () -> Unit,
) {
    val containerColor =
        if (!enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        else if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val labelColor =
        if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    val descrColor =
        if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .then(
                if (enabled) Modifier
                    .clickableNoRipple(onClick = onSelect)
                else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = labelColor,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = descrColor,
                maxLines = 3,
            )
        }
    }
}

/**
 * Standard clickable modifier. Uses Compose Foundation's default ripple.
 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/** v2 §1.5 — NR toggle row: a switch with a short description below. */
@Composable
private fun NrToggleRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    active: Boolean = true,
) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceVariant
             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val titleColor = if (active) MaterialTheme.colorScheme.onSurface
                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val descrColor = if (active) MaterialTheme.colorScheme.onSurfaceVariant
                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Reduce noise",
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (active)
                        "Smooths grain and colored speckle"
                    else
                        "Already processed by another app",
                    style = MaterialTheme.typography.bodySmall,
                    color = descrColor,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled && active, enabled = active, onCheckedChange = onToggle)
        }
    }
}

/** v2 §1.5 — labelled integer slider used for NR luma and NR chroma. */
@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
    }
}

/**
 * Continuous-float slider with a printed value + suffix. Used for the
 * sensor-calibration controls (DN offsets, clip threshold). `decimals=0`
 * prints integer DN; `decimals=2` prints fractional clip thresholds.
 */
@Composable
private fun RawDeltaSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    suffix: String = "",
    decimals: Int = 0,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (decimals == 0)
                    "${value.toInt()}$suffix"
                else
                    "%.${decimals}f$suffix".format(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

/** Chromatic-aberration correction toggle. Defaults to on. */
@Composable
private fun CaToggleRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    active: Boolean = true,
    highIsoReason: Boolean = false,
    isoValue: Int = 0,
) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceVariant
             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val titleColor = if (active) MaterialTheme.colorScheme.onSurface
                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val descrColor = if (active) MaterialTheme.colorScheme.onSurfaceVariant
                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Fix color fringing",
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        active ->
                            "Removes purple/green edges on high-contrast lines"
                        highIsoReason ->
                            "Skipped — too much noise at ISO $isoValue for this to help"
                        else ->
                            "Already corrected by another app"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = descrColor,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled && active, enabled = active, onCheckedChange = onToggle)
        }
    }
}

/** v2 §1.8 — Sidecar XMP write/read toggle. */
@Composable
private fun SidecarToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Remember my edits",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Saves your adjustments next to the photo (recommended)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * Source-format categorization for bit-depth gating in the selector.
 *
 * - [Jpeg], [Webp], [Bmp] — strictly 8-bit per channel. 16-bit workspace gains
 *   nothing and is greyed out.
 * - [Png], [Tiff] — can carry 8 or 16 bits per channel. 16-bit workspace is allowed.
 * - [Raw] — every LibRaw-supported sensor format; always >8-bit. 16-bit allowed.
 * - [Unknown] — unrecognised extension; default to allowing 16-bit (don't punish
 *   the user for a missing extension on a SAF URI).
 */
private enum class SourceFormat { Jpeg, Webp, Bmp, Png, Tiff, Raw, Unknown }

/**
 * Detect the source file's format from its URI. Tries the displayName via
 * ContentResolver (works for SAF/OpenDocument URIs which have no path
 * extension), falls back to the URI's last-path-segment extension.
 */
private fun detectSourceFormat(context: Context, uri: Uri): SourceFormat {
    val name: String = runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        } else uri.lastPathSegment
    }.getOrNull().orEmpty()

    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "jpe" -> SourceFormat.Jpeg
        "webp"               -> SourceFormat.Webp
        "bmp"                -> SourceFormat.Bmp
        "png"                -> SourceFormat.Png
        "tif", "tiff"        -> SourceFormat.Tiff
        // LibRaw-supported RAW extensions
        "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "sr2", "srf",
        "raf", "rw2", "raw", "orf", "pef", "srw", "x3f", "erf", "3fr",
        "fff", "dcr", "k25", "kdc", "mrw", "rwl", "mef", "iiq", "ari",
        "r3d", "gpr", "braw" -> SourceFormat.Raw
        else -> SourceFormat.Unknown
    }
}

/**
 * Loaded-image metadata probed from the picked source file before the workspace
 * pipeline starts. All fields are best-effort: any missing tag is rendered as "—".
 *
 * @property displayName Filename including extension (e.g. "IMG_2743.CR2").
 * @property extension   Lower-case extension without the dot (e.g. "cr2"), or empty.
 * @property cameraModel EXIF Model tag (e.g. "Canon EOS 6D"), trimmed.
 * @property width       EXIF PixelXDimension or ImageWidth, in pre-rotation pixels.
 * @property height      EXIF PixelYDimension or ImageLength, in pre-rotation pixels.
 * @property orientation EXIF Orientation tag (1=normal … 8). 0 means "unknown".
 */
private data class LoadedImageInfo(
    val displayName: String,
    val extension: String,
    val cameraMake: String,
    val cameraModel: String,
    val lensModel: String,
    /** EXIF focal length in mm; 0 when absent (manual/adapted lenses). */
    val focalMm: Float,
    val width: Int,
    val height: Int,
    val orientation: Int,
    /**
     * True when the source has already been demosaiced by upstream software
     * (Adobe Lightroom / Camera Raw "Enhance Details" / generic LinearRaw DNG,
     * Canon mRAW, etc.). Detected via TIFF PhotometricInterpretation == 34892
     * (LinearRaw). When set, the workspace selector greys out Demosaic, CA, and
     * NR controls — those stages are already baked into the pixels and running
     * our own would either double-process or stall on already-clean data.
     */
    val alreadyDemosaiced: Boolean,
    /** True when the upstream software is an Adobe "Enhance" / AI-denoise variant. */
    val adobeEnhanced: Boolean,
    /**
     * EXIF ISO speed rating, 0 when not reported. Drives the CA-toggle gating:
     * at ISO ≥ [CA_ISO_THRESHOLD] sensor noise dominates and CA correction
     * mostly chases random gradients, costing minutes of decode time for no
     * visible benefit. See [WorkspaceSelectorSheet] CA section.
     */
    val isoSpeed: Int,
    /**
     * Camera White Balance mode label, derived from EXIF tags. Empty when not
     * reported. Examples: "Auto", "Manual", "Daylight", "Cloudy", "Tungsten".
     */
    val whiteBalanceMode: String,
    /**
     * Approximate Kelvin value when EXIF reports a color temperature. 0 when
     * not reported (most camera EXIF blocks omit a numeric color temp and the
     * actual as-shot WB lives in proprietary maker-notes that ExifInterface
     * doesn't decode).
     */
    val whiteBalanceKelvin: Int,
    /** EXIF F-number (aperture, e.g. 2.8). 0 when not reported. */
    val apertureFNumber: Double,
    /** EXIF exposure time in seconds (e.g. 1/250 = 0.004). 0 when not reported. */
    val exposureSeconds: Double,
)

/** ISO threshold above which CA correction is auto-disabled. */
private const val CA_ISO_THRESHOLD = 2500

/** Convert EXIF Orientation tag to human-readable label. */
private fun orientationLabel(orientation: Int): String = when (orientation) {
    1 -> "Normal (0°)"
    2 -> "Mirrored horizontally"
    3 -> "Rotated 180°"
    4 -> "Mirrored vertically"
    5 -> "Mirrored + rotated 90° CCW"
    6 -> "Rotated 90° CW (portrait)"
    7 -> "Mirrored + rotated 90° CW"
    8 -> "Rotated 90° CCW (portrait)"
    else -> "—"
}

/**
 * Probe the picked source file for filename, dimensions, camera model, and
 * orientation. Uses ContentResolver for the displayName and a fresh
 * InputStream + ExifInterface for the EXIF block. Designed to be cheap enough
 * (~10–50ms) to run inline when the workspace dialog opens.
 *
 * Never throws — any failure returns a best-effort partial result so the UI
 * always renders *something*.
 */
/** Apply the EXIF orientation tag to the embedded thumbnail so it shows upright. */
private fun rotateBitmapForExif(src: Bitmap, orientation: Int): Bitmap {
    val m = android.graphics.Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90  -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL   -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE  -> { m.postRotate(90f);  m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return src
    }
    return runCatching {
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }.getOrDefault(src)
}

private fun probeLoadedImageInfo(context: Context, uri: Uri): LoadedImageInfo {
    // displayName via SAF, fall back to last-path-segment.
    val displayName: String = runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } else uri.lastPathSegment
    }.getOrNull().orEmpty()

    val extension = displayName.substringAfterLast('.', "").lowercase()

    var cameraMake = ""
    var cameraModel = ""
    var lensModel = ""
    var focalMm = 0f
    var width = 0
    var height = 0
    var orientation = 0
    var alreadyDemosaiced = false
    var adobeEnhanced = false
    var isoSpeed = 0
    var whiteBalanceMode = ""
    var whiteBalanceKelvin = 0
    var apertureFNumber = 0.0
    var exposureSeconds = 0.0

    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
            focalMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).toFloat()
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty().trim()
                .ifEmpty { exif.getAttribute(ExifInterface.TAG_LENS_MAKE).orEmpty().trim() }
            // Prefer PixelXDimension/PixelYDimension (rectified EXIF values).
            // Fall back to ImageWidth/ImageLength when missing (some RAW formats
            // only populate the TIFF IFD0 dims).
            width = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
                .takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            height = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
                .takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            // DNG/TIFF "PhotometricInterpretation" tag (262):
            //  32803 = CFA (raw Bayer mosaic — run our full pipeline).
            //  34892 = LinearRaw (already demosaiced, e.g. Adobe Enhance DNGs).
            // Treat anything other than CFA as "already demosaiced" so we don't
            // double-process. Canon mRAW also lands here (LibRaw reports filters=0).
            val photometric = exif.getAttributeInt(
                ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, -1,
            )
            alreadyDemosaiced = photometric == 34892
            val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE).orEmpty()
            adobeEnhanced = software.contains("Enhance", ignoreCase = true) ||
                (alreadyDemosaiced &&
                    (software.contains("Lightroom", ignoreCase = true) ||
                     software.contains("Camera Raw", ignoreCase = true) ||
                     software.contains("Adobe", ignoreCase = true)))
            // ISO: prefer the EXIF ISO_SPEED_RATINGS tag, fall back to
            // PHOTOGRAPHIC_SENSITIVITY which some newer EXIF blocks use.
            isoSpeed = exif.getAttributeInt(
                ExifInterface.TAG_ISO_SPEED_RATINGS, 0,
            ).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)

            // Camera White Balance — EXIF reports two related tags:
            //   • TAG_WHITE_BALANCE: 0 = Auto, 1 = Manual
            //   • TAG_LIGHT_SOURCE: 0=Unknown, 1=Daylight, 2=Fluorescent,
            //     3=Tungsten, 4=Flash, 9=Fine weather, 10=Cloudy, 11=Shade,
            //     12=Daylight fluorescent, 13=Day white fluorescent,
            //     14=Cool white fluorescent, 15=White fluorescent,
            //     17=Standard light A, 18=B, 19=C, 20=D55, 21=D65, 22=D75,
            //     23=D50, 24=ISO studio tungsten, 255=Other.
            // Prefer LightSource when it carries a real preset; fall back to
            // the binary Auto/Manual WhiteBalance tag.
            val wbAutoTag = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)
            val lightSource = exif.getAttributeInt(ExifInterface.TAG_LIGHT_SOURCE, -1)
            whiteBalanceMode = lightSourceLabel(lightSource)
                ?: when (wbAutoTag) {
                    0 -> "Auto"
                    1 -> "Manual"
                    else -> ""
                }
            // Some EXIF blocks include a temperature reading from camera (rare
            // outside Sony / Nikon / Fuji proprietary maker-notes). The standard
            // EXIF tag name varies; t8rin ExifInterface only exposes a handful
            // of name constants. We probe a few candidate string keys and bail
            // on first non-zero hit.
            val tempCandidates = listOf("ColorTemperature", "WBTemperature", "CameraTemperature")
            whiteBalanceKelvin = tempCandidates
                .map { runCatching { exif.getAttributeInt(it, 0) }.getOrDefault(0) }
                .firstOrNull { it in 1500..15000 }
                ?: 0
            // Aperture (F-number) — prefer TAG_F_NUMBER, fall back to legacy
            // TAG_APERTURE_VALUE (APEX-encoded). Returns f-stop as a Double.
            apertureFNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                .takeIf { it > 0.0 }
                ?: exif.getAttributeDouble(ExifInterface.TAG_APERTURE_VALUE, 0.0)
            // Shutter speed in seconds — TAG_EXPOSURE_TIME is a rational
            // expressed as a Double (e.g. 0.004 for 1/250).
            exposureSeconds = exif.getAttributeDouble(
                ExifInterface.TAG_EXPOSURE_TIME, 0.0,
            )
        }
    }

    // Canon mRAW heuristic: extension is cr2/cr3 but the rendered dims (4104×2736)
    // are smaller than the sensor's native full-res shape. LibRaw will report
    // filters=0 for mRAW so it goes down the already-demosaiced path natively;
    // we can't tell from EXIF alone but the wide dim < 5000 + cr2 ext is a
    // reasonable signal for the UI's "skip demosaic toggle" greying.
    if (!alreadyDemosaiced && extension in setOf("cr2", "cr3")) {
        val longSide = maxOf(width, height)
        if (longSide in 1..5000) alreadyDemosaiced = true
    }

    return LoadedImageInfo(
        displayName = displayName,
        extension = extension,
        cameraMake = cameraMake,
        cameraModel = cameraModel,
        lensModel = lensModel,
        focalMm = focalMm,
        width = width,
        height = height,
        orientation = orientation,
        alreadyDemosaiced = alreadyDemosaiced,
        adobeEnhanced = adobeEnhanced,
        isoSpeed = isoSpeed,
        whiteBalanceMode = whiteBalanceMode,
        whiteBalanceKelvin = whiteBalanceKelvin,
        apertureFNumber = apertureFNumber,
        exposureSeconds = exposureSeconds,
    )
}

/** Format an EXIF shutter speed (seconds, Double) as the conventional
 *  "1/250 s" for short exposures or "1.3 s" for long ones. */
private fun formatShutter(seconds: Double): String {
    if (seconds <= 0.0) return "—"
    if (seconds >= 1.0) {
        // ≥1 s → show as decimal seconds, one decimal place.
        val rounded = (seconds * 10.0).toInt() / 10.0
        return "$rounded s"
    }
    val denom = (1.0 / seconds).toInt().coerceAtLeast(1)
    return "1/$denom s"
}

/** Map EXIF LightSource code to a human-readable preset name. Returns null for "not reported" / "Other". */
private fun lightSourceLabel(code: Int): String? = when (code) {
    1 -> "Daylight"
    2 -> "Fluorescent"
    3 -> "Tungsten"
    4 -> "Flash"
    9 -> "Fine weather"
    10 -> "Cloudy"
    11 -> "Shade"
    12 -> "Daylight fluorescent"
    13 -> "Day white fluorescent"
    14 -> "Cool white fluorescent"
    15 -> "White fluorescent"
    17 -> "Standard light A"
    18 -> "Standard light B"
    19 -> "Standard light C"
    20 -> "D55"
    21 -> "D65"
    22 -> "D75"
    23 -> "D50"
    24 -> "ISO studio tungsten"
    else -> null
}

/**
 * Parse min/max focal (mm) from a lens display name, e.g. "EF 24-70mm f/2.8"
 * → (24, 70), "35mm f/2" → (35, 35). Null when no "mm" token is present.
 */
private fun parseLensFocalRange(model: String): Pair<Float, Float>? {
    if (model.isBlank()) return null
    val t = model.lowercase()
    val mm = t.indexOf("mm")
    if (mm <= 0) return null
    var e = mm
    while (e > 0) {
        val c = t[e - 1]
        if (c.isDigit() || c == '.' || c == '-' || c == ' ') e-- else break
    }
    var seg = t.substring(e, mm).trim()
    if (seg.isEmpty()) return null
    val dash = seg.indexOf('-')
    val minF: Float
    val maxF: Float
    if (dash >= 0) {
        minF = seg.substring(0, dash).trim().toFloatOrNull() ?: return null
        maxF = seg.substring(dash + 1).trim().toFloatOrNull() ?: return null
    } else {
        minF = seg.toFloatOrNull() ?: return null
        maxF = minF
    }
    if (minF <= 0f || maxF <= 0f || maxF < minF) return null
    return minF to maxF
}

/**
 * Read-only info card at the top of the workspace selector. Shows what file
 * the user picked plus the EXIF dims/orientation/model so they can verify the
 * pipeline is about to operate on the file they think they picked.
 */
@Composable
private fun LoadedImageInfoCard(info: LoadedImageInfo?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Loaded Image",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (info == null) {
                Text(
                    text = "Reading photo info…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InfoRowText("File", info.displayName.ifEmpty { "—" })
                InfoRowText("Camera", info.cameraModel.ifEmpty { "—" })
                InfoRowText("Lens", info.lensModel.ifEmpty { "—" })
                val dims = if (info.width > 0 && info.height > 0)
                    "${info.width} × ${info.height} px"
                else "—"
                InfoRowText("Dimensions", dims)
            }
        }
    }
}

/**
 * Pre-flight summary of every workspace selection. Mirrors what
 * FullResPipeline / encoders will receive once the user taps Proceed. Used to
 * cross-check against the RAW Export page after save.
 */
@Composable
private fun WorkspaceSummaryCard(
    bitDepth: WorkspaceBitDepth,
    colorGamut: RawColorSpace,
    demosaic: DemosaicAlgorithm,
    isRawSource: Boolean,
    highlightRecovery: HighlightRecoveryMode,
    nrEnabled: Boolean,
    nrLuma: Int,
    nrChroma: Int,
    dcpProfileId: String,
    outputBitDepthMode: OutputBitDepthMode,
    sidecarEnabled: Boolean,
    caCorrectionEnabled: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Summary",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isRawSource) {
                InfoRowText("Quality", demosaic.displayName)
                InfoRowText("Highlight recovery", highlightRecovery.displayName)
                InfoRowText(
                    "Noise reduction",
                    if (nrEnabled) "On (brightness $nrLuma, color $nrChroma)" else "Off",
                )
                InfoRowText(
                    "Camera color",
                    if (dcpProfileId == WorkspaceConfig.DCP_AUTO) "Automatic" else dcpProfileId,
                )
                InfoRowText("Output", outputBitDepthMode.displayName)
                InfoRowText(
                    "Color fringing",
                    if (caCorrectionEnabled) "On" else "Off",
                )
                InfoRowText(
                    "Edit history",
                    if (sidecarEnabled) "On" else "Off",
                )
            }
        }
    }
}

/** Two-column key/value row used by both info cards. */
@Composable
private fun InfoRowText(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** True iff the device reports `totalMem >= 3 GB`. Used to gate `RAZ_AMAZE_DUAL`. */
private fun deviceHasAtLeast3GbRam(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return false
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return info.totalMem >= 3L * 1024L * 1024L * 1024L
}

/**
 * Horizontal chip row showing all stored workspace presets, plus a "Save"
 * chip that opens a name-input dialog and persists the *current* config.
 * Long-press on a preset chip deletes it (via dialog confirmation).
 *
 * Tapping a preset chip calls [onApply] with the loaded config; the caller
 * is expected to push every field back into its respective state variable.
 */
@Composable
private fun WorkspacePresetsRow(
    currentConfig: WorkspaceConfig,
    onApply: (WorkspaceConfig) -> Unit,
) {
    val context = LocalContext.current
    var names by remember { mutableStateOf(WorkspacePresetsStore.listNames(context)) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { newName = ""; showSaveDialog = true },
            label = { Text("+ Save current", style = MaterialTheme.typography.labelMedium) },
        )
        names.forEach { presetName ->
            AssistChip(
                onClick = {
                    val cfg = WorkspacePresetsStore.load(context, presetName)
                    if (cfg != null) onApply(cfg)
                },
                label = { Text(presetName, style = MaterialTheme.typography.labelMedium) },
                trailingIcon = {
                    TextButton(
                        onClick = { pendingDelete = presetName },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("×", style = MaterialTheme.typography.labelLarge) }
                },
            )
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            WorkspacePresetsStore.save(context, trimmed, currentConfig)
                            names = WorkspacePresetsStore.listNames(context)
                        }
                        showSaveDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete preset?") },
            text = { Text("\"$target\" will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        WorkspacePresetsStore.delete(context, target)
                        names = WorkspacePresetsStore.listNames(context)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}
