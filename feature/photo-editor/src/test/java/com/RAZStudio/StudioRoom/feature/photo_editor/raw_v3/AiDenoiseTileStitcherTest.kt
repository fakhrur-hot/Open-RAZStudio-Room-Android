package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AiDenoiseTileStitcherTest {
    @Test
    fun `identity inference is pixel exact across tile seams and image boundaries`() {
        val width = 521
        val height = 517
        val input = gradient(width, height)
        val output = AiDenoiseTileStitcher.processRgb16(input, width, height) { it.copyOf() }
        assertArrayEquals(input, output)
    }

    @Test
    fun `identity inference handles images smaller than one core`() {
        val width = 37
        val height = 19
        val input = gradient(width, height)
        val output = AiDenoiseTileStitcher.processRgb16(input, width, height) { it.copyOf() }
        assertArrayEquals(input, output)
    }

    @Test
    fun `model output is clamped before core commit`() {
        val width = 300
        val height = 270
        val input = gradient(width, height)
        val output = AiDenoiseTileStitcher.processRgb16(input, width, height) {
            IntArray(it.size) { index -> if (index % 3 == 0) -1 else 65536 }
        }
        for (index in output.indices step 3) {
            assertEquals(0, output[index])
            assertEquals(65535, output[index + 1])
            assertEquals(65535, output[index + 2])
        }
    }

    @Test
    fun `preview allocation guard caps large previews within default memory budget`() {
        val (w, h) = PreviewAllocationGuard.capDimensions(6000, 4000, 2_000_000L)
        assertEquals(true, w > 0 && h > 0)
        assertEquals(true, w.toLong() * h.toLong() <= 2_000_000L)
        assertEquals(true, w < 6000 && h < 4000)
    }

    private fun gradient(width: Int, height: Int): IntArray = IntArray(width * height * 3) { index ->
        val pixel = index / 3
        val channel = index % 3
        val x = pixel % width
        val y = pixel / width
        when (channel) {
            0 -> (x * 65535 / (width - 1).coerceAtLeast(1))
            1 -> (y * 65535 / (height - 1).coerceAtLeast(1))
            else -> ((x + y) * 65535 / (width + height - 2).coerceAtLeast(1))
        }
    }
}
