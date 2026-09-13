/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Per-Lens Chromatic Aberration Profile.
 *
 * Looks up chromatic aberration correction strength and fringe reduction
 * values from the extended lens_tune.tbl (9-column format). Returns null
 * for unknown lenses, lens_id 0 (neutral), and lenses without CA data
 * (7-column legacy rows that default to 0,0).
 *
 * Slot mapping:
 *   [352] aberStrength   — CA correction strength [0..100]
 *   [353] aberFringeReduce — fringe suppression strength [0..100]
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

/**
 * Per-lens chromatic aberration profile lookup.
 *
 * Extends [MLLensTuneTable] with CA-specific access. The underlying table
 * must be loaded via [MLLensTuneTable.ensureLoaded] before calling [forLensId].
 *
 * Returns null when:
 * - lensId is 0 (neutral/fallback — no correction desired)
 * - lensId is not found in the table
 * - The entry has both caStrength and fringeReduce equal to 0 (no CA data,
 *   typically from 7-column legacy rows)
 */
object MLChromaticAberration {

    /**
     * Look up chromatic aberration correction parameters for the given lens.
     *
     * @param lensId Canon lens_id from CR2 EXIF metadata.
     * @return [LensCaEntry] with clamped [0..100] values, or null if the lens
     *   has no CA profile (unknown, neutral, or zero-valued legacy entry).
     */
    fun forLensId(lensId: Int): LensCaEntry? {
        // Lens ID 0 is the neutral/fallback — never apply CA correction
        if (lensId == 0) return null

        val entry = MLLensTuneTable.findEntry(lensId) ?: return null

        // Skip if this is a legacy 7-column row (both CA values are 0)
        // or if the entry is the neutral lens_id 0 fallback
        if (entry.lensId == 0) return null
        if (entry.caStrength == 0f && entry.fringeReduce == 0f) return null

        return LensCaEntry(
            lensId = lensId,
            caStrength = entry.caStrength.coerceIn(0f, 100f),
            fringeReduce = entry.fringeReduce.coerceIn(0f, 100f),
        )
    }
}
