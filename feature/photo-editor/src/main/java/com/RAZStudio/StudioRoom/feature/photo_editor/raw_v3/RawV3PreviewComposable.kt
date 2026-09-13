/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — Compose preview surface (M12.1).
 *
 *  Wraps the existing View-based [RawV3GlSurfaceView] so the production
 *  editor screen can host it inside a Compose tree. The smoke activity
 *  uses the same View directly inside a plain Activity; this Composable
 *  adds:
 *    1. AndroidView lifecycle binding (factory + update + onRelease)
 *    2. Source-AHardwareBuffer ownership (allocate + close on dispose)
 *    3. Uniform reupload on every recomposition triggered by `params`
 *
 *  The Stage A TIFF path comes from [RawV3Coordinator.openRawFile]; the
 *  Composable downsamples Stage A → an `AHardwareBuffer` once on first
 *  composition, hands it to the SurfaceView, then never re-decodes —
 *  per-frame slider feedback is pure uniform reuploads at ~60 FPS.
 *
 *  Deleted-on-M12-completion note: when the v2 raw/ package goes away
 *  this Composable will be the editor's only preview surface, so it
 *  stays.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val TAG = "RawV3.PreviewComposable"

/**
 * Compose canvas for a v3 RAW preview.
 *
 *  • [stageATifPath] — path to the Stage A BigTIFF. Coordinator produces
 *    this via [RawV3Coordinator.openRawFile] and it must be readable for
 *    the lifetime of this Composable.
 *  • [params] — the current [ShaderParams] (slider state flattened from
 *    the editor's action stack). Every recomposition pushes these to the
 *    shader as uniform reuploads — no re-decode.
 *  • [lutCubePath] — optional `.cube` file to upload. The SurfaceView
 *    keeps its uploaded LUT across recompositions; pass null to clear.
 *  • [maxPreviewLongSide] — long-side cap for the Stage B downsample
 *    (default 2560 px, matches the smoke). The preview never renders
 *    above this to keep the editor responsive on phones.
 *  • [onAhbReady] — fires once the source AHardwareBuffer is bound.
 *    Useful for the editor to surface "ready" state (e.g. enable sliders).
 *
 *  Threading: the AHB allocation + Stage B downsample happen on
 *  Dispatchers.IO via a LaunchedEffect; the SurfaceView wraps EGL state
 *  so all GL calls stay on the View's render thread.
 */
@Composable
fun RawV3PreviewComposable(
    stageATifPath: String,
    params: ShaderParams,
    modifier: Modifier = Modifier,
    lutCubePath: String? = null,
    /** Tone Curve LUT: 256 RGB8 texels (768 bytes). Null = identity (cleared). */
    toneCurveLut: ByteArray? = null,
    maxPreviewLongSide: Int = 2560,
    /**
     * M12.2c.1 — U2Net subject mask. When non-null and the
     * SegmentTarget on Vignette / Gradient is Subject / Background, the
     * renderer gates those tabs by `subjectMask²` so the effect fades
     * smoothly along subject silhouettes. Pass null to disable gating
     * (renderer falls back to All everywhere).
     */
    subjectMask: RawV3SegmentationMasks? = null,
    /**
     * Cityscapes 4-class masks. Used here only to derive the bokeh-
     * attenuation plane uploaded on unit 10. Pass null to skip the bokeh
     * sky/terrain softening; the bokeh block then runs at full strength
     * across the whole background.
     */
    cityscapesMasks: RawV3CityscapesMasks? = null,
    /**
     * M12.2c.3 — brush-painted Mask tab mask (ARGB_8888). The alpha
     * channel of every pixel is the painted strength. Quantised to
     * GL_R8 and uploaded to the renderer's brush-mask texture (unit 3)
     * whenever [brushMaskDirty] increments. Pass `null` to clear.
     */
    brushMask: android.graphics.Bitmap? = null,
    brushMaskDirty: Int = 0,
    /**
     * M12.2c.2b — committed mask-layer bitmaps in layer order (index 0 =
     * bottommost). Each is uploaded to its matching GL brush-mask layer;
     * layers beyond this list (up to 4) are cleared. When empty, the
     * single-[brushMask] path above drives layer 0 (live painting).
     */
    brushMaskLayers: List<android.graphics.Bitmap?> = emptyList(),
    /**
     * Preview pan/zoom, applied inside GL (NOT via a Compose graphicsLayer on
     * the SurfaceView). [canvasScale] zooms about the photo centre;
     * [canvasOffsetX]/[canvasOffsetY] pan in surface pixels (Compose top-down
     * sign). Keeping the transform GL-side pins the SurfaceView to its slot so
     * the preview clips to the letterbox and never overflows the chrome.
     */
    canvasScale: Float = 1f,
    canvasOffsetX: Float = 0f,
    canvasOffsetY: Float = 0f,
    /**
     * Mask tab "Show" preview. When true, the GL output tints the masked
     * region (union of every active brush + luminance layer) blue so the user
     * sees the painted/selected area even with no adjustment applied. Drawn
     * GL-side because the SurfaceView is ZOrderOnTop — a Compose overlay would
     * render behind it and stay hidden.
     */
    showMaskOverlay: Boolean = false,
    /**
     * The in-flight editing mask — the layer the user is currently painting or
     * selecting. The "Show" overlay tints ONLY this layer blue; committed layers
     * stay uploaded (their adjustments apply) but aren't tinted, so a fresh
     * layer's edit doesn't inherit the previous card's selection. Null → no layer
     * is being edited, so nothing is tinted (e.g. a new layer, nothing picked yet).
     */
    overlayMaskRef: android.graphics.Bitmap? = null,
    /**
     * Layer index whose luminance mask the "Show" overlay should tint, when that
     * layer has NO brush bitmap (a "Select Luminance" mask is generated per-pixel
     * from [ShaderParams] lum params, so there's no [overlayMaskRef] to key on).
     * <0 = none. Ignored when [overlayMaskRef] is non-null (brush wins).
     */
    lumMaskOverlayLayer: Int = -1,
    onAhbReady: (() -> Unit)? = null,
    /**
     * M12.2d — exposes the bound source AHB to the editor component so the
     * Apply → Export transition can snapshot it via stageBSerialize before
     * freeing the 64 MB GPU allocation.
     */
    onAhbBound: ((HardwareBuffer) -> Unit)? = null,
    /**
     * Fires with the source image dimensions whenever a new RAW finishes
     * Stage A → AHB. Callers use this to set `Modifier.aspectRatio()`
     * on the SurfaceView slot so the GL canvas doesn't letterbox with
     * black bands.
     */
    onImageSize: ((width: Int, height: Int) -> Unit)? = null,
    /**
     * Fires after every settled uniform push with the live GL view, so a
     * caller (the Tone Curves tab) can pull a graded-frame luma histogram via
     * [RawV3GlSurfaceView.histogramGraded]. The [params] generation is passed
     * so the caller can debounce / skip stale requests. Null disables it.
     */
    onGradedFrameReady: ((RawV3GlSurfaceView) -> Unit)? = null,
    /**
     * Fires with a downscaled graded-frame bitmap just before the Activity
     * pauses (screen off / app background). The editor draws this BEHIND the
     * ZOrderOnTop SurfaceView so screen-on / surface reboot does not flash
     * the Stage-A embedded thumbnail (which looked like a full decode restart).
     * Null disables capture. Caller owns the Bitmap (do not recycle while Compose
     * may still draw it — see GOTCHAS).
     */
    onPauseBackdrop: ((Bitmap) -> Unit)? = null,
    /**
     * Stage B bake state callback. Receives (requestedKey, bakedKey). When the
     * two are equal, the editor preview reflects the latest macro for all
     * spatial pre-pass fields (CLAHE, NR, Detail). Verify-harness and any other
     * "snapshot the current preview" consumer should await `requested==baked`
     * before capturing, otherwise the editor reads stale while the save path
     * runs against the current macro → preview/save divergence.
     */
    onBakeStateChange: ((requestedKey: Int, bakedKey: Int) -> Unit)? = null,
    /**
     * Authoritative source dimensions from Stage A (post-EXIF-rotation). When
     * non-zero, `allocateAndFillAhb` skips re-reading the TIFF header, preventing
     * the rare race where a partially-written or stale TIFF returns landscape dims
     * and causes a flipped canvas. Pass `stageA.width` / `stageA.height`.
     */
    knownSrcWidth: Int = 0,
    knownSrcHeight: Int = 0,
) {
    val ctx = LocalContext.current

    // Sustained performance mode — while the GL preview is on screen, ask the
    // OS to favour consistent sustained throughput for this window (Android
    // 14+ throttles aggressively otherwise; this plus the render thread's
    // ADPF hint session in RawV3GlSurfaceView keeps slider→frame latency
    // stable). A pure hint: unsupported devices and battery saver ignore it.
    DisposableEffect(Unit) {
        val activity = generateSequence<android.content.Context>(ctx) {
            (it as? android.content.ContextWrapper)?.baseContext
        }.filterIsInstance<android.app.Activity>().firstOrNull()
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE)
            as? android.os.PowerManager
        val supported = pm?.isSustainedPerformanceModeSupported == true
        if (supported) runCatching { activity?.window?.setSustainedPerformanceMode(true) }
        onDispose {
            if (supported) runCatching { activity?.window?.setSustainedPerformanceMode(false) }
        }
    }

    // Always-current ref so LaunchedEffects see live params after their debounce.
    val currentParams = androidx.compose.runtime.rememberUpdatedState(params)

    // ── Single-AHB Realtime Preview ──────────────────────────────────────
    // One AHardwareBuffer holds the spatially-pre-processed source (CLAHE/NR/
    // Detail). All per-pixel grading (exposure, WB, HSL, LUT, tone curve,
    // bloom, vignette, masks, …) runs live in GLSL via uniform pushes.
    // No graded bake for preview — export bake (Stage C) is separate.
    val glViewState: MutableState<RawV3GlSurfaceView?> = remember { mutableStateOf(null) }
    val ungradedAhbState: MutableState<HardwareBuffer?> = remember { mutableStateOf(null) }
    val lastUploadedLutState: MutableState<String?> = remember { mutableStateOf(null) }
    val ungradedBakeRunning = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val ungradedBakeRetryTick = remember { mutableStateOf(0) }
    val ungradedRetryPending = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // Option A pre-spatial cache: the pristine (downsample-only) AHB is baked
    // ONCE per (photo, resolution). Every spatial-slider change then copies it +
    // re-applies only CLAHE/NR/detail (~150 ms) instead of re-decoding the
    // Stage A TIFF + Lanczos downsampling (~1.4 s). Fixes CLAHE slowness +
    // hit-and-miss (the gate stops coalescing once bakes are this fast).
    val pristineAhbState: MutableState<HardwareBuffer?> = remember { mutableStateOf(null) }
    val pristineKeyState = remember { mutableStateOf<String?>(null) }
    val pristineDimsState = remember { mutableStateOf(0 to 0) }
    // The spatialKey the CURRENTLY-BOUND AHB was baked with. Drives the
    // atomic-update gate below: while the live params' spatialKey differs from
    // this (a CPU rebake is pending/in flight), uniform pushes are HELD so the
    // canvas doesn't update in two visible steps — first the instant GPU
    // uniforms, then the CPU spatial result ~400 ms later (user report
    // 2026-08-29: AI Color Enhance = sat/vib uniforms + CLAHE spatial, and
    // hold-to-compare, both stepped twice). Both land together at bake commit.
    val lastBakedSpatialKey = remember { mutableStateOf<List<Any>?>(null) }

    // Screen-off / background: Activity.onPause runs BEFORE SurfaceView
    // surfaceDestroyed. Snapshot the live graded frame while EGL is still
    // current so Compose can show it under the ZOrderOnTop hole during the
    // ~1–2 s shader recompile on resume (AHB cache is reused — NOT Stage A).
    val lifecycleOwner = LocalLifecycleOwner.current
    val pauseBackdropCb = androidx.compose.runtime.rememberUpdatedState(onPauseBackdrop)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_PAUSE) return@LifecycleEventObserver
            val cb = pauseBackdropCb.value ?: return@LifecycleEventObserver
            val view = glViewState.value ?: return@LifecycleEventObserver
            val (sw, sh) = pristineDimsState.value
            if (sw <= 0 || sh <= 0) return@LifecycleEventObserver
            val bmp = runCatching {
                view.snapshotGradedToBitmap(maxLongSide = 1280, srcW = sw, srcH = sh)
            }.getOrNull()
            if (bmp != null) {
                Log.i(TAG, "pause backdrop captured ${bmp.width}×${bmp.height} (GL resume cache)")
                cb(bmp)
            } else {
                Log.w(TAG, "pause backdrop capture failed — Stage-A thumb may flash on resume")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SPATIAL bake key — drives AHB allocation. Changes only when CLAHE/NR/Detail
    // move. Per-pixel slider drags do NOT churn this bake; the shader handles them.
    // MUST list every field the native spatial pass reads (v3_jni
    // nativeStageBApplySpatialToAhb) — blueNR/redNR/clarityLift/
    // detailSmoothBackground were read natively but never keyed nor passed
    // (found 2026-08-29), so moving those sliders changed nothing in preview.
    val spatialKey = listOf(
        params.claheEnabled, params.claheShadowsBoost, params.claheHighlightsBoost,
        params.luminanceNR, params.colorNR, params.blueNR, params.redNR,
        params.detailSharpness, params.detailSmartSharpness,
        params.detailClarity, params.clarityLift, params.detailTexture,
        params.detailSmoothBackground,
        params.detailFilmGrain, params.detailFilmGrainSize,
        params.detailFilmGrainWash,
        // Subject-mask-driven spatial ops: rebake when the mask itself changes.
        if (params.detailSmartSharpness > 0f || params.detailSmoothBackground > 0f) {
            System.identityHashCode(subjectMask)
        } else 0,
    )
    // ── Spatial AHB bake ─────────────────────────────────────────────────
    // Runs on file-open + every spatial-key change. Does NOT pass
    // toneCurveLut/lutData/maskLayers — the per-pixel kernel pass stays
    // inert and the AHB holds the un-graded downsampled source.
    LaunchedEffect(stageATifPath, spatialKey, ungradedBakeRetryTick.value, knownSrcWidth, knownSrcHeight) {
        if (stageATifPath.isEmpty()) return@LaunchedEffect
        // Bake lifecycle signal: "rebake requested". The consumer (verify
        // harness / future UI) sees requestedKey != bakedKey until the
        // matching commit below reports them equal.
        onBakeStateChange?.invoke(
            spatialKey.hashCode(),
            lastBakedSpatialKey.value?.hashCode() ?: 0,
        )
        kotlinx.coroutines.delay(250)
        if (!ungradedBakeRunning.compareAndSet(false, true)) {
            Log.d(TAG, "ungradedAhb bake skipped — native already running, retry pending")
            ungradedRetryPending.set(true)
            return@LaunchedEffect
        }
        val ungradedCancelFlag = ByteArray(1)
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { cause -> if (cause != null) ungradedCancelFlag[0] = 1 }
        var newAhb: HardwareBuffer? = null
        var w = 0; var h = 0
        var gateReleased = false
        try {
            // Ungraded AHB must carry ONLY spatial pre-passes (NR/CLAHE/detail).
            // Grading params (exposure, WB, tone, HSL, …) must stay at defaults
            // so the GL shader's live per-pixel grading is never double-applied.
            // Hold-to-compare separately shows the AE baseline via aeShaderParams.
            val spatialOnlyParams = ShaderParams(
                claheEnabled         = params.claheEnabled,
                claheShadowsBoost    = params.claheShadowsBoost,
                claheHighlightsBoost = params.claheHighlightsBoost,
                luminanceNR          = params.luminanceNR,
                colorNR              = params.colorNR,
                blueNR               = params.blueNR,
                redNR                = params.redNR,
                detailSharpness      = params.detailSharpness,
                detailSmartSharpness = params.detailSmartSharpness,
                detailClarity        = params.detailClarity,
                clarityLift          = params.clarityLift,
                detailTexture        = params.detailTexture,
                detailSmoothBackground = params.detailSmoothBackground,
                detailFilmGrain      = params.detailFilmGrain,
                detailFilmGrainSize  = params.detailFilmGrainSize,
                detailFilmGrainWash  = params.detailFilmGrainWash,
            )
            // ── Ensure the PRISTINE (downsample-only) AHB for this photo/res ──
            // Baked once; the expensive decode + Lanczos downsample is spatial-
            // independent. Pass an all-spatial-OFF ShaderParams so the native
            // skips CLAHE/NR/detail and leaves a clean downsampled buffer.
            val pKey = "$stageATifPath|$knownSrcWidth|$knownSrcHeight|$maxPreviewLongSide"
            if (pristineAhbState.value == null || pristineKeyState.value != pKey) {
                val noSpatial = ShaderParams(
                    claheEnabled = false,
                    claheShadowsBoost = 0f, claheHighlightsBoost = 0f,
                    luminanceNR = 0f, colorNR = 0f,
                    detailSharpness = 0f, detailSmartSharpness = 0f,
                    detailClarity = 0f, detailTexture = 0f,
                    detailFilmGrain = 0f, detailFilmGrainSize = 0f, detailFilmGrainWash = 0f,
                )
                val pTriple = withContext(Dispatchers.IO) {
                    allocateAndFillAhb(
                        ctx, stageATifPath, maxPreviewLongSide, noSpatial, subjectMask,
                        knownSrcWidth = knownSrcWidth, knownSrcHeight = knownSrcHeight,
                        cancelFlag = ungradedCancelFlag,
                    )
                } ?: run {
                    // TIFF may still be mid-write by Stage A — release gate + retry.
                    Log.w(TAG, "Stage A → pristine AHB not ready, will retry in 2 s")
                    gateReleased = true
                    ungradedBakeRunning.set(false)
                    kotlinx.coroutines.delay(2_000)
                    withContext(kotlinx.coroutines.Dispatchers.Main) { ungradedBakeRetryTick.value++ }
                    return@LaunchedEffect
                }
                pristineAhbState.value?.close()
                pristineAhbState.value = pTriple.first
                pristineKeyState.value = pKey
                pristineDimsState.value = pTriple.second to pTriple.third
                Log.i(TAG, "pristine downsample baked ${pTriple.second}×${pTriple.third} " +
                    "for ${stageATifPath.substringAfterLast('/')}")
            }
            val pristine = pristineAhbState.value!!
            val (pw, ph) = pristineDimsState.value

            // ── Fast path: fresh display AHB ← pristine + spatial pass only ──
            val tSp = System.currentTimeMillis()
            newAhb = HardwareBuffer.create(
                pw, ph, HardwareBuffer.RGBA_FP16, 1,
                HardwareBuffer.USAGE_CPU_READ_RARELY or HardwareBuffer.USAGE_CPU_WRITE_RARELY or
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
            )
            val applied = withContext(Dispatchers.Default) {
                runCatching {
                    RawV3Engine.stageBApplySpatialToAhb(
                        pristine, newAhb!!,
                        params = spatialOnlyParams.toFloatArray(),
                        // Same (refined) mask the GL grade samples — see bestMask().
                        subjectMask = subjectMask?.gatingMask()?.first,
                        subjectMaskSize = subjectMask?.gatingMask()?.second ?: 0,
                        subjectMaskH = subjectMask?.gatingMask()?.third ?: 0,
                    ).success
                }.getOrDefault(false)
            }
            if (!applied) {
                // Fallback to the original full bake — zero regression if the
                // fast path ever fails (e.g. AHB lock contention).
                Log.w(TAG, "stageBApplySpatialToAhb failed → full re-bake fallback")
                newAhb?.close(); newAhb = null
                val triple = withContext(Dispatchers.IO) {
                    allocateAndFillAhb(
                        ctx, stageATifPath, maxPreviewLongSide, spatialOnlyParams, subjectMask,
                        knownSrcWidth = knownSrcWidth, knownSrcHeight = knownSrcHeight,
                        cancelFlag = ungradedCancelFlag,
                    )
                }
                if (triple == null) {
                    gateReleased = true
                    ungradedBakeRunning.set(false)
                    kotlinx.coroutines.delay(2_000)
                    withContext(kotlinx.coroutines.Dispatchers.Main) { ungradedBakeRetryTick.value++ }
                    return@LaunchedEffect
                }
                newAhb = triple.first
            }
            w = pw; h = ph
            val previous = ungradedAhbState.value
            ungradedAhbState.value = newAhb
            newAhb = null  // ownership transferred
            // Atomic commit: the freshest uniforms and the rebaked AHB go to
            // the GL thread as ONE pass with ONE render — never a frame of
            // new-uniforms-on-old-texture. Record which spatialKey this AHB
            // now carries so the SideEffect gate below resumes instant
            // uniform pushes (and releases any pushes held during the bake).
            glViewState.value?.setSourceWithUniforms(
                ungradedAhbState.value!!, currentParams.value,
            )
            lastBakedSpatialKey.value = spatialKey
            // Bake lifecycle signal: "rebake finished" — keys now equal.
            onBakeStateChange?.invoke(spatialKey.hashCode(), spatialKey.hashCode())
            Log.i(TAG, "ungradedAhb ready ${w}×${h} (fast=$applied ${System.currentTimeMillis() - tSp}ms) " +
                "for ${stageATifPath.substringAfterLast('/')} (clahe=${params.claheEnabled} lumaNR=${params.luminanceNR})")
            onAhbReady?.invoke()
            ungradedAhbState.value?.let { onAhbBound?.invoke(it) }
            onImageSize?.invoke(w, h)
            previous?.close()
        } finally {
            newAhb?.close()
            if (!gateReleased) {
                ungradedBakeRunning.set(false)
                if (ungradedRetryPending.getAndSet(false)) {
                    // NonCancellable is REQUIRED: this finally usually runs
                    // because the effect was CANCELLED (a newer tick), and a
                    // plain withContext in a cancelled coroutine throws
                    // immediately — but getAndSet(false) above has already
                    // CONSUMED the retry flag, so the pending bake would be
                    // lost forever (stale spatial AHB, preview≠save until the
                    // next unrelated param change).
                    withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main) {
                        ungradedBakeRetryTick.value++
                    }
                }
            }
        }
    }

    // Push uniforms synchronously after every recomposition where `params` or
    // the View instance changed. SideEffect runs in the same frame as the
    // recomposition (no coroutine launch overhead), so the GL thread gets the
    // new params in the same vsync cycle — critical for low-latency slider response.
    //
    // ATOMIC-UPDATE GATE (2026-08-29): when the change ALSO dirtied the CPU
    // spatial pre-pass (spatialKey != the key the bound AHB was baked with),
    // hold the push — the pending bake's commit (setSourceWithUniforms above)
    // delivers these uniforms together with the rebaked texture in one frame.
    // Pushing here too made mixed adjustments (AI Color Enhance = sat/vib
    // uniforms + CLAHE spatial; hold-to-compare's params swap) update the
    // canvas in two visible steps. Pure-uniform changes (spatialKey unchanged)
    // keep the instant path. Initially null → held until the first bake
    // commits, which is also the first moment there is a texture to grade.
    val glViewForUniforms = glViewState.value
    val uniformsInSyncWithBake = lastBakedSpatialKey.value == spatialKey
    SideEffect {
        if (uniformsInSyncWithBake) glViewForUniforms?.updateUniforms(params)
    }
    // Separate debounced effect for the Tone Curves histogram refresh.
    // Kept as a LaunchedEffect so the 200 ms delay doesn't block the frame.
    LaunchedEffect(params, glViewState.value) {
        val v = glViewState.value ?: return@LaunchedEffect
        if (onGradedFrameReady != null) {
            kotlinx.coroutines.delay(200)
            onGradedFrameReady(v)
        }
    }

    // (Was: "TODO: observe RendererCore state". RendererCore was deleted
    // 2026-08-27 as dead, thread-unsafe scaffolding — renderer state is owned by
    // RawV3GlSurfaceView, which this composable already drives directly.)

    // Subject-mask upload — converts the 320×320 FloatArray probability
    // map to a row-major byte[] (255 = certain subject) and pushes it to
    // the renderer once per mask change. The shader squares the value
    // before gating so 8-bit precision is plenty (256 buckets squared
    // gives ~16-bit effective resolution at the high-confidence end).
    val lastUploadedMaskState: MutableState<RawV3SegmentationMasks?> = remember { mutableStateOf(null) }
    val lastSubjectMaskView: MutableState<RawV3GlSurfaceView?> = remember { mutableStateOf(null) }
    LaunchedEffect(subjectMask, glViewState.value) {
        val v = glViewState.value ?: return@LaunchedEffect
        // Re-upload after a rotation/View-recreation even if the mask
        // data is the same — the new GL context has no texture yet.
        val sameView = lastSubjectMaskView.value === v
        if (sameView && subjectMask === lastUploadedMaskState.value) return@LaunchedEffect
        lastSubjectMaskView.value = v
        if (subjectMask == null || !subjectMask.hasSubject) {
            // An empty matte must not be "ready": the shader would then gate
            // Bokeh/Bloom against nothing (blur everything, protect nothing).
            v.clearSubjectMask()
            lastUploadedMaskState.value = subjectMask
            if (subjectMask == null) Log.i(TAG, "Subject mask cleared")
            else Log.w(TAG, "Subject mask EMPTY (coverage=${"%.2f".format(subjectMask.subjectCoverage * 100f)}% " +
                "of frame > 0.5) — subject gating disabled: Bokeh off, Bloom protect inert (nothing to protect)")
        } else {
            // Prefer the guided-filter-refined mask (1024px) when available —
            // it snaps edges to actual image boundaries. Fall back to the raw
            // 320x320 U2Net output when refinement failed or is still pending.
            val refined = subjectMask.refinedMask
            val (src, maskW, maskH) = if (refined != null &&
                subjectMask.refinedWidth > 0 && subjectMask.refinedHeight > 0) {
                Triple(refined, subjectMask.refinedWidth, subjectMask.refinedHeight)
            } else {
                Triple(subjectMask.subjectMask,
                    RawV3SegmentationMasks.MASK_SIZE, RawV3SegmentationMasks.MASK_SIZE)
            }
            val n = src.size
            if (n == maskW * maskH) {
                val bytes = ByteArray(n)
                for (i in 0 until n) {
                    val q = (src[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    bytes[i] = q.toByte()
                }
                v.uploadSubjectMask(bytes, maskW, maskH)
                v.setSubjectMaskInnerRect(
                    subjectMask.innerRectLeft,
                    subjectMask.innerRectTop,
                    subjectMask.innerRectRight,
                    subjectMask.innerRectBottom,
                )
                // M12.2c.4 — upload Sobel edge alongside so the shader's
                // subjectGate() can snap soft silhouettes to true image
                // gradients. Cheap (~100 KB), same 320×320 grid.
                val rawSide = RawV3SegmentationMasks.MASK_SIZE
                val edge = subjectMask.edgeMask
                if (edge.size == rawSide * rawSide) {
                    val edgeBytes = ByteArray(edge.size)
                    for (i in edge.indices) {
                        val q = (edge[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                        edgeBytes[i] = q.toByte()
                    }
                    v.uploadSobelEdgeMask(edgeBytes, rawSide, rawSide)
                    // Snap strength is driven by the Mask tab's
                    // Structural Snap toggle via a separate effect
                    // below; we just upload the edge texture here so
                    // it's available when the toggle flips on.
                }
                lastUploadedMaskState.value = subjectMask
                val maskLabel = if (refined != null) "refined" else "raw"
                Log.i(TAG, "Subject mask uploaded ($maskLabel) ${maskW}x${maskH} " +
                        "coverage=${"%.1f".format(subjectMask.subjectCoverage * 100f)}% rect=" +
                        "(${subjectMask.innerRectLeft},${subjectMask.innerRectTop})-" +
                        "(${subjectMask.innerRectRight},${subjectMask.innerRectBottom})")
            } else {
                Log.w(TAG, "Subject mask size mismatch: $n != ${maskW * maskH}")
            }
        }
    }

    // Bokeh attenuation mask = max(sky, terrain) from Cityscapes seg.
    // Uploaded once when the masks become available; cleared (texture stays
    // bound but flag goes false) when null. Same 320×320 grid as the subject
    // mask so the shader can reuse uSubjectMaskRect for letterbox UV remap.
    LaunchedEffect(cityscapesMasks, glViewState.value) {
        val v = glViewState.value ?: return@LaunchedEffect
        val masks = cityscapesMasks ?: return@LaunchedEffect
        val side = RawV3SegmentationMasks.MASK_SIZE
        val sky = masks.sky; val terrain = masks.terrain
        if (sky.size != side * side || terrain.size != side * side) {
            Log.w(TAG, "Cityscapes mask size mismatch — sky=${sky.size} terrain=${terrain.size} side=$side")
            return@LaunchedEffect
        }
        val bytes = ByteArray(side * side)
        for (i in bytes.indices) {
            val v01 = kotlin.math.max(sky[i], terrain[i])
            bytes[i] = (v01 * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        v.uploadBokehAttenuation(bytes, side, side)
        Log.i(TAG, "Bokeh attenuation uploaded ${side}×$side (max sky/terrain)")
    }

    // M12.2c.3 — Brush mask upload. The brush canvas mutates the same
    // Bitmap instance across strokes, so React-style identity comparison
    // won't fire. We key on the dirty counter that the editor bumps after
    // every paint/fill/clear operation. Bitmap is downsampled to a small
    // GL_R8 (max 512 long-side) so per-stroke uploads stay cheap.
    // Re-key on the GL view so rotation re-uploads the brush mask.
    LaunchedEffect(brushMask, brushMaskDirty, brushMaskLayers, overlayMaskRef, lumMaskOverlayLayer, glViewState.value) {
        val v = glViewState.value ?: return@LaunchedEffect
        // Resolve the effective per-layer bitmap list. Committed layers
        // (post-Apply) come via [brushMaskLayers]; the live [brushMask]
        // appends as the NEXT layer while painting before commit so the
        // in-flight stroke is visible on top of any prior committed
        // masks (subject / sky / face / etc.). Previously the live brush
        // was dropped whenever committed layers existed → painting on
        // top of a Select-Subject mask had no preview effect.
        val layers: List<android.graphics.Bitmap?> = buildList {
            addAll(brushMaskLayers)
            if (brushMask != null) add(brushMask)
        }.ifEmpty { listOf(brushMask) }
        for (layer in 0 until RawV3GlSurfaceView.MASK_LAYERS) {
            val bmp = layers.getOrNull(layer)
            if (bmp == null) {
                v.clearBrushMask(layer)
                continue
            }
            val maxSide = 512
            val sw = bmp.width
            val sh = bmp.height
            val scale = if (maxOf(sw, sh) > maxSide)
                maxSide.toFloat() / maxOf(sw, sh)
            else 1f
            val dstW = (sw * scale).toInt().coerceAtLeast(1)
            val dstH = (sh * scale).toInt().coerceAtLeast(1)
            val bytes = withContext(Dispatchers.Default) {
                // Pull alpha out of ARGB_8888 → byte[] row-major. Bilinear
                // downsample is good enough for an 8-bit mask.
                val scaled = if (scale < 1f)
                    android.graphics.Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
                else bmp
                val pixels = IntArray(dstW * dstH)
                scaled.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
                val out = ByteArray(dstW * dstH)
                for (i in pixels.indices) {
                    out[i] = ((pixels[i] ushr 24) and 0xFF).toByte()
                }
                if (scaled !== bmp) scaled.recycle()
                out
            }
            v.uploadBrushMask(layer, bytes, dstW, dstH)
            Log.i(TAG, "Brush mask layer $layer uploaded ${dstW}×$dstH (dirty=$brushMaskDirty)")
        }
        // Tell the renderer WHICH layer the "Show" overlay should tint: only the
        // in-flight edit layer (resolved by object identity against the exact
        // upload order above), never committed layers. -1 = tint nothing.
        val overlayIdx = when {
            overlayMaskRef != null   -> layers.indexOfFirst { it === overlayMaskRef }
            lumMaskOverlayLayer >= 0 -> lumMaskOverlayLayer   // lum mask (no bitmap)
            else                     -> -1
        }
        Log.i(TAG, "Mask overlay: overlayIdx=$overlayIdx layers=${layers.size} " +
            "overlayMaskRef=${if (overlayMaskRef != null) "${overlayMaskRef.width}×${overlayMaskRef.height}" else "null"} " +
            "showMaskOverlay=$showMaskOverlay")
        v.setMaskOverlayLayer(overlayIdx)
    }

    // LUT upload — only fires when the path actually changes, so dragging
    // the intensity slider doesn't re-parse the .cube. Re-key on the GL
    // view so a rotation triggers a fresh upload (sticky LUT was lost
    // when the EGL context was torn down).
    val lastLutView: MutableState<RawV3GlSurfaceView?> = remember { mutableStateOf(null) }
    // Also key on ungradedAhbState so we retry the upload once the GL renderer
    // is fully initialized (rendererHandle != 0). The first attempt fires when
    // glViewState is set but EGL may not be ready yet → nativeUploadLut3d
    // returns false. Once the AHB is bound the renderer is guaranteed live.
    LaunchedEffect(lutCubePath, glViewState.value, ungradedAhbState.value) {
        val v = glViewState.value ?: return@LaunchedEffect
        val sameView = lastLutView.value === v
        if (sameView && lutCubePath == lastUploadedLutState.value) return@LaunchedEffect
        lastLutView.value = v
        if (lutCubePath == null) {
            v.clearLut3d()
            lastUploadedLutState.value = null
            Log.i(TAG, "LUT cleared")
        } else {
            v.uploadLut3d(lutCubePath) { ok ->
                if (ok) {
                    lastUploadedLutState.value = lutCubePath
                    Log.i(TAG, "LUT uploaded: ${lutCubePath.substringAfterLast('/')}")
                } else {
                    Log.w(TAG, "LUT upload failed: $lutCubePath")
                }
            }
        }
    }

    // Tone Curve LUT upload — re-key on the view + a stable content signature
    // so it only uploads when the curve actually changes (not every recompose).
    // Keyed ALSO on uniformsInSyncWithBake (the atomic-update gate): while a
    // spatial rebake is pending, a curve change rides along with the same
    // logical edit (hold-to-compare clears the curve AND swaps params), so
    // uploading it early would be a visible extra step before the bake lands.
    // The effect re-fires when the gate opens at commit and uploads then.
    val lastCurveView: MutableState<RawV3GlSurfaceView?> = remember { mutableStateOf(null) }
    val lastCurveSig: MutableState<Int> = remember { mutableStateOf(0) }
    val curveSig = toneCurveLut?.let { java.util.Arrays.hashCode(it) } ?: 0
    LaunchedEffect(curveSig, glViewState.value, uniformsInSyncWithBake) {
        if (!uniformsInSyncWithBake) return@LaunchedEffect
        val v = glViewState.value ?: return@LaunchedEffect
        val sameView = lastCurveView.value === v
        if (sameView && curveSig == lastCurveSig.value) return@LaunchedEffect
        lastCurveView.value = v
        lastCurveSig.value = curveSig
        if (toneCurveLut == null) {
            v.clearToneCurve()
            Log.i(TAG, "Tone curve cleared")
        } else {
            v.uploadToneCurve(toneCurveLut)
            Log.i(TAG, "Tone curve uploaded (sig=$curveSig)")
        }
    }

    // Preview pan/zoom → pushed into GL (window viewport). Re-runs whenever the
    // gesture state changes or the view is (re)created so the transform tracks.
    LaunchedEffect(canvasScale, canvasOffsetX, canvasOffsetY, glViewState.value) {
        val v = glViewState.value ?: return@LaunchedEffect
        v.setViewTransform(canvasScale, canvasOffsetX, canvasOffsetY)
    }

    // Mask tab "Show" overlay tint (GL-side; can't be a Compose overlay).
    LaunchedEffect(showMaskOverlay, glViewState.value) {
        val v = glViewState.value ?: return@LaunchedEffect
        Log.i(TAG, "setShowMaskOverlay($showMaskOverlay)")
        v.setShowMaskOverlay(showMaskOverlay)
    }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            RawV3GlSurfaceView(c).also { view ->
                glViewState.value = view
                // Rebind after rotation/recreate — replay source + current uniforms.
                ungradedAhbState.value?.let {
                    view.updateUniforms(params)
                    view.setSource(it)
                }
            }
        },
        onRelease = { view ->
            // Compose has detached the View; tear down the renderer.
            view.shutdown()
            glViewState.value = null
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            // Closing the AHardwareBuffers here (main thread) while the spatial
            // re-apply is still copying them on the Default dispatcher unmapped
            // the buffer under the native read → SIGSEGV (SEGV_MAPERR) use-after-
            // free in runStageBApplySpatialToAhb. The bake holds
            // `ungradedBakeRunning` for the whole native call, so hand the buffers
            // to a detached daemon that waits (bounded) for the bake to finish
            // before closing. Never blocks the main thread; force-closes after the
            // timeout so a wedged bake can't leak them.
            val u = ungradedAhbState.value
            val p = pristineAhbState.value
            ungradedAhbState.value = null
            pristineAhbState.value = null
            if (u != null || p != null) {
                Thread {
                    var spins = 0
                    while (ungradedBakeRunning.get() && spins++ < 300) Thread.sleep(10)
                    runCatching { u?.close() }
                    runCatching { p?.close() }
                }.apply { isDaemon = true; name = "RawV3.AhbCloser" }.start()
            }
        }
    }
}

/**
 * Downsample the Stage A TIFF at [stageATifPath] into a fresh AHB whose
 * long-side is clamped to [maxLongSide]. Returns (ahb, w, h) or null on
 * failure. Calls [RawV3Engine.stageBDownsample] starting from an
 * already-existing Stage A path instead of running the LibRaw decode.
 */
private fun allocateAndFillAhb(
    context: Context,
    stageATifPath: String,
    maxLongSide: Int,
    params: ShaderParams,
    subjectMask: RawV3SegmentationMasks? = null,
    // When > 0, use these as the authoritative source dimensions instead of
    // re-reading the TIFF header. Eliminates the rare race where readStageADims
    // reads a partially-written or stale TIFF and returns landscape dims.
    knownSrcWidth: Int = 0,
    knownSrcHeight: Int = 0,
    // Cancellation token shared with the native downsample. Set [0]=1 to abort.
    cancelFlag: ByteArray? = null,
): Triple<HardwareBuffer, Int, Int>? {
    val tif = java.io.File(stageATifPath)
    if (!tif.exists() || tif.length() < 256) {
        Log.w(TAG, "Stage A TIFF missing or truncated: $stageATifPath")
        return null
    }
    val srcDims = when {
        knownSrcWidth > 0 && knownSrcHeight > 0 -> knownSrcWidth to knownSrcHeight
        else -> readStageADims(tif) ?: return null
    }
    val (srcW, srcH) = srcDims
    Log.d(TAG, "allocateAndFillAhb: srcDims=${srcW}×${srcH} (known=${knownSrcWidth > 0}) tif=${tif.name}")
    if (srcW > srcH) {
        Log.w(TAG, "allocateAndFillAhb: LANDSCAPE dims ${srcW}×${srcH} — EXIF flip may be missing; thread=${Thread.currentThread().name}")
        Log.w(TAG, "allocateAndFillAhb: tif size=${tif.length()} path=${tif.absolutePath}")
    }

    val scale = min(
        min(maxLongSide.toFloat() / srcW, maxLongSide.toFloat() / srcH),
        1f,
    )
    val targetW = (srcW * scale).toInt().coerceAtLeast(1)
    val targetH = (srcH * scale).toInt().coerceAtLeast(1)
    Log.d(TAG, "allocateAndFillAhb: scale=$scale targetW=$targetW targetH=$targetH")

    // CLAHE's native apply pass reads the buffer back, so the AHB needs CPU
    // READ usage too — otherwise the lock at stage_b_downsample.cpp fails.
    val tAlloc0 = System.currentTimeMillis()
    val ahb = HardwareBuffer.create(
        targetW, targetH,
        HardwareBuffer.RGBA_FP16,
        1,
        HardwareBuffer.USAGE_CPU_READ_RARELY or
            HardwareBuffer.USAGE_CPU_WRITE_RARELY or
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    )
    val tBake0 = System.currentTimeMillis()
    // Subject mask. Passed whenever available so spatial pre-passes
    // (smart sharpness, detail background smoothing) can feather by subject.
    val best = subjectMask?.gatingMask()
    val r = RawV3Engine.stageBDownsample(
        stageATifPath, ahb, targetW, targetH, params.toFloatArray(),
        best?.first, best?.second ?: 0, best?.third ?: 0,
        cancelFlag = cancelFlag,
    )
    if (r.cancelled) {
        ahb.close()
        Log.d(TAG, "Stage B bake cancelled — discarding partial AHB")
        return null
    }
    if (!r.success) {
        ahb.close()
        Log.w(TAG, "Stage B downsample failed: ${r.error}")
        return null
    }
    val tEnd = System.currentTimeMillis()
    Log.i(TAG, "Stage B fill ${r.width}×${r.height} (src ${srcW}×$srcH, long=$maxLongSide): " +
        "alloc=${tBake0 - tAlloc0}ms downsample+spatial=${tEnd - tBake0}ms total=${tEnd - tAlloc0}ms " +
        "[NRluma=${params.luminanceNR} NRcolor=${params.colorNR} clahe=${params.claheEnabled} " +
        "sharp=${params.detailSharpness} clarity=${params.detailClarity} texture=${params.detailTexture}]")
    return Triple(ahb, r.width, r.height)
}

/**
 * Convert up to 4 brush-mask Bitmaps into the (Array<FloatArray?>, IntArray, IntArray)
 * triple the native call expects. Each layer's alpha channel becomes a
 * [0..1] FloatArray sized to the layer's bitmap dims.
 *
 * Mirrors RawV3Coordinator.decodeMaskLayers's logic but accepts in-memory
 * Bitmaps (the Composable already has them loaded) and produces per-layer
 * dims (the native struct accepts mismatched sizes — each layer carries
 * its own W/H).
 *
 * Caps each layer's long side at 1024 to keep memory bounded; the native
 * kernel bilinearly samples by UV so a slightly downscaled mask is fine.
 */
private fun decodeBrushMaskLayers(
    layers: List<android.graphics.Bitmap?>,
): Triple<Array<FloatArray?>?, IntArray?, IntArray?> {
    if (layers.isEmpty() || layers.all { it == null }) {
        return Triple(null, null, null)
    }
    val outData = arrayOfNulls<FloatArray>(4)
    val outW    = IntArray(4)
    val outH    = IntArray(4)
    val maxSide = 1024
    for (i in 0 until minOf(layers.size, 4)) {
        val src = layers[i] ?: continue
        val scale = minOf(maxSide.toFloat() / src.width, maxSide.toFloat() / src.height, 1f)
        val w = (src.width  * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (src.width != w || src.height != h)
            android.graphics.Bitmap.createScaledBitmap(src, w, h, true) else src
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        val plane = FloatArray(w * h) { j -> ((px[j] ushr 24) and 0xFF) / 255f }
        if (scaled !== src) scaled.recycle()
        outData[i] = plane
        outW[i]    = w
        outH[i]    = h
    }
    return Triple(outData, outW, outH)
}

/**
 * Read width/height from a Stage A BigTIFF without decoding pixels.
 *
 * Stage A uses the writer in `tiff_mmap_io.cpp` whose IFD entry layout
 * places ImageWidth (uint32) at file offset 24 + 12 = 36, and
 * ImageLength at 24 + 20 + 12 = 56. This mirrors the smoke's
 * `readStageADims` helper.
 */
private fun readStageADims(tif: java.io.File): Pair<Int, Int>? {
    return runCatching {
        java.io.RandomAccessFile(tif, "r").use { raf ->
            val w = readU32(raf, 24 + 12)
            val h = readU32(raf, 24 + 20 + 12)
            if (w <= 0 || h <= 0) null else w to h
        }
    }.getOrNull()
}

private fun readU32(raf: java.io.RandomAccessFile, off: Long): Int {
    raf.seek(off)
    val b0 = raf.readUnsignedByte()
    val b1 = raf.readUnsignedByte()
    val b2 = raf.readUnsignedByte()
    val b3 = raf.readUnsignedByte()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}
