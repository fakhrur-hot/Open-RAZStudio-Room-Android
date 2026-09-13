/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW v3 — Face-detection mask source.
 *
 *  Runs Qualcomm's "Lightweight-Face-Detection" ONNX model
 *  (assets/models/face_detection.onnx, ~3.5 MB) on the editor's neutral
 *  preview bitmap and produces a 320×320 float mask plane where pixels
 *  inside detected face ellipses are 1.0 and the rest are 0.0. The shape
 *  matches RawV3SegmentationMasks so the Mask tab can consume it without
 *  any extra resize.
 *
 *  ── Model contract ──
 *    input  "input"     : 1×1×480×640 grayscale, FP32, ImageNet-normalised
 *    output "heatmap"   : 1×1×60×80     stride-8 face-centre heatmap
 *    output "bbox"      : 1×4×60×80     per-cell (cx_off, cy_off, log_w, log_h)
 *    output "landmark"  : 1×10×60×80    5×(dx,dy) facial landmark offsets
 *
 *  Decoder uses the heatmap peaks → bbox regression → fill ellipse inside
 *  each detected face on the 320×320 mask. We DON'T currently use landmarks
 *  but the decoder is structured so a future "use landmarks to refine the
 *  contour" pass is a one-method extension.
 *
 *  ── Threading ──
 *  Inference + decode runs on [dispatcher] — a single daemon thread shared
 *  with U²Net via the standard JVM scheduler. Never blocks the UI.
 *
 *  ── Lifecycle ──
 *  OrtSession is lazy; first call costs ~200–800 ms on a modern phone.
 *  Subsequent calls reuse the session. Call [release] when the coordinator
 *  is closed to free native memory.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.OrtSessionGate
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class RawV3FaceDetector(private val context: Context) {

    private val modelAvailable: Boolean by lazy {
        runCatching { context.assets.open(MODEL_ASSET).close(); true }.getOrDefault(false)
    }
    val hasModel: Boolean get() = modelAvailable

    private val sessionLazy: Lazy<OrtSession?> = lazy {
        runCatching {
            if (!modelAvailable) return@runCatching null
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            env.createSession(bytes, buildSessionOptions())
                .also { Log.i(TAG, "OrtSession created (model=$MODEL_ASSET, ${bytes.size / 1024} KB)") }
        }.getOrElse { e -> Log.e(TAG, "Session init failed: ${e.message}"); null }
    }
    private val session: OrtSession? by sessionLazy

    private val gate = OrtSessionGate(TAG)

    // Single-thread daemon dispatcher. Inference is CPU-heavy; we don't
    // want it preempting the GL preview pipeline or the U²Net job. Both
    // segmentation processors share the JVM's scheduler.
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RawV3.FaceDet").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    private fun buildSessionOptions(): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        // Mirror the U²Net config: minimal thread count, no NNAPI (hangs on
        // some MediaTek), no QNN. CPU is fast enough at 480×640 input.
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)
        return opts
    }

    /**
     * Run face detection on [bitmap]. Returns a 320×320 alpha plane (row-
     * major, [0..1]) with elliptical fills inside each detected face. When
     * no faces are found, returns null.
     */
    suspend fun compute(bitmap: Bitmap): FloatArray? {
        if (gate.isReleased || !hasModel) return null
        return withContext(dispatcher) {
            gate.run {
                val maskSize = RawV3SegmentationMasks.MASK_SIZE
                // The model wants 480×640 (H=480, W=640) grayscale. Resize
                // the input bitmap once at the start, then read the
                // bounding boxes out in 480×640 coords and map them to
                // [0..1] image coords for the mask fill.
                val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_W, INPUT_H, true)
                try {
                    val faces  = runDetector(scaled)
                    Log.i(TAG, "compute: found ${faces.size} face(s)")
                    if (faces.isEmpty()) null
                    else fillFacesIntoMask(faces, maskSize)
                } finally {
                    scaled.recycle()
                }
            }
        }
    }

    /**
     * Release the OrtSession. Safe while [compute] is mid-infer — close is
     * deferred until OrtSession.run returns (see [OrtSessionGate]).
     */
    fun release() {
        gate.release {
            if (sessionLazy.isInitialized()) {
                runCatching { session?.close() }
                Log.d(TAG, "OrtSession released")
            }
        }
    }

    // ── Inference + decode ───────────────────────────────────────────────────

    /**
     * One detected face. Coordinates are in normalised [0..1] space relative
     * to the original bitmap (independent of the 480×640 model input size),
     * so the mask filler can paint without knowing the model resolution.
     */
    private data class FaceBox(
        val cx: Float, val cy: Float,
        val w: Float,  val h: Float,
        val score: Float,
    )

    private fun runDetector(scaled480x640: Bitmap): List<FaceBox> {
        val s = session ?: error("OrtSession unavailable")
        val env = OrtEnvironment.getEnvironment()
        val input = bitmapToGrayscaleNchw(scaled480x640, env)
        val output = s.run(mapOf(s.inputNames.first() to input))
        input.close()

        // Output ordering matches the model graph: heatmap, bbox, landmark.
        // Read by name (defensive — model could reorder in a future export).
        val outMap = HashMap<String, Any>()
        s.outputNames.forEachIndexed { i, name -> outMap[name] = output[i].value }
        val heatmap = outMap["heatmap"] as? Array<*>
            ?: error("missing 'heatmap' output")
        val bbox    = outMap["bbox"] as? Array<*>
            ?: error("missing 'bbox' output")
        val faces = decodeHeatmap(heatmap, bbox)
        output.close()
        return faces
    }

    /**
     * Convert ARGB_8888 bitmap → NCHW grayscale FP32 tensor with ImageNet
     * normalisation. Many face models train on ImageNet stats even when the
     * input is single-channel — Qualcomm's release follows this convention.
     */
    private fun bitmapToGrayscaleNchw(bitmap: Bitmap, env: OrtEnvironment): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        require(w == INPUT_W && h == INPUT_H) {
            "bitmap must be ${INPUT_W}x${INPUT_H}, got ${w}x${h}"
        }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(w * h)
        val mean = 0.485f
        val std  = 0.229f
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr  8) and 0xFF) / 255f
            val b = ( p         and 0xFF) / 255f
            // BT.601 luma — same as our preview / Stage A pipeline.
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            buf.put((luma - mean) / std)
        }
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1, 1, INPUT_H.toLong(), INPUT_W.toLong()))
    }

    /**
     * CenterNet-style decode. The heatmap has one value per stride-8 cell;
     * peaks (local maxima above [SCORE_THRESHOLD]) are face-centre candidates.
     * The bbox tensor encodes (cx_offset, cy_offset, log_w, log_h) per cell,
     * with offsets in stride-8 pixels and log-space dims in input pixels.
     */
    private fun decodeHeatmap(heatmap: Array<*>, bbox: Array<*>): List<FaceBox> {
        // Shape: [1][1][60][80]. Unwrap the outer dimensions.
        @Suppress("UNCHECKED_CAST")
        val hm = (heatmap[0] as Array<*>)[0] as Array<FloatArray>
        @Suppress("UNCHECKED_CAST")
        val bb = (bbox[0]    as Array<*>) as Array<Array<FloatArray>>  // [4][60][80]
        val gridH = hm.size            // 60
        val gridW = hm[0].size         // 80
        // Find local maxima (3×3 NMS) above the threshold.
        val candidates = mutableListOf<FaceBox>()
        for (y in 0 until gridH) {
            for (x in 0 until gridW) {
                val s = hm[y][x]
                if (s < SCORE_THRESHOLD) continue
                // 3×3 NMS — only keep cells that are >= every neighbour.
                var isPeak = true
                outer@ for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= gridH) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= gridW) continue
                        if (hm[ny][nx] > s) { isPeak = false; break@outer }
                    }
                }
                if (!isPeak) continue
                // Bbox regression — (cx_off, cy_off) in stride units, (log_w,
                // log_h) → exp() in input pixels. Some Qualcomm variants emit
                // (log w/h * stride); we exp and accept the result as input-
                // pixel sizes which works on the released model.
                val cxOff = bb[0][y][x]
                val cyOff = bb[1][y][x]
                val logW  = bb[2][y][x]
                val logH  = bb[3][y][x]
                val cxPx = (x + cxOff) * STRIDE
                val cyPx = (y + cyOff) * STRIDE
                val wPx  = exp(logW) * STRIDE
                val hPx  = exp(logH) * STRIDE
                // Convert to normalised image coords [0..1].
                candidates += FaceBox(
                    cx = cxPx / INPUT_W,
                    cy = cyPx / INPUT_H,
                    w  = wPx  / INPUT_W,
                    h  = hPx  / INPUT_H,
                    score = s,
                )
            }
        }
        // Sort by score descending and run a simple IoU NMS to drop overlapping
        // duplicates. Face detectors occasionally fire on both sides of an
        // unsharp eyebrow; this prevents two ellipses for one face.
        candidates.sortByDescending { it.score }
        val kept = mutableListOf<FaceBox>()
        for (cand in candidates) {
            var overlap = false
            for (k in kept) {
                if (iou(cand, k) > NMS_THRESHOLD) { overlap = true; break }
            }
            if (!overlap) kept += cand
            if (kept.size >= MAX_FACES) break
        }
        return kept
    }

    private fun iou(a: FaceBox, b: FaceBox): Float {
        val ax0 = a.cx - a.w / 2; val ax1 = a.cx + a.w / 2
        val ay0 = a.cy - a.h / 2; val ay1 = a.cy + a.h / 2
        val bx0 = b.cx - b.w / 2; val bx1 = b.cx + b.w / 2
        val by0 = b.cy - b.h / 2; val by1 = b.cy + b.h / 2
        val ix0 = max(ax0, bx0); val ix1 = min(ax1, bx1)
        val iy0 = max(ay0, by0); val iy1 = min(ay1, by1)
        val iw = max(0f, ix1 - ix0); val ih = max(0f, iy1 - iy0)
        val inter = iw * ih
        val areaA = max(0f, ax1 - ax0) * max(0f, ay1 - ay0)
        val areaB = max(0f, bx1 - bx0) * max(0f, by1 - by0)
        val union = areaA + areaB - inter
        return if (union > 1e-6f) inter / union else 0f
    }

    /**
     * Paint elliptical fills for each face into a [maskSize × maskSize] float
     * plane. The bbox covers face + a little neck/hair margin; an inscribed
     * ellipse hits "just the face" reasonably well across portrait shots.
     */
    private fun fillFacesIntoMask(faces: List<FaceBox>, maskSize: Int): FloatArray {
        val out = FloatArray(maskSize * maskSize)
        for (face in faces) {
            val cx = face.cx * maskSize
            val cy = face.cy * maskSize
            // Shrink the bbox slightly for the ellipse — face landmarks
            // typically fit within ~85% of the detection box.
            val rx = (face.w * maskSize) * 0.5f * 0.95f
            val ry = (face.h * maskSize) * 0.5f * 0.95f
            // Iterate the bbox region (clamped to mask bounds) and fill
            // where (dx/rx)² + (dy/ry)² ≤ 1.
            val x0 = (cx - rx).toInt().coerceAtLeast(0)
            val x1 = (cx + rx).roundToInt().coerceAtMost(maskSize - 1)
            val y0 = (cy - ry).toInt().coerceAtLeast(0)
            val y1 = (cy + ry).roundToInt().coerceAtMost(maskSize - 1)
            val invRx2 = if (rx > 0f) 1f / (rx * rx) else 0f
            val invRy2 = if (ry > 0f) 1f / (ry * ry) else 0f
            for (y in y0..y1) {
                val dy = y - cy
                val dy2 = dy * dy * invRy2
                val row = y * maskSize
                for (x in x0..x1) {
                    val dx = x - cx
                    val r = dx * dx * invRx2 + dy2
                    if (r <= 1f) {
                        // Soft edge: 1.0 inside r≤0.85, falls to 0 at r=1.0.
                        val v = if (r <= 0.85f) 1f else (1f - r) / 0.15f
                        if (v > out[row + x]) out[row + x] = v
                    }
                }
            }
        }
        return out
    }

    companion object {
        private const val TAG = "RawV3.FaceDet"
        private const val MODEL_ASSET = "models/Lightweight-Face-Detection.onnx"
        // Qualcomm's released model — fixed 480×640 input (H × W).
        private const val INPUT_W = 640
        private const val INPUT_H = 480
        private const val STRIDE  = 8f
        // Detector calibration. Group photos pushed us toward more permissive
        // values: the previous 0.50 threshold dropped ~70% of faces in a
        // 15-person group shot, and the previous 0.30 NMS suppressed real
        // neighbouring faces in dense crowds (the IoU between two adjacent
        // faces in a hijab/shoulder-to-shoulder line easily exceeds 0.3).
        //   threshold 0.30 — keeps small / partly-occluded heads
        //   nms       0.50 — only fires on genuinely-overlapping detections
        // Higher MAX_FACES so very large groups don't truncate.
        private const val SCORE_THRESHOLD = 0.30f
        private const val NMS_THRESHOLD   = 0.50f
        private const val MAX_FACES       = 64
    }
}
