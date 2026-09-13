/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * App-level device identity override.
 *
 * Stores custom model/brand/device strings in SharedPreferences so the app
 * reports a different identity to analytics and crash reports without touching
 * the OS. Cleared automatically when the user clears app data.
 *
 * Set via ADB (no root required):
 *   adb shell am broadcast -a com.RAZStudio.StudioRoom.SET_DEVICE_IDENTITY \
 *       --es model "Pixel 9 Pro" \
 *       --es brand "google" \
 *       --es device "caiman"
 *
 * Clear override (revert to real Build.* values):
 *   adb shell am broadcast -a com.RAZStudio.StudioRoom.SET_DEVICE_IDENTITY
 *
 * The receiver is registered in StudioRoomApplication and only responds to
 * broadcasts sent from the same UID or shell — no external app can spoof it.
 */

package com.RAZStudio.StudioRoom.core.ui.utils.helper

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object DeviceIdentityOverride {

    private const val PREFS_NAME = "device_identity_override"
    private const val KEY_MODEL  = "model"
    private const val KEY_BRAND  = "brand"
    private const val KEY_DEVICE = "device"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Effective model string — override if set, real Build.MODEL otherwise. */
    val model: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_MODEL, null) ?: Build.MODEL
                else Build.MODEL

    /** Effective brand string — override if set, real Build.BRAND otherwise. */
    val brand: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_BRAND, null) ?: Build.BRAND
                else Build.BRAND

    /** Effective device string — override if set, real Build.DEVICE otherwise. */
    val device: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_DEVICE, null) ?: Build.DEVICE
                else Build.DEVICE

    /** True when at least one field is overridden. */
    val isActive: Boolean
        get() = if (::prefs.isInitialized)
            prefs.contains(KEY_MODEL) || prefs.contains(KEY_BRAND) || prefs.contains(KEY_DEVICE)
        else false

    /**
     * Set override values. Pass null for any field to leave it at the real Build value.
     * Persists across app restarts; cleared only by clearing app data or calling [clear].
     */
    fun set(model: String? = null, brand: String? = null, device: String? = null) {
        if (!::prefs.isInitialized) return
        prefs.edit().apply {
            if (model  != null) putString(KEY_MODEL,  model)  else remove(KEY_MODEL)
            if (brand  != null) putString(KEY_BRAND,  brand)  else remove(KEY_BRAND)
            if (device != null) putString(KEY_DEVICE, device) else remove(KEY_DEVICE)
        }.apply()
    }

    /** Remove all overrides — reverts to real Build.* values immediately. */
    fun clear() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY_MODEL).remove(KEY_BRAND).remove(KEY_DEVICE).apply()
    }
}
