/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * .3dl 3D-LUT importer (Autodesk Lustre/Flame, Nuke, Resolve export).
 *
 * Text format:
 *   • optional comment lines (#) and an optional "Mesh <bd> <out>" / "3DMESH"
 *     header, then an optional shaper line (the input sample positions — its
 *     token count == the cube edge N, e.g. 17 values → 17³);
 *   • N³ lines of three integers "R G B", scaled to the output bit depth
 *     (0–1023 = 10-bit, 0–4095 = 12-bit, 0–65535 = 16-bit);
 *   • entry order is BLUE-fastest: line index = r·N² + g·N + b
 *     (per the Lustre/Flame spec offset = (r<<2·bd) + (g<<bd) + b).
 *
 * We re-emit as an Adobe .cube (RED-fastest: b·N² + g·N + r), normalised to
 * [0,1], matching the sRGB pipeline. Returns null on a malformed file.
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import kotlin.math.cbrt
import kotlin.math.roundToInt

object ThreeDlConverter {

    private val WS = Regex("\\s+")
    private val INT = Regex("\\d+")

    fun convertToCubeString(text: String): String? {
        var meshN = -1                 // N from "Mesh <bd>" header (2^bd + 1)
        var shaperN = -1               // N from the shaper line (token count)
        val data = ArrayList<IntArray>(40000)

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val lower = line.lowercase()
            if (lower.startsWith("mesh") || lower.startsWith("3dmesh")) {
                if (lower.startsWith("mesh")) {
                    val n = INT.find(line)?.value?.toIntOrNull()
                    if (n != null && n in 1..14) meshN = (1 shl n) + 1   // 2^bd + 1
                }
                continue
            }
            val toks = line.split(WS).mapNotNull { it.toIntOrNull() }
            when {
                toks.size == 3 -> data.add(intArrayOf(toks[0], toks[1], toks[2]))
                toks.size > 3 && shaperN < 0 -> shaperN = toks.size       // shaper → N
                // anything else (stray header text) is ignored
            }
        }
        if (data.isEmpty()) return null

        val n = when {
            meshN in 2..256 && meshN * meshN * meshN == data.size   -> meshN
            shaperN in 2..256 && shaperN * shaperN * shaperN == data.size -> shaperN
            else -> cbrt(data.size.toDouble()).roundToInt()
        }
        if (n < 2 || n.toLong() * n * n != data.size.toLong()) return null

        // Normalisation divisor — smallest standard max ≥ the observed peak.
        var maxV = 0
        for (t in data) { if (t[0] > maxV) maxV = t[0]; if (t[1] > maxV) maxV = t[1]; if (t[2] > maxV) maxV = t[2] }
        val div = when {
            maxV <= 1     -> 1f          // already 0..1 (rare float .3dl)
            maxV <= 255   -> 255f
            maxV <= 1023  -> 1023f
            maxV <= 4095  -> 4095f
            maxV <= 16383 -> 16383f
            else          -> 65535f
        }

        // Re-emit RED-fastest (.cube order) from BLUE-fastest source (.3dl order).
        val sb = StringBuilder(n * n * n * 22 + 32)
        sb.append("LUT_3D_SIZE ").append(n).append('\n')
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val k = (r * n + g) * n + b          // .3dl line index (blue fastest)
            val t = data[k]
            sb.append(fmt(t[0] / div)).append(' ')
              .append(fmt(t[1] / div)).append(' ')
              .append(fmt(t[2] / div)).append('\n')
        }
        return sb.toString()
    }

    private fun fmt(v: Float): String {
        val c = if (v < 0f) 0f else if (v > 1f) 1f else v
        return "%.6f".format(c)
    }
}
