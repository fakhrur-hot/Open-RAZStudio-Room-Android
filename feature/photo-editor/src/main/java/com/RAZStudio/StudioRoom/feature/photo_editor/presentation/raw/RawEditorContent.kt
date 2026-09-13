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
import com.RAZStudio.StudioRoom.feature.compare.presentation.components.CompareSheet
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawAdjustmentPanel
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
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val rawImportSuccessMsg = stringResource(com.RAZStudio.StudioRoom.core.resources.R.string.raw_import_success)
    val rawImportErrorMsg = stringResource(com.RAZStudio.StudioRoom.core.resources.R.string.raw_import_error)
    val rawExportActionsSavedMsg = stringResource(com.RAZStudio.StudioRoom.core.resources.R.string.raw_export_actions_saved)

    var showExitConfirmDialog by remember { mutableStateOf(false) }
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
    var isVignetteCenterMode    by remember { mutableStateOf(false) }
    // Set tracking which segmentation classes are currently part of the mask.
    // Drives the blue-state of each Mask-tab split-button's left half.
    // Select-callbacks add to the set, Remove-callbacks drop their entry,
    // Clear/Invert/maskBitmap=null empties the set.
    var includedMaskClasses by remember { mutableStateOf(emptySet<MaskClass>()) }
    // The "prime" mask class — the first one selected. It owns the mask base
    // (replace semantics). All subsequent selects are additive on top.
    // Pressing the red side of the prime clears the entire mask and resets this.
    var primaryMaskClass by remember { mutableStateOf<MaskClass?>(null) }
    // Range-tool primes (mutually exclusive with primaryMaskClass). Needed so
    // Remove on Luma/Chroma clears when they ARE the base, but carves when a
    // bitmap/object base exists (maskLumCombine=2 / subtractive chroma).
    var primaryIsLuma by remember { mutableStateOf(false) }
    var primaryIsChroma by remember { mutableStateOf(false) }
    // When true, ColorSelect taps SUBTRACT the keyed colour from the current
    // bitmap (chroma carve). When false, taps replace/union as usual.
    var chromaSubtractMode by remember { mutableStateOf(false) }
    // Heal-tab state. `healActive` is the explicit toggle (mirrors
    // vignette's "Move center" button) that suspends pinch/pan/zoom on
    // the canvas so a tap can be routed to the heal pipeline without
    // racing with gesture handlers.
    var healRadiusPx     by remember { mutableFloatStateOf(60f) }
    var healActive       by remember { mutableStateOf(false) }
    var isHealing        by remember { mutableStateOf(false) }
    var healCount        by remember { mutableIntStateOf(0) }
    // Cumulative healed bitmap. Initialised on the first tap from a GL
    // snapshot, then re-inpainted in place by every subsequent tap.
    // Displayed as an overlay on top of the GL surface so the user
    // sees the healing immediately. Stays as long as the eventual
    // RawAction.Heal card exists in Actions.
    var healedOverlay    by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Stack of pre-tap overlay snapshots. Each tap pushes the
    // overlay-before-this-tap (or null if there was none) onto the
    // stack BEFORE the new healed bitmap replaces it. Undo pops the
    // top entry and restores it as the live overlay, recycling the
    // current one. Cleared on Apply/Cancel.
    val healUndoStack = remember {
        mutableListOf<android.graphics.Bitmap?>()
    }
    // Snapshot of [healedOverlay] taken when the Heal tab is entered, so
    // Cancel can revert to whatever overlay state was committed before
    // this editing session (or null for a fresh session). Apply consumes
    // the snapshot (clears it) so the just-committed heal becomes the
    // new baseline for future Heal tab visits.
    var healedOverlayPreEdit by remember {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    // True once at least one heal tap has been applied since the Heal
    // tab was entered. Drives the Cancel/Apply bar visibility and the
    // committed-action label. Cleared by both Apply and Cancel.
    var healDirty by remember { mutableStateOf(false) }
    // Snapshot of [healCount] when the Heal tab was entered. Cancel
    // restores it; Apply consumes it (becomes the new baseline).
    var healCountPreEdit by remember { mutableIntStateOf(0) }
    val healScope = androidx.compose.runtime.rememberCoroutineScope()
    var canvasFraction    by remember { mutableFloatStateOf(0.55f) }
    // Tracks whether the user has manually dragged the canvas/panel
    // splitter handle. Once true, the auto-fit-to-image-aspect effect
    // stops adjusting `canvasFraction` so the user's preferred split
    // is preserved across orientation / photo changes within this
    // editor session. Resets to false on new photo open.
    var canvasFractionUserOverridden by remember { mutableStateOf(false) }
    var isComparing       by remember { mutableStateOf(false) }
    var showCompareSheet  by remember { mutableStateOf(false) }
    var canvasScale     by remember { mutableFloatStateOf(1f) }
    var canvasOffset    by remember { mutableStateOf(Offset.Zero) }
    val pendingExport        = false  // export now navigates to RawExportScreen inline
    var isMaskModeActive     by remember { mutableStateOf(false) }
    // True when the user entered the Mask tab fresh (not by tapping
    // a committed mask card). Suppresses committed mask layers from
    // the GL renderer so the canvas appears clean while creating a
    // new mask. Set by the fresh-tab-entry clear; cleared by Apply,
    // Cancel, or loading a card for re-editing.
    var isFreshMaskSession   by remember { mutableStateOf(false) }
    var maskBitmap           by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Declared here (rather than near its fillFromXxx() call sites further
    // down) so the isMaskEdit-pending check earlier in composition order can
    // read it. See that check for why isActive matters, not just non-null.
    var maskJob: kotlinx.coroutines.Job? by remember { mutableStateOf(null) }
    var maskDirty            by remember { mutableIntStateOf(0) }
    var brushSize            by remember { mutableFloatStateOf(30f) }
    var brushIntensity       by remember { mutableFloatStateOf(0.8f) }
    var brushFeather         by remember { mutableFloatStateOf(0.3f) }
    var brushMode            by remember { mutableStateOf(MaskBrushMode.Draw) }
    // Color-range mask ("Select Color"): sampled ARGB colours (each canvas tap
    // in ColorSelect mode appends one) + the Refine tolerance [0..100]. The mask
    // is (re)built from ALL samples whenever a tap or the slider changes.
    var maskColorSamples     by remember { mutableStateOf<List<Int>>(emptyList()) }
    var maskColorTolerance   by remember { mutableFloatStateOf(50f) }
    // M12.2c.5 — Sharp Edges momentary fill. The slider controls the
    // mask spread (dilation positive, erosion negative) in [-1, +1].
    // The button writes a fresh edge-snapped + spread bitmap into
    // maskBitmap on press; it never latches state.
    var sharpSpread          by remember { mutableFloatStateOf(0f) }
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
    val segmentationMasks    by component.segmentationMasks.collectAsState()
    // Subject-mask-dependent controls (Bokeh, subject/background Vignette &
    // Gradient) grey out while segmentation is running and no subject mask is
    // ready yet. Enabled once masks arrive OR the chain ends (so a detection
    // failure re-enables them to their prior no-op rather than trapping them).
    val segmentationRunning  by component.segmentationRunning.collectAsState()
    val subjectMaskReady     = segmentationMasks != null
    val subjectSegBusy       = segmentationRunning && !subjectMaskReady
    // v3 variant carries the raw FloatArray + innerRect needed by
    // HealMaskBuilder. The v2 adapter (above) drops innerRect.
    val segmentationMasksV3OuterScope by component.segmentationMasksV3.collectAsState()
    // MediaPipe multiclass per-class masks. Drives the new Mask-tab
    // class-specific Select buttons (Hair / Body / Face / Clothes).
    val multiclassMasks by component.multiclassMasks.collectAsState()
    val multiclassLoadingState by component.multiclassLoading.collectAsState()
    // Face-detection mask (Qualcomm ONNX, whole-face elliptical fill).
    // Preferred over multiclass FaceSkin for the "Face" button so a tap
    // covers the entire face shape rather than just the skin region.
    val faceMask by component.faceMask.collectAsState()
    // Cityscapes 4-class masks (SegFormer-B1 ONNX) — landscape mask source.
    val cityscapesMasks by component.cityscapesMasks.collectAsState()
    val cityscapesLoadingState by component.cityscapesLoading.collectAsState()
    // DeepLabV3+ human-parsing masks (LIP 20-class, 45 MB ONNX).
    // Used alongside selfie_multiclass to improve Hair / Face / BodySkin / Clothes.
    val deepLabMasks by component.deepLabMasks.collectAsState()
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
        component.setMaskLayers(maskLayers)
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
    fun fillFromSegmentationSharp(
        subject: FloatArray,
        edges:   FloatArray,
        spread:  Float,
    ) {
        val neutral = component.neutralBitmap ?: return
        maskJob?.cancel()
        maskJob = scope.launch(Dispatchers.Default) {
            val bW = neutral.width; val bH = neutral.height
            val mSize = 320
            val pixels = IntArray(bW * bH)
            // Threshold shifts with spread. Lower threshold = more
            // permissive = bigger subject region (dilation).
            val threshold = (0.5f - spread * 0.4f).coerceIn(0.05f, 0.95f)
            val snapStrength = 0.6f
            val snapThreshold = 0.20f
            for (y in 0 until bH) {
                if ((y and 0x1F) == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                for (x in 0 until bW) {
                    val mx = x.toFloat() / (bW - 1).coerceAtLeast(1) * (mSize - 1)
                    val my = y.toFloat() / (bH - 1).coerceAtLeast(1) * (mSize - 1)
                    val x0 = mx.toInt().coerceIn(0, mSize - 2)
                    val y0 = my.toInt().coerceIn(0, mSize - 2)
                    val dx = mx - x0; val dy = my - y0
                    val p = subject[y0 * mSize + x0]         * (1 - dx) * (1 - dy) +
                            subject[y0 * mSize + x0 + 1]     * dx       * (1 - dy) +
                            subject[(y0 + 1) * mSize + x0]   * (1 - dx) * dy       +
                            subject[(y0 + 1) * mSize + x0 + 1] * dx     * dy
                    val edge = edges[y0 * mSize + x0]         * (1 - dx) * (1 - dy) +
                               edges[y0 * mSize + x0 + 1]     * dx       * (1 - dy) +
                               edges[(y0 + 1) * mSize + x0]   * (1 - dx) * dy       +
                               edges[(y0 + 1) * mSize + x0 + 1] * dx     * dy
                    // Edge-snap: push toward whichever side of the
                    // threshold the soft probability already favours.
                    var v = p
                    if (edge > snapThreshold) {
                        val push = edge * snapStrength
                        v = if (p > threshold) (p + push).coerceAtMost(1f)
                            else (p - push).coerceAtLeast(0f)
                    }
                    // Binarize with smoothstep at the spread-shifted
                    // threshold so we keep a 1-2 px anti-aliased edge.
                    val lo = (threshold - 0.05f).coerceAtLeast(0f)
                    val hi = (threshold + 0.05f).coerceAtMost(1f)
                    val t  = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
                    val sm = t * t * (3f - 2f * t)
                    val a = (sm * 255f).toInt().coerceIn(0, 255)
                    pixels[y * bW + x] = (a shl 24) or 0x00FFFFFF
                }
            }
            val bmp = android.graphics.Bitmap.createBitmap(bW, bH, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, bW, 0, 0, bW, bH)
            withContext(Dispatchers.Main) {
                maskBitmap = bmp
                maskDirty++
                isMaskModeActive = true
                component.updateMask(bmp)
            }
        }
    }

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
    fun synthesizeFaceFromContext(
        multiclass: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3MulticlassMasks?,
        subject: FloatArray?,
    ): FloatArray? {
        if (multiclass == null) return null
        val side = 320
        if (multiclass.faceSkin.size != side * side) return null
        val out = FloatArray(side * side)
        // Vertical search window for context — 8 to 40 rows around each
        // candidate cell. ~12% of the 320 grid height, matches an adult-
        // head proportion in a head-and-shoulders crop.
        val winNear = 4
        val winFar  = 48
        for (y in 0 until side) {
            // Pre-compute the row bounds for above/below scans this row.
            val aboveLo = (y - winFar).coerceAtLeast(0)
            val aboveHi = (y - winNear).coerceAtLeast(0)
            val belowLo = (y + winNear).coerceAtMost(side - 1)
            val belowHi = (y + winFar).coerceAtMost(side - 1)
            for (x in 0 until side) {
                val i = y * side + x
                // 1. Skin requirement — face skin OR body skin (face skin
                //    misses when the face is small / partly occluded).
                val skin = maxOf(multiclass.faceSkin[i], multiclass.bodySkin[i])
                if (skin < 0.20f) continue
                // 2. Subject gate — must be in the foreground.
                if (subject != null && subject.size == out.size && subject[i] < 0.20f) continue
                // 3. Hair-above scan: max hair confidence in the column
                //    above this cell, within the window.
                var hairAbove = 0f
                for (yy in aboveLo..aboveHi) {
                    val v = multiclass.hair[yy * side + x]
                    if (v > hairAbove) hairAbove = v
                }
                if (hairAbove < 0.25f) continue
                // 4. Clothes-below scan: max clothes confidence in the
                //    column below this cell.
                var clothesBelow = 0f
                for (yy in belowLo..belowHi) {
                    val v = multiclass.clothes[yy * side + x]
                    if (v > clothesBelow) clothesBelow = v
                }
                if (clothesBelow < 0.25f) continue
                // Face confidence = product of the four signals, clamped.
                // Multiplicative because we want ALL signals strong; one
                // weak link should kill the cell (typical for triangulation
                // heuristics — additive lets one channel dominate).
                val v = (skin * hairAbove * clothesBelow).coerceIn(0f, 1f)
                out[i] = v
            }
        }
        return out
    }

    /**
     * Return a copy of [classMask] with one or more [competing] planes
     * subtracted. Each competitor contributes its own (1 - mask[i]) factor
     * multiplicatively:
     *   out[i] = classMask[i] * Π_k (1 - competing[k][i])
     *
     * Used by the Cityscapes 4-class fills to enforce these exclusions
     * (in addition to subject subtraction):
     *   • Sky    excludes Subject + Building + Plants
     *   • Plants excludes Subject + Terrain
     *   • Building excludes Subject + Terrain
     *   • Terrain excludes Subject
     * Without this, SegFormer's soft confidences at class boundaries
     * cause overlap — Sky leaking into building roofs, Plants spreading
     * into Terrain grass, etc.
     *
     * Pass-through when [classMask] has no valid competitors (length
     * mismatch or all null) so the call is safe before inference finishes.
     */
    /**
     * Dilate a square 320×320 mask by [radius] pixels using a separable max
     * filter. Used to grow the U²Net subject before subtracting from
     * Cityscapes Buildings: tables, bags, cups held near the subject sit
     * inside the dilated region and get excluded, even though they aren't
     * classified as "subject" by U²Net.
     */
    fun dilateMask(mask: FloatArray, side: Int = 320, radius: Int = 4): FloatArray {
        if (radius <= 0 || mask.size != side * side) return mask
        val tmp = FloatArray(side * side)
        for (y in 0 until side) {
            val row = y * side
            for (x in 0 until side) {
                var m = 0f
                val xMin = (x - radius).coerceAtLeast(0)
                val xMax = (x + radius).coerceAtMost(side - 1)
                for (xx in xMin..xMax) {
                    val v = mask[row + xx]
                    if (v > m) m = v
                }
                tmp[row + x] = m
            }
        }
        val out = FloatArray(side * side)
        for (x in 0 until side) {
            for (y in 0 until side) {
                var m = 0f
                val yMin = (y - radius).coerceAtLeast(0)
                val yMax = (y + radius).coerceAtMost(side - 1)
                for (yy in yMin..yMax) {
                    val v = tmp[yy * side + x]
                    if (v > m) m = v
                }
                out[y * side + x] = m
            }
        }
        return out
    }

    /**
     * Subtract competitor masks from `classMask`, with a per-competitor
     * confidence boost. The boost lets soft-edge masks fully suppress
     * regions where they have only partial confidence — useful when a
     * cross-vendor mask (U²Net subject, MediaPipe clothes/face) needs to
     * fully clear a region even at silhouette feather.
     *
     * Pass `boost = 3f` (default) for cross-vendor / soft masks. Pass
     * `boost = 1f` for same-vendor subtractions (e.g. Cityscapes Sky
     * minus Cityscapes Building) where the source softmax is already
     * sharp and a boost would over-erode legitimate class pixels.
     */
    fun subtractMasks(
        classMask: FloatArray,
        vararg competing: FloatArray?,
        boost: Float = 3f,
    ): FloatArray {
        val valid = competing.filterNotNull().filter { it.size == classMask.size }
        if (valid.isEmpty()) return classMask
        val out = FloatArray(classMask.size)
        for (i in classMask.indices) {
            var v = classMask[i]
            for (c in valid) {
                val sup = (c[i] * boost).coerceIn(0f, 1f)
                v *= (1f - sup)
            }
            out[i] = v
        }
        return out
    }

    /**
     * Convert a 320² float probability mask to a full-preview-resolution
     * alpha bitmap, with optional edge-snap to the source photo's
     * luminance edges.
     *
     * Without [edges], a coarse 320² Cityscapes/MediaPipe mask bilinearly
     * upsamples into 8–15 px fuzzy boundaries — visible as wide gaps
     * around fine foliage / hair / wire-edge subjects (the "tree + sky"
     * problem). With [edges] provided, the soft probability snaps toward
     * 0 or 1 on the side of the threshold that the Sobel mask says is
     * structurally certain, then smoothstep-binarises so the alpha edge
     * locks onto real photo content rather than the model's coarse
     * silhouette.
     *
     * The output alpha is also smoothstep-binarised across a 0.05-wide
     * band so we keep a 1-2 px anti-aliased edge for compositing.
     *
     * Both Sobel snap and binarisation are skipped when [edges] is null,
     * preserving backward compatibility with single-mask call sites that
     * have no edge data.
     */
    fun fillFromSegmentation(
        floatMask: FloatArray,
        edges: FloatArray? = null,
        threshold: Float = 0.50f,
        snapStrength: Float = 0.60f,
        snapThreshold: Float = 0.20f,
        // When true, OR-blends on top of the existing mask (additive).
        // When false (default), replaces the mask entirely.
        additive: Boolean = false,
    ) {
        val neutral = component.neutralBitmap ?: return
        // Starting a fresh object-class selection clears any active range mask
        // (Luma/Chroma). Compositing onto an existing base (additive) keeps
        // luma/chroma so cross-category carve/union stays intact.
        if (!additive) {
            maskColorSamples = emptyList()
            chromaSubtractMode = false
            deltaMacro = deltaMacro.copy(
                maskLumTarget = 0f, maskLumSpread = 0f, maskLumFeather = 0f, maskLumCombine = 0,
            )
            primaryIsLuma = false
            primaryIsChroma = false
            if (brushMode == MaskBrushMode.ColorSelect || brushMode == MaskBrushMode.LumaSelect)
                brushMode = MaskBrushMode.None
        }
        maskJob?.cancel()
        maskJob = scope.launch(Dispatchers.Default) {
            val bW = neutral.width; val bH = neutral.height
            val mSize = 320 // RawSegmentationMasks.MASK_SIZE
            val pixels = IntArray(bW * bH)
            // Capture existing alpha values for additive mode before any writes.
            val existingAlpha = if (additive) {
                maskBitmap?.let { src ->
                    IntArray(bW * bH).also { src.getPixels(it, 0, bW, 0, 0, bW, bH) }
                }
            } else null
            val useEdges = edges != null && edges.size == floatMask.size
            for (y in 0 until bH) {
                if ((y and 0x1F) == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                for (x in 0 until bW) {
                    val mx = x.toFloat() / (bW - 1).coerceAtLeast(1) * (mSize - 1)
                    val my = y.toFloat() / (bH - 1).coerceAtLeast(1) * (mSize - 1)
                    val x0 = mx.toInt().coerceIn(0, mSize - 2)
                    val y0 = my.toInt().coerceIn(0, mSize - 2)
                    val dx = mx - x0; val dy = my - y0
                    val p = floatMask[y0 * mSize + x0]         * (1 - dx) * (1 - dy) +
                            floatMask[y0 * mSize + x0 + 1]     * dx       * (1 - dy) +
                            floatMask[(y0 + 1) * mSize + x0]   * (1 - dx) * dy       +
                            floatMask[(y0 + 1) * mSize + x0 + 1] * dx     * dy
                    val a: Int = if (useEdges) {
                        val edge = edges!![y0 * mSize + x0]         * (1 - dx) * (1 - dy) +
                                   edges[y0 * mSize + x0 + 1]       * dx       * (1 - dy) +
                                   edges[(y0 + 1) * mSize + x0]     * (1 - dx) * dy       +
                                   edges[(y0 + 1) * mSize + x0 + 1] * dx     * dy
                        var v = p
                        if (edge > snapThreshold) {
                            val push = edge * snapStrength
                            v = if (p > threshold) (p + push).coerceAtMost(1f)
                                else (p - push).coerceAtLeast(0f)
                        }
                        val lo = (threshold - 0.05f).coerceAtLeast(0f)
                        val hi = (threshold + 0.05f).coerceAtMost(1f)
                        val t  = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
                        val sm = t * t * (3f - 2f * t)
                        (sm * 255f).toInt().coerceIn(0, 255)
                    } else {
                        (p * 255f).toInt().coerceIn(0, 255)
                    }
                    val finalA = if (existingAlpha != null) {
                        val existA = (existingAlpha[y * bW + x] ushr 24) and 0xFF
                        maxOf(existA, a)
                    } else a
                    pixels[y * bW + x] = (finalA shl 24) or 0x00FFFFFF
                }
            }
            // Always allocate a NEW Bitmap, even in additive mode.
            // If we reuse `maskBitmap!!` (same object), the mutableStateOf
            // assignment `maskBitmap = bmp` is a no-op reference write and
            // Compose skips recomposition — the blue overlay never renders.
            // A new object forces the state change to propagate and invalidates
            // the `remember(committedMaskLayers, maskBitmap)` cache downstream.
            val bmp = android.graphics.Bitmap.createBitmap(bW, bH, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, bW, 0, 0, bW, bH)
            withContext(Dispatchers.Main) {
                maskBitmap = bmp
                maskDirty++
                isMaskModeActive = true
                component.updateMask(bmp)
            }
        }
    }

    /**
     * Color-range mask ("Select Color"). Builds the mask from a set of sampled
     * colours: every source pixel within a colour distance of ANY sample (scaled
     * by [tolerance] 0..100) is selected, with a smooth falloff for soft edges.
     *
     * Distance is measured in an HSV-cone space — hue placed on a chroma-scaled
     * circle (hx,hy) plus value — so it keys on colour like Lightroom does:
     * saturated hues match by hue, near-greys match by brightness, and dark vs
     * bright shades of the same hue separate naturally. Feeds the SAME pipeline
     * as [fillFromSegmentation] (maskBitmap → GL brush layer → adjustments → Apply).
     *
     * @param combine 0=replace, 1=union (OR into existing bitmap), 2=subtract
     *                (carve keyed colour out of existing bitmap). When a live
     *                luma band is the base and combine=2, the keyed colour is
     *                OR'd into the carve-set bitmap and maskLumCombine=1.
     */
    fun fillFromColorRange(samples: List<Int>, tolerance: Float, combine: Int = 0) {
        val neutral = component.neutralBitmap ?: return
        if (samples.isEmpty()) {
            // No samples → clear the color mask only when replacing; leave an
            // existing object/brush bitmap alone in union/subtract arming.
            if (combine == 0) {
                maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                maskBitmap = null
                maskDirty++
                component.updateMask(null)
            }
            return
        }
        val tol = (tolerance / 100f).coerceIn(0f, 1f)
        val radius = 0.05f + tol * 0.55f          // match sphere radius in HSV-cone space
        val rInner = radius * 0.65f               // fully-selected inside this
        // Precompute each sample as (hx, hy, v).
        fun toVec(argb: Int): FloatArray {
            val r = ((argb ushr 16) and 0xFF) / 255f
            val g = ((argb ushr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
            val v = mx; val s = if (mx <= 0f) 0f else d / mx
            val hDeg = when {
                d <= 0f      -> 0f
                mx == r      -> (60f * (((g - b) / d) % 6f))
                mx == g      -> (60f * (((b - r) / d) + 2f))
                else         -> (60f * (((r - g) / d) + 4f))
            }
            val hRad = Math.toRadians(hDeg.toDouble())
            return floatArrayOf((kotlin.math.cos(hRad) * s).toFloat(), (kotlin.math.sin(hRad) * s).toFloat(), v)
        }
        val svecs = samples.map { toVec(it) }
        // Luma-base + chroma carve: grow the carve-set bitmap, live subtract in GPU.
        val lumaBaseCarve = combine == 2 && deltaMacro.maskLumSpread > 0f
        val effectiveCombine = when {
            lumaBaseCarve -> 1 // OR into carve set (handled below + maskLumCombine)
            else -> combine
        }
        maskJob?.cancel()
        maskJob = scope.launch(Dispatchers.Default) {
            // Cap the working resolution — the mask is soft + gets downsampled to
            // 512 on GL upload anyway, so full preview res would be wasted work.
            val longSide = maxOf(neutral.width, neutral.height)
            val scale = if (longSide > 900) 900f / longSide else 1f
            val small = if (scale < 1f)
                android.graphics.Bitmap.createScaledBitmap(
                    neutral, (neutral.width * scale).toInt().coerceAtLeast(1),
                    (neutral.height * scale).toInt().coerceAtLeast(1), true)
            else neutral
            val bW = small.width; val bH = small.height
            val src = IntArray(bW * bH)
            small.getPixels(src, 0, bW, 0, 0, bW, bH)
            if (small !== neutral) small.recycle()
            val out = IntArray(bW * bH)
            var i = 0
            while (i < src.size) {
                if ((i and 0xFFFF) == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val c = src[i]
                val r = ((c ushr 16) and 0xFF) / 255f
                val g = ((c ushr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val dch = mx - mn
                val v = mx; val s = if (mx <= 0f) 0f else dch / mx
                val hDeg = when {
                    dch <= 0f -> 0f
                    mx == r   -> (60f * (((g - b) / dch) % 6f))
                    mx == g   -> (60f * (((b - r) / dch) + 2f))
                    else      -> (60f * (((r - g) / dch) + 4f))
                }
                val hRad = Math.toRadians(hDeg.toDouble())
                val px = (kotlin.math.cos(hRad) * s).toFloat()
                val py = (kotlin.math.sin(hRad) * s).toFloat()
                var best = Float.MAX_VALUE
                for (sv in svecs) {
                    val dx = px - sv[0]; val dy = py - sv[1]; val dv = v - sv[2]
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy + dv * dv)
                    if (dist < best) best = dist
                }
                val a = when {
                    best <= rInner  -> 255
                    best >= radius  -> 0
                    else -> {
                        val t = ((radius - best) / (radius - rInner)).coerceIn(0f, 1f)
                        (t * t * (3f - 2f * t) * 255f).toInt()   // smoothstep
                    }
                }
                out[i] = (a shl 24) or 0x00FFFFFF
                i++
            }
            // Merge with existing bitmap when union/subtract (or luma carve-set).
            val existing = maskBitmap
            if (existing != null && (effectiveCombine == 1 || effectiveCombine == 2 || lumaBaseCarve)) {
                val eW = existing.width; val eH = existing.height
                val merged = IntArray(eW * eH)
                existing.getPixels(merged, 0, eW, 0, 0, eW, eH)
                for (ey in 0 until eH) {
                    if ((ey and 0x1F) == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    for (ex in 0 until eW) {
                        val sx = (ex.toFloat() / (eW - 1).coerceAtLeast(1) * (bW - 1)).toInt().coerceIn(0, bW - 1)
                        val sy = (ey.toFloat() / (eH - 1).coerceAtLeast(1) * (bH - 1)).toInt().coerceIn(0, bH - 1)
                        val keyA = (out[sy * bW + sx] ushr 24) and 0xFF
                        val ei = ey * eW + ex
                        val existA = (merged[ei] ushr 24) and 0xFF
                        val newA = when {
                            lumaBaseCarve || effectiveCombine == 1 -> maxOf(existA, keyA)
                            else -> (existA * (255 - keyA) / 255f).toInt().coerceIn(0, 255) // subtract
                        }
                        merged[ei] = (newA shl 24) or 0x00FFFFFF
                    }
                }
                val bmp = android.graphics.Bitmap.createBitmap(eW, eH, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.setPixels(merged, 0, eW, 0, 0, eW, eH)
                withContext(Dispatchers.Main) {
                    maskBitmap = bmp
                    maskDirty++
                    isMaskModeActive = true
                    if (lumaBaseCarve) deltaMacro = deltaMacro.copy(maskLumCombine = 1)
                    else if (effectiveCombine == 1 && deltaMacro.maskLumSpread > 0f)
                        deltaMacro = deltaMacro.copy(maskLumCombine = 3) // chroma∪luma
                    component.updateMask(bmp)
                }
            } else {
                val bmp = android.graphics.Bitmap.createBitmap(bW, bH, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.setPixels(out, 0, bW, 0, 0, bW, bH)
                withContext(Dispatchers.Main) {
                    maskBitmap = bmp
                    maskDirty++
                    isMaskModeActive = true
                    if (lumaBaseCarve) deltaMacro = deltaMacro.copy(maskLumCombine = 1)
                    else if (effectiveCombine == 1 && deltaMacro.maskLumSpread > 0f)
                        deltaMacro = deltaMacro.copy(maskLumCombine = 3)
                    component.updateMask(bmp)
                }
            }
        }
    }

    /**
     * Invert a luminance-range mask: bake the COMPLEMENT of the tone band
     * [target ± spread] (feathered) into a mask bitmap, mirroring the shader/
     * export `lumMask` formula, then deactivate the GPU luma params so the baked
     * bitmap drives the mask. Lets the halo-free luma selection be inverted while
     * reusing the whole existing mask pipeline (adjustments, further invert, Apply).
     */
    fun bakeInvertedLuminance(target: Float, spread: Float, feather: Float) {
        val neutral = component.neutralBitmap ?: return
        maskJob?.cancel()
        val f = feather.coerceAtLeast(1e-4f)
        val e0 = spread; val e1 = spread + f
        maskJob = scope.launch(Dispatchers.Default) {
            val longSide = maxOf(neutral.width, neutral.height)
            val scale = if (longSide > 900) 900f / longSide else 1f
            val small = if (scale < 1f)
                android.graphics.Bitmap.createScaledBitmap(
                    neutral, (neutral.width * scale).toInt().coerceAtLeast(1),
                    (neutral.height * scale).toInt().coerceAtLeast(1), true)
            else neutral
            val bW = small.width; val bH = small.height
            val srcPx = IntArray(bW * bH)
            small.getPixels(srcPx, 0, bW, 0, 0, bW, bH)
            if (small !== neutral) small.recycle()
            val out = IntArray(bW * bH)
            var i = 0
            while (i < srcPx.size) {
                if ((i and 0xFFFF) == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val c = srcPx[i]
                val r = ((c ushr 16) and 0xFF) / 255f
                val g = ((c ushr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val lum = (r * 0.299f + g * 0.587f + b * 0.114f).coerceIn(0f, 1f)
                val d = kotlin.math.abs(lum - target)
                val t = ((d - e0) / (e1 - e0)).coerceIn(0f, 1f)
                val inBand = 1f - (t * t * (3f - 2f * t))   // 1 inside, feathered out (lumMask)
                val a = ((1f - inBand) * 255f).toInt().coerceIn(0, 255)  // INVERTED
                out[i] = (a shl 24) or 0x00FFFFFF
                i++
            }
            val bmp = android.graphics.Bitmap.createBitmap(bW, bH, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(out, 0, bW, 0, 0, bW, bH)
            withContext(Dispatchers.Main) {
                maskBitmap = bmp
                maskDirty++
                isMaskModeActive = true
                // Deactivate GPU luma params — the baked (inverted) bitmap drives now.
                deltaMacro = deltaMacro.copy(
                    maskLumTarget = 0f, maskLumSpread = 0f, maskLumFeather = 0f, maskLumCombine = 0,
                )
                primaryIsLuma = false
                brushMode = MaskBrushMode.None
                component.updateMask(bmp)
            }
        }
    }

    /** True when nothing has claimed the mask base yet (fresh Add = replace). */
    fun isFreshMaskBase(): Boolean =
        primaryMaskClass == null && !primaryIsLuma && !primaryIsChroma &&
            maskBitmap == null && deltaMacro.maskLumSpread <= 0f && maskColorSamples.isEmpty()

    /**
     * Record an object-class Add. When compositing onto a live luma band with
     * no carve mode yet, switch to union (maskLumCombine=3) so the bitmap is
     * visible alongside the band (legacy mode 0 makes luma win and hides it).
     */
    fun noteObjectClassAdded(cls: MaskClass, isPrime: Boolean) {
        if (isPrime) {
            primaryMaskClass = cls
            primaryIsLuma = false
            primaryIsChroma = false
        } else if (deltaMacro.maskLumSpread > 0f && deltaMacro.maskLumCombine == 0) {
            deltaMacro = deltaMacro.copy(maskLumCombine = 3)
        }
        includedMaskClasses = includedMaskClasses + cls
    }

    /**
     * Synchronous variant of [fillFromSegmentation] used by preset replay.
     * Returns the produced ARGB bitmap directly so the caller can save it
     * to disk and attach `maskPath` to a freshly-built `RawAction`. No
     * state mutation — does not touch [maskBitmap] or `component.updateMask`.
     */
    fun buildSegmentationBitmap(
        floatMask: FloatArray,
        edges: FloatArray? = null,
        threshold: Float = 0.50f,
        snapStrength: Float = 0.60f,
        snapThreshold: Float = 0.20f,
    ): android.graphics.Bitmap? {
        val neutral = component.neutralBitmap ?: return null
        val bW = neutral.width; val bH = neutral.height
        val mSize = 320
        val pixels = IntArray(bW * bH)
        val useEdges = edges != null && edges.size == floatMask.size
        for (y in 0 until bH) {
            for (x in 0 until bW) {
                val mx = x.toFloat() / (bW - 1).coerceAtLeast(1) * (mSize - 1)
                val my = y.toFloat() / (bH - 1).coerceAtLeast(1) * (mSize - 1)
                val x0 = mx.toInt().coerceIn(0, mSize - 2)
                val y0 = my.toInt().coerceIn(0, mSize - 2)
                val dx = mx - x0; val dy = my - y0
                val p = floatMask[y0 * mSize + x0]         * (1 - dx) * (1 - dy) +
                        floatMask[y0 * mSize + x0 + 1]     * dx       * (1 - dy) +
                        floatMask[(y0 + 1) * mSize + x0]   * (1 - dx) * dy       +
                        floatMask[(y0 + 1) * mSize + x0 + 1] * dx     * dy
                val a: Int = if (useEdges) {
                    val edge = edges!![y0 * mSize + x0]         * (1 - dx) * (1 - dy) +
                               edges[y0 * mSize + x0 + 1]       * dx       * (1 - dy) +
                               edges[(y0 + 1) * mSize + x0]     * (1 - dx) * dy       +
                               edges[(y0 + 1) * mSize + x0 + 1] * dx     * dy
                    var v = p
                    if (edge > snapThreshold) {
                        val push = edge * snapStrength
                        v = if (p > threshold) (p + push).coerceAtMost(1f)
                            else (p - push).coerceAtLeast(0f)
                    }
                    val lo = (threshold - 0.05f).coerceAtLeast(0f)
                    val hi = (threshold + 0.05f).coerceAtMost(1f)
                    val t  = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
                    val sm = t * t * (3f - 2f * t)
                    (sm * 255f).toInt().coerceIn(0, 255)
                } else {
                    (p * 255f).toInt().coerceIn(0, 255)
                }
                pixels[y * bW + x] = (a shl 24) or 0x00FFFFFF
            }
        }
        val bmp = android.graphics.Bitmap.createBitmap(bW, bH, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, bW, 0, 0, bW, bH)
        return bmp
    }

    /**
     * Resolve a [MaskClass] to a 320² FloatArray using the SAME subtraction
     * recipes as the editor's per-class fill buttons. Used by preset replay
     * so re-applying a saved Sky / Buildings / Face card on a different
     * photo regenerates the bitmap consistently. Returns null when the
     * required segmentation source isn't available on the current photo
     * (e.g. preset has a Sky card but cityscapesMasks hasn't finished).
     */
    fun resolveMaskForClass(
        cls: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass,
    ): FloatArray? {
        return when (cls) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Subject ->
                segmentationMasks?.subjectMask
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Background ->
                segmentationMasks?.let {
                    subtractMasks(it.backgroundMask, cityscapesMasks?.terrain)
                }
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Sky ->
                cityscapesMasks?.let {
                    val cross = subtractMasks(it.sky,
                        segmentationMasks?.subjectMask,
                        multiclassMasks?.clothes)
                    subtractMasks(cross, it.buildingWall, it.vegetation, boost = 1f)
                }
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Buildings ->
                cityscapesMasks?.let {
                    val subjDilated = segmentationMasks?.subjectMask?.let { s -> dilateMask(s) }
                    val cross = subtractMasks(it.buildingWall, subjDilated,
                        multiclassMasks?.clothes, multiclassMasks?.faceSkin)
                    subtractMasks(cross, it.terrain, boost = 1f)
                }
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Vegetation ->
                cityscapesMasks?.let {
                    val cross = subtractMasks(it.vegetation,
                        segmentationMasks?.subjectMask,
                        multiclassMasks?.clothes)
                    subtractMasks(cross, it.terrain, boost = 1f)
                }
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Terrain ->
                cityscapesMasks?.let {
                    subtractMasks(it.terrain, segmentationMasks?.subjectMask)
                }
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Hair -> {
                // DeepLab hair (LIP class 2) is a dedicated human-parsing signal —
                // prefer it when available, fall back to selfie_multiclass hair.
                val dl = deepLabMasks?.hair
                val mc = multiclassMasks?.hair
                when {
                    dl != null && mc != null && dl.size == mc.size ->
                        FloatArray(dl.size) { i -> maxOf(dl[i], mc[i]).coerceIn(0f, 1f) }
                    dl != null -> dl
                    else -> mc
                }
            }
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.BodySkin -> {
                // DeepLab arms+legs = actual exposed skin area.
                // selfie_multiclass bodySkin covers neck/hands too.
                // MAX union gives the broadest skin selection.
                val dlSkin = deepLabMasks?.bodySkin   // arms + legs computed property
                val mcSkin = multiclassMasks?.bodySkin
                when {
                    dlSkin != null && mcSkin != null && dlSkin.size == mcSkin.size ->
                        FloatArray(dlSkin.size) { i -> maxOf(dlSkin[i], mcSkin[i]).coerceIn(0f, 1f) }
                    dlSkin != null -> dlSkin
                    else -> mcSkin
                }
            }
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.FaceSkin -> {
                val detector = faceMask
                val dlFace   = deepLabMasks?.face    // LIP class 13, pure face region
                val synth    = synthesizeFaceFromContext(multiclassMasks, segmentationMasks?.subjectMask)
                // Merge: DeepLab face + ONNX face detector + context synthesis.
                // More sources = fewer missed faces in group shots.
                val merged = listOfNotNull(detector, dlFace, synth)
                    .reduceOrNull { a, b ->
                        if (a.size == b.size) FloatArray(a.size) { i -> maxOf(a[i], b[i]) } else a
                    } ?: multiclassMasks?.faceSkin
                merged?.let {
                    subtractMasks(it,
                        segmentationMasks?.backgroundMask,
                        multiclassMasks?.hair ?: deepLabMasks?.hair,
                        multiclassMasks?.clothes ?: deepLabMasks?.allClothes,
                        cityscapesMasks?.terrain)
                }
            }
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Clothes -> {
                // DeepLab upper+lower body clothing — more granular than selfie_multiclass.
                val dlClothes = deepLabMasks?.allClothes
                val mcClothes = multiclassMasks?.clothes
                val merged = when {
                    dlClothes != null && mcClothes != null && dlClothes.size == mcClothes.size ->
                        FloatArray(dlClothes.size) { i -> maxOf(dlClothes[i], mcClothes[i]).coerceIn(0f, 1f) }
                    dlClothes != null -> dlClothes
                    else -> mcClothes
                }
                merged?.let { subtractMasks(it, segmentationMasks?.backgroundMask) }
            }
        }
    }

    /**
     * OR-merge resolved float masks per RapidRAW-style screen blend:
     * `out = 1 - Π(1 - mi)`. Returns null when none of the classes
     * resolved (segmentation not ready or model missing).
     */
    fun mergeMaskClasses(
        classes: List<com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass>,
    ): FloatArray? {
        val resolved = classes.mapNotNull { resolveMaskForClass(it) }
        if (resolved.isEmpty()) return null
        if (resolved.size == 1) return resolved[0]
        val n = resolved[0].size
        if (resolved.any { it.size != n }) return resolved[0]
        val out = FloatArray(n)
        for (i in 0 until n) {
            var inv = 1f
            for (m in resolved) inv *= (1f - m[i].coerceIn(0f, 1f))
            out[i] = (1f - inv).coerceIn(0f, 1f)
        }
        return out
    }

    fun removeFromSegmentation(floatMask: FloatArray) {
        // LUMA BASE case: luma is a live GPU band, not a bitmap, so we can't
        // subtract a region out of it in Kotlin. Instead we OR the object region
        // into the shared bitmap (the "carve set") and flag combine = 1
        // (luma × (1 − bitmap)); the shader + export kernel carve it out live.
        if (deltaMacro.maskLumSpread > 0f) {
            fillFromSegmentation(floatMask, additive = true)   // grow the carve set
            deltaMacro = deltaMacro.copy(maskLumCombine = 1)   // luma base − bitmap
            return
        }
        val src = maskBitmap ?: return
        maskJob?.cancel()
        maskJob = scope.launch(Dispatchers.Default) {
            val bW = src.width; val bH = src.height
            val mSize = 320
            val pixels = IntArray(bW * bH)
            src.getPixels(pixels, 0, bW, 0, 0, bW, bH)
            for (y in 0 until bH) {
                if ((y and 0x1F) == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                for (x in 0 until bW) {
                    val mx = x.toFloat() / (bW - 1).coerceAtLeast(1) * (mSize - 1)
                    val my = y.toFloat() / (bH - 1).coerceAtLeast(1) * (mSize - 1)
                    val x0 = mx.toInt().coerceIn(0, mSize - 2)
                    val y0 = my.toInt().coerceIn(0, mSize - 2)
                    val dx = mx - x0; val dy = my - y0
                    val segWeight = floatMask[y0 * mSize + x0]           * (1 - dx) * (1 - dy) +
                                    floatMask[y0 * mSize + x0 + 1]       * dx       * (1 - dy) +
                                    floatMask[(y0 + 1) * mSize + x0]     * (1 - dx) * dy       +
                                    floatMask[(y0 + 1) * mSize + x0 + 1] * dx       * dy
                    val i = y * bW + x
                    val existingA = (pixels[i] ushr 24) and 0xFF
                    val newA = (existingA * (1f - segWeight)).toInt().coerceIn(0, 255)
                    pixels[i] = (newA shl 24) or (pixels[i] and 0x00FFFFFF)
                }
            }
            src.setPixels(pixels, 0, bW, 0, 0, bW, bH)
            withContext(Dispatchers.Main) {
                maskDirty++
                component.updateMask(src)
            }
        }
    }

    fun invertMask() {
        maskJob?.cancel()
        maskJob = scope.launch(Dispatchers.Default) {
            val src = maskBitmap ?: run {
                val neutral = component.neutralBitmap ?: return@launch
                android.graphics.Bitmap.createBitmap(neutral.width, neutral.height, android.graphics.Bitmap.Config.ARGB_8888)
            }
            val w = src.width; val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val a = (pixels[i] ushr 24) and 0xFF
                pixels[i] = ((255 - a) shl 24) or 0x00FFFFFF
            }
            src.setPixels(pixels, 0, w, 0, 0, w, h)
            withContext(Dispatchers.Main) {
                maskBitmap = src
                maskDirty++
                component.updateMask(src)
            }
        }
    }

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
                                    maskBitmap = newBmp
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
                                        maskDirty++
                                        ch.consume()
                                    }
                                }
                                // Pure single-finger tap (no drag, no pinch) → one dab.
                                if (!started && !didTransform) {
                                    val p = screenToBmp(down.position)
                                    bmCanvas.drawLine(p.x, p.y, p.x, p.y, paint)
                                    maskDirty++
                                }
                                component.updateMask(bmp)
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
                                maskColorSamples = updated
                                val colorCombine = when {
                                    chromaSubtractMode -> 2
                                    primaryIsChroma ||
                                        (maskBitmap == null && deltaMacro.maskLumSpread <= 0f) -> 0
                                    else -> 1 // union onto existing bitmap / luma base
                                }
                                fillFromColorRange(updated, maskColorTolerance, colorCombine)
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
                                    isHealing = true
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
                                        isHealing = false
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
                                        // Recycle the prior overlay (if any) — keep `src` only
                                        // if it WAS the overlay (we used it as input).
                                        val old = healedOverlay
                                        // Snapshot the pre-edit baseline ONCE on the first
                                        // dirty tap of this Heal-tab visit. We keep a
                                        // reference to the previous overlay (or null) so
                                        // Cancel can restore it pixel-for-pixel without
                                        // re-running every prior heal.
                                        if (!healDirty) {
                                            healedOverlayPreEdit = old
                                            healCountPreEdit = healCount
                                        }
                                        // Push the pre-tap overlay onto the undo stack so
                                        // Undo last heal can restore it. We never recycle
                                        // bitmaps that the stack (or the pre-edit snapshot)
                                        // still references — Cancel/Apply do the cleanup.
                                        healUndoStack.add(old)
                                        healedOverlay = healed
                                        healCount += 1
                                        healDirty = true
                                    } else {
                                        AppLog.w("RawHeal", "heal returned null")
                                    }
                                    isHealing = false
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
                    val maskLayersForBake by component.maskLayerBitmaps.collectAsState()
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
                    val v3Masks by component.segmentationMasksV3.collectAsState()
                    val pathSnapshot = stageAPath
                    val brushMaskDirtyState by component.maskDirty.collectAsState()
                    // Read the committed mask flow from the component so
                    // Apply-time PNG reloads reach the GL renderer. The
                    // Committed multi-layer masks (up to 4) for post-Apply
                    // replay. While the user is painting a NEW mask, the
                    // in-flight `maskBitmap` drives layer 0 instead (the
                    // layer list is only authoritative once actions commit).
                    val committedMaskLayers by component.maskLayerBitmaps.collectAsState()
                    // Hold-to-compare: while pressed (≥1 s) show the
                    // Stage A + auto-exposure baseline. When autoExposure
                    // is enabled the AE action's light params are the
                    // baseline; identity ShaderParams is used only when
                    // no AE action exists (autoExposure disabled).
                    val aeShaderParams by component.aeShaderParamsFlow.collectAsState()
                    val effectiveParams = if (isComparing)
                        aeShaderParams
                            ?: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams()
                    else if (isFreshMaskSession) {
                        // Suppress COMMITTED mask layer adjustments so the canvas
                        // appears clean while the user creates a new mask — but
                        // never the IN-FLIGHT layer's slot. updateMacro(isMaskEdit
                        // = true) routes the live edit to layer index == committed
                        // mask count (capped at 3); zeroing that slot too made
                        // every Mask-tab slider a silent no-op after Cancel while
                        // the blue overlay still rendered — the reported
                        // "adjustment doesn't apply to the selected mask" bug.
                        val inflightIdx = committedMaskLayers.size.coerceAtMost(3)
                        var zp = params
                        if (inflightIdx != 0) zp = zp.copy(
                            maskBrightness = 0f, maskContrast = 0f,
                            maskTemperature = 0f, maskTint = 0f,
                            maskSaturation = 0f, maskClarity = 0f,
                            maskSharpness = 0f,
                            maskHighlights = 0f, maskShadows = 0f,
                            maskWhites = 0f, maskBlacks = 0f,
                            maskTabOpacity = 1f,
                            maskLumTarget = 0f, maskLumSpread = 0f,
                            maskLumFeather = 0f, maskLumCombine = 0,
                        )
                        if (inflightIdx != 1) zp = zp.copy(
                            mask1Brightness = 0f, mask1Contrast = 0f,
                            mask1Temperature = 0f, mask1Tint = 0f,
                            mask1Saturation = 0f, mask1Clarity = 0f,
                            mask1Sharpness = 0f,
                            mask1Highlights = 0f, mask1Shadows = 0f,
                            mask1Whites = 0f, mask1Blacks = 0f,
                            mask1TabOpacity = 1f,
                            mask1LumTarget = 0f, mask1LumSpread = 0f,
                            mask1LumFeather = 0f,
                        )
                        if (inflightIdx != 2) zp = zp.copy(
                            mask2Brightness = 0f, mask2Contrast = 0f,
                            mask2Temperature = 0f, mask2Tint = 0f,
                            mask2Saturation = 0f, mask2Clarity = 0f,
                            mask2Sharpness = 0f,
                            mask2Highlights = 0f, mask2Shadows = 0f,
                            mask2Whites = 0f, mask2Blacks = 0f,
                            mask2TabOpacity = 1f,
                            mask2LumTarget = 0f, mask2LumSpread = 0f,
                            mask2LumFeather = 0f,
                        )
                        if (inflightIdx != 3) zp = zp.copy(
                            mask3Brightness = 0f, mask3Contrast = 0f,
                            mask3Temperature = 0f, mask3Tint = 0f,
                            mask3Saturation = 0f, mask3Clarity = 0f,
                            mask3Sharpness = 0f,
                            mask3Highlights = 0f, mask3Shadows = 0f,
                            mask3Whites = 0f, mask3Blacks = 0f,
                            mask3TabOpacity = 1f,
                            mask3LumTarget = 0f, mask3LumSpread = 0f,
                            mask3LumFeather = 0f,
                        )
                        zp
                    } else
                        params
                    // Never-blank backdrop: prefer the last graded pause
                    // snapshot (screen-off / background) so GL reboot does
                    // not flash the Stage-A embedded thumbnail ("earliest
                    // process" look). Fall back to that thumbnail only when
                    // no graded snapshot exists yet (first open).
                    val backdropThumb by component.thumbnailBitmapFlow.collectAsState()
                    val backdropBmp = pauseGradedBackdrop ?: backdropThumb
                    backdropBmp?.let { t ->
                        Image(
                            bitmap = t.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (pathSnapshot != null) {
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
                                else if (isFreshMaskSession) emptyList()
                                else committedMaskLayers,
                                onAhbBound = { ahb -> component.setStageBAhb(ahb) },
                                onImageSize = { w, h ->
                                    if (h > 0) imageAspect = w.toFloat() / h.toFloat()
                                },
                                // Capture the live GL view so the Tone Curves
                                // tab can pull a graded-frame histogram when it
                                // opens. (Rendering pauses on that tab, so we
                                // grab the view ref here during normal editing.)
                                onGradedFrameReady = { v -> glViewForHistogram.value = v },
                                onPauseBackdrop = { bmp -> pauseGradedBackdrop = bmp },
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
                                        enabled = !isToneCurvesTab && !isMaskTab && (
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

        RawAdjustmentPanel(
            macro               = deltaMacro,
            onMacroChange       = { deltaMacro = it },
            // Auto-apply: non-mask tabs read their sliders from the committed
            // cards and commit live on every move (no Apply button, no lock).
            composeTabMacro     = { tab -> component.composeTabMacro(tab) },
            onReplaceTabCards   = { tab, cards -> component.replaceTabCards(tab, cards) },
            // AI Color Enhance: enabled when a visible _ai_color_enhance card
            // exists; toggling shows/hides it via the component.
            sceneAutoEnhanceEnabled  = actions.any {
                it.label == "_ai_color_enhance" && it.isVisible
            },
            onSceneAutoEnhanceChange = { component.setAiColorEnhance(it) },
            previewBitmap       = previewBitmap,
            gradedHistogram     = gradedHistogram,
            imageLongSide       = previewDimsForAspect?.let { maxOf(it.first, it.second) }
                ?: CinematicBloomProcessor.REF_LONG_SIDE,
            actions             = actions,
            presets             = presets,
            onSavePreset        = { name ->
                val ok = component.savePreset(name)
                if (ok) presets = component.loadPresetIndex()
                ok
            },
            onLoadPreset        = { index ->
                val loaded = component.loadPreset(index) ?: return@RawAdjustmentPanel
                component.replaceActions(loaded)
                deltaMacro = UserMacro()
            },
            // Settings clipboard — Copy/Paste Settings + Apply from previous.
            // Paste/apply replace the stack, so drop any in-flight delta too.
            onCopySettings      = { component.copySettings() },
            onPasteSettings     = {
                if (component.pasteSettings()) deltaMacro = UserMacro()
            },
            canPasteSettings    = component.canPasteSettings(),
            onApplyPrevious     = {
                if (component.applyPreviousSettings()) deltaMacro = UserMacro()
            },
            canApplyPrevious    = component.canApplyPreviousSettings(),
            onDeletePreset      = { index ->
                component.deletePreset(index)
                presets = component.loadPresetIndex()
            },
            onApplyAction       = { label, tabIndex, currentDelta ->
                // Detect a mask edit by presence of any non-default mask field.
                // If yes, snapshot the brush mask bitmap to disk and link the new
                // action to it. The mask is the live `maskBitmap` (the one the
                // user just painted in the mask tab). Without a snapshot, the
                // next action would inherit/replace this mask and the layers
                // wouldn't stack correctly.
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
                // Reject an Apply that would commit a card carrying zero
                // adjustments. On the Mask tab specifically, an empty
                // commit used to silently shift the brush-mask GL layer
                // mapping → previous masks (subject/sky/face) lost their
                // effect in the preview. Discarding the in-flight delta
                // and bailing out keeps the action stack and GL state in
                // sync.
                val isEmptyMacro = currentDelta == UserMacro()
                if (isEmptyMacro) {
                    // Nothing to commit — drop the in-flight delta and
                    // leave the action stack untouched. Mask-tab GL state
                    // stays bound to the existing top mask.
                    deltaMacro = UserMacro()
                } else {
                val currentMask = maskBitmap
                // Snapshot the included segmentation classes so the new card
                // remembers what auto-mask recipe produced its bitmap. Empty
                // set = hand-painted brush mask (not portable across photos).
                // Ordering is preserved (LinkedHashSet → toList) so replay
                // OR-merges classes in the same order the user selected them.
                val classesForCard = includedMaskClasses.toList()
                val newAction = if (hasMaskEdit && currentMask != null) {
                    val newId = java.util.UUID.randomUUID().toString()
                    val savedPath = com.RAZStudio.StudioRoom.feature.photo_editor.raw
                        .RawMaskStorage.save(context, newId, currentMask)
                    if (savedPath != null) {
                        RawAction(
                            id = newId, label = label, tabIndex = tabIndex,
                            macro = currentDelta, maskPath = savedPath,
                            maskClass = classesForCard.firstOrNull(),
                            maskClasses = classesForCard,
                        )
                    } else {
                        // Save failed: fall back to action without mask layering.
                        // User's adjustments still apply via mergeWith (legacy path).
                        RawAction(label = label, tabIndex = tabIndex, macro = currentDelta)
                    }
                } else {
                    RawAction(
                        label          = label,
                        tabIndex       = tabIndex,
                        macro          = currentDelta,
                        // Mark as auto-exposure when applied from the Light tab while
                        // AE is active so preset reapply recalculates per-photo.
                        isAutoExposure = tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor
                            .presentation.raw.components.TAB_LIGHT && lightAeActive,
                    )
                }
                if (tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor
                        .presentation.raw.components.TAB_LIGHT) {
                    lightAeActive = false
                }
                // Drop the editor-local in-flight mask BEFORE committing so
                // the subsequent addAction → rebuildShaderParams →
                // publishTopmostMaskBitmap can load the freshly saved PNG
                // into the GL brush mask without our nulling it back to
                // empty afterwards.
                if (hasMaskEdit) {
                    maskBitmap = null
                    includedMaskClasses = emptySet()
                    primaryMaskClass = null
                    primaryIsLuma = false
                    primaryIsChroma = false
                    chromaSubtractMode = false
                    maskColorSamples = emptyList()
                    isMaskModeActive = false
                    isFreshMaskSession = false
                }
                component.addAction(newAction)
                // v2-integration §B.5 — commit a sidecar history checkpoint. The new
                // baseline-with-this-action becomes the head; the previous head moves
                // into the revisions list. SidecarStore caps the stack at 50 entries.
                // Fire-and-forget on the Main scope; failure is non-fatal (logged inside
                // SidecarStore).
                scope.launch { component.pushSidecarRevision() }
                deltaMacro = UserMacro()
                } // end else branch (committed a non-empty action)
            },
            onCancelAction      = {
                deltaMacro = UserMacro(); lightAeActive = false
                // Drop any in-flight painted mask too. A stale maskBitmap gets
                // appended to the GL brush layers (takeLast(4) then evicts a REAL
                // committed layer) and injects a phantom in-flight mask layer —
                // corrupting multi-mask rendering. Apply already nulls it; Cancel
                // must as well.
                maskJob?.cancel(); maskJob = null
                maskBitmap = null
                includedMaskClasses = emptySet()
                primaryMaskClass = null
                primaryIsLuma = false
                primaryIsChroma = false
                chromaSubtractMode = false
                maskColorSamples = emptyList()
                isMaskModeActive = false
                brushMode = MaskBrushMode.None
                component.updateMask(null)
                // Mark fresh session so committed mask layers are suppressed
                // from GL while the user creates a new mask.
                isFreshMaskSession = true
            },
            onLoadAction        = { action ->
                deltaMacro = action.macro
                if (action.isAutoExposure) lightAeActive = true
                // Re-editing a committed card — not a fresh session.
                isFreshMaskSession = false
                // M-fix: restore the painted mask bitmap when re-editing a
                // mask card. The action carries the PNG path on disk; without
                // re-loading it into `maskBitmap`, the user would land on the
                // Mask tab with the sliders restored but an empty brush
                // canvas — the original painted region would appear "lost"
                // and any new strokes wouldn't stack with the previous shape.
                //
                // For non-mask cards we explicitly clear any leftover
                // in-flight mask from a prior session — otherwise tapping a
                // Color/Light/Vignette card after touching the Mask tab
                // would leave a stale mask bitmap visible.
                val path = action.maskPath
                if (path != null) {
                    val restored = com.RAZStudio.StudioRoom.feature.photo_editor.raw
                        .RawMaskStorage.loadFromPath(path)
                    if (restored != null) {
                        maskBitmap = restored
                        maskDirty++
                        component.updateMask(restored)
                        // Show the blue overlay so the user sees the committed
                        // mask region on the canvas when re-editing this card.
                        isMaskModeActive = true
                    }
                } else if (action.tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor
                        .presentation.raw.components.TAB_MASK_LAYERS) {
                    // Shader-based mask (luminance/no bitmap) — activate overlay
                    // so the user sees the blue tint from the luma params in the
                    // macro. The GL renderer generates the mask per-pixel from
                    // maskLumTarget/Spread/Feather uniforms.
                    maskBitmap = null
                    val hasLuma = action.macro.maskLumSpread > 0f
                    isMaskModeActive = true
                    if (hasLuma) brushMode = MaskBrushMode.LumaSelect
                } else {
                    maskBitmap = null
                }
                // Loaded action's bitmap is opaque — we can't re-derive which
                // segmentation classes produced it. Start the per-class
                // inclusion tracker fresh so the user can layer additional
                // classes via split-buttons on top of the loaded mask.
                includedMaskClasses = emptySet()
                primaryMaskClass = null
                primaryIsLuma = action.macro.maskLumSpread > 0f && action.maskPath == null
                primaryIsChroma = false
                chromaSubtractMode = false
            },
            onRestoreAction     = { idx, action -> component.restoreActionAt(idx, action) },
            onDeleteAction      = { id -> component.deleteAction(id) },
            onEyeToggleAction   = { id -> component.toggleEye(id) },
            onToggleLockAction  = { id -> component.toggleLock(id) },
            onExportToEditor    = {
                component.updateMacro(deltaMacro)
                // Wait for any in-flight preset apply to finish + give the
                // GL thread two frames to redraw with the final card stack
                // BEFORE snapshotting. Without this the Export page can
                // capture a partially-applied preset (e.g. shows the photo
                // with only the first 2/5 cards rendered, then the user sees
                // a different look from what saves a moment later).
                scope.launch {
                    if (presetApplyInFlight) {
                        // Block on the StateFlow flag until cards finish landing.
                        while (presetApplyInFlight) {
                            kotlinx.coroutines.delay(50)
                        }
                    }
                    // One extra frame so the GL renderer has consumed the
                    // final shaderParamsFlow value triggered by addAction.
                    kotlinx.coroutines.delay(100)
                    val graded = captureGradedCanvas()
                    component.setGradedPreview(graded)
                    // Project photo: the tile should show what is about to be exported.
                    if (graded != null && component.projectContext != null) {
                        component.persistProjectThumbnail(graded)
                    }
                    component.navigateToRawExport()
                }
            },
            onTabSelected       = { tabIndex ->
                // The Tone Curves editor lives under TAB_CURVES_LUT (index 2). The legacy
                // TAB_TONE_CURVES constant is 4, which now collides with the FX tab
                // (TAB_EFFECTS = 4) — using it here misidentified FX as Tone Curves, which
                // hid the panel resize handle on FX and skipped the curve pause/snapshot on
                // the real Curves tab. Key off the current constant so every non-curves tab
                // (FX included) keeps the uniform drag-to-resize handle.
                isToneCurvesTab = tabIndex == com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.TAB_CURVES_LUT
                // Mask tab keeps the Local-tab masking behavior: zoom/pan stays
                // available except while actively painting (gated by brushMode in
                // the transformable below), so isMaskTab is intentionally NOT set
                // for TAB_MASK_LAYERS (that flag fully locks zoom — undesired here).
                val nowMask = false
                isMaskTab = nowMask
                // Tone Curves and Mask tabs show the photo static/fit — reset zoom/pan.
                if (isToneCurvesTab || nowMask) {
                    canvasScale  = 1f
                    canvasOffset = androidx.compose.ui.geometry.Offset.Zero
                }
                // Force a GL redraw immediately after tab switch so the SurfaceView
                // doesn't show a blank frame while Compose recomposes the panel.
                glViewForHistogram.value?.requestRender()
                // Phase-2: suppress/resume via GradingPipeline instead of the
                // old suppressGradedBake flag. No source swap on tab switch.
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
            isVignetteCenterMode       = isVignetteCenterMode,
            onVignetteCenterModeChange = { isVignetteCenterMode = it },
            healRadiusPx               = healRadiusPx,
            onHealRadiusChange         = { healRadiusPx = it },
            healActive                 = healActive,
            onHealActiveChange         = { healActive = it },
            isHealing                  = isHealing,
            healCount                  = healCount,
            onUndoLastHeal             = {
                // Pop the most recent pre-tap overlay and restore it,
                // recycling the current (post-tap) overlay so pixels
                // free instead of leaking. healDirty stays true as long
                // as at least one tap remains in this Heal session —
                // once the stack empties (back to the baseline) we drop
                // the dirty flag so Apply/Cancel disappear.
                if (healUndoStack.isNotEmpty()) {
                    val restored = healUndoStack.removeAt(healUndoStack.lastIndex)
                    val current = healedOverlay
                    if (current != null && current !== restored &&
                        current !== healedOverlayPreEdit) current.recycle()
                    healedOverlay = restored
                    if (healCount > 0) healCount -= 1
                    if (healUndoStack.isEmpty()) {
                        // Back to the pre-edit state — clear the dirty
                        // flag so the Apply/Cancel bar collapses.
                        healDirty = false
                        healedOverlayPreEdit = null
                    }
                }
            },
            onHealModeActive           = { active ->
                // Leaving the tab implicitly disables the toggle so the
                // canvas regains gestures.
                if (!active && healActive) healActive = false
            },
            healDirty                  = healDirty,
            onHealApply                = {
                // Commit a Heal action card with a label reflecting how
                // many taps were folded into this card. The healed bitmap
                // stays as the live in-memory overlay so the canvas keeps
                // showing the inpainted result; the card's existence in
                // Actions makes the heal addressable for delete / hide.
                val count = healCount - healCountPreEdit
                val label = "Heal" + if (count > 1) " ×$count" else ""
                component.addAction(
                    RawAction(
                        label = label,
                        tabIndex = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.TAB_HEAL,
                        macro = UserMacro(),
                    )
                )
                // The just-committed state becomes the new baseline:
                // future Heal-tab visits Cancel back to THIS overlay,
                // not to whatever was there before.
                healedOverlayPreEdit = null
                healCountPreEdit = healCount
                healDirty = false
                healActive = false
                // Drop intermediate snapshots — the committed overlay
                // is the new baseline; nothing in the stack is reachable
                // anymore. Recycle each unless it's still in use as the
                // pre-edit reference or the live overlay.
                healUndoStack.forEach { b ->
                    if (b != null && b !== healedOverlay) {
                        runCatching { b.recycle() }
                    }
                }
                healUndoStack.clear()
            },
            onHealCancel               = {
                // Revert overlay + count to the snapshot taken on tab
                // entry. The discarded `healedOverlay` is recycled
                // unless it's actually the snapshot (no-op case).
                val current = healedOverlay
                val snapshot = healedOverlayPreEdit
                if (current != null && current !== snapshot) current.recycle()
                healedOverlay = snapshot
                healedOverlayPreEdit = null
                healCount = healCountPreEdit
                healDirty = false
                healActive = false
                // Recycle every intermediate snapshot (none are reachable
                // after Cancel) except the one we just restored as live.
                healUndoStack.forEach { b ->
                    if (b != null && b !== healedOverlay) {
                        runCatching { b.recycle() }
                    }
                }
                healUndoStack.clear()
            },
            isNonRawSource             = isNonRawSource,
            onExportActions     = {
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
            onImportActions     = { actionsImportPicker.launch(arrayOf("text/xml", "application/xml")) },
            onSaveEditAsLut     = { name -> component.exportEditAsLut(name) },
            segmentationMasks   = segmentationMasks,
            onMaskModeActive    = { isMaskModeActive = it },
            // showMaskOverlay piggybacks on isMaskModeActive — same flag,
            // just exposed via a second name so the Show button can
            // toggle it independently of the tab selection. The setter
            // also clears brushMode when overlay goes off so paint /
            // tap-select don't stay armed on a no-overlay canvas.
            showMaskOverlay     = isMaskModeActive,
            onShowMaskOverlayChange = { newShow ->
                isMaskModeActive = newShow
                if (!newShow) brushMode = MaskBrushMode.None
            },
            brushMode           = brushMode,
            onBrushModeChange   = { brushMode = it },
            sharpSpread         = sharpSpread,
            onSharpSpreadChange = { sharpSpread = it },
            onFillSharp         = {
                segmentationMasks?.let {
                    fillFromSegmentationSharp(it.subjectMask, it.edgeMask, sharpSpread)
                }
            },
            // Light-tab Auto button: compute slider values from a
            // percentile histogram of the cached Stage A thumbnail,
            // then COMMIT as an action card labelled "AUTO EXPO" with
            // the `isAutoExposure` marker. The marker is what lets
            // saved presets recompute Auto per-file on apply rather
            // than carrying the original photo's exposure numbers
            // forward (different RAWs need different lifts).
            // On-demand segmentation: entering the Mask / Gradient tabs kicks off
            // the ONNX chain (it no longer runs eagerly at open — that was the OOM).
            onSegmentationNeeded = { component.ensureSegmentation() },
            // Grey subject-mask-dependent controls (Bokeh, subject/background
            // Vignette & Gradient) while detection runs; re-enabled when the
            // subject mask arrives or the chain ends.
            subjectSegBusy      = subjectSegBusy,
            subjectDetected     = segmentationMasksV3OuterScope?.hasSubject ?: true,
            onLightAuto         = {
                val src = component.neutralBitmap ?: return@RawAdjustmentPanel
                // AI Expose subject protection needs masks — start segmentation now
                // (idempotent). First tap may run global if masks aren't ready yet;
                // adjusting protection after they arrive gives the subject-aware result.
                component.ensureSegmentation()
                val masks = component.segmentationMasks.value
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
                        // Register immediately as its own action card so it appears
                        // in the Actions tab without requiring an explicit Apply press.
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
            lightAutoEnabled    = component.neutralBitmap != null,
            lightAeActive       = lightAeActive,
            onLightAutoOff      = {
                // Remove the most recent AE action card from the stack.
                actions.lastOrNull { it.isAutoExposure }?.let { component.deleteAction(it.id) }
                lightAeActive = false
            },
            onLightBasicAuto    = {
                // Basic Auto: instant GLOBAL auto-brightness/levels. No subject
                // masks, no U2Net wait, no ISO noise-reduction (iso=0) — just the
                // percentile exposure/blacks/whites/highlights/shadows solve.
                // Commits as the auto-exposure overlay (singleton, so it and AI
                // Expose are mutually exclusive — tapping one replaces the other).
                val src = component.neutralBitmap ?: return@RawAdjustmentPanel
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
            onLightAeProtectionChange = { prot ->
                // Re-run AE with updated protection level, keeping current result
                // as the base so other fields aren't lost.
                val src = component.neutralBitmap ?: return@RawAdjustmentPanel
                component.ensureSegmentation()
                val masks = component.segmentationMasks.value
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
                        // Re-commit the AUTO EXPO card with the re-analysed result
                        // (addAction replaces the AE singleton). Live, no Apply.
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
            // AI Expose is never locked — the workspace-level "Auto Expose on Open"
            // toggle has been removed; the manual AI Expose toggle is always user-controllable.
            lightAeLocked       = false,
            // Smart Bright: adjust freely (like Exposure) until Apply. It locks
            // ONLY once a COMMITTED action carries it — i.e. after the user
            // presses Apply, registering the card. Deleting that card unlocks it.
            // (No commit on slider release, so it isn't locked mid-edit.)
            smartBright         = actions.firstOrNull { it.isVisible && it.macro.smartBright > 0f }
                ?.macro?.smartBright ?: deltaMacro.smartBright,
            smartBrightLocked   = actions.any { it.isVisible && it.macro.smartBright > 0f },
            asShotKelvin        = component.asShotKelvin.collectAsState().value,
            brushSize           = brushSize,
            onBrushSize         = { brushSize = it },
            brushIntensity      = brushIntensity,
            onBrushIntensity    = { brushIntensity = it },
            brushFeather        = brushFeather,
            onBrushFeather      = { brushFeather = it },
            colorTolerance      = maskColorTolerance,
            onColorToleranceChange = {
                maskColorTolerance = it
                // Re-key the range live as the Refine slider moves.
                if (maskColorSamples.isNotEmpty()) {
                    val colorCombine = when {
                        chromaSubtractMode -> 2
                        primaryIsChroma ||
                            (maskBitmap == null && deltaMacro.maskLumSpread <= 0f) -> 0
                        else -> 1
                    }
                    fillFromColorRange(maskColorSamples, it, colorCombine)
                }
            },
            colorSampleArgb     = maskColorSamples.lastOrNull() ?: 0,
            onClearColorSamples = {
                maskColorSamples = emptyList()
                fillFromColorRange(emptyList(), maskColorTolerance)
            },
            chromaSubtractMode  = chromaSubtractMode,
            hasMask             = maskBitmap != null,
            onClearMask         = {
                maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                component.updateMask(null)
                maskBitmap = null
                maskColorSamples = emptyList()
                chromaSubtractMode = false
                // Also deactivate a luminance-range mask (GPU, no bitmap) and
                // reset the luma↔bitmap carve combine mode.
                deltaMacro = deltaMacro.copy(maskLumTarget = 0f, maskLumSpread = 0f, maskLumFeather = 0f, maskLumCombine = 0)
                includedMaskClasses = emptySet()
                primaryMaskClass = null
                primaryIsLuma = false
                primaryIsChroma = false
            },
            onAddLuma = {
                isMaskModeActive = true
                chromaSubtractMode = false
                val fresh = isFreshMaskBase()
                if (fresh) {
                    // Luma becomes the base — clear any stale state first.
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null)
                    maskBitmap = null
                    maskColorSamples = emptyList()
                    includedMaskClasses = emptySet()
                    primaryMaskClass = null
                    primaryIsLuma = true
                    primaryIsChroma = false
                    brushMode = MaskBrushMode.LumaSelect
                    deltaMacro = deltaMacro.copy(
                        maskLumTarget = 0.80f, maskLumSpread = 0.12f,
                        maskLumFeather = 0.15f, maskLumCombine = 0,
                    )
                } else {
                    // Union onto an existing bitmap/object/chroma base.
                    brushMode = MaskBrushMode.LumaSelect
                    val spread = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumSpread else 0.12f
                    deltaMacro = deltaMacro.copy(
                        maskLumTarget = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumTarget else 0.80f,
                        maskLumSpread = spread,
                        maskLumFeather = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumFeather else 0.15f,
                        maskLumCombine = 3, // bitmap ∪ luma
                    )
                }
            },
            onRemoveLuma = {
                val lumaIsBase = primaryIsLuma ||
                    (deltaMacro.maskLumSpread > 0f && maskBitmap == null &&
                        primaryMaskClass == null && !primaryIsChroma)
                when {
                    lumaIsBase -> {
                        // Base Remove clears everything.
                        maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                        component.updateMask(null)
                        maskBitmap = null
                        maskColorSamples = emptyList()
                        chromaSubtractMode = false
                        deltaMacro = deltaMacro.copy(
                            maskLumTarget = 0f, maskLumSpread = 0f,
                            maskLumFeather = 0f, maskLumCombine = 0,
                        )
                        includedMaskClasses = emptySet()
                        primaryMaskClass = null
                        primaryIsLuma = false
                        primaryIsChroma = false
                        brushMode = MaskBrushMode.None
                    }
                    maskBitmap != null || primaryMaskClass != null || primaryIsChroma ||
                        maskColorSamples.isNotEmpty() -> {
                        // Carve luma band out of the bitmap base (live GPU).
                        isMaskModeActive = true
                        chromaSubtractMode = false
                        brushMode = MaskBrushMode.LumaSelect
                        deltaMacro = deltaMacro.copy(
                            maskLumTarget = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumTarget else 0.80f,
                            maskLumSpread = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumSpread else 0.12f,
                            maskLumFeather = if (deltaMacro.maskLumSpread > 0f) deltaMacro.maskLumFeather else 0.15f,
                            maskLumCombine = 2, // bitmap − luma
                        )
                    }
                    else -> {
                        deltaMacro = deltaMacro.copy(
                            maskLumTarget = 0f, maskLumSpread = 0f,
                            maskLumFeather = 0f, maskLumCombine = 0,
                        )
                        primaryIsLuma = false
                        brushMode = MaskBrushMode.None
                    }
                }
            },
            onAddChroma = {
                isMaskModeActive = true
                chromaSubtractMode = false
                val fresh = isFreshMaskBase()
                if (fresh) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null)
                    maskBitmap = null
                    maskColorSamples = emptyList()
                    includedMaskClasses = emptySet()
                    primaryMaskClass = null
                    primaryIsLuma = false
                    primaryIsChroma = true
                    // Clear any leftover luma band — chroma is the new base.
                    deltaMacro = deltaMacro.copy(
                        maskLumTarget = 0f, maskLumSpread = 0f,
                        maskLumFeather = 0f, maskLumCombine = 0,
                    )
                }
                // Non-fresh: keep existing bitmap/luma; taps union keyed colour in.
                brushMode = MaskBrushMode.ColorSelect
            },
            onRemoveChroma = {
                val chromaIsBase = primaryIsChroma ||
                    (maskColorSamples.isNotEmpty() && primaryMaskClass == null &&
                        !primaryIsLuma && deltaMacro.maskLumSpread <= 0f)
                when {
                    chromaIsBase && maskColorSamples.isNotEmpty() -> {
                        // Base Remove clears everything.
                        maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                        component.updateMask(null)
                        maskBitmap = null
                        maskColorSamples = emptyList()
                        chromaSubtractMode = false
                        deltaMacro = deltaMacro.copy(
                            maskLumTarget = 0f, maskLumSpread = 0f,
                            maskLumFeather = 0f, maskLumCombine = 0,
                        )
                        includedMaskClasses = emptySet()
                        primaryMaskClass = null
                        primaryIsLuma = false
                        primaryIsChroma = false
                        brushMode = MaskBrushMode.None
                    }
                    maskBitmap != null || primaryMaskClass != null ||
                        deltaMacro.maskLumSpread > 0f || primaryIsLuma -> {
                        // Arm chroma carve: next colour taps subtract from base.
                        isMaskModeActive = true
                        chromaSubtractMode = true
                        primaryIsChroma = false
                        brushMode = MaskBrushMode.ColorSelect
                        // Hint text in the ColorSelect row is easy to miss —
                        // snackbar so the user knows to tap the photo.
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Tap the photo to carve a colour",
                                duration = androidx.compose.material3.SnackbarDuration.Short,
                            )
                        }
                    }
                    else -> {
                        maskColorSamples = emptyList()
                        primaryIsChroma = false
                        chromaSubtractMode = false
                        brushMode = MaskBrushMode.None
                    }
                }
            },
            onFillSubject       = {
                segmentationMasks?.let {
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(it.subjectMask, edges = it.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.Subject, isPrime)
                }
            },
            // Background = everything EXCEPT the subject (invert-of-subject).
            // This gives "select all but the person" in one tap, which is what
            // users mean when they say "select background" on a portrait.
            onFillBackground    = {
                segmentationMasks?.let {
                    val excludeSubject = subtractMasks(it.backgroundMask, it.subjectMask)
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(excludeSubject, edges = it.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.Background, isPrime)
                }
            },
            onRemoveSubject     = {
                if (primaryMaskClass == MaskClass.Subject) {
                    // Removing the prime — wipe the whole mask and reset.
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null)
                    maskBitmap = null
                    includedMaskClasses = emptySet()
                    primaryMaskClass = null
                    primaryIsLuma = false
                    primaryIsChroma = false
                    chromaSubtractMode = false
                    deltaMacro = deltaMacro.copy(
                        maskLumTarget = 0f, maskLumSpread = 0f,
                        maskLumFeather = 0f, maskLumCombine = 0,
                    )
                } else {
                    segmentationMasks?.let { removeFromSegmentation(it.subjectMask) }
                    includedMaskClasses = includedMaskClasses - MaskClass.Subject
                }
            },
            onRemoveBackground  = {
                if (primaryMaskClass == MaskClass.Background) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null)
                    maskBitmap = null
                    includedMaskClasses = emptySet()
                    primaryMaskClass = null
                    primaryIsLuma = false
                    primaryIsChroma = false
                    chromaSubtractMode = false
                    deltaMacro = deltaMacro.copy(
                        maskLumTarget = 0f, maskLumSpread = 0f,
                        maskLumFeather = 0f, maskLumCombine = 0,
                    )
                } else {
                    segmentationMasks?.let { removeFromSegmentation(it.backgroundMask) }
                    includedMaskClasses = includedMaskClasses - MaskClass.Background
                }
            },
            onInvertMask        = {
                // Luminance mask has no bitmap — invert by baking the COMPLEMENT
                // of the tone band into a mask bitmap (then it behaves like any
                // other mask). Otherwise invert the existing bitmap (brush/chroma).
                if (deltaMacro.maskLumSpread > 0f) {
                    bakeInvertedLuminance(deltaMacro.maskLumTarget, deltaMacro.maskLumSpread, deltaMacro.maskLumFeather)
                } else {
                    invertMask()
                }
                includedMaskClasses = emptySet()
                primaryMaskClass = null
                primaryIsLuma = false
                primaryIsChroma = false
                chromaSubtractMode = false
            },
            // MediaPipe multiclass — each Select button hands a
            // FloatArray of 0/1 values through the existing
            // fillFromSegmentation pipeline. Buttons are hidden when
            // multiclassMasks is null (model not loaded or
            // inference not yet finished).
            // Buttons are visible once ANY human-parsing source is ready:
            // selfie_multiclass, DeepLabV3p, or the ONNX face detector.
            isMulticlassLoading  = multiclassLoadingState && multiclassMasks == null && faceMask == null && deepLabMasks == null,
            hasMulticlass       = multiclassMasks != null || faceMask != null || deepLabMasks != null,
            onFillHair          = {
                val mask = resolveMaskForClass(MaskClass.Hair)
                if (mask != null) {
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(mask, edges = segmentationMasks?.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.Hair, isPrime)
                }
            },
            onFillBodySkin      = {
                val mask = resolveMaskForClass(MaskClass.BodySkin)
                if (mask != null) {
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(mask, edges = segmentationMasks?.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.BodySkin, isPrime)
                }
            },
            onFillFaceSkin      = {
                val mask = resolveMaskForClass(MaskClass.FaceSkin)
                if (mask != null) {
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(mask, edges = segmentationMasks?.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.FaceSkin, isPrime)
                }
            },
            onFillClothes       = {
                val mask = resolveMaskForClass(MaskClass.Clothes)
                if (mask != null) {
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(mask, edges = segmentationMasks?.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.Clothes, isPrime)
                }
            },
            onRemoveHair        = {
                if (primaryMaskClass == MaskClass.Hair) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null); maskBitmap = null
                    includedMaskClasses = emptySet(); primaryMaskClass = null
                } else {
                    val mask = deepLabMasks?.hair ?: multiclassMasks?.hair
                    mask?.let { removeFromSegmentation(it) }
                    includedMaskClasses = includedMaskClasses - MaskClass.Hair
                }
            },
            onRemoveBodySkin    = {
                if (primaryMaskClass == MaskClass.BodySkin) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null); maskBitmap = null
                    includedMaskClasses = emptySet(); primaryMaskClass = null
                } else {
                    val mask = deepLabMasks?.bodySkin ?: multiclassMasks?.bodySkin
                    mask?.let { removeFromSegmentation(it) }
                    includedMaskClasses = includedMaskClasses - MaskClass.BodySkin
                }
            },
            onRemoveFaceSkin    = {
                if (primaryMaskClass == MaskClass.FaceSkin) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null); maskBitmap = null
                    includedMaskClasses = emptySet(); primaryMaskClass = null
                } else {
                    val mask = faceMask ?: deepLabMasks?.face ?: multiclassMasks?.faceSkin
                    mask?.let { removeFromSegmentation(it) }
                    includedMaskClasses = includedMaskClasses - MaskClass.FaceSkin
                }
            },
            onRemoveClothes     = {
                if (primaryMaskClass == MaskClass.Clothes) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null); maskBitmap = null
                    includedMaskClasses = emptySet(); primaryMaskClass = null
                } else {
                    val mask = deepLabMasks?.allClothes ?: multiclassMasks?.clothes
                    mask?.let { removeFromSegmentation(it) }
                    includedMaskClasses = includedMaskClasses - MaskClass.Clothes
                }
            },
            // Cityscapes — 4 landscape mask classes, with subject subtracted.
            // The U²Net subject mask wins ties: when a pixel reads as "person"
            // it's removed from sky/building/vegetation/terrain so a Sky-tap
            // on a portrait doesn't paint the figure's silhouette as sky.
            // When no subject mask is present (subjectDetection off, or
            // U²Net hasn't finished), the class plane passes through
            // unchanged — matches the no-subject expectation.
            isCityscapesLoading  = cityscapesLoadingState,
            hasCityscapes       = cityscapesMasks != null,
            // Cityscapes class exclusions to clean up soft-confidence
            // overlaps at boundaries (subject mask + within-cityscapes class
            // bleeds + cross-model bleeds from MediaPipe clothes/face):
            //   • Sky      − Subject − Building − Plants − Clothes
            //   • Plants   − Subject − Terrain − Clothes
            //   • Building − Subject − Terrain − Clothes − Face
            //   • Terrain  − Subject
            // Cloth/face subtractions catch SegFormer over-classifying a
            // person's torso as "building" or sky leaking into hair.
            onFillBuildingWall  = {
                cityscapesMasks?.let {
                    val subjDilated = segmentationMasks?.subjectMask?.let { s -> dilateMask(s) }
                    val crossVendorCleared = subtractMasks(
                        it.buildingWall,
                        subjDilated,
                        multiclassMasks?.clothes, multiclassMasks?.faceSkin,
                    )
                    val finalMask = subtractMasks(
                        crossVendorCleared, it.terrain,
                        boost = 1f,
                    )
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(finalMask, edges = segmentationMasks?.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.Buildings, isPrime)
                }
            },
            onFillVegetation    = {
                cityscapesMasks?.let {
                    val crossVendorCleared = subtractMasks(
                        it.vegetation,
                        segmentationMasks?.subjectMask,
                        multiclassMasks?.clothes,
                    )
                    val finalMask = subtractMasks(
                        crossVendorCleared, it.terrain,
                        boost = 1f,
                    )
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(finalMask, edges = segmentationMasks?.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.Vegetation, isPrime)
                }
            },
            onFillTerrain       = {
                cityscapesMasks?.let {
                    val finalMask = subtractMasks(it.terrain,
                        segmentationMasks?.subjectMask)
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(finalMask, edges = segmentationMasks?.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.Terrain, isPrime)
                }
            },
            onFillSky           = {
                cityscapesMasks?.let {
                    // Two-pass subtraction:
                    //  • cross-vendor masks (U²Net subject, MediaPipe clothes)
                    //    use ×3 boost so soft silhouettes fully clear sky.
                    //  • same-vendor Cityscapes classes (building, vegetation)
                    //    use ×1 because SegFormer softmax is already sharp.
                    // Then edge-snap to the photo's Sobel edges so the soft
                    // 320² boundary locks onto real foliage edges instead of
                    // leaving 8–15 px fuzzy halos between tree and sky.
                    val crossVendorCleared = subtractMasks(
                        it.sky,
                        segmentationMasks?.subjectMask,
                        multiclassMasks?.clothes,
                    )
                    val finalMask = subtractMasks(
                        crossVendorCleared,
                        it.buildingWall, it.vegetation,
                        boost = 1f,
                    )
                    val isPrime = isFreshMaskBase()
                    fillFromSegmentation(finalMask, edges = segmentationMasks?.edgeMask, additive = !isPrime)
                    noteObjectClassAdded(MaskClass.Sky, isPrime)
                }
            },
            onRemoveBuildingWall = {
                if (primaryMaskClass == MaskClass.Buildings) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null); maskBitmap = null
                    includedMaskClasses = emptySet(); primaryMaskClass = null
                } else {
                    cityscapesMasks?.let { removeFromSegmentation(it.buildingWall) }
                    includedMaskClasses = includedMaskClasses - MaskClass.Buildings
                }
            },
            onRemoveVegetation   = {
                if (primaryMaskClass == MaskClass.Vegetation) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null); maskBitmap = null
                    includedMaskClasses = emptySet(); primaryMaskClass = null
                } else {
                    cityscapesMasks?.let { removeFromSegmentation(it.vegetation) }
                    includedMaskClasses = includedMaskClasses - MaskClass.Vegetation
                }
            },
            onRemoveTerrain      = {
                if (primaryMaskClass == MaskClass.Terrain) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null); maskBitmap = null
                    includedMaskClasses = emptySet(); primaryMaskClass = null
                } else {
                    cityscapesMasks?.let { removeFromSegmentation(it.terrain) }
                    includedMaskClasses = includedMaskClasses - MaskClass.Terrain
                }
            },
            onRemoveSky          = {
                if (primaryMaskClass == MaskClass.Sky) {
                    maskBitmap?.eraseColor(android.graphics.Color.TRANSPARENT)
                    component.updateMask(null); maskBitmap = null
                    includedMaskClasses = emptySet(); primaryMaskClass = null
                } else {
                    cityscapesMasks?.let { removeFromSegmentation(it.sky) }
                    includedMaskClasses = includedMaskClasses - MaskClass.Sky
                }
            },
            includedMaskClasses = includedMaskClasses,
            primaryMaskClass    = primaryMaskClass,
            isFullResReady      = fullResReady,
            isFullResProcessing = isFullResProcessing,
            modifier            = modifier,
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

            // Auto-fit canvas slot to image aspect ratio.
            //   Photo aspect-fits inside the canvas slot via .aspectRatio()
            //   below. For a landscape photo (e.g. 3:2) in a square-ish
            //   canvas slot, the photo touches the side edges and leaves
            //   letterbox above + below. We compute the EXACT canvasFraction
            //   that makes the canvas slot the photo's aspect — so letterbox
            //   collapses to zero and the panel grows up to claim the space.
            //
            //   Skipped when the user has manually dragged the splitter
            //   handle (canvasFractionUserOverridden) so manual control
            //   always wins.
            //
            //   Math:
            //     Portrait: canvas width  = totalWidthPx (full row width)
            //               required H    = totalWidthPx / imageAspect
            //               fraction      = required H / totalHeightPx
            //     Landscape: canvas height = totalHeightPx (full column height)
            //                required W    = totalHeightPx * imageAspect
            //                fraction      = required W / totalWidthPx
            //   Both clamped to [0.25, 0.75] (same range the manual drag uses)
            //   so an extreme aspect ratio doesn't squash the panel to nothing
            //   or crowd the canvas out entirely.
            androidx.compose.runtime.LaunchedEffect(
                imageAspect, isLandscape, totalWidthPx, totalHeightPx,
                canvasFractionUserOverridden,
            ) {
                if (canvasFractionUserOverridden) return@LaunchedEffect
                if (imageAspect <= 0f || totalWidthPx <= 0f || totalHeightPx <= 0f)
                    return@LaunchedEffect
                val needed = if (isLandscape) {
                    (totalHeightPx * imageAspect) / totalWidthPx
                } else {
                    (totalWidthPx / imageAspect) / totalHeightPx
                }
                canvasFraction = needed.coerceIn(0.40f, 0.75f)
            }

            if (isLandscape) {
                // ── Landscape: canvas left | vertical handle | panel right ──────
                Row(modifier = Modifier.fillMaxSize()) {

                    if (true) {  // always keep CanvasBox in composition to avoid SurfaceView destroy/recreate flicker
                        CanvasBox(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(canvasFraction),
                        )

                        // Fallback banner sticks immediately left of the handle.
                        if (embeddedFallback != null) {
                            FallbackBanner(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(end = 4.dp),
                            )
                        }

                        // Vertical handle bar
                        if (isPreviewReady) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(20.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .draggable(
                                        orientation = Orientation.Horizontal,
                                        state       = rememberDraggableState { delta ->
                                            canvasFraction = (canvasFraction + delta / totalWidthPx)
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
                    }

                    PanelBox(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f - canvasFraction),
                    )
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
                    if (isPreviewReady && !isToneCurvesTab) {
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
                        var skippedMaskCards = 0
                        for (c in preset.cards) {
                            // Collect the class set, falling back to the
                            // first-class field for old single-class cards.
                            val classes: List<com.RAZStudio.StudioRoom
                                .feature.photo_editor.raw.model.MaskClass> = when {
                                c.maskClasses.isNotEmpty() -> c.maskClasses
                                c.maskClass != null -> listOf(c.maskClass)
                                else -> emptyList()
                            }
                            var maskPath: String? = null
                            if (classes.isNotEmpty()) {
                                val merged = mergeMaskClasses(classes)
                                val edges = segmentationMasks?.edgeMask
                                val bmp = merged?.let {
                                    buildSegmentationBitmap(it, edges = edges)
                                }
                                if (bmp != null) {
                                    val newId = java.util.UUID.randomUUID().toString()
                                    maskPath = com.RAZStudio.StudioRoom
                                        .feature.photo_editor.raw.RawMaskStorage
                                        .save(context, newId, bmp)
                                    if (maskPath == null) skippedMaskCards++
                                } else {
                                    // Segmentation source wasn't available
                                    // (e.g. preset has Sky but cityscapes
                                    // hasn't finished, or the photo is JPG
                                    // without subject detection). Skip the
                                    // bitmap so the macro values still apply
                                    // globally — the user can re-tap the
                                    // class button later to populate it.
                                    skippedMaskCards++
                                }
                            }
                            val newAction = com.RAZStudio.StudioRoom
                                .feature.photo_editor.raw.model.RawAction(
                                    label = c.label,
                                    tabIndex = c.tabIndex,
                                    macro = c.macro,
                                    maskPath = maskPath,
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
                            val suffix = if (skippedMaskCards > 0)
                                " (${skippedMaskCards} mask${if (skippedMaskCards == 1) "" else "s"} await segmentation)"
                            else ""
                            snackbarHostState.showSnackbar(
                                "Applied preset: ${preset.name} (${preset.cards.size} cards)$suffix"
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
