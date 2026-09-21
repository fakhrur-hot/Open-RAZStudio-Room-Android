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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw

import kotlinx.coroutines.flow.first

import com.RAZStudio.StudioRoom.core.utils.AppLog
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import com.RAZStudio.StudioRoom.core.ui.widget.dialogs.LoadingDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.RAZStudio.StudioRoom.core.ui.widget.image.ImageNotPickedWidget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.Bookmark
import com.RAZStudio.StudioRoom.core.resources.icons.History
import com.RAZStudio.StudioRoom.core.resources.icons.Info
import com.RAZStudio.StudioRoom.core.ui.widget.buttons.CompareButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedFloatingActionButton
import com.RAZStudio.StudioRoom.core.resources.icons.IosShare
import com.RAZStudio.StudioRoom.feature.compare.presentation.components.CompareSheet
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawAdjustmentPanel
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawEditorPanel
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawTabletInspector
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.CinematicBloomProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawProgressCard
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.WorkspaceSelectorSheet
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.rememberEmbeddedThumbnail
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.LensPreviewParams
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import androidx.compose.foundation.shape.RoundedCornerShape
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskBrushMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.CompareRenderState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawBatchSection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.loadWatermarkPreset
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor

// All file extensions supported by LibRaw, mapped to their MIME types.
// Most RAW formats have no registered MIME type and are served as
// image/x-<vendor> or application/octet-stream by Android file managers.
// We include every known variant so the system picker filters correctly.
private val RAW_MIME_TYPES = arrayOf(
    // Generic RAW image catch-all (covers DNG, some ARW, CR2, NEF on modern Android)
    "image/x-adobe-dng",          // .dng
    "image/x-canon-cr2",          // .cr2
    "image/x-canon-cr3",          // .cr3
    "image/x-canon-crw",          // .crw
    "image/x-nikon-nef",          // .nef
    "image/x-nikon-nrw",          // .nrw
    "image/x-sony-arw",           // .arw
    "image/x-sony-sr2",           // .sr2
    "image/x-sony-srf",           // .srf
    "image/x-fuji-raf",           // .raf
    "image/x-panasonic-raw",      // .rw2, .raw
    "image/x-panasonic-rw2",      // .rw2
    "image/x-olympus-orf",        // .orf
    "image/x-pentax-pef",         // .pef
    "image/x-samsung-srw",        // .srw
    "image/x-sigma-x3f",          // .x3f
    "image/x-epson-erf",          // .erf
    "image/x-hasselblad-3fr",     // .3fr
    "image/x-hasselblad-fff",     // .fff
    "image/x-kodak-dcr",          // .dcr
    "image/x-kodak-k25",          // .k25
    "image/x-kodak-kdc",          // .kdc
    "image/x-minolta-mrw",        // .mrw
    "image/x-leica-rwl",          // .rwl
    "image/x-mamiya-mef",         // .mef
    "image/x-phaseone-iiq",       // .iiq
    "image/x-arri-ari",           // .ari
    "image/x-red-r3d",            // .r3d
    "image/x-gopro-gpr",          // .gpr
    "image/x-blackmagic-braw",    // .braw
    // Fallback: generic octet-stream (file managers that don't map RAW to image/x-*)
    "application/octet-stream",
    // Compatibility: also accept standard image formats. 8-bit-only formats
    // (JPEG, WebP, BMP) will have the 16-bit workspace option greyed out
    // since they can't supply >8-bit precision. PNG/TIFF can be 8 or 16-bit.
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/bmp",
    "image/tiff",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawEditorContent(component: RawEditorComponent) {
    val settingsState = com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState.current
    val showRawExport by component.showRawExport.collectAsState()

    // ── RAW file picker (hoisted above the Export branch so the "Clear & Pick New"
    //    button can re-trigger the same Image Source flow that Single RAWEditor uses).
    //    Honors picker mode from Settings, never camera, RAW MIME-filtered.
    val pickerMode = com.RAZStudio.StudioRoom.core.settings.presentation.provider
        .LocalSettingsState.current.picturePickerMode

    val openDocPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) component.openFile(uri) }

    val getContentChooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { component.openFile(it) }
    }

    val photoPickerSingle = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) component.openFile(uri) }

    val launchRawPicker: () -> Unit = {
        when (pickerMode) {
            com.RAZStudio.StudioRoom.core.settings.presentation.model
                .PicturePickerMode.PhotoPicker -> {
                photoPickerSingle.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }
            com.RAZStudio.StudioRoom.core.settings.presentation.model
                .PicturePickerMode.Gallery -> {
                val galleryIntent = android.content.Intent(android.content.Intent.ACTION_PICK).apply {
                    setDataAndType(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "image/*"
                    )
                    putExtra(android.content.Intent.EXTRA_MIME_TYPES, RAW_MIME_TYPES)
                }
                getContentChooser.launch(
                    android.content.Intent.createChooser(galleryIntent, null)
                )
            }
            // Embedded / GetContent / CameraCapture (camera removed) → file explorer
            else -> openDocPicker.launch(RAW_MIME_TYPES)
        }
    }

    val uiState by component.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface pipeline info messages (currently: pixel-shift composite
    // warning when the user opens an A7R IV / E-M1X / Pentax multi-shot
    // file). One-shot — we consume the flow value after showing so the
    // user doesn't see the same warning on every recomposition.
    val pipelineMessage by component.pipelineMessage.collectAsState()
    LaunchedEffect(pipelineMessage) {
        val msg = pipelineMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(
                message = msg,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            component.consumePipelineMessage()
        }
    }

    // Requirement 15.13 — report the "apply to matching photos" outcome.
    // One-shot, same shape as pipelineMessage above.
    val bulkLensApplyResult by component.bulkLensApplyResult.collectAsState()
    LaunchedEffect(bulkLensApplyResult) {
        val result = bulkLensApplyResult
        if (result != null) {
            snackbarHostState.showSnackbar(
                message = "Lens profile applied to ${result.covered} matching photo" +
                    "${if (result.covered == 1) "" else "s"}" +
                    if (result.skipped > 0) ", ${result.skipped} skipped" else "",
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            component.consumeBulkLensApplyResult()
        }
    }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val rawImportSuccessMsg = stringResource(com.RAZStudio.StudioRoom.core.resources.R.string.raw_import_success)
    val rawImportErrorMsg = stringResource(com.RAZStudio.StudioRoom.core.resources.R.string.raw_import_error)
    val rawExportActionsSavedMsg = stringResource(com.RAZStudio.StudioRoom.core.resources.R.string.raw_export_actions_saved)

    var showExitConfirmDialog by remember { mutableStateOf(false) }
    val exportHandler = remember { mutableStateOf<(() -> Unit)?>(null) }
    // Preset menu (top-app-bar overflow → Save / Apply preset).
    var showPresetMenu by remember { mutableStateOf(false) }
    var showPresetExportDialog by remember { mutableStateOf(false) }
    var showPresetPickerDialog by remember { mutableStateOf(false) }
    var presetEntries by remember {
        mutableStateOf<List<com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .preset.RazPresetEntry>>(emptyList())
    }
    // True while preset replay is in progress (cards being added on a
    // background coroutine + masks being regenerated). Used to defer
    // the Export-page navigation snapshot until all cards have landed.
    var presetApplyInFlight by remember { mutableStateOf(false) }

    // Requirement 9.3/9.4 — a project photo's edits are already implicitly
    // saved to its Edit_Sidecar as they happen (persistProjectSidecarLive,
    // called from every persistActions()), so the standalone editor's "Any
    // unsaved adjustments will be lost" confirmation is FALSE for this case —
    // there is nothing pending to lose, and no export/apply step is required
    // to keep it. clearEditorState() only wipes the standalone AutoSaveStore/
    // RawActionsStorage/RawMaskStorage, which are unrelated to the project
    // sidecar and already get re-cleared unconditionally by this component's
    // own init{} block on the next open — skipping it here changes nothing a
    // project session actually depends on.
    // Set later (after the GL view / aspect state exist) to the same graded-canvas
    // capture the Export hand-off uses, so the project exit path can refresh the
    // gallery thumbnail from exactly what was on screen.
    val projectExitCapture = remember {
        mutableStateOf<(suspend () -> android.graphics.Bitmap?)?>(null)
    }
    fun confirmExit() {
        if (component.projectContext != null) {
            // 1) Land the sidecar NOW on the store's detached writer (survives
            //    this component). 2) Refresh the grid tile from the live canvas.
            //    3) Leave. Previously step 1 was a cancellable 700 ms job and
            //    step 2 did not exist — see RawEditorComponent.flushProjectSidecarNow.
            component.flushProjectSidecarNow()
            scope.launch {
                runCatching { projectExitCapture.value?.invoke() }.getOrNull()
                    ?.let { component.persistProjectThumbnail(it) }
                component.onGoBack()
            }
        } else {
            showExitConfirmDialog = true
        }
    }

    BackHandler { confirmExit() }

    // Actions list lives in the component — survives navigation back from PhotoEditor/filter page
    val actions = component.actions

    // Saved presets — loaded from storage, refreshed after save/delete
    var presets by remember { mutableStateOf(component.loadPresetIndex()) }

    // Delta macro: current tab edits from default sliders; resets to UserMacro() after Apply/Cancel
    var deltaMacro by remember { mutableStateOf(UserMacro()) }
    // Observe workspace config so UI reacts when the user changes settings.
    val workspaceConfig by component.workspaceConfigFlow.collectAsState()

    // True once Auto Exposure has been run at least once this session — reveals
    // the Subject Protection slider in the RAW Light tab. Defaults to false
    // because the manual AI Expose toggle is opt-in (user triggers it explicitly).
    var lightAeActive by remember { mutableStateOf(false) }

    val sceneAutoEnhanceEnabled by remember(actions) {
        derivedStateOf {
            actions.any { it.label == "_ai_color_enhance" && it.isVisible }
        }
    }

    // Baseline: fold deltas of NON-MASK visible user cards oldest-to-newest.
    // Mask actions own their own bitmap + adjustments and are applied as
    // separate post-baseline render passes (see RawPipelineCoordinator).
    val baselineMacro by remember {
        derivedStateOf {
            actions
                .filter { it.id != RawAction.ORIGINAL_ID && it.isVisible && it.maskPath == null }
                .reversed()
                .fold(UserMacro()) { acc, action -> acc.mergeWith(action.macro) }
        }
    }

    // Ordered list of mask actions (oldest first), used by the coordinator to
    // apply each layer sequentially after the baseline render. Filtered by
    // visibility so the eye-toggle works per-layer.
    val maskLayers by remember {
        derivedStateOf {
            actions
                .filter { it.id != RawAction.ORIGINAL_ID && it.isVisible && it.maskPath != null }
                .reversed()
        }
    }

    var isToneCurvesTab         by remember { mutableStateOf(false) }
    var isMaskTab               by remember { mutableStateOf(false) }
    // Vignette center-placement mode: armed by the Vignette tab's center
    // button; while true the canvas drag moves the vignette focus point.
    var isVignetteCenterMode    by remember { mutableStateOf(false) }
    var isLensFlareMoveMode     by remember { mutableStateOf(false) }
    // Live graded-frame luma histogram (256 buckets) for the Tone Curves
    // graph backdrop. Captured from the GL renderer when the tab opens — the
    // surface still holds the last graded frame even though rendering pauses
    // on the Tone Curves tab. Null until first captured.
    var gradedHistogram by remember { mutableStateOf<IntArray?>(null) }
    // Live graded-frame bitmap used as the Tone Curves widget's base image so
    // its preview reflects all prior edits (exposure/WB/LUT/…), matching the
    // main canvas — not the ungraded Stage A thumbnail. Captured on tab entry.
    var gradedCurveBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val glViewForHistogram = remember {
        mutableStateOf<com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3GlSurfaceView?>(null)
    }
    // ─── Masking state from sub-component ────────────────────────────
    val includedMaskClasses by component.masking.includedMaskClasses.collectAsState()
    val primaryMaskClass by component.masking.primaryMaskClass.collectAsState()
    val primaryIsLuma by component.masking.primaryIsLuma.collectAsState()
    val primaryIsChroma by component.masking.primaryIsChroma.collectAsState()
    val chromaSubtractMode by component.masking.chromaSubtractMode.collectAsState()
    val maskColorSamples by component.masking.maskColorSamples.collectAsState()
    val maskColorRange by component.masking.maskColorRange.collectAsState()
    val maskColorFeather by component.masking.maskColorFeather.collectAsState()
    val maskColorTolerance by component.masking.maskColorTolerance.collectAsState()

    // ─── Healing state from sub-component ────────────────────────────
    val healRadiusPx by component.healing.healRadiusPx.collectAsState()
    val healActive by component.healing.healActive.collectAsState()
    val isHealing by component.healing.isHealing.collectAsState()
    val healCount by component.healing.healCount.collectAsState()
    val healedOverlay by component.healing.healedOverlay.collectAsState()
    val healDirty by component.healing.healDirty.collectAsState()

    val healScope = androidx.compose.runtime.rememberCoroutineScope()
    var canvasFraction    by remember { mutableFloatStateOf(0.55f) }
    var canvasFractionUserOverridden by remember { mutableStateOf(false) }
    var isComparing       by remember { mutableStateOf(false) }
    var showCompareSheet  by remember { mutableStateOf(false) }
    var canvasScale     by remember { mutableFloatStateOf(1f) }
    var canvasOffset    by remember { mutableStateOf(Offset.Zero) }
    val pendingExport        = false

    // ─── Masking state from sub-component ────────────────────────────
    val isMaskModeActive by component.masking.isMaskModeActive.collectAsState()
    val maskBitmap by component.masking.maskBitmap.collectAsState()
    var maskJob: kotlinx.coroutines.Job? by remember { mutableStateOf(null) }
    val maskDirty by component.masking.maskDirty.collectAsState()
    val brushSize by component.masking.brushSize.collectAsState()
    val brushIntensity by component.masking.brushIntensity.collectAsState()
    val brushFeather by component.masking.brushFeather.collectAsState()
    val brushMode by component.masking.brushMode.collectAsState()
    val sharpSpread by component.masking.sharpSpread.collectAsState()

    // ─── Operational mask graph ──────────────────────────────────────
    // Every mask-producing gesture (model fill, subtract, colour pick,
    // brush stroke) pushes a lightweight MaskNode descriptor; undo/redo/
    // toggle re-evaluate the composite bitmap from the graph and publish
    // it through the single updateMask flow (see MaskGraph.kt). History
    // therefore costs KBs per step, not full-res bitmaps.
    var maskRebuildJob: kotlinx.coroutines.Job? by remember { mutableStateOf(null) }
    component.masking.setMaskGraphRebuildListener { nodes ->
        maskRebuildJob?.cancel()
        maskRebuildJob = scope.launch(Dispatchers.Default) {
            if (nodes.isEmpty()) {
                component.masking.updateMask(null)
                return@launch
            }
            val neutral = component.neutralBitmap ?: return@launch
            val composite = rebuildCompositeFromNodes(
                neutral = neutral,
                nodes = nodes,
                modelMaskResolver = { cls -> component.resolveMaskForClass(cls) },
                edgeMaskResolver = { component.masking.segmentationMasks.value?.edgeMask }
            )
            component.masking.updateMask(composite)
        }
    }

    // Wrapper setters for legacy compatibility
    fun setIsMaskModeActive(v: Boolean) = component.masking.setIsMaskModeActive(v)
    fun setBrushMode(v: MaskBrushMode) = component.masking.setBrushMode(v)
    fun setBrushSize(v: Float) = component.masking.setBrushSize(v)
    fun setBrushIntensity(v: Float) = component.masking.setBrushIntensity(v)
    fun setBrushFeather(v: Float) = component.masking.setBrushFeather(v)
    fun setHealRadius(v: Float) = component.healing.setHealRadius(v)
    fun setHealActive(v: Boolean) = component.healing.setHealActive(v)
    fun setSharpSpread(v: Float) = component.masking.setSharpSpread(v)
    fun setIncludedMaskClasses(v: Set<MaskClass>) = component.masking.setIncludedMaskClasses(v)
    fun setPrimaryMaskClass(v: MaskClass?) = component.masking.setPrimaryMaskClass(v)
    fun setPrimaryIsLuma(v: Boolean) = component.masking.setPrimaryIsLuma(v)
    fun setPrimaryIsChroma(v: Boolean) = component.masking.setPrimaryIsChroma(v)
    fun setChromaSubtractMode(v: Boolean) = component.masking.setChromaSubtractMode(v)
    fun setMaskColorSamples(v: List<Int>) = component.masking.setMaskColorSamples(v)
    fun setMaskColorRange(v: Float) = component.masking.setMaskColorRange(v)
    fun setMaskColorFeather(v: Float) = component.masking.setMaskColorFeather(v)
    fun setMaskColorTolerance(v: Float) = component.masking.setMaskColorTolerance(v)

    fun bakeInvertedLuminance(target: Float, spread: Float, feather: Float) {
        val neutral = component.neutralBitmap ?: return
        maskJob?.cancel()
        maskJob = scope.launch(Dispatchers.Default) {
            val longSide = maxOf(neutral.width, neutral.height)
            val scale = if (longSide > 900) 900f / longSide else 1f
            val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
                neutral, (neutral.width * scale).toInt().coerceAtLeast(1),
                (neutral.height * scale).toInt().coerceAtLeast(1), true,
            ) else neutral
            val width = small.width
            val height = small.height
            val source = IntArray(width * height)
            small.getPixels(source, 0, width, 0, 0, width, height)
            if (small !== neutral) small.recycle()
            val pixels = IntArray(source.size)
            val inner = spread
            val outer = spread + feather.coerceAtLeast(1e-4f)
            for (i in source.indices) {
                if ((i and 0xFFFF) == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val color = source[i]
                val r = ((color ushr 16) and 0xFF) / 255f
                val g = ((color ushr 8) and 0xFF) / 255f
                val b = (color and 0xFF) / 255f
                val luminance = r * 0.299f + g * 0.587f + b * 0.114f
                val distance = kotlin.math.abs(luminance - target)
                val t = ((distance - inner) / (outer - inner)).coerceIn(0f, 1f)
                val inBand = 1f - t * t * (3f - 2f * t)
                pixels[i] = (((1f - inBand) * 255f).toInt() shl 24) or 0x00FFFFFF
            }
            val bitmap = android.graphics.Bitmap.createBitmap(
                pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888,
            )
            withContext(Dispatchers.Main) {
                deltaMacro = deltaMacro.copy(
                    maskLumTarget = 0f, maskLumSpread = 0f,
                    maskLumFeather = 0f, maskLumCombine = 0,
                )
                setPrimaryIsLuma(false)
                setBrushMode(MaskBrushMode.None)
                setIsMaskModeActive(true)
                component.masking.updateMask(bitmap)
            }
        }
    }

    fun invertMask() {
        maskJob?.cancel()
        maskJob = scope.launch(Dispatchers.Default) {
            val source = maskBitmap ?: component.neutralBitmap?.let {
                android.graphics.Bitmap.createBitmap(
                    it.width, it.height, android.graphics.Bitmap.Config.ARGB_8888,
                )
            } ?: return@launch
            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            for (i in pixels.indices) {
                val alpha = (pixels[i] ushr 24) and 0xFF
                pixels[i] = ((255 - alpha) shl 24) or 0x00FFFFFF
            }
            val bitmap = android.graphics.Bitmap.createBitmap(
                pixels, source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888,
            )
            if (source !== maskBitmap) source.recycle()
            withContext(Dispatchers.Main) {
                setIsMaskModeActive(true)
                component.masking.updateMask(bitmap)
            }
        }
    }

    // Color-range mask ("Select Color"): sampled ARGB colours (each canvas tap
    // in ColorSelect mode appends one) + the Refine tolerance [0..100]. The mask
    // is (re)built from ALL samples whenever a tap or the slider changes.
    // maskColorSamples / maskColorTolerance live in component.masking (single
    // source of truth, collected above) — mutate via setMaskColorSamples /
    // setMaskColorTolerance.
    // M12.2c.5 — Sharp Edges momentary fill. The slider controls the
    // mask spread (dilation positive, erosion negative) in [-1, +1].
    // The button writes a fresh edge-snapped + spread bitmap into
    // maskBitmap on press; it never latches state. Held in
    // component.masking — mutate via setSharpSpread.
    // Luminance Range picker: when true, tapping the canvas samples the luma
    // at that pixel (from the neutral Stage A thumbnail) and centers the mask
    // layer's luminance band on it. Mirrors the Vignette center-point mode.
    var canvasWidth          by remember { mutableIntStateOf(1) }
    var canvasHeight         by remember { mutableIntStateOf(1) }
    // Aspect ratio of the currently-loaded source AHB. Drives the
    // GL surface's Modifier.aspectRatio() so we don't paint black
    // letterbox bands inside the canvas slot.
    val previewDimsForAspect by component.previewDimsFlow.collectAsState()
    var imageAspect          by remember {
        val dims = component.previewDimsFlow.value
        val initial = if (dims != null && dims.second > 0) dims.first.toFloat() / dims.second else 1f
        mutableFloatStateOf(initial)
    }
    // Keep imageAspect in sync when dims arrive (first open or new file).
    LaunchedEffect(previewDimsForAspect) {
        val dims = previewDimsForAspect ?: return@LaunchedEffect
        if (dims.second > 0) imageAspect = dims.first.toFloat() / dims.second
    }
    // Graded frame captured on Activity ON_PAUSE (screen-off / background).
    // Prefer this over the Stage-A embedded thumbnail while GL reboots so
    // resume does not flash the "earliest" ungraded look.
    var pauseGradedBackdrop by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var livePreviewRendered by remember { mutableStateOf(false) }
    // Stage B bake tracking — verify harness (and any future "snapshot the
    // current preview" consumer) awaits requested == baked so it doesn't
    // capture a stale AHB while the user is mid-bake.
    var bakeRequestedKey by remember { mutableIntStateOf(0) }
    var bakeBakedKey     by remember { mutableIntStateOf(0) }

    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        canvasScale = (canvasScale * zoomChange).coerceIn(0.5f, 8f)
        val newOffset = canvasOffset + offsetChange
        // Clamp pan so the photo never exits the letterbox / canvas area.
        // Compute the fitted photo size (aspect-fit into canvas), then scale it.
        val cW = canvasWidth.toFloat().coerceAtLeast(1f)
        val cH = canvasHeight.toFloat().coerceAtLeast(1f)
        val fitW = minOf(cW, cH * imageAspect)
        val fitH = fitW / imageAspect.coerceAtLeast(0.001f)
        val maxDx = ((fitW * canvasScale - cW) / 2f).coerceAtLeast(0f)
        val maxDy = ((fitH * canvasScale - cH) / 2f).coerceAtLeast(0f)
        canvasOffset = Offset(
            newOffset.x.coerceIn(-maxDx, maxDx),
            newOffset.y.coerceIn(-maxDy, maxDy),
        )
    }

    // ── Batch processing ──────────────────────────────────────────────────────
    // Hilt-singleton, threaded through [RawEditorComponent]. Shared with
    // Canon Sync's Download & Process flow so the v3 coordinator cache
    // is reused across screens.
    val batchProcessor = component.rawBatchProcessor
    val batchStateNow by batchProcessor.state.collectAsState()
    val isBatchRunning = batchStateNow is com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.BatchProgress.Running
    // Absorb back press entirely during batch — only "Cancel Batch" button exits.
    BackHandler(enabled = isBatchRunning) { /* blocked */ }
    var batchFolderUri  by remember { mutableStateOf<android.net.Uri?>(null) }
    var batchFolderName by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            batchFolderUri  = uri
            batchFolderName = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
        }
    }

    val actionsImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val imported = withContext(Dispatchers.IO) { component.importActions(uri) }
            if (imported != null) {
                component.replaceActions(imported)
                // Explicitly push merged macro so pipeline re-renders after XML load
                val merged = imported
                    .filter { it.id != RawAction.ORIGINAL_ID && it.isVisible }
                    .reversed()
                    .fold(UserMacro()) { acc, action -> acc.mergeWith(action.macro) }
                component.updateMacro(merged)
                snackbarHostState.showSnackbar(rawImportSuccessMsg)
            } else {
                snackbarHostState.showSnackbar(rawImportErrorMsg)
            }
        }
    }

    // Cached path of the XML written before the export save-picker opens
    var pendingExportFilePath by remember { mutableStateOf<String?>(null) }

    val actionsExportSavePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri ->
        val srcPath = pendingExportFilePath ?: return@rememberLauncherForActivityResult
        pendingExportFilePath = null
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = java.io.File(srcPath).readBytes()
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
            withContext(Dispatchers.Main) {
                snackbarHostState.showSnackbar(rawExportActionsSavedMsg)
            }
        }
    }

    val fullResReady         by component.fullResReady.collectAsState()
    val isNonRawSource       by component.isNonRawSource.collectAsState()
    val fullResOutputPath    by component.fullResOutputPathFlow.collectAsState()
    val fullResProcessing    by component.fullResProcessing.collectAsState()
    val embeddedFallback     by component.embeddedFallbackBitmap.collectAsState()
    val segmentationMasks    by component.masking.segmentationMasks.collectAsState()
    // Subject-mask-dependent controls (Bokeh, subject/background Vignette &
    // Gradient) grey out while segmentation is running and no subject mask is
    // ready yet. Enabled once masks arrive OR the chain ends (so a detection
    // failure re-enables them to their prior no-op rather than trapping them).
    val segmentationRunning  by component.masking.segmentationRunning.collectAsState()
    val subjectMaskReady     = segmentationMasks != null
    val subjectSegBusy       = segmentationRunning && !subjectMaskReady
    // v3 variant carries the raw FloatArray + innerRect needed by
    // HealMaskBuilder. The v2 adapter (above) drops innerRect.
    val segmentationMasksV3OuterScope by component.masking.segmentationMasksV3.collectAsState()
    // MediaPipe multiclass per-class masks. Drives the new Mask-tab
    // class-specific Select buttons (Hair / Body / Face / Clothes).
    val multiclassMasks by component.masking.multiclassMasks.collectAsState()
    val multiclassLoadingState by component.masking.multiclassLoading.collectAsState()
    // Face-detection mask (Qualcomm ONNX, whole-face elliptical fill).
    // Preferred over multiclass FaceSkin for the "Face" button so a tap
    // covers the entire face shape rather than just the skin region.
    val faceMask by component.masking.faceMask.collectAsState()
    // Cityscapes 4-class masks (SegFormer-B1 ONNX) — landscape mask source.
    val cityscapesMasks by component.masking.cityscapesMasks.collectAsState()
    val cityscapesLoadingState by component.masking.cityscapesLoading.collectAsState()
    // DeepLabV3+ human-parsing masks (LIP 20-class, 45 MB ONNX).
    // Used alongside selfie_multiclass to improve Hair / Face / BodySkin / Clothes.
    val deepLabMasks by component.masking.deepLabMasks.collectAsState()
    val idleFullResBitmap    by component.idleFullResBitmap.collectAsState()
    val exifInfo             by component.exif.collectAsState()
    // v2-integration §2.1 — toolbar info-button visibility state. The sheet itself
    // reads from [exifInfo]; this flag governs whether ModalBottomSheet is composed.
    var showExifSheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // v2-integration §2.2 — sidecar history sheet visibility.
    var showHistorySheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val isPreviewReady = uiState is RawPipelineState.PreviewReady
    val neutralBmp     = component.neutralBitmap
    val previewBmp     = (uiState as? RawPipelineState.PreviewReady)?.previewBitmap

    val isFullResProcessing = fullResProcessing

    // Pause/resume pipeline rendering when Tone Curves tab is active, and
    // (re-)capture the live graded frame for the curve widget's preview +
    // histogram backdrop. Keying on baselineMacro as well as isToneCurvesTab
    // means an Apply that commits actions while the tab is open re-snapshots
    // immediately, so the graph backdrop tracks the main canvas instead of
    // remaining at the pre-Apply state.
    LaunchedEffect(isToneCurvesTab, baselineMacro) {
        if (isToneCurvesTab) {
            // Resume to let the new baseline render into GL, snapshot,
            // then pause again. Guard with isAttachedToWindow so a
            // re-entry during navigation tear-down (GL released) doesn't
            // post to a dead render handler — symptom was the histogram
            // call silently failing and the graph backdrop staying stale.
            component.resumeRendering()
            val v = glViewForHistogram.value
            if (v != null && v.isAttachedToWindow) {
                runCatching {
                    gradedHistogram = withContext(Dispatchers.Default) { v.histogramGraded(256) }
                    val longSide = 720
                    val sw = if (imageAspect >= 1f) longSide else (longSide * imageAspect).toInt()
                    val sh = if (imageAspect >= 1f) (longSide / imageAspect).toInt() else longSide
                    val graded = withContext(Dispatchers.Default) {
                        v.snapshotGradedToBitmap(longSide, sw.coerceAtLeast(1), sh.coerceAtLeast(1))
                    }
                    gradedCurveBitmap?.takeIf { it !== graded && !it.isRecycled }?.recycle()
                    gradedCurveBitmap = graded
                }
            }
            component.pauseRendering()
        } else {
            component.resumeRendering()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            gradedCurveBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    // Push the IN-FLIGHT delta (not baseline+delta) to the pipeline.
    // `component.updateMacro` already folds `actions + inflight` internally,
    // so passing a pre-merged macro would double-count every committed
    // action: post-Apply the action sits in `actions` AND its values would
    // be re-applied via the inflight slot. Symptom was "Apply makes the
    // preview brighter than the slider showed" — caused by exactly that
    // double fold.
    //
    // Mask fields stay on the delta as-is; the component path threads them
    // straight through into ShaderParams.
    //
    // SideEffect (not LaunchedEffect) — runs synchronously in the same
    // composition frame with zero coroutine launch/cancel overhead.
    // updateMacro is pure Kotlin (list fold + float math + StateFlow emit)
    // and safe on the Main thread; the old LaunchedEffect added a full
    // coroutine dispatch round-trip per slider tick, causing visible lag.
    val capturedDeltaMacro = deltaMacro
    val capturedMaskBitmap = maskBitmap
    // maskJob is assigned synchronously at the top of every fillFromXxx()
    // function, before its coroutine body (the pixel-fill loop) runs on
    // Dispatchers.Default. Without also checking isActive here, a slider
    // dragged while that fill is still in flight (maskBitmap still null/stale
    // from the PREVIOUS mask) gets isMaskEdit=false, so RawEditorComponent
    // tags the delta with maskPath=null instead of INFLIGHT_MASK_SENTINEL —
    // RawV3ActionReplay.maskLayers() then excludes it from applyMaskLayers()
    // entirely, so the adjustment is silently dropped even though the blue
    // mask overlay renders correctly moments later. This was reported as
    // "adjustment doesn't apply to what's selected."
    val capturedMaskFillPending = maskJob?.isActive == true
    // A Select-Luminance mask is parametric — no bitmap, no fill job — so the
    // two checks above both miss it. Without the lumSpread check its edits get
    // maskPath=null, fall out of maskLayers(), and reach the shader only via
    // composeMacro's layer-0 slots — which applyMaskLayers then OVERWRITES
    // whenever any committed masked card exists (the luma edit vanishes and
    // its band leaks onto the committed card's layer instead).
    val capturedLumaMaskActive = capturedDeltaMacro.maskLumSpread > 0f
    SideEffect {
        component.updateMacro(
            capturedDeltaMacro,
            isMaskEdit = capturedMaskBitmap != null ||
                capturedMaskFillPending ||
                capturedLumaMaskActive,
        )
    }

    // Push the ordered list of mask-layer actions to the coordinator. Each layer
    // is applied after the baseline render in [RawPipelineCoordinator]'s slider-
    // render loop and at every full-res render entry point.
    LaunchedEffect(maskLayers) {
        component.masking.setMaskLayers(maskLayers)
    }

    // Multi-pick color range: update the last node when sliders move.
    // Syncs the graph with the current UI slider values (tolerance, range, feather).
    LaunchedEffect(maskColorSamples, maskColorTolerance, maskColorRange, maskColorFeather) {
        val nodes = component.masking.maskNodes.value
        if (nodes.isEmpty() || maskColorSamples.isEmpty()) return@LaunchedEffect
        val last = nodes.last()
        if (last.source is MaskSource.ColorRange) {
            // Replace the last node with updated parameters.
            val updatedNodes = nodes.dropLast(1) + last.copy(
                source = MaskSource.ColorRange(
                    samples = maskColorSamples,
                    tolerance = maskColorTolerance,
                    range = maskColorRange,
                    feather = maskColorFeather
                )
            )
            component.masking.restoreMaskState(
                nodes = updatedNodes,
                primary = primaryMaskClass,
                included = includedMaskClasses,
                isLuma = primaryIsLuma,
                isChroma = primaryIsChroma
            )
        }
    }

    // Show pipeline errors as snackbar
    LaunchedEffect(uiState) {
        if (uiState is RawPipelineState.Error) {
            snackbarHostState.showSnackbar((uiState as RawPipelineState.Error).message)
        }
    }

    // ── Mask helpers ───────────────────────────────────────────────────────────
    //
    // Each helper runs a tight per-pixel loop on Dispatchers.Default and posts
    // the resulting maskBitmap back via the Main dispatcher. Rapid Subject /
    // Background taps used to launch overlapping jobs; the last-write-wins
    // race produced non-deterministic mask state. Hold a single Job slot
    // here, cancel the previous before launching a new one.
    // (declared earlier in this function now — see the maskBitmap block above)

    // M12.2c.5 — Sharp Edges fill. Builds a crisp silhouette from the
    // U2Net probability + Sobel edges, then dilates/erodes by `spread`:
    //   spread =  0  → straight binarize at 0.5
    //   spread > 0   → mask grows outward (more pixels become subject)
    //   spread < 0   → mask shrinks inward
    // Edge-snap pushes pixels at high Sobel gradients toward 0/1 so
    // hair / feather boundaries follow real image edges instead of
    // U2Net's smoothed silhouette.


    /**
     * Synthesize a face-region mask from the MediaPipe multiclass + U²Net
     * masks using a triangulation heuristic the dedicated face-detection
     * ONNX often misses on dense group shots:
     *
     *   For each cell, "is face" iff
     *     • skin probability (FaceSkin OR BodySkin) is non-trivial, AND
     *     • Hair appears above within ~40 px (~head-height window), AND
     *     • Clothes appears below within ~40 px,                  AND
     *     • The cell is NOT background (U²Net subject mask > 0).
     *
     * The visual logic: "round brown object between hair and clothes,
     * inside the subject region, is a face." This recovers small / partly-
     * occluded heads in group shots whose direct face-detection score fell
     * below threshold but whose surrounding context still pins them down.
     *
     * Output: 320×320 row-major [0..1]. Pass-through (returns null) when
     * the required multiclass mask isn't ready.
     */




    /**
     * Invert a luminance-range mask: bake the COMPLEMENT of the tone band
     * [target ± spread] (feathered) into a mask bitmap, mirroring the shader/
     * export `lumMask` formula, then deactivate the GPU luma params so the baked
     * bitmap drives the mask. Lets the halo-free luma selection be inverted while
     * reusing the whole existing mask pipeline (adjustments, further invert, Apply).
     */


    /** True when nothing has claimed the mask base yet (fresh Add = replace). */
    fun isFreshMaskBase(): Boolean =
        primaryMaskClass == null && !primaryIsLuma && !primaryIsChroma &&
            maskBitmap == null && deltaMacro.maskLumSpread <= 0f && maskColorSamples.isEmpty()









    // Canvas content extracted so it can be reused in both portrait and landscape branches
    @Composable
    fun CanvasBox(modifier: Modifier) {
        Box(
            modifier = modifier
                .background(settingsState.letterboxColor)
                .clipToBounds()
                .onSizeChanged { canvasWidth = it.width; canvasHeight = it.height }
                .then(
                    when {
                        isMaskModeActive &&
                            (brushMode == MaskBrushMode.Draw || brushMode == MaskBrushMode.Erase) &&
                            isPreviewReady -> Modifier.pointerInput(
                            brushSize, brushIntensity, brushFeather, brushMode, canvasWidth, canvasHeight,
                        ) {
                            awaitEachGesture {
                                val bmp = maskBitmap ?: run {
                                    val neutral = component.neutralBitmap ?: return@awaitEachGesture
                                    val newBmp = android.graphics.Bitmap.createBitmap(
                                        neutral.width, neutral.height, android.graphics.Bitmap.Config.ARGB_8888,
                                    )
                                    component.masking.updateMask(newBmp)
                                    newBmp
                                }
                                val paint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeCap = android.graphics.Paint.Cap.ROUND
                                    strokeJoin = android.graphics.Paint.Join.ROUND
                                    strokeWidth = brushSize
                                    // Both Draw and Erase honour intensity + feather. Erase uses
                                    // DST_OUT (subtract source alpha from destination) so intensity
                                    // = how much to erase, feather = soft edge. Draw uses opaque
                                    // SrcOver white with alpha = intensity.
                                    color = android.graphics.Color.WHITE
                                    alpha = (255 * brushIntensity).roundToInt().coerceIn(0, 255)
                                    if (brushFeather > 0.01f) {
                                        maskFilter = android.graphics.BlurMaskFilter(
                                            brushSize * brushFeather * 0.5f,
                                            android.graphics.BlurMaskFilter.Blur.NORMAL,
                                        )
                                    }
                                    if (brushMode == MaskBrushMode.Erase) {
                                        xfermode = android.graphics.PorterDuffXfermode(
                                            android.graphics.PorterDuff.Mode.DST_OUT,
                                        )
                                    }
                                }
                                val bmCanvas = android.graphics.Canvas(bmp)

                                fun screenToBmp(pos: Offset): android.graphics.PointF {
                                    val bW = bmp.width.toFloat()
                                    val bH = bmp.height.toFloat()
                                    val cW = canvasWidth.toFloat()
                                    val cH = canvasHeight.toFloat()
                                    val cx = cW / 2f; val cy = cH / 2f
                                    val ix = (pos.x - canvasOffset.x - cx) / canvasScale + cx
                                    val iy = (pos.y - canvasOffset.y - cy) / canvasScale + cy
                                    val imgAspect = bW / bH
                                    val imgW: Float; val imgH: Float
                                    if (imgAspect > cW / cH) { imgW = cW; imgH = cW / imgAspect }
                                    else { imgW = cH * imgAspect; imgH = cH }
                                    val left = (cW - imgW) / 2f
                                    val top  = (cH - imgH) / 2f
                                    return android.graphics.PointF(
                                        ((ix - left) * bW / imgW).coerceIn(0f, bW - 1f),
                                        ((iy - top) * bH / imgH).coerceIn(0f, bH - 1f),
                                    )
                                }

                                // Single finger paints; TWO fingers pan/zoom the
                                // canvas even in Draw/Erase (transformable is off on
                                // the Mask tab, so this handler owns both gestures).
                                // Once a two-finger phase occurs, the rest of the
                                // gesture never draws — a pinch leaves no stray stroke.
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var last = screenToBmp(down.position)
                                // Record the stroke in bitmap coordinates so the
                                // gesture can be pushed onto the operational mask
                                // graph (undo/redo re-rasterizes it — no bitmap
                                // snapshots in history).
                                val strokePoints = mutableListOf(last.x to last.y)
                                var started = false      // has any paint been laid down
                                var didTransform = false // this gesture became a pan/zoom
                                var twoFinger = false     // ≥2 fingers on the PREVIOUS event
                                var prevCentroid = Offset.Zero
                                var prevDist = 0f
                                while (true) {
                                    val evt = awaitPointerEvent()
                                    val pressed = evt.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        val c = (pressed.fold(Offset.Zero) { a, p -> a + p.position }) /
                                            pressed.size.toFloat()
                                        val dist = (pressed[1].position - pressed[0].position).getDistance()
                                        if (twoFinger && prevDist > 0f) {
                                            canvasScale = (canvasScale * (dist / prevDist)).coerceIn(0.5f, 8f)
                                            val newOffset = canvasOffset + (c - prevCentroid)
                                            val cW = canvasWidth.toFloat().coerceAtLeast(1f)
                                            val cH = canvasHeight.toFloat().coerceAtLeast(1f)
                                            val fitW = minOf(cW, cH * imageAspect)
                                            val fitH = fitW / imageAspect.coerceAtLeast(0.001f)
                                            val maxDx = ((fitW * canvasScale - cW) / 2f).coerceAtLeast(0f)
                                            val maxDy = ((fitH * canvasScale - cH) / 2f).coerceAtLeast(0f)
                                            canvasOffset = Offset(
                                                newOffset.x.coerceIn(-maxDx, maxDx),
                                                newOffset.y.coerceIn(-maxDy, maxDy),
                                            )
                                        }
                                        twoFinger = true; didTransform = true
                                        prevCentroid = c; prevDist = dist
                                        pressed.forEach { it.consume() }
                                    } else {
                                        val ch = pressed.first()
                                        if (didTransform) { ch.consume(); continue } // no draw after a pinch
                                        val curr = screenToBmp(ch.position)
                                        if (!started) {
                                            bmCanvas.drawLine(curr.x, curr.y, curr.x, curr.y, paint)
                                            started = true
                                        } else {
                                            bmCanvas.drawLine(last.x, last.y, curr.x, curr.y, paint)
                                        }
                                        last = curr
                                        strokePoints.add(curr.x to curr.y)
                                        ch.consume()
                                    }
                                }
                            }
                        }
                        // Color-range "Select Color": tap the photo to sample a
                        // colour; the mask becomes every pixel within tolerance of
                        // any sample. Each tap extends the range (multi-pick).
                        isMaskModeActive && brushMode == MaskBrushMode.ColorSelect &&
                            isPreviewReady -> Modifier.pointerInput(
                            canvasWidth, canvasHeight, canvasScale, canvasOffset, maskColorTolerance,
                        ) {
                            detectTapGestures { pos ->
                                val neutral = component.neutralBitmap ?: return@detectTapGestures
                                val bW = neutral.width.toFloat(); val bH = neutral.height.toFloat()
                                val cW = canvasWidth.toFloat(); val cH = canvasHeight.toFloat()
                                if (cW <= 0f || cH <= 0f) return@detectTapGestures
                                // Same screen→image mapping as the brush (zoom/pan + letterbox).
                                val cx = cW / 2f; val cy = cH / 2f
                                val ix = (pos.x - canvasOffset.x - cx) / canvasScale + cx
                                val iy = (pos.y - canvasOffset.y - cy) / canvasScale + cy
                                val imgAspect = bW / bH
                                val imgW: Float; val imgH: Float
                                if (imgAspect > cW / cH) { imgW = cW; imgH = cW / imgAspect }
                                else { imgW = cH * imgAspect; imgH = cH }
                                val left = (cW - imgW) / 2f; val top = (cH - imgH) / 2f
                                val sx = (ix - left) * bW / imgW
                                val sy = (iy - top) * bH / imgH
                                if (sx < 0f || sy < 0f || sx >= bW || sy >= bH) return@detectTapGestures // letterbox
                                val argb = neutral.getPixel(
                                    sx.toInt().coerceIn(0, neutral.width - 1),
                                    sy.toInt().coerceIn(0, neutral.height - 1),
                                ) or 0xFF000000.toInt()
                                val updated = maskColorSamples + argb
                                setMaskColorSamples(updated)
                                val colorCombine = when {
                                    chromaSubtractMode -> 2
                                    primaryIsChroma ||
                                        (maskBitmap == null && deltaMacro.maskLumSpread <= 0f) -> 0
                                    else -> 1 // union onto existing bitmap / luma base
                                }

                                // Strictly graph-driven: push a node; the graph
                                // listener re-evaluates the composite bitmap.
                                if (deltaMacro.maskLumSpread <= 0f) {
                                    component.masking.pushMaskNode(
                                        MaskNode(
                                            id = java.util.UUID.randomUUID().toString(),
                                            source = MaskSource.ColorRange(
                                                samples = updated,
                                                tolerance = maskColorTolerance,
                                                range = maskColorRange,
                                                feather = maskColorFeather
                                            ),
                                            operation = if (colorCombine == 2)
                                                MaskOp.SUBTRACT else MaskOp.ADD,
                                        )
                                    )
                                }
                            }
                        }
                        isVignetteCenterMode && isPreviewReady -> Modifier.pointerInput(
                            canvasWidth, canvasHeight, canvasScale, canvasOffset,
                        ) {
                            // Drag to reposition the vignette center point on the canvas.
                            // Convert screen tap/drag coordinates to normalized image coordinates [0,1].
                            fun screenToNorm(pos: Offset): Pair<Float, Float> {
                                val cW = canvasWidth.toFloat()
                                val cH = canvasHeight.toFloat()
                                val cx = cW / 2f; val cy = cH / 2f
                                // Undo canvas pan/zoom transform
                                val ix = (pos.x - canvasOffset.x - cx) / canvasScale + cx
                                val iy = (pos.y - canvasOffset.y - cy) / canvasScale + cy
                                // Image is fit-scaled; find the image rect inside the canvas
                                val neutral = component.neutralBitmap ?: return 0.5f to 0.5f
                                val imgAspect = neutral.width.toFloat() / neutral.height.toFloat()
                                val imgW: Float; val imgH: Float
                                if (imgAspect > cW / cH) { imgW = cW; imgH = cW / imgAspect }
                                else { imgW = cH * imgAspect; imgH = cH }
                                val left = (cW - imgW) / 2f
                                val top  = (cH - imgH) / 2f
                                val nx = ((ix - left) / imgW).coerceIn(0f, 1f)
                                val ny = ((iy - top)  / imgH).coerceIn(0f, 1f)
                                return nx to ny
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val (nx, ny) = screenToNorm(down.position)
                                component.updateVignetteCenter(nx, ny)
                                down.consume()
                                while (true) {
                                    val evt = awaitPointerEvent()
                                    val ch = evt.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val (mx, my) = screenToNorm(ch.position)
                                    component.updateVignetteCenter(mx, my)
                                    ch.consume()
                                }
                            }
                        }
                        isLensFlareMoveMode && isPreviewReady -> Modifier.pointerInput(
                            canvasWidth, canvasHeight, canvasScale, canvasOffset,
                        ) {
                            fun screenToNorm(pos: Offset): Pair<Float, Float> {
                                val cW = canvasWidth.toFloat()
                                val cH = canvasHeight.toFloat()
                                val cx = cW / 2f; val cy = cH / 2f
                                val ix = (pos.x - canvasOffset.x - cx) / canvasScale + cx
                                val iy = (pos.y - canvasOffset.y - cy) / canvasScale + cy
                                val neutral = component.neutralBitmap ?: return 0.5f to 0.5f
                                val imgAspect = neutral.width.toFloat() / neutral.height.toFloat()
                                val imgW: Float; val imgH: Float
                                if (imgAspect > cW / cH) { imgW = cW; imgH = cW / imgAspect }
                                else { imgW = cH * imgAspect; imgH = cH }
                                val left = (cW - imgW) / 2f
                                val top  = (cH - imgH) / 2f
                                val nx = ((ix - left) / imgW).coerceIn(0f, 1f)
                                val ny = ((iy - top)  / imgH).coerceIn(0f, 1f)
                                return nx to ny
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val (nx, ny) = screenToNorm(down.position)
                                component.updateLensFlarePosition(nx * 2f - 1f, ny * 2f - 1f)
                                down.consume()
                                while (true) {
                                    val evt = awaitPointerEvent()
                                    val ch = evt.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val (mx, my) = screenToNorm(ch.position)
                                    component.updateLensFlarePosition(mx * 2f - 1f, my * 2f - 1f)
                                    ch.consume()
                                }
                            }
                        }
                        healActive && isPreviewReady -> Modifier.pointerInput(
                            canvasWidth, canvasHeight, canvasScale, canvasOffset, healRadiusPx,
                        ) {
                            // Heal tap dispatcher. Suspends pinch/pan/zoom so a
                            // tap is interpreted as a heal request. Converts
                            // screen pos → normalised image coords [0..1] using
                            // the same fit-scale math the mask painter uses.
                            fun screenToNorm(pos: Offset): Pair<Float, Float> {
                                val cW = canvasWidth.toFloat()
                                val cH = canvasHeight.toFloat()
                                val cx = cW / 2f; val cy = cH / 2f
                                val ix = (pos.x - canvasOffset.x - cx) / canvasScale + cx
                                val iy = (pos.y - canvasOffset.y - cy) / canvasScale + cy
                                val neutral = component.neutralBitmap ?: return 0.5f to 0.5f
                                val imgAspect = neutral.width.toFloat() / neutral.height.toFloat()
                                val imgW: Float; val imgH: Float
                                if (imgAspect > cW / cH) { imgW = cW; imgH = cW / imgAspect }
                                else { imgW = cH * imgAspect; imgH = cH }
                                val left = (cW - imgW) / 2f
                                val top  = (cH - imgH) / 2f
                                val nx = ((ix - left) / imgW).coerceIn(0f, 1f)
                                val ny = ((iy - top)  / imgH).coerceIn(0f, 1f)
                                return nx to ny
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val (nx, ny) = screenToNorm(down.position)
                                val rPx = healRadiusPx
                                AppLog.i(
                                    "RawHeal", "tap nx=%.3f ny=%.3f r=%.0f".format(nx, ny, rPx),
                                )
                                // Kick off the heal off-thread. The healed
                                // bitmap is stored as an overlay shown on
                                // top of the GL surface.
                                healScope.launch {
                                    if (isHealing) return@launch
                                    component.healing.setIsHealing(true)
                                    val src: android.graphics.Bitmap? =
                                        healedOverlay ?: run {
                                            val v = glViewForHistogram.value
                                            // The "previewBmp" from
                                            // PreviewReady is a 1×1
                                            // SENTINEL — passing it
                                            // produces a 1×1 snapshot
                                            // and silent no-op heal.
                                            // Use the neutralBitmap dims
                                            // (real source aspect) like
                                            // every other path in this
                                            // file does.
                                            val neutral = component.neutralBitmap
                                            val nw = neutral?.width ?: 0
                                            val nh = neutral?.height ?: 0
                                            if (v == null || nw <= 1 || nh <= 1) {
                                                AppLog.w(
                                                    "RawHeal",
                                                    "no GL view / no neutral bmp (v=$v w=$nw h=$nh) — skipping",
                                                )
                                                null
                                            } else {
                                                withContext(Dispatchers.Default) {
                                                    v.snapshotGradedToBitmap(
                                                        /*maxLongSide=*/ 1280,
                                                        nw,
                                                        nh,
                                                    )
                                                }
                                            }
                                        }
                                    if (src == null || src.width <= 1 || src.height <= 1) {
                                        AppLog.w(
                                            "RawHeal",
                                            "snapshot null or 1×1 (${src?.width}×${src?.height}) — skipping",
                                        )
                                        component.healing.setIsHealing(false)
                                        return@launch
                                    }
                                    AppLog.i(
                                        "RawHeal",
                                        "heal src ${src.width}×${src.height}",
                                    )
                                    val healed = withContext(Dispatchers.Default) {
                                        val w = src.width; val h = src.height
                                        val cx = (nx * w).toFloat().coerceIn(0f, (w - 1).toFloat())
                                        val cy = (ny * h).toFloat().coerceIn(0f, (h - 1).toFloat())
                                        // Scale slider's screen-space radius to
                                        // source-bitmap coords. Use canvas-fit
                                        // ratio: source.width / canvas.width.
                                        val canvasW = canvasWidth.toFloat().coerceAtLeast(1f)
                                        val canvasH = canvasHeight.toFloat().coerceAtLeast(1f)
                                        val imgAspect = w.toFloat() / h.toFloat()
                                        val fit = if (imgAspect > canvasW / canvasH) canvasW / w else canvasH / h
                                        val rSrc = (rPx / fit).coerceAtLeast(2f)
                                        // Boundary-guarded mask: circle ∩
                                        // U2Net-same-class-as-tap region.
                                        // When v3 masks aren't ready yet the
                                        // builder silently returns the touch
                                        // circle, preserving legacy behavior.
                                        val mask = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.heal
                                            .HealMaskBuilder.build(
                                                srcW = w, srcH = h,
                                                tapXSrc = cx, tapYSrc = cy,
                                                radiusSrcPx = rSrc,
                                                masks = segmentationMasksV3OuterScope,
                                            )
                                        runCatching {
                                            com.RAZStudio.opencv_tools.spot_heal.SpotHealer.heal(
                                                image = src, mask = mask,
                                                radius = 4f,
                                                type = com.RAZStudio.opencv_tools.spot_heal.model.HealType.TELEA,
                                            )
                                        }.getOrNull().also { mask.recycle() }
                                    }
                                    if (healed != null) {
                                        AppLog.i(
                                            "RawHeal",
                                            "heal OK ${healed.width}×${healed.height}",
                                        )
                                        // Publish via the component: it pushes the
                                        // pre-tap overlay onto its undo stack,
                                        // snapshots the pre-edit baseline on the
                                        // Heal tab's first dirty tap (setHealActive),
                                        // bumps healCount and marks dirty. The stack
                                        // (and the pre-edit snapshot) keep references
                                        // to those bitmaps — nothing is recycled here.
                                        component.healing.setHealedOverlay(healed)
                                    } else {
                                        AppLog.w("RawHeal", "heal returned null")
                                    }
                                    component.healing.setIsHealing(false)
                                }
                                while (true) {
                                    val evt = awaitPointerEvent()
                                    val ch = evt.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    ch.consume()
                                }
                            }
                        }
                        isPreviewReady -> Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { canvasScale = 1f; canvasOffset = Offset.Zero },
                                onPress = {
                                    // Hold-to-compare: only swap to the original
                                    // (neutral) bitmap after the finger has been
                                    // held for ≥ 1 s. Short enough to feel
                                    // responsive, long enough that a normal tap
                                    // (e.g. to dismiss UI) doesn't accidentally
                                    // flash the unedited image.
                                    val held = kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                                        tryAwaitRelease()
                                    }
                                    if (held == null) {
                                        // Timed out → still holding → enter compare
                                        isComparing = true
                                        tryAwaitRelease()
                                        isComparing = false
                                    }
                                    // If `held` is non-null the user released before
                                    // 1 s — nothing to do (compare never activated).
                                },
                            )
                        }
                        else -> Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                is RawPipelineState.AwaitingWorkspaceChoice -> {
                    // Behind the workspace dialog, show a ≤1600px preview of the
                    // photo just picked (LibRaw embedded preview JPEG when available,
                    // not the tiny EXIF IFD thumb). Live lens correction mutates a
                    // copy via applyLensfunToBitmap when the sheet reports a match.
                    val pickedThumb = rememberEmbeddedThumbnail(state.uri)
                    // Live lens-correction preview: when the workspace sheet
                    // reports resolved camera+lens+focal, apply the SAME lensfun
                    // geometry to a copy of the preview so the background photo
                    // shows the correction the import will bake. Null params (or a
                    // no-match) fall back to the uncorrected thumbnail.
                    var lensPreview by remember(state.uri) {
                        mutableStateOf<LensPreviewParams?>(null)
                    }
                    val correctedThumb by produceState<android.graphics.Bitmap?>(
                        null, pickedThumb, lensPreview,
                    ) {
                        val base = pickedThumb
                        val lp = lensPreview
                        if (base == null || lp == null) { value = null; return@produceState }
                        value = withContext(Dispatchers.IO) {
                            val copy = runCatching {
                                base.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                            }.getOrNull() ?: return@withContext null
                            val ok = RawV3Engine.applyLensfunToBitmap(
                                copy, lp.camMaker, lp.camModel, lp.lensMaker, lp.lensModel,
                                lp.focalMm, lp.aperture, lp.dbDir,
                            )
                            if (ok) copy else null
                        }
                    }
                    val displayThumb = correctedThumb ?: pickedThumb
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (displayThumb != null) {
                            val ratio = if (displayThumb.height > 0)
                                displayThumb.width.toFloat() / displayThumb.height.toFloat()
                            else 1.5f
                            Image(
                                bitmap = displayThumb.asImageBitmap(),
                                contentDescription = "Loaded photo preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(ratio)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        } else {
                            ImageNotPickedWidget(
                                onPickImage = launchRawPicker,
                            )
                        }
                    }
                    // Requirement 15.1-15.5 — a photo opened from a Gallery
                    // Workspace project always uses the reduced lens-only
                    // selector; every other entry point is unchanged.
                    val isProjectFirstOpen = component.projectContext != null
                    WorkspaceSelectorSheet(
                        initial = state.suggested,
                        sourceUri = state.uri,
                        reducedMode = isProjectFirstOpen,
                        onProceed = { chosen ->
                            component.confirmWorkspace(state.uri, chosen)
                        },
                        onProceedReduced = if (isProjectFirstOpen) { { chosen, applyToMatching ->
                            component.commitFirstOpenWorkspace(state.uri, chosen, applyToMatching)
                        } } else null,
                        onCancel = { component.cancelWorkspaceChoice() },
                        onLensPreviewParamsChanged = { lensPreview = it },
                    )
                }
                is RawPipelineState.Idle -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ImageNotPickedWidget(
                            onPickImage = launchRawPicker,
                            modifier    = Modifier.fillMaxWidth(),
                            text        = stringResource(R.string.raw_single_editor),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        // Show both 8-bit and 16-bit presets in Apply Preset.
                        val batchPresets = presets
                        RawBatchSection(
                            processor          = batchProcessor,
                            presets            = batchPresets,
                            selectedFolderName = batchFolderName,
                            onBrowseFolder     = { folderPicker.launch(null) },
                            onRunBatch         = { presetIndex, autoExposure, lensCorrection, aiReconstruct, aiEnhance, guidedFilter, useCameraColorProfile, formatFilter, watermarkPresetName, exifPolicy, saveIcc, highlightProtection ->
                                val folderUri = batchFolderUri ?: return@RawBatchSection
                                // Load preset actions, or fall back to the live action stack.
                                val batchActions: List<RawAction> = if (presetIndex != null) {
                                    val chosenFile = batchPresets.getOrNull(presetIndex)?.fileName
                                    val originalIndex = presets.indexOfFirst { it.fileName == chosenFile }
                                    if (originalIndex >= 0) component.loadPreset(originalIndex) ?: emptyList()
                                    else emptyList()
                                } else {
                                    component.actions.toList()
                                }
                                val watermarkConfig = watermarkPresetName?.let {
                                    loadWatermarkPreset(context, it)
                                }
                                batchProcessor.start(
                                    folderTreeUri   = folderUri,
                                    actions         = batchActions,
                                    autoExposure    = autoExposure,
                                    lensCorrection  = lensCorrection,
                                    aiReconstruct   = aiReconstruct,
                                    aiEnhance       = aiEnhance,
                                    guidedFilter    = guidedFilter,
                                    useCameraColorProfile = useCameraColorProfile,
                                    formatFilter    = formatFilter,
                                    watermarkConfig = watermarkConfig,
                                    exifPolicy      = exifPolicy,
                                    saveIcc         = saveIcc,
                                    highlightProtection = highlightProtection,
                                )
                            },
                            onClearBatch       = {
                                batchProcessor.reset()
                                batchFolderUri  = null
                                batchFolderName = null
                            },
                        )
                    }
                }
                is RawPipelineState.PreviewLoading -> {
                    val thumbnailBitmap by component.thumbnailBitmapFlow.collectAsState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        val thumb = thumbnailBitmap
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        RawProgressCard(
                            state    = state,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(androidx.compose.ui.Alignment.BottomCenter)
                                .padding(24.dp),
                        )
                    }
                }
                is RawPipelineState.PreviewReady,
                is RawPipelineState.FullResProcessing,
                is RawPipelineState.FullResReady -> {
                    // M12.1b — v3 live preview surface. The editor used to
                    // render a per-tick Bitmap snapshot here; v3 hosts a
                    // SurfaceView via AndroidView and reuploads shader
                    // uniforms on every slider tick. Zero per-frame
                    // allocation, ~60 FPS.
                    //
                    // The Composable observes:
                    //   • stageATifPath — set when StageBReady fires
                    //   • params         — composed ShaderParams from the
                    //                      committed action stack + the
                    //                      in-flight macro slider
                    //
                    // The compare overlay + the mask brush bitmap were
                    // bitmap-canvas concepts; both are deferred to M12.2b
                    // alongside the Mask tab port (segmentation is
                    // already wired by M12.2a but the brush UI isn't).
                    val stageAPath by component.stageATifPathFlow.collectAsState()
                    val previewDims by component.previewDimsFlow.collectAsState()
                    val params      by component.shaderParamsFlow.collectAsState()
                    val lutPath     by component.lutCubePathFlow.collectAsState()
                    val toneCurveLut by component.toneCurveLutFlow.collectAsState()
                    // Phase-2: forward params changes into GradingPipeline.
                    // The pipeline debounces 250 ms internally — fine to call on every tick.
                    val maskLayersForBake by component.masking.maskLayerBitmaps.collectAsState()
                    val composedMacro by component.composedMacroFlow.collectAsState()
                    androidx.compose.runtime.LaunchedEffect(params, stageAPath, lutPath, toneCurveLut, previewDims) {
                        val path = stageAPath ?: return@LaunchedEffect
                        val dims = previewDims
                        component.gradingPipeline.requestBake(
                            params          = params,
                            macro           = composedMacro,
                            stageATifPath   = path,
                            lutCubePath     = lutPath,
                            brushMaskLayers = maskLayersForBake,
                            toneCurveLut    = toneCurveLut,
                            knownSrcWidth   = dims?.first ?: 0,
                            knownSrcHeight  = dims?.second ?: 0,
                        )
                    }
                    // M12.2c.1 — U2Net subject mask for Vignette + Gradient
                    // SegmentTarget gating. v2 type for now; identical
                    // shape to RawV3SegmentationMasks (see notes there).
                    // Use v3 flow directly so the letterbox innerRect
                    // metadata survives into the renderer (it's missing
                    // from the v2 RawSegmentationMasks bridge type).
                    val v3Masks by component.masking.segmentationMasksV3.collectAsState()
                    val depthMap by component.masking.depthMap.collectAsState()
                    val pathSnapshot = stageAPath
                    val brushMaskDirtyState by component.masking.maskDirty.collectAsState()
                    // Read the committed mask flow from the component so
                    // Apply-time PNG reloads reach the GL renderer. The
                    // Committed multi-layer masks (up to 4) for post-Apply
                    // replay. While the user is painting a NEW mask, the
                    // in-flight `maskBitmap` drives layer 0 instead (the
                    // layer list is only authoritative once actions commit).
                    val committedMaskLayers by component.masking.maskLayerBitmaps.collectAsState()
                    // Hold-to-compare: while pressed (≥1 s) show the
                    // Stage A + auto-exposure baseline. When autoExposure
                    // is enabled the AE action's light params are the
                    // baseline; identity ShaderParams is used only when
                    // no AE action exists (autoExposure disabled).
                    val aeShaderParams by component.aeShaderParamsFlow.collectAsState()
                    val effectiveParams = if (isComparing)
                        aeShaderParams
                            ?: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams()
                    else
                        params
                    // Never-blank backdrop: prefer the last graded pause
                    // snapshot (screen-off / background) so GL reboot does
                    // not flash the Stage-A embedded thumbnail ("earliest
                    // process" look). Fall back to that thumbnail only when
                    // no graded snapshot exists yet (first open).
                    val backdropThumb by component.thumbnailBitmapFlow.collectAsState()
                    val backdropBmp = if (!livePreviewRendered) (pauseGradedBackdrop ?: backdropThumb) else null
                    backdropBmp?.let { t ->
                        Image(
                            bitmap = t.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (pathSnapshot != null) {
                        LaunchedEffect(pathSnapshot) {
                            livePreviewRendered = false
                            pauseGradedBackdrop = null
                        }
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                            .RawV3PreviewComposable(
                                stageATifPath = pathSnapshot,
                                params = effectiveParams,
                                lutCubePath = lutPath,
                                toneCurveLut = if (isComparing) null else toneCurveLut,
                                // Pan/zoom is applied INSIDE GL (window viewport),
                                // not via a graphicsLayer on the SurfaceView — that
                                // keeps the surface pinned to its slot so the preview
                                // clips to the letterbox and never overflows the chrome.
                                canvasScale = canvasScale,
                                canvasOffsetX = canvasOffset.x,
                                canvasOffsetY = canvasOffset.y,
                                // Mask tab "Show" → GL-drawn blue tint on the
                                // masked region. Must be GL-side: the SurfaceView
                                // is ZOrderOnTop, so the Compose mask overlay below
                                // renders behind it and isn't visible.
                                showMaskOverlay = isMaskModeActive,
                                // Tint ONLY the in-flight edit layer blue — never
                                // committed layers. The live `maskBitmap` is the
                                // layer being painted/selected; when it's null (new
                                // layer, nothing picked yet) nothing is tinted.
                                overlayMaskRef = maskBitmap,
                                // "Select Luminance" mask has no brush bitmap — it's
                                // generated in-shader from lum params. The in-flight
                                // edit routes to the NEXT layer slot (see updateMacro's
                                // INFLIGHT_MASK_SENTINEL), i.e. index == committed layer
                                // count. Point the overlay tint there so a 2nd/3rd luma
                                // mask tints ITS OWN region, not layer 0's (cross-layer
                                // overlay leak). Clamp to the last usable slot.
                                lumMaskOverlayLayer = if (brushMode == MaskBrushMode.LumaSelect)
                                    committedMaskLayers.size.coerceAtMost(
                                        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                            .RawV3GlSurfaceView.MASK_LAYERS - 1)
                                else -1,
                                subjectMask = v3Masks,
                                cityscapesMasks = cityscapesMasks,
                                depthMap = depthMap,
                                // MUST be null here: [brushMaskLayers] below already
                                // carries committed layers + the in-flight bitmap, and
                                // the preview APPENDS brushMask on top of that list.
                                // The old `committedMaskBitmap ?: maskBitmap` uploaded
                                // the topmost COMMITTED mask a second time as a phantom
                                // "live" layer — so starting a second mask always
                                // showed/edited through the first applied mask's shape.
                                brushMask = null,
                                brushMaskDirty = brushMaskDirtyState,
                                // Multi-layer replay. While painting a NEW mask,
                                // the live brush canvas goes ON TOP of the
                                // already-committed layers (next free slot), so
                                // earlier mask layers keep rendering and the
                                // in-flight edit maps to its own layer. Matches
                                // updateMacro(isMaskEdit=true), which routes the
                                // in-flight adjustments to that same top layer.
                                // NO fresh-session special case here: the texture
                                // list must always match flatten()'s params routing
                                // (inflight → index == committed count). The old
                                // `listOf(maskBitmap)` shortcut put the live bitmap
                                // at texture index 0 while its adjustments landed at
                                // slot N — an index mismatch that applied the edit
                                // through the wrong (or no) mask. "Clean canvas"
                                // during a fresh session is achieved by zeroing the
                                // COMMITTED layers' params (see effectiveParams
                                // above), not by dropping their textures.
                                brushMaskLayers = if (maskBitmap != null)
                                    (committedMaskLayers + maskBitmap).takeLast(4)
                                else
                                    committedMaskLayers,
                                onAhbBound = { ahb -> component.setStageBAhb(ahb) },
                                onImageSize = { w, h ->
                                    if (h > 0) imageAspect = w.toFloat() / h.toFloat()
                                },
                                // Capture the live GL view so the Tone Curves
                                // tab can pull a graded-frame histogram when it
                                // opens. (Rendering pauses on that tab, so we
                                // grab the view ref here during normal editing.)
                                onGradedFrameReady = { v -> glViewForHistogram.value = v },
                                onPauseBackdrop = { bmp ->
                                    pauseGradedBackdrop = bmp
                                    livePreviewRendered = false
                                },
                                onFirstFrameRendered = { livePreviewRendered = true },
                                onBakeStateChange = { req, baked ->
                                    bakeRequestedKey = req
                                    bakeBakedKey = baked
                                },
                                knownSrcWidth = previewDims?.first ?: 0,
                                knownSrcHeight = previewDims?.second ?: 0,
                                // Size the SurfaceView to the exact image
                                // aspect inside the canvas slot. No black
                                // letterbox bands — the panel/chrome
                                // colour shows through the unused area.
                                // Pan/zoom gestures only on the photo itself, not the letterbox.
                                // Fill the WHOLE canvas slot (not the photo-aspect
                                // rect): the renderer letterboxes the photo inside
                                // the surface at fit, and GL-side zoom expands it
                                // into the letterbox bands. No .graphicsLayer — zoom
                                // is GL-side (canvasScale/canvasOffset params above),
                                // so the surface stays pinned to the slot and the
                                // preview is clipped to the slot, never the chrome.
                                modifier = Modifier
                                    .matchParentSize()
                                    .transformable(
                                        state = transformableState,
                                        enabled = !isToneCurvesTab && !isMaskTab &&
                                            !isVignetteCenterMode && !isLensFlareMoveMode && (
                                            !isMaskModeActive ||
                                            brushMode == MaskBrushMode.None),
                                    ),
                            )

                        // Heal overlay — zoom/pan via graphicsLayer so it tracks the GL image.
                        val healOv = healedOverlay
                        if (healOv != null) {
                            androidx.compose.foundation.Image(
                                bitmap = healOv.asImageBitmap(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                // Fill the slot + Fit so this letterboxes exactly like
                                // the GL surface; graphicsLayer zoom matches GL-side zoom.
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer {
                                        scaleX       = canvasScale
                                        scaleY       = canvasScale
                                        translationX = canvasOffset.x
                                        translationY = canvasOffset.y
                                    },
                            )
                        }

                        // Mask overlay ("Show") is drawn GL-side now — see
                        // showMaskOverlay on RawV3PreviewComposable above. The old
                        // Compose Image overlay that lived here could never be seen:
                        // the GL SurfaceView is ZOrderOnTop, so any Compose layer
                        // rendered behind it. It also rebuilt a full-canvas ARGB
                        // bitmap (createBitmap + get/setPixels) on every mask edit.
                        // The renderer unions the active mask layers and tints them
                        // blue directly in the shader, tracking GL pan/zoom for free.
                    }
                }
                is RawPipelineState.Error -> {
                    Text(
                        text     = state.message,
                        color    = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            // Compare button removed from the canvas top-right per request.
            // The hold-to-compare gesture (press-and-hold the canvas) still
            // works for A/B viewing.
        }
    }

    // Panel content — same in both orientations
    @Composable
    fun PanelBox(modifier: Modifier) {
        if (!isPreviewReady) return
        // M12.1+ — Tone Curve graph + Compare + histogram use a real
        // Stage A thumbnail (decoded into a Bitmap by the component on
        // file open). The 1×1 sentinel in `PreviewReady.previewBitmap`
        // is only there for source-compat with v2's PreviewReady shape
        // — we prefer the real thumbnail when available.
        val neutralThumb by component.neutralBitmapFlow.collectAsState()
        // Prefer the live graded snapshot (captured on Tone Curves entry) so the
        // curve widget's base reflects all prior edits like the main canvas;
        // fall back to the ungraded Stage A thumbnail before it's captured.
        val previewBitmap = gradedCurveBitmap
            ?: neutralThumb
            ?: (uiState as? RawPipelineState.PreviewReady)?.previewBitmap
        // Baking runs in the background — panel stays fully interactive.
        // GL preview updates instantly via uniforms; the bake is a background
        // optimization only. Never lock the panel for baking.
        androidx.compose.runtime.CompositionLocalProvider(
            com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                .LocalPanelControlsEnabled provides true,
        ) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surface)
        ) {
    // Graded canvas EXACTLY as shown (PixelCopy at identity zoom, letterbox-cropped,
    // ≤1280 px), with the native graded snapshot as fallback. Shared by the Export
    // hand-off and the project exit (thumbnail refresh) — one capture, no drift.
    val captureGradedCanvas: suspend () -> android.graphics.Bitmap? = {
        val glv = glViewForHistogram.value
        if (glv != null && glv.width > 0 && glv.height > 0) {
            // ── Capture the WHOLE photo, never the user's zoomed view ──
            // The pinch zoom/pan is applied GL-side by scaling the
            // WINDOW viewport (GlesRenderer::renderFrame → setViewTransform),
            // so PixelCopy — which reads those very window pixels — would
            // hand the Export page a zoomed crop, and the centre-crop below
            // (which assumes a letterbox-fit photo) would compound it.
            // Push identity, let one frame land, capture, then restore the
            // gesture so returning to the editor keeps the user's zoom.
            // Compose state (canvasScale/canvasOffset) is deliberately NOT
            // touched: its LaunchedEffect only re-pushes when the values
            // change, so a silent reset here would strand the renderer at
            // identity until the next pinch.
            val zoomed = canvasScale != 1f || canvasOffset != Offset.Zero
            if (zoomed) {
                glv.setViewTransform(1f, 0f, 0f)
                // Same one-frame wait the preset-apply path above uses.
                kotlinx.coroutines.delay(100)
            }
            // PixelCopy approach: grab the SurfaceView's framebuffer
            // EXACTLY as the user sees it — same bytes, same GL
            // state, same bloom / ambiance / curves / masks /
            // everything. The earlier snapshotGradedToBitmap
            // re-rendered through a parallel programSnap_ path that
            // diverged in subtle ways (missing uBloomTex / uBlurTex
            // binds, no Karis pyramid pre-pass, etc.). With
            // PixelCopy the "preview" on the Export page is by
            // definition what was on screen — no second pipeline,
            // no drift possible.
            val src = android.graphics.Bitmap.createBitmap(
                glv.width, glv.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            // PixelCopy THROWS IllegalArgumentException (not a failure code) when
            // the SurfaceView's surface is gone — which it is whenever the view is
            // INVISIBLE behind the Export page (surfaceDestroyed released the
            // renderer). Crashed the save 2026-09-07. Check first, and treat any
            // exception as "not copied" so the fallback below runs.
            val surfaceOk = runCatching { glv.holder.surface?.isValid == true }.getOrDefault(false)
            val copied = if (!surfaceOk) false else
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    runCatching {
                        android.view.PixelCopy.request(
                            glv,
                            src,
                            { result ->
                                cont.resume(result == android.view.PixelCopy.SUCCESS) {}
                            },
                            android.os.Handler(android.os.Looper.getMainLooper()),
                        )
                    }.onFailure { cont.resume(false) {} }
                }
            // Restore the gesture immediately — the fallback snapshot
            // path below renders its own full frame and is unaffected.
            if (zoomed) glv.setViewTransform(canvasScale, canvasOffset.x, canvasOffset.y)
            val graded: android.graphics.Bitmap? = if (copied) {
                // PixelCopy grabbed the WHOLE SurfaceView, which is usually a
                // different aspect than the photo, so the image sits letterboxed
                // with bars. Crop to the centered photo-content rect (photo AR =
                // imageAspect) so downstream — the Export/Watermark preview and
                // the burned EXIF watermark — sees ONLY the photo and never
                // anchors into a bar (that was the "EXIF overflow" on portraits).
                val vw = src.width; val vh = src.height
                val viewAspect = vw.toFloat() / vh.toFloat()
                val cw: Int; val ch: Int
                if (viewAspect > imageAspect) { ch = vh; cw = (vh * imageAspect).toInt().coerceIn(1, vw) }
                else { cw = vw; ch = (vw / imageAspect).toInt().coerceIn(1, vh) }
                val photoSrc = if (cw < vw || ch < vh) {
                    val cx = ((vw - cw) / 2).coerceAtLeast(0)
                    val cy = ((vh - ch) / 2).coerceAtLeast(0)
                    val c = android.graphics.Bitmap.createBitmap(src, cx, cy, cw, ch)
                    if (c !== src) src.recycle()
                    c
                } else src
                // Downscale to a reasonable preview size to keep memory bounded;
                // the Export page doesn't need a 4K Bitmap for its preview panel.
                val longSide = 1280
                val scale = longSide.toFloat() / maxOf(photoSrc.width, photoSrc.height)
                if (scale < 1f) {
                    val tw = (photoSrc.width * scale).toInt().coerceAtLeast(1)
                    val th = (photoSrc.height * scale).toInt().coerceAtLeast(1)
                    val resized = android.graphics.Bitmap.createScaledBitmap(
                        photoSrc, tw, th, true,
                    )
                    if (resized !== photoSrc) photoSrc.recycle()
                    resized
                } else photoSrc
            } else {
                src.recycle()
                // PixelCopy failed — fall back to the native graded GL
                // snapshot. It can differ from the on-screen pixels in
                // subtle bloom/blur ways, but it is still fully GRADED
                // (WB / tint / saturation / LUT applied), so the Export
                // page and the Verify harness never silently degrade to
                // the UNGRADED Stage B/neutral preview — the source of
                // the bogus ~60 colour MAD.
                val longSide = 1280
                val sw = if (imageAspect >= 1f) longSide
                         else (longSide * imageAspect).toInt()
                val sh = if (imageAspect >= 1f) (longSide / imageAspect).toInt()
                         else longSide
                // Also needs a live EGL surface (eglMakeCurrent on it) — null when
                // the view is hidden; callers keep whatever preview they had.
                withContext(Dispatchers.Default) {
                    runCatching {
                        glv.snapshotGradedToBitmap(
                            longSide, sw.coerceAtLeast(1), sh.coerceAtLeast(1),
                        )
                    }.getOrNull()
                }
            }
            graded
        } else {
            // No GL view to capture from — leave null so the Verify
            // harness hard-fails clearly instead of comparing the
            // ungraded fallback.
            null
        }
    }
    SideEffect { projectExitCapture.value = captureGradedCanvas }

    val cancelCurrentAction = {
        deltaMacro = UserMacro(); lightAeActive = false
        maskJob?.cancel(); maskJob = null
        component.masking.updateMask(null)
        setIncludedMaskClasses(emptySet())
        setPrimaryMaskClass(null)
        setPrimaryIsLuma(false)
        setPrimaryIsChroma(false)
        setChromaSubtractMode(false)
        setMaskColorSamples(emptyList())
        setIsMaskModeActive(false)
        setBrushMode(MaskBrushMode.None)
    }

    RawEditorPanel(
        modifier = modifier,
        component = component,
        uiState = uiState,
        previewBitmap = previewBitmap,
        gradedHistogram = gradedHistogram,
        deltaMacro = deltaMacro,
        onMacroChange = { deltaMacro = it },
        actions = actions,
        presets = presets,
        onApplyAction = { label, tabIndex, currentDelta ->
            val hasMaskEdit = currentDelta.maskBrightness != 0f ||
                    currentDelta.maskContrast != 0f ||
                    currentDelta.maskTemperature != 0 ||
                    currentDelta.maskTint != 0f ||
                    currentDelta.maskSaturation != 0f ||
                    currentDelta.maskClarity != 0f ||
                    currentDelta.maskSharpness != 0f ||
                    currentDelta.maskTone.highlights != 0f ||
                    currentDelta.maskTone.shadows != 0f ||
                    currentDelta.maskTone.whites != 0f ||
                    currentDelta.maskTone.blacks != 0f
            val isEmptyMacro = currentDelta == UserMacro()
            if (isEmptyMacro) {
                deltaMacro = UserMacro()
            } else {
                val currentMask = maskBitmap
                val classesForCard = includedMaskClasses.toList()
                val currentNodes = component.masking.maskNodes.value
                val currentPrimary = primaryMaskClass
                val newAction = if (hasMaskEdit && currentMask != null) {
                    val newId = java.util.UUID.randomUUID().toString()
                    val savedPath = com.RAZStudio.StudioRoom.feature.photo_editor.raw
                        .RawMaskStorage.save(context, newId, currentMask)
                    if (savedPath != null) {
                        android.util.Log.d("MaskInstance", """
                            CREATE/APPLY ----------------
                            ActionId=$newId
                            PrimaryMaskClass=$currentPrimary
                            IncludedClasses=$classesForCard
                            MaskNodeCount=${currentNodes.size}
                            MaskNodeIds=${currentNodes.joinToString { it.id }}
                        """.trimIndent())
                        RawAction(
                            id = newId, label = label, tabIndex = tabIndex,
                            macro = currentDelta, maskPath = savedPath,
                            maskClass = currentPrimary,
                            maskClasses = classesForCard,
                            maskNodes = currentNodes,
                        )
                    } else {
                        RawAction(label = label, tabIndex = tabIndex, macro = currentDelta)
                    }
                } else {
                    RawAction(
                        label          = label,
                        tabIndex       = tabIndex,
                        macro          = currentDelta,
                        isAutoExposure = tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor
                            .presentation.raw.components.TAB_LIGHT && lightAeActive,
                    )
                }
                if (tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor
                        .presentation.raw.components.TAB_LIGHT) {
                    lightAeActive = false
                }
                if (hasMaskEdit) {
                    component.masking.updateMask(null)
                    setIncludedMaskClasses(emptySet())
                    setPrimaryMaskClass(null)
                    setPrimaryIsLuma(false)
                    setPrimaryIsChroma(false)
                    setChromaSubtractMode(false)
                    setMaskColorSamples(emptyList())
                    setIsMaskModeActive(false)
                }
                component.addAction(newAction)
                scope.launch { component.pushSidecarRevision() }
                deltaMacro = UserMacro()
            }
        },
        onCancelAction = cancelCurrentAction,
        onLoadAction = { action ->
            deltaMacro = action.macro
            if (action.isAutoExposure) lightAeActive = true

            // Strictly graph-driven: do not manually load maskPath.
            // restoreMaskState below triggers the graph listener which
            // re-evaluates the composite (including StoredBitmap nodes).
            setIsMaskModeActive(action.maskNodes.isNotEmpty() ||
                (action.tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor
                    .presentation.raw.components.TAB_MASK_LAYERS))

            if (action.tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor
                    .presentation.raw.components.TAB_MASK_LAYERS) {
                val hasLuma = action.macro.maskLumSpread > 0f
                if (hasLuma) setBrushMode(MaskBrushMode.LumaSelect)
            }

            // Restore semantic identity: nodes + class metadata (Fix 1, 2, 3)
            android.util.Log.d("MaskInstance", """
                RESTORE (Graph Driven) ----------------
                ActionId=${action.id}
                MaskNodeCount=${action.maskNodes.size}
            """.trimIndent())
            component.masking.restoreMaskState(
                nodes = action.maskNodes,
                primary = action.maskClass,
                included = action.maskClasses.toSet(),
                isLuma = action.macro.maskLumSpread > 0f && action.maskPath == null,
                isChroma = false // TODO: store chroma identity if needed
            )
            setChromaSubtractMode(false)
        },
        onRestoreAction = { idx, action -> component.restoreActionAt(idx, action) },
        onDeleteAction = { id, keepStorage -> component.deleteAction(id, keepStorage) },
        onEyeToggleAction = { id -> component.toggleEye(id) },
        onToggleLockAction = { id -> component.toggleLock(id) },
        onSavePreset = { name ->
            val ok = component.savePreset(name)
            if (ok) presets = component.loadPresetIndex()
            ok
        },
        onLoadPreset = { index ->
            val loaded = component.loadPreset(index) ?: return@RawEditorPanel
            // Convert legacy presets (maskClasses only) to graph-driven (maskNodes).
            val upgraded = loaded.map { a ->
                if (a.maskNodes.isEmpty() && (a.maskClasses.isNotEmpty() || a.maskClass != null)) {
                    val classes = a.maskClasses.ifEmpty { listOfNotNull(a.maskClass) }
                    a.copy(maskNodes = classes.map { cls ->
                        MaskNode(
                            id = java.util.UUID.randomUUID().toString(),
                            source = MaskSource.ModelClass(cls),
                            operation = MaskOp.ADD
                        )
                    })
                } else a
            }
            component.replaceActions(upgraded)
            deltaMacro = UserMacro()
            // No imperative mask generation here. rebuildShaderParams ->
            // publishTopmostMaskBitmap -> topmostMaskAction -> restoreMaskState
            // handle it purely through the graph.
        },
        onDeletePreset = { index ->
            component.deletePreset(index)
            presets = component.loadPresetIndex()
        },
        onExportDebugMap = { type ->
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ).resolve("SR_Debug")
            dir.mkdirs()
            val name = when (type) {
                0 -> "blend"
                1 -> "edge"
                2 -> "texture"
                3 -> "noise"
                4 -> "highlights"
                else -> "unknown"
            }
            val timestamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
            val path = dir.resolve("${name}_$timestamp.png").absolutePath
            val ok = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine.exportDebugMap(type, path)
            scope.launch {
                snackbarHostState.showSnackbar(if (ok) "Exported $name map to Documents/SR_Debug" else "Export failed for $name map")
            }
        },
        onExportToEditor = {
            component.updateMacro(deltaMacro)
            scope.launch {
                if (presetApplyInFlight) {
                    while (presetApplyInFlight) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                kotlinx.coroutines.delay(100)
                val graded = captureGradedCanvas()
                component.setGradedPreview(graded)
                if (graded != null && component.projectContext != null) {
                    component.persistProjectThumbnail(graded)
                }
                component.navigateToRawExport()
            }
            Unit
        }.also { exportHandler.value = it },
        onExportActions = {
            scope.launch(Dispatchers.IO) {
                val path = runCatching { component.prepareExportFile() }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (path != null) {
                        pendingExportFilePath = path
                        actionsExportSavePicker.launch("raw_actions.xml")
                    }
                }
            }
        },
        onImportActions = { actionsImportPicker.launch(arrayOf("text/xml", "application/xml")) },
        onTabSelected = { tabIndex ->
            isToneCurvesTab = tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.TAB_CURVES_LUT
            val nowMask = tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.TAB_MASK_LAYERS
            isMaskTab = nowMask
            if (isToneCurvesTab || nowMask) {
                canvasScale  = 1f
                canvasOffset = androidx.compose.ui.geometry.Offset.Zero
            }
            glViewForHistogram.value?.requestRender()
            if (nowMask) {
                component.gradingPipeline.suppressBake(
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.SuppressReason.MASK_TAB
                )
            } else {
                val dims = component.previewDimsFlow.value
                component.gradingPipeline.resumeBake(
                    params          = component.shaderParamsFlow.value,
                    macro           = component.composedMacroFlow.value,
                    stageATifPath   = component.stageATifPathFlow.value ?: "",
                    lutCubePath     = component.lutCubePathFlow.value,
                    brushMaskLayers = emptyList(),
                    toneCurveLut    = component.toneCurveLutFlow.value,
                    knownSrcWidth   = dims?.first ?: 0,
                    knownSrcHeight  = dims?.second ?: 0,
                )
            }
        },
        isVignetteCenterMode = isVignetteCenterMode,
        onVignetteCenterModeChange = {
            isVignetteCenterMode = it
            if (it) isLensFlareMoveMode = false
        },
        isLensFlareMoveMode = isLensFlareMoveMode,
        onLensFlareMoveModeChange = {
            isLensFlareMoveMode = it
            if (it) isVignetteCenterMode = false
        },
        sceneAutoEnhanceEnabled = sceneAutoEnhanceEnabled,
        onSceneAutoEnhanceChange = { enabled -> component.setAiColorEnhance(enabled) },
        onUndoLastHeal = { component.healing.undoHeal() },
        onHealApply = {
            val count = healCount - component.healing.healCountPreEdit.value
            val label = "Heal" + if (count > 1) " ×$count" else ""
            component.addAction(
                RawAction(
                    label = label,
                    tabIndex = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.TAB_HEAL,
                    macro = UserMacro(),
                )
            )
            component.healing.applyHeal()
        },
        onHealCancel = { component.healing.cancelHeal() },
        onSaveEditAsLut = { name -> component.exportEditAsLut(name) },
        onMaskModeActive = { setIsMaskModeActive(it) },
        onShowMaskOverlayChange = { newShow ->
            setIsMaskModeActive(newShow)
            if (!newShow) setBrushMode(MaskBrushMode.None)
        },
        onBrushModeChange = { setBrushMode(it) },
        onSharpSpreadChange = { setSharpSpread(it) },
        onFillSharp = {
            android.util.Log.d("MaskInstance", "GRAPH ADD SharpSubject spread=$sharpSpread")
            component.masking.pushMaskNode(
                MaskNode(
                    id = java.util.UUID.randomUUID().toString(),
                    source = MaskSource.SharpSubject(sharpSpread),
                    operation = MaskOp.ADD
                )
            )
            setIsMaskModeActive(true)
        },
        onSegmentationNeeded = { component.ensureSegmentation() },
        subjectSegBusy = subjectSegBusy,
        subjectDetected = segmentationMasksV3OuterScope?.hasSubject ?: true,
        imageLongSide = previewDimsForAspect?.let { maxOf(it.first, it.second) }
            ?: CinematicBloomProcessor.REF_LONG_SIDE,
        onLightAuto = {
            val src = component.neutralBitmap ?: return@RawEditorPanel
            component.ensureSegmentation()
            val masks = component.masking.segmentationMasks.value
            val protection = component.composeTabMacro(
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.TAB_TONE_COLOR
            ).aeSubjectProtection
            scope.launch(Dispatchers.Default) {
                val result = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                    .RawAutoExposure.analyse(
                        bitmap = src,
                        base   = UserMacro(aeSubjectProtection = protection),
                        masks  = masks,
                        iso    = component.rawMetadata?.iso ?: 0,
                        subjectProtection = protection,
                    )
                withContext(Dispatchers.Main) {
                    val aeAction = RawAction(
                        label          = "Tone · AI Expose On",
                        tabIndex       = com.RAZStudio.StudioRoom.feature.photo_editor
                            .presentation.raw.components.TAB_TONE_COLOR,
                        macro          = result,
                        isAutoExposure = true,
                    )
                    component.addAction(aeAction)
                    scope.launch { component.pushSidecarRevision() }
                    lightAeActive = true
                }
            }
        },
        onLightAutoOff = {
            actions.lastOrNull { it.isAutoExposure }?.let { component.deleteAction(it.id) }
            lightAeActive = false
        },
        onLightBasicAuto = {
            val src = component.neutralBitmap ?: return@RawEditorPanel
            scope.launch(Dispatchers.Default) {
                val result = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                    .RawAutoExposure.analyse(
                        bitmap = src,
                        base   = UserMacro(),
                        masks  = null,
                        iso    = 0,
                    )
                withContext(Dispatchers.Main) {
                    component.addAction(
                        RawAction(
                            label          = "Tone · Basic Auto On",
                            tabIndex       = com.RAZStudio.StudioRoom.feature.photo_editor
                                .presentation.raw.components.TAB_TONE_COLOR,
                            macro          = result,
                            isAutoExposure = true,
                        )
                    )
                    lightAeActive = true
                }
            }
        },
        lightAutoEnabled = component.neutralBitmap != null,
        lightAeActive = lightAeActive,
        onLightAeProtectionChange = { prot ->
            val src = component.neutralBitmap ?: return@RawEditorPanel
            component.ensureSegmentation()
            val masks = component.masking.segmentationMasks.value
            scope.launch(Dispatchers.Default) {
                val result = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                    .RawAutoExposure.analyse(
                        bitmap = src,
                        base   = UserMacro(aeSubjectProtection = prot),
                        masks  = masks,
                        iso    = component.rawMetadata?.iso ?: 0,
                        subjectProtection = prot,
                    )
                withContext(Dispatchers.Main) {
                    component.addAction(
                        RawAction(
                            label          = "Tone · AI Expose On",
                            tabIndex       = com.RAZStudio.StudioRoom.feature.photo_editor
                                .presentation.raw.components.TAB_TONE_COLOR,
                            macro          = result,
                            isAutoExposure = true,
                        )
                    )
                    lightAeActive = true
                }
            }
        },
        isToneCurvesTab = isToneCurvesTab,
        isMaskTab = isMaskTab,
        brushSize = brushSize,
        onBrushSize = { setBrushSize(it) },
        brushIntensity = brushIntensity,
        onBrushIntensity = { setBrushIntensity(it) },
        brushFeather = brushFeather,
        onBrushFeather = { setBrushFeather(it) },
        hasMask = maskBitmap != null,
        onClearMask = {
            maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
            component.masking.updateMask(null)
            setMaskColorSamples(emptyList())
            // Also deactivate a luminance-range mask (GPU, no bitmap) and
            // reset the luma↔bitmap carve combine mode.
            deltaMacro = deltaMacro.copy(
                maskLumTarget = 0f, maskLumSpread = 0f, maskLumFeather = 0f, maskLumCombine = 0,
            )
            setIncludedMaskClasses(emptySet())
            setPrimaryMaskClass(null)
            // A clear wipes the selection outright — the op graph resets with it.
            component.masking.clearMaskGraph()
        },
        onFillSubject = {
            val isPrime = primaryMaskClass == null &&
                !primaryIsLuma && !primaryIsChroma &&
                deltaMacro.maskLumSpread <= 0f && maskColorSamples.isEmpty()
            if (isPrime) {
                setMaskColorSamples(emptyList())
                setChromaSubtractMode(false)
                deltaMacro = deltaMacro.copy(
                    maskLumTarget = 0f, maskLumSpread = 0f, maskLumFeather = 0f, maskLumCombine = 0,
                )
                setPrimaryIsLuma(false)
                setPrimaryIsChroma(false)
                if (brushMode == MaskBrushMode.ColorSelect || brushMode == MaskBrushMode.LumaSelect)
                    setBrushMode(MaskBrushMode.None)

                setPrimaryMaskClass(MaskClass.Subject)
                component.masking.clearMaskGraph()
            } else if (deltaMacro.maskLumSpread > 0f) {
                deltaMacro = deltaMacro.copy(maskLumCombine = 3) // union with luma band
            }

            android.util.Log.d("MaskInstance", "GRAPH ADD Subject: isPrime=$isPrime")
            setIncludedMaskClasses(includedMaskClasses + MaskClass.Subject)
            component.masking.pushMaskNode(
                MaskNode(
                    id = java.util.UUID.randomUUID().toString(),
                    source = MaskSource.ModelClass(MaskClass.Subject),
                    operation = MaskOp.ADD,
                )
            )
            setIsMaskModeActive(true)
        },
        // Background = everything EXCEPT the subject (invert-of-subject):
        // "select all but the person" in one tap.
        onFillBackground = {
            val isPrime = primaryMaskClass == null &&
                !primaryIsLuma && !primaryIsChroma &&
                deltaMacro.maskLumSpread <= 0f && maskColorSamples.isEmpty()
            if (isPrime) {
                setMaskColorSamples(emptyList())
                setChromaSubtractMode(false)
                deltaMacro = deltaMacro.copy(
                    maskLumTarget = 0f, maskLumSpread = 0f, maskLumFeather = 0f, maskLumCombine = 0,
                )
                setPrimaryIsLuma(false)
                setPrimaryIsChroma(false)
                if (brushMode == MaskBrushMode.ColorSelect || brushMode == MaskBrushMode.LumaSelect)
                    setBrushMode(MaskBrushMode.None)

                setPrimaryMaskClass(MaskClass.Background)
                component.masking.clearMaskGraph()
            } else if (deltaMacro.maskLumSpread > 0f) {
                deltaMacro = deltaMacro.copy(maskLumCombine = 3)
            }

            android.util.Log.d("MaskInstance", "GRAPH ADD Background: isPrime=$isPrime")
            setIncludedMaskClasses(includedMaskClasses + MaskClass.Background)
            component.masking.pushMaskNode(
                MaskNode(
                    id = java.util.UUID.randomUUID().toString(),
                    source = MaskSource.ModelClass(MaskClass.Background),
                    operation = MaskOp.ADD,
                )
            )
            setIsMaskModeActive(true)
        },
        onRemoveSubject = {
            if (primaryMaskClass == MaskClass.Subject) {
                // Removing the prime — wipe the whole mask and reset.
                maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                component.masking.updateMask(null)
                setIncludedMaskClasses(emptySet())
                setPrimaryMaskClass(null)
                component.masking.clearMaskGraph()
            } else {
                android.util.Log.d("MaskInstance", "GRAPH SUBTRACT Subject")
                component.masking.pushMaskNode(
                    MaskNode(
                        id = java.util.UUID.randomUUID().toString(),
                        source = MaskSource.ModelClass(MaskClass.Subject),
                        operation = MaskOp.SUBTRACT,
                    )
                )
                setIncludedMaskClasses(includedMaskClasses - MaskClass.Subject)
                if (deltaMacro.maskLumSpread > 0f) {
                    deltaMacro = deltaMacro.copy(maskLumCombine = 1) // luma − bitmap
                }
            }
        },
        onRemoveBackground = {
            if (primaryMaskClass == MaskClass.Background) {
                maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                component.masking.updateMask(null)
                setIncludedMaskClasses(emptySet())
                setPrimaryMaskClass(null)
                component.masking.clearMaskGraph()
            } else {
                android.util.Log.d("MaskInstance", "GRAPH SUBTRACT Background")
                component.masking.pushMaskNode(
                    MaskNode(
                        id = java.util.UUID.randomUUID().toString(),
                        source = MaskSource.ModelClass(MaskClass.Background),
                        operation = MaskOp.SUBTRACT,
                    )
                )
                setIncludedMaskClasses(includedMaskClasses - MaskClass.Background)
                if (deltaMacro.maskLumSpread > 0f) {
                    deltaMacro = deltaMacro.copy(maskLumCombine = 1)
                }
            }
        },
        onFillDetectedClass = { cls ->
            val isPrime = primaryMaskClass == null &&
                !primaryIsLuma && !primaryIsChroma &&
                deltaMacro.maskLumSpread <= 0f && maskColorSamples.isEmpty()
            if (isPrime) {
                setMaskColorSamples(emptyList())
                setChromaSubtractMode(false)
                deltaMacro = deltaMacro.copy(
                    maskLumTarget = 0f, maskLumSpread = 0f, maskLumFeather = 0f, maskLumCombine = 0,
                )
                setPrimaryIsLuma(false)
                setPrimaryIsChroma(false)
                if (brushMode == MaskBrushMode.ColorSelect || brushMode == MaskBrushMode.LumaSelect)
                    setBrushMode(MaskBrushMode.None)

                setPrimaryMaskClass(cls)
                component.masking.clearMaskGraph()
            } else if (deltaMacro.maskLumSpread > 0f) {
                deltaMacro = deltaMacro.copy(maskLumCombine = 3)
            }

            android.util.Log.d("MaskInstance", "GRAPH ADD $cls: isPrime=$isPrime")
            setIncludedMaskClasses(includedMaskClasses + cls)
            component.masking.pushMaskNode(
                MaskNode(
                    id = java.util.UUID.randomUUID().toString(),
                    source = MaskSource.ModelClass(cls),
                    operation = MaskOp.ADD,
                )
            )
            setIsMaskModeActive(true)
        },
        onRemoveDetectedClass = { cls ->
            if (primaryMaskClass == cls) {
                maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                component.masking.updateMask(null)
                setIncludedMaskClasses(emptySet())
                setPrimaryMaskClass(null)
                component.masking.clearMaskGraph()
            } else {
                android.util.Log.d("MaskInstance", "GRAPH SUBTRACT $cls")
                component.masking.pushMaskNode(
                    MaskNode(
                        id = java.util.UUID.randomUUID().toString(),
                        source = MaskSource.ModelClass(cls),
                        operation = MaskOp.SUBTRACT,
                    )
                )
                setIncludedMaskClasses(includedMaskClasses - cls)
                if (deltaMacro.maskLumSpread > 0f) {
                    deltaMacro = deltaMacro.copy(maskLumCombine = 1)
                }
            }
        },
        primaryIsLuma = primaryIsLuma,
        primaryIsChroma = primaryIsChroma,
        onInvertMask = {
            // Luminance mask has no bitmap — invert by baking the COMPLEMENT
            // of the tone band into a mask bitmap (then it behaves like any
            // other mask). Otherwise invert the existing bitmap (brush/chroma).
            if (deltaMacro.maskLumSpread > 0f) {
                bakeInvertedLuminance(deltaMacro.maskLumTarget, deltaMacro.maskLumSpread, deltaMacro.maskLumFeather)
            } else {
                invertMask()
            }
            setIncludedMaskClasses(emptySet())
            setPrimaryMaskClass(null)
            // Inversion is not expressible as an ADD/SUBTRACT node; reset the
            // graph so a later undo can't resurrect the pre-invert composite.
            component.masking.clearMaskGraph()
        },
        onAddLuma = {
            val hasBitmapBase = maskBitmap != null ||
                includedMaskClasses.isNotEmpty() || primaryMaskClass != null
            val combine = when {
                hasBitmapBase -> 3 // union: max(luma, bitmap)
                else -> 0
            }
            val spread = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumSpread else 0.5f
            val target = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumTarget else 0.5f
            val feather = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumFeather else 0.12f
            deltaMacro = deltaMacro.copy(
                maskLumSpread = spread,
                maskLumTarget = target,
                maskLumFeather = feather,
                maskLumCombine = combine,
            )
            if (!hasBitmapBase) setPrimaryIsLuma(true)
            setIsMaskModeActive(true)
            setBrushMode(MaskBrushMode.LumaSelect)
        },
        onRemoveLuma = {
            val hasBitmapBase = maskBitmap != null ||
                includedMaskClasses.isNotEmpty() || primaryMaskClass != null
            if (primaryIsLuma && !hasBitmapBase) {
                deltaMacro = deltaMacro.copy(
                    maskLumTarget = 0f, maskLumSpread = 0f, maskLumFeather = 0f, maskLumCombine = 0,
                )
                setPrimaryIsLuma(false)
                setBrushMode(MaskBrushMode.None)
                if (maskBitmap == null) cancelCurrentAction()
            } else {
                val spread = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumSpread else 0.5f
                val target = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumTarget else 0.5f
                val feather = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumFeather else 0.12f
                deltaMacro = deltaMacro.copy(
                    maskLumSpread = spread,
                    maskLumTarget = target,
                    maskLumFeather = feather,
                    maskLumCombine = 2, // bitmap − luma
                )
                setPrimaryIsLuma(false)
                setIsMaskModeActive(true)
                setBrushMode(MaskBrushMode.LumaSelect)
            }
        },
        onAddChroma = {
            val hasBitmapBase = maskBitmap != null ||
                includedMaskClasses.isNotEmpty() || primaryMaskClass != null ||
                deltaMacro.maskLumSpread > 0f
            if (!hasBitmapBase) setPrimaryIsChroma(true)
            setChromaSubtractMode(false)
            setIsMaskModeActive(true)
            setBrushMode(MaskBrushMode.ColorSelect)
        },
        onRemoveChroma = {
            val hasBitmapBase = maskBitmap != null ||
                includedMaskClasses.isNotEmpty() || primaryMaskClass != null ||
                deltaMacro.maskLumSpread > 0f
            if (primaryIsChroma && !hasBitmapBase) {
                setMaskColorSamples(emptyList())
                setPrimaryIsChroma(false)
                setChromaSubtractMode(false)
                setBrushMode(MaskBrushMode.None)
            } else {
                setChromaSubtractMode(true)
                setPrimaryIsChroma(false)
                setIsMaskModeActive(true)
                setBrushMode(MaskBrushMode.ColorSelect)
            }
        },
        onColorToleranceChange = { setMaskColorTolerance(it) },
        onClearColorSamples = { setMaskColorSamples(emptyList()) },
        isFullResReady = fullResReady,
        isFullResProcessing = fullResProcessing,
    )

        } // Box
        } // CompositionLocalProvider
    }

    // ── Idle full-res canvas upgrade ──────────────────────────────────────────
    // Swap the canvas to the full-res render after 1 second of no adjustments.
    // Live mask fields overlay the merged result so the idle render reflects
    // the user's currently-painted mask edit.
    val mergedMacro = baselineMacro.mergeWith(deltaMacro).copy(
        maskBrightness  = deltaMacro.maskBrightness,
        maskContrast    = deltaMacro.maskContrast,
        maskTemperature = deltaMacro.maskTemperature,
        maskTint        = deltaMacro.maskTint,
        maskSaturation  = deltaMacro.maskSaturation,
        maskClarity     = deltaMacro.maskClarity,
        maskSharpness   = deltaMacro.maskSharpness,
        maskTone        = deltaMacro.maskTone,
    )

    // Bokeh is now rendered live in the GL uber-shader (FBO blur + subject-
    // mask composite, driven by the bokeh macro params through
    // mapMacroToShaderParams). No CPU proxy overlay is needed for preview.

    LaunchedEffect(mergedMacro) {
        // Any macro change → immediately drop the cached full-res bitmap so the
        // canvas snaps back to the live preview without waiting.
        component.clearIdleFullRes()
        // After ~2.5 seconds of stability, kick off the low-priority full-res render.
        // The previous 1 s delay caused jank when the user was making rapid micro-
        // adjustments — each release would race a heavy 8-bit full-res pass that
        // then got cancelled on the next tick. Tuning up makes the canvas snappier
        // and avoids burning CPU on renders the user immediately invalidates.
        if (fullResReady) {
            kotlinx.coroutines.delay(2500L)
            component.renderIdleFullRes(mergedMacro)
        }
    }

    // Fires whenever fullResOutputPath changes — both on the early preview-quality
    // emit (~15s after open) and on the final full-res quality emit (~30s).
    // This ensures the canvas upgrades as soon as any output is available, not only
    // after the full 30-second pipeline completes.
    LaunchedEffect(fullResOutputPath) {
        if (fullResOutputPath != null) {
            component.renderIdleFullRes(mergedMacro)
        }
    }

    // ── Full-res compare render ───────────────────────────────────────────────
    val compareState by component.compareState.collectAsState()

    // When sheet opens or macro changes while open → trigger a fresh full-res render.
    LaunchedEffect(showCompareSheet, mergedMacro) {
        if (showCompareSheet && fullResReady) {
            component.renderForCompare(mergedMacro)
        } else if (!showCompareSheet) {
            component.cancelCompareRender()
        }
    }

    val compareData: Pair<android.graphics.Bitmap?, android.graphics.Bitmap?>? =
        when (val cs = compareState) {
            is CompareRenderState.Ready -> cs.original to cs.rendered
            else -> null to null  // sheet stays open, BeforeAfterLayout shows nothing until ready
        }
    val compareRendering = compareState is CompareRenderState.Rendering

    CompareSheet(
        data    = compareData,
        visible = showCompareSheet,
        onDismiss = {
            showCompareSheet = false
            component.cancelCompareRender()
        },
    )

    // Loading overlay shown inside the sheet area while compare render is in progress
    if (showCompareSheet && compareRendering) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (isPreviewReady) {
                EnhancedFloatingActionButton(
                    onClick = if (fullResReady && !isFullResProcessing) {
                        { exportHandler.value?.invoke() }
                    } else null,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.IosShare,
                        contentDescription = stringResource(R.string.raw_export_save),
                    )
                }
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val isStartPage = uiState is RawPipelineState.Idle ||
                        uiState is RawPipelineState.AwaitingWorkspaceChoice
                    Text(
                        stringResource(
                            if (isStartPage) R.string.raw_editor_start_page
                            else R.string.raw_editor
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { confirmExit() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // v2-integration §2.1 — info button surfaces the full EXIF panel.
                    // Hidden when no EXIF is available (typical reasons: non-RAW source,
                    // probe still running, or LibRaw couldn't parse the file).
                    if (exifInfo != null) {
                        IconButton(onClick = { showExifSheet = true }) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = "Photo info",
                            )
                        }
                    }
                    // v2-integration §2.2 — sidecar history. Visible only on RAW sources
                    // (proxy: EXIF probe succeeded) and when the editor is past the
                    // workspace-selector stage (preview ready).
                    if (exifInfo != null && isPreviewReady) {
                        IconButton(onClick = { showHistorySheet = true }) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = "Edit history",
                            )
                        }
                    }
                    // Preset overflow menu — the header bookmark icon (Save as
                    // preset… / Apply preset…) was removed per owner request
                    // (2026-08-29); preset save/apply lives in the Actions tab.
                    // The menu body is kept but unreachable (showPresetMenu can no
                    // longer be set true), so it renders nothing and the debug
                    // verify-harness items stay available only if re-triggered.
                    if (false && isPreviewReady) {
                        DropdownMenu(
                            expanded = showPresetMenu,
                            onDismissRequest = { showPresetMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save as preset…") },
                                onClick = {
                                    showPresetMenu = false
                                    showPresetExportDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Apply preset…") },
                                onClick = {
                                    showPresetMenu = false
                                    presetEntries = com.RAZStudio.StudioRoom
                                        .feature.photo_editor.raw.preset
                                        .RazPresetStore.list(context)
                                    showPresetPickerDialog = true
                                },
                            )
                            // Phase 4 — drift verification harness. Debug-
                            // only because the CPU stageCToBitmap render
                            // takes a few seconds at full Stage A dims,
                            // and the comparison output is a developer
                            // diagnostic, not a user feature.
                            //
                            // Gated on BuildConfig.DEBUG (not the in-app
                            // debug-mode toggle) because the toggle isn't
                            // registered into any SettingsGroup, so users
                            // can't reach it without code. BuildConfig.DEBUG
                            // is true on every debug APK we install via adb
                            // and false on market builds.
                            if (com.RAZStudio.StudioRoom.feature.photo_editor
                                .BuildConfig.DEBUG) {
                                // Phase 3 Checkpoint 1 — headless GL
                                // smoke test. Confirms the pbuffer EGL
                                // pipeline reaches a writable bitmap.
                                // Expected: bitmap fully red.
                                DropdownMenuItem(
                                    text = { Text("GL save smoke test (red)") },
                                    onClick = {
                                        showPresetMenu = false
                                        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                                            val bmp = android.graphics.Bitmap
                                                .createBitmap(256, 256,
                                                    android.graphics.Bitmap.Config.ARGB_8888)
                                            val ok = com.RAZStudio.StudioRoom
                                                .feature.photo_editor.raw_v3
                                                .RawV3Engine.offscreenClearTest(bmp)
                                            val outDir = java.io.File(
                                                context.externalCacheDir ?: context.cacheDir,
                                                "verify",
                                            ).apply { if (!exists()) mkdirs() }
                                            val out = java.io.File(outDir,
                                                "gl_smoke_${System.currentTimeMillis()}.png")
                                            runCatching {
                                                java.io.FileOutputStream(out).use {
                                                    bmp.compress(android.graphics
                                                        .Bitmap.CompressFormat.PNG, 100, it)
                                                }
                                            }
                                            // Sample center pixel to verify red.
                                            val center = bmp.getPixel(128, 128)
                                            val r = (center ushr 16) and 0xFF
                                            val g = (center ushr 8)  and 0xFF
                                            val b =  center          and 0xFF
                                            bmp.recycle()
                                            kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.Main,
                                            ) {
                                                snackbarHostState.showSnackbar(
                                                    "GL smoke: ok=$ok center=($r,$g,$b) -> ${out.name}",
                                                    duration = androidx.compose
                                                        .material3.SnackbarDuration.Long,
                                                )
                                            }
                                        }
                                    },
                                )
                                // Phase 3 Checkpoint 2 — UV gradient
                                // shader compile/draw smoke test. Expect
                                // smooth red-green gradient in saved PNG.
                                DropdownMenuItem(
                                    text = { Text("GL save smoke test (UV)") },
                                    onClick = {
                                        showPresetMenu = false
                                        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                                            val bmp = android.graphics.Bitmap
                                                .createBitmap(256, 256,
                                                    android.graphics.Bitmap.Config.ARGB_8888)
                                            val ok = com.RAZStudio.StudioRoom
                                                .feature.photo_editor.raw_v3
                                                .RawV3Engine.offscreenUvTest(bmp)
                                            val outDir = java.io.File(
                                                context.externalCacheDir ?: context.cacheDir,
                                                "verify",
                                            ).apply { if (!exists()) mkdirs() }
                                            val out = java.io.File(outDir,
                                                "gl_uv_${System.currentTimeMillis()}.png")
                                            runCatching {
                                                java.io.FileOutputStream(out).use {
                                                    bmp.compress(android.graphics
                                                        .Bitmap.CompressFormat.PNG, 100, it)
                                                }
                                            }
                                            // Top-right corner expected ~ (255,255,128).
                                            val tr = bmp.getPixel(254, 1)
                                            val rr = (tr ushr 16) and 0xFF
                                            val rg = (tr ushr 8)  and 0xFF
                                            val rb =  tr          and 0xFF
                                            bmp.recycle()
                                            kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.Main,
                                            ) {
                                                snackbarHostState.showSnackbar(
                                                    "GL UV: ok=$ok TR=($rr,$rg,$rb) -> ${out.name}",
                                                    duration = androidx.compose
                                                        .material3.SnackbarDuration.Long,
                                                )
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Verify save fidelity") },
                                    onClick = {
                                        showPresetMenu = false
                                        val glv = glViewForHistogram.value
                                        // Option B compares the editor GL
                                        // snapshot against the user's MOST-
                                        // RECENT saved file on disk. The
                                        // user must have done Apply → Export
                                        // first; if not, the harness
                                        // surfaces a clear "save first" toast.
                                        val savedPath = component.fullResOutputPath
                                        if (glv == null) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Verify: editor not ready"
                                                )
                                            }
                                            return@DropdownMenuItem
                                        }
                                        if (savedPath.isNullOrEmpty()) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Verify: save a file first (Apply → Export)"
                                                )
                                            }
                                            return@DropdownMenuItem
                                        }
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Verify: comparing editor vs saved file…"
                                            )
                                            // Wait until the GRADED Stage B
                                            // bake is current. With the
                                            // dual-AHB Hybrid, equality of
                                            // requested == baked means the
                                            // GL view is sampling gradedAhb
                                            // (which IS the save-equivalent
                                            // pre-graded texture), so the
                                            // snapshot will match Stage C
                                            // output. 5 s ceiling so the
                                            // verify can't hang on a stuck
                                            // bake.
                                            run {
                                                val deadline = System.currentTimeMillis() + 5_000
                                                while (bakeRequestedKey != bakeBakedKey &&
                                                       System.currentTimeMillis() < deadline) {
                                                    kotlinx.coroutines.delay(50)
                                                }
                                                if (bakeRequestedKey != bakeBakedKey) {
                                                    AppLog.w(
                                                        "PresetVerifyHarness",
                                                        "Stage B not current after 5s; verifying anyway (req=$bakeRequestedKey baked=$bakeBakedKey)"
                                                    )
                                                }
                                            }
                                            // Snapshot the live GL preview
                                            // at a moderate longSide so the
                                            // CPU render side stays under a
                                            // few seconds.
                                            val longSide = 1024
                                            val sw = if (imageAspect >= 1f) longSide
                                                else (longSide * imageAspect).toInt()
                                            val sh = if (imageAspect >= 1f)
                                                (longSide / imageAspect).toInt()
                                                else longSide
                                            val snap = glv.snapshotGradedToBitmap(
                                                longSide,
                                                sw.coerceAtLeast(1),
                                                sh.coerceAtLeast(1),
                                            )
                                            if (snap == null) {
                                                snackbarHostState.showSnackbar(
                                                    "Verify: GL snapshot failed"
                                                )
                                                return@launch
                                            }
                                            val result = kotlinx.coroutines
                                                .withContext(
                                                    kotlinx.coroutines.Dispatchers.Default
                                                ) {
                                                com.RAZStudio.StudioRoom.feature
                                                    .photo_editor.raw.preset
                                                    .PresetVerifyHarness.run(
                                                        context = context,
                                                        editorSnapshot = snap,
                                                        savedFilePath  = component
                                                            .fullResOutputPath,
                                                    )
                                            }
                                            val msg = if (result.success) {
                                                val patch = if (result.centerPatchMad >= 0)
                                                    " patch=${"%.1f".format(result.centerPatchMad)}" else ""
                                                "MAD ${"%.1f".format(result.meanAbsDiff)}$patch /255 in ${result.durationMs}ms"
                                            } else {
                                                "Verify failed: ${result.error}"
                                            }
                                            snackbarHostState.showSnackbar(
                                                message = msg,
                                                duration = androidx.compose
                                                    .material3.SnackbarDuration.Long,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (isFullResProcessing) {
                        CircularProgressIndicator(
                            modifier    = Modifier.padding(end = 12.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    if (embeddedFallback != null) {
                        // Compact pill — full message lives in the canvas overlay banner.
                        Text(
                            text = "Preview only",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            val totalWidthPx  = constraints.maxWidth.toFloat()
            val totalHeightPx = constraints.maxHeight.toFloat()
            val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

            // Auto-fit canvas slot to photo dimensions.
            //   The handle is initially positioned so the photo fits its
            //   shorter side (width for portrait, height for landscape).
            //   For a 3:2 landscape photo, this results in an optimized fit
            //   with no background color (letterboxing). For portrait, the
            //   photo appears slightly smaller but ensures maximum reserved
            //   space for the adjustment panel.
            //
            //   Skipped when the user has manually dragged the splitter
            //   handle (canvasFractionUserOverridden) so manual control
            //   always wins.
            androidx.compose.runtime.LaunchedEffect(
                imageAspect, isLandscape, totalWidthPx, totalHeightPx,
                canvasFractionUserOverridden,
            ) {
                if (canvasFractionUserOverridden) return@LaunchedEffect
                if (imageAspect <= 0f || totalWidthPx <= 0f || totalHeightPx <= 0f)
                    return@LaunchedEffect
                val targetDim = if (isLandscape) {
                    kotlin.math.min(totalWidthPx, totalHeightPx * imageAspect)
                } else {
                    kotlin.math.min(totalHeightPx, totalWidthPx / imageAspect)
                }
                val fraction = if (isLandscape) targetDim / totalWidthPx else targetDim / totalHeightPx
                canvasFraction = fraction.coerceIn(0.25f, 0.75f)
            }

            if (isTablet) {
                Row(modifier = Modifier.fillMaxSize()) {
                    PanelBox(
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 360.dp)
                            .weight(0.32f, fill = false),
                    )
                    CanvasBox(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f),
                    )
                    RawTabletInspector(gradedHistogram = gradedHistogram)
                }
            } else if (isLandscape) {
                // Landscape: adjustments | canvas
                Row(modifier = Modifier.fillMaxSize()) {
                    PanelBox(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f - canvasFraction),
                    )

                    if (isPreviewReady) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(20.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    state       = rememberDraggableState { delta ->
                                        canvasFraction = (canvasFraction - delta / totalWidthPx)
                                            .coerceIn(0.25f, 0.75f)
                                        canvasFractionUserOverridden = true
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                verticalArrangement   = Arrangement.spacedBy(4.dp),
                                horizontalAlignment   = Alignment.CenterHorizontally,
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                }
                            }
                        }
                    }

                    CanvasBox(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(canvasFraction),
                    )

                    if (embeddedFallback != null) {
                        FallbackBanner(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(start = 4.dp),
                        )
                    }
                }
            } else {
                // ── Portrait: Column — canvas top, handle, panel bottom — no overlap ──
                Column(modifier = Modifier.fillMaxSize()) {

                    // Canvas takes its exact fraction; SurfaceView stays within these bounds.
                    CanvasBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(canvasFraction),
                    )

                    if (embeddedFallback != null) {
                        FallbackBanner(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                        )
                    }

                    // Drag handle between canvas and panel.
                    if (isPreviewReady) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .draggable(
                                    orientation = Orientation.Vertical,
                                    state       = rememberDraggableState { delta ->
                                        canvasFractionUserOverridden = true
                                        // Drag down → canvas grows; drag up → panel grows
                                        canvasFraction = (canvasFraction + delta / totalHeightPx)
                                            .coerceIn(0.20f, 0.75f)
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                }
                            }
                        }
                    }

                    // Panel takes the remaining height.
                    PanelBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f - canvasFraction),
                    )
                }
            }

        }
    }

    // ── v2-integration §2.1 — EXIF info bottom sheet ──────────────────────────
    if (showExifSheet) {
        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.ExifInfoSheet(
            exif = exifInfo,
            // The strip-GPS flag is a session toggle that lives on the delta macro so it
            // composes with the live edit before merging back into the action stack at
            // commit time. baselineMacro is derived from committed actions and would
            // discard the flag on next recomposition.
            stripGpsOnSave = deltaMacro.stripGps || (exifInfo?.hasGps == true && baselineMacro.stripGps),
            onStripGpsToggle = { on ->
                deltaMacro = deltaMacro.copy(stripGps = on)
            },
            onDismiss = { showExifSheet = false },
        )
    }

    // ── v2-integration §2.2 — Sidecar history bottom sheet ────────────────────
    if (showHistorySheet) {
        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.SidecarHistorySheet(
            loadSidecar = { component.loadSidecar() },
            onRevert = { idx -> component.revertSidecar(idx) },
            onDismiss = { showHistorySheet = false },
        )
    }

    // ── Batch AMOLED screensaver + music ──────────────────────────────────────
    // Active only while the batch processor is in Running state.
    //   • Screensaver shows 5 s after Run Batch tap and after 5 s of idle.
    //   • Tap anywhere dismisses (no visible UI on the overlay).
    //   • Auto-stops when batch transitions to Done/Cancelled.
    val batchProtectionPrefs = remember {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.BatchProtectionPrefs(context)
    }
    val batchAudioPlayer = remember {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.BatchAudioPlayer(context)
    }
    // Sustained-performance + screen-on + wake lock for the duration of any
    // batch run. Sustained mode caps peak clocks to avoid thermal throttling
    // on long batches; keep-screen-on + partial wake lock keep the device
    // alive so background processes don't starve the export worker. All
    // released when the batch transitions out of Running.
    androidx.compose.runtime.DisposableEffect(isBatchRunning) {
        val scope = if (isBatchRunning) {
            val activity = context as? android.app.Activity
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                .BatchPerfScope.acquire(context, activity, tag = "RawBatchProcessor")
        } else null
        onDispose { scope?.release() }
    }
    var screensaverVisible by remember { mutableStateOf(false) }
    var lastInteractionMs   by remember { mutableStateOf(0L) }
    val screenProtEnabled = batchProtectionPrefs.screenProtectionEnabled

    // Drive screensaver show / hide based on batch lifecycle + idle timer.
    LaunchedEffect(isBatchRunning, screenProtEnabled) {
        if (!isBatchRunning || !screenProtEnabled) {
            screensaverVisible = false
            batchAudioPlayer.stop()
            return@LaunchedEffect
        }
        lastInteractionMs = System.currentTimeMillis()
        while (isBatchRunning) {
            kotlinx.coroutines.delay(500L)
            val idleMs = System.currentTimeMillis() - lastInteractionMs
            if (idleMs >= 5_000L && !screensaverVisible) {
                screensaverVisible = true
                if (batchProtectionPrefs.musicEnabled) {
                    batchAudioPlayer.start(batchProtectionPrefs.volumeLevel.gain)
                }
            }
        }
        screensaverVisible = false
        batchAudioPlayer.stop()
    }

    DisposableEffect(Unit) {
        onDispose { batchAudioPlayer.stop() }
    }

    // Blocking batch UI (owner rule 2026-09-07): while the folder batch runs the
    // modal progress dialog swallows back/taps — only its Cancel stops the batch.
    // Hidden while the screensaver overlay is up so the dimmed screen still
    // shows (a tap there dismisses the screensaver and this dialog returns).
    run {
        val running = batchStateNow as? com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.BatchProgress.Running
        val done = ((running?.currentIndex ?: 1) - 1).coerceAtLeast(0)
        LoadingDialog(
            visible = running != null && !screensaverVisible,
            done = done,
            left = ((running?.totalCount ?: 0) - done).coerceAtLeast(0),
            onCancelLoading = { batchProcessor.cancel() },
        )
    }

    if (screensaverVisible) {
        val running = batchStateNow as? com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.BatchProgress.Running
        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.BatchScreensaverOverlay(
            windowBrightness = batchProtectionPrefs.brightnessLevel.windowBrightness,
            currentIndex = running?.currentIndex ?: 0,
            totalCount = running?.totalCount ?: 0,
            onDismiss = {
                screensaverVisible = false
                lastInteractionMs = System.currentTimeMillis()
                batchAudioPlayer.stop()
            },
        )
    }

    // ── Exit confirmation dialog ──────────────────────────────────────────────
    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title   = { Text(stringResource(R.string.raw_exit_title)) },
            text    = { Text(stringResource(R.string.raw_exit_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirmDialog = false
                    // Wipe in-memory + on-disk editor state so re-opening
                    // any photo starts fresh. The exit-confirm dialog is
                    // the explicit "discard and leave" surface — anything
                    // the user wanted preserved should have been saved
                    // (Apply → Export) before this point.
                    component.clearEditorState()
                    component.onGoBack()
                }) {
                    Text(
                        stringResource(R.string.raw_exit_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmDialog = false }) {
                    Text(stringResource(R.string.raw_cancel))
                }
            },
        )
    }

    if (showPresetExportDialog) {
        // Project the live action stack into a portable preset, dropping
        // hand-painted brush cards (those carry photo-specific bitmaps).
        val exportResult = remember(actions.toList()) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.preset
                .buildPresetFromActions(
                    name = "Untitled",
                    actions = actions.toList(),
                )
        }
        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
            .components.PresetExportDialog(
                initialName = "Untitled",
                droppedCount = exportResult.droppedCardLabels.size,
                onConfirm = { typedName ->
                    val finalPreset = exportResult.preset.copy(name = typedName)
                    val saved = com.RAZStudio.StudioRoom.feature.photo_editor
                        .raw.preset.RazPresetStore.save(context, finalPreset)
                    showPresetExportDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (saved != null) "Preset saved: $typedName"
                            else "Failed to save preset"
                        )
                    }
                },
                onDismiss = { showPresetExportDialog = false },
            )
    }

    if (showPresetPickerDialog) {
        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
            .components.PresetPickerDialog(
                entries = presetEntries,
                onApply = { entry ->
                    showPresetPickerDialog = false
                    val preset = com.RAZStudio.StudioRoom.feature.photo_editor
                        .raw.preset.RazPresetStore.load(entry.file)
                    if (preset == null) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Failed to load preset")
                        }
                        return@PresetPickerDialog
                    }
                    // Apply cards onto the current editor in order. Mask
                    // cards (those carrying `maskClasses`) need their
                    // bitmap regenerated from the TARGET photo's
                    // segmentation outputs — we run that on a background
                    // dispatcher because OR-merging a few 320² masks +
                    // upsampling to full preview dims is ~50-150 ms.
                    presetApplyInFlight = true
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        for (c in preset.cards) {
                            // Strictly graph-driven: reconstruct nodes from classes.
                            val classes = c.maskClasses.ifEmpty { listOfNotNull(c.maskClass) }
                            val nodes = classes.map { cls ->
                                MaskNode(
                                    id = java.util.UUID.randomUUID().toString(),
                                    source = MaskSource.ModelClass(cls),
                                    operation = MaskOp.ADD
                                )
                            }
                            val newAction = com.RAZStudio.StudioRoom
                                .feature.photo_editor.raw.model.RawAction(
                                    label = c.label,
                                    tabIndex = c.tabIndex,
                                    macro = c.macro,
                                    maskNodes = nodes,
                                    maskClass = c.maskClass,
                                    maskClasses = c.maskClasses,
                                    isAutoExposure = c.isAutoExposure,
                                )
                            kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.Main,
                            ) {
                                component.addAction(newAction)
                            }
                        }
                        kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.Main,
                        ) {
                            presetApplyInFlight = false
                            snackbarHostState.showSnackbar(
                                "Applied preset: ${preset.name} (${preset.cards.size} cards)"
                            )
                        }
                    }
                },
                onDelete = { entry ->
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw.preset
                        .RazPresetStore.delete(entry.file)
                    presetEntries = com.RAZStudio.StudioRoom.feature.photo_editor
                        .raw.preset.RazPresetStore.list(context)
                },
                onDismiss = { showPresetPickerDialog = false },
            )
    }

    // ── Export screen overlay ─────────────────────────────────────────────────
    // Rendered AFTER the editor body so it floats on top in Z-order.
    // Keeping this as an overlay (rather than an early-return branch) means
    // the GL SurfaceView stays composed the whole time — no surface
    // destroy/recreate cycle when navigating export ↔ editor, so dismissing
    // the export page instantly shows the live GL canvas again.
    //
    // SurfaceView Z-layer fix: SurfaceViews bypass Compose's Z-order and
    // punch through any Compose overlay placed on top of them. Hide the GL
    // view with INVISIBLE (keeps the surface alive, no destroy/recreate) while
    // the export screen is showing, then restore VISIBLE on dismiss.
    LaunchedEffect(showRawExport) {
        glViewForHistogram.value?.visibility =
            if (showRawExport) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }
    // Export page "Saved presets" (owner report 2026-09-07): selecting a preset
    // replaced the action stack, but the page displays a graded bitmap captured
    // ONCE on hand-off, so the canvas never changed. The GL view is INVISIBLE
    // here with its renderer RELEASED (setZOrderOnTop → it must hide; hiding
    // destroys the surface), so a screen grab is impossible — the first attempt
    // crashed in PixelCopy. Instead re-render a proxy through the real export
    // kernel (see renderExportProxy): slower, but exactly what Save produces.
    LaunchedEffect(showRawExport) {
        if (!showRawExport) return@LaunchedEffect
        var first = true
        component.shaderParamsFlow.collectLatest {
            if (first) { first = false; return@collectLatest }
            kotlinx.coroutines.delay(400)
            component.renderExportProxy(context)
        }
    }
    if (showRawExport) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        ) {
            RawExportScreen(
                component      = component,
                onGoBack       = { component.dismissRawExport() },
                onGoToEditor   = { component.dismissRawExport() },
                onPickNewImage = {
                    component.dismissRawExport()
                    launchRawPicker()
                },
                onOpenDetailsEditor = {
                    component.dismissRawExport()
                    component.onNavigate(
                        com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.RawDetailsEditor(
                            uri = component.activeUri,
                        )
                    )
                },
            )
        }
    }
}

/**
 * Embedded-JPEG fallback banner. Placed in the layout flow immediately above
 * (portrait) or beside (landscape) the canvas/panel divider handle, so it's
 * always reachable regardless of canvas zoom or panel state.
 */
@Composable
private fun FallbackBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "Decoder slow or unsupported for this RAW — showing camera's embedded preview. Save and edits are paused until the full decode finishes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
