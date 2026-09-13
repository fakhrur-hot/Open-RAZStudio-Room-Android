/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Bundled factory presets — shipped in `assets/presets/` and seeded into the
 * user's preset list exactly once each (see
 * [RawPresetsStorage.seedDefaultsIfNeeded]). The user is free to delete any of
 * them; a deleted preset is never re-seeded thanks to per-asset marker files.
 *
 * Every preset is an AUTHOR-SAVED preset captured verbatim from the authoring
 * device as a [RawActionSerializer] v2 blob — NOT reconstructed from
 * hand-picked values. `assets/presets/index.txt` is the manifest: one
 * `displayName|assetFile|bitDepth` line per preset, in seeding order.
 *
 * Two portability fix-ups happen at load time (unchanged from the original
 * single-preset "RAZDream" mechanism this generalises):
 *   1. Any stored absolute `.../files/lut_cache/<name>.cube` path is rewritten
 *      to THIS install's filesDir (the package segment differs between the
 *      debug and release build types, and between the author's device and a
 *      fresh install).
 *   2. Each referenced bundled LUT is materialized into `<filesDir>/lut_cache/`
 *      so the rewritten path actually resolves. Every LUT referenced by a
 *      bundled preset MUST therefore exist under some `assets/luts/<category>/`.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.content.Context
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth
import java.io.File

object BundledPresets {

    data class Entry(
        val name: String,
        val assetFile: String,
        val bitDepth: WorkspaceBitDepth,
    )

    private const val DIR = "presets"
    private const val MANIFEST = "$DIR/index.txt"

    /** The one preset that predates the manifest — its historical seed marker
     *  (`.defaults_seeded`) must keep counting as "already seeded". */
    const val LEGACY_SEEDED_ASSET = "razdream.xml"

    // Matches an absolute app-private lut_cache dir, e.g.
    //   /data/user/0/com.RAZStudio.StudioRoom.debug/files/lut_cache/
    //   /data/data/com.RAZStudio.StudioRoom/files/lut_cache/
    private val LUT_CACHE_DIR = Regex("""/data/(?:user/0|data)/[^/<>"]+/files/lut_cache/""")
    // Bundled presets may reference .cube or binary .smcube LUTs.
    private val LUT_CACHE_NAME = Regex("""/lut_cache/([^/<>"]+\.(?:sm)?cube)""")

    /** Parse the bundled manifest; empty when missing/corrupt. */
    fun list(context: Context): List<Entry> = runCatching {
        context.assets.open(MANIFEST).bufferedReader().useLines { lines ->
            lines.filter { it.contains('|') }.map { line ->
                val parts = line.split('|')
                Entry(
                    name = parts.getOrNull(0).orEmpty().trim(),
                    assetFile = parts.getOrNull(1).orEmpty().trim(),
                    bitDepth = parts.getOrNull(2)?.trim()
                        ?.let { tag -> WorkspaceBitDepth.entries.firstOrNull { it.name == tag } }
                        ?: WorkspaceBitDepth.BIT_16,
                )
            }.filter { it.name.isNotEmpty() && it.assetFile.isNotEmpty() }.toList()
        }
    }.getOrDefault(emptyList())

    /**
     * Load one bundled preset's actions for THIS install, or null if the asset
     * is missing/unreadable. Rewrites device-specific LUT cache paths to this
     * install and materializes the referenced bundled LUTs into lut_cache.
     */
    fun load(context: Context, assetFile: String): List<RawAction>? {
        val raw = runCatching {
            context.assets.open("$DIR/$assetFile").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null

        val cacheDir = File(context.filesDir, "lut_cache").absolutePath.replace('\\', '/')
        val rewritten = LUT_CACHE_DIR.replace(raw, "$cacheDir/")

        materializeLuts(context, rewritten)

        return runCatching {
            RawActionSerializer.deserialize(rewritten).takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Copy every bundled `luts/<category>/<name>.{cube,smcube}` referenced by
     *  [xml] into `<filesDir>/lut_cache/<name>` (skips ones already present). */
    private fun materializeLuts(context: Context, xml: String) {
        val names = LUT_CACHE_NAME.findAll(xml).map { it.groupValues[1] }.toSet()
        if (names.isEmpty()) return
        val am = context.assets
        val categories = runCatching { am.list("luts")?.toList() }.getOrNull().orEmpty()
        for (name in names) {
            val dest = File(context.filesDir, "lut_cache/$name")
            if (dest.exists()) continue
            val assetPath = categories.firstNotNullOfOrNull { cat ->
                val p = "luts/$cat/$name"
                if (runCatching { am.open(p).close(); true }.getOrDefault(false)) p else null
            } ?: continue
            dest.parentFile?.mkdirs()
            runCatching {
                am.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
