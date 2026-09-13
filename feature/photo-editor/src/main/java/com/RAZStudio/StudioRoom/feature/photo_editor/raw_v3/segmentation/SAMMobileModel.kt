/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  MobileSAM (Segment Anything · Mobile) ONNX wrapper.
 *
 *  Refines a coarse U2Net saliency mask into a sharp-edged subject mask. Used
 *  as the "SAM" refinement stage of OfflineFusionSegmenter.
 *
 *  REQUIRED MODEL FILES (standard MobileSAM ONNX export — segment-anything
 *  `export_onnx_model.py` style). Drop both into `assets/models/`:
 *
 *    • mobile_sam_encoder.onnx
 *        input  : image  [1, 3, 1024, 1024]  float32  (SAM-normalised, padded)
 *        output : image_embeddings [1, 256, 64, 64]   float32
 *
 *    • mobile_sam_decoder.onnx   (the prompt decoder export)
 *        inputs : image_embeddings [1,256,64,64]
 *                 point_coords    [1, N, 2]   float32  (in 1024-resize space)
 *                 point_labels    [1, N]      float32  (0=bg pt,1=fg pt,2=box TL,3=box BR)
 *                 mask_input      [1,1,256,256] float32
 *                 has_mask_input  [1]         float32
 *                 orig_im_size    [2]         float32  (origH, origW)
 *        outputs: masks [1, M, origH, origW] float32 logits (>0 == foreground)
 *                 iou_predictions [1, M]
 *                 low_res_masks   [1, M, 256, 256]
 *
 *  When EITHER file is missing or the session fails to build, [load] throws and
 *  OfflineFusionSegmenter falls back cleanly to U2Net-only (no crash, honest
 *  logging). So this file is inert until the two models are present.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.segmentation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.OrtSessionGate
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

internal class SAMMobileModel private constructor(
    private val encoder: OrtSession,
    private val decoder: OrtSession,
) : AutoCloseable {

    private val gate = OrtSessionGate(TAG)

    /**
     * Refine [coarseMask] (U2Net binary mask, any size) into a sharp subject
     * mask using [preview] as the image. Returns a CV_32FC1 [0,1] mask sized
     * [width]×[height] (matching the fusion stage's expectations). On any
     * inference failure, returns the coarse mask converted to that format so
     * the downstream guided-filter fusion still has valid U2Net data.
     */
    suspend fun refineOffline(
        preview: Bitmap,
        coarseMask: Mat,
        width: Int,
        height: Int,
    ): Mat {
        if (gate.isReleased) return coarseToFloatMat(coarseMask, width, height)
        return gate.run {
            try {
                val env = OrtEnvironment.getEnvironment()
                val pw = preview.width
                val ph = preview.height
                val longest = max(pw, ph)
                val scale = TARGET_LENGTH.toFloat() / longest      // ResizeLongestSide
                val newW = (pw * scale).roundToInt().coerceAtMost(TARGET_LENGTH)
                val newH = (ph * scale).roundToInt().coerceAtMost(TARGET_LENGTH)

                // ── Encoder ──────────────────────────────────────────────────────
                val inputTensor = buildEncoderInput(env, preview, newW, newH)
                val embedding: OnnxTensor = try {
                    val out = encoder.run(mapOf(encoder.inputNames.first() to inputTensor))
                    val embBuf = (out[0] as OnnxTensor).floatBuffer
                    // Copy into a fresh tensor so the encoder output can be closed.
                    val emb = FloatArray(embBuf.remaining()).also { embBuf.get(it) }
                    out.close()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 256, 64, 64))
                } finally {
                    inputTensor.close()
                }

                // ── Prompt: bounding box of the coarse U2Net mask ────────────────
                val boxOrNull = coarseMaskBoxInPreview(coarseMask, pw, ph)
                if (boxOrNull == null) {
                    embedding.close()
                    Log.i(TAG, "coarse mask empty — returning coarse as refined")
                    return@run coarseToFloatMat(coarseMask, width, height)
                }
                val box = boxOrNull
                // Box corners → 1024-resize space (apply_coords = * scale).
                val coords = floatArrayOf(
                    box[0] * scale, box[1] * scale,   // top-left  (label 2)
                    box[2] * scale, box[3] * scale,   // bot-right (label 3)
                )
                val pointCoords = OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, 2, 2))
                val pointLabels = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(2f, 3f)), longArrayOf(1, 2),
                )
                val maskInput = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256),
                )
                val hasMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1))
                val origSize = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(ph.toFloat(), pw.toFloat())), longArrayOf(2),
                )

                val decoderInputs = mapOf(
                    "image_embeddings" to embedding,
                    "point_coords" to pointCoords,
                    "point_labels" to pointLabels,
                    "mask_input" to maskInput,
                    "has_mask_input" to hasMask,
                    "orig_im_size" to origSize,
                )
                val refined = try {
                    val out = decoder.run(decoderInputs)
                    val mat = decoderOutputToMat(out, ph, pw, width, height)
                    out.close()
                    mat
                } finally {
                    embedding.close(); pointCoords.close(); pointLabels.close()
                    maskInput.close(); hasMask.close(); origSize.close()
                }
                Log.i(TAG, "SAM refine ok → ${width}x$height")
                refined
            } catch (e: Throwable) {
                Log.w(TAG, "refineOffline failed (${e.message}) — using coarse mask")
                coarseToFloatMat(coarseMask, width, height)
            }
        } ?: coarseToFloatMat(coarseMask, width, height)
    }

    /**
     * Prompted segmentation for the Heal tool's "Detect objects" snapping
     * (Lightroom behaviour: a rough scribble snaps to the object under it).
     *
     * [fgPoints] are foreground point prompts and [box] an optional
     * (x0,y0,x1,y1) box prompt, BOTH in [preview] pixel coordinates. Returns a
     * CV_32FC1 {0,1} mask at preview size, or null on failure. Unlike
     * [refineOffline] there is no coarse mask — the user's stroke IS the prompt.
     */
    suspend fun segmentPrompted(
        preview: Bitmap,
        fgPoints: List<Pair<Float, Float>>,
        box: FloatArray?,
    ): Mat? {
        if (fgPoints.isEmpty() && box == null) return null
        if (gate.isReleased) return null
        return gate.run {
            try {
                val env = OrtEnvironment.getEnvironment()
                val pw = preview.width
                val ph = preview.height
                val scale = TARGET_LENGTH.toFloat() / max(pw, ph)
                val newW = (pw * scale).roundToInt().coerceAtMost(TARGET_LENGTH)
                val newH = (ph * scale).roundToInt().coerceAtMost(TARGET_LENGTH)

                val inputTensor = buildEncoderInput(env, preview, newW, newH)
                val embedding: OnnxTensor = try {
                    val out = encoder.run(mapOf(encoder.inputNames.first() to inputTensor))
                    val embBuf = (out[0] as OnnxTensor).floatBuffer
                    val emb = FloatArray(embBuf.remaining()).also { embBuf.get(it) }
                    out.close()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 256, 64, 64))
                } finally {
                    inputTensor.close()
                }

                // Prompts → 1024-resize space. Points first (label 1), then the box
                // corners (labels 2, 3) — the standard SAM prompt encoding.
                val n = fgPoints.size + (if (box != null) 2 else 0)
                val coords = FloatArray(n * 2)
                val labels = FloatArray(n)
                fgPoints.forEachIndexed { i, (x, y) ->
                    coords[2 * i] = x * scale; coords[2 * i + 1] = y * scale
                    labels[i] = 1f
                }
                if (box != null) {
                    val b = fgPoints.size
                    coords[2 * b] = box[0] * scale;     coords[2 * b + 1] = box[1] * scale
                    coords[2 * b + 2] = box[2] * scale; coords[2 * b + 3] = box[3] * scale
                    labels[b] = 2f; labels[b + 1] = 3f
                }
                val pointCoords = OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n.toLong(), 2))
                val pointLabels = OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n.toLong()))
                val maskInput = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256),
                )
                val hasMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1))
                val origSize = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(ph.toFloat(), pw.toFloat())), longArrayOf(2),
                )
                try {
                    val out = decoder.run(
                        mapOf(
                            "image_embeddings" to embedding,
                            "point_coords" to pointCoords,
                            "point_labels" to pointLabels,
                            "mask_input" to maskInput,
                            "has_mask_input" to hasMask,
                            "orig_im_size" to origSize,
                        )
                    )
                    val mat = decoderOutputToMat(out, ph, pw, pw, ph)
                    out.close()
                    Log.i(TAG, "SAM prompted segment ok (${fgPoints.size} pts, box=${box != null})")
                    mat
                } finally {
                    embedding.close(); pointCoords.close(); pointLabels.close()
                    maskInput.close(); hasMask.close(); origSize.close()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "segmentPrompted failed: ${e.message}")
                null
            }
        }
    }

    override fun close() {
        gate.release {
            runCatching { encoder.close() }
            runCatching { decoder.close() }
        }
    }

    // ── Encoder input: SAM-normalised, padded [1,3,1024,1024] ───────────────
    private fun buildEncoderInput(
        env: OrtEnvironment, preview: Bitmap, newW: Int, newH: Int,
    ): OnnxTensor {
        val scaled = Bitmap.createScaledBitmap(preview, newW, newH, true)
        val px = IntArray(newW * newH)
        scaled.getPixels(px, 0, newW, 0, 0, newW, newH)
        scaled.recycle()

        // NCHW, zero-padded to 1024×1024. Plane stride = 1024*1024.
        val plane = TARGET_LENGTH * TARGET_LENGTH
        val data = FloatArray(3 * plane)   // zero = pad
        for (y in 0 until newH) {
            val rowBase = y * TARGET_LENGTH
            for (x in 0 until newW) {
                val p = px[y * newW + x]
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                val idx = rowBase + x
                data[idx]             = (r - MEAN[0]) / STD[0]
                data[plane + idx]     = (g - MEAN[1]) / STD[1]
                data[2 * plane + idx] = (b - MEAN[2]) / STD[2]
            }
        }
        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data), longArrayOf(1, 3, TARGET_LENGTH.toLong(), TARGET_LENGTH.toLong()),
        )
    }

    // ── Bounding box (x0,y0,x1,y1) of coarse mask, in PREVIEW px ─────────────
    private fun coarseMaskBoxInPreview(coarseMask: Mat, pw: Int, ph: Int): FloatArray? {
        val bin = Mat()
        when (coarseMask.type()) {
            CvType.CV_8UC1 -> Imgproc.threshold(coarseMask, bin, 127.0, 255.0, Imgproc.THRESH_BINARY)
            else -> {
                val tmp = Mat()
                coarseMask.convertTo(tmp, CvType.CV_8UC1, 255.0)
                Imgproc.threshold(tmp, bin, 127.0, 255.0, Imgproc.THRESH_BINARY)
                tmp.release()
            }
        }
        val pts = Mat()
        Core.findNonZero(bin, pts)
        val box = if (pts.empty()) null else {
            val r = Imgproc.boundingRect(pts)
            val sx = pw.toFloat() / coarseMask.cols()
            val sy = ph.toFloat() / coarseMask.rows()
            floatArrayOf(
                r.x * sx, r.y * sy,
                (r.x + r.width) * sx, (r.y + r.height) * sy,
            )
        }
        pts.release(); bin.release()
        return box
    }

    // ── Decoder masks → CV_32FC1 [0,1] at width×height ──────────────────────
    private fun decoderOutputToMat(
        out: OrtSession.Result, origH: Int, origW: Int, width: Int, height: Int,
    ): Mat {
        // "masks" [1, M, origH, origW] logits; pick the channel with best IoU.
        @Suppress("UNCHECKED_CAST")
        val masks = out.get("masks").get().value as Array<Array<Array<FloatArray>>>
        val m = masks[0]
        val best = run {
            val iou = runCatching {
                @Suppress("UNCHECKED_CAST")
                (out.get("iou_predictions").get().value as Array<FloatArray>)[0]
            }.getOrNull()
            if (iou != null && iou.size == m.size) iou.indices.maxByOrNull { iou[it] } ?: 0 else 0
        }
        val plane = m[best]                      // [origH][origW] logits
        val mat = Mat(origH, origW, CvType.CV_32FC1)
        val row = FloatArray(origW)
        for (y in 0 until origH) {
            val src = plane[y]
            for (x in 0 until origW) row[x] = if (src[x] > 0f) 1f else 0f  // logit>0 == fg
            mat.put(y, 0, row)
        }
        if (origW != width || origH != height) {
            val resized = Mat()
            Imgproc.resize(mat, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            mat.release()
            return resized
        }
        return mat
    }

    // ── Fallback: coarse mask → CV_32FC1 [0,1] at width×height ──────────────
    private fun coarseToFloatMat(coarseMask: Mat, width: Int, height: Int): Mat {
        val f = Mat()
        when (coarseMask.type()) {
            CvType.CV_32FC1 -> coarseMask.copyTo(f)
            else -> coarseMask.convertTo(f, CvType.CV_32FC1, if (coarseMask.depth() == CvType.CV_8U) 1.0 / 255.0 else 1.0)
        }
        if (f.cols() != width || f.rows() != height) {
            val r = Mat()
            Imgproc.resize(f, r, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            f.release()
            return r
        }
        return f
    }

    companion object {
        private const val TAG = "SAMMobile"
        private const val TARGET_LENGTH = 1024
        private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
        private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)
        private const val ENCODER_ASSET = "models/mobile_sam_encoder.onnx"
        private const val DECODER_ASSET = "models/mobile_sam_decoder.onnx"

        /**
         * Build the encoder + decoder sessions from assets. THROWS when either
         * model is absent or a session fails — the caller treats that as
         * "SAM unavailable" and falls back to U2Net-only.
         */
        fun load(context: Context): SAMMobileModel {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1); setInterOpNumThreads(1)
            }
            val encBytes = context.assets.open(ENCODER_ASSET).use { it.readBytes() }
            val decBytes = context.assets.open(DECODER_ASSET).use { it.readBytes() }
            val enc = env.createSession(encBytes, opts)
            val dec = env.createSession(decBytes, opts)
            Log.i(TAG, "MobileSAM sessions created (enc=${encBytes.size / 1024}KB dec=${decBytes.size / 1024}KB)")
            return SAMMobileModel(enc, dec)
        }
    }
}
