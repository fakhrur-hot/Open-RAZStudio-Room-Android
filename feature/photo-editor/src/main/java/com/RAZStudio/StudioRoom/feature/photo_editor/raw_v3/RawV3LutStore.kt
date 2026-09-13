/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — 3D LUT discovery.
 *
 *  Discovers `.cube` LUTs under context.getExternalFilesDir("presets/luts/")
 *  per Plan.md §10.2. On first run, seeds the directory from `assets/luts/`
 *  so users have a starting set without needing to source LUTs themselves.
 *
 *  This is intentionally tiny — no caching, no DB. The directory is small
 *  (≤ a few MB of text), the editor reads it lazily.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object RawV3LutStore {

    private const val TAG = "RawV3.LutStore"
    private const val ASSETS_DIR  = "luts"               // assets/luts/*.cube
    private const val EXTERNAL_DIR = "presets/luts"      // externalFilesDir/presets/luts

    data class LutEntry(
        val name: String,        // file name without extension
        val file: File,          // absolute path
    )

    /** Returns the discovery dir, creating it (and seeding on first run) if needed. */
    fun discoveryDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), EXTERNAL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
            seedFromAssets(context, dir)
        }
        return dir
    }

    /** List every `.cube` file in the discovery dir. Sorted by name. */
    fun list(context: Context): List<LutEntry> {
        val dir = discoveryDir(context)
        return dir.listFiles { f -> f.isFile && f.extension.equals("cube", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.map { LutEntry(it.nameWithoutExtension, it) }
            ?: emptyList()
    }

    /** Parsed `.cube` ready to feed Stage C's NDK kernel. */
    data class ParsedCube(
        val size: Int,
        val data: FloatArray,
        val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
        val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    )

    /**
     * Parse a `.cube` 3D LUT into a Stage-C-compatible flat FloatArray.
     *
     * Format consulted (Adobe spec): a single `LUT_3D_SIZE N` header
     * followed by N³ rows of `R G B` floats in [0, 1]. Comments (`#`)
     * and the optional `TITLE`, `DOMAIN_MIN`, `DOMAIN_MAX`, `LUT_3D_INPUT_RANGE`
     * keywords are tolerated and ignored. Output layout matches the GLES
     * `uploadLut3d`'s native expectation: size³ × 3 floats, RGB triplets
     * in row-major order (the same order [stageCExport]'s `lutData` accepts).
     *
     * Returns null on any parse error.
     */
    fun parseCubeFile(file: File): ParsedCube? {
        // Native first: raw_v3::parseCubeFile is the same reader the GL preview
        // uses and it covers every format we ship — ASCII .cube, 1D .cube
        // (expanded to 3D) and binary smol-cube (.smcube). The ASCII reader
        // below cannot see a .smcube at all ("missing LUT_3D_SIZE"), which is
        // exactly how the EXPORT lost B&W film LUTs that the preview applied.
        // Kept as a fallback so a LUT still loads if the .so is unavailable.
        RawV3Engine.parseLutFile(file.absolutePath)?.let { flat ->
            val size = if (flat.isNotEmpty()) flat[0].toInt() else 0
            val expected = size * size * size * 3
            if (size >= 2 && flat.size >= 7 + expected) {
                return ParsedCube(
                    size      = size,
                    data      = flat.copyOfRange(7, 7 + expected),
                    domainMin = floatArrayOf(flat[1], flat[2], flat[3]),
                    domainMax = floatArrayOf(flat[4], flat[5], flat[6]),
                )
            }
            Log.w(TAG, "native LUT parse returned ${flat.size} floats for size=$size — using ASCII reader")
        }
        return runCatching {
            var size = -1
            val domainMin = floatArrayOf(0f, 0f, 0f)
            val domainMax = floatArrayOf(1f, 1f, 1f)
            val triplets = ArrayList<Float>(64 * 64 * 64 * 3)
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    when {
                        line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                            size = line.substring("LUT_3D_SIZE".length).trim()
                                .toIntOrNull() ?: error("malformed LUT_3D_SIZE")
                        }
                        line.startsWith("DOMAIN_MIN", ignoreCase = true) -> {
                            val parts = line.substring("DOMAIN_MIN".length).trim().split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                domainMin[0] = parts[0].toFloatOrNull() ?: 0f
                                domainMin[1] = parts[1].toFloatOrNull() ?: 0f
                                domainMin[2] = parts[2].toFloatOrNull() ?: 0f
                            }
                        }
                        line.startsWith("DOMAIN_MAX", ignoreCase = true) -> {
                            val parts = line.substring("DOMAIN_MAX".length).trim().split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                domainMax[0] = parts[0].toFloatOrNull() ?: 1f
                                domainMax[1] = parts[1].toFloatOrNull() ?: 1f
                                domainMax[2] = parts[2].toFloatOrNull() ?: 1f
                            }
                        }
                        line.startsWith("TITLE", ignoreCase = true) ||
                            line.startsWith("LUT_3D_INPUT_RANGE", ignoreCase = true) -> {
                            // metadata — ignored
                        }
                        else -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size < 3) continue
                            val r = parts[0].toFloatOrNull() ?: continue
                            val g = parts[1].toFloatOrNull() ?: continue
                            val b = parts[2].toFloatOrNull() ?: continue
                            triplets.add(r); triplets.add(g); triplets.add(b)
                        }
                    }
                }
            }
            if (size <= 0) error("missing LUT_3D_SIZE")
            val expected = size * size * size * 3
            if (triplets.size != expected) {
                error("expected $expected floats, got ${triplets.size}")
            }
            ParsedCube(size = size, data = triplets.toFloatArray(), domainMin = domainMin, domainMax = domainMax)
        }.onFailure { Log.w(TAG, "parseCubeFile failed for ${file.absolutePath}", it) }
            .getOrNull()
    }

    /**
     * Bake [lut1] + [lut2] into a single chained LUT of [lut1]'s size.
     * For each lattice point: apply lut1 at [intensity1], then apply lut2
     * at [intensity2] on top. The result can be uploaded as one texture.
     *
     * When [bw1]/[bw2] is true (Black & White pack), intensity mixes from
     * achromatic source luma toward the LUT colour — same rule as the GL /
     * apply_macro `lutBwForce` path so colour never bleeds back mid-chain.
     */
    fun chainLuts(
        lut1: ParsedCube, intensity1: Float,
        lut2: ParsedCube, intensity2: Float,
        bw1: Boolean = false,
        bw2: Boolean = false,
    ): ParsedCube {
        val n = lut1.size
        val out = FloatArray(n * n * n * 3)
        val t1 = intensity1.coerceIn(0f, 1f)
        val t2 = intensity2.coerceIn(0f, 1f)
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            val idx = (bi * n * n + gi * n + ri) * 3
            val r0 = ri.toFloat() / (n - 1f)
            val g0 = gi.toFloat() / (n - 1f)
            val b0 = bi.toFloat() / (n - 1f)
            // Apply lut1
            val r1 = lut1.data[idx]; val g1 = lut1.data[idx + 1]; val b1 = lut1.data[idx + 2]
            val (rA, gA, bA) = mixLutSample(r0, g0, b0, r1, g1, b1, t1, bw1)
            // Sample lut2 at rA,gA,bA via trilinear
            val rL = trilinearSample(lut2, rA, gA, bA, 0)
            val gL = trilinearSample(lut2, rA, gA, bA, 1)
            val bL = trilinearSample(lut2, rA, gA, bA, 2)
            val (rO, gO, bO) = mixLutSample(rA, gA, bA, rL, gL, bL, t2, bw2)
            out[idx] = rO; out[idx + 1] = gO; out[idx + 2] = bO
        }
        return ParsedCube(size = n, data = out)
    }

    /** Intensity mix mirroring shader/apply_macro lutBwForce. */
    private fun mixLutSample(
        sr: Float, sg: Float, sb: Float,
        lr: Float, lg: Float, lb: Float,
        t: Float,
        bw: Boolean,
    ): FloatArray {
        if (bw) {
            val y = 0.2627f * sr + 0.6780f * sg + 0.0593f * sb
            return floatArrayOf(y + (lr - y) * t, y + (lg - y) * t, y + (lb - y) * t)
        }
        return floatArrayOf(sr + (lr - sr) * t, sg + (lg - sg) * t, sb + (lb - sb) * t)
    }

    fun trilinearSample(lut: ParsedCube, r: Float, g: Float, b: Float, ch: Int): Float {
        val n = lut.size
        val rf = (r.coerceIn(0f, 1f) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
        val gf = (g.coerceIn(0f, 1f) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
        val bf = (b.coerceIn(0f, 1f) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
        val r0 = rf.toInt().coerceAtMost(n - 2); val r1 = r0 + 1
        val g0 = gf.toInt().coerceAtMost(n - 2); val g1 = g0 + 1
        val b0 = bf.toInt().coerceAtMost(n - 2); val b1 = b0 + 1
        val dr = rf - r0; val dg = gf - g0; val db = bf - b0
        fun s(ri: Int, gi: Int, bi: Int) = lut.data[(bi * n * n + gi * n + ri) * 3 + ch]
        return s(r0,g0,b0)*(1-dr)*(1-dg)*(1-db) + s(r1,g0,b0)*dr*(1-dg)*(1-db) +
               s(r0,g1,b0)*(1-dr)*dg*(1-db)     + s(r1,g1,b0)*dr*dg*(1-db) +
               s(r0,g0,b1)*(1-dr)*(1-dg)*db     + s(r1,g0,b1)*dr*(1-dg)*db +
               s(r0,g1,b1)*(1-dr)*dg*db         + s(r1,g1,b1)*dr*dg*db
    }

    /**
     * Chain multiple parsed LUT layers into a single LUT. Layers are applied
     * bottom-to-top with each layer's own intensity. The returned LUT already
     * encodes the full composite, so the caller should sample it at intensity
     * 1.0 when feeding it to a single-LUT renderer.
     *
     * @param layers Ordered list of (parsedCube, intensity[, isBlackAndWhite]).
     *               The optional third flag defaults to false. Must contain at
     *               least one layer.
     */
    fun chainLuts(layers: List<Pair<ParsedCube, Float>>): ParsedCube {
        require(layers.isNotEmpty()) { "chainLuts requires at least one layer" }
        if (layers.size == 1) return layers[0].first
        var chain = chainLuts(layers[0].first, layers[0].second, layers[1].first, layers[1].second)
        for (i in 2 until layers.size) {
            chain = chainLuts(chain, 1f, layers[i].first, layers[i].second)
        }
        return chain
    }

    fun chainLutsBw(layers: List<Triple<ParsedCube, Float, Boolean>>): ParsedCube {
        require(layers.isNotEmpty()) { "chainLutsBw requires at least one layer" }
        if (layers.size == 1) return layers[0].first
        var chain = chainLuts(
            layers[0].first, layers[0].second,
            layers[1].first, layers[1].second,
            layers[0].third, layers[1].third,
        )
        for (i in 2 until layers.size) {
            chain = chainLuts(
                chain, 1f,
                layers[i].first, layers[i].second,
                bw1 = false, bw2 = layers[i].third,
            )
        }
        return chain
    }

    /**
     * Write a parsed cube to a .cube file in the standard Adobe format.
     */
    fun writeCubeFile(cube: ParsedCube, file: File) {
        file.bufferedWriter().use { w ->
            w.write("LUT_3D_SIZE ${cube.size}\n")
            val n = cube.size
            val data = cube.data
            for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
                val idx = (bi * n * n + gi * n + ri) * 3
                w.write("${data[idx]} ${data[idx + 1]} ${data[idx + 2]}\n")
            }
        }
    }

    /** Copy any bundled .cube files from assets/luts/ into [dst] on first run. */
    private fun seedFromAssets(context: Context, dst: File) {
        val am = context.assets
        val files = runCatching { am.list(ASSETS_DIR) }.getOrNull().orEmpty()
        for (name in files) {
            if (!name.endsWith(".cube", ignoreCase = true)) continue
            val out = File(dst, name)
            runCatching {
                am.open("$ASSETS_DIR/$name").use { ins ->
                    FileOutputStream(out).use { o -> ins.copyTo(o) }
                }
                Log.i(TAG, "seeded $name (${out.length()} bytes)")
            }.onFailure { Log.w(TAG, "seed $name failed: ${it.message}") }
        }
    }
}
