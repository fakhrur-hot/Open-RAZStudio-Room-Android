/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — Stage B GLES surface (M3+M4).
 *
 *  SurfaceView-backed renderer. We tried TextureView (composes inside Compose
 *  hierarchies cleanly) but the device's compositor delivered black frames
 *  every time despite glReadPixels confirming correct framebuffer contents.
 *  SurfaceView punches a dedicated window-system surface — older mechanism,
 *  better real-time behaviour, no dependence on the compositor's texture-
 *  upload heuristics.
 *
 *  Important: the host Activity must NOT use a Compose AlertDialog around
 *  this view (SurfaceView is incompatible with dialog scrims). The dedicated
 *  RawV3SmokeActivity hosts it inside a vanilla LinearLayout instead.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

class RawV3GlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    init {
        runCatching { System.loadLibrary("raw_decoder") }
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        setZOrderOnTop(true)
    }

    private val renderThread = HandlerThread("RawV3Gl").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    private val ahbMaskLoader = AhbMaskLoader()
    private var eglDisplay: Long = 0L

    // ── ADPF performance hint session (API 31+) ──────────────────────────────
    // Registers the render thread with the OS scheduler and reports each
    // frame's actual duration against the display's frame budget, so Android
    // 14+ battery policies keep the cores boosted while the preview is live
    // instead of throttling the "background-looking" HandlerThread. Created ON
    // the render thread so Process.myTid() is the right tid; best-effort —
    // absent/failed sessions degrade to exactly the previous behaviour.
    @Volatile private var hintSession: android.os.PerformanceHintManager.Session? = null

    init {
        renderHandler.post {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    val mgr = context.getSystemService(android.os.PerformanceHintManager::class.java)
                    val refresh = (display?.refreshRate ?: 60f).coerceAtLeast(30f)
                    val budgetNs = (1_000_000_000.0 / refresh).toLong()
                    hintSession = mgr?.createHintSession(
                        intArrayOf(android.os.Process.myTid()), budgetNs,
                    )
                    Log.i(TAG, "ADPF hint session ${if (hintSession != null) "created" else "unavailable"} " +
                        "(budget=${budgetNs / 1_000_000.0}ms @ ${refresh}Hz)")
                }
            }
        }
    }

    // When the panel-resize handle is dragged, the SurfaceView's bounds change
    // continuously. surfaceChanged() doesn't always fire per layout pass, so the
    // GL surface buffer grows while the renderer's last frame still covers the
    // OLD (smaller) viewport → black strip on the right/bottom of the new area.
    // Force a fresh renderFrame on every size change; renderFrame() re-queries
    // the surface size and re-letterboxes, repainting the full buffer.
    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        renderHandler.post {
            val handle = rendererHandle
            if (handle != 0L) render(handle, "onSizeChanged")
        }
    }

    @Volatile private var rendererHandle: Long = 0L

    /** Traced render — every preview update goes through here so logcat shows the caller. */
    private fun render(handle: Long, caller: String) {
        Log.d(TAG, "render[$caller] handle=$handle thread=${Thread.currentThread().name}")
        val t0 = System.nanoTime()
        val alive = nativeRenderFrame(handle)
        // ADPF: report the real frame cost so the scheduler can tell whether
        // the render thread is meeting the display budget (see hintSession).
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            hintSession?.let { s ->
                runCatching { s.reportActualWorkDuration(System.nanoTime() - t0) }
            }
        }
        if (!alive) {
            // Surface was lost mid-render — C++ self-released; clear stale handle
            // and schedule a reboot so the next update restores the renderer.
            Log.w(TAG, "render[$caller]: surface lost, clearing handle and rebooting")
            rendererHandle = 0L
            if (pendingSurfaceHolder != null && pendingAhb != null) {
                scheduleReboot()
            }
        }
    }
    // True while a surfaceChanged-triggered release+boot is queued on the
    // render thread. Prevents setSource from queuing a
    // second bootRenderer() over the top of it.
    @Volatile private var bootPending: Boolean = false
    @Volatile private var pendingAhb: HardwareBuffer? = null
    @Volatile private var pendingSurfaceHolder: SurfaceHolder? = null

    /** Fired (on the render thread) once the first frame after a boot has been
     *  re-presented — the signal the composable uses to drop the thumbnail
     *  placeholder only when the GL surface is provably showing content, so the
     *  canvas is never blank in between (never-blank preview). */
    @Volatile var onFirstFrameRendered: (() -> Unit)? = null
    /** Fired on the main thread after EGL boot/replay (screen-on). Compose must
     *  re-upload masks: the SurfaceView instance is reused so LaunchedEffects
     *  keyed only on the view would skip. */
    @Volatile var onEglBooted: (() -> Unit)? = null
    /** Graded snapshot taken on the GL thread immediately before EGL release. */
    @Volatile var onPreEglReleaseSnapshot: ((android.graphics.Bitmap) -> Unit)? = null
    @Volatile private var firstFrameSignalled: Boolean = false

    // pendingAhb is a sticky ref that can outlive the buffer: the composable's
    // dispose path closes its AHBs, but a delayed setSource retry / reboot may
    // still hand pendingAhb to native — AHardwareBuffer_fromHardwareBuffer on a
    // CLOSED HardwareBuffer is a dangling pointer. Guard every deferred reuse.
    // (isClosed is API 28; on 26/27 fall back to "assume open" — same as before.)
    private fun HardwareBuffer.isUsable(): Boolean =
        android.os.Build.VERSION.SDK_INT < 28 || !isClosed

    fun setSource(ahb: HardwareBuffer) {
        pendingAhb = ahb
        renderHandler.post {
            val h = rendererHandle
            Log.d(TAG, "setSource: h=$h bootPending=$bootPending holder=${pendingSurfaceHolder != null}")
            if (h != 0L && !bootPending) {
                nativeUpdateAhb(h, ahb, -1)
                render(h, "setSource")
            } else if (pendingSurfaceHolder != null) {
                scheduleReboot()
            } else {
                renderHandler.postDelayed({
                    val retry = pendingAhb ?: ahb
                    if (retry.isUsable()) setSource(retry)
                    else Log.w(TAG, "setSource retry: buffer closed — dropping")
                }, 200)
            }
        }
    }

    fun requestRender() {
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) render(h, "requestRender")
        }
    }

    /**
     * Atomic commit of a spatial-rebake result: upload [params] AND bind [ahb]
     * in ONE render-thread pass with a single render() at the end, so the
     * preview advances in exactly one visible step. Before this existed the
     * bake commit called updateUniforms() then setSource() — two posted
     * runnables, each rendering its own frame, so one frame flashed the NEW
     * uniforms over the OLD texture (the second half of the "adjustments
     * update the canvas in two steps" report, 2026-08-29). Boot/retry
     * semantics mirror [setSource]; params stay sticky in the scratch buffer
     * so a reboot replay picks them up together with [pendingAhb].
     */
    fun setSourceWithUniforms(ahb: HardwareBuffer, params: ShaderParams) {
        synchronized(paramsLock) { params.fillFloatArray(scratchFloatArray) }
        hasParams = true
        pendingAhb = ahb
        renderHandler.post {
            val h = rendererHandle
            Log.d(TAG, "setSourceWithUniforms: h=$h bootPending=$bootPending")
            if (h != 0L && !bootPending) {
                synchronized(paramsLock) {
                    System.arraycopy(scratchFloatArray, 0, renderFloatArray, 0, renderFloatArray.size)
                }
                nativeUpdateUniforms(h, renderFloatArray)
                nativeUpdateAhb(h, ahb, -1)
                render(h, "setSourceWithUniforms")
            } else if (pendingSurfaceHolder != null) {
                scheduleReboot()
            } else {
                renderHandler.postDelayed({
                    val retry = pendingAhb ?: ahb
                    if (retry.isUsable()) setSource(retry)
                    else Log.w(TAG, "setSourceWithUniforms retry: buffer closed — dropping")
                }, 200)
            }
        }
    }

    /**
     * Set the preview pan/zoom. [scale] zooms about the photo centre;
     * [offsetX]/[offsetY] pan in surface pixels (offsetY uses Compose's
     * top-down sign). Applied inside GL to the window viewport so the
     * SurfaceView stays pinned to its slot and the preview is clipped to the
     * letterbox — it can never paint over the surrounding chrome. Cached so
     * the transform is replayed after a surface reboot (rotation / resize).
     */
    fun setViewTransform(scale: Float, offsetX: Float, offsetY: Float) {
        synchronized(pendingLock) {
            pendingViewScale = scale
            pendingViewOffsetX = offsetX
            pendingViewOffsetY = offsetY
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeSetViewTransform(h, scale, offsetX, offsetY)
                render(h, "setViewTransform")
            }
        }
    }

    /**
     * Mask tab "Show" preview. When [show] is true the GL output tints the
     * masked region blue so the painted/selected area is visible even before
     * any adjustment is applied. Must be drawn GL-side: this surface is
     * ZOrderOnTop, so a Compose overlay would render behind it and never show.
     */
    fun setShowMaskOverlay(show: Boolean) {
        synchronized(pendingLock) { pendingShowMaskOverlay = show }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeSetShowMaskOverlay(h, show)
                render(h, "setShowMaskOverlay")
            }
        }
    }

    /**
     * Which mask layer the "Show" overlay tints blue — the layer currently being
     * edited. Committed layers stay uploaded (their adjustments apply) but are not
     * tinted, so a fresh layer's edit doesn't inherit the previous card's blue.
     * Pass a negative value when no layer is being edited (tint nothing).
     */
    fun setMaskOverlayLayer(layer: Int) {
        synchronized(pendingLock) { pendingMaskOverlayLayer = layer }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeSetMaskOverlayLayer(h, layer)
                render(h, "setMaskOverlayLayer")
            }
        }
    }

    // Sticky GPU state. Each setter caches the latest values here so the
    // renderer's first frame after surfaceCreated() can replay them — the
    // EGL context is torn down across configuration changes (rotation),
    // and Compose can't predict when the new surface will boot.
    //
    // THREADING: setters run on the MAIN thread; bootRenderer() reads these on
    // the RENDER thread with no happens-before edge (the 120 ms-delayed reboot
    // runnable was posted long before the setter ran). Multi-field groups
    // (bytes + W + H) could therefore be observed torn — new bytes with stale
    // dims — which the JNI length guard turns into a silently skipped replay
    // (mask missing after rotation). Same bug class as the old params flicker.
    // Every write below and the whole bootRenderer replay read take
    // [pendingLock] so the replay always sees consistent groups.
    private val pendingLock = Any()
    private var pendingLutPath: String? = null
    private var pendingClearLut: Boolean = false
    private var pendingSubjectMaskBytes: ByteArray? = null
    private var pendingSubjectMaskAhb: HardwareBuffer? = null
    private var pendingSubjectMaskW: Int = 0
    private var pendingSubjectMaskH: Int = 0
    private var pendingSubjectRect: FloatArray? = null
    // 4 brush-mask layers (M12.2c.2b). Each layer keeps its own sticky state
    // so a surface re-create (rotation) replays every layer independently.
    private val pendingBrushMaskBytes = arrayOfNulls<ByteArray>(MASK_LAYERS)
    private val pendingBrushMaskAhb = arrayOfNulls<HardwareBuffer>(MASK_LAYERS)
    private val pendingBrushMaskW = IntArray(MASK_LAYERS)
    private val pendingBrushMaskH = IntArray(MASK_LAYERS)
    private val pendingClearBrushMask = BooleanArray(MASK_LAYERS)
    private var pendingSobelEdgeBytes: ByteArray? = null
    private var pendingSobelEdgeAhb: HardwareBuffer? = null
    private var pendingSobelEdgeW: Int = 0
    private var pendingSobelEdgeH: Int = 0
    private var pendingEdgeSnap: FloatArray? = null
    private var pendingBokehAttenAhb: HardwareBuffer? = null
    private var pendingDepthMapAhb: HardwareBuffer? = null
    private var pendingDepthMapFocus: Float = 0.5f
    private var pendingToneCurve: ByteArray? = null
    private var pendingClearToneCurve: Boolean = false
    private var pendingCurveLuts: Array<FloatArray>? = null  // [master, r, g, b]
    private var pendingVintageMist: ByteArray? = null
    private var pendingVintageMistW: Int = 0
    private var pendingVintageMistH: Int = 0
    private var pendingVintageFilm: ByteArray? = null
    private var pendingVintageFilmW: Int = 0
    private var pendingVintageFilmH: Int = 0
    // Preview pan/zoom (replayed on surface reboot). Identity until a gesture.
    private var pendingViewScale: Float = 1f
    private var pendingViewOffsetX: Float = 0f
    private var pendingViewOffsetY: Float = 0f
    private var pendingShowMaskOverlay: Boolean = false
    private var pendingMaskOverlayLayer: Int = -1

    // ── Double-buffered params (thread-safe, no per-frame allocation) ─────
    //   scratchFloatArray is written by the MAIN thread (updateUniforms);
    //   renderFloatArray is read by the RENDER thread (native upload). The
    //   main thread fills scratch under paramsLock; the render runnable copies
    //   scratch→render under the SAME lock, so the native upload always sees a
    //   CONSISTENT snapshot. Previously both threads shared ONE array with no
    //   lock — a fast slider drag let the main thread overwrite it mid-read on
    //   the render thread, uploading a torn mix of old+new slots → the preview
    //   FLICKERED (worse the faster the value changed). hasParams gates the
    //   first frame + the rotation/boot replay.
    private val paramsLock = Any()
    private val scratchFloatArray = FloatArray(ShaderParams.FLOAT_COUNT)  // main writes
    private val renderFloatArray  = FloatArray(ShaderParams.FLOAT_COUNT)  // render reads
    @Volatile private var hasParams = false

    // ── Frame coalescing — drop intermediate frames ──────────────────────
    //   When the render thread is busy, new updateUniforms calls stack up in
    //   the Handler queue. We keep at most one pending Runnable; it copies the
    //   LATEST scratch values when it finally runs.
    @Volatile private var uniformRenderPending = false

    private val uniformRenderRunnable = Runnable {
        uniformRenderPending = false
        val h = rendererHandle
        if (h == 0L || !hasParams) return@Runnable
        // Snapshot the latest params under the lock so a concurrent main-thread
        // fill can't tear this read.
        synchronized(paramsLock) {
            System.arraycopy(scratchFloatArray, 0, renderFloatArray, 0, renderFloatArray.size)
        }
        nativeUpdateUniforms(h, renderFloatArray)
        render(h, "updateUniforms")
    }

    fun updateUniforms(params: ShaderParams) {
        // Fill the scratch array in-place (no allocation) under the lock so the
        // render thread never reads a half-written array.
        synchronized(paramsLock) {
            params.fillFloatArray(scratchFloatArray)
        }
        hasParams = true
        // Coalesce — if a render is already queued, skip posting a new Runnable.
        // The pending runnable snapshots the latest scratch values when it runs.
        if (!uniformRenderPending) {
            uniformRenderPending = true
            renderHandler.post(uniformRenderRunnable)
        }
        // Do NOT boot from updateUniforms — params are cached in scratchFloatArray
        // (hasParams) and replayed by bootRenderer() when setSource fires.
        // Booting here caused a premature EGL init before surfaceChanged settled
        // the final surface dimensions, producing a second full release+reboot
        // cycle that flooded the BLASTBufferQueue.
    }

    /** Parse + upload an Adobe/Resolve .cube LUT. Posts to the GL thread. */
    fun uploadLut3d(cubePath: String, onResult: ((Boolean) -> Unit)? = null) {
        synchronized(pendingLock) {
            pendingLutPath = cubePath
            pendingClearLut = false
        }
        renderHandler.post {
            val h = rendererHandle
            val ok = if (h != 0L) nativeUploadLut3d(h, cubePath) else false
            if (ok && h != 0L) render(h, "uploadLut3d")
            onResult?.let { post { it(ok) } }
        }
    }

    /** Upload a Tone Curve LUT (256 RGB8 texels = 768 bytes). Posts to GL. */
    fun uploadToneCurve(lut: ByteArray) {
        synchronized(pendingLock) {
            pendingToneCurve = lut
            pendingClearToneCurve = false
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                val ok = nativeUploadToneCurve(h, lut)
                if (ok) render(h, "uploadToneCurve")
            }
        }
    }

    /** Upload 4 per-channel curve LUTs from 8 (x,y) control points each. Posts to GL. */
    fun uploadCurveLuts(master: FloatArray, r: FloatArray, g: FloatArray, b: FloatArray) {
        val luts = arrayOf(master.copyOf(), r.copyOf(), g.copyOf(), b.copyOf())
        synchronized(pendingLock) { pendingCurveLuts = luts }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeUploadCurveLuts(h, luts[0], luts[1], luts[2], luts[3])
                render(h, "uploadCurveLuts")
            }
        }
    }

    /** Clear the Tone Curve LUT (curve reset to identity). Posts to GL. */
    fun clearToneCurve() {
        synchronized(pendingLock) {
            pendingToneCurve = null
            pendingClearToneCurve = true
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeClearToneCurve(h)
                render(h, "clearToneCurve")
            }
        }
    }

    /**
     * Upload vintage mist overlay. [bytes] is gray (`w*h`), RGB (`w*h*3`)
     * or RGBA (`w*h*4`), row-major. Posts to the GL thread.
     */
    fun uploadVintageMist(bytes: ByteArray, width: Int, height: Int) {
        synchronized(pendingLock) {
            pendingVintageMist = bytes
            pendingVintageMistW = width
            pendingVintageMistH = height
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                val ok = nativeUploadVintageMist(h, bytes, width, height)
                if (ok) render(h, "uploadVintageMist")
            }
        }
    }

    /**
     * Upload vintage film/texture overlay. Same packing as [uploadVintageMist].
     */
    fun uploadVintageFilm(bytes: ByteArray, width: Int, height: Int) {
        synchronized(pendingLock) {
            pendingVintageFilm = bytes
            pendingVintageFilmW = width
            pendingVintageFilmH = height
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                val ok = nativeUploadVintageFilm(h, bytes, width, height)
                if (ok) render(h, "uploadVintageFilm")
            }
        }
    }

    /**
     * Upload the U2Net subject mask. [grayBytes] is a row-major
     * `width * height` byte array where each byte encodes the subject
     * probability (`255` = certain subject, `0` = certain background).
     * Posts to the GL thread. Vignette + Gradient tabs honour this mask
     * when their SegmentTarget is Subject / Background.
     */
    fun uploadSubjectMask(grayBytes: ByteArray, width: Int, height: Int) {
        synchronized(pendingLock) {
            pendingSubjectMaskBytes = grayBytes
            pendingSubjectMaskAhb = null
            pendingSubjectMaskW = width
            pendingSubjectMaskH = height
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                val ok = nativeUploadSubjectMask(h, grayBytes, width, height)
                if (ok) render(h, "uploadSubjectMask")
            } else {
                Log.d(TAG, "uploadSubjectMask: renderer not ready; sticky replay retained")
            }
        }
    }

    /** Zero-copy AHB overload for subject mask. */
    fun uploadSubjectMask(ahb: HardwareBuffer) {
        synchronized(pendingLock) {
            pendingSubjectMaskAhb = ahb
            pendingSubjectMaskBytes = null
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L && eglDisplay != 0L) {
                // Use the zero-copy cache-aware loader
                val texId = ahbMaskLoader.importAhbMask(ahb, eglDisplay, 0)
                if (texId != 0) {
                    nativeBindTextureToMask(h, -1, texId)
                    render(h, "uploadSubjectMaskAhb(cached)")
                } else {
                    // Fallback to the original legacy path if cache import fails
                    nativeUploadSubjectMaskAhb(h, ahb, -1)
                    render(h, "uploadSubjectMaskAhb(fallback)")
                }
            }
        }
    }

    /**
     * Upload a 320×320 GL_R8 byte mask carrying max(skyMask, terrainMask)
     * from the Cityscapes 4-class segmentation. The bokeh shader path scales
     * its background gate by (1 - 0.75 * mask) where this is non-zero, so
     * sky and ground receive 25% of the user's bokeh strength instead of
     * the full background pull. Posts to the GL thread; safe to call any
     * time after the surface is ready. Pass an empty array via
     * `nativeClearSubjectMask`-equivalent (not yet exposed) when the
     * masks are no longer relevant.
     */
    fun uploadBokehAttenuation(grayBytes: ByteArray, width: Int, height: Int) {
        synchronized(pendingLock) {
            pendingBokehAttenAhb = null
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                val ok = nativeUploadBokehAttenuation(h, grayBytes, width, height)
                if (ok) render(h, "uploadBokehAttenuation")
            }
        }
    }

    /** Zero-copy AHB overload for bokeh attenuation. */
    fun uploadBokehAttenuation(ahb: HardwareBuffer) {
        synchronized(pendingLock) {
            pendingBokehAttenAhb = ahb
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L && eglDisplay != 0L) {
                val texId = ahbMaskLoader.importAhbMask(ahb, eglDisplay, 0)
                if (texId != 0) {
                    nativeBindTextureToMask(h, -2, texId)
                    render(h, "uploadBokehAttenuationAhb(cached)")
                } else {
                    nativeUploadBokehAttenuationAhb(h, ahb, -1)
                    render(h, "uploadBokehAttenuationAhb(fallback)")
                }
            }
        }
    }

    /**
     * Upload relative depth (MASK_SIZE gray8) for depth→CoC bokeh.
     * Packed into unit-10 RG8 .g beside attenuation. [focusDepth01] is the
     * subject-median depth (focus plane); subject pixels stay sharp via mask.
     */
    fun uploadDepthMap(grayBytes: ByteArray, width: Int, height: Int, focusDepth01: Float) {
        synchronized(pendingLock) {
            pendingDepthMapAhb = null
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                val ok = nativeUploadDepthMap(h, grayBytes, width, height, focusDepth01)
                if (ok) render(h, "uploadDepthMap")
            }
        }
    }

    /** Zero-copy AHB overload for depth map. */
    fun uploadDepthMap(ahb: HardwareBuffer, focusDepth01: Float) {
        synchronized(pendingLock) {
            pendingDepthMapAhb = ahb
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L && eglDisplay != 0L) {
                val texId = ahbMaskLoader.importAhbMask(ahb, eglDisplay, 0)
                if (texId != 0) {
                    // Note: Depth map is special as it's packed with attenuation in unit 10.
                    // For now, use the legacy path until we grow bindTextureToMask support for it.
                    nativeUploadDepthMapAhb(h, ahb, focusDepth01, -1)
                    render(h, "uploadDepthMapAhb")
                } else {
                    nativeUploadDepthMapAhb(h, ahb, focusDepth01, -1)
                    render(h, "uploadDepthMapAhb")
                }
            }
        }
    }

    fun clearDepthMap() {
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeClearDepthMap(h)
                render(h, "clearDepthMap")
            }
        }
    }

    /**
     * Tell the renderer the UV rectangle inside the 320×320 subject mask
     * where the actual source image lives (the rest is letterbox
     * padding from the U2Net input prep). Defaults to (0,0,1,1) — full
     * mask — when nothing is set, so legacy callers keep working.
     */
    fun setSubjectMaskInnerRect(u0: Float, v0: Float, u1: Float, v1: Float) {
        synchronized(pendingLock) { pendingSubjectRect = floatArrayOf(u0, v0, u1, v1) }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeSetSubjectMaskInnerRect(h, u0, v0, u1, v1)
                render(h, "setSubjectMaskInnerRect")
            }
        }
    }

    /**
     * Upload the brush-painted Mask tab mask. `grayBytes` is row-major
     * `width*height` bytes, each byte the alpha at that texel (255 =
     * fully painted, 0 = unpainted). Bound to texture unit 3 as GL_R8.
     */
    fun uploadBrushMask(layer: Int, grayBytes: ByteArray, width: Int, height: Int) {
        if (layer < 0 || layer >= MASK_LAYERS) return
        synchronized(pendingLock) {
            pendingBrushMaskBytes[layer] = grayBytes
            pendingBrushMaskAhb[layer] = null
            pendingBrushMaskW[layer] = width
            pendingBrushMaskH[layer] = height
            pendingClearBrushMask[layer] = false
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                val ok = nativeUploadBrushMask(h, layer, grayBytes, width, height)
                if (ok) render(h, "uploadBrushMask")
            }
        }
    }

    /** Zero-copy AHB overload for brush mask. */
    fun uploadBrushMask(layer: Int, ahb: HardwareBuffer) {
        if (layer < 0 || layer >= MASK_LAYERS) return
        synchronized(pendingLock) {
            pendingBrushMaskAhb[layer] = ahb
            pendingBrushMaskBytes[layer] = null
            pendingClearBrushMask[layer] = false
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L && eglDisplay != 0L) {
                val texId = ahbMaskLoader.importAhbMask(ahb, eglDisplay, 0)
                if (texId != 0) {
                    nativeBindTextureToMask(h, layer, texId)
                    render(h, "uploadBrushMaskAhb(cached)")
                } else {
                    nativeUploadBrushMaskAhb(h, layer, ahb, -1)
                    render(h, "uploadBrushMaskAhb(fallback)")
                }
            }
        }
    }

    /**
     * Upload the Sobel edge mask (same 320×320 grid as the subject mask).
     * Used by the shader to snap soft U2Net silhouettes to true image
     * gradients (hair / feather / fur boundaries).
     */
    fun uploadSobelEdgeMask(grayBytes: ByteArray, width: Int, height: Int) {
        synchronized(pendingLock) {
            pendingSobelEdgeBytes = grayBytes
            pendingSobelEdgeAhb = null
            pendingSobelEdgeW = width
            pendingSobelEdgeH = height
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                val ok = nativeUploadSobelEdgeMask(h, grayBytes, width, height)
                if (ok) render(h, "uploadSobelEdgeMask")
            }
        }
    }

    /** Zero-copy AHB overload for Sobel edge mask. */
    fun uploadSobelEdgeMask(ahb: HardwareBuffer) {
        synchronized(pendingLock) {
            pendingSobelEdgeAhb = ahb
            pendingSobelEdgeBytes = null
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L && eglDisplay != 0L) {
                val texId = ahbMaskLoader.importAhbMask(ahb, eglDisplay, 0)
                if (texId != 0) {
                    nativeBindTextureToMask(h, -3, texId)
                    render(h, "uploadSobelEdgeMaskAhb(cached)")
                } else {
                    nativeUploadSobelEdgeMaskAhb(h, ahb, -1)
                    render(h, "uploadSobelEdgeMaskAhb(fallback)")
                }
            }
        }
    }

    /** Tune the edge-snap effect; pass strength=0 to disable. */
    fun setEdgeSnap(strength: Float, threshold: Float) {
        synchronized(pendingLock) { pendingEdgeSnap = floatArrayOf(strength, threshold) }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeSetEdgeSnap(h, strength, threshold)
                render(h, "setEdgeSnap")
            }
        }
    }

    /** Discard the painted brush mask for [layer]. */
    fun clearBrushMask(layer: Int) {
        if (layer < 0 || layer >= MASK_LAYERS) return
        synchronized(pendingLock) {
            pendingBrushMaskBytes[layer] = null
            pendingClearBrushMask[layer] = true
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeClearBrushMask(h, layer)
                render(h, "clearBrushMask")
            }
        }
    }

    /** Free the subject mask texture. */
    fun clearSubjectMask() {
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeClearSubjectMask(h)
                render(h, "clearSubjectMask")
            }
        }
    }

    /** Discard the currently-bound LUT. */
    fun clearLut3d() {
        synchronized(pendingLock) {
            pendingLutPath = null
            pendingClearLut = true
        }
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeClearLut3d(h)
                render(h, "clearLut3d")
            }
        }
    }

    /**
     * Render the current shader output into [dst]. Must run on the GL
     * thread and waits for completion via a CountDownLatch — the caller
     * (Apply path) needs to read the AHB right after. dst must be the same
     * size + RGBA_F16 format as the source AHB.
     */
    fun snapshotGraded(dst: HardwareBuffer): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        // post() returns false after shutdown() quit the render thread — the
        // runnable would never run and latch.await() would block this thread
        // forever (ANR when called from Main during editor teardown).
        val posted = renderHandler.post {
            val h = rendererHandle
            if (h != 0L) ok = nativeSnapshotGraded(h, dst)
            latch.countDown()
        }
        if (!posted) return false
        latch.await()
        return ok
    }

    /**
     * Compute a 256-bucket BT.601 luma histogram of the current graded frame
     * by rendering it into a small offscreen [side]×[side] buffer on the GL
     * thread. Returns 256 ints, or null if the renderer isn't ready. Used to
     * drive the Tone Curves graph backdrop so it reflects the live edit.
     */
    fun histogramGraded(side: Int = 256): IntArray? {
        if (rendererHandle == 0L) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: IntArray? = null
        val posted = runCatching {
            renderHandler.post {
                val h = rendererHandle
                if (h != 0L) result = nativeHistogramGraded(h, side)
                latch.countDown()
            }
        }.getOrDefault(false) == true
        if (!posted) return null
        latch.await()
        return result
    }

    /**
     * Capture the live graded frame into a downscaled [android.graphics.Bitmap]
     * (long side ≤ [maxLongSide]) at the [srcW]×[srcH] aspect. Used by the RAW
     * Export page so its preview matches the editor + saved file rather than
     * the ungraded Stage A thumbnail. Returns null if the renderer isn't ready.
     */
    fun snapshotGradedToBitmap(
        maxLongSide: Int,
        srcW: Int,
        srcH: Int,
    ): android.graphics.Bitmap? {
        if (srcW <= 0 || srcH <= 0) return null
        val scale = minOf(
            maxLongSide.toFloat() / srcW,
            maxLongSide.toFloat() / srcH,
            1f,
        )
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        // See snapshotGraded: guard against posting to a quit render thread,
        // which would leave latch.await() blocked forever (save/export tapped
        // as the editor tears down → deadlocked coroutine / ANR).
        val posted = renderHandler.post {
            val hh = rendererHandle
            if (hh != 0L) ok = nativeSnapshotGradedToBitmap(hh, bmp)
            latch.countDown()
        }
        if (!posted) { bmp.recycle(); return null }
        latch.await()
        return if (ok) bmp else { bmp.recycle(); null }
    }

    private fun bootRenderer() {
        val surface = pendingSurfaceHolder?.surface ?: run {
            Log.w(TAG, "bootRenderer: abort — no surface (holder=${pendingSurfaceHolder})")
            return
        }
        val ahb = pendingAhb ?: run {
            Log.w(TAG, "bootRenderer: abort — pendingAhb is null")
            return
        }
        if (!ahb.isUsable()) {
            Log.w(TAG, "bootRenderer: abort — pendingAhb is closed (disposed while reboot was queued)")
            return
        }
        if (rendererHandle != 0L) {
            Log.w(TAG, "bootRenderer: abort — handle already set ($rendererHandle)")
            return
        }
        val h = nativeInitRenderer(surface, ahb)
        if (h == 0L) {
            Log.e(TAG, "bootRenderer: nativeInitRenderer returned 0")
            return
        }
        rendererHandle = h
        eglDisplay = nativeGetEglDisplay(h)
        // Replay any sticky state that was set BEFORE bootRenderer ran
        // (typical after rotation: Compose calls updateUniforms /
        // uploadLut3d / etc. against a still-booting renderer — without
        // this replay the new EGL context paints with identity uniforms
        // and no LUT / no segmentation mask).
        if (hasParams) {
            synchronized(paramsLock) {
                System.arraycopy(scratchFloatArray, 0, renderFloatArray, 0, renderFloatArray.size)
            }
            nativeUpdateUniforms(h, renderFloatArray)
        }
        // The whole replay reads the sticky pending* fields under pendingLock so
        // a concurrent main-thread setter can't be observed torn (e.g. new mask
        // bytes with stale W/H — the JNI length guard would silently skip the
        // upload and the mask would vanish after rotation). The lock is held
        // across the native uploads; that's fine — this is the rare reboot path
        // and the only contenders are per-setter writes on the main thread.
        var maskReplayed = false
        synchronized(pendingLock) {
            val smBytes = pendingSubjectMaskBytes
            val smAhb = pendingSubjectMaskAhb
            when {
                smAhb != null -> nativeUploadSubjectMaskAhb(h, smAhb, -1)
                smBytes != null -> nativeUploadSubjectMask(h, smBytes, pendingSubjectMaskW, pendingSubjectMaskH)
            }
            pendingSubjectRect?.let {
                nativeSetSubjectMaskInnerRect(h, it[0], it[1], it[2], it[3])
            }
            val seBytes = pendingSobelEdgeBytes
            val seAhb = pendingSobelEdgeAhb
            when {
                seAhb != null -> nativeUploadSobelEdgeMaskAhb(h, seAhb, -1)
                seBytes != null -> nativeUploadSobelEdgeMask(h, seBytes, pendingSobelEdgeW, pendingSobelEdgeH)
            }
            pendingEdgeSnap?.let { nativeSetEdgeSnap(h, it[0], it[1]) }
            // Replay each mask layer's last-known state independently.
            for (layer in 0 until MASK_LAYERS) {
                val bytes = pendingBrushMaskBytes[layer]
                val ahb = pendingBrushMaskAhb[layer]
                when {
                    pendingClearBrushMask[layer] -> nativeClearBrushMask(h, layer)
                    ahb != null -> nativeUploadBrushMaskAhb(h, layer, ahb, -1)
                    bytes != null ->
                        nativeUploadBrushMask(h, layer, bytes,
                                              pendingBrushMaskW[layer], pendingBrushMaskH[layer])
                }
            }
            pendingBokehAttenAhb?.let { nativeUploadBokehAttenuationAhb(h, it, -1) }
            pendingDepthMapAhb?.let { nativeUploadDepthMapAhb(h, it, pendingDepthMapFocus, -1) }
            when {
                pendingClearLut -> nativeClearLut3d(h)
                pendingLutPath != null -> nativeUploadLut3d(h, pendingLutPath!!)
            }
            when {
                pendingClearToneCurve -> nativeClearToneCurve(h)
                pendingToneCurve != null -> nativeUploadToneCurve(h, pendingToneCurve!!)
            }
            pendingCurveLuts?.let { luts ->
                nativeUploadCurveLuts(h, luts[0], luts[1], luts[2], luts[3])
            }
            pendingVintageMist?.let {
                nativeUploadVintageMist(h, it, pendingVintageMistW, pendingVintageMistH)
            }
            pendingVintageFilm?.let {
                nativeUploadVintageFilm(h, it, pendingVintageFilmW, pendingVintageFilmH)
            }
            // Replay preview pan/zoom (skip when identity to save a call).
            if (pendingViewScale != 1f || pendingViewOffsetX != 0f || pendingViewOffsetY != 0f) {
                nativeSetViewTransform(h, pendingViewScale, pendingViewOffsetX, pendingViewOffsetY)
            }
            if (pendingShowMaskOverlay) nativeSetShowMaskOverlay(h, true)
            if (pendingMaskOverlayLayer >= 0) nativeSetMaskOverlayLayer(h, pendingMaskOverlayLayer)
            maskReplayed = smBytes != null || smAhb != null
        }
        render(h, "bootRenderer")
        Log.i(TAG, "bootRenderer: ok handle=$h maskReplayed=$maskReplayed lut=${pendingLutPath != null}")
        post { onEglBooted?.invoke() }
        // Present-retry: rendering is on-demand, and the very first frame after a
        // boot can be swapped before the SurfaceView's buffer is actually being
        // composited — the swap is silently dropped and, with nothing else
        // triggering a redraw, the ZOrderOnTop surface stays transparent and the
        // canvas shows the Compose background (a permanent blank until a slider
        // moves). Re-post a few renders as the surface settles so the bound AHB
        // is guaranteed to reach the screen. Cheap: each is one nativeRenderFrame,
        // and they no-op harmlessly once content is already presented.
        firstFrameSignalled = false
        for (delayMs in intArrayOf(32, 100, 250)) {
            renderHandler.postDelayed({
                val hh = rendererHandle
                if (hh != 0L && pendingAhb != null) {
                    render(hh, "boot-retry")
                    if (!firstFrameSignalled) {
                        firstFrameSignalled = true
                        onFirstFrameRendered?.invoke()
                    }
                }
            }, delayMs.toLong())
        }
    }

    // ── Unified debounced reboot ─────────────────────────────────────────────
    // All boot triggers (surfaceCreated, surfaceChanged, AHB-ready) funnel
    // through scheduleReboot(). It cancels any previously queued boot and
    // replaces it with a fresh 120 ms delayed one, so rapid AHB deliveries
    // racing with surfaceChanged callbacks all collapse into a single boot
    // at the final settled surface dimensions.
    private var pendingRebootRunnable: Runnable? = null

    private fun scheduleReboot() {
        // Must be called on the render thread (all callers post to renderHandler).
        pendingRebootRunnable?.let { renderHandler.removeCallbacks(it) }
        bootPending = true
        val runnable = Runnable {
            pendingRebootRunnable = null
            val h = rendererHandle
            if (h != 0L) {
                nativeReleaseRenderer(h)
                rendererHandle = 0L
            }
            bootRenderer()
            bootPending = false
        }
        pendingRebootRunnable = runnable
        renderHandler.postDelayed(runnable, 120)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        pendingSurfaceHolder = holder
        // Frame-rate hint (API 30+): ask the compositor for the panel's highest
        // refresh rate while the editor preview is on screen — a pure hint, the
        // user's battery-saver / OEM policies still win.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val max = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
                if (max > 0f) {
                    holder.surface.setFrameRate(
                        max, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    )
                    Log.i(TAG, "surface frame-rate hint: ${max}Hz")
                }
            }
        }
        renderHandler.post { scheduleReboot() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        pendingSurfaceHolder = holder
        renderHandler.post {
            // If the renderer is already running, onSizeChanged handles the resize
            // via a lightweight re-render — no need to tear down and recreate EGL.
            // Only reboot when the renderer hasn't started yet.
            if (rendererHandle == 0L) scheduleReboot()
            else render(rendererHandle, "surfaceChanged")
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                // ON_PAUSE often runs AFTER this OEM tears down the surface, so
                // Compose's pause snapshot misses. Read back while EGL is current.
                val ahb = pendingAhb
                if (ahb != null && ahb.isUsable()) {
                    val sw = ahb.width
                    val sh = ahb.height
                    if (sw > 0 && sh > 0) {
                        val scale = minOf(1280f / sw, 1280f / sh, 1f)
                        val bw = (sw * scale).toInt().coerceAtLeast(1)
                        val bh = (sh * scale).toInt().coerceAtLeast(1)
                        val bmp = android.graphics.Bitmap.createBitmap(
                            bw, bh, android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        val ok = nativeSnapshotGradedToBitmap(h, bmp)
                        if (ok) {
                            Log.i(TAG, "surfaceDestroyed snapshot ${bw}×${bh} before EGL release")
                            post { onPreEglReleaseSnapshot?.invoke(bmp) }
                        } else {
                            bmp.recycle()
                        }
                    }
                }
                nativeReleaseRenderer(h)
                rendererHandle = 0L
            }
            if (pendingAhb != null) {
                Log.d(TAG, "surfaceDestroyed: retaining sticky state for reboot replay")
            }
        }
        pendingSurfaceHolder = null
    }

    fun shutdown() {
        renderHandler.post {
            val h = rendererHandle
            if (h != 0L) {
                nativeReleaseRenderer(h)
                rendererHandle = 0L
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                runCatching { hintSession?.close() }
                hintSession = null
            }
            ahbMaskLoader.close()
            renderThread.quitSafely()
        }
    }

    private external fun nativeInitRenderer(surface: Any, ahb: HardwareBuffer): Long
    private external fun nativeUpdateAhb(handle: Long, ahb: HardwareBuffer, fenceFd: Int)
    private external fun nativeUpdateUniforms(handle: Long, params: FloatArray)
    private external fun nativeUploadLut3d(handle: Long, cubePath: String): Boolean
    private external fun nativeClearLut3d(handle: Long)
    private external fun nativeUploadSubjectMask(handle: Long, gray: ByteArray, width: Int, height: Int): Boolean
    private external fun nativeUploadSubjectMaskAhb(handle: Long, ahb: HardwareBuffer, fenceFd: Int): Boolean
    private external fun nativeUploadBokehAttenuation(handle: Long, gray: ByteArray, width: Int, height: Int): Boolean
    private external fun nativeUploadBokehAttenuationAhb(handle: Long, ahb: HardwareBuffer, fenceFd: Int): Boolean
    private external fun nativeUploadDepthMap(handle: Long, gray: ByteArray, width: Int, height: Int, focusDepth01: Float): Boolean
    private external fun nativeUploadDepthMapAhb(handle: Long, ahb: HardwareBuffer, focusDepth01: Float, fenceFd: Int): Boolean
    private external fun nativeClearDepthMap(handle: Long)
    private external fun nativeClearSubjectMask(handle: Long)
    private external fun nativeSetSubjectMaskInnerRect(handle: Long, u0: Float, v0: Float, u1: Float, v1: Float)
    private external fun nativeSetViewTransform(handle: Long, scale: Float, offsetX: Float, offsetY: Float)
    private external fun nativeUploadBrushMask(handle: Long, layer: Int, gray: ByteArray, width: Int, height: Int): Boolean
    private external fun nativeUploadBrushMaskAhb(handle: Long, layer: Int, ahb: HardwareBuffer, fenceFd: Int): Boolean
    private external fun nativeClearBrushMask(handle: Long, layer: Int)
    private external fun nativeUploadSobelEdgeMask(handle: Long, gray: ByteArray, width: Int, height: Int): Boolean
    private external fun nativeUploadSobelEdgeMaskAhb(handle: Long, ahb: HardwareBuffer, fenceFd: Int): Boolean
    private external fun nativeSetEdgeSnap(handle: Long, strength: Float, threshold: Float)
    private external fun nativeUploadToneCurve(handle: Long, lut: ByteArray): Boolean
    private external fun nativeClearToneCurve(handle: Long)
    private external fun nativeUploadCurveLuts(handle: Long, master: FloatArray, r: FloatArray, g: FloatArray, b: FloatArray)
    private external fun nativeUploadVintageMist(handle: Long, bytes: ByteArray, width: Int, height: Int): Boolean
    private external fun nativeUploadVintageFilm(handle: Long, bytes: ByteArray, width: Int, height: Int): Boolean
    private external fun nativeSnapshotGraded(handle: Long, dst: HardwareBuffer): Boolean
    private external fun nativeHistogramGraded(handle: Long, side: Int): IntArray?
    private external fun nativeSnapshotGradedToBitmap(handle: Long, bitmap: android.graphics.Bitmap): Boolean
    private external fun nativeSetShowMaskOverlay(handle: Long, show: Boolean)
    private external fun nativeSetMaskOverlayLayer(handle: Long, layer: Int)
    private external fun nativeRenderFrame(handle: Long): Boolean
    private external fun nativeReleaseRenderer(handle: Long)
    private external fun nativeGetEglDisplay(handle: Long): Long
    private external fun nativeBindTextureToMask(handle: Long, layer: Int, textureId: Int): Boolean

    companion object {
        private const val TAG = "RawV3.GlSurface"
        /** Number of brush-mask layers — must match GlesRenderer::kMaskLayers. */
        const val MASK_LAYERS = 4
    }
}
