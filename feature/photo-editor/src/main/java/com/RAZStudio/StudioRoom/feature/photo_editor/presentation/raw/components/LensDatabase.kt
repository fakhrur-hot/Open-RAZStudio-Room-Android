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
    /**
     * Brands present in the corpus. The nine common lens makers stay in that
     * order; every other brand follows, A–Z.
     */
    fun brands(ctx: Context): List<String> {
        val display = LinkedHashMap<String, String>()
        for (name in all(ctx)) {
            val brand = name.substringBefore(' ').trim()
            if (brand.isEmpty()) continue
            display.putIfAbsent(brand.lowercase(), brand)
        }
        val out = ArrayList<String>()
        for (key in PRIORITY_BRANDS) {
            display[key]?.let { out.add(it) }
        }
        val rest = display.keys
            .filter { it !in PRIORITY_BRANDS }
            .sorted()
            .map { display[it]!! }
        out.addAll(rest)
        return out
    }

    fun search(ctx: Context, query: String, limit: Int = 12): List<String> =
        search(ctx, query, brand = null, limit = limit)

    /**
     * Suggestions for a typed phrase. [brand] limits the list to names that
     * start with that maker. Focal lengths (`50mm`, `24-70mm`) and apertures
     * (`f/1.8`, `1.8`) are matched as their own tokens. "nifty fifty" is
     * treated as 50mm f/1.8.
     */
    fun search(ctx: Context, query: String, brand: String?, limit: Int = 12): List<String> {
        val parsed = parseQuery(query)
        val brandKey = brand?.trim()?.lowercase().orEmpty()
        if (parsed.needles.isEmpty() && brandKey.isEmpty()) return emptyList()
        if (parsed.needles.isEmpty() && parsed.raw.length < 1) return emptyList()
        val all = all(ctx)
        val prefix = ArrayList<String>()
        val contains = ArrayList<String>()
        for (name in all) {
            val nl = name.lowercase()
            if (brandKey.isNotEmpty() && !nl.startsWith(brandKey)) continue
            if (parsed.needles.any { !nl.contains(it) }) continue
            if (parsed.raw.isNotEmpty() && nl.startsWith(parsed.raw)) prefix.add(name)
            else contains.add(name)
            if (prefix.size >= limit) break
        }
        return (prefix + contains).distinct().take(limit)
    }

    private data class ParsedQuery(val raw: String, val needles: List<String>)

    private fun parseQuery(query: String): ParsedQuery {
        var text = query.trim().lowercase()
        for ((from, to) in SYNONYMS) text = text.replace(from, to)
        val needles = ArrayList<String>()
        val focal = Regex("""(\d{1,3}(?:\.\d+)?(?:-\d{1,3}(?:\.\d+)?)?)\s*mm""")
        focal.findAll(text).forEach { needles.add(it.groupValues[1] + "mm") }
        text = focal.replace(text, " ")
        val aperture = Regex("""f\s*/?\s*(\d(?:\.\d+)?)|(?<![\d.])(\d\.\d)(?![\d.])""")
        aperture.findAll(text).forEach { m ->
            val n = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (n.isNotEmpty()) needles.add(n)
        }
        text = aperture.replace(text, " ")
        text.split(Regex("""\s+"""))
            .filter { it.length >= 2 && it !in NOISE }
            .forEach { needles.add(it) }
        val raw = needles.joinToString(" ")
        return ParsedQuery(raw, needles.distinct())
    }

    private val PRIORITY_BRANDS = listOf(
        "canon", "sony", "nikon", "sigma", "tamron",
        "tokina", "viltrox", "ttartisan", "7artisans",
    )
    private val SYNONYMS = listOf("nifty fifty" to "50mm f/1.8")
    private val NOISE = setOf("fast", "prime", "lens", "camera", "the", "and")

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
