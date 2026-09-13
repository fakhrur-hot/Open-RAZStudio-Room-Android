/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — Adobe XMP preset discovery (M5.5).
 *
 *  Mirrors RawV3LutStore but for `.xmp` files. Seeds the directory from
 *  `assets/xmp_presets/` on first run per Plan.md §10.6.
 *
 *      context.getExternalFilesDir("presets/xmp/")
 *      → /storage/emulated/0/Android/data/<pkg>/files/presets/xmp/
 *
 *  The General adjustment tab (M5.5 UI, lands after the C++/shader path is
 *  verified) calls list() to populate its chip row.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object RawV3XmpPresetStore {

    private const val TAG = "RawV3.XmpStore"
    private const val ASSETS_DIR   = "xmp_presets"        // assets/xmp_presets/*.xmp
    private const val EXTERNAL_DIR = "presets/xmp"        // externalFilesDir/presets/xmp

    data class XmpEntry(
        val name: String,        // file name without extension
        val file: File,
    )

    /** Discovery dir, created (and seeded on first run) if needed. */
    fun discoveryDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), EXTERNAL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
            seedFromAssets(context, dir)
        }
        return dir
    }

    fun list(context: Context): List<XmpEntry> {
        val dir = discoveryDir(context)
        return dir.listFiles { f -> f.isFile && f.extension.equals("xmp", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.map { XmpEntry(it.nameWithoutExtension, it) }
            ?: emptyList()
    }

    /**
     * Write a [ShaderParams] state as a user XMP preset into the discovery
     * directory. M10 surface — called from the smoke activity's "Save as
     * Preset" button.
     *
     * Output is a minimal Adobe-compatible CRS sidecar carrying only the
     * tags v3's XMP parser knows about. Sliders in the [-1, +1] domain are
     * scaled back to the [-100, +100] integer range Adobe writes
     * (matches the values [adobe_xmp_parser.cpp] expects on parse).
     *
     * Returns the written file on success, or null on I/O failure.
     */
    /**
     * Persist a [ShaderParams] state as a user XMP preset.
     *
     * Optional vendor LUT round-trip: when [lutPath] is non-null we also
     * emit `crs:RAZLutPath` (basename of the .cube file) and
     * `crs:RAZLutIntensity` (0..100 integer percent) so a batch run can
     * find the LUT inside `externalFilesDir/presets/lut/` and re-upload
     * it at the saved strength. The intensity reads back via the slider
     * in the smoke editor too; users see "85" if they originally saved
     * at 0.85.
     */
    fun saveUserPreset(
        context: Context,
        name: String,
        params: ShaderParams,
        lutPath: String? = null,
    ): File? {
        // Sanitise the preset name into a filename — no slashes, no extension.
        val safe = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "preset_${System.currentTimeMillis()}" }
            .removeSuffix(".xmp")
        val dst = File(discoveryDir(context), "$safe.xmp")
        return runCatching {
            dst.writeText(buildCrsXmp(params, lutPath))
            Log.i(TAG, "saved preset $safe → ${dst.absolutePath} (${dst.length()} bytes) " +
                "lutPath=${lutPath ?: "(none)"}")
            dst
        }.onFailure { Log.w(TAG, "saveUserPreset failed for $name", it) }.getOrNull()
    }

    /**
     * Build a tiny `<x:xmpmeta>` document carrying the Camera Raw 2012
     * tag set we round-trip. Exposure / contrast / whites / blacks /
     * highlights / shadows are in the same scale Adobe uses (-1..+1
     * for Exposure2012, -100..+100 for everything else), so we
     * scale the shader's `[-1, +1]` saturation/etc by 100. HSL is
     * scaled by 100 too. The Adobe XMP parser in v3 reads back the
     * same tags, so this round-trips cleanly through the engine.
     */
    /**
     * Public-API wrapper so the production sidecar writer can reuse
     * the same CRS XMP serialisation. M12.1b only writes a no-LUT
     * sidecar (the LUT path is per-session, not part of the source-
     * side sidecar contract); call this for any current ShaderParams.
     */
    fun buildPublicCrsXmp(p: ShaderParams): String = buildCrsXmp(p, lutPath = null)

    private fun buildCrsXmp(p: ShaderParams, lutPath: String? = null): String {
        // Convert shader-domain values (mostly [-1, +1]) back to the
        // [-100, +100] integer ranges Adobe writes. Exposure stays in EV
        // (already what the shader expects). HSL packs as 6×3 groups:
        // [hue, sat, lum] × [red, orange, yellow, green, aqua/cyan, blue]
        // — we only carry the 18 v3 retains.
        fun pct(v: Float): Int = (v * 100f).toInt().coerceIn(-100, 100)
        val sb = StringBuilder(2048)
        sb.append("<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>\n")
        sb.append("<x:xmpmeta xmlns:x='adobe:ns:meta/'>\n")
        sb.append(" <rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>\n")
        sb.append("  <rdf:Description rdf:about=''\n")
        sb.append("    xmlns:crs='http://ns.adobe.com/camera-raw-settings/1.0/'\n")
        sb.append("    crs:Exposure2012='").append("%+.2f".format(p.exposure)).append("'\n")
        sb.append("    crs:Contrast2012='").append(pct(p.contrast)).append("'\n")
        sb.append("    crs:Highlights2012='").append(pct(p.highlights)).append("'\n")
        sb.append("    crs:Shadows2012='").append(pct(p.shadows)).append("'\n")
        sb.append("    crs:Whites2012='").append(pct(p.whites)).append("'\n")
        sb.append("    crs:Blacks2012='").append(pct(p.blacks)).append("'\n")
        sb.append("    crs:Saturation='").append(pct(p.saturation)).append("'\n")
        sb.append("    crs:Vibrance='").append(pct(p.vibrance)).append("'\n")
        sb.append("    crs:Temperature='").append(pct(p.whiteBalance)).append("'\n")
        sb.append("    crs:Tint='").append(pct(p.tint)).append("'\n")
        // Vendor extension — round-trips the LUT pick + intensity so
        // batch can re-upload the right .cube and dial the same strength.
        // Adobe parsers ignore tags they don't recognise; v3's native
        // parser only reads the standard CRS set; smoke + batch read
        // these tags via the Kotlin XMP scanner below.
        if (lutPath != null) {
            val base = lutPath.substringAfterLast('/').substringAfterLast('\\')
            sb.append("    crs:RAZLutPath='")
                .append(base.replace("'", "&apos;"))
                .append("'\n")
            sb.append("    crs:RAZLutIntensity='")
                .append(((p.lutIntensity * 100f).toInt().coerceIn(0, 100)))
                .append("'/>\n")
        } else {
            // Close the empty Description element.
            sb.deleteCharAt(sb.length - 1)
            sb.append("/>\n")
        }
        sb.append(" </rdf:RDF>\n")
        sb.append("</x:xmpmeta>\n")
        sb.append("<?xpacket end='w'?>\n")
        return sb.toString()
    }

    /**
     * Vendor LUT round-trip info parsed from an XMP file's
     * `crs:RAZLutPath` / `crs:RAZLutIntensity` attributes. v3 writes these
     * from [saveUserPreset]; readers (smoke editor's Apply Preset, batch
     * coordinator) call [readLutInfo] to recover the .cube file + the
     * saved intensity. The standard CRS tags are handled separately by
     * the native parser ([RawV3Engine.parseAdobeXmp]).
     */
    data class LutInfo(
        /** Basename of the .cube file. Resolved against the LUT store's
         *  discovery dir by the caller. */
        val cubeBaseName: String,
        /** Mix factor in [0, 1]. 1.0 = full LUT effect, 0.0 = identity. */
        val intensity: Float,
    )

    /**
     * Scan an XMP file for the vendor LUT round-trip tags. Returns null
     * when the file doesn't contain `crs:RAZLutPath` — every Adobe XMP
     * ever made falls in that bucket, so this is the common case.
     */
    fun readLutInfo(xmpFile: File): LutInfo? {
        val text = runCatching { xmpFile.readText() }.getOrNull() ?: return null
        val pathRegex = Regex("""crs:RAZLutPath\s*=\s*['\"]([^'\"]+)['\"]""")
        val pctRegex  = Regex("""crs:RAZLutIntensity\s*=\s*['\"](\d+)['\"]""")
        val pathMatch = pathRegex.find(text) ?: return null
        val pctMatch  = pctRegex.find(text)
        val cubeName  = pathMatch.groupValues[1].replace("&apos;", "'")
        val pct       = pctMatch?.groupValues?.get(1)?.toIntOrNull() ?: 100
        val intensity = (pct / 100f).coerceIn(0f, 1f)
        return LutInfo(cubeBaseName = cubeName, intensity = intensity)
    }

    private fun seedFromAssets(context: Context, dst: File) {
        val am = context.assets
        val files = runCatching { am.list(ASSETS_DIR) }.getOrNull().orEmpty()
        for (name in files) {
            if (!name.endsWith(".xmp", ignoreCase = true)) continue
            val out = File(dst, name)
            runCatching {
                am.open("$ASSETS_DIR/$name").use { ins ->
                    FileOutputStream(out).use { o -> ins.copyTo(o) }
                }
                Log.i(TAG, "seeded $name (${out.length()} bytes)")
            }.onFailure { Log.w(TAG, "seed $name failed: ${it.message}") }
        }
    }
}
