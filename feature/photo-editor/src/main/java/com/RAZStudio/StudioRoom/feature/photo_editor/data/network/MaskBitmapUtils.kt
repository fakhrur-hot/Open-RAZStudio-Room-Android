/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Converts the app's existing soft-segmentation mask representation
 * (row-major FloatArray, values in [0,1] — the format used by
 * RawV3MulticlassMasks.faceSkin/bodySkin and RawV3FaceDetector's
 * face-ellipse mask) into a Bitmap suitable for OnlineAiEditClient's
 * faceMask upload parameter.
 *
 * No such conversion existed anywhere in the codebase before this feature —
 * every other consumer of these FloatArrays (mask/bokeh/tone routing) reads
 * them directly as float planes, never as a Bitmap. This is genuinely new
 * code, not a reuse of an existing utility (Requirement 5.1).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.data.network

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Renders this soft-mask [FloatArray] (row-major, [size] x [size], values in
 * `[0, 1]`) as a grayscale [Bitmap.Config.ARGB_8888] bitmap — each value
 * becomes an opaque gray pixel (`0f` -> black, `1f` -> white), so the encoded
 * PNG is directly viewable as a mask image on the Worker side without any
 * StudioRoom-specific decoding.
 *
 * @throws IllegalArgumentException if this array's size does not equal
 *   `size * size`.
 */
internal fun FloatArray.toMaskBitmap(size: Int): Bitmap {
    require(this.size == size * size) {
        "Mask FloatArray size (${this.size}) does not match $size x $size"
    }

    val pixels = IntArray(size * size) { i ->
        val gray = (this[i].coerceIn(0f, 1f) * 255f).toInt()
        Color.argb(255, gray, gray, gray)
    }

    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
