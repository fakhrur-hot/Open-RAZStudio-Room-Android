/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.core.database.model

/**
 * Stack identity for [com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity.stackKey].
 *
 * A RAW+JPEG pair out of the camera differs only by extension, so the basename
 * is the whole rule — deliberately NOT capture time, which would also stack
 * unrelated frames shot in the same second on a burst.
 */
object StackKeys {

    /** "DSC08956.ARW" → "dsc08956"; a name with no extension is used as-is. */
    fun of(displayName: String): String {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return ""
        val dot = trimmed.lastIndexOf('.')
        val base = if (dot > 0) trimmed.substring(0, dot) else trimmed
        return base.lowercase().take(120)
    }
}
