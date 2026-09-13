/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud

import android.content.Context

/**
 * Persists the Sony-Sync cloud setup: which provider, its pasted token, and the
 * currently-selected shared folder (id + name + public link). SharedPreferences
 * is fine here — the token is a short-lived Drive access token, not a password.
 */
class CloudPrefs(context: Context) {
    private val p = context.getSharedPreferences("sony_cloud_prefs", Context.MODE_PRIVATE)

    var provider: String
        get() = p.getString("provider", "gdrive") ?: "gdrive"
        set(v) = p.edit().putString("provider", v).apply()

    var accessToken: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()

    var folderId: String
        get() = p.getString("folderId", "") ?: ""
        set(v) = p.edit().putString("folderId", v).apply()

    var folderName: String
        get() = p.getString("folderName", "") ?: ""
        set(v) = p.edit().putString("folderName", v).apply()

    var shareLink: String
        get() = p.getString("shareLink", "") ?: ""
        set(v) = p.edit().putString("shareLink", v).apply()

    // ── "Lenses you brought" kit, saved PER cloud folder so each shoot's
    //    destination remembers its own kit. Keyed by the Drive folder id.
    fun lensKit(folderId: String): String =
        if (folderId.isBlank()) "" else p.getString("lensKit_$folderId", "") ?: ""

    fun setLensKit(folderId: String, blob: String) {
        if (folderId.isBlank()) return
        p.edit().putString("lensKit_$folderId", blob).apply()
    }

    // ── Sony Sync screen sticky options ─────────────────────────────────────
    // Saved on every user change so the next session restores the last choice.

    /** SonyConnectionType ordinal. Default 0 (AUTO). */
    var connectionTypeOrdinal: Int
        get() = p.getInt("sony_connection_type", 0)
        set(v) = p.edit().putInt("sony_connection_type", v).apply()

    /** SonyCameraRemoteApi.ImportSize ordinal. Default 0 (ORIGINAL). */
    var importSizeOrdinal: Int
        get() = p.getInt("sony_import_size", 0)
        set(v) = p.edit().putInt("sony_import_size", v).apply()

    // ── Sony Cloud Upload (SonyCloudController) sticky options ──────────────

    /** Last selected RAZBatch preset name, "" = None. */
    var cloudPresetName: String
        get() = p.getString("cloud_preset_name", "") ?: ""
        set(v) = p.edit().putString("cloud_preset_name", v).apply()

    /** Last selected watermark name, "" = None. */
    var cloudWatermarkName: String
        get() = p.getString("cloud_watermark_name", "") ?: ""
        set(v) = p.edit().putString("cloud_watermark_name", v).apply()
}
