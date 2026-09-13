/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted state for the batch AMOLED screensaver + music feature.
 *
 * Surfaced in Settings → Batch as four controls:
 *   1. Enable screen protection (default ON)
 *   2. Brightness level (Lowest / Low / Medium / Never High)
 *   3. Enable music
 *   4. Music volume (Lowest / Low / Medium / High)
 *
 * Brightness intentionally caps below full — the whole point is to *protect*
 * the AMOLED panel from burn-in during a long batch, so "Never High" maps to
 * ~50 % window brightness, never 1.0.
 */
class BatchProtectionPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("batch_protection_prefs", Context.MODE_PRIVATE)

    enum class BrightnessLevel(val windowBrightness: Float, val label: String) {
        Lowest(0.05f, "Lowest"),
        Low(0.15f, "Low"),
        Medium(0.30f, "Medium"),
        NeverHigh(0.50f, "Never High"),
    }

    enum class VolumeLevel(val gain: Float, val label: String) {
        Lowest(0.10f, "Lowest"),
        Low(0.25f, "Low"),
        Medium(0.50f, "Medium"),
        High(0.85f, "High"),
    }

    var screenProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_PROT, true)
        set(v) = prefs.edit().putBoolean(KEY_SCREEN_PROT, v).apply()

    var brightnessLevel: BrightnessLevel
        get() = BrightnessLevel.entries.find {
            it.name == prefs.getString(KEY_BRIGHTNESS, null)
        } ?: BrightnessLevel.Low
        set(v) = prefs.edit().putString(KEY_BRIGHTNESS, v.name).apply()

    var musicEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC, true)
        set(v) = prefs.edit().putBoolean(KEY_MUSIC, v).apply()

    var volumeLevel: VolumeLevel
        get() = VolumeLevel.entries.find {
            it.name == prefs.getString(KEY_VOLUME, null)
        } ?: VolumeLevel.Medium
        set(v) = prefs.edit().putString(KEY_VOLUME, v.name).apply()

    companion object {
        private const val KEY_SCREEN_PROT = "screen_protection_enabled"
        private const val KEY_BRIGHTNESS  = "brightness_level"
        private const val KEY_MUSIC       = "music_enabled"
        private const val KEY_VOLUME      = "volume_level"
    }
}
