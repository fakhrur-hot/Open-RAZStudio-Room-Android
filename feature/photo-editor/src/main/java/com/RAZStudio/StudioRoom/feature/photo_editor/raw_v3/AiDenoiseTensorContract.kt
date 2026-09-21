package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import kotlin.math.roundToInt

internal object AiDenoiseTensorContract {
    const val TILE_SIZE = 288
    const val CHANNELS = 3
    const val PIXELS = TILE_SIZE * TILE_SIZE
    const val VALUES = PIXELS * CHANNELS

    fun packRgbToNchw(inputRgb: FloatArray): FloatArray {
        require(inputRgb.size == VALUES) { "expected 288x288 RGB tile" }
        val nchw = FloatArray(VALUES)
        for (pixel in 0 until PIXELS) {
            nchw[pixel] = inputRgb[pixel * CHANNELS].coerceIn(0f, 1f)
            nchw[PIXELS + pixel] = inputRgb[pixel * CHANNELS + 1].coerceIn(0f, 1f)
            nchw[PIXELS * 2 + pixel] = inputRgb[pixel * CHANNELS + 2].coerceIn(0f, 1f)
        }
        return nchw
    }

    fun unpackNchwToRgb(outputNchw: FloatArray): FloatArray {
        require(outputNchw.size == VALUES) { "expected 288x288 NCHW output" }
        val rgb = FloatArray(VALUES)
        for (pixel in 0 until PIXELS) {
            rgb[pixel * CHANNELS] = outputNchw[pixel].coerceIn(0f, 1f)
            rgb[pixel * CHANNELS + 1] = outputNchw[PIXELS + pixel].coerceIn(0f, 1f)
            rgb[pixel * CHANNELS + 2] = outputNchw[PIXELS * 2 + pixel].coerceIn(0f, 1f)
        }
        return rgb
    }

    fun uint16ToNormalized(inputRgb: IntArray): FloatArray {
        require(inputRgb.size == VALUES) { "expected 288x288 RGB16 tile" }
        return FloatArray(VALUES) { inputRgb[it].coerceIn(0, 65535) / 65535f }
    }

    fun normalizedToUint16(outputRgb: FloatArray): IntArray {
        require(outputRgb.size == VALUES) { "expected 288x288 RGB output" }
        return IntArray(VALUES) { (outputRgb[it].coerceIn(0f, 1f) * 65535f).roundToInt() }
    }
}
