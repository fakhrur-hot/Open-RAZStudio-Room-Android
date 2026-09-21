/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * Boolean-combination layer over a base mask, Lightroom-style:
 *
 * ```
 * Mask Layer (final output)
 * ├── Base Mask            (initial model output: Subject / Sky / Face / …)
 * ├── Union Ops (+) [Add]      — extra model detections, manual brush, colour samples
 * └── Difference Ops (-) [Sub] — model detections to carve out, manual erase brush
 * ```
 *
 * Model outputs stay non-destructive inputs: each op references a bitmap
 * produced by a detector (or the user's brush), and [buildCompositeMask]
 * folds them over the base in one pass. PorterDuff mode per op:
 *  • [MaskOp.ADD]      → `PorterDuff.Mode.ADD`   (union of coverages)
 *  • [MaskOp.SUBTRACT] → `PorterDuff.Mode.DST_OUT` (carve source alpha out of dest)
 */
enum class MaskOp { ADD, SUBTRACT }

/** One non-destructive boolean op against the layer's base mask. */
class MaskSubOperation(
    val bitmap: Bitmap,
    val operation: MaskOp,
)

/**
 * Fold [subOps] over [baseMask] and return the composite as a NEW bitmap
 * ([baseMask] itself is never mutated — callers publish the result via
 * `component.masking.updateMask`). Ops apply in list order: unions grow the
 * selection, differences carve out of everything beneath them.
 */
fun buildCompositeMask(
    baseMask: Bitmap,
    subOps: List<MaskSubOperation>,
): Bitmap {
    val result = baseMask.copy(baseMask.config ?: Bitmap.Config.ARGB_8888, true)
    if (subOps.isEmpty()) return result
    val canvas = Canvas(result)
    val paint = Paint()
    subOps.forEach { op ->
        paint.xfermode = when (op.operation) {
            MaskOp.ADD -> PorterDuffXfermode(PorterDuff.Mode.ADD)
            MaskOp.SUBTRACT -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        canvas.drawBitmap(op.bitmap, 0f, 0f, paint)
    }
    paint.xfermode = null
    return result
}

/**
 * Bilinear upsample of a 320×320 row-major [0..1] probability plane (the
 * format produced by every detector: U²Net subject/background, MediaPipe
 * multiclass, Cityscapes, DeepLab) into a full-size alpha bitmap the
 * composite painter can consume. Alpha = weight × 255, RGB opaque white.
 */
fun rasterizeMaskPlane(
    plane: FloatArray,
    width: Int,
    height: Int,
    planeSide: Int = 320,
): Bitmap {
    require(plane.size == planeSide * planeSide) {
        "plane size ${plane.size} != ${planeSide * planeSide}"
    }
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val my = y.toFloat() / (height - 1).coerceAtLeast(1) * (planeSide - 1)
        val y0 = my.toInt().coerceIn(0, planeSide - 2)
        val dy = my - y0
        for (x in 0 until width) {
            val mx = x.toFloat() / (width - 1).coerceAtLeast(1) * (planeSide - 1)
            val x0 = mx.toInt().coerceIn(0, planeSide - 2)
            val dx = mx - x0
            val p = plane[y0 * planeSide + x0] * (1 - dx) * (1 - dy) +
                    plane[y0 * planeSide + x0 + 1] * dx * (1 - dy) +
                    plane[(y0 + 1) * planeSide + x0] * (1 - dx) * dy +
                    plane[(y0 + 1) * planeSide + x0 + 1] * dx * dy
            val a = (p.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
            pixels[y * width + x] = (a shl 24) or 0x00FFFFFF
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
