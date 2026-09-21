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
    val galleryWorkspace: Boolean get() = BuildConfig.FEATURE_GALLERY_WORKSPACE
    val openAllowlistOnly: Boolean get() = BuildConfig.FEATURE_OPEN_ALLOWLIST_ONLY

    fun includeOnHome(screen: Screen): Boolean {
        if (screen is Screen.LutCreator && !lutCreator) return false
        if (screen is Screen.VideoEditor && !videoEditor) return false
        if (!galleryWorkspace && (
                screen is Screen.GalleryWorkspace ||
                    screen is Screen.AddToProject ||
                    screen is Screen.GalleryProject
            )
        ) return false
        return true
    }

    /** Open keeps normal tools wired; private unstable tools are omitted above. */
    fun isHomeWired(screen: Screen): Boolean {
        if (!includeOnHome(screen)) return false
        return true
    }

    /**
     * Open keeps the normal RAW editor wired but exposes LUT and LUT Adj as
     * disabled chrome. Their implementations are removed by export-open.ps1.
     */
    fun isRawTabWired(tabId: Int): Boolean {
        if (!openAllowlistOnly) return true
        return tabId != 11 && tabId != 13
    }
}
