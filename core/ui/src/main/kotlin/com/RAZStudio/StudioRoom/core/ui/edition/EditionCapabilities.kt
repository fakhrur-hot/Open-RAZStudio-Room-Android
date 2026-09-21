/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */
package com.RAZStudio.StudioRoom.core.ui.edition

import com.RAZStudio.StudioRoom.core.ui.BuildConfig
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen

/**
 * Private vs Open vs hardened capabilities. Values come from this module's
 * BuildConfig (debug vs release/hardened). Open export overwrites the
 * OPEN_ALLOWLIST / PRIVATE_TRIAL fields.
 */
object EditionCapabilities {
    val privateTrial: Boolean get() = BuildConfig.EDITION_PRIVATE_TRIAL
    val lutCreator: Boolean get() = BuildConfig.FEATURE_LUT_CREATOR
    val videoEditor: Boolean get() = BuildConfig.FEATURE_VIDEO_EDITOR
    val openAllowlistOnly: Boolean get() = BuildConfig.FEATURE_OPEN_ALLOWLIST_ONLY

    fun includeOnHome(screen: Screen): Boolean {
        if (screen is Screen.LutCreator && !lutCreator) return false
        if (screen is Screen.VideoEditor && !videoEditor) return false
        return true
    }

    /** Open: only these home entries stay clickable. Others are grey chrome. */
    fun isHomeWired(screen: Screen): Boolean {
        if (!includeOnHome(screen)) return false
        if (!openAllowlistOnly) return true
        return when (screen) {
            is Screen.GalleryWorkspace,
            is Screen.RawEditor,
            is Screen.CanonSync,
            is Screen.SonySync -> true
            else -> false
        }
    }

    /**
     * RAW editor tab ids from RawAdjustmentPanel. Open keeps LUT / LUT Adj /
     * Curves / FX (halation). Others stay visible but disabled.
     */
    fun isRawTabWired(tabId: Int): Boolean {
        if (!openAllowlistOnly) return true
        // Actions (XMP), Curves, LUT, LUT Adj, FX (halation)
        return tabId == 6 || tabId == 2 || tabId == 11 || tabId == 13 || tabId == 4
    }
}
