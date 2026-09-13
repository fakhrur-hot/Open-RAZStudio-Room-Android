/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.core.database.model

/**
 * Wire format for [com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity.keywords]:
 * `|tag|tag|` with lower-cased, trimmed tags and no empties. The leading and
 * trailing bars are what make `LIKE '%|sunset|%'` a whole-tag match instead of
 * a substring one ("sun" must not match "sunset").
 *
 * Display case is not preserved — keywords are matching tokens, and one photo
 * tagged "Sunset" plus another "sunset" being two different tags would be a bug,
 * not a feature.
 */
object Keywords {

    const val MAX_PER_PHOTO = 32
    const val MAX_LENGTH = 40

    /** Normalise one tag; returns null when nothing usable is left. */
    fun normalise(raw: String): String? = raw.trim()
        .replace('|', ' ')
        .replace(Regex("\\s+"), " ")
        .lowercase()
        .take(MAX_LENGTH)
        .ifBlank { null }

    /** `|a|b|` → ["a", "b"]; blank/invalid → empty list. */
    fun decode(stored: String?): List<String> =
        stored.orEmpty().split('|').mapNotNull { it.trim().ifBlank { null } }

    /** ["b", "A", "b"] → `|a|b|` (normalised, de-duplicated, sorted, capped). */
    fun encode(tags: Collection<String>): String {
        val clean = tags.mapNotNull(::normalise).distinct().sorted().take(MAX_PER_PHOTO)
        return if (clean.isEmpty()) "" else clean.joinToString("|", prefix = "|", postfix = "|")
    }

    /** Add [tags] to an existing stored value. */
    fun plus(stored: String?, tags: Collection<String>): String =
        encode(decode(stored) + tags)

    /** Remove [tags] from an existing stored value. */
    fun minus(stored: String?, tags: Collection<String>): String {
        val drop = tags.mapNotNull(::normalise).toSet()
        return encode(decode(stored).filterNot { it in drop })
    }
}
