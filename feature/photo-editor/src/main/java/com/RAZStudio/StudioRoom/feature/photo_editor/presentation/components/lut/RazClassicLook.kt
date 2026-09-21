/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

/**
 * Owned RAZ Looks entry: the FX Vintage "Classic" chip, listed on the LUT tab.
 * Not a .cube — applies the same vintage + Mist (PNG) macro as the old FX button.
 */
internal object RazClassicLook {
    const val CATEGORY = "RAZ Looks"
    const val DISPLAY_NAME = "RAZ Classic"
    const val SENTINEL = "luts/RAZ Looks/RAZ_Classic.look"

    fun entry(): LutEntry = LutEntry(
        name = DISPLAY_NAME,
        assetPath = SENTINEL,
        filePath = null,
    )

    fun isSentinel(entry: LutEntry): Boolean =
        entry.assetPath == SENTINEL || entry.name.equals(DISPLAY_NAME, ignoreCase = true)

    fun isApplied(m: UserMacro): Boolean =
        m.fxVintageStrength >= 35f && m.fxVintageMistIntensity >= 10f

    fun apply(m: UserMacro, hasMistPng: Boolean): UserMacro = m.copy(
        fxVintageStrength = 40f,
        fxVintageFade = 25f,
        fxVintageVig = 0f,
        fxVintageMistIntensity = if (hasMistPng) 20f else m.fxVintageMistIntensity,
        fxVintageMistScale = 1f,
        fxMistWarmth = 10f,
    )
}
