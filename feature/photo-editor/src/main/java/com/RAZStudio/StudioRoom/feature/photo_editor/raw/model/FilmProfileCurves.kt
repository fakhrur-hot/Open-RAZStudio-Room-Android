/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

/**
 * Per-profile tone-curve presets injected into the action stack on fresh open.
 *
 * Format: List of 4 curves [master, R, G, B].
 * Each curve is 5 Y-values at fixed X = {0, 0.25, 0.5, 0.75, 1.0}.
 * Identity = [0, 0.25, 0.50, 0.75, 1.0].
 *
 * These run through the existing 256-LUT pipeline (Stage B + Stage C) on top of
 * the film_sim.cpp matrix pass, giving the profile a proper tonal character that
 * a single power-law exponent cannot express.
 */
object FilmProfileCurves {

    private val ID = UserMacro.DEFAULT_CURVE_POINTS

    fun forProfile(profile: FilmProfile): List<List<Float>>? = when (profile) {

        FilmProfile.DEFAULT -> null  // no injection for standard linear

        FilmProfile.CLASSIC_NEG -> listOf(
            // Master: moderate S-curve — lifted shadows, slight shoulder rolloff
            listOf(0.04f, 0.27f, 0.50f, 0.72f, 0.96f),
            // R: pulled back slightly — cooler, less warm
            listOf(0.00f, 0.23f, 0.48f, 0.72f, 0.95f),
            // G: restrained — muted greens
            listOf(0.00f, 0.22f, 0.46f, 0.70f, 0.94f),
            // B: pushed in shadows — cold cyan shadow cast
            listOf(0.05f, 0.28f, 0.51f, 0.73f, 0.96f),
        )

        FilmProfile.VELVIA -> listOf(
            // Master: strong S-curve — deep blacks, punchy mids, rolloff highlights
            listOf(0.00f, 0.22f, 0.52f, 0.78f, 1.00f),
            // R: boosted — vivid reds/oranges
            listOf(0.00f, 0.26f, 0.54f, 0.80f, 1.00f),
            // G: slight boost — saturated greens
            listOf(0.00f, 0.24f, 0.52f, 0.78f, 1.00f),
            // B: boosted in shadows, rolled off highlights — rich deep blues
            listOf(0.00f, 0.26f, 0.52f, 0.76f, 0.98f),
        )

        FilmProfile.PROVIA -> listOf(
            // Master: gentle S-curve — balanced, natural reference
            listOf(0.00f, 0.24f, 0.50f, 0.76f, 1.00f),
            // R: near identity
            listOf(0.00f, 0.25f, 0.50f, 0.75f, 1.00f),
            // G: near identity
            listOf(0.00f, 0.25f, 0.50f, 0.75f, 1.00f),
            // B: very slight lift — clean neutral blues
            listOf(0.01f, 0.25f, 0.50f, 0.75f, 1.00f),
        )

        FilmProfile.ACROS -> listOf(
            // Master: strong contrast S-curve — zone-system B&W character
            listOf(0.00f, 0.20f, 0.50f, 0.80f, 1.00f),
            // R/G/B identity — panchromatic collapse already done in C++
            ID[1], ID[2], ID[3],
        )

        FilmProfile.CLASSIC_CHROME -> listOf(
            // Master: flat — wide tonal range, subdued contrast
            listOf(0.03f, 0.26f, 0.50f, 0.73f, 0.96f),
            // R: warm pull — slight red suppression
            listOf(0.02f, 0.25f, 0.49f, 0.72f, 0.95f),
            // G: desaturated greens — muted documentary
            listOf(0.02f, 0.24f, 0.48f, 0.71f, 0.94f),
            // B: cool push — documentary blue cast
            listOf(0.03f, 0.27f, 0.51f, 0.74f, 0.97f),
        )

        FilmProfile.ASTIA -> listOf(
            // Master: gentle S — soft, flattering tones
            listOf(0.00f, 0.25f, 0.51f, 0.76f, 1.00f),
            // R: very slight boost — warm skin
            listOf(0.00f, 0.26f, 0.51f, 0.76f, 1.00f),
            // G: boosted mids — vibrant secondaries
            listOf(0.00f, 0.26f, 0.52f, 0.77f, 1.00f),
            // B: boosted — vivid blues/purples
            listOf(0.00f, 0.26f, 0.52f, 0.77f, 1.00f),
        )

        FilmProfile.ETERNA -> listOf(
            // Master: flat log-like — lifted blacks, rolled-off highlights
            listOf(0.06f, 0.28f, 0.50f, 0.70f, 0.92f),
            // R: warm shadow lift
            listOf(0.05f, 0.27f, 0.50f, 0.70f, 0.92f),
            // G: neutral
            listOf(0.05f, 0.27f, 0.50f, 0.70f, 0.92f),
            // B: slightly lifted blacks — cinematic cool teal in shadows
            listOf(0.07f, 0.29f, 0.51f, 0.71f, 0.92f),
        )

        FilmProfile.PRO_NEG_STD -> listOf(
            // Master: very flat — studio portrait, wide tonal range
            listOf(0.04f, 0.27f, 0.50f, 0.73f, 0.96f),
            // R/G/B near identity — accurate skin, no cast
            listOf(0.03f, 0.26f, 0.50f, 0.74f, 0.97f),
            listOf(0.03f, 0.26f, 0.50f, 0.74f, 0.97f),
            listOf(0.03f, 0.26f, 0.50f, 0.74f, 0.97f),
        )

        FilmProfile.PRO_NEG_HI -> listOf(
            // Master: modest S — crisper than Std, controlled
            listOf(0.00f, 0.24f, 0.50f, 0.76f, 1.00f),
            listOf(0.00f, 0.25f, 0.50f, 0.75f, 1.00f),
            listOf(0.00f, 0.25f, 0.50f, 0.75f, 1.00f),
            listOf(0.00f, 0.24f, 0.50f, 0.75f, 1.00f),
        )

        FilmProfile.ETERNA_BLEACH -> listOf(
            // Master: very hard S — bleach bypass crush
            listOf(0.00f, 0.18f, 0.50f, 0.82f, 1.00f),
            // R: pulled slightly — silver-cool look
            listOf(0.00f, 0.20f, 0.50f, 0.80f, 1.00f),
            listOf(0.00f, 0.20f, 0.50f, 0.80f, 1.00f),
            // B: slightly cooler toe
            listOf(0.00f, 0.19f, 0.50f, 0.81f, 1.00f),
        )

        FilmProfile.NOSTALGIC_NEG -> listOf(
            // Master: lifted shadows, compressed highlights — 70s faded film
            listOf(0.05f, 0.28f, 0.51f, 0.72f, 0.94f),
            // R: warm push
            listOf(0.04f, 0.28f, 0.52f, 0.74f, 0.96f),
            // G: neutral
            listOf(0.04f, 0.27f, 0.50f, 0.72f, 0.93f),
            // B: suppressed — warm overall
            listOf(0.03f, 0.25f, 0.48f, 0.70f, 0.91f),
        )

        FilmProfile.REALA_ACE -> listOf(
            // Master: print-film response — deep shadows, hard highlight shoulder
            listOf(0.00f, 0.22f, 0.50f, 0.77f, 1.00f),
            listOf(0.00f, 0.23f, 0.50f, 0.76f, 1.00f),
            listOf(0.00f, 0.23f, 0.50f, 0.76f, 1.00f),
            // B: slight boost — clean neutral print
            listOf(0.00f, 0.24f, 0.51f, 0.77f, 1.00f),
        )

        FilmProfile.ACROS_Y -> listOf(
            // Master: punchy B&W — yellow filter boosts warm tones, darkens sky
            listOf(0.00f, 0.21f, 0.50f, 0.79f, 1.00f),
            ID[1], ID[2], ID[3],
        )

        FilmProfile.ACROS_R -> listOf(
            // Master: dramatic B&W — red filter, very dark sky
            listOf(0.00f, 0.19f, 0.50f, 0.81f, 1.00f),
            ID[1], ID[2], ID[3],
        )

        FilmProfile.ACROS_G -> listOf(
            // Master: natural B&W — green filter, bright foliage
            listOf(0.00f, 0.22f, 0.50f, 0.78f, 1.00f),
            ID[1], ID[2], ID[3],
        )
    }
}
