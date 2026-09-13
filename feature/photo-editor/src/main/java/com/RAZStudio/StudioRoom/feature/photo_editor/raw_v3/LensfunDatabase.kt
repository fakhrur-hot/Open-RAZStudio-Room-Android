/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Materialises the Lensfun database (XML files placed in assets/lensfun_db —
 * a snapshot of lensfun master's data/db) into filesDir so the native expat
 * loader can read it from a plain directory path.
 *
 * FOSS build note:
 * The Lensfun database is NOT bundled in this open-source repository. To
 * enable lens correction, a builder must supply their own copy: drop the
 * lensfun database XML files (data/db, the dot-xml files) into
 *   feature/photo-editor/src/main/assets/lensfun_db/
 * before building (see that folder's README). When the directory is absent
 * or empty, [ensureMaterialized] returns null and lens correction is simply
 * disabled — the app still builds and runs offline.
 *
 * Copy happens once per install/update: a marker file records the asset
 * count, so shipping an updated database in a new APK re-materialises
 * automatically. Same pattern as the bundled-LUT materialisation.
 */
object LensfunDatabase {

    private const val TAG = "LensfunDatabase"
    private const val ASSET_DIR = "lensfun_db"
    /** Bump when bundled XML content changes without a file-count change. */
    private const val DB_VERSION = 7  // v7: master a5e2caeb (2026-09-05) re-sync + PR#2858/#2004 vignetting patches + zz-community-extras.xml

    /**
     * Returns the absolute path of the on-disk database directory, copying
     * from assets if needed. Null when the assets are missing/unreadable
     * (callers treat null as "lens correction unavailable").
     */
    @Synchronized
    fun ensureMaterialized(context: Context): String? = runCatching {
        val assetNames = context.assets.list(ASSET_DIR)
            ?.filter { it.endsWith(".xml") }
            .orEmpty()
        if (assetNames.isEmpty()) return@runCatching null

        val dir = File(context.filesDir, ASSET_DIR)
        // DB_VERSION guards in-place edits to bundled XMLs (upstream-PR patches)
        // that don't change the file count; bump it whenever entries change.
        val marker = File(dir, ".materialized_v${DB_VERSION}_${assetNames.size}")
        if (marker.exists()) return@runCatching dir.absolutePath

        dir.mkdirs()
        // Clear stale files from a previous database version.
        dir.listFiles()?.forEach { it.delete() }
        var copied = 0
        for (name in assetNames) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { out -> input.copyTo(out) }
            }
            copied++
        }
        marker.createNewFile()
        Log.i(TAG, "Materialized $copied lensfun XML files → ${dir.absolutePath}")
        dir.absolutePath
    }.onFailure {
        Log.e(TAG, "ensureMaterialized failed", it)
    }.getOrNull()

    /** Human label for a sensor crop factor (shown as "Sensor format"). */
    fun sensorFormatLabel(cropFactor: Float): String = when {
        cropFactor <= 0f     -> "Unknown"
        cropFactor < 0.85f   -> "Medium Format (${fmt(cropFactor)}×)"
        cropFactor < 1.15f   -> "Full Frame (${fmt(cropFactor)}×)"
        cropFactor < 1.85f   -> "APS-C (${fmt(cropFactor)}×)"
        cropFactor < 2.4f    -> "Micro 4/3 (${fmt(cropFactor)}×)"
        cropFactor < 4.0f    -> "1\" / small sensor (${fmt(cropFactor)}×)"
        else                 -> "Compact (${fmt(cropFactor)}×)"
    }

    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}" else "%.1f".format(v)

    /**
     * Canonical brand for a raw Lensfun `<maker>` string. The DB spells the
     * same manufacturer many ways ("Nikon" vs "Nikon Corporation", four
     * Olympus variants, "KMZ" = Zenit/Helios…); the brand dropdown groups by
     * this canonical name.
     */
    fun canonicalBrand(makerRaw: String): String {
        val m = makerRaw.trim()
        val lower = m.lowercase()
        return when {
            lower.startsWith("nikon")           -> "Nikon"
            lower.startsWith("olympus")         -> "Olympus"
            lower.startsWith("om ")             -> "OM System"
            lower.startsWith("om-")             -> "OM System"
            lower.startsWith("leica")           -> "Leica"
            lower.contains("zeiss")             -> "Zeiss"
            lower.startsWith("pentax")          -> "Pentax"
            lower.startsWith("ricoh")           -> "Ricoh"
            lower.startsWith("konica minolta")  -> "Minolta"
            lower.startsWith("minolta")         -> "Minolta"
            lower.startsWith("kmz")             -> "KMZ (Zenit)"
            lower.startsWith("voigtl")          -> "Voigtländer"
            lower.startsWith("panasonic")       -> "Panasonic"
            lower.startsWith("lumix")           -> "Panasonic"
            lower.startsWith("fuji")            -> "Fujifilm"
            lower.startsWith("hasselblad")      -> "Hasselblad"
            lower.startsWith("samsung")         -> "Samsung"
            lower.startsWith("sony")            -> "Sony"
            lower.startsWith("canon")           -> "Canon"
            lower.startsWith("sigma")           -> "Sigma"
            lower.startsWith("tamron")          -> "Tamron"
            lower.startsWith("tokina")          -> "Tokina"
            lower.startsWith("samyang")         -> "Samyang"
            lower.startsWith("rokinon")         -> "Samyang"
            lower.startsWith("yashica")         -> "Yashica"
            lower.startsWith("contax")          -> "Contax"
            else -> m.split(' ').firstOrNull()?.replaceFirstChar { it.uppercase() } ?: m
        }
    }
}
