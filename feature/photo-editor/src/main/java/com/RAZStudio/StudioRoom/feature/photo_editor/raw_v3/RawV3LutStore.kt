package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import java.io.File

/** Open edition ABI stub. Dedicated LUT parsing and chaining are private. */
object RawV3LutStore {
    data class LutEntry(val name: String, val file: File)
    data class ParsedCube(
        val size: Int,
        val data: FloatArray,
        val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
        val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    )
    fun discoveryDir(context: Context) = File(context.cacheDir, "open_lut_disabled")
    fun list(context: Context): List<LutEntry> = emptyList()
    fun parseCubeFile(file: File): ParsedCube? = null
    fun chainLuts(
        lut1: ParsedCube, intensity1: Float, lut2: ParsedCube, intensity2: Float,
        bw1: Boolean = false, bw2: Boolean = false,
    ): ParsedCube = ParsedCube(0, FloatArray(0))
    fun chainLuts(layers: List<Pair<ParsedCube, Float>>) = ParsedCube(0, FloatArray(0))
    fun chainLutsBw(layers: List<Triple<ParsedCube, Float, Boolean>>) = ParsedCube(0, FloatArray(0))
    fun trilinearSample(lut: ParsedCube, r: Float, g: Float, b: Float, ch: Int) = 0f
    fun writeCubeFile(cube: ParsedCube, file: File) = Unit
}