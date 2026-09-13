/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Lens name database + autocomplete for the EXIF watermark.
 *
 * The full lens names (brand-prefixed, focal length in mm + aperture) live in the
 * `assets/lens_database.txt` asset, one per line, compiled from Wikipedia lens
 * lists (Canon EF/RF/EF-M/FD, Nikon Z, Sony E/FE, Fujifilm, Micro Four Thirds,
 * Tokina, Samyang, Pentax/M42/vintage incl. Sigma/Tamron/Zeiss/Voigtländer/
 * Takumar/Helios). Used when the user types a custom lens — an autocomplete
 * dropdown fires at the 3rd typed character.
 *
 * Since 2026-08-27 this corpus is MERGED with the bundled Lensfun lens database
 * (the one behind Lens Correction), so any lens you can correct with is also a
 * lens you can name in the watermark — the two used to be unrelated databases.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.content.Context

object LensDatabase {

    @Volatile private var cache: List<String>? = null
    @Volatile private var lensfunCache: List<String>? = null

    /**
     * Every lens registered in the bundled **Lensfun** database (the same corpus
     * the Lens Correction picker uses — ~1500 entries incl. our locally added
     * profiles). Merged into [all] so a lens you can correct with is also a lens
     * you can name in the watermark; previously the two databases were unrelated,
     * so e.g. "Canon EF 28-105mm f/3.5-4.5 II USM" existed for correction but
     * could not be autocompleted here.
     *
     * Cached process-wide. Both underlying calls are themselves cached
     * (materialisation is marker-guarded; the native parse is memoised per dir),
     * but the FIRST call can touch disk — [search] runs off the main thread.
     */
    private fun lensfunNames(ctx: Context): List<String> {
        lensfunCache?.let { return it }
        val list = runCatching {
            val dir = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                .LensfunDatabase.ensureMaterialized(ctx.applicationContext)
                ?: return@runCatching emptyList()
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                .RawV3Engine.lensfunLenses(dir)
                .map { it.model.trim() }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        lensfunCache = list
        return list
    }

    /**
     * Load + cache the asset once, merged with the Lensfun lens names.
     * Distinct, blank-stripped, order preserved (asset names first — they are
     * the marketing spellings; Lensfun names follow as the technical corpus).
     */
    fun all(ctx: Context): List<String> {
        cache?.let { return it }
        val asset = runCatching {
            ctx.applicationContext.assets.open(ASSET).bufferedReader().useLines { seq ->
                seq.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .distinct().toList()
            }
        }.getOrDefault(emptyList())
        val list = (asset + lensfunNames(ctx)).distinct()
        cache = list
        return list
    }

    /**
     * Autocomplete matches for [query]. Empty until the query is >= 3 chars (the
     * "magic on 3rd character" rule). Word-prefix and full-prefix matches rank
     * above mid-string contains; capped at [limit]. Case/space-insensitive.
     */
    fun search(ctx: Context, query: String, limit: Int = 12): List<String> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val ql = q.lowercase()
        val all = all(ctx)
        val prefix = ArrayList<String>()
        val wordStart = ArrayList<String>()
        val contains = ArrayList<String>()
        for (name in all) {
            val nl = name.lowercase()
            when {
                nl.startsWith(ql) -> prefix.add(name)
                // token boundary: query starts a word within the name
                nl.contains(" $ql") -> wordStart.add(name)
                nl.contains(ql) -> contains.add(name)
            }
            if (prefix.size >= limit) break
        }
        return (prefix + wordStart + contains).distinct().take(limit)
    }

    /**
     * Strip a leading brand word from [lens] when it duplicates the camera [make],
     * so the baked watermark never reads "Canon EOS 6D | Canon EF 50mm…". Only the
     * exact leading brand token is removed; a different lens brand (e.g. Sigma on a
     * Canon body) is kept. Case-insensitive; no-op when [make] is blank.
     */
    fun stripRedundantBrand(lens: String, make: String): String {
        val brand = make.trim().substringBefore(' ').trim()   // "Canon" from "Canon EOS 6D"
        if (brand.isEmpty()) return lens.trim()
        val l = lens.trim()
        return if (l.length > brand.length + 1 && l.regionMatches(0, brand, 0, brand.length, ignoreCase = true) &&
            l[brand.length] == ' ') {
            l.substring(brand.length + 1).trim()
        } else l
    }

    private const val ASSET = "lens_database.txt"
}
