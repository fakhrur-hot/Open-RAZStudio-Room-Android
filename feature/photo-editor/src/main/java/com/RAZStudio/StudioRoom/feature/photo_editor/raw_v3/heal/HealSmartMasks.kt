/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Smart mask builders for the Heal/Erase tool — the two Lightroom-mobile
 * behaviours our RAZGAN heal lacked:
 *
 *  • [snapToObject] — "Detect objects": a rough scribble becomes a prompt to
 *    MobileSAM (stroke points as foreground prompts + the stroke's padded
 *    bounding box), and the returned object mask replaces the literal brush
 *    shape. The stroke itself is ALWAYS unioned back in, so painted pixels are
 *    healed even when the model disagrees — the model can only add, never
 *    silently shrink what the user painted.
 *
 *  • [peopleMask] — one-tap "Remove people": DeepLabV3+ human parsing (the
 *    bundled LIP model) unions all 19 person classes into a single hole mask.
 *
 *  • [subjectMask] — one-tap "Remove subject": BiRefNet-lite (when present)
 *    then U2Net general saliency — the same rembg-style subject chain the
 *    editor uses for Select Subject — dilated into a hole for RAZGAN/TELEA.
 *
 * Both return strict-binary ARGB masks (white = hole) at SOURCE size, i.e.
 * exactly what [InpaintEngine.heal] expects. Both are null-safe: missing
 * models or inference failure return null and the caller keeps the plain
 * brush behaviour — Heal never gets worse because a model is absent.
 *
 * The prior object-aware heal (U2Net whole-image saliency, removed from the
 * brush path) grew every stroke to THE subject; SAM prompted by the stroke
 * selects the object UNDER the stroke, which is the Lightroom behaviour.
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.heal

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3DeepLabMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3DeepLabProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3SegmentationMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3SegmentationProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.segmentation.SAMMobileModel
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object HealSmartMasks {
    private const val TAG = "HealSmartMasks"

    /** Long-side cap for the SAM working copy — masks don't need full res. */
    private const val SNAP_LONG_SIDE = 1024

    /** Max foreground point prompts sampled along the stroke. */
    private const val MAX_PROMPT_POINTS = 6

    @Volatile private var sam: SAMMobileModel? = null
    @Volatile private var samTried = false

    private fun sam(ctx: Context): SAMMobileModel? {
        sam?.let { return it }
        if (samTried) return sam
        synchronized(this) {
            if (samTried) return sam
            samTried = true
            sam = runCatching { SAMMobileModel.load(ctx) }
                .onFailure { Log.w(TAG, "MobileSAM unavailable — Detect objects disabled", it) }
                .getOrNull()
            return sam
        }
    }

    fun isObjectSnapAvailable(ctx: Context): Boolean = sam(ctx) != null

    /**
     * Snap a brush stroke to the object under it. [strokePts] and [radius] are
     * in [src] pixel coordinates. Returns a white-on-black ARGB hole mask at
     * source size covering (object ∪ stroke), or null → caller uses the plain
     * stroke mask.
     */
    suspend fun snapToObject(
        ctx: Context,
        src: Bitmap,
        strokePts: List<Pair<Float, Float>>,
        radius: Float,
    ): Bitmap? {
        val model = sam(ctx) ?: return null
        if (strokePts.isEmpty()) return null
        runCatching { org.opencv.android.OpenCVLoader.initLocal() }

        // Work at ≤1024 long side: the decoder emits masks at the working size,
        // so this bounds both inference cost and the float mask allocation.
        val down = max(src.width, src.height).toFloat() / SNAP_LONG_SIDE
        val work: Bitmap
        val s: Float           // source px → work px
        if (down > 1f) {
            s = 1f / down
            work = Bitmap.createScaledBitmap(
                src,
                (src.width * s).roundToInt().coerceAtLeast(1),
                (src.height * s).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            s = 1f
            work = src
        }
        return try {
            // Evenly-sampled foreground points along the stroke.
            val step = max(1, strokePts.size / MAX_PROMPT_POINTS)
            val points = strokePts.filterIndexed { i, _ -> i % step == 0 }
                .take(MAX_PROMPT_POINTS)
                .map { (x, y) -> (x * s) to (y * s) }
            // Box = stroke bbox padded by 2× brush radius. The pad gives SAM
            // room to complete the object; the box keeps it from wandering to
            // some other salient region (the old U2Net failure mode).
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = 0f; var maxY = 0f
            strokePts.forEach { (x, y) ->
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
            val pad = 2f * radius
            val box = floatArrayOf(
                ((minX - pad) * s).coerceIn(0f, work.width - 1f),
                ((minY - pad) * s).coerceIn(0f, work.height - 1f),
                ((maxX + pad) * s).coerceIn(0f, work.width - 1f),
                ((maxY + pad) * s).coerceIn(0f, work.height - 1f),
            )

            val mat = model.segmentPrompted(work, points, box) ?: return null
            val mask = matToHoleMask(mat, src.width, src.height)
            mat.release()
            mask
        } finally {
            if (work !== src) work.recycle()
        }
    }

    /**
     * One-tap "Remove people": DeepLab LIP human parsing → union of all person
     * classes → dilated hole mask at source size. Null when the model is
     * missing or no person was detected. The processor is created and released
     * per call (45 MB session) — this is a rare, explicit action.
     */
    fun peopleMask(ctx: Context, src: Bitmap): Bitmap? {
        val proc = RawV3DeepLabProcessor(ctx)
        try {
            if (!proc.hasModel) return null
            val masks = proc.compute(src) ?: return null
            val n = RawV3DeepLabMasks.SIZE * RawV3DeepLabMasks.SIZE
            val union = FloatArray(n)
            listOf(
                masks.face, masks.hair, masks.upperBody, masks.lowerBody,
                masks.arms, masks.legs, masks.shoes, masks.accessories,
            ).forEach { g -> for (i in 0 until n) if (g[i] > 0.5f) union[i] = 1f }
            var count = 0
            for (i in 0 until n) if (union[i] > 0f) count++
            // Below ~0.05% of pixels is parsing noise, not a person.
            if (count < n / 2000) {
                Log.i(TAG, "peopleMask: no person detected ($count px)")
                return null
            }
            // Dilate at 320² (r=3 ≈ 1% of frame) so the hole swallows the
            // person's soft edge — an inpaint seeded with halo pixels leaves a
            // ghost outline.
            val sz = RawV3DeepLabMasks.SIZE
            val dilated = dilate(union, sz, sz, 3)
            // 320² binary → ARGB → bilinear upscale → re-threshold. The scale
            // blur widens the boundary a touch further, which is desirable here.
            val small = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            val px = IntArray(n)
            for (i in 0 until n) px[i] = if (dilated[i] > 0.5f) -0x1 else -0x1000000
            small.setPixels(px, 0, sz, 0, 0, sz, sz)
            val big = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            small.recycle()
            val out = binarize(big, threshold = 64)
            big.recycle()
            Log.i(TAG, "peopleMask: person coverage ${count * 100 / n}% of frame")
            return out
        } finally {
            proc.release()
        }
    }

    /**
     * One-tap "Remove subject": rembg-style general subject matte
     * (BiRefNet-lite → U2Net) → dilated hole at source size. Null when both
     * models are missing or neither finds a usable subject. Sessions are
     * created and released per call — rare explicit action, same pattern as
     * [peopleMask]. [withTimeoutOrNull] + finally [release] is safe: each
     * processor's [OrtSessionGate] defers OrtSession.close until run returns.
     */
    suspend fun subjectMask(ctx: Context, src: Bitmap): Bitmap? {
        // BiRefNet-lite first (portrait / hard-edge subjects). Cap at 90 s so a
        // hung ORT session can't pin the Heal sheet forever.
        val bi = RawV3SegmentationProcessor(ctx)
        try {
            if (bi.hasModel) {
                val masks = withTimeoutOrNull(90_000L) { bi.compute(src) }
                if (masks != null && masks.hasSubject) {
                    Log.i(TAG, "subjectMask: BiRefNet coverage=" +
                        "${"%.1f".format(masks.subjectCoverage * 100f)}%")
                    return floatSubjectToHole(masks.subjectMask, src)
                }
                Log.i(TAG, "subjectMask: BiRefNet empty/timeout — trying U2Net")
            }
        } finally {
            bi.release()
        }

        val u2 = RawSegmentationProcessor(ctx)
        try {
            if (!u2.hasModel) {
                Log.w(TAG, "subjectMask: no BiRefNet/U2Net model")
                return null
            }
            val masks = withTimeoutOrNull(30_000L) { u2.compute(src) }
            if (masks == null) {
                Log.w(TAG, "subjectMask: U2Net null/timeout")
                return null
            }
            val n = masks.subjectMask.size
            var count = 0
            for (v in masks.subjectMask) if (v > 0.5f) count++
            if (count < n / 2000) {
                Log.i(TAG, "subjectMask: U2Net no subject ($count px)")
                return null
            }
            Log.i(TAG, "subjectMask: U2Net coverage ${count * 100 / n}% of frame")
            return floatSubjectToHole(masks.subjectMask, src)
        } finally {
            u2.release()
        }
    }

    /** 320² [0,1] subject → dilated white-on-black ARGB hole at [src] size. */
    private fun floatSubjectToHole(subject: FloatArray, src: Bitmap): Bitmap? {
        val sz = RawV3SegmentationMasks.MASK_SIZE
        val n = sz * sz
        if (subject.size != n) {
            Log.w(TAG, "subjectMask: unexpected size ${subject.size} (want $n)")
            return null
        }
        val dilated = dilate(subject, sz, sz, 3)
        val small = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val px = IntArray(n)
        for (i in 0 until n) px[i] = if (dilated[i] > 0.5f) -0x1 else -0x1000000
        small.setPixels(px, 0, sz, 0, 0, sz, sz)
        val big = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        small.recycle()
        val out = binarize(big, threshold = 64)
        big.recycle()
        return out
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** CV_32FC1 {0,1} mat → white-on-black ARGB hole mask, scaled to [w]×[h]. */
    private fun matToHoleMask(mat: Mat, w: Int, h: Int): Bitmap? {
        if (mat.type() != CvType.CV_32FC1) return null
        val mw = mat.cols(); val mh = mat.rows()
        val row = FloatArray(mw)
        val px = IntArray(mw * mh)
        var fg = 0
        for (y in 0 until mh) {
            mat.get(y, 0, row)
            val base = y * mw
            for (x in 0 until mw) {
                if (row[x] > 0.5f) { px[base + x] = -0x1; fg++ } else px[base + x] = -0x1000000
            }
        }
        if (fg == 0) return null
        val small = Bitmap.createBitmap(px, mw, mh, Bitmap.Config.ARGB_8888)
        if (mw == w && mh == h) return small
        val big = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()
        val out = binarize(big, threshold = 96)
        big.recycle()
        return out
    }

    /** Re-threshold a bilinear-scaled mask back to strict 0/255 binary. */
    private fun binarize(bmp: Bitmap, threshold: Int): Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = if ((px[i] and 0xFF) > threshold) -0x1 else -0x1000000
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun dilate(m: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        // Two-pass separable max filter.
        val tmp = FloatArray(m.size)
        val out = FloatArray(m.size)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var v = 0f
                for (k in max(0, x - r)..min(w - 1, x + r)) v = max(v, m[base + k])
                tmp[base + x] = v
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var v = 0f
                for (k in max(0, y - r)..min(h - 1, y + r)) v = max(v, tmp[k * w + x])
                out[y * w + x] = v
            }
        }
        return out
    }
}
