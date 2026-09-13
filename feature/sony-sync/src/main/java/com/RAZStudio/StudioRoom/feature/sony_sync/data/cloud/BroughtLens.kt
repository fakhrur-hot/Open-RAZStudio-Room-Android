/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud

/**
 * One row of the "Lenses you brought" kit used by the Share-to-Cloud processing.
 *
 * The camera body is auto-detected from EXIF (for the sensor crop factor), but an
 * adapted lens rarely reports a usable name/profile — so the user pre-declares the
 * handful of lenses they brought. At ingest each snapped photo's EXIF lens focal
 * range (LensSpecification → LensModel) is matched to one enabled row.
 *
 * @param enabled  row is part of this shoot's kit (matched against).
 * @param name     display name — free text (autocompleted from the watermark lens
 *                 DB); naming only, overwrites the uploaded JPEG's EXIF lens tag +
 *                 the burned watermark.
 * @param profile  a real Lensfun "Maker Model" string (brand + type merged,
 *                 autocompleted from the Lensfun DB); drives the optical
 *                 correction and supplies the focal range used for matching.
 * @param manual   this is the ONE manual/adapted lens — used when a photo carries
 *                 no lens EXIF (no aperture / f0). At most one row may set this.
 */
data class BroughtLens(
    val enabled: Boolean = false,
    val name: String = "",
    val profile: String = "",
    val manual: Boolean = false,
)

/** Fixed kit size — 10 declarable lenses. */
const val LENS_KIT_SIZE = 10

/** A full kit is always exactly [LENS_KIT_SIZE] rows (blanks for unused). */
fun emptyLensKit(): List<BroughtLens> = List(LENS_KIT_SIZE) { BroughtLens() }

// ── Persistence: tab-separated fields, newline-separated rows. Lens names and
//    Lensfun model strings never contain tabs or newlines, so this stays simple
//    and dependency-free (no JSON lib in the sony-sync module).

fun List<BroughtLens>.encodeLensKit(): String = joinToString("\n") { r ->
    listOf(
        if (r.enabled) "1" else "0",
        if (r.manual) "1" else "0",
        r.name.replace('\t', ' ').replace('\n', ' '),
        r.profile.replace('\t', ' ').replace('\n', ' '),
    ).joinToString("\t")
}

fun decodeLensKit(blob: String?): List<BroughtLens> {
    val rows = blob?.takeIf { it.isNotBlank() }
        ?.split('\n')
        ?.map { line ->
            val f = line.split('\t')
            BroughtLens(
                enabled = f.getOrNull(0) == "1",
                manual = f.getOrNull(1) == "1",
                name = f.getOrNull(2).orEmpty(),
                profile = f.getOrNull(3).orEmpty(),
            )
        }
        ?: emptyList()
    return List(LENS_KIT_SIZE) { rows.getOrElse(it) { BroughtLens() } }
}
