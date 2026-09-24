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
 * ─────────────────────────────────────────────────────────────────────────────
 *  M12.1b — Production RAW editor on v3 engine.
 *
 *  The public API of this component is INTENTIONALLY kept identical to the
 *  v2 incarnation. Every method and property listed in M12.1 research is
 *  still here, with the same name, types, and contract. What changed is
 *  the internal implementation: instead of `RawPipelineCoordinator`
 *  (v2 chain that owns demosaic + preview + full-res render), the
 *  component now drives v3's `RawV3Coordinator` plus an in-component
 *  [ShaderParams] state derived from the action stack.
 *
 *  Features v3 doesn't yet back (segmentation, mask layers, sidecar
 *  history, compare render, idle full-res render) return their no-op
 *  equivalents (null / empty / unchanged state). Their downstream UI
 *  paths still compile and render — Compose just sees null and skips
 *  the relevant overlay/banner/section. Production editor tabs that
 *  depend on these features (Mask, Vignette, Gradient) get an amber
 *  banner from RawAdjustmentPanel saying "deferred to M12.2".
 *
 *  Live preview: a separate `RawV3PreviewComposable` (M12.1a) hosts a
 *  `RawV3GlSurfaceView` via AndroidView. The component publishes the
 *  current Stage A TIFF path + the current [ShaderParams] via two
 *  StateFlows; the Composable observes both and pushes uniforms on
 *  every recomposition. No per-tick bitmap allocation.
 *
 *  Apply → Export routes through `RawV3Coordinator.exportRawToGallery`
 *  (the same single-file pipeline batch uses), so a single-image edit
 *  and a 7-file batch share identical encode + ICC + publish paths.
 *
 *  Sidecar XMP write next to source RAW lands via
 *  `RawV3SidecarWriter.writeNextToSource`; falls back to app-private
 *  when the SAF tree URI isn't writable. v2's revision-history API is
 *  retained as a no-op for source compatibility.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw


import com.RAZStudio.StudioRoom.core.utils.AppLog
import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import com.RAZStudio.StudioRoom.core.domain.image.ImageCompressor
import com.RAZStudio.StudioRoom.core.domain.image.ImageScaler
import com.RAZStudio.StudioRoom.core.domain.image.Metadata
import com.RAZStudio.StudioRoom.core.domain.image.clearAttributes
import com.RAZStudio.StudioRoom.core.domain.image.metadataOf
import com.RAZStudio.StudioRoom.core.domain.image.readOnly
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageInfo
import com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag
import com.RAZStudio.StudioRoom.core.domain.image.model.Quality
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageScaleMode
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeType
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.CompareRenderState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawActionsStorage
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPresetsStorage
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.DemosaicAlgorithm
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Bitmap16Sampler
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Png16Writer
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Tiff16Writer
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.GradingPipelineShim
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Cache
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawEditorExportPipeline
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3IccEmbed
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3ActionReplay
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Coordinator
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3LutChainResolver
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3LutStore
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3State
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3WorkspaceOptions
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import com.RAZStudio.StudioRoom.feature.photo_editor.data.network.toMaskBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RawEditorComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val initialUri: Uri?,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    /**
     * Set when this photo was opened from a Gallery Workspace project
     * (Requirement 9). Makes this component load/save THAT photo's
     * Edit_Sidecar via [editorPort] instead of the standalone editor's
     * next-to-source-or-cache behaviour, and switches the first-open flow
     * to the reduced lens-only selector (Requirement 15). Null for every
     * other entry point — standalone-editor behaviour untouched.
     */
    @Assisted val projectContext: com.RAZStudio.StudioRoom.core.ui.utils.navigation.ProjectPhotoRef?,
    @ApplicationContext private val appContext: Context,
    private val settingsProvider: SettingsProvider,
    private val imageScaler: ImageScaler<Bitmap>,
    private val imageCompressor: ImageCompressor<Bitmap>,
    /**
     * Hilt-injected batch processor — singleton across the app. Replaces
     * the `remember { RawBatchProcessor(context) }` that lived in
     * [RawEditorContent]; sharing the instance with Canon Sync's
     * `DownloadAndProcessCoordinator` means the v3 coordinator backing
     * it gets reused across screens too (per-file lazy alloc).
     */
    val rawBatchProcessor: com.RAZStudio.StudioRoom.feature.photo_editor.raw
        .RawBatchProcessor,
    /** Hilt-injected — see [submitOnlineAiBeautify]. */
    private val onlineAiEditClient: com.RAZStudio.StudioRoom.feature.photo_editor.data
        .network.OnlineAiEditClient,
    /** Hilt-injected — Gallery Workspace's sidecar/bulk-apply port. See [projectContext]. */
    private val editorPort: com.RAZStudio.StudioRoom.feature.photo_editor.raw
        .project.GalleryProjectEditorPort,
) : ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─── M12.1b — v3 coordinator (replaces v2 RawPipelineCoordinator) ─────
    private val v3 = RawV3Coordinator(appContext)

    val masking: RawMaskingComponent = RawMaskingComponentImpl(componentContext, v3, scope)
    val healing: RawHealingComponent = RawHealingComponentImpl(componentContext)

    /** Resolve a saved mask recipe against the current v3 segmentation results. */
    fun resolveMaskForClass(cls: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass): FloatArray? = when (cls) {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Subject -> masking.segmentationMasksV3.value?.subjectMask
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Background -> masking.segmentationMasksV3.value?.backgroundMask
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Sky -> masking.cityscapesMasks.value?.sky
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Buildings -> masking.cityscapesMasks.value?.buildingWall
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Vegetation -> masking.cityscapesMasks.value?.vegetation
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Terrain -> masking.cityscapesMasks.value?.terrain
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Hair -> masking.deepLabMasks.value?.hair ?: masking.multiclassMasks.value?.hair
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.BodySkin -> masking.deepLabMasks.value?.bodySkin ?: masking.multiclassMasks.value?.bodySkin
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.FaceSkin -> masking.faceMask.value ?: masking.deepLabMasks.value?.face ?: masking.multiclassMasks.value?.faceSkin
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.Clothes -> masking.deepLabMasks.value?.allClothes ?: masking.multiclassMasks.value?.clothes
    }

    /**
     * Editor-owned workspace-dialog gate.
 The v3 coordinator doesn't
     * own dialog state (it expects pre-picked options at openRawFile);
     * the editor surfaces the picker, drives the user's pick into v3
     * via [confirmWorkspace], then clears this flag. Layered into
     * [uiState] below.
     */
    private val _showWorkspaceDialog = MutableStateFlow(false)
    private val _pendingDialogUri = MutableStateFlow<Uri?>(null)
    // Last LUT stack key published to lutCubePathFlow — used by updateMacro to detect
    // changes without calling publishLutPathFor on every slider tick.
    private var lastPublishedLutUri: String? = null
    private var lastPublishedLutIntensity: Float = -1f
    private var lastPublishedStackKey: String = ""
    // Signature of the last tone-curve LUT published to toneCurveLutFlow. Lets
    // updateMacro republish the curve live during in-flight edits (the Curves
    // graph is an in-flight edit) without rebuilding it on unrelated drags.
    private var lastPublishedCurveSig: Int = Int.MIN_VALUE
    /** In-flight multi-LUT chain bake. Cancelled and replaced on every stack change. */
    private var lutBakeJob: kotlinx.coroutines.Job? = null
    // Non-null while AE bake + sidecar restore run after Stage A completes.
    // Overrides uiState from PreviewReady → PreviewLoading so the progress card
    // shows stage 2 (post-decode setup) instead of flipping to "ready" before
    // the action stack is populated.
    private val _postDecodeOverride = MutableStateFlow<RawPipelineState.PreviewLoading?>(null)

    // ─── Public state surface preserved from v2 ───────────────────────────
    //
    // The downstream Compose layer (RawEditorContent + tab Composables)
    // observes these as before; their meanings are translated below.

    /**
     * Translated `RawV3State` → `RawPipelineState` so RawEditorContent's
     * existing `when(state)` block continues to compile and route
     * correctly. The mapping is straightforward:
     *
     *   Idle                       → Idle
     *   DialogShown                → AwaitingWorkspaceChoice
     *   StageADecoding(p)          → PreviewLoading(stage=1, progress=p)
     *   StageBReady(sha, …)        → PreviewReady(neutralBitmap, …)
     *   Exporting(p)               → FullResProcessing(progress=p)
     *   Done(path)                 → FullResReady(path, metadata)
     *   Failed(msg, cause)         → Error(msg, cause)
     *
     * The `PreviewReady` case used to carry a Bitmap (the v2 graded
     * preview). In v3 the canvas is rendered live by the SurfaceView, so
     * we surface a 1×1 sentinel bitmap — Compose paths that consume the
     * value as "preview is ready" still flip correctly; paths that try
     * to render the bitmap directly will draw a 1×1 pixel (and the
     * SurfaceView underneath delivers the real frames).
     */
    val uiState: StateFlow<RawPipelineState> =
        kotlinx.coroutines.flow.combine(
            v3.state,
            _showWorkspaceDialog,
            _pendingDialogUri,
            _postDecodeOverride,
        ) { v3s, dlg, dlgUri, postDecodeOverride ->
            if (dlg && dlgUri != null) {
                RawPipelineState.AwaitingWorkspaceChoice(
                    uri = dlgUri,
                    suggested = WorkspaceConfig.Default,
                )
            } else if (postDecodeOverride != null && v3s is RawV3State.StageBReady) {
                // Stage A finished but AE bake / sidecar restore still running —
                // keep the progress card showing stage 2 rather than flipping to ready.
                postDecodeOverride
            } else {
                translateState(v3s)
            }
        }.stateIn(scope, SharingStarted.Eagerly, RawPipelineState.Idle)

    /**
     * Path to the Stage A TIFF for the currently-open RAW. Drives the
     * v3 preview Composable; null when no file is open.
     */
    val stageATifPathFlow: StateFlow<String?> = v3.state
        .combine(MutableStateFlow(Unit)) { s, _ ->
            (s as? RawV3State.StageBReady)?.stageATifPath
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Embedded JPEG thumbnail from Stage A — shown instantly while Stage A decode runs. Null when unavailable or once StageBReady. */
    // Latches the last non-null Stage-A thumbnail. The raw value is only present
    // during StageADecoding; latching it keeps the embedded thumbnail available
    // as the never-blank canvas backdrop in PreviewReady too (the GL SurfaceView
    // is ZOrderOnTop and transparent until it presents a real frame). Reset to a
    // new photo's thumbnail as soon as its decode emits one.
    val thumbnailBitmapFlow: StateFlow<Bitmap?> = v3.state
        .map { (it as? RawV3State.StageADecoding)?.thumbnailBitmap }
        .runningFold<Bitmap?, Bitmap?>(null) { last, cur -> cur ?: last }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Authoritative post-EXIF-rotation source dims from Stage A. Null until first StageBReady. */
    val previewDimsFlow: StateFlow<Pair<Int, Int>?> = v3.state
        .combine(MutableStateFlow(Unit)) { s, _ ->
            (s as? RawV3State.StageBReady)?.let { it.previewWidth to it.previewHeight }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Phase-2 grading pipeline coordinator. Owns the graded-AHB bake coroutine
     * in component scope so it survives Compose recomposition. The bakeBlock is
     * a thin lambda — real JNI details stay in RawV3PreviewComposable's
     * allocateAndFillAhb; the pipeline calls back into it via the injected block.
     *
     * Tab-switch suppression: call gradingPipeline.suppressBake() / resumeBake()
     * instead of toggling suppressGradedBake — no source swap, no visual pop.
     */
    // No-op shim. The "replaced by RendererCore + FrameScheduler" plan was
    // abandoned and those classes were deleted 2026-08-27 (dead + thread-unsafe);
    // the live GPU path is RawV3GlSurfaceView + GlesRenderer. See
    // GradingPipelineShim's header before touching this.
    val gradingPipeline: GradingPipelineShim = GradingPipelineShim()

    /**
     * Composed [ShaderParams] of the current action stack. Updated on
     * every `updateMacro` tick. The preview Composable observes this
     * and re-pushes uniforms to the SurfaceView per frame.
     */
    val shaderParamsFlow: StateFlow<ShaderParams> =
        MutableStateFlow(ShaderParams.Default)

    /**
     * The composed [UserMacro] that produced [shaderParamsFlow]'s current value.
     * Published in [updateMacro] so callers (e.g. [GradingPipeline.requestBake])
     * can check [EffectRegistry.anyDiverging] without re-deriving from ShaderParams.
     */
    val composedMacroFlow: StateFlow<UserMacro> =
        MutableStateFlow(UserMacro())

    /**
     * The ShaderParams derived from the auto-exposure action only (exposure,
     * highlights, shadows, whites, blacks). Null when no AE action exists in
     * the stack. Used by hold-to-compare so it shows Stage A + AE, never the
     * raw camera file baseline.
     */
    val aeShaderParamsFlow: StateFlow<ShaderParams?> =
        MutableStateFlow<ShaderParams?>(null)

    /**
     * Highlight Protection Pass baseline — in-memory only, NOT persisted,
     * NOT serialized into presets or action stacks. Recomputed every file
     * open by [bakeHighlightProtection]. Defaults to a neutral [UserMacro]
     * (no adjustment). The final macro fed to the GL preview and Stage C
     * export is composed as:
     *   highlightProtectionBaseline.mergeWith(cameraStyleFinish + actionStack)
     * so user actions compose additively on top of the baseline.
     */
    private val _highlightProtectionBaseline = MutableStateFlow(UserMacro())

    /**
     * Path to the `.cube` file the preview Composable should upload.
     * Updates on every action-stack change (LUT add/delete/toggle) and
     * every slider tick where the user is editing a new LUT layer.
     * Null when no LUT is wired into the current action stack.
     *
     * The Composable observes this and re-uploads the LUT only when the
     * path actually changes — dragging the intensity slider doesn't
     * re-parse the `.cube`.
     */
    val lutCubePathFlow: StateFlow<String?> =
        MutableStateFlow<String?>(null)

    /** Tone Curve LUT (256 RGB8 = 768 bytes) for the GL renderer; null when
     *  the composed curve is identity. Published by [rebuildShaderParams];
     *  the preview Composable uploads it via RawV3GlSurfaceView.uploadToneCurve. */
    val toneCurveLutFlow: StateFlow<ByteArray?> =
        MutableStateFlow<ByteArray?>(null)

    /**
     * v3 doesn't run a separate full-res render — Stage C IS the
     * full-res render and is only invoked at Apply time. So
     * `fullResOutputPath` is the most recent Apply→Export output, null
     * until Apply fires. Kept as a property + StateFlow for v2 API
     * parity.
     */
    private val _fullResOutputPath = MutableStateFlow<String?>(null)
    val fullResOutputPath: String? get() = _fullResOutputPath.value
    val fullResOutputPathFlow: StateFlow<String?> = _fullResOutputPath.asStateFlow()

    /**
     * Debug-only — bytes of the most recently saved JPG / WebP / PNG before
     * SAF publish. Used by Phase 4 verify harness so the diff doesn't have
     * to chase the user-facing SAF display path back to a real filesystem
     * Uri. Cleared on each new save. Limited to ~80 MB; the harness scales
     * the decoded bitmap down to editor snapshot dims before computing MAD.
     */
    var lastSavedBytesDebug: ByteArray? = null
        internal set

    /** True once Stage A + B complete and the live preview is rendering. */
    val fullResReady: StateFlow<Boolean> = v3.state
        .combine(MutableStateFlow(Unit)) { s, _ -> s is RawV3State.StageBReady }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Whether the open file is a non-RAW source (JPEG/PNG/WebP/TIFF/…). Set by
     * [confirmWorkspace] from the source URI. Drives UI that must hide RAW-only
     * controls (e.g. Smart Bright = LibRaw auto-bright, meaningless without
     * sensor data) for non-RAW images.
     */
    private val _isNonRawSource = MutableStateFlow(false)
    val isNonRawSource: StateFlow<Boolean> = _isNonRawSource.asStateFlow()
    private val _isJpegSource = MutableStateFlow(false)
    val isJpegSource: StateFlow<Boolean> = _isJpegSource.asStateFlow()

    /**
     * v3 has no "Stage C runs in the background while editing" concept —
     * the full-res render only fires at Apply time. So this is always
     * false outside the brief moment Apply→Export is in flight.
     */
    private val _fullResProcessing = MutableStateFlow(false)
    val fullResProcessing: StateFlow<Boolean> = _fullResProcessing.asStateFlow()

    /**
     * Embedded-JPEG fallback bitmap. v2 surfaced this when LibRaw hung
     * past a watchdog; v3's LibRaw isn't watchdogged the same way and
     * the editor never needs the fallback in practice. Returns null
     * always — Compose paths that check this skip the banner cleanly.
     */
    val embeddedFallbackBitmap: StateFlow<android.graphics.Bitmap?> =
        MutableStateFlow<android.graphics.Bitmap?>(null).asStateFlow()

    /**
     * v2 §6 EXIF surface. Stage A populates [RawV3Engine.stageADecode]'s
     * `StageAResult` which carries the same camera / lens / iso /
     * shutter / aperture / focal / dateTimeOriginal fields v2 exposes
     * via `RawExif`. Mapped on first decode; null until Stage A runs.
     */
    private val _exif = MutableStateFlow<com.raz.razstudio.lib.raw.RawExif?>(null)
    val exif: StateFlow<com.raz.razstudio.lib.raw.RawExif?> = _exif.asStateFlow()

    // ── v2-integration §2.2 — sidecar history (no-op in M12.1b) ───────────
    //
    // M12.1b implements only a SINGLE sidecar XMP write next to the source
    // RAW (no revision history). The history API stays here so downstream
    // RawHistorySheet still compiles, but always returns null / no-ops.
    // Full revision history is M12.3 follow-up.

    /**
     * M10 — XMP sidecar IO. Reads/writes next to the source RAW (or the
     * app-cache fallback when the source is read-only). Held as a lazy
     * singleton because [SidecarStore] keeps per-URI debounce state.
     *
     * When [projectContext] is set, this is instead backed by
     * [editorPort]'s resolver — beside the original when a folder grant
     * covers it, the per-project fallback otherwise — which is also what
     * keeps the Gallery Workspace `edits` row's location and has-edits
     * bookkeeping in step (Requirement 9.2, 9.3). Every existing sidecar
     * method below (load/save/pushRevision/revert) is unchanged; only
     * WHERE they read and write moves.
     */
    private val sidecarStore by lazy {
        val ctx = projectContext
        if (ctx != null) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarStore(
                appContext,
                editorPort.resolverFor(ctx.projectId, ctx.photoId, ctx.displayName),
            )
        } else {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar
                .SidecarStore(appContext)
        }
    }

    /**
     * URI of the currently-open RAW. Captured by [confirmWorkspace] so
     * the sidecar push path knows which file to write next to. Null
     * when no session is active.
     */
    private var currentSourceUri: android.net.Uri? = null

    /**
     * Public read of [currentSourceUri] for cross-component plumbing — the
     * Details editor needs the live source URI (which may differ from
     * [initialUri] after an in-app picker swap) to open the same RAW in
     * its shadow editor.
     */
    val activeUri: android.net.Uri? get() = currentSourceUri ?: initialUri

    /**
     * As-shot Kelvin probed from the source RAW's EXIF on file open.
     * Drives the Color tab Temperature slider's centre point so the
     * slider reads in absolute Kelvin (e.g. "5200 K"). 0 when no EXIF
     * temperature tag is available — the Color tab then falls back to
     * a 5500 K daylight centre.
     *
     * Most consumer DSLR RAWs don't write a `ColorTemperature` tag; in
     * that case this stays 0 and we keep the 5500 K assumption. Higher-
     * end bodies (Sony / Fuji / some Nikon) embed it.
     */
    private val _asShotKelvin = MutableStateFlow(0)
    val asShotKelvin: StateFlow<Int> = _asShotKelvin.asStateFlow()

    suspend fun loadSidecar(): com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot? {
        val uri = currentSourceUri ?: return null
        return sidecarStore.load(uri)
    }

    suspend fun pushSidecarRevision() {
        val uri = currentSourceUri ?: return
        val workspace = currentWorkspaceConfig
            ?: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig.Default
        val composed = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3ActionReplay.composeMacro(actions.toList())
        sidecarStore.pushRevision(uri, workspace, composed)
        if (projectContext != null) {
            flushProjectSidecarNow()?.join()
        } else {
            saveActionStackSidecar(uri, workspace, composed)
        }
    }

    /**
     * True when [actions] carries something a user would recognise as an
     * edit — excludes the Original sentinel and workspace-generated cards
     * (AI Color Enhance, film-profile curves, …). Reported to the project
     * sidecar resolver so `edits.isNeutral` reflects reality (Requirement
     * 15.18: a first-open commit with no adjustment must not light the
     * has-edits indicator) — same filter [RawActionsTab] already uses to
     * decide what to show the user.
     */
    private val hasVisibleEdit: Boolean
        get() = actions.any {
            it.id != com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.ORIGINAL_ID &&
                !it.isWorkspaceDefault
        }

    /**
     * Write the full SidecarSnapshot including the action stack. v2's
     * [SidecarStore] only round-trips workspace + macro + revisions; v3
     * needs the action stack too. To avoid breaking v2 callers we run
     * this in parallel: SidecarStore handles workspace/macro/revisions
     * + debounce + atomic write, then we re-read its output, layer the
     * actionStack on top, and write again through [SidecarStore.writeSnapshot]
     * — the SAME resolver the read went through, so a project photo's
     * action stack lands at exactly the location its `edits` row records
     * (Requirement 9.2, 9.3) instead of a hand-rolled path guess.
     */
    private fun saveActionStackSidecar(
        uri: android.net.Uri,
        workspace: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig,
        macro: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro,
    ) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Wait for the SidecarStore debounce window to flush, then
            // overwrite with the same file + actionStack appended.
            kotlinx.coroutines.delay(700)
            val snap = sidecarStore.load(uri) ?: return@launch
            val withActions = snap.copy(actionStack = sidecarActionEntries())
            sidecarStore.writeSnapshot(uri, withActions, hasVisibleEdit)
        }
    }

    /** Debounce job for [persistProjectSidecarLive] — one per open session. */
    private var projectSidecarLiveJob: kotlinx.coroutines.Job? = null

    /**
     * Requirement 9.3/9.4 — implicit save for edits made through the
     * AUTO-APPLY tabs (Tone, Color, Effects, Details, …), which commit via
     * [replaceTabCards] → [persistActionsDebounced] and never call
     * [pushSidecarRevision] themselves (that call is reserved for discrete
     * boundaries: Apply-button tabs, AE toggle, mask commit). Without this,
     * sliding Exposure on a project photo would update the in-memory stack
     * and the plain-file action-stack cache but NEVER reach the photo's
     * actual Edit_Sidecar. Writes the CURRENT head (no new revision
     * boundary — [pushSidecarRevision] already owns those) so continuous
     * dragging doesn't spam the history stack. No-op outside project
     * context. Called from [persistActions], which every mutation path
     * already funnels through.
     */
    private fun persistProjectSidecarLive() {
        if (projectContext == null) return
        if (currentSourceUri == null) return
        // Coalesce slider drags on the component scope, then hand ONE complete
        // snapshot to the store's detached writer. The write itself never runs
        // on `scope`, so it cannot be lost to scope.cancel() at exit.
        projectSidecarLiveJob?.cancel()
        projectSidecarLiveJob = scope.launch {
            kotlinx.coroutines.delay(400)
            flushProjectSidecarNow()
        }
    }

    /** The current action stack as sidecar entries — shared by every write path. */
    private fun sidecarActionEntries() = actions.toList().map { a ->
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarActionEntry(
            id        = a.id,
            label     = a.label,
            tabIndex  = a.tabIndex,
            macro     = a.macro,
            isVisible = a.isVisible,
            isLocked  = a.isLocked,
            maskPath  = a.maskPath,
            maskClass = a.maskClass?.name,
            maskClasses = a.maskClasses.map { it.name },
            isAutoExposure = a.isAutoExposure,
        )
    }

    /**
     * Immediately write the project photo's full sidecar (workspace + macro +
     * action stack) on the store's detached writer. Cancels the coalescing
     * job so nothing older lands afterwards. Called on every exit path from
     * the editor — back, export hand-off, component destroy — so the edits
     * you see are the edits that persist. No-op outside project context.
     */
    fun flushProjectSidecarNow(): kotlinx.coroutines.Job? {
        if (projectContext == null) return null
        val uri = currentSourceUri ?: return null
        projectSidecarLiveJob?.cancel()
        val composed = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3ActionReplay.composeMacro(actions.toList())
        val workspace = currentWorkspaceConfig
            ?: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig.Default
        return sidecarStore.flushSnapshotDetached(
            uri, workspace, composed, sidecarActionEntries(), hasVisibleEdit,
        )
    }

    /** Detached IO scope for one-shot work that must outlive this component (thumbnail refresh). */
    private val detachedIo = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Refresh the Gallery Workspace grid thumbnail from [edited] — the graded
     * canvas as the user sees it. Copies the bitmap so the caller keeps
     * ownership of its own. No-op outside project context.
     */
    fun persistProjectThumbnail(edited: android.graphics.Bitmap) {
        val ctx = projectContext ?: return
        if (edited.isRecycled) return
        val copy = runCatching { edited.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        detachedIo.launch {
            runCatching { editorPort.updateThumbnail(ctx.projectId, ctx.photoId, copy) }
                .onFailure { AppLog.w(TAG, "project thumbnail refresh failed: ${it.message}") }
            copy.recycle()
        }
    }

    /**
     * M10 — restore the committed action stack from the XMP sidecar.
     * Called after [confirmWorkspace] fires Stage A. Tolerates:
     *   • missing sidecar (first edit ever) → no-op
     *   • v1 sidecar (predates M10) → actionStack is empty in load result
     *   • mask PNGs missing on disk → action is still restored, but the
     *     mask path is dropped so rebuildShaderParams treats it as a
     *     plain (no-mask) action.
     */
    private suspend fun restoreActionStackFromSidecar() {
        val snap = loadSidecar() ?: return
        if (snap.actionStack.isEmpty()) {
            val composed = snap.macro
            if (composed != com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro()) {
                actions.clear()
                actions.add(
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction(
                        label = "Restored edit",
                        tabIndex = -1,
                        macro = composed,
                    )
                )
                actions.add(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.Original)
                rebuildShaderParams()
            }
            return
        }
        val src = neutralBitmap
        val restored = snap.actionStack.map { e ->
            val maskPathOnDisk = e.maskPath?.takeIf { java.io.File(it).exists() }
            // The manual "Tone · AI Expose On" / "Tone · Basic Auto On" card
            // is a user-initiated action that should be restored as-is.
            // The Highlight Protection Pass now handles only highlights/whites
            // as an invisible baseline — it never recreates a full AE card,
            // so the saved card must survive restore (Requirement 7.1, 7.3).
            val effectiveMacro: UserMacro = e.macro
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction(
                id        = e.id,
                label     = e.label,
                tabIndex  = e.tabIndex,
                macro     = effectiveMacro,
                isVisible = e.isVisible,
                isLocked  = e.isLocked,
                maskPath  = maskPathOnDisk,
                maskClass = e.maskClass?.let { name ->
                    runCatching {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw
                            .model.MaskClass.valueOf(name)
                    }.getOrNull()
                },
                maskClasses = e.maskClasses.mapNotNull { name ->
                    runCatching {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw
                            .model.MaskClass.valueOf(name)
                    }.getOrNull()
                },
                isAutoExposure = e.isAutoExposure,
                maskNodes = buildList {
                    // M12.2c.6 — Reconstruct the graph from sidecar metadata.
                    // If a bitmap exists, it becomes the base node for this photo.
                    maskPathOnDisk?.let { path ->
                        add(com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.MaskNode(
                            id = java.util.UUID.randomUUID().toString(),
                            source = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.MaskSource.StoredBitmap(path),
                            operation = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.MaskOp.ADD
                        ))
                    }
                    // Portable reconstruction: if bitmap is missing but classes exist,
                    // use ModelClass nodes so they re-derive from the NPU/models.
                    if (maskPathOnDisk == null) {
                        val classes = e.maskClasses.mapNotNull { name ->
                            runCatching { com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.valueOf(name) }.getOrNull()
                        }.ifEmpty {
                            listOfNotNull(e.maskClass?.let { name ->
                                runCatching { com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass.valueOf(name) }.getOrNull()
                            })
                        }
                        classes.forEach { cls ->
                            add(com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.MaskNode(
                                id = java.util.UUID.randomUUID().toString(),
                                source = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.MaskSource.ModelClass(cls),
                                operation = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.MaskOp.ADD
                            ))
                        }
                    }
                },
                // Keep auto-created workspace cards hidden from the Actions tab across a
                // mid-session restore. (_ai_color_enhance / _smart_defaults / film-profile
                // Curves are workspace-generated and shouldn't clutter the user's stack.)
                isWorkspaceDefault = e.label == "_ai_color_enhance" ||
                    e.label == "_smart_defaults" || e.label.endsWith(" Curves"),
            )
        }
        // Replace in-memory stack + ensure the Original sentinel sits
        // at the bottom (existing rebuildShaderParams already filters it).
        actions.clear()
        actions.addAll(restored.filterNotNull())
        if (actions.none { it.id == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.ORIGINAL_ID }) {
            actions.add(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.Original)
        }
        rebuildShaderParams()
    }

    /**
     * Restore an earlier revision (Requirement 5.3, 5.3c). [SidecarStore.revert]
     * handles the flat macro/workspace/history bookkeeping; a revision only ever
     * recorded a flat composed macro (a v2-era limitation this task doesn't
     * re-architect — the action stack's per-card/per-mask breakdown isn't
     * captured per revision), so v3's action stack is collapsed to ONE card
     * carrying the reverted macro. That card becomes the new head; the state
     * being reverted FROM was already pushed onto history by [SidecarStore.revert]
     * itself, so the revert is undoable per 5.3c without a second explicit push.
     */
    suspend fun revertSidecar(
        revisionIndex: Int,
    ): com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot? {
        val uri = currentSourceUri ?: return null
        val reverted = sidecarStore.revert(uri, revisionIndex) ?: return null
        actions.clear()
        actions.add(
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction(
                label = "Reverted edit",
                tabIndex = -1,
                macro = reverted.macro,
            )
        )
        actions.add(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.Original)
        rebuildShaderParams()
        persistActions()
        // Sync the actionStack field onto the head SidecarStore.revert() just
        // wrote, WITHOUT pushing a second revision boundary (revert already
        // recorded one). Unconditional — unlike persistActions' project-only
        // live save, the on-disk actionStack needs to match this collapsed
        // stack in BOTH modes, or the next open would restore stale cards.
        saveActionStackSidecar(uri, reverted.workspace, reverted.macro)
        return reverted
    }

    /**
     * v3's live canvas is a SurfaceView, so a per-tick Bitmap doesn't
     * exist. Some Compose surfaces — Tone Curve graph background,
     * Compare overlay, histogram source — still want a stable Bitmap
     * snapshot of the source. We provide one by decoding the Stage A
     * TIFF (already on disk after openRawFile) into a small ARGB_8888
     * thumbnail. The thumbnail is "neutral" (no adjustments applied)
     * because Stage A's output is the unedited graded source.
     *
     * Loaded once per file open; null until Stage A finishes.
     */
    private val _neutralBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val neutralBitmap: android.graphics.Bitmap? get() = _neutralBitmap.value

    // Per-image LibRaw auto-bright factor for the Smart Bright slider. Computed
    // once from the decoded thumbnail; 1.0 until then (Smart Bright inert).
    @Volatile private var autoBrightFactor: Float = 1f
    // Per-channel auto-WB stats for Smart Color Enhancement GL preview parity.
    // Computed once from the Stage A 256px thumbnail; null until then (no stretch).
    @Volatile private var cachedSmartWbStats: FloatArray? = null
    @Volatile private var cachedExtDiagnostics: FloatArray? = null
    val neutralBitmapFlow: StateFlow<android.graphics.Bitmap?> = _neutralBitmap.asStateFlow()

    // Camera Color Profile (route A): the embedded-JPEG preview captured during
    // Stage A (only present while StageADecoding), and the per-channel matched
    // curve derived from it at StageBReady. cameraMatchLut composes UNDER the
    // user's tone curve in publishToneCurveFor so the live preview adopts the
    // in-camera colour (the export path matches independently in the coordinator).
    @Volatile private var embeddedThumbForMatch: Bitmap? = null
    @Volatile private var cameraMatchLut: ByteArray? = null

    /**
     * One-shot user-facing info messages emitted by the pipeline. Currently
     * carries the pixel-shift "merge with vendor software first" warning;
     * future entries might surface "Canon HTP detected", "ALO baked in",
     * "this is a corrupt thumbnail" etc. Compose layer collects this flow
     * and surfaces each non-null payload as a Toast / Snackbar, then sets
     * it back to null (via [consumePipelineMessage]).
     */
    private val _pipelineMessage = MutableStateFlow<String?>(null)
    val pipelineMessage: StateFlow<String?> = _pipelineMessage.asStateFlow()
    fun consumePipelineMessage() { _pipelineMessage.value = null }

    /**
     * v3 doesn't render a separate "compare" frame — the preview is
     * already at neutral when sliders are zeroed. The state flow stays
     * Idle. Downstream RawCompareOverlay sees Idle and renders nothing.
     */
    val compareState: StateFlow<CompareRenderState> =
        MutableStateFlow(CompareRenderState.Idle).asStateFlow()

    fun renderForCompare(macro: UserMacro) {
        // Compare-mode preview is a M12.2 follow-up — for now we just
        // log so the call doesn't silently no-op.
        AppLog.i(TAG, "renderForCompare ignored (deferred to M12.2)")
    }

    fun cancelCompareRender() = Unit

    /**
     * v3 doesn't produce an "idle full-res preview" — the live
     * SurfaceView already shows the graded result. Returns null; the
     * Export screen's preview falls through to the Stage A path.
     */
    val idleFullResBitmap: StateFlow<android.graphics.Bitmap?> =
        MutableStateFlow<android.graphics.Bitmap?>(null).asStateFlow()

    fun renderIdleFullRes(macro: UserMacro) {
        AppLog.i(TAG, "renderIdleFullRes ignored (v3 renders live)")
    }

    fun clearIdleFullRes() = Unit

    fun renderExportPreview() {
        AppLog.i(TAG, "renderExportPreview ignored (v3 renders live)")
    }

    // ── Actions list — lives in the component, survives navigation ────────────

    val actions: SnapshotStateList<RawAction> = mutableStateListOf(RawAction.Original)

    private val sourceKey: String? get() = initialUri?.toString()

    // ── Auto-save crash/background recovery ────────────────────────────────
    //   Persist the current actions list + source URI to app-private
    //   storage on a 2s debounce whenever the actions list mutates. On
    //   process death (Android reclaims memory after the user browses
    //   another app for ~5min) the editor restarts cold; [restoreFromAutoSave]
    //   reads the last snapshot and re-seeds the editor state so the user
    //   doesn't lose their work.
    //
    //   Saving is fire-and-forget — failures are logged but never crash
    //   the editor (the timer fires on the IO dispatcher).
    private var autoSaveJob: kotlinx.coroutines.Job? = null

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(2000)
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar
                .AutoSaveStore.save(appContext, currentSourceUri ?: initialUri,
                    actions.toList())
        }
    }

    /**
     * Actions recovered from [AutoSaveStore] on process-death resume.
     * Applied AFTER [confirmWorkspace]'s Stage A completes (same slot as
     * project sidecar restore) so the intentional `actions.clear()` at
     * open does not wipe them. Null = fresh open, not a cold recovery.
     */
    @Volatile private var pendingAutoSaveActions: List<RawAction>? = null

    /**
     * Try to recover mid-edit state after process death / Activity recreate.
     * Returns the restored snapshot when the autosave URI matches [uri]
     * (or [uri] is null and autosave is the only source). Does NOT mutate
     * the live action stack — caller stashes into [pendingAutoSaveActions]
     * and applies after Stage A.
     */
    private fun loadMatchingAutoSave(uri: Uri?): com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.AutoSaveStore.Restored? {
        val restored = com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar
            .AutoSaveStore.loadLast(appContext) ?: return null
        val incoming = uri?.toString()
        if (incoming != null && incoming != restored.sourceUri.toString()) {
            AppLog.i(TAG, "restoreFromAutoSave: URI mismatch " +
                "($incoming vs ${restored.sourceUri}) — skipping restore")
            return null
        }
        AppLog.i(TAG, "restoreFromAutoSave: recovered " +
            "${restored.actions.size} actions for ${restored.sourceUri}")
        return restored
    }

    fun replaceActions(newActions: List<RawAction>) {
        // Recompute AUTO EXPO cards against THIS photo's histogram. The
        // incoming list may be a preset loaded from disk where each
        // AUTO EXPO entry carries the original photo's exposure deltas;
        // those are useless on a different RAW. We only run this for
        // entries where isAutoExposure=true so manual edits pass
        // through untouched. Neutral bitmap may not be ready yet
        // (Stage A still decoding) — in that case we leave the carried
        // macro alone; the next pipeline tick will rebuild when ready.
        val src = neutralBitmap
        val resolved = if (src == null) newActions else newActions.map { a ->
            if (a.isAutoExposure) a.copy(
                macro = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                    .RawAutoExposure.analyse(src, a.macro, masking.segmentationMasks.value,
                                              iso = rawMetadata?.iso ?: 0,
                                              zeroDceLightScore = v3.zeroDceLightScore.value)
            ) else a
        }
        actions.clear()
        actions.addAll(resolved)
        if (!actions.any { it.id == RawAction.ORIGINAL_ID }) actions.add(RawAction.Original)
        persistActions()
        rebuildShaderParams()
    }

    fun clearAllActions() = replaceActions(emptyList())

    /**
     * Swap the mask PNG path on an existing action (identified by [actionId])
     * without touching the rest of the stack. Used by preset replay to inject
     * segmentation bitmaps asynchronously: [replaceActions] installs the cards
     * first so global-only cards take effect immediately, then [ensureSegmentation]
     * runs in the background and each mask card is patched in as its bitmap is ready.
     */
    fun updateActionMask(actionId: String, maskPath: String?) {
        val idx = actions.indexOfFirst { it.id == actionId }
        if (idx < 0) return
        actions[idx] = actions[idx].copy(maskPath = maskPath)
        persistActions()
        rebuildShaderParams()
    }

    fun addAction(action: RawAction) {
        // AE actions are singletons: a new auto-exposure card always replaces
        // any previous one so the exposure doesn't double-stack via mergeWith.
        if (action.isAutoExposure) actions.removeAll { it.isAutoExposure }
        // LUT slots are singletons too: each of LUT 1 / LUT 2 is applied once, so
        // a new edit for a slot replaces that slot's previous card. A clear (empty
        // cubeUri) just removes the slot's card without adding an empty one.
        if (action.macro.lutEdited) {
            val slot = action.macro.lutSlot
            actions.removeAll { it.macro.lutEdited && it.macro.lutSlot == slot }
            if (action.macro.lutCubeUri.isEmpty()) {
                persistActions()
                rebuildShaderParams()
                return
            }
        }
        val origIdx = actions.indexOfFirst { it.id == RawAction.ORIGINAL_ID }
        if (origIdx >= 0) actions.add(origIdx, action) else actions.add(action)
        persistActions()
        rebuildShaderParams()
    }

    /**
     * Fold the committed user cards for [tabIndex] into one macro — the values
     * the auto-apply panel shows on that tab's sliders (so edits persist and are
     * visible when revisiting the tab). Excludes Original, mask cards, and AE
     * cards. For the Tone/Color tabs the shared WB/Tint (stored as a single card
     * but editable from either tab) are injected so both tabs display the live
     * value. Mirrors the fold the GL preview uses, minus the camera-style base.
     */
    fun composeTabMacro(tabIndex: Int): UserMacro {
        val toneTab = com.RAZStudio.StudioRoom.feature.photo_editor
            .presentation.raw.components.TAB_TONE_COLOR
        val colorTab = com.RAZStudio.StudioRoom.feature.photo_editor
            .presentation.raw.components.TAB_COLOR_TOOLS
        val base = actions
            .filter {
                it.id != RawAction.ORIGINAL_ID && it.maskPath == null &&
                    !it.isAutoExposure && it.tabIndex == tabIndex
            }
            .fold(UserMacro()) { acc, a -> acc.mergeWith(a.macro) }
        // WB + Tint are shared between the Tone and Color tabs but stored once.
        if (tabIndex != toneTab && tabIndex != colorTab) return base
        val wb = actions.firstOrNull { it.maskPath == null && it.macro.whiteBalance != 0 }?.macro?.whiteBalance
        val tint = actions.firstOrNull { it.maskPath == null && it.macro.tint != 0f }?.macro?.tint
        // The Light tab's "subject protection" lives on the (excluded) AUTO EXPO
        // card — inject it so the slider shows the active value rather than 0.
        val aeProt = if (tabIndex == toneTab)
            actions.firstOrNull { it.isAutoExposure }?.macro?.aeSubjectProtection else null
        return base.copy(
            whiteBalance = wb ?: base.whiteBalance,
            tint = tint ?: base.tint,
            aeSubjectProtection = aeProt ?: base.aeSubjectProtection,
        )
    }

    /**
     * Toggle AI Color Enhance (the "_ai_color_enhance" card). When the card
     * exists, its visibility is flipped (instant, loss-free — the per-image
     * AI-fusion macro was computed once). When it does NOT exist — legacy
     * sidecar sessions from before the feature, sessions restored with the
     * old `_smart_defaults` card, or an open where the 256px scene decode
     * failed — enabling now COMPUTES the card on demand with the exact same
     * math as the open-time bake (Zero-DCE probe + histogram fusion), so the
     * checkbox works for EVERY format and every session instead of
     * dead-clicking. Disabling with no card is a genuine no-op.
     */
    fun setAiColorEnhance(enabled: Boolean) {
        val idx = actions.indexOfFirst { it.label == "_ai_color_enhance" }
        if (idx >= 0) {
            if (actions[idx].isVisible == enabled) return
            actions[idx] = actions[idx].copy(isVisible = enabled)
            persistActions()
            rebuildShaderParams()
            return
        }
        if (!enabled) return
        scope.launch {
            // Scene bitmap: the neutral preview is present in any open editor;
            // fall back to a fresh 256px Stage A decode if it was reclaimed.
            val bmp = _neutralBitmap.value ?: run {
                val tif = (v3.state.value as? com.RAZStudio.StudioRoom.feature
                    .photo_editor.raw_v3.RawV3State.StageBReady)?.stageATifPath
                    ?: return@launch
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                            .RawV3BigTiffReader.decodeStageAToArgb8888(
                                java.io.File(tif), maxLongSide = 256,
                            )
                    }.getOrNull()
                } ?: return@launch
            }
            val dceScore = runCatching { v3.probeLowLight(bmp) }.getOrNull()
            val aiMacro = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model
                .UserMacro.createAiColorEnhance(bmp, dceScore)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                // Re-check — a concurrent open/restore may have added one.
                if (actions.any { it.label == "_ai_color_enhance" }) return@withContext
                // Supersede a VISIBLE legacy Scene-Adaptive card so the two
                // global enhances don't stack.
                val legacy = actions.indexOfFirst { it.label == "_smart_defaults" }
                if (legacy >= 0 && actions[legacy].isVisible) {
                    actions[legacy] = actions[legacy].copy(isVisible = false)
                }
                val origIdx = actions.indexOfFirst { it.id == RawAction.ORIGINAL_ID }
                actions.add(
                    if (origIdx >= 0) origIdx + 1 else 0,
                    RawAction(
                        label     = "_ai_color_enhance",
                        tabIndex  = -1,
                        macro     = aiMacro,
                        isLocked  = true,
                        isVisible = true,
                        isWorkspaceDefault = true,
                    ),
                )
                AppLog.i(TAG, "AI Color Enhance computed on demand (zeroDce=$dceScore)")
                persistActions()
                rebuildShaderParams()
            }
        }
    }

    /**
     * Auto-apply commit: atomically replace tab [tabIndex]'s cards with [cards]
     * (label → single-field macros from buildIndividualCards). Every slider move
     * in a non-mask tab calls this, so edits register live without an Apply
     * button and persist (read back via [composeTabMacro]). WB/Tint are shared
     * singletons (additive + editable from two tabs) so only one ever survives.
     * A cleared LUT (empty cube) is removed, not stored. Eye/lock carry over by
     * label prefix. One preview rebuild; disk persistence debounced off the drag.
     */
    fun replaceTabCards(tabIndex: Int, cards: List<Pair<String, UserMacro>>) {
        fun keyOf(label: String) = label.substringBeforeLast(' ')
        val prevMeta = actions.associate { keyOf(it.label) to Pair(it.isVisible, it.isLocked) }
        val newHasWb = cards.any { it.second.whiteBalance != 0 }
        val newHasTint = cards.any { it.second.tint != 0f }
        // Drop this tab's existing cards, plus any prior WB/Tint singleton when
        // the new set re-sets them (they may live under the other shared tab).
        actions.removeAll {
            it.id != RawAction.ORIGINAL_ID && it.maskPath == null && !it.isAutoExposure && (
                it.tabIndex == tabIndex ||
                    (newHasWb && it.macro.whiteBalance != 0) ||
                    (newHasTint && it.macro.tint != 0f)
            )
        }
        val built = cards.mapNotNull { (label, m) ->
            // A cleared LUT slot (lutEdited but no cube) just removes the card.
            if (m.lutEdited && m.lutCubeUri.isEmpty()) return@mapNotNull null
            val meta = prevMeta[keyOf(label)]
            RawAction(
                label = label, tabIndex = tabIndex, macro = m,
                isVisible = meta?.first ?: true, isLocked = meta?.second ?: false,
            )
        }
        val origIdx = actions.indexOfFirst { it.id == RawAction.ORIGINAL_ID }
        if (origIdx >= 0) actions.addAll(origIdx, built) else actions.addAll(built)
        rebuildShaderParams()
        persistActionsDebounced()
    }

    private var persistJob: kotlinx.coroutines.Job? = null
    /** Coalesce rapid live-edit persists into one disk write 600 ms after the
     *  last change so dragging a slider doesn't write the action XML per frame. */
    private fun persistActionsDebounced() {
        persistJob?.cancel()
        persistJob = scope.launch {
            kotlinx.coroutines.delay(600)
            persistActions()
        }
    }

    /**
     * Live vignette-center drag from the preview canvas. Updates the existing
     * Vignette card's center in place — the Vignette tab auto-applies, so there
     * is at most one such card. No-op until a vignette amount has been set
     * (a centre with no vignette is invisible). Mirrors the old deltaMacro path.
     */
    fun updateVignetteCenter(x: Float, y: Float) {
        val vigTab = com.RAZStudio.StudioRoom.feature.photo_editor
            .presentation.raw.components.TAB_VIGNETTE_PANE
        val idx = actions.indexOfFirst {
            it.id != RawAction.ORIGINAL_ID && it.maskPath == null && it.tabIndex == vigTab
        }
        if (idx < 0) return
        val old = actions[idx]
        actions[idx] = old.copy(
            macro = old.macro.copy(
                vignetteCenterX = x, vignetteCenterY = y, vignetteCenterAutoSnapped = false,
            ),
        )
        rebuildShaderParams()
        persistActionsDebounced()
    }

    /**
     * Live lens-flare drag from the preview canvas. Writes Position X/Y
     * (shader [-1..1]) onto the FX Lens Flare card.
     */
    fun updateLensFlarePosition(x: Float, y: Float) {
        val fxTab = com.RAZStudio.StudioRoom.feature.photo_editor
            .presentation.raw.components.TAB_EFFECTS
        val idxHit = actions.indexOfFirst {
            it.id != RawAction.ORIGINAL_ID && it.maskPath == null && it.tabIndex == fxTab &&
                it.macro.lensFlare.brightness > 0f
        }
        val idx = if (idxHit >= 0) idxHit else actions.indexOfFirst {
            it.id != RawAction.ORIGINAL_ID && it.maskPath == null && it.tabIndex == fxTab
        }
        if (idx < 0) return
        val old = actions[idx]
        actions[idx] = old.copy(
            macro = old.macro.copy(
                lensFlare = old.macro.lensFlare.copy(
                    x = x.coerceIn(-1f, 1f),
                    y = y.coerceIn(-1f, 1f),
                ),
            ),
        )
        rebuildShaderParams()
        persistActionsDebounced()
    }

    /** Live pinch on the preview while Move flare is on. Size stays 0.1..5. */
    fun updateLensFlareSize(size: Float) {
        val fxTab = com.RAZStudio.StudioRoom.feature.photo_editor
            .presentation.raw.components.TAB_EFFECTS
        val idxHit = actions.indexOfFirst {
            it.id != RawAction.ORIGINAL_ID && it.maskPath == null && it.tabIndex == fxTab &&
                it.macro.lensFlare.brightness > 0f
        }
        val idx = if (idxHit >= 0) idxHit else actions.indexOfFirst {
            it.id != RawAction.ORIGINAL_ID && it.maskPath == null && it.tabIndex == fxTab
        }
        if (idx < 0) return
        val old = actions[idx]
        actions[idx] = old.copy(
            macro = old.macro.copy(
                lensFlare = old.macro.lensFlare.copy(size = size.coerceIn(0.1f, 5f)),
            ),
        )
        rebuildShaderParams()
        persistActionsDebounced()
    }

    fun currentLensFlareSize(): Float {
        val fxTab = com.RAZStudio.StudioRoom.feature.photo_editor
            .presentation.raw.components.TAB_EFFECTS
        val idxHit = actions.indexOfFirst {
            it.id != RawAction.ORIGINAL_ID && it.maskPath == null && it.tabIndex == fxTab &&
                it.macro.lensFlare.brightness > 0f
        }
        val idx = if (idxHit >= 0) idxHit else actions.indexOfFirst {
            it.id != RawAction.ORIGINAL_ID && it.maskPath == null && it.tabIndex == fxTab
        }
        if (idx < 0) return 1f
        return actions[idx].macro.lensFlare.size
    }

    /**
     * Re-insert [action] at [originalIndex] in the stack — used by
     * the Actions-tap-to-edit Cancel path so the restored card lands
     * back in its original layer position instead of at the bottom.
     * Index is clamped to the current list length; if the user has
     * mutated the list in between (rare — Cancel is the very next
     * tap), the action just lands at the closest valid slot.
     */
    fun restoreActionAt(originalIndex: Int, action: RawAction) {
        val clamped = originalIndex.coerceIn(0, actions.size)
        actions.add(clamped, action)
        persistActions()
        rebuildShaderParams()
    }

    fun deleteAction(id: String, keepStorage: Boolean = false) {
        val victim = actions.firstOrNull { it.id == id && !it.isLocked }
        if (victim?.maskPath != null && !keepStorage) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw
                .RawMaskStorage.delete(appContext, victim.id)
        }
        actions.removeAll { it.id == id && !it.isLocked }
        persistActions()
        rebuildShaderParams()
    }







    fun toggleEye(id: String) {
        val idx = actions.indexOfFirst { it.id == id }
        if (idx >= 0) actions[idx] = actions[idx].copy(isVisible = !actions[idx].isVisible)
        persistActions()
        rebuildShaderParams()
    }

    fun toggleLock(id: String) {
        val idx = actions.indexOfFirst { it.id == id }
        if (idx >= 0) actions[idx] = actions[idx].copy(isLocked = !actions[idx].isLocked)
        persistActions()
    }

    fun persistActions() {
        val key = sourceKey ?: return
        val snapshot = actions.toList()
        // Feed the in-memory settings clipboard so "Apply from previous" on the
        // NEXT photo can offer this stack (Lightroom's Apply-from-previous).
        com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .RawSettingsClipboard.noteSession(key, snapshot)
        scope.launch(Dispatchers.IO) {
            RawActionsStorage.save(appContext, key, snapshot)
        }
        // Requirement 9.3/9.4 — every mutation path (addAction, deleteAction,
        // toggleEye/Lock, replaceTabCards' auto-apply, restoreActionAt,
        // replaceActions) funnels through here, so this is the one place that
        // guarantees a project photo's live edits reach its Edit_Sidecar
        // without an explicit export step. No-op outside project context.
        persistProjectSidecarLive()
    }

    fun prepareExportFile(): String {
        val dir = java.io.File(appContext.cacheDir, "raw_actions_export").also { it.mkdirs() }
        val file = java.io.File(dir, "raw_actions.xml")
        file.writeText(
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawActionSerializer.serialize(
                actions.toList(),
                stripMaskFields = true
            )
        )
        return file.absolutePath
    }

    fun importActions(uri: Uri): List<RawAction>? =
        RawActionsStorage.importFromUri(appContext, uri)

    // ── Settings clipboard (Copy / Paste / Apply from previous) ────────────

    /** Copy the current edit stack to the in-memory settings clipboard. */
    fun copySettings() {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .RawSettingsClipboard.copy(sourceKey, actions.toList())
    }

    /**
     * Paste the clipboard onto this photo — REPLACES the current stack, like
     * Lightroom's Paste Settings (and our preset load). AE cards are recomputed
     * for this photo's histogram by [replaceActions]. Returns false when the
     * clipboard is empty.
     */
    fun pasteSettings(): Boolean {
        val cards = com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .RawSettingsClipboard.pasteableFor(sourceKey) ?: return false
        replaceActions(cards)
        return true
    }

    /** Apply the previous photo's edit stack to this photo. */
    fun applyPreviousSettings(): Boolean {
        val cards = com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .RawSettingsClipboard.previousFor(sourceKey) ?: return false
        replaceActions(cards)
        return true
    }

    fun canPasteSettings(): Boolean =
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawSettingsClipboard.hasCopy

    fun canApplyPreviousSettings(): Boolean =
        com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .RawSettingsClipboard.hasPreviousFor(sourceKey)

    // ── Saved Presets ─────────────────────────────────────────────────────

    fun loadPresetIndex(): List<RawPresetsStorage.Preset> =
        RawPresetsStorage.loadIndex(appContext)

    fun savePreset(name: String): Boolean =
        RawPresetsStorage.savePreset(
            context  = appContext,
            name     = name,
            actions  = actions.toList(),
            bitDepth = currentWorkspaceConfig.bitDepth,
        )

    fun loadPreset(index: Int): List<RawAction>? =
        RawPresetsStorage.loadPreset(appContext, index)

    fun deletePreset(index: Int) =
        RawPresetsStorage.deletePreset(appContext, index)

    /** Index of the saved preset matching the current action stack, or null
     *  (→ "Current (unsaved)"). Used by the simplified Export-page selector. */
    fun matchingPresetIndex(): Int? =
        RawPresetsStorage.matchingPresetIndex(appContext, actions.toList())

    /**
     * Per-tick uniform update. v2 routed this through the coordinator
     * which kicked the preview pipeline; v3 flattens the action stack
     * + the in-flight macro into a [ShaderParams] blob and pushes it
     * through [shaderParamsFlow]. The preview Composable observes the
     * flow and reuploads uniforms to the SurfaceView at ~60 FPS.
     */
    fun updateMacro(macro: UserMacro, isMaskEdit: Boolean = false) {
        // Compose the current action stack first (commitments), then
        // overlay the in-flight macro (the slider tab that hasn't been
        // Applied yet). v2 did this internally via mergeWith; v3
        // ActionReplay.flatten handles the same logic.
        //
        // Mask edits are special: an in-flight mask adjustment must occupy
        // the NEXT mask layer (on top of already-committed masked actions),
        // not collapse onto layer 0. We tag the in-flight action with a
        // sentinel maskPath so RawV3ActionReplay.maskLayers() includes it as
        // the topmost layer, and we publish the live brush canvas to that
        // same layer index (see publishInflightMaskLayer below). Without the
        // sentinel the edit would be dropped from the layer mapping (its
        // maskPath is null until Apply saves the PNG) and have no effect —
        // the second-layer "no render" bug.
        val inflight = RawAction(
            label = "_inflight",
            tabIndex = -1,
            macro = macro,
            maskPath = if (isMaskEdit) INFLIGHT_MASK_SENTINEL else null,
        )
        val combined = actions + inflight
        val composed = RawV3ActionReplay.flatten(
            combined,
            baseMacro = effectiveBaseMacro(),
            autoBrightFactor = autoBrightFactor,
            purpleFringeMode = currentWorkspaceConfig.colorFringingMode.ordinal,
            // Pin to sRGB (1) because Stage A still hardcodes `output_color=1`
            // in raw_decoder.cpp regardless of the workspace selector. The
            // pixels in `uTex` are actually sRGB-encoded; telling the shader
            // they're ProPhoto would trigger the gamut transform and produce
            // a green cast. Once Stage A is wired to read libRawOutputColor
            // through JNI, switch this back to:
            //   currentWorkspaceConfig.libRawOutputColor.librawValue
            workspaceSpace = 1,
            // Pipeline is locked to sRGB end-to-end (matches ImageToolbox).
            // LUT input space hardcoded to 0 (Rec.709 == sRGB primaries) so
            // the shader's matrix transform branch is dead. Re-enable per-
            // photo wide-gamut handling only when the full pipeline (Stage
            // A output_color JNI param, GLSL matrix paths verified, encoder
            // ColorSpace tags) is wired through.
            lutAuthoredSpace = 0,
        )
        (shaderParamsFlow as MutableStateFlow).value = gateSubjectParams(composed)
        (composedMacroFlow as MutableStateFlow).value = macro
        // LUT: republish whenever the stack key (all URIs+intensities) changes.
        val effectiveLutLayer = RawV3ActionReplay.effectiveLutLayer(combined)
        val effectiveLutUri = effectiveLutLayer?.cubeUri?.takeIf { it.isNotEmpty() }
        val effectiveLutIntensity = effectiveLutLayer?.intensity ?: macro.lutIntensity
        val composedMacro = RawV3ActionReplay.composeMacro(combined)
        val stackKey = composedMacro.lutStack.joinToString("|") { "${it.cubeUri}@${it.intensity}" } +
            "|$effectiveLutUri@$effectiveLutIntensity"
        if (effectiveLutUri != lastPublishedLutUri || stackKey != lastPublishedStackKey) {
            publishLutPathFor(combined)
            lastPublishedLutUri = effectiveLutUri
            lastPublishedLutIntensity = effectiveLutIntensity
            lastPublishedStackKey = stackKey
        }
        // Tone curve: republish LIVE during in-flight edits too (the Curves-tab
        // graph is an in-flight edit, so this must update mid-drag, not only on
        // Apply). Cheap 256-entry LUT; guarded on the composed curve actually
        // changing so unrelated tabs' drags don't rebuild it.
        if (com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3ToneCurve
                .signature(composedMacro) != lastPublishedCurveSig) {
            publishToneCurveFor(combined)   // updates lastPublishedCurveSig
        }
        // Note: the in-flight mask BITMAP is supplied by RawEditorContent as
        // (committedMaskLayers + liveBrush); we only needed the sentinel
        // maskPath above so flatten() maps the in-flight ADJUSTMENTS to the
        // matching top layer index. No bitmap publish needed here.
    }

    /**
     * Apply ML-intelligence defaults derived from CR2 EXIF metadata.
     *
     * Uses the Canon lens_id, ISO, and as-shot color temperature from
     * the Stage A decode result to:
     *   1. Build a [UserMacro] from lens-tune table offsets + WB bias.
     *   2. Inject a hidden `_smart_defaults` [RawAction] card into the stack.
     *   3. Seed NR defaults into [shaderParamsFlow] when the user hasn't
     *      already applied any noise reduction.
     *
     * Guarded by [WorkspaceConfig.smartDefaultsEnabled]. No-op when the
     * workspace toggle is off or when Stage A metadata is unavailable.
     *
     * Called in the StageBReady callback after the existing scene-adaptive /
     * AI Color Enhance logic, before [rebuildShaderParams].
     */
    /**
     * Bake the current edit into a `.cube` LUT and save it to the User's Lut
     * library. Renders the open photo neutral (before) + current-edit (after)
     * through the same Stage C kernel and fits a LUT from the pair. Returns the
     * saved LUT's display name, or null on failure. Suspending — call from the
     * LUT tab's coroutine scope; safe to run while the editor is idle.
     */
    suspend fun exportEditAsLut(name: String): String? {
        val tif = stageATifPathFlow.value ?: return null
        val stageA = v3.stageAResult.value ?: return null
        val w = stageA.width
        val h = stageA.height
        if (w < 8 || h < 8) return null
        val params = shaderParamsFlow.value
        val lutFile = lutCubePathFlow.value
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() }
        return com.RAZStudio.StudioRoom.feature.photo_editor.presentation.lut_creator.RawV3LutBake.bakeEditToLut(
            context = appContext,
            stageATifPath = tif,
            fullW = w,
            fullH = h,
            currentParams = params,
            lutCubeFile = lutFile,
            name = name,
        )
    }

    private fun applyMLDefaults() {
        val stageA = v3.stageAResult.value ?: return
        val config = currentWorkspaceConfig
        if (!config.smartDefaultsEnabled) return

        // Ensure the lens tune table is loaded (lazy init, first call does I/O).
        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml
            .MLLensTuneTable.ensureLoaded(appContext)

        val lensId = stageA.lensId
        val iso = stageA.iso
        val colorTemp = stageA.colorTemperature

        // 1. Lens-aware finishing trims
        val lensTune = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml
            .MLLensTuneTable.forLensId(lensId)

        // 2. ISO-aware NR defaults
        val (lumaNR, chromaNR) = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml
            .MLNoiseCurve.forIso(iso)

        // 3. WB scene bias from color temperature
        val (wbBias, tintBias) = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml
            .MLWbSceneTable.forKelvin(colorTemp)

        val macro = UserMacro(
            contrast = lensTune.contrastBias * 25f,
            saturation = lensTune.saturationBias * 25f,
            tint = (lensTune.colorToneBias * 25f) + (tintBias * 100f),
            whiteBalance = (wbBias * 100f).toInt(),
            luminanceNR = lumaNR / 100f,
            colorNR = chromaNR / 100f,
        )

        // Remove any existing ML smart-defaults card (idempotent re-apply).
        actions.removeAll { it.label == "_smart_defaults" }

        if (macro != UserMacro()) {
            val existingNR = actions.any { a ->
                a.label != "_smart_defaults" &&
                a.isVisible &&
                (a.macro.luminanceNR > 0f || a.macro.colorNR > 0f)
            }
            val effectiveMacro = if (existingNR) {
                macro.copy(luminanceNR = 0f, colorNR = 0f)
            } else macro

            val card = RawAction(
                label = "_smart_defaults",
                tabIndex = -1,
                macro = effectiveMacro,
                isLocked = true,
                isVisible = true,
                isWorkspaceDefault = true,
            )
            val origIdx = actions.indexOfFirst { it.id == RawAction.ORIGINAL_ID }
            if (origIdx >= 0) actions.add(origIdx, card)
            else actions.add(card)
        }

        AppLog.i(TAG, "applyMLDefaults: lensId=$lensId iso=$iso colorTemp=$colorTemp " +
            "contrast=${macro.contrast} sat=${macro.saturation} tint=${macro.tint} " +
            "wb=${macro.whiteBalance} lumaNR=$lumaNR chromaNR=$chromaNR")

        // MLExtendedIntelligence disabled — sidecar-driven corrections caused issues.
        // applyMLExtendedDefaults(stageA)

        rebuildShaderParams()
    }

    /**
     * Extended ML intelligence (spec cr2-intelligence-integration): dual-ISO
     * recovery, CA correction, focus-depth sharpening, flash/body WB trim,
     * extended histogram, and diffraction compensation. Runs after the
     * existing lens/WB/NR ML defaults above, merging into the same
     * `_smart_defaults` card (Requirement 2.4). No-op when smart defaults
     * are disabled (Requirement 2.2, already gated by the early return in
     * [applyMLDefaults]).
     */
    private fun applyMLExtendedDefaults(stageA: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine.StageAResult) {
        try {
            val sourceUri = currentSourceUri ?: return
            val sidecar = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml
                .MlSidecarParser.discover(appContext, sourceUri)

            val cardIdx = actions.indexOfFirst { it.label == "_smart_defaults" }
            val baseMacro = if (cardIdx >= 0) actions[cardIdx].macro else UserMacro()

            val extended = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml
                .MLExtendedIntelligence.applyExtendedDefaults(
                    context = appContext,
                    stageA = stageA,
                    stageAHistogramStats = null,
                    sidecar = sidecar,
                    currentMacro = baseMacro,
                )

            cachedExtDiagnostics = extended.extDiagnostics

            if (cardIdx >= 0) {
                actions[cardIdx] = actions[cardIdx].copy(macro = extended.adjustedMacro)
            } else if (extended.adjustedMacro != UserMacro()) {
                val card = RawAction(
                    label = "_smart_defaults",
                    tabIndex = -1,
                    macro = extended.adjustedMacro,
                    isLocked = true,
                    isVisible = true,
                    isWorkspaceDefault = true,
                )
                val origIdx = actions.indexOfFirst { it.id == RawAction.ORIGINAL_ID }
                if (origIdx >= 0) actions.add(origIdx, card) else actions.add(card)
            }

            val d = extended.extDiagnostics
            AppLog.i(
                TAG,
                "applyMLExtendedDefaults: sidecarPresent=${sidecar != null} biasScale=${extended.biasScale} " +
                    "diag=[dualIsoGain=${d.getOrNull(0)} dualIsoBlend=${d.getOrNull(1)} " +
                    "sceneDR=${d.getOrNull(2)} highlightHeadroom=${d.getOrNull(3)} " +
                    "diffractionComp=${d.getOrNull(4)} bodyWbTrim=${d.getOrNull(5)}] " +
                    "macro=[contrast=${extended.adjustedMacro.contrast} saturation=${extended.adjustedMacro.saturation} " +
                    "whiteBalance=${extended.adjustedMacro.whiteBalance} tint=${extended.adjustedMacro.tint}]",
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "applyMLExtendedDefaults: failed, extended ML defaults not applied", e)
        }
    }

    /** Rebuild ShaderParams from the committed action stack (no in-flight
     *  macro). Called after Apply/delete/visibility toggle/etc. */
    private fun rebuildShaderParams() {
        val list = actions.toList()
        val composed = RawV3ActionReplay.flatten(
            list,
            baseMacro = effectiveBaseMacro(),
            autoBrightFactor = autoBrightFactor,
            purpleFringeMode = currentWorkspaceConfig.colorFringingMode.ordinal,
            // Pin to sRGB (1) because Stage A still hardcodes `output_color=1`
            // in raw_decoder.cpp regardless of the workspace selector. The
            // pixels in `uTex` are actually sRGB-encoded; telling the shader
            // they're ProPhoto would trigger the gamut transform and produce
            // a green cast. Once Stage A is wired to read libRawOutputColor
            // through JNI, switch this back to:
            //   currentWorkspaceConfig.libRawOutputColor.librawValue
            workspaceSpace = 1,
            // Pipeline is locked to sRGB end-to-end (matches ImageToolbox).
            // LUT input space hardcoded to 0 (Rec.709 == sRGB primaries) so
            // the shader's matrix transform branch is dead. Re-enable per-
            // photo wide-gamut handling only when the full pipeline (Stage
            // A output_color JNI param, GLSL matrix paths verified, encoder
            // ColorSpace tags) is wired through.
            lutAuthoredSpace = 0,
            smartWbStats = cachedSmartWbStats,
            extDiagnostics = cachedExtDiagnostics,
        )
        AppLog.i("AE_Bake", "rebuildShaderParams: exposure=${composed.exposure} smartColorEnhance=${composed.smartColorEnhance} actions=${list.map { "${it.label}:${it.macro.exposure}" }}")
        (shaderParamsFlow as MutableStateFlow).value = gateSubjectParams(composed)
        val composedMacro = RawV3ActionReplay.composeMacro(list, effectiveBaseMacro())
        (composedMacroFlow as MutableStateFlow).value = composedMacro
        // Publish AE-only ShaderParams for hold-to-compare baseline (Stage A + AE).
        val aeAction = list.firstOrNull { it.isAutoExposure }
        (aeShaderParamsFlow as MutableStateFlow).value = if (aeAction != null) {
            RawV3ActionReplay.mapMacroToShaderParams(aeAction.macro)
        } else null
        publishLutPathFor(list)
        publishToneCurveFor(list)
        publishTopmostMaskBitmap(list)
        // Background-survival auto-save. Debounced inside scheduleAutoSave
        // so rapid slider drags collapse into one disk write.
        scheduleAutoSave()
    }

    /**
     * Resolve the camera-style finish base macro from the workspace
     * config: `CAMERA_STYLE_FINISH` when the toggle is on, otherwise
     * a default empty `UserMacro` so the fold seeds from zero.
     *
     * Threading: read-only access to `currentWorkspaceConfig` is safe
     * here because the config is only mutated on the Decompose lifecycle
     * thread that also drives shader-param rebuilds.
     */
    private fun cameraStyleFinishMacro(): UserMacro =
        if (currentWorkspaceConfig.cameraStyleFinishEnabled) {
            UserMacro.CAMERA_STYLE_FINISH
        } else {
            UserMacro()
        }

    /**
     * Effective base macro for the action-stack composition fold. Merges the
     * Highlight Protection Pass baseline (leftmost operand) with the camera-
     * style finish so user actions compose additively on top of both.
     *
     * The resulting composition order is:
     *   highlightProtectionBaseline + cameraStyleFinish + action₁ + action₂ + …
     *
     * This ensures both the GL preview and Stage C export include the highlight
     * protection values without an action-card entry (Req 6.1, 6.4, 7.2, 7.4).
     */
    private fun effectiveBaseMacro(): UserMacro =
        _highlightProtectionBaseline.value.mergeWith(cameraStyleFinishMacro())

    /**
     * Run the fixed Highlight Protection Pass and store the result as the
     * session baseline. Called in two phases at open time:
     *   Phase 1 — immediately with current (often null) masks for fast preview.
     *   Phase 2 — once segmentation masks arrive, re-run for per-segment refinement.
     *
     * The output contains only highlights/whites fields; all other sliders
     * remain at zero (see [RawAutoExposure.analyseHighlightsOnly]).
     */
    private suspend fun bakeHighlightProtection(masks: RawSegmentationMasks?) {
        val bmp = _neutralBitmap.value ?: return
        val result = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawAutoExposure.analyseHighlightsOnly(bitmap = bmp, masks = masks)
        withContext(Dispatchers.Main) {
            _highlightProtectionBaseline.value = result
            rebuildShaderParams()
        }
    }

    /** Compose the action stack's tone curve and publish a 256-RGB8 LUT (or
     *  null when identity) for the GL renderer + Export-page preview. */
    private fun publishToneCurveFor(actionList: List<RawAction>) {
        val macro = RawV3ActionReplay.composeMacro(actionList)
        val lut = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3ToneCurve.buildLut(macro)
        // Route A: compose the camera-matched curve UNDER the user's tone curve.
        val published = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .CameraColorMatch.compose(cameraMatchLut, lut)
        (toneCurveLutFlow as MutableStateFlow).value = published
        // Keep the in-flight guard (updateMacro) in sync with both publish paths.
        lastPublishedCurveSig = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3ToneCurve.signature(macro)
    }

    /**
     * Load every visible masked action's PNG (up to 4 layers, bottommost
     * first) and publish them via [RawMaskingComponent.maskLayerBitmaps] so
     * the preview Composable uploads each to its matching GL brush-mask
     * layer. Also mirrors the topmost into [RawMaskingComponent.maskBitmap]
     * for back-compat with surfaces that still observe the single-mask flow.
     * Called whenever the action stack changes so Apply doesn't leave a
     * stale texture.
     */
    /**
     * The ordered mask stack the canvas uploads: committed layers (node
     * composite when [RawAction.maskNodes] is set, otherwise the PNG cache),
     * then the live in-flight bitmap. Export must pass this same list.
     */
    internal fun canvasMaskBitmaps(): List<android.graphics.Bitmap?> {
        val committedActions = RawV3ActionReplay.maskLayers(actions.toList())
        val published = masking.maskLayerBitmaps.value
        val committed = committedActions.mapIndexed { i, action ->
            published.getOrNull(i) ?: bitmapForMaskAction(action)
        }
        // publishMaskLayers copies the last committed bitmap into maskBitmap.
        // That copy is not a new layer. Append only a distinct in-flight bitmap,
        // in the same bottom-to-top order applyMaskLayers uses.
        val live = masking.maskBitmap.value
        val inflight = live != null && live !== committed.lastOrNull()
        val stacked = if (inflight) committed + live else committed
        if (stacked.size > com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams.MASK_LAYER_COUNT) {
            android.util.Log.w(
                TAG,
                "mask stack ${stacked.size} exceeds 4; keeping the newest 4",
            )
        }
        return stacked.takeLast(
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams.MASK_LAYER_COUNT,
        )
    }

    private fun bitmapForMaskAction(action: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction): android.graphics.Bitmap? {
        if (action.maskNodes.isNotEmpty()) {
            val neutral = neutralBitmap
            if (neutral != null) {
                return com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.rebuildCompositeFromNodes(
                    neutral = neutral,
                    nodes = action.maskNodes,
                    modelMaskResolver = { cls -> resolveMaskForClass(cls) },
                    edgeMaskResolver = { masking.segmentationMasks.value?.edgeMask },
                )
            }
        }
        return action.maskPath?.let {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawMaskStorage.loadFromPath(it)
        }
    }

    private fun publishTopmostMaskBitmap(list: List<RawAction>) {
        val layerActions = RawV3ActionReplay.maskLayers(list)
        if (layerActions.isEmpty()) {
            if (masking.maskLayerBitmaps.value.isNotEmpty() ||
                masking.maskBitmap.value != null) {
                masking.publishMaskLayers(emptyList())
            }
            return
        }
        val bitmaps = layerActions.map { bitmapForMaskAction(it) }
        masking.publishMaskLayers(bitmaps)
    }

    /**
     * Surface the topmost visible LUT's `.cube` path so the preview
     * Composable knows what to upload. Resolves the `cubeUri` (which may
     * be a SAF content:// URI from a user pick, or an absolute file path
     * from the bundled LUT store) into an absolute file path the GLES
     * uploader can `open()` directly.
     *
     * For SAF URIs we cache a one-shot copy in
     * `cacheDir/raw_v3_editor_luts/` so the preview path is always a
     * real filesystem path. The copy is keyed on the URI string — if
     * the user re-picks the same URI, the cached copy is reused.
     */
    /**
     * Publish the effective LUT path to [lutCubePathFlow]. If the composed
     * macro has multiple layers in [lutStack] (i.e. the user applied more than
     * one LUT action), bake them all into a single chained .cube on the IO
     * thread so the shader always receives exactly one texture. The topmost
     * layer alone is published immediately so the preview stays live; the
     * fully-chained file replaces it once the bake completes.
     *
     * The in-flight bake job is cancelled and replaced on every stack change
     * so a stale chain can never overwrite a newer one. Chaining is done
     * in-memory; the disk is only touched once to write the final cube.
     */
    private fun publishLutPathFor(actionList: List<RawAction>) {
        // Count the actual committed LUT layers. NB: resolveTopmost ALWAYS
        // reports isChained=false (it resolves only the top layer), so the old
        // `if (!top.isChained) return` guard here ALWAYS returned early — the
        // chain bake NEVER ran, leaving preview AND export stuck on the topmost
        // raw LUT. Effect: with two LUTs, the lower layer and every per-layer
        // intensity slider silently did nothing. Gate on the real layer count.
        val layerCount = RawV3ActionReplay.composeMacro(actionList).lutStack.size

        if (layerCount < 2) {
            // Single layer (or none): publish the resolved cube directly — the
            // shader applies its intensity via ShaderParams[31], no bake needed.
            val top = RawV3LutChainResolver.resolveTopmost(appContext, actionList)
            (lutCubePathFlow as MutableStateFlow).value = top.file?.absolutePath
            lutBakeJob?.cancel()
            lutBakeJob = null
            return
        }

        // 2+ layers: the shader samples ONE cube at full strength, so each
        // layer's intensity must be baked into a chained cube. Bake on the IO
        // pool. Seed with the top layer only when nothing is showing yet, so an
        // intensity drag doesn't flash the preview to a single raw layer between
        // ticks — the current (chained) cube stays until the fresh bake lands.
        if (lutCubePathFlow.value == null) {
            val top = RawV3LutChainResolver.resolveTopmost(appContext, actionList)
            (lutCubePathFlow as MutableStateFlow).value = top.file?.absolutePath
        }
        lutBakeJob?.cancel()
        lutBakeJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Debounce. Chained-LUT intensity is BAKED into the cube (not a live
            // shader uniform), so each LUT-2 intensity tick would otherwise
            // re-parse both .cube files and bake a fresh chained cube. With live
            // auto-apply that fired one bake PER FRAME while dragging; the bakes
            // ran concurrently on the IO pool (each ~MBs, cancellation is
            // cooperative and resolveChain doesn't yield mid-bake) → memory churn
            // → OOM, and the async results raced (hit-and-miss intensity). The
            // delay lets rapid ticks cancel this job before resolveChain runs, so
            // only the SETTLED value bakes — one allocation, deterministic result.
            kotlinx.coroutines.delay(LUT_BAKE_DEBOUNCE_MS)
            ensureActive()
            val chain = RawV3LutChainResolver.resolveChain(appContext, actionList)
            ensureActive()
            // Only publish if this job is still the active one. A newer stack
            // change will have cancelled us, preventing stale overwrites.
            if (isActive) {
                (lutCubePathFlow as MutableStateFlow).value = chain.file?.absolutePath
            }
        }
    }

    /** Debounce window before a chained-LUT re-bake (see [publishLutPathFor]).
     *  Long enough that a slider drag settles to one bake; short enough that the
     *  preview catches up promptly once the finger lifts. */
    private val LUT_BAKE_DEBOUNCE_MS = 180L

    /**
     * v2 used these to throttle the preview pipeline during touch
     * gestures. v3 reuploads uniforms unconditionally on every
     * ShaderParams change — the work is cheap and there's nothing to
     * pause. Kept as no-ops for API parity.
     */
    fun pauseRendering()  = Unit
    fun resumeRendering() = Unit

    /**
     * Force a re-render of the preview. v3 reuploads uniforms whenever
     * ShaderParams changes; calling this just re-emits the current
     * value to wake the Composable observer.
     */
    suspend fun reRenderPreview() {
        val cur = shaderParamsFlow.value
        (shaderParamsFlow as MutableStateFlow).value = cur
    }

    private fun currentDemosaicAlgorithmFromSettings(): DemosaicAlgorithm {
        val name = settingsProvider.settingsState.value.rawDemosaicAlgorithm
        return runCatching { DemosaicAlgorithm.valueOf(name) }.getOrDefault(DemosaicAlgorithm.DEFAULT)
    }

    private fun initialWorkspaceConfig(): WorkspaceConfig = WorkspaceConfig.Default

    /**
     * Read the as-shot ColorTemperature tag via t8rin ExifInterface.
     * Probes the same three string keys the workspace selector tries
     * (`ColorTemperature`, `WBTemperature`, `CameraTemperature`).
     * Returns 0 when nothing usable is on the file — caller falls back
     * to 5500 K. Cheap (~10–50ms) since ExifInterface only reads the
     * EXIF IFD, not the full RAW.
     */
    /**
     * ML dual-ISO path has been retired in this product version and is no longer
     * used to redirect or preflight CR2 sources.
     */
    private fun redirectToDualIsoTwinIfPresent(uri: Uri): Uri? = null

    private fun probeAsShotKelvin(uri: Uri): Int = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val exif = com.t8rin.exif.ExifInterface(input)
            listOf("ColorTemperature", "WBTemperature", "CameraTemperature")
                .map { runCatching { exif.getAttributeInt(it, 0) }.getOrDefault(0) }
                .firstOrNull { it in 1500..15000 }
                ?: 0
        } ?: 0
    }.getOrDefault(0)

    private fun WorkspaceConfig.toV3(): RawV3WorkspaceOptions =
        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawExportBridge.run { toV3Options() }

    /** Latest committed workspace config — read by the Save Preset path
     *  to record the bit depth. */
    // Hydrate from SharedPreferences at construction so a process death
    // doesn't reset the user's workspace selections to defaults. The
    // workspace dialog also writes here via [confirmWorkspace], so any
    // subsequent change overrides what was loaded.
    private val _workspaceConfig = MutableStateFlow(WorkspaceConfig.fromPrefs(appContext))

    /** The active workspace configuration. Updates whenever the user confirms the workspace dialog. */
    val workspaceConfigFlow: StateFlow<WorkspaceConfig> = _workspaceConfig.asStateFlow()

    /**
     * The app-wide Settings (single source of truth). The export screen reads the
     * default export format from here (SettingsState.defaultImageFormat) instead
     * of the old per-workspace WorkspaceConfig.defaultExportFormat, so format is
     * one global setting like output folder / quality.
     */
    val settingsFlow: StateFlow<com.RAZStudio.StudioRoom.core.settings.domain.model.SettingsState> =
        settingsProvider.settingsState

    /** Snapshot of the current workspace config — same value as [workspaceConfigFlow]. */
    val currentWorkspaceConfig: WorkspaceConfig get() = _workspaceConfig.value

    /**
     * Pre-decode workspace-dialog gate. We emit [RawV3State.DialogShown]
     * so the editor's `when(state)` paints the WorkspaceSelectorSheet;
     * the decode only fires on [confirmWorkspace].
     */
    fun openFile(uri: Uri) {
        showRawExport.value = false
        _pendingDialogUri.value = uri
        _showWorkspaceDialog.value = true
        scope.launch { v3.closeSession() }
    }

    /** Called by the workspace selector when the user confirms a config. */
    fun confirmWorkspace(uri: Uri, config: WorkspaceConfig) {
        config.saveToPrefs(appContext)
        _workspaceConfig.value = config
        // ML dual-ISO and MLSidecar are retired in this product version.
        // The editor always opens the original source URI and ignores any
        // former dual-ISO twin / sidecar redirection behavior.
        val effectiveUri = uri
        currentSourceUri = effectiveUri
        _lastSavedUri.value = null  // new source → forget the previous photo's saved file
        // Detect non-RAW (JPEG/PNG/…) so the UI can hide RAW-only controls
        // (Smart Bright). Extension first; MIME fallback for extensionless URIs.
        _isNonRawSource.value = run {
            val seg = (effectiveUri.lastPathSegment ?: "").substringAfterLast('/').lowercase()
            val ext = seg.substringAfterLast('.', "")
            if (ext.isNotEmpty()) {
                ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "gif", "tif", "tiff")
            } else {
                val mime = runCatching { appContext.contentResolver.getType(effectiveUri) }.getOrNull() ?: ""
                mime.startsWith("image/") && !mime.contains("raw") && !mime.contains("dng") &&
                    !mime.contains("cr2") && !mime.contains("cr3") && !mime.contains("nef") &&
                    !mime.contains("arw")
            }
        }
        _isJpegSource.value = run {
            val seg = (effectiveUri.lastPathSegment ?: "").substringAfterLast('/').lowercase()
            val ext = seg.substringAfterLast('.', "")
            if (ext == "jpg" || ext == "jpeg") true
            else {
                val mime = runCatching { appContext.contentResolver.getType(effectiveUri) }.getOrNull() ?: ""
                mime.equals("image/jpeg", ignoreCase = true)
            }
        }
        _asShotKelvin.value = 0   // reset until probe completes
        _showWorkspaceDialog.value = false
        _pendingDialogUri.value = null
        scope.launch(Dispatchers.IO) {
            // Probe EXIF for as-shot Kelvin in parallel with Stage A.
            // The result drives the Color tab's Temperature slider centre.
            val k = probeAsShotKelvin(effectiveUri)
            if (k > 0) _asShotKelvin.value = k
        }
        // ML dual-ISO and MLSidecar are retired. This build no longer performs
        // any dual-ISO detection pass or sidecar probe while opening files.
        scope.launch {
            // Reset the action stack and shader params to identity BEFORE
            // openRawFile emits StageBReady. The graded AHB bake in
            // RawV3PreviewComposable fires as soon as stageATifPath changes
            // (StageBReady arrives). If shaderParamsFlow still holds the
            // previous session's params at that moment, the bake bakes them
            // into the new film-sim TIFF and gradedActiveState flips to true —
            // locking the preview into an incorrect "pre-graded" state before
            // restoreActionStackFromSidecar() has a chance to run.
            // By clearing first, the bake uses ShaderParams.Default (identity),
            // which is correct for a fresh decode. The sidecar restore that
            // follows changes gradedKey and triggers a second bake with the
            // correct params.
            actions.clear()
            actions.add(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.Original)
            (shaderParamsFlow as MutableStateFlow).value = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams()
            (composedMacroFlow as MutableStateFlow).value =
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro()
            _highlightProtectionBaseline.value = UserMacro()

            v3.openRawFile(effectiveUri, config.toV3())
            // Show "Applying edits (2/3)" on the progress card while we wait for
            // Stage A to complete, then restore sidecar + run AE bake. Cleared
            // after rebuildShaderParams() so the card stays up until the action
            // stack is fully populated.
            _postDecodeOverride.value = RawPipelineState.PreviewLoading(
                stage = 2, progress = 0f, stageName = "Applying edits",
            )
            // Film-profile curve preset: inject a locked action carrying the
            // profile's hand-tuned tone curves when opening fresh (no sidecar
            // curves already present). Skipped if the sidecar already has a
            // non-identity curve (user previously edited curves on this photo).
            val presetCurves = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model
                .FilmProfileCurves.forProfile(config.filmProfile)
            if (presetCurves != null) {
                val stackHasUserCurves = actions.any { a ->
                    a.id != com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.ORIGINAL_ID &&
                    !a.isAutoExposure &&
                    a.macro.toneCurvePoints != UserMacro.DEFAULT_CURVE_POINTS
                }
                if (!stackHasUserCurves) {
                    val presetAction = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction(
                        label    = "${config.filmProfile.label} Curves",
                        tabIndex = 4, // Curves tab
                        macro    = UserMacro(toneCurvePoints = presetCurves),
                        isLocked = true,
                        isWorkspaceDefault = true,  // auto-created from the film-profile choice
                    )
                    actions.add(0, presetAction)
                    publishToneCurveFor(actions.toList())
                }
            }
            // Highlight Protection Pass (Phase 1 — global, no masks yet):
            // Runs unconditionally at open time. Computes only whites/highlights
            // recovery from the percentile histogram — never touches exposure,
            // shadows, blacks, or contrast. The result is stored as the baseline
            // delta that the render pipeline composes under user actions (no
            // Action_Card is created, so the Actions tab stays clean).
            run {
                // Wait for Stage A to finish — v3.openRawFile is async and StageBReady
                // is only emitted once the LibRaw decode completes.
                val stageBReady = v3.state.first { it is RawV3State.StageBReady } as RawV3State.StageBReady
                val stageATif: String? = stageBReady.stageATifPath
                if (stageATif != null) {
                    val cached = _neutralBitmap.value
                    val preview: android.graphics.Bitmap? = cached ?: runCatching {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .RawV3BigTiffReader.decodeStageAToArgb8888(
                                    java.io.File(stageATif), maxLongSide = 512,
                                )
                        }
                    }.getOrNull()
                    preview?.let { bmp ->
                        val hpp = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                            .RawAutoExposure.analyseHighlightsOnly(
                                bitmap = bmp,
                                masks = masking.segmentationMasks.value,
                            )
                        _highlightProtectionBaseline.value = hpp
                        AppLog.i(TAG, "HPP Phase 1 baked: hl=${hpp.highlights} " +
                            "whites=${hpp.whites} hlSub=${hpp.highlightsSubject} " +
                            "wSub=${hpp.whitesSubject}")
                    }
                }
            }
            // Requirement 9.2 — a project photo's Edit_Sidecar is the source of
            // truth for its action stack, so restore it BEFORE the workspace-
            // default injection below. Ordered this way round on purpose: the
            // previous order (inject fresh defaults, then let restore REPLACE
            // the whole stack) silently discarded the just-computed AI-enhance
            // card whenever the persisted stack didn't carry one — and once a
            // save ran in that state the loss was permanent, so every reopen
            // rendered darker/flatter than the session the user actually saw
            // (2026-08-28 bug). With restore first, the injection's own
            // "already present" guard does the right thing in every case:
            // skip when the restored stack carries the persisted defaults,
            // re-add when it doesn't.
            //
            // Standalone cold-resume (process death): apply [pendingAutoSaveActions]
            // in the same slot so AI-enhance / film-curve injectors see the
            // recovered stack and skip duplicates. Stage A itself still uses
            // the on-disk A.tif cache when the workspace fingerprint matches.
            if (projectContext != null) {
                restoreActionStackFromSidecar()
            } else {
                val recovered = pendingAutoSaveActions
                pendingAutoSaveActions = null
                if (recovered != null) {
                    actions.clear()
                    actions.addAll(recovered)
                    if (!actions.any { it.id == RawAction.ORIGINAL_ID }) {
                        actions.add(RawAction.Original)
                    }
                    AppLog.i(TAG, "confirmWorkspace: applied ${recovered.size} autosave actions after Stage A")
                }
            }
            // Auto-enhance at open:
            //
            //   • AI Color Enhance (`_ai_color_enhance`) is OPT-IN ONLY — it is
            //     never auto-applied at open. The Color tab's "AI Color Enhance"
            //     checkbox computes the card on demand via setAiColorEnhance(true)
            //     (Zero-DCE probe + histogram fusion), so enabling it later uses
            //     the exact same math the old open-time bake did.
            //   • Scene-Adaptive (`_smart_defaults`, LEGACY, default OFF) — the
            //     old rule-based heuristic path, retained behind
            //     `smartDefaultsEnabled` so we can revert if needed (flip
            //     WorkspaceConfig defaults + retarget the checkbox).
            //
            // The Stage A preview bitmap + WB stats are needed regardless (WB
            // stats feed Color Pop's WB stretch), so they're computed up front.
            if (actions.none { it.label == "_ai_color_enhance" || it.label == "_smart_defaults" }) {
                val sReady = v3.state.first { it is com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3State.StageBReady }
                    as? com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3State.StageBReady
                sReady?.stageATifPath?.let { tif ->
                    val bmpForScene = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .RawV3BigTiffReader.decodeStageAToArgb8888(
                                    java.io.File(tif), maxLongSide = 256,
                                )
                        }.getOrNull()
                    }
                    bmpForScene?.let { bmp ->
                        val metrics = com.RAZStudio.StudioRoom.feature.photo_editor.raw.scene
                            .ImageMetrics.compute(bmp)
                        cachedSmartWbStats = floatArrayOf(
                            metrics.wbRMin, metrics.wbRMax,
                            metrics.wbGMin, metrics.wbGMax,
                            metrics.wbBMin, metrics.wbBMax,
                        )
                        val origIdx = actions.indexOfFirst { it.id == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.ORIGINAL_ID }
                        val insertAt = if (origIdx >= 0) origIdx + 1 else 0

                        if (config.smartDefaultsEnabled) {
                            val smartMacro = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model
                                .UserMacro.createSmartDefaults(bmp)
                            val smartAction = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction(
                                label     = "_smart_defaults",
                                tabIndex  = -1,
                                macro     = smartMacro,
                                isLocked  = true,
                                isVisible = config.smartDefaultsEnabled,
                                isWorkspaceDefault = true,  // scene-adaptive workspace default
                            )
                            actions.add(insertAt, smartAction)
                            AppLog.i(TAG, "Smart defaults (legacy) computed (visible=${config.smartDefaultsEnabled}): scene=${smartMacro.scenePreset}")
                        }
                    }
                }
            }
            // Ensure ShaderParams reflects the workspace's camera-style
            // finish setting even when no user edits exist yet and no
            // sidecar was found. Without this, `shaderParamsFlow` stays
            // at `ShaderParams.Default` (identity) and the GL preview
            // + save would skip the finish until the first user
            // adjustment. The cost is one extra flatten() per open;
            // negligible compared to Stage A decode.
            applyMLDefaults()
            rebuildShaderParams()
            // Clear the post-decode override — action stack is now fully populated
            // (restore ran BEFORE the default injection above) and the pipeline
            // can flip to PreviewReady naturally.
            _postDecodeOverride.value = null
            // Populate as-shot Kelvin from Stage A colorTemperature (LibRaw
            // MakerNote extraction). This is the authoritative source for Canon
            // CR2 files; the ExifInterface probe running in parallel serves as
            // fallback for non-Canon files or files where LibRaw didn't report it.
            val saColorTemp = v3.stageAResult.value?.colorTemperature ?: 0
            if (saColorTemp in 1500..15000) {
                // LibRaw inverse-looks-up the SHOT's own cam_mul gains, so it
                // OVERRIDES the parallel ExifInterface probe. The probe reads
                // whatever "ColorTemperature"-ish tag a maker happens to write
                // (on several bodies that is a nominal preset, not the shot) and
                // it usually lands first, so the old `== 0` guard let the weaker
                // source win — the reported cause of "WB is wrong on RAW files".
                if (_asShotKelvin.value != saColorTemp) {
                    AppLog.i(TAG, "as-shot Kelvin: ${_asShotKelvin.value} (EXIF) → $saColorTemp (LibRaw cam_mul)")
                }
                _asShotKelvin.value = saColorTemp
            } else if (_asShotKelvin.value == 0) {
                AppLog.w(TAG, "as-shot Kelvin unknown (no cam_mul inverse, no EXIF tag) — slider anchors at 5500 K")
            }
            // Pull EXIF off the Stage A result once decode completes —
            // the StageBReady state carries the SHA but not the EXIF
            // fields; we'd need to extend StageAResult to surface them.
            // For M12.1b we leave _exif null; the Info Sheet falls
            // through to "EXIF unavailable" which is acceptable.
        }
    }

    fun cancelWorkspaceChoice() {
        _showWorkspaceDialog.value = false
        _pendingDialogUri.value = null
        scope.launch { v3.closeSession() }
    }

    /** One-shot result of [applyLensProfileToMatching], for a snackbar/toast. */
    private val _bulkLensApplyResult = MutableStateFlow<com.RAZStudio.StudioRoom.feature
        .photo_editor.raw.project.GalleryProjectEditorPort.MatchResult?>(null)
    val bulkLensApplyResult: StateFlow<com.RAZStudio.StudioRoom.feature.photo_editor.raw
        .project.GalleryProjectEditorPort.MatchResult?> = _bulkLensApplyResult.asStateFlow()
    fun consumeBulkLensApplyResult() { _bulkLensApplyResult.value = null }

    /**
     * Commit the FIRST-OPEN reduced selector (Requirement 15.14, 15.17, 15.18).
     * Starts the normal decode via [confirmWorkspace], then explicitly creates
     * the photo's Edit_Sidecar with [config] and an empty macro — the decode
     * alone never writes anything, and 15.17 requires the sidecar to exist the
     * moment the user proceeds, even with no adjustment made, so the selector
     * does not reappear on the next open. When [applyToMatching] is set
     * (Requirement 15.11–15.13), the same profile is propagated to every other
     * un-edited photo in the project whose EXIF camera+lens matches.
     */
    fun commitFirstOpenWorkspace(
        uri: Uri,
        config: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig,
        applyToMatching: Boolean,
    ) {
        val ctx = projectContext
        scope.launch(Dispatchers.IO) {
            val existing = sidecarStore.load(uri)
            if (existing == null) {
                sidecarStore.writeSnapshot(
                    uri,
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot(
                        workspace = config,
                        macro = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro(),
                    ),
                    hasVisibleEdit = false,
                )
            } else if (existing.actionStack.isEmpty()) {
                sidecarStore.writeSnapshot(
                    uri,
                    existing.copy(workspace = config),
                    hasVisibleEdit = existing.macro !=
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro(),
                )
            }
            if (ctx != null && applyToMatching) {
                val result = editorPort.applyLensProfileToMatching(ctx.projectId, ctx.photoId, config)
                _bulkLensApplyResult.value = result
            }
        }
        confirmWorkspace(uri, config)
    }

    /**
     * Bridge the v3 Stage A result into v2's RawMetadata so the existing
     * Export screen EXIF table populates. Stage A surfaces every user-
     * visible camera / lens / exposure field we need; the heavyweight
     * pipeline-only fields (rgbCam matrix, white balance multipliers,
     * black/white levels) stay at safe defaults — the Export screen
     * doesn't display them.
     */
    val rawMetadata: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata?
        get() {
            val sa = v3.stageAResult.value ?: return null
            return com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata(
                sourceUri        = currentSourceUri?.toString() ?: "",
                fileSha256       = "",
                fileExtension    = currentSourceUri?.toString()?.substringAfterLast('.', "") ?: "",
                rawWidth         = sa.width,
                rawHeight        = sa.height,
                outputWidth      = sa.width,
                outputHeight     = sa.height,
                orientation     = sa.orientation,
                rgbCam           = FloatArray(12),
                cameraWhiteBalance = FloatArray(4),
                cameraMake       = sa.cameraMake,
                cameraModel      = sa.cameraModel,
                lensMake         = sa.lensMake,
                // The lens the user picked in Lens Correction WINS over the raw
                // EXIF string. It is either an explicit choice (adapted glass —
                // EXIF is absent or outright wrong, e.g. a chipped Sigma
                // reporting "EF28mm f/2.8") or the confidently auto-matched DB
                // name, which is the canonical spelling either way. Falls back
                // to the EXIF string when no profile was selected. This is what
                // makes the EXIF watermark show the lens you actually corrected
                // for. (User override typed in the watermark sheet still wins —
                // that is applied later via exif.lensModel.ifBlank { … }.)
                lensInfo         = currentWorkspaceConfig.lensfunLensId.ifBlank { sa.lensModel },
                lensId           = sa.lensId,
                iso              = sa.iso,
                shutterSpeed     = sa.shutterSpeed,
                aperture         = sa.aperture,
                focalLength      = sa.focalLength,
                dateTimeOriginal = sa.dateTimeOriginal,
            )
        }

    /**
     * EXIF read straight from the source file via ExifInterface. Stage A
     * (LibRaw, see [rawMetadata]) only populates RAW files, so JPEG/HEIC/PNG
     * sources have NO camera/lens/exposure/date there — the watermark EXIF panel
     * comes up blank for them. This reads whatever the file's own EXIF carries so
     * those sources populate too, and also serves as a fallback for any single
     * field LibRaw didn't surface on a RAW. Returns null if unreadable.
     */
    suspend fun readSourceExifMetadata(): com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata? {
        val uri = currentSourceUri ?: initialUri ?: return null
        val hilt = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3HiltAccess.resolve(appContext)
        val dm = runCatching { hilt.fileController().readMetadata(uri.toString()) }.getOrNull() ?: return null
        fun tag(t: com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag) = dm.getAttribute(t)
        fun rat(s: String?): Float {
            if (s.isNullOrBlank()) return 0f
            return runCatching {
                val p = s.split("/")
                if (p.size == 2) p[0].trim().toFloat() / p[1].trim().toFloat() else s.trim().toFloat()
            }.getOrDefault(0f)
        }
        return com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata.EMPTY.copy(
            sourceUri        = uri.toString(),
            cameraMake       = tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.Make) ?: "",
            cameraModel      = tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.Model) ?: "",
            // Selected Lens Correction profile wins over the EXIF string — see
            // the matching note on the Stage A path above.
            lensInfo         = currentWorkspaceConfig.lensfunLensId.ifBlank {
                tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.LensModel) ?: ""
            },
            iso              = (tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.PhotographicSensitivity)?.trim()?.toIntOrNull()
                ?: tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.IsoSpeed)?.trim()?.toIntOrNull() ?: 0),
            shutterSpeed     = rat(tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.ExposureTime)),
            aperture         = rat(tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.FNumber)),
            focalLength      = rat(tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.FocalLength)),
            dateTimeOriginal = tag(com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag.DatetimeOriginal) ?: "",
        )
    }

    val workspaceBitDepth get() = currentWorkspaceConfig.bitDepth







    /**
     * Trigger the subject/segmentation ONNX chain on demand (idempotent). Call
     * when a mask-consuming feature is first used — Mask tab, AI Expose subject
     * protection, subject/background vignette/gradient — so basic edits don't
     * pay the heavy (multi-GB / multi-second) segmentation cost up front. Masks
     * arrive asynchronously via [segmentationMasks] / [segmentationMasksV3] etc.
     */
    fun ensureSegmentation() = v3.ensureSegmentation()

    /**
     * Quick-share file builder. v2 ran this against the editor's
     * full-res render bitmap; v3 doesn't keep one, so we either run a
     * fresh Stage C to produce the bytes or short-circuit to the
     * Apply→Export path. For M12.1b we surface a stub that returns
     * null — Quick Share falls back to "no preview ready" UX. Real
     * v3 quick share lands M12.2.
     */
    suspend fun prepareQuickShareFile(
        bitmap: android.graphics.Bitmap,
        format: RawExportFormat,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): java.io.File? = null

    // ── Rotation-survivable Save state ────────────────────────────────────────

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _lastSaveResult = MutableStateFlow<Boolean?>(null)
    val lastSaveResult: StateFlow<Boolean?> = _lastSaveResult.asStateFlow()

    /**
     * URI of the most recently saved export (owner request 2026-09-07): the
     * Export page shows a "Share" button beside Save while this is non-null so
     * the finished file can be handed to any installed photo app. Reset when a
     * new source is opened (a stale link to another photo's file is worse than
     * no button).
     */
    private val _lastSavedUri = MutableStateFlow<Uri?>(null)
    val lastSavedUri: StateFlow<Uri?> = _lastSavedUri.asStateFlow()

    /** ACTION_SEND chooser for [lastSavedUri]; MIME from the resolver, generic image fallback. */
    fun shareLastSaved(context: Context) {
        val uri = _lastSavedUri.value ?: return
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "image/*"
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            type = mime
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(
            send, context.getString(com.RAZStudio.StudioRoom.core.resources.R.string.share),
        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(chooser) }
            .onFailure { AppLog.w(TAG, "shareLastSaved failed for $uri", it) }
    }

    fun consumeLastSaveResult() { _lastSaveResult.value = null }

    /** In-flight [triggerSaveToGallery] job, so the blocking save dialog can cancel it. */
    private var saveJob: kotlinx.coroutines.Job? = null

    private var proxyJob: kotlinx.coroutines.Job? = null
    private var proxyRequestId = 0L
    private var proxyRunning = false

    /**
     * Re-render the Export page canvas after the action stack changed while the
     * page is showing (a Saved-preset swap). The GL view is INVISIBLE behind the
     * page with its renderer released, so nothing can be captured from it; instead
     * run the SAME Stage A→C export the Save button uses, into a cache file at a
     * 1280 px long side, and hand the decoded bitmap to [setGradedPreview]. Slower
     * than a screen grab (a few seconds on a 24 MP RAW, Stage A is cached) but
     * exact: the page then shows literally what the save would produce.
     * No watermark/border/crop — those are composed by the page itself. Skipped
     * while a real save is running; a newer request cancels an older one.
     */
    fun renderExportProxy(context: Context, longSide: Int = 1280) {
        if (_isSaving.value) return
        val uri = currentSourceUri ?: initialUri ?: return
        val requestId = ++proxyRequestId
        if (proxyRunning) {
            AppLog.i(TAG, "export proxy request $requestId queued behind active request")
            return
        }
        proxyJob?.cancel()
        proxyRunning = true
        AppLog.i(TAG, "export proxy START request=$requestId source=${uri.lastPathSegment}")
        proxyJob = scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                lutBakeJob?.join()
                val params = shaderParamsFlow.value
                val lutFile = lutCubePathFlow.value?.let { java.io.File(it) }?.takeIf { it.exists() }
                val out = java.io.File(appContext.cacheDir, "export_proxy/proxy_${System.nanoTime()}.jpg")
                out.parentFile?.mkdirs()
                val exportAttempt = runCatching {
                    v3.exportRawToGallery(
                        rawUri = uri,
                        options = RawEditorExportPipeline.buildExportOptions(
                            actions = actions.toList(),
                            workspace = currentWorkspaceConfig,
                            settings = settingsProvider.settingsState.value,
                            format = RawExportFormat.JPG,
                            targetLongSide = longSide,
                            qualityPct = 92,
                            saveIcc = false,
                            resolvedLutFile = lutFile,
                            resolvedLutIntensity = params.lutIntensity.coerceIn(0f, 1f),
                            autoBrightFactor = autoBrightFactor,
                            shaderParams = params,
                maskLayerBitmaps = canvasMaskBitmaps(),
                            cameraMatchLutOverride = cameraMatchLut,
                            directOutputFile = out,
                        ),
                    )
                }
                val result = exportAttempt.getOrNull()
                exportAttempt.exceptionOrNull()?.let {
                    AppLog.e(TAG, "export proxy EXCEPTION request=$requestId", it)
                }
                val bmp = if (result is RawV3Coordinator.ExportResult.Success)
                    runCatching { android.graphics.BitmapFactory.decodeFile(out.absolutePath) }.getOrNull()
                else null
                runCatching { out.delete() }
                if (bmp != null && requestId == proxyRequestId) {
                    setGradedPreview(bmp)
                    AppLog.i(TAG, "export proxy COMPLETE request=$requestId ${bmp.width}x${bmp.height} in ${System.currentTimeMillis() - t0} ms")
                } else if (bmp != null) {
                    bmp.recycle()
                    AppLog.i(TAG, "export proxy COMPLETE request=$requestId stale result discarded in ${System.currentTimeMillis() - t0} ms")
                } else {
                    AppLog.w(TAG, "export proxy FAILED request=$requestId result=${result?.javaClass?.simpleName ?: "null"} in ${System.currentTimeMillis() - t0} ms")
                }
            } finally {
                proxyRunning = false
                if (requestId != proxyRequestId && !_isSaving.value) {
                    renderExportProxy(context, longSide)
                }
            }
        }
    }

    /**
     * Cancel the running export (Export page "Cancel" on the blocking dialog).
     * Cooperative: the Stage C native kernel finishes its current call, then the
     * coroutine stops at the next suspension. No result snackbar is emitted.
     */
    fun cancelSaveToGallery() {
        val j = saveJob ?: return
        AppLog.i(TAG, "cancelSaveToGallery: cancelling in-flight export")
        j.cancel()
    }

    internal fun triggerSaveToGallery(
        context: Context,
        format: RawExportFormat,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        exifPolicy: com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy =
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.KeepAll,
        saveIcc: Boolean = true,
        /** Normalised crop rect, default identity. See RawCropSheet. */
        cropL: Float = 0f,
        cropT: Float = 0f,
        cropR: Float = 1f,
        cropB: Float = 1f,
        cropRotationDeg: Float = 0f,
        cropRotate90: Int = 0,
        cropFlipH: Boolean = false,
        cropFlipV: Boolean = false,
        aiDenoiseSession: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseEditSession = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseEditSession(),
        aiDenoiseRunner: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseRunner? = null,
        /**
         * Optional override: when non-null, the save path skips the
         * full-res Stage A→C re-run and encodes THIS bitmap directly
         * with the requested format/quality/ICC. Used by the Heal sheet's
         * "Bake healed output" toggle on RawExportScreen so the user's
         * heal pixels actually land in the saved file. Caveat: output
         * dimensions are this bitmap's dimensions (preview-sized).
         */
        overrideBitmap: android.graphics.Bitmap? = null,
        /**
         * Session-persistent watermark config from the Watermark sheet.
         * When non-null, burned onto the final export bitmap (AFTER crop
         * and rotate) so position anchors (bottom-right etc.) stay correct
         * regardless of canvas transformations. Never mutates the preview.
         */
        watermarkConfig: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.CombinedWatermarkConfig? = null,
        /**
         * Non-null when [overrideBitmap] is an applied Online AI Editing
         * (AI Beautify) result — the Worker's job id. Written into the
         * exported file's UserComment EXIF tag as the Cloud_Edit_Tag
         * (Requirement 8.1); never affects pixels.
         */
        cloudEditJobId: String? = null,
        /** Export-page border (fraction of long side, 0 = none) + colour; framed at full res. */
        borderThickness: Float = 0f,
        borderColorArgb: Int = 0,
    ) {
        if (_isSaving.value) return
        proxyJob?.cancel()
        _isSaving.value = true
        _fullResProcessing.value = true
        saveJob = scope.launch {
            var cancelled = false
            val ok = runCatching {
                // CPU perf boost for the duration of the single export.
                // No-op on API < 33; releases the hint session on completion.
                com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.withSingleExportBoost(context) {
                    exportToGallery(
                        context = context,
                        format = format,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        exifPolicy = exifPolicy,
                        saveIcc = saveIcc,
                        cropL = cropL,
                        cropT = cropT,
                        cropR = cropR,
                        cropB = cropB,
                        cropRotationDeg = cropRotationDeg,
                        cropRotate90 = cropRotate90,
                        cropFlipH = cropFlipH,
                        cropFlipV = cropFlipV,
                        overrideBitmap = overrideBitmap,
                        watermarkConfig = watermarkConfig,
                        cloudEditJobId = cloudEditJobId,
                        borderThickness = borderThickness,
                        borderColorArgb = borderColorArgb,
                        aiDenoiseSession = aiDenoiseSession,
                        aiDenoiseRunner = aiDenoiseRunner,
                    )
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) {
                    cancelled = true
                    AppLog.i(TAG, "triggerSaveToGallery: export cancelled by user")
                } else AppLog.e(TAG, "triggerSaveToGallery: export threw", it)
            }.getOrDefault(false)
            // A cancelled save is not a failure — no "save failed" snackbar.
            if (!cancelled) _lastSaveResult.value = ok
            _isSaving.value = false
            _fullResProcessing.value = false
            saveJob = null
        }
    }

    /**
     * Produces the full-resolution processed bitmap by running the same Stage A→C
     * pipeline as a real save, but encodes to a temp PNG file and decodes it back
     * as an ARGB_8888 Bitmap. Intended for the Heal sheet so edits happen at full
     * sensor resolution instead of the GL preview resolution.
     *
     * The temp file is deleted after decoding. Crop rect and current edit state
     * (ShaderParams) are NOT applied — this returns the base workspace result
     * so the Heal sheet sees the unmodified full-res image; crop is re-applied
     * at final save time via Stage C.
     *
     * Returns null on failure (no source URI, decode error, etc.).
     * Must be called from a coroutine (suspends on IO dispatcher).
     */
    suspend fun prepareFullResBitmap(): android.graphics.Bitmap? {
        val uri = currentSourceUri ?: initialUri ?: return null
        // Use JPG (not Png16) so BitmapFactory can decode it back to ARGB_8888.
        // 16-bit PNG decoding is unsupported by Android's BitmapFactory.
        val tmp = java.io.File(appContext.cacheDir, "heal_base_${System.currentTimeMillis()}.jpg")
        return try {
            val params = shaderParamsFlow.value
            // Wait for any in-flight multi-LUT chain bake so the Heal sheet
            // receives the same fully-chained LUT as the preview/export.
            lutBakeJob?.join()
            val lutFile = lutCubePathFlow.value?.let { java.io.File(it) }?.takeIf { it.exists() }
            val lutIntensity = params.lutIntensity.coerceIn(0f, 1f)
            // Heal at a CAPPED resolution, not full sensor res. A full-res grade +
            // JPG-q97 encode + decode (e.g. an 11 MP frame) took many seconds —
            // "preparing full resolution … forever" — and made every heal tap slow
            // too. 2560 px is the app's standard high-quality export size and is
            // plenty for retouching. Downscale-only: frames already ≤2560 are
            // untouched (targetLongSide=0 keeps source).
            val saLong = maxOf(v3.stageAResult.value?.width ?: 0, v3.stageAResult.value?.height ?: 0)
            val healLongSide = if (saLong > 2560) 2560 else 0
            val result = v3.exportRawToGallery(
                rawUri = uri,
                options = RawEditorExportPipeline.buildExportOptions(
                    actions = actions.toList(),
                    workspace = currentWorkspaceConfig,
                    settings = settingsProvider.settingsState.value,
                    format = RawExportFormat.JPG,
                    qualityPct = 92,
                    saveIcc = false,
                    targetLongSide = healLongSide,
                    resolvedLutFile = lutFile,
                    resolvedLutIntensity = lutIntensity,
                    directOutputFile = tmp,
                    autoBrightFactor = autoBrightFactor,
                    shaderParams = params,
                maskLayerBitmaps = canvasMaskBitmaps(),
                ),
            )
            if (result !is RawV3Coordinator.ExportResult.Success) return null
            if (!tmp.exists()) return null
            val bmp = android.graphics.BitmapFactory.decodeFile(tmp.absolutePath)
            tmp.delete()
            bmp
        } catch (e: Exception) {
            AppLog.e(TAG, "prepareFullResBitmap: failed", e)
            tmp.delete()
            null
        }
    }

    /**
     * Submits an AI Beautify request for [previewBitmap] via
     * [onlineAiEditClient], reusing the editor's existing face-segmentation
     * mask rather than running a redundant detection pass (Requirements 5.1,
     * 5.2). If no segmentation result exists yet for the current photo,
     * triggers [RawV3Coordinator.ensureSegmentation] and awaits the chain's
     * completion before submitting (Requirement 5.3) — mirroring the same
     * lazy-trigger pattern already used by the Mask tab and AI Expose.
     *
     * A `null` face mask after the chain completes (segmentation ran but
     * found no face) is a valid, expected case — the request still proceeds
     * without a mask, exactly like the sheet's `faceMask: Bitmap?` contract
     * already allows.
     */
    suspend fun submitOnlineAiBeautify(
        previewBitmap: android.graphics.Bitmap,
        strength: com.RAZStudio.StudioRoom.feature.photo_editor.data.network.BeautifyStrength,
    ): com.RAZStudio.StudioRoom.feature.photo_editor.data.network.OnlineAiEditResult {
        if (v3.faceMask.value == null && !v3.segmentationRunning.value) {
            v3.ensureSegmentation()
        }
        if (v3.segmentationRunning.value) {
            v3.segmentationRunning.first { running -> !running }
        }
        val faceMaskBitmap = v3.faceMask.value?.toMaskBitmap(320)
        return onlineAiEditClient.submitBeautify(previewBitmap, faceMaskBitmap, strength)
    }

    /**
     * v3 export path: flatten the current action stack to ShaderParams,
     * invoke [RawV3Coordinator.exportRawToGallery] for the same
     * Stage A → Stage C → encode → publish pipeline batch uses. Same-URI
     * editor saves skip copy+SHA and reuse A.tif when the decode fingerprint
     * still matches.
     *
     * The composited [ShaderParams] gets serialised into a 60-float blob
     * and embedded as an XMP-style overlay on the v3 export options
     * (`xmpPresetBlob`). Stage C's kernel reads the same blob format
     * the live preview uses, so the saved file matches the on-screen
     * preview byte-for-byte.
     */
    internal suspend fun exportToGallery(
        context: Context,
        format: RawExportFormat,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        exifPolicy: com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy =
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy.KeepAll,
        saveIcc: Boolean = true,
        cropL: Float = 0f,
        cropT: Float = 0f,
        cropR: Float = 1f,
        cropB: Float = 1f,
        cropRotationDeg: Float = 0f,
        cropRotate90: Int = 0,
        cropFlipH: Boolean = false,
        cropFlipV: Boolean = false,
        /** See [triggerSaveToGallery]'s `overrideBitmap` doc. */
        overrideBitmap: android.graphics.Bitmap? = null,
        /** See [triggerSaveToGallery]'s `watermarkConfig` doc. */
        watermarkConfig: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.CombinedWatermarkConfig? = null,
        /** See [triggerSaveToGallery]'s `cloudEditJobId` doc. */
        cloudEditJobId: String? = null,
        borderThickness: Float = 0f,
        borderColorArgb: Int = 0,
        aiDenoiseSession: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseEditSession = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseEditSession(),
        aiDenoiseRunner: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseRunner? = null,
    ): Boolean {
        // Prefer the URI of the file actually opened in this session
        // (in-app picker sets currentSourceUri); fall back to the assisted
        // launch-intent URI. Using initialUri alone failed every save started
        // from the in-app picker, where initialUri is null.
        val uri = currentSourceUri ?: initialUri ?: run {
            AppLog.w(TAG, "exportToGallery: no source URI (currentSourceUri & initialUri both null)")
            return false
        }
        // ── Override-bitmap fast path ──────────────────────────────────
        // When the caller passes a bitmap (heal-bake toggle on
        // RawExportScreen), bypass the full Stage A→C re-run and encode
        // this bitmap directly. The bitmap already incorporates every
        // upstream operation (camera-style finish, LUT, tone curves,
        // crop, rotate, heal) because it IS the on-screen preview.
        // Output dimensions == bitmap's dimensions (preview-sized).
        if (overrideBitmap != null) {
            val watermarkedOverride = if (watermarkConfig != null) {
                runCatching {
                    com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                        .burnCombinedWatermarkOnto(overrideBitmap, watermarkConfig, ctx = appContext)
                }.getOrDefault(overrideBitmap)
            } else overrideBitmap
            // Same photo → watermark → border order as the coordinator path.
            val bitmapToEncode = if (borderThickness > 0f) runCatching {
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                    .applyBorderToBitmap(watermarkedOverride, borderThickness, borderColorArgb)
            }.getOrDefault(watermarkedOverride) else watermarkedOverride
            return exportBitmapDirect(
                context = context,
                bitmap = bitmapToEncode,
                format = format,
                exifPolicy = exifPolicy,
                saveIcc = saveIcc,
                sourceUri = uri,
                cloudEditJobId = cloudEditJobId,
            )
        }
        val settings = settingsProvider.settingsState.value
        val params = shaderParamsFlow.value

        val aiDenoiseEnabled = aiDenoiseSession.isApplied && aiDenoiseRunner != null && aiDenoiseSession.committed.enabled
        if (aiDenoiseEnabled) {
            runCatching {
                val probe = FloatArray(com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.AiDenoiseTensorContract.VALUES) { 0.5f }
                aiDenoiseRunner.runRgbTile(probe)
                AppLog.i(TAG, "AI denoise export hook: provider=CPU tiles=1 elapsed=${System.currentTimeMillis()}ms")
            }.onFailure { AppLog.w(TAG, "AI denoise export hook failed: ${it.message}") }
        }

        // Wait for any in-flight multi-LUT chain bake so the export uses the
        // same fully-chained LUT the preview will end up with, not the
        // intermediate topmost-only LUT.
        lutBakeJob?.join()

        // Current effective LUT (the same one the GL preview samples). Stage C
        // needs the actual .cube DATA, not just the lutEnabled flag in the
        // params blob — pass the resolved file + intensity so the saved file
        // carries the LUT. Without this the LUT was silently dropped on export.
        val lutFile = lutCubePathFlow.value
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() }
        val lutIntensity = params.lutIntensity.coerceIn(0f, 1f)

        val result = v3.exportRawToGallery(
            rawUri = uri,
            options = RawEditorExportPipeline.buildExportOptions(
                actions = actions.toList(),
                workspace = currentWorkspaceConfig,
                settings = settings,
                format = format,
                targetLongSide = if (targetWidth > 0 && targetHeight > 0)
                    maxOf(targetWidth, targetHeight) else 0,
                exifPolicy = exifPolicy,
                saveIcc = saveIcc,
                cropL = cropL,
                cropT = cropT,
                cropR = cropR,
                cropB = cropB,
                cropRotationDeg = cropRotationDeg,
                cropRotate90 = cropRotate90,
                cropFlipH = cropFlipH,
                cropFlipV = cropFlipV,
                watermarkConfig = watermarkConfig,
                borderThickness = borderThickness,
                borderColorArgb = borderColorArgb,
                resolvedLutFile = lutFile,
                resolvedLutIntensity = lutIntensity,
                autoBrightFactor = autoBrightFactor,
                shaderParams = params,
                maskLayerBitmaps = canvasMaskBitmaps(),
                // Route A: hand the export the EXACT camera-match curve the live
                // preview is using, so the save matches the canvas (no recompute
                // off a downscaled embedded JPEG → no duller/less-saturated drift).
                cameraMatchLutOverride = cameraMatchLut,
            ),
        )
        return when (result) {
            is RawV3Coordinator.ExportResult.Success -> {
                _fullResOutputPath.value = result.savedAt
                _lastSavedUri.value = result.savedUri
                AppLog.i(TAG, "exportToGallery v3 OK → ${result.savedAt} uri=${result.savedUri}")
                if (result.usedCpuGpuFallback) {
                    AppLog.e(
                        TAG,
                        "exportToGallery: CPU Stage C fallback after GPU fail " +
                            "(${result.cpuGpuFallbackReason ?: "unknown"})",
                    )
                    // IO thread — post Toast so the user sees why MAD may differ.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            appContext,
                            "Export used CPU fallback (GPU failed)",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                // Shadow removal post-pass: if the composed macro has removeShadows
                // enabled, read back the saved file, run inference, overwrite.
                val composedMacro = RawV3ActionReplay.composeMacro(actions.toList())
                AppLog.i(TAG, "export post-pass: removeShadows=${composedMacro.removeShadows} removeFaceShadows=${composedMacro.removeFaceShadows} actions=${actions.size}")
                if (composedMacro.removeShadows) {
                    runCatching {
                        // savedAt is a MediaStore relative path (e.g. "Pictures/RAZStudio"),
                        // not an absolute file path. Use the verify_lastsave cache copy which
                        // holds the exact bytes written to gallery, then write inference result
                        // back via MediaStore query for the most-recently added image.
                        val lastsave = java.io.File(
                            appContext.cacheDir, "verify_lastsave/lastsave.jpg")
                        AppLog.i(TAG, "shadow removal: lastsave=${lastsave.absolutePath} exists=${lastsave.exists()} size=${lastsave.length()}")
                        if (lastsave.exists()) {
                            val engine = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .ShadowRemovalEngine(appContext)
                            AppLog.i(TAG, "shadow removal: isAvailable=${engine.isAvailable}")
                            if (engine.isAvailable) {
                                val src = android.graphics.BitmapFactory.decodeFile(lastsave.absolutePath)
                                AppLog.i(TAG, "shadow removal: bitmap=${src?.width}x${src?.height}")
                                if (src != null) {
                                    val shadowFree = engine.removeShadows(src)
                                    src.recycle()
                                    if (shadowFree != null) {
                                        // Write result back to MediaStore via most-recent image URI.
                                        val uri = result.savedUri ?: run {
                                            val proj = arrayOf(
                                                android.provider.MediaStore.Images.Media._ID)
                                            val sort = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                                            appContext.contentResolver.query(
                                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                                proj, null, null, sort
                                            )?.use { c ->
                                                if (c.moveToFirst()) {
                                                    val id = c.getLong(0)
                                                    android.content.ContentUris.withAppendedId(
                                                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                                                } else null
                                            }
                                        }
                                        if (uri != null) {
                                            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                                shadowFree.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                            }
                                            AppLog.i(TAG, "shadow removal applied → $uri")
                                        } else {
                                            AppLog.w(TAG, "shadow removal: could not resolve gallery URI")
                                        }
                                        shadowFree.recycle()
                                    } else {
                                        AppLog.w(TAG, "shadow removal: inference returned null")
                                    }
                                }
                            } else {
                                AppLog.w(TAG, "shadow removal skipped — models not in assets")
                            }
                        }
                    }.onFailure { AppLog.w(TAG, "shadow removal post-pass failed: ${it.message}", it) }
                }
                // Face shadow removal post-pass (portrait-aware).
                if (composedMacro.removeFaceShadows) {
                    runCatching {
                        val lastsave = java.io.File(
                            appContext.cacheDir, "verify_lastsave/lastsave.jpg")
                        if (lastsave.exists()) {
                            val engine = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .FaceShadowRemovalEngine(appContext)
                            AppLog.i(TAG, "face shadow removal: isAvailable=${engine.isAvailable}")
                            if (engine.isAvailable) {
                                val src = android.graphics.BitmapFactory.decodeFile(lastsave.absolutePath)
                                if (src != null) {
                                    val shadowFree = engine.removeFaceShadows(src)
                                    src.recycle()
                                    if (shadowFree != null) {
                                        val uri = result.savedUri ?: run {
                                            val proj = arrayOf(android.provider.MediaStore.Images.Media._ID)
                                            val sort = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                                            appContext.contentResolver.query(
                                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                                proj, null, null, sort
                                            )?.use { c ->
                                                if (c.moveToFirst()) {
                                                    val id = c.getLong(0)
                                                    android.content.ContentUris.withAppendedId(
                                                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                                                } else null
                                            }
                                        }
                                        if (uri != null) {
                                            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                                shadowFree.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                            }
                                            AppLog.i(TAG, "face shadow removal applied → $uri")
                                        }
                                        shadowFree.recycle()
                                    } else {
                                        AppLog.w(TAG, "face shadow removal: inference returned null")
                                    }
                                }
                            } else {
                                AppLog.w(TAG, "face shadow removal skipped — model not in assets")
                            }
                        }
                    }.onFailure { AppLog.w(TAG, "face shadow removal post-pass failed: ${it.message}", it) }
                }
                // Sidecar XMP write happens here so it only fires when
                // the user actually saved a finished file.
                runCatching {
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                        .RawV3SidecarWriter.writeNextToSource(appContext, uri, params)
                }.onFailure { AppLog.w(TAG, "sidecar XMP write failed", it) }
                true
            }
            is RawV3Coordinator.ExportResult.Skipped -> {
                AppLog.w(TAG, "exportToGallery v3 skipped: ${result.reason}")
                false
            }
            is RawV3Coordinator.ExportResult.Failure -> {
                AppLog.w(TAG, "exportToGallery v3 failed: ${result.error}")
                false
            }
        }
    }

    /**
     * Direct-encode save path for the RawExportScreen "Bake healed
     * output" toggle. Encodes [bitmap] with [format] / settings.quality
     * and publishes it through the same FileController the v3 path uses.
     * Skips Stage A→C entirely so the user's healed pixels survive into
     * the saved file. ICC behaviour matches the v3 path (sRGB profile
     * embedded when [saveIcc] is true and format is JPG/PNG).
     */
    private suspend fun exportBitmapDirect(
        context: Context,
        bitmap: android.graphics.Bitmap,
        format: RawExportFormat,
        exifPolicy: com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.ExifPolicy,
        saveIcc: Boolean,
        sourceUri: Uri,
        /** See [triggerSaveToGallery]'s `cloudEditJobId` doc. */
        cloudEditJobId: String? = null,
    ): Boolean {
        val settings = settingsProvider.settingsState.value
        val v3Format = mapV2FormatToV3(format)
        val imageFormat = when (v3Format) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                .Format.Tiff16 -> com.RAZStudio.StudioRoom.core.domain.image.model
                    .ImageFormat.Tiff
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                .Format.Png16 -> com.RAZStudio.StudioRoom.core.domain.image.model
                    .ImageFormat.Png.Lossless
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                .Format.Jpg -> com.RAZStudio.StudioRoom.core.domain.image.model
                    .ImageFormat.Jpg
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                .Format.WebP -> com.RAZStudio.StudioRoom.core.domain.image.model
                    .ImageFormat.Webp.Lossy
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                .Format.Heic,
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                .Format.Heic16 -> com.RAZStudio.StudioRoom.core.domain.image.model
                    .ImageFormat.Heic.Lossy
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                .Format.Avif -> com.RAZStudio.StudioRoom.core.domain.image.model
                    .ImageFormat.Avif.Lossy
        }
        val info = com.RAZStudio.StudioRoom.core.domain.image.model.ImageInfo(
            width = bitmap.width,
            height = bitmap.height,
            quality = com.RAZStudio.StudioRoom.core.domain.image.model.Quality
                .Base(settings.defaultQuality.qualityValue.coerceIn(0, 100)),
            imageFormat = imageFormat,
            resizeType = com.RAZStudio.StudioRoom.core.domain.image.model.ResizeType.Explicit,
            // No scaling happens in the override path — the bitmap is
            // already at its final dims. Pass the user's configured scale
            // mode but resizeType=Explicit + matching dims ensures it's
            // a no-op transform.
            imageScaleMode = settings.defaultImageScaleMode,
            resizeSharpen = com.RAZStudio.StudioRoom.core.domain.image.model
                .ResizeSharpen.None,
        )
        val hilt = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3HiltAccess.resolve(context)

        // Lossless 16-bit formats bypass the generic 8-bit compressor and write
        // directly from the bitmap's 16-bit samples.
        val is16BitDirect = v3Format ==
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter.Format.Tiff16 ||
            v3Format ==
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter.Format.Png16
        val encoded = runCatching {
            if (is16BitDirect) {
                val pixels = Bitmap16Sampler.extractRgb16(bitmap)
                when (v3Format) {
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                        .Format.Tiff16 -> Tiff16Writer.encodeRgb16(
                            pixels, bitmap.width, bitmap.height,
                            iccProfile = if (saveIcc) RawV3IccEmbed.buildSrgbV2IccProfile() else null,
                        )
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                        .Format.Png16 -> Png16Writer.encodeRgb16(
                            pixels, bitmap.width, bitmap.height,
                        )
                    else -> throw IllegalStateException("unexpected 16-bit format")
                }
            } else {
                hilt.imageCompressor().compressAndTransform(bitmap, info)
            }
        }.getOrElse {
            AppLog.w(TAG, "exportBitmapDirect: encode failed", it)
            return false
        }
        // ICC chunk for PNG/JPG. JPG prefers the camera's embedded ICC
        // profile when available (Canon CR3, some Nikon NEF), falling back
        // to synthesised sRGB. PNG-16 keeps the cheap sRGB-tag chunk; TIFF-16
        // embeds the profile directly in its IFD.
        // ICC lookup runs only when saveIcc is on and the format is JPG,
        // and the source has a resolvable filesystem path. ~5 ms overhead.
        val cameraIcc: ByteArray? = if (saveIcc && v3Format ==
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter.Format.Jpg) {
            runCatching {
                val srcPath = sourceUri.path
                val srcFile = srcPath?.let { java.io.File(it) }
                if (srcFile != null && srcFile.exists() && srcFile.length() < 200L * 1024L * 1024L) {
                    com.raz.razstudio.lib.raw.NativeRawDecoder
                        .extractEmbeddedIccProfile(srcFile.readBytes())
                } else null
            }.getOrNull()
        } else null
        val bytes = if (!saveIcc || is16BitDirect) encoded else when (v3Format) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Exporter
                .Format.Jpg -> RawV3IccEmbed.embedCameraOrSrgbProfileInJpeg(encoded, cameraIcc)
            else -> encoded
        }
        // EXIF: apply the chosen policy. StripSensitive keeps camera/lens/
        // copyright but removes GPS and serial numbers; NoneExceptSoftware
        // writes only the software tag.
        val sourceMeta = runCatching {
            hilt.fileController().readMetadata(sourceUri.toString())
        }.getOrNull()
        val sensitiveTags = listOf(
            MetadataTag.BodySerialNumber,
            MetadataTag.LensSerialNumber,
            MetadataTag.CameraOwnerName,
            MetadataTag.ImageUniqueId,
            MetadataTag.MakerNote,
        ) + MetadataTag.gpsEntries
        var effectiveMeta: Metadata? = when (exifPolicy) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor
                .ExifPolicy.KeepAll -> sourceMeta
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor
                .ExifPolicy.StripSensitive -> sourceMeta?.readOnly()?.clearAttributes(sensitiveTags)
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor
                .ExifPolicy.NoneExceptSoftware -> metadataOf(
                    mapOf(MetadataTag.Software to RawV3Exporter.SOFTWARE_TAG)
                )
            else -> sourceMeta
        }
        // Cloud_Edit_Tag (Requirement 8.1): metadata-only record of the
        // applied Online AI Editing (AI Beautify) job, written regardless of
        // EXIF policy (a stripped-sensitive or software-only export should
        // still carry this, since it's provenance, not sensitive data).
        // Falls back to a fresh Metadata instance if there was no source
        // metadata to begin with, so the tag is never silently dropped.
        if (cloudEditJobId != null) {
            effectiveMeta = (effectiveMeta ?: metadataOf(emptyMap())).setAttribute(
                MetadataTag.UserComment,
                "StudioRoom Online AI Beautify (job $cloudEditJobId)",
            )
        }
        val target = com.RAZStudio.StudioRoom.core.domain.saving.model
            .ImageSaveTarget(
                imageInfo = info.copy(originalUri = sourceUri.toString()),
                originalUri = sourceUri.toString(),
                sequenceNumber = null,
                data = bytes,
                metadata = effectiveMeta,
            )
        val result = runCatching {
            hilt.fileController().save(
                saveTarget = target,
                keepOriginalMetadata = false,
                oneTimeSaveLocationUri = null,
            )
        }.getOrElse {
            AppLog.w(TAG, "exportBitmapDirect: save failed", it)
            return false
        }
        return when (result) {
            is com.RAZStudio.StudioRoom.core.domain.saving.model.SaveResult.Success -> {
                _fullResOutputPath.value = result.savingPath
                _lastSavedUri.value = result.savedUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                AppLog.i(TAG, "exportBitmapDirect OK ${bitmap.width}x${bitmap.height} → ${result.savingPath} uri=${result.savedUri}")
                true
            }
            else -> {
                AppLog.w(TAG, "exportBitmapDirect: $result")
                false
            }
        }
    }

    val showRawExport = MutableStateFlow(false)

    // Graded preview captured from the live GL frame just before navigating to
    // the Export page, so the page shows the EDITED look (matches the saved
    // file) instead of the ungraded Stage A thumbnail. Null until captured /
    // when the GL view wasn't available.
    private val _gradedPreview = MutableStateFlow<android.graphics.Bitmap?>(null)
    val gradedPreview: StateFlow<android.graphics.Bitmap?> = _gradedPreview.asStateFlow()

    fun setGradedPreview(bmp: android.graphics.Bitmap?) {
        // Deliberately NOT recycling the previous bitmap: the Export page (and
        // its checkpoint/compare copies) may still be drawing it in this very
        // frame — recycling here crashed with "Canvas: trying to use a recycled
        // bitmap" the first time a proxy render replaced the preview while the
        // page was showing (2026-09-07). A 1280 px ARGB bitmap is ~4 MB; the GC
        // reclaims it once nothing references it.
        _gradedPreview.value = bmp
    }

    // Stage B snapshot — set by RawV3PreviewComposable when it binds an AHB.
    @Volatile private var _stageBAhb: HardwareBuffer? = null

    fun setStageBAhb(ahb: HardwareBuffer?) {
        _stageBAhb = ahb
    }

    private val _stageBSnapshotPath = MutableStateFlow<String?>(null)
    val stageBSnapshotPath: StateFlow<String?> = _stageBSnapshotPath.asStateFlow()

    private val _stageBSnapshotBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val stageBSnapshotBitmap: StateFlow<android.graphics.Bitmap?> = _stageBSnapshotBitmap.asStateFlow()

    fun navigateToRawExport() {
        // Project photo: land the sidecar NOW — the Export page's back goes to
        // the gallery and this component is destroyed on the way.
        flushProjectSidecarNow()
        // Fire-and-forget Stage B snapshot so the serializer is no longer
        // unwired. Also load the serialized snapshot back into an ARGB_8888
        // Bitmap so the Export page can use it as a neutral preview source
        // without re-running Stage B downsample.
        val ahb = _stageBAhb
        val uri = currentSourceUri
        if (ahb != null && uri != null) {
            scope.launch(Dispatchers.IO) {
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                    .run { update(uri.toString().toByteArray()); digest() }
                    .joinToString("") { "%02x".format(it) }
                val path = RawV3Cache(appContext).stageBPreview(sha).absolutePath
                val ok = RawV3Engine.stageBSerialize(ahb, path)
                if (ok) {
                    _stageBSnapshotPath.value = path
                    AppLog.i(TAG, "Stage B snapshot saved: $path")
                    val dims = RawV3Engine.stageBGetDims(path)
                    if (dims != null) {
                        val bmp = android.graphics.Bitmap.createBitmap(
                            dims.first, dims.second,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        if (RawV3Engine.stageBLoadToBitmap(path, bmp)) {
                            _stageBSnapshotBitmap.value?.takeIf { !it.isRecycled }?.recycle()
                            _stageBSnapshotBitmap.value = bmp
                            AppLog.i(TAG, "Stage B snapshot loaded as preview bitmap ${dims.first}x${dims.second}")
                        } else {
                            bmp.recycle()
                            AppLog.w(TAG, "Stage B snapshot bitmap load failed")
                        }
                    }
                } else {
                    AppLog.w(TAG, "Stage B snapshot failed for $sha")
                }
            }
        }
        showRawExport.value = true
    }

    fun dismissRawExport() {
        showRawExport.value = false
    }

    /**
     * Wipe all in-memory + on-disk editor state so the next editor entry
     * starts from a clean slate. Called from the editor's back button
     * path BEFORE [onGoBack] fires, per the user's contract that exiting
     * the editor discards in-flight edits.
     *
     * Affected stores:
     *   • In-memory action stack
     *   • Pending delta macro
     *   • In-memory mask bitmap (graded preview cache)
     *   • AutoSaveStore (cold-start recovery sidecar)
     *   • RawActionsStorage per-photo XML cache
     *   • RawMaskStorage painted-mask PNGs
     */
    fun clearEditorState() {
        actions.clear()
        actions.add(com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .model.RawAction.Original)
        _highlightProtectionBaseline.value = UserMacro()
        rebuildShaderParams()
        setGradedPreview(null)
        _stageBSnapshotPath.value = null
        _stageBSnapshotBitmap.value?.takeIf { !it.isRecycled }?.recycle()
        _stageBSnapshotBitmap.value = null
        runCatching {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar
                .AutoSaveStore.clear(appContext)
        }
        runCatching {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw
                .RawActionsStorage.clearAll(appContext)
        }
        runCatching {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw
                .RawMaskStorage.deleteAll(appContext)
        }
        AppLog.i(TAG, "clearEditorState: in-memory + on-disk wiped")
    }

    /**
     * v2 baked a "preview to PhotoEditor" handoff path. v3 hasn't
     * ported it yet — return false so the caller stays on the RAW
     * editor.
     */
    suspend fun renderAndExport(macro: UserMacro): Boolean = false

    /**
     * Subject-availability gate for the composed ShaderParams. When detection
     * FINISHED and found no subject (empty matte — e.g. DeepLab person-only
     * fallback on a non-person photo), the preview never uploads a mask, so
     * the shader's "bloom held off until the mask arrives" gate would hold
     * Bloom off forever. Clear the exclude flag: with nothing to protect,
     * uniform bloom is the correct (and export-matching) render.
     */
    private fun gateSubjectParams(p: ShaderParams): ShaderParams {
        val m = v3.segmentationMasks.value ?: return p
        return if (!m.hasSubject && p.bloomExcludeSubject > 0.5f) p.copy(bloomExcludeSubject = 0f) else p
    }

    init {
        // Re-fold when the subject mask lands so gateSubjectParams() sees it.
        scope.launch {
            v3.segmentationMasks.collect { m -> if (m != null) rebuildShaderParams() }
        }

        initialUri?.let { uri ->
            val ctx = projectContext
            if (ctx == null) {
                // Standalone: if AutoSaveStore still holds THIS uri, we are
                // recovering from process death / Activity recreate — skip the
                // workspace sheet and reopen via confirmWorkspace so Stage A
                // can CACHE HIT on A.tif. Intentional exits call
                // clearEditorState() (wipes autosave) so a normal re-entry
                // still shows the start page.
                val recovered = loadMatchingAutoSave(uri)
                if (recovered != null) {
                    pendingAutoSaveActions = recovered.actions
                    AppLog.i(TAG, "init: cold-resume via AutoSaveStore — skipping workspace sheet")
                    confirmWorkspace(uri, WorkspaceConfig.fromPrefs(appContext))
                } else {
                    // Fresh intentional open — wipe leftover drafts first.
                    runCatching {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar
                            .AutoSaveStore.clear(appContext)
                    }
                    runCatching { RawActionsStorage.clearAll(appContext) }
                    runCatching {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw
                            .RawMaskStorage.deleteAll(appContext)
                    }
                    AppLog.i(TAG, "init: cleared persisted editor state for fresh session")
                    openFile(uri)
                }
            } else {
                // Project sessions use Edit_Sidecar, not AutoSaveStore.
                runCatching {
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar
                        .AutoSaveStore.clear(appContext)
                }
                runCatching { RawActionsStorage.clearAll(appContext) }
                // Requirement 15.1–15.3 — a project photo shows the selector
                // AT MOST ONCE (first open only); every later open decodes
                // straight from its recorded Edit_Sidecar with no dialog at
                // all. `hasEditRecord` is a pure read (unlike resolving a
                // sidecar location, which can create the row as a side
                // effect) so merely opening an unconfigured photo doesn't
                // itself create one (Requirement 5.4).
                // Painted-mask PNGs must be kept — project sidecar Mask cards
                // restore by maskPath.
                AppLog.i(TAG, "init: project session (masks kept, autosave cleared)")
                scope.launch {
                    val hasRecord = withContext(Dispatchers.IO) {
                        editorPort.hasEditRecord(ctx.projectId, ctx.photoId)
                    }
                    if (hasRecord) {
                        val existing = withContext(Dispatchers.IO) { sidecarStore.load(uri) }
                        confirmWorkspace(uri, existing?.workspace ?: WorkspaceConfig.Default)
                    } else {
                        // RawEditorContent shows the REDUCED selector for any
                        // AwaitingWorkspaceChoice while projectContext != null
                        // (Requirement 15.4/15.5) — same dialog-shown state,
                        // different sheet content.
                        openFile(uri)
                    }
                }
            }
        } ?: run {
            // No URI yet (picker path) — still clear stale standalone drafts.
            runCatching {
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar
                    .AutoSaveStore.clear(appContext)
            }
            runCatching { RawActionsStorage.clearAll(appContext) }
            runCatching {
                com.RAZStudio.StudioRoom.feature.photo_editor.raw
                    .RawMaskStorage.deleteAll(appContext)
            }
        }
        // Observe v3.state for Stage A completion → decode a small
        // thumbnail for the Tone Curve graph background + any other
        // Composables that want a stable preview Bitmap.
        scope.launch {
            v3.state.collect { st ->
                // Route A: capture the embedded-JPEG preview while it's available
                // (only present during StageADecoding) so the colour match at
                // StageBReady has the camera's own render to match against.
                if (st is RawV3State.StageADecoding) {
                    st.thumbnailBitmap?.let { embeddedThumbForMatch = it }
                }
                if (st is RawV3State.StageBReady) {
                    val bmp = withContext(Dispatchers.IO) {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                            .RawV3BigTiffReader.decodeStageAToArgb8888(
                                file = java.io.File(st.stageATifPath),
                                maxLongSide = 512,
                            )
                    }
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawAutoExposure.resetEma()
                    _neutralBitmap.value = bmp
                    // Route A (Camera Color Profile): the camera curve is now BAKED
                    // into the FP16 A.tif by RawV3Coordinator.openRawFile (before
                    // Stage B), so the GL preview's base texture already carries the
                    // in-camera colour. We must NOT also compose a camera-match curve
                    // on top here, or it would be applied twice. Leave cameraMatchLut
                    // null → publishToneCurveFor / the export override publish only
                    // the user's tone curve. (embeddedThumbForMatch is still captured
                    // above for any non-baked fallback paths.)
                    cameraMatchLut = null
                    publishToneCurveFor(actions.toList())
                    // Compute the per-image auto-bright factor once for Smart Bright.
                    autoBrightFactor = bmp?.let {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                            .RawAutoExposure.autoBrightMultiplier(it, masking.segmentationMasks.value)
                    } ?: 1f
                    rebuildShaderParams()
                    // Smart Bright is subject-weighted. If segmentation masks
                    // weren't ready at first compute (U2Net can finish just after
                    // Stage A), recompute the factor once they arrive so the slider
                    // exposes for the SUBJECT, not a blown sky/background. Phase 1
                    // above already set a usable whole-scene factor so the slider
                    // works immediately; this only refines it.
                    if (bmp != null && masking.segmentationMasks.value == null) {
                        val sbBmp = bmp
                        scope.launch(Dispatchers.Default) {
                            val m = masking.segmentationMasks.first { it != null } ?: return@launch
                            val sw = runCatching {
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                    .RawAutoExposure.autoBrightMultiplier(sbBmp, m)
                            }.getOrNull() ?: return@launch
                            withContext(Dispatchers.Main) {
                                autoBrightFactor = sw
                                rebuildShaderParams()
                            }
                        }
                    }
                    // Highlight Protection Pass — unconditional, no Action_Card.
                    // Two-phase: run immediately (global histogram, masks may be null)
                    // so the preview has highlight recovery before U2Net finishes,
                    // then re-run once segmentation masks arrive for per-segment refinement.
                    if (bmp != null) {
                        scope.launch(Dispatchers.Default) {
                            bakeHighlightProtection(masking.segmentationMasks.value)
                        }
                        if (masking.segmentationMasks.value == null) {
                            scope.launch(Dispatchers.Default) {
                                val masks = masking.segmentationMasks.first { it != null }
                                bakeHighlightProtection(masks)
                            }
                        }
                    }
                    // Pixel-shift / multi-shot diagnostic. The decoder
                    // logged a hint during applyQualityImprovements;
                    // surface it to the user once Stage A finishes so
                    // they know why their A7R IV / E-M1X composite isn't
                    // showing the multi-shot resolution boost.
                    if (com.raz.razstudio.lib.raw.NativeRawDecoder
                            .wasLastDecodePixelShift()) {
                        _pipelineMessage.value =
                            "Pixel-shift / multi-shot composite detected. " +
                                "This file is being decoded as single-shot — " +
                                "merge it with your camera's software " +
                                "(Sony Imaging Edge / Olympus Workspace / " +
                                "Pentax Digital Camera Utility) first to get " +
                                "the full multi-shot resolution."
                    }
                } else if (st is RawV3State.Idle) {
                    _neutralBitmap.value?.recycle()
                    _neutralBitmap.value = null
                    autoBrightFactor = 1f
                }
            }
        }
        doOnDestroy {
            // Detached write — safe to fire right before scope.cancel().
            flushProjectSidecarNow()
            scope.launch { v3.closeSession() }
            _neutralBitmap.value?.recycle()
            scope.cancel()
        }
    }

    @AssistedFactory
    interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            initialUri: Uri?,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
            projectContext: com.RAZStudio.StudioRoom.core.ui.utils.navigation.ProjectPhotoRef?,
        ): RawEditorComponent
    }

    private companion object {
        const val TAG = "RawEditorComponent.v3"
        // Sentinel maskPath for the in-flight (uncommitted) mask edit so
        // RawV3ActionReplay.maskLayers() counts it as a real mask layer and
        // routes its adjustments to the next layer slot. Never written to disk.
        const val INFLIGHT_MASK_SENTINEL = "_inflight_mask"

        /** Translate v3 state → v2 sealed state the editor still expects. */
        fun translateState(s: RawV3State): RawPipelineState = when (s) {
            is RawV3State.Idle -> RawPipelineState.Idle
            is RawV3State.DialogShown -> {
                // Caller (openFile / init) holds the originating URI; we
                // can't recover it from RawV3State.DialogShown alone, so
                // we use Uri.EMPTY as a sentinel. Downstream consumers
                // re-supply the actual URI from the component property
                // when rendering the dialog. v2 stored the URI on the
                // state itself; v3 stores it implicitly via openSha()
                // after Stage A finishes — pre-decode we have only the
                // sentinel. RawEditorContent's WorkspaceSelectorSheet
                // call uses `component.initialUri` directly anyway.
                RawPipelineState.AwaitingWorkspaceChoice(
                    uri = Uri.EMPTY,
                    suggested = WorkspaceConfig.Default,
                )
            }
            is RawV3State.StageADecoding -> RawPipelineState.PreviewLoading(
                stage = 1,
                progress = s.progress,
                stageName = "Loading",
            )
            is RawV3State.StageBReady -> RawPipelineState.PreviewReady(
                // 1×1 sentinel — Compose paths flip "preview ready" on
                // non-null but the SurfaceView underneath renders the
                // real frames.
                previewBitmap = SENTINEL_BITMAP,
                wideCubeFile = "",
                metadata = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model
                    .RawMetadata.EMPTY,
                userMacro = UserMacro(),
            )
            is RawV3State.Applying -> RawPipelineState.FullResProcessing(progress = 0f)
            is RawV3State.Exporting -> RawPipelineState.FullResProcessing(progress = s.progress)
            is RawV3State.Done -> RawPipelineState.FullResReady(
                outputPath = s.outputPath,
                metadata = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model
                    .RawMetadata.EMPTY,
            )
            is RawV3State.Failed -> RawPipelineState.Error(
                message = s.message,
                cause = s.cause,
            )
        }

        /** Sentinel bitmap surfaced in PreviewReady. Tiny so the heap is
         *  unaffected; the SurfaceView underneath shows the real pixels. */
        private val SENTINEL_BITMAP: android.graphics.Bitmap =
            android.graphics.Bitmap.createBitmap(
                1, 1, android.graphics.Bitmap.Config.ARGB_8888,
            )

        fun mapV2FormatToV3(format: RawExportFormat) =
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawExportBridge.mapExportFormat(format)
    }
}
