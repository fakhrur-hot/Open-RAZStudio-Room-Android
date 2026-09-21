/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt


/**
 * Lightweight descriptor representing how a mask component was generated.
 * Storing operations — not full-resolution bitmaps — is what keeps the
 * undo history at a few KB per step (a few hundred KB for a stored 320²
 * model plane) instead of multi-megapixel snapshots per step.
 */
sealed interface MaskSource {
    /** Manual brush stroke, in bitmap pixel coordinates. [hardness] is
     *  1 − feather; [intensity] is the paint alpha; erasing is expressed
     *  by the node's MaskOp.SUBTRACT, not a flag here. */
    data class BrushStroke(
        val points: List<Pair<Float, Float>>,
        val radius: Float,
        val hardness: Float,
        val intensity: Float = 0.8f,
    ) : MaskSource

    /** Detector output: identifying semantic class for UI labels/re-resolves.
     *  The actual 320² probability plane is resolved during reconstruction
     *  from the current session's segmentation result to avoid high-memory
     *  persistence. */
    data class ModelClass(
        val maskClass: MaskClass,
    ) : MaskSource

    /** "Select Color": sampled ARGB colours + Refine tolerance [0..100],
     *  keyed in HSV-cone space against the neutral bitmap at rebuild time. */
    data class ColorRange(
        val samples: List<Int>,
        val tolerance: Float,
        val range: Float = 60f,
        val feather: Float = 30f,
    ) : MaskSource

    /** Persistence fallback: a previously-saved mask bitmap from disk. Used
     *  when a project or preset is loaded and the individual edit operations
     *  (strokes) are no longer available. */
    data class StoredBitmap(
        val path: String,
    ) : MaskSource

    /** "Sharp Edges" fill: subject probability + Sobel edge-snap + dilation. */
    data class SharpSubject(
        val spread: Float,
    ) : MaskSource
}

/** An individual node in the layer stack. The first node IS the base mask;
 *  every later node unions into or carves out of the accumulated composite. */
data class MaskNode(
    val id: String,
    val source: MaskSource,
    val operation: MaskOp, // ADD or SUBTRACT
    val isEnabled: Boolean = true,
)

/**
 * Maintains historical states using simple node-mutation operations. Each
 * entry on the undo stack is a full node LIST — lists are tiny (descriptors
 * only), so snapshotting them is cheap. [onRebuildComposite] is invoked on
 * every transition; the owner re-evaluates the composite bitmap from the
 * node list and publishes it (component.masking.updateMask).
 */
class MaskHistoryTracker(
    private val onRebuildComposite: (List<MaskNode>) -> Unit,
) {
    private val undoStack = ArrayDeque<List<MaskNode>>()
    private val redoStack = ArrayDeque<List<MaskNode>>()

    var currentNodes: List<MaskNode> = emptyList()
        private set

    fun pushOperation(node: MaskNode) {
        undoStack.addLast(currentNodes)
        redoStack.clear()
        currentNodes = currentNodes + node
        onRebuildComposite(currentNodes)
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(currentNodes)
        currentNodes = undoStack.removeLast()
        onRebuildComposite(currentNodes)
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.addLast(currentNodes)
        currentNodes = redoStack.removeLast()
        onRebuildComposite(currentNodes)
        return true
    }

    /** Non-undoable node-list replacement — used by enable/disable toggles
     *  (Lightroom-style: toggling a mask component is not an edit step). */
    fun replaceCurrent(nodes: List<MaskNode>) {
        currentNodes = nodes
        onRebuildComposite(currentNodes)
    }

    fun reset() {
        undoStack.clear()
        redoStack.clear()
        currentNodes = emptyList()
        onRebuildComposite(currentNodes)
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
}

/**
 * Re-evaluate the composite bitmap from the operational graph. Iterates the
 * enabled nodes in order, rasterizing each lightweight descriptor and folding
 * it over the accumulator with [buildCompositeMask] (PorterDuff ADD / DST_OUT
 * per the node's op). Only ONE full-size composite bitmap exists at a time —
 * intermediates are recycled as we go.
 *
 * [neutral] supplies both the output dimensions and the source pixels the
 * colour-range keyer reads.
 */
fun rebuildCompositeFromNodes(
    neutral: Bitmap,
    nodes: List<MaskNode>,
    modelMaskResolver: (MaskClass) -> FloatArray?,
    edgeMaskResolver: () -> FloatArray? = { null },
): Bitmap {
    val width = neutral.width
    val height = neutral.height
    var composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    nodes.filter { it.isEnabled }.forEach { node ->
        val nodeBitmap = when (val source = node.source) {
            is MaskSource.ModelClass -> {
                val plane = modelMaskResolver(source.maskClass)
                if (plane != null) rasterizeMaskPlane(plane, width, height) else null
            }
            is MaskSource.ColorRange ->
                generateColorRangeBitmap(
                    source.samples, source.tolerance,
                    source.range, source.feather, neutral
                )
            is MaskSource.BrushStroke ->
                rasterizeBrushPath(source, width, height)
            is MaskSource.StoredBitmap ->
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawMaskStorage
                    .loadFromPath(source.path)
            is MaskSource.SharpSubject -> {
                val subj = modelMaskResolver(MaskClass.Subject)
                val edge = edgeMaskResolver()
                if (subj != null && edge != null) {
                    generateSharpSubjectBitmap(subj, edge, source.spread, neutral)
                } else null
            }
        }

        if (nodeBitmap != null) {
            val updatedComposite = buildCompositeMask(
                baseMask = composite,
                subOps = listOf(MaskSubOperation(nodeBitmap, node.operation)),
            )

            if (composite !== updatedComposite) composite.recycle()
            nodeBitmap.recycle()
            composite = updatedComposite
        }
    }

    return composite
}

/** Port of the editor's HSV-cone "Select Color" keyer — kept self-contained
 *  here so graph rebuilds don't depend on the interactive fill path. */
private fun generateColorRangeBitmap(
    samples: List<Int>,
    tolerance: Float,
    range: Float,
    feather: Float,
    neutral: Bitmap,
): Bitmap {
    val tol = (tolerance / 100f).coerceIn(0f, 1f)
    val spread = (range / 100f).coerceIn(0f, 1f)
    val edge = (feather / 100f).coerceIn(0f, 1f)
    val radiusBase = 0.06f + spread * 0.75f
    val radius = radiusBase + tol * 0.30f + edge * 0.12f
    val rInner = (radiusBase * 0.72f - edge * 0.18f).coerceAtLeast(0.02f)

    fun toVec(argb: Int): FloatArray {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val v = mx; val s = if (mx <= 0f) 0f else d / mx
        val hDeg = when {
            d <= 0f -> 0f
            mx == r -> (60f * (((g - b) / d) % 6f))
            mx == g -> (60f * (((b - r) / d) + 2f))
            else    -> (60f * (((r - g) / d) + 4f))
        }
        val hRad = Math.toRadians(hDeg.toDouble())
        return floatArrayOf((cos(hRad) * s).toFloat(), (sin(hRad) * s).toFloat(), v)
    }
    val svecs = samples.map { toVec(it) }

    // Cap working resolution — the mask is soft and gets downsampled to
    // 512 on GL upload anyway (same rationale as the interactive path).
    val longSide = max(neutral.width, neutral.height)
    val scale = if (longSide > 900) 900f / longSide else 1f
    val small = if (scale < 1f)
        Bitmap.createScaledBitmap(
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
        val c = src[i]
        val r = ((c ushr 16) and 0xFF) / 255f
        val g = ((c ushr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val dch = mx - mn
        val v = mx; val s = if (mx <= 0f) 0f else dch / mx
        val hDeg = when {
            dch <= 0f -> 0f
            mx == r   -> (60f * (((g - b) / dch) % 6f))
            mx == g   -> (60f * (((b - r) / dch) + 2f))
            else      -> (60f * (((r - g) / dch) + 4f))
        }
        val hRad = Math.toRadians(hDeg.toDouble())
        val px = (cos(hRad) * s).toFloat()
        val py = (sin(hRad) * s).toFloat()
        var best = Float.MAX_VALUE
        for (sv in svecs) {
            val dx = px - sv[0]; val dy = py - sv[1]; val dv = v - sv[2]
            val dist = sqrt(dx * dx + dy * dy + dv * dv)
            if (dist < best) best = dist
        }
        val a = when {
            best <= rInner -> 255
            best >= radius -> 0
            else -> {
                val t = ((radius - best) / (radius - rInner)).coerceIn(0f, 1f)
                (t * t * (3f - 2f * t) * 255f).toInt()
            }
        }
        out[i] = (a shl 24) or 0x00FFFFFF
        i++
    }
    val bmp = Bitmap.createBitmap(bW, bH, Bitmap.Config.ARGB_8888)
    bmp.setPixels(out, 0, bW, 0, 0, bW, bH)
    return bmp
}

private fun generateSharpSubjectBitmap(
    subject: FloatArray,
    edges: FloatArray,
    spread: Float,
    neutral: Bitmap,
): Bitmap {
    val bW = neutral.width; val bH = neutral.height
    val mSize = 320
    val pixels = IntArray(bW * bH)
    val threshold = (0.5f - spread * 0.4f).coerceIn(0.05f, 0.95f)
    val snapStrength = 0.6f
    val snapThreshold = 0.20f
    for (y in 0 until bH) {
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
            val a = (sm * 255f).toInt().coerceIn(0, 255)
            pixels[y * bW + x] = (a shl 24) or 0x00FFFFFF
        }
    }
    val bmp = Bitmap.createBitmap(bW, bH, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, bW, 0, 0, bW, bH)
    return bmp
}


/** Rasterize a committed brush stroke onto a scratch bitmap, matching the
 *  live paint parameters (stroke cap/join/width, intensity alpha, feather
 *  blur). The node's ADD/SUBTRACT op supplies the PorterDuff mode at
 *  composite time; for erase strokes we also pre-apply DST_OUT semantics by
 *  drawing the path with full coverage — alpha carries the intensity. */
private fun rasterizeBrushPath(
    source: MaskSource.BrushStroke,
    width: Int,
    height: Int,
): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    if (source.points.isEmpty()) return bmp
    val canvas = Canvas(bmp)
    val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = source.radius
        color = android.graphics.Color.WHITE
        alpha = (255 * source.intensity).toInt().coerceIn(0, 255)
        val feather = (1f - source.hardness).coerceIn(0f, 1f)
        if (feather > 0.01f) {
            maskFilter = BlurMaskFilter(
                source.radius * feather * 0.5f,
                BlurMaskFilter.Blur.NORMAL,
            )
        }
    }
    var last = source.points[0]
    canvas.drawLine(last.first, last.second, last.first, last.second, paint)
    for (p in source.points.asIterable().drop(1)) {
        canvas.drawLine(last.first, last.second, p.first, p.second, paint)
        last = p
    }
    return bmp
}
