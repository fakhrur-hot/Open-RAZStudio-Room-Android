package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

internal object AiDenoiseTileStitcher {
    private const val CORE = 256
    private const val HALO = 16
    private const val TILE = CORE + HALO * 2

    fun processRgb16(
        input: IntArray,
        width: Int,
        height: Int,
        infer: (IntArray) -> IntArray,
    ): IntArray {
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(input.size == width * height * 3) { "expected interleaved RGB16 input" }
        val output = input.copyOf()
        var coreY = 0
        while (coreY < height) {
            var coreX = 0
            while (coreX < width) {
                val tile = IntArray(TILE * TILE * 3)
                val tileOriginX = coreX - HALO
                val tileOriginY = coreY - HALO
                for (tileY in 0 until TILE) {
                    val sourceY = mirror(tileOriginY + tileY, height)
                    for (tileX in 0 until TILE) {
                        val sourceX = mirror(tileOriginX + tileX, width)
                        val sourceOffset = (sourceY * width + sourceX) * 3
                        val tileOffset = (tileY * TILE + tileX) * 3
                        tile[tileOffset] = input[sourceOffset]
                        tile[tileOffset + 1] = input[sourceOffset + 1]
                        tile[tileOffset + 2] = input[sourceOffset + 2]
                    }
                }
                val restored = infer(tile)
                require(restored.size == tile.size) { "model returned an invalid tile size" }
                val endX = minOf(coreX + CORE, width)
                val endY = minOf(coreY + CORE, height)
                for (y in coreY until endY) {
                    for (x in coreX until endX) {
                        val tileOffset = ((y - tileOriginY) * TILE + (x - tileOriginX)) * 3
                        val outputOffset = (y * width + x) * 3
                        output[outputOffset] = restored[tileOffset].coerceIn(0, 65535)
                        output[outputOffset + 1] = restored[tileOffset + 1].coerceIn(0, 65535)
                        output[outputOffset + 2] = restored[tileOffset + 2].coerceIn(0, 65535)
                    }
                }
                coreX += CORE
            }
            coreY += CORE
        }
        return output
    }

    private fun mirror(index: Int, size: Int): Int {
        if (size == 1) return 0
        var value = index
        while (value < 0 || value >= size) {
            value = if (value < 0) -value else 2 * size - 2 - value
        }
        return value
    }
}
