/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Optical look sidecar for User's Lut entries.
 *
 * A 3D .cube is photometric only (colour + tone). Grain and halation / glow
 * cannot live inside a LUT (pixel-independent map). When LUT Creator saves a
 * look, optional film-grain + glow parameters are written beside the cube as
 * `<name>.look.json` and applied when the user picks that entry in the RAW
 * LUT tab — mapped onto existing UserMacro fields (filmGrain*, fxGlow*).
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.lut_creator

import org.json.JSONObject
import java.io.File

object LutOpticalSidecar {

    const val SUFFIX = ".look.json"
    private const val VERSION = 1

    data class Params(
        val filmGrain: Float = 0f,
        val filmGrainSize: Float = 0.5f,
        val filmGrainWashOut: Float = 0f,
        /** UI units 0..100 (same as UserMacro.fxGlowStrength). */
        val fxGlowStrength: Float = 0f,
        /** UI units 0..100. */
        val fxGlowSpread: Float = 0f,
        /** UI units −50..+50. */
        val fxGlowWarmth: Float = 0f,
    ) {
        val isEmpty: Boolean
            get() = filmGrain <= 1e-4f &&
                fxGlowStrength <= 1e-4f &&
                filmGrainWashOut <= 1e-4f
    }

    fun pathBesideCube(cubePath: String): String {
        val f = File(cubePath)
        val base = f.nameWithoutExtension
        return File(f.parentFile ?: File("."), base + SUFFIX).absolutePath
    }

    fun writeBesideCube(cubePath: String, params: Params) {
        if (params.isEmpty) {
            deleteBesideCube(cubePath)
            return
        }
        val out = File(pathBesideCube(cubePath))
        val json = JSONObject()
            .put("version", VERSION)
            .put("filmGrain", params.filmGrain.toDouble())
            .put("filmGrainSize", params.filmGrainSize.toDouble())
            .put("filmGrainWashOut", params.filmGrainWashOut.toDouble())
            .put("fxGlowStrength", params.fxGlowStrength.toDouble())
            .put("fxGlowSpread", params.fxGlowSpread.toDouble())
            .put("fxGlowWarmth", params.fxGlowWarmth.toDouble())
        out.writeText(json.toString(2))
    }

    fun readBesideCube(cubePath: String): Params? {
        val f = File(pathBesideCube(cubePath))
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            Params(
                filmGrain = o.optDouble("filmGrain", 0.0).toFloat().coerceIn(0f, 1f),
                filmGrainSize = o.optDouble("filmGrainSize", 0.5).toFloat().coerceIn(0f, 1f),
                filmGrainWashOut = o.optDouble("filmGrainWashOut", 0.0).toFloat().coerceIn(0f, 1f),
                fxGlowStrength = o.optDouble("fxGlowStrength", 0.0).toFloat().coerceIn(0f, 100f),
                fxGlowSpread = o.optDouble("fxGlowSpread", 0.0).toFloat().coerceIn(0f, 100f),
                fxGlowWarmth = o.optDouble("fxGlowWarmth", 0.0).toFloat().coerceIn(-50f, 50f),
            )
        }.getOrNull()
    }

    fun deleteBesideCube(cubePath: String) {
        runCatching { File(pathBesideCube(cubePath)).delete() }
    }
}
