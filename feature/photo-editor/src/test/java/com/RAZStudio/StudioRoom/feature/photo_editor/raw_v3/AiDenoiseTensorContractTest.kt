package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import org.junit.Assert.assertEquals
import org.junit.Test

class AiDenoiseTensorContractTest {
    @Test
    fun `solid colors preserve RGB channel order through NCHW round trip`() {
        val colors = listOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        for (color in colors) {
            val rgb = FloatArray(AiDenoiseTensorContract.VALUES) { color[it % 3] }
            val roundTrip = AiDenoiseTensorContract.unpackNchwToRgb(
                AiDenoiseTensorContract.packRgbToNchw(rgb),
            )
            assertFloatArrayEquals(rgb, roundTrip)
        }
    }

    @Test
    fun `gradient preserves planar values without channel mixing`() {
        val rgb = FloatArray(AiDenoiseTensorContract.VALUES) { index ->
            when (index % 3) {
                0 -> (index / 3 % 288) / 287f
                1 -> (index / 3 / 288) / 287f
                else -> 1f - ((index / 3) % 288) / 287f
            }
        }
        val roundTrip = AiDenoiseTensorContract.unpackNchwToRgb(
            AiDenoiseTensorContract.packRgbToNchw(rgb),
        )
        assertFloatArrayEquals(rgb, roundTrip)
    }

    @Test
    fun `uint16 conversion clamps and round trips normalized endpoints`() {
        val input = IntArray(AiDenoiseTensorContract.VALUES) { index ->
            when (index % 4) {
                0 -> -10
                1 -> 0
                2 -> 65535
                else -> 65545
            }
        }
        val output = AiDenoiseTensorContract.normalizedToUint16(
            AiDenoiseTensorContract.uint16ToNormalized(input),
        )
        for (index in output.indices) {
            val expected = when (index % 4) {
                0, 1 -> 0
                else -> 65535
            }
            assertEquals(expected, output[index])
        }
    }

    private fun assertFloatArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertEquals(expected[index].toDouble(), actual[index].toDouble(), 1e-6)
        }
    }
}
