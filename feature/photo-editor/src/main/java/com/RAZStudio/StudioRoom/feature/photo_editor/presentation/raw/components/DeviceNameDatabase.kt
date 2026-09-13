/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Maps an Android device MODEL CODE (as reported in EXIF, e.g. "Infinix X6873"
 * or "SM-G991B") to its consumer MARKETING NAME ("GT 30 Pro", "Galaxy S21") for
 * the watermark EXIF panel. Data: assets/device_names.txt, built from Google's
 * official Play Console supported_devices.csv (Retail Branding, Marketing Name,
 * Device, Model). One "brand<TAB>model<TAB>marketing" row per line; '#' = comment.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.content.Context

object DeviceNameDatabase {

    private const val ASSET = "device_names.txt"

    private data class Entry(val brandNorm: String, val marketing: String)

    // normalized model code -> candidate marketing names (multiple brands may
    // share a code; the make disambiguates at lookup time).
    @Volatile private var map: Map<String, List<Entry>>? = null

    private fun norm(s: String): String =
        s.trim().uppercase().replace(Regex("\\s+"), " ")

    private fun load(context: Context): Map<String, List<Entry>> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val built = HashMap<String, MutableList<Entry>>(32768)
            runCatching {
                context.applicationContext.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line[0] == '#') continue
                        val p = line.split('\t')
                        if (p.size < 3) continue
                        val brand = p[0].trim()
                        val model = p[1].trim()
                        val marketing = p[2].trim()
                        if (model.isEmpty() || marketing.isEmpty()) continue
                        val key = norm(model)
                        val list = built.getOrPut(key) { ArrayList(1) }
                        // de-dup identical (brand, marketing) for the same code
                        if (list.none { it.brandNorm == norm(brand) && it.marketing == marketing }) {
                            list.add(Entry(norm(brand), marketing))
                        }
                    }
                }
            }
            map = built
            return built
        }
    }

    private fun brandMatches(makeNorm: String, brandNorm: String): Boolean {
        if (makeNorm.isBlank()) return true
        // Camera EXIF "make" varies ("INFINIX", "Infinix Mobility Limited",
        // "samsung"). Match if either contains the other's leading token.
        val mk = makeNorm.substringBefore(' ')
        val bd = brandNorm.substringBefore(' ')
        return brandNorm.contains(mk) || makeNorm.contains(bd) || mk == bd
    }

    /**
     * The marketing name for a device given its EXIF [make] + [model], or null if
     * there's no mapping (caller keeps the raw model). Tries the model verbatim,
     * then with the make prefixed, then with the make prefix stripped — covering
     * EXIF that reports "X6873", "Infinix X6873", or "INFINIX X6873".
     */
    fun marketingName(context: Context, make: String, model: String): String? {
        val m = norm(model)
        if (m.isBlank()) return null
        val db = load(context)
        val mk = norm(make)

        fun pick(key: String): String? {
            val list = db[key] ?: return null
            list.firstOrNull { brandMatches(mk, it.brandNorm) }?.let { return it.marketing }
            // No brand match but a single unambiguous entry → use it.
            return if (list.size == 1 && mk.isBlank()) list[0].marketing else null
        }

        pick(m)?.let { return it }
        if (mk.isNotBlank()) {
            val mkTok = mk.substringBefore(' ')
            if (!m.startsWith("$mkTok ")) pick("$mkTok $m")?.let { return it }
            if (m.startsWith("$mkTok ")) pick(m.removePrefix("$mkTok ").trim())?.let { return it }
        }
        return null
    }
}
