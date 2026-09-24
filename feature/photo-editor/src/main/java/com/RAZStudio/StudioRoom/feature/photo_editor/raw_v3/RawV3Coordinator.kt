/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — single-file Coordinator (M11).
 *
 *  Plan.md acceptance: "Batch processor uses RawV3Coordinator instead of
 *  the old one. Batch run from a folder works end-to-end."
 *
 *  This file defines the per-file entry point the v2 batch processor
 *  (RawBatchProcessor) and any future surface will call:
 *
 *      val result = RawV3Coordinator(context).exportRawToGallery(
 *          rawUri = uri,
 *          options = ExportOptions(...),
 *          onStage = { stage -> /* progress */ },
 *      )
 *
 *  Internally:
 *    1. Copy rawUri → app scratch file (skipped when this coordinator
 *       already has the same URI open and A.tif is still valid).
 *    2. SHA-256 + EXIF probe — Adobe-Enhanced / LinearRaw triggers the
 *       LibRaw `dcraw_process` bypass instead of RCD.
 *    3. Stage A (LibRaw / RCD) → cacheDir/raw_v3/<sha>/A.tif.
 *    4. Stage C (NDK row-streaming kernel) → intermediate BigTIFF.
 *    5. Decode BigTIFF → ARGB_8888 Bitmap (RawV3BigTiffReader).
 *    6. AndroidImageCompressor: scale + encode to the picked format.
 *    7. FileController.save(...) — honours the user's Settings save folder
 *       and filename pattern (or oneTimeSaveLocationUri if provided).
 *    8. Per-file cleanup: scratch + Stage C intermediate TIFF. Keep A.tif so
 *       a second save of the same photo does not re-run LibRaw.
 *
 *  We use [RawV3HiltAccess] to pull FileController + ImageCompressor from
 *  the application Hilt graph without requiring the caller to be Hilt-
 *  injected — same pattern the debug surface uses.
 *
 *  Cancellation: every long-running stage checks coroutine cancellation
 *  via [coroutineContext.ensureActive], so cancelling the calling scope
 *  stops the next stage cleanly.
 *
 *  M1 skeleton (`requestWorkspaceChoice` / `openRawFile` / `apply` /
 *  `cancel` / `close`) is retained for source compatibility with anything
 *  the prior milestones might have referenced. They still throw
 *  [NotImplementedError]; the production surface lives entirely in the
 *  M11 [exportRawToGallery] entry point until M12 rewires the editor.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.media.ExifInterface
import com.RAZStudio.StudioRoom.core.domain.image.Metadata
import com.RAZStudio.StudioRoom.core.domain.image.clearAttributes
import com.RAZStudio.StudioRoom.core.domain.image.metadataOf
import com.RAZStudio.StudioRoom.core.domain.image.readOnly
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat
import com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageInfo
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageScaleMode
import com.RAZStudio.StudioRoom.core.domain.image.model.Quality
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeSharpen as DomainResizeSharpen
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeType
import com.RAZStudio.StudioRoom.core.domain.saving.model.ImageSaveTarget
import com.RAZStudio.StudioRoom.core.domain.saving.model.SaveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class RawV3Coordinator(private val context: Context) {

    // ─── State + session lifecycle (M12.1) ──────────────────────────────────

    private val _state = MutableStateFlow<RawV3State>(RawV3State.Idle)
    val state: StateFlow<RawV3State> = _state.asStateFlow()

    /** U2Net segmentation masks for the currently-open RAW. Null when the
     *  user didn't enable subject detection, when inference is still in
     *  flight, or when the model failed to load. Updates exactly once per
     *  file open. M12.2a — wired into the editor's Mask/Vignette/Gradient
     *  tabs at M12.2b. */
    private val _segmentationMasks = MutableStateFlow<RawV3SegmentationMasks?>(null)
    val segmentationMasks: StateFlow<RawV3SegmentationMasks?> = _segmentationMasks.asStateFlow()

    /**
     * Relative depth from Depth-Anything-V2-Small (Apache-2.0). Null when the
     * onnx asset is absent, inference failed, or session closed. Independent
     * of [segmentationMasks] so subject protect and CoC can compose later.
     */
    private val _depthMap = MutableStateFlow<RawV3DepthMap?>(null)
    val depthMap: StateFlow<RawV3DepthMap?> = _depthMap.asStateFlow()

    /**
     * True from the moment [ensureSegmentation] launches the chain until the
     * whole job finishes (success OR failure). The editor uses this to grey out
     * subject-mask-dependent controls (Bokeh, subject/background Vignette &
     * Gradient) while detection runs — a control is enabled once
     * `segmentationMasks != null` (subject mask, incl. DeepLab fallback, ready)
     * OR the job has ended (so a total failure re-enables it to no-op rather
     * than trapping the user). Reset on open/close.
     */
    private val _segmentationRunning = MutableStateFlow(false)
    val segmentationRunning: StateFlow<Boolean> = _segmentationRunning.asStateFlow()

    /**
     * Per-class masks from MediaPipe selfie-multiclass (Hair, BodySkin,
     * FaceSkin, Clothes, Accessories). Null when the model isn't
     * present or inference hasn't finished. Runs alongside the U2Net
     * pass on Stage A finish; cheap (~50-100 ms on a mid-range GPU).
     */
    private val _multiclassMasks = MutableStateFlow<RawV3MulticlassMasks?>(null)
    val multiclassMasks: StateFlow<RawV3MulticlassMasks?> = _multiclassMasks.asStateFlow()

    /** True while MediaPipe multiclass inference is running; false once done or model unavailable. */
    private val _multiclassLoading = MutableStateFlow(false)
    val multiclassLoading: StateFlow<Boolean> = _multiclassLoading.asStateFlow()

    /** Lazy MediaPipe segmenter. Released on session close. */
    private var multiclassSegmenter:
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.SelfieMulticlassSegmenter? = null

    /**
     * Face-detection mask (Qualcomm Lightweight-Face-Detection ONNX). Fills
     * full elliptical face bounding boxes — complementary to MediaPipe's
     * FaceSkin which is just the skin region. Useful when the user wants
     * "select the whole face including eyes/mouth/hair-overhang" in one tap.
     * Null until inference finishes (~200-500 ms after file open).
     */
    private val _faceMask = MutableStateFlow<FloatArray?>(null)
    val faceMask: StateFlow<FloatArray?> = _faceMask.asStateFlow()

    /** Lazy face detector. Released on session close. */
    private var faceDetector: RawV3FaceDetector? = null

    /**
     * Cityscapes 4-class semantic masks (Building+Wall / Vegetation /
     * Terrain / Sky) from a remapped SegFormer-B1 ONNX. Powers landscape
     * masking. Null until inference finishes or when the model isn't
     * bundled (assets/models/segformer_cityscapes_remap_fp16.onnx missing).
     */
    private val _cityscapesMasks = MutableStateFlow<RawV3CityscapesMasks?>(null)
    val cityscapesMasks: StateFlow<RawV3CityscapesMasks?> = _cityscapesMasks.asStateFlow()

    /** True while cityscapes inference is in-progress; false once done OR model unavailable. */
    private val _cityscapesLoading = MutableStateFlow(false)
    val cityscapesLoading: StateFlow<Boolean> = _cityscapesLoading.asStateFlow()

    /** Lazy Cityscapes detector. Released on session close. */
    private var cityscapesDetector: RawV3CityscapesDetector? = null

    /**
     * DeepLabV3+ ResNet50 human-parsing masks (20 LIP classes, grouped into
     * face / hair / upperBody / lowerBody / arms / legs / shoes / accessories).
     * Null until inference finishes or when the model isn't bundled.
     */
    private val _deepLabMasks = MutableStateFlow<RawV3DeepLabMasks?>(null)
    val deepLabMasks: StateFlow<RawV3DeepLabMasks?> = _deepLabMasks.asStateFlow()

    /** Lazy DeepLab processor. Released on session close. */
    private var deepLabProcessor: RawV3DeepLabProcessor? = null

    /**
     * Zero-DCE A-map mean magnitude (scene low-light score). Higher value =
     * darker scene that the model would lift more. Auto Expo reads this to
     * push `claheHighlightsBoost` one step further negative when channel
     * clipping AND a low-light score coincide — the "bright bulb in a dim
     * room" case where users want highlights pulled back harder than the
     * baseline AE would do. Null until probe finishes; safe to call AE
     * before the probe lands (AE just skips the extra lift).
     */
    private val _zeroDceLightScore = MutableStateFlow<Float?>(null)
    val zeroDceLightScore: StateFlow<Float?> = _zeroDceLightScore.asStateFlow()

    /** Lazy Zero-DCE light probe. Released on session close. */
    private var zeroDceLightProbe: RawV3ZeroDceLightProbe? = null

    /**
     * Standalone, eager Zero-DCE low-light probe for **AI Color Enhance**
     * (instant global tier at open). Returns the mean A-map lift (higher =
     * darker scene) or null when the model is missing / inference fails.
     *
     * Uses a DEDICATED short-lived probe instance (created + released here) so
     * it never races the lazy segmentation-chain probe ([zeroDceLightProbe]),
     * which has its own unload lifecycle. Zero-DCE is tiny (~312 KB, ~0.5 s) so
     * running it eagerly at open costs little and does not reintroduce the
     * segmentation OOM pressure the lazy path was built to avoid.
     */
    suspend fun probeLowLight(bitmap: Bitmap): Float? {
        val probe = RawV3ZeroDceLightProbe(context)
        return try {
            probe.probeAverageLift(bitmap)
        } finally {
            probe.release()
        }
    }

    /** Latest Stage A result, surfaced so the editor can build a
     *  RawMetadata for the Export screen's EXIF table. Cleared on
     *  session close. */
    private val _stageAResult = MutableStateFlow<RawV3Engine.StageAResult?>(null)
    val stageAResult: StateFlow<RawV3Engine.StageAResult?> = _stageAResult.asStateFlow()

    /** Lazily-created ONNX segmentation processor. One session per
     *  coordinator (i.e. one per editor surface); the 168 MB OrtSession
     *  init runs on first compute call. Released on [closeSession]. */
    private var segmentationProcessor: RawV3SegmentationProcessor? = null
    /** Depth-Anything-V2-Small OrtSession; released on [closeSession]. */
    private var depthProcessor: RawV3DepthProcessor? = null
    /**
     * General-saliency FALLBACK subject model (U2Net, 320² input, ~1 s, ~170 MB
     * session). Used whenever BiRefNet is skipped (memory gate) or fails/times
     * out, BEFORE the DeepLab person-class union. Device evidence 2026-09-04:
     * on the 12 GB Infinix BiRefNet was skipped at 3.4 GB free and timed out
     * (103 s) when it did run, so EVERY subject mask came from the person-only
     * DeepLab union — blank for any non-person subject, which made Bokeh blur
     * the whole frame and Bloom "Protect subject" protect nothing.
     */
    private var u2netFallbackProcessor:
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationProcessor? = null

    /** Active segmentation job — cancelled when the user opens a different
     *  file before inference finished. */
    private var segmentationJob: kotlinx.coroutines.Job? = null

    // Inputs captured at open so the segmentation chain can run LATER, on
    // demand (see [ensureSegmentation]) instead of eagerly on every open.
    private var segSha: String? = null
    private var segStageATif: java.io.File? = null
    private var segSourceName: String = ""
    @Volatile private var segLaunched = false

    /**
     * Sequential Model Manager policy. The import segmentation pass loads five
     * model sessions (U2Net ~168 MB, SegFormer, selfie-multiclass, face-detect,
     * Zero-DCE) one after another. Without lifecycle management all five stay
     * resident until [closeSession] (next-file-open / editor exit), so peak RAM
     * ≈ the SUM of all sessions on top of the FP16 buffers — the OOM exposure.
     *
     *  • [ModelLifecycle.UNLOAD_PER_PASS] (default, editor single-file): release
     *    each session the instant its pass finishes, so peak RAM ≈ the LARGEST
     *    single model, not the sum.
     *  • [ModelLifecycle.KEEP_RESIDENT]: keep sessions cached across files to
     *    avoid per-file reload cost. Forward-hook for a future batch path that
     *    runs these passes per file — today only the editor-open path does, so
     *    this branch is currently unused but kept so the policy is explicit.
     *
     * [segModelLock] guards the field release/null so the per-pass unload (on
     * the segmentation dispatcher) can't double-free against a concurrent
     * [closeSession] (on the caller thread).
     */
    enum class ModelLifecycle { UNLOAD_PER_PASS, KEEP_RESIDENT }
    @Volatile var modelLifecycle: ModelLifecycle = ModelLifecycle.UNLOAD_PER_PASS
    private val segModelLock = Any()

    /** Release a just-finished model session when the editor policy is in
     *  effect. No-op under KEEP_RESIDENT (sessions linger for reuse). */
    private fun unloadAfterPass(label: String, release: () -> Unit) {
        if (modelLifecycle != ModelLifecycle.UNLOAD_PER_PASS) return
        synchronized(segModelLock) { release() }
        Log.d(TAG, "model-manager: unloaded $label after its pass (peak-RAM policy)")
    }

    /**
     * True when the device has enough free RAM to run BiRefNet's fixed 1024²
     * subject-mask pass, which peaks ~6 GB of ONNX activations. On RAM-tight
     * devices (or when memory is already low) it returns false so the coordinator
     * skips BiRefNet and uses the much lighter DeepLab person mask as the subject
     * fallback — avoiding the lmkd OOM-kill. Measured right before the pass so it
     * reflects the current headroom (after Stage A decode).
     */
    private fun hasMemoryForBiRefNet(relaxForPortrait: Boolean = false): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return false
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        // ~4 GB available headroom required for the ~6 GB spike (some from zram),
        // and never when the system is already flagging low memory.
        // Portraits: BiRefNet-lite is our best person matte — allow ~3.2 GB when
        // a face was already detected on the cheap probe (still refuse lowMemory).
        val minAvailBytes = if (relaxForPortrait) 3200L * 1024 * 1024 else 4L * 1024 * 1024 * 1024
        // ALSO require Java-heap headroom. BiRefNet makes large on-heap
        // allocations (~224 MB tensor/bitmap) that must fit under the ART growth
        // limit (~512 MB), which system availMem is blind to. In batch the heap is
        // already full across files, so a 224 MB alloc threw OutOfMemoryError even
        // with 5.5 GB system RAM free — gate on free Java heap so batch quietly
        // falls back to U2Net / DeepLab while the editor (emptier heap) still gets
        // BiRefNet.
        val rt = Runtime.getRuntime()
        val javaHeapFree = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val minJavaHeapFree = if (relaxForPortrait) 280L * 1024 * 1024 else 340L * 1024 * 1024
        val ok = !am.isLowRamDevice && !mi.lowMemory &&
            mi.availMem >= minAvailBytes && javaHeapFree >= minJavaHeapFree
        Log.i(TAG, "hasMemoryForBiRefNet=$ok portraitRelax=$relaxForPortrait " +
            "(availMem=${mi.availMem / (1024 * 1024)}MB " +
            "javaHeapFree=${javaHeapFree / (1024 * 1024)}MB " +
            "lowMemory=${mi.lowMemory} lowRamDevice=${am.isLowRamDevice})")
        return ok
    }

    /**
     * Cheap face count on an already-decoded preview bitmap. Used only as a
     * routing hint for BiRefNet's RAM gate — never as the subject matte itself.
     * Returns 0 when the face model is missing or detection fails.
     */
    private suspend fun quickFaceCount(bmp: android.graphics.Bitmap, sourceName: String): Int {
        val fd = faceDetector ?: RawV3FaceDetector(context).also { faceDetector = it }
        if (!fd.hasModel) return 0
        return runCatching {
            val mask = fd.compute(bmp) ?: return@runCatching 0
            // Coverage proxy: each face blob contributes; count pixels above 0.5
            // / typical face area (~3% of 320²) as a rough face count.
            var n = 0
            for (v in mask) if (v > 0.5f) n++
            val faces = (n / (RawV3SegmentationMasks.MASK_SIZE * RawV3SegmentationMasks.MASK_SIZE * 0.03f))
                .toInt().coerceAtLeast(if (n > 0) 1 else 0)
            Log.i(TAG, "$sourceName: face probe → ~$faces face(s) (fgPx=$n)")
            faces
        }.onFailure { Log.w(TAG, "$sourceName: face probe failed: ${it.message}") }
            .getOrDefault(0)
    }

    /** Path to the currently-open Stage A TIFF. Drives the preview Composable
     *  + the Apply→Export path. Null when no session is open. */
    private var openStageAPath: String? = null

    /** SHA-256 of the source RAW for the current session — used by Stage C
     *  cache lookups and the M10 session-survival action state. */
    private var openSha: String? = null

    /** SAF URI passed to [openRawFile]; export skips copy+SHA when it matches. */
    private var openUri: android.net.Uri? = null

    private var openNonRaw: Boolean = false

    /**
     * Open a RAW source for live editing.
     *
     *  1. Copy SAF stream → app scratch file (LibRaw needs a real path)
     *  2. EXIF probe — Adobe-Enhanced sources force AHD instead of RCD
     *  3. Stage A decode → cacheDir/raw_v3/<sha>/A.tif
     *  4. Emit `RawV3State.StageBReady(sha, stageATifPath, w, h)` — the
     *     editor Composable picks up the path and runs Stage B + the
     *     GLES shader on every uniform tick.
     *
     *  Cancelable by emitting a new value on [_state]. Subsequent calls
     *  replace any in-flight session.
     */
    suspend fun openRawFile(
        rawUri: android.net.Uri,
        workspace: RawV3WorkspaceOptions,
    ) = withContext(Dispatchers.IO) {
        _state.value = RawV3State.StageADecoding(0f)
        val sourceName = rawUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: "(unknown)"

        // Copy SAF → scratch (same trick the batch coordinator uses).
        val scratch = File(context.cacheDir, "raw_v3_open_${System.nanoTime()}.bin")
        runCatching {
            context.contentResolver.openInputStream(rawUri)?.use { ins ->
                FileOutputStream(scratch).use { out -> ins.copyTo(out) }
            } ?: error("openInputStream null")
        }.onFailure { e ->
            scratch.delete()
            _state.value = RawV3State.Failed("copy failed: ${e.message}", e)
            return@withContext
        }
        if (scratch.length() == 0L) {
            scratch.delete()
            _state.value = RawV3State.Failed("empty source after copy")
            return@withContext
        }

        // Extract embedded JPEG thumbnail (~10–30 ms) and show it immediately
        // while the full Stage A decode runs in the background (~15–26 s).
        val thumbnailBitmap = runCatching {
            val jpegBytes = RawV3Engine.extractEmbeddedThumbnail(scratch.absolutePath)
                ?: return@runCatching null
            val raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return@runCatching null
            // Read EXIF orientation from the JPEG bytes and rotate accordingly.
            val thumbFile = File(context.cacheDir, "thumb_exif_${System.nanoTime()}.jpg")
            thumbFile.writeBytes(jpegBytes)
            val orientationInt = runCatching {
                ExifInterface(thumbFile.absolutePath)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.also { thumbFile.delete() }.getOrElse { ExifInterface.ORIENTATION_NORMAL }
            val rotation = when (orientationInt) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else                                 -> 0f
            }
            if (rotation == 0f) raw
            else {
                val m = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    .also { if (it !== raw) raw.recycle() }
            }
        }.getOrNull()
        if (thumbnailBitmap != null) {
            Log.i(TAG, "$sourceName: embedded thumbnail ${thumbnailBitmap.width}×${thumbnailBitmap.height}")
        }
        _state.value = RawV3State.StageADecoding(0f, thumbnailBitmap)

        val sha = sha256(scratch)
        val cache = RawV3Cache(context)
        // ── Film-sim cache key (Task 8.1) ─────────────────────────────────────
        // DEFAULT uses the standard pristine path. Active profiles get an
        // isolated path encoding profile + grain so multiple profiles for the
        // same RAW can coexist and the pristine TIFF is never mutated.
        val linearTif = cache.stageATif(sha)  // pristine linear; always the fallback

        // ── Stage A cache validity ───────────────────────────────────────────
        // A plain reopen of the SAME file with the SAME options is bit-for-bit
        // reproducible, so it should not pay the ~35-40s LibRaw pass again. The
        // pristine tif is trusted ONLY when its recorded options fingerprint
        // (every field RawV3WorkspaceOptions's own doc comment says is "baked
        // into the Stage A TIFF cache") matches exactly what THIS open would
        // decode with — anything else (missing/empty tif, missing/corrupt meta,
        // or a fingerprint mismatch because WB/demosaic/CA/lens/etc. changed)
        // falls straight through to a full re-decode, same as before this cache
        // existed. See CachedStageAMeta.
        // LFA_ALGO_TAG: bump when the native lens-correction MATH changes (not
        // just its inputs) so cached A.tifs baked with the old algorithm are
        // re-decoded once. v2 = generic natural-vignetting fallback for
        // profiles without <vignetting> data; v3 = Rayxie moved AFTER the
        // lens profile (both 2026-09-04).
        val optionsFingerprint = workspace.toString() + "|lfa=3"
        val cachedMeta = readStageAMeta(cache.stageAMeta(sha))
        val stageACacheHit = cachedMeta != null &&
            cachedMeta.optionsFingerprint == optionsFingerprint &&
            linearTif.exists() && linearTif.length() > 0L

        val stageATif: File
        val filmSimActive = workspace.filmProfileIndex != 0
        if (filmSimActive) {
            val profileId = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmProfile
                .entries.getOrNull(workspace.filmProfileIndex)?.id ?: "default"
            val grainId = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmGrainLevel
                .entries.find { it.value == workspace.filmGrainAmount }?.id ?: "off"
            stageATif = File(linearTif.parentFile, "stage_a_${profileId}_g${grainId}_${sha}.tif")
        } else {
            stageATif = linearTif
        }

        // Non-RAW (JPEG/PNG/WebP/…) sources have no Bayer mosaic — LibRaw can't
        // open them. Route through the synthetic Stage A: decode to an
        // EXIF-oriented bitmap and write the same A.tif a RAW decode produces.
        val isNonRaw = isNonRawSource(scratch, sourceName)
        val stageA: RawV3Engine.StageAResult
        if (stageACacheHit) {
            Log.i(TAG, "$sourceName: Stage A CACHE HIT sha=${sha.take(8)}… " +
                "(options unchanged since last open) — skipping LibRaw decode")
            stageA = cachedMeta.toStageAResult()
            scratch.delete()
        } else {
        // Only delete the pristine linear TIFF so a genuine cache MISS always
        // re-decodes fresh. Profile-keyed film-sim TIFFs are left intact — they
        // only depend on the RAW content (SHA) + profile + grain, not on
        // workspace sliders, so they remain valid across re-opens of the same
        // file with the same profile selection.
        cache.stageATif(sha).delete()
        // Drop the camera-profile bake marker for the pristine path too: this tif
        // is about to be re-decoded NEUTRAL, so a marker left from a prior open
        // would wrongly tell the export the (now neutral) pixels are baked.
        File("${cache.stageATif(sha).absolutePath}.camprofile").delete()
        stageA = if (isNonRaw) {
            Log.i(TAG, "$sourceName: openRawFile sha=${sha.take(8)}… (non-RAW → synthetic Stage A)")
            synthStageAWithLensPipeline(scratch, linearTif, workspace, sourceName)
        } else {
            val probe = RawV3SourceProbe.probe(scratch)
            Log.i(TAG, "$sourceName: openRawFile sha=${sha.take(8)}… skipRcd=${probe.skipRcd} filmProfile=${workspace.filmProfileIndex}")
            val effectiveWorkspace = if (probe.skipRcd) {
                workspace.copy(demosaicAlgorithm = 3 /* AHD */)
            } else workspace
            Log.i(TAG, "HDR recovery flag: hdrRecovery=${effectiveWorkspace.hdrRecovery}  shadowRecovery=${effectiveWorkspace.shadowRecovery}")
            val hdrBytes = if (effectiveWorkspace.hdrRecovery) {
                runCatching {
                    val bytes = context.assets.open("models/raw_hdr_recovery.bin").use { it.readBytes() }
                    Log.i(TAG, "HDR model loaded: ${bytes.size} bytes")
                    bytes
                }.getOrElse { e ->
                    Log.w(TAG, "HDR model not found — skipping: ${e.message}")
                    null
                }
            } else null
            val shadowBytes = if (effectiveWorkspace.shadowRecovery) {
                runCatching {
                    val bytes = context.assets.open("models/raw_shadow_recovery.bin").use { it.readBytes() }
                    Log.i(TAG, "Shadow model loaded: ${bytes.size} bytes")
                    bytes
                }.getOrElse { e ->
                    Log.w(TAG, "Shadow model not found — skipping: ${e.message}")
                    null
                }
            } else null
            // Zero-DCE guided adaptive devignetting: probe the embedded JPEG
            // thumbnail BEFORE Stage A bakes the Lensfun pass — the lift map
            // attenuates the radial gain in deep-shadow (low-SNR) regions so
            // corner noise is not amplified. Reuses thumbnailBitmap (already
            // EXIF-rotated — orientation parity with the Stage A buffer) and
            // skips entirely when Lensfun is off (map would be unused) or the
            // probe can't run (null map = classic static correction).
            val adaptiveLiftMap = if (effectiveWorkspace.lensfunDbDir.isNotEmpty()) {
                thumbnailBitmap?.let { thumb ->
                    runCatching {
                        val probe = RawV3ZeroDceLightProbe(context)
                        try {
                            val map = probe.probeLiftMap(thumb)
                            map?.let { _zeroDceLightScore.value = it.average().toFloat() }
                            map
                        } finally {
                            probe.release()
                        }
                    }.getOrNull()
                }
            } else null

            val tA0 = System.currentTimeMillis()
            val s = RawV3Engine.stageADecode(
                rawFilePath     = scratch.absolutePath,
                outTifPath      = linearTif.absolutePath,
                options         = effectiveWorkspace,
                hdrModelData    = hdrBytes,
                shadowModelData = shadowBytes,
                isLinearRaw     = probe.alreadyDemosaiced,
                liftMap         = adaptiveLiftMap,
                liftTau         = effectiveWorkspace.liftTau,
            )
            Log.i(TAG, "$sourceName: Stage A decode in ${System.currentTimeMillis() - tA0} ms " +
                "(ok=${s.success} ${s.width}×${s.height} linearRaw=${probe.alreadyDemosaiced})")
            scratch.delete()
            s
        }
        if (stageA.success) {
            runCatching {
                writeStageAMeta(cache.stageAMeta(sha), CachedStageAMeta.from(stageA, optionsFingerprint))
            }.onFailure { Log.w(TAG, "$sourceName: Stage A meta write failed: ${it.message}") }
        }
        }

        if (!stageA.success) {
            _state.value = RawV3State.Failed("Stage A: ${stageA.error}")
            return@withContext
        }

        // ── Film-sim bake (Task 8.3) ──────────────────────────────────────────
        // Applies after pristine linear decode; writes to the profile-keyed path.
        // Cache hit skips the bake entirely. Failure falls back to the linear TIFF.
        var activeStageATifPath = linearTif.absolutePath
        if (filmSimActive) {
            Log.d(TAG, "$sourceName: film-sim active — profileIndex=${workspace.filmProfileIndex} grainAmount=${workspace.filmGrainAmount} → ${stageATif.name}")
            if (stageATif.exists()) {
                Log.i(TAG, "$sourceName: film-sim CACHE HIT — skipping bake, size=${stageATif.length()} bytes → ${stageATif.name}")
                activeStageATifPath = stageATif.absolutePath
            } else {
                Log.i(TAG, "$sourceName: film-sim BAKE START — profileIndex=${workspace.filmProfileIndex} grainAmount=${workspace.filmGrainAmount} input=${linearTif.length()} bytes")
                val bakeStart = System.currentTimeMillis()
                val ok = RawV3Engine.applyFilmSim(
                    inputPath    = linearTif.absolutePath,
                    outputPath   = stageATif.absolutePath,
                    profileIndex = workspace.filmProfileIndex,
                    grainAmount  = workspace.filmGrainAmount,
                )
                val bakeMs = System.currentTimeMillis() - bakeStart
                if (ok) {
                    Log.i(TAG, "$sourceName: film-sim BAKE OK — ${bakeMs}ms output=${stageATif.length()} bytes → ${stageATif.name}")
                    activeStageATifPath = stageATif.absolutePath
                } else {
                    Log.w(TAG, "$sourceName: film-sim BAKE FAILED after ${bakeMs}ms — falling back to linear decode")
                }
            }
        } else {
            Log.d(TAG, "$sourceName: film-sim inactive (profileIndex=0, grain=0) — using linear TIFF")
        }

        // ── DnCNN denoised baseline (baked at import) ─────────────────────────
        // When AI Enhance ("AI Denoise") is on, denoise the 16-bit pipeline
        // SOURCE once, here in the import window, so BOTH the live preview and
        // the save inherit one clean foundation — no per-render neural pass, and
        // the noise the preview shows now matches the saved file (WYSIWYG).
        // We compute DnCNN on a display-space proxy (≤16 MP, the model's guard)
        // and bake it as a per-pixel luma RATIO into the FP16 A.tif (chroma +
        // 16-bit depth preserved). A sibling ".dncnn" marker tells the export
        // path to SKIP its own DnCNN so we never denoise twice. Fresh decodes
        // only — a film-sim cache hit keeps the bake from its creating open,
        // and (as of the Stage A reopen cache above) so does a pristine cache
        // hit: reusing `linearTif` unbaked would otherwise look "fresh" here
        // and double-apply DnCNN on top of the bake from its original open.
        val freshTif = if (filmSimActive) !stageATif.exists() else !stageACacheHit
        if (workspace.enhanceEnabled && freshTif) {
            runCatching {
                val tD = System.currentTimeMillis()
                // ≤16 MP cap (worst case square 4000² = 16.0 MP) so DnCNN runs;
                // the scale map is bilinearly upsampled to full res in native.
                val proxy = RawV3BigTiffReader.decodeStageAToArgb8888(
                    file = File(activeStageATifPath), maxLongSide = 4000,
                )
                if (proxy != null) {
                    val den = DncnnLumaDenoiser.denoise(context, proxy, strength = 0.45f)
                    if (den !== proxy) {
                        val w = proxy.width; val h = proxy.height; val n = w * h
                        val op = IntArray(n); proxy.getPixels(op, 0, w, 0, 0, w, h)
                        val dp = IntArray(n); den.getPixels(dp, 0, w, 0, 0, w, h)
                        val scale = FloatArray(n)
                        for (i in 0 until n) {
                            val oc = op[i]; val dc = dp[i]
                            val oy = 0.2126f * ((oc ushr 16) and 0xFF) + 0.7152f * ((oc ushr 8) and 0xFF) + 0.0722f * (oc and 0xFF)
                            val dy = 0.2126f * ((dc ushr 16) and 0xFF) + 0.7152f * ((dc ushr 8) and 0xFF) + 0.0722f * (dc and 0xFF)
                            scale[i] = if (oy > 1f) dy / oy else 1f
                        }
                        val ok = RawV3Engine.applyLumaScaleToStageA(activeStageATifPath, scale, w, h)
                        if (ok) runCatching { File("$activeStageATifPath.dncnn").writeText("1") }
                        den.recycle()
                        Log.i(TAG, "$sourceName: DnCNN baseline baked at import ok=$ok (${w}x${h}) in ${System.currentTimeMillis() - tD}ms")
                    } else {
                        Log.i(TAG, "$sourceName: DnCNN baseline skipped (model absent or >16MP proxy)")
                    }
                    proxy.recycle()
                }
            }.onFailure { Log.w(TAG, "$sourceName: DnCNN baseline bake failed: ${it.message}", it) }
        }

        // ── Camera Color Profile baked at import (route A) ────────────────────
        // Derive the per-channel histogram-matched camera curve (neutral Stage-A
        // render vs the embedded JPEG) and bake it INTO the FP16 A.tif right
        // here — before Stage B downsamples it — so the live preview AND the
        // export inherit the in-camera colour directly, instead of each stage
        // re-applying the LUT. RAW only (non-RAW is already camera-rendered). A
        // sibling ".camprofile" marker tells the export path the curve is baked
        // so it skips the redundant Stage C compose. Switching the route off and
        // reopening changes `optionsFingerprint` (useCameraColorProfile is part
        // of it), so the Stage A cache check above correctly forces a fresh,
        // un-baked decode rather than serving a stale baked tif.
        // `freshTif` (computed above for the DnCNN bake) is false on a film-sim
        // cache hit OR a pristine Stage A cache hit — either way that tif was
        // already baked on its creating open, so re-baking would double-apply
        // the curve. The marker check is a belt-and-braces idempotency guard on
        // the same path.
        val alreadyBaked = File("$activeStageATifPath.camprofile").exists()
        if (workspace.useCameraColorProfile && !isNonRaw && thumbnailBitmap != null &&
            freshTif && !alreadyBaked) {
            runCatching {
                val tC = System.currentTimeMillis()
                val neutral = RawV3BigTiffReader.decodeStageAToArgb8888(
                    file = File(activeStageATifPath), maxLongSide = 512,
                )
                val camLut = if (neutral != null)
                    CameraColorMatch.matchPerChannel(neutral, thumbnailBitmap) else null
                neutral?.recycle()
                if (camLut != null) {
                    val ok = RawV3Engine.applyToneCurveToStageA(activeStageATifPath, camLut)
                    if (ok) runCatching { File("$activeStageATifPath.camprofile").writeText("1") }
                    Log.i(TAG, "$sourceName: camera-color-profile baked into A.tif ok=$ok in ${System.currentTimeMillis() - tC}ms")
                } else {
                    Log.w(TAG, "$sourceName: camera-color-profile bake skipped (neutral/match null)")
                }
            }.onFailure { Log.w(TAG, "$sourceName: camera-color-profile bake failed: ${it.message}", it) }
        }

        // Task 8.5: suppress Camera Style Finish when film sim is active —
        // the profile already provides its own tonal/colour character.
        // Stored on the state so Stage B / Stage C macro composition reads it.
        @Suppress("UNUSED_VARIABLE")
        val effectiveCameraStyleFinish = workspace.filmProfileIndex == 0

        openStageAPath = activeStageATifPath
        openSha = sha
        openUri = rawUri
        openNonRaw = isNonRaw
        _stageAResult.value = stageA
        _state.value = RawV3State.StageBReady(
            sha = sha,
            stageATifPath = activeStageATifPath,
            previewWidth = stageA.width,
            previewHeight = stageA.height,
        )
        Log.i(TAG, "$sourceName: StageBReady ${stageA.width}×${stageA.height} (filmSim=${workspace.filmProfileIndex})")

        // ── M12.2a — U2Net segmentation kickoff ───────────────────────────
        // Only when the user opted in via the workspace selector. Runs on
        // RawV3SegmentationProcessor.dispatcher (a MIN_PRIORITY daemon
        // thread) so it never starves the preview. Cache hit first;
        // inference only when there's no persisted entry for this SHA.
        // The job is cancellable — opening a new file before inference
        // finishes drops the previous run cleanly.
        segmentationJob?.cancel()
        _segmentationMasks.value = null  // reset so editor doesn't see stale masks
        _depthMap.value = null           // reset depth — new photo, new CoC map
        _segmentationRunning.value = false  // stale run cancelled; new photo re-triggers
        _multiclassMasks.value = null    // reset multiclass too — new image, new classes
        _multiclassLoading.value = false  // will flip to true just before inference starts
        _faceMask.value = null           // reset face-detection mask
        _cityscapesMasks.value = null    // reset Cityscapes 4-class masks
        _cityscapesLoading.value = false  // will flip to true just before inference starts
        _deepLabMasks.value = null       // reset DeepLabV3p human-parsing masks
        _zeroDceLightScore.value = null  // reset Zero-DCE low-light score
        // Segmentation is now LAZY — see [ensureSegmentation]. Running all six
        // ONNX models (U2Net/BiRefNet ~213 MB + SegFormer-Cityscapes + selfie-
        // multiclass + DeepLabV3+ + face-detect + Zero-DCE) on EVERY open was
        // the OOM driver: on a 42 MP RAW the chain peaked multiple GB of native
        // heap (37 s for U2Net alone on a slow device) and lmkd killed the app.
        // Capture what the chain needs; the editor calls ensureSegmentation()
        // the first time a mask-consuming feature is used (Mask tab, AI Expose
        // subject protection, subject/background vignette/gradient).
        segSha = sha
        segStageATif = stageATif
        segSourceName = sourceName
        segLaunched = false
    }

    /**
     * Lazily run the subject/segmentation ONNX model chain ONCE for the current
     * open. Idempotent — repeated calls while running or after completion are
     * no-ops (guarded by [segLaunched]). The editor triggers it when a mask-
     * consuming feature is first used, so basic edits never pay the multi-GB,
     * multi-second segmentation cost. Results land in the same flows the old
     * eager path populated, so existing observers (preview subject gating, Mask
     * tab, AI Expose) update live as each model finishes.
     */
    fun ensureSegmentation() {
        val sha = segSha ?: return
        val stageATif = segStageATif ?: return
        val sourceName = segSourceName
        synchronized(segModelLock) {
            if (segLaunched) return
            segLaunched = true
        }
        _segmentationRunning.value = true  // grey subject-dependent controls until done
        run {
            segmentationJob = CoroutineScope(
                RawV3SegmentationProcessor.dispatcher + SupervisorJob()
            ).launch {
              try {
                val cached = RawV3SegmentationStorage.load(context, sha)?.takeIf { c ->
                    // A cached matte with no subject (blank DeepLab person union
                    // from before the U2Net fallback existed) must not be served
                    // forever — drop it and let the chain run again.
                    c.hasSubject.also { ok ->
                        if (!ok) Log.w(TAG, "$sourceName: cached segmentation has no subject " +
                            "(coverage=${"%.2f".format(c.subjectCoverage * 100f)}%) — recomputing")
                    }
                }
                if (cached != null) {
                    // The cache stores only the 320² arrays. Rebuild the
                    // guided-filter refined matte so a REOPENED photo gates
                    // (GL preview, spatial passes, export) exactly like the
                    // first open did — otherwise reopen silently fell back to
                    // the raw 320² mask (softer edge, preview≠export drift).
                    val withRefined = refineCachedMasks(cached, stageATif)
                    _segmentationMasks.value = withRefined
                    Log.i(TAG, "$sourceName: segmentation cache hit — subject coverage=" +
                        "${"%.1f".format(withRefined.subjectCoverage * 100f)}% " +
                        "hasSubject=${withRefined.hasSubject} refined=${withRefined.refinedMask != null}")
                    return@launch
                }
                // Decode Stage A → small ARGB_8888 Bitmap for U2Net input.
                val bmp = RawV3BigTiffReader.decodeStageAToArgb8888(
                    file = stageATif,
                    maxLongSide = 512,  // small enough to be cheap, large enough that the
                                        // 320×320 rescale inside compute() doesn't blur further
                ) ?: run {
                    Log.w(TAG, "$sourceName: segmentation skipped — Stage A decode failed")
                    return@launch
                }
                val t0 = System.currentTimeMillis()
                // Quick face probe on the already-decoded 512 preview — used to
                // (a) slightly relax the BiRefNet RAM gate for portraits and
                // (b) log portrait vs general routing. Free (~3.5 MB model).
                val faceHint = quickFaceCount(bmp, sourceName)
                val portraitLikely = faceHint > 0
                // BiRefNet's fixed 1024² pass peaks ~6 GB of ONNX activations —
                // enough to get the app OOM-killed on RAM-tight devices. Only run
                // it when the device has real headroom; portraits get a slightly
                // relaxed gate because BiRefNet-lite is the best person matte we
                // ship (rembg birefnet-portrait equivalent in this build).
                val runBiRefNet = hasMemoryForBiRefNet(relaxForPortrait = portraitLikely)
                val biRefNetMasks = if (runBiRefNet) {
                    val processor = segmentationProcessor
                        ?: RawV3SegmentationProcessor(context).also { segmentationProcessor = it }
                    withTimeoutOrNull(90_000L) { processor.compute(bmp) }
                        ?.takeIf { it.hasSubject }
                } else {
                    Log.w(TAG, "$sourceName: BiRefNet subject mask skipped — low memory" +
                        (if (portraitLikely) " (portrait hint=$faceHint faces)" else "") +
                        "; falling through to U2Net general saliency")
                    null
                }
                val ms = System.currentTimeMillis() - t0
                var maskSource = when {
                    biRefNetMasks != null -> "BiRefNet"
                    else -> "none"
                }
                // General-saliency fallback (U2Net) — ALWAYS attempt when BiRefNet
                // is missing OR returned an empty matte. An empty BiRefNet used to
                // be published and then blocked the DeepLab person fallback, leaving
                // product/landscape subjects with a blank-but-"ready" mask.
                val u2 = if (biRefNetMasks == null) {
                    u2netSubjectMasks(bmp, sourceName)?.takeIf { it.hasSubject }
                        ?.also { maskSource = "U2Net" }
                } else null
                val masks = biRefNetMasks ?: u2
                // Depth-Anything-V2-Small (optional asset). Same RGB decode as
                // subject seg; independent OrtSession. Null when onnx absent.
                val depthMap = runCatching {
                    val dp = depthProcessor
                        ?: RawV3DepthProcessor(context).also { depthProcessor = it }
                    if (!dp.hasModel) {
                        Log.i(TAG, "$sourceName: depth skipped — depth_anything_v2_vits.onnx absent")
                        null
                    } else {
                        withTimeoutOrNull(60_000L) { dp.compute(bmp) }
                    }
                }.onFailure { Log.w(TAG, "$sourceName: depth failed: ${it.message}") }
                    .getOrNull()
                _depthMap.value = depthMap
                if (depthMap != null) {
                    Log.i(TAG, "$sourceName: depth ready ${depthMap.width}x${depthMap.height}")
                }
                bmp.recycle()
                if (biRefNetMasks == null && segmentationProcessor != null) {
                    // Ran but timed-out / failed / empty. Request release — gate
                    // defers OrtSession.close until any in-flight run returns.
                    Log.w(TAG, "$sourceName: BiRefNet null/timeout/empty after ${ms}ms — releasing processor")
                    runCatching { segmentationProcessor?.release() }
                    segmentationProcessor = null
                }
                // ── Shared luma guide for ALL guided-filter passes ──
                // Decoded once; shared by U2Net, multiclass, face, cityscapes.
                // Null = Stage A luma decode failed → all refinements are no-ops.
                val sharedLuma = runCatching {
                    RawV3BigTiffReader.decodeStageALumaLinear(
                        file = stageATif,
                        maxLongSide = RawV3SegmentationMasks.REFINED_LONG_SIDE,
                    )
                }.onFailure { Log.w(TAG, "luma decode failed: ${it.message}") }
                 .getOrNull()
                val sharedLumaArr = sharedLuma?.first
                val sharedLw = sharedLuma?.second ?: 0
                val sharedLh = sharedLuma?.third ?: 0

                // Core guided-filter helper: upsample 320² → luma dims → refine.
                // Returns the refined array at (sharedLw × sharedLh), or null.
                fun refineToLumaDims(mask320: FloatArray): FloatArray? {
                    if (sharedLumaArr == null || sharedLw == 0) return null
                    val side = RawV3SegmentationMasks.MASK_SIZE
                    val up = upsampleMaskBilinear(mask320, side, side, sharedLw, sharedLh)
                    return RawV3Engine.guidedFilterRefine(
                        guideLinearLuma = sharedLumaArr,
                        mask            = up,
                        width           = sharedLw,
                        height          = sharedLh,
                        radius          = 32,
                        eps             = 1e-3f,
                        scale           = 4,
                    )
                }

                // Variant for multiclass/face/cityscapes: refine then downsample
                // back to 320² so [y*320+x] indexing in RawEditorContent stays valid.
                fun refineWith320Mask(mask320: FloatArray): FloatArray? {
                    val refined = refineToLumaDims(mask320) ?: return null
                    val side = RawV3SegmentationMasks.MASK_SIZE
                    return upsampleMaskBilinear(refined, sharedLw, sharedLh, side, side)
                }

                if (masks == null) {
                    Log.w(TAG, "$sourceName: segmentation returned null (model missing or failed)")
                } else {
                    // ── Guided-filter mask refinement (U2Net subject mask) ──
                    // Uses refineToLumaDims (not refineWith320Mask) so the result
                    // stays at 1024px for the GL preview refined-mask path.
                    val refinedTriple = runCatching {
                        val refined = refineToLumaDims(masks.subjectMask)
                            ?: return@runCatching null
                        Triple(refined, sharedLw, sharedLh)
                    }.onFailure { Log.w(TAG, "mask refinement failed: ${it.message}") }
                     .getOrNull()
                    val finalMasks = if (refinedTriple != null) {
                        val (refined, rw, rh) = refinedTriple
                        Log.i(TAG, "$sourceName: refined mask ${rw}×${rh}")
                        masks.copy(
                            refinedMask   = refined,
                            refinedWidth  = rw,
                            refinedHeight = rh,
                        )
                    } else masks
                    RawV3SegmentationStorage.save(context, sha, finalMasks)
                    _segmentationMasks.value = finalMasks
                    Log.i(TAG, "$sourceName: segmentation OK in $ms ms ($maskSource) — subject coverage=" +
                        "${"%.1f".format(finalMasks.subjectCoverage * 100f)}% hasSubject=${finalMasks.hasSubject} " +
                        "refined=${finalMasks.refinedMask != null}")
                }
                // U2Net done — its 168 MB session is the biggest; free it before
                // the next model loads so peak RAM never holds two large sessions.
                unloadAfterPass("u2net") { segmentationProcessor?.release(); segmentationProcessor = null }
                // ── MediaPipe multiclass pass (Hair / BodySkin / FaceSkin
                //    / Clothes / Accessories). Runs on the same small
                //    bitmap the U2Net pass used. Best-effort: if the
                //    model asset is missing or init fails, the StateFlow
                //    just stays null and the Mask tab gates the new
                //    buttons accordingly. Runs in series with U2Net to
                //    avoid double peak CPU/GPU contention.
                val mc = multiclassSegmenter
                    ?: com.RAZStudio.StudioRoom.feature.photo_editor.raw
                        .segmentation.SelfieMulticlassSegmenter
                        .load(context)?.also { multiclassSegmenter = it }
                if (mc != null) {
                    _multiclassLoading.value = true
                    // Re-decode at the model's native 256×256 so the
                    // class boundaries don't get distorted by an
                    // intermediate resize. Cheap.
                    val mcBmp = RawV3BigTiffReader.decodeStageAToArgb8888(
                        file = stageATif,
                        maxLongSide = 256,
                    )
                    if (mcBmp != null) {
                        val t1 = System.currentTimeMillis()
                        val mcResult = runCatching { mc.segment(mcBmp) }
                            .onFailure { Log.w(TAG, "$sourceName: multiclass segment threw", it) }
                            .getOrNull()
                        mcBmp.recycle()
                        if (mcResult != null) {
                            val mcMs = System.currentTimeMillis() - t1
                            val packed = RawV3MulticlassMasks.fromSoftProbs(
                                perClass = mcResult.perClass,
                                srcSize  = mcResult.width,
                            )
                            // Refine each class plane with guided filter so soft
                            // class edges snap to real photo boundaries.
                            val refinedPacked = if (sharedLumaArr != null && sharedLw > 0) {
                                runCatching {
                                    RawV3MulticlassMasks(
                                        hair        = refineWith320Mask(packed.hair)        ?: packed.hair,
                                        bodySkin    = refineWith320Mask(packed.bodySkin)    ?: packed.bodySkin,
                                        faceSkin    = refineWith320Mask(packed.faceSkin)    ?: packed.faceSkin,
                                        clothes     = refineWith320Mask(packed.clothes)     ?: packed.clothes,
                                        accessories = refineWith320Mask(packed.accessories) ?: packed.accessories,
                                    )
                                }.onFailure { Log.w(TAG, "multiclass guided filter failed: ${it.message}") }
                                 .getOrDefault(packed)
                            } else packed
                            _multiclassMasks.value = refinedPacked
                            Log.i(TAG, "$sourceName: multiclass OK in $mcMs ms (${mcResult.width}×${mcResult.height})")
                        }
                    }
                    _multiclassLoading.value = false
                } else {
                    Log.i(TAG, "$sourceName: multiclass skipped — model not loaded")
                    // _multiclassLoading stays false — model not bundled, spinner never shown
                }
                unloadAfterPass("multiclass") { multiclassSegmenter?.close(); multiclassSegmenter = null }

                // 3. Face-detection ONNX (Qualcomm Lightweight-Face-
                //    Detection, ~3.5 MB). Runs in series after multiclass
                //    so peak CPU/GPU contention stays bounded. Fills a
                //    [0,1] alpha plane at the same 320×320 grid as
                //    SegmentationMasks so the Mask tab can consume it
                //    with no extra resize. Skipped silently if the model
                //    asset is missing.
                val fd = faceDetector
                    ?: RawV3FaceDetector(context).also { faceDetector = it }
                if (fd.hasModel) {
                    // Decode a larger preview than the model's 640px input —
                    // the detector internally downsamples to 640×480, and any
                    // upstream resize from the source RAW compounds the loss.
                    // Group shots with small faces (~30 px in the source after
                    // a 640-long decode) were missing detections; feeding
                    // 1280-long gives the detector a sharper resample target,
                    // catching faces in the 20-30 px range without measurable
                    // CPU overhead (the model resize is fixed cost).
                    val fdBmp = RawV3BigTiffReader.decodeStageAToArgb8888(
                        file = stageATif, maxLongSide = 1280,
                    )
                    if (fdBmp != null) {
                        val tF = System.currentTimeMillis()
                        val mask = runCatching { fd.compute(fdBmp) }
                            .onFailure { Log.w(TAG, "$sourceName: face detector threw", it) }
                            .getOrNull()
                        fdBmp.recycle()
                        if (mask != null) {
                            val refinedFace = if (sharedLumaArr != null && sharedLw > 0)
                                runCatching { refineWith320Mask(mask) }
                                    .onFailure { Log.w(TAG, "face guided filter failed: ${it.message}") }
                                    .getOrNull() ?: mask
                            else mask
                            _faceMask.value = refinedFace
                            Log.i(TAG, "$sourceName: face mask OK in ${System.currentTimeMillis() - tF} ms")
                        } else {
                            Log.i(TAG, "$sourceName: no faces found")
                        }
                    }
                } else {
                    Log.i(TAG, "$sourceName: face detector skipped — model not loaded")
                }
                unloadAfterPass("face-detect") { faceDetector?.release(); faceDetector = null }

                // 4. Cityscapes SegFormer-B1 ONNX (~50 MB FP16). Produces
                //    Building+Wall / Vegetation / Terrain / Sky masks. Runs
                //    last because it's the heaviest pass (~1-3 s on a mid-
                //    range phone) — by this point the editor's already
                //    interactive, so the long inference doesn't block UX.
                //    Skipped silently if the model asset isn't bundled.
                val cs = cityscapesDetector
                    ?: RawV3CityscapesDetector(context).also { cityscapesDetector = it }
                if (cs.hasModel) {
                    _cityscapesLoading.value = true
                    val csBmp = RawV3BigTiffReader.decodeStageAToArgb8888(
                        file = stageATif, maxLongSide = 1024,
                    )
                    if (csBmp != null) {
                        val tCS = System.currentTimeMillis()
                        val csResult = runCatching { cs.compute(csBmp) }
                            .onFailure { Log.w(TAG, "$sourceName: cityscapes seg threw", it) }
                            .getOrNull()
                        csBmp.recycle()
                        if (csResult != null) {
                            val refinedCs = if (sharedLumaArr != null && sharedLw > 0) {
                                runCatching {
                                    RawV3CityscapesMasks(
                                        buildingWall = refineWith320Mask(csResult.buildingWall) ?: csResult.buildingWall,
                                        vegetation   = refineWith320Mask(csResult.vegetation)   ?: csResult.vegetation,
                                        terrain      = refineWith320Mask(csResult.terrain)      ?: csResult.terrain,
                                        sky          = refineWith320Mask(csResult.sky)          ?: csResult.sky,
                                    )
                                }.onFailure { Log.w(TAG, "cityscapes guided filter failed: ${it.message}") }
                                 .getOrDefault(csResult)
                            } else csResult
                            _cityscapesMasks.value = refinedCs
                            Log.i(TAG, "$sourceName: cityscapes OK in ${System.currentTimeMillis() - tCS} ms")
                        }
                    }
                    _cityscapesLoading.value = false
                } else {
                    Log.i(TAG, "$sourceName: cityscapes skipped — model not loaded")
                    // _cityscapesLoading stays false — model not bundled, spinner never shown
                }
                unloadAfterPass("cityscapes") { cityscapesDetector?.release(); cityscapesDetector = null }

                // 5. DeepLabV3+ ResNet50 human-parsing (LIP 20-class, 45 MB).
                //    Runs after cityscapes — also a CPU-heavy ONNX, series
                //    scheduling keeps peak memory bounded. Produces finer
                //    human body-part masks than selfie_multiclass (which is
                //    temporal-video tuned). Skipped silently if model absent.
                val dl = deepLabProcessor
                    ?: RawV3DeepLabProcessor(context).also { deepLabProcessor = it }
                if (dl.hasModel) {
                    val dlBmp = RawV3BigTiffReader.decodeStageAToArgb8888(
                        file = stageATif, maxLongSide = 512,
                    )
                    if (dlBmp != null) {
                        val tDL = System.currentTimeMillis()
                        val dlResult = runCatching { dl.compute(dlBmp) }
                            .onFailure { Log.w(TAG, "$sourceName: deeplabv3p threw", it) }
                            .getOrNull()
                        dlBmp.recycle()
                        if (dlResult != null) {
                            // Refine each class plane with the shared guided-filter luma guide
                            // so soft class edges snap to actual photo boundaries.
                            val refinedDl = if (sharedLumaArr != null && sharedLw > 0) {
                                runCatching {
                                    RawV3DeepLabMasks(
                                        face        = refineWith320Mask(dlResult.face)        ?: dlResult.face,
                                        hair        = refineWith320Mask(dlResult.hair)        ?: dlResult.hair,
                                        upperBody   = refineWith320Mask(dlResult.upperBody)   ?: dlResult.upperBody,
                                        lowerBody   = refineWith320Mask(dlResult.lowerBody)   ?: dlResult.lowerBody,
                                        arms        = refineWith320Mask(dlResult.arms)        ?: dlResult.arms,
                                        legs        = refineWith320Mask(dlResult.legs)        ?: dlResult.legs,
                                        shoes       = refineWith320Mask(dlResult.shoes)       ?: dlResult.shoes,
                                        accessories = refineWith320Mask(dlResult.accessories) ?: dlResult.accessories,
                                    )
                                }.onFailure { Log.w(TAG, "deeplab guided filter failed: ${it.message}") }
                                 .getOrDefault(dlResult)
                            } else dlResult
                            _deepLabMasks.value = refinedDl
                            Log.i(TAG, "$sourceName: deeplabv3p OK in ${System.currentTimeMillis() - tDL} ms")
                            // Subject-mask FALLBACK: when BiRefNet + U2Net both
                            // missed (low memory / empty / timeout), derive the
                            // subject from DeepLab person classes so Select
                            // Subject / AE protection still work for people.
                            // Only publish when the union actually has coverage —
                            // a blank person mask must not block hasSubject=false
                            // consumers (Bokeh/Bloom) with a "ready but empty" matte.
                            if (_segmentationMasks.value == null) {
                                val side = RawV3SegmentationMasks.MASK_SIZE
                                val n = side * side
                                val parts = listOf(
                                    refinedDl.face, refinedDl.hair, refinedDl.upperBody,
                                    refinedDl.lowerBody, refinedDl.arms, refinedDl.legs,
                                    refinedDl.shoes, refinedDl.accessories,
                                )
                                if (parts.all { it.size == n }) {
                                    val union = FloatArray(n) { i ->
                                        parts.maxOf { it[i] }.coerceIn(0f, 1f)
                                    }
                                    val refined = runCatching { refineToLumaDims(union) }.getOrNull()
                                    val subj = RawV3SegmentationMasks(
                                        subjectMask   = union,
                                        edgeMask      = FloatArray(n),
                                        refinedMask   = refined,
                                        refinedWidth  = if (refined != null) sharedLw else 0,
                                        refinedHeight = if (refined != null) sharedLh else 0,
                                    )
                                    if (subj.hasSubject) {
                                        _segmentationMasks.value = subj
                                        runCatching { RawV3SegmentationStorage.save(context, sha, subj) }
                                        Log.i(TAG, "$sourceName: subject mask from DeepLab PERSON union " +
                                            "(BiRefNet/U2Net fallback) — coverage=" +
                                            "${"%.1f".format(subj.subjectCoverage * 100f)}%")
                                    } else {
                                        Log.i(TAG, "$sourceName: DeepLab person union empty — " +
                                            "no subject published (non-person scene)")
                                    }
                                } else {
                                    Log.w(TAG, "$sourceName: DeepLab subject fallback skipped — class size != $n")
                                }
                            }
                        } else {
                            Log.i(TAG, "$sourceName: deeplabv3p returned null result")
                        }
                    }
                } else {
                    Log.i(TAG, "$sourceName: deeplabv3p skipped — model not loaded")
                }
                unloadAfterPass("deeplabv3p") { deepLabProcessor?.release(); deepLabProcessor = null }

                // 6. Zero-DCE low-light probe. Cheap (~50-150 ms), single
                //    scalar output. Auto Expo combines this with its
                //    channel-clip detection to push CLAHE highlights harder
                //    on dark scenes that ALSO have hot spots.
                val zd = zeroDceLightProbe
                    ?: RawV3ZeroDceLightProbe(context).also { zeroDceLightProbe = it }
                if (zd.hasModel) {
                    val zdBmp = RawV3BigTiffReader.decodeStageAToArgb8888(
                        file = stageATif, maxLongSide = 256,
                    )
                    if (zdBmp != null) {
                        val tZ = System.currentTimeMillis()
                        val liftMap = runCatching { zd.probeLiftMap(zdBmp) }
                            .onFailure { Log.w(TAG, "$sourceName: zero-dce probe threw", it) }
                            .getOrNull()
                        // The gain field is low-frequency (analytic radial ×
                        // 256² lift), so it is baked at a capped resolution —
                        // consumers sample it normalized.
                        zdBmp.recycle()
                        if (liftMap != null) {
                            val score = liftMap.average().toFloat()
                            _zeroDceLightScore.value = score
                            Log.i(TAG, "$sourceName: zero-dce probe score=%.3f in %d ms"
                                .format(score, System.currentTimeMillis() - tZ))
                            // NOTE: the adaptive devignette is applied NATIVELY
                            // inside Stage A's Lensfun pass (the pre-decode
                            // lift map is passed via StageAOptions.liftMap).
                            // There is deliberately no GL-side gain field here.
                        }
                    }
                } else {
                    Log.i(TAG, "$sourceName: zero-dce probe skipped — model not loaded")
                }
                unloadAfterPass("zero-dce") { zeroDceLightProbe?.release(); zeroDceLightProbe = null }
              } finally {
                // Whole chain finished (or threw / was cancelled) — ungrey the
                // subject-dependent controls. If a subject mask was produced they
                // enable fully; if not, they re-enable to their prior no-op
                // behaviour rather than staying greyed forever.
                _segmentationRunning.value = false
              }
            }
        }
    }

    /** Path of the open Stage A TIFF, or null when no session is active. */
    fun openStageAPath(): String? = openStageAPath

    /** SHA-256 of the currently-open RAW. */
    fun openSha(): String? = openSha

    /**
     * Tear down the current session. Stage A cache for the active SHA is
     * retained (M10 session restore) — only the in-memory pointers reset.
     */
    fun closeSession() {
        openStageAPath = null
        openSha = null
        openUri = null
        openNonRaw = false
        // Cancel any in-flight segmentation. OrtSession.close is deferred by
        // [OrtSessionGate] until native run() returns — cancel alone does not
        // interrupt OrtSession.run (CityscapesSeg / raw-seg-u2net crash class).
        segmentationJob?.cancel()
        segmentationJob = null
        // Reset the lazy-segmentation capture so a stale sha/path can't trigger
        // a chain against the previous file after close.
        segSha = null
        segStageATif = null
        segLaunched = false
        _segmentationMasks.value = null
        _depthMap.value = null
        _segmentationRunning.value = false
        _stageAResult.value = null
        // Request release of any sessions still resident. Under UNLOAD_PER_PASS
        // most are already freed by unloadAfterPass; this catches leftovers.
        // Gate ensures close waits for in-flight OrtSession.run (no UAF).
        synchronized(segModelLock) {
            segmentationProcessor?.release()
            segmentationProcessor = null
            depthProcessor?.release()
            depthProcessor = null
            u2netFallbackProcessor?.release()
            u2netFallbackProcessor = null
            multiclassSegmenter?.close()
            multiclassSegmenter = null
            faceDetector?.release()
            faceDetector = null
            cityscapesDetector?.release()
            cityscapesDetector = null
            deepLabProcessor?.release()
            deepLabProcessor = null
            zeroDceLightProbe?.release()
            zeroDceLightProbe = null
        }
        _multiclassMasks.value = null
        _faceMask.value = null
        _cityscapesMasks.value = null
        _deepLabMasks.value = null
        _zeroDceLightScore.value = null
        _state.value = RawV3State.Idle
    }

    // ─── M11 production surface ────────────────────────────────────────────

    /**
     * Stage in the per-file pipeline. Reported via the [onStage] callback
     * so v2 batch can show fine-grained progress beyond "file N of M".
     */
    enum class Stage {
        Copying,
        Probing,
        StageA,
        StageC,
        Encoding,
        Publishing,
    }

    /**
     * One file's worth of choices. Mirrors [RawV3Exporter.Options] plus
     * the workspace + optional preset overlay.
     */
    data class ExportOptions(
        val workspace: RawV3WorkspaceOptions,
        val format: RawV3Exporter.Format,
        val targetLongSide: Int = 0,
        val scaleMode: RawV3Exporter.ScaleMode = RawV3Exporter.ScaleMode.Lanczos3,
        val resizeSharpen: RawV3Exporter.ResizeSharpen = RawV3Exporter.ResizeSharpen.None,
        val qualityPct: Int = 95,
        val embedIcc: Boolean = true,
        val xmpPresetBlob: FloatArray? = null,
        val oneTimeSaveLocationUri: String? = null,
        /**
         * Optional 3D LUT to apply at the very end of the Stage C kernel
         * (matches what the live preview shader does). When the file is
         * present the coordinator parses it via [RawV3LutStore.parseCubeFile]
         * once and threads the FloatArray into [RawV3Engine.stageCExport].
         * Null = no LUT pass.
         */
        val lutCubeFile: File? = null,
        /** LUT intensity in [0, 1]. Honoured only when [lutCubeFile] is set. */
        val lutIntensity: Float = 1f,
        /**
         * Full ShaderParams blob (the live edit state, [ShaderParams.FLOAT_COUNT]
         * floats). When non-null this is used VERBATIM as the Stage C kernel
         * params, so the saved file reproduces every edit — Light/Color/Detail/
         * CLAHE/NR/tonemap/vignette/gradient/mask — exactly as the preview shows.
         * When null, the coordinator falls back to the legacy XMP-overlay-only
         * reconstruction. The interactive RAW editor always passes this.
         */
        val fullParamsBlob: FloatArray? = null,
        /**
         * EXIF copy policy for the saved file. 0 = keep all, 1 = strip
         * sensitive (GPS/serial), 2 = none (except Software). Mirrors the
         * Export page's File Info radio group.
         */
        val exifPolicyOrdinal: Int = 0,
        /**
         * Normalised crop rect (0..1 in decoded source coords). Default
         * identity (0,0,1,1) = no crop. Honored as a post-bokeh,
         * pre-resize `Bitmap.createBitmap` in exportRawToGallery so
         * resize/sharpen run on cropped pixels.
         */
        val cropL: Float = 0f,
        val cropT: Float = 0f,
        val cropR: Float = 1f,
        val cropB: Float = 1f,
        /**
         * Snapseed-style straighten angle in degrees. Positive = clockwise.
         * Applied BEFORE the crop rect so the rect is interpreted against
         * the rotated image. 0 = no rotation.
         */
        val cropRotationDeg: Float = 0f,
        /**
         * Discrete orientation (img.ly TRANSFORM parity), applied to the source
         * BEFORE straighten + crop, in order: flip H/V → rotate90. The crop rect
         * is interpreted against the oriented+straightened canvas.
         */
        val cropRotate90: Int = 0,     // 0..3 clockwise quarter-turns
        val cropFlipH: Boolean = false,
        val cropFlipV: Boolean = false,
        /**
         * Up to 4 brush-mask layer PNG paths (bottommost = index 0), matching
         * the live preview's Mask tab layers. The coordinator decodes each PNG's
         * alpha to a [0,1] float plane and threads them into Stage C so the
         * saved file applies the same per-region mask adjustments. Empty = none.
         */
        val maskLayerPaths: List<String> = emptyList(),
        /**
         * Canvas mask graph, same list the preview uploads (committed layers,
         * then the in-flight subject/brush bitmap). Null entries are holes and
         * must keep their index. When non-null this replaces [maskLayerPaths].
         */
        val maskLayerBitmaps: List<android.graphics.Bitmap?>? = null,
        /**
         * "Apply Auto Expo only" batch mode. When true, the coordinator runs
         * [RawAutoExposure.analyse] on THIS file's decoded preview and folds
         * the derived exposure/highlights/shadows/whites/blacks (+ tonemap
         * bias) into the Stage C params — so each file gets its own auto
         * exposure. Ignored when [fullParamsBlob] is supplied.
         */
        val autoExposure: Boolean = false,
        /**
         * True when the AE values are already baked into [fullParamsBlob]
         * (because [RawEditorComponent.confirmWorkspace] ran AE immediately
         * after Stage A and committed it as a RawAction). Skips the redundant
         * re-analysis that would otherwise double-apply exposure correction.
         */
        val aeBaked: Boolean = false,
        /** Subject-protection level forwarded to [RawAutoExposure.analyse] (0..1). */
        val aeSubjectProtection: Float = 0.95f,
        /**
         * Noise-reduction level applied ALONGSIDE [autoExposure] for the
         * "Auto Expo + N NR" batch presets. None = exposure only. Sets the
         * Detail-tab NR fields (brightness/colour noise + smooth background)
         * into the Stage C params at the chosen strength.
         */
        val nrLevel: NrLevel = NrLevel.None,
        /**
         * Bokeh (background blur + bokeh balls + bloom). Applied on the decoded
         * full-res bitmap after Stage C (OpenCV engine — can't run in the GL
         * shader). Mask-gated. Default = no bokeh.
         */
        val bokeh: BokehParams = BokehParams.Default,
        /**
         * Optional brush-mask PNG path that drives the bokeh region. When set,
         * the bokeh blur is applied wherever this mask's alpha is non-zero
         * (i.e. wherever the user painted). When null, the bokeh falls back
         * to the U2Net subject mask and blurs the area OUTSIDE the subject
         * (legacy behaviour).
         */
        val paintedBokehMaskPath: String? = null,
        /**
         * Tone Curve LUT (256 RGB8 = 768 bytes), built from the composed
         * macro's curve points. Applied per-pixel in Stage C after grading +
         * 3D LUT, matching the GL preview. Null = identity (no curve).
         */
        val toneCurveLut: ByteArray? = null,
        /**
         * Route A "Camera Color Profile": when true the coordinator derives a
         * per-channel auto-matched curve from this file's embedded JPEG and
         * composes it UNDER [toneCurveLut] before Stage C, so the export adopts
         * the in-camera colour while keeping full RAW detail. See [CameraColorMatch].
         */
        val useCameraColorProfile: Boolean = false,
        /**
         * Route A only. When true, apply edge-aware chroma guided-filter smoothing
         * on the export bitmap after the camera colour-profile LUT is applied.
         * Uses luminance as the guide to preserve detail at edges while smoothing
         * colour-cast transitions in flat regions (sky, skin).
         */
        val cameraProfileGuidedFilter: Boolean = false,
        /**
         * Route A: the editor's EXACT camera-match curve (256×3 byte LUT), as
         * used by the live preview. When non-null the export uses it verbatim
         * (guaranteeing preview == save) instead of recomputing the match. Null
         * for the headless/batch path, which recomputes from the embedded JPEG.
         */
        val cameraMatchLutOverride: ByteArray? = null,
        /**
         * Session-persistent watermark to burn onto the final export bitmap
         * (after crop, rotate, resize, sharpen). Never applied to the preview.
         * Position anchors (bottom-right etc.) are relative to the final
         * bitmap dims, so they stay correct regardless of crop/rotate.
         * Null = no watermark.
         */
        val watermarkConfig: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.CombinedWatermarkConfig? = null,
        /**
         * Export-page Border/Frame: solid frame added OUTSIDE the photo as the
         * very last compositing step (photo → watermark → border), at full
         * export resolution. Fraction of the long side; 0 = no border. Until
         * 2026-09-07 the page pre-composed the border on the ~1280 px preview
         * and saved THAT via the override-bitmap fast path, so any bordered
         * export silently came out at preview size.
         */
        val borderThickness: Float = 0f,
        val borderColorArgb: Int = 0,
        /**
         * When non-null, the encoded bytes are written directly to this [File]
         * and [fileController().save()] is skipped entirely. The file is NOT
         * added to the MediaStore gallery. Use this for ephemeral full-res
         * intermediates (e.g. Heal sheet base bitmap preparation) that should
         * never appear in the user's photo library.
         * [ExportResult.Success.savedAt] will equal this file's absolute path.
         */
        val directOutputFile: java.io.File? = null,
        /**
         * Smart Bright slider amount (0..4) from the composed preset macro, for
         * the HEADLESS path only. [RawEditorExportPipeline.buildExportOptions]
         * sets this > 0 only when it recomputes the params blob itself (batch /
         * Canon Sync, `shaderParams == null`) — there [flatten] ran with
         * autoBrightFactor = 1, so Smart Bright is NOT yet in [fullParamsBlob].
         * The coordinator computes the per-file auto-bright factor from this
         * file's own Stage A preview and folds the resulting exposure stops in,
         * giving batch the same per-image Smart Bright the editor gets live.
         * Left 0 for the interactive editor (its blob already has it baked), so
         * the editor export is byte-for-byte unchanged.
         */
        val smartBrightAmount: Float = 0f,
    )

    /** Detail-tab NR strength for the Auto-Expo batch presets. Medium mirrors
     *  the in-editor defaults (Brightness 50 / Colour 35 / Smooth-Bg 20). */
    enum class NrLevel(
        val luminanceNR: Float,   // [0..1] brightness noise
        val colorNR: Float,       // [0..1] colour noise
        val smoothBackground: Float, // [0..1]
    ) {
        None(0f, 0f, 0f),
        Low (0.25f, 0.18f, 0.10f),
        Med (0.50f, 0.35f, 0.20f),
        High(0.70f, 0.50f, 0.35f),
    }

    sealed interface ExportResult {
        data class Success(
            val sourceName: String,
            val savedAt: String,
            val widthPx: Int,
            val heightPx: Int,
            val bytes: Long,
            val totalMs: Long,
            val savedUri: android.net.Uri? = null,
            /** True when the configured save folder was unavailable and the file
             *  fell back to the default folder (stale SAF grant). */
            val savedToFallbackFolder: Boolean = false,
            /**
             * JPG/WebP preferred GPU offscreen grade failed and Stage C ran on
             * CPU instead — MAD surprises / preview≠save risk. UI should toast.
             */
            val usedCpuGpuFallback: Boolean = false,
            /** Short reason for [usedCpuGpuFallback] (OOM, renderGraded failed, …). */
            val cpuGpuFallbackReason: String? = null,
        ) : ExportResult

        data class Skipped(val sourceName: String, val reason: String) : ExportResult
        data class Failure(val sourceName: String, val error: String) : ExportResult
    }

    // ── Batch-invariant caches ────────────────────────────────────────────
    // These inputs are identical for every file in a batch run, but were
    // re-loaded/re-parsed per file: the HDR/shadow recovery model assets
    // (read from APK assets on every Stage A re-decode) and the resolved
    // LUT chain .cube (re-parsed per file at Stage C). Cached here on the
    // coordinator, which lives across the whole batch. The cube cache is
    // keyed by path+mtime+size so a different chain (new preset) reparses.
    private var hdrModelCache: ByteArray? = null
    private var hdrModelTried = false
    private var shadowModelCache: ByteArray? = null
    private var shadowModelTried = false
    private var parsedCubeCacheKey: String? = null
    private var parsedCubeCache: RawV3LutStore.ParsedCube? = null

    private fun loadHdrModel(): ByteArray? {
        if (!hdrModelTried) {
            hdrModelTried = true
            hdrModelCache = runCatching {
                context.assets.open("models/raw_hdr_recovery.bin").use { it.readBytes() }
            }.getOrElse { e ->
                Log.w(TAG, "HDR model not found — skipping: ${e.message}"); null
            }
        }
        return hdrModelCache
    }

    private fun loadShadowModel(): ByteArray? {
        if (!shadowModelTried) {
            shadowModelTried = true
            shadowModelCache = runCatching {
                val bytes = context.assets.open("models/raw_shadow_recovery.bin").use { it.readBytes() }
                Log.i(TAG, "Shadow model loaded: ${bytes.size} bytes")
                bytes
            }.getOrElse { e ->
                Log.w(TAG, "Shadow model not found — skipping: ${e.message}"); null
            }
        }
        return shadowModelCache
    }

    private fun parseCubeCached(file: File): RawV3LutStore.ParsedCube? {
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        if (parsedCubeCacheKey != key) {
            parsedCubeCache = RawV3LutStore.parseCubeFile(file)
            parsedCubeCacheKey = key
            // A null here means the SAVED file silently loses a LUT the preview
            // is showing — the failure mode that hid the .smcube gap. Say so at
            // ERROR level rather than leaving only LutStore's buried warning.
            if (parsedCubeCache == null) {
                Log.e(TAG, "LUT PARSE FAILED for ${file.name} — export will save WITHOUT the LUT")
            }
        }
        return parsedCubeCache
    }

    // Serializes native LibRaw Stage A decodes: the batch prefetch
    // (prewarmStageA) may run concurrently with a file's export, and two
    // simultaneous full-res demosaics would double peak native RAM. One
    // decode at a time keeps the prefetch overlapping Stage C/encode work
    // only — which is the useful overlap — never another Stage A.
    private val stageADecodeMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Pre-decode Stage A for [rawUri] into the SHA cache so a following
     * [exportRawToGallery] for the same file cache-hits and skips the
     * 15-26s LibRaw run. Designed to run CONCURRENTLY with the previous
     * file's Stage C/encode (classic two-stage pipeline): the caller
     * (RawBatchProcessor) launches it for file N+1 while file N exports.
     *
     * Fire-and-forget semantics: any failure just means the export decodes
     * Stage A itself as before. Never touches per-file coordinator state
     * (_segmentationMasks etc.) — file-level cache writes only.
     */
    suspend fun prewarmStageA(rawUri: Uri, options: ExportOptions): Boolean =
        withContext(Dispatchers.IO) {
            val scratch = File(context.cacheDir, "raw_v3_prewarm_${System.nanoTime()}.bin")
            try {
                context.contentResolver.openInputStream(rawUri)?.use { ins ->
                    FileOutputStream(scratch).use { out -> ins.copyTo(out) }
                } ?: return@withContext false
                if (scratch.length() == 0L) return@withContext false
                coroutineContext.ensureActive()
                val name = rawUri.lastPathSegment ?: ""
                // Non-RAW Stage A is a cheap bitmap re-encode — no prewarm value.
                if (isNonRawSource(scratch, name)) return@withContext false
                val probe = RawV3SourceProbe.probe(scratch)
                val sha = sha256(scratch)
                coroutineContext.ensureActive()
                val cache = RawV3Cache(context)
                val tif = cache.stageATif(sha)
                // Same route-poison rule as the export path.
                val poisoned = !options.useCameraColorProfile &&
                    File("${tif.absolutePath}.camprofile").exists()
                if (!poisoned && tif.exists() && tif.length() > 0 &&
                    runCatching { RawV3BigTiffReader.readDims(tif) }.getOrNull() != null
                ) return@withContext true // already cached and route-valid
                cache.purge(sha)
                val fresh = cache.stageATif(sha)
                val ws = if (probe?.skipRcd == true)
                    options.workspace.copy(demosaicAlgorithm = 3) else options.workspace
                val result = stageADecodeMutex.withLock {
                    coroutineContext.ensureActive()
                    RawV3Engine.stageADecode(
                        rawFilePath     = scratch.absolutePath,
                        outTifPath      = fresh.absolutePath,
                        options         = ws,
                        hdrModelData    = if (ws.hdrRecovery) loadHdrModel() else null,
                        shadowModelData = if (ws.shadowRecovery) loadShadowModel() else null,
                        isLinearRaw     = probe?.alreadyDemosaiced == true,
                    )
                }
                if (result.success) Log.i(TAG, "prewarmStageA: $name ready " +
                    "(${result.width}×${result.height})")
                result.success
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "prewarmStageA failed (export will decode itself): ${e.message}")
                false
            } finally {
                scratch.delete()
            }
        }

    /**
     * True when [p] carries any adjustment whose Stage C rendering needs the
     * subject segmentation mask: bokeh, per-segment tone slots, vignette
     * Subject/Background routing, semantic gradient gating, smart sharpness.
     */
    /**
     * Does this params blob need the subject mask to render CORRECTLY?
     *
     * Every subject-scoped control belongs here. A control that is missing does
     * not fail loudly — segmentation simply never runs for that file, the mask
     * is null, and the effect silently renders UNGATED in the saved file while
     * the editor preview (which always holds a mask) shows it gated. That is how
     * "Bloom: protect subject" and "FX blur: exclude subject" were being lost on
     * every batch and Gallery export until 2026-09-07.
     */
    private fun paramsNeedSubjectMask(p: ShaderParams): Boolean =
        p.bokehBlur > 0f || p.bokehBalls > 0f || p.bokehSpread > 0f ||
            p.vigEffect != 0 ||
            p.detailSmartSharpness > 0f ||
            p.bloomExcludeSubject > 0f || p.subjectBloom > 0f ||
            p.fxBlurExcludeSubject > 0f ||
            p.gradTopApplyTo != 0 || p.gradBottomApplyTo != 0 ||
            p.gradLeftApplyTo != 0 || p.gradRightApplyTo != 0 ||
            p.highlightsSubject != 0f || p.whitesSubject != 0f ||
            p.blacksSubject != 0f || p.shadowsSubject != 0f ||
            p.ambianceSubject != 0f ||
            p.highlightsBackground != 0f || p.whitesBackground != 0f ||
            p.blacksBackground != 0f || p.shadowsBackground != 0f ||
            p.ambianceBackground != 0f

    private data class SessionStageAReuse(
        val sha: String,
        val nonRaw: Boolean,
        val stageATif: File,
        val stageA: RawV3Engine.StageAResult,
    )

    /**
     * Editor save of the already-open URI: skip SAF copy + SHA-256 when A.tif
     * is still valid for this workspace. Batch / a different URI returns null.
     */
    private fun tryReuseOpenSessionStageA(
        rawUri: Uri,
        options: ExportOptions,
    ): SessionStageAReuse? {
        val sha = openSha ?: return null
        val path = openStageAPath ?: return null
        if (openUri?.toString() != rawUri.toString()) return null
        val cache = RawV3Cache(context)
        val tif = cache.stageATif(sha)
        if (tif.absolutePath != path) return null
        if (!tif.exists() || tif.length() == 0L) return null
        if (!options.useCameraColorProfile &&
            File("${tif.absolutePath}.camprofile").exists()
        ) return null
        val ws = options.workspace
        val fp = ws.toString() + "|lfa=3"
        val fpAhd = ws.copy(demosaicAlgorithm = 3).toString() + "|lfa=3"
        val meta = readStageAMeta(cache.stageAMeta(sha)) ?: return null
        if (meta.optionsFingerprint != fp && meta.optionsFingerprint != fpAhd) return null
        if (runCatching { RawV3BigTiffReader.readDims(tif) }.getOrNull() == null) return null
        return SessionStageAReuse(
            sha = sha,
            nonRaw = openNonRaw,
            stageATif = tif,
            stageA = meta.toStageAResult(),
        )
    }

    /**
     * Run the full per-file pipeline. Suspending; honours coroutine
     * cancellation between stages. Always runs on [Dispatchers.IO]
     * internally, so the caller may invoke this from any dispatcher.
     */
    suspend fun exportRawToGallery(
        rawUri: Uri,
        options: ExportOptions,
        onStage: ((Stage) -> Unit)? = null,
    ): ExportResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val sourceName = rawUri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?: "(unknown)"

        // Segmentation masks are per-file. The coordinator is reused across a
        // batch run, so any mask left from a previous file must be cleared
        // before we know this file's SHA. The correct mask for THIS file is
        // restored from cache immediately after SHA is computed below.
        _segmentationMasks.value = null

        val sessionReuse = tryReuseOpenSessionStageA(rawUri, options)

        // 1) SAF → scratch (skipped when this editor session already decoded it)
        onStage?.invoke(if (sessionReuse != null) Stage.StageA else Stage.Copying)
        val scratch = if (sessionReuse != null) null else
            File(context.cacheDir, "raw_v3_in_${System.nanoTime()}.bin")
        if (sessionReuse == null) {
            runCatching {
                context.contentResolver.openInputStream(rawUri)?.use { ins ->
                    FileOutputStream(scratch!!).use { out -> ins.copyTo(out) }
                } ?: return@withContext fail(sourceName, "openInputStream null", scratch)
            }.onFailure {
                return@withContext fail(sourceName, "copy failed: ${it.message}", scratch)
            }
            if (scratch!!.length() == 0L)
                return@withContext fail(sourceName, "empty source after copy", scratch)
        }
        coroutineContext.ensureActive()

        // 2) Probe + SHA
        if (sessionReuse == null) onStage?.invoke(Stage.Probing)
        val nonRaw = sessionReuse?.nonRaw ?: isNonRawSource(scratch!!, sourceName)
        val probe = if (sessionReuse != null || nonRaw) null else RawV3SourceProbe.probe(scratch!!)
        val sha = sessionReuse?.sha ?: sha256(scratch!!)
        if (sessionReuse != null) {
            Log.i(TAG, "$sourceName: sha=${sha.take(8)}… skip copy+SHA (open session A.tif)")
        } else {
            Log.i(TAG, "$sourceName: sha=${sha.take(8)}… nonRaw=$nonRaw skipRcd=${probe?.skipRcd}")
        }
        // Restore the segmentation masks for THIS file if they were previously
        // computed (editor session or earlier batch pass). Keeps subject/background
        // vignette, per-segment tone moves, and smart-sharpness feathering intact
        // in the saved JPEG. A null result simply means "no mask available yet".
        _segmentationMasks.value = RawV3SegmentationStorage.load(context, sha)
        val effectiveWorkspace = if (probe?.skipRcd == true) {
            options.workspace.copy(demosaicAlgorithm = 3 /* AHD via LibRaw */)
        } else options.workspace
        coroutineContext.ensureActive()

        // 3) Stage A — synthetic for non-RAW (JPEG/PNG/…), LibRaw for RAW.
        //    Reuse an existing A.tif from the editor session if present — the
        //    batch may be exporting the same file the editor has open, and
        //    purging + re-decoding would (a) delete the editor's in-progress
        //    Stage C intermediate and (b) waste 10-15s on a second LibRaw run.
        //    Stage A output is deterministic for a given RAW file + workspace,
        //    so reuse is safe. Only purge when no valid A.tif exists.
        onStage?.invoke(Stage.StageA)
        // Route A (Camera Color Profile): grab the embedded JPEG NOW, while
        // `scratch` still exists. The A.tif cache-hit path (editor save) deletes
        // `scratch` just below, so re-extracting later for the camera-match would
        // return null → the SAVE would drop the camera-colour curve the preview
        // shows (dull/desaturated/linear-looking output). Held here, fed to the
        // match right before Stage C.
        val camProfileJpegBytes: ByteArray? =
            if (sessionReuse != null) null
            else if (options.useCameraColorProfile && !nonRaw)
                RawV3Engine.extractEmbeddedThumbnail(scratch!!.absolutePath)
            else null
        val cache = RawV3Cache(context)
        val stageATif = cache.stageATif(sha)
        // Stage A pixels include the selected camera/lens profile and every
        // other decode-time workspace option. A SHA-only hit can therefore
        // export pixels made with a previous lens choice. Reuse only a cache
        // record carrying the same decode fingerprint as this request.
        val stageAFingerprint = effectiveWorkspace.toString() + "|lfa=3"
        val cachedStageAMeta = readStageAMeta(cache.stageAMeta(sha))
        // Always delete just the Stage C intermediate so a fresh export is
        // written — never touch A.tif if it's already there.
        File(stageATif.parentFile, "stage_c.intermediate.tif").delete()
        // Route-aware cache validation. The SHA cache key carries NO route
        // information, but a ".camprofile"-marked A.tif has the Route-A camera
        // curve BAKED INTO ITS PIXELS (openRawFile bake or a previous Route-A
        // export below). Reusing it for a Route-B (RAW-depth) export silently
        // shipped camera-profiled pixels — the route selector appeared to do
        // nothing for any file previously opened/batched under Route A. When
        // poisoned, force a fresh Stage A decode (purge happens in the miss
        // branch). Route A reusing a marked A.tif stays valid (the marker is
        // exactly what tells Stage C to skip the re-compose).
        val routePoisoned = !options.useCameraColorProfile &&
            File("${stageATif.absolutePath}.camprofile").exists()
        if (routePoisoned) Log.i(TAG, "$sourceName: cached A.tif is Route-A baked " +
            "but this export is Route B — re-decoding Stage A")
        val cacheFingerprintMatches = cachedStageAMeta?.optionsFingerprint == stageAFingerprint
        if (!routePoisoned && stageATif.exists() && stageATif.length() > 0 &&
            cachedStageAMeta != null && !cacheFingerprintMatches) {
            Log.i(TAG, "$sourceName: Stage A cache fingerprint changed — re-decoding " +
                "(profile/decode options changed)")
        }
        val dims = if (!routePoisoned && cacheFingerprintMatches &&
            stageATif.exists() && stageATif.length() > 0)
            runCatching { RawV3BigTiffReader.readDims(stageATif) }.getOrNull() else null
        val stageA = if (sessionReuse != null) {
            Log.i(TAG, "$sourceName: Stage A cache hit (${sessionReuse.stageA.width}×${sessionReuse.stageA.height}) → reusing A.tif")
            sessionReuse.stageA
        } else if (dims != null) {
            Log.i(TAG, "$sourceName: Stage A cache hit (${dims.first}×${dims.second}) → reusing A.tif")
            scratch?.delete()
            cachedStageAMeta!!.toStageAResult()
        } else {
            cache.purge(sha)
            val freshTif = cache.stageATif(sha)
            if (nonRaw) synthStageAWithLensPipeline(scratch!!, freshTif, effectiveWorkspace, sourceName)
            else stageADecodeMutex.withLock {
                // Model assets come from the batch-invariant cache (loaded once
                // per coordinator lifetime, not per file). The mutex prevents a
                // concurrent batch prefetch (prewarmStageA) from running a second
                // LibRaw demosaic at the same time — see stageADecodeMutex doc.
                RawV3Engine.stageADecode(
                    rawFilePath     = scratch!!.absolutePath,
                    outTifPath      = freshTif.absolutePath,
                    options         = effectiveWorkspace,
                    hdrModelData    = if (effectiveWorkspace.hdrRecovery) loadHdrModel() else null,
                    shadowModelData = if (effectiveWorkspace.shadowRecovery) loadShadowModel() else null,
                    isLinearRaw     = probe?.alreadyDemosaiced == true,
                )
            }
        }
        if (stageA.success) {
            runCatching {
                writeStageAMeta(
                    cache.stageAMeta(sha),
                    CachedStageAMeta.from(stageA, stageAFingerprint),
                )
            }.onFailure { Log.w(TAG, "$sourceName: Stage A meta write failed: ${it.message}") }
        }
        if (!stageA.success) {
            cleanup(scratch, stageATif.parentFile)
            return@withContext fail(sourceName, "Stage A: ${stageA.error}")
        }
        Log.i(TAG, "$sourceName: Stage A ${stageA.width}×${stageA.height}")
        coroutineContext.ensureActive()

        // 4) Build ShaderParams blob.
        //   Preferred: the caller hands us the full live edit blob — use it
        //   verbatim so the saved file matches the preview exactly. Legacy
        //   fallback (batch / preset paths): reconstruct from the XMP overlay.
        val parsedCube = options.lutCubeFile?.takeIf { it.exists() }
            ?.let { parseCubeCached(it) }
        val params = run {
            var base = if (options.fullParamsBlob != null &&
                           options.fullParamsBlob.size >= 57) {
                ShaderParams.fromFloatArray(options.fullParamsBlob) ?: ShaderParams()
            } else {
                var b = ShaderParams()
                options.xmpPresetBlob?.let { b = b.withXmp(it) }
                b
            }
            if (parsedCube != null) {
                base = base.copy(
                    lutEnabled   = true,
                    lutIntensity = options.lutIntensity.coerceIn(0f, 1f),
                    lutBwForce   = com.RAZStudio.StudioRoom.feature.photo_editor
                        .presentation.components.lut
                        .isBlackAndWhiteLutPath(options.lutCubeFile?.absolutePath.orEmpty()),
                )
            }
            // Auto-Expo: analyse THIS file's own preview and fold its
            // auto-derived exposure into the Light-tab slots. Per-file, so each
            // photo lands at a balanced exposure. Runs for "Apply Auto Expo
            // only" mode AND when a preset carries an AUTO EXPO action (the
            // batch sets autoExposure=true and supplies fullParamsBlob with the
            // rest of the look — auto-expo overrides just the exposure slots).
            // Non-RAW (JPEG/PNG/…) is ALWAYS Camera Color Profile — it's already a
            // camera-rendered image, so it gets NO route-B RAW-depth tonal processing
            // (auto-expose here, smart-bright below). Use its pixels as-is.
            //
            // Headless/batch subject detection for Auto Expose. The editor gets
            // subject-PROTECTED AE because its lazy segmentation eventually runs and
            // fills _segmentationMasks; the headless batch path never triggered it,
            // so AE fell back to a GLOBAL (mask-less) solve that lifts harder and
            // OVEREXPOSED vs the editor (logged subjMask=false, +1.1–1.3 EV). Run the
            // SAME chain synchronously here (RAM-gated BiRefNet → DeepLab fallback,
            // identical to the editor) so batch AE is subject-protected too. Gated on
            // the workspace's subjectDetection flag (batch sets forceSubjectDetection
            // when Auto Expose is on). Per-file: prime the seg session for THIS sha
            // and await. `segSha != sha` skips it for the editor (openRawFile already
            // set segSha + populated masks; editor AE also usually has aeBaked=true).
            // Memory-safe: batch runs one file at a time and the chain unloads each
            // model per pass (see ensureSegmentation), so peak RAM ≈ one editor open.
            if (options.autoExposure && !options.aeBaked && !nonRaw &&
                options.workspace.subjectDetectionEnabled &&
                (segSha != sha || _segmentationMasks.value == null)) {
                _segmentationMasks.value = null
                segSha = sha
                segStageATif = stageATif
                synchronized(segModelLock) { segLaunched = false }
                ensureSegmentation()
                val segJob = segmentationJob
                // Await only the SUBJECT mask (set early in the chain by BiRefNet /
                // DeepLab fallback — before the mask-tab models). AE doesn't need
                // multiclass / cityscapes / face, so once the subject mask lands we
                // wind the rest down. cancelAndJoin (not cancel) so the old chain is
                // fully unwound before the next file starts — no two seg runs holding
                // model sessions at once.
                while (_segmentationMasks.value == null && segJob != null && segJob.isActive) {
                    kotlinx.coroutines.delay(100)
                }
                runCatching { segJob?.cancelAndJoin() }
                Log.i(TAG, "$sourceName: batch subject detection for AE " +
                    "(subjMask=${_segmentationMasks.value != null})")
            }
            // One shared 512px Stage A decode for Auto-Expo + Smart-Bright — both
            // analyse the SAME downscaled preview. Batch has no Stage B, so this
            // used to be 2 redundant BigTIFF decodes of the same file; decode once,
            // reuse, recycle at the end of this block.
            val aeWants = options.autoExposure && !options.aeBaked && !nonRaw
            val sbWants = options.smartBrightAmount > 0f && !nonRaw
            val preview512 = if (aeWants || sbWants) runCatching {
                RawV3BigTiffReader.decodeStageAToArgb8888(stageATif, maxLongSide = 512)
            }.getOrNull() else null
            if (aeWants) {
                if (preview512 != null) {
                    // Auto-Expo uses the SAME per-segment percentile-stretch
                    // engine as the in-editor Auto Expo button (workspace's
                    // subjectDetection must be enabled for per-segment to fire,
                    // otherwise the global fallback path runs).
                    val v3masks = _segmentationMasks.value
                    // Map ALL mask fields — the editor's Auto-Expose-on-Open bake
                    // passes the full set (adaptV3SegmentationMasks), and
                    // RawAutoExposure.analyse PREFERS refinedMask over the coarse
                    // 320² subjectMask. Dropping refinedMask here made batch use
                    // the coarse mask: subject luma bled into the background mean
                    // that drives the exposure decision, so batch solved a
                    // different (typically brighter) exposure than the editor for
                    // the SAME file with the SAME Auto Expose setting.
                    val masks = v3masks?.let {
                        com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation
                            .RawSegmentationMasks(
                                subjectMask   = it.subjectMask,
                                edgeMask      = it.edgeMask,
                                refinedMask   = it.refinedMask,
                                refinedWidth  = it.refinedWidth,
                                refinedHeight = it.refinedHeight,
                            )
                    }
                    val aeMacro = RawAutoExposure.analyse(
                        bitmap = preview512,
                        base = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model
                            .UserMacro(aeSubjectProtection = options.aeSubjectProtection),
                        masks = masks,
                        iso = 0,
                        subjectProtection = options.aeSubjectProtection,
                    )
                    // Fold the result into the Light-tab + per-segment slots
                    // on top of the (preset) base params.
                    // UserMacro stores these at -100..100; ShaderParams uses -1..1.
                    base = base.copy(
                        exposure         = aeMacro.exposure,
                        highlights       = aeMacro.highlights / 100f,
                        shadows          = aeMacro.shadows    / 100f,
                        whites           = aeMacro.whites     / 100f,
                        blacks           = aeMacro.blacks     / 100f,
                        highlightsSubject    = aeMacro.highlightsSubject    / 100f,
                        whitesSubject        = aeMacro.whitesSubject        / 100f,
                        blacksSubject        = aeMacro.blacksSubject        / 100f,
                        shadowsSubject       = aeMacro.shadowsSubject       / 100f,
                        highlightsBackground = aeMacro.highlightsBackground / 100f,
                        whitesBackground     = aeMacro.whitesBackground     / 100f,
                        blacksBackground     = aeMacro.blacksBackground     / 100f,
                        shadowsBackground    = aeMacro.shadowsBackground    / 100f,
                    )
                    Log.i(TAG, "$sourceName: auto-expo (RawAutoExposure) stops=${aeMacro.exposure} " +
                        "wh=${aeMacro.whites} bl=${aeMacro.blacks} sh=${aeMacro.shadows} " +
                        "subjMask=${masks != null}")
                } else {
                    Log.w(TAG, "$sourceName: auto-expo preview decode failed — exporting without expo")
                }
            }
            // Smart Bright (headless path only): the preset blob was flattened
            // with autoBrightFactor = 1, so its Smart Bright slider is inert.
            // Recover it per-file — measure THIS file's LibRaw auto-bright factor
            // from its own Stage A preview (the same value the editor computes
            // live) and fold the resulting exposure stops in, on top of any
            // auto-expo above. smartBrightAmount is 0 for the interactive editor
            // (its blob already has Smart Bright baked), so this is a no-op there.
            if (sbWants) {
                if (preview512 != null) {
                    val ab = RawAutoExposure.autoBrightMultiplier(preview512)
                    val sbStops = RawV3ActionReplay.smartBrightStops(options.smartBrightAmount, ab)
                    if (sbStops != 0f) {
                        base = base.copy(exposure = (base.exposure + sbStops).coerceIn(-4f, 4f))
                        Log.i(TAG, "$sourceName: smart-bright slider=${options.smartBrightAmount} " +
                            "AB=${"%.3f".format(ab)} +${"%.3f".format(sbStops)}EV → exposure=${base.exposure}")
                    }
                } else {
                    Log.w(TAG, "$sourceName: smart-bright preview decode failed — slider skipped")
                }
            }
            preview512?.recycle()  // shared AE + smart-bright decode, done with it
            // Auto-Expo + N NR presets: layer the Detail-tab NR on top.
            if (options.nrLevel != NrLevel.None && options.fullParamsBlob == null) {
                base = base.copy(
                    luminanceNR            = options.nrLevel.luminanceNR,
                    colorNR                = options.nrLevel.colorNR,
                    detailSmoothBackground = options.nrLevel.smoothBackground,
                )
                Log.i(TAG, "$sourceName: applied ${options.nrLevel} NR " +
                    "(lum=${options.nrLevel.luminanceNR} col=${options.nrLevel.colorNR} " +
                    "smoothBg=${options.nrLevel.smoothBackground})")
            }
            base
        }
        val paramsArr = params.toFloatArray()
        VintageFxAssets.bakeNative(context)
        Log.i(TAG, "$sourceName: export gradient blob → angle=${paramsArr.getOrNull(68)} " +
            "top[i1=${paramsArr.getOrNull(69)} tl=${paramsArr.getOrNull(75)}] " +
            "bottom[i1=${paramsArr.getOrNull(84)}] left[i1=${paramsArr.getOrNull(99)}] " +
            "right[i1=${paramsArr.getOrNull(114)}] opac=${paramsArr.getOrNull(129)} " +
            "(blobSize=${paramsArr.size}, fullBlob=${options.fullParamsBlob != null})")
        // Fade = fxVintageFade [370]; Strength [369] must be >0 for applyVintage.
        Log.i(TAG, "$sourceName: export FX blob → vintageStr=${paramsArr.getOrNull(369)} " +
            "fade=${paramsArr.getOrNull(370)} vig=${paramsArr.getOrNull(371)} " +
            "mistWash=${paramsArr.getOrNull(365)} mistInt=${paramsArr.getOrNull(454)} " +
            "texInt=${paramsArr.getOrNull(456)} glow=${paramsArr.getOrNull(372)} " +
            "orton=${paramsArr.getOrNull(209)} filmRolloff=${paramsArr.getOrNull(207)}")

        // Subject mask for RENDERING. The AE branch above runs segmentation
        // only for the auto-expose SOLVE — a preset carrying baked AE
        // (aeBaked=true) or no AE at all never entered it, so bokeh silently
        // produced no blur and every subject-scoped effect (per-segment tone,
        // vignette Subject/Background, semantic gradients, smart sharpness)
        // rendered with a null mask in batch — while the editor preview,
        // whose lazy segmentation had filled the masks, showed them working.
        // Run the same synchronous chain here whenever the FINAL params
        // actually need a mask and none is cached for this file.
        // NOTE: deliberately NOT gated on !nonRaw. The AE branch above excludes
        // non-RAW because auto-expose is RAW-depth TONAL processing that a
        // camera-rendered JPEG must not receive. Masks are spatial, not tonal:
        // the editor segments JPEGs fine (Stage A exists for non-RAW via
        // synthStageAFromBitmap), so a JPEG batch with a bokeh/subject preset
        // must segment too — copying the !nonRaw guard here made every
        // JPEG-folder batch log "bokeh skipped — no mask available".
        if (_segmentationMasks.value == null &&
            options.workspace.subjectDetectionEnabled &&
            paramsNeedSubjectMask(params)
        ) {
            segSha = sha
            segStageATif = stageATif
            synchronized(segModelLock) { segLaunched = false }
            ensureSegmentation()
            val segJob = segmentationJob
            while (_segmentationMasks.value == null && segJob != null && segJob.isActive) {
                kotlinx.coroutines.delay(100)
            }
            runCatching { segJob?.cancelAndJoin() }
            Log.i(TAG, "$sourceName: subject detection for render " +
                "(subjMask=${_segmentationMasks.value != null})")
        }

        // 5) Stage C → intermediate BigTIFF
        onStage?.invoke(Stage.StageC)
        val intermediate = File(stageATif.parentFile, "stage_c.intermediate.tif")
        // Subject mask — passed whenever available, NOT just when
        // detailSmartSharpness is on. The kernel uses it for:
        //   • Vignette per-segment routing (vigEffect = Subject/Background)
        //   • Gradient per-side semantic gating
        //   • Per-segment highlights/whites/blacks/shadows + ambiance
        //   • Smart sharpness feather
        // Gating it on smartSharpness only meant the editor preview (which
        // always sees the mask) and the save (which only got it for smart-
        // sharp photos) diverged dramatically on every other per-segment
        // effect — most visibly: vigEffect=2 (Background only) returned
        // vigGate=0 on save → vignette completely disappeared in the save.
        // Cost is trivial (320² float = 400 KB).
        val exportMasks = _segmentationMasks.value
        // Preview = export: sample the SAME mask the GL preview uploads — the
        // 1024px guided-filter refined mask when present (bestMask()), not the
        // raw 320² model output. The innerRect remap applies identically to both
        // (the refinement is a uniform upsample of the whole 320² grid).
        // (Kept to ONE local: exportSingle sits at the JVM back-end coroutine
        // transform ceiling — extra spilled locals trip "Couldn't transform
        // method node" on clean builds.)
        val exportBest = exportMasks?.gatingMask()
        // Orton sky/terrain attenuation — same max(sky, terrain) plane the GL
        // preview uploads as uBokehAttenuation. Reuses subject letterbox rect.
        // Packed as Pair(mask, side) to keep exportSingle local-spill low.
        val exportAtten = _cityscapesMasks.value?.let { cs ->
            val side = RawV3SegmentationMasks.MASK_SIZE
            if (cs.sky.size != side * side || cs.terrain.size != side * side) null
            else side to FloatArray(side * side) { i -> maxOf(cs.sky[i], cs.terrain[i]) }
        }
        // Brush-mask layers: decode each PNG's alpha to a [0,1] float plane and
        // concatenate (layer i at offset i·W·H) so Stage C applies the same
        // per-region adjustments the GL preview shows. All layers share the
        // dims of the first decoded PNG; mismatched sizes are skipped.
        val maskBundle = if (options.maskLayerBitmaps != null) {
            encodeMaskLayerBitmaps(options.maskLayerBitmaps)
        } else {
            decodeMaskLayers(options.maskLayerPaths)
        }
        // Route A (Camera Color Profile): derive a per-channel auto-matched curve
        // from THIS file's embedded JPEG preview and compose it UNDER the user's
        // tone curve, so the export adopts the in-camera colour while keeping full
        // RAW detail. RAW only — a non-RAW source has no separate camera render.
        //
        // If the editor already BAKED the camera curve into A.tif (sibling
        // ".camprofile" marker, written by openRawFile), the colour is in the
        // pixels — Stage C must apply ONLY the user tone curve, not re-compose
        // the camera curve, or it would be applied twice. The batch/headless path
        // (no prior open → no marker) still composes here as before.
        // Prefer composing under the user curve when the editor already has this
        // A.tif open: openRawFile bakes + nulls cameraMatchLut, so a missing
        // .camprofile marker must NOT re-bake (SIGBUS under concurrent mmap /
        // double-curve). Heal the marker and treat as baked.
        // Hard rule: editor session NEVER re-bakes Stage A during export —
        // compose-only / skip. Bake stays on openRawFile / true headless.
        val editorSession = openSha == sha &&
            openStageAPath == stageATif.absolutePath
        val camProfileBaked = File("${stageATif.absolutePath}.camprofile").exists() ||
            editorSession
        if (editorSession && options.useCameraColorProfile && !nonRaw &&
            !File("${stageATif.absolutePath}.camprofile").exists()) {
            runCatching { File("${stageATif.absolutePath}.camprofile").writeText("1") }
            Log.i(TAG, "$sourceName: healed missing .camprofile marker (editor session — no re-bake)")
        }
        val effectiveToneCurve: ByteArray? = if (camProfileBaked) {
            Log.i(TAG, "$sourceName: camera-color-profile already baked into A.tif — Stage C uses user curve only")
            options.toneCurveLut
        } else if (options.useCameraColorProfile && !nonRaw) {
            // Prefer the editor's EXACT preview curve (byte-identical to what the
            // user sees) when provided — guarantees preview == save. Only the
            // headless/batch path (no live preview) recomputes here, and it must
            // match the editor's full-resolution embedded-JPEG histogram, NOT a
            // 512-downscaled one (downscaling narrows the histogram → a duller,
            // less-saturated matched curve, which is the drift the editor save hit).
            val cam = options.cameraMatchLutOverride ?: runCatching {
                val baseline = RawV3BigTiffReader.decodeStageAToArgb8888(stageATif, maxLongSide = 512)
                // Decode the embedded JPEG at FULL resolution (no 512 bound) so the
                // ref histogram matches the editor's, giving an identical curve.
                val ref = camProfileJpegBytes?.let {
                    android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                }
                val out = if (baseline != null && ref != null)
                    CameraColorMatch.matchPerChannel(baseline, ref) else null
                baseline?.recycle(); ref?.recycle()
                out
            }.getOrNull()
            if (cam != null) {
                if (options.cameraMatchLutOverride == null) {
                    // Headless/batch: BAKE the camera curve into A.tif (mirrors the
                    // editor's openRawFile bake) so the preset's 3D LUT + user tone
                    // curve operate on camera-CURVED pixels — pixel-identical to
                    // single edit. Composing it into the tone-curve LUT instead left
                    // Stage A pixels NEUTRAL, so the 3D LUT saw flat pixels and
                    // crushed shadows vs the editor. After baking, Stage C reads the
                    // rewritten A.tif and applies ONLY the user curve. The marker
                    // makes a re-run skip (cache-hit path already treats it as baked).
                    val ok = RawV3Engine.applyToneCurveToStageA(stageATif.absolutePath, cam)
                    if (ok) runCatching { File("${stageATif.absolutePath}.camprofile").writeText("1") }
                    Log.i(TAG, "$sourceName: camera-color-profile BAKED into A.tif (batch parity) ok=$ok")
                    options.toneCurveLut
                } else {
                    // Editor override without open session: exact preview curve —
                    // compose so preview==save (never bake under a live mmap).
                    Log.i(TAG, "$sourceName: camera-color-profile curve applied (editor override, compose-only)")
                    CameraColorMatch.compose(cam, options.toneCurveLut)
                }
            } else {
                Log.w(TAG, "$sourceName: camera-color-profile skipped (no override + baseline/embedded-JPEG missing)")
                options.toneCurveLut
            }
        } else options.toneCurveLut

        // ── GPU canvas-matched export (preferred for 8-bit) ─────────────────
        // Grade at export working size (targetLongSide after crop/orient), not
        // full-res → linear downscale. Spatial ops scale to the graded
        // buffer's long side (preview contract at that resolution). 16-bit
        // TIFF stays on full-res CPU Stage C below.
        val preferGpuCanvasExport = when (options.format) {
            RawV3Exporter.Format.Jpg,
            RawV3Exporter.Format.WebP -> true
            else -> false
        }
        // Work dims shared by GPU path + CPU Stage C fallback (same size).
        // When preferGpu but we end on CPU, [gpuFallbackReason] is set so logs
        // + ExportResult can surface it (MAD surprises are easy to miss).
        var gpuFallbackReason: String? = null
        val gradeWork: Pair<Int, Int>? = if (preferGpuCanvasExport) {
            val d = runCatching { RawV3BigTiffReader.readDims(stageATif) }.getOrNull()
            if (d == null) {
                gpuFallbackReason = "Stage A dims read failed"
                Log.e(TAG, "$sourceName: GPU offscreen graded SKIPPED ($gpuFallbackReason) — CPU Stage C")
                null
            } else computeExportGradeWorkDims(
                srcW = d.first, srcH = d.second,
                targetLongSide = options.targetLongSide,
                scaleMode = options.scaleMode,
                cropL = options.cropL, cropT = options.cropT,
                cropR = options.cropR, cropB = options.cropB,
                cropRotationDeg = options.cropRotationDeg,
                cropRotate90 = options.cropRotate90,
            )
        } else null
        val gpuGraded: android.graphics.Bitmap? = if (gradeWork != null) {
            val ww = gradeWork.first
            val wh = gradeWork.second
            // Working-res ARGB — at targetLongSide this is ~few MP, not 40+.
            val bmp = runCatching {
                android.graphics.Bitmap.createBitmap(
                    ww, wh, android.graphics.Bitmap.Config.ARGB_8888)
            }.onFailure {
                Log.e(TAG, "$sourceName: GPU export Bitmap.createBitmap(${ww}x${wh}) OOM/fail: ${it.message}")
            }.getOrNull()
            if (bmp == null) {
                gpuFallbackReason = "bitmap alloc ${ww}x${wh} OOM/fail"
                Log.e(TAG, "$sourceName: GPU offscreen graded SKIPPED ($gpuFallbackReason) — CPU Stage C")
                null
            } else {
                val maskSide = exportBest?.second ?: 0
                val maskH = exportBest?.third ?: maskSide
                                val exportDepth = _depthMap.value?.takeIf { !it.isEmpty }
                val exportFocus = if (exportDepth != null) {
                    BokehFocusPlane.solve(
                        subject = exportBest?.first,
                        subjectW = exportBest?.second ?: 0,
                        subjectH = exportBest?.third ?: 0,
                        depth = exportDepth.depth,
                        depthW = exportDepth.width,
                        depthH = exportDepth.height,
                    )
                } else 0.5f
                val ok = RawV3Engine.renderGradedOffscreen(
                    stageATifPath = stageATif.absolutePath,
                    actionParams = paramsArr,
                    outputBitmap = bmp,
                    lutData = parsedCube?.data,
                    lutSize = parsedCube?.size ?: 0,
                    lutDomainMin = parsedCube?.domainMin ?: floatArrayOf(0f, 0f, 0f),
                    lutDomainMax = parsedCube?.domainMax ?: floatArrayOf(1f, 1f, 1f),
                    toneCurveLut = effectiveToneCurve,
                    subjectMask = exportBest?.first,
                    subjectMaskW = maskSide,
                    subjectMaskH = maskH,
                    subjectMaskRect = floatArrayOf(
                        exportMasks?.innerRectLeft ?: 0f,
                        exportMasks?.innerRectTop ?: 0f,
                        exportMasks?.innerRectRight ?: 1f,
                        exportMasks?.innerRectBottom ?: 1f,
                    ),
                    // Same decodeMaskLayers bundle Stage C gets — without this,
                    // GPU JPG/WebP export binds black brush units and drops every
                    // Mask-tab bitmap layer while the live canvas still shows them.
                    maskLayers = maskBundle?.data,
                    maskLayerW = maskBundle?.w ?: 0,
                    maskLayerH = maskBundle?.h ?: 0,
                    maskLayerCount = maskBundle?.count ?: 0,
                    attenMask = exportAtten?.second,
                    attenMaskW = exportAtten?.first ?: 0,
                    attenMaskH = exportAtten?.first ?: 0,
                    depthMap = exportDepth?.depth,
                    depthMapW = exportDepth?.width ?: 0,
                    depthMapH = exportDepth?.height ?: 0,
                    focusDepth = exportFocus,
                )
                if (ok) {
                    Log.i(TAG, "$sourceName: export path = GPU offscreen graded " +
                        "(canvas-matched) ${ww}x${wh} fade=${paramsArr.getOrNull(370)} " +
                        "vintageStr=${paramsArr.getOrNull(369)} " +
                        "brushMasks=${maskBundle?.count ?: 0}")
                    bmp
                } else {
                    bmp.recycle()
                    gpuFallbackReason = "renderGradedOffscreen failed"
                    Log.e(TAG, "$sourceName: GPU offscreen graded FAILED ($gpuFallbackReason) — falling back to CPU Stage C")
                    null
                }
            }
        } else null
        val usedCpuGpuFallback = preferGpuCanvasExport && gpuGraded == null
        if (usedCpuGpuFallback && gpuFallbackReason == null) {
            gpuFallbackReason = "GPU path unavailable"
            Log.e(TAG, "$sourceName: GPU offscreen graded SKIPPED ($gpuFallbackReason) — CPU Stage C")
        }

        val srcBitmap: android.graphics.Bitmap = if (gpuGraded != null) {
            gpuGraded
        } else {
            // CPU Stage C: for JPG/WebP, grade a pre-resized Stage A at the
            // same working size as the GPU path. TIFF / Original keep full-res.
            var stageCInput = stageATif
            var workTif: File? = null
            if (preferGpuCanvasExport && gradeWork != null) {
                val d = runCatching { RawV3BigTiffReader.readDims(stageATif) }.getOrNull()
                val ww = gradeWork.first
                val wh = gradeWork.second
                if (d != null && (ww < d.first || wh < d.second)) {
                    val tmp = File(stageATif.parentFile, "stage_a_grade_work.tif")
                    if (RawV3Engine.downsampleStageATiff(
                            stageATif.absolutePath, tmp.absolutePath, ww, wh,
                        )
                    ) {
                        stageCInput = tmp
                        workTif = tmp
                        Log.i(TAG, "$sourceName: CPU Stage C work Stage A ${ww}x${wh} " +
                            "(from ${d.first}x${d.second})")
                    }
                }
            }
            val stageC = RawV3Engine.stageCExport(
                stageATifPath = stageCInput.absolutePath,
                outputPath    = intermediate.absolutePath,
                format        = RawV3Engine.StageCFormat.Tiff16,
                targetW       = 0,
                targetH       = 0,
                actionParams  = paramsArr,
                lutData       = parsedCube?.data,
                lutSize       = parsedCube?.size ?: 0,
                lutDomainMin  = parsedCube?.domainMin ?: floatArrayOf(0f, 0f, 0f),
                lutDomainMax  = parsedCube?.domainMax ?: floatArrayOf(1f, 1f, 1f),
                subjectMask       = exportBest?.first,
                subjectMaskSize   = exportBest?.second ?: 0,
                subjectMaskH      = exportBest?.third ?: 0,
                subjectMaskRectU0 = exportMasks?.innerRectLeft   ?: 0f,
                subjectMaskRectV0 = exportMasks?.innerRectTop    ?: 0f,
                subjectMaskRectU1 = exportMasks?.innerRectRight  ?: 1f,
                subjectMaskRectV1 = exportMasks?.innerRectBottom ?: 1f,
                attenMask         = exportAtten?.second,
                attenMaskSize     = exportAtten?.first ?: 0,
                attenMaskH        = exportAtten?.first ?: 0,
                depthMap          = _depthMap.value?.takeIf { !it.isEmpty }?.depth,
                depthMapW         = _depthMap.value?.takeIf { !it.isEmpty }?.width ?: 0,
                depthMapH         = _depthMap.value?.takeIf { !it.isEmpty }?.height ?: 0,
                focusDepth        = run {
                    val dm = _depthMap.value?.takeIf { !it.isEmpty } ?: return@run 0.5f
                    BokehFocusPlane.solve(
                        subject = exportBest?.first,
                        subjectW = exportBest?.second ?: 0,
                        subjectH = exportBest?.third ?: 0,
                        depth = dm.depth,
                        depthW = dm.width,
                        depthH = dm.height,
                    )
                },
                maskLayers      = maskBundle?.data,
                maskLayerW      = maskBundle?.w ?: 0,
                maskLayerH      = maskBundle?.h ?: 0,
                maskLayerCount  = maskBundle?.count ?: 0,
                toneCurveLut    = effectiveToneCurve,
                iccProfile      = if (options.embedIcc) RawV3IccEmbed.buildSrgbV2IccProfile() else null,
            )
            workTif?.delete()
            if (!stageC.success) {
                cleanup(scratch, intermediate)
                return@withContext fail(sourceName, "Stage C: ${stageC.error}")
            }
            if (stageC.karisBloomFallback) {
                Log.w(TAG, "$sourceName: Karis bloom GPU failed; Orton/Glow fell back to Gaussian (preview↔save divergence)")
            }
            Log.i(TAG, "$sourceName: export path = CPU Stage C (apply_macro)")
            coroutineContext.ensureActive()

            // 6) BigTIFF → ARGB_8888 → format-specific encode via project compressor
            onStage?.invoke(Stage.Encoding)
            val tDec0 = System.currentTimeMillis()
            val decoded = RawV3BigTiffReader.decodeToArgb8888(intermediate)
                ?: run {
                    cleanup(scratch, intermediate)
                    return@withContext fail(sourceName, "BigTIFF decode returned null")
                }
            Log.i(TAG, "$sourceName: decodeToArgb8888 ${decoded.width}x${decoded.height} " +
                "in ${System.currentTimeMillis() - tDec0} ms")
            decoded
        }
        if (gpuGraded != null) onStage?.invoke(Stage.Encoding)
        coroutineContext.ensureActive()

        // 6a-pre) Camera Profile Guided Filter — Route A only.
        //   Applies edge-aware chroma smoothing after the colour-profile LUT has
        //   been baked into the pixels by Stage C. Uses BT.601 luma as the guide
        //   so luminance/edge detail is preserved while flat-area colour transitions
        //   (sky banding, skin cast from histogram-spec matching) are smoothed.
        val srcBitmapGF: android.graphics.Bitmap = if (options.useCameraColorProfile && options.cameraProfileGuidedFilter) {
            val t0 = System.currentTimeMillis()
            val filtered = runCatching {
                CameraColorMatch.applyChromaGuidedFilter(srcBitmap)
            }.onFailure { Log.w(TAG, "$sourceName: chroma guided filter failed: ${it.message}") }
             .getOrNull()
            if (filtered != null && filtered !== srcBitmap) {
                srcBitmap.recycle()
                Log.i(TAG, "$sourceName: camera profile guided filter in ${System.currentTimeMillis() - t0} ms")
                filtered
            } else srcBitmap
        } else srcBitmap

        // 6a) Bokeh bake (OpenCV) on the full-res bitmap.
        //     Runs here (not in Stage C / GL) because it's a heavy bitmap op.
        //     Mask selection:
        //       • If options.paintedBokehMaskPath is set: load the brush-mask PNG
        //         alpha as a 0..1 float plane. Blur is applied where alpha > 0
        //         (i.e. the painted region). The shape matches what the user
        //         drew in the Mask tab on the same action card.
        //       • Else: fall back to the U2Net subject mask. RawV3Bokeh.apply
        //         keeps its legacy semantics (inverts the mask internally to
        //         blur the BACKGROUND outside the subject).
        //       • If neither mask is available: bokeh is SKIPPED entirely.
        //     Blurring the whole photo with no targeting is never desirable.
        val srcBitmap2 = run {
            if (!options.bokeh.any) return@run srcBitmapGF
            // Select bokeh mask source: painted overrides subject.
            data class BokehMaskSource(
                val data: FloatArray?,
                val w: Int,
                val h: Int,
                val label: String,
                val isPainted: Boolean,
            )
            val src = run {
                val pp = options.paintedBokehMaskPath
                if (pp != null) {
                    val loaded = runCatching { loadPaintedMaskPng(pp) }.getOrNull()
                    if (loaded != null) BokehMaskSource(loaded.data, loaded.w, loaded.h, "painted", true)
                    else BokehMaskSource(null, 0, 0, "painted-load-failed", true)
                } else {
                    val sm = _segmentationMasks.value?.subjectMask
                    if (sm != null) BokehMaskSource(
                        sm,
                        RawV3SegmentationMasks.MASK_SIZE,
                        RawV3SegmentationMasks.MASK_SIZE,
                        "subject",
                        false,
                    ) else BokehMaskSource(null, 0, 0, "none", false)
                }
            }
            if (src.data == null) {
                Log.i(TAG, "$sourceName: bokeh skipped — no mask available (${src.label})")
                return@run srcBitmapGF
            }
            val tBk = System.currentTimeMillis()
            // Painted-mask path: user paints WHERE they want blur. Subject-mask
            // path: the legacy convention blurs OUTSIDE the subject. Flip
            // isolateSubject so RawV3Bokeh picks the right composite branch.
            val effective = options.bokeh.copy(isolateSubject = !src.isPainted)
            val baked = runCatching {
                RawV3Bokeh.apply(srcBitmapGF, effective, src.data, src.w, src.h)
            }.getOrDefault(srcBitmapGF)
            if (baked !== srcBitmapGF) srcBitmapGF.recycle()
            Log.i(TAG, "$sourceName: bokeh bake in ${System.currentTimeMillis() - tBk} ms (${src.label} mask)")
            baked
        }

        // 6a.2) FX bitmap pass removed — mist/vintage/glow/dust/blur already
        //       run inside Stage C apply_macro / GPU offscreen (mirrors GL).
        //       See RawV3FxBitmapPass.kt (intentional no-op hook retained).

        // 6b) Straighten + crop bake (RAW Export transform-bar Crop sheet).
        //     Order matters: rotate first (canvas grows to fit the rotated
        //     content), then crop the rect AGAINST the rotated canvas. The
        //     UI shows the user the rotated image with the crop overlay on
        //     top, so the rect coords are already in rotated-canvas space.
        val srcBitmap3 = run {
            // 6a) Discrete orientation (flip H/V → rotate90), applied FIRST so the
            //     straighten + crop below operate on the oriented canvas — exactly
            //     the order RawCropSheet bakes it, keeping the normalized rect valid.
            val orientedBase: android.graphics.Bitmap = run {
                val rot = ((options.cropRotate90 % 4) + 4) % 4
                if (!options.cropFlipH && !options.cropFlipV && rot == 0) srcBitmap2
                else {
                    val m = android.graphics.Matrix()
                    if (options.cropFlipH || options.cropFlipV) {
                        m.postScale(if (options.cropFlipH) -1f else 1f, if (options.cropFlipV) -1f else 1f)
                    }
                    if (rot != 0) m.postRotate(90f * rot)
                    val out = runCatching {
                        android.graphics.Bitmap.createBitmap(
                            srcBitmap2, 0, 0, srcBitmap2.width, srcBitmap2.height, m, true,
                        )
                    }.onFailure {
                        Log.w(TAG, "$sourceName: orient (rot90=$rot flipH=${options.cropFlipH} flipV=${options.cropFlipV}) failed", it)
                    }.getOrNull()
                    if (out != null && out !== srcBitmap2) {
                        srcBitmap2.recycle()
                        Log.i(TAG, "$sourceName: orient rot90=$rot flipH=${options.cropFlipH} flipV=${options.cropFlipV} → ${out.width}x${out.height}")
                        out
                    } else srcBitmap2
                }
            }
            val angle = options.cropRotationDeg
            val rotated: android.graphics.Bitmap = if (kotlin.math.abs(angle) > 0.01f) {
                val tR = System.currentTimeMillis()
                val m = android.graphics.Matrix().apply { postRotate(angle) }
                val out = runCatching {
                    android.graphics.Bitmap.createBitmap(
                        orientedBase, 0, 0, orientedBase.width, orientedBase.height, m, true,
                    )
                }.onFailure {
                    Log.w(TAG, "$sourceName: rotate $angle° failed", it)
                }.getOrNull()
                if (out != null && out !== orientedBase) {
                    orientedBase.recycle()
                    Log.i(TAG, "$sourceName: straighten ${angle}° → ${out.width}x${out.height} " +
                        "in ${System.currentTimeMillis() - tR} ms")
                    out
                } else orientedBase
            } else orientedBase

            val cl = options.cropL.coerceIn(0f, 1f)
            val ct = options.cropT.coerceIn(0f, 1f)
            val cr = options.cropR.coerceIn(0f, 1f)
            val cb = options.cropB.coerceIn(0f, 1f)
            val isIdentity = cl <= 0.001f && ct <= 0.001f && cr >= 0.999f && cb >= 0.999f
            if (isIdentity || cr <= cl || cb <= ct) return@run rotated
            val sw = rotated.width
            val sh = rotated.height
            val x = (cl * sw).toInt().coerceIn(0, sw - 1)
            val y = (ct * sh).toInt().coerceIn(0, sh - 1)
            val cw = ((cr - cl) * sw).toInt().coerceAtLeast(1).coerceAtMost(sw - x)
            val ch = ((cb - ct) * sh).toInt().coerceAtLeast(1).coerceAtMost(sh - y)
            val tC = System.currentTimeMillis()
            val cropped = runCatching {
                android.graphics.Bitmap.createBitmap(rotated, x, y, cw, ch)
            }.onFailure {
                Log.w(TAG, "$sourceName: crop $x,$y ${cw}x$ch on ${sw}x$sh failed", it)
            }.getOrNull()
            if (cropped != null && cropped !== rotated) {
                rotated.recycle()
                Log.i(TAG, "$sourceName: crop bake → ${cw}x$ch in ${System.currentTimeMillis() - tC} ms")
                cropped
            } else rotated
        }

        val targetDims = computeTargetDims(
            longSide  = options.targetLongSide,
            srcW      = srcBitmap3.width,
            srcH      = srcBitmap3.height,
            scaleMode = options.scaleMode,
        )
        Log.i(TAG, "$sourceName: export opts → targetLongSide=${options.targetLongSide} " +
            "→ dims=${targetDims.first}x${targetDims.second} (src ${srcBitmap3.width}x${srcBitmap3.height}) " +
            "embedIcc=${options.embedIcc} exifOrd=${options.exifPolicyOrdinal} fmt=${options.format}")
        val imageFormat = options.format.toImageFormat()
        // Long side BEFORE the downscale — used to derive automatic output
        // sharpening (below) proportional to how much the image is reduced.
        val preResizeLongSide = maxOf(srcBitmap3.width, srcBitmap3.height)
        // Pre-resize here rather than relying on the compressor's internal
        // scaler: AndroidImageScaler silently falls back to the ORIGINAL image
        // when its native scale path fails (getOrNull() ?: image), which left
        // exports at full 20MP (huge file, ~14s encode) and ignored the user's
        // dimensions entirely. A direct Bitmap.createScaledBitmap is reliable
        // and fast (~100ms for 20MP→2MP). After this, the bitmap already has
        // the target dims so the compressor's resize is a no-op.
        val encodeBitmap = if (targetDims.first > 0 && targetDims.second > 0 &&
            (targetDims.first != srcBitmap3.width || targetDims.second != srcBitmap3.height)) {
            val tScale = System.currentTimeMillis()
            // Ansel/darktable "finalscale" approach: downsample in linear RGB,
            // NOT in sRGB. Bitmap.createScaledBitmap averages sRGB bytes directly,
            // which produces dark fringes on high-contrast edges (the sRGB OETF
            // makes 50%-gray of two values ≠ the perceptual midpoint). Linear-
            // domain downscale fixes this. Falls back to createScaledBitmap on
            // failure so export never breaks.
            val scaled = runCatching {
                linearDownscale(srcBitmap3, targetDims.first, targetDims.second)
            }.getOrElse {
                Log.w(TAG, "$sourceName: linear downscale failed, falling back", it)
                android.graphics.Bitmap.createScaledBitmap(
                    srcBitmap3, targetDims.first, targetDims.second, /*filter=*/true,
                )
            }
            if (scaled !== srcBitmap3) srcBitmap3.recycle()
            Log.i(TAG, "$sourceName: pre-resize (linear) → ${scaled.width}x${scaled.height} " +
                "in ${System.currentTimeMillis() - tScale} ms")
            scaled
        } else srcBitmap3
        // ── DnCNN neural luma denoise (flat-region gated) + chroma blur ──
        //   Gated by the workspace "AI Denoise" (AI Enhance) toggle, reused so
        //   users get one switch. Runs on the export bitmap BEFORE sharpen,
        //   model assets/models/dncnn_gray_s15.tflite (≤16 MP). Flat-gated so
        //   edges/thin lines stay crisp. The 16-bit TIFF path (runStageC) and
        //   the live GL preview never reach this code, so they bypass it.
        // Skip the export DnCNN when the import already baked a denoised baseline
        // into this A.tif (editor save reuses that A.tif). Batch re-decodes fresh
        // → no marker → still denoises here as before. Avoids double-denoise.
        val baselineBaked = File("${stageATif.absolutePath}.dncnn").exists()
        // Non-RAW = Camera Color Profile only → no route-B AI Enhance (DnCNN) either.
        val neuralDenoise = options.workspace.enhanceEnabled && !baselineBaked && !nonRaw
        Log.i(TAG, "$sourceName: DnCNN neural-denoise toggle=$neuralDenoise baselineBaked=$baselineBaked " +
            "modelPresent=${DncnnLumaDenoiser.isModelPresent(context)}")
        val denoisedBitmap = if (neuralDenoise) {
            runCatching { DncnnLumaDenoiser.denoise(context, encodeBitmap, strength = 0.45f) }
                .getOrElse { Log.w(TAG, "$sourceName: DnCNN denoise failed", it); encodeBitmap }
        } else encodeBitmap
        if (denoisedBitmap !== encodeBitmap) encodeBitmap.recycle()
        // Post-resize sharpen for ALL scale modes. The compressor's scaler only
        // sharpens on RAZSharp (libresize), and the pre-resize above bypasses
        // the scaler anyway — so we run the unsharp here on the final
        // downscaled bitmap, making the Low/Med/High level work everywhere.
        // Explicit user level (Low/Med/High) takes priority. When the user left
        // it at None, apply AUTOMATIC output sharpening for 8-bit lossy formats
        // (JPG/WebP/HEIC/AVIF) scaled to the downscale ratio — Lanczos/linear
        // downscaling is faithful but slightly soft at the reduced resolution, so
        // a gentle USM restores crisp "vector-like" edges WYSIWYG at the size the
        // user chose. 16-bit TIFF/PNG keep their own (param-379) sharpen path and
        // are intentionally excluded here.
        val explicitSharpen = options.resizeSharpen.toDomainResizeSharpen().strength
        val isLossy8Bit = when (options.format) {
            RawV3Exporter.Format.Jpg, RawV3Exporter.Format.WebP,
            RawV3Exporter.Format.Heic, RawV3Exporter.Format.Avif -> true
            else -> false
        }
        val outLongSide = maxOf(denoisedBitmap.width, denoisedBitmap.height)
        val downscaleRatio = if (outLongSide > 0) preResizeLongSide.toFloat() / outLongSide else 1f
        val sharpenStrength = when {
            explicitSharpen > 0f -> explicitSharpen
            isLossy8Bit && downscaleRatio > 1.2f ->
                // (R-1)*0.15 hits the benchmarks exactly: R=2→0.15 (Low),
                // R=3→0.30 (Medium), capping at 0.60 (High) for severe downsizes
                // (e.g. 45MP→2MP, R≈5) where the most edge data is discarded. The
                // clamp prevents ringing; mild resizes (<1.2x) stay untouched.
                ((downscaleRatio - 1f) * 0.15f).coerceIn(0f, 0.60f)
            else -> 0f
        }
        if (sharpenStrength > 0f && explicitSharpen <= 0f) {
            Log.i(TAG, "$sourceName: auto output-sharpen s=$sharpenStrength " +
                "(downscale ${"%.2f".format(downscaleRatio)}x, fmt=${options.format})")
        }
        val sharpenedBitmap = if (sharpenStrength > 0f && denoisedBitmap.width > 6 && denoisedBitmap.height > 6) {
            val tSh = System.currentTimeMillis()
            val out = runCatching { unsharpMask(denoisedBitmap, sharpenStrength) }.getOrDefault(denoisedBitmap)
            if (out !== denoisedBitmap) denoisedBitmap.recycle()
            Log.i(TAG, "$sourceName: resize-sharpen ${options.resizeSharpen} " +
                "(s=$sharpenStrength) in ${System.currentTimeMillis() - tSh} ms")
            out
        } else denoisedBitmap
        val hilt = RawV3HiltAccess.resolve(context)
        // 6c) Watermark burn — applied AFTER resize+sharpen, so position anchors
        //     are relative to the final bitmap dims and text/logo sizing is
        //     proportional to the output photo. Never touches the in-session
        //     preview (that's a Compose draw-over overlay in RawWatermarkSheet).
        //
        //     For EXIF watermarks, per-file metadata must be read HERE (before
        //     burn) so make/model/lens/exposure values are available. We also
        //     cache the result in earlySourceMetadata and reuse it at publish
        //     time to avoid a second readMetadata call on the same file.
        val earlySourceMetadata: com.RAZStudio.StudioRoom.core.domain.image.Metadata? =
            if (options.watermarkConfig != null || options.exifPolicyOrdinal == 0 || options.exifPolicyOrdinal == 1) {
                runCatching { hilt.fileController().readMetadata(rawUri.toString()) }.getOrNull()
            } else null
        val watermarkedBitmap = options.watermarkConfig?.let { cfg ->
            // If the config has an EXIF section, merge per-file metadata into it.
            // mergeExifFromDomainMetadata respects user overrides already stored in
            // cfg.exif.make/model/lensModel (empty = use per-file value).
            val effectiveCfg = if (cfg.exif != null && earlySourceMetadata != null) {
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                    .mergeExifFromDomainMetadata(context, cfg, earlySourceMetadata)
            } else cfg
            val tWm = System.currentTimeMillis()
            val wm = runCatching {
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                    .burnCombinedWatermarkOnto(sharpenedBitmap, effectiveCfg, ctx = context)
            }.onFailure { Log.w(TAG, "$sourceName: watermark burn failed", it) }
             .getOrDefault(sharpenedBitmap)
            if (wm !== sharpenedBitmap) sharpenedBitmap.recycle()
            Log.i(TAG, "$sourceName: watermark burned in ${System.currentTimeMillis() - tWm} ms")
            wm
        } ?: sharpenedBitmap

        // Border/frame — outermost layer, full-res, after the watermark so the
        // watermark sits on the PHOTO and the frame around it (Export page rule).
        val hasBorder = options.borderThickness > 0f
        val framedBitmap = if (hasBorder) {
            val tB = System.currentTimeMillis()
            val framed = runCatching {
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.applyBorderToBitmap(watermarkedBitmap, options.borderThickness, options.borderColorArgb)
            }.onFailure { Log.w(TAG, "$sourceName: border failed", it) }.getOrDefault(watermarkedBitmap)
            // NOT recycled here: the 16-bit path still composites the un-framed
            // watermark bitmap (photo dims) before framing the F16 result.
            Log.i(TAG, "$sourceName: border ${(options.borderThickness * 100).toInt()}% → " +
                "${framed.width}x${framed.height} in ${System.currentTimeMillis() - tB} ms")
            framed
        } else watermarkedBitmap

        val tEnc0 = System.currentTimeMillis()

        // ── 16-bit path (PNG-16, TIFF-16, HEIC-16, AVIF-16) ──────────────────
        // Bypasses the ARGB_8888 Bitmap encoder entirely. Re-reads the Stage C
        // TIFF intermediate (rgb16 uint sRGB already on disk), applies crop +
        // area-filter resize + Laplacian sharpen at full 16-bit depth, then
        // composites the 8-bit watermark bitmap on top before encoding.
        //
        // Bokeh, FX and rotation were already baked into watermarkedBitmap —
        // for rotation the 8-bit result is used as the watermark layer which
        // carries the rotated pixels composited at 16-bit. For no-rotation
        // cases the watermark layer is transparent outside its drawn regions.
        val is16BitFormat = when (options.format) {
            RawV3Exporter.Format.Png16,
            RawV3Exporter.Format.Tiff16,
            RawV3Exporter.Format.Heic16 -> true
            else -> false
        }
        val encoded: ByteArray = if (is16BitFormat) {
            // Crop rect in Stage C TIFF pixel space. srcBitmap (pre-bokeh, pre-fx,
            // pre-crop) has the Stage C dims; compute the same crop the 8-bit chain used.
            val stageW = srcBitmap.width
            val stageH = srcBitmap.height
            val cl = options.cropL.coerceIn(0f, 1f); val ct = options.cropT.coerceIn(0f, 1f)
            val cr = options.cropR.coerceIn(0f, 1f); val cb = options.cropB.coerceIn(0f, 1f)
            val hasCrop = !(cl <= 0.001f && ct <= 0.001f && cr >= 0.999f && cb >= 0.999f)
            val cropX = if (hasCrop) (cl * stageW).toInt().coerceIn(0, stageW - 1) else 0
            val cropY = if (hasCrop) (ct * stageH).toInt().coerceIn(0, stageH - 1) else 0
            val cropW = if (hasCrop) ((cr - cl) * stageW).toInt().coerceAtLeast(1).coerceAtMost(stageW - cropX) else 0
            val cropH = if (hasCrop) ((cb - ct) * stageH).toInt().coerceAtLeast(1).coerceAtMost(stageH - cropY) else 0
            // Target dims from the 8-bit chain's final bitmap dims.
            val finalW = watermarkedBitmap.width
            val finalH = watermarkedBitmap.height
            val sharpen16 = if (options.fullParamsBlob != null && options.fullParamsBlob.size > 379)
                options.fullParamsBlob[379].coerceIn(0f, 1f) else 0f
            // Watermark bitmap carries any burned overlays (text, image, EXIF). Pass it
            // for 16-bit compositing only when something was actually burned.
            val wmBitmap = if (options.watermarkConfig != null) watermarkedBitmap else null

            if (options.format == RawV3Exporter.Format.Heic16 || hasBorder) {
                // 16-bit HEIC: run the same 16-bit crop/resize/sharpen/watermark
                // pass, but render into an RGBA_F16 Bitmap and feed it to the
                // project's HeifCoder-backed compressor. Also the route for a
                // BORDERED PNG-16/TIFF-16: the native file encoder cannot grow
                // the canvas, so frame the F16 bitmap and write it with the
                // 16-bit writers (same code the 16-bit direct save uses).
                val f16Bitmap = android.graphics.Bitmap.createBitmap(
                    finalW, finalH, android.graphics.Bitmap.Config.RGBA_F16,
                )
                val r16 = RawV3Engine.encode16BitToBitmap(
                    rgb16TifPath    = intermediate.absolutePath,
                    outputBitmap    = f16Bitmap,
                    cropX = cropX, cropY = cropY, cropW = cropW, cropH = cropH,
                    dstW  = if (finalW != (if (hasCrop) cropW else stageW)) finalW else 0,
                    dstH  = if (finalH != (if (hasCrop) cropH else stageH)) finalH else 0,
                    sharpenAmount   = sharpen16,
                    watermarkBitmap = wmBitmap,
                )
                if (!r16.success) {
                    f16Bitmap.recycle()
                    cleanup(scratch, intermediate)
                    return@withContext fail(sourceName, "encode16BitToBitmap failed: ${r16.error}")
                }
                val f16Final = if (hasBorder) {
                    val framed = runCatching {
                        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.applyBorderToBitmap(f16Bitmap, options.borderThickness, options.borderColorArgb)
                    }.getOrDefault(f16Bitmap)
                    if (framed !== f16Bitmap) f16Bitmap.recycle()
                    framed
                } else f16Bitmap
                val q = options.qualityPct.coerceIn(1, 100)
                val codecQuality = Quality.Base(q)
                val bytes = runCatching {
                    when (options.format) {
                        RawV3Exporter.Format.Png16 ->
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Png16Writer.encodeRgb16(
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Bitmap16Sampler.extractRgb16(f16Final),
                                f16Final.width, f16Final.height,
                            )
                        RawV3Exporter.Format.Tiff16 ->
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Tiff16Writer.encodeRgb16(
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw.export.Bitmap16Sampler.extractRgb16(f16Final),
                                f16Final.width, f16Final.height,
                                iccProfile = if (options.embedIcc) RawV3IccEmbed.buildSrgbV2IccProfile() else null,
                            )
                        else -> hilt.imageCompressor().compress(f16Final, imageFormat, codecQuality)
                    }
                }.getOrElse { e ->
                    f16Final.recycle()
                    cleanup(scratch, intermediate)
                    return@withContext fail(sourceName, "16-bit encode failed: ${e.message}")
                }
                f16Final.recycle()
                bytes
            } else {
                val outFmt  = if (options.format == RawV3Exporter.Format.Png16) 0 else 1
                val outFile = File(stageATif.parentFile, "stage_c_16bit_out.tmp")
                val r16 = RawV3Engine.encode16Bit(
                    rgb16TifPath  = intermediate.absolutePath,
                    outputPath    = outFile.absolutePath,
                    outputFormat  = outFmt,
                    cropX = cropX, cropY = cropY, cropW = cropW, cropH = cropH,
                    dstW  = if (finalW != (if (hasCrop) cropW else stageW)) finalW else 0,
                    dstH  = if (finalH != (if (hasCrop) cropH else stageH)) finalH else 0,
                    sharpenAmount = sharpen16,
                    watermarkBitmap = wmBitmap,
                    iccProfile = if (options.embedIcc) RawV3IccEmbed.buildSrgbV2IccProfile() else null,
                )
                if (!r16.success) {
                    cleanup(scratch, intermediate)
                    return@withContext fail(sourceName, "encode16Bit failed: ${r16.error}")
                }
                val bytes = runCatching { outFile.readBytes() }.getOrElse { e ->
                    cleanup(scratch, intermediate)
                    return@withContext fail(sourceName, "16-bit read-back failed: ${e.message}")
                }
                outFile.delete()
                bytes
            }
        } else {
            // ── 8-bit path (JPG, WebP, HEIC, AVIF, …) ───────────────────────
            // The bitmap is already at the correct dims and fully graded by
            // Stage C; we only need a raw byte encode with no extra scaling,
            // tone adjustment, or ICC inject.
            val q = options.qualityPct.coerceIn(1, 100)
            runCatching {
                when (options.format) {
                    RawV3Exporter.Format.Heic,
                    RawV3Exporter.Format.Avif -> {
                        // Bitmap.compress() has no HEIC/AVIF codec; without this
                        // branch it silently emits JPEG with the wrong extension.
                        val quality = when (options.format) {
                            RawV3Exporter.Format.Avif -> Quality.Avif(
                                qualityValue = q,
                                effort = 0
                            )
                            else -> Quality.Base(q)
                        }
                        hilt.imageCompressor().compress(
                            image = framedBitmap,
                            imageFormat = imageFormat,
                            quality = quality,
                        )
                    }
                    else -> {
                        @Suppress("DEPRECATION")
                        val fmt = when (options.format) {
                            RawV3Exporter.Format.WebP ->
                                if (android.os.Build.VERSION.SDK_INT >= 30)
                                    android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                                else
                                    android.graphics.Bitmap.CompressFormat.WEBP
                            else -> android.graphics.Bitmap.CompressFormat.JPEG
                        }
                        java.io.ByteArrayOutputStream(framedBitmap.width * framedBitmap.height).also { bos ->
                            framedBitmap.compress(fmt, q, bos)
                        }.toByteArray()
                    }
                }
            }.getOrElse { e ->
                cleanup(scratch, intermediate)
                return@withContext fail(sourceName, "encode failed: ${e.message}")
            }
        }

        // Unified ImageInfo for publishing (width/height reflect final output dims).
        val info = ImageInfo(
            width          = framedBitmap.width,
            height         = framedBitmap.height,
            quality        = Quality.Base(options.qualityPct.coerceIn(0, 100)),
            imageFormat    = imageFormat,
            resizeType     = ResizeType.Explicit,
            imageScaleMode = options.scaleMode.toDomainScaleMode(),
            resizeSharpen  = DomainResizeSharpen.None,
        )
        Log.i(TAG, "$sourceName: encode ${info.width}x${info.height} " +
            "fmt=${options.format} → ${encoded.size} bytes in ${System.currentTimeMillis() - tEnc0} ms")
        if (framedBitmap !== watermarkedBitmap) runCatching { watermarkedBitmap.recycle() }
        coroutineContext.ensureActive()

        // ICC handling for encoded bytes.
        // embedIcc=true  → inject sRGB ICC so color-managed viewers honour it.
        // embedIcc=false → strip any ICC Android injected automatically (Bitmap.compress
        //                  on API 26+ embeds sRGB APP2 even when we didn't ask for it),
        //                  so the baked pixel values are shown verbatim by all viewers.
        val bytesToSave = when {
            options.embedIcc -> when (options.format) {
                RawV3Exporter.Format.Png16 -> RawV3IccEmbed.embedSrgbChunkInPng(encoded)
                RawV3Exporter.Format.Jpg   -> RawV3IccEmbed.embedSrgbProfileInJpeg(encoded)
                else -> encoded
            }
            options.format == RawV3Exporter.Format.Jpg ->
                RawV3IccEmbed.stripIccFromJpeg(encoded)
            else -> encoded
        }

        // Debug-only — stash the encoded bytes to a known app-cache path so
        // the Phase 4 verify harness can read them directly. Avoids chasing
        // the user-facing SAF display path (e.g. "Device storage/Pictures/...")
        // which isn't a real filesystem path. Skipped on release builds.
        if (com.RAZStudio.StudioRoom.feature.photo_editor.BuildConfig.DEBUG) {
            runCatching {
                val ext = when (options.format) {
                    RawV3Exporter.Format.Jpg -> "jpg"
                    RawV3Exporter.Format.WebP -> "webp"
                    RawV3Exporter.Format.Png16 -> "png"
                    RawV3Exporter.Format.Tiff16 -> "tif"
                    RawV3Exporter.Format.Heic,
                    RawV3Exporter.Format.Heic16 -> "heic"
                    RawV3Exporter.Format.Avif -> "avif"
                }
                val verifyDir = File(context.cacheDir, "verify_lastsave")
                    .apply { if (!exists()) mkdirs() }
                // Single rolling slot; previous contents replaced.
                verifyDir.listFiles()?.forEach { it.delete() }
                val out = File(verifyDir, "lastsave.$ext")
                out.outputStream().use { it.write(bytesToSave) }
                Log.i(TAG, "$sourceName: verify lastsave dumped → ${out.absolutePath} (${bytesToSave.size} bytes)")
            }.onFailure { Log.w(TAG, "verify lastsave dump failed", it) }
        }

        // 7) Publish through Settings-aware FileController, or write directly to
        //    a caller-supplied File (for ephemeral intermediates like heal prep).
        onStage?.invoke(Stage.Publishing)
        val directFile = options.directOutputFile
        if (directFile != null) {
            // Direct-file path: write bytes to disk, skip gallery/MediaStore.
            val tPub0 = System.currentTimeMillis()
            runCatching { directFile.writeBytes(bytesToSave) }.onFailure { e ->
                cleanup(scratch, intermediate)
                return@withContext fail(sourceName, "direct write failed: ${e.message}")
            }
            Log.i(TAG, "$sourceName: direct write ${bytesToSave.size} bytes in ${System.currentTimeMillis() - tPub0} ms")
            cleanup(scratch, intermediate)
            return@withContext ExportResult.Success(
                sourceName = sourceName,
                savedAt    = directFile.absolutePath,
                widthPx    = info.width,
                heightPx   = info.height,
                bytes      = bytesToSave.size.toLong(),
                totalMs    = System.currentTimeMillis() - t0,
                usedCpuGpuFallback = usedCpuGpuFallback,
                cpuGpuFallbackReason = gpuFallbackReason,
            )
        }
        // EXIF: build the effective metadata object based on the chosen policy.
        // We always read source metadata (if available) and then filter it,
        // so StripSensitive keeps camera/lens/copyright while removing GPS
        // and serial numbers, and NoneExceptSoftware writes only the software tag.
        val sourceMetadata = earlySourceMetadata
        val sensitiveTags = listOf(
            MetadataTag.BodySerialNumber,
            MetadataTag.LensSerialNumber,
            MetadataTag.CameraOwnerName,
            MetadataTag.ImageUniqueId,
            MetadataTag.MakerNote,
        ) + MetadataTag.gpsEntries
        val exifPolicy = RawV3Exporter.ExifPolicy.entries.getOrNull(options.exifPolicyOrdinal)
            ?: RawV3Exporter.ExifPolicy.KeepAll
        val effectiveMetadata: Metadata? = when (exifPolicy) {
            RawV3Exporter.ExifPolicy.KeepAll -> sourceMetadata
            RawV3Exporter.ExifPolicy.StripSensitive -> sourceMetadata?.readOnly()?.clearAttributes(sensitiveTags)
            RawV3Exporter.ExifPolicy.NoneExceptSoftware -> metadataOf(
                mapOf(MetadataTag.Software to RawV3Exporter.SOFTWARE_TAG)
            )
        }
        val publishTarget = ImageSaveTarget(
            imageInfo      = info.copy(originalUri = rawUri.toString()),
            originalUri    = rawUri.toString(),
            sequenceNumber = null,
            data           = bytesToSave,
            metadata       = effectiveMetadata,
        )
        // When metadata is supplied, FileController.copyMetadata uses it as the
        // initialExif source; keepOriginalMetadata is therefore irrelevant. We
        // keep it false so the destination is cleared first and only the filtered
        // tags (or none) are written.
        val keepMeta = false
        val tPub0 = System.currentTimeMillis()
        val publish = runCatching {
            hilt.fileController().save(
                saveTarget             = publishTarget,
                keepOriginalMetadata   = keepMeta,
                oneTimeSaveLocationUri = options.oneTimeSaveLocationUri,
            )
        }.getOrElse { e ->
            cleanup(scratch, intermediate)
            return@withContext fail(sourceName, "publish failed: ${e.message}")
        }
        Log.i(TAG, "$sourceName: fileController.save (keepMeta=$keepMeta) in ${System.currentTimeMillis() - tPub0} ms")

        // 8) Cleanup + return
        cleanup(scratch, intermediate)
        val totalMs = System.currentTimeMillis() - t0
        return@withContext publishToResult(
            publish, sourceName, info.width, info.height, bytesToSave.size.toLong(), totalMs,
            usedCpuGpuFallback = usedCpuGpuFallback,
            cpuGpuFallbackReason = gpuFallbackReason,
        )
    }

    /**
     * Map a [SaveResult] from the FileController publish step to an
     * [ExportResult]. Extracted from [exportSingle] purely to keep that
     * coroutine below the Kotlin JVM back-end's per-method transform ceiling —
     * no behavioural change.
     */
    private fun publishToResult(
        publish: SaveResult,
        sourceName: String,
        widthPx: Int,
        heightPx: Int,
        bytes: Long,
        totalMs: Long,
        usedCpuGpuFallback: Boolean = false,
        cpuGpuFallbackReason: String? = null,
    ): ExportResult = when (publish) {
        is SaveResult.Success -> ExportResult.Success(
            sourceName = sourceName,
            savedAt    = publish.savingPath,
            widthPx    = widthPx,
            heightPx   = heightPx,
            bytes      = bytes,
            totalMs    = totalMs,
            savedToFallbackFolder = publish.savedToFallbackFolder,
            savedUri   = publish.savedUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
            usedCpuGpuFallback = usedCpuGpuFallback,
            cpuGpuFallbackReason = cpuGpuFallbackReason,
        )
        is SaveResult.Skipped -> ExportResult.Skipped(sourceName, "FileController skipped")
        is SaveResult.Error.MissingPermissions ->
            ExportResult.Failure(sourceName, "missing permissions")
        is SaveResult.Error.Exception ->
            ExportResult.Failure(sourceName, "publish error: ${publish.throwable.message}")
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    /**
     * Bilinear upsample a float mask from (sw×sh) to (dw×dh). Used to lift
     * U2Net's 320² output to the resolution of the guided-filter luma guide.
     */
    /**
     * U2Net general-saliency subject mask (see [u2netFallbackProcessor]). Runs
     * on the v2 processor's own single-thread dispatcher; 30 s cap. Returns the
     * v3 mask type (raw 320², no refinement — the caller refines like BiRefNet's
     * output) or null. Session is released after a successful run; on timeout
     * we still [release] — [OrtSessionGate] defers OrtSession.close until the
     * in-flight run returns (safe vs the old drop-without-close leak).
     */
    private suspend fun u2netSubjectMasks(bmp: android.graphics.Bitmap, sourceName: String): RawV3SegmentationMasks? {
        val t0 = System.currentTimeMillis()
        val proc = u2netFallbackProcessor
            ?: com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation
                .RawSegmentationProcessor(context).also { u2netFallbackProcessor = it }
        if (!proc.hasModel) {
            Log.w(TAG, "$sourceName: U2Net fallback unavailable (model missing)")
            return null
        }
        val v2 = withTimeoutOrNull(30_000L) { proc.compute(bmp) }
        val side = RawV3SegmentationMasks.MASK_SIZE
        // Always request release — gate defers close if run is still in flight
        // (timeout / cancel). Drop the coordinator reference either way.
        runCatching { proc.release() }
        u2netFallbackProcessor = null
        if (v2 == null) {
            Log.w(TAG, "$sourceName: U2Net fallback null/timeout after ${System.currentTimeMillis() - t0} ms")
            return null
        }
        if (v2.subjectMask.size != side * side || v2.edgeMask.size != side * side) {
            Log.w(TAG, "$sourceName: U2Net fallback size mismatch (${v2.subjectMask.size}) — ignored")
            return null
        }
        Log.i(TAG, "$sourceName: U2Net fallback subject mask in ${System.currentTimeMillis() - t0} ms")
        return RawV3SegmentationMasks(subjectMask = v2.subjectMask, edgeMask = v2.edgeMask)
    }

    /**
     * Cache-hit companion of the refine step inside [ensureSegmentation]: the
     * disk cache holds only the 320² arrays, so rebuild the guided-filter
     * refined matte from Stage A luma. Falls back to [cached] unchanged on any
     * failure. Kept OUT of the segmentation lambda on purpose — that suspend
     * body is near the JVM back-end transform ceiling.
     */
    private fun refineCachedMasks(cached: RawV3SegmentationMasks, stageATif: File): RawV3SegmentationMasks =
        runCatching {
            val (lArr, lw, lh) = RawV3BigTiffReader.decodeStageALumaLinear(
                file = stageATif,
                maxLongSide = RawV3SegmentationMasks.REFINED_LONG_SIDE,
            ) ?: return@runCatching cached
            val side = RawV3SegmentationMasks.MASK_SIZE
            val up = upsampleMaskBilinear(cached.subjectMask, side, side, lw, lh)
            val refined = RawV3Engine.guidedFilterRefine(
                guideLinearLuma = lArr, mask = up, width = lw, height = lh,
                radius = 32, eps = 1e-3f, scale = 4,
            ) ?: return@runCatching cached
            cached.copy(refinedMask = refined, refinedWidth = lw, refinedHeight = lh)
        }.getOrDefault(cached)

    private fun upsampleMaskBilinear(
        src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int,
    ): FloatArray {
        val out = FloatArray(dw * dh)
        val xs = if (dw > 1) (sw - 1).toFloat() / (dw - 1) else 0f
        val ys = if (dh > 1) (sh - 1).toFloat() / (dh - 1) else 0f
        for (y in 0 until dh) {
            val fy = y * ys
            val y0 = fy.toInt()
            val y1 = (y0 + 1).coerceAtMost(sh - 1)
            val ty = fy - y0
            val r0 = y0 * sw
            val r1 = y1 * sw
            val dRow = y * dw
            for (x in 0 until dw) {
                val fx = x * xs
                val x0 = fx.toInt()
                val x1 = (x0 + 1).coerceAtMost(sw - 1)
                val tx = fx - x0
                val a = src[r0 + x0] * (1f - tx) + src[r0 + x1] * tx
                val b = src[r1 + x0] * (1f - tx) + src[r1 + x1] * tx
                out[dRow + x] = a * (1f - ty) + b * ty
            }
        }
        return out
    }

    private fun fail(name: String, reason: String, vararg toCleanup: File?): ExportResult {
        cleanup(*toCleanup)
        Log.w(TAG, "$name failed: $reason")
        return ExportResult.Failure(name, reason)
    }

    private fun cleanup(vararg paths: File?) {
        paths.forEach { f ->
            runCatching {
                if (f == null) return@runCatching
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
    }

    /**
     * True for already-decoded image formats (no Bayer mosaic). Detects by
     * MAGIC BYTES first (extension-independent — MediaStore/SAF opens give a
     * numeric id with no extension, which broke the extension-only check), then
     * falls back to the filename extension.
     */
    private fun isNonRawSource(scratch: File, name: String): Boolean {
        val magic = runCatching {
            scratch.inputStream().use { ins ->
                val b = ByteArray(12)
                val n = ins.read(b)
                if (n < 4) return@runCatching false
                // JPEG: FF D8 FF
                if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return@runCatching true
                // PNG: 89 50 4E 47
                if (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                    b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()) return@runCatching true
                // BMP: 42 4D ("BM")
                if (b[0] == 0x42.toByte() && b[1] == 0x4D.toByte()) return@runCatching true
                // WebP: "RIFF"...."WEBP"
                if (n >= 12 && b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
                    b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&
                    b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
                    b[10] == 0x42.toByte() && b[11] == 0x50.toByte()) return@runCatching true
                false
            }
        }.getOrDefault(false)
        if (magic) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "jpe", "png", "webp", "bmp", "tif", "tiff")
    }

    /**
     * Synthetic Stage A for non-RAW sources: decode the file to an
     * EXIF-oriented ARGB_8888 bitmap, extract RGBA bytes, and write the same
     * Stage A BigTIFF a RAW decode produces. Everything downstream is unchanged.
     */
    /**
     * Non-RAW (JPEG/PNG/WebP…) Stage A: decode to A.tif, then the SAME lens
     * pipeline the RAW decode gets — user-selected camera+lens profile
     * (geometry/vignette/TCA) followed by Rayxie CA — and one LENS-REPORT line.
     * Shared by [openRawFile] and [exportRawToGallery]: the headless export used
     * to call [synthStageAFromBitmap] directly, so a JPEG exported without a
     * prior editor open (Gallery "Export photos", folder batch, Canon/Sony sync)
     * silently skipped both corrections while the editor's preview had them.
     * Deletes [scratch] (callers only pass it to cleanup afterwards).
     */
    private fun synthStageAWithLensPipeline(
        scratch: File,
        linearTif: File,
        workspace: RawV3WorkspaceOptions,
        sourceName: String,
    ): RawV3Engine.StageAResult {
        val syn = synthStageAFromBitmap(scratch, linearTif)
        // Read the shot's focal + aperture from the file's OWN EXIF while the
        // scratch copy still exists. The RAW path gets these from LibRaw; the
        // synthetic path has no decoder, so without this the correction was
        // called with focal=0 and `lfa_correct_rgba_f16` bailed at its
        // `focalMm <= 0 -> return false` guard — i.e. JPEG lens correction
        // silently never applied whenever EXIF carried a focal (which is also
        // exactly when the UI hides the manual focal field). The manual
        // override still wins when present (adapted lenses report no focal).
        var exifFocalMm = 0f
        var exifApertureF = 0f
        var exifCameraMake = ""
        var exifCameraModel = ""
        var exifLensMake = ""
        var exifLensModel = ""
        runCatching {
            val ex = android.media.ExifInterface(scratch.absolutePath)
            exifCameraMake = ex.getAttribute(android.media.ExifInterface.TAG_MAKE).orEmpty()
            exifCameraModel = ex.getAttribute(android.media.ExifInterface.TAG_MODEL).orEmpty()
            exifLensMake = ex.getAttribute("LensMake").orEmpty()
            exifLensModel = ex.getAttribute("LensModel").orEmpty()
            exifFocalMm = ex.getAttributeDouble(
                android.media.ExifInterface.TAG_FOCAL_LENGTH, 0.0).toFloat()
            exifApertureF = ex.getAttributeDouble(
                android.media.ExifInterface.TAG_F_NUMBER, 0.0).toFloat()
        }
        scratch.delete()
        // JPEG/PNG Lensfun: the synthetic Stage A starts with no correction.
        // Prefer the user's exact camera/lens selection when present; otherwise
        // pass the source EXIF identity to the same strict native matcher used
        // for RAW. This keeps detection primary and manual selection secondary,
        // without treating an EXIF lens-type string as a profile by itself.
        val effectiveCameraModel = workspace.lensfunCameraId.ifBlank { exifCameraModel }
        val effectiveLensModel = workspace.lensfunLensId.ifBlank { exifLensModel }
        var lensReport = if (effectiveLensModel.isBlank()) "lens=NONE (no lens detected)"
                         else "lens=NONE (lens DB missing)"
        if (syn.success &&
            workspace.lensfunDbDir.isNotBlank()
        ) {
            val tLf = System.currentTimeMillis()
            val effFocal = if (workspace.lensfunFocalOverrideMm > 0f)
                workspace.lensfunFocalOverrideMm else exifFocalMm
            val applied = runCatching {
                RawV3Engine.applyLensfunToStageA(
                    tifPath      = linearTif.absolutePath,
                    camMaker     = exifCameraMake,
                    camModel     = effectiveCameraModel,
                    lensMaker    = exifLensMake,
                    lensModel    = effectiveLensModel,
                    focalMm      = effFocal,
                    aperture     = exifApertureF,
                    lensfunDbDir = workspace.lensfunDbDir,
                )
            }.getOrDefault(false)
            Log.i(TAG, "$sourceName: JPEG Lensfun ${if (applied) "APPLIED" else "skip/no-op"} " +
                "('$effectiveLensModel' focal=${effFocal}mm f/${exifApertureF}) " +
                "in ${System.currentTimeMillis() - tLf} ms")
            lensReport = RawV3Engine.lensfunLastReport()
        }
        // JPEG/PNG Rayxie CA — AFTER the lens profile (owner decision
        // 2026-09-04, mirrors Stage A): the profile's TCA is the calibrated
        // geometric fix; Rayxie's edge-based R-G / B-G clipping + guided
        // defringe then only handle the residual. RGB-domain, so it needs
        // no CFA data. Same `caCorrectionEnabled` toggle as RAW.
        var rayxieState = if (workspace.caCorrectionEnabled) "fail" else "off"
        if (syn.success && workspace.caCorrectionEnabled) {
            val tCa = System.currentTimeMillis()
            val caOk = runCatching {
                RawV3Engine.applyRayxieCaToStageA(linearTif.absolutePath)
            }.getOrDefault(false)
            rayxieState = if (caOk) "applied" else "fail"
            Log.i(TAG, "$sourceName: JPEG Rayxie CA ${if (caOk) "APPLIED" else "skip/fail"} " +
                "in ${System.currentTimeMillis() - tCa} ms")
        }
        // One grep-able line per import, same shape as Stage A's native line.
        com.RAZStudio.StudioRoom.core.utils.AppLog.i(TAG,
            "LENS-REPORT [JPEG]: $lensReport | rayxie-clip+defringe=$rayxieState | order=profile->rayxie")
        return syn
    }

    private fun synthStageAFromBitmap(
        srcFile: File,
        outTif: File,
    ): RawV3Engine.StageAResult {
        return runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            var bmp = android.graphics.BitmapFactory.decodeFile(srcFile.absolutePath, opts)
                ?: error("BitmapFactory returned null")
            // Apply EXIF orientation so the synthetic A.tif is upright (we then
            // report orientation=1 to the pipeline to avoid double-rotation).
            bmp = applyExifOrientation(srcFile, bmp)
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            // ARGB int → RGBA byte (R,G,B,A).
            val rgba = ByteArray(w.toLong().toInt() * h * 4)
            var o = 0
            for (p in pixels) {
                rgba[o++] = ((p shr 16) and 0xFF).toByte() // R
                rgba[o++] = ((p shr 8) and 0xFF).toByte()  // G
                rgba[o++] = (p and 0xFF).toByte()          // B
                rgba[o++] = 0xFF.toByte()                  // A
            }
            RawV3Engine.stageAFromBitmap(rgba, w, h, 1, outTif.absolutePath)
        }.getOrElse { e ->
            RawV3Engine.StageAResult(
                success = false, width = 0, height = 0, orientation = 1,
                cameraMake = "", cameraModel = "", lensMake = "", lensModel = "",
                lensId = 0, colorTemperature = 0,
                iso = 0, shutterSpeed = 0f, aperture = 0f, focalLength = 0f,
                dateTimeOriginal = "", error = "synthStageA: ${e.message}",
            )
        }
    }

    /** Rotate/flip [bmp] per the file's EXIF orientation tag. */
    private fun applyExifOrientation(
        srcFile: File,
        bmp: android.graphics.Bitmap,
    ): android.graphics.Bitmap {
        val orient = runCatching {
            android.media.ExifInterface(srcFile.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val m = android.graphics.Matrix()
        when (orient) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(90f)
                m.postScale(1f, -1f)
            }
            else -> return bmp
        }
        return runCatching {
            android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                .also { if (it != bmp) bmp.recycle() }
        }.getOrDefault(bmp)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Persisted record of a Stage A decode, so [openRawFile] can recognise a
     * bit-for-bit-reproducible reopen and skip the ~35-40 s LibRaw pass
     * entirely instead of unconditionally re-decoding every time (the
     * previous behaviour — see the removed comment above the old
     * unconditional `stageATif(sha).delete()`).
     *
     * [optionsFingerprint] is simply `RawV3WorkspaceOptions.toString()` — a
     * Kotlin data class's generated `toString()` lists every constructor
     * property with a stable format, which is exactly the set of fields the
     * class's own doc comment says are "baked into the Stage A TIFF cache;
     * they cannot be changed mid-session without re-decoding." Comparing it
     * by plain string equality is sufficient — we only ever need to know
     * "identical or not," never to parse it back apart.
     */
    private data class CachedStageAMeta(
        val optionsFingerprint: String,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val cameraMake: String,
        val cameraModel: String,
        val lensMake: String,
        val lensModel: String,
        val lensId: Int,
        val colorTemperature: Int,
        val iso: Int,
        val shutterSpeed: Float,
        val aperture: Float,
        val focalLength: Float,
        val dateTimeOriginal: String,
        val dualContrastThreshold: Float,
    ) {
        fun toStageAResult() = RawV3Engine.StageAResult(
            success = true,
            width = width,
            height = height,
            orientation = orientation,
            cameraMake = cameraMake,
            cameraModel = cameraModel,
            lensMake = lensMake,
            lensModel = lensModel,
            lensId = lensId,
            colorTemperature = colorTemperature,
            iso = iso,
            shutterSpeed = shutterSpeed,
            aperture = aperture,
            focalLength = focalLength,
            dateTimeOriginal = dateTimeOriginal,
            error = null,
            dualContrastThreshold = dualContrastThreshold,
        )

        companion object {
            fun from(result: RawV3Engine.StageAResult, optionsFingerprint: String) = CachedStageAMeta(
                optionsFingerprint = optionsFingerprint,
                width = result.width,
                height = result.height,
                orientation = result.orientation,
                cameraMake = result.cameraMake,
                cameraModel = result.cameraModel,
                lensMake = result.lensMake,
                lensModel = result.lensModel,
                lensId = result.lensId,
                colorTemperature = result.colorTemperature,
                iso = result.iso,
                shutterSpeed = result.shutterSpeed,
                aperture = result.aperture,
                focalLength = result.focalLength,
                dateTimeOriginal = result.dateTimeOriginal,
                dualContrastThreshold = result.dualContrastThreshold,
            )
        }
    }

    // Plain `key=value` lines, one field per line — not JSON. Every field is a
    // primitive or a single-line string (camera/lens names never contain a
    // newline in practice), so a hand-rolled format avoids pulling in a JSON
    // dependency for what is otherwise a dozen scalar fields.
    private fun writeStageAMeta(file: File, meta: CachedStageAMeta) {
        file.writeText(buildString {
            appendLine("optionsFingerprint=${meta.optionsFingerprint}")
            appendLine("width=${meta.width}")
            appendLine("height=${meta.height}")
            appendLine("orientation=${meta.orientation}")
            appendLine("cameraMake=${meta.cameraMake}")
            appendLine("cameraModel=${meta.cameraModel}")
            appendLine("lensMake=${meta.lensMake}")
            appendLine("lensModel=${meta.lensModel}")
            appendLine("lensId=${meta.lensId}")
            appendLine("colorTemperature=${meta.colorTemperature}")
            appendLine("iso=${meta.iso}")
            appendLine("shutterSpeed=${meta.shutterSpeed}")
            appendLine("aperture=${meta.aperture}")
            appendLine("focalLength=${meta.focalLength}")
            appendLine("dateTimeOriginal=${meta.dateTimeOriginal}")
            appendLine("dualContrastThreshold=${meta.dualContrastThreshold}")
        })
    }

    private fun readStageAMeta(file: File): CachedStageAMeta? = runCatching {
        if (!file.isFile) return null
        val fields = mutableMapOf<String, String>()
        file.forEachLine { line ->
            val i = line.indexOf('=')
            if (i > 0) fields[line.substring(0, i)] = line.substring(i + 1)
        }
        CachedStageAMeta(
            optionsFingerprint = fields.getValue("optionsFingerprint"),
            width = fields.getValue("width").toInt(),
            height = fields.getValue("height").toInt(),
            orientation = fields.getValue("orientation").toInt(),
            cameraMake = fields["cameraMake"].orEmpty(),
            cameraModel = fields["cameraModel"].orEmpty(),
            lensMake = fields["lensMake"].orEmpty(),
            lensModel = fields["lensModel"].orEmpty(),
            lensId = fields.getValue("lensId").toInt(),
            colorTemperature = fields.getValue("colorTemperature").toInt(),
            iso = fields.getValue("iso").toInt(),
            shutterSpeed = fields.getValue("shutterSpeed").toFloat(),
            aperture = fields.getValue("aperture").toFloat(),
            focalLength = fields.getValue("focalLength").toFloat(),
            dateTimeOriginal = fields["dateTimeOriginal"].orEmpty(),
            dualContrastThreshold = fields.getValue("dualContrastThreshold").toFloat(),
        )
    }.getOrNull()

    /** Concatenated mask-layer alpha planes for Stage C. */
    private class MaskLayerBundle(
        val data: FloatArray, val w: Int, val h: Int, val count: Int,
    )

    /**
     * Decode up to 4 brush-mask PNGs into one concatenated [0,1] float buffer
     * (layer i at offset i·w·h). All layers are scaled to the first PNG's dims
     * so the native side can index them uniformly. The mask alpha is taken from
     * the PNG's alpha channel (the brush canvas is ARGB_8888 with painted
     * strength in alpha). Returns null if no layers decode.
     */
    /**
     * Load a single brush-mask PNG and return its alpha channel as a
     * row-major `[0, 1]` FloatArray plus the working width/height. The
     * mask is downscaled to a maxSide=1024 cap (same as [decodeMaskLayers])
     * so it doesn't blow memory; the bokeh kernel bilinearly samples,
     * so a downscaled mask is fine. Returns null on load failure.
     *
     * Used by the bokeh path so a user-painted Mask-tab card can drive
     * the bokeh region instead of the U2Net subject mask.
     */
    private data class PaintedMaskPng(val data: FloatArray, val w: Int, val h: Int)
    private fun loadPaintedMaskPng(path: String): PaintedMaskPng? {
        val bm = runCatching {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawMaskStorage.loadFromPath(path)
        }.getOrNull() ?: return null
        val maxSide = 1024
        val scale = minOf(maxSide.toFloat() / bm.width, maxSide.toFloat() / bm.height, 1f)
        val w = (bm.width * scale).toInt().coerceAtLeast(1)
        val h = (bm.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (bm.width != w || bm.height != h)
            android.graphics.Bitmap.createScaledBitmap(bm, w, h, true)
        else bm
        val plane = w * h
        val px = IntArray(plane)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bm) scaled.recycle()
        val out = FloatArray(plane)
        for (j in 0 until plane) out[j] = ((px[j] ushr 24) and 0xFF) / 255f
        return PaintedMaskPng(out, w, h)
    }

    private fun decodeMaskLayers(paths: List<String>): MaskLayerBundle? {
        if (paths.isEmpty()) return null
        // Keep a failed path as a hole. Compacting shifted every later
        // layer onto the previous slot's adjustments.
        val bitmaps = paths.take(4).map { p ->
            runCatching {
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawMaskStorage.loadFromPath(p)
            }.getOrNull()
        }
        return packMaskBitmaps(bitmaps)
    }

    /**
     * Same packing as [decodeMaskLayers], from the bitmaps the canvas already
     * uploaded. Null entries stay as transparent planes so layer indexes match
     * the adjustment slots. Inputs are not recycled — the preview still owns them.
     */
    private fun encodeMaskLayerBitmaps(
        layers: List<android.graphics.Bitmap?>,
    ): MaskLayerBundle? = packMaskBitmaps(layers.take(4))

    private fun packMaskBitmaps(
        bitmaps: List<android.graphics.Bitmap?>,
    ): MaskLayerBundle? {
        val seed = bitmaps.firstOrNull { it != null } ?: return null
        val maxSide = 1024
        val scale = minOf(maxSide.toFloat() / seed.width, maxSide.toFloat() / seed.height, 1f)
        val w = (seed.width * scale).toInt().coerceAtLeast(1)
        val h = (seed.height * scale).toInt().coerceAtLeast(1)
        val plane = w * h
        val count = bitmaps.size
        val out = FloatArray(plane * count)
        val px = IntArray(plane)
        for (i in 0 until count) {
            val src = bitmaps[i] ?: continue
            val scaled = if (src.width != w || src.height != h)
                android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
            else src
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            val base = plane * i
            for (j in 0 until plane) {
                out[base + j] = ((px[j] ushr 24) and 0xFF) / 255f
            }
            if (scaled !== src) scaled.recycle()
        }
        return MaskLayerBundle(out, w, h, count)
    }

    private fun computeTargetDims(
        longSide: Int,
        srcW: Int, srcH: Int,
        scaleMode: RawV3Exporter.ScaleMode,
    ): Pair<Int, Int> {
        if (longSide <= 0 || srcW == 0 || srcH == 0) return 0 to 0
        val clamped = if (scaleMode == RawV3Exporter.ScaleMode.RAZSharp)
            minOf(longSide, 2560) else longSide
        return if (srcW >= srcH) clamped to (srcH * clamped / srcW)
        else (srcW * clamped / srcH) to clamped
    }

    /**
     * Working W×H for JPG/WebP GPU (and CPU fallback) grade.
     *
     * Uniformly scales Stage A so that after orient → straighten → crop the
     * long side matches [targetLongSide]. Spatial ops (bloom tent, USM via
     * textureSize) use this buffer's long side — same as preview of an image
     * at that resolution. targetLongSide≤0 → full Stage A (Original).
     */
    private fun computeExportGradeWorkDims(
        srcW: Int,
        srcH: Int,
        targetLongSide: Int,
        scaleMode: RawV3Exporter.ScaleMode,
        cropL: Float,
        cropT: Float,
        cropR: Float,
        cropB: Float,
        cropRotationDeg: Float,
        cropRotate90: Int,
    ): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 1 to 1
        if (targetLongSide <= 0) return srcW to srcH

        val rot = ((cropRotate90 % 4) + 4) % 4
        val orientedW = if (rot % 2 == 1) srcH else srcW
        val orientedH = if (rot % 2 == 1) srcW else srcH
        var canvasW = orientedW
        var canvasH = orientedH
        val angle = cropRotationDeg
        if (kotlin.math.abs(angle) > 0.01f) {
            val rad = Math.toRadians(angle.toDouble())
            val c = kotlin.math.abs(kotlin.math.cos(rad))
            val s = kotlin.math.abs(kotlin.math.sin(rad))
            canvasW = kotlin.math.ceil(orientedW * c + orientedH * s).toInt().coerceAtLeast(1)
            canvasH = kotlin.math.ceil(orientedW * s + orientedH * c).toInt().coerceAtLeast(1)
        }

        val cl = cropL.coerceIn(0f, 1f)
        val ct = cropT.coerceIn(0f, 1f)
        val cr = cropR.coerceIn(0f, 1f)
        val cb = cropB.coerceIn(0f, 1f)
        val cw = ((cr - cl) * canvasW).coerceAtLeast(1f)
        val ch = ((cb - ct) * canvasH).coerceAtLeast(1f)
        val (fw, _) = computeTargetDims(
            longSide = targetLongSide,
            srcW = cw.toInt().coerceAtLeast(1),
            srcH = ch.toInt().coerceAtLeast(1),
            scaleMode = scaleMode,
        )
        if (fw <= 0) return srcW to srcH

        // Same scale on Stage A as Fw/Cpw on the post-geometry canvas.
        val scale = (fw.toFloat() / cw).coerceAtMost(1f)
        val workW = (srcW * scale).toInt().coerceIn(4, srcW)
        val workH = (srcH * scale).toInt().coerceIn(4, srcH)
        return workW to workH
    }

    private fun RawV3Exporter.ScaleMode.toDomainScaleMode(): ImageScaleMode = when (this) {
        RawV3Exporter.ScaleMode.Basic               -> ImageScaleMode.Bilinear()
        RawV3Exporter.ScaleMode.Bilinear            -> ImageScaleMode.Bilinear()
        RawV3Exporter.ScaleMode.Spline64            -> ImageScaleMode.Spline64()
        RawV3Exporter.ScaleMode.Lanczos3            -> ImageScaleMode.Lanczos3()
        RawV3Exporter.ScaleMode.Lanczos4SharpestEwa -> ImageScaleMode.EwaLanczos4Sharpest()
        RawV3Exporter.ScaleMode.RAZSharp            -> ImageScaleMode.RAZSharp
    }

    private fun RawV3Exporter.ResizeSharpen.toDomainResizeSharpen(): DomainResizeSharpen =
        DomainResizeSharpen.valueOf(this.name)

    // sRGB ↔ linear lookup tables. The piecewise sRGB OETF is exact at integer
    // byte values, so a 256-entry table is lossless for the forward direction.
    // The inverse table is 4096 entries (12-bit) — enough to avoid posterisation
    // when round-tripping a downscale through linear and back to 8-bit sRGB.
    private val SRGB_TO_LIN: FloatArray = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    }
    private val LIN_TO_SRGB: IntArray = IntArray(4097) { i ->
        val v = i / 4096f
        val s = if (v <= 0.0031308f) v * 12.92f
                else (1.055f * Math.pow(v.toDouble(), 1.0 / 2.4).toFloat() - 0.055f)
        (s.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }

    /**
     * Linear-RGB-domain downscale (Ansel/darktable "finalscale" placement).
     * Decodes sRGB → linear, averages in linear space, re-encodes to sRGB.
     * Uses a simple box-area filter (each output pixel = mean of the source
     * region it covers, fractional coverage at edges). Box filtering is what
     * `createScaledBitmap(filter=true)` approximates already, but in sRGB —
     * the fix is purely the colour-space, not a fancier kernel.
     */
    private fun linearDownscale(
        src: android.graphics.Bitmap, dstW: Int, dstH: Int,
    ): android.graphics.Bitmap {
        val srcW = src.width; val srcH = src.height
        if (dstW <= 0 || dstH <= 0) return src
        if (dstW >= srcW && dstH >= srcH) {
            // Up- or same-size: linear-domain box has no advantage over the
            // platform scaler. Use Android's bilinear (still in sRGB but for
            // upscale the artefact is invisible).
            return android.graphics.Bitmap.createScaledBitmap(src, dstW, dstH, true)
        }
        val srcArgb = if (src.config == android.graphics.Bitmap.Config.ARGB_8888) src
                      else src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val sPix = IntArray(srcW * srcH)
        srcArgb.getPixels(sPix, 0, srcW, 0, 0, srcW, srcH)
        if (srcArgb !== src) srcArgb.recycle()

        val sx = srcW.toDouble() / dstW
        val sy = srcH.toDouble() / dstH
        val out = IntArray(dstW * dstH)
        val s2l = SRGB_TO_LIN; val l2s = LIN_TO_SRGB

        for (oy in 0 until dstH) {
            val y0 = oy * sy
            val y1 = y0 + sy
            val iy0 = y0.toInt()
            val iy1 = ((y1 - 1e-9).toInt()).coerceAtMost(srcH - 1)
            for (ox in 0 until dstW) {
                val x0 = ox * sx
                val x1 = x0 + sx
                val ix0 = x0.toInt()
                val ix1 = ((x1 - 1e-9).toInt()).coerceAtMost(srcW - 1)
                var rAcc = 0.0; var gAcc = 0.0; var bAcc = 0.0; var wAcc = 0.0
                for (iy in iy0..iy1) {
                    val wy = (minOf((iy + 1).toDouble(), y1) - maxOf(iy.toDouble(), y0))
                    if (wy <= 0.0) continue
                    val rowOff = iy * srcW
                    for (ix in ix0..ix1) {
                        val wx = (minOf((ix + 1).toDouble(), x1) - maxOf(ix.toDouble(), x0))
                        if (wx <= 0.0) continue
                        val w = wx * wy
                        val p = sPix[rowOff + ix]
                        rAcc += s2l[(p ushr 16) and 0xFF] * w
                        gAcc += s2l[(p ushr 8)  and 0xFF] * w
                        bAcc += s2l[p and 0xFF]          * w
                        wAcc += w
                    }
                }
                val inv = if (wAcc > 0.0) 1.0 / wAcc else 0.0
                val rIdx = (rAcc * inv * 4096.0).toInt().coerceIn(0, 4096)
                val gIdx = (gAcc * inv * 4096.0).toInt().coerceIn(0, 4096)
                val bIdx = (bAcc * inv * 4096.0).toInt().coerceIn(0, 4096)
                out[oy * dstW + ox] = (0xFF shl 24) or
                    (l2s[rIdx] shl 16) or (l2s[gIdx] shl 8) or l2s[bIdx]
            }
        }
        val bm = android.graphics.Bitmap.createBitmap(dstW, dstH, android.graphics.Bitmap.Config.ARGB_8888)
        bm.setPixels(out, 0, dstW, 0, 0, dstW, dstH)
        return bm
    }

    /**
     * Post-resize unsharp mask applied to the final downscaled bitmap for ALL
     * scale modes (the compressor's scaler only sharpens RAZSharp, and our
     * pre-resize bypasses it). Mirrors core.data PostResizeSharpen: an 8-tap
     * cross blur (radius 1 + 3) as the low-pass, then `px + 2*strength*(px-blur)`.
     */
    private fun unsharpMask(bitmap: android.graphics.Bitmap, strength: Float): android.graphics.Bitmap {
        val src = if (bitmap.config == android.graphics.Bitmap.Config.ARGB_8888) bitmap
                  else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val w = src.width; val h = src.height; val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val fr = FloatArray(n) { ((px[it] shr 16) and 0xFF) / 255f }
        val fg = FloatArray(n) { ((px[it] shr 8)  and 0xFF) / 255f }
        val fb = FloatArray(n) { (px[it] and 0xFF) / 255f }
        val out = IntArray(n)
        val s = strength * 2f
        for (y in 0 until h) {
            val ym1 = (y - 1).coerceAtLeast(0) * w
            val yp1 = (y + 1).coerceAtMost(h - 1) * w
            val ym3 = (y - 3).coerceAtLeast(0) * w
            val yp3 = (y + 3).coerceAtMost(h - 1) * w
            val yw  = y * w
            for (x in 0 until w) {
                val i = yw + x
                val xm1 = (x - 1).coerceAtLeast(0); val xp1 = (x + 1).coerceAtMost(w - 1)
                val xm3 = (x - 3).coerceAtLeast(0); val xp3 = (x + 3).coerceAtMost(w - 1)
                val br = (fr[ym1 + x] + fr[yp1 + x] + fr[yw + xm1] + fr[yw + xp1] +
                          fr[ym3 + x] + fr[yp3 + x] + fr[yw + xm3] + fr[yw + xp3]) * 0.125f
                val bg = (fg[ym1 + x] + fg[yp1 + x] + fg[yw + xm1] + fg[yw + xp1] +
                          fg[ym3 + x] + fg[yp3 + x] + fg[yw + xm3] + fg[yw + xp3]) * 0.125f
                val bb = (fb[ym1 + x] + fb[yp1 + x] + fb[yw + xm1] + fb[yw + xp1] +
                          fb[ym3 + x] + fb[yp3 + x] + fb[yw + xm3] + fb[yw + xp3]) * 0.125f
                val r = (fr[i] + s * (fr[i] - br)).coerceIn(0f, 1f)
                val g = (fg[i] + s * (fg[i] - bg)).coerceIn(0f, 1f)
                val b = (fb[i] + s * (fb[i] - bb)).coerceIn(0f, 1f)
                out[i] = (px[i].toLong() and 0xFF000000L).toInt() or
                    ((r * 255f + 0.5f).toInt() shl 16) or
                    ((g * 255f + 0.5f).toInt() shl 8) or
                    (b * 255f + 0.5f).toInt()
            }
        }
        if (src !== bitmap) src.recycle()
        return android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun RawV3Exporter.Format.toImageFormat(): ImageFormat = when (this) {
        RawV3Exporter.Format.Tiff16 -> ImageFormat.Tiff
        RawV3Exporter.Format.Png16  -> ImageFormat.Png.Lossless
        RawV3Exporter.Format.Jpg    -> ImageFormat.Jpg
        // Lossy (quality-controlled) WebP: lossless WebP at 20MP took tens of
        // seconds and produced huge files. Lossy honours the quality slider and
        // encodes fast, which is what a normal "Save" expects.
        RawV3Exporter.Format.WebP   -> ImageFormat.Webp.Lossy
        RawV3Exporter.Format.Heic,
        RawV3Exporter.Format.Heic16 -> ImageFormat.Heic.Lossy
        RawV3Exporter.Format.Avif -> ImageFormat.Avif.Lossy
    }

    private companion object {
        const val TAG = "RawV3.Coordinator"
    }
}
