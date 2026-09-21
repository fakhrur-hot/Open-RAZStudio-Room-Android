/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * MLSidecar and ML dual-ISO support are retired in this version. This file stays
 * as a compatibility stub so callers fail closed to the EXIF-only code path.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import android.content.Context
import android.net.Uri
import com.RAZStudio.StudioRoom.core.utils.AppLog

private const val TAG = "MlSidecar"

object MlSidecarParser {
    const val MAX_SIDECAR_SIZE_BYTES = 1_048_576L
    const val CURRENT_FORMAT_VERSION = 2

    fun parseShotSidecar(json: String, expectedFilename: String? = null): MlShotSidecar? {
        if (json.isBlank()) return null
        AppLog.i(TAG, "MLSidecar retired: parseShotSidecar rejected for '$expectedFilename'")
        return null
    }

    fun parseExportSession(json: String): MlExportSession? {
        if (json.isBlank()) return null
        AppLog.i(TAG, "MLSidecar retired: parseExportSession rejected")
        return null
    }

    fun isValidShotSidecar(json: String, expectedFilename: String? = null): Boolean = false
    fun isValidExportSession(json: String): Boolean = false

    fun discover(context: Context, cr2Uri: Uri): MlShotSidecar? {
        AppLog.i(TAG, "MLSidecar retired: discover rejected for ${cr2Uri.lastPathSegment}")
        return null
    }
}

