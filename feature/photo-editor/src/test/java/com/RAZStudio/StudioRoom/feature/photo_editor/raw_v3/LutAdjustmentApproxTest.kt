package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds a tiny synthetic cube by running StudioRoom-like saturation+contrast
 * on the lattice, then checks [LutAdjustmentApprox] recovers the sign/range.
 */
class LutAdjustmentApproxTest {

    @Test
    fun recoversPositiveSaturationAndContrastSign() {
        val size = 9
        val data = FloatArray(size * size * size * 3)
        val sat = 0.35f
        val contrast = 0.25f
        var i = 0
        for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
            var r = ri / (size - 1f)
            var g = gi / (size - 1f)
            var b = bi / (size - 1f)
            // Contrast around mid (display-approx of linear pivot).
            r = 0.5f + (r - 0.5f) * (1f + contrast)
            g = 0.5f + (g - 0.5f) * (1f + contrast)
            b = 0.5f + (b - 0.5f) * (1f + contrast)
            val L = r * 0.2627f + g * 0.6780f + b * 0.0593f
            r = (L + (r - L) * (1f + sat)).coerceIn(0f, 1f)
            g = (L + (g - L) * (1f + sat)).coerceIn(0f, 1f)
            b = (L + (b - L) * (1f + sat)).coerceIn(0f, 1f)
            data[i++] = r; data[i++] = g; data[i++] = b
        }
        val cube = RawV3LutStore.ParsedCube(size = size, data = data)
        val result = LutAdjustmentApprox.analyze(cube, intensity = 1f)
        assertTrue("sat=${result.saturation}", result.saturation > 15f)
        assertTrue("contrast=${result.contrast}", result.contrast > 5f)
        assertTrue("explained=${result.explained}", result.explained > 0.2f)
    }

    @Test
    fun identityCubeIsNearNeutral() {
        val size = 5
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
            data[i++] = ri / (size - 1f)
            data[i++] = gi / (size - 1f)
            data[i++] = bi / (size - 1f)
        }
        val result = LutAdjustmentApprox.analyze(
            RawV3LutStore.ParsedCube(size, data), 1f,
        )
        assertTrue(kotlin.math.abs(result.saturation) < 5f)
        assertTrue(kotlin.math.abs(result.contrast) < 5f)
        assertTrue(kotlin.math.abs(result.whiteBalanceKelvinDelta) < 50)
    }
}
