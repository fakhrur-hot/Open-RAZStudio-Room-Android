/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Maps Black Pro-Mist style density tiers onto UserMacro fields that drive
 * the native Karis / Orton / FX Glow filmic path. Pixel math stays in
 * shader_sources.cpp + apply_macro.cpp — this object only picks params.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import kotlin.math.sqrt

/**
 * Pro-Mist density → UserMacro. Does not run OpenGL; the native pipeline
 * reads [UserMacro.mistTightness] / [UserMacro.mistHalation] via ShaderParams
 * slots [447]/[448] after [RawV3ActionReplay].
 */
object CinematicBloomProcessor {

    /**
     * Reference Stage-A long side for density scaling. Soft scale keeps big
     * files from washing out and small ones from exploding. Baseline is 1×
     * (not 2×) so chip labels 1/16…1/2 track visual halo extent.
     */
    const val REF_LONG_SIDE = 2048

    enum class Tier(val label: String) {
        NONE("Off"),
        PRO_MIST_1_16("1/16"),
        PRO_MIST_1_8("1/8"),
        PRO_MIST_1_4("1/4"),
        PRO_MIST_1_2("1/2"),
    }

    private data class TierParams(
        val ortonUi: Float,
        val radius: Float,
        val tightUi: Float,
        val halaUi: Float,
        val glowUi: Float,
    )

    /**
     * Soft-scaled by Stage A long side so bloom stays comparable across
     * resolutions (sqrt curve, clamped). No extra 2× — that maxed 1/8 on
     * typical RAWs and made halation look ~1/3 of frame.
     */
    fun densityMul(imageLongSide: Int): Float {
        return sqrt(
            imageLongSide.coerceAtLeast(1).toFloat() / REF_LONG_SIDE.toFloat(),
        ).coerceIn(0.75f, 1.35f)
    }

    /**
     * @param strengthUi Bloom Strength dial 0..5 (same as FX tab); when null,
     *   each tier picks a default strength (already density-scaled).
     * @param imageLongSide Stage A max(width,height); used to scale density.
     */
    fun apply(
        macro: UserMacro,
        tier: Tier,
        strengthUi: Float? = null,
        imageLongSide: Int = REF_LONG_SIDE,
    ): UserMacro {
        if (tier == Tier.NONE) {
            return macro.copy(
                ortonStrength = 0f,
                mistTightness = 55f,
                mistHalation = 0f,
                fxGlowStrength = 0f,
                fxGlowSpread = 0f,
                // Warmth stays user-owned — Off does not force a warm bias.
                cinematicMistTier = 0,
                subjectBloom = if (macro.bloomExcludeSubject) 0f else macro.subjectBloom,
            )
        }
        val mul = densityMul(imageLongSide)
        // Radii / halation tuned so labeled density ≈ relative halo extent
        // (1/16 tiny … 1/2 wide). Tightness still pin-point→open (mip bias).
        // bloomRadius also drives the soft-diffusion Gaussian (uBlurTex).
        val p = when (tier) {
            Tier.NONE -> error("unreachable")
            Tier.PRO_MIST_1_16 -> TierParams(1.0f, 2.5f, 88f, 8f, 5f)
            Tier.PRO_MIST_1_8 -> TierParams(1.6f, 4.0f, 78f, 14f, 9f)
            Tier.PRO_MIST_1_4 -> TierParams(2.5f, 7.0f, 58f, 22f, 16f)
            Tier.PRO_MIST_1_2 -> TierParams(3.5f, 11f, 40f, 32f, 24f)
        }
        val defaultOrtonUi = (p.ortonUi * mul).coerceIn(0f, 5f)
        val strength = (strengthUi ?: defaultOrtonUi).coerceIn(0f, 5f)
        val glowUi = (p.glowUi * mul).coerceIn(0f, 100f)
        val halaUi = (p.halaUi * mul.coerceAtMost(1.35f)).coerceIn(0f, 100f)
        val radius = (p.radius * mul).coerceIn(1f, 24f)
        return macro.copy(
            ortonStrength = (strength / 5f).coerceIn(0f, 1f),
            bloomRadius = radius,
            mistTightness = p.tightUi,
            mistHalation = halaUi,
            // Glow strength is driven by density chips (fused into Bloom).
            fxGlowStrength = glowUi,
            fxGlowSpread = (halaUi * 0.35f).coerceIn(0f, 100f),
            // Default warmth neutral — user slider only.
            fxGlowWarmth = macro.fxGlowWarmth,
            cinematicMistTier = tier.ordinal,
            subjectBloom = if (macro.bloomExcludeSubject) {
                (strength / 5f).coerceIn(0f, 1f) * 0.10f
            } else {
                macro.subjectBloom
            },
        )
    }

    fun activeTier(macro: UserMacro): Tier {
        val t = macro.cinematicMistTier
        return Tier.entries.getOrElse(t) { Tier.NONE }
    }
}
