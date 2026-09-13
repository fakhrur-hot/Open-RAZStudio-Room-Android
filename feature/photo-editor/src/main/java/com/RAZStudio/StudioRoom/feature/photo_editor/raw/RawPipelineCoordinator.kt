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
import androidx.documentfile.provider.DocumentFile
import com.t8rin.exif.ExifInterface
import com.RAZStudio.StudioRoom.core.domain.utils.timestamp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LutLayer
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationStorage
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarStore
import com.raz.razstudio.lib.raw.NativeRawDecoderV2
import com.raz.razstudio.lib.raw.RawExif
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level coordinator for the two-pipeline RAW processing architecture.
 *
 * ── Responsibilities ────────────────────────────────────────────────────────
 *
 * 1. On [openRawFile]: cancel any active session, spin up [PreviewPipeline] and
 *    [FullResPipeline] for the new file.
 *
 * 2. Relay [PreviewPipeline.state] on [uiState] so the workspace Composable can
 *    drive its canvas and show loading progress.
 *
 * 3. When [PreviewPipeline] emits [RawPipelineState.PreviewReady], forward that
 *    signal to [FullResPipeline] via [previewReadySignal] so full-res processing
 *    starts automatically and silently.
 *
 * 4. When [FullResPipeline] emits [RawPipelineState.FullResReady], store the
 *    output path in [fullResOutputPath] for the export flow (edit-exif module).
 *
 * 5. Expose [updateMacro] so the workspace can report user slider changes.
 *    The macro is snapshotted at the moment preview completes; the full-res
 *    pipeline uses that snapshot so it never chases moving sliders.
 *
 * ── Threading ───────────────────────────────────────────────────────────────
 *
 * Preview pipeline runs on [Dispatchers.Default] (CPU-bound decode/convert).
 * Full-res pipeline runs on the same dispatcher, in a separate coroutine.
 * Both are supervised by [scope] so one failure does not cancel the other.
 *
 * ── Lifecycle ───────────────────────────────────────────────────────────────
 *
 * Bind to the ViewModel's `viewModelScope` or an application-scoped scope.
 * Call [close] in `onCleared()` to cancel all background work.
 *
 * Usage example (inside a ViewModel):
 * ```kotlin
 * private val coordinator = RawPipelineCoordinator(context)
 * val uiState = coordinator.uiState
 *
 * fun onFileOpened(uri: Uri) {
 *     coordinator.openRawFile(uri, viewModelScope)
 * }
 * fun onMacroChanged(macro: UserMacro) {
 *     coordinator.updateMacro(macro)
 * }
 * override fun onCleared() = coordinator.close()
 * ```
 */
/** State of the on-demand full-resolution compare render. */
sealed interface CompareRenderState {
    /** No render requested yet, or compare sheet closed. */
    data object Idle : CompareRenderState
    /** Render in progress. */
    data object Rendering : CompareRenderState
    /**
     * Render complete.
     * @param original Full-res Stage-C bitmap (no macro), scaled to screen fit.
     * @param rendered Full-res bitmap with macro applied, scaled to screen fit.
     */
    data class Ready(val original: Bitmap, val rendered: Bitmap) : CompareRenderState
    /** Render failed. */
    data class Error(val message: String) : CompareRenderState
}

/** RAZRAW product version embedded in exported file metadata (Software EXIF tag). */
const val RAZRAW_SOFTWARE_VERSION = "v1.01 alpha"

class RawPipelineCoordinator(private val context: Context) {

    private val cache       = RawStageCache(context)
    private val detector    = DeviceCapabilityDetector(context)

    // Single background thread at MIN_PRIORITY for compare renders.
    // Never blocks UI; yields CPU automatically due to thread priority.
    private val compareDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "raw-compare").also { it.priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

    // Dedicated thread for the full-res pipeline at NORM_PRIORITY-2.
    // Keeps it off Dispatchers.Default so blocking JNI decode doesn't starve
    // the 120ms preview re-render debounce loop running on Default.
    private val fullResDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "raw-fullres").also { it.priority = Thread.NORM_PRIORITY - 2 }
    }.asCoroutineDispatcher()

    // Dedicated thread for the preview pipeline cold-start (Stage A → C decode +
    // wide-gamut conversion). Runs at NORM_PRIORITY so first preview comes up
    // promptly, but isolated from Dispatchers.Default — the macro re-render loop
    // and the state-collector both ran on Default before, which meant a long
    // preview decode could stall slider responsiveness. Single-threaded by design
    // (Stage A → C is strictly sequential per file).
    private val previewDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "raw-preview").also { it.priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()

    // Dedicated thread for the macro re-render loop (slider-driven). A two-thread
    // pool so two adjacent ticks can overlap if one is mid-render — keeps the
    // canvas updating smoothly on rapid drags. NORM_PRIORITY+1 nudges it ahead of
    // Default workers but stays below the main thread.
    private val renderDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "raw-render").also { it.priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()

    private val _uiState    = MutableStateFlow<RawPipelineState>(RawPipelineState.Idle)
    val uiState: StateFlow<RawPipelineState> = _uiState.asStateFlow()

    /** Absolute path of the full-res output PNG, available after [RawPipelineState.FullResReady]. */
    @Volatile var fullResOutputPath: String? = null
        private set

    // Observable version of fullResOutputPath — changes on each FullResReady emit
    // (early preview-quality path, then final full-res path). The UI observes this
    // to re-trigger renderIdleFullRes on both the early and quality upgrades.
    private val _fullResOutputPathFlow = MutableStateFlow<String?>(null)
    val fullResOutputPathFlow: StateFlow<String?> = _fullResOutputPathFlow.asStateFlow()

    private val _isFullResProcessing = MutableStateFlow(false)
    val isFullResProcessing: StateFlow<Boolean> = _isFullResProcessing.asStateFlow()

    private val _isFullResReady = MutableStateFlow(false)
    val isFullResReady: StateFlow<Boolean> = _isFullResReady.asStateFlow()

    /**
     * Embedded-JPEG fallback bitmap. Non-null when the full-res decode hit
     * the [FullResPipeline.FULL_RES_WATCHDOG_MS] watchdog and we surfaced
     * the camera's own preview JPEG so the user sees something. The UI
     * overlays this on the canvas with a "preview only" banner. Cleared
     * automatically when the decode finishes or errors.
     */
    private val _embeddedFallbackBitmap = MutableStateFlow<Bitmap?>(null)
    val embeddedFallbackBitmap: StateFlow<Bitmap?> = _embeddedFallbackBitmap.asStateFlow()

    // True when the currently-open file is a non-RAW source (JPEG/PNG/WebP/BMP/TIFF).
    // Drives the UI to hide RAW-specific affordances (RAW Light tab, demosaic info,
    // white-balance temperature sliders that depend on sensor data, …).
    private val _isNonRawSource = MutableStateFlow(false)
    val isNonRawSource: StateFlow<Boolean> = _isNonRawSource.asStateFlow()

    // v2 §6 — full structured EXIF surfaced from LibRaw during openRawFile. Drives the
    // editor info sheet (v2-integration §2.1) and the RAW Export page (§3.1). Populated
    // by the cheap NativeRawDecoderV2.readExif() probe before any pipeline starts; null
    // until the probe lands or when the source is non-RAW (BitmapDirectPipeline path).
    private val _exif = MutableStateFlow<RawExif?>(null)
    val exif: StateFlow<RawExif?> = _exif.asStateFlow()

    // v2-integration §6.1 — sidecar reader/writer. Lazily created on first openRawFile so
    // tests can instantiate the coordinator without a real Android Context. Single instance
    // per coordinator lifetime; per-URI mutex is owned by the store itself.
    private val sidecarStore by lazy { SidecarStore(context) }

    /**
     * v2-integration §2.2 — read the sidecar snapshot for the currently-open file. Returns
     * null when no file is open, the sidecar doesn't exist, or parsing failed (corrupt
     * sidecar). UI uses this to render the history list and revision metadata.
     */
    suspend fun loadCurrentSidecar(): com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot? {
        val uri = currentSourceUri ?: return null
        return runCatching { sidecarStore.load(uri) }.getOrNull()
    }

    /**
     * v2-integration §B.3 — snapshot the current macro into the sidecar history stack.
     * Called when the user commits an action (e.g. moves between editing tools) so undo
     * boundaries persist across app restarts. Coalesces with the debounced save: pending
     * writes are cancelled because pushRevision is itself a save.
     */
    suspend fun pushSidecarRevision() {
        val uri = currentSourceUri ?: return
        val ws = currentWorkspaceConfig
        if (!ws.sidecarEnabled) return
        runCatching { sidecarStore.pushRevision(uri, ws, currentMacro) }
    }

    /**
     * v2-integration §2.2 — restore a specific revision from the history stack. Newer
     * revisions than the target are dropped because going forward from a restored point
     * creates a new branch. Returns the snapshot post-restore so the UI can refresh.
     */
    suspend fun revertSidecarRevision(revisionIndex: Int): com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot? {
        val uri = currentSourceUri ?: return null
        val restored = runCatching { sidecarStore.revert(uri, revisionIndex) }.getOrNull() ?: return null
        // Push the restored macro through the same observer path as a normal edit so the
        // render loop refreshes. updateMacro also kicks the debounced save, which is fine
        // because revert() already wrote the truncated history.
        updateMacro(restored.macro)
        return restored
    }

    /** Tracks the source URI of the currently-open file so cleanup paths know which sidecar
     *  to flush. Cleared on cancelSession. */
    @Volatile private var currentSourceUri: Uri? = null

    // Segmentation masks: background, subject, sharp edges — computed once per file.
    private val _segmentationMasks = MutableStateFlow<RawSegmentationMasks?>(null)
    val segmentationMasks: StateFlow<RawSegmentationMasks?> = _segmentationMasks.asStateFlow()

    // Brush mask painted by the user — session-only, reset on new file open.
    // Used by the in-flight delta macro (the currently-being-painted mask), NOT
    // by the per-action mask layers below. Each action with `maskPath != null`
    // owns its own bitmap loaded from disk.
    private val _maskBitmap = MutableStateFlow<Bitmap?>(null)
    val maskBitmap: StateFlow<Bitmap?> = _maskBitmap.asStateFlow()

    /**
     * Ordered list of mask-layer actions (oldest first). Each entry's `maskPath`
     * points to a PNG with the painted alpha. The render pipeline applies each
     * layer sequentially after the baseline macro pass, so multiple mask actions
     * compose correctly. Provided by [RawEditorContent] via [setMaskLayers].
     */
    private val _maskLayers = MutableStateFlow<List<com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction>>(emptyList())

    fun setMaskLayers(layers: List<com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction>) {
        _maskLayers.value = layers
    }

    /**
     * Apply the hidden "Default details" pre-baked sharpening + noise reduction
     * macro on top of the user's [macro]. The fields are additive — user-set
     * sliders compose with these baseline values (e.g. user's `sharpness=10`
     * becomes effective `sharpness=35.95`).
     *
     * Six fields are baked in (values from the "Default details" preset):
     *   - texture = 20.448181, sharpness = 25.945377, smartSharpness = 1.0
     *   - noiseReduction = 0.7824143, luminanceNR = 0.15931372, colorNR = 0.66001403
     *
     * Implementation note: this is invisible to the user — no Actions-tab card
     * is created. The defaults still respect mergeWith semantics so a user who
     * wants zero sharpening would need to negate it (sharpness = -25.95). If
     * this becomes a complaint, expose a "Skip Default details" workspace
     * toggle and consult that here.
     */
    private fun applyRazamazeDefaults(macro: UserMacro): UserMacro {
        // Always inherit the workspace gamut into `outputColorSpace` so the
        // save path encodes the file in the same gamut the canvas is displaying.
        // Previously `outputColorSpace` defaulted to SRGB independently of
        // workspace, which made every save convert wide-gamut → sRGB at write
        // time — visible to the user as the canvas (DCI-P3) looking *different*
        // from the gallery preview (sRGB) of the same file. Verified adb
        // 2026-05-25: with this inheritance the gallery decodes the PNG's
        // embedded ICC and renders at the same on-screen colors as the canvas.
        val gamutMacro = macro.copy(
            outputColorSpace = currentWorkspaceConfig.colorGamut,
        )
        return gamutMacro.copy(
            texture        = (gamutMacro.texture        + 20.448181f).coerceIn(-100f, 100f),
            sharpness      = (gamutMacro.sharpness      + 25.945377f).coerceIn(0f, 100f),
            smartSharpness = (gamutMacro.smartSharpness + 1.0f       ).coerceIn(0f, 1f),
            noiseReduction = (gamutMacro.noiseReduction + 0.7824143f ).coerceIn(0f, 100f),
            luminanceNR    = (gamutMacro.luminanceNR    + 0.15931372f).coerceIn(0f, 1f),
            colorNR        = (gamutMacro.colorNR        + 0.66001403f).coerceIn(0f, 1f),
        )
    }

    /**
     * Apply every visible mask-layer action on top of [base] in order. Each layer
     * has its mask bitmap loaded from disk (`maskPath`) and its `mask*` adjustment
     * fields applied via [MacroProcessor.applyMaskLayer]. Layers with a missing
     * mask file are silently skipped (defensive: file may have been deleted).
     *
     * Returns [base] unchanged when no mask layers are active — saves the function
     * call cost for the common no-mask case.
     */
    private suspend fun applyMaskLayersSequentially(base: Bitmap): Bitmap {
        val layers = _maskLayers.value
        if (layers.isEmpty()) return base
        android.util.Log.i(
            "MaskLayers",
            "applyMaskLayersSequentially: ${layers.size} layer(s) on " +
                "${base.width}x${base.height}",
        )
        var current = base
        for ((index, layer) in layers.withIndex()) {
            // Cancellation checkpoint. Without this, a slow inner loop
            // (`MacroProcessor.applyMaskLayer` is ~500ms per layer) blocks
            // the parent `collectLatest` from honoring its cancellation
            // for the full sequence duration. Result: a stale render
            // finishes *after* a newer render has already published, and
            // overwrites the newer pixels with stale ones — manifests as
            // "rendering won't revert when actions are deleted" because the
            // stale render's bitmap (with deleted layers still applied)
            // stomps the fresh render's bitmap (without them).
            //
            // ensureActive() throws CancellationException if the parent
            // coroutine was cancelled; collectLatest catches and discards.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val path = layer.maskPath ?: run {
                android.util.Log.w(
                    "MaskLayers",
                    "[$index] ${layer.id} '${layer.label}' has no maskPath — skipping",
                )
                return@run null
            } ?: continue
            val mask = RawMaskStorage.loadFromPath(path) ?: run {
                android.util.Log.w(
                    "MaskLayers",
                    "[$index] ${layer.id} '${layer.label}' mask file missing at $path",
                )
                continue
            }
            val mm = layer.macro
            // Per-layer mask field dump — shows whether the layer carries any
            // adjustments at all. A layer with all zeros applies an identity
            // pass (early-return inside applyMaskLayer) and produces no visible
            // change even though it iterates.
            android.util.Log.i(
                "MaskLayers",
                "[$index] '${layer.label}' maskFile=${java.io.File(path).name} " +
                    "size=${mask.width}x${mask.height} | " +
                    "br=${mm.maskBrightness} ct=${mm.maskContrast} " +
                    "temp=${mm.maskTemperature} tint=${mm.maskTint} " +
                    "sat=${mm.maskSaturation} clarity=${mm.maskClarity}",
            )
            val next = MacroProcessor.applyMaskLayer(current, layer.macro, mask)
            val changed = next !== current
            android.util.Log.i(
                "MaskLayers",
                "[$index] applied; outputChanged=$changed (next===base=${next === base})",
            )
            mask.recycle()
            // Recycle the intermediate bitmap when we've moved past it, but
            // never recycle `base` (caller still references it) nor the
            // `cachedPreLutBitmap` (the slider-render cache, line ~720). The
            // pre-LUT cache holds a strong ref AND a Compose canvas may still
            // be rendering from it — recycling here would crash the canvas
            // with a "trying to use a recycled bitmap" SkBitmap error.
            val isCachedPreLut = synchronized(preLutCacheLock) {
                current === cachedPreLutBitmap
            }
            if (next !== current && current !== base &&
                !isCachedPreLut && !current.isRecycled
            ) {
                current.recycle()
            }
            current = next
        }
        return current
    }

    private val segmentationProcessor = RawSegmentationProcessor(context)

    private var sessionScope: CoroutineScope? = null
    private var previewJob: Job? = null
    private var fullResJob: Job? = null
    private var segmentationJob: Job? = null

    private val previewReadySignal = MutableSharedFlow<RawPipelineState.PreviewReady>(replay = 1)

    // @Volatile: mutated on main thread (updateMacro), read from background
    // coroutines (exportToGallery, renderZoomCrop, pushSidecarRevision). Without
    // the volatile guarantee, ARM cores can read a stale cached value.
    @Volatile
    var currentMacro: UserMacro = UserMacro()
        private set
    /**
     * Workspace configuration in effect for the currently-open session.
     * Defaults to [WorkspaceConfig.Default] (8-bit sRGB + RAZAmaze/RCD) when no file
     * is open. Set by [openRawFile] and consumed by Steps 2–4 of the upgrade plan.
     */
    @Volatile var currentWorkspaceConfig:
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig =
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig.Default
        private set
    private var currentFilePath: String? = null

    /**
     * Monotonic session counter. Bumped by [cancelSession] before each new
     * session starts. Long-running jobs (PreviewPipeline, FullResPipeline,
     * idle render, compare render, save) capture the value at launch and
     * check before publishing. If the captured value no longer matches
     * [sessionEpoch], the result is silently dropped — protects against
     * cross-session bleed when cancellation hasn't fully propagated through
     * uninterruptible native work (LibRaw decode, GPU release).
     */
    @Volatile
    private var sessionEpoch: Long = 0L

    // Neutral Stage-C bitmap stored when the preview first becomes ready.
    // MacroProcessor reads this as its immutable source; it is never modified.
    // Scaled to screen-fit resolution so it is 1:1 with what the canvas displays.
    //
    // On the 16-bit workspace path, [neutralBitmap] is `RGBA_F16` (linear-extended-sRGB
    // tagged) so the live Compose canvas shows wide-gamut color. To keep slider response
    // fast, we additionally cache [neutralBitmap8Bit] as `ARGB_8888` and feed THAT to
    // `MacroProcessor.apply()` during slider work — bypassing the slow FP16↔8 round-trip
    // bridge on every tick. The 16-bit precision survives at the Stage C boundary and at
    // export time (which can re-sample from the FP16 source).
    @Volatile var neutralBitmap: Bitmap? = null
        private set
    @Volatile private var neutralBitmap8Bit: Bitmap? = null
    // Full Stage-C bitmap (pre-scale). Used as the crop source for zoom re-render.
    @Volatile var zoomSourceBitmap: Bitmap? = null
        private set
    @Volatile private var latestPreviewReady: RawPipelineState.PreviewReady? = null
    @Volatile private var currentOrientation: Int = 1

    // Single GPU LUT processor per coordinator — create once, reuse, release on close.
    // Avoids EGL context exhaustion (EGL_BAD_ALLOC) on constrained devices.
    @Volatile private var gpuProcessor: com.RAZStudio.opencv_tools.gpu.GpuLutProcessor? = null
    @Volatile private var gpuProcessorInitDone = false
    /**
     * Tracks the previous GPU processor's async release. [acquireGpuProcessor]
     * awaits this before creating a new processor, so the EGL context can't be
     * acquired twice concurrently — root cause of the EGL_BAD_ALLOC the existing
     * code comment was warning about.
     */
    @Volatile private var gpuReleaseJob: Job? = null

    // Debounced macro signal for real-time re-render without rendering on every slider tick.
    private val macroFlow = MutableStateFlow(UserMacro())

    // Tracks the last nav-preview file so it can be deleted when the session ends.
    @Volatile private var lastNavPreviewFile: java.io.File? = null

    // ── Full-res compare render ───────────────────────────────────────────────
    private val _compareState = MutableStateFlow<CompareRenderState>(CompareRenderState.Idle)
    val compareState: StateFlow<CompareRenderState> = _compareState.asStateFlow()
    private var compareJob: Job? = null
    // Generation guard for compare renders. `job?.cancel()` is cooperative —
    // MacroProcessor.apply hops to Dispatchers.Default immediately, so a
    // cancelled job keeps running through the un-checkpointed LUT/gamut stages
    // and can reach the publish site AFTER a newer job already published. The
    // stale job would then recycle the NEWER job's bitmaps (which the canvas is
    // drawing — hard crash) and stomp the state with older pixels. Each job
    // takes a generation at launch; only the latest generation may recycle the
    // previous state or publish.
    private val compareGen = java.util.concurrent.atomic.AtomicLong(0)

    // ── Idle full-res canvas upgrade ──────────────────────────────────────────
    // When the user stops adjusting for 1 second and full-res is ready, this
    // bitmap replaces the preview on the canvas. Cleared immediately on any
    // macro change so the canvas snaps back to the live preview bitmap.
    private val _idleFullResBitmap = MutableStateFlow<Bitmap?>(null)
    val idleFullResBitmap: StateFlow<Bitmap?> = _idleFullResBitmap.asStateFlow()
    private var idleFullResJob: Job? = null
    // Same stale-job guard as compareGen, for the idle full-res upgrade.
    private val idleFullResGen = java.util.concurrent.atomic.AtomicLong(0)

    // Cached decoded full-res base bitmap. Reused across macro tweaks so we don't
    // re-decode the 12 MB+ PNG (and re-allocate the ~20 MB ARGB_8888 bitmap) every
    // ~1 s while the user edits. Invalidated when the source file's mtime changes
    // or when [close]/[clearFullResCache] is called.
    @Volatile private var cachedFullResBase: Bitmap? = null
    @Volatile private var cachedFullResPath: String? = null
    @Volatile private var cachedFullResMtime: Long = -1L

    // Cached pre-LUT bitmap from the most recent slider tick. When the user drags a
    // LUT-only field (intensity, contrastBoost, new LUT URI), the heavy
    // MacroProcessor pass — HSL, sharpness, NR, gradients, etc. — produces the same
    // result and can be skipped. The cache holds one bitmap keyed by the macro fields
    // that affect MacroProcessor's output (everything except lut*+contrastBoost).
    // Always synchronized on [preLutCacheLock] — both the bitmap and the key change
    // together and must be read atomically against the re-render loop.
    private var cachedPreLutBitmap: Bitmap? = null
    private var cachedPreLutKey: UserMacro? = null
    private var cachedPreLutMaskBitmap: Bitmap? = null
    private var cachedPreLutMasks: RawSegmentationMasks? = null
    private var cachedPreLutSource: Bitmap? = null
    private val preLutCacheLock = Any()
    // Deferred-recycle slot for the pre-LUT cache. The lock guards the POINTER
    // swap, not the PIXELS: a cancelled-but-still-running older tick may be
    // reading the evicted bitmap inside applyLutChain (no checkpoints there)
    // when a newer tick swaps the cache. Recycling immediately crashed that
    // reader ("Can't call getPixels() on a recycled bitmap"). Instead the
    // evicted bitmap parks here for ONE more cache generation before being
    // recycled — by then the older tick's LUT call has long finished (ticks are
    // 40 ms-debounced; the deferred window spans a full compute cycle).
    private var retiredPreLutBitmap: Bitmap? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Open a new RAW file. Cancels any active session first.
     *
     * @param uri   Content or file URI of the RAW file.
     * @param scope Coroutine scope from the caller (usually ViewModel's viewModelScope).
     */
    /**
     * Emit the [RawPipelineState.AwaitingWorkspaceChoice] state so the UI can show the
     * workspace selector dialog. Cancels any active session first — opening a new
     * file always supersedes whatever was previously loaded. The actual pipeline
     * starts only when [openRawFile] is called (typically from the dialog's Proceed
     * handler in `RawEditorComponent.confirmWorkspace`).
     */
    fun requestWorkspaceChoice(
        uri: Uri,
        suggested: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig =
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig.Default,
    ) {
        cancelSession()
        android.util.Log.i(
            "RawPipelineCoord",
            "requestWorkspaceChoice suggested=$suggested",
        )
        _uiState.value =
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
                .AwaitingWorkspaceChoice(uri, suggested)
    }

    /** Discard the pending workspace choice and return to [RawPipelineState.Idle]. */
    fun cancelWorkspaceChoice() {
        if (_uiState.value is com.RAZStudio.StudioRoom.feature.photo_editor.raw.model
                .RawPipelineState.AwaitingWorkspaceChoice
        ) {
            _uiState.value =
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState.Idle
        }
    }

    fun openRawFile(
        uri: Uri,
        scope: CoroutineScope,
        macro: UserMacro = UserMacro(),
        config: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig =
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig.Default,
    ) {
        // Step 1: the workspace config arrives here pre-confirmed (the UI surfaces the
        // dialog before this call). Only the demosaic axis is consumed in Step 1 —
        // bit-depth and color-gamut land in Steps 2/3. The full config is stashed
        // so later steps can read it without changing the signature again.
        currentWorkspaceConfig = config
        val demosaicAlgorithm = config.demosaicAlgorithm
        android.util.Log.i(
            "RawPipelineCoord",
            "openRawFile config=$config (bitDepth=${config.bitDepth.name} gamut=${config.colorGamut.name} demosaic=${config.demosaicAlgorithm.name})",
        )

        cancelSession()
        currentMacro = macro
        fullResOutputPath = null
        currentSourceUri = uri

        val session = CoroutineScope(scope.coroutineContext + SupervisorJob())
        sessionScope = session

        // Detect non-RAW sources (JPEG/PNG/WebP/BMP/TIFF). These bypass LibRaw
        // entirely and run through BitmapDirectPipeline, which decodes via
        // BitmapFactory + applies EXIF orientation and emits PreviewReady /
        // FullResReady directly. The downstream editor (MacroProcessor, masks,
        // export) works unchanged because it only consumes the preview bitmap
        // + full-res PNG path.
        val isNonRaw = BitmapDirectPipeline.isNonRawSource(context, uri)
        android.util.Log.i("RawPipelineCoord", "openRawFile: isNonRaw=$isNonRaw uri=$uri")
        _isNonRawSource.value = isNonRaw

        // ── v2 §6 / §B.4 — EXIF probe + sidecar load ────────────────────────
        //
        // Runs on previewDispatcher in parallel with the pipeline cold-start. The probe is
        // cheap (~50 ms on the supported-floor device) so the editor canvas usually has
        // the EXIF + restored macro before the first PreviewReady emit.
        //
        // For non-RAW sources we skip the LibRaw EXIF probe — BitmapDirectPipeline reads
        // the EXIF block via ExifInterface itself and surfaces it through a different
        // path. The sidecar reader still runs because edits should round-trip through any
        // source format the user opens.
        //
        // The restored macro overrides the `macro` parameter passed to this call. That's
        // the right priority order for "user opens a file again" because the sidecar is
        // the user's last-known state, while the parameter is typically `UserMacro()` from
        // the new-file path or whatever the caller had stashed in their ViewModel.
        _exif.value = null
        session.launch(previewDispatcher) {
            if (!isNonRaw) {
                val path = resolveFilePath(uri)
                if (path != null) {
                    runCatching { NativeRawDecoderV2.readExif(path) }
                        .onSuccess { exif ->
                            if (exif != null) {
                                _exif.value = exif
                                android.util.Log.i(
                                    "RawPipelineCoord",
                                    "openRawFile: EXIF probe ok " +
                                        "make=${exif.make} model=${exif.model} " +
                                        "iso=${exif.iso} shutter=${exif.shutterDisplay}",
                                )
                            } else {
                                android.util.Log.i("RawPipelineCoord",
                                    "openRawFile: EXIF probe returned null — degrading info sheet")
                            }
                        }
                        .onFailure { t ->
                            android.util.Log.w("RawPipelineCoord",
                                "openRawFile: EXIF probe threw — degrading info sheet", t)
                        }
                }
            }
            if (config.sidecarEnabled) {
                val snapshot = runCatching { sidecarStore.load(uri) }.getOrNull()
                if (snapshot != null) {
                    android.util.Log.i(
                        "RawPipelineCoord",
                        "openRawFile: restored sidecar (${snapshot.revisions.size} revisions, " +
                            "lastEdited=${snapshot.lastEditedEpochMs})",
                    )
                    // Restore the saved macro; the in-flight render loop picks it up via
                    // macroFlow. The workspace config from the sidecar is informational —
                    // the caller's `config` is what governs the active pipeline because
                    // it already passed the workspace selector.
                    currentMacro = snapshot.macro
                    macroFlow.value = snapshot.macro
                }
            }
        }

        // Resolve file path once (ContentResolver copy if needed) and share between pipelines
        val preview = PreviewPipeline(context, cache, detector)
        val fullRes = FullResPipeline(
            context, cache, previewReadySignal,
            freeCanvasMemory = { freeMemoryForFullRes() },
        )
        val bitmapDirect = if (isNonRaw)
            BitmapDirectPipeline(context, cache, previewReadySignal)
        else null

        // Capture the session epoch at job-start so cross-session bleed checks
        // can compare against [sessionEpoch] which gets bumped on cancelSession.
        val mySessionEpoch = sessionEpoch

        // Relay preview state → UI; capture neutral bitmap on first PreviewReady.
        // Runs on previewDispatcher so the collector and downstream segmentation
        // launch share an isolated thread, never competing with the slider-driven
        // render loop on renderDispatcher.
        previewJob = session.launch(previewDispatcher) {
            preview.state.collectLatest { state ->
                // Drop if this session was cancelled — a stale PreviewReady
                // arriving after the user switched files would otherwise stamp
                // the new session's UI with the old file's bitmap.
                if (mySessionEpoch != sessionEpoch) return@collectLatest
                _uiState.value = state
                if (state is RawPipelineState.PreviewReady) {
                    val caps = detector.capabilities
                    zoomSourceBitmap   = state.previewBitmap
                    currentOrientation = state.metadata.orientation
                    val screenFit = scaleToScreenFit(
                        state.previewBitmap, caps.screenWidth, caps.screenHeight
                    )
                    neutralBitmap      = screenFit
                    // Pre-bake the 8-bit slider source when Stage C produced an FP16
                    // bitmap (BIT_16 workspace). One-time ARGB_8888 copy cost (Android
                    // does the linear→sRGB gamma here) saves us the same cost on every
                    // slider tick. For the 8-bit workspace this remains null and the
                    // re-render loop just uses [neutralBitmap] directly.
                    neutralBitmap8Bit = if (screenFit.config == Bitmap.Config.RGBA_F16) {
                        runCatching { screenFit.copy(Bitmap.Config.ARGB_8888, false) }
                            .getOrNull()
                    } else null
                    latestPreviewReady = state.copy(previewBitmap = screenFit)
                    previewReadySignal.emit(state)

                    // Start U2Net segmentation in background once the preview bitmap is ready.
                    // Cancels any previous segmentation (e.g. if the file changed mid-run).
                    val previewBitmap = state.previewBitmap
                    val sha           = state.metadata.fileSha256
                    // Pin this SHA so the LRU sweep doesn't evict our cache dirs
                    // mid-edit. cancelSession unpins on session teardown.
                    cache.pin(sha)
                    segmentationJob?.cancel()
                    segmentationJob = session.launch(RawSegmentationProcessor.dispatcher) {
                        runSegmentation(previewBitmap, sha)
                    }
                }
            }
        }

        // Macro+mask re-render loop: debounce rapid changes, then apply MacroProcessor.
        // Was 120 ms — that's the minimum perceived slider lag floor (user releases slider →
        // wait 120 ms → start rendering → ~200 ms render → frame appears 320 ms later).
        // 40 ms is short enough to feel responsive, long enough to coalesce 60 Hz tick storms
        // (a fast drag produces ~16 ms ticks, this groups them into batches of ~3).
        // Runs on renderDispatcher (2-thread pool at NORM_PRIORITY+1) so slider
        // ticks aren't queued behind preview decode / segmentation work that
        // shares Dispatchers.Default.
        macroFlow.value = macro
        session.launch(renderDispatcher) {
            // Combine includes `_maskLayers` so adding/deleting/eye-toggling
            // mask actions invalidates the cache and triggers a fresh render.
            // `_segmentationMasks` is included so subject-targeted vignette
            // and gradient stages start applying as soon as U2Net finishes —
            // without the user nudging another slider to trigger a re-render.
            combine(
                macroFlow,
                _maskBitmap,
                _maskLayers,
                _segmentationMasks,
            ) { m, mask, _, _ -> m to mask }
                .debounce(40L)
                .collectLatest { (m, mask) ->
                    // Snapshot atomically — latestPreviewReady always contains the screen-fit
                    // bitmap, so reading neutral from it avoids a race with neutralBitmap assignment.
                    //
                    // Note: we hand the FP16 bitmap directly to `MacroProcessor.apply` so the
                    // float-domain Group A pipeline can run on it without a round-trip. Stages
                    // not yet ported to float (Turn 1: spatial / vignette / gradient / mask)
                    // fall back inside `applyFloat` to the 8-bit bridge as needed; we don't
                    // double-bridge at this level.
                    val ready   = latestPreviewReady ?: return@collectLatest
                    val neutral = ready.previewBitmap.takeIf { !it.isRecycled } ?: neutralBitmap ?: return@collectLatest
                    val masks = _segmentationMasks.value
                    // Guard the entire render block so a single bad tick doesn't
                    // poison the StateFlow with a half-built bitmap that confuses
                    // the Compose canvas's recomposer. Common triggers: bitmap
                    // recycled mid-render due to session cancel, OOM during
                    // FP16↔8 conversion, NaN propagation. The catch silences the
                    // tick and lets the next macroFlow value retry from neutral.
                    try {

                    // Pre-LUT stage: every macro field EXCEPT the LUT chain (lut*, contrastBoost)
                    // and the alpha-format background. Cache by this normalized key so that when
                    // the user drags a LUT slider only the LUT chain re-runs.
                    val preLutKey = m.copy(
                        lut1CubeUri = "", lut1Intensity = 1f,
                        lut2CubeUri = "", lut2Intensity = 1f,
                        lutCubeUri = "", lutIntensity = 1f,
                        lutEdited = false,
                        contrastBoost = 0f,
                    )
                    val preLut = synchronized(preLutCacheLock) {
                        val cached = cachedPreLutBitmap
                        if (cached != null && !cached.isRecycled &&
                            cachedPreLutKey == preLutKey &&
                            cachedPreLutMaskBitmap === mask &&
                            cachedPreLutMasks === masks &&
                            cachedPreLutSource === neutral
                        ) cached else null
                    }

                    val effectiveM = applyRazamazeDefaults(m)
                    var adjusted = preLut ?: run {
                        val computed = MacroProcessor.apply(neutral, effectiveM, masks, mask)
                        synchronized(preLutCacheLock) {
                            // Deferred recycle (see retiredPreLutBitmap): free the
                            // TWO-generations-old bitmap, park the evicted one.
                            retiredPreLutBitmap?.takeIf {
                                it !== computed && it !== neutral && !it.isRecycled &&
                                    it !== cachedPreLutBitmap
                            }?.recycle()
                            retiredPreLutBitmap = cachedPreLutBitmap
                                ?.takeIf { it !== computed && it !== neutral }
                            cachedPreLutBitmap = computed
                            cachedPreLutKey = preLutKey
                            cachedPreLutMaskBitmap = mask
                            cachedPreLutMasks = masks
                            cachedPreLutSource = neutral
                        }
                        computed
                    }

                    // LUT chain operates on the pre-LUT result. applyLutChain never recycles
                    // its input, so the cache stays valid across ticks. When the chain is a
                    // pass-through (no LUTs and no contrast boost) we must copy the cache
                    // bitmap before emitting — otherwise a subsequent invalidation that
                    // recycles the cache would yank the bitmap out from under the UI.
                    val hasLutWork = m.lutCubeUri.isNotEmpty() || m.lutStack.isNotEmpty() || m.contrastBoost > 0f
                    adjusted = if (hasLutWork) {
                        applyLutChain(adjusted, m)
                    } else if (adjusted === preLut || adjusted === cachedPreLutBitmap) {
                        adjusted.copy(adjusted.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                    } else {
                        adjusted
                    }
                    // Per-action mask layers stack on top of the baseline macro
                    // + LUT chain. Each layer reads its mask PNG and applies the
                    // per-layer mask* fields gated by that mask.
                    adjusted = applyMaskLayersSequentially(adjusted)
                    if (m.outputColorSpace != RawColorSpace.SRGB) {
                        adjusted = WideGamutConverter.convertBitmapToColorSpace(adjusted, m.outputColorSpace)
                    }
                    // Publish checkpoint: collectLatest cancellation is
                    // cooperative and the stages above (LUT chain, gamut
                    // convert) have no checkpoints — a cancelled tick could
                    // reach here AFTER a newer tick already published, stomping
                    // the canvas with the OLDER macro's pixels. ensureActive()
                    // throws for the cancelled tick (rethrown below), so only
                    // the live tick may publish.
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    _uiState.value = ready.copy(previewBitmap = adjusted, userMacro = m)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e   // honour structured concurrency
                    } catch (e: Throwable) {
                        android.util.Log.e(
                            "RawPipelineCoord",
                            "slider re-render tick FAILED: ${e::class.simpleName}: ${e.message}",
                            e,
                        )
                        // Do NOT update _uiState — leave whatever was last good.
                    }
                }
        }

        if (bitmapDirect != null) {
            // Non-RAW source: single dispatcher, no LibRaw, no FullResPipeline.
            val resolvedPath = resolveFilePath(uri)
            if (resolvedPath == null) {
                _uiState.value = RawPipelineState.Error("Cannot resolve file path", null)
                return
            }
            currentFilePath = resolvedPath

            // Relay BitmapDirectPipeline's preview state to the UI.
            previewJob = session.launch(previewDispatcher) {
                bitmapDirect.previewState.collectLatest { state ->
                    if (mySessionEpoch != sessionEpoch) return@collectLatest
                    _uiState.value = state
                    if (state is RawPipelineState.PreviewReady) {
                        val caps = detector.capabilities
                        zoomSourceBitmap   = state.previewBitmap
                        currentOrientation = state.metadata.orientation
                        val screenFit = scaleToScreenFit(
                            state.previewBitmap, caps.screenWidth, caps.screenHeight
                        )
                        neutralBitmap     = screenFit
                        neutralBitmap8Bit = null
                        latestPreviewReady = state.copy(previewBitmap = screenFit)
                        previewReadySignal.emit(state)

                        val previewBitmap = state.previewBitmap
                        val sha           = state.metadata.fileSha256
                        cache.pin(sha)
                        segmentationJob?.cancel()
                        segmentationJob = session.launch(RawSegmentationProcessor.dispatcher) {
                            runSegmentation(previewBitmap, sha)
                        }
                    }
                }
            }

            // Relay full-res events.
            session.launch {
                bitmapDirect.fullResEvents.collectLatest { event ->
                    if (mySessionEpoch != sessionEpoch) return@collectLatest
                    when (event) {
                        is RawPipelineState.FullResReady -> {
                            android.util.Log.d("FullResCoord", "FullResReady (non-RAW): path=${event.outputPath}")
                            fullResOutputPath = event.outputPath
                            _fullResOutputPathFlow.value = event.outputPath
                            _isFullResProcessing.value = false
                            _isFullResReady.value = true
                        }
                        else -> {}
                    }
                }
            }

            // Kick off the decode.
            session.launch(previewDispatcher) {
                bitmapDirect.run(uri, resolvedPath, macro, config)
            }
            return
        }

        // Run preview pipeline on its dedicated dispatcher so the JNI decode
        // (~340 ms readRawPreviewDimensions + several seconds for LibRaw) doesn't
        // block any Default-pool worker from picking up other work.
        session.launch(previewDispatcher) {
            preview.run(uri, macro, config)
        }

        // Run full-res pipeline — waits internally for previewReadySignal
        val resolvedPath = resolveFilePath(uri)
        if (resolvedPath != null) {
            currentFilePath = resolvedPath
            fullResJob = session.launch(fullResDispatcher) {
                fullRes.run(resolvedPath, demosaicAlgorithm, config)
            }
            // Relay full-res events to observable StateFlows
            session.launch {
                fullRes.events.collectLatest { event ->
                    if (mySessionEpoch != sessionEpoch) return@collectLatest
                    when (event) {
                        is RawPipelineState.FullResProcessing -> _isFullResProcessing.value = true
                        is RawPipelineState.FullResReady -> {
                            android.util.Log.d("FullResCoord", "FullResReady: path=${event.outputPath}")
                            fullResOutputPath = event.outputPath
                            _fullResOutputPathFlow.value = event.outputPath
                            _isFullResProcessing.value = false
                            _isFullResReady.value = true
                        }
                        else -> {}
                    }
                }
            }
            // Embedded-JPEG fallback relay — watchdog inside FullResPipeline
            // emits non-null when the decode hangs past its budget; null again
            // when it finishes (or errors). The UI binds [embeddedFallbackBitmap]
            // to overlay a banner + the embedded preview.
            session.launch {
                fullRes.embeddedFallback.collectLatest { bmp ->
                    _embeddedFallbackBitmap.value = bmp
                }
            }
        }
    }

    // When true, macro changes are tracked but not pushed to the re-render loop.
    // Used when Tone Curves tab is active (the tone curve graph has its own rendering).
    @Volatile private var renderingPaused = false

    fun pauseRendering() { renderingPaused = true }

    fun resumeRendering() {
        renderingPaused = false
        // Flush the latest macro to immediately re-render with any changes made while paused.
        macroFlow.value = currentMacro
    }

    /**
     * Update the user macro. If preview is ready the macro re-render loop will pick up
     * the change (debounced 120 ms) and emit a new [RawPipelineState.PreviewReady] with
     * the adjusted bitmap. The full-res pipeline snapshotted the macro at launch.
     */
    fun updateMacro(macro: UserMacro) {
        currentMacro = macro
        if (!renderingPaused) macroFlow.value = macro
        // v2-integration §B.5 — persist edits to the XMP sidecar. SidecarStore.save is
        // debounced 500 ms internally, coalesced per source URI, so calling it on every
        // slider tick is safe — disk I/O only fires after the user stops moving the
        // slider. Skipped when no file is open or when the workspace has sidecar off.
        val uri = currentSourceUri ?: return
        val ws = currentWorkspaceConfig
        if (ws.sidecarEnabled) sidecarStore.save(uri, ws, macro)
    }

    /**
     * Replace the brush mask bitmap used for local adjustments.
     * Pass null to remove the mask; the next preview render will skip the mask stage.
     */
    fun updateMask(bitmap: Bitmap?) {
        _maskBitmap.value = bitmap
    }

    /**
     * Start (or restart) a low-priority full-resolution compare render for [macro].
     *
     * Cancels any in-flight render immediately before starting.
     * Emits [CompareRenderState.Rendering] → [CompareRenderState.Ready] on the
     * [compareState] flow. Both output bitmaps are scaled to screen-fit to avoid
     * holding a 60 MB+ full-res bitmap in memory.
     *
     * Runs on [compareDispatcher] (single MIN_PRIORITY thread) so it never
     * competes with the UI or the main pipeline.
     */
    fun renderForCompare(macro: UserMacro) {
        compareJob?.cancel()
        // Generation for THIS render — see compareGen. Taken before launch so a
        // newer call invalidates this job even if its body hasn't started yet.
        val myGen = compareGen.incrementAndGet()
        _compareState.value = CompareRenderState.Rendering
        compareJob = kotlinx.coroutines.CoroutineScope(compareDispatcher + SupervisorJob()).launch {
            runCatching {
                val basePath = fullResOutputPath
                    ?: error("Full-res output not ready")
                val baseFile = java.io.File(basePath)
                if (!baseFile.exists()) error("Full-res file missing: $basePath")

                val caps = detector.capabilities

                // ── Original side: decode full-res base PNG, scale to screen fit ──
                val originalFull = android.graphics.BitmapFactory.decodeFile(basePath)
                    ?: error("Cannot decode full-res base PNG")
                val originalScaled = scaleToScreenFit(originalFull, caps.screenWidth, caps.screenHeight)
                if (originalScaled !== originalFull) originalFull.recycle()

                // ── Rendered side: apply macro stack + LUT + orientation ──────────
                // Scale FIRST, then apply MacroProcessor. Running the macro on
                // the full-res ARGB_8888 (~80MB at 20MP) cascades into the
                // 242MB FloatArray spatial path → OOM → 8-bit bridge → bitmap
                // tagged with the wrong ColorSpace (LINEAR_EXTENDED_SRGB instead
                // of the workspace gamut) → canvas flickers between gamuts mid-
                // adjustment, then "settles" when IdleFullRes reloads the FP16
                // cache. Verified adb 2026-05-25 (OOM at MacroProcessor.kt:1369
                // during a 20MP Compare render). Same fix the IdleFullRes paths
                // already use at line 1106 (BIT_16) and 1166 (BIT_8).
                var renderedRaw = android.graphics.BitmapFactory.decodeFile(basePath)
                    ?: error("Cannot decode full-res base PNG")
                var rendered = scaleToScreenFit(renderedRaw, caps.screenWidth, caps.screenHeight)
                if (rendered !== renderedRaw) renderedRaw.recycle()
                val effMacroCompare = applyRazamazeDefaults(macro)
                rendered = MacroProcessor.apply(rendered, effMacroCompare, _segmentationMasks.value, _maskBitmap.value)
                rendered = applyLutChain(rendered, effMacroCompare)
                rendered = applyMaskLayersSequentially(rendered)
                rendered = applyExifOrientation(rendered, currentOrientation)
                if (macro.outputColorSpace != RawColorSpace.SRGB) {
                    rendered = WideGamutConverter.convertBitmapToColorSpace(rendered, macro.outputColorSpace)
                }
                // After-orientation scale is a no-op when the pre-macro scale
                // already fit it — kept as a safety net.
                val renderedScaled = scaleToScreenFit(rendered, caps.screenWidth, caps.screenHeight)
                if (renderedScaled !== rendered) rendered.recycle()

                // Recycle whatever the StateFlow was holding before stomping
                // it. Without this, every compare cycle leaks two large
                // bitmaps (original + rendered, screen-fit, ~16MB each on
                // mid-range). Subscribers (Compose canvas) hold the
                // reference for the current frame; by the time we overwrite
                // on the next render they've moved on.
                // Stale-job guard: only the LATEST generation may recycle the
                // previous state or publish. A cancelled job that ran through
                // the un-checkpointed LUT/gamut stages would otherwise recycle
                // the newer job's bitmaps (canvas crash) and stomp newer pixels.
                if (compareGen.get() != myGen) {
                    originalScaled.recycle()
                    renderedScaled.recycle()
                    return@runCatching
                }
                (_compareState.value as? CompareRenderState.Ready)?.let { prev ->
                    if (!prev.original.isRecycled) prev.original.recycle()
                    if (!prev.rendered.isRecycled) prev.rendered.recycle()
                }
                _compareState.value = CompareRenderState.Ready(originalScaled, renderedScaled)
            }.onFailure { e ->
                if (compareGen.get() == myGen) {
                    _compareState.value = CompareRenderState.Error(e.message ?: "Compare render failed")
                }
            }
        }
    }

    /** Cancel any in-flight compare render and reset state to Idle. */
    fun cancelCompareRender() {
        compareJob?.cancel()
        compareJob = null
        // Invalidate the running job's generation so it can neither recycle the
        // state we reset below nor re-publish after this cancel.
        compareGen.incrementAndGet()
        (_compareState.value as? CompareRenderState.Ready)?.let { prev ->
            if (!prev.original.isRecycled) prev.original.recycle()
            if (!prev.rendered.isRecycled) prev.rendered.recycle()
        }
        _compareState.value = CompareRenderState.Idle
    }

    /**
     * Render the current macro applied to the full-res base on [compareDispatcher]
     * (MIN_PRIORITY). When complete, emits to [idleFullResBitmap] so the canvas can
     * swap the preview for a sharper image while the user is idle.
     *
     * Cancels any prior idle render. Should only be called after a 1-second idle delay
     * in the UI layer, not on every macro change.
     */
    fun renderIdleFullRes(macro: UserMacro) {
        idleFullResJob?.cancel()
        // Stale-job guard (see idleFullResGen): cancel() is cooperative and the
        // body hops to Dispatchers.Default, so an older job can outlive this
        // call and reach its publish site after a newer job already published.
        val myGen = idleFullResGen.incrementAndGet()
        idleFullResJob = kotlinx.coroutines.CoroutineScope(compareDispatcher + SupervisorJob()).launch {
            runCatching {
                val basePath = fullResOutputPath
                if (basePath == null) return@runCatching
                val file = java.io.File(basePath)
                if (!file.exists()) return@runCatching
                val caps = detector.capabilities

                // ── BIT_16 idle path ──────────────────────────────────────────
                // When the workspace is 16-bit, the 8-bit `workspace_full_base.png`
                // on disk lost both bit depth and gamut. Swapping that onto the
                // canvas darkens the image and narrows it to sRGB. Read the
                // FP16 cache that FullResPipeline persists for BIT_16 sessions —
                // raw RGBA half-float bytes plus a sidecar gamut name.
                val cfg = currentWorkspaceConfig
                if (cfg.bitDepth == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth.BIT_16) {
                    val sha = latestPreviewReady?.metadata?.fileSha256
                    val fp16File = sha?.let { cache.stageFile(it, RawStageCache.Stage.C_FULLRES, "workspace_full_base.fp16") }
                    val dimsText = sha?.let { cache.readStageText(it, RawStageCache.Stage.A_FULLRES, "dims.bin") }
                    val gamutText = sha?.let { cache.readStageText(it, RawStageCache.Stage.C_FULLRES, "workspace_full_base.gamut") }
                    if (fp16File != null && fp16File.exists() && dimsText != null) {
                        val parts = dimsText.split(",")
                        val fullW = parts.getOrNull(0)?.trim()?.toIntOrNull()
                        val fullH = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        if (fullW != null && fullH != null) {
                            android.util.Log.i(
                                "IdleFullRes",
                                "BIT_16 path: loading FP16 cache ${fullW}x$fullH gamut=$gamutText",
                            )
                            // Resolve the gamut for tagging.
                            val gamut = runCatching {
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.valueOf(
                                    gamutText?.trim() ?: "SRGB",
                                )
                            }.getOrDefault(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.SRGB)
                            // CRITICAL: the persisted bytes are LINEAR-light (FP16 written
                            // by WideGamutConverter.build16BitBitmap). Tag with the same
                            // linear-transfer color spaces used at write time — never the
                            // gamma-encoded Named.DISPLAY_P3, which would force Android
                            // to inverse-sRGB-decode our already-linear values and crush
                            // midtones to near-black on the canvas swap.
                            val colorSpace = when (gamut) {
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.SRGB         -> android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB)
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.DISPLAY_P3   -> WideGamutConverter.linearDisplayP3Public()
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.PROPHOTO_RGB -> WideGamutConverter.linearProPhotoPublic()
                            }
                            // Memory-map the FP16 bytes and load into an RGBA_F16 bitmap.
                            val mapped = java.io.RandomAccessFile(fp16File, "r").use { raf ->
                                raf.channel.map(
                                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                                    0, fp16File.length(),
                                )
                            }.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val fp16Bmp = android.graphics.Bitmap.createBitmap(
                                fullW, fullH, android.graphics.Bitmap.Config.RGBA_F16, true, colorSpace,
                            )
                            fp16Bmp.copyPixelsFromBuffer(mapped)
                            // ── OOM-prevention: scale to screen-fit BEFORE macro ──────
                            // The canvas only ever displays a screen-fit-sized bitmap.
                            // Running MacroProcessor on the full 20MP F16 buffer
                            // allocates 242MB JVM heap for the working FloatArray and
                            // OOMs on the supported-floor 512MB cap (verified in
                            // adb trace 2026-05-25). Scale first, process second,
                            // skip the upscale — IdleFullRes is a *quality* upgrade
                            // for the canvas swap, not a full-res render. The save
                            // path uses a different route at renderAndExport time.
                            var bmp: android.graphics.Bitmap = applyExifOrientation(fp16Bmp, currentOrientation)
                            val preScaled = scaleToScreenFit(bmp, caps.screenWidth, caps.screenHeight)
                            if (preScaled !== bmp) {
                                bmp.recycle()
                                bmp = preScaled
                            }
                            val effMacroIdle = applyRazamazeDefaults(macro)
                            bmp = MacroProcessor.apply(bmp, effMacroIdle, _segmentationMasks.value, _maskBitmap.value)
                            bmp = applyLutChain(bmp, effMacroIdle)
                            bmp = applyMaskLayersSequentially(bmp)
                            // No forceWorkspaceGamut here: applyExifOrientation
                            // and scaleToScreenFit now preserve the source
                            // ColorSpace tag end-to-end, so the bitmap arrives
                            // at the canvas correctly tagged as linear DCI-P3 /
                            // linear sRGB / linear ProPhoto. Re-tagging via
                            // Canvas.drawBitmap (what forceWorkspaceGamut did)
                            // would only color-manage and de-saturate the
                            // already-correct pixels.
                            // Stale-job guard: only the latest generation may
                            // recycle the previous idle bitmap or publish.
                            if (idleFullResGen.get() != myGen) {
                                bmp.recycle()
                                return@runCatching
                            }
                            // Recycle the previous idle bitmap before stomp.
                            _idleFullResBitmap.value?.takeIf { it !== bmp && !it.isRecycled }
                                ?.recycle()
                            _idleFullResBitmap.value = bmp
                            return@runCatching
                        }
                    }
                    // FP16 cache missing — fall through to 8-bit path so the
                    // canvas still upgrades, accepting the gamut loss for this tick.
                    android.util.Log.w(
                        "IdleFullRes",
                        "BIT_16 requested but FP16 cache missing; falling back to 8-bit path",
                    )
                }

                // Reuse the cached decoded bitmap when the source file hasn't changed.
                // MacroProcessor and the LUT chain may mutate or replace the bitmap, so
                // we always work on a copy and leave the cached original intact.
                val mtime = file.lastModified()
                // COPY INSIDE the monitor. Previously `base` (the cached bitmap)
                // escaped the synchronized block and was copied at the call site;
                // an overlapping idle job entering this block with a changed
                // mtime would `existing?.recycle()` the very bitmap the first
                // job was about to copy → "Can't copy a recycled bitmap". The
                // copy is now made while the lock forbids that recycle. Holding
                // the monitor for a full-res copy is acceptable on this
                // MIN_PRIORITY idle path.
                val baseCopy = synchronized(this@RawPipelineCoordinator) {
                    val existing = cachedFullResBase
                    val src = if (existing != null && !existing.isRecycled &&
                        cachedFullResPath == basePath && cachedFullResMtime == mtime
                    ) {
                        existing
                    } else {
                        existing?.recycle()
                        val decoded = android.graphics.BitmapFactory.decodeFile(basePath)
                            ?: return@synchronized null
                        cachedFullResBase = decoded
                        cachedFullResPath = basePath
                        cachedFullResMtime = mtime
                        decoded
                    }
                    src.copy(src.config ?: android.graphics.Bitmap.Config.ARGB_8888, true)
                } ?: run {
                    android.util.Log.e("IdleFullRes", "decodeFile returned null for $basePath")
                    return@runCatching
                }

                // workspace_full_base.png is written by FullResPipeline WITHOUT rotation
                // (raw Stage C output, sensor-native landscape coords). We must apply the
                // EXIF orientation here, mirroring the BIT_16 path at line 948. Without
                // this, portrait-tagged photos display as sideways landscape on the 8-bit
                // idle-full-res canvas upgrade (verified on device 2026-05-24 with Canon
                // EOS 6D IMG_2743.CR2 in BIT_8 + DISPLAY_P3 workspace).
                var bmp: android.graphics.Bitmap = baseCopy
                bmp = applyExifOrientation(bmp, currentOrientation)
                // OOM-prevention: scale to screen-fit BEFORE macro (mirror of the
                // BIT_16 branch). 8-bit allocations are smaller (~80MB at 20MP)
                // but on a fragmented heap the same OOM cascade can still trip
                // here. Always pre-scale.
                val preScaled = scaleToScreenFit(bmp, caps.screenWidth, caps.screenHeight)
                if (preScaled !== bmp) {
                    bmp.recycle()
                    bmp = preScaled
                }
                val effMacro = applyRazamazeDefaults(macro)
                bmp = MacroProcessor.apply(bmp, effMacro, _segmentationMasks.value, _maskBitmap.value)
                bmp = applyLutChain(bmp, effMacro)
                bmp = applyMaskLayersSequentially(bmp)
                if (macro.outputColorSpace != RawColorSpace.SRGB) {
                    bmp = WideGamutConverter.convertBitmapToColorSpace(bmp, macro.outputColorSpace)
                }
                // Safety net — see forceWorkspaceGamut docstring + the matching
                // call in the BIT_16 idle path above.
                bmp = forceWorkspaceGamut(bmp)
                // Stale-job guard — mirror of the BIT_16 publish above.
                if (idleFullResGen.get() != myGen) {
                    bmp.recycle()
                    return@runCatching
                }
                _idleFullResBitmap.value?.takeIf { it !== bmp && !it.isRecycled }
                    ?.recycle()
                _idleFullResBitmap.value = bmp
            }.onFailure { e ->
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("IdleFullRes", "FAILED: ${e::class.simpleName}: ${e.message}", e)
                }
            }
        }
    }

    /** Release the cached full-res base bitmap. Call when switching photos. */
    @Synchronized
    fun clearFullResCache() {
        cachedFullResBase?.recycle()
        cachedFullResBase = null
        cachedFullResPath = null
        cachedFullResMtime = -1L
    }

    /** Release the pre-LUT cached bitmap. Called on session cancel. */
    private fun clearPreLutCache() {
        synchronized(preLutCacheLock) {
            cachedPreLutBitmap?.takeIf { !it.isRecycled }?.recycle()
            cachedPreLutBitmap = null
            cachedPreLutKey = null
            cachedPreLutMaskBitmap = null
            cachedPreLutMasks = null
            cachedPreLutSource = null
        }
    }

    /** Cancel any in-flight idle render and clear the cached bitmap immediately. */
    fun clearIdleFullRes() {
        idleFullResJob?.cancel()
        idleFullResJob = null
        _idleFullResBitmap.value?.takeIf { !it.isRecycled }?.recycle()
        _idleFullResBitmap.value = null
    }

    /**
     * Aggressively release every long-lived bitmap this coordinator holds, then run
     * a paired GC + finalization pass. Called by [RawBatchProcessor] before each
     * file so the next LibRaw decode (~150 MB heap allocation for the FP16 pixel
     * ByteArray) doesn't OOM on devices with a 512 MB heap cap.
     *
     * Safe to call between files — every consumer re-fetches its bitmap from
     * Stage C output anyway.
     */
    fun freeMemoryForBatch() {
        neutralBitmap?.takeIf { !it.isRecycled }?.recycle()
        neutralBitmap = null
        neutralBitmap8Bit?.takeIf { !it.isRecycled }?.recycle()
        neutralBitmap8Bit = null
        zoomSourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        zoomSourceBitmap = null
        _maskBitmap.value?.takeIf { !it.isRecycled }?.recycle()
        _maskBitmap.value = null
        clearFullResCache()
        clearPreLutCache()
        clearIdleFullRes()
        gcAndLog("freeMemoryForBatch")
    }

    /**
     * Soft cleanup used during full-res Save when the canvas is still live.
     * Drops *quality-upgrade* and *intermediate* caches that the UI can survive
     * losing, but preserves the bitmaps the canvas is actively rendering. After
     * this call the canvas falls back to [neutralBitmap] / [neutralBitmap8Bit]
     * for the slider re-render loop — no visible glitch, just a one-frame skip
     * of the idle-full-res quality upgrade.
     *
     * Targets ~80–120 MB of reclaimable heap on a Helio G99 / 4 GB phone — the
     * idle full-res bitmap (~30 MB at 1224×817 RGBA_F16 + cached scratch), the
     * full-res cache from BitmapFactory (~80 MB ARGB_8888 at 5496×3669), and
     * the pre-LUT cache (~30 MB). Total ~140 MB ceiling.
     */
    fun freeMemoryForSave() {
        clearFullResCache()
        clearPreLutCache()
        clearIdleFullRes()
        gcAndLog("freeMemoryForSave")
    }

    /**
     * Cleanup invoked from FullResPipeline.waitForHeapHeadroom when heap is too
     * pressured to allocate the ~161 MB linear buffer for native decode. Stronger
     * than [freeMemoryForSave] but **must not** recycle bitmaps that are still
     * published in StateFlows — Compose holds strong references via BitmapPainter
     * and the next frame's RecordingCanvas.drawBitmap would throw
     * `Canvas: trying to use a recycled bitmap` (observed adb 2026-05-25 crash).
     *
     * Safe reclaim only:
     *   • Stage caches (FullRes/PreLut/IdleFullRes) — these aren't published.
     *   • GPU/EGL processor (~80 MB) — released on its own thread; next acquire
     *     awaits [gpuReleaseJob].
     *
     * Live canvas bitmaps (neutralBitmap, neutralBitmap8Bit, _maskBitmap) are
     * intentionally NOT recycled here. They get reclaimed naturally on file
     * switch via [freeMemoryForBatch].
     */
    fun freeMemoryForFullRes() {
        clearFullResCache()
        clearPreLutCache()
        clearIdleFullRes()

        // Release the EGL/GPU processor — ~80 MB of GL+EGL memory. The upcoming
        // full-res decode doesn't need it (full-res LUT runs CPU-side in stage C).
        val oldGpu = gpuProcessor
        if (oldGpu != null) {
            gpuProcessor = null
            gpuProcessorInitDone = false
            gpuReleaseJob = kotlinx.coroutines.CoroutineScope(
                com.RAZStudio.opencv_tools.gpu.GpuLutProcessor.gpuDispatcher,
            ).launch { try { oldGpu.release() } catch (_: Exception) {} }
        }

        gcAndLog("freeMemoryForFullRes")
    }

    private fun gcAndLog(label: String) {
        val rt = Runtime.getRuntime()
        val before = rt.freeMemory()
        System.gc()
        System.runFinalization()
        System.gc()
        android.util.Log.w(
            "RawPipelineCoordinator",
            "$label: free=${rt.freeMemory() / (1024 * 1024)}MB " +
                "(was ${before / (1024 * 1024)}MB) max=${rt.maxMemory() / (1024 * 1024)}MB",
        )
    }

    /**
     * Re-render the preview with the latest macro without re-decoding stages A/B/C.
     * Useful when the user changes a LUT or slider after the initial load.
     *
     * Returns the re-rendered [android.graphics.Bitmap] or null if preview is not ready.
     */
    suspend fun reRenderPreview(): Bitmap? {
        val ready = (uiState.value as? RawPipelineState.PreviewReady) ?: return null
        val macro = currentMacro
        if (macro.lutStack.isEmpty() && macro.lutCubeUri.isEmpty()) return ready.previewBitmap
        return applyLutChain(ready.previewBitmap, macro)
    }

    /**
     * Crop the [zoomSourceBitmap] viewport visible at the given pan/zoom, apply the current
     * macro, and scale the result to exactly the visible canvas area in screen pixels.
     *
     * This produces a sharp bitmap that fills the canvas 1:1 at any zoom level.
     *
     * @param canvasW  Canvas width in physical pixels (from BoxWithConstraints).
     * @param canvasH  Canvas height in physical pixels.
     * @param scale    Current pinch-zoom scale factor.
     * @param offsetX  Current pan offset in pixels (translationX of graphicsLayer).
     * @param offsetY  Current pan offset in pixels (translationY of graphicsLayer).
     */
    suspend fun renderZoomCrop(
        canvasW: Int, canvasH: Int,
        scale: Float, offsetX: Float, offsetY: Float,
    ): Bitmap? {
        val src     = zoomSourceBitmap ?: return null
        val neutral = neutralBitmap    ?: return null
        val macro   = currentMacro

        val neutralW = neutral.width.toFloat()
        val neutralH = neutral.height.toFloat()

        // How the neutral bitmap is fitted inside the canvas (ContentScale.Fit)
        val fitScale = min(canvasW / neutralW, canvasH / neutralH)
        val dispW    = neutralW * fitScale
        val dispH    = neutralH * fitScale

        // Top-left corner of the scaled+panned image in canvas coordinates
        val imgLeft = canvasW / 2f + offsetX - dispW * scale / 2f
        val imgTop  = canvasH / 2f + offsetY - dispH * scale / 2f

        // Visible area clipped to canvas bounds
        val visLeft   = max(0f, imgLeft)
        val visTop    = max(0f, imgTop)
        val visRight  = min(canvasW.toFloat(), imgLeft + dispW * scale)
        val visBottom = min(canvasH.toFloat(), imgTop  + dispH * scale)

        if (visRight <= visLeft || visBottom <= visTop) return null

        // Map visible canvas pixels back to source bitmap coordinates
        val zoomRatio = src.width.toFloat() / neutralW
        val cropX = ((visLeft   - imgLeft) / scale * zoomRatio).roundToInt().coerceAtLeast(0)
        val cropY = ((visTop    - imgTop ) / scale * zoomRatio).roundToInt().coerceAtLeast(0)
        val cropW = ((visRight  - visLeft) / scale * zoomRatio)
            .roundToInt().coerceIn(1, src.width  - cropX)
        val cropH = ((visBottom - visTop ) / scale * zoomRatio)
            .roundToInt().coerceIn(1, src.height - cropY)

        val crop = Bitmap.createBitmap(src, cropX, cropY, cropW, cropH)

        val outW = (visRight  - visLeft).roundToInt().coerceIn(1, canvasW)
        val outH = (visBottom - visTop ).roundToInt().coerceIn(1, canvasH)

        // Apply macro adjustments to the cropped region. Mask layers are applied
        // after the crop; the mask bitmap is bilinearly sampled to the crop size,
        // so the masked region is approximate inside zoom (acceptable for the
        // zoom-pan UX since the live preview still shows correct mask alignment).
        val effMacroZoom = applyRazamazeDefaults(macro)
        val processed = MacroProcessor.apply(crop, effMacroZoom, _segmentationMasks.value, _maskBitmap.value)
        val withLut = applyLutChain(processed, effMacroZoom)
        val withMasks = applyMaskLayersSequentially(withLut)

        return Bitmap.createScaledBitmap(withMasks, outW, outH, true)
    }

    /** Cancel the current session. Safe to call with no active session. */
    fun cancelSession() {
        // Bump the epoch FIRST so any work currently running checks-and-drops
        // before publishing, even if its cancellation hasn't propagated yet
        // (e.g. mid-LibRaw-decode where the native side isn't interruptible).
        sessionEpoch++
        previewJob?.cancel()
        fullResJob?.cancel()
        segmentationJob?.cancel()
        sessionScope?.coroutineContext?.get(Job)?.cancel()
        sessionScope = null
        // Drop any replayed PreviewReady so the next session's FullResPipeline
        // doesn't see the previous session's stale bitmap via startSignal.first().
        previewReadySignal.resetReplayCache()
        // Unpin the active session's cache dir BEFORE we drop latestPreviewReady,
        // otherwise we lose the SHA and the entry stays pinned for the process
        // lifetime.
        latestPreviewReady?.metadata?.fileSha256?.takeIf { it.isNotEmpty() }?.let {
            cache.unpin(it)
        }
        neutralBitmap         = null
        neutralBitmap8Bit?.takeIf { !it.isRecycled }?.recycle()
        neutralBitmap8Bit     = null
        zoomSourceBitmap      = null
        latestPreviewReady    = null
        currentOrientation    = 1
        _uiState.value        = RawPipelineState.Idle
        _isFullResProcessing.value  = false
        _isFullResReady.value       = false
        _isNonRawSource.value       = false
        _fullResOutputPathFlow.value = null
        _segmentationMasks.value    = null
        _maskBitmap.value          = null
        // v2 §6 — clear the EXIF snapshot so the next openRawFile doesn't briefly show
        // the previous file's metadata while its own probe is in flight.
        _exif.value = null
        // Clear the embedded-JPEG fallback bitmap. Without this, the red banner
        // + camera-preview overlay from the previous file's watchdog fire stays
        // visible until the new file's FullResPipeline emits null. Race-prone:
        // a quick file switch leaves the user staring at the wrong preview.
        _embeddedFallbackBitmap.value?.let { stale ->
            if (!stale.isRecycled) stale.recycle()
        }
        _embeddedFallbackBitmap.value = null
        // v2-integration §B — cancel any pending debounced sidecar write for the file
        // we're closing. The most recent macro is already persisted (the previous
        // updateMacro call kicked the debounce timer) so this only drops in-flight
        // duplicates; no edits are lost.
        currentSourceUri?.let { sidecarStore.cancelPending(it) }
        currentSourceUri = null

        cancelCompareRender()
        clearIdleFullRes()
        clearFullResCache()
        clearPreLutCache()
        synchronized(parsedLutCacheLock) { parsedLutCache.clear() }

        // Clear the singleton export bridge so the next session starts clean.
        // Without this, stale full-res dimensions and a captured lambda from the previous
        // coordinator would linger until the user clicks Apply again — and pushNew() would
        // skip navigation entirely because the URI (fixed filename) matches the top of stack.
        PendingRawExport.clear()
        lastNavPreviewFile?.delete()
        lastNavPreviewFile = null

        // Release the EGL context on the GPU thread so it can be reclaimed immediately.
        // Track the release Job — the next acquire awaits it to avoid two EGL
        // contexts trying to coexist (EGL_BAD_ALLOC on fast file switches).
        val oldGpu = gpuProcessor
        gpuProcessor = null
        gpuProcessorInitDone = false
        if (oldGpu != null) {
            gpuReleaseJob = kotlinx.coroutines.CoroutineScope(
                com.RAZStudio.opencv_tools.gpu.GpuLutProcessor.gpuDispatcher
            ).launch { try { oldGpu.release() } catch (_: Exception) {} }
        }
    }

    /** Cancel and release resources. Call in ViewModel.onCleared(). */
    fun close() {
        cancelSession()
        clearFullResCache()
        segmentationProcessor.release()
        compareDispatcher.close()
        fullResDispatcher.close()
        previewDispatcher.close()
        renderDispatcher.close()
    }

    // ── Segmentation ──────────────────────────────────────────────────────────────

    /**
     * Check disk cache first; if no valid entry exists, run U2Net inference and then
     * persist the result. Emits to [segmentationMasks] on success.
     *
     * Runs entirely on [RawSegmentationProcessor.dispatcher] (MIN_PRIORITY, single thread).
     */
    private suspend fun runSegmentation(bitmap: Bitmap, sha256: String) {
        if (sha256.isEmpty()) return

        val cached = RawSegmentationStorage.load(context, sha256)
        if (cached != null) {
            _segmentationMasks.value = cached
            return
        }

        val masks = segmentationProcessor.compute(bitmap) ?: return
        RawSegmentationStorage.save(context, sha256, masks)
        _segmentationMasks.value = masks
    }

    /**
     * Fast path for "Apply to Editor": write the current rendered preview (already in memory)
     * to a temp file and register a deferred full-res render in [PendingRawExport].
     *
     * Navigation happens immediately with the small preview PNG.  The actual full-res
     * [MacroProcessor.apply] runs only when the user presses Save in PhotoEditorComponent,
     * so the transition is instant and no full-res bitmap is held in memory during browsing.
     *
     * Returns the temp preview file path, or null if the preview is not yet ready or
     * the full-res base is unavailable.
     */
    suspend fun prepareExportNavigation(macro: UserMacro): String? = withContext(Dispatchers.Default) {
        if (fullResOutputPath == null) return@withContext null
        val preview = (_uiState.value as? RawPipelineState.PreviewReady)?.previewBitmap
            ?: return@withContext null

        // Inherit the workspace gamut into the macro that drives the full-res
        // render + the nav preview's ICC tag. Without this, the RAW Export
        // page renders the preview as sRGB even when the workspace is DCI-P3,
        // so the user sees a color shift on entry to the export page that
        // doesn't match the canvas. Same logic as applyRazamazeDefaults.
        val effMacro = macro.copy(outputColorSpace = currentWorkspaceConfig.colorGamut)

        // Read full-res dimensions without decoding the whole bitmap (inJustDecodeBounds).
        // The on-disk Stage C PNG is sensor-native (landscape, no orientation
        // baked in). Swap W/H for portrait-tagged shots so the export page
        // shows the actual final-image dimensions, not the sensor's landscape
        // shape. EXIF orientation codes 5–8 = 90°/270° rotation.
        val fullResPath = fullResOutputPath!!
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(fullResPath, opts)
        val rawW = opts.outWidth.takeIf  { it > 0 } ?: preview.width
        val rawH = opts.outHeight.takeIf { it > 0 } ?: preview.height
        val orientation = latestPreviewReady?.metadata?.orientation ?: 1
        val rotated = orientation in 5..8
        val fullW = if (rotated) rawH else rawW
        val fullH = if (rotated) rawW else rawH

        PendingRawExport.set(fullW, fullH, latestPreviewReady?.metadata) { renderAndExport(effMacro) }

        // Use a unique filename per session so every navigation produces a different URI.
        // pushNew() deduplicates by Screen config equality; a fixed name would cause it to
        // silently skip the push and leave the previous photo's component on screen.
        val sessionTag = System.currentTimeMillis()
        lastNavPreviewFile?.delete()
        val outFile = java.io.File(context.cacheDir, "raw_nav_preview_${sessionTag}.png")
        lastNavPreviewFile = outFile
        withContext(Dispatchers.IO) {
            outFile.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 0, it) }
            // Embed RAW EXIF into the navigation preview PNG so PhotoEditorComponent
            // surfaces the real camera metadata in the save/export screen.
            embedExifToFile(outFile.absolutePath)
            // ICC must come after ExifInterface.saveAttributes() which rewrites the PNG
            runCatching {
                val pngWithIcc = IccProfileWriter.embedColorSpace(outFile.readBytes(), effMacro.outputColorSpace)
                outFile.writeBytes(pngWithIcc)
            }
        }
        outFile.absolutePath
    }

    /**
     * Write all available EXIF attributes from [meta] to [exif].
     * [appSoftwareTag] overrides the Software field with the RAZRAW signature.
     * Dimension tags (ImageWidth / ImageLength) are intentionally omitted so
     * the decoder reads them from the actual pixel data.
     * GPS coordinates are converted from decimal degrees to the rational-string
     * format required by ExifInterface.
     */
    private fun embedRawExif(
        exif: ExifInterface,
        meta: RawMetadata,
        appSoftwareTag: String = "",
        keepDateTime: Boolean = true,
        /** When false, GPS-bearing tags (latitude/longitude/altitude/timestamp) are skipped. */
        keepGps: Boolean = true,
    ) {
        fun rational(n: Int, d: Int = 1): String = "$n/$d"
        fun degreesToRational(deg: Double): String {
            val d = deg.toInt()
            val mRaw = (deg - d) * 60.0
            val m = mRaw.toInt()
            val s = ((mRaw - m) * 60.0 * 1000.0).toInt()
            return "${rational(d)},${rational(m)},${rational(s, 1000)}"
        }

        if (meta.cameraMake.isNotEmpty())       exif.setAttribute("Make",                   meta.cameraMake)
        if (meta.cameraModel.isNotEmpty())      exif.setAttribute("Model",                  meta.cameraModel)
        // Software: always use RAZRAW signature when provided, fall back to original camera value
        val softwareTag = appSoftwareTag.ifEmpty { meta.softwareVersion }
        if (softwareTag.isNotEmpty())           exif.setAttribute("Software",               softwareTag)
        if (meta.lensInfo.isNotEmpty())         exif.setAttribute("LensModel",              meta.lensInfo)
        if (meta.lensMake.isNotEmpty())         exif.setAttribute("LensMake",               meta.lensMake)
        if (meta.artist.isNotEmpty())           exif.setAttribute("Artist",                 meta.artist)
        if (meta.copyright.isNotEmpty())        exif.setAttribute("Copyright",              meta.copyright)
        if (meta.imageDescription.isNotEmpty()) exif.setAttribute("ImageDescription",       meta.imageDescription)
        if (keepDateTime && meta.dateTimeOriginal.isNotEmpty()) exif.setAttribute("DateTimeOriginal",  meta.dateTimeOriginal)
        if (keepDateTime && meta.dateTimeDigitized.isNotEmpty()) exif.setAttribute("DateTimeDigitized", meta.dateTimeDigitized)
        if (meta.focalLength > 0f)              exif.setAttribute("FocalLength",            rational((meta.focalLength * 100).toInt(), 100))
        if (meta.focalLength35mm > 0f)          exif.setAttribute("FocalLengthIn35mmFilm",  "${meta.focalLength35mm.toInt()}")
        if (meta.aperture > 0f)                 exif.setAttribute("FNumber",                rational((meta.aperture * 100).toInt(), 100))
        if (meta.iso > 0)                       exif.setAttribute("PhotographicSensitivity", "${meta.iso}")
        if (meta.exposureBias != 0f)            exif.setAttribute("ExposureBiasValue",      rational((meta.exposureBias * 100).toInt(), 100))
        if (meta.shutterSpeed > 0f) {
            val expTime = if (meta.shutterSpeed >= 1f) {
                rational((meta.shutterSpeed * 10).toInt(), 10)
            } else {
                rational(1, (1.0 / meta.shutterSpeed).toInt().coerceAtLeast(1))
            }
            exif.setAttribute("ExposureTime", expTime)
        }
        // CRITICAL: write Orientation=1 (no rotation), NOT the source's orientation tag.
        //
        // The pixel buffer was already physically rotated to match the EXIF orientation
        // back in renderAndExport line 1462 (applyExifOrientation). So the PNG on disk
        // is in display orientation — width and height already swapped if portrait.
        // Writing the source orientation tag (e.g. 8 for portrait CCW) on top of that
        // tells viewers to rotate ANOTHER 90° — net effect: portrait pixels rendered
        // sideways. User reported on device 2026-05-24: "saved file always in landscape"
        // because portrait-source pixels physically rotated to 2735×4104 + EXIF=8 viewer
        // hint = double-rotation = landscape display.
        //
        // The source's orientation is informational only at this point — kept here as
        // a comment for forensic purposes. Output is always "viewed-as" upright.
        exif.setAttribute("Orientation", "1")
        if (keepGps) {
            meta.gpsLatitude?.let { lat ->
                exif.setAttribute("GPSLatitudeRef",  if (lat >= 0) "N" else "S")
                exif.setAttribute("GPSLatitude",      degreesToRational(kotlin.math.abs(lat)))
            }
            meta.gpsLongitude?.let { lon ->
                exif.setAttribute("GPSLongitudeRef", if (lon >= 0) "E" else "W")
                exif.setAttribute("GPSLongitude",     degreesToRational(kotlin.math.abs(lon)))
            }
            meta.gpsAltitude?.let { alt ->
                exif.setAttribute("GPSAltitudeRef",  if (alt >= 0) "0" else "1")
                exif.setAttribute("GPSAltitude",      rational((kotlin.math.abs(alt) * 100).toInt(), 100))
            }
            if (meta.gpsTimestamp.isNotEmpty()) exif.setAttribute("GPSTimeStamp", meta.gpsTimestamp)
        }
    }

    /**
     * Embed original RAW EXIF (plus RAZRAW Software signature) into an already-written
     * image file at [filePath]. No-op if metadata is unavailable.
     * When [keepDateTime] is false, date/time EXIF tags are omitted.
     * When [stripSensitive] is true, GPS + Artist + Copyright are skipped
     * (camera/lens/exposure stays). Implies [keepGps] = false.
     */
    fun embedExifToFile(
        filePath: String,
        keepDateTime: Boolean = true,
        keepGps: Boolean = true,
        stripSensitive: Boolean = false,
    ) {
        val meta = latestPreviewReady?.metadata ?: return
        runCatching {
            val softwareTag = "RAZStudio RAZRAW $RAZRAW_SOFTWARE_VERSION (Android)"
            val exif = ExifInterface(filePath)
            val effMeta = if (stripSensitive) meta.copy(artist = "", copyright = "") else meta
            val effKeepGps = keepGps && !stripSensitive
            embedRawExif(exif, effMeta, appSoftwareTag = softwareTag, keepDateTime = keepDateTime, keepGps = effKeepGps)
            exif.saveAttributes()
        }
    }

    /**
     * Write ONLY the RAZStudio Software tag (plus Orientation=1) — no camera,
     * lens, GPS, or date metadata. Used when the user's ExifPolicy is
     * NoneExceptSoftware.
     */
    fun embedExifSoftwareOnlyToFile(filePath: String) {
        runCatching {
            val softwareTag = "RAZStudio RAZRAW $RAZRAW_SOFTWARE_VERSION (Android)"
            val exif = ExifInterface(filePath)
            exif.setAttribute("Software", softwareTag)
            exif.setAttribute("Orientation", "1")
            exif.saveAttributes()
        }
    }

    /**
     * Apply [macro] and EXIF orientation to the cached full-res base PNG and write
     * the result as workspace_export.png alongside the base file.
     *
     * Returns the absolute path of the export file, or null if the base PNG is not
     * available (full-res pipeline not yet complete).
     */
    suspend fun renderAndExport(macro: UserMacro): String? = withContext(Dispatchers.Default) {
        // ── Stage-C-cache-cleared (Save-claim) fast path ─────────────────────
        // exportToGallery clears `fullResOutputPath` and deletes the Stage C
        // disk cache up-front to free memory for the save. In that case there
        // is no cached PNG to read; route directly into the re-decode path
        // which reads the original RAW from disk and runs the full pipeline.
        val basePath = fullResOutputPath
        if (basePath == null) {
            android.util.Log.i(
                "RAZ.Save",
                "renderAndExport: no Stage C cache (cleared by Save claim) — re-decoding from source RAW",
            )
            val sha = latestPreviewReady?.metadata?.fileSha256
            val originalDimsText = sha?.let { cache.readStageText(it, RawStageCache.Stage.A_FULLRES, "original_dims.bin") }
            val originalDims = originalDimsText?.let { parseDimsLocal(it) }
                ?: run {
                    // Fall back to preview metadata's outputW/H × 2 if the
                    // original_dims sidecar wasn't written yet.
                    val md = latestPreviewReady?.metadata ?: return@withContext null
                    md.outputWidth * 2 to md.outputHeight * 2
                }
            return@withContext renderAndExportFromFullDecode(macro, originalDims)
        }

        val baseFile = java.io.File(basePath)
        if (!baseFile.exists()) return@withContext null

        // ── Save-path integrity check ────────────────────────────────────────
        // The cached `workspace_full_base.png` MAY be a half-resolution fallback
        // if Stage A hit OOM during full-res decode (see
        // FullResPipeline.decodeFullResWithFallback). Saving that to disk would
        // silently produce a half-res output file — the user expects original
        // sensor resolution. Detect that case by comparing the cached buffer
        // dims (`dims.bin`) against the true sensor dims (`original_dims.bin`).
        // When they disagree, take the full re-render path below.
        val sha = latestPreviewReady?.metadata?.fileSha256
        val cachedDimsText  = sha?.let { cache.readStageText(it, RawStageCache.Stage.A_FULLRES, "dims.bin") }
        val originalDimsText = sha?.let { cache.readStageText(it, RawStageCache.Stage.A_FULLRES, "original_dims.bin") }
        val cachedDims  = cachedDimsText?.let  { parseDimsLocal(it) }
        val originalDims = originalDimsText?.let { parseDimsLocal(it) }
        // Compare by total pixel count, not positional W/H. Portrait CR2 files
        // record original_dims in post-orientation (e.g. 3670x5494) while
        // LibRaw returns landscape sensor-native dims (e.g. 5496x3669) — same
        // image, rotation-swapped. Area comparison handles that cleanly.
        val cachedPixels   = cachedDims?.let { it.first.toLong() * it.second }
        val originalPixels = originalDims?.let { it.first.toLong() * it.second }
        val isHalfResCache = cachedPixels != null && originalPixels != null &&
            // half-res buffer has 1/4 the pixel count of full; use a 0.6×
            // threshold so any meaningful shortfall trips the re-decode path
            // while floating-point/trimming differences don't.
            cachedPixels < originalPixels * 0.6

        if (isHalfResCache) {
            android.util.Log.w(
                "RAZ.Save",
                "Cached Stage C is half-res (cached=${cachedDims.first}x${cachedDims.second} " +
                    "vs original=${originalDims!!.first}x${originalDims.second}). " +
                    "Re-decoding from original RAW for full-resolution export.",
            )
            return@withContext renderAndExportFromFullDecode(macro, originalDims)
        }

        // ── BIT_16 Save path: route through FP16 cache + applyFloatBanded ────
        // The 8-bit PNG load + applyInt path below has subtly different math
        // from the canvas (which runs applyFloat on FP16), causing visible
        // mismatch in shadows (green/magenta speckle from per-channel
        // quantization rounding). When the workspace is BIT_16 AND the FP16
        // cache exists, load it instead — Save then runs the SAME float
        // pipeline as the canvas. Falls back to the 8-bit path on any failure.
        val cfgSave = currentWorkspaceConfig
        if (cfgSave.bitDepth == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth.BIT_16) {
            // Aggressive heap reclaim BEFORE the BIT_16 save. The 242 MB FloatArray
            // allocation that spatial ops need OOMs when the Dalvik heap is sitting
            // at ~330 MB of canvas/pre-LUT/idle-full-res bitmaps (verified adb
            // 2026-05-25). freeMemoryForSave only drops the quality-upgrade caches
            // (clearFullResCache + clearPreLutCache + clearIdleFullRes ≈ 140 MB).
            // We additionally drop the live neutral/zoom/mask bitmaps here — the
            // canvas briefly dims during save and recovers on the next idle render
            // tick. Empirically frees enough headroom for the 242 MB allocation to
            // succeed every time on a 512 MB cap.
            //
            // The fp16Bmp we're about to load is independent (loadFp16CacheBitmap
            // re-mmaps the .fp16 file), so dropping the canvas state doesn't
            // affect the save's source data.
            android.util.Log.i("RAZ.Save", "BIT_16 export: aggressive heap reclaim before FP16 load")
            neutralBitmap?.takeIf { !it.isRecycled }?.recycle()
            neutralBitmap = null
            neutralBitmap8Bit?.takeIf { !it.isRecycled }?.recycle()
            neutralBitmap8Bit = null
            zoomSourceBitmap?.takeIf { !it.isRecycled }?.recycle()
            zoomSourceBitmap = null
            // _maskBitmap is preserved — applyMaskAdjustmentsFloat still needs it
            // for any user-painted mask actions, and it's small (~16 MB at canvas
            // resolution).
            clearFullResCache()
            clearPreLutCache()
            clearIdleFullRes()
            // Paired gc + finalize to compact the heap before the big allocation.
            System.gc()
            System.runFinalization()
            System.gc()
            kotlinx.coroutines.delay(200L)
            val rt = Runtime.getRuntime()
            val freeMb = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
            android.util.Log.i("RAZ.Save", "BIT_16 export: post-reclaim freeMb=${freeMb}MB (target needs 242 MB)")

            val fp16Bmp = runCatching { loadFp16CacheBitmap(sha, cachedDims) }.getOrNull()
            if (fp16Bmp != null) {
                android.util.Log.i(
                    "RAZ.Save",
                    "BIT_16 export: routing through FP16 cache (${fp16Bmp.width}x${fp16Bmp.height})",
                )
                var bmpF = fp16Bmp
                val effMacroExportF = applyRazamazeDefaults(macro)
                // releaseNeutral=true: this fp16Bmp was just loaded from cache for
                // this save call and is NOT shared with the canvas. MacroProcessor
                // can recycle it mid-pipeline to free ~161 MB of heap for the
                // 242 MB spatial FloatArray — eliminates the OOM → 8-bit bridge
                // fallback that was making save take 50 s with banding output.
                bmpF = MacroProcessor.apply(bmpF, effMacroExportF, _segmentationMasks.value, _maskBitmap.value, releaseNeutral = true)
                bmpF = applyLutChain(bmpF, effMacroExportF)
                bmpF = applyMaskLayersSequentially(bmpF)
                bmpF = applyExifOrientation(bmpF, currentOrientation)
                if (effMacroExportF.outputColorSpace != RawColorSpace.SRGB) {
                    bmpF = WideGamutConverter.convertBitmapToColorSpace(bmpF, effMacroExportF.outputColorSpace)
                }
                // CRITICAL: Skia's PNG encoder produces an all-black file when
                // asked to encode an RGBA_F16 bitmap tagged with our custom
                // linearDisplayP3() / linearProPhoto() ColorSpace (verified adb
                // 2026-05-25 — saved JPG was 67 KB of solid black for a 2736×4103
                // image). The encoder doesn't know how to invert our custom
                // linear-transfer ColorSpace and the output samples crush to 0.
                //
                // Fix: convert FP16 → ARGB_8888 via Bitmap.copy(ARGB_8888) BEFORE
                // PNG compress. This triggers Android's color management — the
                // linear-P3/ProPhoto samples are correctly transformed into
                // gamma-encoded sRGB ARGB_8888 (which Skia DOES know how to
                // encode). Subsequent loadAndScale (BitmapFactory.decodeFile)
                // would decode to ARGB_8888 anyway, so we lose nothing here.
                //
                // The 16-bit save formats (PNG_16, TIFF) read via Bitmap16Sampler
                // which upsamples 8→16 with v*257; precision matches what they
                // would get anyway through the decode-from-PNG round-trip.
                if (bmpF.config == Bitmap.Config.RGBA_F16) {
                    // Convert FP16/linear → ARGB_8888 tagged with the workspace
                    // gamut's GAMMA-ENCODED variant. Critical: `Bitmap.copy(ARGB_8888)`
                    // would default the destination tag to plain sRGB and
                    // gamma-encode the linear-P3 samples into sRGB primaries —
                    // then we'd embed a DCI-P3 ICC saying "interpret as P3",
                    // and the gallery would render sRGB values as P3 →
                    // perceptually dark/desaturated (verified adb 2026-05-25).
                    //
                    // We create the destination bitmap tagged with the matching
                    // gamma-encoded ColorSpace upfront and use `Canvas.drawBitmap`
                    // to blit. Android color-manages: linear-P3 FP16 → gamma-P3
                    // ARGB_8888. The PNG bytes + DCI-P3 ICC then render correctly
                    // in any P3-aware viewer.
                    val targetGamutSpace: android.graphics.ColorSpace = when (effMacroExportF.outputColorSpace) {
                        RawColorSpace.SRGB ->
                            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                        RawColorSpace.DISPLAY_P3 ->
                            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
                        RawColorSpace.PROPHOTO_RGB ->
                            // No named gamma-ProPhoto; fall back to sRGB tag and
                            // let the ICC embed handle viewer interpretation.
                            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                    }
                    val rgb8 = runCatching {
                        val target = Bitmap.createBitmap(
                            bmpF.width, bmpF.height, Bitmap.Config.ARGB_8888,
                            true, targetGamutSpace,
                        )
                        android.graphics.Canvas(target).drawBitmap(bmpF, 0f, 0f, null)
                        target
                    }.getOrElse {
                        android.util.Log.w(
                            "RAZ.Save",
                            "BIT_16 export: tagged FP16→ARGB_8888 blit failed (${it.message}); falling back to plain copy",
                        )
                        bmpF.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    if (rgb8 != null && rgb8 !== bmpF) {
                        bmpF.recycle()
                        bmpF = rgb8
                    }
                    android.util.Log.i(
                        "RAZ.Save",
                        "BIT_16 export: downconverted to ARGB_8888 tagged ${bmpF.colorSpace?.name}",
                    )
                }
                val exportFileF = java.io.File(baseFile.parent, "workspace_export.png")
                val outF = java.io.ByteArrayOutputStream()
                val compressOk = bmpF.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outF)
                android.util.Log.i(
                    "RAZ.Save",
                    "BIT_16 export: intermediate PNG compress ok=$compressOk bytes=${outF.size()} (${bmpF.width}x${bmpF.height} config=${bmpF.config})",
                )
                val pngBytesF = IccProfileWriter.embedColorSpace(outF.toByteArray(), effMacroExportF.outputColorSpace)
                exportFileF.writeBytes(pngBytesF)
                return@withContext exportFileF.absolutePath
            } else {
                android.util.Log.w(
                    "RAZ.Save",
                    "BIT_16 export: FP16 cache unavailable, falling back to 8-bit PNG path",
                )
            }
        }

        var bmp = android.graphics.BitmapFactory.decodeFile(basePath) ?: return@withContext null
        val effMacroExport = applyRazamazeDefaults(macro)
        bmp = MacroProcessor.apply(bmp, effMacroExport, _segmentationMasks.value, _maskBitmap.value)
        bmp = applyLutChain(bmp, effMacroExport)
        // Stack every visible mask layer on top of the baseline render before
        // orientation + colorspace + PNG write. The full-res export sees the
        // same composition the on-screen preview shows.
        bmp = applyMaskLayersSequentially(bmp)
        bmp = applyExifOrientation(bmp, currentOrientation)
        if (macro.outputColorSpace != RawColorSpace.SRGB) {
            bmp = WideGamutConverter.convertBitmapToColorSpace(bmp, macro.outputColorSpace)
        }

        val exportFile = java.io.File(baseFile.parent, "workspace_export.png")
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 0, out)
        val pngBytes = IccProfileWriter.embedColorSpace(out.toByteArray(), macro.outputColorSpace)
        exportFile.writeBytes(pngBytes)
        exportFile.absolutePath
    }

    /**
     * Load the FP16 Stage C cache (written by [FullResPipeline] when workspace
     * is BIT_16) as an RGBA_F16 bitmap tagged with the gamut from the sidecar.
     * Memory-mapped — no JVM heap copy for the 161MB pixel data. Returns null
     * if the cache files are missing or unreadable.
     */
    private fun loadFp16CacheBitmap(
        sha: String?,
        dims: Pair<Int, Int>?,
    ): android.graphics.Bitmap? {
        if (sha == null || dims == null) return null
        val fp16File = cache.stageFile(sha, RawStageCache.Stage.C_FULLRES, "workspace_full_base.fp16")
        if (!fp16File.exists()) return null
        val gamutText = cache.readStageText(sha, RawStageCache.Stage.C_FULLRES, "workspace_full_base.gamut")
        val gamut = runCatching {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.valueOf(
                gamutText?.trim() ?: "SRGB",
            )
        }.getOrDefault(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.SRGB)
        val colorSpace = when (gamut) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.SRGB         ->
                android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB)
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.DISPLAY_P3   ->
                WideGamutConverter.linearDisplayP3Public()
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace.PROPHOTO_RGB ->
                WideGamutConverter.linearProPhotoPublic()
        }
        val mapped = java.io.RandomAccessFile(fp16File, "r").use { raf ->
            raf.channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                0, fp16File.length(),
            )
        }.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val bmp = android.graphics.Bitmap.createBitmap(
            dims.first, dims.second, android.graphics.Bitmap.Config.RGBA_F16, true, colorSpace,
        )
        bmp.copyPixelsFromBuffer(mapped)
        return bmp
    }

    /**
     * Fallback export path used when the on-disk Stage C cache is half-res
     * (post-OOM-fallback). Re-decodes the original RAW file at FULL resolution,
     * re-runs Stage C wide-gamut conversion, then applies the macro pipeline.
     *
     * Returns null and logs a `RAZ.Save` error when:
     *   - the original RAW source path is missing (session torn down), or
     *   - the full-res LibRaw decode OOMs again (same condition that produced
     *     the half-res cache in the first place — re-trying may or may not
     *     succeed depending on current heap pressure; if it OOMs we refuse to
     *     save rather than silently emit a half-res file).
     *
     * The UI export layer treats a null return as "save failed" and surfaces a
     * snackbar/dialog so the user knows their save didn't silently degrade.
     */

    private suspend fun renderAndExportFromFullDecode(
        macro: UserMacro,
        originalDims: Pair<Int, Int>,
    ): String? = withContext(Dispatchers.Default) {
        val srcPath = currentFilePath
        if (srcPath == null) {
            android.util.Log.e("RAZ.Save", "Re-decode aborted: original RAW file path missing")
            return@withContext null
        }
        val sha = latestPreviewReady?.metadata?.fileSha256
        val metadata = latestPreviewReady?.metadata
        if (sha == null || metadata == null) {
            android.util.Log.e("RAZ.Save", "Re-decode aborted: no PreviewReady metadata (session torn down)")
            return@withContext null
        }

        // Free any retained bitmaps and run paired GC + finalization to
        // maximize the chance that the ~161 MB full-res decode succeeds this
        // time. This is the same recipe FullResPipeline uses on its first
        // attempt; we replay it for the export retry.
        freeMemoryForBatch()

        val cfg = currentWorkspaceConfig

        // ── 8-bit-direct decode (preferred path for BIT_8 workspace) ─────────
        // When the workspace is 8-bit, skip the float16 intermediate entirely.
        // The uint16 → uint8 conversion happens in a single per-pixel kernel
        // inside decodeToLinear8bit, producing an ARGB_8888 IntArray ready
        // for MacroProcessor directly. Memory profile for a 24 MP RAW:
        //   - 121 MB native uint16 BGR buffer (released after conversion)
        //   - 80 MB Java heap IntArray (the result we hand to MacroProcessor)
        // Total ~80 MB JVM heap, vs ~241 MB on the float16-then-Stage-C path.
        //
        // Skipped entirely: float16 intermediate (161 MB), Stage C cam2srgb
        // matrix multiplication (LibRaw already outputs linear sRGB with WB
        // applied), and the linear-to-gamma conversion inside Stage C.
        if (cfg.bitDepth == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth.BIT_8) {
            // ── Save-path demosaic strategy ──────────────────────────────────
            // The workspace-selector's demosaic choice (e.g. AMAZE, RAZAmaze)
            // drives the preview + on-canvas editing decode where memory and
            // time budgets are different. For the SAVE path the constraint is
            // strict: must produce a full-resolution real photo without
            // stalling for minutes inside LibRaw's heavy demosaic algorithms.
            //
            // LibRaw scratch memory + CPU cost by algorithm (24 MP Canon CR2):
            //   AMaZE (userQual=12):    ~400 MB native scratch, ~60-180 s on phone — STALLS on memory-pressured devices
            //   RAZAmaze (RCD, -1):     ~360 MB scratch, ~30-90 s — STALLS likewise
            //   AHD (3):                ~240 MB scratch, ~15-30 s
            //   DCB (4):                ~180 MB scratch, ~10-20 s
            //   VNG (1):                ~150 MB scratch, ~8-15 s
            //   Linear bilinear (0):    ~120 MB scratch, ~3-8 s — bare minimum
            //
            // We **start** with Linear at full resolution. It always completes
            // and produces a real full-res photo. The 15-20% softness vs AMAZE
            // is much better than:
            //   - a 2+ minute stall waiting for AMAZE to swap-thrash, or
            //   - a solid-color failure when AMAZE silently OOMs, or
            //   - a half-res save (user explicitly forbade this).
            //
            // Sliders + LUT + macro still produce the user's edit fully on top
            // of the Linear-demosaiced base. The only difference vs the canvas
            // preview is the demosaic step; all subsequent processing is shared.
            val originalQual = cfg.demosaicAlgorithm.userQual
            // Progression: start light, escalate ONLY if Linear fails (which is
            // rare — Linear has the smallest possible scratch budget).
            val qualProgression = buildList {
                add(0)  // Linear — fastest + lightest, always tries first
                if (originalQual != 0) add(1) // VNG — slightly better edges
                if (originalQual != 0 && originalQual != 1) add(2) // DCB
            }.distinct()
            android.util.Log.i(
                "RAZ.Save",
                "Save demosaic progression: $qualProgression (workspace's choice was userQual=$originalQual)",
            )

            var decoded8: LibRawJniBridge.LinearDecode8Result? = null
            for ((attemptIdx, qual) in qualProgression.withIndex()) {
                if (attemptIdx > 0) {
                    android.util.Log.w(
                        "RAZ.Save",
                        "8bit-direct attempt ${attemptIdx + 1}: retrying at full-res with lower demosaic quality (userQual=$qual)",
                    )
                    freeMemoryForBatch()
                    kotlinx.coroutines.delay(250L)
                }
                decoded8 = try {
                    LibRawJniBridge.decodeToLinear8bit(
                        filePath = srcPath,
                        halfSize = false,           // ALWAYS full-res
                        userQual = qual,
                    )
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w(
                        "RAZ.Save",
                        "8bit-direct full-res OOM at userQual=$qual (${e.message})",
                    )
                    null
                }
                if (decoded8 != null && decoded8.pixels.isNotEmpty()) {
                    android.util.Log.i(
                        "RAZ.Save",
                        "8bit-direct decode SUCCESS at ${decoded8.width}x${decoded8.height} " +
                            "(userQual=$qual, ${decoded8.pixels.size * 4} bytes IntArray)",
                    )
                    return@withContext renderAndExportFrom8bitDecode(macro, decoded8, metadata)
                }
            }
            android.util.Log.e(
                "RAZ.Save",
                "8bit-direct decode failed at all demosaic quality levels — device cannot decode this RAW at full resolution. Refusing to save half-res.",
            )
            return@withContext null
        }

        // ── Memory-mapped decode (preferred path for BIT_16) ─────────────────
        // The float16 output is written to a temp file via FileChannel.map(),
        // never touching the JVM heap. This is the key memory savings: a
        // 5500×3669 RAW that previously needed a 161 MB Java heap ByteArray
        // (which OOMs on heap-pressured devices) now needs zero JVM heap for
        // the f16 buffer — only the OS page cache backs the data.
        //
        // The mapped file lives in the cache dir for the duration of the save
        // and is deleted at the end. Disk cost: ~161 MB per save, freed
        // immediately when the save returns.
        val mappedTempFile = java.io.File(
            context.cacheDir,
            "raw_save_mapped_${System.currentTimeMillis()}.f16"
        )
        var mappedDecoded: LibRawJniBridge.MappedLinearDecodeResult? = null
        // Try the mapped path first — succeeds on devices where the heap-array
        // path would OOM. One attempt only; if it fails we still have the
        // legacy ByteArray retry loop + half-res fallback below.
        mappedDecoded = try {
            LibRawJniBridge.decodeToLinearMapped(
                filePath   = srcPath,
                halfSize   = false,
                userQual   = cfg.demosaicAlgorithm.userQual,
                outputFile = mappedTempFile,
            )
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("RAZ.Save", "Mapped decode OOM (${e.message}); falling back to heap-array path")
            null
        } catch (e: Throwable) {
            android.util.Log.w("RAZ.Save", "Mapped decode failed (${e.message}); falling back to heap-array path")
            null
        }

        if (mappedDecoded != null) {
            android.util.Log.i(
                "RAZ.Save",
                "Mapped decode SUCCESS at ${mappedDecoded.width}x${mappedDecoded.height} " +
                    "(file=${mappedTempFile.length()} bytes on disk, zero JVM heap)",
            )
            // Render Stage C + macro on the mapped buffer, write export, then delete the temp file.
            val path = try {
                renderAndExportFromMappedDecode(macro, mappedDecoded, metadata)
            } finally {
                mappedTempFile.delete()
            }
            return@withContext path
        }
        // Mapped path failed — fall through to legacy ByteArray retry loop.

        // Retry up to 2x on zero-buffer (silent OOM in NativeRawDecoder's float16
        // conversion). Each retry does a paired GC + finalization first so the
        // native allocation has the cleanest possible heap. If both retries
        // still come back zero we give up cleanly so the user sees a real
        // failure instead of a solid-color image.
        fun isZeroBuf(bytes: ByteArray): Boolean {
            val sz = bytes.size
            val probes = intArrayOf(sz / 4, sz / 2, (3 * sz) / 4, sz / 3)
            return probes.all { off ->
                off in 0 until sz - 1 && bytes[off] == 0.toByte() && bytes[off + 1] == 0.toByte()
            }
        }
        var decoded: LibRawJniBridge.LinearDecodeResult? = null
        var attempt = 0
        while (attempt < 3 && decoded == null) {
            if (attempt > 0) {
                android.util.Log.w(
                    "RAZ.Save",
                    "Re-decode retry $attempt/2 after zero-buffer (silent native OOM)",
                )
                freeMemoryForBatch()
                // Tiny wait so the GC's reclaim has time to complete before
                // the next ~161 MB native allocation request.
                kotlinx.coroutines.delay(250L)
            }
            val candidate = try {
                LibRawJniBridge.decodeToLinear(
                    filePath = srcPath,
                    halfSize = false,
                    userQual = cfg.demosaicAlgorithm.userQual,
                    // Forward user workspace settings. Without this, the save-
                    // path re-decode ignores the user's CA-off / NR-off choices
                    // and runs CA on every save, which routinely exceeds the
                    // 60s watchdog on 20MP files (verified adb 2026-05-25).
                    caCorrectionEnabled = cfg.caCorrectionEnabled,
                    nrEnabled = cfg.nrEnabled,
                    nrLuma = cfg.nrLuma,
                    nrChroma = cfg.nrChroma,
                )
            } catch (e: OutOfMemoryError) {
                android.util.Log.e(
                    "RAZ.Save",
                    "Re-decode OOM on attempt ${attempt + 1}: heap exhausted (${e.message}).",
                )
                null
            }
            if (candidate != null && isZeroBuf(candidate.pixels)) {
                android.util.Log.w(
                    "RAZ.Save",
                    "Re-decode attempt ${attempt + 1} came back zero-filled — retrying",
                )
            } else if (candidate != null) {
                decoded = candidate
            }
            attempt++
        }

        // Half-res fallback. If all 3 full-resolution attempts came back zero-
        // filled, the device genuinely can't fit the ~161 MB native allocation
        // right now. Falling back to halfSize=true cuts that to ~40 MB and the
        // decode reliably succeeds. The user gets a 2748×1834 save instead of
        // 5496×3669 — still ~5 MP, useful, and unambiguously better than no
        // save at all. Logged at INFO so the user can see in adb why their
        // output is smaller than the source.
        if (decoded == null) {
            android.util.Log.w(
                "RAZ.Save",
                "Full-res re-decode failed 3x; falling back to half-resolution save.",
            )
            freeMemoryForBatch()
            kotlinx.coroutines.delay(250L)
            val halfRes = try {
                LibRawJniBridge.decodeToLinear(
                    filePath = srcPath,
                    halfSize = true,
                    userQual = cfg.demosaicAlgorithm.userQual,
                    // Forward workspace settings on fallback too. (CA is always
                    // skipped at halfSize=true inside decodeToLinear regardless,
                    // but NR forwarding keeps behavior consistent.)
                    caCorrectionEnabled = cfg.caCorrectionEnabled,
                    nrEnabled = cfg.nrEnabled,
                    nrLuma = cfg.nrLuma,
                    nrChroma = cfg.nrChroma,
                )
            } catch (e: OutOfMemoryError) {
                android.util.Log.e(
                    "RAZ.Save",
                    "Half-res decode also OOMed (${e.message}). Refusing to save.",
                )
                null
            }
            if (halfRes != null && !isZeroBuf(halfRes.pixels)) {
                android.util.Log.i(
                    "RAZ.Save",
                    "Half-res decode SUCCESS at ${halfRes.width}x${halfRes.height} — saving at reduced resolution.",
                )
                decoded = halfRes
            } else if (halfRes != null) {
                android.util.Log.e(
                    "RAZ.Save",
                    "Half-res decode came back zero-filled too. Device cannot save this RAW right now. " +
                        "Close background apps and try again.",
                )
            }
        }
        if (decoded == null) {
            android.util.Log.e("RAZ.Save", "Re-decode returned null after full-res + half-res attempts")
            return@withContext null
        }
        // (Zero-buffer + smaller-than-expected guards removed: the retry loop
        // and half-res fallback above already drop the buffer if it's zero, and
        // a deliberate half-res decode is now an accepted outcome rather than
        // a failure mode.)
        android.util.Log.i(
            "RAZ.Save",
            "Re-decode SUCCESS at ${decoded.width}x${decoded.height} — running Stage C",
        )

        // Wrap the entire downstream block (Stage C + MacroProcessor box blur
        // + LUT chain + mask layers + PNG encode) in an OOM catch. A full-res
        // 8-bit pass on a 24 MP RAW peaks at ~240 MB resident; on a 512 MB
        // capped heap the noise-reduction box-blur's working IntArray (~80 MB)
        // can push us over once the ~161 MB float16 input plus 40 MB IntArray
        // plus 40 MB ARGB bitmap are all live. If that happens we return null
        // and surface a "save failed" toast — much better than the previous
        // behavior where the OOM propagated out and the crash screen fired.
        val decodedW = decoded.width
        val decodedH = decoded.height
        val exportPath = try {
            // Run Stage C wide-gamut conversion in 8-bit (the export path consumes
            // an ARGB_8888 bitmap regardless of workspace bit-depth; FP16 would
            // double the memory bill without changing the final 8-bit PNG output).
            val stageC = WideGamutConverter.convert(
                pixelsFloat16 = decoded.pixels,
                width = decodedW,
                height = decodedH,
                metadata = metadata,
                config = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig.Default,
            )
            var bmp = stageC.workspaceBitmap

            // Drop our reference to the ~161 MB decoded.pixels ByteArray and the
            // intermediate `decoded` result — Stage C has consumed them. Nudge
            // GC so the heap is as clean as possible before MacroProcessor's
            // box-blur tries to grab its 80 MB working IntArray.
            @Suppress("UNUSED_VALUE")
            decoded.pixels.also { /* keep `it` referenced only to here */ }
            // (decoded itself goes out of scope after the try block; no manual
            // clear needed beyond what the JIT inlines.)
            run {
                val rt = Runtime.getRuntime()
                val freeMb = rt.freeMemory() / (1024 * 1024)
                val maxMb  = rt.maxMemory() / (1024 * 1024)
                android.util.Log.i(
                    "RAZ.Save",
                    "Pre-Macro GC: free=${freeMb}MB max=${maxMb}MB",
                )
                System.gc()
                System.runFinalization()
                System.gc()
            }

            val effMacroExport = applyRazamazeDefaults(macro)
            bmp = MacroProcessor.apply(bmp, effMacroExport, _segmentationMasks.value, _maskBitmap.value)
            bmp = applyLutChain(bmp, effMacroExport)
            bmp = applyMaskLayersSequentially(bmp)
            bmp = applyExifOrientation(bmp, currentOrientation)
            if (macro.outputColorSpace != RawColorSpace.SRGB) {
                bmp = WideGamutConverter.convertBitmapToColorSpace(bmp, macro.outputColorSpace)
            }

            // Write next to the existing Stage C cache so cleanup logic finds it.
            val basePath = fullResOutputPath
            val parent = basePath?.let { java.io.File(it).parent } ?: context.cacheDir.absolutePath
            val exportFile = java.io.File(parent, "workspace_export.png")
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 0, out)
            val pngBytes = IccProfileWriter.embedColorSpace(out.toByteArray(), macro.outputColorSpace)
            exportFile.writeBytes(pngBytes)
            android.util.Log.i(
                "RAZ.Save",
                "Re-decode export wrote ${exportFile.length()} bytes at ${decodedW}x${decodedH}",
            )
            exportFile.absolutePath
        } catch (e: OutOfMemoryError) {
            android.util.Log.e(
                "RAZ.Save",
                "OOM in post-decode pipeline (${e.message}). Refusing to save.",
                e,
            )
            null
        }
        exportPath
    }

    private fun parseDimsLocal(text: String): Pair<Int, Int>? {
        val parts = text.trim().split(",")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return w to h
    }

    /**
     * Stage C + macro pipeline starting from a uint8-direct decode (the
     * 8-bit-workspace fast path produced by [LibRawJniBridge.decodeToLinear8bit]).
     *
     * Skips Stage C wide-gamut conversion entirely — the decoder already
     * produced sRGB ARGB_8888 pixels. The macro pipeline runs on the IntArray
     * directly via setPixels, then orientation + color-space tag + PNG write.
     *
     * Memory profile vs the float16 path on a 24 MP RAW:
     *   - This path:    80 MB IntArray + 80 MB ARGB bitmap = 160 MB peak
     *   - Mapped path: 161 MB mapped f16 + 40 MB IntArray + 80 MB bitmap = 281 MB peak
     *   - Heap-array:  161 MB f16 ByteArray + 40 MB IntArray + 80 MB bitmap = 281 MB peak
     *
     * Plus this is roughly 2× faster end-to-end because the float16 conversion
     * and Stage C matrix multiplication are both skipped.
     */
    private suspend fun renderAndExportFrom8bitDecode(
        macro: UserMacro,
        decoded: LibRawJniBridge.LinearDecode8Result,
        metadataFromSession: RawMetadata,
    ): String? = withContext(Dispatchers.Default) {
        val decodedW = decoded.width
        val decodedH = decoded.height

        val exportPath = try {
            // Build an ARGB_8888 bitmap from the IntArray. setPixels copies the
            // ints into the bitmap's native buffer; after that we can let the
            // IntArray go for GC.
            var bmp = android.graphics.Bitmap.createBitmap(decodedW, decodedH, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(decoded.pixels, 0, decodedW, 0, 0, decodedW, decodedH)
            // Drop our reference to the 80 MB IntArray so GC can reclaim it
            // before MacroProcessor's box-blur scratch grabs more memory.
            // (decoded itself goes out of scope at the end of the try.)

            run {
                val rt = Runtime.getRuntime()
                android.util.Log.i(
                    "RAZ.Save",
                    "Pre-Macro GC (8bit-direct): free=${rt.freeMemory() / (1024 * 1024)}MB max=${rt.maxMemory() / (1024 * 1024)}MB",
                )
                System.gc(); System.runFinalization(); System.gc()
            }

            val effMacroExport = applyRazamazeDefaults(macro)
            bmp = MacroProcessor.apply(bmp, effMacroExport, _segmentationMasks.value, _maskBitmap.value)
            bmp = applyLutChain(bmp, effMacroExport)
            bmp = applyMaskLayersSequentially(bmp)
            bmp = applyExifOrientation(bmp, currentOrientation)
            if (macro.outputColorSpace != RawColorSpace.SRGB) {
                bmp = WideGamutConverter.convertBitmapToColorSpace(bmp, macro.outputColorSpace)
            }

            val basePath = fullResOutputPath
            val parent = basePath?.let { java.io.File(it).parent } ?: context.cacheDir.absolutePath
            val exportFile = java.io.File(parent, "workspace_export.png")
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 0, out)
            val pngBytes = IccProfileWriter.embedColorSpace(out.toByteArray(), macro.outputColorSpace)
            exportFile.writeBytes(pngBytes)
            android.util.Log.i(
                "RAZ.Save",
                "8bit-direct export wrote ${exportFile.length()} bytes at ${decodedW}x${decodedH}",
            )
            exportFile.absolutePath
        } catch (e: OutOfMemoryError) {
            android.util.Log.e(
                "RAZ.Save",
                "OOM in 8bit-direct macro pipeline (${e.message}). Refusing to save.",
                e,
            )
            null
        }
        exportPath
    }

    /**
     * Stage C + macro pipeline starting from a memory-mapped float16 buffer.
     *
     * The mapped buffer lives in the OS page cache, not the JVM heap — so
     * Stage C, MacroProcessor, LUT chain, and PNG encode all run with the
     * full Java heap available for their working sets. This is what makes
     * full-resolution saves possible on memory-pressured devices where the
     * legacy heap-array path OOMs.
     *
     * Uses [WideGamutConverter.convert]'s `ByteBuffer` overload which was
     * designed for exactly this case (the comment on it predicts: "The full-res
     * cache-hit path uses this with a `FileChannel.map()` mapped buffer to
     * avoid loading 161 MB into the JVM heap as a `ByteArray`").
     */
    private suspend fun renderAndExportFromMappedDecode(
        macro: UserMacro,
        mapped: LibRawJniBridge.MappedLinearDecodeResult,
        metadataFromSession: RawMetadata,
    ): String? = withContext(Dispatchers.Default) {
        val decodedW = mapped.width
        val decodedH = mapped.height

        // Merge metadata: keep sourceUri + fileSha256 from the session-level
        // PreviewReady (those are not in the mapped decode's synthetic metadata)
        // but use the decoder's freshly-computed rgbCam / camMul / orientation.
        val mergedMetadata = mapped.metadata.copy(
            sourceUri  = metadataFromSession.sourceUri,
            fileSha256 = metadataFromSession.fileSha256,
        )

        val exportPath = try {
            val stageC = WideGamutConverter.convert(
                pixelsFloat16 = mapped.pixels,           // <-- ByteBuffer overload
                width = decodedW,
                height = decodedH,
                metadata = mergedMetadata,
                config = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig.Default,
            )
            var bmp = stageC.workspaceBitmap

            // The mapped file is now consumed; we can let the OS reclaim its
            // pages on GC pressure. Force a GC pair so the heap is as clean
            // as possible for MacroProcessor's spatial-pass IntArrays.
            run {
                val rt = Runtime.getRuntime()
                android.util.Log.i(
                    "RAZ.Save",
                    "Pre-Macro GC (mapped): free=${rt.freeMemory() / (1024 * 1024)}MB max=${rt.maxMemory() / (1024 * 1024)}MB",
                )
                System.gc(); System.runFinalization(); System.gc()
            }

            val effMacroExport = applyRazamazeDefaults(macro)
            bmp = MacroProcessor.apply(bmp, effMacroExport, _segmentationMasks.value, _maskBitmap.value)
            bmp = applyLutChain(bmp, effMacroExport)
            bmp = applyMaskLayersSequentially(bmp)
            bmp = applyExifOrientation(bmp, currentOrientation)
            if (macro.outputColorSpace != RawColorSpace.SRGB) {
                bmp = WideGamutConverter.convertBitmapToColorSpace(bmp, macro.outputColorSpace)
            }

            val basePath = fullResOutputPath
            val parent = basePath?.let { java.io.File(it).parent } ?: context.cacheDir.absolutePath
            val exportFile = java.io.File(parent, "workspace_export.png")
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 0, out)
            val pngBytes = IccProfileWriter.embedColorSpace(out.toByteArray(), macro.outputColorSpace)
            exportFile.writeBytes(pngBytes)
            android.util.Log.i(
                "RAZ.Save",
                "Mapped-decode export wrote ${exportFile.length()} bytes at ${decodedW}x${decodedH}",
            )
            exportFile.absolutePath
        } catch (e: OutOfMemoryError) {
            android.util.Log.e(
                "RAZ.Save",
                "OOM in mapped-decode post-Stage-C pipeline (${e.message}). Refusing to save.",
                e,
            )
            null
        }
        exportPath
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Return the shared [GpuLutProcessor], creating it on first call (on [gpuDispatcher]).
     * Returns null if OpenGL ES 3.0 is unavailable on this device.
     */
    private suspend fun acquireGpuProcessor(): com.RAZStudio.opencv_tools.gpu.GpuLutProcessor? {
        if (gpuProcessorInitDone) return gpuProcessor
        // Wait for any prior release to finish before creating a new EGL
        // context. Without this, a fast file switch races the previous
        // session's release against a new acquire — two EGL contexts try
        // to coexist on a single-context GPU thread → EGL_BAD_ALLOC.
        gpuReleaseJob?.join()
        gpuReleaseJob = null
        return withContext(com.RAZStudio.opencv_tools.gpu.GpuLutProcessor.gpuDispatcher) {
            if (!gpuProcessorInitDone) {
                gpuProcessor = runCatching {
                    com.RAZStudio.opencv_tools.gpu.GpuLutProcessor.createOrNull()
                }.getOrNull()
                gpuProcessorInitDone = true
            }
            gpuProcessor
        }
    }

    private fun resolveFilePath(uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path
        else   -> {
            val ext = uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: run {
                    val mime = context.contentResolver.getType(uri) ?: ""
                    android.webkit.MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mime) ?: ""
                }
            val suffix = if (ext.isNotEmpty()) ".$ext" else ""
            val tmp = java.io.File(context.cacheDir, "raw_open_${uri.hashCode()}$suffix")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                tmp.absolutePath
            }.getOrNull()
        }
    }

    /**
     * Apply every LUT layer in the macro: committed [UserMacro.lutStack] entries first,
     * in stack order, then the currently-editing slot LUT (if non-empty) on top.
     * Returns the input bitmap unchanged if there are no LUT layers.
     * contrastBoost is applied only once, on the final layer.
     */
    private suspend fun applyLutChain(bitmap: Bitmap, macro: UserMacro): Bitmap {
        val layers = buildList {
            addAll(macro.lutStack)
            if (macro.lutCubeUri.isNotEmpty()) add(LutLayer(macro.lutCubeUri, macro.lutIntensity))
        }
        if (layers.isEmpty()) return bitmap

        var current = bitmap
        layers.forEachIndexed { index, layer ->
            val isLast = index == layers.size - 1
            val parsed = parseCubeLut(layer.cubeUri) ?: return@forEachIndexed
            val gpuResult = runCatching {
                kotlinx.coroutines.withContext(com.RAZStudio.opencv_tools.gpu.GpuLutProcessor.gpuDispatcher) {
                    val gpu = acquireGpuProcessor() ?: return@withContext null
                    gpu.applyLut(
                        image         = current,
                        lutTable      = parsed.first,
                        lutSize       = parsed.second,
                        intensity     = layer.intensity,
                        mask          = null,
                        contrastBoost = if (isLast) macro.contrastBoost else 0f,
                    )
                }
            }.getOrNull()
            val next = gpuResult ?: MacroProcessor.applyCpuLut(current, parsed.first, parsed.second, layer.intensity)
            if (next !== current && current !== bitmap) current.recycle()
            current = next
        }
        return current
    }

    /**
     * Guarantee a bitmap carries the workspace gamut's ColorSpace tag before
     * it's published to the canvas. Various code paths (LUT GPU pass, OOM
     * bridge, BitmapFactory decode) can return bitmaps tagged with sRGB or
     * LINEAR_EXTENDED_SRGB even when the workspace is DCI-P3 — which causes
     * the user-visible "color flip" on the canvas when bitmaps from different
     * paths land on `_idleFullResBitmap` in sequence.
     *
     * Strategy:
     *   • If the bitmap already carries the target ColorSpace → return as-is
     *   • If not → blit into a freshly-tagged bitmap of the same config.
     *     Canvas.drawBitmap triggers Android's color management so sample
     *     values are correctly transformed across spaces.
     *
     * No-op on API < 26 (no per-bitmap ColorSpace there).
     */
    private fun forceWorkspaceGamut(src: Bitmap): Bitmap {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return src
        val workspaceGamut = currentWorkspaceConfig.colorGamut
        val target: android.graphics.ColorSpace = when (workspaceGamut) {
            RawColorSpace.SRGB ->
                if (src.config == Bitmap.Config.RGBA_F16)
                    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB)
                else
                    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            RawColorSpace.DISPLAY_P3 ->
                if (src.config == Bitmap.Config.RGBA_F16)
                    WideGamutConverter.linearDisplayP3Public()
                else
                    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
            RawColorSpace.PROPHOTO_RGB ->
                // ProPhoto: only linear (FP16) form ships in this build.
                WideGamutConverter.linearProPhotoPublic()
        }
        if (src.colorSpace == target) return src
        return runCatching {
            val cfg = src.config ?: Bitmap.Config.ARGB_8888
            val tagged = Bitmap.createBitmap(src.width, src.height, cfg, src.hasAlpha(), target)
            android.graphics.Canvas(tagged).drawBitmap(src, 0f, 0f, null)
            src.recycle()
            tagged
        }.getOrElse {
            android.util.Log.w(
                "RawPipelineCoord",
                "forceWorkspaceGamut: re-tag to ${target.name} failed (${it.message}); keeping source",
                it,
            )
            src
        }
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
        // CRITICAL: createBitmap(Bitmap, x, y, w, h, matrix, filter) does NOT
        // preserve the source ColorSpace — the destination defaults to
        // EXTENDED_SRGB for RGBA_F16. For linear-tagged inputs (linear DCI-P3,
        // linear sRGB, linear ProPhoto) that mis-tag is the entire root cause of
        // the "unsaturated after idle" + "unsaturated save" bugs: downstream
        // Canvas.drawBitmap then color-manages from the wrong source space and
        // crushes saturation. Re-tag the rotated bitmap with the source's
        // ColorSpace via Canvas blit (the only platform-correct way to attach a
        // ColorSpace post-create — setColorSpace is rejected when component
        // minimums differ, see MacroProcessor.upsample8To16's note).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            rotated !== src && rotated.colorSpace != src.colorSpace
        ) {
            val srcCs = src.colorSpace
            if (srcCs != null) {
                val retagged = runCatching {
                    val tagged = Bitmap.createBitmap(rotated.width, rotated.height, rotated.config ?: Bitmap.Config.ARGB_8888, rotated.hasAlpha(), srcCs)
                    android.graphics.Canvas(tagged).drawBitmap(rotated, 0f, 0f, null)
                    rotated.recycle()
                    src.recycle()
                    tagged
                }
                if (retagged.isSuccess) return retagged.getOrNull()!!
            }
        }
        if (rotated !== src) src.recycle()
        return rotated
    }

    private fun scaleToScreenFit(bitmap: Bitmap, screenW: Int, screenH: Int): Bitmap {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = min(screenW / bw, screenH / bh)
        if (scale >= 1f) return bitmap
        val newW = (bw * scale).roundToInt().coerceAtLeast(1)
        val newH = (bh * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        // createScaledBitmap drops the ColorSpace tag — re-tag the result.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            scaled !== bitmap && scaled.colorSpace != bitmap.colorSpace
        ) {
            val srcCs = bitmap.colorSpace
            if (srcCs != null) {
                val retagged = runCatching {
                    val tagged = Bitmap.createBitmap(newW, newH, scaled.config ?: Bitmap.Config.ARGB_8888, scaled.hasAlpha(), srcCs)
                    android.graphics.Canvas(tagged).drawBitmap(scaled, 0f, 0f, null)
                    scaled.recycle()
                    tagged
                }
                if (retagged.isSuccess) return retagged.getOrNull()!!
            }
        }
        return scaled
    }

    // ── RAW Export helpers ────────────────────────────────────────────────────

    /** Metadata from the most recently opened RAW file, or null before any file is opened. */
    val rawMetadata: RawMetadata?
        get() = latestPreviewReady?.metadata

    /**
     * Returns the actual full-sensor output dimensions by reading dims.bin from the
     * Stage-A full-res cache. Returns null if the full-res pass hasn't completed yet.
     * These are the true sensor dimensions (e.g. 5496×3669), not the half-res preview
     * dimensions stored in RawMetadata.outputWidth/outputHeight (e.g. 2748×1834).
     */
    fun fullResSensorDims(): Pair<Int, Int>? {
        val sha = latestPreviewReady?.metadata?.fileSha256 ?: return null
        // Prefer `original_dims.bin` (true sensor W×H, written by FullResPipeline
        // regardless of whether the actual decode used the half-size OOM fallback).
        // Fall back to `dims.bin` (actual decoded buffer W×H) for older cache entries
        // that predate the original_dims.bin file.
        val text = cache.readStageText(sha, RawStageCache.Stage.A_FULLRES, "original_dims.bin")
            ?: cache.readStageText(sha, RawStageCache.Stage.A_FULLRES, "dims.bin")
            ?: return null
        val parts = text.split(",")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * Returns the absolute path of the Stage-C preview workspace PNG (the quick PNG
     * used for Quick Share — no re-render required), or null if not yet available.
     */
    fun getStageAPreviewPath(): String? {
        val sha = latestPreviewReady?.metadata?.fileSha256 ?: return null
        val file = cache.stageFile(sha, RawStageCache.Stage.C_PREVIEW, "workspace.png")
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Export the current pipeline output in [format] to the device gallery.
     *
     * Full re-render via [renderAndExport] for all formats.
     * After obtaining the source bitmap, scales to [targetWidth]×[targetHeight] using [scaleMode]
     * via [imageScaler], then encodes with [quality]. Dimensions of 0 mean "keep source resolution".
     * Returns true on success.
     */
    @Suppress("DEPRECATION")
    suspend fun exportToGallery(
        context: Context,
        format: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        scaleMode: com.RAZStudio.StudioRoom.core.domain.image.model.ImageScaleMode = com.RAZStudio.StudioRoom.core.domain.image.model.ImageScaleMode.RAZSharp,
        quality: com.RAZStudio.StudioRoom.core.domain.image.model.Quality = com.RAZStudio.StudioRoom.core.domain.image.model.Quality.Base(98),
        imageScaler: com.RAZStudio.StudioRoom.core.domain.image.ImageScaler<Bitmap>? = null,
        /**
         * When provided, HEIC/BMP/JPEG2000 are encoded through this domain compressor
         * (which dispatches to the right Android/libheif/openjpeg backend). When null
         * the legacy Bitmap.CompressFormat fallback writes PNG bytes — useful for
         * the batch path where no compressor is available, but it produces an
         * unusable file for those three formats.
         */
        imageCompressor: com.RAZStudio.StudioRoom.core.domain.image.ImageCompressor<Bitmap>? = null,
        resizeSharpen: com.RAZStudio.StudioRoom.core.domain.image.model.ResizeSharpen = com.RAZStudio.StudioRoom.core.domain.image.model.ResizeSharpen.None,
        settings: com.RAZStudio.StudioRoom.core.settings.domain.model.SettingsState? = null,
        sourceOriginalName: String? = null,
        /** When false, skip writing the EXIF block entirely (Software-only tag is still written). */
        saveExif: Boolean = true,
        /** When false, skip embedding the ICC color profile chunk into the output. */
        saveIcc: Boolean = true,
        /** When false, strip GPS coordinates from the EXIF block. Has no effect when saveExif is false. */
        saveGps: Boolean = true,
        /** When true, drop Artist/Copyright/GPS but keep camera/lens/exposure. Ignored when saveExif is false. */
        stripSensitive: Boolean = false,
        /**
         * Filename collision policy for the target file. AddDateSuffix appends
         * `_yyyyMMdd_HHmmss` before the extension if the target already exists.
         */
        collisionPolicy: RawBatchProcessor.FilenameCollisionPolicy = RawBatchProcessor.FilenameCollisionPolicy.Overwrite,
    ): Boolean = withContext(Dispatchers.IO) {
            val saveStartMs = System.currentTimeMillis()
            android.util.Log.d(
                "RAZ.Save",
                "exportToGallery start: format=${format.name} target=${targetWidth}x${targetHeight}",
            )

            // ── V2 fast-path: skip the V1-era "Save claim" entirely ──────────
            // When BuildConfig.USE_RAW_V2 is on, the background full-res decode
            // produces a real full-res RCD result that's already cached in Stage C.
            // The V1-era Save-claim block below would WAIT 90 seconds for a
            // (frequently already-finished) JNI call AND THEN delete the cache it
            // just waited for, forcing a re-decode through legacy V1 with
            // VNG/Linear instead of the user's chosen RAZAmaze. On-device test
            // 2026-05-24 IMG_2744.CR2: Save tap → 33 s wait for already-done
            // decode → 90 s timeout (job-state tracking false positive) → cache
            // wipe → V1 VNG re-decode → ~2 min of wasted work on top of the
            // actual save.
            //
            // With V2 we trust the Stage C cache. If it's missing, renderAndExport
            // falls through to the re-decode path below; if it's present, it reuses
            // it directly.
            if (com.RAZStudio.StudioRoom.feature.photo_editor.BuildConfig.USE_RAW_V2) {
                android.util.Log.i(
                    "RAZ.Save",
                    "Save claim: skipped (USE_RAW_V2=true — V2 RCD cache is trusted as-is)",
                )
            } else runCatching {
                // ── Step 1: WAIT for the background full-res decode to finish ──
                // The background `fullResJob` runs LibRaw at full resolution in
                // a JNI call that can't be cancelled mid-flight (Java's Job.cancel
                // only takes effect at coroutine suspension points, but the JNI
                // call is one giant native block). If we proceed with the save
                // while that decode is still running natively, the save's own
                // decode and the background decode compete for native heap and
                // both stall for minutes.
                //
                // We wait up to 90 seconds for fullResJob to complete — long
                // enough for a 24 MP CR2 to finish decoding on this device,
                // short enough that a stuck pipeline doesn't hold the user
                // forever. After the wait, the on-disk linear.rawbuf is either
                // valid full-res (we reuse it) or half-res (we re-decode).
                val activeFullResJob = fullResJob
                if (activeFullResJob != null && activeFullResJob.isActive) {
                    android.util.Log.i(
                        "RAZ.Save",
                        "Save claim: background full-res decode still active — waiting up to 90s for it to finish",
                    )
                    val joinStart = System.currentTimeMillis()
                    val finishedInTime = kotlinx.coroutines.withTimeoutOrNull(90_000L) {
                        activeFullResJob.join()
                        true
                    } ?: false
                    val joinMs = System.currentTimeMillis() - joinStart
                    if (finishedInTime) {
                        android.util.Log.i(
                            "RAZ.Save",
                            "Save claim: background full-res decode finished after ${joinMs}ms — proceeding",
                        )
                    } else {
                        android.util.Log.w(
                            "RAZ.Save",
                            "Save claim: background full-res decode still running after 90s — proceeding anyway " +
                                "(competing decodes may stall, but blocking longer is worse UX)",
                        )
                    }
                }
                fullResJob = null
                _isFullResProcessing.value = false

                // ── BIT_16 preservation ─────────────────────────────────────────
                // When the workspace is BIT_16, the FP16 Stage C cache is the
                // authoritative source for renderAndExport's fast path
                // (loadFp16CacheBitmap → MacroProcessor.apply on FP16 → encode).
                // Wiping it here forces a 2.5-minute re-decode-from-RAW (observed
                // adb 2026-05-25). Preserve the FP16 + gamut files; only delete
                // the stub workspace_full_base.png and any prior export artifact.
                val saveCfg = currentWorkspaceConfig
                val keepFp16 =
                    saveCfg.bitDepth == com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth.BIT_16
                android.util.Log.i(
                    "RAZ.Save",
                    "Save claim: clearing Stage C cache (keepFp16=$keepFp16)",
                )
                val sha = latestPreviewReady?.metadata?.fileSha256
                if (sha != null) {
                    val toDelete = if (keepFp16) {
                        // BIT_16: keep .fp16 + .gamut; the .png is a stub anyway.
                        listOf("workspace_export.png")
                    } else {
                        listOf(
                            "workspace_full_base.png",
                            "workspace_full_base.fp16",
                            "workspace_full_base.gamut",
                            "workspace_export.png",
                        )
                    }
                    toDelete.forEach { name ->
                        runCatching {
                            cache.stageFile(sha, RawStageCache.Stage.C_FULLRES, name).delete()
                        }
                    }
                }
                if (!keepFp16) {
                    // BIT_8: legacy behavior — renderAndExport falls through to
                    // the re-decode path because there's nothing to read.
                    fullResOutputPath = null
                    _fullResOutputPathFlow.value = null
                }
                // Reclaim heap (recycles long-lived bitmaps + paired GC).
                // freeMemoryForSave (not freeMemoryForBatch) so we keep the
                // FP16 cache + canvas bitmaps alive when keepFp16=true.
                if (keepFp16) freeMemoryForSave() else freeMemoryForBatch()
            }.onFailure { e ->
                android.util.Log.w("RAZ.Save", "Save claim: cleanup failed (non-fatal): ${e.message}")
            }

            try {
                val macro = currentMacro
                val srcPath: String? = renderAndExport(macro)
                if (srcPath == null) {
                    android.util.Log.w("RAZ.Save", "exportToGallery aborted: renderAndExport returned null")
                    return@withContext false
                }

                val srcFile = java.io.File(srcPath)
                if (!srcFile.exists()) {
                    android.util.Log.w("RAZ.Save", "exportToGallery aborted: srcFile missing at $srcPath")
                    return@withContext false
                }

                // ── Filename from settings ────────────────────────────────────
                val ext = format.extension
                val displayName = buildRawExportFilename(settings, sourceOriginalName, ext)

                // ── Decode and scale ──────────────────────────────────────────
                suspend fun loadAndScale(): Bitmap? {
                    var bmp = android.graphics.BitmapFactory.decodeFile(srcPath) ?: return null
                    val srcW = bmp.width; val srcH = bmp.height
                    val tw = if (targetWidth > 0) targetWidth else bmp.width
                    val th = if (targetHeight > 0) targetHeight else bmp.height
                    android.util.Log.i(
                        "RAZ.Save",
                        "loadAndScale: src=${srcW}x${srcH} requested=${tw}x${th} (targetW=$targetWidth targetH=$targetHeight)",
                    )
                    if (tw != bmp.width || th != bmp.height) {
                        val scaled = if (imageScaler != null) {
                            android.util.Log.i("RAZ.Save", "loadAndScale: scaling via imageScaler (mode=$scaleMode)")
                            imageScaler.scaleImage(
                                image = bmp,
                                width = tw,
                                height = th,
                                resizeType = com.RAZStudio.StudioRoom.core.domain.image.model.ResizeType.Explicit,
                                imageScaleMode = scaleMode,
                                resizeSharpen = resizeSharpen,
                            )
                        } else {
                            android.util.Log.i("RAZ.Save", "loadAndScale: scaling via createScaledBitmap (no imageScaler)")
                            android.graphics.Bitmap.createScaledBitmap(bmp, tw, th, true)
                        }
                        if (scaled !== bmp) bmp.recycle()
                        bmp = scaled
                        android.util.Log.i(
                            "RAZ.Save",
                            "loadAndScale: scaled result=${bmp.width}x${bmp.height} (requested=${tw}x${th})",
                        )
                    } else {
                        android.util.Log.i("RAZ.Save", "loadAndScale: no scaling needed (src already matches target)")
                    }
                    return bmp
                }

                val qualityValue = quality.qualityValue

                val compressFormat = when (format) {
                    com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.JPG  -> android.graphics.Bitmap.CompressFormat.JPEG
                    com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.WEBP ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                            android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
                        else
                            @Suppress("DEPRECATION") android.graphics.Bitmap.CompressFormat.WEBP
                    else -> android.graphics.Bitmap.CompressFormat.PNG
                }

                // PNG-16 and TIFF take the encoder route — they share the rest of
                // the export pipeline (filename, EXIF, MediaStore copy) but
                // produce their own bytes via Png16Writer / Tiff16Writer.
                val is16BitFormat =
                    format == com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.PNG_16 ||
                    format == com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.TIFF

                // ── Resolve output folder from settings ───────────────────────
                // saveFolderUri null  → default "Pictures/RAZStudio"
                // saveFolderUri set   → write into that DocumentTree folder
                val customFolderUri = settings?.saveFolderUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { android.net.Uri.parse(it) }

                // ── Exif: skip date/time tags when keepDateTime = false ───────
                val keepDateTime = settings?.keepDateTime ?: true

                // ── Stage 1: compress to a temp file and embed EXIF ──────────────────
                // We can't compress straight to the destination URI because
                // ExifInterface.saveAttributes() needs a writable file path. So we
                // compress + embed EXIF into a cache file once, then move the bytes
                // into the destination (MediaStore / SAF / public dir) using
                // FileChannel.transferTo — kernel-level copy, no userspace buffer.
                val tmpFile = java.io.File(context.cacheDir, "export_tmp_${System.currentTimeMillis()}.$ext")
                val tCompressStart = System.currentTimeMillis()
                java.io.BufferedOutputStream(tmpFile.outputStream(), 64 * 1024).use { out ->
                    val bmp = loadAndScale() ?: run {
                        android.util.Log.w("RAZ.Save", "loadAndScale returned null; aborting")
                        return@withContext false
                    }
                    if (is16BitFormat) {
                        // ── 16-bit encoder route ─────────────────────────────
                        // Pull RGB samples from the bitmap and emit through the
                        // pure-Kotlin Png16Writer / Tiff16Writer. The samples
                        // are upsampled from 8-bit when the source bitmap is
                        // ARGB_8888 (`v16 = v8 * 257` maps [0,255] → [0,65535]
                        // with no quantization gaps); samples come directly
                        // from a 16-bit FP source on the precision-preserving
                        // Stage C path.
                        val rgb16 = com.RAZStudio.StudioRoom.feature.photo_editor.raw.export
                            .Bitmap16Sampler.extractRgb16(bmp)
                        val encoded = when (format) {
                            com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.PNG_16 ->
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Png16Writer
                                    .encodeRgb16(rgb16, bmp.width, bmp.height)
                            com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.TIFF ->
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Tiff16Writer
                                    .encodeRgb16(rgb16, bmp.width, bmp.height, iccProfile = null)
                            else -> error("unreachable: is16BitFormat true but format=$format")
                        }
                        out.write(encoded)
                    } else {
                        // HEIC/BMP/JPEG2000 must go through the domain ImageCompressor —
                        // Bitmap.CompressFormat has no entries for these formats and the
                        // PNG fallback would write PNG bytes into a `.heic`/`.bmp`/`.jp2`
                        // file. When no compressor is supplied (e.g. batch path), fall
                        // back to PNG so the file at least isn't corrupt for JPG/WEBP.
                        val domainFormat: com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat? =
                            when (format) {
                                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.HEIC ->
                                    com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat.Heic.Lossy
                                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.BMP ->
                                    com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat.Bmp
                                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.JPEG2000 ->
                                    com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat.Jpeg2000.Jp2
                                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.AVIF ->
                                    com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat.Avif.Lossy
                                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.JXL ->
                                    com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat.Jxl.Lossy
                                else -> null
                            }
                        if (domainFormat != null && imageCompressor != null) {
                            val bytes = imageCompressor.compress(
                                image = bmp,
                                imageFormat = domainFormat,
                                quality = com.RAZStudio.StudioRoom.core.domain.image.model.Quality.Base(qualityValue),
                            )
                            out.write(bytes)
                        } else {
                            bmp.compress(compressFormat, qualityValue, out)
                        }
                    }
                    bmp.recycle()
                }
                val compressMs = System.currentTimeMillis() - tCompressStart
                val tExifStart = System.currentTimeMillis()
                if (saveExif) {
                    embedExifToFile(
                        tmpFile.absolutePath,
                        keepDateTime = keepDateTime,
                        keepGps = saveGps,
                        stripSensitive = stripSensitive,
                    )
                } else {
                    // RAZStudio Software tag must be written for every export
                    // regardless of EXIF policy.
                    embedExifSoftwareOnlyToFile(tmpFile.absolutePath)
                }
                // ICC profile is currently embedded inside the encoder paths (PNG
                // via IccProfileWriter and TIFF via the iccProfile parameter). For
                // PNG legacy path the call is at line 1015 in prepareExportNavigation;
                // strip-after-the-fact for the gallery export means deleting the
                // iCCP chunk if saveIcc is false. We re-decode and re-encode bypassing
                // ICC for that case.
                if (!saveIcc && (format ==
                        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.PNG ||
                    format ==
                        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat.PNG_16)
                ) {
                    runCatching {
                        val raw = tmpFile.readBytes()
                        val stripped = stripPngChunkType(raw, "iCCP") ?: raw
                        if (stripped !== raw) tmpFile.writeBytes(stripped)
                    }
                }
                val exifMs = System.currentTimeMillis() - tExifStart
                val tmpBytes = tmpFile.length()

                try {
                    val tCopyStart = System.currentTimeMillis()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && customFolderUri == null) {
                        // ── MediaStore path (default folder, API 29+) ───────────────────
                        // Apply filename collision policy. MediaStore otherwise
                        // auto-appends " (1)" — we either delete the existing row
                        // (Overwrite) or pre-suffix the name (AddDateSuffix).
                        val mediaDisplayName = runCatching {
                            val resolver = context.contentResolver
                            val proj = arrayOf(android.provider.MediaStore.Images.Media._ID)
                            val sel = "${android.provider.MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                                "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                            val args = arrayOf(displayName, "Pictures/RAZStudio/%")
                            resolver.query(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                proj, sel, args, null,
                            )?.use { c ->
                                if (c.moveToFirst()) {
                                    val id = c.getLong(0)
                                    when (collisionPolicy) {
                                        RawBatchProcessor.FilenameCollisionPolicy.Overwrite -> {
                                            val existingUri = android.content.ContentUris.withAppendedId(
                                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
                                            )
                                            runCatching { resolver.delete(existingUri, null, null) }
                                            displayName
                                        }
                                        RawBatchProcessor.FilenameCollisionPolicy.AddDateSuffix ->
                                            appendDateSuffix(displayName)
                                    }
                                } else displayName
                            } ?: displayName
                        }.getOrDefault(displayName)
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, mediaDisplayName)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RAZStudio")
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                        ) ?: run {
                            android.util.Log.e("RAZ.Save", "MediaStore.insert returned null")
                            return@withContext false
                        }
                        transferTmpToUri(context, tmpFile, uri)
                        values.clear()
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                        val copyMs = System.currentTimeMillis() - tCopyStart
                        android.util.Log.d(
                            "RAZ.Save",
                            "MediaStore write OK uri=$uri bytes=$tmpBytes " +
                                "compress=${compressMs}ms exif=${exifMs}ms copy=${copyMs}ms",
                        )
                    } else if (customFolderUri != null) {
                        // ── Custom DocumentTree folder from settings ────────────────────
                        val treeDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, customFolderUri)
                            ?: run {
                                android.util.Log.e("RAZ.Save", "fromTreeUri returned null for $customFolderUri")
                                return@withContext false
                            }
                        // Apply filename collision policy: AddDateSuffix appends
                        // _yyyyMMdd_HHmmss when a same-named file exists; Overwrite
                        // deletes the existing one first.
                        val existing = treeDoc.findFile(displayName)
                        val finalDisplayName = if (existing != null) {
                            when (collisionPolicy) {
                                RawBatchProcessor.FilenameCollisionPolicy.Overwrite -> {
                                    existing.delete()
                                    displayName
                                }
                                RawBatchProcessor.FilenameCollisionPolicy.AddDateSuffix -> {
                                    appendDateSuffix(displayName)
                                }
                            }
                        } else displayName
                        val newFile = treeDoc.createFile(format.mimeType, finalDisplayName.substringBeforeLast('.'))
                            ?: run {
                                android.util.Log.e("RAZ.Save", "createFile returned null in $customFolderUri")
                                return@withContext false
                            }
                        transferTmpToUri(context, tmpFile, newFile.uri)
                        val copyMs = System.currentTimeMillis() - tCopyStart
                        android.util.Log.d(
                            "RAZ.Save",
                            "SAF write OK uri=${newFile.uri} bytes=$tmpBytes " +
                                "compress=${compressMs}ms exif=${exifMs}ms copy=${copyMs}ms",
                        )
                    } else {
                        // ── Legacy public-dir path (pre-API 29, no custom folder) ───────
                        val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES,
                        )
                        val outDir = java.io.File(picturesDir, "RAZStudio").also { it.mkdirs() }
                        val candidate = java.io.File(outDir, displayName)
                        val outFile = if (candidate.exists()) {
                            when (collisionPolicy) {
                                RawBatchProcessor.FilenameCollisionPolicy.Overwrite -> candidate.also { it.delete() }
                                RawBatchProcessor.FilenameCollisionPolicy.AddDateSuffix ->
                                    java.io.File(outDir, appendDateSuffix(displayName))
                            }
                        } else candidate
                        // File-to-file: FileChannel.transferTo is the kernel-level zero-copy
                        // path on every Android filesystem we care about.
                        java.io.FileInputStream(tmpFile).channel.use { src ->
                            java.io.FileOutputStream(outFile).channel.use { dst ->
                                var transferred = 0L
                                val total = src.size()
                                while (transferred < total) {
                                    transferred += src.transferTo(transferred, total - transferred, dst)
                                }
                            }
                        }
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
                        val copyMs = System.currentTimeMillis() - tCopyStart
                        android.util.Log.d(
                            "RAZ.Save",
                            "Legacy write OK path=${outFile.absolutePath} bytes=$tmpBytes " +
                                "compress=${compressMs}ms exif=${exifMs}ms copy=${copyMs}ms",
                        )
                    }
                } finally {
                    tmpFile.delete()
                }

                val totalMs = System.currentTimeMillis() - saveStartMs
                android.util.Log.d("RAZ.Save", "exportToGallery success total=${totalMs}ms")
                true
            } catch (e: Exception) {
                val totalMs = System.currentTimeMillis() - saveStartMs
                android.util.Log.e("RAZ.Save", "exportToGallery FAILED after ${totalMs}ms: ${e.message}", e)
                false
            }
        }

    /** Insert `_yyyyMMdd_HHmmss` between the basename and the extension. */
    private fun appendDateSuffix(name: String): String {
        val ext = name.substringAfterLast('.', "")
        val base = if (ext.isNotEmpty()) name.substringBeforeLast('.') else name
        val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val stamp = fmt.format(java.util.Date())
        return if (ext.isNotEmpty()) "${base}_$stamp.$ext" else "${base}_$stamp"
    }

    private fun buildRawExportFilename(
        settings: com.RAZStudio.StudioRoom.core.settings.domain.model.SettingsState?,
        originalName: String?,
        ext: String,
    ): String {
        if (settings == null) return "RAWEdit_${System.currentTimeMillis()}.$ext"
        val prefix = settings.filenamePrefix.ifBlank { "RAWEdit_" }
        val suffix = settings.filenameSuffix
        val baseName = if (settings.addOriginalFilename && !originalName.isNullOrBlank()) {
            originalName.substringBeforeLast('.')
        } else ""
        val timestamp = if (settings.addTimestampToFilename) {
            if (settings.useFormattedFilenameTimestamp) {
                "_${com.RAZStudio.StudioRoom.core.domain.utils.timestamp()}"
            } else {
                "_${System.currentTimeMillis()}"
            }
        } else "_${System.currentTimeMillis()}"
        return buildString {
            append(prefix)
            if (baseName.isNotEmpty()) append(baseName)
            append(timestamp)
            if (suffix.isNotEmpty()) append("_$suffix")
            append(".$ext")
        }
    }

    /**
     * Parsed LUTs are cached by URI string. A 33³ cube file is ~360 KB of text and
     * takes ~50–100 ms to parse + tokenize on a mid-range phone. The macro re-render
     * loop walks the entire LUT chain on every slider tick, so without this cache
     * each tick paid that parse cost for every committed LUT in the stack — the
     * primary reason multi-LUT editing felt sluggish.
     *
     * Bounded to 8 entries (typical stacks are 1–3 LUTs; presets may bring 5–6).
     * Roughly 8 × ~150 KB = 1.2 MB resident.
     */
    private val parsedLutCache = object : LinkedHashMap<String, Pair<FloatArray, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<FloatArray, Int>>?): Boolean =
            size > 8
    }
    private val parsedLutCacheLock = Any()

    private fun parseCubeLut(uriString: String): Pair<FloatArray, Int>? {
        synchronized(parsedLutCacheLock) {
            parsedLutCache[uriString]?.let { return it }
        }
        val parsed = parseCubeLutUncached(uriString) ?: return null
        synchronized(parsedLutCacheLock) {
            parsedLutCache[uriString] = parsed
        }
        return parsed
    }

    private fun parseCubeLutUncached(uriString: String): Pair<FloatArray, Int>? = runCatching {
        val uri = android.net.Uri.parse(uriString)
        val stream = when {
            uri.scheme == "file" -> {
                // Prefer the encoded path decoded by Uri; fall back to treating the whole
                // string as a plain absolute path so saved presets that store a bare path work.
                val filePath = uri.path ?: uriString
                java.io.File(filePath).inputStream()
            }
            uri.scheme == null || uri.scheme == "" -> java.io.File(uriString).inputStream()
            else -> context.contentResolver.openInputStream(uri) ?: return@runCatching null
        }
        var size = 33; val vals = mutableListOf<Float>()
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.trim()
                when {
                    t.isBlank() || t.startsWith("#") -> Unit
                    t.startsWith("LUT_3D_SIZE") -> size = t.substringAfterLast(' ').toIntOrNull() ?: size
                    t.startsWith("TITLE") || t.startsWith("DOMAIN") -> Unit
                    else -> t.split(Regex("\\s+")).take(3).forEach { vals.add(it.toFloatOrNull() ?: 0f) }
                }
            }
        }
        if (vals.size < size * size * size * 3) return@runCatching null
        vals.toFloatArray() to size
    }.getOrNull()

    /**
     * Move bytes from [tmpFile] to a content URI ([dstUri]) using kernel-level
     * zero-copy where possible. `FileChannel.transferTo` to a `WritableByteChannel`
     * adapter avoids the per-iteration userspace byte buffer that
     * `inputStream.copyTo(outputStream)` allocates. The destination is wrapped in a
     * 64 KB BufferedOutputStream so the MediaStore/SAF backing layer sees fewer,
     * larger writes.
     */
    private fun transferTmpToUri(context: Context, tmpFile: java.io.File, dstUri: android.net.Uri) {
        val rawOut = context.contentResolver.openOutputStream(dstUri)
            ?: error("openOutputStream returned null for $dstUri")
        java.io.BufferedOutputStream(rawOut, 64 * 1024).use { buffered ->
            java.nio.channels.Channels.newChannel(buffered).use { dstChannel ->
                java.io.FileInputStream(tmpFile).channel.use { src ->
                    var transferred = 0L
                    val total = src.size()
                    while (transferred < total) {
                        transferred += src.transferTo(transferred, total - transferred, dstChannel)
                    }
                }
            }
        }
    }

    /**
     * Remove every chunk of the given [chunkType] from a PNG byte array.
     * Returns the cleaned bytes, or null if [data] is not a valid PNG (signature
     * missing). Used to strip iCCP / sRGB chunks when the user opts out of ICC
     * profile embedding.
     */
    private fun stripPngChunkType(data: ByteArray, chunkType: String): ByteArray? {
        if (data.size < 8 ||
            data[0] != 0x89.toByte() || data[1] != 0x50.toByte() ||
            data[2] != 0x4E.toByte() || data[3] != 0x47.toByte()
        ) return null
        val type = chunkType.toByteArray(Charsets.US_ASCII)
        if (type.size != 4) return null
        val out = java.io.ByteArrayOutputStream(data.size)
        out.write(data, 0, 8)                  // PNG signature
        var i = 8
        while (i + 8 <= data.size) {
            val length = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            val chunkLen = 4 + 4 + length + 4  // length + type + data + CRC
            if (i + chunkLen > data.size) break
            val matches = data[i + 4] == type[0] && data[i + 5] == type[1] &&
                data[i + 6] == type[2] && data[i + 7] == type[3]
            if (!matches) out.write(data, i, chunkLen)
            i += chunkLen
        }
        return out.toByteArray()
    }
}
