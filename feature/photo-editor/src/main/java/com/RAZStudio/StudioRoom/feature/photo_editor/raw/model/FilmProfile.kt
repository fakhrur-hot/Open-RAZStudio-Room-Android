/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

/**
 * Film simulation profile. Selected upfront in WorkspaceSelectorSheet before
 * entering the editor ("Commit on Open"). Changing profile requires
 * back-navigation + re-decode.
 *
 * DEFAULT = standard linear pass-through (no film sim applied).
 * Ordinal maps directly to the C++ ProfileIndex enum — do not reorder.
 */
enum class FilmProfile(val id: String, val label: String) {
    DEFAULT("default",             "Standard Linear"),
    CLASSIC_NEG("classic_neg",     "Classic Negative"),
    VELVIA("velvia",               "Velvia (Vivid)"),
    PROVIA("provia",               "Provia (Standard)"),
    ACROS("acros",                 "Acros (Monochrome)"),
    CLASSIC_CHROME("classic_chrome", "Classic Chrome"),
    ASTIA("astia",                 "Astia (Soft)"),
    ETERNA("eterna",               "Eterna (Cinema)"),
    // ── X-Trans II / III / IV / V additions — ordinals 8–14, do not reorder ──
    PRO_NEG_STD("pro_neg_std",         "Pro Neg Std"),
    PRO_NEG_HI("pro_neg_hi",           "Pro Neg Hi"),
    ETERNA_BLEACH("eterna_bleach",     "Bleach Bypass"),
    NOSTALGIC_NEG("nostalgic_neg",     "Nostalgic Neg"),
    REALA_ACE("reala_ace",             "Reala Ace"),
    ACROS_Y("acros_y",                 "Acros+Y"),
    ACROS_R("acros_r",                 "Acros+R"),
    ACROS_G("acros_g",                 "Acros+G");

    companion object {
        fun fromId(id: String?): FilmProfile =
            entries.find { it.id == id } ?: DEFAULT
    }
}

/**
 * Film grain intensity applied alongside the film profile bake.
 * OFF = 0.0 amplitude; C++ grain pass is skipped when grain_amount == 0.
 *
 * [value] maps directly to the grainAmount float parameter in the C++ kernel.
 * Serialized by [id], never by ordinal.
 */
enum class FilmGrainLevel(val id: String, val label: String, val value: Float) {
    OFF("off",           "Off",      0.00f),
    FINE("fine",         "Fine",     0.02f),
    STANDARD("standard", "Standard", 0.04f),
    COARSE("coarse",     "Coarse",   0.07f);

    companion object {
        fun fromId(id: String?): FilmGrainLevel =
            entries.find { it.id == id } ?: OFF
    }
}
